package net.prototux.aare.aap.channels

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.UpdateUiConfigReplyOuterClass.UpdateUiConfigReply
import aap_protobuf.service.control.message.UpdateUiConfigRequestOuterClass.UpdateUiConfigRequest
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.shared.message.StopOuterClass.Stop
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import aap_protobuf.service.media.source.message.MicrophoneRequestOuterClass.MicrophoneRequest
import aap_protobuf.service.media.source.message.MicrophoneResponseOuterClass.MicrophoneResponse
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import aap_protobuf.service.media.video.message.VideoFocusNotificationOuterClass.VideoFocusNotification
import aap_protobuf.service.media.video.message.VideoFocusReasonOuterClass.VideoFocusReason
import aap_protobuf.service.media.video.message.VideoFocusRequestNotificationOuterClass.VideoFocusRequestNotification
import net.prototux.aare.aap.AapMessage
import net.prototux.aare.aap.MediaMsg
import net.prototux.aare.aap.Priority
import net.prototux.aare.aap.ServiceChannel
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Common part of the phone→head unit media streams (video and audio sinks on the head unit):
 * setup, config, start/stop, and ack-based flow control.
 *
 * The head unit tells us in its Config how many media messages may be in flight without an ack
 * ([maxUnacked]); every DATA message is acked. We never exceed that window.
 */
abstract class MediaSinkChannel(service: Service, name: String, private val codec: MediaCodecType) : ServiceChannel(service, name) {
    @Volatile var ready = false
        private set
    @Volatile var maxUnacked = 1
        private set
    @Volatile var started = false
        protected set
    protected var configIndices: List<Int> = emptyList()
    private val sessionCounter = AtomicInteger(0)
    @Volatile protected var mediaSession = 0
    protected val unacked = AtomicInteger(0)
    /**
     * Some head units don't ack (newer protocol versions), and some ack for a while then go quiet
     * mid-stream. Either way we eventually stop counting acks rather than stall the stream.
     */
    @Volatile var ackless = false
        private set
    /** Messages tried (sent or dropped) since the last ack, or since the stream started. */
    private val sinceAck = AtomicInteger(0)

    override fun onOpened() {
        requestSetup(codec)
    }

