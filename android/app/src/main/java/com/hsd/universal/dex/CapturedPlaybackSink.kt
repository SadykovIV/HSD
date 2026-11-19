package com.hsd.universal.dex

import com.hsd.universal.aa.AaBridgeCore
import com.hsd.universal.aa.AapMessages
import com.hsd.universal.aa.MusicResampler

/** Capture owns source timing/volume; a protocol adapter owns formats and stream lifecycle. */
interface CapturedPlaybackSink {
    val separateNavigationAvailable: Boolean
    fun start(resume: Boolean)
    fun media(pcm: ByteArray, ptsUs: Long): Boolean
    fun navigation(pcm: ByteArray, ptsUs: Long, active: Boolean): Boolean
    fun pause()
    fun stop()
}

/** Stateful 48 kHz stereo PCM16 conversion. Never reset at ordinary read boundaries. */
internal class ProjectionPcmConverter(val rate: Int, val channels: Int) {
    init { require(rate in 1..48000 && channels in 1..2) }
    private val resampler = if (rate == 48000) null else MusicResampler(rate)
    fun convert(pcm: ByteArray): ByteArray {
        require(pcm.size % 4 == 0)
        val stereo = resampler?.process(pcm) ?: pcm
        if (channels == 2) return stereo
        return ByteArray(stereo.size / 2).also { out ->
            for (i in stereo.indices step 4) {
                fun sample(at: Int) = ((stereo[at].toInt() and 255) or (stereo[at + 1].toInt() shl 8)).toShort().toInt()
                val value = (sample(i) + sample(i + 2)) / 2
                out[i / 2] = value.toByte(); out[i / 2 + 1] = (value shr 8).toByte()
            }
        }
    }
    fun reset() { resampler?.reset() }
}

internal data class NavigationPlaybackState(val active: Boolean, val changedAtUs: Long)

internal fun navigationPlaybackActive(tracks: List<PlaybackActivity>, stems: List<PlaybackStem>): Boolean? {
    var unknown = false
    for (track in tracks) {
        if (track.active == false) continue
        for (stem in stems.filter { it.guidance && track.usage in it.usages }) {
            val matches = track.uid?.let { stem.matches(track.usage, it) }
                ?: if (stem.includeUids.isEmpty() && stem.excludeUids.isEmpty()) true else null
            if (matches == true && track.active == true) return true
            if (matches != false) unknown = true
        }
    }
    return if (unknown) null else false
}

/** Bridge brief speech pauses, drain the capture tail, and release even a permanently open silent player. */
internal class NavigationPlaybackGate {
    private var playing = false
    private var lastSignalEndUs = 0L
    fun update(pcm: ByteArray, ptsUs: Long, source: NavigationPlaybackState?): Boolean {
        val signal = DesktopPcmFrames.peak(pcm) > 0
        if (signal) { playing = true; lastSignalEndUs = ptsUs + pcm.size / 4 * 1_000_000L / 48000 }
        if (playing && !signal) {
            val ended = when {
                source == null -> ptsUs >= lastSignalEndUs + 500_000 // callback unavailable: bounded silence tail
                source.active -> ptsUs >= lastSignalEndUs + 500_000
                else -> ptsUs >= maxOf(lastSignalEndUs, source.changedAtUs)
            }
            if (ended) playing = false
        }
        return playing
    }
    fun reset() { playing = false; lastSignalEndUs = 0 }
}

internal class CarlifeCapturedPlaybackSink(private val core: AaBridgeCore) : CapturedPlaybackSink {
    private val speech = ProjectionPcmConverter(16000, 1)
    private var speaking = false
    override val separateNavigationAvailable get() = core.audioViaUsb
    override fun start(resume: Boolean) {
        if (resume) core.onCapturedAudioResume() else core.onAudioStart(DesktopAudioPolicy.MEDIA_CHANNEL)
    }
    override fun media(pcm: ByteArray, ptsUs: Long): Boolean {
        core.onAudioData(DesktopAudioPolicy.MEDIA_CHANNEL, pcm, ptsUs)
        return true
    }
    override fun navigation(pcm: ByteArray, ptsUs: Long, active: Boolean): Boolean {
        if (active && !speaking) { speech.reset(); core.onAudioStart(AapMessages.CH_AUDIO_SPEECH) }
        if (active) core.onAudioData(AapMessages.CH_AUDIO_SPEECH, speech.convert(pcm), ptsUs)
        if (!active && speaking) core.onAudioStop(AapMessages.CH_AUDIO_SPEECH)
        speaking = active
        return true
    }
    override fun pause() {
        if (speaking) core.onAudioStop(AapMessages.CH_AUDIO_SPEECH)
        speaking = false
        core.onCapturedAudioPause()
    }
    override fun stop() {
        if (speaking) core.onAudioStop(AapMessages.CH_AUDIO_SPEECH)
        speaking = false
        core.onAudioStop(DesktopAudioPolicy.MEDIA_CHANNEL)
    }
}
