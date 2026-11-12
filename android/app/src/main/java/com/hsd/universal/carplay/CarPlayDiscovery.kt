package com.hsd.universal.carplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.hsd.universal.ProjectionResources
import java.net.*
import kotlin.concurrent.thread

/**
 * Keep all Bonjour, control, timing and media traffic on the network obtained from the accessory.
 */
@Suppress("DEPRECATION")
internal class CarPlayDiscovery(
    context: Context,
    private val network: Network,
    private val id: String,
    private val life: ProjectionResources,
) {
    private val callbacks = context.mainExecutor
    @Volatile private var discovering = false
    private val nsd = context.getSystemService(NsdManager::class.java)!!
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)!!
    private val link =
        context.getSystemService(ConnectivityManager::class.java)!!.getLinkProperties(network)

    private data class Candidate(val host: InetAddress, val port: Int, val carPlay: Boolean)

    private val candidates = java.util.concurrent.ConcurrentHashMap<String, Candidate>()
    @Volatile private var invitation: InetAddress? = null

    fun endpoint(): Pair<InetAddress, Int>? {
        val endpoints = candidates.values.toList()
        val match =
            invitation?.let { sender -> endpoints.firstOrNull { it.host == sender } }
                ?: endpoints.filter { it.carPlay }.singleOrNull()
        return match?.let { it.host to it.port }
    }

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
                this.network = this@CarPlayDiscovery.network
                setAttribute("id", id)
                setAttribute("srcvers", "950.7.1")
            }
        val registration =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}

                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}

                override fun onServiceUnregistered(info: NsdServiceInfo) {}

                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        life.own { nsd.unregisterService(registration) }
        val discovery =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}

                override fun onDiscoveryStopped(type: String) {}

                override fun onStartDiscoveryFailed(type: String, code: Int) {}

                override fun onStopDiscoveryFailed(type: String, code: Int) {}

                override fun onServiceLost(info: NsdServiceInfo) {
                    candidates.remove(info.serviceName)
                }

                override fun onServiceFound(info: NsdServiceInfo) {
                    if (!discovering) return
                    nsd.resolveService(
                        info,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}

                            override fun onServiceResolved(i: NsdServiceInfo) {
                                if (!discovering) return
                                val host = i.host ?: return
                                if (i.port !in 1..65535) return
                                val high =
                                    i.attributes["features"]
                                        ?.decodeToString()
                                        ?.split(',')
                                        ?.getOrNull(1)
                                        ?.trim()
                                        ?.removePrefix("0x")
                                        ?.removePrefix("0X")
                                        ?.toLongOrNull(16) ?: 0L
                                candidates[i.serviceName] =
                                    Candidate(
                                        address(host.hostAddress.orEmpty()) ?: host,
                                        i.port,
                                        high and (1L shl (37 - 32)) != 0L,
                                    )
                            }
                        },
                    )
                }
            }
        // NSD delivers its final callback asynchronously after stopServiceDiscovery returns.
        discovering = true
        nsd.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, network, callbacks, discovery)
        life.own {
            discovering = false
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
