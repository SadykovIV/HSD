package com.hsd.universal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** File I/O never runs on a media/input producer. Slow storage has a bounded, visible cost. */
internal class AsyncLogWriter(private val output: OutputStream, private val limitBytes: Int = 256 * 1024,
                              private val onFailure: (IOException) -> Unit = {}) : AutoCloseable {
    private data class Entry(val sequence: Long, val bytes: ByteArray)
    private val lock = Object()
    private val pending = ArrayDeque<Entry>()
    private var bytes = 0
    private var submitted = 0L
    private var written = 0L
    private var dropped = 0L
    private var closing = false
    private var failed = false

    init {
        require(limitBytes > 0)
        Thread(::writeLoop, "hsd-log-writer").apply { isDaemon = true }.start()
    }

    fun append(line: String) {
        val data = (line + "\n").toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            if (closing || failed) return
            val sequence = ++submitted
            if (data.size > limitBytes) dropped++ else {
                while (bytes + data.size > limitBytes) {
                    bytes -= pending.removeFirst().bytes.size
                    dropped++
                }
                pending.addLast(Entry(sequence, data)); bytes += data.size
            }
            lock.notifyAll()
        }
    }

    /** Wait for the entries present at this call, with a finite export/crash budget. */
    fun flush(timeoutMs: Long = 500): Boolean {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        synchronized(lock) {
            val target = submitted
            while (!failed && written < target) {
                val left = until - System.nanoTime()
                if (left <= 0) return false
                try { TimeUnit.NANOSECONDS.timedWait(lock, left) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
            return !failed
        }
    }

    override fun close() = synchronized(lock) { closing = true; lock.notifyAll() }

    private fun writeLoop() {
        try {
            while (true) {
                val batch = ByteArrayOutputStream()
                val sequence = synchronized(lock) {
                    while (pending.isEmpty() && dropped == 0L && !closing) lock.wait()
                    if (pending.isEmpty() && dropped == 0L && closing) return
                    if (dropped > 0) {
                        batch.write("[HSD log] dropped $dropped entries: diagnostic storage backlog\n".toByteArray())
                        dropped = 0
                    }
                    var last = written
                    while (pending.isNotEmpty() && batch.size() < 32 * 1024) {
                        val entry = pending.removeFirst(); bytes -= entry.bytes.size
                        batch.write(entry.bytes); last = entry.sequence
                    }
                    // Oversized entries can be represented only by the dropped-entry marker.
                    if (pending.isEmpty()) submitted else last
                }
                output.write(batch.toByteArray()); output.flush()
                synchronized(lock) { written = sequence; lock.notifyAll() }
            }
        } catch (error: IOException) {
            synchronized(lock) { failed = true; pending.clear(); bytes = 0; lock.notifyAll() }
            onFailure(error)
        } catch (_: InterruptedException) {
            synchronized(lock) { failed = true; pending.clear(); bytes = 0; lock.notifyAll() }
            Thread.currentThread().interrupt()
        } finally { runCatching(output::close) }
    }
}
