package com.hsd.universal.carplay

import com.hsd.universal.ProjectionResources
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Both secure RFCOMM roles are optional for the peer. Keep the other role alive if one fails. */
internal fun <T : Closeable> connectCarPlayRfcomm(
    life: ProjectionResources,
    incoming: (ProjectionResources) -> T,
    outgoing: (ProjectionResources) -> T,
    log: (String) -> Unit,
    timeoutMs: Long = TimeUnit.MINUTES.toMillis(2),
): T {
    val result = CompletableFuture<Pair<ProjectionResources, T>>()
    val receiving = ProjectionResources()
    val dialing = ProjectionResources()
    val failures = linkedMapOf<String, String>()
    life.own(receiving::close)
    life.own(dialing::close)
    life.own { result.cancel(false) }

    fun attempt(role: String, resources: ProjectionResources, connect: (ProjectionResources) -> T) {
        thread(name = "CarPlay RFCOMM $role", isDaemon = true) {
            try {
                val socket = connect(resources)
                if (result.complete(resources to socket)) log("RFCOMM $role connected")
                else resources.close()
            } catch (e: Exception) {
                resources.close()
                synchronized(failures) {
                    if (!result.isDone) {
                        failures[role] = bluetoothError(e)
                        log("RFCOMM $role failed · ${failures[role]}")
                        if (failures.size == 2)
                            result.completeExceptionally(IOException("Оба канала Bluetooth iAP2 недоступны. Проверьте сопряжение и включите беспроводной CarPlay на магнитоле.", e))
                    }
                }
            }
        }
    }

    var winner: ProjectionResources? = null
    try {
        attempt("incoming", receiving, incoming)
        attempt("outgoing", dialing, outgoing)
        val (owner, socket) = result.get(timeoutMs, TimeUnit.MILLISECONDS)
        winner = owner
        return socket
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: TimeoutException) {
        val directions = synchronized(failures) {
            listOf("incoming", "outgoing").joinToString { "$it=${if (it in failures) "failed" else "pending"}" }
        }
        log("RFCOMM timeout · $directions")
        throw IOException("За ${timeoutMs / 1000} секунд канал Bluetooth iAP2 не открылся. Выберите Apple CarPlay на магнитоле. Для первого сопряжения включите видимость телефона в настройках подключения HSD.", e)
    } finally {
        result.cancel(false)
        if (winner !== receiving) receiving.close()
        if (winner !== dialing) dialing.close()
    }
}

/** A paired head unit can publish its accessory SDP record after the first connection attempt. */
internal fun <T : Closeable> dialCarPlayRfcomm(
    resources: ProjectionResources,
    open: () -> T,
    connect: (T) -> Unit,
    log: (String) -> Unit,
    initialRetryMs: Long = 3_000,
): T {
    val stopped = CountDownLatch(1)
    val current = AtomicReference<T?>()
    resources.own {
        stopped.countDown()
        current.getAndSet(null)?.close()
    }
    var delayMs = initialRetryMs
    var attempt = 0
    while (stopped.count != 0L) {
        val socket = open()
        current.set(socket)
        var connected = false
        try {
            if (stopped.count == 0L) break
            attempt++
            connect(socket)
            connected = true
            return socket
        } catch (e: IOException) {
            if (stopped.count == 0L) break
            log("RFCOMM outgoing attempt $attempt · ${bluetoothError(e)} · retry in ${delayMs} ms")
        } finally {
            if (!connected && current.compareAndSet(socket, null)) runCatching(socket::close)
        }
        if (stopped.await(delayMs, TimeUnit.MILLISECONDS)) break
        delayMs = minOf(delayMs * 2, 12_000)
    }
    throw IOException("Поиск соединения Bluetooth остановлен")
}

private val BLUETOOTH_ADDRESS = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
private fun bluetoothError(e: Exception): String {
    val detail = e.message.orEmpty().replace(BLUETOOTH_ADDRESS, "[BT]").lineSequence().first().take(140)
    return "${e.javaClass.simpleName} $detail".trim()
}
