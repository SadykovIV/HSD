package com.hsd.universal.dex

import org.junit.Assert.*
import org.junit.Test

class SamsungHuActivityTest {
    class Motion {
        var tagged = false
        fun semSetNonResetUserActivityTimeout() { tagged = true }
    }
    class Key {
        var tagged = false
        fun semSetNonResetUserActivityTimeout() { tagged = true }
    }
    class Missing
    class Rejected { fun semSetNonResetUserActivityTimeout() { error("unavailable") } }

    @Test fun bothKindsOfOwnedInputAreMarkedAndReportedOnceEach() {
        val reports = mutableListOf<Pair<String, Boolean>>()
        val access = SamsungHuActivity(Motion::class.java, Key::class.java) { kind, ok -> reports += kind to ok }
        val motion = Motion()
        val key = Key()
        assertTrue(access.mark(motion, 1))
        assertTrue(access.mark(key, 2))
        assertTrue(access.mark(Motion(), 1))
        assertEquals(listOf("motion" to true, "key" to true), reports)
        assertTrue(motion.tagged)
        assertTrue(key.tagged)
    }

    @Test fun neverMarksThePhysicalScreenOrAnInvalidDisplay() {
        val access = SamsungHuActivity(Motion::class.java, Key::class.java)
        val motion = Motion()
        assertFalse(access.mark(motion, 0))
        assertFalse(access.mark(motion, -1))
        assertFalse(motion.tagged)
    }

    @Test fun unavailableFirmwareExtensionDoesNotBreakEventDelivery() {
        val reports = mutableListOf<Pair<String, Boolean>>()
        val access = SamsungHuActivity(Missing::class.java, Rejected::class.java) { kind, ok -> reports += kind to ok }
        assertFalse(access.mark(Missing(), 1))
        assertFalse(access.mark(Rejected(), 1))
        assertFalse(access.mark(Any(), 1))
        assertFalse(access.mark(Missing(), 1))
        assertEquals(listOf("motion" to false, "key" to false), reports)
    }
}
