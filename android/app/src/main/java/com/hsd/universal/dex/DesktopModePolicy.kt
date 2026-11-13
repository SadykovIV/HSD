package com.hsd.universal.dex

import com.hsd.universal.compat.PublishedDesktop
import com.hsd.universal.shared.ProjectionHost
import com.hsd.universal.BuildConfig

/** Desktop selection is independent from the transport and never changes the phone's HOME app. */
object DesktopModePolicy {
    const val SAMSUNG_DEX = 0
    const val ANDROID_DESKTOP = 1
    const val DRIVEDECK = 2
    const val ANDROID_HOME_CLASS = "com.hsd.universal.desktop.AndroidDesktopActivity"
    const val DRIVEDECK_PACKAGE = PublishedDesktop.DRIVEDECK
    const val DRIVEDECK_HOME_CLASS = "ru.drivedeck.launcher.MainActivity"
    const val UNIVERSAL_DRIVEDECK_HOME = "ru.drivedeck.launcher.HsdActivity"

    fun driveDeckHome(context: android.content.Context): String {
        val component = android.content.ComponentName(DRIVEDECK_PACKAGE, UNIVERSAL_DRIVEDECK_HOME)
        return try {
            val activity = context.packageManager.getActivityInfo(component, 0)
            check(activity.enabled && activity.exported && activity.permission == "${BuildConfig.APPLICATION_ID}.permission.EMBED_DISPLAY")
            UNIVERSAL_DRIVEDECK_HOME
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            DRIVEDECK_HOME_CLASS
        }
    }

    const val SAMSUNG_PACKAGE = "com.sec.android.app.launcher"
    const val SAMSUNG_HOME_CLASS = "com.honeyspace.dexservice.SecondaryLauncher"
    private const val SAMSUNG_HOME = "$SAMSUNG_PACKAGE/$SAMSUNG_HOME_CLASS"

    fun requireMode(mode: Int) {
        require(mode == SAMSUNG_DEX || mode == ANDROID_DESKTOP || mode == DRIVEDECK) { "Неизвестный режим рабочего стола" }
    }

    fun label(mode: Int): String {
        requireMode(mode)
        return when (mode) {
            SAMSUNG_DEX -> "Samsung DeX"
            DRIVEDECK -> "DriveDeck"
            else -> "Android Desktop"
        }
    }

    fun displayName(mode: Int): String = "${BuildConfig.BRAND} ${label(mode)}"

    /** PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | TRUSTED | DESTROY_CONTENT. */
    const val ANDROID_DISPLAY_FLAGS = 1 or 2 or 8 or 64 or 1024 or 256
    private const val VIRTUAL_DISPLAY_OWN_FOCUS = 1 shl 14
    const val VIRTUAL_DISPLAY_OWN_DISPLAY_GROUP = 1 shl 11
    const val VIRTUAL_DISPLAY_ALWAYS_UNLOCKED = 1 shl 12

    @JvmOverloads fun displayFlags(mode: Int, sdkInt: Int, independentLock: Boolean = false, trusted: Boolean = true): Int {
        requireMode(mode)
        if (!trusted) {
            check(sdkInt in 29..32 && mode != SAMSUNG_DEX) { "Этот режим требует доверенного дисплея Android" }
            // A public presentation containing only content launched on this display.
            return ANDROID_DISPLAY_FLAGS and 1024.inv()
        }
        if (mode == SAMSUNG_DEX) return DexUserServicePolicy.VIRTUAL_DISPLAY_FLAGS
        // A tap is forwarded parent -> child. Independent focus prevents the parent's DOWN
        // from clearing the embedded app's pressed state before its UP arrives. Android 13
        // has no per-display flag; retain its existing behavior. Do not disable stealing top
        // focus: that separate flag prevents the child from showing its software keyboard.
        return ANDROID_DISPLAY_FLAGS or (if (sdkInt >= 34) VIRTUAL_DISPLAY_OWN_FOCUS else 0) or
            (if (independentLock) VIRTUAL_DISPLAY_OWN_DISPLAY_GROUP or VIRTUAL_DISPLAY_ALWAYS_UNLOCKED else 0)
    }

    /** Every launch names the owned secondary display; no default-display or phone-HOME fallback. */
    fun homeCommand(mode: Int, displayId: Int, appPackage: String, driveDeck: String = DRIVEDECK_HOME_CLASS): Array<String> {
        requireMode(mode)
        require(displayId > 0) { "Запуск рабочего стола на основном экране запрещён" }
        require(appPackage.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) {
            "Неверный пакет приложения"
        }
        val common = arrayOf("/system/bin/am", "start", "--display", displayId.toString())
        return when (mode) {
            SAMSUNG_DEX -> common + arrayOf(
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.SECONDARY_HOME", "-n", SAMSUNG_HOME,
            )
            DRIVEDECK -> common + arrayOf(
                "--windowingMode", "1", "-f", "0x34000000", // NEW_TASK | CLEAR_TOP | SINGLE_TOP
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                "--es", ProjectionHost.PACKAGE, appPackage, "--ei", ProjectionHost.DISPLAY, displayId.toString(),
                "-n", "$DRIVEDECK_PACKAGE/$driveDeck",
            )
            else -> common + arrayOf(
                "--windowingMode", "1", "-f", "0x34000000", // NEW_TASK | CLEAR_TOP | SINGLE_TOP
                "-a", "android.intent.action.MAIN", "-n", "$appPackage/$ANDROID_HOME_CLASS",
            )
        }
    }

    /** HOME and APP_SWITCH must never reach a primary-display global navigation handler. */
    fun consumesNavigationKey(mode: Int, keycode: Int): Boolean {
        requireMode(mode)
        return mode != SAMSUNG_DEX && (keycode == 3 || keycode == 187)
    }
}
