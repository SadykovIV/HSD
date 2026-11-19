package com.hsd.universal

import android.content.Context

internal enum class ProjectionProtocol(val title: String) {
    ANDROID_AUTO("Android Auto"), CARPLAY("CarPlay"), CARLIFE("CarLife"), HICAR("Huawei HiCar")
}

internal enum class ProjectionRoute(val family: ProjectionProtocol, val titleRes: Int, private val linkRes: Int, val wired: Boolean = false) {
    USB(ProjectionProtocol.ANDROID_AUTO, R.string.route_usb, R.string.route_link_usb, true),
    WIRELESS_AA(ProjectionProtocol.ANDROID_AUTO, R.string.route_aa_wireless, R.string.route_link_bt_wifi),
    LAN_SERVER(ProjectionProtocol.ANDROID_AUTO, R.string.route_phone_server, R.string.route_phone_server),
    LAN_CLIENT(ProjectionProtocol.ANDROID_AUTO, R.string.route_hu_server, R.string.route_hu_server),
    CARPLAY(ProjectionProtocol.CARPLAY, R.string.route_cp_wireless, R.string.route_link_bt_wifi),
    HICAR(ProjectionProtocol.HICAR, R.string.route_hicar_wireless, R.string.route_link_bt_wifi),
    HICAR_USB(ProjectionProtocol.HICAR, R.string.route_hicar_usb, R.string.route_link_usb, true),
    CARLIFE_USB(ProjectionProtocol.CARLIFE, R.string.route_cl_usb, R.string.route_link_usb, true),
    CARLIFE_WIFI(ProjectionProtocol.CARLIFE, R.string.route_cl_wireless, R.string.route_link_wifi);

    val protocol get() = family.title
    fun label(context: Context) = "$protocol · ${context.getString(linkRes)}"
    val carLife get() = family == ProjectionProtocol.CARLIFE
    val usesHeadUnitHotspot get() = this == CARPLAY || this == WIRELESS_AA || this == HICAR
    val usesUsbAccessory get() = this == USB || this == CARLIFE_USB
    val usesLocalNetwork get() = !usesUsbAccessory

    companion object {
        fun prefs(context: Context) = context.getSharedPreferences("projection-route", Context.MODE_PRIVATE)
        fun save(context: Context, edit: android.content.SharedPreferences.Editor) {
            val settings = prefs(context)
            val before = settings.all
            edit.apply()
            if (before != settings.all) com.hsd.universal.ui.Actions.routeChanged(context)
        }
        fun restore(value: String?) = entries.firstOrNull { it.name == value } ?: USB
        fun selected(context: Context) = if (DesktopTransport.universal) restore(prefs(context).getString("mode", null)) else CARLIFE_USB
    }
}
