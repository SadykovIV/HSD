package com.hsd.universal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.hsd.universal.aa.CarlifeAudio
import com.hsd.universal.aa.TouchPoint
import com.hsd.universal.dex.DesktopConnectionState
import com.hsd.universal.dex.DesktopVideoDelivery
import com.hsd.universal.dex.DexPrefs
import com.hsd.universal.hicar.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class HiCarProjection(private val context: Context, private val lifetime: ProjectionResources, private val wired: Boolean,
    private val status: (String) -> Unit, private val ended: (String) -> Unit, private val ready: () -> Unit) :
    ProjectionSession, DesktopProjectionOutput {
    private val main = Handler(Looper.getMainLooper())
    private val closing = AtomicBoolean()
    private val first = AtomicBoolean(true)
    private val delivery = DesktopVideoDelivery()
    private var source: DesktopProjectionSource? = null
    @Volatile private var sender: HiCarSender? = null
    @Volatile var usbNetworkReady = false
        private set
    private data class Frame(val bytes: ByteArray, val pts: Long, val queued: Long)
    private val frames = ArrayBlockingQueue<Frame>(2)
    private var needKeyFrame = true
    private val encoder = AutoAudioEncoder(prefix = 0)
    private val audioQueue = AutoPcmQueue(synchronize = DexPrefs(context).synchronizeAudioVideo) { pcm, pts, current ->
        val output = sender
        if (current() && output?.audioReady == true) try {
            encoder.offer(pcm, 0, pts, checkNotNull(output.audioRate), true) { packet, time ->
                if (current()) output.audio(packet, time)
            }
        } catch (e: Exception) { fail(e) }
    }
    @Volatile private var writeStartedAt = 0L
    private val firstFrameDeadline = Runnable { if (first.get()) stop("HiCar: рабочий стол не передал первый кадр") }
    init {
        lifetime.own(encoder::close)
        lifetime.own(audioQueue::close)
    }
    override fun start() {
        DesktopConnectionState.attach(delivery)
        thread(name = "HiCar connection", isDaemon = true) {
            try {
                val endpoint = HiCarBootstrap(context, lifetime, status, ::fail).connect(wired) {
                    ProjectionCpuLease.hold(context, lifetime)
                    usbNetworkReady = true
                }
                status("HiCar · согласование изображения и звука")
                val link = endpoint.link
                val output = HiCarSender(lifetime, link.own, link.peer, endpoint.identity, endpoint.key.clone(),
                    link::connect, ::input, ::fail, link::bind)
                val video = output.start(endpoint.dataPort)
                sender = output
                main.post {
                    if (!closing.get()) try {
                        CarLifeState.auth = "HiChain · PIN"
                        CarLifeState.protocol = "Huawei HiCar"
                        source = DesktopProjectionSource(context, this, false, wired, ::stop).also {
                            delivery.start(SystemClock.elapsedRealtime())
                            main.postDelayed(firstFrameDeadline, 55_000)
                            it.open(video.width, video.height, video.fps, density = video.dpi, bitrateLimit = video.bitrate)
                        }
                        audioQueue.setPlaying(true)
                        startVideoWriter(output)
                    } catch (e: Exception) { fail(e) }
                }
            } catch (e: Exception) { fail(e) }
        }
    }
    private fun startVideoWriter(output: HiCarSender) {
        thread(name = "HiCar video writer", isDaemon = true) {
            try {
                while (!closing.get()) {
                    val frame = frames.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    writeStartedAt = SystemClock.elapsedRealtime()
                    val began = System.nanoTime()
                    val written = output.video(frame.bytes, frame.pts)
                    writeStartedAt = 0
                    val now = SystemClock.elapsedRealtime()
                    if (written) {
                        delivery.frameWritten(now)
                        audioQueue.videoWritten(frame.pts)
                        val elapsed = (System.nanoTime() - began).coerceAtLeast(1)
                        main.post { source?.tune(now, (now-frame.queued).toDouble(), frame.bytes.size * 1e9 / elapsed, false) }
                        if (first.compareAndSet(true, false)) {
                            ready()
                            main.removeCallbacks(firstFrameDeadline)
                            status("HiCar · рабочий стол подключён")
                        }
                    }
                }
            } catch (e: Exception) { fail(e) }
        }
        thread(name = "HiCar video watchdog", isDaemon = true) {
            while (!closing.get()) {
                Thread.sleep(500)
                val began = writeStartedAt
                if (began != 0L && SystemClock.elapsedRealtime() - began > 3000)
                    stop("HiCar: магнитола перестала принимать изображение")
            }
        }
    }
    override val repeatAvcParameterSets get() = true
    @Synchronized override fun video(payload: ByteArray, ptsUs: Long): Boolean {
        if (closing.get() || sender == null) return false
        val key = HiCarMedia.keyFrame(payload)
        if (needKeyFrame && !key) return true
        if (key) needKeyFrame = false
        val frame = Frame(payload.copyOf(), ptsUs, SystemClock.elapsedRealtime())
        if (!frames.offer(frame)) {
            frames.clear()
            needKeyFrame = !key
            if (key) frames.offer(frame)
            main.post {
                source?.requestKeyFrame()
                source?.tune(SystemClock.elapsedRealtime(), 150.0, 0.0, true)
            }
        }
        return true
    }
    override fun audio(type: Int, pcm: ByteArray, ptsUs: Long) = !closing.get() &&
        (type != CarlifeAudio.MEDIA_DATA || audioQueue.enqueue(pcm, ptsUs))
    override fun audioRate() = sender?.audioRate
    override fun audioOutputAvailable() = !closing.get() && !first.get() && sender?.audioReady == true
    override fun freshVideo() = delivery.isFlowing(SystemClock.elapsedRealtime())
    private fun input(event: HiCarMedia.Input) {
        main.post {
            if (closing.get()) return@post
            when (event.action) {
                0, 1, 2 -> source?.touch(when (event.action) {
                    0 -> MotionEvent.ACTION_DOWN; 1 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE
                }, 0, listOf(TouchPoint(0, event.x, event.y)))
                4 -> if (event.key == 3) source?.home() else source?.key(event.key)
            }
        }
    }
    override fun home() { source?.home() }
    override fun panel(off: Boolean) { source?.setPhoneScreenOff(off) }
    override fun carMenu() { stop("HiCar · сеанс завершён для возврата в меню магнитолы") }
    private fun fail(error: Exception) {
        if (!closing.get()) AppLog.e("HiCar connection failed", error)
        stop("HiCar · " + (error.message ?: error.javaClass.simpleName))
    }
    override fun stop(message: String) {
        if (!closing.compareAndSet(false, true)) return
        // Closing sockets interrupts blocked media I/O before releasing codecs on the worker.
        lifetime.close()
        frames.clear()
        main.post {
            main.removeCallbacks(firstFrameDeadline)
            delivery.close()
            DesktopConnectionState.detach(delivery)
            val current = source
            source = null
            sender = null
            current?.cancelTouch()
            current?.outputLost(!first.get(), carriedAudio = true)
            if (current == null) ended(message) else current.close(sessionEnded = true, hadPicture = !first.get()) { ended(message) }
        }
    }
}
