package com.hsd.universal.dex

/** A way to obtain access, independent of the process that supplies it. */
enum class ActivationMethod(val labelRes: Int) {
    WIFI(com.hsd.universal.R.string.activation_wifi), SHIZUKU(com.hsd.universal.R.string.activation_shizuku), COMPUTER(com.hsd.universal.R.string.activation_computer), DIRECT_ADB(com.hsd.universal.R.string.activation_direct);

    fun backend(profile: Int): DesktopAccessMode? = when (this) {
        WIFI, COMPUTER -> DesktopAccessMode.COMPUTER
        SHIZUKU -> DesktopAccessMode.SHIZUKU
        DIRECT_ADB -> DesktopAccessMode.LEGACY_ADB.takeIf { profile == DesktopModePolicy.SAMSUNG_DEX }
    }

    companion object {
        fun restore(profile: Int, saved: String?, backend: DesktopAccessMode): ActivationMethod =
            entries.firstOrNull { it.name == saved && it.backend(profile) == backend }
                ?: when (backend) {
                    DesktopAccessMode.COMPUTER -> WIFI
                    DesktopAccessMode.SHIZUKU -> SHIZUKU
                    DesktopAccessMode.LEGACY_ADB -> DIRECT_ADB
                }
    }
}
