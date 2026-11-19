package com.hsd.universal.hicar

import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID

/** Phone initiator for the Nearby CCMP negotiation; formats cross-checked with VW libnearby. */
internal class HiCarNearby(private val input: InputStream, private val output: OutputStream) {
    private val decoder = NearbyWire.Decoder()
    private val pending = ArrayDeque<NearbyWire.Frame>()
    private val scratch = ByteArray(4096)
    fun send(kind: Int, tag: Int, data: ByteArray) { output.write(NearbyWire.pack(kind, NearbyWire.tlv(tag, data))); output.flush() }
    private fun frame(): NearbyWire.Frame {
        while (pending.isEmpty()) {
            val n = input.read(scratch)
            check(n > 0) { "HiCar: Nearby connection closed" }
            pending.addAll(decoder.feed(scratch, n))
        }
        return pending.removeFirst().also { check(it.flags and 1 == 0) { "HiCar: unsupported Nearby encryption" } }
    }
    private fun expect(kind: Int, tag: Int): ByteArray {
        repeat(32) {
            val frame = frame()
            val fields = NearbyWire.fields(frame.payload)
            fields.firstOrNull { frame.kind == kind && it.tag == tag }?.let { return it.value }
            check(frame.kind == kind || frame.kind == 0) { "HiCar: unexpected Nearby stage (${frame.kind})" }
        }
        error("HiCar: Nearby response limit")
    }
    fun negotiate(identity: String) {
        output.write(NearbyWire.pack(0, NearbyWire.tlv(1, byteArrayOf(2)) +
            NearbyWire.tlv(3, identity.toByteArray(Charsets.US_ASCII)) + NearbyWire.tlv(5, byteArrayOf(0))))
        output.flush()
        var seen = 0
        repeat(16) {
            val f = frame(); check(f.kind == 0)
            for (field in NearbyWire.fields(f.payload)) when (field.tag) {
                2 -> { check(field.value.size == 1 && field.value[0] >= 2); seen = seen or 1 }
                4 -> { check(field.value.size in 1..256); seen = seen or 2 }
                6 -> { check(field.value.contentEquals(byteArrayOf(0))); seen = seen or 4 }
            }
            if (seen == 7) return
        }
        error("HiCar: Nearby negotiation incomplete")
    }
    fun authenticationService(): UUID {
        val request = socketRequest(2, 19, 0)
        send(2, 3, request)
        val reply = NearbyWire.Cursor(expect(2, 4), 0)
        check(reply.uint() == 0) { "HiCar: authentication service refused" }
        check(reply.uint() in 1..30)
        check(reply.take(request.size).contentEquals(request))
        check(reply.uint() == 16)
        val bytes = ByteBuffer.wrap(reply.take(16))
        return UUID(bytes.long, bytes.long).also { check(reply.p == reply.b.size) }
    }
    fun networkOffer(clientAddress: () -> String): Offer {
        val caps = uints(2, 1, 4, 1, 5, 511)
        send(4, 1, NearbyWire.uint(3) + NearbyWire.tlv(3, caps))
        val response = NearbyWire.Cursor(expect(4, 2), 0)
        check(response.uint() == 3 && response.uint() == 1) { "HiCar: ГУ не приняло режим Wi-Fi" }
        val fields = NearbyWire.fields(response.b.copyOfRange(response.p, response.b.size))
        val role = fields.single { it.tag == 3 }.value
        check(role.size > 1 && role[0] == 1.toByte()) { "HiCar: ГУ не предложило точку доступа" }
        val frequency = NearbyWire.Cursor(role, 1).uint()
        check(frequency in frequencies) { "HiCar: неподдерживаемая частота Wi-Fi" }
        send(4, 7, uints(3, 1) + NearbyWire.tlv(3, byteArrayOf(2) + NearbyWire.uint(frequency)))
        expect(33, 1)
        val clientMac = clientAddress()
        check(validMac(clientMac)) { "HiCar: требуется настоящий адрес Wi-Fi Direct" }
        send(33, 2, clientMac.toByteArray(Charsets.US_ASCII))
        return Offer.parse(expect(33, 3))
    }
    fun dataPort(): Int = tcpPort(channel = 3, business = 20)

    fun usbAuthenticationPort(): Int = tcpPort(channel = 6, business = 19)
    fun usbDataPort(): Int = tcpPort(channel = 6, business = 20)

    private fun tcpPort(channel: Int, business: Int): Int {
        val request = socketRequest(channel, business, 1)
        send(4, 3, request)
        val response = NearbyWire.Cursor(expect(4, 4), 0)
        check(response.uint() == 0) { "HiCar: ГУ отклонило запрос канала $business" }
        val port = response.uint()
        check(port in 1..65535 && response.take(request.size).contentEquals(request) && response.p == response.b.size)
        return port
    }
    class Offer(val ssid: String, val bssid: String?, val password: String, val frequency: Int,
                val owner: Inet4Address, val client: Inet4Address, val controlPort: Int) {
        override fun toString() = "HiCar Wi-Fi offer" // Never expose network credentials in diagnostics.
        companion object {
            fun parse(bytes: ByteArray): Offer {
                check(bytes.size in 1..2048 && bytes.all { it.toInt() in 1..127 }) { "HiCar: invalid network offer" }
                val fields = bytes.toString(Charsets.US_ASCII).split("##")
                check(fields.size == 6 && fields[2] == "version:3" && fields[5].startsWith("hicarApIp:"))
                val wifi = fields[1].split('\n')
                check(wifi.size == 5 && wifi[0].toByteArray().size in 1..32 && wifi[2].length in 8..63)
                check(wifi[1].isEmpty() || validMac(wifi[1]))
                val frequency = wifi[3].toInt(); check(frequency in frequencies)
                val port = fields[3].toInt(); check(port in 1..65535)
                val owner = ipv4(fields[5].substringAfter(':'))
                val client = ipv4(fields[0])
                check(owner != client && owner.isSiteLocalAddress && client.isSiteLocalAddress)
                return Offer(wifi[0], wifi[1].ifEmpty { null }, wifi[2], frequency, owner, client, port)
            }
            private fun ipv4(text: String): Inet4Address {
                val parts = text.split('.')
                check(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 })
                return InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray()) as Inet4Address
            }
        }
    }
    companion object {
        val controlUuid: UUID = UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb")
        private val frequencies = setOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825)
        fun validMac(mac: String?) = mac != null && mac.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) &&
            mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00" && (mac.substring(0,2).toInt(16) and 1) == 0
        private fun uints(vararg values: Int) = values.fold(byteArrayOf()) { b, n -> b + NearbyWire.uint(n) }
        private fun socketRequest(channel: Int, business: Int, protocol: Int) =
            uints(channel, business, 0, protocol)
    }
}
