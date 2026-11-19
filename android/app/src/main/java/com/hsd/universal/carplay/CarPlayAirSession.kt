package com.hsd.universal.carplay

import android.content.Context
import com.hsd.universal.AutoAudioEncoder
import com.hsd.universal.AppLog
import com.hsd.universal.ProjectionResources
import net.prototux.aare.transport.wireless.ProjectionNetworkAccess
import io.github.shaonialife.cardeck.airplay.*
import java.net.*
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A descriptor describes the accessory controls, and contains no user input. */
internal fun logHidDescriptor(descriptor: ByteArray) {
    AppLog.i("CarPlay HID descriptor (${descriptor.size} B): " +
        descriptor.take(512).joinToString("") { "%02x".format(it.toInt() and 255) }, false)
}

/** Plist encoders can represent uint16 ports as either unsigned or signed 16-bit integers. */
internal fun airPlayPort(value: Any?): Int = (value as? Number)?.toLong()
    ?.takeIf { it in -32768L..65535L }?.toInt()?.and(0xffff) ?: 0

internal class CarPlayAirSession(
    private val context: Context,
    private val ep: CarPlayEndpoint,
    private val life: ProjectionResources,
    private val event: (Map<*, *>) -> Unit,
    private val failed: (Exception) -> Unit,
    private val screenFocus: (Boolean) -> Unit = {},
) {
    private val networkAccess = ProjectionNetworkAccess(context)
    private val screenSocket = java.util.concurrent.atomic.AtomicReference<Socket?>()
    init { life.own { screenSocket.getAndSet(null)?.close() } }
    private var eventIap: CarPlayIapChannel? = null
    @Volatile private var screenController: CarPlayScreenController? = null
    val screenProjected get() = screenController?.projected == true
    fun requestCarMenu() { screenController?.carMenu() }
    fun sendVideo(payload: ByteArray, ptsUs: Long) = screenController?.send(payload, ptsUs) == true

    var audio: CarPlayAudio? = null
        private set
    var navigationAudio: CarPlayAudio? = null
        private set

    data class Display(
        val width: Int,
        val height: Int,
        val fps: Int,
        val hid: Map<String, ByteArray>,
    )

    private fun tcp(port: Int, ownedByScreen: Boolean = false): Socket {
        require(port in 1..65535)
        val s = networkAccess.socket(ep.network)
        try {
            if (ownedByScreen) {
                // A screen may be recreated many times in one journey. Retain only its live or
                // connecting socket, while session close can still interrupt an unfinished connect.
                screenSocket.getAndSet(s)?.close()
                if (life.isClosed) throw java.io.IOException("CarPlay session closed")
            } else life.own(s::close)
            s.tcpNoDelay = true
            s.soTimeout = 12_000
            networkAccess.checked { s.connect(InetSocketAddress(ep.host, port), 7000) }
            return s
        } catch (e: Exception) {
            if (ownedByScreen) screenSocket.compareAndSet(s, null)
            s.close()
            throw e
        }
    }

    private fun udp() =
        DatagramSocket(null).also {
            life.own(it::close)
            networkAccess.checked { ep.network.bindSocket(it) }
            it.bind(InetSocketAddress(0))
        }

    private fun pair(wire: AirPlayWire) =
        CarPlayReceiverPairing.authenticate(context, ep.peer, ep.publicKey, TlvTransport { path, body ->
            wire.request("POST", path, body, "application/pairing+tlv8")
        })

    fun start(): Display {
        val controlSocket = tcp(ep.port)
        val control = AirPlayWire(controlSocket, "RTSP (control)")
        val keys = pair(control)
        control.cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
        controlSocket.soTimeout = 0
        control.startResponseReader { error -> if (!life.isClosed) failed(error) }
        val time = TimingServer(log = { AppLog.i("CarPlay $it", false) }, socketFactory = ::udp)
        check(time.start())
        life.own(time::stop)
        val eventRequests = java.util.concurrent.atomic.AtomicInteger()
        val eventReplies = java.util.concurrent.atomic.AtomicInteger()
        life.own {
            AppLog.i("CarPlay channels: timing ${time.progressSummary()}; " +
                "events requests=${eventRequests.get()} replies=${eventReplies.get()}", false)
        }
        fun dict(method: String, path: String, body: Map<String, Any?>? = null): Map<*, *> {
            val result = control.request(method, path, body?.let { BinaryPlist.encode(it) } ?: byteArrayOf())
            return if (result.isEmpty()) emptyMap<Any, Any>()
            else BinaryPlist.parse(result) as Map<*, *>
        }
        val setup =
            dict(
                "SETUP",
                "/setup",
                mapOf(
                    "deviceID" to ep.localId,
                    "name" to "HSD",
                    "model" to android.os.Build.MODEL,
                    "sourceVersion" to "566.25.21",
                    "timingPort" to time.port.toLong(),
                    "sessionUUID" to UUID.randomUUID().toString().uppercase(),
                    "sessionCorrelationUUID" to UUID.randomUUID().toString().uppercase(),
                    "features" to listOf("uiContext", "h.264Level5.1"),
                    "keepAliveLowPower" to true,
                    "statsCollectionEnabled" to false,
                ),
            )
        val keepPort = airPlayPort(setup["keepAlivePort"])
        // The receiver starts its idle watchdog during setup. Keep its UDP channel independent
        // of RTSP response times and blocked writes to the screen stream.
        val udpSent = java.util.concurrent.atomic.AtomicInteger()
        if (keepPort != 0) {
            val socket = udp()
            val payload = ByteArray(4) // AirPlay idle keepalive, version 0, active session.
            val packet = DatagramPacket(payload, payload.size, ep.host, keepPort)
            socket.send(packet)
            udpSent.incrementAndGet()
            val timer = Executors.newSingleThreadScheduledExecutor()
            life.own { timer.shutdownNow() }
            timer.scheduleWithFixedDelay({
                try { socket.send(packet); udpSent.incrementAndGet() }
                catch (e: Exception) { if (!life.isClosed) failed(e) }
            }, 1, 1, TimeUnit.SECONDS)
        }
        val info = dict("GET", "/info")
        val screenStats = info["keepAliveSendStatsAsBody"] == true || (info["keepAliveSendStatsAsBody"] as? Number)?.toInt() == 1
        AppLog.i("CarPlay keepalive: UDP=${keepPort != 0}, screenStats=$screenStats", false)
        val display =
            (info["displays"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull()
                ?: error("Магнитола не объявила дисплей CarPlay")
        val width = (display["widthPixels"] as Number).toInt()
        val height = (display["heightPixels"] as Number).toInt()
        val fps = (display["maxFPS"] as? Number)?.toInt()?.coerceIn(1, 60) ?: 30
        val uuid =
            display["uuid"]
                ?: display["UUID"]
                ?: display["displayUUID"]
                ?: error("Не указан идентификатор дисплея")
        val hid =
            (info["hidDevices"] as? List<*>)
                .orEmpty()
                .filterIsInstance<Map<*, *>>()
                .mapNotNull {
                    val desc = it["hidDescriptor"] as? ByteArray ?: return@mapNotNull null
                    (it["uuid"] ?: it["UUID"])?.toString()?.let { id -> id to desc }
                }
                .toMap()
        hid.values.forEach { logHidDescriptor(it) }
        fun stream(desc: Map<String, Any?>): Map<*, *> {
            val response = dict("SETUP", "/", mapOf("streams" to listOf(desc)))
            return (response["streams"] as List<*>).filterIsInstance<Map<*, *>>().first {
                it["type"] == desc["type"]
            }
        }
        fun nextId() = SecureRandom().nextLong().ushr(1).coerceAtLeast(1)
        val screens = CarPlayScreenController(
            setup = {
                val id = nextId()
                val description = stream(mapOf("type" to 110L, "streamConnectionID" to id,
                    "uuid" to uuid, "latencyMs" to 25L))
                val socket = tcp(airPlayPort(description["dataPort"]), ownedByScreen = true)
                val sender = CarPlayVideo(socket.getOutputStream(),
                    AirPlayStreamKeys.chachaStreamKey(keys.sharedSecret, id), width, height, screenStats)
                object : CarPlayScreenController.Stream {
                    override fun send(payload: ByteArray, ptsUs: Long) = sender.send(payload, ptsUs)
                    override fun keepAlive() = sender.keepAlive()
                    override fun close() { screenSocket.compareAndSet(socket, null); socket.close() }
                }
            },
            teardown = { dict("TEARDOWN", "/", mapOf("streams" to listOf(mapOf("type" to 110L, "uuid" to uuid)))) },
            notifyModes = { control.request("POST", "/command", BinaryPlist.encode(it)) },
            focus = {
                AppLog.i("CarPlay screen: ${if (it) "projection" else "native head unit menu"}; audio session retained", false)
                screenFocus(it)
            },
            failed = failed,
        )
        life.own(screens::close)
        screenController = screens
        val eventPort = airPlayPort(setup["eventPort"])
        AppLog.i("CarPlay channels: eventPort=$eventPort timingPort=${time.port}", false)
        if (eventPort > 0) {
            val s = tcp(eventPort)
            s.soTimeout = 0
            fun derive(label: String) =
                AirPlayCrypto.hkdfSha512(
                    keys.sharedSecret,
                    "Events-Salt".toByteArray(),
                    label.toByteArray(),
                )
            val listener = AirPlayWire(s, "RTSP (events)")
            listener.cipher =
                ControlCipher(
                    derive("Events-Write-Encryption-Key"),
                    derive("Events-Read-Encryption-Key"),
                )
            thread(name = "CarPlay events", isDaemon = true) {
                try {
                    val observedKinds = HashSet<String>()
                    while (!s.isClosed) {
                        val m = listener.read()
                        if (m.line.startsWith("RTSP/") || m.line.startsWith("HTTP/")) continue
                        val count = eventRequests.incrementAndGet()
                        listener.respond(m)
                        eventReplies.incrementAndGet()
                        if (m.line.contains(" /command ") && m.data.isNotEmpty()) {
                            val cmd = BinaryPlist.parse(m.data) as? Map<*, *>
                            val kind = (cmd?.get("type") as? String)?.take(64)
                                ?.replace(Regex("[^A-Za-z0-9_-]"), "?") ?: "unknown"
                            if (count <= 12 || observedKinds.size < 64 && observedKinds.add(kind)) {
                                AppLog.i("CarPlay event #$count type=$kind acknowledged", false)
                            }
                            if (kind in setOf("changeModes", "modesChanged", "duckAudio", "unduckAudio")) {
                                // Public control values only. Never log iAP messages, HID reports,
                                // network parameters or arbitrary strings from the accessory.
                                val params = cmd?.get("params") as? Map<*, *> ?: emptyMap<Any, Any>()
                                val resources = (params["resources"] as? List<*>).orEmpty().take(8)
                                    .filterIsInstance<Map<*, *>>().map { r ->
                                        r.filter { (k, v) -> k in setOf("resourceID", "entity", "permanentEntity", "transferType") && v is Number }
                                    }
                                AppLog.i("CarPlay audio control: $kind resources=$resources duration=${params["durationMs"] as? Number} volume=${params["volume"] as? Number}", false)
                            }
                            if (cmd?.get("type") == "iAPSendMessage") {
                                val data =
                                    ((cmd["params"] as? Map<*, *>)?.get("data") ?: cmd["data"])
                                        as? ByteArray
                                if (data != null) {
                                    val link =
                                        eventIap
                                            ?: CarPlayIapChannel(
                                                    context,
                                                    life,
                                                    ep.device,
                                                    { payload ->
                                                        control.request("POST", "/command",
                                                            BinaryPlist.encode(
                                                                mapOf(
                                                                    "type" to "iAPSendMessage",
                                                                    "params" to
                                                                        mapOf("data" to payload),
                                                                )
                                                            )
                                                        )
                                                    },
                                                    event,
                                                    failed,
                                                )
                                                .also { eventIap = it }
                                    link.accept(data)
                                }
                            } else if (cmd != null) {
                                screens.event(cmd)
                                event(cmd)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!s.isClosed) failed(e)
                }
            }
        }
        val modes =
            mapOf(
                "type" to "modesChanged",
                "params" to
                    mapOf(
                        "resources" to
                            listOf(1L, 2L).map {
                                mapOf("resourceID" to it, "entity" to 1L, "permanentEntity" to 1L)
                            }
                    ),
            )
        control.request("POST", "/command", BinaryPlist.encode(modes))
        hid.keys.forEach { uuid ->
            control.request("POST", "/command",
                BinaryPlist.encode(
                    mapOf(
                        "type" to "hidSetInputMode",
                        "params" to mapOf("uuid" to uuid, "hidInputMode" to 0L),
                    )
                )
            )
        }
        screens.start()
        val enabled =
            (setup["enabledFeatures"] as? List<*>).orEmpty().map { it.toString().lowercase() }
        if (enabled.any { it in setOf("iap", "iap2", "datastream", "iapdatastream") }) {
            val seed = nextId() and 0x7fffffff
            val d =
                stream(
                    mapOf(
                        "type" to 130L,
                        "clientTypeUUID" to "E9459FD0-BCAD-4C45-820F-1E72447EF2F2",
                        "seed" to seed,
                        "streamConnectionID" to nextId(),
                    )
                )
            val sock = tcp(airPlayPort(d["dataPort"]))
            sock.soTimeout = 0
            fun key(label: String) =
                AirPlayCrypto.hkdfSha512(
                    keys.sharedSecret,
                    "DataStream-Salt$seed".toByteArray(),
                    label.toByteArray(),
                )
            val tunnel =
                CarPlayDataStream(
                    sock,
                    key("DataStream-Output-Encryption-Key"),
                    key("DataStream-Input-Encryption-Key"),
                )
            val link = CarPlayIapChannel(context, life, ep.device, tunnel::send, event, failed)
            thread(name = "CarPlay iAP DataStream", isDaemon = true) {
                try {
                    tunnel.read(link::accept)
                } catch (e: Exception) {
                    if (!sock.isClosed) failed(e)
                }
            }
        }
        val formats = (info["audioFormats"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        AppLog.i("CarPlay audio capabilities: " + formats.take(32).joinToString { d ->
            "${d["type"]}/${d["audioType"]} output=0x${(d["audioOutputFormats"] as? Number)?.toLong()?.toString(16)}"
        }, false)
        fun negotiateAudio(navigation: Boolean): CarPlayAudio? {
          for (fmt in CarPlayAudioProfile.candidates(info, navigation)) {
            val encoder = if (fmt.aac) AutoAudioEncoder(prefix = 0, channels = fmt.channels,
                profile = fmt.aacProfile) else null
            try {
                encoder?.prepare(fmt.rate)
                encoder?.verifyFrameLength(fmt.rate, fmt.framesPerPacket)
            } catch (e: Exception) {
                encoder?.close()
                AppLog.i("CarPlay audio: ${fmt.stream}/${fmt.role} codec unavailable (${e.message})", false)
                continue
            }
            if (encoder != null) life.own(encoder::close)
            val audioId = nextId()
            val socket = udp()
            val desc = try {
                stream(mapOf(
                    "type" to fmt.stream, "audioType" to fmt.role, "audioFormat" to fmt.bit,
                    "streamConnectionID" to audioId, "input" to false, "audioLatencyMs" to fmt.latencyMs,
                    "controlPort" to socket.localPort.toLong(), "spf" to fmt.framesPerPacket.toLong(),
                    "supportsHighAccuracyTimestamps" to false, "supportsRTPPacketRedundancy" to false,
                ))
            } catch (e: RtspError) {
                if (e.status !in setOf(400, 406, 415, 451, 461, 501)) throw e
                AppLog.i("CarPlay audio: ${fmt.stream}/${fmt.role} format=${fmt.bit} rejected, RTSP ${e.status}", false)
                socket.close(); encoder?.close()
                continue
            }
            val port = airPlayPort(desc["dataPort"])
            if (port == 0) { socket.close(); encoder?.close(); continue }
            val sender = CarPlayAudio(socket, ep.host, port, airPlayPort(desc["controlPort"]),
                fmt.stream.toInt(), AirPlayStreamKeys.chachaStreamKey(keys.sharedSecret, audioId), fmt.rate, encoder, fmt.latencyMs,
                channels = fmt.channels, encodedFrames = fmt.framesPerPacket)
            val codec = if (!fmt.aac) "PCM" else if (fmt.aacProfile == 39) "AAC-ELD" else "AAC-LC"
            AppLog.i("CarPlay audio: ${fmt.stream}/${fmt.role}, $codec ${fmt.channels} channels ${fmt.rate} Hz", false)
            return sender
          }
          return null
        }
        audio = negotiateAudio(false)
        if (com.hsd.universal.dex.DexPrefs(context).separateNavigationAudio) {
            navigationAudio = negotiateAudio(true)
            if (navigationAudio == null) AppLog.i("CarPlay navigation: no compatible alternate output; using mixed audio", false)
        }
        if (audio == null) AppLog.i("CarPlay audio: no negotiated media output", false)
        control.request("RECORD", "/", byteArrayOf())
        val recordedAt = android.os.SystemClock.elapsedRealtime()
        val feedbackReplies = java.util.concurrent.atomic.AtomicInteger()
        life.own {
            AppLog.i("CarPlay: RECORD ${android.os.SystemClock.elapsedRealtime() - recordedAt} ms, " +
                "UDP keepalive sent=${udpSent.get()}, feedback replies=${feedbackReplies.get()}", false)
        }
        // Streaming health is observed on media/event sockets. Optional feedback shares control
        // with iAP commands; a slow reply must not block those or the media heartbeat.
        // Closing life interrupts the reader and releases every outstanding request.
        val executor = Executors.newScheduledThreadPool(2)
        life.own { executor.shutdownNow() }
        life.own {
            audio?.let { AppLog.i("CarPlay audio final: ${it.progressSummary()}", false) }
            navigationAudio?.let { AppLog.i("CarPlay navigation final: ${it.progressSummary()}", false) }
        }
        val audioReportAt = java.util.concurrent.atomic.AtomicLong(0L)
        val feedbackSince = java.util.concurrent.atomic.AtomicLong(0L)
        executor.scheduleAtFixedRate(
            {
                try {
                    screens.keepAlive()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - audioReportAt.get() >= 10_000) {
                        audioReportAt.set(now)
                        audio?.let { AppLog.i("CarPlay audio: ${it.progressSummary()}", false) }
                        navigationAudio?.let { AppLog.i("CarPlay navigation: ${it.progressSummary()}", false) }
                    }
                    val since = feedbackSince.get()
                    if (since > 0 && android.os.SystemClock.elapsedRealtime() - since >= 12_000 &&
                        feedbackSince.compareAndSet(since, -1L))
                        AppLog.i("CarPlay: магнитола задерживает статистику; видео, звук и heartbeat продолжаются", false)
                } catch (e: Exception) {
                    failed(e)
                }
            },
            1,
            1,
            TimeUnit.SECONDS,
        )
        // Feedback is optional receiver telemetry. The negotiated stream and its heartbeat
        // continue when the receiver declines telemetry. Transport and session errors are fatal.
        val feedback = object : Runnable {
            var delay = 1L
            var reportAt = 0L
            override fun run() {
                try {
                    feedbackSince.set(android.os.SystemClock.elapsedRealtime())
                    val body = control.request("POST", "/feedback", timeoutMillis = 0)
                    feedbackReplies.incrementAndGet()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (body.isNotEmpty() && now - reportAt >= 10_000) {
                        reportAt = now
                        val response = runCatching { BinaryPlist.parse(body) as? Map<*, *> }.getOrNull()
                        val streams = (response?.get("streams") as? List<*>).orEmpty().take(8)
                            .filterIsInstance<Map<*, *>>().map { stream ->
                                stream.filter { (k, v) -> k in setOf("type", "streamConnectionID", "sampleTime", "rate", "buffered", "audioLatencyMs") && v is Number }
                            }
                        AppLog.i("CarPlay receiver audio feedback: streams=$streams", false)
                    }
                    delay = 1L
                } catch (e: RtspError) {
                    when (e.status) {
                        404, 405, 501 -> return
                        500, 502, 503, 504 -> {
                            if (delay == 1L) com.hsd.universal.AppLog.i("CarPlay · магнитола временно не предоставляет статистику (RTSP ${e.status})", false)
                            delay = (delay * 2).coerceAtMost(30L)
                        }
                        else -> { failed(e); return }
                    }
                } catch (e: Exception) {
                    failed(e)
                    return
                } finally {
                    feedbackSince.set(0L)
                }
                if (!executor.isShutdown) executor.schedule(this, delay, TimeUnit.SECONDS)
            }
        }
        executor.schedule(feedback, 1, TimeUnit.SECONDS)
        return Display(width, height, fps, hid)
    }
}
