package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.airplay.ControlCipher
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CarPlayDataStreamTest {
    @Test
    fun handlesHeadersAndPayloadsAcrossEncryptedRecords() {
        val inputKey = ByteArray(32) { it.toByte() }
        val outputKey = ByteArray(32) { (it + 1).toByte() }
        val peer = ControlCipher(outputKey, inputKey)
        fun packet(body: ByteArray) =
            ByteBuffer.allocate(32 + body.size)
                .apply {
                    putInt(capacity())
                    position(16)
                    put("comm".encodeToByteArray())
                    position(32)
                    put(body)
                }
                .array()
        val first = ByteArray(3400) { (it % 127).toByte() }
        val second = byteArrayOf(4, 6, 8)
        val combined = packet(first) + packet(second)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(listener.inetAddress, listener.localPort).use { sender ->
                listener.accept().use { receiver ->
                    val wire = CarPlayDataStream(receiver, inputKey, outputKey)
                    val out = sender.getOutputStream()
                    out.write(peer.encrypt(combined.copyOfRange(0, 17)))
                    out.write(peer.encrypt(combined.copyOfRange(17, combined.size)))
                    sender.shutdownOutput()
                    val got = mutableListOf<ByteArray>()
                    wire.read(got::add)
                    assertEquals(2, got.size)
                    assertArrayEquals(first, got[0])
                    assertArrayEquals(second, got[1])
                    wire.send(second)
                    receiver.shutdownOutput()
                    assertArrayEquals(
                        packet(second),
                        peer.decrypt(sender.getInputStream().readBytes()).data,
                    )
                }
            }
        }
    }
}
