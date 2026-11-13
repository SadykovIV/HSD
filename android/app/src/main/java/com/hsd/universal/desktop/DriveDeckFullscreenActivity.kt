package com.hsd.universal.desktop

import androidx.core.view.WindowCompat
import com.hsd.universal.compat.hostDisplay
import com.hsd.universal.compat.safeInsets
import com.hsd.universal.compat.hideDesktopBars

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.hsd.universal.R
import com.hsd.universal.embed.EmbeddedGatewayPolicy
import com.hsd.universal.embed.EmbeddedSessionRegistry
import kotlin.math.roundToInt

/** Signature-protected launcher for one full-screen DriveDeck app on its active HU display. */
class DriveDeckFullscreenActivity : Activity() {
    private lateinit var pane: DesktopAppPane
    private var hardwareBack: FullscreenHardwareBack? = null
    private lateinit var message: TextView
    private lateinit var back: ImageButton
    private lateinit var home: ImageButton
    private var leaving = false
    private var target: ComponentName? = null
    private var parentProof: Any? = null

    private val parentId get() = hostDisplay?.displayId ?: -1
    private fun config(pkg: String? = null) = runCatching {
        EmbeddedSessionRegistry.renderConfig(parentId, ownDesktop = false, packageName = pkg)
    }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentProof = EmbeddedSessionRegistry.captureParent(parentId)
        if (parentProof == null || config() == null) { finish(); return }
        setShowWhenLocked(true)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
            // A touchable host can otherwise take focus from the Android 13 child between DOWN and UP.
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { v, i ->
                val bars = i.safeInsets(v)
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                i
            }
        }
        pane = DesktopAppPane(this, onOpened = { message.visibility = View.GONE }, onHome = ::goHome,
            onError = { error ->
                if (tryNativeLaunch()) {
                    Toast.makeText(this, getString(R.string.desktop_the_application_is_open_directly_on_the_head_unit), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    goHome()
                }
            }, fullscreenDriveDeck = true)
        root.addView(pane.view, FrameLayout.LayoutParams(-1, -1))
        message = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            text = getString(R.string.desktop_opening_app)
        }
        root.addView(message, FrameLayout.LayoutParams(-1, -1))
        back = button(getString(R.string.set_back), R.drawable.desktop_back) { pane.back() }
        home = button(getString(R.string.desktop_drivedeck_home), R.drawable.desktop_home) { goHome() }
        root.addView(back, buttonPosition(aboveHome = false))
        root.addView(home, buttonPosition(aboveHome = false))
        setContentView(root)
        hardwareBack = FullscreenHardwareBack.attach(this, pane) { !leaving }
            ?: run { finish(); return }
        openTarget(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::pane.isInitialized && !isFinishing) openTarget(intent)
    }

    private fun openTarget(intent: Intent) {
        val component = ComponentName.unflattenFromString(intent.getStringExtra("component").orEmpty())
        if (component == null || !EmbeddedGatewayPolicy.allowsLauncherPackage(component.packageName, packageName)) {
            finish(); return
        }
        // Only accept exactly the exported MAIN+LAUNCHER component from DriveDeck's app picker.
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(component.packageName)
        val entry = packageManager.queryIntentActivities(launch, 0).firstOrNull {
            val info = it.activityInfo
            info.name == component.className && info.packageName == component.packageName &&
                info.exported && info.enabled && info.applicationInfo.enabled &&
                (info.permission == null || checkSelfPermission(info.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        } ?: run { goHome(); return }
        target = component
        val backEnabled = intent.getBooleanExtra("back", false)
        val homeEnabled = intent.getBooleanExtra("home", false)
        val policy = config(component.packageName) ?: run { finish(); return }
        if (!backEnabled && !homeEnabled && policy[1] == 0) {
            // No overlay or app-specific rendering is needed on the parent display.
            if (!tryNativeLaunch()) goHome()
            return
        }
        back.visibility = if (backEnabled) View.VISIBLE else View.GONE
        home.visibility = if (homeEnabled) View.VISIBLE else View.GONE
        back.layoutParams = buttonPosition(aboveHome = homeEnabled)
        message.visibility = View.VISIBLE
        val label = entry.loadLabel(packageManager).toString()
        pane.open(DesktopAppCatalog.Entry(component.packageName, component.className, label))
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || !::pane.isInitialized) return
        if (config() == null) { finish(); return }
        hardwareBack?.resume()
        window.hideDesktopBars()
        pane.refreshBuffer()
    }

    override fun onPause() {
        hardwareBack?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        hardwareBack?.close()
        hardwareBack = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) hardwareBack?.touchDown()
        return super.dispatchTouchEvent(event)
    }

    @Deprecated("Route Back to the owned child display")
    override fun onBackPressed() {
        if (!::pane.isInitialized || config() == null) finish() else pane.back()
    }

    private fun tryNativeLaunch(): Boolean {
        val component = target ?: return false
        if (leaving || config() == null) return false
        return runCatching {
            startActivity(Intent.makeMainActivity(component), ActivityOptions.makeBasic().setLaunchDisplayId(parentId).toBundle())
            leaving = true
            finish()
        }.isSuccess
    }

    private fun goHome() {
        if (leaving || isFinishing) return
        leaving = true
        parentProof?.let { EmbeddedSessionRegistry.requestHome(parentId, it) }
        finish()
    }

    private fun button(label: String, icon: Int, click: () -> Unit) = ImageButton(this).apply {
        contentDescription = label
        setImageResource(icon)
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xD9212932.toInt())
            setStroke(dp(1), 0x66FFFFFF)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), circle, null)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val inset = dp(12)
        setPadding(inset, inset, inset, inset)
        setOnClickListener { if (config() != null) click() else finish() }
    }

    private fun buttonPosition(aboveHome: Boolean): FrameLayout.LayoutParams {
        val diameter = dp(48)
        val edge = dp(12)
        return FrameLayout.LayoutParams(diameter, diameter, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = edge
            bottomMargin = edge + if (aboveHome) diameter + dp(8) else 0
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