    protected fun requestSetup(type: MediaCodecType, priority: Int = Priority.CONTROL) {
        ready = false
        send(MediaMsg.SETUP, Setup.newBuilder().setType(type).build(), priority)
    }

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            MediaMsg.CONFIG -> {
                val cfg = Config.parseFrom(msg.bodyBytes())
                maxUnacked = if (cfg.hasMaxUnacked() && cfg.maxUnacked > 0) cfg.maxUnacked else 1
                configIndices = cfg.configurationIndicesList
                ready = cfg.status == Config.Status.STATUS_READY
                onConfig(cfg)
            }
            MediaMsg.ACK -> {
                val ack = Ack.parseFrom(msg.bodyBytes())
                val n = if (ack.hasAck()) ack.ack else 1
                // Older receivers omit the session id or send zero. A tagged ACK from a
                // retired START must not grant the new stream credits or confirm reception.
                if (!started || n <= 0 || (ack.sessionId != 0 && ack.sessionId != mediaSession)) return
                sinceAck.set(0)
                unacked.updateAndGet { maxOf(0, it - n) }
                onAcked()
            }
            else -> onOtherMessage(msg)
        }
    }

    /**
     * Send priority of this stream's DATA. START and STOP use it too, so they stay in order with
     * the data around them instead of overtaking it at control priority.
     */
    protected abstract val dataPriority: Int

    protected open fun onConfig(cfg: Config) {}
    protected open fun onAcked() {}
    protected open fun onOtherMessage(msg: AapMessage) {}

    /** Starts a media session with the given configuration index. */
    protected fun startStream(configIndex: Int) {
        mediaSession = sessionCounter.incrementAndGet()
        unacked.set(0)
        sinceAck.set(0)
        // AAP 6.x video and 5.x audio do not require per-frame ACKs (including LIVI).
        ackless = session.headUnitAtLeast(if (this is VideoChannel) 6 else 5, 0)
        send(MediaMsg.START, Start.newBuilder().setSessionId(mediaSession).setConfigurationIndex(configIndex).build(), dataPriority)
        started = true
    }

    protected fun stopStream() {
        if (!started) return
        started = false
        send(MediaMsg.STOP, Stop.getDefaultInstance(), dataPriority)
    }

    /**
     * Counts a message we tried to send (sent or dropped for lack of window). Head units may stop
     * acking: some never ack (newer protocol versions), some ack for a while then go quiet mid-
     * stream (#82: music plays a second, then the in-flight window fills and never drains, so every
     * chunk is dropped and the car falls silent until reconnect). Either way, once we are well past
     * the window with no ack in sight, stop waiting for acks so the stream keeps flowing. The
     * counter resets on every ack ([onMessage]), so a head unit that acks normally never trips it.
     * Dropped messages count too, or a full, stuck window would hide the stall.
     */
    protected fun noteAttempt() {
        if (!ackless && sinceAck.incrementAndGet() > 2 * maxUnacked + ACKLESS_AFTER) {
            ackless = true
            unacked.set(0)
        }
    }

    /** Whether the in-flight window has room for one more message. */
    protected fun canSend() = ackless || unacked.get() < maxUnacked

    /**
     * Sends a DATA message. [buf] must have 10 bytes of headroom before [offset] (id + timestamp),
     * which this fills in so the payload is never copied.
     */
    protected fun sendData(buf: ByteArray, offset: Int, length: Int, timestampUs: Long, priority: Int, onSent: (() -> Unit)? = null) {
        val start = offset - 10
        require(start >= 0) { "need 10 bytes of headroom" }
        buf[start] = (MediaMsg.DATA shr 8).toByte()
        buf[start + 1] = MediaMsg.DATA.toByte()
        for (i in 0 until 8) buf[start + 2 + i] = (timestampUs ushr (56 - 8 * i)).toByte()
        unacked.incrementAndGet()
        noteAttempt()
        if (ackless) unacked.set(0)
        val plain = if (start == 0) buf else buf.copyOfRange(start, offset + length)
        session.sendRaw(id, plain, length + 10, priority = priority, onSent = onSent)
    }

    protected fun sendCodecConfigData(data: ByteArray, offset: Int, length: Int, priority: Int) {
        val plain = ByteArray(2 + length)
        plain[0] = (MediaMsg.CODEC_CONFIG shr 8).toByte()
        plain[1] = MediaMsg.CODEC_CONFIG.toByte()
        System.arraycopy(data, offset, plain, 2, length)
        session.sendRaw(id, plain, priority = priority)
    }

    override fun onClosed() {
        ready = false
        started = false
    }

    companion object {
        const val ACKLESS_AFTER = 16
    }
}

/**
 * The projected display. The phone picks one of the head unit's video configurations, streams
 * H.264 and reacts to video focus (the driver switching to the car's own screens and back).
 */
