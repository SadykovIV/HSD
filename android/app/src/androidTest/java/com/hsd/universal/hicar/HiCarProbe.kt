package com.hsd.universal.hicar

import android.app.Instrumentation
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import com.hsd.universal.AutoAudioEncoder
import com.hsd.universal.ProjectionResources
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.sin

internal fun checkHiCar(test: Instrumentation): Bundle {
    val identity = HiCarAuthentication.initialize(test.targetContext)
    fun normalized(data: ByteArray) = data.toString(Charsets.UTF_8)
        .replace(Regex("\"(peerUserType|keyLength)\":\"(0|1|16|32)\""), "\"$1\":$2").toByteArray()
    val left = HiCarNativeAuth.create("11".repeat(32), "22".repeat(32), "012345", 1)
    val right = HiCarNativeAuth.create("22".repeat(32), "11".repeat(32), "987654", 2)
    check(left != 0L && right != 0L)
    try {
        check(HiCarNativeAuth.sessionKey(left) == null && HiCarNativeAuth.sessionKey(right) == null)
        var packets = HiCarNativeAuth.step(left, null, 32)
        var target = right; var rejected = false
        repeat(24) {
            if (!rejected && packets.size > 1) {
                check(packets.size == 2)
                packets = HiCarNativeAuth.step(target, normalized(packets[1]), 32)
                rejected = ByteBuffer.wrap(packets[0]).order(ByteOrder.nativeOrder()).asIntBuffer()[1] == 1
                target = if (target == right) left else right
            }
        }
        check(rejected && HiCarNativeAuth.sessionKey(left) == null && HiCarNativeAuth.sessionKey(right) == null) { "Wrong PIN was not rejected" }
    } finally { HiCarNativeAuth.close(left); HiCarNativeAuth.close(right) }
    fun pair(peer: String, wired: Boolean, pin: () -> String): ByteArray {
        val peerKey = CompletableFuture<ByteArray>()
        val loopback = java.net.InetAddress.getByName("127.0.0.1")
        val listener = ServerSocket(0, 1, loopback)
        val receiver = thread(name = "HiCar reference auth", isDaemon = true) {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 10_000
                    RefAuthBusiness(peer, "012345", "12345678", object : RefAuthBusiness.Events {
                        override fun log(s: String) { android.util.Log.i("HiCarFixture", s) }
                        override fun authenticated(key: ByteArray) { peerKey.complete(key.clone()) }
                    }, wired).use { auth ->
                        val buffer = ByteArray(4096)
                        while (!auth.done()) {
                            val n = socket.getInputStream().read(buffer)
                            check(n > 0)
                            auth.feed(buffer, n, socket.getOutputStream())
                        }
                    }
                }
            } catch (error: Exception) { peerKey.completeExceptionally(error) }
        }
        try {
            val key = Socket(loopback, listener.localPort).use { socket ->
                socket.soTimeout = 10_000
                HiCarAuthentication(identity, wired).use {
                    it.authenticate(socket.getInputStream(), socket.getOutputStream(), pin)
                }
            }
            val remote = peerKey.get(10, TimeUnit.SECONDS)
            try { check(key.contentEquals(remote)) { "HiChain session keys differ" } }
            finally { remote.fill(0) }
            return key
        } finally {
            listener.close()
            receiver.join(1000)
        }
    }
    val pairedPeer = "55".repeat(32)
    val key = pair(pairedPeer, wired = false) { "012345" }
    // STS needs independent keystores for the two long-term identities. That case
    // is exercised with the receiver in a separate process rather than in this probe.
    val wiredKey = pair("33".repeat(32), wired = true) { error("Wired PIN should arrive in the VW envelope") }
    check(!key.contentEquals(wiredKey)) { "New receiver has the same session key" }
    wiredKey.fill(0)
    val result = Bundle().apply {
        putString("hicar_auth", "PAKE verified, wrong PIN rejected, VW USB PIN envelope")
    }
    HiCarReceiverFixture(key).use { receiver ->
        ProjectionResources().use { life ->
            val errors = CopyOnWriteArrayList<Exception>(); val inputs = CopyOnWriteArrayList<HiCarMedia.Input>()
            val sender = HiCarSender(life, receiver.address, receiver.address, identity, key.clone(), { port ->
                Socket().also { life.own(it::close); it.connect(InetSocketAddress(receiver.address, port), 5000); it.soTimeout = 15000 }
            }, { inputs.add(it) }, { errors.add(it) })
            val mode = sender.start(receiver.port)
            val deadline = System.nanoTime() + 10_000_000_000L
            while ((!sender.audioReady || inputs.size < 3) && System.nanoTime() < deadline) Thread.sleep(10)
            check(sender.audioReady && inputs.size == 3) { "HiCar services not ready: $errors" }
            val units = encodeVideo(mode.width, mode.height)
            for ((index, bytes) in units.withIndex()) check(sender.video(bytes, 1_000_000L + index * 33333))
            sendProbeAudio(sender, 1_000_000L)
            while ((receiver.videos.size < units.size || receiver.audioFrames.size < 46) && System.nanoTime() < deadline) Thread.sleep(10)
            receiver.assertHealthy(); check(errors.isEmpty()) { "Sender failed: $errors" }
            check(receiver.videos.size == units.size && receiver.audioFrames.size >= 46)
            val pictures = decodeVideo(receiver.videos.toList(), mode.width, mode.height)
            val samples = decodeAudio(receiver.audioFrames.toList())
            check(pictures >= 8 && samples > 40000)
            result.putString("hicar_media", "independent receiver: $pictures decoded H.264 pictures; $samples decoded PCM samples with 440 Hz tone; ${inputs.size} UIBC events")
        }
    }
    key.fill(0)
    return result
}

