package com.hsd.universal.dex

import com.hsd.universal.R
import com.hsd.universal.DesktopTransport

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hsd.universal.AppLog
import com.hsd.universal.aa.AaBridgeCore
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

object DesktopAudioAccess {
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(DesktopAudioPolicy.PERMISSION) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(DesktopAudioPolicy.ROUTING_PERMISSION) == PackageManager.PERMISSION_GRANTED
}

object DesktopAudioState {
    @Volatile var statusRes = R.string.desktop_sound_switched_off
    fun status(context: Context): String {
        val message = statusRes
        val name = if (message == R.string.desktop_for_sound_activate_access) DesktopTransport.shortName else DesktopTransport.name
        return context.getString(message, name)
    }
    @Volatile var running = false
    @Volatile var error: String? = null
    @Volatile var pcmBytes = 0L
    @Volatile var nonSilentBuffers = 0L
}

/** Main-process owner. PCM exists only in memory; the existing CarLife queue enforces backpressure. */
class DesktopAudioCapture(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var core: AaBridgeCore? = null
    private var usb = false
    private var canCaptureOutput: (() -> Boolean)? = null
    private var closed = false
    private var failed = false
    private var worker: Capture? = null
    private val sessions = DesktopAudioSessionGate()
    private var generation = 0L
    private class Capture(val core: AaBridgeCore, val generation: Long) {
        val cancelled = AtomicBoolean()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            reconcile()
            main.postDelayed(this, 250)
        }
    }

    /** Called only for a live transport, never for the desktop's disconnected grace period. */
    fun attach(value: AaBridgeCore, isUsb: Boolean, canCapture: () -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        detach()
        generation = sessions.next()
        core = value
        usb = isUsb
        canCaptureOutput = canCapture
        failed = false
        main.removeCallbacks(tick)
        tick.run()
    }

    fun detach() {
        check(Looper.myLooper() == Looper.getMainLooper())
        main.removeCallbacks(tick)
        worker?.cancelled?.set(true)
        sessions.next() // old callbacks must not PAUSE/STOP a newly attached CarLife link
        core?.usbAudioEnabled = false
        core = null
        canCaptureOutput = null
        usb = false
        DesktopAudioState.running = false
        DesktopAudioState.statusRes = if (DexPrefs(context).usbPlaybackAudio) R.string.desktop_sound_i_m_waiting_to_connect else R.string.desktop_sound_switched_off
    }

    fun close() { detach(); closed = true }

    private fun reconcile() {
        val current = core ?: return
        val prefs = DexPrefs(context)
        val enabled = prefs.usbPlaybackAudio
        val delegated = prefs.useDelegated
        val ready = DelegatedDesktopAccess.isReady(context)
        val permission = DesktopAudioAccess.hasPermission(context)
        // Enable the CarLife sink only after an explicit setting + scoped activation.
        current.usbAudioEnabled = enabled && delegated && ready && permission && usb
        val outputFresh = runCatching { canCaptureOutput?.invoke() == true }.getOrDefault(false)
        val permitted = outputFresh && DesktopAudioPolicy.mayCapture(enabled, delegated, ready, permission,
            usb, current.isCarlifeStarted && current.hasCarlifeLink, current.audioViaUsb)
        if (!permitted) {
            if (!outputFresh && worker?.cancelled?.compareAndSet(false, true) == true)
                AppLog.i("DESKTOP AUDIO: USB write lease expired; releasing playback capture", false)
            worker?.cancelled?.set(true)
            failed = false // a user setting/access/session transition allows a fresh attempt
            DesktopAudioState.running = false
            DesktopAudioState.statusRes = when {
                !enabled -> R.string.desktop_sound_switched_off
                !delegated -> R.string.desktop_sound_available_in_computer_access_mode
                !ready -> R.string.desktop_for_sound_activate_access
                !permission -> R.string.desktop_for_sound_re_activate_access_with_the_audio_parameter
                !usb -> R.string.desktop_sound_i_m_waiting_to_connect
                !current.isCarlifeStarted -> R.string.desktop_sound_awaiting_launch
                !current.audioViaUsb -> R.string.desktop_head_unit_requested_bluetooth_audio
                !outputFresh -> R.string.desktop_audio_waiting_for_receiver
                else -> R.string.desktop_audio_connection_closed
            }
        } else if (worker == null && !failed) {
            val capture = Capture(current, generation)
            worker = capture
            DesktopAudioState.error = null
            DesktopAudioState.pcmBytes = 0
            DesktopAudioState.nonSilentBuffers = 0
            DesktopAudioState.statusRes = R.string.desktop_connecting_audio
            Thread({ runCapture(capture) }, "${com.hsd.universal.BuildConfig.BRAND}-playback-audio").start()
        }
    }

    private fun updateSignalStatus(capture: Capture, hasSignal: Boolean) {
        main.post {
            if (worker === capture && !capture.cancelled.get()) {
                DesktopAudioState.statusRes = if (hasSignal) R.string.desktop_sending_audio_over
                    else R.string.desktop_sound_i_m_waiting_for_music_or_a_hint
            }
        }
    }

    private fun runCapture(capture: Capture) {
        var mix: RegisteredPlaybackMix? = null
        var started = false
        var failure: Throwable? = null
        try {
            // AudioService propagates new delegated grants to its native permission cache asynchronously.
            for (attempt in 0..2) {
                if (capture.cancelled.get()) return
                try { mix = RegisteredPlaybackMix.open(context); break }
                catch (error: Exception) { if (attempt == 2) throw error; Thread.sleep(150) }
            }
            val audio = checkNotNull(mix).record
            if (capture.cancelled.get()) return
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { context.getString(R.string.desktop_android_has_not_launched_the_transmission_of_sound) }
            started = true
            sessions.runIfCurrent(capture.generation) {
                if (!capture.cancelled.get()) capture.core.onAudioStart(DesktopAudioPolicy.MEDIA_CHANNEL)
            }
            main.post { if (worker === capture && !capture.cancelled.get()) {
                DesktopAudioState.running = true
                DesktopAudioState.statusRes = R.string.desktop_sound_i_m_waiting_for_music_or_a_hint
            } }
            AppLog.i("DESKTOP AUDIO: registered AudioMix LOOP_BACK media/navigation/assistant; PCM 48000 Hz stereo16 started")
            var nextReport = SystemClock.elapsedRealtime() + 10_000L
            var intervalPeak = 0
            var reportedSignal = false
            var intervalHadSignal = false
            val input = ByteArray(DesktopAudioPolicy.READ_BYTES)
            val frames = DesktopPcmFrames()
            val clock = DesktopAudioClock(bufferCapacityFrames = audio.bufferSizeInFrames)
            val stamp = android.media.AudioTimestamp()
            while (!capture.cancelled.get()) {
                val count = audio.read(input, 0, input.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { context.getString(R.string.desktop_android_sound_reading_error, count) }
                val now = SystemClock.elapsedRealtime()
                if (now >= nextReport) {
                    AppLog.i("DESKTOP AUDIO: PCM bytes=${DesktopAudioState.pcmBytes} " +
                        "non-silent buffers=${DesktopAudioState.nonSilentBuffers} peak10s=$intervalPeak", false)
                    if (intervalPeak == 0 && intervalHadSignal) updateSignalStatus(capture, hasSignal = false)
                    intervalHadSignal = intervalPeak > 0
                    intervalPeak = 0
                    nextReport = now + 10_000L
                }
                if (count == 0) { Thread.sleep(5); continue }
                val pcm = frames.append(input, count)
                if (pcm.isEmpty() || capture.cancelled.get()) continue
                DesktopAudioState.pcmBytes += pcm.size
                val peak = DesktopPcmFrames.peak(pcm)
                if (peak > 0) DesktopAudioState.nonSilentBuffers++
                if (peak > 0 && !reportedSignal) {
                    reportedSignal = true
                    AppLog.i("DESKTOP AUDIO: first non-silent PCM peak=$peak", false)
                }
                if (peak > 0 && !intervalHadSignal) {
                    intervalHadSignal = true
                    updateSignalStatus(capture, hasSignal = true)
                }
                intervalPeak = maxOf(intervalPeak, peak)
                val hasTimestamp = runCatching {
                    audio.getTimestamp(stamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                }.getOrDefault(false)
                val capturedAtUs = clock.read(pcm.size / DesktopAudioPolicy.BYTES_PER_FRAME, System.nanoTime() / 1000,
                    stamp.framePosition.takeIf { hasTimestamp }, (stamp.nanoTime / 1000).takeIf { hasTimestamp })
                sessions.runIfCurrent(capture.generation) {
                    if (!capture.cancelled.get()) capture.core.onAudioData(DesktopAudioPolicy.MEDIA_CHANNEL, pcm,
                        capturedAtUs)
                }
            }
        } catch (error: Exception) {
            if (!capture.cancelled.get()) {
                failure = (error as? InvocationTargetException)?.targetException ?: error
                AppLog.e("DESKTOP AUDIO: playback unavailable; desktop remains active", failure)
            }
        } finally {
            // Stop rerouting output before notifying CarLife; never retain capture during USB grace.
            if (started) runCatching { mix?.record?.stop() }
            runCatching { mix?.close() }.onFailure { AppLog.e("DESKTOP AUDIO: unable to release playback route", it) }
            if (started) runCatching { sessions.runIfCurrent(capture.generation) {
                capture.core.onAudioStop(DesktopAudioPolicy.MEDIA_CHANNEL)
            } }
            val error = failure
            main.post {
                if (worker === capture) {
                    worker = null
                    DesktopAudioState.running = false
                    if (error != null && core === capture.core && !capture.cancelled.get()) {
                        failed = true
                        DesktopAudioState.error = "${error.javaClass.simpleName}: ${error.message}"
                        DesktopAudioState.statusRes = R.string.desktop_android_didn_t_open_the_sound_repeat_activation_with
                    }
                }
            }
            AppLog.i("DESKTOP AUDIO: stopped; PCM bytes=${DesktopAudioState.pcmBytes}, non-silent buffers=${DesktopAudioState.nonSilentBuffers}", false)
        }
    }
}
