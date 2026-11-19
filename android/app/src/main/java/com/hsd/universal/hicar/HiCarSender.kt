package com.hsd.universal.hicar

import com.hsd.universal.ProjectionResources
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Phone half of DMSDP/HiSight. Every reverse socket is bound to the selected local network
 * and accepts only the already authenticated head unit's address. */
internal class HiCarSender(
    private val life: ProjectionResources,
    private val own: InetAddress,
    private val peer: InetAddress,
    private val identity: String,
    private val key: ByteArray,
    private val connect: (Int) -> Socket,
    private val onInput: (HiCarMedia.Input) -> Unit,
    private val failed: (Exception) -> Unit,
    bindAudio: (DatagramSocket) -> Unit = {},
) {
    data class Video(val width: Int, val height: Int, val fps: Int, val dpi: Int, val bitrate: Int)
    private val started = CompletableFuture<Video>()
    private val media = HiCarMedia(key)
    private var videoOutput: DataOutputStream? = null
    private val speakerSocket = DatagramSocket(null).also {
        life.own(it::close)
        bindAudio(it)
        it.bind(InetSocketAddress(own, 0))
    }
    @Volatile private var speakerPort = 0
    @Volatile var audioRate: Int? = null
        private set
    private var speakerId: String? = null
    private var displayStarted = false
    @Volatile private var audioParametersSequence: String? = null
    private var speakerSession = 1
    private lateinit var dmsdp: HiCarControl
    init {
        life.own { started.cancel(false) }
        life.own(media::close)
        life.own { key.fill(0) }
    }
    private fun worker(name: String, work: () -> Unit) = thread(name = "HiCar $name", isDaemon = true) {
        try { work() } catch (e: Exception) { started.completeExceptionally(e); if (!life.isClosed) failed(e) }
    }
    private fun server(): ServerSocket = ServerSocket().also {
        life.own(it::close)
        it.reuseAddress = true
        it.bind(InetSocketAddress(own, 0), 2)
        it.soTimeout = 30_000
    }
    private fun accept(server: ServerSocket): Socket {
        val socket = server.accept()
        if (socket.inetAddress != peer) { socket.close(); error("HiCar: reverse connection from another host") }
        life.own(socket::close)
        socket.tcpNoDelay = true
        socket.soTimeout = 30_000
        server.close()
        return socket
    }
    fun start(dataPort: Int): Video {
        worker("DMSDP") {
            val entry = connect(dataPort)
            val decoder = DmsdpWire.Decoder()
            entry.getOutputStream().write(DmsdpWire.pack(1, ByteArray(16)))
            val buffer = ByteArray(4096)
            var port = 0
            while (port == 0) {
                val n = entry.getInputStream().read(buffer); check(n > 0)
                for (packet in decoder.feed(buffer, n)) {
                    check(packet.module == 1 && packet.payload.size == 16)
                    port = ((packet.payload[1].toInt() and 255) shl 8) or (packet.payload[2].toInt() and 255)
                    check(port in 1..65535)
                }
            }
            val socket = connect(port)
            dmsdp = HiCarControl(socket, key, false).also { life.own(it::close) }
            val options = dmsdp.read()
            check(options.method == "OPTIONS" && options.headers["cryptover"] == "1" &&
                options.headers["cryptoalg"] == "aes128-ctr" && options.headers["cryptoability"] == "222") { "HiCar: DMSDP cipher unsupported" }
            val sequence = dmsdp.request("SET_PARAMETER", mapOf("CryptoVer" to "1", "CryptoSwitch" to "222", "CryptoAlg" to "aes128-ctr"))
            val response = dmsdp.read()
            check(response.success && response.cseq == sequence && response.headers["cryptoswitch"] == "222" &&
                response.headers["cryptoalg"] == "aes128-ctr") { "HiCar: DMSDP encryption not confirmed" }
            dmsdp.encrypted = true
            val device = JSONObject().put("deviceId", identity).put("deviceName", "HSD").put("deviceType", 3)
                .put("btName", "HSD").put("localIp", own.hostAddress).put("remoteIp", peer.hostAddress)
                .put("port", 0).put("businessId", 1).put("properties", JSONObject())
            dmsdp.send(HiCarControl.business(3, 101, device.toString().toByteArray()))
            worker("heartbeat") {
                while (!life.isClosed) { Thread.sleep(1000); if (!life.isClosed) dmsdp.request("GET_PARAMETER") }
            }
            while (!life.isClosed) process(dmsdp.read())
        }
        return started.get(65, TimeUnit.SECONDS)
    }
    private fun process(message: HiCarControl.Message) {
        if (message.module != null) {
            if (message.module != 3 || message.type != -1) return
            val cursor = NearbyWire.Cursor(message.body, 0)
            val command = cursor.uint()
            val data = HiCarJson.`object`(message.body.copyOfRange(cursor.p, message.body.size).toString(Charsets.UTF_8))
            when (command) {
                101 -> dmsdp.send(HiCarControl.business(3, 104, ByteBuffer.allocate(4).putInt(12).array())) // display + speaker only
                102 -> when (data.getInt("serviceType")) {
                    8 -> if (!displayStarted) {
                        displayStarted = true
                        val listener = server()
                        worker("HiSight") { video(accept(listener)) }
                        dmsdp.send(HiCarControl.business(5, 101, NearbyWire.uint(listener.localPort)))
                    }
                    4 -> if (speakerId == null) {
                        val id = data.getString("serviceId")
                        check(id.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
                        val rates = data.optJSONObject("properties")?.optString("2003").orEmpty().split(',')
                        check("48000" in rates || "44100" in rates) { "HiCar: unsupported speaker sample rate" }
                        audioRate = if ("48000" in rates) 48000 else 44100
                        speakerId = id
                        dmsdp.request("SET_PARAMETER", speakerHeaders() + ("Simple" to "1"), "msdp_trigger_method: OPTIONS\r\n")
                    }
                }
            }
            return
        }
        if (message.headers["serviceid"] == speakerId && speakerId != null) {
            check(message.headers["datasessionid"] == speakerSession.toString() && message.headers["servicetype"] == "3")
            when (message.method) {
                "SETUP" -> {
                    val transport = message.headers["transport"].orEmpty()
                    val offered = Regex("(?:^|;)client_port=([0-9]+)--1(?:;|$)").find(transport)?.groupValues?.get(1)?.toInt()
                    check(offered != null && offered in 1..65535 && message.headers["cryptoalg"] == "aes128-ctr" &&
                        message.headers["codec"] == "0" && message.headers["tcp"] == "0") { "HiCar: speaker transport unsupported" }
                    audioParametersSequence = "pending"
                    speakerPort = offered
                    dmsdp.reply(message, speakerHeaders() + mapOf("Transport" to "RTP/AVP/UDP;unicast;client_port=$offered--1;server_port=${speakerSocket.localPort}--1",
                        "CryptoVer" to "1", "CryptoAlg" to "aes128-ctr", "CryptoSwitch" to "222", "Simple" to "1", "Codec" to "0"))
                    audioParametersSequence = dmsdp.request("SET_PARAMETER", speakerHeaders() +
                        ("AudioParameter" to "SampleRate=$audioRate;Format=16;ChannelNum=2;Codec=0"))
                }
                "TEARDOWN" -> { speakerPort = 0; dmsdp.reply(message, speakerHeaders()) }
                "GET_PARAMETER" -> dmsdp.reply(message, speakerHeaders())
                "DMSDP/1.0" -> if (message.cseq == audioParametersSequence) {
                    check(message.success) { "HiCar: speaker format rejected" }
                    audioParametersSequence = null
                }
                else -> dmsdp.reply(message, speakerHeaders(), code = 405)
            }
        } else if (message.method == "GET_PARAMETER") dmsdp.reply(message)
        else if (message.method == "TEARDOWN") { dmsdp.reply(message); error("HiCar: магнитола завершила сеанс") }
    }
    private fun speakerHeaders() = mapOf("ServiceID" to checkNotNull(speakerId), "ServiceType" to "3", "DataSessionID" to speakerSession.toString())
    val audioReady get() = speakerPort != 0 && audioParametersSequence == null && audioRate != null && !life.isClosed
    fun audio(adts: ByteArray, ptsUs: Long) {
        if (!audioReady) return
        val packet = media.audio(adts, ptsUs)
        speakerSocket.send(DatagramPacket(packet, packet.size, peer, speakerPort))
    }

    private fun video(socket: Socket) {
        val control = HiCarControl(socket, key, true).also { life.own(it::close) }
        val announce = control.read()
        check(announce.method == "ANNOUNCE" && announce.parameters()["encrypt_description"]?.contains("encrypt_list=aes128ctr") == true)
        control.reply(announce)
        val seq = control.request("ANNOUNCE", body = "encrypt_description: encrypt_list=aes128ctr;version=1\r\n")
        val selected = control.read(); check(selected.success && selected.cseq == seq)
        control.encrypted = true
        val options = control.request("OPTIONS", mapOf("Require" to "hisight1.0"))
        var capabilities: String? = null
        var mode: Video? = null
        var allowInput = false
        val mediaServer = server()
        val videoReady = CompletableFuture<Socket>()
        life.own { videoReady.cancel(false) }
        var listenersStarted = false
        while (!life.isClosed) {
            val message = control.read()
            when (message.method) {
                "OPTIONS" -> control.reply(message, mapOf("Public" to "hisight1.0, GET_PARAMETER, SET_PARAMETER"))
                "RTSP/1.0" -> {
                    check(message.success) { "HiCar: HiSight request rejected" }
                    if (message.cseq == options) capabilities = control.request("GET_PARAMETER", body =
                        "his_version\r\nhis_video_formats\r\nhis_device_type\r\nhis_uibc_capability\r\n")
                    else if (message.cseq == capabilities) {
                        val parameters = message.parameters()
                        mode = parseVideo(parameters["his_video_formats"].orEmpty())
                        allowInput = parameters["his_uibc_capability"]?.contains("input_category_list=GENERIC") == true
                        val selectedFormat = "codecs 1;fps ${mode.fps};gop ${mode.fps};bitrate ${mode.bitrate};dpi ${mode.dpi};scr-w ${mode.width};scr-h ${mode.height};width ${mode.width};height ${mode.height}"
                        control.request("SET_PARAMETER", body = "his_video_formats: $selectedFormat\r\nhis_presentation_url: rtsp://${own.hostAddress}/hsd\r\n" +
                            (if (allowInput) "his_uibc_capability: input_category_list=GENERIC;generic_cap_list=left_point_down, left_point_up, left_move, left_key_down, left_key_up;\r\n" else "") +
                            "his_trigger_method: SETUP\r\n")
                    }
                }
                "SETUP" -> {
                    check(mode != null && !listenersStarted) { "HiCar: unexpected video setup" }
                    listenersStarted = true
                    worker("video connection") { videoReady.complete(accept(mediaServer).apply { sendBufferSize = 65536 }) }
                    val inputServer = if (allowInput) server() else null
                    if (inputServer != null) worker("input") { input(accept(inputServer), mode) }
                    val transport = "RTP/AVP/UDP;unicast;client_port=0;server_port=${mediaServer.localPort}" +
                        (inputServer?.let { ";remotectl_port=${it.localPort}" } ?: "")
                    control.reply(message, mapOf("Transport" to transport))
                }
                "PLAY" -> {
                    check(mode != null && listenersStarted && !started.isDone)
                    videoOutput = DataOutputStream(videoReady.get(15, TimeUnit.SECONDS).getOutputStream())
                    control.reply(message)
                    started.complete(mode)
                    worker("HiSight heartbeat") {
                        while (!life.isClosed) { Thread.sleep(5000); if (!life.isClosed) control.request("GET_PARAMETER") }
                    }
                }
                "GET_PARAMETER" -> control.reply(message)
                "TEARDOWN" -> { control.reply(message); error("HiCar: магнитола остановила проекцию") }
                "SET_PARAMETER" -> control.reply(message)
                else -> control.reply(message, code = 405)
            }
        }
    }
    private fun input(socket: Socket, mode: Video) {
        val input = DataInputStream(socket.getInputStream())
        socket.soTimeout = 0 // Idle touch is valid; lifecycle closes this socket on disconnect.
        while (!life.isClosed) {
            check(input.readUnsignedShort() == 0) { "HiCar: UIBC version" }
            val size = input.readUnsignedShort()
            check(size in 21..1503) { "HiCar: UIBC record limit" }
            val packet = ByteArray(size-4).also(input::readFully)
            onInput(media.input(packet, mode.width, mode.height))
        }
    }
    fun video(frame: ByteArray, ptsUs: Long): Boolean {
        val out = videoOutput ?: return false
        if (life.isClosed) return false
        media.video(frame, ptsUs) { packet -> out.writeInt(packet.size); out.write(packet) }
        out.flush()
        return true
    }
    companion object {
        fun parseVideo(value: String): Video {
            val fields = value.split(';').filter { it.isNotBlank() }.associate {
                val pair = it.trim().split(Regex("\\s+"), limit = 2); check(pair.size == 2); pair[0] to pair[1]
            }
            check(fields["codecs"]?.split(',')?.contains("1") == true) { "HiCar: H.264 not offered" }
            fun number(key: String, fallback: Int? = null) = fields[key]?.toIntOrNull() ?: fallback ?: error("HiCar: missing video $key")
            val width = number("width"); val height = number("height")
            check(width in 320..4096 && height in 240..2160 && width % 2 == 0 && height % 2 == 0)
            val fps = number("fps"); check(fps in 1..120)
            val dpi = number("dpi", 160); check(dpi in 80..640)
            val bitrate = number("bitrate", 2_000_000); check(bitrate in 100_000..40_000_000)
            return Video(width, height, minOf(60, fps), dpi, bitrate)
        }
    }
}
