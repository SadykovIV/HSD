package com.hsd.universal.dex

import android.app.ActivityManager
import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.os.Parcel
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Fixed native slots, created once by shell with tokens owned by our zygote process.
 * A projection lease parks a slot; only activation retirement destroys native resources.
 * The public component contract still acknowledges release only after its Surface is detached.
 */
@android.annotation.TargetApi(37)
internal class AppOwnedVdmPool(private val context: Context) : IVdmDesktopHost.Stub() {
    private val lock = Any()
    private val owner = Binder()
    private val connected = CountDownLatch(1)
    @Volatile private var tokens: IVdmPoolTokens? = null
    @Volatile private var closed = false
    private var retired = false
    @Volatile private var installed = false
    private var bound = false
    private var associationId = -1
    private var group = -1
    private var active: Session? = null
    private var parkingThread: HandlerThread? = null
    private var drainThread: HandlerThread? = null
    private val slots = LinkedHashMap<String, Slot>()
    private val dms by lazy { VdmPoolApi.remote("android.hardware.display.IDisplayManager", "display") }
    private val power = context.getSystemService(PowerManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val tasks = context.getSystemService(ActivityManager::class.java)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) return
            tokens = IVdmPoolTokens.Stub.asInterface(service)
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { installed = false }
        override fun onBindingDied(name: ComponentName) { installed = false }
        override fun onNullBinding(name: ComponentName) { connected.countDown() }
    }
    val lifetime: IBinder? get() = tokens?.asBinder()
    val tokenPid: Int get() = checkNotNull(tokens).ownerPid()
    val ready: Boolean get() = installed && !closed && lifetime?.isBinderAlive == true
    private fun requireClient() = check(Binder.getCallingUid() == Process.myUid()) { "Foreign VDM pool client" }

    fun prepareTokens(): Bundle {
        check(!closed && !bound)
        check(VdmPoolProtocol.permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
        bound = context.bindService(Intent(context, VdmPoolTokensService::class.java),
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT, java.util.concurrent.Executor { it.run() }, connection)
        check(bound && connected.await(4, TimeUnit.SECONDS)) { "Display lifetime owner did not start" }
        val transactionName = Class.forName("android.hardware.display.IVirtualDisplayCallback\$Stub")
            .getMethod("getDefaultTransactionName", Int::class.javaPrimitiveType)
        val code = (1..32).single { transactionName.invoke(null, it) == "onStopped" }
        return checkNotNull(tokens).claim(owner, code)
    }

    /** Authenticated bootstrap metadata is checked against actual DMS ownership before acceptance. */
    fun install(offer: Bundle, callbacks: Bundle) = asApp {
        synchronized(lock) {
            check(!closed && !installed && lifetime?.isBinderAlive == true)
            val ids = checkNotNull(offer.getIntArray("displayIds"))
            check(ids.size == VdmPoolProtocol.names.size && ids.all { it > 0 } && ids.toSet().size == ids.size)
            associationId = offer.getInt("associationId", -1)
            check(associationId > 0)
            VdmPoolProtocol.names.forEachIndexed { index, name ->
                val callback = VdmPoolApi.iface("android.hardware.display.IVirtualDisplayCallback",
                    checkNotNull(callbacks.getBinder("displayToken$index")))
                val slot = Slot(ids[index], name, callback, ImageReader.newInstance(128, 128, PixelFormat.RGBA_8888, 2))
                slots[name] = slot
                drainParking(slot.parking)
                val info = nativeInfo(slot)
                val actual = info.javaClass.getField("displayGroupId").getInt(info)
                check(actual > 0 && (group < 0 || group == actual))
                group = actual
                check(DesktopDisplayIsolation.independent(info.javaClass.getField("flags").getInt(info), actual))
            }
            // No producer has ever been attached: verify token operations and actual geometry.
            // An empty display need not compose a frame. Its parking consumer remains retained
            // until native retirement; real producer handoffs below require a replacement frame.
            for (slot in slots.values) {
                VdmPoolApi.call(dms, "resizeVirtualDisplay", slot.callback, 130, 128, 160)
                VdmPoolApi.call(dms, "setVirtualDisplaySurface", slot.callback, slot.parking.surface)
            }
            wake()
            for (slot in slots.values) {
                awaitGeometry(slot, 130, 128, 160)
                VdmPoolApi.call(dms, "resizeVirtualDisplay", slot.callback, 128, 128, 160)
                awaitGeometry(slot, 128, 128, 160)
            }
            sleep(); installed = true
        }
    }

    private fun nativeInfo(slot: Slot): Any {
        val info = checkNotNull(VdmPoolApi.call(dms, "getDisplayInfo", slot.id)) { "Independent display was removed" }
        check(info.javaClass.getField("ownerUid").getInt(info) == VdmHostPolicy.SHELL_UID &&
            info.javaClass.getField("name").get(info) == slot.name &&
            info.javaClass.getField("type").getInt(info) == 5) { "Independent display ownership changed" }
        val actualGroup = info.javaClass.getField("displayGroupId").getInt(info)
        check(actualGroup > 0 && (group < 0 || actualGroup == group) &&
            DesktopDisplayIsolation.independent(info.javaClass.getField("flags").getInt(info), actualGroup)) {
            "Independent display isolation changed"
        }
        return info
    }
    private fun wake() {
        val id = slots.values.first().id
        nativeInfo(slots.values.first())
        VdmPoolApi.call(power, "wakeUp", SystemClock.uptimeMillis(), 2 /* APPLICATION */, "HSD:independent-display", id)
    }
    private fun sleep() {
        if (slots.isNotEmpty() && lifetime?.isBinderAlive == true) {
            nativeInfo(slots.values.first())
            VdmPoolApi.call(power, "goToSleep", slots.values.first().id, SystemClock.uptimeMillis(), 0, 0)
        }
    }
    private fun awaitParking(parked: List<Slot>, timeoutMs: Long) {
        val pending = parked.toMutableSet(); val until = SystemClock.elapsedRealtime() + timeoutMs
        while (pending.isNotEmpty() && SystemClock.elapsedRealtime() < until && lifetime?.isBinderAlive == true) {
            // Continue consuming ACKed displays too. A full output queue can block
            // SurfaceFlinger and prevent the remaining displays from submitting an ACK.
            parked.forEach { slot ->
                slot.parking.acquireLatestImage()?.let { it.close(); pending.remove(slot) }
            }
            if (pending.isNotEmpty()) Thread.sleep(5)
        }
        check(pending.isEmpty()) { "Native display did not acknowledge Surface replacement: " +
            pending.joinToString { "${it.id}/${it.name} state=${displayManager.getDisplay(it.id)?.state}" } }
    }
    private fun parkingHandler(): Handler {
        val thread = parkingThread ?: HandlerThread("hsd-vdm-park").also { it.start(); parkingThread = it }
        return Handler(thread.looper)
    }
    private fun drainParking(reader: ImageReader) {
        // Never drain on the Presentation looper: hardware drawing may wait for
        // SurfaceFlinger, which in turn needs these output buffers to be consumed.
        val thread = drainThread ?: HandlerThread("hsd-vdm-drain").also { it.start(); drainThread = it }
        reader.setOnImageAvailableListener({ source -> runCatching { source.acquireLatestImage()?.close() } }, Handler(thread.looper))
    }
    private fun awaitGeometry(slot: Slot, width: Int, height: Int, density: Int) {
        // DMS's native resize precedes WindowManager's logical override and animations.
        // Reusing a display requires both to agree before a new client receives its ID.
        // Commit input/window geometry, without waiting for every app animation on
        // every display. That global wait can otherwise block this input worker for
        // seconds because of an unrelated app's launch or phone-side transition.
        VdmPoolApi.call(VdmPoolApi.remote("android.view.IWindowManager", "window"), "syncInputTransactions", false)
        val until = SystemClock.elapsedRealtime() + 1500
        var observed = "unknown"
        do {
            val info = nativeInfo(slot)
            val mode = checkNotNull(VdmPoolApi.call(info, "getMode")) as android.view.Display.Mode
            observed = "${info.javaClass.getField("logicalWidth").getInt(info)}x${info.javaClass.getField("logicalHeight").getInt(info)}@${info.javaClass.getField("logicalDensityDpi").getInt(info)} native=${mode.physicalWidth}x${mode.physicalHeight}"
            if (info.javaClass.getField("logicalWidth").getInt(info) == width &&
                info.javaClass.getField("logicalHeight").getInt(info) == height &&
                info.javaClass.getField("logicalDensityDpi").getInt(info) == density &&
                mode.physicalWidth == width && mode.physicalHeight == height) return
            Thread.sleep(5)
        } while (SystemClock.elapsedRealtime() < until && lifetime?.isBinderAlive == true)
        error("Native display ${slot.id} geometry was not applied: $observed expected=${width}x$height@$density")
    }
    private fun withParkingWindows(leased: List<Slot>, handoff: () -> Unit) {
        // An empty or already-finished task has nothing to compose. Draw a private black
        // presentation on the retiring displays so the replacement consumer acknowledges
        // the actual native handoff even when no application is drawing anymore.
        // A private looper keeps retirement independent of the phone UI (including Stop).
        val renderer = parkingHandler()
        val windows = ArrayList<Presentation>() // accessed only on the parking looper
        val submitted = CountDownLatch(leased.size)
        val show = FutureTask {
            leased.forEach { slot ->
                nativeInfo(slot)
                Presentation(context, checkNotNull(displayManager.getDisplay(slot.id)),
                    android.R.style.Theme_Material_NoActionBar).also { window ->
                    windows.add(window)
                    window.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    window.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window.setContentView(object : View(window.context) {
                        private var phase = false
                        override fun onDraw(canvas: android.graphics.Canvas) {
                            // A changing buffer supplies damage even if the previous app was
                            // already black. No continuous rendering survives window dismissal.
                            phase = !phase
                            canvas.drawColor(if (phase) android.graphics.Color.BLACK else 0xff010101.toInt())
                            postInvalidateOnAnimation()
                        }
                    })
                    window.window?.setWindowAnimations(0)
                    // Ensure the producer has submitted a real buffer before closing
                    // the app. Native output acknowledgement is checked separately.
                    window.window?.decorView?.viewTreeObserver?.registerFrameCommitCallback {
                        submitted.countDown()
                    }
                    window.show()
                }
            }
        }
        try {
            check(renderer.post(show))
            show.get(3, TimeUnit.SECONDS)
            check(submitted.await(1500, TimeUnit.MILLISECONDS)) { "Parking windows did not submit their first frame" }
            handoff()
        } finally {
            // Ordered after show even if its caller timed out; no orphan windows.
            val dismiss = FutureTask { windows.forEach { it.dismiss() } }
            check(renderer.post(dismiss)); dismiss.get(3, TimeUnit.SECONDS)
        }
    }
    @Suppress("DEPRECATION")
    private fun clearTasks(ids: Set<Int>) {
        check(ids.isNotEmpty() && ids.all { id -> slots.values.any { it.id == id } })
        val am = VdmPoolApi.remote("android.app.IActivityManager", "activity")
        val until = SystemClock.elapsedRealtime() + 1000
        do {
            val all = tasks.getRunningTasks(1000)
            check(all.size < 1000) { "Cannot confirm the complete display task list" }
            val own = all.filter { it.javaClass.getField("displayId").getInt(it) in ids }
            if (own.isEmpty()) return
            own.forEach { VdmPoolApi.call(am, "removeTask", it.taskId) }
            Thread.sleep(10)
        } while (SystemClock.elapsedRealtime() < until)
        error("Retired display tasks did not finish")
    }
    private fun park(leased: List<Slot>) {
        if (leased.isEmpty()) return
        // Establish a visible producer before closing the apps. Starting the
        // handoff window inside their CLOSE transition can leave its first frame
        // hidden until that transition's unrelated activities finish drawing.
        withParkingWindows(leased) {
            handoffParking(leased)
            leased.forEach { it.surface?.release(); it.surface = null }
            clearTasks(leased.map { it.id }.toSet())
        }
        // The client Surface is already detached and all tasks/windows are gone.
        // Idle geometry may follow WMS's CLOSE transition asynchronously; it is not
        // part of releasing the client. A subsequent lease verifies both native and
        // logical geometry before its display ID is handed out. Parking consumers
        // are already 128x128, so that transition retains no full-size output queue.
        leased.forEach {
            VdmPoolApi.call(dms, "resizeVirtualDisplay", it.callback, 128, 128, 160)
            it.parking.discardFreeBuffers()
        }
    }
    private fun handoffParking(leased: List<Slot>) {
        if (leased.isEmpty()) return
        // Old consumers stay retained until a frame arrives at every replacement consumer.
        for (slot in leased) {
            nativeInfo(slot)
            // A fresh consumer cannot acknowledge a stale frame from a previous lease.
            slot.oldParking.add(slot.parking)
            slot.parking = ImageReader.newInstance(128, 128, PixelFormat.RGBA_8888, 2)
            VdmPoolApi.call(dms, "setVirtualDisplaySurface", slot.callback, slot.parking.surface)
        }
        wake()
        awaitParking(leased, 1500)
        // Keep a real consumer attached. Null surfaces generate asynchronous display-off
        // transitions which can overtake the next lease on the same native display ID.
        leased.forEach { drainParking(it.parking) }
        leased.forEach {
            it.oldParking.forEach(ImageReader::close); it.oldParking.clear()
        }
    }

    override fun protocol(): Int { requireClient(); return VdmHostPolicy.PROTOCOL }
    override fun hostPid(): Int { requireClient(); return checkNotNull(tokens).ownerPid() }
    override fun ownsDisplay(displayId: Int): Boolean { requireClient(); return synchronized(lock) { ready && active?.leased?.any { it.id == displayId } == true } }
    override fun openSession(producerLifetime: IBinder, producerPid: Int): IVdmDesktopSession {
        requireClient()
        return asApp { synchronized(lock) {
            check(ready && active == null && producerLifetime.isBinderAlive && producerPid > 0)
            Session(producerLifetime).also { session ->
                producerLifetime.linkToDeath(session.death, 0); active = session
            }
        } }
    }
    private inner class Session(private val producer: IBinder) : IVdmDesktopSession.Stub() {
        val leased = LinkedHashSet<Slot>()
        private var ended = false
        private var parent = -1
        val death: IBinder.DeathRecipient = IBinder.DeathRecipient { runCatching { finish() }.onFailure { retire() } }
        private fun live() { requireClient(); check(!ended && ready && active === this) }
        override fun createDisplay(surface: Surface, name: String, width: Int, height: Int, density: Int, flags: Int): Int {
            requireClient()
            val parcelInput = Binder.getCallingPid() != Process.myPid()
            try { return asApp { synchronized(lock) {
                live(); VdmHostPolicy.requireGeometry(width, height, density)
                check(VdmHostPolicy.displayFlags(flags) == VdmHostPolicy.displayFlags(
                    DesktopModePolicy.displayFlags(DesktopModePolicy.DRIVEDECK, 37, true, true))) {
                    "Display capabilities do not match the activated pool"
                }
                check(surface.isValid)
                val slot = checkNotNull(slots[name]) { "Unknown independent display slot" }
                check(slot !in leased && slot.surface == null)
                if (parent < 0) check(name in VdmPoolProtocol.names.take(2)) else check(name in VdmPoolProtocol.names.drop(2))
                clearTasks(setOf(slot.id)); nativeInfo(slot)
                val retained = cloneSurface(surface)
                slot.surface = retained; leased.add(slot)
                try {
                    VdmPoolApi.call(dms, "resizeVirtualDisplay", slot.callback, width, height, density)
                    VdmPoolApi.call(dms, "setVirtualDisplaySurface", slot.callback, retained)
                    wake()
                    awaitGeometry(slot, width, height, density)
                    if (parent < 0) parent = slot.id
                    slot.id
                } catch (error: Throwable) { retire(); throw IllegalStateException("Independent display attach failed", error) }
            } } } finally { if (parcelInput) surface.release() }
        }
        override fun resizeDisplay(displayId: Int, width: Int, height: Int, density: Int) {
            requireClient(); asApp { synchronized(lock) {
                live(); VdmHostPolicy.requireGeometry(width, height, density)
                val slot = leased.single { it.id == displayId }; nativeInfo(slot)
                VdmPoolApi.call(dms, "resizeVirtualDisplay", slot.callback, width, height, density)
                awaitGeometry(slot, width, height, density)
            } }
        }
        override fun releaseDisplay(displayId: Int) {
            requireClient(); asApp { synchronized(lock) {
                if (!ended) {
                    if (displayId == parent) finish()
                    else leased.firstOrNull { it.id == displayId }?.let { slot ->
                        try { park(listOf(slot)); leased.remove(slot) }
                        catch (error: Throwable) {
                            com.hsd.universal.AppLog.e("VDM child retirement failed", error)
                            retire(); throw NativeDisplayReleaseUnconfirmed(error)
                        }
                    }
                }
            } }
        }
        override fun displayGroup(displayId: Int): Int {
            requireClient(); return synchronized(lock) { if (!ended && ready && leased.any { it.id == displayId }) group else -1 }
        }
        override fun ownsDisplay(displayId: Int): Boolean {
            requireClient(); return synchronized(lock) { !ended && ready && leased.any { it.id == displayId } }
        }
        override fun getLifetime(): IBinder { requireClient(); return checkNotNull(this@AppOwnedVdmPool.lifetime) }
        override fun close() { requireClient(); asApp { finish() } }
        private fun finish(): Unit = synchronized(lock) {
            if (ended) return@synchronized
            try { if (ready) { park(leased.toList()); sleep() } }
            catch (error: Throwable) {
                com.hsd.universal.AppLog.e("VDM session retirement failed", error)
                retire(); throw NativeDisplayReleaseUnconfirmed(error)
            }
            finally {
                ended = true; leased.clear(); producer.unlinkToDeath(death, 0)
                if (active === this) active = null
            }
        }
    }
    override fun shutdown() { requireClient(); retire() }
    /** Token process death is independent of activation and retires even an unacknowledged create. */
    fun retire(): Unit = asApp {
        synchronized(lock) {
            if (retired) return@synchronized
            closed = true; installed = false
            runCatching { tokens?.retire() }
            val until = SystemClock.elapsedRealtime() + 2000
            while (lifetime?.isBinderAlive == true && SystemClock.elapsedRealtime() < until) Thread.sleep(5)
            check(lifetime?.isBinderAlive != true) { "Native token owner did not retire" }
            // Wait for system_server's death recipients to remove known displays before consumers close.
            val removedBy = SystemClock.elapsedRealtime() + 2000
            fun remaining() = slots.values.any { displayManager.getDisplay(it.id) != null }
            while (remaining() && SystemClock.elapsedRealtime() < removedBy) Thread.sleep(5)
            check(!remaining()) { "Native displays did not finish retiring" }
            slots.values.forEach {
                it.surface?.release(); it.surface = null; it.parking.close()
                it.oldParking.forEach(ImageReader::close); it.oldParking.clear()
            }
            parkingThread?.quitSafely(); parkingThread = null
            drainThread?.quitSafely(); drainThread = null
            if (bound) { runCatching { context.unbindService(connection) }; bound = false }
            if (associationId > 0) runCatching {
                val cdm = VdmPoolApi.remote("android.companion.ICompanionDeviceManager", "companiondevice")
                val all = VdmPoolApi.call(cdm, "getAllAssociationsForUser", Process.myUid() / 100000) as List<*>
                val own = all.filterNotNull().singleOrNull { VdmPoolApi.call(it, "getId") == associationId }
                if (own != null && VdmHostPolicy.ownAssociation(VdmPoolApi.call(own, "getPackageName") as? String,
                        VdmPoolApi.call(own, "getDeviceProfile") as? String, VdmPoolApi.call(own, "getDeviceMacAddress")?.toString()))
                    VdmPoolApi.call(cdm, "disassociate", associationId)
            }
            retired = true
        }
    }
    private class Slot(val id: Int, val name: String, val callback: Any, var parking: ImageReader,
        var surface: Surface? = null, val oldParking: MutableList<ImageReader> = ArrayList())
    private fun cloneSurface(surface: Surface): Surface = Parcel.obtain().let { parcel ->
        try { surface.writeToParcel(parcel, 0); parcel.setDataPosition(0); Surface.CREATOR.createFromParcel(parcel) }
        finally { parcel.recycle() }
    }
    private inline fun <T> asApp(action: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        try { return action() } finally { Binder.restoreCallingIdentity(identity) }
    }
}

internal object VdmPoolApi {
    fun iface(name: String, binder: IBinder): Any = Class.forName(name + "\$Stub")
        .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    fun remote(name: String, service: String) = iface(name, VdmHostCore.service(service))
    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val methods = target.javaClass.methods.filter { it.name == name && it.parameterCount == args.size }
        check(methods.size == 1) { "Unsupported platform method $name" }
        try { return methods.single().invoke(target, *args) }
        catch (error: java.lang.reflect.InvocationTargetException) {
            val cause = error.targetException
            if (cause is RuntimeException) throw cause
            throw IllegalStateException("Platform operation failed: $name", cause)
        }
    }
}