class VideoChannel(
    service: Service,
    private val listener: Listener,
    /** H.264 Baseline unless the head unit only offers H.265 and the phone can encode it. */
    codec: MediaCodecType = MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP,
) : MediaSinkChannel(service, "video", codec) {

    @Volatile var codec: MediaCodecType = codec
        private set
    private val attemptedCodecs = mutableSetOf(codec)

    interface Listener {
        fun onUnavailable(message: String) {}
        fun acceptsLayout(config: VideoConfiguration): Boolean = true

        /** Choose among the head unit's configurations; return the index into [configs]. */
        fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int

        /** Start (or restart) the encoder for this configuration. */
        fun onVideoStart(index: Int, config: VideoConfiguration)

        /** Projection visible or not. When it becomes visible, send a key frame. */
        fun onVideoFocus(projected: Boolean)

        /** Frames were dropped to catch up: the next frame sent must be a key frame. */
        fun onKeyFrameNeeded()

        /** The complete access unit reached the transport writer. */
        fun onFrameWritten(keyFrame: Boolean, ptsUs: Long, bytes: Int) {}
        fun onReceiverConfirmed() {}

        /** The head unit changed the UI layout (insets, margins, theme) of the running stream. */
        fun onUiConfig(config: UiConfig) {}
    }

    val configs: List<VideoConfiguration> = service.mediaSinkService.videoConfigsList
    @Volatile var chosen: VideoConfiguration? = null
        private set
    /** The configuration index of the running stream, to START a new session on the same one. */
    @Volatile private var startIndex = 0
    @Volatile var focusMode: VideoFocusMode? = null
        private set
    // CONFIG/START opens the decoder. Some receivers report UI focus only after decoding video.
    val acceptsVideo get() = ready && started && focusMode?.let(::isProjected) != false
    @Volatile var receiverConfirmed = false
        private set
    private var submittedVideo = false
    private var videoGeneration = 0L
    private var wroteKeyFrame = false
    private var receiverEvidence = false
    @Volatile var videoExpectedSinceNanos: Long? = 0L
        private set

    private val pending = PendingVideoFrames(listener::onKeyFrameNeeded)
    private var outstandingWrites = 0
    private var writeStartedNanos: Long? = null
    private val lock = Any()
    @Volatile private var codecConfig: ByteArray? = null
    private val needKeyFrame get() = pending.needsKeyFrame
    val droppedFrames get() = pending.dropped

    // How well the head unit keeps up, for the phone's adaptive bitrate.
    /** Frames waiting for room in the ack window. */
    val pendingFrames get() = pending.size
    /** Times the backlog overflowed and was dropped (not the drops while waiting for a key frame). */
    val congestionDrops get() = pending.congestions
    /** Send to ack of the latest acked frame: transport, head-unit queue and decode. */
    @Volatile var ackLatencyMs = 0L
        private set
    /** Session queue to socket write of the latest frame: grows when the link (TCP) backs up. */
    @Volatile var sendLatencyMs = 0L
        private set
    /** Latest frame actually written to the receiver, used by the HSD audio/display lease. */
    @Volatile var lastFrameWriteNanos = 0L
        private set
    private val sentAt = ArrayDeque<Long>()

    override val openPriority = 0
    override val dataPriority = Priority.VIDEO

    override fun onConfig(cfg: Config) {
        if (!ready) return
        val index = listener.chooseConfiguration(configs, configIndices)
        val next = configs.getOrNull(index)
        if (next == null || (configIndices.isNotEmpty() && index !in configIndices)) {
            synchronized(lock) { chosen = null; stopStream(); clearPending() }
            listener.onUnavailable("У телефона и магнитолы нет общего режима в VIDEO CONFIG (индексы ${configIndices.joinToString()})")
            return
        }
        val type = if (next.hasVideoCodecType()) next.videoCodecType else service.mediaSinkService.let {
            if (it.hasAvailableType()) it.availableType else MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
        }
        synchronized(lock) {
            if (codec != type) {
                chosen = null
                stopStream()
                clearPending()
                if (attemptedCodecs.add(type)) {
                    codec = type
                    requestSetup(type, dataPriority)
                } else listener.onUnavailable("Магнитола не согласовала кодек ${type.name} при повторном VIDEO SETUP")
                return
            }
            attemptedCodecs.clear()
            attemptedCodecs.add(type)
            if (next != chosen) clearPending() else discardQueuedFrames()
            clearReceiverEvidence()
            chosen = next
            startIndex = index
            sentAt.clear()
            if (focusMode != VideoFocusMode.VIDEO_FOCUS_NATIVE) {
                startStream(index)
                if (acceptsVideo) codecConfig?.let { sendCodecConfigData(it, 0, it.size, dataPriority) }
            }
            if (focusMode == null) send(MediaMsg.VIDEO_FOCUS_REQUEST, VideoFocusRequestNotification.newBuilder()
                .setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED).setReason(VideoFocusReason.UNKNOWN).build(), dataPriority)
        }
        listener.onVideoStart(index, next)
    }

    override fun onOtherMessage(msg: AapMessage) {
        when (msg.id) {
            MediaMsg.VIDEO_FOCUS_NOTIFICATION -> {
                val n = VideoFocusNotification.parseFrom(msg.bodyBytes())
                if (n.hasFocus()) setFocus(n.focus)
            }
            MediaMsg.VIDEO_FOCUS_REQUEST -> {
                // Some head units ask us to change focus; we comply and confirm.
                val r = VideoFocusRequestNotification.parseFrom(msg.bodyBytes())
                if (!r.hasMode()) return
                send(MediaMsg.VIDEO_FOCUS_NOTIFICATION, VideoFocusNotification.newBuilder()
                    .setFocus(r.mode)
                    .setUnsolicited(false).build())
                setFocus(r.mode)
            }
            MediaMsg.UPDATE_UI_CONFIG_REQUEST -> {
                // The head unit changes the layout at runtime (insets, theme): apply it and
                // confirm with the config we now use, like the official phone.
                val ui = UpdateUiConfigRequest.parseFrom(msg.bodyBytes()).uiConfig
                val applied = synchronized(lock) {
                    val current = chosen ?: return
                    val merged = current.uiConfig.toBuilder().apply {
                        if (ui.hasMargins()) setMargins(ui.margins)
                        if (ui.hasStableContentInsets()) setStableContentInsets(ui.stableContentInsets)
                        if (ui.hasContentInsets()) setContentInsets(ui.contentInsets)
                        if (ui.hasUiTheme()) setUiTheme(ui.uiTheme)
                    }.build()
                    val next = current.toBuilder().setUiConfig(merged).build()
                    if (listener.acceptsLayout(next)) {
                        chosen = next
                        merged
                    } else current.uiConfig
                }
                listener.onUiConfig(applied)
                send(MediaMsg.UPDATE_UI_CONFIG_REPLY, UpdateUiConfigReply.newBuilder().setUiConfig(applied).build())
            }
            MediaMsg.UPDATE_UI_CONFIG_REPLY -> Unit
            else -> Unit
        }
    }

    /** Serializes the producer against a new START, including the codec header and first frame. */
    fun writeFor(config: VideoConfiguration, write: () -> Unit): Boolean = synchronized(lock) {
        if (chosen != config || !acceptsVideo) return false
        write()
        true
    }

    private fun discardQueuedFrames() = pending.clear()

    data class FlowStats(val queued: Int, val writing: Int, val unacknowledged: Int,
                         val queueAgeMs: Long, val writeAgeMs: Long, val ackAgeMs: Long,
                         val lastWriteMs: Long, val lastAckMs: Long, val ackless: Boolean,
                         val dropped: Long, val expired: Long) {
        val backlogMs get() = maxOf(queueAgeMs, writeAgeMs, ackAgeMs)
    }

    fun flowStats(): FlowStats = synchronized(lock) {
        val now = System.nanoTime()
        fun age(start: Long?) = start?.let { ((now - it) / 1_000_000).coerceAtLeast(0) } ?: 0L
        FlowStats(pending.size, outstandingWrites, unacked.get(), pending.oldestAgeMs(now),
            age(writeStartedNanos), if (ackless) 0 else age(sentAt.peekFirst()),
            sendLatencyMs, ackLatencyMs, ackless, pending.dropped, pending.expired)
    }

    private fun clearPending() {
        discardQueuedFrames()
        codecConfig = null
        sentAt.clear()
    }

    private fun clearReceiverEvidence() {
        videoGeneration++
        receiverConfirmed = false
        submittedVideo = false
        wroteKeyFrame = false
        receiverEvidence = false
    }

    private fun confirmIfReady() {
        if (!receiverConfirmed && acceptsVideo && wroteKeyFrame && (receiverEvidence || focusMode?.let(::isProjected) == true)) {
            receiverConfirmed = true
            listener.onReceiverConfirmed()
        }
    }

    /** Some receivers omit both video ACK and focus. Input on this display is receiver-side evidence. */
    fun noteDisplayInput() = synchronized(lock) {
        if (acceptsVideo && submittedVideo && focusMode != VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS) {
            receiverEvidence = true
            confirmIfReady()
        }
    }

    private fun setFocus(mode: VideoFocusMode) {
        val projected = isProjected(mode)
        var notifyFocus = true
        synchronized(lock) {
            val previous = focusMode
            if (previous == mode && !projected) return
            focusMode = mode
            if (projected) {
                if (videoExpectedSinceNanos == null) videoExpectedSinceNanos = System.nanoTime()
                if (!started && ready && chosen != null) {
                    sentAt.clear()
                    startStream(startIndex)
                }
                if (previous?.let(::isProjected) != true || previous == mode) {
                    // A repeated projected focus can follow a decoder reset without a native
                    // transition. Reuse START, but send headers and a decodable frame again.
                    if (previous == mode && (!acceptsVideo || needKeyFrame)) return
                    discardQueuedFrames()
                    if (acceptsVideo) codecConfig?.let { sendCodecConfigData(it, 0, it.size, dataPriority) }
                }
                notifyFocus = previous != mode
                confirmIfReady()
            } else {
                clearReceiverEvidence()
                videoExpectedSinceNanos = null
                discardQueuedFrames()
                if (mode == VideoFocusMode.VIDEO_FOCUS_NATIVE) stopStream()
            }
        }
        if (notifyFocus) listener.onVideoFocus(projected) else listener.onKeyFrameNeeded()
    }

    /** Asks the head unit to show its own UI (e.g. the driver chose "exit" on the phone). */
    fun requestNativeFocus() {
        send(MediaMsg.VIDEO_FOCUS_REQUEST, VideoFocusRequestNotification.newBuilder()
            .setMode(VideoFocusMode.VIDEO_FOCUS_NATIVE).setReason(VideoFocusReason.LAUNCH_NATIVE).build())
    }

    fun requestProjectedFocus() {
        send(MediaMsg.VIDEO_FOCUS_REQUEST, VideoFocusRequestNotification.newBuilder()
            .setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED).setReason(VideoFocusReason.UNKNOWN).build())
    }

    fun sendCodecConfig(data: ByteArray, offset: Int, length: Int) {
        codecConfig = data.copyOfRange(offset, offset + length)
        if (acceptsVideo) sendCodecConfigData(data, offset, length, Priority.VIDEO)
    }

    /**
     * Queues an encoded frame (with 10 bytes of headroom before [offset]). [recycle] gets the
     * buffer back once it has been written or dropped.
     */
    fun sendFrame(buf: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean, recycle: (ByteArray) -> Unit) {
        synchronized(lock) {
            if (!acceptsVideo) { recycle(buf); return }
            if (!canSend()) noteAttempt()
            pending.offer(PendingVideoFrames.Frame(buf, offset, length, ptsUs, keyFrame, System.nanoTime(), recycle))
            pump()
        }
    }

    override fun onAcked() {
        synchronized(lock) {
            if (submittedVideo && acceptsVideo) {
                receiverEvidence = true
                confirmIfReady()
            }
            // Acks are cumulative: whatever is beyond the in-flight count has been acked, and the
            // newest of those gives the latency.
            var acked = -1L
            while (sentAt.size > unacked.get()) acked = sentAt.removeFirst()
            if (acked >= 0) ackLatencyMs = (System.nanoTime() - acked) / 1_000_000
            pump()
        }
    }

    private fun pump() {
        // Only one access unit leaves this queue at a time. Otherwise the session's
        // separate writer queue hides an additional, irrevocable backlog of reference frames.
        while (acceptsVideo && pending.size > 0 && canSend() && outstandingWrites == 0) {
            val t = System.nanoTime()
            val p = pending.poll(t) ?: break
            writeStartedNanos = t
            outstandingWrites++
            val generation = videoGeneration
            submittedVideo = true
            if (ackless) sentAt.clear() else sentAt.addLast(t)
            sendData(p.buf, p.offset, p.length, p.ptsUs, Priority.VIDEO) {
                p.recycle(p.buf)
                val current = synchronized(lock) {
                    val active = videoGeneration == generation && acceptsVideo
                    if (active) {
                        lastFrameWriteNanos = System.nanoTime()
                        sendLatencyMs = (lastFrameWriteNanos - t) / 1_000_000
                        if (p.key) wroteKeyFrame = true
                        confirmIfReady()
                    }
                    outstandingWrites--
                    writeStartedNanos = null
                    pump()
                    active
                }
                if (current) listener.onFrameWritten(p.key, p.ptsUs, p.length)
            }
        }
    }

    override fun onClosed() {
        synchronized(lock) {
            super.onClosed()
            focusMode = null
            videoExpectedSinceNanos = 0L
            chosen = null
            clearReceiverEvidence()
            clearPending()
        }
    }

    companion object {
        /** Our projection is on screen, with or without input focus (native, transient native: not). */
        private fun isProjected(mode: VideoFocusMode) =
            mode == VideoFocusMode.VIDEO_FOCUS_PROJECTED || mode == VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS
    }
}

