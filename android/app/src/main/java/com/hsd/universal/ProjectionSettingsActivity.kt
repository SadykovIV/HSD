package com.hsd.universal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.security.KeyChain
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.hsd.universal.DesktopTransport
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.dex.DesktopSetupViews
import java.text.DateFormat
import java.util.Date

internal class ProjectionSettingsActivity : Activity() {
    private enum class Page { ROUTES, ROUTE, CERTIFICATES, RECEIVER }
    private val ui by lazy { DesktopSetupViews(this) }
    private val prefs by lazy { ProjectionRoute.prefs(this) }
    private val identities by lazy { ProjectionIdentity(this) }
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var page = Page.ROUTES
    private var route = ProjectionRoute.USB
    private var receiver = ""
    private var peer = ""
    private var peerName = ""
    private var lan = ""
    private var port = ""
    private var addressField: EditText? = null
    private var portField: EditText? = null
    private var request: String? = null
    private var askedForLocation = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        route = state?.getString("route")?.let(ProjectionRoute::valueOf) ?: ProjectionRoute.selected(this)
        page = state?.getString("page")?.let(Page::valueOf) ?: Page.ROUTES
        receiver = state?.getString("receiver").orEmpty()
        peer = state?.getString("peer") ?: prefs.getString("bluetoothPeer", "").orEmpty()
        peerName = state?.getString("peerName") ?: prefs.getString("bluetoothPeerName", "").orEmpty()
        lan = state?.getString("lan") ?: prefs.getString("lanPeer", "").orEmpty()
        port = state?.getString("port") ?: prefs.getInt("lanPort", 5288).toString()
        request = state?.getString("request")
        askedForLocation = state?.getBoolean("location") == true
        window.setDecorFitsSystemWindows(false)
        content = ui.column().apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(20))
            isFocusableInTouchMode = true
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ui.background)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            addView(content)
        }
        setContentView(scroll)
        render()
        scroll.post { scroll.scrollTo(0, state?.getInt("scroll") ?: 0) }
    }

    private fun capture() {
        addressField?.let { lan = it.text.toString() }
        portField?.let { port = it.text.toString() }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        capture()
        out.putString("page", page.name)
        out.putString("route", route.name)
        out.putString("receiver", receiver)
        out.putString("peer", peer)
        out.putString("peerName", peerName)
        out.putString("lan", lan)
        out.putString("port", port)
        out.putString("request", request)
        out.putBoolean("location", askedForLocation)
        out.putInt("scroll", scroll.scrollY)
    }

    private fun open(next: Page) {
        capture()
        page = next
        render()
        scroll.scrollTo(0, 0)
    }

    @Deprecated("Activity back navigation")
    override fun onBackPressed() {
        when (page) {
            Page.ROUTES -> finish()
            Page.RECEIVER -> open(Page.CERTIFICATES)
            Page.ROUTE, Page.CERTIFICATES -> open(Page.ROUTES)
        }
    }

    private fun render() {
        addressField = null
        portField = null
        content.removeAllViews()
        val heading = when (page) {
            Page.ROUTES -> getString(R.string.desktop_connection_type)
            Page.ROUTE -> getString(route.titleRes)
            Page.CERTIFICATES -> getString(R.string.desktop_android_auto_certificates)
            Page.RECEIVER -> identities.receiverName(receiver)
        }
        title = heading
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; content.addView(this) }
        ui.button(header, getString(R.string.set_back)) { onBackPressed() }.layoutParams = LinearLayout.LayoutParams(-2, -2)
        ui.text(header, heading, 23f, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(ui.dp(12), 0, 0, 0)
            isAccessibilityHeading = true
        }
        when (page) {
            Page.ROUTES -> {
                val aa = ui.section(content, "Android Auto")
                choice(aa, ProjectionRoute.USB, getString(R.string.desktop_connect_the_phone_to_the_head_unit_s_usb))
                choice(aa, ProjectionRoute.WIRELESS_AA, getString(R.string.desktop_pair_over_bluetooth_and_project_over_wi_fi))
                val carLife = ui.section(content, "Baidu CarLife")
                choice(carLife, ProjectionRoute.CARLIFE_USB, getString(R.string.desktop_connect_the_phone_to_the_carlife_usb_port))
                choice(carLife, ProjectionRoute.CARLIFE_WIFI, getString(R.string.desktop_project_over_wi_fi_or_wi_fi_direct))
                val cp = ui.section(content, "CarPlay")
                choice(cp, ProjectionRoute.CARPLAY, getString(R.string.desktop_pair_over_bluetooth_and_project_over_wi_fi))
                val lanGroup = ui.section(content, getString(R.string.desktop_android_auto_over_lan))
                choice(lanGroup, ProjectionRoute.LAN_SERVER, getString(R.string.desktop_the_head_unit_connects_to_the_phone_on_port))
                choice(lanGroup, ProjectionRoute.LAN_CLIENT, getString(R.string.desktop_the_phone_connects_to_the_head_unit_s_ip))
                ui.navigation(ui.section(content), getString(R.string.desktop_android_auto_certificates), identities.defaultAlias ?: getString(R.string.desktop_automatic)) {
                    open(Page.CERTIFICATES)
                }
            }
            Page.ROUTE -> routeOptions()
            Page.CERTIFICATES -> certificates()
            Page.RECEIVER -> receiverOptions()
        }
    }

    private fun choice(parent: LinearLayout, option: ProjectionRoute, hint: String) {
        val active = ProjectionRoute.selected(this) == option
        ui.navigation(parent, getString(option.titleRes), if (active) getString(R.string.desktop_selected, hint) else hint) {
            route = option
            open(Page.ROUTE)
        }.view.isSelected = active
    }

    private fun routeOptions() {
        val card = ui.section(content, route.protocol)
        when (route) {
            ProjectionRoute.CARLIFE_USB -> ui.text(card, getString(R.string.desktop_open_baidu_carlife_on_a_head_unit_connect_your))
            ProjectionRoute.CARLIFE_WIFI -> {
                ui.text(card, getString(R.string.desktop_open_wireless_carlife_on_a_head_unit_the_head))
                ui.navigation(card, getString(R.string.desktop_head_unit_and_network), getString(R.string.desktop_bluetooth_wi_fi_direct_and_hotspot)) {
                    com.hsd.universal.link.car.WirelessCarLife.configure(this)
                }
            }
            ProjectionRoute.USB -> ui.text(card, getString(R.string.desktop_open_android_auto_on_a_head_unit_and_connect))
            ProjectionRoute.LAN_SERVER, ProjectionRoute.LAN_CLIENT -> {
                ui.text(card, if (route == ProjectionRoute.LAN_SERVER)
                    getString(R.string.desktop_connect_the_devices_to_the_same_network_on_the)
                else getString(R.string.desktop_connect_your_devices_to_a_single_network_and_turn))
                if (route == ProjectionRoute.LAN_SERVER) {
                    val ips = localAddresses()
                    ui.text(card, if (ips.isEmpty()) getString(R.string.desktop_the_phone_address_will_appear_after_connecting_to_wi) else getString(R.string.desktop_phone_address, ips), 15f, true)
                }
                addressField = field(card, getString(R.string.desktop_head_unit_ip_address), lan)
                if (route == ProjectionRoute.LAN_CLIENT) portField = field(card, getString(R.string.desktop_head_unit_port), port, true)
            }
            ProjectionRoute.WIRELESS_AA, ProjectionRoute.CARPLAY -> {
                ui.text(card, getString(R.string.desktop_turn_on_the_wireless_select_a_head_unit_select, route.protocol))
                ui.navigation(card, getString(R.string.mock_bt_headunit), peerName.ifBlank { peer.ifBlank { getString(R.string.desktop_select_bluetooth_device) } }) {
                    withPermissions("pick")
                }
                ui.navigation(card, getString(R.string.desktop_bluetooth_settings), getString(R.string.desktop_pair_a_new_head_unit)) {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                if (route == ProjectionRoute.CARPLAY) {
                    ui.navigation(card, getString(R.string.desktop_make_phone_discoverable), getString(R.string.desktop_use_for_initial_pairing_when_the_head_unit_searches)) {
                        withPermissions("discoverable")
                    }
                    val bonds = getSharedPreferences("carplay-identities", MODE_PRIVATE)
                    if (peer.isNotEmpty() && bonds.contains("peer.$peer"))
                        ui.button(card, getString(R.string.desktop_forget_carplay_pairing)) {
                            AlertDialog.Builder(this).setTitle(getString(R.string.desktop_forget_this_carplay_pairing))
                                .setMessage(peerName.ifBlank { peer }).setNegativeButton(getString(R.string.mock_cancel), null)
                                .setPositiveButton(getString(R.string.desktop_forget)) { _, _ -> bonds.edit().remove("peer.$peer").apply(); capture(); render() }.show()
                        }
                }
            }
        }
        ui.button(content, if (route == ProjectionRoute.selected(this)) getString(R.string.diag_done) else getString(R.string.desktop_use_this_connection)) { withPermissions("save") }
    }

    private fun field(parent: LinearLayout, label: String, value: String, numeric: Boolean = false): EditText {
        val caption = ui.text(parent, label, 14f, true)
        return EditText(this).apply {
            id = View.generateViewId()
            caption.labelFor = id
            setSingleLine()
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(value)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun certificates() {
        val phone = ui.section(content, getString(R.string.desktop_phone_certificate))
        ui.text(phone, getString(R.string.desktop_hsd_creates_a_certificate_the_first_time_it_connects))
        ui.navigation(phone, getString(R.string.desktop_select_certificate), identities.defaultAlias ?: getString(R.string.desktop_automatic)) { chooseIdentity(null) }
        val receivers = ui.section(content, getString(R.string.desktop_head_unit_3d48))
        val ids = identities.receiverIds()
        if (ids.isEmpty()) ui.text(receivers, getString(R.string.desktop_after_the_first_connection_the_radio_will_appear_here))
        for (id in ids) ui.navigation(receivers, identities.receiverName(id), identities.connectionLabel(id) +
            if (identities.changed(id) != null) getString(R.string.desktop_certificate_changed) else "") {
            receiver = id
            open(Page.RECEIVER)
        }
    }

    private fun receiverOptions() {
        val id = receiver
        val card = ui.section(content, identities.connectionLabel(id))
        val last = identities.seen(id)
        if (last > 0) ui.text(card, getString(R.string.desktop_last_connection) + DateFormat.getDateTimeInstance().format(Date(last)))
        val alias = identities.assignedAlias(id)
        ui.navigation(card, getString(R.string.desktop_certificate_of_telephone_for_this_head_unit), when (alias) {
            null -> getString(R.string.desktop_general_settings)
            "" -> getString(R.string.desktop_hsd_certificate)
            else -> alias
        }) { chooseIdentity(id) }
        val fingerprints = identities.get(id)
        val changed = identities.changed(id)
        if (changed != null || fingerprints.isNotEmpty()) {
            val detail = ui.section(content, getString(R.string.desktop_certificate_of_recorder))
            for (pin in fingerprints.sorted()) fingerprint(detail, getString(R.string.desktop_save_sha_256), pin)
            if (changed != null) {
                fingerprint(detail, getString(R.string.desktop_new_sha_256), changed)
                ui.text(detail, getString(R.string.desktop_check_the_new_print_with_the_head_unit_data))
                ui.button(detail, getString(R.string.desktop_accept_new_certificate)) {
                    AlertDialog.Builder(this).setTitle(getString(R.string.desktop_replace_the_head_unit_certificate)).setMessage(changed)
                        .setNegativeButton(getString(R.string.mock_cancel), null).setPositiveButton(getString(R.string.desktop_confirm)) { _, _ ->
                            identities.acceptChange(id, changed); render()
                        }.show()
                }
            }
        }
        ui.button(content, getString(R.string.desktop_forget_head_unit)) {
            AlertDialog.Builder(this).setTitle(getString(R.string.desktop_forget_the_tape)).setMessage(getString(R.string.desktop_the_next_time_you_connect_the_hsd_will_remember))
                .setNegativeButton(getString(R.string.mock_cancel), null).setPositiveButton(getString(R.string.desktop_forget)) { _, _ ->
                    identities.forget(id); open(Page.CERTIFICATES)
                }.show()
        }
    }

    private fun fingerprint(parent: LinearLayout, label: String, value: String) {
        ui.text(parent, label, 14f, true)
        ui.text(parent, value.chunked(8).joinToString(" "), 14f).setTextIsSelectable(true)
    }

    private fun chooseIdentity(id: String?) {
        val alias = if (id == null) identities.defaultAlias else identities.assignedAlias(id)
        val labels = if (id == null) arrayOf(getString(R.string.desktop_automatic), getString(R.string.desktop_select_from_android_storage))
            else arrayOf(getString(R.string.desktop_general_settings), getString(R.string.desktop_hsd_certificate), getString(R.string.desktop_select_from_android_storage))
        fun select(value: String?) {
            if (id == null) identities.selectDefault(value) else identities.select(id, value)
            render()
        }
        val selected = when (alias) { null -> 0; "" -> 1; else -> labels.lastIndex }
        AlertDialog.Builder(this).setTitle(getString(R.string.desktop_phone_certificate)).setSingleChoiceItems(labels, selected) { dialog, which ->
            dialog.dismiss()
            when {
                which == 0 -> select(null)
                which < labels.lastIndex -> select("")
                else -> KeyChain.choosePrivateKeyAlias(this, { result ->
                    runOnUiThread {
                        if (result != null) {
                            if (id == null) identities.selectDefault(result) else identities.select(id, result)
                        }
                        if (!isFinishing && !isDestroyed) {
                            if (result == null) Toast.makeText(this, getString(R.string.desktop_certificate_not_selected_you_can_set_your_p12_or), Toast.LENGTH_LONG).show()
                            render()
                        }
                    }
                }, arrayOf("RSA", "EC"), null, null, -1, alias?.takeIf { it.isNotEmpty() })
            }
        }.setNegativeButton(getString(R.string.mock_cancel), null).show()
    }

    private fun withPermissions(action: String) {
        val required = buildList {
            if (action == "discoverable") {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                return@buildList
            }
            if (!route.wired && Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            if (route == ProjectionRoute.CARLIFE_WIFI) addAll(com.hsd.universal.link.car.WirelessCarLife.missingPermissions(this@ProjectionSettingsActivity))
            if (route == ProjectionRoute.WIRELESS_AA || route == ProjectionRoute.CARPLAY) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                if (!askedForLocation && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            if (route == ProjectionRoute.CARPLAY && action == "save") add(Manifest.permission.BLUETOOTH_SCAN)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (required.isEmpty()) perform(action)
        else {
            askedForLocation = askedForLocation || Manifest.permission.ACCESS_FINE_LOCATION in required
            request = action
            requestPermissions(required.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 1) return
        val pending = request ?: return
        request = null
        if (results.isNotEmpty() && permissions.indices.all { i ->
                results.getOrNull(i) == PackageManager.PERMISSION_GRANTED || permissions[i] == Manifest.permission.ACCESS_COARSE_LOCATION ||
                    permissions[i] == Manifest.permission.ACCESS_FINE_LOCATION || permissions[i] == Manifest.permission.BLUETOOTH_SCAN
            }) perform(pending)
        else Toast.makeText(this, getString(R.string.desktop_to_connect_provide_the_requested_access_in_the_android), Toast.LENGTH_LONG).show()
    }

    private fun perform(action: String) {
        when (action) {
            "pick" -> pickBondedDevice()
            "discoverable" -> {
                try {
                    startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE), REQUEST_DISCOVERABLE)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, getString(R.string.desktop_open_bluetooth_settings_and_make_the_phone_discoverable), Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(this, getString(R.string.desktop_allow_hsd_to_access_nearby_devices), Toast.LENGTH_LONG).show()
                }
            }
            else -> save()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DISCOVERABLE && resultCode > 0)
            Toast.makeText(this, getString(R.string.desktop_phone_visible_select_it_on_the_head_unit_and, resultCode), Toast.LENGTH_LONG).show()
    }

    private companion object { const val REQUEST_DISCOVERABLE = 2 }

    @SuppressLint("MissingPermission")
    private fun pickBondedDevice() {
        val devices = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().sortedBy { it.name.orEmpty() }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.desktop_paired_devices))
                .setMessage(getString(R.string.desktop_pair_the_phone_with_the_head_unit_then_select))
                .setPositiveButton(getString(R.string.step_bluetooth_primary)) { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.mock_cancel), null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.desktop_choose_head_unit)).setItems(devices.map { "${it.name.orEmpty()} · ${it.address}" }.toTypedArray()) { _, index ->
            capture()
            peer = devices[index].address
            peerName = devices[index].name.orEmpty()
            render()
        }.show()
    }

    private fun save() {
        capture()
        val edit = prefs.edit().putString("mode", route.name)
        if (route == ProjectionRoute.LAN_CLIENT || route == ProjectionRoute.LAN_SERVER) {
            val ip = runCatching { InetAddresses.parseNumericAddress(lan.trim()) }.getOrNull()
            if (ip == null || ip.isAnyLocalAddress || ip.isMulticastAddress) {
                addressField?.error = getString(R.string.desktop_enter_the_ip_address_of_one_head_unit); return
            }
            edit.putString("lanPeer", ip.hostAddress)
            if (route == ProjectionRoute.LAN_CLIENT) {
                val parsed = port.toIntOrNull()
                if (parsed == null || parsed !in 1..65535) { portField?.error = getString(R.string.desktop_port_1_to_65535); return }
                edit.putInt("lanPort", parsed)
            }
        }
        if (route == ProjectionRoute.WIRELESS_AA || route == ProjectionRoute.CARPLAY) {
            if (peer.isEmpty()) { pickBondedDevice(); return }
            edit.putString("bluetoothPeer", peer).putString("bluetoothPeerName", peerName)
        }
        ProjectionRoute.save(this, edit)
        finish()
    }

    private fun localAddresses() = runCatching {
        val peer = runCatching { InetAddresses.parseNumericAddress(lan.trim()) }.getOrNull()
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return@runCatching ""
        receiverLocalAddresses(connectivity, peer).ifEmpty { receiverLocalAddresses(connectivity, null) }
            .mapNotNull { it.hostAddress }.joinToString(", ")
    }.getOrDefault("")
}
