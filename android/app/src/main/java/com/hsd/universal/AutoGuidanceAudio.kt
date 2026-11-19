package com.hsd.universal

import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import com.hsd.universal.dex.ProjectionPcmConverter
import net.prototux.aare.aap.channels.AudioChannel

/** The receiver's GUIDANCE stream has its own codec, queue and playback session. */
internal class AutoGuidanceAudio(val channel: AudioChannel,
    private val ended: () -> Unit) : AutoCloseable {
    private var converter: ProjectionPcmConverter? = null
    private var encoder: AutoAudioEncoder? = null
    @Volatile private var allowed = false
    @Volatile var active = false
        private set
    @Volatile private var failed = false
    val writtenPackets = java.util.concurrent.atomic.AtomicLong()
    private val packetWritten: () -> Unit = { writtenPackets.incrementAndGet(); Unit }
    val available get() = channel.ready && channel.config != null && !failed
    private val queue = AutoPcmQueue(synchronize = false) { data, pts, current ->
        val config = channel.config
        if (config != null && !failed && allowed && current()) try {
            channel.startPlayback()
            val codec = channel.service.mediaSinkService.availableType
            if (codec == MediaCodecType.MEDIA_CODEC_AUDIO_PCM) send(data, pts, current)
            else {
                val aac = encoder ?: AutoAudioEncoder(channels = config.numberOfChannels).also { encoder = it }
                aac.offer(data, 10, pts, config.samplingRate,
                    codec == MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_ADTS) { bytes, timestamp ->
                    send(bytes, timestamp, current)
                }
            }
        } catch (error: Exception) {
            failed = true
            AppLog.e("AAP GUIDANCE encoder failed", error)
        }
    }
    private fun send(data: ByteArray, timestamp: Long, current: () -> Boolean) =
        channel.sendPcm(data, 10, data.size - 10, timestamp, timestamp + AutoPcmQueue.DEFAULT_MAX_LATENESS_US,
            current = { allowed && current() }, onSent = packetWritten)
    @Synchronized fun focus(granted: Boolean) {
        if (allowed == granted) return
        allowed = granted
        if (allowed) queue.setPlaying(true)
        else {
            queue.setPlaying(false); channel.stopPlayback()
            queue.resetCodec { encoder?.close(); encoder = null }
        }
    }
    @Synchronized fun offer(pcm: ByteArray, ptsUs: Long, speaking: Boolean): Boolean {
        if (!speaking) {
            val wasActive = active
            active = false
            if (wasActive && !allowed) {
                queue.setPlaying(false)
                ended()
            } else if (wasActive) queue.afterPending { current ->
                try {
                    val config = channel.config
                    if (config != null && current()) encoder?.finish(config.samplingRate,
                        channel.service.mediaSinkService.availableType == MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_ADTS) { bytes, timestamp ->
                        send(bytes, timestamp, current)
                    }
                } catch (error: Exception) {
                    failed = true
                    AppLog.e("AAP GUIDANCE final packet failed", error)
                } finally {
                    encoder?.close(); encoder = null
                    if (current()) { channel.stopPlayback(); ended() }
                }
            }
            return true
        }
        if (!available) return false
        val config = checkNotNull(channel.config)
        if (!active) {
            converter = ProjectionPcmConverter(config.samplingRate, config.numberOfChannels)
            active = true
            if (allowed) queue.setPlaying(true)
            AppLog.i("AAP GUIDANCE: ${config.samplingRate} Hz ${config.numberOfChannels} channels", false)
        }
        val converted = checkNotNull(converter).convert(pcm)
        if (converted.isEmpty()) return true
        val packet = ByteArray(converted.size + 10)
        converted.copyInto(packet, 10)
        return queue.enqueue(packet, ptsUs)
    }
    override fun close() { allowed = false; queue.close { encoder?.close(); encoder = null } }
    companion object {
        fun supports(config: AudioConfiguration) = config.numberOfBits == 16 &&
            config.numberOfChannels in 1..2 && config.samplingRate in listOf(16000, 24000, 32000, 44100, 48000)
    }
}
