package com.hsd.universal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.hsd.universal.CarLifeState
import com.hsd.universal.aa.CarlifeAudio
import com.hsd.universal.aa.TouchPoint
import com.hsd.universal.dex.DesktopConnectionState
import com.hsd.universal.dex.DesktopVideoDelivery
import com.hsd.universal.carplay.*
import io.github.shaonialife.cardeck.input.ConsumerKeys
import io.github.shaonialife.cardeck.input.TouchReports
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class CarPlayProjection(
    private val context: Context,
    private val lifetime: ProjectionResources,
    private val status: (String) -> Unit,
    private val ended: (String) -> Unit,
    private val ready: () -> Unit = {},
) : ProjectionSession, DesktopProjectionOutput {
    private val main = Handler(Looper.getMainLooper())
    private val closing = AtomicBoolean(false)
    private val first = AtomicBoolean(true)
    @Volatile var linkInterrupted = false
        private set
    @Volatile var firstFrameAt: Long? = null
        private set
    private val delivery = DesktopVideoDelivery()
    private var desktop: DesktopProjectionSource? = null
    @Volatile private var air: CarPlayAirSession? = null
    private var display: CarPlayAirSession.Display? = null
    private class Controls(val descriptor: ByteArray) {
        val touch = CarPlayTouchLayout.parse(descriptor)
        val keys = ConsumerKeys.parseDescriptor(descriptor)
    }
    private val controls = mutableMapOf<String, Controls>()
    private val fingers = linkedMapOf<Int, TouchPoint>()
    private val frameTimeout = Runnable {
        if (first.get()) stop(context.getString(R.string.desktop_carplay_did_not_receive_the_first_desktop_frame))
    }

    override fun start() {
        DesktopConnectionState.attach(delivery)
        thread(name = "CarPlay connection", isDaemon = true) {
            try {
                val endpoint = CarPlayBootstrap(context, lifetime, status).connect()
                status(context.getString(R.string.desktop_carplay_checking_airplay_pairing))
                val session = CarPlayAirSession(context, endpoint, lifetime, ::input, ::linkEnded)
                val geometry = session.start()
                main.post {
                    if (!closing.get()) {
                        try {
                            display = geometry
                            air = session
                            CarLifeState.auth = "iAP2 · Pair Verify"
                            CarLifeState.protocol = "CarPlay"
                            val source = DesktopProjectionSource(context, this, false, false, ::stop)
                            desktop = source
                            delivery.start(SystemClock.elapsedRealtime())
                            main.postDelayed(frameTimeout, 55_000)
                            source.open(geometry.width, geometry.height, geometry.fps)
                        } catch (e: Exception) {
                            stop(e.message ?: context.getString(R.string.desktop_could_not_open_the_desktop))
                        }
                    }
                }
            } catch (e: Exception) {
                linkEnded(e)
            }
        }
    }

    override fun video(payload: ByteArray, ptsUs: Long): Boolean {
        val session = air ?: return false
        if (closing.get()) return false
        return try {
            val written = session.video.send(payload, ptsUs)
            if (written) {
                delivery.frameWritten(SystemClock.elapsedRealtime())
                if (first.compareAndSet(true, false)) {
                    firstFrameAt = SystemClock.elapsedRealtime()
                    ready()
                    main.removeCallbacks(frameTimeout)
                    val text =
                        context.getString(R.string.desktop_carplay_desk_h_264, display?.width, display?.height)
                    CarLifeState.video = text
                    status(text)
                }
            }
            written
        } catch (e: Exception) {
            linkEnded(e)
            false
        }
    }

    override fun audio(type: Int, pcm: ByteArray, ptsUs: Long): Boolean {
        val sender = air?.audio ?: return false
        if (closing.get()) return false
        return try {
            if (type == CarlifeAudio.MEDIA_DATA) sender.offer(pcm, ptsUs)
            true
        } catch (e: Exception) {
            linkEnded(e)
            false
        }
    }

    override fun audioRate() = air?.audio?.rate
    override fun audioOutputAvailable() = !closing.get() && !first.get() && air?.audio != null

    override fun freshVideo() = !closing.get() && delivery.isFlowing(SystemClock.elapsedRealtime())

    private fun input(command: Map<*, *>) {
        if (command["type"] != "hidSendReport") return
        val params = command["params"] as? Map<*, *> ?: command
        val report = params["hidReport"] as? ByteArray ?: return
        val id = params["uuid"]?.toString() ?: return
        main.post {
            if (closing.get()) return@post
            val g = display ?: return@post
            val descriptor = (params["descriptor"] as? ByteArray) ?: g.hid[id]
            val device = descriptor?.let { desc ->
                controls[id]?.takeIf { it.descriptor === desc || it.descriptor.contentEquals(desc) }
                    ?: Controls(desc).also { controls[id] = it }
            }
            val contacts = device?.touch?.contacts(report, g.width, g.height)
                ?: if (device == null) TouchReports.parse(report) else null
            if (contacts == null) {
                device?.keys
                    ?.usagesFor(report)
                    ?.map(ConsumerKeys::keyCodeFor)
                    ?.filter { it != 0 }
                    ?.forEach { desktop?.key(it) }
                return@post
            }
            for (c in contacts) {
                val old = fingers[c.slot]
                if (old == null && !c.down) continue
                val point =
                    TouchPoint(
                        c.slot,
                        c.xPixels.coerceIn(0, g.width - 1),
                        c.yPixels.coerceIn(0, g.height - 1),
                    )
                fingers[c.slot] = point
                val points = fingers.values.toList()
                val index = points.indexOf(point)
                val action =
                    when {
                        !c.down ->
                            if (points.size == 1) MotionEvent.ACTION_UP
                            else MotionEvent.ACTION_POINTER_UP
                        old == null ->
                            if (points.size == 1) MotionEvent.ACTION_DOWN
                            else MotionEvent.ACTION_POINTER_DOWN
                        else -> MotionEvent.ACTION_MOVE
                    }
                desktop?.touch(action, index, points)
                if (!c.down) fingers.remove(c.slot)
            }
        }
    }

    override fun home() {
        desktop?.home()
    }

    override fun panel(off: Boolean) {
        desktop?.setPhoneScreenOff(off)
    }

    private fun linkEnded(error: Exception) {
        if (!closing.get()) {
            AppLog.e("CarPlay channel failed", error)
            AppLog.detail("CarPlay failure origin: " + error.stackTrace.take(8).joinToString(" ← "))
        }
        end("CarPlay · " + (error.message ?: error.javaClass.simpleName), error.carPlayLinkInterrupted())
    }
    override fun stop(message: String) = end(message, false)

    private fun end(message: String, interrupted: Boolean) {
        if (!closing.compareAndSet(false, true)) return
        linkInterrupted = interrupted
        lifetime.close()
        main.post {
            main.removeCallbacks(frameTimeout)
            delivery.close()
            DesktopConnectionState.detach(delivery)
            val source = desktop
            desktop = null
            air = null
            if (source == null) ended(message) else source.close { ended(message) }
        }
    }
}
