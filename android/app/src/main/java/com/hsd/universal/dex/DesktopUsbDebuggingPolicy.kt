package com.hsd.universal.dex

/** USB reconfiguration must be able to restart the shell host's parent, adbd. */
internal object DesktopUsbDebuggingPolicy {
    enum class State { ENABLED, DISABLED, UNKNOWN }

    // Samsung's wired USB mode change can stop the shell host. Standard Wi-Fi ADB has no USB prerequisite.
    fun required(sdk: Int, samsung: Boolean, delegated: Boolean, nativeDex: Boolean, wired: Boolean): Boolean =
        sdk >= 37 && samsung && delegated && !nativeDex && wired

    fun state(setting: String?, usbAdb: Boolean?): State = when {
        usbAdb == true || setting?.trim() == "1" -> State.ENABLED
        setting?.trim() == "0" -> State.DISABLED
        else -> State.UNKNOWN
    }

    fun hasAdbFunction(functions: String?): Boolean =
        functions.orEmpty().split(',').any { it.trim() == "adb" }

    fun verifiedState(setting: String?, functions: String?): State =
        state(setting, null).takeUnless { it == State.UNKNOWN }
            ?: state(null, hasAdbFunction(functions))

    /** App-side settings may be filtered by a firmware. Only the authenticated shell can reject setup. */
    data class Confirmation(val appSetting: String?, val shellState: State) {
        fun stillMissing(currentAppSetting: String?, usbAdb: Boolean?): Boolean =
            shellState == State.DISABLED && currentAppSetting == appSetting &&
                usbAdb != true
    }
}
