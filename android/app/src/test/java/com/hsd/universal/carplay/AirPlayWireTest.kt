package com.hsd.universal.carplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AirPlayWireTest {
    @Test fun portsPreserveTheUnsignedWireValue() {
        for (port in listOf(1, 32767, 32768, 49152, 65535)) {
            assertEquals(port, airPlayPort(port.toLong()))
            assertEquals(port, airPlayPort(port.toShort().toLong()))
        }
        for (bad in listOf(null, 0L, -32769L, 65536L, Long.MAX_VALUE)) assertEquals(0, airPlayPort(bad))
    }

    @Test fun eventAcknowledgementPreservesTheRequestProtocolAndSequence() {
        for (protocol in listOf("RTSP/1.0", "HTTP/1.0", "HTTP/1.1")) {
            val output = ByteArrayOutputStream()
            val socket = object : Socket() {
                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                override fun getOutputStream() = output
            }
            AirPlayWire(socket).respond(AirPlayWire.Message("POST /command $protocol", mapOf("cseq" to "73"), byteArrayOf()))
            assertEquals("$protocol 200 OK\r\nCSeq: 73\r\nContent-Length: 0\r\n\r\n", output.toString("US-ASCII"))
        }
    }

    @Test fun delayedFeedbackDoesNotBlockCommandsOrStealTheirResponses() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { client ->
                server.accept().use { receiver ->
                    receiver.soTimeout = 3000
                    val wire = AirPlayWire(client)
                    wire.startResponseReader { }
                    val hu = AirPlayWire(receiver)
                    val feedback = CompletableFuture.supplyAsync { wire.request("POST", "/feedback", timeoutMillis = 0) }
                    val held = hu.read()
                    assertEquals("POST /feedback RTSP/1.0", held.line)
                    val command = CompletableFuture.supplyAsync { wire.request("POST", "/command", byteArrayOf(1, 2)) }
                    val next = hu.read()
                    assertEquals("POST /command RTSP/1.0", next.line)
                    assertArrayEquals(byteArrayOf(1, 2), next.data)
                    hu.respond(next)
                    assertArrayEquals(byteArrayOf(), command.get(3, TimeUnit.SECONDS))
                    assertFalse(feedback.isDone)
                    hu.respond(held)
                    assertArrayEquals(byteArrayOf(), feedback.get(3, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun expiredResponseCannotCompleteTheNextRequest() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { client ->
                server.accept().use { receiver ->
                    receiver.soTimeout = 3000
                    val wire = AirPlayWire(client)
                    wire.startResponseReader { }
                    val hu = AirPlayWire(receiver)
                    val first = CompletableFuture.supplyAsync { wire.request("POST", "/command", timeoutMillis = 100) }
                    val expired = hu.read()
                    try { first.get(3, TimeUnit.SECONDS); fail("Request did not expire") }
                    catch (error: ExecutionException) { assertTrue(error.cause is SocketTimeoutException) }
                    val next = CompletableFuture.supplyAsync { wire.request("GET", "/info") }
                    val current = hu.read()
                    hu.respond(expired)
                    // A different body makes assigning the expired CSeq to 'next' observable.
                    receiver.getOutputStream().write(("RTSP/1.0 200 OK\r\nCSeq: ${current.headers.getValue("cseq")}\r\n" +
                        "Content-Length: 2\r\n\r\nok").toByteArray())
                    assertArrayEquals("ok".toByteArray(), next.get(3, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun closingTransportReleasesFeedbackWithoutADeadline() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { client ->
                server.accept().use { receiver ->
                    receiver.soTimeout = 3000
                    val wire = AirPlayWire(client)
                    val failure = CompletableFuture<Exception>()
                    wire.startResponseReader { failure.complete(it) }
                    val pending = CompletableFuture.supplyAsync { wire.request("POST", "/feedback", timeoutMillis = 0) }
                    AirPlayWire(receiver).read()
                    receiver.close()
                    assertTrue(failure.get(3, TimeUnit.SECONDS) is java.io.EOFException)
                    try { pending.get(3, TimeUnit.SECONDS); fail("Closed transport left request alive") }
                    catch (error: ExecutionException) { assertTrue(error.cause is java.io.EOFException) }
                }
            }
        }
    }
}
