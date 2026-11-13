package com.hsd.universal.dex

import com.hsd.universal.shared.ProjectionHost
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.view.InputEvent
import android.view.Surface

/** Real app Context only: no shell attribution, app_process workarounds, commands or UiAutomation IPC. */
internal class DelegatedDesktopRuntime(private val context: Context, private val inputGateway: IDelegatedInputGateway) : DesktopRuntimeAccess {
    private val displays = checkNotNull(context.getSystemService(DisplayManager::class.java))
    private val independentLock = DelegatedDesktopAccess.canKeepDisplayUnlocked(context)
    // TestApi input access is process-local. Only the instrumented main process may
    // set display/actionButton; this ordinary helper owns and releases the producers.
    private val inputLeases = HashMap<Int, IBinder>() // Worker only, just like the display leases.

    override fun createDisplay(surface: Surface, width: Int, height: Int, density: Int, mode: Int,
        producerLifetime: IBinder, sessionToken: IBinder): OwnedDesktopDisplay {
        DesktopModePolicy.requireMode(mode)
        val name = DesktopModePolicy.displayName(mode)
        val flags = DesktopModePolicy.displayFlags(mode, Build.VERSION.SDK_INT, independentLock, DelegatedDesktopAccess.trustedDisplay(context))
        if (DesktopDisplayBackendPolicy.requiresVdm(Build.VERSION.SDK_INT, mode)) {
            val family = VdmDisplayFamily(context, inputGateway.openVdmSession(producerLifetime, sessionToken))
            return family.create(name, surface, width, height, density, flags, parent = true)
        }
        return LocalDesktopDisplay(create(name, surface, width, height, density, flags), producerLifetime)
    }

    override fun createChildDisplay(parent: OwnedDesktopDisplay, name: String, surface: Surface,
        width: Int, height: Int, density: Int): OwnedDesktopDisplay {
        val flags = EmbeddedDisplayPolicy.displayFlags(Build.VERSION.SDK_INT, independentLock, DelegatedDesktopAccess.trustedDisplay(context))
        return parent.family?.create(name, surface, width, height, density, flags, parent = false)
            ?: LocalDesktopDisplay(create(name, surface, width, height, density, flags), parent.nativeLifetime)
    }

    // Permission-gated flags are intentionally absent from the SDK IntDef. A vendor refusing
    // lock isolation must still permit normal projection with the previously supported flags.
    @SuppressLint("WrongConstant")
    private fun create(name: String, surface: Surface, width: Int, height: Int, density: Int, flags: Int): VirtualDisplay {
        val isolation = DesktopModePolicy.VIRTUAL_DISPLAY_OWN_DISPLAY_GROUP or DesktopModePolicy.VIRTUAL_DISPLAY_ALWAYS_UNLOCKED
        return checkNotNull(try {
            displays.createVirtualDisplay(name, width, height, density, surface, flags)
        } catch (denied: SecurityException) {
            if (flags and isolation == 0) throw denied
            com.hsd.universal.AppLog.i("DESKTOP LOCK: isolation rejected by framework, creating normal display", false)
            displays.createVirtualDisplay(name, width, height, density, surface, flags and isolation.inv())
        }) { "Android отклонил доверенный виртуальный экран" }
    }

    override fun inject(event: InputEvent, id: Int, actionButton: Int) {
        check(id > 0) { "Ввод на основной экран запрещён" }
        inputGateway.injectInput(id, checkNotNull(inputLeases[id]) { "Экран ввода уже закрыт" }, event, actionButton)
    }

    override fun registerParentDisplay(owned: OwnedDesktopDisplay, token: IBinder, width: Int, height: Int) {
        val id = owned.display.displayId
        check(id > 0 && !inputLeases.containsKey(id)) { "Неверный собственный экран ввода" }
        // Remember before IPC: if registration succeeded but its reply was lost, cleanup
        // must still revoke this exact token before releasing the producer.
        inputLeases[id] = token
        inputGateway.registerParent(id, token, width, height, (owned as? LocalDesktopDisplay)?.presentation)
    }

    override fun registerChildDisplay(parentId: Int, owned: OwnedDesktopDisplay, token: IBinder, width: Int, height: Int, fullscreen: Boolean) {
        val id = owned.display.displayId
        EmbeddedDisplayPolicy.requireChildId(id, parentId)
        check(inputLeases.containsKey(parentId) && !inputLeases.containsKey(id)) { "Родительский экран ввода уже закрыт" }
        inputLeases[id] = token
        if (fullscreen) inputGateway.registerFullscreenChild(parentId, id, token, width, height, (owned as? LocalDesktopDisplay)?.presentation)
        else inputGateway.registerChild(parentId, id, token, width, height, (owned as? LocalDesktopDisplay)?.presentation)
    }

