package com.hsd.universal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hsd.universal.AppLog
import com.hsd.universal.aa.*
import com.hsd.universal.dex.*

/** Bind the selected Desktop profile and access method to the phone-side Android Auto session. */
internal class DesktopProjectionSource(private val context: Context, private val output: DesktopProjectionOutput, private val hevc: Boolean, private val wired: Boolean,
                               private val failed: (String) -> Unit) : CarlifeLink {
    private val protocol = com.hsd.universal.ProjectionRoute.selected(context).protocol
    private val log = { line: String, ui: Boolean -> AppLog.i(line.replace("CarLife", protocol), ui) }
    private val prefs = DexPrefs(context)
    private val profile = DesktopModePolicy.label(prefs.desktopMode)
    private val core = AaBridgeCore(log, connectingText = context.getString(R.string.desktop_starting, profile), waitingText = context.getString(R.string.desktop_waiting_for, profile), desktopHevc = hevc)
    private val capture = DesktopAudioCapture(context)
    private val main = Handler(Looper.getMainLooper())
    private var source: AaLifecycle? = null
    private val framePolicy = DesktopFrameRatePolicy()
    private var bitrate = BitratePolicy(prefs.desktopBitrateMbps * 1_000_000)
    private val thermal = ThermalThrottle()
    private val phoneThermal = com.hsd.universal.PhoneThermal(context)
    private var fps = 30
    private val power = DesktopPowerDiagnostics(context)
    private val idle = CarLifePhoneIdleTimer(context, { !closed && DexState.running && output.freshVideo() },
        { setPhoneScreenOff(true) })
    @Volatile private var closed = false

    private var frameWidth = 0
    private var frameHeight = 0
    private var viewport: VideoViewport? = null
    private var selectedEncoder: DesktopEncoderSupport.Encoding? = null

    fun open(width: Int, height: Int, frameRate: Int, layout: AaVideoLayout? = null,
             encoding: DesktopEncoderSupport.Encoding? = null) {
        frameWidth = layout?.mode?.width ?: width
        frameHeight = layout?.mode?.height ?: height
        viewport = layout?.content
        selectedEncoder = encoding
        if (encoding != null) bitrate = BitratePolicy(encoding.maxBitrate, maxOf(BitratePolicy.FLOOR_BPS, encoding.minBitrate))

        check(!prefs.useDesktopService || DesktopAccess.isReady(context)) {
            context.getString(R.string.projection_activate_access)
        }
        fps = if (prefs.useDesktopService) frameRate else minOf(30, frameRate)
        framePolicy.reset(fps)
        core.videoMode = NativeDesktopGeometry(width, height)
        core.desktopEncoderGeometry = NativeDesktopGeometry(frameWidth, frameHeight)
        com.hsd.universal.CarLifeState.videoMode = core.videoMode
        core.densityOverride = layout?.density ?: prefs.desktopVideoSelection().densityFor(core.videoMode)
        com.hsd.universal.CarLifeState.densityOverride = core.densityOverride
        core.mapHomeKey = true
        core.touchDecimationProvider = { AaPrefs(context).touchDecimation }
        core.desktopInputEnabled = true
        core.transcodeEnabled = true
        core.expectFeatureConfig = false
        core.spliceMs = 0
        core.musicRateProvider = { MusicRate.entries.firstOrNull { it.hz == output.audioRate() } ?: MusicRate.HZ48 }
        val onFailure = { main.post { if (!closed) failed(DexState.error ?: context.getString(R.string.projection_access_ended)) }; Unit }
        source = when {
            prefs.useDelegated -> DelegatedDexConnector(context, core) { onFailure() }
            prefs.useShizuku -> ShizukuDexConnector(context, core) { onFailure() }
            else -> DexConnector(context, core, onFailure)
        }
        core.attach(this)
        source!!.start()
        core.onCarlifeStart()
        power.start()
        idle.start()
        capture.attach(core, true) { !closed && output.freshVideo() && output.audioRate() != null && DexState.running }
        DelegatedPanelAccess.attachOutput(this) { !closed && output.freshVideo() }
        DesktopMusicPlayback.attach(context, context, this, wired, { !closed && DexState.running && output.freshVideo() },
            { !prefs.usbPlaybackAudio || !core.audioViaUsb || DesktopAudioState.running })
    }

    override fun createTranscoder(out: TranscodeOutput) = createEncoder(out, false)
    override fun createSurfaceEncoder(out: TranscodeOutput) = createEncoder(out, true)

    private fun createEncoder(out: TranscodeOutput, surfaceInput: Boolean): VideoTranscoder {
        val size = core.videoMode
        val encoder = AaTranscoder(frameWidth, frameHeight, fps, bitrate.current, out,
            srcWidth = if (surfaceInput) size.outW else DexVideoGeometry.WIDTH,
            srcHeight = if (surfaceInput) size.outH else DexVideoGeometry.HEIGHT,
            directSurface = surfaceInput, hevc = hevc, viewport = viewport, projectionEncoder = selectedEncoder)
        try {
            encoder.start()
            return encoder
        } catch (error: Exception) {
            val codec = if (hevc) "H.265" else "H.264"
            val reason = generateSequence<Throwable>(error) { it.cause }.last()
            AppLog.e("Projection $codec $frameWidth×$frameHeight at $fps fps", reason)
            main.post { if (!closed) failed(context.getString(R.string.desktop_encoder_start_failure,
                codec, frameWidth, frameHeight, fps, reason.message ?: reason.javaClass.simpleName)) }
            throw error
        }
    }
    override fun sendVideo(payload: ByteArray) = sendVideoAt(payload, null)
    override val videoExpectedSinceNanos get() = output.videoExpectedSinceNanos
    override fun sendVideoAt(payload: ByteArray, presentationUs: Long?) =
        !closed && output.video(payload, presentationUs ?: System.nanoTime() / 1000)
    override fun sendVideoTakeoverAt(payload: ByteArray, presentationUs: Long?) = sendVideoAt(payload, presentationUs)
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray) = sendAudioAt(channel, type, payload, System.nanoTime() / 1000)
    override fun sendAudioAt(channel: Int, type: Int, payload: ByteArray, capturedAtUs: Long) =
        !closed && channel == CarlifeAudio.CH_MEDIA && output.audio(type, payload, capturedAtUs)
    override fun sendModuleStatus(module: Int, status: Int) = false
    override fun showPlaceholder(text: String) {}
    override fun hidePlaceholder() {}

    fun tune(nowMs: Long, backlogMs: Double, bytesPerSecond: Double, overflow: Boolean) {
        if (closed || !prefs.useDesktopService) return
        val changed = thermal.update(nowMs, phoneThermal.status(), phoneThermal.headroom())
        val capped = changed != null && bitrate.setThermalCap(thermal.capBitrate(bitrate.ceilingBps))
        val bps = bitrate.update(nowMs, backlogMs, bytesPerSecond)
        if (bps != null || capped) core.setEncoderBitrate(bitrate.current)
        framePolicy.update(nowMs, backlogMs, thermal.capFps(fps), overflow)?.let(core::setTargetFps)
    }

    fun outputLost(hadPicture: Boolean, carriedAudio: Boolean) {
        if (!closed) DesktopMusicPlayback.usbOutputLost(context, this, hadPicture && DexState.running,
            carriedAudio = carriedAudio && prefs.usbPlaybackAudio && core.audioViaUsb)
    }

    fun requestKeyFrame() { if (!closed) core.onCarlifeKeyFrameRequest() }
    fun touch(action: Int, index: Int, points: List<TouchPoint>) { if (!closed) core.sendProjectionTouch(action, index, points) }
    fun cancelTouch() = core.cancelProjectionTouch()
    val hasActiveTouch get() = core.hasActiveTouch
    val repeatedTouchDowns get() = core.repeatedProjectionDowns
    fun key(code: Int) { if (!closed) core.peer?.sendKey(code) }
    fun rotate(delta: Int) { if (!closed) core.peer?.sendRotary(delta.coerceIn(-12, 12)) }
    fun setPhoneScreenOff(off: Boolean): Boolean = !closed &&
        (!off || prefs.desktopPanelControl && prefs.phoneScreenMode == PhoneIdleMode.PANEL) &&
        (source as? DesktopPowerControl)?.setPhoneScreenOff(off) == true

    fun home() { if (!closed) (source as? DesktopNavigation)?.showDesktop() }

    fun close(done: () -> Unit) {
        if (closed) return
        closed = true
        idle.stop()
        power.stop()
        capture.close()
        DelegatedPanelAccess.detachOutput(this)
        DesktopMusicPlayback.linkClosed(this)
        core.onCarlifeClosed(this)
        source?.stop(done) ?: done()
        source = null
    }
}

internal interface DesktopProjectionOutput {
    /** Null while the receiver explicitly owns the screen. Zero means no earlier suspension. */
    val videoExpectedSinceNanos: Long? get() = 0L
    fun video(payload: ByteArray, ptsUs: Long): Boolean
    fun audio(type: Int, pcm: ByteArray, ptsUs: Long): Boolean
    fun audioRate(): Int?
    fun freshVideo(): Boolean
}
