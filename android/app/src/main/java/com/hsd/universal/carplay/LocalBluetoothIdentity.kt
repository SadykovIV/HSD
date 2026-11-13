package com.hsd.universal.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.provider.Settings
import android.util.AtomicFile
import androidx.annotation.Keep
import com.hsd.universal.AppLog
import com.hsd.universal.BuildConfig
import com.hsd.universal.ProjectionResources
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.dex.DesktopAccessMode
import com.hsd.universal.dex.DexAccess
import com.hsd.universal.dex.DexPrefs
import com.hsd.universal.dex.DexPrivilegeAccess
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import rikka.shizuku.Shizuku

internal object LocalBluetoothIdentity {
    private const val SETTING = "bluetooth_address"
    internal const val COMMAND = "/system/bin/settings --user current get secure bluetooth_address"
    fun normalize(value: String?): String? = BluetoothIdentity.normalize(value)

    @SuppressLint("HardwareIds", "MissingPermission")
    fun platform(context: Context): String? =
        normalize(runCatching { Settings.Secure.getString(context.contentResolver, SETTING) }.getOrNull())
            ?: normalize(runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.address }.getOrNull())

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, BluetoothIdentity.BASENAME))

    /** Runs on the connection worker. Only the access method selected by the user may be consulted. */
    fun resolve(context: Context, life: ProjectionResources): String {
        val prefs = DexPrefs(context)
        val system = platform(context)
        val detected = system ?: try {
            when (prefs.accessMode) {
                DesktopAccessMode.COMPUTER -> null // Both computer and on-phone activation populate the private file.
                DesktopAccessMode.SHIZUKU -> if (DexPrivilegeAccess.isReady(context)) shizuku(context, life) else null
                DesktopAccessMode.LEGACY_ADB -> if (DexAccess.hasVerifiedEndpoint(context)) {
                    DexAccess.connect(context, prefs.connectPort).use { adb ->
                        life.own(adb::close)
                        val result = adb.shell(COMMAND)
                        if (result.exitCode == 0) normalize(result.output) else null
                    }
                } else null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            AppLog.i("CarPlay local Bluetooth identity · ${prefs.accessMode} · ${e.javaClass.simpleName}", false)
            null
        }

        val route = ProjectionRoute.prefs(context)
        val oldKey = "localBluetoothAddress"
        val cached = synchronized(this) {
            runCatching { normalize(file(context).readFully().toString(Charsets.US_ASCII)) }.getOrNull()
        }
        val legacy = normalize(route.getString(oldKey, null))
        val address = detected ?: cached ?: legacy
            ?: throw IllegalStateException("Не удалось определить Bluetooth-адрес телефона. Включите Bluetooth и проверьте выбранный способ доступа в HSD → Настройки → Активация.")
        if (address != cached) {
            synchronized(this) {
                val dest = file(context)
                runCatching {
                    val out = dest.startWrite()
                    try { out.write(address.toByteArray(Charsets.US_ASCII)); dest.finishWrite(out) }
                    catch (e: Exception) { dest.failWrite(out); throw e }
                }.onSuccess { if (route.contains(oldKey)) route.edit().remove(oldKey).apply() }
            }
        } else if (route.contains(oldKey)) route.edit().remove(oldKey).apply()
        val source = when {
            system != null -> "Android"
            detected != null -> prefs.accessMode.name
            cached != null -> "device cache"
            else -> "previous setting"
        }
        AppLog.i("CarPlay local Bluetooth identity · $source", false)
        return address
    }

    private fun shizuku(context: Context, life: ProjectionResources): String? {
        val service = CompletableFuture<ILocalBluetoothAddress>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service.complete(ILocalBluetoothAddress.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                service.completeExceptionally(IOException("Shizuku disconnected"))
            }
        }
        val args = Shizuku.UserServiceArgs(ComponentName(context, LocalBluetoothAddressService::class.java))
            .processNameSuffix("carplay-address").tag("carplay-address-${UUID.randomUUID()}")
            .daemon(false).version(BuildConfig.VERSION_CODE)
        life.own { service.cancel(false) }
        try {
            Shizuku.bindUserService(args, connection)
            return normalize(service.get(30, TimeUnit.SECONDS).readAddress())
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }
}

@Keep
class LocalBluetoothAddressService @Keep constructor(private val context: Context) : ILocalBluetoothAddress.Stub() {
    override fun readAddress(): String? {
        LocalBluetoothIdentity.platform(context)?.let { return it }
        val process = ProcessBuilder("/system/bin/settings", "--user", "current", "get", "secure", "bluetooth_address").start()
        return try {
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0)
                process.inputStream.bufferedReader().use { LocalBluetoothIdentity.normalize(it.readLine()) }
            else null
        } finally { process.destroyForcibly() }
    }
    override fun destroy() = exitProcess(0)
}
