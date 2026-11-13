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
        fun available(profile: Int, sdk: Int) = entries.filter {
            it.backend(profile) != null && (it != WIFI || sdk >= 30)
        }

        fun restore(profile: Int, saved: String?, backend: DesktopAccessMode, sdk: Int = 30): ActivationMethod =
            available(profile, sdk).firstOrNull { it.name == saved && it.backend(profile) == backend }
                ?: when (backend) {
                    DesktopAccessMode.COMPUTER -> if (sdk >= 30) WIFI else COMPUTER
                    DesktopAccessMode.SHIZUKU -> SHIZUKU
                    DesktopAccessMode.LEGACY_ADB -> DIRECT_ADB
                }
    }
}
