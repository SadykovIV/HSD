package com.hsd.universal.update

/** DriveDeck's notification listener is separate from HSD's HUD notification access. */
internal object DriveDeckPermissionPolicy {
    const val LISTENER_CLASS = "ru.drivedeck.launcher.NotificationBridgeService"
    const val PACKAGE = com.hsd.universal.dex.DesktopModePolicy.DRIVEDECK_PACKAGE
    const val LISTENER = "$PACKAGE/$LISTENER_CLASS"

    fun listenerEnabled(serialized: String?): Boolean = serialized?.split(':')?.any { it == LISTENER } == true
}
