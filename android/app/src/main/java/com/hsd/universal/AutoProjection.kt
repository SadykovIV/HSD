package com.hsd.universal

import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.hsd.universal.AnnexB
import com.hsd.universal.aa.DesktopEncoderSupport
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import com.hsd.universal.aa.CarlifeAudio
import com.hsd.universal.aa.TouchPoint
import com.hsd.universal.dex.DexPrefs
import net.prototux.aare.aap.*
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.InputChannel
import net.prototux.aare.aap.channels.VideoChannel

/** AAP as the phone. The receiver selects channels and DriveDeck produces the actual pixels. */
internal class AutoProjection(private val context: Context, transport: Transport, identity: PhoneIdentity,
                              private val pins: PinStore, private val peer: String, private val status: (String) -> Unit,
                              private val ended: (String) -> Unit, private val ready: () -> Unit = {}) :
    ProjectionSession, AapSession.Callbacks, VideoChannel.Listener, InputChannel.Listener, DesktopProjectionOutput {
    private val cluster = AutoCluster(context)
    private val prefs = DexPrefs(context)
    private val profile = com.hsd.universal.dex.DesktopModePolicy.label(prefs.desktopMode)
    private val delivery = com.hsd.universal.dex.DesktopVideoDelivery()
    private val calibration = com.hsd.universal.link.ReceiverAudioCalibration(context, "aap-" + com.hsd.universal.ProjectionRoute.selected(context).name.lowercase())
    private val audioEncoder = AutoAudioEncoder()
    @Volatile private var audioEncoderFailed = false
    private val pcmQueue = AutoPcmQueue(calibration) { data, timestamp ->
        val sink = playback
        if (!stopping && audioFocus && sink != null) {
            val type = sink.service.mediaSinkService.availableType
            if (type == MediaCodecType.MEDIA_CODEC_AUDIO_PCM) sink.sendPcm(data, 10, data.size - 10, timestamp)
            else if (!audioEncoderFailed) sink.config?.let { config ->
                try {
                    audioEncoder.offer(data, 10, timestamp, config.samplingRate, type == MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_ADTS) { out, pts ->
                        sink.sendPcm(out, 10, out.size - 10, pts)
                    }
                } catch (error: Exception) {
                    audioEncoderFailed = true
                    audioEncoder.close()
                    com.hsd.universal.AppLog.i("Android Auto AAC: ${error.message}", false)
                    publish(context.getString(R.string.desktop_android_auto_aac_encoder_is_not_available_the_image))
                }
            }
        }
    }
    private val writtenBytes = java.util.concurrent.atomic.AtomicLong()
    private var frameRate = 30
    private var previousDrops = 0L
    private var lastSampleMs = 0L
    private val sample = object : Runnable {
        override fun run() {
            if (stopping) return
            val now = SystemClock.elapsedRealtime()
            val rate = writtenBytes.getAndSet(0) * 1000.0 / (now - lastSampleMs).coerceAtLeast(1)
            val drops = videoChannel.congestionDrops
            val backlog = maxOf(videoChannel.sendLatencyMs.toDouble(), videoChannel.pendingFrames * 1000.0 / frameRate)
            source?.tune(now, backlog, rate, drops != previousDrops)
            lastSampleMs = now
            previousDrops = drops
            main.postDelayed(this, 1000)
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val session = AapSession(transport, identity,
        PhoneDescription(Build.MODEL, "HSD", instanceId = fingerprint(identity.certificate).take(32)),
        this, options = SessionOptions(sendPings = com.hsd.universal.ProjectionRoute.wireless(context), pins = pins, peerKey = peer, trustOnFirstUse = true))
    @Volatile var retryUsbOpen = false
        private set
    @Volatile var rejectedVideo = false
        private set
    @Volatile var networkInterrupted = false
        private set
    @Volatile private var stopping = false
    @Volatile private var source: DesktopProjectionSource? = null
    private lateinit var videoChannel: VideoChannel
    private var input: InputChannel? = null
    private var playback: AudioChannel? = null
    @Volatile private var audioFocus = false
    private var audioRequested = false
    private var width = 0
    private var height = 0
    @Volatile private var runningConfig: VideoConfiguration? = null
    @Volatile private var activeLayout: AaVideoLayout? = null
    private var activeCodec: MediaCodecType? = null
    @Volatile private var codecConfig: ByteArray? = null
    private var touchLayout: AaVideoLayout? = null
    private val selection = prefs.desktopVideoSelection()
    private val legacyTiles = prefs.desktopMode == com.hsd.universal.dex.DesktopModePolicy.DRIVEDECK && runCatching {
        context.packageManager.getPackageInfo(com.hsd.universal.dex.DesktopModePolicy.DRIVEDECK_PACKAGE, 0).longVersionCode < 336
    }.getOrDefault(false)
    @Volatile private var submitted = false
    private var endMessage: String? = null
    // A new surface can only start after the desktop service releases the previous one.
    private var retiringSource = false
    private var pendingConfiguration: VideoConfiguration? = null
    private var finishAfterRetirement: (() -> Unit)? = null
    private val deadline = Runnable { if (!submitted) stop(context.getString(R.string.desktop_in_55_seconds_the_radio_did_not_receive_the)) }

    override fun home() { source?.home() }
    override fun panel(off: Boolean) { source?.setPhoneScreenOff(off) }

    override fun start() {
        com.hsd.universal.dex.DesktopConnectionState.attach(delivery)
        main.postDelayed(deadline, 55_000)
        session.start()
    }
    override fun stop(message: String) {
        if (stopping) return
        stopping = true
        endMessage = message
        main.removeCallbacks(deadline)
        session.stop()
    }

    override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> {
        val model = response.headunitInfo.headUnitModel.ifBlank { response.headUnitModel }
        val make = response.headunitInfo.headUnitMake.ifBlank { response.headUnitMake }
        (pins as? ProjectionIdentity)?.authenticated(peer, response.displayName.ifBlank {
            listOf(make, model).filter(String::isNotBlank).distinct().joinToString(" ")
        })
        calibration.identify(response.headunitInfo.headUnitMake.ifBlank { response.headUnitMake },
            response.headunitInfo.headUnitModel.ifBlank { response.headUnitModel.ifBlank { response.displayName } })
        val displays = response.channelsList.filter { it.hasMediaSinkService() && it.mediaSinkService.displayType.number == 0 }
        val display = displays.firstOrNull { ch ->
            val sink = ch.mediaSinkService
            choose(sink.videoConfigsList, emptyList(), sink) >= 0
        } ?: error(context.getString(R.string.desktop_the_radio_and_phone_encoder_screens_do_not_share) +
            displays.flatMap { it.mediaSinkService.videoConfigsList }.joinToString { c ->
                val layout = layoutOf(c)
                if (layout == null) context.getString(R.string.desktop_regime, c.codecResolution) else "${layout.mode.title} @ ${layout.fps}"
            })
        val sink = display.mediaSinkService
        val offers = sink.videoConfigsList.mapIndexed { i, c ->
            val layout = layoutOf(c)
            val type = codecOf(c, sink.hasAvailableType(), sink.availableType)
            if (layout == null) "$i=${c.codecResolution} (unsupported)" else
                "$i=${layout.mode.width}x${layout.mode.height}@${layout.fps} ${type.name}"
        }
        com.hsd.universal.AppLog.i("AAP VIDEO discovery · " + offers.joinToString(limit = 24), false)
        val initial = sink.videoConfigsList[choose(sink.videoConfigsList, emptyList(), sink)]
        videoChannel = VideoChannel(display, this, codecOf(initial, sink.hasAvailableType(), sink.availableType))
        val inputs = response.channelsList.filter { it.hasInputSourceService() &&
            it.inputSourceService.displayId == display.mediaSinkService.displayId }
        val panel = inputs.firstOrNull { it.inputSourceService.touchscreenList.any { t -> !t.isSecondary } } ?: inputs.firstOrNull()
        input = panel?.let { InputChannel(it, this) }
        com.hsd.universal.CarLifeState.auth = "TLS"
        com.hsd.universal.CarLifeState.protocol = "Android Auto (AAP)"
        com.hsd.universal.CarLifeState.touch = if (input?.touchScreen != null) context.getString(R.string.desktop_touchscreen) else context.getString(R.string.desktop_no_touch_screen)
        playback = response.channelsList.firstOrNull { ch ->
            val sink = ch.mediaSinkService
            ch.hasMediaSinkService() && sink.audioType.number == 3 &&
                sink.availableType in audioCodecs && sink.audioConfigsList.any(::supportsAudio)
        }?.let { AudioChannel(it, supports = ::supportsAudio) }
        publish(context.getString(R.string.desktop_android_auto_connected_negotiating_the_display, profile))
        return listOfNotNull(videoChannel, input, playback) + cluster.channels(response.channelsList)
    }

    private fun layoutOf(config: VideoConfiguration) = AaVideoLayout.of(config, selection.densityOverride, legacyTiles)
    override fun acceptsLayout(config: VideoConfiguration) = layoutOf(config) != null
    override fun onUiConfig(config: UiConfig) {
        videoChannel.chosen?.let { onVideoStart(-1, it) }
    }
    override fun onUnavailable(message: String) {
        rejectedVideo = true
        main.post { stop(message) }
    }

    private fun choose(configs: List<VideoConfiguration>, indices: List<Int>,
                       sink: aap_protobuf.service.media.sink.MediaSinkServiceOuterClass.MediaSinkService) =
        AaVideoNegotiation.choose(configs, indices, selection) { config ->
            val layout = layoutOf(config)
            val type = codecOf(config, sink.hasAvailableType(), sink.availableType)
            layout?.takeIf { encoder(it, type) != null }?.mode
        }

    override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int {
        val index = choose(configs, preferred, videoChannel.service.mediaSinkService)
        com.hsd.universal.AppLog.i("AAP VIDEO CONFIG ${preferred.joinToString(limit = 24)} · index=$index", false)
        return index
    }

    override fun onVideoStart(index: Int, config: VideoConfiguration) {
        main.post {
            if (!stopping) {
                pendingConfiguration = config
                applyVideoConfiguration()
            }
        }
    }

    private fun applyVideoConfiguration() {
        val config = pendingConfiguration ?: return
        if (retiringSource || stopping) return
        val layout = layoutOf(config) ?: return stop(context.getString(R.string.desktop_the_head_unit_offered_an_empty_desktop_area))
        val w = layout.mode.width
        val h = layout.mode.height
        val type = videoChannel.codec
        val fps = layout.fps
        val old = source
        if (old != null) {
            if (activeLayout == layout && activeCodec == type) {
                runningConfig = config
                codecConfig = null
                pendingConfiguration = null
                old.requestKeyFrame()
                return
            }
            retiringSource = true
            runningConfig = null
            activeLayout = null
            source = null
            main.removeCallbacks(sample)
            delivery.pause()
            pcmQueue.clear()
            old.close {
                main.post {
                    retiringSource = false
                    if (stopping) {
                        finishAfterRetirement?.invoke()
                        finishAfterRetirement = null
                    } else applyVideoConfiguration()
                }
            }
            return
        }
        pendingConfiguration = null
        codecConfig = null
        submitted = false
        main.removeCallbacks(deadline)
        main.postDelayed(deadline, 55_000)
        delivery.start(SystemClock.elapsedRealtime())
        width = w
        height = h
        runCatching {
            val encoding = requireNotNull(encoder(layout, type)) { context.getString(R.string.desktop_mode_encoder_unavailable, layout.mode.title, fps) }
            val desktop = DesktopProjectionSource(context, this, type == h265, ::stop)
            activeLayout = layout
            activeCodec = type
            runningConfig = config
            source = desktop
            com.hsd.universal.AppLog.i("AAP VIDEO ${encoding.name} · frame=${w}x${h} · desktop=${layout.desktopWidth}x${layout.desktopHeight}" +
                " · viewport=${layout.content} · dpi=${layout.density}", false)
            status(context.getString(R.string.desktop_starting_6b56, profile, w, h))
            desktop.open(layout.desktopWidth, layout.desktopHeight, fps, layout, encoding)
            frameRate = fps
            lastSampleMs = SystemClock.elapsedRealtime()
            main.postDelayed(sample, 1000)
        }.onFailure { stop(it.message ?: context.getString(R.string.desktop_failed_to_open, profile)) }
    }

    override fun onVideoFocus(projected: Boolean) {
        if (stopping) return
        if (projected) {
            delivery.start(SystemClock.elapsedRealtime())
            onKeyFrameNeeded()
        } else {
            val hadPicture = delivery.hasPicture()
            delivery.pause()
            pcmQueue.clear()
            main.post { source?.outputLost(hadPicture) }
        }
    }
    override fun onKeyFrameNeeded() {
        // The AAP reader may hold the channel's lock; the encoder holds the desktop core's.
        main.post { if (!stopping) source?.requestKeyFrame() }
    }
    override fun video(payload: ByteArray, ptsUs: Long): Boolean {
        if (stopping || source == null || !videoChannel.started || !videoChannel.focused) return false
        val config = runningConfig ?: return false
        return videoChannel.writeFor(config) { sendVideoFrame(payload, ptsUs, videoChannel.codec == h265) }
    }

    private fun sendVideoFrame(payload: ByteArray, ptsUs: Long, hevc: Boolean) {
        val units = AnnexB.units(payload)
        val key = units.any { unit ->
            val type = unit.type(payload, hevc)
            if (hevc) type in 16..21 else type == 5
        }
        if (key) {
            val types = if (hevc) hevcHeaders else avcHeaders
            val headers = types.mapNotNull { type -> units.firstOrNull { it.type(payload, hevc) == type } }
            if (headers.size == types.size) {
                val config = ByteArray(headers.sumOf { 4 + it.size })
                var pos = 0
                for (unit in headers) {
                    config[pos + 3] = 1
                    payload.copyInto(config, pos + 4, unit.start, unit.end)
                    pos += 4 + unit.size
                }
                if (codecConfig?.contentEquals(config) != true) {
                    videoChannel.sendCodecConfig(config, 0, config.size)
                    codecConfig = config
                }
            }
        }
        val frame = ByteArray(10 + payload.size)
        payload.copyInto(frame, 10)
        videoChannel.sendFrame(frame, 10, payload.size, ptsUs, key) {}
    }

    override fun onFrameWritten(keyFrame: Boolean, ptsUs: Long, bytes: Int) {
        if (stopping || !videoChannel.focused) return
        writtenBytes.addAndGet(bytes.toLong())
        pcmQueue.videoWritten(ptsUs)
        delivery.frameWritten(SystemClock.elapsedRealtime())
        if (keyFrame && !submitted) {
            submitted = true
            ready()
            main.removeCallbacks(deadline)
            com.hsd.universal.AppLog.i("Android Auto: первый ключевой кадр записан, $width × $height, $bytes байт")
            val message = "$profile · $width × $height · ${if (videoChannel.codec == h265) "H.265" else "H.264"}"
            com.hsd.universal.CarLifeState.video = message
            publish(message)
        }
    }
    override fun freshVideo() = !stopping && delivery.isFlowing(SystemClock.elapsedRealtime())

    override fun onInput(report: InputReport) {
        val desktop = source ?: return
        if (stopping || !videoChannel.focused) return
        if (report.hasTouchEvent()) {
            val panel = input?.touchScreen
            val e = report.touchEvent
            if (panel != null && e.pointerDataCount in 1..2 && e.actionIndex in e.pointerDataList.indices) {
                val layout = activeLayout ?: return
                val w = if (prefs.useDesktopService) layout.desktopWidth else com.hsd.universal.dex.DexVideoGeometry.WIDTH
                val h = if (prefs.useDesktopService) layout.desktopHeight else com.hsd.universal.dex.DexVideoGeometry.HEIGHT
                val down = e.action.number == 0
                if (down) touchLayout = null
                if (down || touchLayout === layout) {
                    val points = e.pointerDataList.map {
                        val (x, y) = layout.touch(it.x, it.y, panel.width, panel.height, w, h, clamp = !down) ?: return
                        TouchPoint(it.pointerId, x, y)
                    }
                    touchLayout = if (e.action.number == 1) null else layout
                    desktop.touch(e.action.number, e.actionIndex, points)
                }
            }
        }
        // The desktop API injects a full down/up pair for a key.
        for (key in report.keyEvent.keysList) if (!key.down) {
            if (key.keycode == KeyEvent.KEYCODE_HOME) desktop.home() else desktop.key(key.keycode)
        }
        for (rel in report.relativeEvent.dataList) if (rel.keycode == 65536) desktop.rotate(rel.delta)
    }
    private fun supportsAudio(config: AudioConfiguration) =
        config.numberOfBits == 16 && config.numberOfChannels == 2 && config.samplingRate in rates

    override fun audioRate(): Int? = playback?.takeIf { it.ready && !audioEncoderFailed }?.config?.samplingRate

    @Synchronized override fun audio(type: Int, pcm: ByteArray, ptsUs: Long): Boolean {
        val sink = playback ?: return false
        if (stopping || audioRate() == null) return false
        when (type) {
            CarlifeAudio.MEDIA_STOP, CarlifeAudio.MEDIA_PAUSE -> {
                pcmQueue.clear()
                sink.stopPlayback()
                if (audioRequested) session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, Priority.AUDIO)
                audioRequested = false
                audioFocus = false
            }
            CarlifeAudio.MEDIA_INIT, CarlifeAudio.MEDIA_RESUME, CarlifeAudio.MEDIA_DATA -> {
                if (!audioRequested) {
                    audioRequested = true
                    session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
                }
                if (type == CarlifeAudio.MEDIA_DATA) {
                    if (!audioFocus) return false
                    sink.startPlayback()
                    val bytes = ByteArray(10 + pcm.size)
                    pcm.copyInto(bytes, 10)
                    return pcmQueue.enqueue(bytes, ptsUs)
                }
            }
            else -> return false
        }
        return true
    }

    override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) {
        audioFocus = notification.focusState.number in listOf(1, 2, 6)
        if (!audioFocus) pcmQueue.clear()
    }
    override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
        val oldUsb = session.closedDuring == AapSession.State.VERSION && session.oldUsbFramesBeforeVersion > 0
        retryUsbOpen = !stopping && !oldUsb && session.closedDuring == AapSession.State.VERSION && error is java.io.IOException
        networkInterrupted = !stopping && session.closedDuring == AapSession.State.RUNNING &&
            error is java.io.IOException && error !is javax.net.ssl.SSLException
        stopping = true
        main.post {
            main.removeCallbacks(sample)
            pcmQueue.close()
            audioEncoder.close()
            cluster.close()
            delivery.close()
            com.hsd.universal.dex.DesktopConnectionState.detach(delivery)
            main.removeCallbacks(deadline)
            if (session.pinRefusal != null || session.certificateRejected || session.closedDuring == AapSession.State.AUTH)
                (pins as? ProjectionIdentity)?.rejected(peer, session.pinRefusal?.fingerprint)
            val message = endMessage ?: if (oldUsb)
                context.getString(R.string.desktop_the_head_unit_continued_the_previous_usb_session_without)
            else if (session.pinRefusal != null)
                context.getString(R.string.desktop_the_stereo_certificate_has_changed_check_the_fingerprint_under)
            else if (session.certificateRejected || session.closedDuring == AapSession.State.AUTH)
                context.getString(R.string.desktop_the_head_unit_closed_the_connection_during_authorization_if)
            else context.getString(R.string.desktop_android_auto_is_completed, session.closedDuring, error?.message ?: reason)
            val cleanup = source
            source = null
            when {
                retiringSource -> finishAfterRetirement = { ended(message) }
                cleanup != null -> cleanup.close { main.post { ended(message) } }
                else -> ended(message)
            }
        }
    }

    private val encoders = mutableMapOf<Triple<Int, Int, MediaCodecType>, DesktopEncoderSupport.Encoding?>()
    @Synchronized private fun encoder(layout: AaVideoLayout, type: MediaCodecType): DesktopEncoderSupport.Encoding? {
        if (type != h264 && type != h265) return null
        val fps = if (prefs.useDesktopService) layout.fps else minOf(30, layout.fps)
        val key = Triple(layout.mode.aapId, fps, type)
        if (!encoders.containsKey(key)) encoders[key] = DesktopEncoderSupport.projectionEncoder(
            layout.mode.width, layout.mode.height, fps, prefs.desktopBitrateMbps * 1_000_000,
            if (type == h265) "video/hevc" else "video/avc")
        return encoders[key]
    }

    private fun publish(message: String) { main.post { if (!stopping) status(message) } }
    private companion object {
        val h264 = MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
        val h265 = MediaCodecType.MEDIA_CODEC_VIDEO_H265
        val avcHeaders = listOf(7, 8)
        val hevcHeaders = listOf(32, 33, 34)
        fun codecOf(c: VideoConfiguration, hasType: Boolean, type: MediaCodecType) =
            if (c.hasVideoCodecType()) c.videoCodecType else if (hasType) type else h264
        val audioCodecs = setOf(MediaCodecType.MEDIA_CODEC_AUDIO_PCM, MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC,
            MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_ADTS)
        val rates = setOf(48000, 44100, 32000, 24000, 22050)
    }
}
