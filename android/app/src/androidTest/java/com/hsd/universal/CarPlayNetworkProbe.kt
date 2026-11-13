package com.hsd.universal

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.os.Bundle
import com.hsd.universal.carplay.*
import io.github.shaonialife.cardeck.airplay.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.*
import kotlin.concurrent.thread

/**
 * RTSP receiver on loopback. Tests the production authenticated AirPlay transport on an Android
 * AVD.
 */
internal class CarPlayNetworkProbe(private val context: Context, private val aac: Boolean = false) {
    private val local = InetAddress.getByName("127.0.0.1")
    private val life = ProjectionResources()
    private val peer = CarPlayTestPeer()
    private val captured = CompletableFuture<Int>()
    private val heard = CompletableFuture<Boolean>()
    private val gesture = CompletableFuture<Boolean>()
    private val input = CompletableFuture<Boolean>()
    private val threadErrors = ConcurrentLinkedQueue<Throwable>()
    private val heartbeats = java.util.concurrent.atomic.AtomicInteger()
    private val delayedFeedback = CompletableFuture<Int>()

    /** A second Android codec decodes the receiver's decrypted raw access units. Check the
     * fundamental against its neighbours, not just the presence of non-zero compressed data. */
    private fun verify440Hz(units: List<ByteArray>): Int {
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = arrayListOf<Int>()
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12,0x10)))
            decoder.configure(format,null,null,0); decoder.start()
            var next = 0; var ended = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!ended && System.nanoTime() < deadline) {
                if (next <= units.size) {
                    val id = decoder.dequeueInputBuffer(5000)
                    if (id >= 0) {
                        val data = units.getOrNull(next); if (data != null) decoder.getInputBuffer(id)!!.put(data)
                        decoder.queueInputBuffer(id,0,data?.size ?: 0,next*1024_000_000L/44100,
                            if (data == null) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        next++
                    }
                }
                val id = decoder.dequeueOutputBuffer(info,5000)
                if (id >= 0) {
                    val buffer = decoder.getOutputBuffer(id)!!.order(ByteOrder.LITTLE_ENDIAN)
                    for (offset in info.offset until info.offset + info.size - 3 step 4) samples.add(buffer.getShort(offset).toInt())
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(id,false)
                }
            }
            check(ended && samples.size >= 8192)
            val steady = samples.takeLast(8192)
            fun energy(hz:Int):Double {
                var x=0.0;var y=0.0
                steady.forEachIndexed { i,v -> val phase=2*Math.PI*hz*i/44100; x+=v*kotlin.math.cos(phase);y+=v*kotlin.math.sin(phase) }
                return x*x+y*y
            }
            val level = energy(440)
            check(level>1e8 && level>10*maxOf(energy(330),energy(550)))
            return samples.size
        } finally { runCatching { decoder.stop() }; decoder.release() }
    }

    private fun listener() = ServerSocket(0, 4, local).also { life.own(it::close) }

    private val ctrl = listener()
    private val events = listener()
    private val video = listener()
    private val audio = DatagramSocket(0, local).also { life.own(it::close) }
    private val record = CountDownLatch(1)
    private val screenId = CompletableFuture<Long>()
    private val audioId = CompletableFuture<Long>()

    private fun task(name: String, fn: () -> Unit) =
        thread(name = name, isDaemon = true) {
            try {
                fn()
            } catch (e: Exception) {
                threadErrors.add(e)
            }
        }

    private fun receive(server: ServerSocket) =
        server.accept().also { s ->
            life.own(s::close)
            s.soTimeout = 12_000
        }

    private fun derive(name: String) =
        AirPlayCrypto.hkdfSha512(peer.pvShared, "Events-Salt".toByteArray(), name.toByteArray())

    private fun control() {
        val socket = receive(ctrl)
        val raw = DataInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        var cipher: ControlCipher? = null
        var plain = ByteArrayInputStream(byteArrayOf())
        val bytes =
            object : InputStream() {
                override fun read(): Int {
                    val codec = cipher ?: return raw.read()
                    if (plain.available() == 0) {
                        val a = raw.read()
                        if (a < 0) return -1
                        val b = raw.readUnsignedByte()
                        val data = ByteArray(18 + a + (b shl 8))
                        data[0] = a.toByte()
                        data[1] = b.toByte()
                        raw.readFully(data, 2, data.size - 2)
                        plain = ByteArrayInputStream(codec.decrypt(data).data)
                    }
                    return plain.read()
                }
            }
        while (!socket.isClosed) {
            val (line, h) = AirPlayWire.head(bytes) ?: break
            val body =
                ByteArray(h.getValue("content-length").toInt()).also {
                    DataInputStream(bytes).readFully(it)
                }
            val method = line.substringBefore(' ')
            val path = line.split(' ')[1]
            val content =
                when {
                    path.startsWith("/pair-") -> peer.handle(path, body)
                    method == "GET" && path == "/info" ->
                        BinaryPlist.encode(
                            mapOf(
                                "displays" to
                                    listOf(
                                        mapOf(
                                            "widthPixels" to 320L,
                                            "heightPixels" to 240L,
                                            "maxFPS" to 30L,
                                            "uuid" to "screen",
                                        )
                                    ),
                                "audioFormats" to
                                    listOf(
                                        mapOf(
                                            "type" to if (aac) 102L else 100L,
                                            "audioType" to "media",
                                            "audioOutputFormats" to if (aac) (1L shl 22) else 32768L,
                                        )
                                    ),
                            )
                        )
                    method == "SETUP" && path == "/setup" -> {
                        check(((BinaryPlist.parse(body) as Map<*, *>)["timingPort"] as Long) > 0)
                        BinaryPlist.encode(mapOf("eventPort" to events.localPort.toLong()))
                    }
                    method == "SETUP" -> {
                        val d =
                            (((BinaryPlist.parse(body) as Map<*, *>)["streams"] as List<*>).single()
                                as Map<*, *>)
                        val id = d["streamConnectionID"] as Long
                        val port =
                            if (d["type"] == 110L) {
                                check(d["uuid"] == "screen")
                                screenId.complete(id)
                                video.localPort
                            } else {
                                check(d["audioFormat"] == if (aac) (1L shl 22) else 32768L)
                                check(d["spf"] == if (aac) 1024L else 352L)
                                audioId.complete(id)
                                audio.localPort
                            }
                        BinaryPlist.encode(
                            mapOf(
                                "streams" to
                                    listOf(mapOf("type" to d["type"], "dataPort" to port.toLong()))
                            )
                        )
                    }
                    method == "RECORD" -> {
                        record.countDown()
                        byteArrayOf()
                    }
                    aac && path == "/feedback" && !delayedFeedback.isDone -> {
                        val before = heartbeats.get()
                        Thread.sleep(14_000)
                        delayedFeedback.complete(heartbeats.get() - before)
                        byteArrayOf()
                    }
                    else -> byteArrayOf()
                }
            val response =
                "RTSP/1.0 200 OK\r\nCSeq: ${h.getValue("cseq")}\r\nContent-Length: ${content.size}\r\n\r\n"
                    .toByteArray() + content
            out.write(cipher?.encrypt(response) ?: response)
            out.flush()
            if (cipher == null && path == "/pair-verify" && peer.lastWriteKey != null)
                cipher = ControlCipher(peer.lastReadKey!!, peer.lastWriteKey!!)
        }
    }

    private fun videoReceiver() {
        val socket = receive(video)
        val data = DataInputStream(socket.getInputStream())
        val key =
            AirPlayStreamKeys.chachaStreamKey(peer.pvShared, screenId.get(8, TimeUnit.SECONDS))
        var csd = byteArrayOf()
        var counter = 0L
        while (!socket.isClosed && (!captured.isDone || aac)) {
            val head = ByteArray(128).also(data::readFully)
            val buf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val body = ByteArray(buf.getInt(0)).also(data::readFully)
            if (head[4].toInt() == 2) { heartbeats.incrementAndGet(); continue }
            if (captured.isDone) continue
            if (head[4].toInt() == 1) {
                check(head[6].toInt() == 4 && buf.getFloat(16) == 320f && buf.getFloat(20) == 240f)
                val config = DataInputStream(body.inputStream())
                val prefix = ByteArray(6).also(config::readFully)
                check(prefix[0].toInt() == 1)
                val sps = ByteArray(config.readUnsignedShort()).also(config::readFully)
                check(config.readUnsignedByte() == 1)
                val pps = ByteArray(config.readUnsignedShort()).also(config::readFully)
                csd = start + sps + start + pps
            }
            if (head[4].toInt() != 0) continue
            val decoded =
                AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter++), body, head)
            val nals = DataInputStream(decoded.inputStream())
            val frame = ByteArrayOutputStream()
            while (nals.available() > 0) {
                val n = nals.readInt()
                check(n in 1..nals.available())
                frame.write(start)
                frame.write(ByteArray(n).also(nals::readFully))
            }
            check(csd.isNotEmpty())
            File(context.cacheDir, "carplay-receiver.h264").writeBytes(csd + frame.toByteArray())
            captured.complete(decode(csd, frame.toByteArray()))
        }
    }

    private fun decode(headers: ByteArray, frame: ByteArray): Int {
        val info =
            android.media
                .MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .first {
                    !it.isEncoder && it.isSoftwareOnly && "video/avc" in it.supportedTypes
                }
        val codec = MediaCodec.createByCodecName(info.name)
        try {
            val f = MediaFormat.createVideoFormat("video/avc", 320, 240)
            val csd = com.hsd.universal.aa.H264Stream.nalUnits(headers)
            csd.forEachIndexed { i, b -> f.setByteBuffer("csd-" + i, ByteBuffer.wrap(b)) }
            codec.configure(f, null, null, 0)
            codec.start()
            val idx = codec.dequeueInputBuffer(2_000_000)
            codec.getInputBuffer(idx)!!.put(frame)
            codec.queueInputBuffer(idx, 0, frame.size, 100_000, 0)
            val end = codec.dequeueInputBuffer(2_000_000)
            codec.queueInputBuffer(end, 0, 0, 111_111, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val info = MediaCodec.BufferInfo()
            repeat(40) {
                val output = codec.dequeueOutputBuffer(info, 50_000)
                if (output >= 0) {
                    val n = info.size
                    codec.releaseOutputBuffer(output, false)
                    if (n > 0) return n
                }
            }
            error("AVC decoder produced no picture")
        } finally {
            codec.release()
        }
    }

    private fun generateVideo(session: CarPlayAirSession) {
        val codec = MediaCodec.createEncoderByType("video/avc")
        try {
            val f = MediaFormat.createVideoFormat("video/avc", 320, 240)
            f.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            f.setInteger(MediaFormat.KEY_BIT_RATE, 400000)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            repeat(50) { n ->
                val id = codec.dequeueInputBuffer(35000)
                if (id >= 0) {
                    val pixels =
                        ByteArray(320 * 240 * 3 / 2) { (if (it < 320 * 240) 120 else 128).toByte() }
                    codec.getInputBuffer(id)!!.put(pixels)
                    codec.queueInputBuffer(id, 0, pixels.size, n * 33333L, 0)
                }
                val o = codec.dequeueOutputBuffer(info, 35000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
                    for (c in listOf("csd-0", "csd-1")) codec.outputFormat.getByteBuffer(c)?.let {
                        val b = ByteArray(it.remaining())
                        it.get(b)
                        session.video.send(b, 0)
                    }
                if (o >= 0) {
                    val b = ByteArray(info.size)
                    codec.getOutputBuffer(o)!!.get(b)
                    codec.releaseOutputBuffer(o, false)
                    if (b.isNotEmpty() && session.video.send(b, info.presentationTimeUs)) return
                }
            }
            error("No encoded frame")
        } finally {
            codec.release()
        }
    }

    fun run(): Bundle {
        try {
            task("CarPlay QA control", ::control)
            task("CarPlay QA screen", ::videoReceiver)
            task("CarPlay QA HID") {
                val s = receive(events)
                check(record.await(10, TimeUnit.SECONDS))
                val message =
                    BinaryPlist.encode(
                        mapOf(
                            "type" to "hidSendReport",
                            "params" to
                                mapOf(
                                    "uuid" to "touch",
                                    "hidReport" to byteArrayOf(1, 12, 0, 23, 0),
                                ),
                        )
                    )
                val cipher =
                    ControlCipher(
                        derive("Events-Read-Encryption-Key"),
                        derive("Events-Write-Encryption-Key"),
                    )
                val request =
                    "POST /command RTSP/1.0\r\nCSeq: 7\r\nContent-Length: ${message.size}\r\n\r\n"
                        .toByteArray() + message
                s.getOutputStream().write(cipher.encrypt(request))
                val d = DataInputStream(s.getInputStream())
                val a = d.readUnsignedByte()
                val b = d.readUnsignedByte()
                val packet = ByteArray(18 + a + (b shl 8))
                packet[0] = a.toByte()
                packet[1] = b.toByte()
                d.readFully(packet, 2, packet.size - 2)
                gesture.complete(cipher.decrypt(packet).data.decodeToString().contains("CSeq: 7"))
            }
            val ep =
                CarPlayEndpoint(
                    context.getSystemService(ConnectivityManager::class.java)!!.activeNetwork!!,
                    local,
                    ctrl.localPort,
                    "02:AA:11:BB:22:CC",
                    "test-" + UUID.randomUUID(),
                    peer.staticKey.publicKey,
                )
            val s =
                CarPlayAirSession(
                    context,
                    ep,
                    life,
                    { cmd -> input.complete(cmd["type"] == "hidSendReport") },
                    { threadErrors.add(it) },
                )
            val display = s.start()
            check(display.width == 320)
            generateVideo(s)
            audio.soTimeout = 5000
            fun receiveUnit(): ByteArray {
                val data = DatagramPacket(ByteArray(8192),8192)
                audio.receive(data)
                val p = data.data.copyOf(data.length)
                check((p[1].toInt() and 127) == if (aac) 102 else 100)
                return AirPlayCrypto.chachaOpen(AirPlayStreamKeys.chachaStreamKey(peer.pvShared, audioId.get()),
                    ByteArray(4) + p.takeLast(8).toByteArray(), p.copyOfRange(12, p.size-8), p.copyOfRange(4,12))
            }
            val verified = if (aac) {
                val sender = checkNotNull(s.audio)
                repeat(24) { n ->
                    val pcm = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(1024) { i ->
                        val sample = (3000 * kotlin.math.sin(2 * Math.PI * 440 * (n * 1024 + i) / 44100)).toInt().toShort()
                        pcm.putShort(sample); pcm.putShort(sample)
                    }
                    sender.offer(pcm.array(), n * 1024_000_000L / 44100)
                }
                verify440Hz(List(16) { receiveUnit() })
            } else {
                val expected = ByteArray(352 * 4) { (it and 127).toByte() }
                s.audio!!.offer(expected)
                val actual = receiveUnit()
                check(actual.size == expected.size && actual.indices.all { actual[it] == expected[it xor 1] })
                actual.size
            }
            check(input.get(4, TimeUnit.SECONDS) && gesture.get(4, TimeUnit.SECONDS))
            return Bundle().apply {
                if (aac) {
                    val received = delayedFeedback.get(24,TimeUnit.SECONDS)
                    check(received >= 10 && threadErrors.none { it is SocketTimeoutException }) {
                        "Media heartbeat stalled during optional feedback: $received"
                    }
                    putInt("CarPlay_heartbeats_during_delayed_feedback",received)
                }
                putInt("CarPlay_decoded_YUV_bytes", captured.get(8, TimeUnit.SECONDS))
                putInt(if (aac) "CarPlay_440Hz_decoded_AAC_frames" else "CarPlay_authenticated_PCM_bytes", verified)
                putString("CarPlay_HID", "encrypted event and CSeq acknowledgement")
            }
        } catch (e: Exception) {
            threadErrors.forEach(e::addSuppressed)
            throw e
        } finally {
            life.close()
        }
    }

    private companion object {
        val start = byteArrayOf(0, 0, 0, 1)
    }
}
