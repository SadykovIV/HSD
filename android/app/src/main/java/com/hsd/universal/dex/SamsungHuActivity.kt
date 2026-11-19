package com.hsd.universal.dex

/** Samsung's optional per-event marker leaves normal dispatch and its target application's tap handling intact. */
internal class SamsungHuActivity(motionType: Class<*>, keyType: Class<*>,
    private val report: (String, Boolean) -> Unit = { _, _ -> }) {
    private class Marker(val type: Class<*>, val label: String) {
        val method = runCatching {
            type.getMethod("semSetNonResetUserActivityTimeout").takeIf { it.returnType == Void.TYPE }
        }.getOrNull()
        var lastResult: Boolean? = null
    }
    private val motion = Marker(motionType, "motion")
    private val key = Marker(keyType, "key")

    fun mark(event: Any, displayId: Int): Boolean {
        if (displayId <= 0) return false
        val marker = when {
            motion.type.isInstance(event) -> motion
            key.type.isInstance(event) -> key
            else -> null
        } ?: return false
        val ok = marker.method?.let { runCatching { it.invoke(event) }.isSuccess } ?: false
        if (marker.lastResult != ok) {
            marker.lastResult = ok
            report(marker.label, ok)
        }
        return ok
    }
}
