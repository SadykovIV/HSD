package com.hsd.universal.aa

import org.junit.Assert.*
import org.junit.Test

class DesktopFrameRatePolicyTest {
    @Test fun musicOverrunReducesVideoOnAClearWireUntilAudioStaysHealthy() {
        val policy = DesktopFrameRatePolicy().apply { reset(60) }
        assertEquals(30, policy.update(0, 0.0, 60, audioOverrun = true))
        assertEquals(24, policy.update(1200, 0.0, 60, audioOverrun = true))
        for (now in 1300L..10_900L step 100) {
            assertNull(policy.update(now, 0.0, 60))
        }
        assertEquals(30, policy.update(11_300L, 0.0, 60))
        for (now in 11_400L..19_000L step 100) policy.update(now, 0.0, 60)
        assertEquals(60, policy.current)
    }
    @Test fun sustainedCongestionReducesRateThenRecoversWithinHuLimit() {
        val policy = DesktopFrameRatePolicy().apply { reset(60) }
        for (now in 0L..7000L step 100) policy.update(now, 250.0, 60)
        assertEquals(10, policy.current)
        for (now in 7100L..100000L step 100) policy.update(now, 0.0, 45)
        assertEquals(45, policy.current)
    }
    @Test fun sparseEncoderSpikesPermitRecoveryAfterASlowStart() {
        val policy = DesktopFrameRatePolicy().apply { reset(30) }
        for (now in 0L..2500L step 100) policy.update(now, 0.0, 30, encoderAgeMs = 400.0)
        assertTrue(policy.current < 30)
        for (now in 2600L..20000L step 100)
            policy.update(now, 0.0, 30, encoderAgeMs = if (now % 1000 == 0L) 160.0 else 12.0)
        assertEquals(30, policy.current)
    }

    @Test fun briefIdrBurstDoesNotOscillate() {
        val policy = DesktopFrameRatePolicy().apply { reset(30) }
        policy.update(0, 500.0, 30)
        policy.update(200, 500.0, 30)
        policy.update(300, 0.0, 30)
        assertEquals(30, policy.current)
    }
    @Test fun thermalOrHuCapIsImmediateAndRecoveryHasHysteresis() {
        val policy = DesktopFrameRatePolicy().apply { reset(60) }
        assertEquals(15, policy.update(0, 0.0, 15))
        assertNull(policy.update(1000, 0.0, 60))
        assertNull(policy.update(2000, 0.0, 60))
        assertEquals(20, policy.update(9000, 0.0, 60))
    }
    @Test fun overflowReducesProducerRateAndResetForgetsPreviousTransport() {
        val policy = DesktopFrameRatePolicy().apply { reset(30) }
        assertEquals(24, policy.update(0, 0.0, 30, true))
        assertNull(policy.update(100, 0.0, 30, true))
        policy.reset(3)
        for (now in 0L..10000L step 100) policy.update(now, 500.0, 3, true)
        assertEquals(3, policy.current)
    }
}
