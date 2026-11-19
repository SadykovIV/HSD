package com.hsd.universal.ui

/** One bounded set of UsbManager queries per attach signal. All calls share the owner's lock. */
internal class UsbEnumerationRetry(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val unschedule: () -> Unit,
) {
    private var pending = false
    private var generation = 0L

    fun signal(probe: () -> Boolean) {
        if (probe()) {
            cancel()
        } else if (!pending) {
            pending = true
            scheduleNext(probe, ++generation, 0)
        }
    }

    fun cancel() {
        if (!pending) return
        pending = false
        unschedule()
    }

    private fun scheduleNext(probe: () -> Boolean, id: Long, step: Int) {
        val delay = delays.getOrNull(step) ?: return cancel()
        schedule(delay) {
            if (!pending || generation != id) return@schedule
            if (probe()) cancel() else if (pending && generation == id) scheduleNext(probe, id, step + 1)
        }
    }

    private companion object {
        val delays = longArrayOf(100, 300, 700, 1400, 2500)
    }
}
