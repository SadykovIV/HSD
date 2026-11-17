package com.hsd.universal.dex

/** Read-only USB transport diagnostics. A setting is not proof of a live ADB channel. */
internal object DesktopUsbDebuggingPolicy {
    enum class State { ENABLED, DISABLED, UNKNOWN }

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

}
