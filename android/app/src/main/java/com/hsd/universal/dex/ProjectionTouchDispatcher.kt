package com.hsd.universal.dex

import android.os.SystemClock
import android.view.MotionEvent

/** Called serially by the input lease owner. Track the contacts Android actually accepted. */
internal class ProjectionTouchDispatcher(
    private val send: (Int, MotionEvent) -> Boolean,
    private val report: (String) -> Unit,
) {
    private val active = mutableMapOf<Int, MotionEvent>()

    fun dispatch(id: Int, event: MotionEvent) {
        require(id > 0)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) {
            finish(id)
            return
        }
        if (action != MotionEvent.ACTION_DOWN && id !in active) return
        if (action == MotionEvent.ACTION_DOWN && id in active) finish(id)

        if (!send(id, event)) {
            // A disconnected receiver or a previous APK can leave a DOWN on a pooled display.
            // Retry only an explicitly rejected DOWN, once, after Android accepts its CANCEL.
            if (action != MotionEvent.ACTION_DOWN || !sendCancel(id, event) || !send(id, event)) {
                finish(id)
                report("display=$id action=$action rejected; waiting for next DOWN")
                return
            }
            report("display=$id recovered rejected DOWN")
        }
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
                active.put(id, MotionEvent.obtainNoHistory(event))?.recycle()
            MotionEvent.ACTION_UP -> forget(id)
        }
    }

    fun forget(id: Int) { active.remove(id)?.recycle() }

    /** Cancel before revoking the display. A dead target cannot block cleanup. */
    fun finish(id: Int) {
        val last = active.remove(id) ?: return
        try {
            if (!sendCancel(id, last)) report("display=$id CANCEL rejected")
        } catch (failure: Exception) {
            report("display=$id CANCEL unavailable: ${failure.javaClass.simpleName}")
        } finally { last.recycle() }
    }

    private fun sendCancel(id: Int, last: MotionEvent): Boolean {
        val lifted = if (last.actionMasked == MotionEvent.ACTION_POINTER_UP) last.actionIndex else -1
        val count = last.pointerCount - if (lifted >= 0) 1 else 0
        fun sourceIndex(index: Int) = if (lifted >= 0 && index >= lifted) index + 1 else index
        val properties = Array(count) { i ->
            MotionEvent.PointerProperties().also { last.getPointerProperties(sourceIndex(i), it) }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().also { last.getPointerCoords(sourceIndex(i), it); it.pressure = 0f }
        }
        val cancel = MotionEvent.obtain(last.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL,
            count, properties, coords, last.metaState, last.buttonState, last.xPrecision, last.yPrecision,
            last.deviceId, last.edgeFlags, last.source, last.flags or MotionEvent.FLAG_CANCELED)
        return try { send(id, cancel) } finally { cancel.recycle() }
    }
}
