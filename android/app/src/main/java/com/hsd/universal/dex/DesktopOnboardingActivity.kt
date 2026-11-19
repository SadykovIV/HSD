package com.hsd.universal.dex

import androidx.core.view.WindowCompat
import com.hsd.universal.compat.safeInsets

import com.hsd.universal.R

import com.hsd.universal.DesktopTransport

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import com.hsd.universal.update.ComponentUpdatesActivity
import com.hsd.universal.update.ComponentPackages
import com.hsd.universal.component.DesktopComponents
import com.hsd.universal.CarLifeState

/** Resumable first-run setup. Android permissions are granted only through explicit system UI. */
class DesktopOnboardingActivity : Activity() {
    private val ui by lazy { DesktopSetupViews(this) }
    private val prefs by lazy { DexPrefs(this) }
    private var step = 0
    private var ready = false
    private var positions = IntArray(2)
    private var displayedStep = -1
    private var viewport: ScrollView? = null
    private fun rememberScroll() {
        if (displayedStep in positions.indices) viewport?.let { positions[displayedStep] = it.scrollY }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        positions = savedInstanceState?.getIntArray("positions")?.takeIf { it.size == 2 } ?: positions
        step = (savedInstanceState?.getInt("step")
            ?: if (prefs.initialSetupInProgress) prefs.initialSetupStep else 0).coerceIn(0, 2)
        prefs.initialSetupAutoPending = false
        prefs.initialSetupInProgress = true
        saveProgress()
        ready = true
    }
    override fun onResume() { super.onResume(); if (ready) { if (step == 2) openActivationStep() else render() } }
    override fun onSaveInstanceState(outState: Bundle) { rememberScroll(); outState.putIntArray("positions", positions); outState.putInt("step", step); super.onSaveInstanceState(outState) }

