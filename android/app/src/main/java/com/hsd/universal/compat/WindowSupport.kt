package com.hsd.universal.compat

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowInsets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Resolve the activity's own display, including before its decor is attached. */
@Suppress("DEPRECATION")
internal val Activity.hostDisplay: Display?
    get() = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay

internal fun WindowInsets.safeInsets(view: View, keyboard: Boolean = false): androidx.core.graphics.Insets {
    val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    return WindowInsetsCompat.toWindowInsetsCompat(this, view).getInsets(
        if (keyboard) types or WindowInsetsCompat.Type.ime() else types)
}

internal fun Window.hideDesktopBars() {
    WindowCompat.getInsetsController(this, decorView)?.apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
