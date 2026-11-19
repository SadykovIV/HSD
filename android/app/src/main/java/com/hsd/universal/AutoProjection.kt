package com.hsd.universal

import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.hsd.universal.AnnexB
import com.hsd.universal.aa.DesktopEncoderSupport
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import com.hsd.universal.aa.CarlifeAudio
import com.hsd.universal.aa.TouchPoint
import com.hsd.universal.dex.DesktopModePolicy
import com.hsd.universal.dex.DexPrefs
import net.prototux.aare.aap.*
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.InputChannel
import net.prototux.aare.aap.channels.VideoChannel

/** AAP as the phone. The receiver selects channels and DriveDeck produces the actual pixels. */
internal class AutoProjection(private val context: Context, transport: Transport, identity: PhoneIdentity,
                              private val pins: PinStore, private val peer: String, private val status: (String) -> Unit,
                              private val ended: (String) -> Unit, private val ready: () -> Unit = {},
                              private val canReopenUsb: () -> Boolean = { false },
                              private val usbOpen: com.hsd.universal.ui.Actions.UsbOpen? = null,
                              private val onUsbNegotiation: () -> Unit = {}) :
    ProjectionSession, AapSession.Callbacks, VideoChannel.Listener, InputChannel.Listener, DesktopProjectionOutput {
    private val cluster = AutoCluster(context)
    private val wired = transport.usbAccessory
    private val certificateDates = PhoneCertificateDates(identity.certificate)
    private val transportLabel = when {
        wired -> "USB"
        peer.startsWith("bt|") -> "Bluetooth/Wi-Fi"
        peer.startsWith("lan|") -> "TCP"
        else -> "stream"
    }
    private val prefs = DexPrefs(context)
    private val profile = com.hsd.universal.dex.DesktopModePolicy.label(prefs.desktopMode)
    private val delivery = com.hsd.universal.dex.DesktopVideoDelivery()
    private val calibration = com.hsd.universal.link.ReceiverAudioCalibration(context, ProjectionRoute.selected(context))
    private val audioEncoder = AutoAudioEncoder()
    @Volatile private var audioEncoderFailed = false
    // Writer completions from a retired focus grant must not arm the next grant's unplug policy.
    private class AudioGrant {
        val delivered = java.util.concurrent.atomic.AtomicBoolean()
        var firstSignalUs: Long? = null // PCM worker only
        val onWritten: () -> Unit = {
            if (delivered.compareAndSet(false, true)) AppLog.i("AAP AUDIO first packet with playback signal written", false)
        }
    }
    @Volatile private var audioGrant: AudioGrant? = null
    private val pcmQueue = AutoPcmQueue(calibration, prefs.synchronizeAudioVideo) { data, timestamp, current ->
        val sink = playback
        val grant = audioGrant
        if (!stopping && grant != null && sink != null && current()) {
            val type = sink.service.mediaSinkService.availableType
            val valid = { current() && audioGrant === grant }
            val hasSignal = !grant.delivered.get() && (10 until data.size).any { data[it] != 0.toByte() }
            if (hasSignal && grant.firstSignalUs == null) grant.firstSignalUs = timestamp
            if (type == MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                sink.sendPcm(data, 10, data.size - 10, timestamp, timestamp + AutoPcmQueue.DEFAULT_MAX_LATENESS_US,
                    valid, if (hasSignal) grant.onWritten else null)
            else if (!audioEncoderFailed) sink.config?.let { config ->
                try {
                    audioEncoder.offer(data, 10, timestamp, config.samplingRate, type == MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_ADTS) { out, pts ->
                        val signal = grant.firstSignalUs
                        sink.sendPcm(out, 10, out.size - 10, pts, pts + AutoPcmQueue.DEFAULT_MAX_LATENESS_US, valid,
                            if (!grant.delivered.get() && signal != null && pts >= signal) grant.onWritten else null)
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
    private var lastFlowLogMs = 0L
    private var previousDrops = 0L
    private var lastSampleMs = 0L
    private val sample = object : Runnable {
        override fun run() {
            if (stopping) return
            val now = SystemClock.elapsedRealtime()
            val rate = writtenBytes.getAndSet(0) * 1000.0 / (now - lastSampleMs).coerceAtLeast(1)
            val drops = videoChannel.congestionDrops
            val flow = videoChannel.flowStats()
            source?.tune(now, flow.backlogMs.toDouble(), rate, drops != previousDrops)
            if (now - lastFlowLogMs >= 5000) {
                AppLog.i("AAP VIDEO flow: queued=${flow.queued} writing=${flow.writing} unacked=${flow.unacknowledged} " +
                    "ageMs=${flow.queueAgeMs}/${flow.writeAgeMs}/${flow.ackAgeMs} " +
                    "lastWriteMs=${flow.lastWriteMs} lastAckMs=${flow.lastAckMs} " +
                    "ackless=${flow.ackless} dropped=${flow.dropped} expired=${flow.expired}", false)
                playback?.let { audio -> AppLog.i("AAP AUDIO flow: ${pcmQueue.summary()} " +
                    "receiverUnderflows=${audio.underflows} unackedDrops=${audio.droppedChunks} " +
                    "guidanceUnderflows=${guidance?.channel?.underflows} guidanceWritten=${guidance?.writtenPackets?.get() ?: 0} " +
                    "guidanceDrops=${guidance?.channel?.droppedChunks ?: 0}", false) }
                lastFlowLogMs = now
            }
            lastSampleMs = now
            previousDrops = drops
            main.postDelayed(this, 100)
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private var touchReports = 0
    private var touchMoves = 0
    private var rejectedTouchReports = 0L
    private val session = AapSession(transport, identity,
        ProjectionDescription.create(context),
        this, options = SessionOptions(sendPings = !wired, pins = pins, peerKey = peer, trustOnFirstUse = true))
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
    private var guidance: AutoGuidanceAudio? = null
    private var guidanceFocusRequested = false
    private var guidanceAllowed = false
    private var audioRequested = false
    private var awaitingAudioFocus = false
    private val mediaGain = PcmGain()
    private var width = 0
    private var height = 0
    @Volatile private var runningConfig: VideoConfiguration? = null
    @Volatile private var activeLayout: AaVideoLayout? = null
    private var activeCodec: MediaCodecType? = null
    @Volatile private var codecConfig: ByteArray? = null
    private val selection = prefs.desktopVideoSelection()
    private val legacyTiles = prefs.desktopMode == DesktopModePolicy.DRIVEDECK && !DesktopModePolicy.supportsNativeTiles(context)
    @Volatile private var submitted = false
    @Volatile private var projectionEstablished = false
    private var endMessage: String? = null
    // A new surface can only start after the desktop service releases the previous one.
    private var retiringSource = false
    private var pendingConfiguration: VideoConfiguration? = null
    private var finishAfterRetirement: (() -> Unit)? = null
    private val deadline = Runnable {
        if (!submitted && (!::videoChannel.isInitialized || videoExpectedSinceNanos != null))
            stop(context.getString(R.string.desktop_in_55_seconds_the_radio_did_not_receive_the))
    }

    override fun home() { source?.home() }
    override fun carMenu() {
        if (!stopping && ::videoChannel.isInitialized && projectionEstablished) {
            AppLog.i("AAP: request native head unit menu", false)
            videoChannel.requestNativeFocus()
        }
    }
    override fun panel(off: Boolean) { source?.setPhoneScreenOff(off) }

    override fun start() {
        com.hsd.universal.dex.DesktopConnectionState.attach(delivery)
        if (!wired) main.postDelayed(deadline, 55_000)
        session.start()
    }
    override fun onFirstUsbRead(session: AapSession) {
        usbOpen?.log("first incoming bytes · ${session.discoveryReadProgress}")
    }
    override fun onWaitingForUsbVersion(session: AapSession) {
        main.post {
            if (!stopping && session.state == AapSession.State.VERSION) {
                CarLifeState.waitingForUsbVersion = true
                status(context.getString(R.string.aa_usb_wait_version))
                AppLog.i("Android Auto USB: waiting on the attached accessory for VERSION_REQUEST", false)
            }
        }
    }

    override fun onNegotiationStarted(session: AapSession) {
        if (wired) onUsbNegotiation()
        if (wired) main.post {
            if (!stopping) {
                CarLifeState.waitingForUsbVersion = false
                if (!submitted) main.postDelayed(deadline, 55_000)
            }
        }
    }
    override fun onDiscoveryRequestSent(session: AapSession) {
        AppLog.i("AAP discovery sent · ${session.discoveryRequestSummary} · ${session.negotiatedTls}", false)
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
        (pins as? ProjectionIdentity)?.let { identities ->
            identities.observeReceiver(peer, session.headUnitCertificates, session.tlsEstablished)
            identities.authenticated(peer, response.displayName.ifBlank {
                listOf(make, model).filter(String::isNotBlank).distinct().joinToString(" ")
            })
        }
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
        val audioOffers = response.channelsList.filter { it.hasMediaSinkService() && it.mediaSinkService.audioConfigsCount > 0 }
        AppLog.i("AAP audio offers: " + audioOffers.joinToString { ch ->
            val sink = ch.mediaSinkService
            "${ch.id}/${sink.audioType}/${sink.availableType} " +
                sink.audioConfigsList.joinToString("/") { "${it.samplingRate}x${it.numberOfChannels}x${it.numberOfBits}" }
        }, false)
        // Preserve receiver order within each codec. PCM avoids an extra codec and its
        // startup delay for short prompts on receivers that offer both PCM and AAC.
        val preferred = audioOffers.sortedBy { it.mediaSinkService.availableType != MediaCodecType.MEDIA_CODEC_AUDIO_PCM }
        playback = preferred.firstOrNull { ch ->
            val sink = ch.mediaSinkService
            sink.hasAudioType() && sink.audioType == AudioStreamType.AUDIO_STREAM_MEDIA &&
                sink.availableType in audioCodecs && sink.audioConfigsList.any(::supportsAudio)
        }?.let { AudioChannel(it, supports = ::supportsAudio) }
        guidance = preferred.firstOrNull { ch ->
            ch.mediaSinkService.hasAudioType() && ch.mediaSinkService.audioType == AudioStreamType.AUDIO_STREAM_GUIDANCE &&
                ch.mediaSinkService.availableType in audioCodecs &&
                ch.mediaSinkService.audioConfigsList.any(AutoGuidanceAudio::supports)
        }?.let { AutoGuidanceAudio(AudioChannel(it, supports = AutoGuidanceAudio::supports), ::guidanceEnded) }
        AppLog.i("AAP audio routes: media=${playback?.id} guidance=${guidance?.channel?.id} separateRequested=${prefs.separateNavigationAudio}", false)
        publish(context.getString(R.string.desktop_android_auto_connected_negotiating_the_display, profile))
        return listOfNotNull(videoChannel, input, playback, guidance?.channel?.takeIf { prefs.separateNavigationAudio }) +
            cluster.channels(response.channelsList)
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
                if (!videoChannel.receiverConfirmed) delivery.start(SystemClock.elapsedRealtime())
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
        updateFirstFrameDeadline()
        delivery.start(SystemClock.elapsedRealtime())
        width = w
        height = h
        runCatching {
            val encoding = requireNotNull(encoder(layout, type)) { context.getString(R.string.desktop_mode_encoder_unavailable, layout.mode.title, fps) }
            val desktop = DesktopProjectionSource(context, this, type == h265, wired, ::stop)
            activeLayout = layout
            activeCodec = type
            runningConfig = config
            source = desktop
            com.hsd.universal.AppLog.i("AAP VIDEO ${encoding.name} · frame=${w}x${h} · desktop=${layout.desktopWidth}x${layout.desktopHeight}" +
                " · viewport=${layout.content} · dpi=${layout.density}", false)
            status(context.getString(R.string.desktop_starting_6b56, profile, w, h))
            desktop.open(layout.desktopWidth, layout.desktopHeight, fps, layout, encoding)
            lastSampleMs = SystemClock.elapsedRealtime()
            main.postDelayed(sample, 1000)
        }.onFailure { stop(it.message ?: context.getString(R.string.desktop_failed_to_open, profile)) }
    }

    override fun onVideoFocus(projected: Boolean) {
        if (stopping) return
        if (!projected || videoChannel.focusMode == VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS) {
            source?.cancelTouch()
        }
        AppLog.i("AAP VIDEO focus=${videoChannel.focusMode} started=${videoChannel.started}", false)
        main.post { if (!stopping) updateFirstFrameDeadline() }
        if (projected) {
            if (!delivery.hasPicture()) delivery.start(SystemClock.elapsedRealtime())
            onKeyFrameNeeded()
        } else {
            CarLifeState.waitingForVideoReceiver = false
            val hadPicture = delivery.hasPicture()
            delivery.pause()
            main.post { source?.outputLost(hadPicture, carriedAudio = false) }
        }
    }
    private fun updateFirstFrameDeadline() {
        main.removeCallbacks(deadline)
        if (!submitted && videoChannel.videoExpectedSinceNanos != null) main.postDelayed(deadline, 55_000)
    }

    override val videoExpectedSinceNanos get() = videoChannel.videoExpectedSinceNanos
    override fun onKeyFrameNeeded() {
        // The AAP reader may hold the channel's lock; the encoder holds the desktop core's.
        main.post { if (!stopping) source?.requestKeyFrame() }
    }
    override fun video(payload: ByteArray, ptsUs: Long): Boolean {
        if (stopping || source == null) return false
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
        if (stopping || !videoChannel.acceptsVideo) return
        writtenBytes.addAndGet(bytes.toLong())
        pcmQueue.videoWritten(ptsUs)
        if (videoChannel.receiverConfirmed) delivery.frameWritten(SystemClock.elapsedRealtime())
        if (keyFrame && !submitted) {
            submitted = true
            main.removeCallbacks(deadline)
            com.hsd.universal.AppLog.i("Android Auto: первый ключевой кадр записан, $width × $height, $bytes байт")
            main.post {
                if (!stopping && videoChannel.acceptsVideo && !videoChannel.receiverConfirmed) {
                    CarLifeState.waitingForVideoReceiver = true
                    status(context.getString(R.string.aa_wait_receiver_video))
                }
            }
        }
    }

    override fun onReceiverConfirmed() {
        main.post {
            if (stopping || !videoChannel.receiverConfirmed || !videoChannel.acceptsVideo) return@post
            val ageMs = (System.nanoTime() - videoChannel.lastFrameWriteNanos).coerceAtLeast(0) / 1_000_000
            delivery.frameWritten(SystemClock.elapsedRealtime() - ageMs)
            projectionEstablished = true
            CarLifeState.waitingForVideoReceiver = false
            ready()
            AppLog.i("AAP VIDEO receiver confirmed · focus=${videoChannel.focusMode ?: "implicit"}", false)
            val message = "$profile · $width × $height · ${if (videoChannel.codec == h265) "H.265" else "H.264"}"
            com.hsd.universal.CarLifeState.video = message
            publish(message)
        }
    }
    override fun freshVideo() = !stopping && ::videoChannel.isInitialized && videoChannel.receiverConfirmed &&
        videoChannel.acceptsVideo && delivery.isFlowing(SystemClock.elapsedRealtime())

    override fun onInput(report: InputReport) {
        val desktop = source ?: return
        if (stopping || !videoChannel.acceptsVideo || !submitted ||
            videoChannel.focusMode == VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS) return
        if (report.touchEvent.pointerDataCount > 0 || report.keyEvent.keysCount > 0 || report.relativeEvent.dataCount > 0)
            videoChannel.noteDisplayInput()
        if (report.hasTouchEvent()) onTouch(desktop, report.touchEvent)
        // The desktop API injects a full down/up pair for a key.
        for (key in report.keyEvent.keysList) if (!key.down) {
            AppLog.i("AAP INPUT key=${key.keycode} action=up", false)
            if (key.keycode == KeyEvent.KEYCODE_HOME) desktop.home() else desktop.key(key.keycode)
        }
        for (rel in report.relativeEvent.dataList) if (rel.keycode == 65536) desktop.rotate(rel.delta)
    }

    private fun onTouch(desktop: DesktopProjectionSource, report: TouchEvent) {
        val action = report.action
        touchReports++
        val move = action == PointerAction.ACTION_MOVED
        if (move) touchMoves++
        val trace = (!move && touchReports <= 40) || (move && touchMoves == 1) || touchReports % 256 == 0
        fun reject(reason: String) {
            rejectedTouchReports++
            if (rejectedTouchReports <= 8 || rejectedTouchReports % 64 == 0L)
                AppLog.i("AAP touch rejected: count=$rejectedTouchReports action=${report.actionValue} reason=$reason", false)
        }
        if (action == PointerAction.UNRECOGNIZED) return reject("unknown action")
        if (action == PointerAction.ACTION_CANCEL) {
            desktop.cancelTouch()
            return
        }
        if (report.pointerDataCount !in 1..2) return reject("contacts=${report.pointerDataCount}")
        val active = desktop.hasActiveTouch
        if (!active && action != PointerAction.ACTION_DOWN) return reject("no active DOWN")
        val panel = input?.touchScreen ?: return reject("no touch panel in discovery")
        val layout = activeLayout ?: return reject("no active layout")
        val w = if (prefs.useDesktopService) layout.desktopWidth else com.hsd.universal.dex.DexVideoGeometry.WIDTH
        val h = if (prefs.useDesktopService) layout.desktopHeight else com.hsd.universal.dex.DexVideoGeometry.HEIGHT
        val points = report.pointerDataList.map {
            val (x, y) = layout.touch(it.x, it.y, panel.width, panel.height, w, h, clamp = active)
                ?: return reject("outside content: ${it.x},${it.y}; panel=${panel.width}x${panel.height} visible=${layout.visible} content=${layout.content}")
            TouchPoint(it.pointerId, x, y)
        }
        if (trace) AppLog.i("AAP touch: reports=$touchReports moves=$touchMoves action=${report.actionValue} " +
            "wire=${report.pointerDataList.map { "${it.pointerId}:${it.x},${it.y}" }} mapped=$points " +
            "panel=${panel.width}x${panel.height} target=${w}x$h repeatedDown=${desktop.repeatedTouchDowns} rejected=$rejectedTouchReports", false)
        desktop.touch(report.actionValue, report.actionIndex, points)
    }

    private fun supportsAudio(config: AudioConfiguration) =
        config.numberOfBits == 16 && config.numberOfChannels == 2 && config.samplingRate in rates

    override fun audioRate(): Int? = playback?.takeIf { it.ready && !audioEncoderFailed }?.config?.samplingRate
    override fun audioOutputAvailable() = !stopping && projectionEstablished && session.state == AapSession.State.RUNNING && audioRate() != null
    override fun navigationAvailable(): Boolean {
        val voice = guidance ?: return false
        if (stopping) return false
        session.openOptionalChannel(voice.channel)
        return voice.available
    }

    @Synchronized override fun navigation(pcm: ByteArray, ptsUs: Long, active: Boolean): Boolean {
        val voice = guidance ?: return false
        if (stopping) return false
        val wasActive = voice.active
        if (active && !wasActive && !guidanceAllowed && !guidanceFocusRequested) {
            guidanceFocusRequested = true
            awaitingAudioFocus = true
            session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK, Priority.AUDIO)
        }
        if (active && !guidanceAllowed && !awaitingAudioFocus) return false
        return voice.offer(pcm, ptsUs, active)
    }

    @Synchronized private fun guidanceEnded() {
        if (guidance?.active != true && guidanceFocusRequested && !stopping) {
            guidanceFocusRequested = false
            if (audioRequested) {
                awaitingAudioFocus = true
                session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN, Priority.AUDIO)
            } else {
                audioGrant = null
                awaitingAudioFocus = false
                session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, Priority.AUDIO)
            }
        }
    }

    @Synchronized override fun audio(type: Int, pcm: ByteArray, ptsUs: Long): Boolean {
        val sink = playback ?: return false
        if (stopping || audioRate() == null) return false
        when (type) {
            CarlifeAudio.MEDIA_STOP, CarlifeAudio.MEDIA_PAUSE -> {
                audioGrant = null
                pcmQueue.setPlaying(false)
                sink.stopPlayback()
                pcmQueue.resetCodec(audioEncoder::close)
                if (audioRequested) {
                    val stillSpeaking = guidance?.active == true
                    guidanceFocusRequested = stillSpeaking
                    session.requestAudioFocus(if (stillSpeaking) AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK
                        else AudioFocusRequestType.AUDIO_FOCUS_RELEASE, Priority.AUDIO)
                }
                audioRequested = false
                awaitingAudioFocus = false
            }
            CarlifeAudio.MEDIA_INIT, CarlifeAudio.MEDIA_RESUME -> {
                if (audioGrant == null && !awaitingAudioFocus) audioRequested = false
            }
            CarlifeAudio.MEDIA_DATA -> {
                if (!audioRequested) {
                    if (pcm.all { it == 0.toByte() }) return true
                    audioRequested = true
                    if (audioGrant == null && !guidanceFocusRequested) {
                        awaitingAudioFocus = true
                        AppLog.i("AAP AUDIO requesting media focus for playback", false)
                        session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN, Priority.AUDIO)
                    } else if (audioGrant != null) {
                        sink.startPlayback()
                        pcmQueue.setPlaying(true)
                    }
                }
                if (audioGrant == null && !awaitingAudioFocus) return false
                val adjusted = mediaGain.apply(pcm, sink.config!!.samplingRate, sink.config!!.numberOfChannels)
                val bytes = ByteArray(10 + adjusted.size)
                adjusted.copyInto(bytes, 10)
                return pcmQueue.enqueue(bytes, ptsUs)
            }
            else -> return false
        }
        return true
    }

    @Synchronized override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) {
        if (stopping) return
        awaitingAudioFocus = false
        AppLog.i("AAP AUDIO receiver focus=${notification.focusState}", false)
        val duck = notification.focusState == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK
        mediaGain.target = if (duck) 0.2 else 1.0
        val common = notification.focusState == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN ||
            notification.focusState == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT
        guidanceAllowed = common || notification.focusState == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY ||
            (duck && guidanceAllowed)
        guidance?.focus(guidanceAllowed)
        if (common || notification.focusState == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY || (duck && audioGrant != null)) {
            if (audioGrant == null) audioGrant = AudioGrant()
            if (audioRequested) {
                playback?.startPlayback()
                pcmQueue.setPlaying(true)
            }
        } else {
            audioGrant = null
            pcmQueue.setPlaying(false)
            playback?.stopPlayback()
            pcmQueue.resetCodec(audioEncoder::close)
        }
    }
    override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
        usbOpen?.log("closed in ${session.closedDuring}: $reason")
        val oldUsb = session.closedDuring == AapSession.State.VERSION && session.oldUsbFramesBeforeVersion > 0
        networkInterrupted = !stopping && session.closedDuring == AapSession.State.RUNNING &&
            error is java.io.IOException && error !is javax.net.ssl.SSLException
        retryUsbOpen = wired && !stopping && (oldUsb ||
            (error is java.io.IOException && session.closedDuring == AapSession.State.VERSION) || networkInterrupted)
        val hadPicture = projectionEstablished
        val lostUsbPicture = wired && !stopping && error is java.io.IOException && delivery.hasPicture()
        val carriedAudio = audioGrant?.delivered?.get() == true
        val interruptedAuth = !stopping && error != null &&
            (session.closedDuring == AapSession.State.HANDSHAKE || session.closedDuring == AapSession.State.AUTH)
        stopping = true
        main.post {
            if (lostUsbPicture) source?.outputLost(true, carriedAudio && !(retryUsbOpen && canReopenUsb()))
            if (wired) CarLifeState.waitingForUsbVersion = false
            CarLifeState.waitingForVideoReceiver = false
            main.removeCallbacks(sample)
            pcmQueue.close(audioEncoder::close)
            guidance?.close()
            cluster.close()
            delivery.close()
            com.hsd.universal.dex.DesktopConnectionState.detach(delivery)
            main.removeCallbacks(deadline)
            val authorization = session.authorization
            AppLog.i("Android Auto closed · transport=$transportLabel · phase=${session.closedDuring} · version=${session.headUnitVersion?.let { "${it.first}.${it.second}" } ?: "none"} · auth=${authorization?.description ?: "none"} · cause=$reason", false)
            if (wired && session.closedDuring == AapSession.State.VERSION)
                AppLog.i("Android Auto USB before VERSION_REQUEST · previous-session frames=${session.oldUsbFramesBeforeVersion} · ${session.discoveryReadProgress}", false)
            if (session.closedDuring == AapSession.State.DISCOVERY) AppLog.i(
                "AAP discovery: request=${session.discoveryRequestBytes}B written=${session.discoveryRequestWritten}; " +
                    "inbound=${session.discoveryInboundMessages} lastType=${session.discoveryLastMessage ?: "none"}; " +
                    session.discoveryReadProgress, false)
            if (interruptedAuth) (pins as? ProjectionIdentity)?.let { identities ->
                identities.rejected(peer, session.pinRefusal?.fingerprint)
                identities.observeReceiver(peer, session.headUnitCertificates, session.tlsEstablished)
            }
            val message = endMessage ?: if (oldUsb)
                context.getString(R.string.desktop_the_head_unit_continued_the_previous_usb_session_without)
            else if (session.pinRefusal != null)
                context.getString(R.string.desktop_the_stereo_certificate_has_changed_check_the_fingerprint_under)
            else if (authorization != null && authorization.code != 0) {
                val resource = when (authorization.status) {
                    MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED -> R.string.aa_auth_cert_expired
                    MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID -> R.string.aa_auth_cert_not_yet_valid
                    MessageStatus.STATUS_CERTIFICATE_ERROR -> R.string.aa_auth_certificate
                    MessageStatus.STATUS_AUTHENTICATION_FAILURE -> R.string.aa_auth_unspecified
                    MessageStatus.STATUS_BUSY -> R.string.aa_auth_busy
                    else -> R.string.aa_auth_rejected
                }
                when (resource) {
                    R.string.aa_auth_cert_expired, R.string.aa_auth_cert_not_yet_valid ->
                        context.getString(resource, authorization.description) + " " +
                            context.getString(R.string.aa_check_certificate_dates, certificateDates.from, certificateDates.until, java.time.Instant.now())
                    else -> context.getString(resource, authorization.description)
                }
            }
            else if (wired && session.closedDuring == AapSession.State.VERSION && error is java.io.IOException)
                context.getString(R.string.aa_usb_closed_before_version, reason)
            else if (session.closedDuring == AapSession.State.HANDSHAKE || session.closedDuring == AapSession.State.AUTH)
                context.getString(R.string.aa_handshake_interrupted, session.closedDuring.name, reason)
            else context.getString(R.string.desktop_android_auto_is_completed, session.closedDuring, reason)
            val cleanup = source
            source = null
            when {
                retiringSource -> finishAfterRetirement = { ended(message) }
                cleanup != null -> cleanup.close(sessionEnded = true, hadPicture = hadPicture) { main.post { ended(message) } }
                else -> ended(message)
            }
        }
    }

    private val encoders = mutableMapOf<Triple<Int, Int, MediaCodecType>, DesktopEncoderSupport.Encoding?>()
    @Synchronized private fun encoder(layout: AaVideoLayout, type: MediaCodecType): DesktopEncoderSupport.Encoding? {
        if (type != h264 && type != h265) return null
        val fps = if (prefs.useDesktopService) com.hsd.universal.aa.DesktopVideoQuality.chooseFps(prefs.desktopFps, layout.fps) else minOf(30, layout.fps)
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