/** One of the head unit's audio outputs: media, guidance (navigation voice), system sounds, telephony. */
class AudioChannel(service: Service, private val listener: Listener? = null,
                   private val supports: (AudioConfiguration) -> Boolean = { true }) :
    MediaSinkChannel(service, "audio:${service.mediaSinkService.audioType}", service.mediaSinkService.availableType) {

    interface Listener {
        fun onAudioReady(channel: AudioChannel, config: AudioConfiguration)
        /** The car ran out of sound to play on this stream (reader thread). */
        fun onUnderflow(channel: AudioChannel) {}
    }

    val streamType: AudioStreamType = service.mediaSinkService.audioType
    override val dataPriority = Priority.AUDIO
    /** Underflows the car reported on this stream (0x800B). */
    @Volatile var underflows = 0
        private set

    override fun onOtherMessage(msg: AapMessage) {
        if (msg.id != MediaMsg.AUDIO_UNDERFLOW_NOTIFICATION) return
        // Body (session id) is optional in practice: some head units send it empty.
        underflows++
        listener?.onUnderflow(this)
    }
    /** CONFIG indices are the sink's ordered preferences. START must name the format actually encoded. */
    @Volatile private var selectedIndex: Int? = null
    val config: AudioConfiguration?
        get() = selectedIndex?.let { service.mediaSinkService.getAudioConfigs(it) }
    @Volatile var droppedChunks = 0L
        private set

    override fun onConfig(cfg: Config) {
        val formats = service.mediaSinkService.audioConfigsList
        selectedIndex = configIndices.ifEmpty { formats.indices.toList() }.firstOrNull { i ->
            i in formats.indices && supports(formats[i])
        }
        if (ready) config?.let { listener?.onAudioReady(this, it) }
    }

    /** Opens a playback session on the head unit (call when sound starts). */
    fun startPlayback() {
        if (ready && !started) selectedIndex?.let(::startStream)
    }

    /** Closes the session (call after a short silence, so the head unit can restore its own audio). */
    fun stopPlayback() = stopStream()

    /**
     * Sends a PCM chunk (10 bytes of headroom before [offset]). Returns false, dropping the chunk,
     * when the head unit is too far behind: audio must stay in real time.
     */
    /** Whether [sendPcm] would send a chunk now rather than drop it. */
    val hasRoom: Boolean get() = started && (ackless || unacked.get() < maxUnacked + SLACK)

    @JvmOverloads
    fun sendPcm(buf: ByteArray, offset: Int, length: Int, timestampUs: Long, onSent: (() -> Unit)? = null): Boolean {
        if (!started) return false
        if (!ackless && unacked.get() >= maxUnacked + SLACK) { droppedChunks++; noteAttempt(); return false }
        sendData(buf, offset, length, timestampUs, Priority.AUDIO, onSent)
        return true
    }

    companion object {
        /** Head units ack audio in bursts; allow a little beyond the window before dropping. */
        const val SLACK = 4
    }
}

