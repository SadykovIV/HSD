package com.hsd.universal.update

import androidx.core.view.WindowCompat
import com.hsd.universal.compat.safeInsets

import com.hsd.universal.R
import com.hsd.universal.dex.DesktopPermissionList
import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import com.hsd.universal.dex.DesktopSetupViews

/** Phone-only component setup. It never grants access or starts the component on display zero. */
class ComponentPermissionsActivity : Activity() {
    private val ui by lazy { DesktopSetupViews(this) }
    override fun onCreate(state: Bundle?) { super.onCreate(state) }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val root = ui.column().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(24)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ui.background)
            isFillViewport = true
            addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.safeInsets(view)
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(scroll)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; root.addView(this) }
        ui.button(header, getString(R.string.set_back)) { finish() }
        ui.text(root, getString(R.string.desktop_drivedeck_permissions), 25f, true)
        DesktopPermissionList(this, ui).drivedeck(ui.section(root))
    }
}
