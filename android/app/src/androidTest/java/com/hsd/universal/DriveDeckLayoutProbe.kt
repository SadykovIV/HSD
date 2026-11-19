package com.hsd.universal

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hsd.universal.desktop.DriveDeckFullscreenActivity
import com.hsd.universal.dex.DesktopModePolicy
import com.hsd.universal.dex.IDexUserService
import com.hsd.universal.shared.ProjectionHost

/** Measures both real app containers and the draggable split handle on the projection display. */
internal class DriveDeckLayoutProbe(spec: String, private val expectedGap: Int?) {
    private val dimensions = requireNotNull(Regex("""(\d+)x(\d+)@(\d+)""").matchEntire(spec)).destructured
    val width = dimensions.component1().toInt()
    val height = dimensions.component2().toInt()
    val dpi = dimensions.component3().toInt()

    private fun viewDump(ui: UiAutomation, activity: String): String {
        val fd = ui.executeShellCommand("dumpsys activity ru.drivedeck.headswap/$activity")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }

    private fun bounds(dump: String, type: String): List<Rect> {
        val views = Regex("""ru\.drivedeck\.launcher\.$type\{\S+ V[^\n]*? (-?\d+),(-?\d+)-(-?\d+),(-?\d+)[ }].*""")
        return views.findAll(dump).map {
            val (l, t, r, b) = it.destructured
            Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }.filter { !it.isEmpty }.toList()
    }

    fun hasPanes(ui: UiAutomation, activity: String) = bounds(viewDump(ui, activity), "N2").size == 2

