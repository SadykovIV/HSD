package com.hsd.universal.aa

/** Input-side rate control. Encoded reference frames are never discarded to lower FPS. */
internal class DesktopFrameRatePolicy {
    private var congestedSince: Long? = null
    private var clearSince: Long? = null
    private var lastChange: Long? = null
    private var lastAudioOverrun: Long? = null
    private var encoderPressureMs = 0.0
    @Volatile var current = 30
        private set

    @Synchronized fun reset(maximum: Int) {
        current = maximum.coerceIn(1, 60)
        congestedSince = null
        clearSince = null
        lastChange = null
        lastAudioOverrun = null
        encoderPressureMs = 0.0
    }

    @Synchronized fun update(nowMs: Long, backlogMs: Double, maximum: Int, overflow: Boolean = false,
                             encoderAgeMs: Double = 0.0, audioOverrun: Boolean = false): Int? {
        val limit = maximum.coerceIn(1, 60)
        if (audioOverrun) lastAudioOverrun = nowMs
        val late = overflow || audioOverrun
        if (current > limit) return change(limit, nowMs)
        if (!backlogMs.isFinite() || backlogMs < 0) return null
        // Sampled every 100 ms. A peak from one expensive intra picture must
        // not hold the producer at a low rate once its encoder and link drain.
        encoderPressureMs += (encoderAgeMs.coerceIn(0.0, 450.0) - encoderPressureMs) * 0.25
        val frameMs = 1000.0 / current
        val encoderBudgetMs = if (current > 30) 3 * frameMs else maxOf(150.0, 2 * frameMs)
        if (late || backlogMs >= 150 || encoderPressureMs >= encoderBudgetMs) {
            val since = congestedSince ?: nowMs.also { congestedSince = it }
            if (late || nowMs - since >= 500) clearSince = null
            if ((late || nowMs - since >= 500) && (lastChange == null || nowMs - lastChange!! >= 1000)) {
                val lower = if (audioOverrun && current > 30) 30 else STEPS.lastOrNull { it < current } ?: minOf(10, limit)
                if (lower < current) return change(lower, nowMs)
            }
        } else {
            congestedSince = null
            if (backlogMs <= 50 && encoderPressureMs < maxOf(80.0, frameMs)) {
                val since = clearSince ?: nowMs.also { clearSince = it }
                if (nowMs - since >= 3000 && (lastChange == null || nowMs - lastChange!! >= 3000) &&
                    (lastAudioOverrun == null || nowMs - lastAudioOverrun!! >= 10_000)) {
                    val higher = minOf(limit, STEPS.firstOrNull { it > current } ?: limit)
                    if (higher > current) return change(higher, nowMs)
                }
            } else clearSince = clearSince?.let { minOf(nowMs, it + 100) }
        }
        return null
    }

    private fun change(value: Int, nowMs: Long): Int {
        current = value
        lastChange = nowMs
        congestedSince = null
        clearSince = null
        return value
    }

    companion object { private val STEPS = intArrayOf(10, 15, 20, 24, 30, 45, 60) }
}
