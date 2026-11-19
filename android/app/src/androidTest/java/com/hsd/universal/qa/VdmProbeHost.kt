package com.hsd.universal.qa

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import com.hsd.universal.dex.VdmHostBootstrapPolicy
import com.hsd.universal.dex.VdmHostLink
import java.util.UUID

/** Same authenticated production bootstrap for each API37 runtime regression; no UI permission bypass. */
internal object VdmProbeHost {
    fun start(test: Instrumentation, ui: UiAutomation): AutoCloseable {
        if (Build.VERSION.SDK_INT < 37) return AutoCloseable { }
        check(Build.HARDWARE in listOf("ranchu", "goldfish"))
        return startDisplayOwner(test, ui)
    }

    /** Start the ordinary HSD display owner for a standalone generated scene or an emulator test. */
    fun startDisplayOwner(test: Instrumentation, ui: UiAutomation): AutoCloseable {
        check(Build.VERSION.SDK_INT >= 37 && test.targetContext.packageName == "com.hsd.universal")
        val apk = test.targetContext.applicationInfo.sourceDir
        check(VdmHostBootstrapPolicy.validApk(apk) && apk.none(Char::isWhitespace))
        val token = UUID.randomUUID().toString().replace("-", "")
        VdmHostLink.beginActivation(token)
        try {
            fun launch() {
                val request = checkNotNull(VdmHostLink.requestLaunch())
                // UiAutomation uses argv rather than a shell; env supplies CLASSPATH explicitly.
                val command = "/system/bin/env CLASSPATH=$apk /system/bin/app_process / " +
                    "${VdmHostBootstrapPolicy.ENTRY} ${Process.myUid()} ${Process.myPid()} $token ${request.nonce}"
                val output = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                check(output.lineSequence().any { it.trim() == "HSD_VDM_READY" } && VdmHostLink.isReady) {
                    "Production VDM bootstrap did not acknowledge readiness"
                }
            }
            launch()
            return AutoCloseable {
                VdmHostLink.endActivation()
                val displays = test.targetContext.getSystemService(android.hardware.display.DisplayManager::class.java)
                val until = android.os.SystemClock.elapsedRealtime() + 3000
                fun retained() = displays.displays.any { it.name in com.hsd.universal.dex.VdmPoolProtocol.names }
                while (retained() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20)
                check(!retained()) { "Native displays remained after activation ended" }
            }
        } catch (error: Throwable) {
            VdmHostLink.endActivation()
            throw error
        }
    }
}
