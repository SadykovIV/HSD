package com.hsd.universal.hicar

import com.hsd.universal.ProjectionResources
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Independent receiver state machines from the pinned MIT implementation above. */
internal class HiCarReceiverFixture(private val key: ByteArray,
    val address: InetAddress = InetAddress.getLoopbackAddress(), private val phone: InetAddress = address) : AutoCloseable {
    val life = ProjectionResources()
    private fun listener() = ServerSocket(0, 4, address).also { life.own(it::close) }
    private val data = listener()
    private val control = listener()
    private val audio = DatagramSocket(InetSocketAddress(address, 0)).also { life.own(it::close) }
    val port get() = data.localPort
    val videos = CopyOnWriteArrayList<ByteArray>()
    val audioFrames = CopyOnWriteArrayList<ByteArray>()
    val errors = CopyOnWriteArrayList<Throwable>()
    val touchSent = AtomicInteger()
    private fun work(name: String, action: () -> Unit) = thread(name = "reference HiCar $name", isDaemon = true) {
        try { action() } catch (e: Exception) { if (!life.isClosed) errors.add(e) }
    }
    fun connect(port: Int) = Socket().also {
        life.own(it::close); it.bind(InetSocketAddress(address, 0)); it.connect(InetSocketAddress(phone, port), 5000)
        it.soTimeout = 10_000; it.tcpNoDelay = true
    }
    init {
        work("entry") {
            val socket = data.accept().also { life.own(it::close) }
            val decoder = RefDmsdpWire.Decoder(); val buffer = ByteArray(4096)
            while (!life.isClosed) {
                val n = socket.getInputStream().read(buffer); if (n < 0) break
                for (packet in decoder.feed(buffer, n)) socket.getOutputStream().write(RefDmsdpWire.controlOffer(packet, control.localPort))
            }
        }
        work("DMSDP") {
            val socket = control.accept().also { life.own(it::close) }
            val speaker = RefSpeakerControl(object : RefSpeakerControl.Output {
                override fun port() = audio.localPort
                override fun begin() = Unit
                override fun ready(port: Int) = Unit
                override fun format(rate: Int, channels: Int) { check(rate == 48000 && channels == 2) }
                override fun stop() = Unit
                override fun log(text: String) = Unit
            })
            val business = RefDeviceBusiness("22".repeat(32), "12345678", address.hostAddress, phone.hostAddress, { },
                object : RefDeviceBusiness.Display {
                    override fun ready() = true
                    override fun endpoint(port: Int) { work("HiSight") { video(port) } }
                }, { true })
            val session = RefControlSession(key, { }, business, speaker)
            life.own(session::close)
            val output = socket.getOutputStream()
            val lock = Any()
            output.write(session.initial())
            val buffer = ByteArray(4096)
            while (!life.isClosed) {
                val n = socket.getInputStream().read(buffer); if (n < 0) break
                for (reply in session.feed(buffer, n)) synchronized(lock) { output.write(reply); output.flush() }
            }
        }
        work("audio") {
            val decoder = RefAudioMedia()
            RefSessionCipher(key, 65536).use { cipher ->
                val buffer = ByteArray(65536)
                while (!life.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size); audio.receive(packet)
                    val rtp = RefAudioMedia.decrypt(packet.data.copyOf(packet.length), cipher)
                    decoder.receive(rtp).forEach { audioFrames.add(it.data.clone()) }
                }
            }
        }
    }
    private fun video(port: Int) {
        val socket = connect(port)
        RefHiSightWire(key, { }, true).use { session ->
            val output = socket.getOutputStream()
            output.write(session.initial())
            val buffer = ByteArray(4096)
            var mediaOpened = false
            while (!life.isClosed) {
                val n = socket.getInputStream().read(buffer); if (n < 0) break
                val replies = session.feed(buffer, n)
                if (!mediaOpened && session.mediaPort() != 0) {
                    mediaOpened = true
                    val media = connect(session.mediaPort())
                    val input = connect(session.inputPort())
                    work("video data") {
                        val stream = DataInputStream(media.getInputStream())
                        RefHiSightMedia(key).use { decoder ->
                            while (!life.isClosed) {
                                val high = stream.read()
                                if (high < 0) break // EOF between complete video records is a normal disconnect.
                                val size = (high shl 24) or (stream.readUnsignedByte() shl 16) or stream.readUnsignedShort()
                                check(size in 1..2*1024*1024)
                                val packet = ByteArray(size).also(stream::readFully)
                                decoder.packet(packet) { _, _, bytes -> videos.add(bytes.clone()) }
                            }
                        }
                    }
                    replies.add(session.play())
                    work("input") {
                        Thread.sleep(200)
                        RefUiInput(key).use { encoder ->
                            for (action in 0..2) {
                                input.getOutputStream().write(encoder.encode(RefUiInput.Event.touch(action, 200, 120), 200))
                                touchSent.incrementAndGet()
                            }
                        }
                    }
                }
                for (reply in replies) { output.write(reply); output.flush() }
            }
        }
    }
    fun assertHealthy() { if (errors.isNotEmpty()) throw AssertionError("Independent receiver failed", errors[0]) }
    override fun close() = life.close()
}
