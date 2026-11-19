package com.hsd.universal.hicar

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

class HiCarProtocolTest {
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Malformed input accepted") } catch (e: Exception) { /* expected */ }
    }
    @Test fun videoDecryptsInIndependentReceiverAcrossFragmentsAndClockWrap() {
        val key = ByteArray(32) { it.toByte() }
        HiCarMedia(key).use { sender ->
            RefHiSightMedia(key).use { receiver ->
                for (size in listOf(32, 1400, 70000, 500000)) {
                    val frame = ByteArray(size) { (it * 71).toByte() }
                    val pts = 47_721_858_850L + size
                    var received = 0
                    sender.video(frame, pts) { packet ->
                        receiver.packet(packet) { kind, ts, bytes ->
                            assertEquals(96, kind)
                            assertEquals(HiCarMedia.ticks(pts), ts)
                            assertArrayEquals(frame, bytes)
                            received++
                        }
                    }
                    assertEquals(1, received)
                }
            }
        }
    }
    @Test fun audioDecryptsAsIndependentAacRtp() {
        val key = ByteArray(32) { it.toByte() }
        HiCarMedia(key).use { sender ->
            RefSessionCipher(key, 65536).use { cipher ->
                val receiver = RefAudioMedia()
                // AAC-LC, 48 kHz, stereo, one ADTS access unit. Payload is opaque to the packet parser.
                val adts = byteArrayOf(-1, -15, 0x4c, -128, 1, 63, -4, 42, 12)
                repeat(12) { n ->
                    val decoded = receiver.receive(RefAudioMedia.decrypt(sender.audio(adts, n * 21333L), cipher))
                    assertEquals(1, decoded.size)
                    assertEquals(48000, decoded[0].rate)
                    assertEquals(2, decoded[0].channels)
                    assertArrayEquals(byteArrayOf(42, 12), decoded[0].data)
                }
                assertEquals(0L, receiver.lost)
            }
        }
    }
    @Test fun touchAndKeysFromIndependentReceiver() {
        val key = ByteArray(32) { (it+4).toByte() }
        RefUiInput(key).use { remote ->
            HiCarMedia(key).use { phone ->
                for (action in 0..2) {
                    val packet = remote.encode(RefUiInput.Event.touch(action, 799, 479), 100)
                    val parsed = phone.input(packet.copyOfRange(4, packet.size), 800, 480)
                    assertEquals(HiCarMedia.Input(action, 799, 479), parsed)
                }
                val packet = remote.encode(RefUiInput.Event.key(4, false), 200)
                assertEquals(4, phone.input(packet.copyOfRange(4, packet.size), 800, 480).key)
                rejected { phone.input(packet.copyOfRange(4, 12), 800, 480) }
            }
        }
    }
    @Test fun networkCredentialsAreParsedWithoutDnsAndNeverPrinted() {
        val offer = RefNetworkResource.Offer("HiCar-test", "12:34:56:78:9A:BC", "testsecret", "192.168.49.1", "192.168.49.2", 5180, 43210)
        val parsed = HiCarNearby.Offer.parse(offer.credentials())
        assertEquals(43210, parsed.controlPort)
        assertEquals("192.168.49.1", parsed.owner.hostAddress)
        assertFalse(parsed.toString().contains("testsecret"))
        rejected { HiCarNearby.Offer.parse(offer.credentials().toString(Charsets.US_ASCII).replace("192.168.49.1", "example.com").toByteArray()) }
        rejected { HiCarNearby.Offer.parse(offer.credentials().toString(Charsets.US_ASCII).replace("43210", "99999").toByteArray()) }
    }
    @Test fun negotiatedGeometryComesFromReceiver() {
        assertEquals(HiCarSender.Video(1920, 720, 30, 180, 4_000_000), HiCarSender.parseVideo("codecs 1;fps 30;dpi 180;width 1920;height 720;bitrate 4000000"))
        rejected { HiCarSender.parseVideo("codecs 2;fps 30;width 800;height 480") }
        rejected { HiCarSender.parseVideo("codecs 1;fps 0;width 800;height 480") }
        rejected { HiCarSender.parseVideo("codecs 1;fps 30;width 90000;height 480") }
    }
    @Test fun incrementalMessagesRejectDuplicateAndOversizedHeaders() {
        val valid = HiCarControl.encode("GET_PARAMETER * DMSDP/1.0", mapOf("CSeq" to "1"), "hello".toByteArray())
        for (size in 0 until valid.size) assertNull(HiCarControl.parse(valid.copyOf(size), "DMSDP/1.0"))
        assertArrayEquals("hello".toByteArray(), HiCarControl.parse(valid, "DMSDP/1.0")!!.first.body)
        rejected { HiCarControl.parse("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\ncseq: 2\r\n\r\n".toByteArray(), "DMSDP/1.0") }
        rejected { HiCarControl.parse(ByteArray(8193) { 65 }, "DMSDP/1.0") }
        rejected { HiCarJson.read(ByteArrayInputStream("{\"a\":1,\"a\":2}".toByteArray())) }
        rejected { HiCarJson.read(ByteArrayInputStream("{\"a\":${"[".repeat(40)}0${"]".repeat(40)}}".toByteArray())) }
    }
    @Test fun nearbyPhoneCompletesIndependentNegotiation() {
        val incoming = ByteArrayOutputStream()
        val receiver = RefNearbyWire.Negotiator("receiver".toByteArray(), 7)
        incoming.write(receiver.respond(RefNearbyWire.Frame(0, 0, RefNearbyWire.tlv(1, byteArrayOf(2)) +
            RefNearbyWire.tlv(3, "phone".toByteArray()) + RefNearbyWire.tlv(5, byteArrayOf(0)))))
        val request = RefNearbyWire.uint(2)+RefNearbyWire.uint(19)+RefNearbyWire.uint(0)+RefNearbyWire.uint(0)
        incoming.write(receiver.respond(RefNearbyWire.Frame(2, 0, RefNearbyWire.tlv(3, request))))
        val out = ByteArrayOutputStream()
        val phone = HiCarNearby(ByteArrayInputStream(incoming.toByteArray()), out)
        phone.negotiate("phone")
        assertEquals("0000fe36-0000-1000-8000-00805f9b34fb", phone.authenticationService().toString())
        val frames = RefNearbyWire.Decoder().feed(out.toByteArray(), out.size())
        assertEquals(listOf(0, 2), frames.map { it.kind })
    }
    @Test fun corruptedCcmpNeverResynchronizesIntoAValidPacket() {
        val frame = RefNearbyWire.pack(0, RefNearbyWire.tlv(1, byteArrayOf(2)))
        frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
        val decoder = NearbyWire.Decoder()
        rejected { decoder.feed(frame, frame.size) }
        rejected { decoder.feed(RefNearbyWire.pack(0, byteArrayOf()), 7) }
    }

    @Test fun wireDiscoveryUsesOnlyVolkswagenNearbyServiceAndMatchingQuery() {
        fun response(instance: String): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                for (word in listOf(7, 0x8400, 1, 2, 0, 0)) out.writeShort(word)
                for (label in listOf(instance, "_nearby", "_tcp", "local")) {
                    out.writeByte(label.length)
                    out.writeBytes(label)
                }
                out.writeByte(0)
                out.writeShort(33)
                out.writeShort(1)
                fun header(type: Int, bytes: Int) {
                    out.writeShort(0xc00c) // service instance from the question
                    out.writeShort(type)
                    out.writeShort(1)
                    out.writeInt(120)
                    out.writeShort(bytes)
                }
                header(33, 12)
                out.writeShort(0)
                out.writeShort(0)
                out.writeShort(40123)
                out.writeByte(3)
                out.writeBytes("car")
                out.writeShort(0xc000 or (12 + 1 + instance.length + 1 + 7 + 1 + 4))
                val attribute = "ncm.ip=192.168.66.125"
                header(16, 1 + attribute.length)
                out.writeByte(attribute.length)
                out.writeBytes(attribute)
            }
            return buffer.toByteArray()
        }
        val valid = response("com.huawei.nearby.usb.nsd")
        assertEquals(40123 to "192.168.66.125", HiCarNcmDiscovery.answer(valid, setOf(7)))
        assertNull(HiCarNcmDiscovery.answer(valid, setOf(8)))
        assertEquals(null to null, HiCarNcmDiscovery.answer(response("other_nearby"), setOf(7)))
        rejected { HiCarNcmDiscovery.answer(valid.copyOf(valid.size - 1), setOf(7)) }
    }
}
