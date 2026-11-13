package com.hsd.universal.link.car

import com.hsd.universal.R
import com.hsd.universal.BuildConfig
import com.hsd.universal.DesktopTransport

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.hsd.universal.AppLocale
import com.hsd.universal.CarLifeState
import com.hsd.universal.dex.DesktopSetupViews

/** Configuration only. The foreground BridgeService owns all connection resources. */
class WirelessSetupActivity : Activity() {
    private lateinit var ui: DesktopSetupViews
    private lateinit var status: TextView
    private lateinit var selectedCar: TextView
    private lateinit var network: TextView
    private val main = Handler(Looper.getMainLooper())
    private enum class PermissionAction { CHECK, CONNECT, PAIR }
    private var afterPermission: PermissionAction? = null
    private val refresh = object : Runnable {
        override fun run() { updateStatus(); main.postDelayed(this, 1000) }
    }

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        afterPermission = PermissionAction.entries.firstOrNull { it.name == state?.getString("afterPermission") }
        ui = DesktopSetupViews(this)
        val content = ui.column().apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(ui.background); addView(content) }
        setContentView(scroll)
        ui.button(content, getString(R.string.desktop_connection_208e)) { finish() }
        ui.text(content, getString(R.string.desktop_carlife_over_wi_fi), 26f, true)
        ui.text(content, getString(R.string.desktop_for_a_wireless_carlife_head_unit_a_separate_step, BuildConfig.BRAND))
        val connection = ui.section(content, getString(R.string.desktop_connection))
        if (!DesktopTransport.universal) ui.toggle(connection, getString(R.string.desktop_wireless_connection), WirelessPrefs(this).enabled) {
            WirelessPrefs(this).enabled = it
            updateStatus()
        }
        status = ui.text(connection, "")
        ui.button(connection, getString(R.string.desktop_allow_connection)) { withPermissions(PermissionAction.CHECK) }
        if (WirelessPrefs(this).enabled || !DesktopTransport.universal)
            ui.button(connection, getString(R.string.desktop_connect_over_wi_fi)) { withPermissions(PermissionAction.CONNECT) }
        ui.text(connection, getString(R.string.desktop_turn_on_your_phone_s_wi_fi_and_bluetooth))
        ui.button(connection, getString(R.string.desktop_open_wi_fi_settings)) { startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
        ui.button(connection, getString(R.string.step_bluetooth_primary)) { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }

        val car = ui.section(content, getString(R.string.desktop_my_head_unit))
        selectedCar = ui.text(car, "")
        ui.text(car, getString(R.string.desktop_select_a_paired_bluetooth_head_unit_device_when_it, BuildConfig.BRAND))
        ui.button(car, getString(R.string.desktop_choose_head_unit)) { withPermissions(PermissionAction.PAIR) }
        ui.button(car, getString(R.string.desktop_forget_head_unit)) { CarPairing.forget(this); updateStatus() }

        val wifi = ui.section(content, getString(R.string.desktop_head_unit_network))
        network = ui.text(wifi, "")
        ui.text(wifi, getString(R.string.desktop_wi_fi_direct_is_automatically_configured_via_bluetooth_if))
        ui.button(wifi, getString(R.string.desktop_set_head_unit_network)) { editNetwork() }
        ui.button(wifi, getString(R.string.desktop_remove_network)) { CarWifi(this).clear(); updateStatus() }
        if (state == null && intent.getBooleanExtra(WirelessCarLife.EXTRA_CONNECT, false))
            withPermissions(PermissionAction.CONNECT)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("afterPermission", afterPermission?.name)
    }

    override fun onResume() { super.onResume(); main.removeCallbacks(refresh); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        status.text = WirelessCarLife.unavailableReason(this) ?: when {
            CarLifeState.wirelessSession && CarLifeState.running -> getString(R.string.desktop_carlife_is_connected_over_wi_fi)
            CarLifeState.wireless != null -> CarLifeState.wireless
            else -> getString(R.string.desktop_ready_to_connect)
        }
        selectedCar.text = CarProfile(this).let { it.btName ?: if (it.isSet) getString(R.string.desktop_head_unit_selected) else getString(R.string.desktop_the_head_unit_is_not_selected_yet) }
        network.text = CarWifi(this).ssid?.let { getString(R.string.desktop_saved_network, it) } ?: getString(R.string.desktop_network_not_manually_set)
    }

    private fun withPermissions(action: PermissionAction) {
        if (!DesktopTransport.universal && !WirelessPrefs(this).enabled) {
            status.text = getString(R.string.desktop_first_turn_on_the_wireless_connection)
            return
        }
        val missing = WirelessCarLife.missingPermissions(this)
        if (missing.isEmpty()) { perform(action); return }
        afterPermission = action
        requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun perform(action: PermissionAction) {
        when (action) {
            PermissionAction.CHECK -> updateStatus()
            PermissionAction.CONNECT -> { WirelessCarLife.startReady(this); updateStatus() }
            PermissionAction.PAIR -> CarPairing.start(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val next = afterPermission
        afterPermission = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED } &&
            WirelessCarLife.missingPermissions(this).isEmpty()) next?.let(::perform)
        updateStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CarPairing.REQ_CAR_PAIR) { CarPairing.onResult(this, resultCode, data); updateStatus() }
    }

    private fun editNetwork() {
        val saved = CarWifi(this)
        val box = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val name = EditText(this).apply {
            hint = getString(R.string.desktop_head_unit_wi_fi_network_name)
            setSingleLine(true)
            setText(saved.ssid)
        }
        val password = EditText(this).apply {
            hint = getString(R.string.desktop_password_leave_blank_for_an_open_network)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            isSaveEnabled = false
            setText(saved.pass)
        }
        box.addView(name); box.addView(password)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.desktop_head_unit_network)).setView(box)
            .setPositiveButton(getString(R.string.desktop_save), null).setNegativeButton(getString(R.string.mock_cancel), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ssid = name.text.toString()
                val pass = password.text.toString()
                if (ssid.isBlank() || ssid.toByteArray(Charsets.UTF_8).size > 32) {
                    name.error = getString(R.string.desktop_enter_the_network_name_to_32_bytes)
                } else if (pass.isNotEmpty() && pass.length !in 8..63) {
                    password.error = getString(R.string.desktop_password_must_contain_8_63_characters)
                } else {
                    saved.save(ssid, pass)
                    dialog.dismiss()
                    updateStatus()
                }
            }
        }
        dialog.show()
    }

    companion object { private const val REQUEST_PERMISSIONS = 196 }
}
