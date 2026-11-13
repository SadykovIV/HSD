package com.hsd.universal

import java.io.Closeable
import java.io.IOException

internal interface ProjectionSession {
    fun start()

    fun home()

    fun panel(off: Boolean)

    fun stop(message: String)
}

/** Tracks blocking Bluetooth, listening and data sockets even when a stop races a connection. */
internal class ProjectionResources : Closeable {
    private val releases = ArrayDeque<() -> Unit>()
    private var closed = false

    fun own(release: () -> Unit) {
        synchronized(this) {
            if (!closed) {
                releases.addFirst(release)
                return
            }
        }
        runCatching(release)
        throw IOException("Соединение остановлено")
    }

    override fun close() {
        val callbacks =
            synchronized(this) {
                if (closed) return
                closed = true
                releases.toList().also { releases.clear() }
            }
        callbacks.forEach { runCatching(it) }
    }
}