private fun encodeVideo(width: Int, height: Int): List<ByteArray> {
    val codec = MediaCodec.createEncoderByType("video/avc")
    val frames = arrayListOf<ByteArray>()
    try {
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, 30); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val info = MediaCodec.BufferInfo(); var queued = 0; var ended = false
        val deadline = System.nanoTime() + 10_000_000_000
        while (!ended && System.nanoTime() < deadline) {
            if (queued <= 8) {
                val id = codec.dequeueInputBuffer(5000)
                if (id >= 0) {
                    val size = if (queued == 8) 0 else width * height * 3 / 2
                    if (size != 0) codec.getInputBuffer(id)!!.put(ByteArray(size) { if (it < width*height) (50+queued*15).toByte() else 128.toByte() })
                    codec.queueInputBuffer(id, 0, size, queued*33333L, if (queued == 8) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0); queued++
                }
            }
            val id = codec.dequeueOutputBuffer(info, 5000)
            if (id >= 0) {
                if (info.size > 0) frames.add(ByteArray(info.size).also { bytes -> codec.getOutputBuffer(id)!!.apply { position(info.offset); get(bytes) } })
                ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(id, false)
            }
        }
        check(ended && frames.size >= 8)
    } finally { runCatching { codec.stop() }; codec.release() }
    return frames
}

/** Same production NCM discovery and sender, with synthetic media and no desktop permissions. */
internal fun checkHiCarCable(test: Instrumentation): Bundle {
    val context = test.targetContext
    val inputs = CopyOnWriteArrayList<HiCarMedia.Input>()
    val errors = CopyOnWriteArrayList<Exception>()
    ProjectionResources().use { life ->
        val endpoint = HiCarBootstrap(context, life, { android.util.Log.i("HiCarCableQA", it) }, { errors.add(it) })
            .connect(wired = true)
        val link = endpoint.link
        val sender = HiCarSender(life, link.own, link.peer, endpoint.identity, endpoint.key.clone(), link::connect,
            { inputs.add(it) }, { errors.add(it) }, link::bind)
        val mode = sender.start(endpoint.dataPort)
        val deadline = System.nanoTime() + 10_000_000_000L
        while ((!sender.audioReady || inputs.size < 3) && System.nanoTime() < deadline) Thread.sleep(10)
        check(sender.audioReady && inputs.map { it.action }.containsAll(listOf(0, 1, 2))) { "USB speaker or UIBC incomplete: $errors" }
        val frames = encodeVideo(mode.width, mode.height)
        val base = System.nanoTime() / 1000
        frames.forEachIndexed { index, bytes -> check(sender.video(bytes, base + index * 33333)) }
        sendProbeAudio(sender, base, paced = true)
        Thread.sleep(650)
        check(errors.isEmpty()) { "USB sender failed: $errors" }
        return Bundle().apply {
            putString("hicar_cable", "NCM DNS-SD, VW PIN, ${frames.size} H.264 units, AAC tone, ${inputs.size} UIBC events")
        }
    }
}

