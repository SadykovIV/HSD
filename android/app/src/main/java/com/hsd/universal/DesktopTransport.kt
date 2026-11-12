package com.hsd.universal

import android.content.Context
import android.content.Intent

/** The phone UI and desktop sources are shared. Only the projection endpoint changes. */
internal object DesktopTransport {
    const val universal = BuildConfig.PHONE_PROJECTION
    @Volatile private var application: Context? = null
    @Volatile internal var activeRoute: ProjectionRoute? = null
    val name get() = activeRoute?.protocol ?: application?.let { ProjectionRoute.selected(it).protocol }
        ?: if (universal) "Android Auto" else "CarLife"

    fun init(context: Context) { application = context.applicationContext }
    val appName = BuildConfig.APP_TITLE
    val shortName = BuildConfig.BRAND

    fun statusText(value: String): String =
        if (universal) value.replace("CarLife", name, ignoreCase = true) else value

    fun service(context: Context): Intent = if (universal)
        Intent().setClassName(context.packageName, "com.hsd.universal.AutoService")
    else Intent(context, BridgeService::class.java)
}
