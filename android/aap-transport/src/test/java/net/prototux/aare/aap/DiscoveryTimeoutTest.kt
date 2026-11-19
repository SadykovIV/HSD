package net.prototux.aare.aap

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTimeoutTest {
    private val timers = mutableListOf<() -> Unit>()
    private val failures = mutableListOf<String>()
    private val exchange = DiscoveryTimeout({ timers.add(it) }, { failures.add(it) })

    @Test fun slowWriteLeavesReceiverItsEntireReplyInterval() {
        exchange.start()
        exchange.written()
        timers.first()()
        assertTrue(exchange.received())
        timers.last()()
        assertTrue(failures.isEmpty())
    }
    @Test fun stalledWriteAndSilentReceiverAreDistinctFailures() {
        exchange.start()
        timers.single()()
        exchange.written()
        assertFalse(exchange.received())
        assertEquals(listOf("service discovery request write timed out"), failures)
    }
    @Test fun writtenRequestWithNoReplyExpiresOnce() {
        exchange.start()
        exchange.written()
        timers.reversed().forEach { it() }
        assertEquals(listOf("no service discovery response"), failures)
    }
    @Test fun immediateReplyCanWinARaceWithFlushCompletion() {
        exchange.start()
        assertTrue(exchange.received())
        exchange.written()
        timers.single()()
        assertTrue(failures.isEmpty())
    }
}
