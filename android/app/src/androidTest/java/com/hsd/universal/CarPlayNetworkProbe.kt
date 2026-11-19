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
internal class CarPlayNetworkProbe(private val context: Context, private val aac: Boolean = false,
    private val mediaSeconds: Int = 30, private val navigationMode: String? = null, private val carMenu: Boolean = false) {
    init { require(mediaSeconds in 30..3600) }
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
    private val udpKeepalives = java.util.concurrent.atomic.AtomicInteger()
    private val watchdogFailure = CompletableFuture<String>()
    private val hidConfiguration = CompletableFuture<Boolean>()
    private val timingReply = CompletableFuture<Boolean>()
    private val videoFrames = java.util.concurrent.atomic.AtomicInteger()
    private val eventAcks = java.util.concurrent.atomic.AtomicInteger()
    private val videoFinished = CompletableFuture<Boolean>()
    private val audioFinished = CompletableFuture<Boolean>()
    private val gesturesFinished = CompletableFuture<Boolean>()

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
    private val audioControl = DatagramSocket(0, local).also { life.own(it::close) }
    private val audioSyncPackets = java.util.concurrent.atomic.AtomicInteger()
    private val initialAudioSyncLeadUs = java.util.concurrent.atomic.AtomicLong()
    private val firstAudioSync = CompletableFuture<Boolean>()
    private val idle = DatagramSocket(0, local).also { life.own(it::close) }
    private val record = CountDownLatch(1)
    private val screenIds = LinkedBlockingQueue<Long>()
    private val usedScreenIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val screenModes = LinkedBlockingQueue<Int>()
    private val uiRequests = LinkedBlockingQueue<Map<String, Any>>()
    private val screenTeardowns = java.util.concurrent.atomic.AtomicInteger()
    private val decodedScreens = java.util.concurrent.atomic.AtomicInteger()
    private val recordCalls = java.util.concurrent.atomic.AtomicInteger()
    private val mediaSetups = java.util.concurrent.atomic.AtomicInteger()
    private val audioId = CompletableFuture<Long>()
    private val voice = DatagramSocket(0,local).also { life.own(it::close) }
    private val voiceId = CompletableFuture<Long>()

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
                                "keepAliveSendStatsAsBody" to aac,
                                "hidDevices" to listOf(mapOf("uuid" to "touch", "hidDescriptor" to byteArrayOf(5, 13, 9, 4))),
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
                                    ) + (if (navigationMode != null && navigationMode != "fallback") listOf(
                                        mapOf("type" to 101L,"audioType" to "default","audioOutputFormats" to (1L shl 4))
                                    ) else emptyList()),
                            )
                        )
                    method == "SETUP" && path == "/setup" -> {
                        val timingPort = ((BinaryPlist.parse(body) as Map<*, *>)["timingPort"] as Long).toInt()
                        check(timingPort > 0)
                        task("CarPlay QA timing") {
                            DatagramSocket(0, local).use { timing ->
                                timing.soTimeout = 3000
                                val request = ByteArray(32)
                                request[0] = 0x80.toByte(); request[1] = 0xd2.toByte(); request[3] = 7
                                byteArrayOf(0x83.toByte(), 0xc1.toByte(), 0x17, 0xcc.toByte(), 0x12, 0x34, 0x56, 0x78).copyInto(request, 24)
                                timing.send(DatagramPacket(request, request.size, local, timingPort))
                                val reply = DatagramPacket(ByteArray(256), 256)
                                timing.receive(reply)
                                timingReply.complete(reply.length == 32 && reply.data[1] == 0xd3.toByte() &&
                                    reply.data.copyOfRange(8, 16).contentEquals(request.copyOfRange(24, 32)))
                            }
                        }
                        val setup = mutableMapOf("eventPort" to if (aac) events.localPort.toShort().toLong() else events.localPort.toLong())
                        if (aac) {
                            setup["keepAlivePort"] = idle.localPort.toShort().toLong()
                            task("CarPlay QA idle watchdog") { watchKeepalives(socket) }
                        }
                        BinaryPlist.encode(setup)
                    }
                    method == "SETUP" -> {
                        val d =
                            (((BinaryPlist.parse(body) as Map<*, *>)["streams"] as List<*>).single()
                                as Map<*, *>)
                        val id = d["streamConnectionID"] as Long
                        val port =
                            if (d["type"] == 110L) {
                                check(d["uuid"] == "screen")
                                check(usedScreenIds.add(id)) { "Screen stream reused an encryption nonce/key epoch" }
                                screenIds.add(id)
                                video.localPort
                            } else if (d["type"] == 101L) {
                                check(navigationMode == "on" && d["audioFormat"] == 1L shl 4 && d["spf"] == 352L)
                                voiceId.complete(id)
                                voice.localPort
                            } else {
                                check(d["audioFormat"] == if (aac) (1L shl 22) else 32768L)
                                check(d["spf"] == if (aac) 1024L else 352L)
                                audioId.complete(id)
                                mediaSetups.incrementAndGet()
                                audio.localPort
                            }
                        BinaryPlist.encode(
                            mapOf(
                                "streams" to
                                    listOf(buildMap {
                                        put("type", checkNotNull(d["type"]))
                                        put("dataPort", port.toLong())
                                        if (d["type"] != 110L && d["type"] != 101L) put("controlPort", audioControl.localPort.toLong())
                                    })
                            )
                        )
                    }
                    method == "RECORD" -> {
                        recordCalls.incrementAndGet()
                        record.countDown()
                        byteArrayOf()
                    }
                    method == "TEARDOWN" -> {
                        check(carMenu)
                        val streams = (BinaryPlist.parse(body) as Map<*, *>)["streams"] as List<*>
                        check(streams == listOf(mapOf("type" to 110L, "uuid" to "screen"))) { "Handoff must retire only the screen" }
                        screenTeardowns.incrementAndGet()
                        byteArrayOf()
                    }
                    path == "/command" -> {
                        val cmd = BinaryPlist.parse(body) as Map<*, *>
                        if (cmd["type"] == "hidSetInputMode") hidConfiguration.complete(true)
                        if (cmd["type"] == "modesChanged") {
                            val resources = (cmd["params"] as Map<*, *>)["resources"] as List<*>
                            if (carMenu && recordCalls.get() > 0) check(resources.size == 1)
                            resources.filterIsInstance<Map<*, *>>().firstOrNull { it["resourceID"] == 1L }?.let {
                                screenModes.add((it["entity"] as Number).toInt())
                            }
                        }
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

    /** A strict receiver ignores malformed packets and retires the RTSP session on inactivity. */
    private fun watchKeepalives(control: Socket) {
        var lastValid = System.nanoTime()
        val buffer = ByteArray(256)
        while (!control.isClosed && !idle.isClosed) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(lastValid + TimeUnit.SECONDS.toNanos(10) - System.nanoTime())
            if (remaining <= 0) {
                watchdogFailure.complete("Receiver closed RTSP after 10 s without a valid UDP keepalive")
                control.close()
                return
            }
            idle.soTimeout = remaining.coerceAtLeast(1L).toInt()
            val packet = DatagramPacket(buffer, buffer.size)
            try { idle.receive(packet) } catch (_: SocketTimeoutException) { continue }
            if (packet.length == 4 && (buffer[0].toInt() and 0xe0) == 0) {
                udpKeepalives.incrementAndGet()
                lastValid = System.nanoTime()
            }
        }
    }

    private fun videoReceiver() {
      do {
        val socket = receive(video)
        val data = DataInputStream(socket.getInputStream())
        val key =
            AirPlayStreamKeys.chachaStreamKey(peer.pvShared, checkNotNull(screenIds.poll(8, TimeUnit.SECONDS)))
        var csd = byteArrayOf()
        var counter = 0L
        var decodedScreen = false
        while (!socket.isClosed && (!captured.isDone || aac || carMenu)) {
            val head = try { ByteArray(128).also(data::readFully) } catch (e: EOFException) { if (carMenu) break else throw e }
            val buf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val body = ByteArray(buf.getInt(0)).also(data::readFully)
            val opcode = head[4].toInt()
            if (opcode == 2 || opcode == 5) {
                if (aac) {
                    if (opcode != 5) continue
                    check(buf.getFloat(124) == body.size.toFloat())
                    val stats = BinaryPlist.parse(body) as Map<*, *>
                    check(listOf("txUsageAvg", "encoderCurrentFPS", "sentFramesAvg", "queuedFramesAvg", "lossAvg").all { stats[it] is Number })
                } else check(opcode == 2 && body.isEmpty())
                heartbeats.incrementAndGet()
                continue
            }
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
            videoFrames.incrementAndGet()
            if (!decodedScreen) {
                File(context.cacheDir, "carplay-receiver.h264").writeBytes(csd + frame.toByteArray())
                val count = decode(csd, frame.toByteArray())
                captured.complete(count)
                decodedScreen = true
                decodedScreens.incrementAndGet()
            }
        }
      } while (carMenu && !life.isClosed)
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
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frame.size)
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
            val started = System.nanoTime()
            repeat(if (aac) mediaSeconds * 30 else 50) { n ->
                if (aac) waitUntil(started + n * 1_000_000_000L / 30)
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
                        session.sendVideo(b, 0)
                    }
                if (o >= 0) {
                    val b = ByteArray(info.size)
                    codec.getOutputBuffer(o)!!.get(b)
                    codec.releaseOutputBuffer(o, false)
                    if (b.isNotEmpty() && session.sendVideo(b, info.presentationTimeUs) && !aac) return
                }
            }
            check(aac && videoFrames.get() >= mediaSeconds * 20) { "Continuous video stopped: ${videoFrames.get()} frames" }
        } finally {
            codec.release()
        }
    }

    private fun waitUntil(deadline: Long) {
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            TimeUnit.NANOSECONDS.sleep(remaining)
        }
    }

    fun run(): Bundle {
        try {
            // The production AutoService owns this lease. This direct transport fixture must
            // exercise the same screen-off lifetime rather than suspending its synthetic source.
            ProjectionCpuLease.hold(context,life)
            task("CarPlay QA control", ::control)
            task("CarPlay QA screen", ::videoReceiver)
            task("CarPlay QA HID") {
                val s = receive(events)
                check(record.await(10, TimeUnit.SECONDS))
                val firstMessage =
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
                repeat(if (carMenu) 4 else if (aac) mediaSeconds else 1) { index ->
                val message = if (index == 0 || !carMenu) firstMessage
                    else BinaryPlist.encode(checkNotNull(uiRequests.poll(12, TimeUnit.SECONDS)))
                val sequence = 7 + index
                val request =
                    "POST /command ${if (aac) "HTTP/1.1" else "RTSP/1.0"}\r\nCSeq: $sequence\r\nContent-Length: ${message.size}\r\n\r\n"
                        .toByteArray() + message
                s.getOutputStream().write(cipher.encrypt(request))
                val d = DataInputStream(s.getInputStream())
                val a = d.readUnsignedByte()
                val b = d.readUnsignedByte()
                val packet = ByteArray(18 + a + (b shl 8))
                packet[0] = a.toByte()
                packet[1] = b.toByte()
                d.readFully(packet, 2, packet.size - 2)
                val reply = cipher.decrypt(packet).data.decodeToString()
                check(reply.startsWith("${if (aac) "HTTP/1.1" else "RTSP/1.0"} 200 OK\r\n")) {
                    "Receiver expected an event response, got a phone-originated request or wrong protocol"
                }
                check(reply.contains("CSeq: $sequence\r\n"))
                eventAcks.incrementAndGet()
                gesture.complete(true)
                if (aac) Thread.sleep(1000)
                }
                gesturesFinished.complete(true)
            }
            val ep =
                CarPlayEndpoint(
                    context.getSystemService(ConnectivityManager::class.java)!!.activeNetwork!!,
                    local,
                    ctrl.localPort,
                    "02:AA:11:BB:22:CC",
                    "test-" + UUID.randomUUID(),
                    peer.staticKey.publicKey,
                    com.hsd.universal.carplay.CarPlayDeviceInformation(UUID.randomUUID()),
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
            fun queued(sender: CarPlayAudio) = CarPlayQueuedAudio(sender) { threadErrors.add(it) }
                .also { life.own(it::close) }
            val mediaSender = queued(checkNotNull(s.audio))
            task("CarPlay QA audio sync receiver") {
                audioControl.soTimeout = 5000
                while (!audioFinished.isDone) {
                    val datagram = DatagramPacket(ByteArray(64), 64)
                    audioControl.receive(datagram)
                    val receivedAt = System.nanoTime()
                    val sync = ByteBuffer.wrap(datagram.data).order(ByteOrder.BIG_ENDIAN)
                    check(datagram.length == 20 && sync.get(1) == 0xd4.toByte())
                    check(sync.getShort(2).toInt() == 4) { "Invalid audio sync length" }
                    val playing = sync.getInt(4)
                    val source = sync.getInt(16)
                    check(source - playing == (if (aac) 44100 else 48000) / 4) { "Negotiated latency lost" }
                    val count = audioSyncPackets.incrementAndGet()
                    if (count == 1) {
                        check(source == 0)
                        val seconds = sync.getInt(8).toLong() and 0xffffffffL
                        val fraction = sync.getInt(12).toLong() and 0xffffffffL
                        val anchorNs = seconds * 1_000_000_000L + (fraction * 1_000_000_000L ushr 32)
                        val firstPlayNs = anchorNs + (0 - playing).toLong() * 1_000_000_000L / (if (aac) 44100 else 48000)
                        initialAudioSyncLeadUs.set((firstPlayNs - receivedAt) / 1000)
                        // The menu test starts its continuous source after this codec/crypto warmup.
                        // The existing media timing test still enforces cold-start playout latency.
                        if (!carMenu) check(firstPlayNs > receivedAt) { "First audio packet is already late at the receiver" }
                        firstAudioSync.complete(true)
                        if (!aac) return@task
                    }
                }
            }
            if (aac) task("CarPlay QA continuous video") {
                generateVideo(s)
                videoFinished.complete(true)
            } else generateVideo(s)
            audio.soTimeout = 5000
            var previousAudioSequence: Int? = null
            var previousAudioTimestamp: Long? = null
            var skippedSourceFrames = 0L
            val lateInputs = java.util.concurrent.atomic.AtomicInteger()
            val maxSourceAgeUs = java.util.concurrent.atomic.AtomicLong()
            fun receiveUnit(): ByteArray {
                check(threadErrors.isEmpty()) { "Receiver failed during live media: ${threadErrors.first()}" }
                val data = DatagramPacket(ByteArray(8192),8192)
                audio.receive(data)
                val p = data.data.copyOf(data.length)
                check((p[1].toInt() and 127) == if (aac) 102 else 100)
                val header = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
                val sequence = header.getShort(2).toInt() and 65535
                val timestamp = header.getInt(4).toLong() and 0xffffffffL
                previousAudioSequence?.let { check(sequence == (it + 1) and 65535) { "RTP packet sequence gap" } }
                previousAudioTimestamp?.let {
                    val samples = if (aac) 1024 else 352
                    val advance = (timestamp-it) and 0xffffffffL
                    if (mediaSeconds <= 30) check(advance == samples.toLong()) { "RTP sample clock gap" }
                    else {
                        check(advance >= samples && advance < 0x80000000L && advance % samples == 0L) { "RTP sample clock rewound or corrupted" }
                        skippedSourceFrames += advance-samples
                    }
                }
                previousAudioSequence = sequence; previousAudioTimestamp = timestamp
                return AirPlayCrypto.chachaOpen(AirPlayStreamKeys.chachaStreamKey(peer.pvShared, audioId.get()),
                    ByteArray(4) + p.takeLast(8).toByteArray(), p.copyOfRange(12, p.size-8), p.copyOfRange(4,12))
            }
            val verified = if (aac) {
                val targetPackets = (mediaSeconds * 44100 + 1023) / 1024
                task("CarPlay QA continuous audio") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val started = System.nanoTime()
                repeat(targetPackets) { n ->
                    waitUntil(started + n * 1024_000_000_000L / 44100)
                    val pcm = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(1024) { i ->
                        val sample = (3000 * kotlin.math.sin(2 * Math.PI * 440 * (n * 1024 + i) / 44100)).toInt().toShort()
                        pcm.putShort(sample); pcm.putShort(sample)
                    }
                    val pts = started / 1000 + n * 1024_000_000L / 44100
                    maxSourceAgeUs.accumulateAndGet(System.nanoTime()/1000-pts, ::maxOf)
                    if (!mediaSender.offer(pcm.array(),pts)) {
                        check(mediaSeconds > 30) { "Live source late at packet $n: ageUs=${System.nanoTime()/1000-pts}" }
                        lateInputs.incrementAndGet()
                    }
                }
                audioFinished.complete(true)
                }
                val units = ArrayList<ByteArray>()
                val lastUnits = ArrayDeque<ByteArray>()
                var packets = 0
                while (!audioFinished.isDone) {
                    val unit = receiveUnit()
                    if (units.size < 16) units.add(unit)
                    if (lastUnits.size == 16) lastUnits.removeFirst()
                    lastUnits.addLast(unit)
                    packets++
                    if (packets % 2584 == 0) AppLog.i("CarPlay QA audio progress: packets=$packets of $targetPackets", false)
                    if (packets >= targetPackets - 12) break // Encoder may retain final access units until close.
                }
                check(packets >= targetPackets - 20 - mediaSender.discardedPackets) { "Continuous audio stopped: $packets packets" }
                check(audioFinished.get(5, TimeUnit.SECONDS) && videoFinished.get(8, TimeUnit.SECONDS))
                check(skippedSourceFrames <= mediaSender.discardedPackets * 1024) { "Audio clock lost samples without a discarded source buffer" }
                if (mediaSeconds <= 30) check(mediaSender.discardedPackets == 0L)
                verify440Hz(units) + verify440Hz(lastUnits.toList())
            } else {
                val expected = ByteArray(352 * 4) { (it and 127).toByte() }
                val audioStartUs = System.nanoTime()/1000
                check(mediaSender.offer(expected,audioStartUs))
                val actual = receiveUnit()
                check(actual.size == expected.size && actual.indices.all { actual[it] == expected[it xor 1] })
                if (carMenu) {
                    // Initial encode/decode and JIT are not a running capture source. Start one
                    // now, then require every subsequent RTP sample through all handoff cycles.
                    val continuousStartUs = System.nanoTime()/1000
                    previousAudioTimestamp = null
                    val producing = java.util.concurrent.atomic.AtomicBoolean(true)
                    val sourceFinished = java.util.concurrent.atomic.AtomicBoolean(false)
                    val received = java.util.concurrent.atomic.AtomicInteger()
                    val produced = java.util.concurrent.atomic.AtomicInteger()
                    val source = task("CarPlay QA menu audio source") {
                        var n = 0L
                        try { while (producing.get()) {
                            val pts = continuousStartUs + n * 352_000_000L / 48000
                            waitUntil(pts * 1000)
                            check(mediaSender.offer(expected, pts)) { "Menu audio source became late" }
                            produced.incrementAndGet(); n++
                        } } finally { sourceFinished.set(true) }
                    }
                    val sink = task("CarPlay QA menu audio sink") {
                        while (!sourceFinished.get() || received.get() < produced.get()) {
                            val unit = receiveUnit()
                            check(unit.size == expected.size && unit.indices.all { unit[it] == expected[it xor 1] })
                            received.incrementAndGet()
                        }
                    }
                    fun await(what: String, condition: () -> Boolean) {
                        val end = System.nanoTime() + 8_000_000_000L
                        while (!condition()) {
                            check(threadErrors.isEmpty() && System.nanoTime() < end) { what }
                            Thread.sleep(10)
                        }
                    }
                    try {
                        check(screenModes.poll(2, TimeUnit.SECONDS) == 1)
                        await("Continuous source did not start") { received.get() >= 12 }
                        repeat(3) { cycle ->
                            s.requestCarMenu()
                            check(screenModes.poll(5, TimeUnit.SECONDS) == 0)
                            await("No screen TEARDOWN") { screenTeardowns.get() == cycle + 1 }
                            check(!s.screenProjected)
                            val before = received.get()
                            await("Audio stopped in native UI") { received.get() >= before + 24 }
                            // Include the parameterless request sent by many native CarPlay launchers.
                            uiRequests.add(if (cycle == 0) mapOf("type" to "requestUI")
                                else mapOf("type" to "requestUI", "params" to emptyMap<String, Any>()))
                            check(screenModes.poll(5, TimeUnit.SECONDS) == 1)
                            await("Screen did not return") { s.screenProjected }
                            generateVideo(s)
                            await("New stream has no decodable key frame") { decodedScreens.get() == cycle + 2 }
                        }
                    } finally {
                        producing.set(false); source.join(2000); sink.join(3000)
                    }
                    check(!source.isAlive && !sink.isAlive)
                    check(recordCalls.get() == 1 && mediaSetups.get() == 1 && usedScreenIds.size == 4)
                    check(mediaSender.discardedPackets == 0L && received.get() >= 72)
                }
                actual.size
            }
            check(input.get(4, TimeUnit.SECONDS) && gesture.get(4, TimeUnit.SECONDS))
            check(hidConfiguration.get(4, TimeUnit.SECONDS) && timingReply.get(4, TimeUnit.SECONDS))
            check(gesturesFinished.get(8, TimeUnit.SECONDS))
            check(firstAudioSync.get(5, TimeUnit.SECONDS))
            check(audioSyncPackets.get() >= if (aac) 20 else 1)
            navigationMode?.let { mode ->
                if (mode != "on") check(s.navigationAudio == null && !voiceId.isDone)
                else {
                    val sender = checkNotNull(s.navigationAudio)
                    check(sender.channels == 1 && sender.rate == 16000)
                    voice.soTimeout=3000
                    val plain = ByteArray(352*2) { (it and 127).toByte() }
                    val voiceSender = queued(sender)
                    check(voiceSender.offer(plain.copyOfRange(0,176*2),System.nanoTime()/1000))
                    voiceSender.endSegment()
                    val datagram = DatagramPacket(ByteArray(4096),4096)
                    voice.receive(datagram)
                    val packet = datagram.data.copyOf(datagram.length)
                    check(packet[1].toInt() and 127 == 101)
                    val decoded = AirPlayCrypto.chachaOpen(AirPlayStreamKeys.chachaStreamKey(peer.pvShared,voiceId.get()),
                        ByteArray(4)+packet.takeLast(8).toByteArray(),packet.copyOfRange(12,packet.size-8),packet.copyOfRange(4,12))
                    check(decoded.size == 352*2)
                    check((0 until 176*2).all { decoded[it] == plain[it xor 1] })
                    check(decoded.drop(176*2).all { it == 0.toByte() })
                    check(ByteBuffer.wrap(packet).getInt(4)==0) // independent RTP clock, not the media clock
                }
            }
            check(threadErrors.isEmpty()) { "Receiver failed during live media: ${threadErrors.first()}" }
            return Bundle().apply {
                if (carMenu) putString("CarPlay_car_menu", "3 native UI/return cycles; 4 independent encrypted screen streams decoded; continuous PCM sequence and clock; one audio SETUP and RECORD")
                if (carMenu) putLong("CarPlay_warmup_sync_lead_us", initialAudioSyncLeadUs.get())
                navigationMode?.let { putString("CarPlay_navigation_$it", if (it == "on")
                    "Independent mono RTP and complete final PCM frame checked through the bounded sender queue"
                    else "No alternate output negotiated; mixed route selected") }
                if (aac) {
                    putInt("CarPlay_source_rejected_packets",lateInputs.get())
                    putLong("CarPlay_source_max_age_us",maxSourceAgeUs.get())
                    putLong("CarPlay_source_discarded_packets",mediaSender.discardedPackets)
                    putLong("CarPlay_skipped_source_frames",skippedSourceFrames)
                    CompletableFuture.anyOf(watchdogFailure, delayedFeedback).get(24, TimeUnit.SECONDS)
                    check(!watchdogFailure.isDone) { watchdogFailure.join() }
                    val received = delayedFeedback.get()
                    check(udpKeepalives.get() >= 10)
                    putInt("CarPlay_accepted_UDP_keepalives", udpKeepalives.get())
                    check(received >= 10 && threadErrors.none { it is SocketTimeoutException }) {
                        "Media heartbeat stalled during optional feedback: $received"
                    }
                    putInt("CarPlay_heartbeats_during_delayed_feedback",received)
                    putInt("CarPlay_continuous_video_frames",videoFrames.get())
                    putInt("CarPlay_continuous_event_acknowledgements",eventAcks.get())
                    putInt("CarPlay_continuous_media_seconds",mediaSeconds)
                }
                putInt("CarPlay_decoded_YUV_bytes", captured.get(8, TimeUnit.SECONDS))
                putInt("CarPlay_valid_audio_sync_packets", audioSyncPackets.get())
                putInt(if (aac) "CarPlay_440Hz_decoded_AAC_frames" else "CarPlay_authenticated_PCM_bytes", verified)
                putString("CarPlay_HID", "encrypted event and CSeq acknowledgement")
                putString("CarPlay_channels", "phone commands on control only; events contain matching responses; timing challenge echoed")
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
