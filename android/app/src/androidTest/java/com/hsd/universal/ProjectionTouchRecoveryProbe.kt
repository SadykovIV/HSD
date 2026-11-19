package com.hsd.universal

import android.app.Instrumentation
import android.app.Presentation
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Binder
import android.os.Process
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import com.hsd.universal.dex.DelegatedDesktopAccess
import com.hsd.universal.dex.DesktopModePolicy
import com.hsd.universal.dex.LocalDesktopDisplay
import com.hsd.universal.dex.OwnedDesktopDisplay
import com.hsd.universal.dex.VdmDisplayFamily
import com.hsd.universal.dex.VdmHostLink
import com.hsd.universal.dex.VdmPoolProtocol
import com.hsd.universal.qa.VdmProbeHost
import com.hsd.universal.dex.DisplayEventAccess
import com.hsd.universal.dex.ProjectionTouchDispatcher
import com.hsd.universal.aa.ProjectionTouchStream
import com.hsd.universal.hicar.forEachTouchProtocol
import java.util.concurrent.atomic.AtomicInteger

/** Keep the generated test scene separate from both the owner's apps and the phone power group. */
internal fun createTouchTestDisplay(test: Instrumentation, name: String, width: Int, height: Int,
    density: Int, surface: Surface): OwnedDesktopDisplay {
    val manager = test.targetContext.getSystemService(DisplayManager::class.java)
    val sdk = android.os.Build.VERSION.SDK_INT
    if (sdk < 37) {
        val vd = checkNotNull(manager.createVirtualDisplay(name, width, height, density, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION))
        return LocalDesktopDisplay(vd, Binder())
    }
    // Android 17 assigns the lock-independent group through VirtualDeviceManager.
    // Use the same authenticated display family as HSD. Only the test's marker is rendered.
    val ui = test.uiAutomation
    ui.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
        DelegatedDesktopAccess.ADD_ALWAYS_UNLOCKED_DISPLAY, DelegatedDesktopAccess.REAL_GET_TASKS, *VdmPoolProtocol.permissions)
    var owner: AutoCloseable? = null
    try {
        owner = VdmProbeHost.startDisplayOwner(test, ui)
        val family = VdmDisplayFamily(test.targetContext, VdmHostLink.openSession(Binder(), Process.myPid()))
        val mode = DesktopModePolicy.DRIVEDECK
        val parent = family.create(DesktopModePolicy.displayName(mode), surface, width, height, density,
            DesktopModePolicy.displayFlags(mode, sdk, independentLock = true), parent = true)
        return object : OwnedDesktopDisplay by parent {
            override fun release() {
                try { parent.release() } finally {
                    try { owner.close() } finally { ui.dropShellPermissionIdentity() }
                }
            }
        }
    } catch (failure: Throwable) {
        try { owner?.close() } finally { ui.dropShellPermissionIdentity() }
        throw failure
    }
}