    private fun render() {
        rememberScroll()
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(24)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ui.background)
            isFillViewport = true
            addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.safeInsets(view)
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        viewport = scroll; displayedStep = step
        setContentView(scroll)
        val y = positions.getOrNull(step) ?: 0
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
        val header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        ui.brand(header, 64)
        ui.text(header, "${DesktopTransport.appName}", 27f, true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        ui.text(body, getString(R.string.desktop_initial_setup_of_3, step + 1), 15f)
        ui.text(body, getString(R.string.desktop_components_permissions_activation), 13f)
        when (step) {
            0 -> components(body)
            1 -> permissions(body)
            else -> return
        }
        ui.button(body, getString(R.string.desktop_next)) { moveTo(step + 1) }
        if (step == 0 && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)) {
            ui.button(body, getString(R.string.usb_helper_title)) { startActivity(Intent(this, UsbActivationActivity::class.java)) }
        }
        if (step > 0) ui.button(body, getString(R.string.set_back)) { moveTo(step - 1) }
        ui.button(body, getString(R.string.desktop_close_setup)) { closeSetup() }
    }
    private fun permissions(parent: LinearLayout) {
        val list = DesktopPermissionList(this, ui)
        list.hsd(ui.section(parent, getString(R.string.desktop_permissions, DesktopTransport.appName)))
        list.drivedeck(ui.section(parent, getString(R.string.desktop_drivedesk_permissions)))
    }
    private fun components(parent: LinearLayout) {
        val card = ui.section(parent, getString(R.string.desktop_1_components))
        val selected = DesktopComponents.selected(this).takeIf { prefs.desktopMode == DesktopModePolicy.ANDROID_DESKTOP }
        val name = selected?.let(DesktopProfilePicker::name) ?: when (prefs.desktopMode) {
            DesktopModePolicy.DRIVEDECK -> "DriveDeck"
            DesktopModePolicy.SAMSUNG_DEX -> "Samsung DeX"
            else -> "Android Desktop"
        }
        ui.text(card, getString(R.string.desktop_desktop_32f4, name), 18f, true)
        ui.text(card, when (prefs.desktopMode) {
            DesktopModePolicy.DRIVEDECK -> runCatching { ComponentPackages.requireDriveDeck(this) }.fold(
                onSuccess = { getString(R.string.desktop_installed_build, it.versionName.orEmpty(), it.longVersionCode) },
                onFailure = { it.message ?: getString(R.string.desktop_open_the_profile_list_again_and_check_the_package) }
            )
            DesktopModePolicy.SAMSUNG_DEX -> getString(R.string.desktop_it_uses_a_samsung_desktop_which_does_not_require)
            else -> if (selected != null) getString(R.string.desktop_the_installed_compatible_component_is_selected_and_its_updates)
                else getString(R.string.desktop_android_desktop_is_already_built_in_you_don_t)
        })
        ui.button(card, getString(R.string.desktop_components, DesktopTransport.appName)) { startActivity(Intent(this, ComponentUpdatesActivity::class.java)) }
        ui.button(card, getString(R.string.desktop_choose_desktop)) { chooseProfile() }
        if (prefs.desktopMode == DesktopModePolicy.SAMSUNG_DEX && !SamsungDexCapability.isAvailable(this)) {
            ui.text(card, getString(R.string.desktop_install_the_selected_component_or_select_the_built_in), 14f)
        }
    }

    private fun choiceUnlocked(): Boolean = !CarLifeState.running && !PhonePairingService.active && !DexState.running

    private fun chooseProfile() {
        if (!choiceUnlocked()) return
        DesktopProfilePicker.show(this, prefs.desktopMode, ::choiceUnlocked) { mode, entry, media ->
            if (DesktopBackendTransitionPolicy.requiresActivationEnd(DelegatedDesktopAccess.ownsActivation,
                    prefs.accessMode, prefs.accessModeFor(mode))) {
                AlertDialog.Builder(this).setTitle(getString(R.string.desktop_changing_access_method))
                    .setMessage(getString(R.string.desktop_first_complete_the_current_activation_on_the_activation_screen))
                    .setPositiveButton(getString(R.string.desktop_open_activation)) { _, _ -> openActivation() }
                    .setNegativeButton(getString(R.string.mock_cancel), null).show()
                return@show
            }
            if (entry == null) DesktopComponents.clear(this)
            else if (!DesktopComponents.select(this, entry, media)) {
                AlertDialog.Builder(this).setTitle(getString(R.string.desktop_component_has_changed))
                    .setMessage(getString(R.string.desktop_open_the_profile_list_again_and_check_the_installed))
                    .setPositiveButton(getString(R.string.apps_warn_ok), null).show()
                return@show
            }
            prefs.desktopMode = mode
            render()
        }
    }

    private fun openActivationStep() {
        if (isFinishing) return
        startActivity(Intent(this, DesktopWizardActivationActivity::class.java)
            .putExtra(DexSetupActivity.EXTRA_SHOW_ACTIVATION, true)
            .putExtra(DexSetupActivity.EXTRA_RETURN_TO_SETUP, true))
        finish()
    }

    private fun saveProgress() {
        prefs.initialSetupStep = step
        if (step == 2) prefs.initialSetupReviewed = true
    }
    private fun moveTo(next: Int) { step = next.coerceIn(0, 2); saveProgress(); if (step == 2) openActivationStep() else render() }
    private fun closeSetup() { prefs.initialSetupInProgress = false; finish() }
    private fun openActivation() {
        saveProgress()
        startActivity(Intent(this, DexSetupActivity::class.java)
            .putExtra(DexSetupActivity.EXTRA_SHOW_ACTIVATION, true)
            .putExtra(DexSetupActivity.EXTRA_RETURN_TO_SETUP, true))
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DesktopPermissionList.REQUEST && step == 1) render()
    }
    @Suppress("DEPRECATION") override fun onBackPressed() { if (step > 0) moveTo(step - 1) else closeSetup() }

    companion object {
        internal fun profileAvailable(context: Context, prefs: DexPrefs): Boolean = when (prefs.desktopMode) {
            DesktopModePolicy.DRIVEDECK -> runCatching { ComponentPackages.requireDriveDeck(context) }.isSuccess
            DesktopModePolicy.SAMSUNG_DEX -> SamsungDexCapability.isAvailable(context)
            else -> true
        }
        fun batteryExempt(context: Context): Boolean = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
        fun requestBatteryExemption(activity: Activity) {
            if (batteryExempt(activity)) return
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
            for (intent in listOf(request, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))) {
                try { activity.startActivity(intent); return } catch (_: RuntimeException) { }
            }
            AlertDialog.Builder(activity).setTitle(activity.getString(R.string.step_background_short))
                .setMessage(activity.getString(R.string.desktop_open_your_phone_settings_applications_battery_and_allow_work, DesktopTransport.appName))
                .setPositiveButton(activity.getString(R.string.apps_warn_ok), null).show()
        }
    }
}
