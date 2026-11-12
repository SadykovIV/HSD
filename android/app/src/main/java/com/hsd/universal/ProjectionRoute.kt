package com.hsd.universal

import android.content.Context

internal enum class ProjectionProtocol(val title: String) {
    ANDROID_AUTO("Android Auto"), CARPLAY("CarPlay"), CARLIFE("CarLife")
}

internal enum class ProjectionRoute(val family: ProjectionProtocol, val title: String, val link: String, val wired: Boolean = false) {
    USB(ProjectionProtocol.ANDROID_AUTO, "По USB", "USB", true),
    WIRELESS_AA(ProjectionProtocol.ANDROID_AUTO, "Беспроводной Android Auto", "Bluetooth и Wi-Fi"),
    LAN_SERVER(ProjectionProtocol.ANDROID_AUTO, "Сервер на телефоне", "сервер на телефоне"),
    LAN_CLIENT(ProjectionProtocol.ANDROID_AUTO, "Сервер на магнитоле", "сервер на магнитоле"),
    CARPLAY(ProjectionProtocol.CARPLAY, "Беспроводной CarPlay", "Bluetooth и Wi-Fi"),
    CARLIFE_USB(ProjectionProtocol.CARLIFE, "CarLife по USB", "USB", true),
    CARLIFE_WIFI(ProjectionProtocol.CARLIFE, "Беспроводной CarLife", "Wi-Fi");

    val protocol get() = family.title
    val label get() = "$protocol · $link"
    val carLife get() = family == ProjectionProtocol.CARLIFE

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
        fun wireless(context: Context) = !selected(context).wired
    }
}
