package com.hsd.universal.ui

import org.junit.Assert.*
import org.junit.Test

class UsbEnumerationRetryTest {
    private var now = 0L
    private var next: (() -> Unit)? = null
    private var due = 0L
    private val retry = UsbEnumerationRetry({ delay, action ->
        assertNull("only one scheduled USB query", next)
        due = now + delay
        next = action
    }, { next = null })

    private fun advance() {
        val action = requireNotNull(next)
        next = null
        now = due
        action()
    }

    @Test fun lateAccessoryStartsExactlyOnceAndStopsQuerying() {
        var started = 0
        retry.signal { (now >= 900).also { if (it) started++ } }
        while (next != null) advance()
        assertEquals(1, started)
        assertEquals(1100L, now)
    }

    @Test fun repeatedEmptySystemSignalsDoNotCreateOrExtendTheWindow() {
        retry.signal { false }
        repeat(5) {
            retry.signal { false }
            advance()
        }
        assertNull(next)
        assertEquals(5000L, now)
    }

    @Test fun anotherSignalCanDeliverDuringWait() {
        var visible = false
        var probes = 0
        val probe = { probes++; visible }
        retry.signal(probe)
        visible = true
        retry.signal(probe)
        assertNull(next)
        assertEquals(2, probes)
    }

    @Test fun cancelledCallbackCannotAttachToANewerConnectionUsingTheSameProbe() {
        var probes = 0
        val probe = { probes++; false }
        retry.signal(probe)
        val stale = requireNotNull(next)
        retry.cancel()
        retry.signal(probe)
        val fresh = next
        stale()
        assertSame(fresh, next)
        assertEquals(2, probes)
        retry.cancel()
        assertNull(next)
    }
}
