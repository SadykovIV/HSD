package com.hsd.universal

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import com.hsd.universal.carplay.CarPlayDiscovery
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real system DNS-SD with ordinary AirPlay advertisements alongside a CarPlay receiver. */
internal fun checkCarPlayDiscovery(context: Context) {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    val network = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: error("Connect the emulator to the head unit test Wi-Fi first")
    val nsd = context.getSystemService(NsdManager::class.java)
    val name = "HSD-QA-" + UUID.randomUUID().toString().take(8)
    ProjectionResources().use { life ->
        fun register(index: Int, car: Boolean): CompletableFuture<Unit> {
            val started = CompletableFuture<Unit>()
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) { started.complete(Unit) }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                    started.completeExceptionally(IllegalStateException("DNS-SD registration $code"))
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
            }
            val info = NsdServiceInfo().apply {
                serviceName = "$name-$index"
                serviceType = "_airplay._tcp"
                port = 24100 + index
                setAttribute("features", if (car) "0x0,0x20" else "0x0,0x0")
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            life.own { nsd.unregisterService(listener) }
            return started
        }
        val advertisements = (0..2).map { register(it, it == 2) }
        CompletableFuture.allOf(*advertisements.toTypedArray()).get(12, TimeUnit.SECONDS)
        val browser = CarPlayDiscovery(context, network, name, life) { android.util.Log.i("HSD-QA", it) }
        browser.start()
        val deadline = SystemClock.elapsedRealtime() + 16_000
        while (browser.endpoint()?.second != 24102 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(browser.endpoint()?.second == 24102) { "CarPlay endpoint missing among multiple AirPlay services" }
    }
}
