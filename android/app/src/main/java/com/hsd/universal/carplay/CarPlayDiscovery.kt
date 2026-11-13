package com.hsd.universal.carplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import com.hsd.universal.ProjectionResources
import java.net.*
import kotlin.concurrent.thread

internal data class CarPlayService(val hosts: List<InetAddress>, val port: Int, val carPlay: Boolean) {
    companion object {
        fun select(services: Collection<CarPlayService>, invitation: InetAddress?): Pair<InetAddress, Int>? {
            val invited = if (invitation == null) emptyList() else services.filter { invitation in it.hosts }
            val possible = invited.ifEmpty { services.toList() }
            val advertised = possible.filter { it.carPlay }
            val matches = advertised.ifEmpty { invited }
            return matches.map { service ->
                (invitation?.takeIf { it in service.hosts } ?: service.hosts.first()) to service.port
            }.distinct().singleOrNull()
        }
    }
}

/**
 * Keep all Bonjour, control, timing and media traffic on the network obtained from the accessory.
 */
@Suppress("DEPRECATION")
internal class CarPlayDiscovery(
    context: Context,
    private val network: Network,
    private val id: String,
    private val life: ProjectionResources,
    private val trace: (String) -> Unit,
) {
    private val callbacks = context.mainExecutor
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var discovering = false
    private val nsd = context.getSystemService(NsdManager::class.java)!!
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)!!
    private val link =
        context.getSystemService(ConnectivityManager::class.java)!!.getLinkProperties(network)

    private val candidates = java.util.concurrent.ConcurrentHashMap<String, CarPlayService>()
    @Volatile private var invitation: InetAddress? = null

    // The legacy NSD implementation accepts one resolve operation at a time. All
    // changes to the queue, including service-lost events, run on the main looper.
    private class Lookup(val service: NsdServiceInfo, var busyRetries: Int = 0)
    private val announced = mutableMapOf<String, Lookup>()
    private val pending = ArrayDeque<Lookup>()
    private var active: Lookup? = null

    private fun found(info: NsdServiceInfo) {
        if (!discovering) return
        val next = Lookup(info)
        announced.put(info.serviceName, next)?.let { pending.remove(it) }
        pending.addLast(next)
        resolveNext()
    }

    private fun lost(info: NsdServiceInfo) {
        announced.remove(info.serviceName)?.let { pending.remove(it) }
        candidates.remove(info.serviceName)
    }

    private fun resolveNext() {
        if (!discovering || active != null) return
        val next = pending.removeFirstOrNull() ?: return
        active = next
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post { resolved(next, info, null) }
            }
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                main.post { resolved(next, null, error) }
            }
        }
        try {
            nsd.resolveService(next.service, listener)
        } catch (error: RuntimeException) {
            trace("Bonjour resolve · ${error.javaClass.simpleName}")
            main.post { resolved(next, null, NsdManager.FAILURE_INTERNAL_ERROR) }
        }
    }

    private fun resolved(lookup: Lookup, result: NsdServiceInfo?, error: Int?) {
        if (active !== lookup) return
        active = null
        val name = lookup.service.serviceName
        if (discovering && announced[name] === lookup) {
            if (result != null) accept(name, result)
            else if (error == NsdManager.FAILURE_ALREADY_ACTIVE && lookup.busyRetries < 3) {
                lookup.busyRetries++
                main.postDelayed({
                    if (discovering && announced[name] === lookup) {
                        pending.addLast(lookup)
                        resolveNext()
                    }
                }, 250L shl (lookup.busyRetries - 1))
            } else trace("Bonjour resolve failed · code=$error")
        }
        resolveNext()
    }

    private fun accept(name: String, info: NsdServiceInfo) {
        val addresses = if (Build.VERSION.SDK_INT >= 34 ||
            Build.VERSION.SDK_INT >= 33 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7)
            info.hostAddresses else listOfNotNull(info.host)
        // Legacy discovery spans interfaces. Keep only a directly connected
        // route on the network joined during the accessory handshake.
        val hosts = addresses.filter { host ->
            !host.isAnyLocalAddress && !host.isLoopbackAddress && !host.isMulticastAddress &&
                link?.routes?.any { !it.isDefaultRoute && it.matches(host) } == true
        }.mapNotNull { address(it.hostAddress.orEmpty()) }.distinct()
        if (hosts.isEmpty() || info.port !in 1..65535) {
            candidates.remove(name)
            return
        }
        val high = info.attributes["features"]?.decodeToString()?.split(',')?.getOrNull(1)
            ?.trim()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16) ?: 0L
        candidates[name] = CarPlayService(hosts, info.port, high and (1L shl 5) != 0L)
    }

    fun endpoint(): Pair<InetAddress, Int>? = CarPlayService.select(candidates.values.toList(), invitation)

    fun address(value: String): InetAddress? = runCatching {
        val raw = InetAddresses.parseNumericAddress(value.substringBefore('%'))
        if (raw is Inet6Address && raw.isLinkLocalAddress)
            Inet6Address.getByAddress(
                null,
                raw.address,
                NetworkInterface.getByName(link?.interfaceName),
            )
        else raw
    }
        .getOrNull()

    fun start() {
        val multicast = wifi.createMulticastLock("HSD CarPlay")
        multicast.acquire()
        life.own(multicast::release)
        val local =
            link
                ?.linkAddresses
                .orEmpty()
                .map { it.address }
                .filter {
                    !it.isLoopbackAddress && !it.isAnyLocalAddress
                }
                .distinct()
                .sortedBy { it !is Inet4Address }
        require(local.isNotEmpty()) { "CarPlay Wi-Fi не имеет адреса" }
        val listeners = mutableListOf<ServerSocket>()
        for (host in local) {
            val scoped = address(host.hostAddress.orEmpty()) ?: continue
            val port = listeners.firstOrNull()?.localPort ?: 0
            val socket = runCatching { ServerSocket(port, 4, scoped) }.getOrNull() ?: continue
            life.own(socket::close)
            listeners.add(socket)
        }
        val invitationPort =
            listeners.firstOrNull()?.localPort
                ?: error("Не удалось открыть порт приглашений CarPlay")
        val info =
            NsdServiceInfo().apply {
                serviceName = "HSD"
                serviceType = "_carplay-ctrl._tcp"
                port = invitationPort
                if (Build.VERSION.SDK_INT >= 33 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 3)
                    this.network = this@CarPlayDiscovery.network
                setAttribute("id", id)
                setAttribute("srcvers", "950.7.1")
            }
        val registration =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}

                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { trace("Bonjour registration failed · code=$code") }

                override fun onServiceUnregistered(info: NsdServiceInfo) {}

                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        life.own { nsd.unregisterService(registration) }
        val discovery =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}

                override fun onDiscoveryStopped(type: String) {}

                override fun onStartDiscoveryFailed(type: String, code: Int) { trace("Bonjour discovery failed · code=$code") }

                override fun onStopDiscoveryFailed(type: String, code: Int) {}

                override fun onServiceLost(info: NsdServiceInfo) { main.post { lost(info) } }

                override fun onServiceFound(info: NsdServiceInfo) { main.post { found(info) } }
            }
        // NSD delivers its final callback asynchronously after stopServiceDiscovery returns.
        discovering = true
        if (Build.VERSION.SDK_INT >= 33 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 3)
            nsd.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, network, callbacks, discovery)
        else nsd.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)
        life.own {
            discovering = false
            main.post { pending.clear(); announced.clear(); candidates.clear() }
            nsd.stopServiceDiscovery(discovery)
        }
        for (server in listeners) thread(name = "CarPlay invitations", isDaemon = true) {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { s ->
                        s.soTimeout = 2000
                        val req = AirPlayWire.head(s.getInputStream()) ?: return@use
                        val ok =
                            req.first.startsWith("GET ") &&
                                req.first.split(' ').getOrNull(1)?.substringBefore('?') ==
                                    "/ctrl-int/1/connect"
                        if (ok) invitation = s.inetAddress
                        val status = if (ok) "200 OK" else "404 Not Found"
                        s.getOutputStream()
                            .write(
                                "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray()
                            )
                    }
                }
            }
        }
    }
}