private fun sendProbeAudio(sender: HiCarSender, base: Long, paced: Boolean = false) {
    AutoAudioEncoder(prefix = 0).use { encoder ->
        repeat(100) { packet ->
            val pcm = ByteBuffer.allocate(480 * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(480) { sample ->
                val value = (5000 * sin(2 * Math.PI * 440 * (packet * 480 + sample) / 48000)).toInt().toShort()
                pcm.putShort(value).putShort(value)
            }
            encoder.offer(pcm.array(), 0, base + packet * 10_000, 48000, true, sender::audio)
            if (paced) Thread.sleep(10)
        }
        encoder.finish(48000, true, sender::audio)
    }
}

private fun decodeVideo(units: List<ByteArray>, width: Int, height: Int): Int {
    val info = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
        .first { !it.isEncoder && it.isSoftwareOnly && "video/avc" in it.supportedTypes }
    val codec = MediaCodec.createByCodecName(info.name); var pictures = 0
    try {
        codec.configure(MediaFormat.createVideoFormat("video/avc", width, height), null, null, 0); codec.start()
        drain(codec, units, 33333L) { bytes -> if (bytes.isNotEmpty()) pictures++ }
    } finally { runCatching { codec.stop() }; codec.release() }
    return pictures
}
private fun decodeAudio(units: List<ByteArray>): Int {
    val codec = MediaCodec.createDecoderByType("audio/mp4a-latm"); val samples = arrayListOf<Short>()
    try {
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2).apply { setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x11, 0x90.toByte()))) }
        codec.configure(format, null, null, 0); codec.start()
        drain(codec, units, 1024_000_000L / 48000) { data ->
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            while (b.remaining() >= 4) { samples.add(b.short); b.short }
        }
    } finally { runCatching { codec.stop() }; codec.release() }
    fun amplitude(hz: Int): Double {
        val slice = samples.drop(5000).take(24000)
        var re = 0.0; var im = 0.0
        slice.forEachIndexed { i, x -> val phase=2*Math.PI*hz*i/48000; re+=x*kotlin.math.cos(phase); im+=x*sin(phase) }
        return 2*kotlin.math.hypot(re,im)/slice.size
    }
    check(amplitude(440) > 3000 && amplitude(440) > amplitude(700)*20) { "AAC did not preserve the synthetic tone" }
    return samples.size
}
private fun drain(codec: MediaCodec, units: List<ByteArray>, spacing: Long, output: (ByteArray) -> Unit) {
    var queued = 0; var ended = false; val info=MediaCodec.BufferInfo(); val deadline=System.nanoTime()+10_000_000_000L
    while (!ended && System.nanoTime()<deadline) {
        if (queued <= units.size) {
            val id=codec.dequeueInputBuffer(5000)
            if (id>=0) {
                val data=units.getOrNull(queued); if(data!=null) codec.getInputBuffer(id)!!.put(data)
                codec.queueInputBuffer(id,0,data?.size ?: 0,queued*spacing,if(data==null) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0); queued++
            }
        }
        val id=codec.dequeueOutputBuffer(info,5000)
        if(id>=0) {
            if(info.size>0) output(ByteArray(info.size).also { b -> codec.getOutputBuffer(id)!!.apply { position(info.offset); get(b) } })
            ended=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0; codec.releaseOutputBuffer(id,false)
        }
    }
    check(ended) { "Media decoder stalled" }
}
