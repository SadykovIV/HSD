package com.hsd.universal

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsyncLogWriterTest {
    @Test fun blockedStorageCannotBlockMediaAndOnlyABoundedTailIsRetained() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val produced = CountDownLatch(1)
        val file = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(value: Int) = error("Expected batched writes")
            override fun write(data: ByteArray, offset: Int, length: Int) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                file.write(data, offset, length)
            }
        }
        val log = AsyncLogWriter(output, 100)
        try {
            log.append("first")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            Thread { repeat(10_000) { log.append("entry-$it") }; produced.countDown() }.start()
            assertTrue("Disk latency must not reach the producer", produced.await(3, TimeUnit.SECONDS))
            assertFalse(log.flush(1))
            release.countDown()
            assertTrue(log.flush(3000))
            val text = file.toString("UTF-8")
            assertTrue(text.startsWith("first\n[HSD log] dropped "))
            assertTrue(text.endsWith("entry-9999\n"))
            assertTrue(text.length < 220)
        } finally { release.countDown(); log.close() }
    }

    @Test fun exportFlushPreservesOrderAndOversizedEntriesAreExplicitlyReported() {
        val output = ByteArrayOutputStream(); val log = AsyncLogWriter(output, 128)
        try {
            for (i in 0..2) { log.append("line-$i"); assertTrue(log.flush(3000)) }
            log.append("x".repeat(200))
            assertTrue(log.flush(3000))
            assertEquals("line-0\nline-1\nline-2\n[HSD log] dropped 1 entries: diagnostic storage backlog\n", output.toString("UTF-8"))
        } finally { log.close() }
    }

    @Test fun storageFailureEndsFlushAndDoesNotBreakLaterProducers() {
        val failed = CountDownLatch(1)
        val log = AsyncLogWriter(object : OutputStream() {
            override fun write(value: Int) { throw IOException("disk unavailable") }
        }, onFailure = { failed.countDown() })
        log.append("message")
        assertTrue(failed.await(3, TimeUnit.SECONDS))
        assertFalse(log.flush(3000))
        repeat(1000) { log.append("still live") }
        log.close()
    }
}
