package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.asString
import org.junit.Assert.*
import org.junit.Test

class CarPlayWirelessSetupTest {
    private val local = "02:11:22:33:44:55"
    private fun messages(sends: List<Int>, receives: List<Int>) = buildList {
        CarPlayWirelessSetup(sends, receives).announce(local) { id, body -> add(Iap2Csm.Frame(id, body)) }
    }

    @Test fun identifierIsAvailableBeforeTheHeadUnitIsAskedForItsWifiSession() {
        val frames = messages(listOf(0x4301), listOf(0x4e0e, 0x4e0d))
        assertEquals(listOf(0x4e0e, 0x4e0d, 0x4300), frames.map { it.messageId })
        assertEquals(local, Iap2Csm.parseParams(frames.first().body).single { it.id == 0 }.asString())
    }

    @Test fun advertisedWifiReplyIsEnoughEvenIfTheRequestIsMissingFromTheList() {
        assertEquals(listOf(0x5702), messages(listOf(0x5703), listOf(0x4e0a)).map { it.messageId })
    }

    @Test fun neitherListDescribesTheExchangeSoBothCredentialPathsAreOffered() {
        assertEquals(listOf(0x4300, 0x5702), messages(listOf(0x5000), listOf(0x5001)).map { it.messageId })
    }
}
