package com.hsd.universal

import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import net.prototux.aare.aap.SocketTransport

/** AAP head-unit server. Only the head unit selected on the phone reaches TLS discovery. */
internal class HeadUnitListener(private val allowed: InetAddress, port: Int = 5277) :
    AutoCloseable {
    private val server =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(if (allowed.isLoopbackAddress) allowed else null, port))
        }

    fun accept(): SocketTransport {
        while (true) {
            val socket = server.accept()
            if (socket.inetAddress == allowed) {
                try {
                    return SocketTransport(socket, "Android Auto LAN · ${allowed.hostAddress}")
                } catch (error: Throwable) {
                    socket.close()
                    throw error
                }
            }
            socket.close()
        }
    }

    override fun close() = server.close()
}

/** Use the same local interfaces in the connection form and when opening the receiver socket. */
private data class LanAddress(val address: InetAddress, val network: android.net.Network?)

private fun lanAddresses(connectivity: ConnectivityManager, peer: InetAddress?): List<LanAddress> {
    if (peer?.isLoopbackAddress == true) return listOf(LanAddress(peer, null))
    val managed = mutableSetOf<String>()
    val sources = mutableListOf<LanAddress>()
    for (network in connectivity.allNetworks) {
        val links = connectivity.getLinkProperties(network) ?: continue
        links.interfaceName?.let(managed::add)
        val caps = connectivity.getNetworkCapabilities(network) ?: continue
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
        if (peer != null && links.routes.none { !it.isDefaultRoute && it.matches(peer) }) continue
        for (link in links.linkAddresses) {
            if (peer == null || link.address.address.size == peer.address.size) sources += LanAddress(link.address, network)
        }
    }
    // A tethering interface can exist without a ConnectivityManager Network.
    for (nic in NetworkInterface.getNetworkInterfaces().toList()) {
        if (!nic.isUp || nic.isLoopback || nic.name in managed) continue
        for (link in nic.interfaceAddresses) {
            val length = link.networkPrefixLength.toInt()
            if (length > 0 && (peer == null || IpPrefix(link.address, length).contains(peer)))
                sources += LanAddress(link.address, null)
        }
    }
    return sources
}

internal fun receiverLocalAddresses(connectivity: ConnectivityManager, peer: InetAddress?): List<InetAddress> {
    val sources = lanAddresses(connectivity, peer).map { it.address }.distinct()
    val ipv4 = sources.filterIsInstance<java.net.Inet4Address>()
    return ipv4.ifEmpty { sources }
}

internal fun localSocketFor(connectivity: ConnectivityManager, peer: InetAddress): Socket {
    if (peer.isLoopbackAddress) return Socket()
    val source = lanAddresses(connectivity, peer).firstOrNull()
        ?: throw NoRouteToHostException("Магнитола ${peer.hostAddress} недоступна в локальной сети")
    source.network?.let { return it.socketFactory.createSocket() }
    return Socket().apply {
        try { bind(InetSocketAddress(source.address, 0)) }
        catch (error: Exception) { close(); throw error }
    }
}
