package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import org.junit.Assert.*
import org.junit.Test

class CarPlayControlVersionTest {
    @Test fun aReliableLinkDoesNotReportALocalWriteAsTheHeadUnitsAcknowledgement() {
        val receiver = Iap2LinkEngine(maxRetransmissions = 0, controlVersion = 1)
        val phone = Iap2LinkEngine()
        receiver.start(0)
        phone.startResponder(0)
        phone.feed(receiver.takeOutput(), 1)
        receiver.feed(phone.takeOutput(), 2)
        phone.feed(receiver.takeOutput(), 3)
        val payload = byteArrayOf(0x40, 0x40, 0, 6, 0xaa.toByte(), 0)
        assertTrue(phone.sendControl(payload, 4))
        val certificateRequest = phone.takeOutput()
        receiver.feed(certificateRequest, 5)
        phone.feed(receiver.takeOutput(), 6)
        val ackBefore = phone.progressSummary().substringAfter("peerAck=").substringBefore(' ')
        assertTrue(phone.sendControl(payload, 7))
        val waiting = phone.progressSummary()
        assertTrue(waiting.contains("peerMaxRetries=0"))
        assertEquals(ackBefore, waiting.substringAfter("peerAck=").substringBefore(' '))
        assertNotEquals(ackBefore, waiting.substringAfter("windowAck=").substringBefore(' '))
        receiver.feed(phone.takeOutput(), 8)
        phone.feed(receiver.takeOutput(), 9)
        assertEquals(waiting.substringAfter("tx=").substringBefore(' '),
            phone.progressSummary().substringAfter("peerAck=").substringBefore(' '))
    }

    @Test fun bothPeersAgreeOnControlVersionBeforeSendingApplicationMessages() {
        for (headUnitVersion in 1..2) for (phoneInitiates in listOf(false, true)) {
            val phone = Iap2LinkEngine()
            val headUnit = Iap2LinkEngine(controlVersion = headUnitVersion)
            if (phoneInitiates) {
                phone.start(0)
                headUnit.startResponder(0)
            } else {
                headUnit.start(0)
                phone.startResponder(0)
            }
            for (now in 1L..2000L step 10) {
                headUnit.feed(phone.takeOutput(), now)
                phone.feed(headUnit.takeOutput(), now)
                phone.advanceTime(now)
                headUnit.advanceTime(now)
            }
            assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
            assertEquals(Iap2LinkEngine.State.NORMAL, headUnit.state)
            assertEquals(headUnitVersion, phone.negotiatedControlVersion)
            assertEquals(headUnitVersion, headUnit.negotiatedControlVersion)
            val control = byteArrayOf(0x40, 0x40, 0, 6, 0xaa.toByte(), 0)
            assertTrue(phone.sendControl(control, 2001))
            val good = phone.takeOutput()
            val damaged = good.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            headUnit.feed(damaged + good, 2002)
            assertArrayEquals(control, (headUnit.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
            assertTrue(headUnit.progressSummary().contains("badPayloads=1"))
        }
    }
}