    /** Exercise the companion's actual picker and asynchronous host callback, not a direct host intent. */
    fun checkControls(test: Instrumentation, ui: UiAutomation, display: Int, activity: String, remote: IDexUserService): String {
        check(android.os.Build.VERSION.SDK_INT >= 30)
        val context = test.targetContext
        val pkg = DesktopModePolicy.DRIVEDECK_PACKAGE
        val info = ui.serviceInfo
        val originalFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo = info
        fun poll(what: String, match: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 9000
            while (!match()) {
                check(SystemClock.elapsedRealtime() < deadline) { what }
                SystemClock.sleep(80)
            }
        }
        fun find(node: AccessibilityNodeInfo?, labels: Set<String>): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && (node.text?.toString() in labels || node.contentDescription?.toString() in labels)) return node
            for (i in 0 until node.childCount) find(node.getChild(i), labels)?.let { return it }
            return null
        }
        fun visible(vararg labels: String) = ui.windowsOnAllDisplays[display].orEmpty().asSequence()
            .mapNotNull { it.root }.filter { it.packageName == pkg }
            .mapNotNull { find(it, labels.toSet()) }.firstOrNull()
        fun element(vararg labels: String): AccessibilityNodeInfo {
            var found: AccessibilityNodeInfo? = null
            poll("Missing ${labels.toList()} on DriveDeck display $display") {
                found = visible(*labels)
                found != null
            }
            return checkNotNull(found)
        }
        fun click(node: AccessibilityNodeInfo) {
            // Layout/accessibility can precede InputDispatcher's surface transaction.
            // Resolve coordinates only after Android's own UI input barrier.
            UiAutomation::class.java.getMethod("syncInputTransactions").invoke(ui)
            check(node.refresh()) { "Touch target disappeared before input" }
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            val rectangle = Rect()
            checkNotNull(target) { "No clickable parent for ${node.text}" }.getBoundsInScreen(rectangle)
            val x = rectangle.centerX().toFloat(); val y = rectangle.centerY().toFloat()
            check(x >= 0 && x < width && y >= 0 && y < height) { "Touch target outside projection: $rectangle" }
            remote.injectTouchPoints(MotionEvent.ACTION_DOWN, 0, intArrayOf(0), floatArrayOf(x), floatArrayOf(y))
            remote.injectTouchPoints(MotionEvent.ACTION_UP, 0, intArrayOf(0), floatArrayOf(x), floatArrayOf(y))
        }
        fun show(extra: String) {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ProjectionHost.PACKAGE, context.packageName).putExtra(ProjectionHost.DISPLAY, display)
                .putExtra(extra, true), ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
        }
        fun edges() = bounds(viewDump(ui, activity), "N2").sortedBy { it.left }
        fun gap() = edges().let { if (it.size == 2) it[1].left - it[0].right else -1 }
        val carRequests = java.util.concurrent.atomic.AtomicInteger()
        val carControl = CarMenuControl.register(context) { carRequests.incrementAndGet() }
        try {
            // The first-run tutorial covers the entire launcher and deliberately consumes touch.
            if (bounds(viewDump(ui, activity), "Q3").isNotEmpty()) {
                click(element("Пропустить", "Skip"))
                poll("DriveDeck tutorial is still visible") { bounds(viewDump(ui, activity), "Q3").isEmpty() }
            }
            val originalGap = gap()
            CarMenuControl.request(context, 0)
            CarMenuControl.request(context, Int.MAX_VALUE)
            SystemClock.sleep(150)
            check(carRequests.get() == 0) { "Invalid parent accepted for car menu" }
            click(element("Меню автомобиля", "Car menu"))
            poll("DriveDeck did not request the native car menu") { carRequests.get() == 1 }
            val first = Rect()
            element("Изменить размер окон", "Resize windows").getBoundsInScreen(first)
            val x = first.centerX()
            val y = first.centerY()
            fun contact(action: Int, at: Int) = remote.injectTouchPoints(action, 0, intArrayOf(0), floatArrayOf(at.toFloat()), floatArrayOf(y.toFloat()))
            contact(MotionEvent.ACTION_DOWN, x)
            for (step in 1..12) { SystemClock.sleep(25); contact(MotionEvent.ACTION_MOVE, x - step * 10) }
            contact(MotionEvent.ACTION_UP, x - 120)
            poll("Drag from $first did not move the boundary on display $display") {
                val handle = bounds(viewDump(ui, activity), "l2").singleOrNull()
                handle != null && handle.centerX() < x - 50 && gap() == originalGap
            }
            show("ru.drivedeck.launcher.DOCK_SETTINGS")
            click(element("Главный экран", "Home screen"))
            val join = arrayOf("Окна без промежутка", "Remove gap between windows")
            val original = element(*join).isChecked
            click(element(*join))
            poll("Window gap did not change with the visible switch") { if (original) gap() > 0 else gap() == 0 }
            click(element(*join))
            poll("Window gap did not restore") { if (original) gap() == 0 else gap() > 0 }
            val dismiss = element("Закрыть настройки", "Close settings")
            val dialogId = dismiss.windowId
            click(dismiss)
            poll("DriveDeck settings are still open") { ui.windowsOnAllDisplays[display].orEmpty().none { it.id == dialogId } }
            show("ru.drivedeck.launcher.ALL_APPS")
            // Clock and Settings are already visible in the fixture's dock. Select a drawer-only
            // entry so an accessibility event cannot hit the dock while the dialog is opening.
            val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage("com.android.calendar") ?:
                context.packageManager.getLaunchIntentForPackage("com.google.android.calendar"))
            val entry = checkNotNull(launcher.component)
            val label = context.packageManager.getActivityInfo(entry, 0).loadLabel(context.packageManager).toString()
            val monitor = test.addMonitor(DriveDeckFullscreenActivity::class.java.name, null, false)
            val host = try {
                click(element(label))
                checkNotNull(monitor.waitForActivityWithTimeout(8000)) { "Picker did not open the HSD fullscreen host" }
            } finally { test.removeMonitor(monitor) }
            check(ComponentName.unflattenFromString(host.intent.getStringExtra("component").orEmpty()) == entry)
            tapCarMenu(test, ui, display, context.packageName) { x, y ->
                remote.injectTouchPoints(MotionEvent.ACTION_DOWN, 0, intArrayOf(0), floatArrayOf(x.toFloat()), floatArrayOf(y.toFloat()))
                remote.injectTouchPoints(MotionEvent.ACTION_UP, 0, intArrayOf(0), floatArrayOf(x.toFloat()), floatArrayOf(y.toFloat()))
            }
            poll("Fullscreen host did not request the native car menu") { carRequests.get() == 2 }
            poll("Fullscreen menu and Back must be accessible on the projection display") {
                ui.windowsOnAllDisplays[display].orEmpty().any {
                    val root = it.root
                    root?.packageName == context.packageName &&
                        find(root, setOf(context.getString(R.string.set_back))) != null &&
                        find(root, setOf(context.getString(R.string.desktop_drivedeck_menu)))?.let { node -> click(node); true } == true
                }
            }
            element(label)
            poll("Menu opened without returning to DriveDeck") { host.isDestroyed }
            return "Injected touchscreen: dock and fullscreen Car menu, invalid-display rejection, spacing switch and divider, picker launch, fullscreen Menu, returned to app list on display $display"
        } finally {
            carControl.close()
            info.flags = originalFlags
            ui.serviceInfo = info
        }
    }

    fun measure(ui: UiAutomation, activity: String): Bundle {
        val dump = viewDump(ui, activity)
        val panes = bounds(dump, "N2")
        check(panes.size == 2) { "Expected two visible app panes, got $panes" }
        val a = panes[0]
        val b = panes[1]
        val sideBySide = a.top < b.bottom && b.top < a.bottom
        val gap = if (sideBySide) maxOf(a.left, b.left) - minOf(a.right, b.right)
            else maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)
        check(gap >= 0 && (expectedGap == null || gap == expectedGap)) { "Expected gap $expectedGap, got $gap in $panes" }
        return Bundle().apply {
            putInt("tile_gap_px", gap)
            putString("tile_bounds", panes.toString())
            if (bounds(dump, "m2").isNotEmpty()) {
                val handle = bounds(dump, "l2").single()
                val size = if (sideBySide) handle.width() else handle.height()
                val center = if (sideBySide) handle.centerX() else handle.centerY()
                val boundary = (if (sideBySide) maxOf(a.left, b.left) else maxOf(a.top, b.top)) - gap / 2
                if (expectedGap == 0) check(size > 0 && kotlin.math.abs(center - boundary) <= 1) { "Handle $handle, panes $panes" }
                putInt("divider_touch_px", size)
            }
        }
    }
}