/** The car's microphone (a media source on the head unit). */
class MicrophoneChannel(service: Service, private val listener: Listener) :
    ServiceChannel(service, "mic") {

    interface Listener {
        fun onMicReady(config: AudioConfiguration?) {}
        fun onMicOpened(ok: Boolean) {}
        /** PCM from the car (usually 16 kHz mono 16-bit). Called on the reader thread. */
        fun onMicData(data: ByteArray, offset: Int, length: Int, timestampUs: Long)
    }

    val config: AudioConfiguration? = service.mediaSourceService.audioConfig
    @Volatile var open = false
        private set
    /** We asked for the mic and haven't closed it since: a late answer must not reopen it. */
    @Volatile private var requested = false
    private var sessionId = 0

    override fun onOpened() {
        send(MediaMsg.SETUP, Setup.newBuilder().setType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM).build())
    }

    fun openMic() {
        requested = true
        send(MediaMsg.MICROPHONE_REQUEST, MicrophoneRequest.newBuilder().setOpen(true)
            .setAncEnabled(false).setEcEnabled(false).setMaxUnacked(2).build())
    }

    fun closeMic() {
        requested = false
        open = false
        send(MediaMsg.MICROPHONE_REQUEST, MicrophoneRequest.newBuilder().setOpen(false).build())
    }

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            MediaMsg.CONFIG -> listener.onMicReady(config)
            MediaMsg.MICROPHONE_RESPONSE -> {
                // An answer to a request since closed (or to the close itself) changes nothing.
                if (!requested) return
                val r = MicrophoneResponse.parseFrom(msg.bodyBytes())
                open = r.status == 0
                sessionId = r.sessionId
                listener.onMicOpened(open)
            }
            MediaMsg.DATA -> {
                if (msg.bodyLength < 8) return
                var ts = 0L
                for (i in 0 until 8) ts = (ts shl 8) or (msg.body[msg.bodyOffset + i].toLong() and 0xff)
                session.send(id, MediaMsg.ACK, Ack.newBuilder().setSessionId(sessionId).setAck(1).build(), priority = Priority.AUDIO)
                if (open) listener.onMicData(msg.body, msg.bodyOffset + 8, msg.bodyLength - 8, ts)
            }
            MediaMsg.CODEC_CONFIG -> Unit
        }
    }

    override fun onClosed() {
        open = false
        requested = false
    }
}
