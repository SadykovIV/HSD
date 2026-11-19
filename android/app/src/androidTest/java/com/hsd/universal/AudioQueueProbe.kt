package com.hsd.universal

import com.hsd.universal.carplay.CarPlayAudio
import com.hsd.universal.carplay.CarPlayQueuedAudio
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun checkAudioQueue(): String {
    val entered = CountDownLatch(1); val release = CountDownLatch(1); val resumed = CountDownLatch(1)
    val staleRejected = AtomicBoolean()
    val codecReset = AtomicBoolean()
    val queue = AutoPcmQueue(synchronize = false) { bytes, _, current ->
        if (bytes[0] == 1.toByte()) {
            entered.countDown()
            check(release.await(3,TimeUnit.SECONDS))
            staleRejected.set(!current())
        } else if (bytes[0] == 3.toByte()) {
            check(codecReset.get()) { "New-epoch PCM reached the retired codec" }
            resumed.countDown()
        }
    }
    try {
        queue.setPlaying(true)
        check(queue.enqueue(byteArrayOf(1),System.nanoTime()/1000))
        check(entered.await(2,TimeUnit.SECONDS))
        val enqueued = CountDownLatch(1)
        val producer = kotlin.concurrent.thread(name="QA capture while encoder blocked") {
            check(queue.enqueue(byteArrayOf(2),System.nanoTime()/1000))
            queue.resetCodec { codecReset.set(true) }
            check(queue.enqueue(byteArrayOf(3),System.nanoTime()/1000))
            enqueued.countDown()
        }
        // The blocked consumer cannot prevent capture, focus loss or the new epoch.
        check(enqueued.await(1,TimeUnit.SECONDS)) { "Audio I/O holds the capture queue lock" }
        release.countDown(); producer.join(1000)
        check(resumed.await(2,TimeUnit.SECONDS) && staleRejected.get())
        checkAudioQueueBoundary()
        checkCarPlayAudioWindow()
        return "Blocked encoder: capture enqueue and focus invalidation complete independently; stale output rejected, codec reset before new epoch; speech STOP survives overload"
    } finally { release.countDown(); queue.close() }
}

private fun checkAudioQueueBoundary() {
    val boundary = AtomicBoolean()
    val delivered = CountDownLatch(1)
    val queue = AutoPcmQueue(synchronize = false) { _, _, _ ->
        check(boundary.get()) { "Overload discarded the speech stream boundary" }
        delivered.countDown()
    }
    try {
        check(queue.enqueue(ByteArray(320 * 1024),System.nanoTime()/1000))
        queue.afterPending { current -> check(current()); boundary.set(true) }
        check(queue.enqueue(ByteArray(320 * 1024),System.nanoTime()/1000))
        queue.setPlaying(true)
        check(delivered.await(2,TimeUnit.SECONDS))
    } finally { queue.close() }
}

private fun checkCarPlayAudioWindow() {
    val local = InetAddress.getLoopbackAddress()
    DatagramSocket(0, local).use { rx ->
        DatagramSocket().use { tx ->
            val sender = CarPlayAudio(tx, local, rx.localPort, 0, 100, ByteArray(32), 48000, latencyMs = 500)
            CarPlayQueuedAudio(sender) { throw AssertionError(it) }.use { queue ->
                val block = ByteArray(352 * 4)
                val now = System.nanoTime() / 1000
                check(!queue.offer(block, now - 600_000))
                check(queue.offer(block, now - 200_000))
                rx.soTimeout = 1500
                val packet = DatagramPacket(ByteArray(2048), 2048)
                rx.receive(packet)
                check(packet.length > block.size && queue.discardedPackets == 1L)
            }
        }
    }
}

internal fun checkProjectionCpuLease(test: android.app.Instrumentation): String {
    val context = test.targetContext
    val life = ProjectionResources()
    val lock = ProjectionCpuLease.hold(context,life)
    try {
        check(lock.isHeld)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        test.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
        val deadline = android.os.SystemClock.elapsedRealtime()+3000
        while (power.isInteractive && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(20)
        check(!power.isInteractive && lock.isHeld) { "CPU lease prevented the phone panel from sleeping" }
        life.close(); check(!lock.isHeld)
        life.close(); check(!lock.isHeld)
    } finally {
        life.close()
        test.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
    }
    val closed = ProjectionResources().apply { close() }
    check(runCatching { ProjectionCpuLease.hold(context,closed) }.exceptionOrNull() is java.io.IOException)
    return "Partial CPU lock kept while phone panel sleeps; success, duplicate stop and stop/start race release it"
}
