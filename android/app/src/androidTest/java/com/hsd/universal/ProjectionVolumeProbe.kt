package com.hsd.universal

import android.app.Instrumentation
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.hsd.universal.aa.AaBridgeCore
import com.hsd.universal.aa.CarlifeAudio
import com.hsd.universal.aa.CarlifeLink
import com.hsd.universal.dex.DesktopAudioCapture
import com.hsd.universal.dex.DesktopAudioState
import com.hsd.universal.dex.DexPrefs
import java.io.ByteArrayOutputStream

/** Two simultaneous real AudioTracks -> native disjoint policy -> common projection PCM. */
internal fun checkProjectionVolumes(test: Instrumentation): String {
    val context = test.targetContext
    val prefs = DexPrefs(context)
    val oldMusic = prefs.musicVolume; val oldNavigation = prefs.navigationVolume; val oldApps = prefs.navigationAudioApps
    val bytes = ByteArrayOutputStream()
    val futureUs = java.util.concurrent.atomic.AtomicLong()
    val link = object : CarlifeLink {
        override fun sendVideo(payload: ByteArray) = true
        override fun showPlaceholder(text: String) {}
        override fun hidePlaceholder() {}
        override fun sendModuleStatus(module: Int, status: Int) = true
        override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
            if (type == CarlifeAudio.MEDIA_DATA) synchronized(bytes) {
                if (bytes.size() < 48000 * 4 * 3) bytes.write(payload)
            }
            return true
        }
        override fun sendAudioAt(channel: Int, type: Int, payload: ByteArray, capturedAtUs: Long): Boolean {
            futureUs.accumulateAndGet(capturedAtUs - System.nanoTime() / 1000, ::maxOf)
            return sendAudio(channel, type, payload)
        }
    }
    val core = AaBridgeCore({ message, ui -> AppLog.i(message, ui) }).apply { desktopInputEnabled = true }
    core.attach(link); core.onCarlifeStart()
    fun tone(usage: Int, frequency: Int): AudioTrack {
        val samples = ShortArray(4800) { (2000 * kotlin.math.sin(2 * Math.PI * it * frequency / 48000)).toInt().toShort() }
        return AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setSampleRate(48000).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build().also {
                check(it.write(samples, 0, samples.size) == samples.size)
                check(it.setLoopPoints(0, samples.size, -1) == AudioTrack.SUCCESS)
            }
    }
    var measurement = 0
    var minimumBlockRms = 0.0
    fun amplitudes(): Pair<Double, Double> {
        Thread.sleep(350) // Let the 20 ms ramp and bounded capture ring settle.
        synchronized(bytes) { bytes.reset() }
        val deadline = SystemClock.elapsedRealtime() + 4000
        while (synchronized(bytes) { bytes.size() < 48000 * 4 / 2 } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        val pcm = synchronized(bytes) { bytes.toByteArray() }
        check(pcm.size >= 48000 * 4 / 2) { "No captured PCM: ${DesktopAudioState.error}" }
        // Synthetic tones only; preserve the actual waveform for regression diagnosis.
        context.cacheDir.resolve("projection-volume-${measurement++}.pcm").writeBytes(pcm)
        fun sample(i: Int) = ((pcm[i*4].toInt() and 255) or (pcm[i*4+1].toInt() shl 8)).toShort().toDouble()
        // 50 ms contains whole cycles of both tones. Average spectral magnitudes,
        // not complex phases across the entire recording: a capture-clock correction
        // can change phase without muting or attenuating the continuous waveform.
        val blockFrames = 2400
        val blocks = (0 until pcm.size / 4 - blockFrames + 1 step blockFrames).toList()
        minimumBlockRms = blocks.minOf { start ->
            kotlin.math.sqrt((start until start + blockFrames).sumOf { sample(it) * sample(it) } / blockFrames)
        }
        fun amplitude(hz: Int): Double = blocks.map { start ->
            var re = 0.0; var im = 0.0
            for (i in 0 until blockFrames) {
                val value = sample(start + i)
                val phase = 2 * Math.PI * hz * i / 48000
                re += value * kotlin.math.cos(phase); im += value * kotlin.math.sin(phase)
            }
            2 * kotlin.math.hypot(re, im) / blockFrames
        }.average()
        return (amplitude(440) to amplitude(880)).also {
            android.util.Log.i("ProjectionVolumeProbe", "sample=$measurement bytes=${pcm.size} amplitudes=$it")
        }
    }
    val music = tone(AudioAttributes.USAGE_MEDIA, 440)
    val navigation = tone(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, 880)
    var capture: DesktopAudioCapture? = null
    try {
        prefs.navigationAudioApps = emptySet(); prefs.musicVolume = 100; prefs.navigationVolume = 100
        capture = DesktopAudioCapture(context).also { value -> test.runOnMainSync { value.attach(core, true) { true } } }
        music.play(); navigation.play()
        val readyBy = SystemClock.elapsedRealtime() + 5000
        while (synchronized(bytes) { bytes.size() < 48000 * 4 } && SystemClock.elapsedRealtime() < readyBy) Thread.sleep(10)
        check(synchronized(bytes) { bytes.size() >= 48000 * 4 }) { "Native capture did not start: ${DesktopAudioState.error}" }
        val baseline = amplitudes()
        check(baseline.first > 100 && baseline.second > 100) { "Missing native stems: $baseline" }
        repeat(2) {
            navigation.pause()
            val quiet = amplitudes()
            check(quiet.first / baseline.first in .80..1.20 && quiet.second < baseline.second * .05) {
                "Guidance end interrupted music: $quiet vs $baseline"
            }
            check(minimumBlockRms * kotlin.math.sqrt(2.0) >= baseline.first * .80) {
                "Guidance end caused a music gap: minimum 50 ms RMS=$minimumBlockRms"
            }
            navigation.play()
            val speaking = amplitudes()
            check(speaking.first / baseline.first in .80..1.20 && speaking.second / baseline.second in .80..1.20) {
                "Guidance restart lost a stem: $speaking vs $baseline"
            }
        }
        prefs.navigationVolume = 0
        val noNavigation = amplitudes()
        check(noNavigation.first / baseline.first in .80..1.20 && noNavigation.second < baseline.second * .05) { "Navigation leaked or changed music: $baseline -> $noNavigation" }
        prefs.navigationVolume = 100; prefs.musicVolume = 0
        val noMusic = amplitudes()
        check(noMusic.first < baseline.first * .05 && noMusic.second / baseline.second in .80..1.20) { "Music leaked or changed navigation: $baseline -> $noMusic" }
        prefs.musicVolume = 50; prefs.navigationVolume = 25
        val attenuated = amplitudes()
        check(attenuated.first / baseline.first in .40.. .60 && attenuated.second / baseline.second in .18.. .32) { "Wrong independent gains: $attenuated vs $baseline" }
        test.runOnMainSync { capture?.close() }; capture = null
        Thread.sleep(400)
        prefs.navigationAudioApps = setOf(context.packageName)
        prefs.musicVolume = 100; prefs.navigationVolume = 0
        capture = DesktopAudioCapture(context).also { value -> test.runOnMainSync { value.attach(core, true) { true } } }
        val selected = amplitudes()
        check(selected.first < baseline.first * .05 && selected.second < baseline.second * .05) { "Selected UID media did not join navigation: $selected" }
        check(futureUs.get() <= 20_000) { "Capture clock runs ahead of real samples: ${futureUs.get()} us" }
        return "440 Hz MEDIA + 880 Hz NAVIGATION: independent mute, 50%/25% live gain, two guidance pauses/resumes, selected UID override; baseline=$baseline; max future PTS=${futureUs.get()}us"
    } finally {
        test.runOnMainSync { capture?.close() }
        music.release(); navigation.release(); core.onCarlifeClosed(link)
        // Instrumentation completion kills the process immediately: flush the restoration
        // before another case starts, rather than losing an asynchronous apply().
        check(context.getSharedPreferences("hsd_dex", android.content.Context.MODE_PRIVATE).edit()
            .putInt("music_volume", oldMusic).putInt("navigation_volume", oldNavigation)
            .putStringSet("navigation_audio_apps", oldApps).commit())
    }
}
