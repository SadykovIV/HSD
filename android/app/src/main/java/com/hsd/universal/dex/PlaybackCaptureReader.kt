package com.hsd.universal.dex

import android.media.AudioRecord
import android.media.AudioTimestamp

/** Reads every stem on one worker and aligns it by sample timestamps, not polling order. */
internal class PlaybackCaptureReader(private val mix: RegisteredPlaybackMix) {
    private val mixer = PlaybackVolumeMixer(mix.stems.map { it.guidance })
    private val clocks = mix.records.map { DesktopAudioClock(bufferCapacityFrames = it.bufferSizeInFrames) }
    private val frames = mix.records.map { DesktopPcmFrames() }
    private val stamps = mix.records.map { AudioTimestamp() }
    private val input = ByteArray(DesktopAudioPolicy.READ_BYTES)
    private val reads = IntArray(mix.records.size)
    private val samples = LongArray(mix.records.size)
    private val lastReadsUs = LongArray(mix.records.size)
    private val corrections = IntArray(mix.records.size)
    private val correctionMaxUs = LongArray(mix.records.size)
    private val correctionLoggedUs = LongArray(mix.records.size)

    fun read(music: Int, navigation: Int): PlaybackVolumeMixer.Output? {
        mix.records.forEachIndexed { index, audio ->
            val count = audio.read(input, 0, input.size, AudioRecord.READ_NON_BLOCKING)
            check(count >= 0) { "AudioRecord read failed: $count" }
            if (count > 0) {
                val pcm = frames[index].append(input, count)
                if (pcm.isNotEmpty()) {
                    val stamp = stamps[index]
                    val timed = runCatching {
                        audio.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                    }.getOrDefault(false)
                    val arrived = System.nanoTime() / 1000
                    val at = clocks[index].read(pcm.size / 4, arrived,
                        stamp.framePosition.takeIf { timed }, (stamp.nanoTime / 1000).takeIf { timed })
                    val read = ++reads[index]
                    samples[index] += pcm.size / 4
                    if (read <= 3 || read == 50 || clocks[index].discontinuityUs > 1000) com.hsd.universal.AppLog.i(
                        "DESKTOP AUDIO CLOCK: route=$index read=$read frames=${pcm.size/4} total=${samples[index]} buffer=${audio.bufferSizeInFrames} " +
                            "timed=$timed position=${stamp.framePosition} nativeLagUs=${arrived-stamp.nanoTime/1000} " +
                            "captureAgeUs=${arrived-at} readGapUs=${if (lastReadsUs[index] == 0L) 0 else arrived-lastReadsUs[index]} " +
                            "discontinuityUs=${clocks[index].discontinuityUs} peak=${DesktopPcmFrames.peak(pcm)}", false)
                    lastReadsUs[index] = arrived
                    val corrected = mixer.offer(index, pcm, at, arrived)
                    clocks[index].correctFutureSamples(corrected)
                    if (corrected > 1000) {
                        corrections[index]++
                        correctionMaxUs[index] = maxOf(correctionMaxUs[index], corrected)
                        if (corrections[index] <= 3 || arrived - correctionLoggedUs[index] >= 5_000_000) {
                            com.hsd.universal.AppLog.i("DESKTOP AUDIO CLOCK: route=$index " +
                                "origin corrections=${corrections[index]} maxUs=${correctionMaxUs[index]}", false)
                            correctionLoggedUs[index] = arrived
                            correctionMaxUs[index] = 0
                        }
                    }
                }
            }
        }
        return mixer.poll(System.nanoTime() / 1000, music, navigation)
    }

    fun discard() = mixer.discard()
}
