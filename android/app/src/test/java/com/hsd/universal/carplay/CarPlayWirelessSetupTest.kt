package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.asString
import com.hsd.universal.ProjectionResources
import java.nio.ByteBuffer
import java.time.ZonedDateTime
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CarPlayWirelessSetupTest {
    private val local = "02:11:22:33:44:55"
    private val uuid = UUID.randomUUID()
    private val device = CarPlayDeviceInformation(uuid)
    private fun messages(sends: List<Int>, receives: List<Int>) = buildList {
        CarPlayWirelessSetup(sends, receives).announce(local, device, 0) { id, body -> add(Iap2Csm.Frame(id, body)) }
    }

    @Test fun identifierIsAvailableBeforeTheHeadUnitIsAskedForItsWifiSession() {
        val frames = messages(listOf(0x4301), listOf(0x4e0e, 0x4e0d))
        assertEquals(listOf(0x4e0e, 0x4e0d, 0x4300), frames.map { it.messageId })
        assertEquals(local, Iap2Csm.parseParams(frames.first().body).single { it.id == 0 }.asString())
    }

    @Test fun advertisedWifiReplyIsEnoughEvenIfTheRequestIsMissingFromTheList() {
        assertEquals(listOf(0x4e0a, 0x5702), messages(listOf(0x5703), listOf(0x4e0a)).map { it.messageId })
    }

    @Test fun incompleteDeclarationsLeaveRoomForAnOlderReceiverToAnswer() {
        val setup = CarPlayWirelessSetup(listOf(0x5000), listOf(0x5001))
        val seen = mutableListOf<Int>()
        fun send(id: Int, body: ByteArray) { seen.add(id) }
        setup.announce(local, device, 100, ::send)
        setup.whileWaitingForWifi(local, 1599, ::send)
        assertEquals(listOf(0x5702), seen)
        setup.whileWaitingForWifi(local, 1600, ::send)
        setup.whileWaitingForWifi(local, 20_000, ::send)
        assertEquals(listOf(0x5702, 0x4300), seen)
        assertEquals(listOf(0x4e0e, 0x4e0d, 0x5702), messages(emptyList(), emptyList()).map { it.messageId })
    }

    @Test fun allAdvertisedDeviceUpdatesPrecedeWifiRequests() {
        val receives = listOf(0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x4e0d, 0x4e0e, 0x5702)
        val frames = messages(emptyList(), receives)
        assertEquals(receives.toSet(), frames.map { it.messageId }.toSet())
        assertEquals(0x5702, frames.last().messageId)
        assertEquals(uuid, UUID.fromString(Iap2Csm.parseParams(frames.first { it.messageId == 0x4e0c }.body).single().asString()))
        val time = ZonedDateTime.parse("2026-07-01T12:00:00-02:30[America/St_Johns]")
        val update = mutableMapOf<Int, ByteArray>()
        device.publish(receives, "ru", time) { id, body -> update[id] = body }
        assertEquals("ru", Iap2Csm.parseParams(update.getValue(0x4e0a)).single().asString())
        val fields = Iap2Csm.parseParams(update.getValue(0x4e0b)).associate { it.id to it.value }
        assertEquals(time.toEpochSecond(), ByteBuffer.wrap(fields.getValue(0)).long)
        assertEquals(time.offset.totalSeconds / 60, ByteBuffer.wrap(fields.getValue(1)).short.toInt())
        assertEquals(60, fields.getValue(2).single().toInt())
    }

    @Test fun deviceUuidIsSharedByConcurrentLinksAndExpiresAfterDisconnect() {
        var now = 1_000L
        val ids = CarPlayDeviceUuids { now }
        val first = ProjectionResources()
        val other = ProjectionResources()
        val id = ids.acquire(first)
        assertEquals(id, ids.acquire(other))
        first.close()
        now += 90_000
        ProjectionResources().use { assertEquals(id, ids.acquire(it)) }
        other.close()
        now += 59_999
        ProjectionResources().use { assertEquals(id, ids.acquire(it)) }
        now += 60_000
        ProjectionResources().use { assertNotEquals(id, ids.acquire(it)) }
    }
}
