package com.hsd.universal.carplay

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CarPlayScreenControllerTest {
    private fun resource(transfer: Int, vararg values: Pair<String, Int>) =
        mapOf("resourceID" to 1, "transferType" to transfer) + values
    private fun entity(mode: CarPlayScreenOwnership, key: String): Long =
        (((mode.notification()["params"] as Map<*, *>)["resources"] as List<*>).single() as Map<*, *>)[key] as Long

    @Test fun `car menu releases screen and parameterless UI request takes it back`() {
        val mode = CarPlayScreenOwnership()
        assertTrue(mode.releaseController())
        assertEquals(0L, entity(mode, "entity"))
        assertEquals(0L, entity(mode, "permanentEntity"))
        assertFalse(mode.releaseController())
        assertTrue(mode.requestController())
        assertEquals(1L, entity(mode, "entity"))
        val resources = (mode.notification()["params"] as Map<*, *>)["resources"] as List<*>
        assertEquals(listOf(1L), resources.map { (it as Map<*, *>)["resourceID"] })
    }

    @Test fun `camera borrows screen and only the last unborrow restores projection`() {
        val mode = CarPlayScreenOwnership()
        val borrow = resource(3, "transferPriority" to 500, "unborrowConstraint" to 1000)
        assertTrue(mode.accessory(borrow)); assertTrue(mode.accessory(borrow))
        assertEquals(2, mode.entity)
        assertEquals(1L, entity(mode, "permanentEntity"))
        assertFalse(mode.requestController())
        assertTrue(mode.accessory(resource(4))); assertEquals(2, mode.entity)
        assertTrue(mode.accessory(resource(4))); assertEquals(1, mode.entity)
        assertTrue(mode.accessory(resource(4))); assertEquals(1, mode.entity)
    }

    @Test fun `accessory ownership constraints are honored without changing audio`() {
        val mode = CarPlayScreenOwnership()
        assertTrue(mode.accessory(resource(1, "transferPriority" to 500, "takeConstraint" to 1000, "borrowConstraint" to 100)))
        assertFalse(mode.requestController())
        assertFalse(mode.releaseController())
        assertFalse(mode.accessory(mapOf("resourceID" to 2, "transferType" to 2)))
        assertFalse(mode.accessory(resource(2, "transferPriority" to 500)))
        assertEquals(2, mode.entity)
        assertTrue(mode.accessory(resource(2)))
        assertTrue(mode.requestController())
    }

    private class Stream(private val block: Boolean = false) : CarPlayScreenController.Stream {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun send(payload: ByteArray, ptsUs: Long): Boolean {
            entered.countDown()
            if (block) { check(closed.await(3, TimeUnit.SECONDS)); throw IOException("socket closed during handoff") }
            return true
        }
        override fun keepAlive() {}
        override fun close() { closed.countDown() }
    }
    private fun await(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) { check(System.nanoTime() < until) { what }; Thread.sleep(2) }
    }

    @Test fun `handoff closes a blocked video write without failing session and resumes only once`() {
        val streams = CopyOnWriteArrayList<Stream>()
        val errors = CopyOnWriteArrayList<Exception>()
        val notices = CopyOnWriteArrayList<Map<String, Any>>()
        val focus = CopyOnWriteArrayList<Boolean>()
        val teardowns = AtomicInteger()
        CarPlayScreenController(
            { Stream(streams.isEmpty()).also(streams::add) }, { teardowns.incrementAndGet() },
            notices::add, focus::add, errors::add,
        ).use { control ->
            control.start()
            val sent = java.util.concurrent.CompletableFuture<Boolean>()
            val writer = thread { sent.complete(control.send(byteArrayOf(1), 0)) }
            assertTrue(streams[0].entered.await(1, TimeUnit.SECONDS))
            control.carMenu(); control.carMenu()
            await("screen not released") { teardowns.get() == 1 }
            assertFalse(sent.get(1, TimeUnit.SECONDS)); writer.join(1000)
            assertFalse(control.projected)
            assertTrue(control.event(mapOf("type" to "requestUI")))
            assertTrue(control.event(mapOf("type" to "requestUI", "params" to emptyMap<String, Any>())))
            await("screen not resumed") { notices.size == 3 }
            assertEquals(2, streams.size)
            assertEquals(listOf(false, true), focus.toList())
            assertTrue(control.send(byteArrayOf(2), 10))
            assertTrue(errors.isEmpty())
        }
        assertTrue(streams.all { it.closed.count == 0L })
    }

    @Test fun `closing during screen setup rejects a late stream and late button clicks`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val second = Stream()
        val count = AtomicInteger()
        val errors = CopyOnWriteArrayList<Exception>()
        val control = CarPlayScreenController({
            if (count.incrementAndGet() == 1) Stream() else {
                entered.countDown()
                // Like a platform connect that finishes despite executor interruption.
                while (release.count != 0L) try { release.await() } catch (_: InterruptedException) {}
                second
            }
        }, {}, {}, {}, errors::add)
        control.start(); control.carMenu()
        await("not native") { !control.projected }
        control.event(mapOf("type" to "requestUI"))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        control.close(); release.countDown()
        assertTrue(second.closed.await(2, TimeUnit.SECONDS))
        control.carMenu(); control.event(mapOf("type" to "requestUI"))
        assertEquals(2, count.get())
        assertFalse(control.projected)
        assertTrue(errors.isEmpty())
    }
}
