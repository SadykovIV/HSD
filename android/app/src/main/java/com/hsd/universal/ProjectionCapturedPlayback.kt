package com.hsd.universal

import com.hsd.universal.aa.CarlifeAudio
import com.hsd.universal.aa.EqLive
import com.hsd.universal.aa.Equalizer
import com.hsd.universal.dex.CapturedPlaybackSink
import com.hsd.universal.dex.ProjectionPcmConverter

/** Desktop audio must not wait on the legacy bridge's video/desktop monitor. */
internal class ProjectionCapturedPlayback(private val output: DesktopProjectionOutput) : CapturedPlaybackSink {
    private val equalizer = Equalizer(48000, 2)
    private var converter: ProjectionPcmConverter? = null
    override val separateNavigationAvailable get() = output.navigationAvailable()
    override fun start(resume: Boolean) {
        converter = output.audioRate()?.let { ProjectionPcmConverter(it, 2) }
        output.audio(if (resume) CarlifeAudio.MEDIA_RESUME else CarlifeAudio.MEDIA_INIT, byteArrayOf(), 0)
    }
    override fun media(pcm: ByteArray, ptsUs: Long): Boolean {
        val rate = output.audioRate() ?: return false
        if (converter?.rate != rate) converter = ProjectionPcmConverter(rate, 2)
        equalizer.update(EqLive.settings)
        equalizer.process(pcm)
        val formatted = checkNotNull(converter).convert(pcm)
        return formatted.isEmpty() || output.audio(CarlifeAudio.MEDIA_DATA, formatted, ptsUs)
    }
    override fun navigation(pcm: ByteArray, ptsUs: Long, active: Boolean) = output.navigation(pcm, ptsUs, active)
    override fun pause() {
        output.navigation(byteArrayOf(), 0, false)
        output.audio(CarlifeAudio.MEDIA_PAUSE, byteArrayOf(), 0)
    }
    override fun stop() {
        output.navigation(byteArrayOf(), 0, false)
        output.audio(CarlifeAudio.MEDIA_STOP, byteArrayOf(), 0)
    }
}
