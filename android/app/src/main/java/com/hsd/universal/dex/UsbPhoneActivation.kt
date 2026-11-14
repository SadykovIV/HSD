package com.hsd.universal.dex

import android.content.Context
import android.content.pm.PackageManager
import com.flyfishxu.kadb.Kadb
import com.hsd.universal.BuildConfig
import com.hsd.universal.R
import com.hsd.universal.update.readSignedArchive
import okio.Buffer
import okio.ForwardingSink
import okio.sink
import java.io.File

/** One fixed operation on the Android phone selected and authorized by the user. */
internal object UsbPhoneActivation {
    private val basePath = Regex("""/(?:[A-Za-z0-9_~+=.-]+/)+base\.apk""")

    fun installedBase(paths: String): String? = paths.lineSequence()
        .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trimEnd() }
        .singleOrNull { basePath.matches(it) && it.split('/').none { part -> part == "." || part == ".." } }

    fun activate(context: Context, adb: Kadb, cancelled: () -> Boolean) {
        if (cancelled()) return
        val current = adb.shell("am get-current-user")
        val user = current.output.trim().toIntOrNull()?.takeIf { current.exitCode == 0 && it >= 0 }
            ?: error(context.getString(R.string.usb_helper_failed))
        val installed = adb.shell("pm path --user $user ${ManualAccessPolicy.PACKAGE}")
        val path = installedBase(installed.output).takeIf { installed.exitCode == 0 }
            ?: error(context.getString(R.string.usb_helper_install))
        // The target APK's entry point runs in its authorized shell. Check the installed signer
        // before invoking it, also when the two phones have different versions of HSD.
        val copy = File.createTempFile("usb-hsd-", ".apk", context.cacheDir)
        try {
            object : ForwardingSink(copy.sink()) {
                var received = 0L
                override fun write(source: Buffer, byteCount: Long) {
                    received += byteCount
                    check(received <= 128L * 1024 * 1024) { context.getString(R.string.usb_helper_invalid_apk) }
                    super.write(source, byteCount)
                }
            }.use { adb.pull(it, path) }
            verifyTarget(context, copy)
        } finally { copy.delete() }
        if (cancelled()) return
        val result = adb.shell("CLASSPATH='$path' app_process / ${ManualAccessPolicy.LAUNCHER} --saved")
        if (result.exitCode != 0 || result.output.lineSequence().none { it.trim() == ManualAccessPolicy.READY }) {
            val reason = result.allOutput.lineSequence().firstOrNull { it.startsWith("Активация не завершена:") }?.take(320)
            error(reason ?: context.getString(R.string.usb_helper_failed))
        }
    }

    fun verifyTarget(context: Context, apk: File) {
        val pm = context.packageManager
        val archive = pm.readSignedArchive(apk)
        val owner = pm.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = owner.signingInfo?.apkContentsSigners?.toSet()
        check(archive?.packageName == BuildConfig.APPLICATION_ID && !signers.isNullOrEmpty() &&
            archive.signingInfo?.apkContentsSigners?.toSet() == signers) { context.getString(R.string.usb_helper_invalid_apk) }
        check(archive.longVersionCode >= 82) { context.getString(R.string.usb_helper_install) }
    }
}
