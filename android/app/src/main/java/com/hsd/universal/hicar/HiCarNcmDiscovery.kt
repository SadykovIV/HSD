package com.hsd.universal.hicar

import com.hsd.universal.ProjectionResources
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Discover the head unit on the cable's existing CDC-NCM link using Huawei's DNS-SD service. */
internal object HiCarNcmDiscovery {
    private val service = listOf("_nearby", "_tcp", "local")
    private val instance = listOf("com.huawei.nearby.usb.nsd") + service
    private val ncmName = Regex("(?:ncm|usb)\\d+")
    private const val MDNS_PORT = 5353

    data class Endpoint(val local: Inet4Address, val host: Inet4Address, val port: Int)

    private fun links() = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && ncmName.matches(it.name) }
            .flatMap { nic -> nic.interfaceAddresses.mapNotNull { addr ->
                (addr.address as? Inet4Address)?.takeIf { localAddress(it) && addr.networkPrefixLength in 1..31 }
                    ?.let { Triple(nic, it, addr.networkPrefixLength.toInt()) }
            } }

    private fun localAddress(ip: Inet4Address) = ip.isSiteLocalAddress || ip.isLinkLocalAddress

    private fun awaitLink(life: ProjectionResources, missingDetail: () -> String): Triple<NetworkInterface, Inet4Address, Int> {
        val cancelled = CountDownLatch(1)
        life.own(cancelled::countDown)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        do {
            val available = links()
            available.singleOrNull()?.let { return it }
            if (System.nanoTime() >= deadline) {
                if (available.size > 1) throw IOException("HiCar USB · несколько адресов USB-сети, интерфейс ГУ не определён")
                throw IOException(missingDetail())
            }
        } while (!cancelled.await(250, TimeUnit.MILLISECONDS))
        throw IOException("HiCar USB · поиск NCM остановлен")
    }

    fun find(life: ProjectionResources, missingDetail: () -> String, found: (String) -> Unit): Endpoint {
        val (nic, own, prefix) = awaitLink(life, missingDetail)
        found(nic.name)
        val group = InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))
        val socket = MulticastSocket(InetSocketAddress(own, 0))
        life.own(socket::close)
        return socket.use { endpoint ->
            endpoint.networkInterface = nic
            endpoint.timeToLive = 1
            endpoint.soTimeout = 400
            val queries = listOf(12 to service, 33 to instance, 16 to instance)
            val offers = mutableMapOf<Inet4Address, Pair<Int?, String?>>()
            val accepted = mutableSetOf<Int>()
            val deadline = System.nanoTime() + 12_000_000_000L
            var nextQuery = 0L
            val bytes = ByteArray(4096)
            while (!life.isClosed && System.nanoTime() < deadline) {
                if (System.nanoTime() >= nextQuery) {
                    accepted.clear()
                    for ((type, labels) in queries) {
                        var nonce: Int
                        do { nonce = Random.nextInt(1, 65536) } while (!accepted.add(nonce))
                        val data = query(nonce, labels, type)
                        endpoint.send(DatagramPacket(data, data.size, group, MDNS_PORT))
                    }
                    nextQuery = System.nanoTime() + 1_250_000_000L
                }
                val packet = DatagramPacket(bytes, bytes.size)
                try { endpoint.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val peer = packet.address as? Inet4Address ?: continue
                if (packet.port != MDNS_PORT || !sameLink(own, peer, prefix)) continue
                val response = runCatching { answer(bytes.copyOf(packet.length), accepted) }.getOrNull() ?: continue
                if (offers.size >= 8 && !offers.containsKey(peer)) continue
                val earlier = offers[peer]
                val port = response.first ?: earlier?.first
                val address = response.second ?: earlier?.second
                offers[peer] = port to address
                if (port != null && address == peer.hostAddress) return Endpoint(own, peer, port)
            }
            throw IOException("HiCar USB: ГУ не объявило Nearby на USB‑интерфейсе NCM")
        }
    }

    private fun sameLink(own: Inet4Address, other: Inet4Address, prefix: Int): Boolean {
        if (own == other || !localAddress(other)) return false
        val a = own.address
        val b = other.address
        return (0 until prefix).all { bit ->
            ((a[bit / 8].toInt() xor b[bit / 8].toInt()) and (128 ushr (bit % 8))) == 0
        }
    }

    private fun query(nonce: Int, labels: List<String>, type: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeShort(nonce)
            out.writeShort(0)
            out.writeShort(1)
            repeat(3) { out.writeShort(0) }
            for (label in labels) {
                out.writeByte(label.length)
                out.write(label.toByteArray(Charsets.US_ASCII))
            }
            out.writeByte(0)
            out.writeShort(type)
            out.writeShort(1)
        }
        return buffer.toByteArray()
    }

    // DNS compression pointers may follow earlier labels. Limit jumps and expanded size.
    private class Message(private val bytes: ByteArray) {
        var offset = 0
        fun byte() = bytes[offset++].toInt() and 255
        fun ushort() = (byte() shl 8) or byte()
        fun name(): List<String> {
            val labels = mutableListOf<String>()
            val origin = offset
            var resume = -1
            var expanded = 0
            repeat(64) {
                val length = byte()
                if (length == 0) {
                    if (resume >= 0) offset = resume
                    return labels
                }
                if (length and 192 == 192) {
                    val pointer = ((length and 63) shl 8) or byte()
                    if (resume < 0) resume = offset
                    require(pointer in 12 until origin)
                    offset = pointer
                } else {
                    require(length in 1..63)
                    expanded += length + 1
                    require(expanded < 255 && offset + length <= bytes.size)
                    labels.add(bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII).lowercase(Locale.ROOT))
                    offset += length
                }
            }
            error("DNS name limit")
        }
        fun text(length: Int): String {
            require(offset + length <= bytes.size)
            return bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII).also { offset += length }
        }
    }

    internal fun answer(bytes: ByteArray, nonces: Set<Int>): Pair<Int?, String?>? {
        if (bytes.size !in 12..4096) return null
        val message = Message(bytes)
        val nonce = message.ushort()
        if (nonce !in nonces || message.ushort() and 0xf80f != 0x8000) return null
        val questions = message.ushort()
        val records = message.ushort() + message.ushort() + message.ushort()
        if (questions !in 0..4 || records !in 1..128) return null
        repeat(questions) { message.name(); message.ushort(); message.ushort() }
        var port: Int? = null
        var address: String? = null
        repeat(records) {
            val name = message.name()
            val type = message.ushort()
            val clazz = message.ushort() and 0x7fff
            val ttl = (message.ushort().toLong() shl 16) or message.ushort().toLong()
            val length = message.ushort()
            val end = message.offset + length
            require(end <= bytes.size)
            if (name == instance && clazz == 1 && ttl > 0) when (type) {
                33 -> {
                    require(length >= 7)
                    message.ushort() // Priority
                    message.ushort() // Weight
                    val value = message.ushort()
                    require(value != 0 && message.name().isNotEmpty() && message.offset == end)
                    port = value
                }
                16 -> while (message.offset < end) {
                    val count = message.byte()
                    require(message.offset + count <= end)
                    val row = message.text(count)
                    if (row.startsWith("ncm.ip=")) address = row.removePrefix("ncm.ip=")
                }
                else -> Unit
            }
            message.offset = end
        }
        return port to address
    }
}
