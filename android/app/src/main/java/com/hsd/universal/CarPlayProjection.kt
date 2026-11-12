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
) : ProjectionSession, DesktopProjectionOutput {
    private val main = Handler(Looper.getMainLooper())
    private val closing = AtomicBoolean(false)
    private val first = AtomicBoolean(true)
    private val delivery = DesktopVideoDelivery()
    private var desktop: DesktopProjectionSource? = null
    @Volatile private var air: CarPlayAirSession? = null
    private var display: CarPlayAirSession.Display? = null
    private val fingers = linkedMapOf<Int, TouchPoint>()
    private val frameTimeout = Runnable {
        if (first.get()) stop("CarPlay не получил первый кадр рабочего стола")
    }

    override fun start() {
        DesktopConnectionState.attach(delivery)
        thread(name = "CarPlay connection", isDaemon = true) {
            try {
                val endpoint = CarPlayBootstrap(context, lifetime, status).connect()
                status("CarPlay · проверяю сопряжение AirPlay")
                val session = CarPlayAirSession(context, endpoint, lifetime, ::input, ::stop)
                val geometry = session.start()
                main.post {
                    if (!closing.get()) {
                        try {
                            display = geometry
                            air = session
                            CarLifeState.auth = "iAP2 · Pair Verify"
                            CarLifeState.protocol = "CarPlay"
                            val source = DesktopProjectionSource(context, this, false, ::stop)
                            desktop = source
                            delivery.start(SystemClock.elapsedRealtime())
                            main.postDelayed(frameTimeout, 55_000)
                            source.open(geometry.width, geometry.height, geometry.fps)
                        } catch (e: Exception) {
                            stop(e.message ?: "Не удалось открыть рабочий стол")
                        }
                    }
                }
            } catch (e: Exception) {
                stop("CarPlay · " + (e.message ?: e.javaClass.simpleName))
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
                    main.removeCallbacks(frameTimeout)
                    val text =
                        "CarPlay · рабочий стол · ${display?.width} × ${display?.height} · H.264"
                    CarLifeState.video = text
                    status(text)
                }
            }
            written
        } catch (e: Exception) {
            stop(e.message ?: "Потерян видеоканал CarPlay")
            false
        }
    }

    override fun audio(type: Int, pcm: ByteArray, ptsUs: Long): Boolean {
        val sender = air?.audio ?: return false
        if (closing.get()) return false
        return try {
            if (type == CarlifeAudio.MEDIA_DATA) sender.offer(pcm)
            true
        } catch (e: Exception) {
            stop(e.message ?: "Потерян аудиоканал CarPlay")
            false
        }
    }

    override fun audioRate() = air?.audio?.rate

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
            val keymap = descriptor?.let(ConsumerKeys::parseDescriptor)
            if (keymap != null) {
                keymap
                    .usagesFor(report)
                    .map(ConsumerKeys::keyCodeFor)
                    .filter { it != 0 }
                    .forEach { desktop?.key(it) }
                return@post
            }
            val format = descriptor?.let(TouchReports::formatFromDescriptor)
            if (descriptor != null && format == null) return@post
            val contacts = TouchReports.parse(report, format) ?: return@post
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

    override fun stop(message: String) {
        if (!closing.compareAndSet(false, true)) return
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