/** Only the private test display receives events. The reader observes the actual composed pixel. */
internal fun checkProjectionTouchRecovery(test: Instrumentation): Bundle {
    val width = 640
    val height = 480
    val received = AtomicInteger()
    val red = AtomicInteger(-1)
    val thread = HandlerThread("qa-touch-pixel").apply { start() }
    try {
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).use { reader ->
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    val p = image.planes[0]
                    red.set(p.buffer.get(height / 2 * p.rowStride + width / 2 * p.pixelStride).toInt() and 255)
                }
            }, Handler(thread.looper))
            val vd = createTouchTestDisplay(test, "HSD touch QA", width, height, 160, reader.surface)
            val id = vd.display.displayId
            check(id > 0)
            var page: Presentation? = null
            val target = DisplayEventAccess()
            val reports = mutableListOf<String>()
            val send = { display: Int, e: MotionEvent ->
                check(display == id && vd.display.isValid)
                target.targetProjectionDisplay(e, id)
                test.uiAutomation.injectInputEvent(e, true)
            }
            val route = ProjectionTouchDispatcher(send, reports::add)
            var gestureStart = SystemClock.uptimeMillis()
            fun event(action: Int, vararg ids: Int, startX: Float = width * .5f,
                startY: Float = height * .5f, deliver: (MotionEvent) -> Unit) {
                val now = SystemClock.uptimeMillis()
                val props = Array(ids.size) { i -> MotionEvent.PointerProperties().apply {
                    this.id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coords = Array(ids.size) { i -> MotionEvent.PointerCoords().apply {
                    x = startX + i * 20; y = startY; pressure = 1f; size = 1f
                } }
                val e = MotionEvent.obtain(gestureStart, now, action, ids.size, props, coords, 0, 0, 1f, 1f,
                    0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { deliver(e) } finally { e.recycle() }
            }
            fun pixel(value: Int) {
                val deadline = SystemClock.uptimeMillis() + 4000
                while (red.get() != value && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(8)
                check(red.get() == value) { "display=$id expected red=$value observed=${red.get()} ups=${received.get()}" }
            }
            try {
                test.runOnMainSync {
                    page = Presentation(test.targetContext, vd.display).apply {
                        setContentView(object : View(context) {
                            init { setBackgroundColor(Color.rgb(32, 64, 96)) }
                            override fun onTouchEvent(e: MotionEvent): Boolean {
                                if (e.actionMasked == MotionEvent.ACTION_UP) {
                                    val n = received.incrementAndGet()
                                    setBackgroundColor(Color.rgb(32 + n * 8, 64, 96))
                                }
                                return true
                            }
                        })
                        show()
                    }
                }
                pixel(32)
                val result = Bundle()
                var taps = 0
                forEachTouchProtocol(width, height) { name, decode ->
                    // The lost connection delivered DOWN but never sent UP. Reconnect to this display.
                    val (seed, contact) = decode(0)
                    gestureStart = SystemClock.uptimeMillis()
                    event(seed, contact.id, startX = contact.x.toFloat(), startY = contact.y.toFloat()) {
                        check(send(id, it))
                    }
                    val stream = ProjectionTouchStream()
                    repeat(3) {
                        gestureStart = SystemClock.uptimeMillis()
                        for (action in 0..1) {
                            val (masked, p) = decode(action)
                            stream.send(masked, 0, listOf(p)) { a, _, points ->
                                val at = points.single()
                                event(a, at.id, startX = at.x.toFloat(), startY = at.y.toFloat()) { route.dispatch(id, it) }
                            }
                        }
                        pixel(32 + ++taps * 8)
                    }
                    result.putInt(name + "_visible_taps", 3)
                }
                // Retire a display lease during a two-finger gesture, then reuse the same ID.
                gestureStart = SystemClock.uptimeMillis()
                event(MotionEvent.ACTION_DOWN, 2) { route.dispatch(id, it) }
                event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, 7) { route.dispatch(id, it) }
                event(MotionEvent.ACTION_POINTER_UP, 2, 7) { route.dispatch(id, it) }
                route.finish(id)
                gestureStart = SystemClock.uptimeMillis()
                event(MotionEvent.ACTION_DOWN, 0) { route.dispatch(id, it) }
                event(MotionEvent.ACTION_UP, 0) { route.dispatch(id, it) }
                pixel(136)
                check(received.get() == 13)
                check(reports.none { "waiting" in it || "unavailable" in it || "CANCEL rejected" in it })
                return result.apply {
                    putInt("composed_taps", received.get())
                    putInt("recovered_down", reports.count { "recovered" in it })
                    putString("touch_target", "private display $id, interrupted USB gesture, two pointers, reuse")
                }
            } finally {
                route.finish(id)
                test.runOnMainSync { page?.dismiss() }
                vd.release()
                reader.setOnImageAvailableListener(null, null)
                // ImageReader.close() also invalidates an Image being sampled on Android 10.
                thread.quitSafely()
                thread.join()
            }
        }
    } finally { thread.quitSafely(); thread.join() }
}
