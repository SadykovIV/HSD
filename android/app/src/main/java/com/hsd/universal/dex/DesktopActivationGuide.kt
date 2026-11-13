package com.hsd.universal.dex

import com.hsd.universal.DesktopTransport

/** User-visible commands, with Android substitutions quoted for the host shell. */
internal object DesktopActivationGuide {
    const val PLATFORM_TOOLS_URL = "https://developer.android.com/tools/releases/platform-tools"
    fun command(windows: Boolean, audio: Boolean, screen: Boolean): String {
        val adb = if (windows) ".\\adb.exe" else "./adb"
        val flags = (if (audio) " --audio" else "") + (if (screen) " --screen" else "")
        return "$adb -d shell 'CLASSPATH=\$(pm path ${ManualAccessPolicy.PACKAGE} | cut -d: -f2 | head -n 1) app_process / ${ManualAccessPolicy.LAUNCHER}$flags'"
    }
    fun instructions(context: android.content.Context, audio: Boolean, screen: Boolean) =
        context.getString(com.hsd.universal.R.string.desktop_activation_commands,
            command(true, audio, screen), command(false, audio, screen), ManualAccessPolicy.READY, DesktopTransport.name)
}
