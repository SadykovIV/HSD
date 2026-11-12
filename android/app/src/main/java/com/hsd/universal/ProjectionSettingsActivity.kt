package com.hsd.universal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
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
    private var mac = ""
    private var addressField: EditText? = null
    private var portField: EditText? = null
    private var macField: EditText? = null
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
        mac = state?.getString("mac") ?: prefs.getString("localBluetoothAddress", "").orEmpty()
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
        macField?.let { mac = it.text.toString() }
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
        out.putString("mac", mac)
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
        macField = null
        content.removeAllViews()
        val heading = when (page) {
            Page.ROUTES -> "Тип подключения"
            Page.ROUTE -> route.title
            Page.CERTIFICATES -> "Сертификаты Android Auto"
            Page.RECEIVER -> identities.receiverName(receiver)
        }
        title = heading
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; content.addView(this) }
        ui.button(header, "Назад") { onBackPressed() }.layoutParams = LinearLayout.LayoutParams(-2, -2)
        ui.text(header, heading, 23f, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(ui.dp(12), 0, 0, 0)
            isAccessibilityHeading = true
        }
        when (page) {
            Page.ROUTES -> {
                val aa = ui.section(content, "Android Auto")
                choice(aa, ProjectionRoute.USB, "Кабель от телефона к USB-порту магнитолы")
                choice(aa, ProjectionRoute.WIRELESS_AA, "Сопряжение по Bluetooth, проекция по Wi-Fi")
                val carLife = ui.section(content, "Baidu CarLife")
                choice(carLife, ProjectionRoute.CARLIFE_USB, "Кабель от телефона к USB-порту CarLife")
                choice(carLife, ProjectionRoute.CARLIFE_WIFI, "Проекция по Wi-Fi или Wi-Fi Direct")
                val cp = ui.section(content, "CarPlay")
                choice(cp, ProjectionRoute.CARPLAY, "Сопряжение по Bluetooth, проекция по Wi-Fi")
                val lanGroup = ui.section(content, "Android Auto по локальной сети")
                choice(lanGroup, ProjectionRoute.LAN_SERVER, "Магнитола подключается к телефону на порт 5277")
                choice(lanGroup, ProjectionRoute.LAN_CLIENT, "Телефон подключается к IP и порту магнитолы")
                ui.navigation(ui.section(content), "Сертификаты Android Auto", identities.defaultAlias ?: "Автоматически") {
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
        ui.navigation(parent, option.title, if (active) "✓ Выбрано · $hint" else hint) {
            route = option
            open(Page.ROUTE)
        }.view.isSelected = active
    }

    private fun routeOptions() {
        val card = ui.section(content, route.protocol)
        when (route) {
            ProjectionRoute.CARLIFE_USB -> ui.text(card, "Откройте Baidu CarLife на магнитоле, подключите телефон кабелем передачи данных и разрешите доступ к USB.")
            ProjectionRoute.CARLIFE_WIFI -> {
                ui.text(card, "Откройте беспроводной CarLife на магнитоле. Магнитола может использовать общую сеть Wi-Fi, точку доступа или согласовать Wi-Fi Direct через Bluetooth.")
                ui.navigation(card, "Магнитола и сеть", "Bluetooth, Wi-Fi Direct и точка доступа") {
                    com.hsd.universal.link.car.WirelessCarLife.configure(this)
                }
            }
            ProjectionRoute.USB -> ui.text(card, "Откройте Android Auto на магнитоле и подключите телефон кабелем с передачей данных.")
            ProjectionRoute.LAN_SERVER, ProjectionRoute.LAN_CLIENT -> {
                ui.text(card, if (route == ProjectionRoute.LAN_SERVER)
                    "Подключите устройства к одной сети. На магнитоле укажите адрес телефона и порт 5277."
                else "Подключите устройства к одной сети и включите сервер Android Auto на магнитоле.")
                if (route == ProjectionRoute.LAN_SERVER) {
                    val ips = localAddresses()
                    ui.text(card, if (ips.isEmpty()) "Адрес телефона появится после подключения к Wi-Fi." else "Адрес телефона · $ips", 15f, true)
                }
                addressField = field(card, "IP-адрес магнитолы", lan)
                if (route == ProjectionRoute.LAN_CLIENT) portField = field(card, "Порт магнитолы", port, true)
            }
            ProjectionRoute.WIRELESS_AA, ProjectionRoute.CARPLAY -> {
                ui.text(card, "Включите беспроводной ${route.protocol} на магнитоле. Выберите сопряжённое устройство.")
                ui.navigation(card, "Магнитола", peerName.ifBlank { peer.ifBlank { "Выбрать устройство Bluetooth" } }) {
                    withPermissions("pick")
                }
                ui.navigation(card, "Настройки Bluetooth", "Сопряжение с новой магнитолой") {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                if (route == ProjectionRoute.CARPLAY) {
                    val extra = ui.section(content, "Параметры CarPlay")
                    ui.text(extra, "Если Android скрывает Bluetooth-адрес телефона, введите его из сведений об устройстве.")
                    macField = field(extra, "Bluetooth-адрес телефона", mac)
                    val bonds = getSharedPreferences("carplay-identities", MODE_PRIVATE)
                    if (peer.isNotEmpty() && bonds.contains("peer.$peer"))
                        ui.button(extra, "Забыть сопряжение CarPlay") {
                            AlertDialog.Builder(this).setTitle("Забыть сопряжение CarPlay?")
                                .setMessage(peerName.ifBlank { peer }).setNegativeButton("Отмена", null)
                                .setPositiveButton("Забыть") { _, _ -> bonds.edit().remove("peer.$peer").apply(); capture(); render() }.show()
                        }
                }
            }
        }
        ui.button(content, if (route == ProjectionRoute.selected(this)) "Готово" else "Использовать этот способ") { withPermissions("save") }
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
        val phone = ui.section(content, "Сертификат телефона")
        ui.text(phone, "HSD создаёт сертификат при первом подключении. Если магнитола требует сертификат своего издателя, выберите выданный вам ключ в Android.")
        ui.navigation(phone, "Выбор сертификата", identities.defaultAlias ?: "Автоматически") { chooseIdentity(null) }
        val receivers = ui.section(content, "Магнитолы")
        val ids = identities.receiverIds()
        if (ids.isEmpty()) ui.text(receivers, "После первого соединения магнитола появится здесь. При повторном подключении HSD сверит её сертификат автоматически.")
        for (id in ids) ui.navigation(receivers, identities.receiverName(id), identities.connectionLabel(id) +
            if (identities.changed(id) != null) " · Сертификат изменился" else "") {
            receiver = id
            open(Page.RECEIVER)
        }
    }

    private fun receiverOptions() {
        val id = receiver
        val card = ui.section(content, identities.connectionLabel(id))
        val last = identities.seen(id)
        if (last > 0) ui.text(card, "Последнее соединение · " + DateFormat.getDateTimeInstance().format(Date(last)))
        val alias = identities.assignedAlias(id)
        ui.navigation(card, "Сертификат телефона для этой магнитолы", when (alias) {
            null -> "Общая настройка"
            "" -> "Сертификат HSD"
            else -> alias
        }) { chooseIdentity(id) }
        val fingerprints = identities.get(id)
        val changed = identities.changed(id)
        if (changed != null || fingerprints.isNotEmpty()) {
            val detail = ui.section(content, "Сертификат магнитолы")
            for (pin in fingerprints.sorted()) fingerprint(detail, "Сохранённый SHA-256", pin)
            if (changed != null) {
                fingerprint(detail, "Новый SHA-256", changed)
                ui.text(detail, "Сверьте новый отпечаток с данными магнитолы перед заменой.")
                ui.button(detail, "Принять новый сертификат") {
                    AlertDialog.Builder(this).setTitle("Заменить сертификат магнитолы?").setMessage(changed)
                        .setNegativeButton("Отмена", null).setPositiveButton("Подтвердить") { _, _ ->
                            identities.acceptChange(id, changed); render()
                        }.show()
                }
            }
        }
        ui.button(content, "Забыть магнитолу") {
            AlertDialog.Builder(this).setTitle("Забыть магнитолу?").setMessage("При следующем подключении HSD заново запомнит сертификат этого устройства.")
                .setNegativeButton("Отмена", null).setPositiveButton("Забыть") { _, _ ->
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
        val labels = if (id == null) arrayOf("Автоматически", "Выбрать из хранилища Android…")
            else arrayOf("Общая настройка", "Сертификат HSD", "Выбрать из хранилища Android…")
        fun select(value: String?) {
            if (id == null) identities.selectDefault(value) else identities.select(id, value)
            render()
        }
        val selected = when (alias) { null -> 0; "" -> 1; else -> labels.lastIndex }
        AlertDialog.Builder(this).setTitle("Сертификат телефона").setSingleChoiceItems(labels, selected) { dialog, which ->
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
                            if (result == null) Toast.makeText(this, "Сертификат не выбран. Свой файл .p12 или .pfx можно установить в настройках безопасности Android.", Toast.LENGTH_LONG).show()
                            render()
                        }
                    }
                }, arrayOf("RSA", "EC"), null, null, -1, alias?.takeIf { it.isNotEmpty() })
            }
        }.setNegativeButton("Отмена", null).show()
    }

    private fun withPermissions(action: String) {
        val required = buildList {
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
                    permissions[i] == Manifest.permission.ACCESS_FINE_LOCATION
            }) perform(pending)
        else Toast.makeText(this, "Для подключения предоставьте запрошенный доступ в настройках Android.", Toast.LENGTH_LONG).show()
    }

    private fun perform(action: String) {
        if (action == "pick") pickBondedDevice() else save()
    }

    @SuppressLint("MissingPermission")
    private fun pickBondedDevice() {
        val devices = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().sortedBy { it.name.orEmpty() }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Сопряжённые устройства")
                .setMessage("Выполните сопряжение с магнитолой и вернитесь к её выбору.")
                .setPositiveButton("Открыть Bluetooth") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Отмена", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Выбрать магнитолу").setItems(devices.map { "${it.name.orEmpty()} · ${it.address}" }.toTypedArray()) { _, index ->
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
                addressField?.error = "Введите IP-адрес одной магнитолы"; return
            }
            edit.putString("lanPeer", ip.hostAddress)
            if (route == ProjectionRoute.LAN_CLIENT) {
                val parsed = port.toIntOrNull()
                if (parsed == null || parsed !in 1..65535) { portField?.error = "Порт от 1 до 65535"; return }
                edit.putInt("lanPort", parsed)
            }
        }
        if (route == ProjectionRoute.WIRELESS_AA || route == ProjectionRoute.CARPLAY) {
            if (peer.isEmpty()) { pickBondedDevice(); return }
            edit.putString("bluetoothPeer", peer).putString("bluetoothPeerName", peerName)
            if (route == ProjectionRoute.CARPLAY) {
                val local = mac.trim().uppercase()
                if (local.isNotEmpty() && !com.hsd.universal.carplay.BluetoothManagerAddress.valid(local)) {
                    macField?.error = "Введите Bluetooth-адрес телефона"; return
                }
                edit.putString("localBluetoothAddress", local)
            }
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
