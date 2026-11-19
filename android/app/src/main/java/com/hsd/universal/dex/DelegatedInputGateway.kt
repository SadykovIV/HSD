package com.hsd.universal.dex

import com.hsd.universal.BuildConfig
import com.hsd.universal.DesktopTransport

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.hsd.universal.AppLog

/** TestApi calls stay in the already instrumented main process, not in ordinary helper ART. */
internal class DelegatedInputGateway(context: Context,
    private val onNativeProducer: (IBinder) -> Unit = {}) : IDelegatedInputGateway.Stub() {
    private val context = context.applicationContext
    private val registry = DelegatedInputRegistry()
    private val presentations = HashMap<Int, ILocalPresentation>()
    private val imeRoutes = DelegatedImeRoutes()
    private val displayWakeLocks = DesktopDisplayWakeLocks(this.context)
    @Volatile private var liveDisplayIds: Set<Int> = emptySet()
    @Volatile private var panelParent: DelegatedInputRegistry.Entry? = null
    private val phonePanel = DelegatedPanelController(this.context)
    private val displays = checkNotNull(context.getSystemService(DisplayManager::class.java))
    private val inputManager = checkNotNull(context.getSystemService(InputManager::class.java))
    private val inject by lazy { InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }
    private val displayEvents = DisplayEventAccess()
    private val touches = ProjectionTouchDispatcher({ id, event ->
        displayEvents.targetProjectionDisplay(event, id)
        inject.invoke(inputManager, event, 0) == true
    }, { AppLog.i("DESKTOP INPUT: $it", false) })
    @Volatile private var vdmSession: IVdmDesktopSession? = null
    @Volatile var nativeProducerLifetime: IBinder? = null
        private set
    private val setActionButton by lazy { MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType) }

    fun preflight() {
        try { inject; displayEvents.preflight(); setActionButton }
        catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Android не разрешил управление отдельным экраном в процессе активации: ${error.message}", error)
        }
    }

    @Synchronized fun beginGeneration(generation: Long, token: IBinder, allowFullscreen: Boolean) {
        displayWakeLocks.close()
        registry.begin(generation, token, allowFullscreen)
        vdmSession = null
        nativeProducerLifetime = null
        presentations.clear()
        imeRoutes.clear()
        panelParent = null
        DexState.independentPhoneLock = null
        liveDisplayIds = emptySet()
        activeGateway.set(this)
    }
    @Synchronized fun deactivate() {
        registry.registeredDisplayIds().forEach { finishTouch(it) }
        displayWakeLocks.close()
        presentations.clear()
        imeRoutes.clear()
        panelParent = null
        DexState.independentPhoneLock = null
        liveDisplayIds = emptySet()
        activeGateway.compareAndSet(this, null)
        phonePanel.restoreBestEffort()
        registry.deactivate()
    }

    @Synchronized override fun openVdmSession(producerLifetime: IBinder, parentToken: IBinder): IVdmDesktopSession {
        requireActive()
        registry.requirePendingParent(parentToken)
        check(vdmSession == null && producerLifetime.isBinderAlive) { "Семейство экранов уже открыто" }
        val opened = VdmHostLink.openSession(producerLifetime, Binder.getCallingPid())
        vdmSession = opened
        val lifetime = checkNotNull(opened.lifetime)
        nativeProducerLifetime = lifetime
        // Observed before the producer can submit its first Surface/create transaction.
        onNativeProducer(lifetime)
        return opened
    }

    @Synchronized override fun registerParent(displayId: Int, token: IBinder, width: Int, height: Int, presentation: ILocalPresentation?) {
        requireActive(); requireOwnedDisplay(displayId, presentation)
        registry.registerParent(displayId, token, width, height)
        if (presentation != null) presentations[displayId] = presentation
        val entry = registry.requireDisplay(displayId, token)
        panelParent = entry
        DexState.independentPhoneLock = displays.getDisplay(displayId)?.let(DesktopDisplayIsolation::confirmedIndependence)
        registerIme(entry)
        displayWakeLocks.hold(displayId, managedByVirtualDevice = vdmSession != null)
        liveDisplayIds = registry.registeredDisplayIds()
    }

    @Synchronized override fun configureSamsungDesktop(displayId: Int, parentToken: IBinder) {
        requireActive()
        val parent = registry.requireDisplay(displayId, parentToken)
        check(parent === panelParent && parent.parent == null && DexPrefs(context).desktopMode == DesktopModePolicy.SAMSUNG_DEX)
        requireOwnedDisplay(displayId)
        val display = checkNotNull(displays.getDisplay(displayId))
        check(display.name == DesktopModePolicy.displayName(DesktopModePolicy.SAMSUNG_DEX) &&
            display.flags and DexUserServicePolicy.DISPLAY_ALLOWS_CONTENT_MODE_SWITCH != 0)
        ShellDisplayIme.configureSamsungDesktop(displayId, Process.myUid())
    }

    override fun registerChild(parentId: Int, displayId: Int, token: IBinder, width: Int, height: Int, presentation: ILocalPresentation?) =
        registerChild(parentId, displayId, token, width, height, presentation, fullscreen = false)

    override fun registerFullscreenChild(parentId: Int, displayId: Int, token: IBinder, width: Int, height: Int, presentation: ILocalPresentation?) =
        registerChild(parentId, displayId, token, width, height, presentation, fullscreen = true)

    @Synchronized private fun registerChild(parentId: Int, displayId: Int, token: IBinder, width: Int, height: Int, presentation: ILocalPresentation?, fullscreen: Boolean) {
        requireActive(); requireOwnedDisplay(parentId); requireOwnedDisplay(displayId, presentation)
        registry.registerChild(parentId, displayId, token, width, height, fullscreen)
        if (presentation != null) presentations[displayId] = presentation
        registerIme(registry.requireDisplay(displayId, token))
        displayWakeLocks.hold(displayId, managedByVirtualDevice = vdmSession != null)
        liveDisplayIds = registry.registeredDisplayIds()
    }

    private fun registerIme(entry: DelegatedInputRegistry.Entry) {
        imeRoutes.register(entry)
        if (imeBackend() == null) {
            AppLog.i("DESKTOP IME: дисплей ${entry.id} ожидает связи со службой клавиатуры", false)
        } else refreshIme(entry)
    }

    // USB handoff can retire the whole ADB process group. The active app still has its
    // desktop lease and can set LOCAL itself before the first app is launched on a new display.
    private fun imeBackend(): Any? =
        ShellPanelClient.imeConnection ?: ShellDisplayIme.takeIf { it.availableInActivation(context) }

    private fun refreshIme(only: DelegatedInputRegistry.Entry? = null) {
        if (activeGateway.get() !== this) return
        val backend = imeBackend()
        imeRoutes.refresh(backend, SystemClock.elapsedRealtime(), only) { entry ->
            val parent = panelParent ?: return@refresh false
            val token = entry.token as IBinder
            if (activeGateway.get() !== this || imeBackend() !== backend ||
                !(parent.token as IBinder).isBinderAlive || !token.isBinderAlive ||
                !DelegatedDesktopAccess.isReady(context)) return@refresh false
            val source = try {
                requireOwnedDisplay(entry.id)
                val ownerUid = displayOwnerUid(entry.id)
                val fromHelper = ownerUid == Process.myUid() && backend !== ShellDisplayIme &&
                    ShellPanelClient.enableLocalIme(entry.id) && ShellPanelClient.imeConnection === backend
                when {
                    fromHelper -> "helper"
                    ShellDisplayIme.availableInActivation(context) -> {
                        ShellDisplayIme.enable(entry.id, ownerUid)
                        "activation"
                    }
                    else -> null
                }
            } catch (e: Exception) {
                android.util.Log.w(BuildConfig.LOG_TAG, "IME policy unavailable on display ${entry.id}", e)
                null
            } catch (_: LinkageError) { null }
            val state = if (source != null) "LOCAL подтверждён ($source)" else "LOCAL недоступен, запланирован повтор"
            AppLog.i("DESKTOP IME: дисплей ${entry.id} — $state", false)
            source != null
        }
    }

    override fun childImeVisibility(displayId: Int, token: IBinder): Int {
        val child = synchronized(this) {
            requireActive()
            requireOwnedDisplay(displayId)
            registry.requireDisplay(displayId, token).also { check(it.parent != null) }
        }
        val ownerUid = synchronized(this) { displayOwnerUid(displayId) }
        val visible = (if (ownerUid == Process.myUid()) ShellPanelClient.imeVisible(displayId) else null)
            ?: if (ShellDisplayIme.availableInActivation(context)) {
            try { ShellDisplayIme.isVisible(displayId, ownerUid) }
            catch (_: Exception) { null }
            catch (_: LinkageError) { null }
        } else null
        return synchronized(this) {
            requireActive()
            check(registry.requireDisplay(displayId, token) === child)
            when (visible) { true -> 1; false -> 0; null -> -1 }
        }
    }

    @Synchronized override fun resizeDisplay(displayId: Int, token: IBinder, width: Int, height: Int) {
        requireActive(); requireOwnedDisplay(displayId)
        registry.resize(displayId, token, width, height)
    }

    @Synchronized override fun unregisterDisplay(displayId: Int, token: IBinder) {
        requireCaller() // Cleanup remains possible after activation has ended.
        val owned = runCatching { registry.requireDisplay(displayId, token) }.getOrNull()
        if (owned != null) {
            if (owned.parent == null) registry.registeredDisplayIds().forEach { finishTouch(it) }
            else finishTouch(displayId)
        }
        if (owned != null) imeRoutes.unregister(owned)
        if (owned?.parent == null && owned != null) {
            panelParent = null
            DexState.independentPhoneLock = null
            phonePanel.restoreBestEffort()
        }
        registry.unregister(displayId, token)
        liveDisplayIds = registry.registeredDisplayIds()
        presentations.keys.retainAll(liveDisplayIds)
        displayWakeLocks.retain(liveDisplayIds)
    }

    @Synchronized override fun injectInput(displayId: Int, token: IBinder, event: InputEvent, actionButton: Int) {
        try {
            requireActive()
            val owned = registry.requireDisplay(displayId, token)
            requireOwnedDisplay(displayId)
            when (event) {
                is MotionEvent -> {
                    require(event.source == InputDevice.SOURCE_MOUSE || event.source == InputDevice.SOURCE_TOUCHSCREEN)
                    require(event.pointerCount in 1..2 &&
                        (event.source != InputDevice.SOURCE_MOUSE || event.pointerCount == 1))
                    require((0 until event.pointerCount).all { i ->
                        event.getX(i).isFinite() && event.getY(i).isFinite() &&
                            event.getX(i) >= 0 && event.getX(i) < owned.width &&
                            event.getY(i) >= 0 && event.getY(i) < owned.height
                    })
                    if (event.source == InputDevice.SOURCE_TOUCHSCREEN) {
                        require(actionButton == 0 && event.buttonState == 0)
                        DesktopTouchState.validate(event.actionMasked, event.actionIndex,
                            IntArray(event.pointerCount) { event.getPointerId(it) },
                            FloatArray(event.pointerCount) { event.getX(it) },
                            FloatArray(event.pointerCount) { event.getY(it) })
                    }
                    require(actionButton == 0 || actionButton == MotionEvent.BUTTON_PRIMARY)
                    if (actionButton != 0) setActionButton.invoke(event, actionButton)
                }
                is KeyEvent -> require(actionButton == 0 && event.keyCode in 0..1000 &&
                    event.action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                else -> error("Недопустимый тип ввода")
            }
            if (event is MotionEvent && event.source == InputDevice.SOURCE_TOUCHSCREEN) {
                touches.dispatch(displayId, event)
            } else {
                displayEvents.targetProjectionDisplay(event, displayId)
                check(inject.invoke(inputManager, event, 0) == true) { "Android отклонил ввод на отдельный экран" }
            }
        } finally { (event as? MotionEvent)?.recycle() }
    }

    private fun finishTouch(id: Int) {
        // Numeric display IDs can be reused. Cleanup may only address the still-owned display.
        if (runCatching { check(DelegatedDesktopAccess.isReady(context)); requireOwnedDisplay(id) }.isSuccess)
            touches.finish(id)
        else touches.forget(id)
    }

    @Suppress("DEPRECATION")
    override fun childTaskState(displayId: Int, token: IBinder, packageName: String): Int {
        val owned = synchronized(this) {
            requireActive()
            requireOwnedDisplay(displayId)
            registry.requireDisplay(displayId, token).also { check(it.parent != null) { "Ожидался экран виджета" } }
        }
        EmbeddedDisplayPolicy.requirePackage(packageName)
        val tasks = runCatching {
            context.getSystemService(ActivityManager::class.java).getRunningTasks(100).map {
                val identity = FrameworkTaskIdentityReader.read(it) ?: return@runCatching null
                DelegatedDesktopPolicy.Task(identity.displayId, identity.userId,
                    it.baseActivity?.packageName, it.topActivity?.packageName)
            }
        }.getOrNull()
        return synchronized(this) {
            requireActive()
            check(registry.requireDisplay(displayId, token) === owned) { "Экран виджета изменился" }
            DelegatedDesktopPolicy.taskState(displayId, context.applicationInfo.uid / 100000, packageName,
                DelegatedDesktopAccess.hasPermissions(context), tasks)
        }
    }

    internal fun setLocalPanelOff(generation: Long, off: Boolean) {
        val parent = synchronized(this) { registry.requireParent(generation) }
        setPhonePanelOff(parent.id, parent.token as IBinder, off)
    }

    override fun setPhonePanelOff(parentId: Int, token: IBinder, off: Boolean) {
        requireCaller()
        if (off) requireActive() // restoring a lease remains possible after grant/liveness loss
        val parent = synchronized(this) {
            registry.requireDisplay(parentId, token).also {
                check(it === panelParent && it.parent == null) { "Панель доступна только родительскому рабочему столу" }
                if (off) requireOwnedDisplay(parentId)
            }
        }
        phonePanel.setOff(parentId, token, off) { panelParent === parent }
    }

    private fun requireCaller() {
        check(Binder.getCallingUid() == Process.myUid()) { "Шлюз ввода доступен только ${DesktopTransport.shortName}" }
    }
    private fun requireActive() { requireCaller(); check(DelegatedDesktopAccess.isReady(context)) { "Доступ завершён" } }
    private fun requireOwnedDisplay(id: Int, presentation: ILocalPresentation? = presentations[id]) {
        require(id > 0) { "Ввод на основной экран телефона запрещён" }
        val display = checkNotNull(displays.getDisplay(id)) { "Экран уже закрыт" }
        check(display.isValid) { "Экран уже закрыт" }
        val trusted = display.flags and DexUserServicePolicy.DISPLAY_TRUSTED != 0
        if (presentation != null) {
            // The non-exported same-UID producer holds the native VirtualDisplay. Registration
            // requires a live session token and can never move that lease to another display.
            check(vdmSession == null && presentation.currentDisplayId() == id && DesktopImePolicy.isSupportedName(display.name))
            check(trusted || android.os.Build.VERSION.SDK_INT in 29..32 && !DelegatedDesktopAccess.trustedDisplay(context)) {
                "Android не подтвердил собственный экран HSD"
            }
        } else if (vdmSession != null) {
            check(trusted)
            displayOwnerUid(id) // The authenticated native owner checks UID, live handle and exact family group.
        } else {
            check(trusted && DesktopDisplayIsolation.ownerUid(display) == Process.myUid()) {
                "Android не подтвердил владельца доверенного экрана"
            }
        }
    }

    private fun displayOwnerUid(id: Int): Int {
        val family = vdmSession ?: return Process.myUid()
        check(nativeProducerLifetime?.isBinderAlive == true &&
            VdmHostLink.ownsDisplay(id, family.asBinder())) {
            "Экран не принадлежит текущей службе ${DesktopTransport.shortName}"
        }
        return VdmHostPolicy.SHELL_UID
    }

    companion object {
        const val EXTRA_BINDER = "hsd_delegated_input_gateway"
        private val activeGateway = java.util.concurrent.atomic.AtomicReference<DelegatedInputGateway?>()
        /** Instrumentation heartbeat, after attempting to reconnect the authenticated helper. */
        fun refreshImeRoutes() { activeGateway.get()?.refreshIme() }
        fun imeRoutesReady(): Boolean = activeGateway.get()?.imeRoutes?.allApplied() != false
        fun ownedDisplayIds(): Set<Int> = activeGateway.get()?.liveDisplayIds.orEmpty()
        fun ownsFocusedDisplay(id: Int): Boolean = id > 0 && activeGateway.get()?.liveDisplayIds?.contains(id) == true
    }
}
