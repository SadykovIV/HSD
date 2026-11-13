package com.hsd.universal.carplay

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class CarPlayServiceTest {
    private val ipv4 = InetAddress.getByName("192.0.2.2")
    private val ipv6 = InetAddress.getByName("2001:db8::2")
    private val stereo = CarPlayService(listOf(ipv4, ipv6), 7100, true)
    private val speaker = CarPlayService(listOf(ipv6), 7000, false)

    @Test fun invitationCanUseAnyAdvertisedAddressAndTheCarPlayPort() {
        assertEquals(ipv6 to 7100, CarPlayService.select(listOf(speaker, stereo), ipv6))
        assertEquals(ipv4 to 7100, CarPlayService.select(listOf(stereo, speaker), null))
    }

    @Test fun invitationSelectsAnUnambiguousLegacyReceiver() {
        assertNull(CarPlayService.select(listOf(speaker), null))
        assertEquals(ipv6 to 7000, CarPlayService.select(listOf(speaker), ipv6))
        assertNull(CarPlayService.select(listOf(speaker, speaker.copy(port = 7200)), ipv6))
        assertNull(CarPlayService.select(listOf(stereo, stereo.copy(port = 7200)), ipv6))
    }
}
