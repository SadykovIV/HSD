package com.hsd.universal.carplay

import com.hsd.universal.ProjectionResources
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CarPlayRfcommTest {
    @Test fun outgoingSocketFactoryCanRecoverDuringServiceDiscovery() {
        ProjectionResources().use { life ->
            var opens = 0
            val socket = Closeable {}
            val result = dialCarPlayRfcomm(life, open = {
                if (++opens == 1) throw IOException("service unavailable")
                socket
            }, connect = {}, log = {}, initialRetryMs = 1)
            assertSame(socket, result)
            assertEquals(2, opens)
        }
    }

    @Test fun lateAccessoryServiceGetsAFreshSocketAndRetiresFailedSockets() {
        var opened = 0
        var attempts = 0
        val closed = mutableListOf<Int>()
        ProjectionResources().use { life ->
            dialCarPlayRfcomm(life, open = {
                val id = ++opened
                Closeable { closed += id }
            }, connect = { if (++attempts < 3) throw IOException("SDP unavailable") },
                log = {}, initialRetryMs = 1)
            assertEquals(3, attempts)
            assertEquals(listOf(1, 2), closed)
        }
        assertEquals(listOf(1, 2, 3), closed)
    }

    @Test fun incomingWinnerCancelsOutgoingRetryImmediately() {
        val retrying = CountDownLatch(1)
        val done = CountDownLatch(1)
        val accepted = Closeable {}
        ProjectionResources().use { life ->
            val winner = connectCarPlayRfcomm(life, incoming = { owner ->
                check(retrying.await(2, TimeUnit.SECONDS))
                owner.own(accepted::close)
                accepted
            }, outgoing = { owner ->
                try {
                    dialCarPlayRfcomm(owner, { Closeable {} }, { throw IOException() }, { retrying.countDown() })
                } finally { done.countDown() }
            }, log = {})
            assertSame(accepted, winner)
            assertTrue(done.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun eitherRoleCanConnectAfterTheOtherFails() {
        for (incomingWins in listOf(true, false)) ProjectionResources().use { life ->
            val loggedFailure = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val socket = Closeable { closed.countDown() }
            val success: (ProjectionResources) -> Closeable = { owner ->
                check(loggedFailure.await(2, TimeUnit.SECONDS))
                owner.own(socket::close)
                socket
            }
            val failure: (ProjectionResources) -> Closeable = { throw IOException("SDP failed") }
            val result = connectCarPlayRfcomm(life,
                if (incomingWins) success else failure, if (incomingWins) failure else success,
                { if ("failed" in it) loggedFailure.countDown() })
            assertSame(socket, result)
            assertEquals(1, closed.count)
            life.close()
            assertEquals(0, closed.count)
        }
    }

    @Test fun bothFailedReportsImmediatelyAndRedactsAddresses() {
        val logs = CopyOnWriteArrayList<String>()
        val fail: (ProjectionResources) -> Closeable = { throw IOException("socket AA:12:34:56:78:9B closed") }
        ProjectionResources().use { life ->
            val error = assertThrows(IOException::class.java) { connectCarPlayRfcomm(life, fail, fail, logs::add) }
            assertTrue(error.message.orEmpty().contains("Оба канала"))
            assertTrue(logs.any { "incoming failed" in it })
            assertTrue(logs.any { "outgoing failed" in it })
            assertTrue(logs.all { "AA:12" !in it })
        }
    }

    @Test fun timeoutClosesPendingAcceptAndIdentifiesBothDirections() {
        val closed = CountDownLatch(1)
        val logs = CopyOnWriteArrayList<String>()
        ProjectionResources().use { life ->
            val error = assertThrows(IOException::class.java) {
                connectCarPlayRfcomm<Closeable>(life, incoming = { owner ->
                    owner.own(closed::countDown)
                    closed.await()
                    throw IOException()
                }, outgoing = { throw IOException("no remote service") }, log = logs::add, timeoutMs = 500)
            }
            assertTrue(error.cause is java.util.concurrent.TimeoutException)
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(logs.any { "incoming=pending" in it && "outgoing=failed" in it })
        }
    }

    @Test fun stoppingSessionInterruptsBothRoles() {
        val life = ProjectionResources()
        val started = CountDownLatch(2)
        val closed = CountDownLatch(2)
        val connect: (ProjectionResources) -> Closeable = { owner ->
            owner.own(closed::countDown)
            started.countDown()
            closed.await()
            throw IOException("closed")
        }
        val task = java.util.concurrent.FutureTask { connectCarPlayRfcomm(life, connect, connect, {}) }
        Thread(task).apply { isDaemon = true; start() }
        try { assertTrue(started.await(2, TimeUnit.SECONDS)) } finally { life.close() }
        val exception = assertThrows(java.util.concurrent.ExecutionException::class.java) { task.get(2, TimeUnit.SECONDS) }
        assertTrue(exception.cause is CancellationException)
        assertEquals(0, closed.count)
    }
}
