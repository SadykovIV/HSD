package net.prototux.aare.aap.channels

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.MediaSinkServiceOuterClass.MediaSinkService
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import net.prototux.aare.aap.*
import net.prototux.aare.aap.cert.SelfSignedCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class AudioReceiverWindowTest {
    private val transport = object : Transport {
        override val name = "audio flow test"
        override val input = ByteArrayInputStream(byteArrayOf())
        override val output = ByteArrayOutputStream()
        override fun close() {}
    }
    private val callbacks = object : AapSession.Callbacks {
        override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse) = emptyList<ServiceChannel>()
        override fun onClosed(session: AapSession, reason: String, error: Throwable?) {}
    }
    private val channel = AudioChannel(Service.newBuilder().setId(3).setMediaSinkService(
        MediaSinkService.newBuilder().setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
            .addAudioConfigs(AudioConfiguration.getDefaultInstance())).build())

    private fun fillWindow() {
        val keys = SelfSignedCertificate.generate("Audio credit test")
        channel.attach(AapSession(transport, PhoneIdentity(keys.certificate, keys.keyPair.private),
            PhoneDescription("test", "audio"), callbacks))
        val config = Config.newBuilder().setStatus(Config.Status.STATUS_READY).setMaxUnacked(1).build()
        channel.onMessage(AapMessage(3, false, true, MediaMsg.CONFIG, config.toByteArray()))
        channel.startPlayback()
        repeat(1 + AudioChannel.SLACK) { assertTrue(channel.sendPcm(ByteArray(14), 10, 4, it.toLong())) }
        assertFalse(channel.hasRoom)
    }

    private fun pendingPacket(): FutureTask<Boolean> {
        val entered = CountDownLatch(1)
        val future = FutureTask {
            val deadline = System.nanoTime() / 1000 + 2_000_000
            channel.sendPcm(ByteArray(14), 10, 4, 20_000, deadline, { entered.countDown(); true })
        }
        Thread(future).apply { isDaemon = true; start() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        return future
    }

    private fun ack(sessionId: Int) {
        val ack = Ack.newBuilder().setSessionId(sessionId).setAck(1).build()
        channel.onMessage(AapMessage(3, false, true, MediaMsg.ACK, ack.toByteArray()))
    }

    @Test fun aBurstAckReleasesThePendingPacketInItsOwnSession() {
        fillWindow()
        val packet = pendingPacket()
        ack(42)
        assertFalse(packet.isDone)
        ack(1)
        assertTrue(packet.get(1, TimeUnit.SECONDS))
        assertEquals(0L, channel.droppedChunks)
    }

    @Test fun aStoppedPacketCannotConsumeCreditFromTheNextStream() {
        fillWindow()
        val packet = pendingPacket()
        channel.stopPlayback()
        channel.startPlayback()
        assertFalse(packet.get(1, TimeUnit.SECONDS))
        assertEquals(0L, channel.droppedChunks)
    }

    @Test fun aMissingAckExpiresOnceAtItsPlayoutDeadline() {
        fillWindow()
        val now = System.nanoTime() / 1000
        assertFalse(channel.sendPcm(ByteArray(14), 10, 4, now, now + 5_000, { true }))
        assertEquals(1L, channel.droppedChunks)
    }
}
