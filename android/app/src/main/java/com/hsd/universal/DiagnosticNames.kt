package com.hsd.universal

import com.hsd.universal.dex.DesktopModePolicy

/** Format identifiers supplied by other installed apps only at the diagnostic output boundary. */
internal object DiagnosticNames {
    private val formerName = Regex("headswap(?:[ _-]?(?:desktop|desk|dex)\\b)?", RegexOption.IGNORE_CASE)
    private val formerProjection = Regex("\\bHSD[ _-]+AA\\b", RegexOption.IGNORE_CASE)

    fun format(message: String): String {
        if (!message.contains("headswap", ignoreCase = true) && !formerProjection.containsMatchIn(message)) return message
        val readable = message.replace(DesktopModePolicy.DRIVEDECK_PACKAGE, "DriveDeck")
            .replace("com.headswap.desk", "Desktop")
        return formerProjection.replace(formerName.replace(readable, BuildConfig.BRAND), BuildConfig.BRAND)
    }
}
