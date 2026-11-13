package com.hsd.universal.dex

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Availability only. Privileged work lives in the separately bound UserService. */
object DexPrivilegeAccess {
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    enum class Status { NOT_INSTALLED, NOT_RUNNING, UPDATE_REQUIRED, PERMISSION_REQUIRED, DENIED_PERMANENTLY, READY }

    // DexUserService requires the Context constructor introduced in server API 13.
    const val MIN_SERVER_VERSION = 13

    fun status(context: Context): Status {
        // The binder may disappear between any two calls; never let this crash the UI.
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return runCatching {
                when {
                    Shizuku.isPreV11() || Shizuku.getVersion() < MIN_SERVER_VERSION -> Status.UPDATE_REQUIRED
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Status.READY
                    Shizuku.shouldShowRequestPermissionRationale() -> Status.DENIED_PERMANENTLY
                    else -> Status.PERMISSION_REQUIRED
                }
            }.getOrDefault(Status.NOT_RUNNING)
        }
        val installed = runCatching {
            context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        }.isSuccess
        return if (installed) Status.NOT_RUNNING else Status.NOT_INSTALLED
    }

    fun isReady(context: Context): Boolean = status(context) == Status.READY

    fun requestPermission(requestCode: Int) {
        check(Shizuku.pingBinder()) { "Сначала запустите Shizuku" }
        check(!Shizuku.isPreV11() && Shizuku.getVersion() >= MIN_SERVER_VERSION) { "Для рабочего стола нужен Shizuku 13 или новее" }
        Shizuku.requestPermission(requestCode)
    }
}
