package com.hsd.universal.carplay

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Serializes screen control off the UI/event threads, independently of audio and feedback. */
internal class CarPlayScreenController(
    private val setup: () -> Stream,
    private val teardown: () -> Unit,
    private val notifyModes: (Map<String, Any>) -> Unit,
    private val focus: (Boolean) -> Unit,
    private val failed: (Exception) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "CarPlay screen control").apply { isDaemon = true } },
) : Closeable {
    interface Stream : Closeable {
        fun send(payload: ByteArray, ptsUs: Long): Boolean
        fun keepAlive()
    }

    private val mode = CarPlayScreenOwnership()
    private val ready = CountDownLatch(1)
    private val gate = Any()
    @Volatile private var current: Stream? = null
    @Volatile private var closed = false
    val projected get() = !closed && current != null

    fun start() {
        install(setup())
        ready.countDown()
    }

    fun carMenu() = submit { mode.releaseController() }

    fun event(command: Map<*, *>): Boolean = when (command["type"]) {
        // requestUI without params is a valid request from the receiver's CarPlay icon.
        "requestUI" -> { submit { mode.requestController() }; true }
        "changeModes" -> {
            val resources = ((command["params"] as? Map<*, *>)?.get("resources") as? List<*>)
                .orEmpty().filterIsInstance<Map<*, *>>()
                .filter { (it["resourceID"] as? Number)?.toInt() == CarPlayScreenOwnership.SCREEN }
            if (resources.isNotEmpty()) submit {
                var handled = false
                resources.forEach { if (mode.accessory(it)) handled = true }
                handled
            }
            resources.isNotEmpty()
        }
        else -> false
    }

    private fun submit(change: () -> Boolean) {
        if (closed) return
        try {
            executor.execute {
                try {
                    ready.await()
                    if (closed || !change()) return@execute
                    val projected = mode.entity == CarPlayScreenOwnership.CONTROLLER
                    if (!projected) {
                        // Closing the old socket releases an in-flight send. Its expected failure
                        // must not tear down audio or the authenticated AirPlay session.
                        val old = detach()
                        if (old != null) { old.close(); focus(false) }
                        notifyModes(mode.notification())
                        if (old != null) teardown()
                    } else {
                        notifyModes(mode.notification())
                        if (current == null && !closed) {
                            if (install(setup())) focus(true)
                        }
                    }
                } catch (e: Exception) { if (!closed) failed(e) }
            }
        } catch (e: RejectedExecutionException) { if (!closed) failed(e) }
    }

    fun send(payload: ByteArray, ptsUs: Long): Boolean {
        val stream = current ?: return false
        return try { stream.send(payload, ptsUs) && current === stream }
        catch (e: Exception) { if (!closed && current === stream) throw e else false }
    }

    fun keepAlive() {
        val stream = current ?: return
        try { stream.keepAlive() }
        catch (e: Exception) { if (!closed && current === stream) throw e }
    }

    private fun install(stream: Stream): Boolean {
        synchronized(gate) { if (!closed) { check(current == null); current = stream; return true } }
        stream.close()
        return false
    }
    private fun detach(): Stream? = synchronized(gate) { current.also { current = null } }

    override fun close() {
        val stream = synchronized(gate) { closed = true; current.also { current = null } }
        ready.countDown()
        executor.shutdownNow()
        stream?.close()
    }
}