    override fun resizeRegisteredDisplay(id: Int, token: IBinder, width: Int, height: Int) {
        check(inputLeases[id] == token) { "Экран ввода уже закрыт" }
        inputGateway.resizeDisplay(id, token, width, height)
    }

    override fun unregisterDisplay(id: Int, token: IBinder) {
        if (inputLeases[id] != token) return
        inputLeases.remove(id)
        inputGateway.unregisterDisplay(id, token)
    }

    override fun launchDesktop(mode: Int, id: Int) {
        val component = DelegatedDesktopPolicy.desktopComponent(mode, id, context.packageName)
        val activity = if (mode == DesktopModePolicy.DRIVEDECK) DesktopModePolicy.driveDeckHome(context) else component.className
        launch(id, ComponentName(component.packageName, activity), component.ownActivity, requireLauncher = false,
            secondaryHome = mode == DesktopModePolicy.SAMSUNG_DEX)
    }

    override fun launchChild(parentId: Int, id: Int, packageName: String, className: String, desktopPane: Boolean) {
        // Reuse explicit component validation without executing the resulting shell arguments.
        EmbeddedDisplayPolicy.launchCommand(id, parentId, context.applicationInfo.uid / 100000, packageName, className)
        val fullClassName = if (className.startsWith('.')) packageName + className else className
        launch(id, ComponentName(packageName, fullClassName), ownActivity = false, requireLauncher = true, desktopPane = desktopPane)
    }

    private fun launch(id: Int, component: ComponentName, ownActivity: Boolean, requireLauncher: Boolean, desktopPane: Boolean = false,
                      secondaryHome: Boolean = false) {
        require(id > 0) { "Запуск на основном экране запрещён" }
        val pm = context.packageManager
        val info = pm.getActivityInfo(component, 0)
        check(info.enabled && info.applicationInfo.enabled && (info.exported || ownActivity &&
            info.applicationInfo.uid == context.applicationInfo.uid && component.packageName == context.packageName)) {
            "Приложение рабочего стола отключено или не разрешает внешний запуск"
        }
        if (requireLauncher) {
            val candidates = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(component.packageName), 0)
            check(candidates.any { it.activityInfo?.let { candidate ->
                candidate.packageName == component.packageName && candidate.name == component.className &&
                    candidate.exported && candidate.enabled && candidate.applicationInfo.enabled
            } == true }) { "Виджет принимает только доступное приложение из списка запуска" }
        }
        // Android Desktop resumes the app's task like a launcher (including its current page).
        val taskFlags = if (desktopPane) Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            else Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val intent = Intent(Intent.ACTION_MAIN).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or taskFlags)
        if (secondaryHome) intent.addCategory(Intent.CATEGORY_SECONDARY_HOME)
        if (!requireLauncher && component.packageName == DesktopModePolicy.DRIVEDECK_PACKAGE) {
            intent.putExtra(ProjectionHost.PACKAGE, context.packageName).putExtra(ProjectionHost.DISPLAY, id)
        }
        if (requireLauncher || component.packageName == DesktopModePolicy.DRIVEDECK_PACKAGE) {
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
        }
        check(context.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(context, id, intent)) {
            "Android не разрешил запуск приложения на собственном экране"
        }
        context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
    }

    override fun setPhonePanelOff(parentId: Int, off: Boolean) {
        check(parentId > 0) { "Неизвестный собственный рабочий стол" }
        inputGateway.setPhonePanelOff(parentId, checkNotNull(inputLeases[parentId]) { "Рабочий стол уже закрыт" }, off)
    }

    override fun childTaskState(id: Int, packageName: String) = childTaskQuery(id, packageName)()
    override fun childImeVisibilityQuery(id: Int): () -> Boolean? {
        val token = inputLeases[id] ?: return { null }
        return { when (inputGateway.childImeVisibility(id, token)) { 1 -> true; 0 -> false; else -> null } }
    }
    override fun childTaskQuery(id: Int, packageName: String): () -> Int {
        check(id > 0) { "Проверка основного экрана запрещена" }
        val token = inputLeases[id] ?: return { -1 }
        return { inputGateway.childTaskState(id, token, packageName) }
    }
}
