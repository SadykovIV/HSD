package com.hsd.universal.compat

/** Identifiers published by other installed APKs and OS state shared with released desktop versions. */
internal object PublishedDesktop {
    const val PREFS = "headswap_dex"
    const val ADB_KEY = "headswap.dex.adb.identity.v1"
    const val ASSOCIATION_LABEL = "headswap-desk"
    const val DENSITY_JOURNAL = "headswap_primary_density_lease_v1"
    const val DENSITY_LOCK_PREFIX = "headswap.primary.density.v1."
    const val OVERLAY_OWNER = "/data/local/tmp/headswap-dex-overlay.owner"
    const val OVERLAY_LOCK = "/data/local/tmp/headswap-dex-overlay.lock"
    const val DRIVEDECK = "ru.drivedeck.headswap"
    const val CARBRIDGE = "com.example.carbridge.headswap"
    const val CARBRIDGE_PREVIEW = "com.example.headswap.panel.carbridgepreview"
    const val NAVIGATION_ADAPTER = "com.headswap.navigator.experimental"
    const val GUIDANCE_SERVICE = "com.headswap.navbridge.GuidanceService"
    const val GUIDANCE_DESCRIPTOR = "com.headswap.navigation.Source.v1"
}
