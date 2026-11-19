package com.hsd.universal

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/** Locate the real companion/fullscreen button, then send its coordinates through the HU protocol. */
internal fun tapCarMenu(test: Instrumentation, ui: UiAutomation, display: Int,
                       expectedPackage: String = "ru.drivedeck.headswap", tap: (Int, Int) -> Unit): Rect {
    val targetDisplay = checkNotNull(test.targetContext.getSystemService(android.hardware.display.DisplayManager::class.java)
        .getDisplay(display))
    val dimensions = android.graphics.Point().apply { @Suppress("DEPRECATION") targetDisplay.getRealSize(this) }
    fun tapBounds(bounds: Rect): Rect {
        check(!bounds.isEmpty && Rect(0, 0, dimensions.x, dimensions.y).contains(bounds)) { "Car menu outside display: $bounds" }
        tap(bounds.centerX(), bounds.centerY())
        return bounds
    }
    if (targetDisplay.flags and android.view.Display.FLAG_PRIVATE != 0) {
        // Android excludes private virtual displays from AccessibilityManager's window list.
        // Read the real Views instead; still inject a physical HU touch, never performClick.
        check(expectedPackage == com.hsd.universal.dex.DesktopModePolicy.DRIVEDECK_PACKAGE)
        val activity = com.hsd.universal.dex.DesktopModePolicy.driveDeckHome(test.targetContext)
        val fd = ui.executeShellCommand("dumpsys activity $expectedPackage/$activity")
        val dump = ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        File(test.targetContext.cacheDir, "qa-car-menu-private-views.txt").writeText(dump)
        check("mResumed=true mStopped=false" in dump && "mBounds=Rect(0, 0 - ${dimensions.x}, ${dimensions.y})" in dump)
        data class Parent(val indent: Int, val x: Int, val y: Int, val visible: Boolean)
        val parents = mutableListOf<Parent>()
        val buttons = mutableListOf<Rect>()
        val view = Regex("""^(\s*)([\w.$]+)\{\S+ ([VIG])\S* \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+)[ }].*""")
        dump.lineSequence().forEach { line ->
            if ("View Hierarchy:" in line) parents.clear()
            val match = view.matchEntire(line) ?: return@forEach
            val (space, type, visibility, l, t, r, b) = match.destructured
            while (parents.isNotEmpty() && parents.last().indent >= space.length) parents.removeAt(parents.lastIndex)
            val parent = parents.lastOrNull()
            val x = (parent?.x ?: 0) + l.toInt(); val y = (parent?.y ?: 0) + t.toInt()
            val visible = visibility == "V" && parent?.visible != false
            parents += Parent(space.length, x, y, visible)
            check(!visible || type != "ru.drivedeck.launcher.Q3") { "Complete DriveDeck's first-run tutorial before testing the dock" }
            if (visible && type == "ru.drivedeck.hsd.CarMenuButton")
                buttons += Rect(x, y, x + r.toInt() - l.toInt(), y + b.toInt() - t.toInt())
        }
        UiAutomation::class.java.getMethod("syncInputTransactions").invoke(ui)
        return tapBounds(buttons.single())
    }
    check(android.os.Build.VERSION.SDK_INT >= 30)
    val info = ui.serviceInfo
    val flags = info.flags
    info.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    ui.serviceInfo = info
    fun find(node: AccessibilityNodeInfo?, labels: Set<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.contentDescription?.toString() in labels || node.text?.toString() in labels)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), labels)?.let { return it }
        return null
    }
    try {
        val deadline = SystemClock.elapsedRealtime() + 9000
        var node: AccessibilityNodeInfo? = null
        var dismissedTutorial = false
        while (node == null) {
            val roots = ui.windowsOnAllDisplays[display].orEmpty().mapNotNull { it.root }
                .filter { it.packageName == expectedPackage }
            val skip = roots.mapNotNull { find(it, setOf("Skip", "Пропустить")) }.firstOrNull()
            if (skip != null && !dismissedTutorial) {
                var button: AccessibilityNodeInfo? = skip
                while (button != null && !button.isClickable) button = button.parent
                val rectangle = Rect().also(checkNotNull(button)::getBoundsInScreen)
                UiAutomation::class.java.getMethod("syncInputTransactions").invoke(ui)
                tap(rectangle.centerX(), rectangle.centerY())
                dismissedTutorial = true
            } else if (skip == null) {
                UiAutomation::class.java.getMethod("syncInputTransactions").invoke(ui)
                node = roots.mapNotNull { find(it, setOf("Car menu", "Меню автомобиля")) }
                    .firstOrNull { it.refresh() && it.isClickable }
            }
            check(SystemClock.elapsedRealtime() < deadline) { "No car-menu button on display $display" }
            if (node == null) SystemClock.sleep(80)
        }
        UiAutomation::class.java.getMethod("syncInputTransactions").invoke(ui)
        check(node.refresh() && node.isClickable) { "Car-menu button disappeared after the input barrier" }
        val bounds = Rect().also(node::getBoundsInScreen)
        return tapBounds(bounds)
    } catch (failure: Throwable) {
        // Keep the actual window and View state before projection teardown destroys them.
        val dump = StringBuilder()
        fun describe(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null || depth > 18 || dump.length > 50_000) return
            val bounds = Rect().also(node::getBoundsInScreen)
            dump.append("  ".repeat(depth)).append("${node.className} ${node.packageName} visible=${node.isVisibleToUser} clickable=${node.isClickable} $bounds text=${node.text} description=${node.contentDescription}\n")
            for (i in 0 until node.childCount) describe(node.getChild(i), depth + 1)
        }
        runCatching {
            val windows = ui.windowsOnAllDisplays
            for (i in 0 until windows.size()) {
                dump.append("DISPLAY ${windows.keyAt(i)}\n")
                windows.valueAt(i).forEach { dump.append(it).append('\n'); describe(it.root) }
            }
            File(test.targetContext.cacheDir, "qa-failure-car-menu-accessibility.txt").writeText(dump.toString())
            val fd = ui.executeShellCommand("dumpsys activity $expectedPackage")
            val views = ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
            File(test.targetContext.cacheDir, "qa-failure-car-menu-views.txt").writeText(views)
        }
        throw failure
    } finally { info.flags = flags; ui.serviceInfo = info }
}
