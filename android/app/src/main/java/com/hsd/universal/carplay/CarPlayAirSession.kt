package com.hsd.universal.carplay

import android.content.Context
import android.util.Base64
import com.hsd.universal.ProjectionResources
import io.github.shaonialife.cardeck.airplay.*
import java.net.*
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class CarPlayAirSession(
    private val context: Context,
    private val ep: CarPlayEndpoint,
    private val life: ProjectionResources,
    private val event: (Map<*, *>) -> Unit,
    private val failed: (String) -> Unit,
) {
    private var eventSender: AirPlayWire? = null
    private var eventIap: CarPlayIapChannel? = null
    lateinit var video: CarPlayVideo
        private set

    var audio: CarPlayAudio? = null
        private set

    data class Display(
        val width: Int,
        val height: Int,
        val fps: Int,
        val hid: Map<String, ByteArray>,
    )

    private fun tcp(port: Int): Socket {
        require(port in 1..65535)
        val s = ep.network.socketFactory.createSocket()
        life.own(s::close)
        s.tcpNoDelay = true
        s.soTimeout = 12_000
        s.connect(InetSocketAddress(ep.host, port), 7000)
        return s
    }

    private fun udp() =
        DatagramSocket(null).also {
            life.own(it::close)
            ep.network.bindSocket(it)
            it.bind(InetSocketAddress(0))
        }

    private fun pair(wire: AirPlayWire): PairVerifyClient.ControlKeys {
        val prefs = context.getSharedPreferences("carplay-identities", Context.MODE_PRIVATE)
        fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        fun stored(k: String) = prefs.getString(k, null)?.let { Base64.decode(it, Base64.DEFAULT) }
        val privateKey =
            stored("key")
                ?: AirPlayCrypto.ed25519Generate().privateKey.also {
                    check(prefs.edit().putString("key", encode(it)).commit())
                }
        val id =
            prefs.getString("controller", null)
                ?: UUID.randomUUID().toString().also {
                    check(prefs.edit().putString("controller", it).commit())
                }
        val identity =
            ControllerIdentity(id, privateKey, AirPlayCrypto.ed25519PublicKey(privateKey))
        val io = TlvTransport { path, body ->
            wire.request("POST", path, body, "application/pairing+tlv8")
        }
        val pinned = stored("peer." + ep.peer)
        if (pinned != null && ep.publicKey != null)
            require(pinned.contentEquals(ep.publicKey)) { "Ключ сопряжённой магнитолы изменился" }
        val expected =
            pinned
                ?: run {
                    val setup = PairSetupClient(identity, io) {}.run()
                    val key =
                        setup?.publicKey
                            ?: ep.publicKey
                            ?: error("Магнитола не предоставила проверяемый ключ AirPlay")
                    if (ep.publicKey != null)
                        require(ep.publicKey.contentEquals(key)) {
                            "Ключ AirPlay отличается от ключа iAP2"
                        }
                    key
                }
        val keys = PairVerifyClient(identity, io) {}.run(expected)
        if (pinned == null)
            check(prefs.edit().putString("peer." + ep.peer, encode(expected)).commit())
        return keys
    }

    fun start(): Display {
        val control = AirPlayWire(tcp(ep.port))
        val keys = pair(control)
        control.cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
        val time = TimingServer(socketFactory = ::udp)
        check(time.start())
        life.own(time::stop)
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
        val info = dict("GET", "/info")
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
        val eventPort = (setup["eventPort"] as? Number)?.toInt()
        if (eventPort != null && eventPort > 0) {
            val s = tcp(eventPort)
            s.soTimeout = 0
            fun derive(label: String) =
                AirPlayCrypto.hkdfSha512(
                    keys.sharedSecret,
                    "Events-Salt".toByteArray(),
                    label.toByteArray(),
                )
            val listener = AirPlayWire(s)
            listener.cipher =
                ControlCipher(
                    derive("Events-Write-Encryption-Key"),
                    derive("Events-Read-Encryption-Key"),
                )
            eventSender = listener
            thread(name = "CarPlay events", isDaemon = true) {
                try {
                    while (!s.isClosed) {
                        val m = listener.read()
                        if (m.line.startsWith("RTSP/") || m.line.startsWith("HTTP/")) continue
                        listener.respond(m)
                        if (m.line.contains(" /command ") && m.data.isNotEmpty()) {
                            val cmd = BinaryPlist.parse(m.data) as? Map<*, *>
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
                                                    { payload ->
                                                        listener.command(
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
                            } else if (cmd != null) event(cmd)
                        }
                    }
                } catch (e: Exception) {
                    if (!s.isClosed) failed(e.message ?: "Закрыт канал событий CarPlay")
                }
            }
        }
        fun stream(desc: Map<String, Any?>): Map<*, *> {
            val response = dict("SETUP", "/", mapOf("streams" to listOf(desc)))
            return (response["streams"] as List<*>).filterIsInstance<Map<*, *>>().first {
                it["type"] == desc["type"]
            }
        }
        fun nextId() = SecureRandom().nextLong().ushr(1).coerceAtLeast(1)
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
            eventSender?.command(
                BinaryPlist.encode(
                    mapOf(
                        "type" to "hidSetInputMode",
                        "params" to mapOf("uuid" to uuid, "hidInputMode" to 0L),
                    )
                )
            )
        }
        val screenId = nextId()
        val screen =
            stream(
                mapOf(
                    "type" to 110L,
                    "streamConnectionID" to screenId,
                    "uuid" to uuid,
                    "latencyMs" to 25L,
                )
            )
        video =
            CarPlayVideo(
                tcp((screen["dataPort"] as Number).toInt()).getOutputStream(),
                AirPlayStreamKeys.chachaStreamKey(keys.sharedSecret, screenId),
                width,
                height,
            )
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
            val sock = tcp((d["dataPort"] as Number).toInt())
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
            val link = CarPlayIapChannel(context, life, tunnel::send, event, failed)
            thread(name = "CarPlay iAP DataStream", isDaemon = true) {
                try {
                    tunnel.read(link::accept)
                } catch (e: Exception) {
                    if (!sock.isClosed) failed(e.message ?: "Закрыт DataStream CarPlay")
                }
            }
        }
        val formats = (info["audioFormats"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        val media = formats.filter {
            (it["type"] as? Number)?.toInt() in listOf(100, 102) && it["audioType"] == "media"
        }
        val fmt = media.firstNotNullOfOrNull { d ->
            val mask = (d["audioOutputFormats"] as? Number)?.toLong() ?: 0L
            listOf(32768L to 48000, 2048L to 44100)
                .firstOrNull { mask and it.first != 0L }
                ?.let { Triple((d["type"] as Number).toLong(), it.first, it.second) }
        }
        if (fmt != null) {
            val audioId = nextId()
            val socket = udp()
            val desc =
                stream(
                    mapOf(
                        "type" to fmt.first,
                        "audioType" to "media",
                        "audioFormat" to fmt.second,
                        "streamConnectionID" to audioId,
                        "input" to false,
                        "audioLatencyMs" to 250L,
                        "controlPort" to socket.localPort.toLong(),
                        "spf" to 352L,
                        "supportsRTPPacketRedundancy" to false,
                    )
                )
            audio =
                CarPlayAudio(
                    socket,
                    ep.host,
                    (desc["dataPort"] as Number).toInt(),
                    (desc["controlPort"] as? Number)?.toInt() ?: 0,
                    fmt.first.toInt(),
                    AirPlayStreamKeys.chachaStreamKey(keys.sharedSecret, audioId),
                    fmt.third,
                )
        }
        control.request("RECORD", "/", byteArrayOf())
        val executor = Executors.newSingleThreadScheduledExecutor()
        life.own { executor.shutdownNow() }
        val keepPort = (setup["keepAlivePort"] as? Number)?.toInt() ?: 0
        val heartbeat = if (keepPort in 1..65535) udp() else null
        executor.scheduleAtFixedRate(
            {
                try {
                    video.keepAlive()
                    heartbeat?.send(DatagramPacket(byteArrayOf(0), 1, ep.host, keepPort))
                } catch (e: Exception) {
                    failed(e.message ?: "CarPlay отключён")
                }
            },
            1,
            1,
            TimeUnit.SECONDS,
        )
        // Feedback is optional receiver telemetry. The negotiated stream and its heartbeat
        // continue when the receiver declines telemetry. Transport and session errors are fatal.
        val feedback = object : Runnable {
            var delay = 2L
            override fun run() {
                try {
                    control.request("POST", "/feedback")
                    delay = 2L
                } catch (e: RtspError) {
                    when (e.status) {
                        404, 405, 501 -> return
                        500, 502, 503, 504 -> {
                            if (delay == 2L) com.hsd.universal.AppLog.i("CarPlay · магнитола временно не предоставляет статистику (RTSP ${e.status})", false)
                            delay = (delay * 2).coerceAtMost(30L)
                        }
                        else -> { failed(e.message ?: "Завершён сеанс CarPlay"); return }
                    }
                } catch (e: Exception) {
                    failed(e.message ?: "Закрыт канал управления CarPlay")
                    return
                }
                if (!executor.isShutdown) executor.schedule(this, delay, TimeUnit.SECONDS)
            }
        }
        executor.schedule(feedback, 2, TimeUnit.SECONDS)
        return Display(width, height, fps, hid)
    }
}
