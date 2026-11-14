package com.hsd.universal.aa

/** Converts receiver reports into one Android gesture per contact lifetime. */
internal class ProjectionTouchStream {
    private var contacts = emptyList<TouchPoint>()
    private var firstDown: TouchPoint? = null
    val active get() = contacts.isNotEmpty()
    var repeatedDowns = 0
        private set

    fun cancel(emit: (Int, Int, List<TouchPoint>) -> Unit) {
        val previous = contacts
        contacts = emptyList()
        firstDown = null
        if (previous.isNotEmpty()) emit(3, 0, previous)
    }

    fun send(action: Int, index: Int, points: List<TouchPoint>, emit: (Int, Int, List<TouchPoint>) -> Unit) {
        if (action == 3) { cancel(emit); return }
        if (points.size !in 1..2 || points.any { it.id !in 0..31 } ||
            points.size == 2 && points[0].id == points[1].id) return
        val sameContacts = points.size == contacts.size && points.all { p -> contacts.any { it.id == p.id } }
        when (action) {
            AapMessages.TOUCH_DOWN -> {
                val point = points.singleOrNull() ?: return
                val previous = contacts.firstOrNull { it.id == point.id }
                if (previous != null) {
                    repeatedDowns++
                    // Some receivers repeat the original DOWN even after MOVE. Do not rewind the drag.
                    if (contacts.size == 1 && point != firstDown && point != previous) {
                        contacts = points
                        emit(AapMessages.TOUCH_MOVE, 0, points)
                    }
                    return
                }
                cancel(emit)
                firstDown = point
                contacts = points
                emit(action, 0, points)
            }
            AapMessages.TOUCH_MOVE -> if (sameContacts) {
                contacts = points
                emit(action, 0, points)
            }
            AapMessages.TOUCH_POINTER_DOWN -> {
                if (points.size != 2 || index !in points.indices || contacts.size != 1 ||
                    points[1 - index].id != contacts.single().id) return
                contacts = points
                emit(action, index, points)
            }
            AapMessages.TOUCH_POINTER_UP -> {
                if (points.size != 2 || index !in points.indices || !sameContacts) return
                contacts = listOf(points[1 - index])
                if (firstDown?.id == points[index].id) firstDown = null
                emit(action, index, points)
            }
            AapMessages.TOUCH_UP -> if (points.size == 1 && sameContacts) {
                contacts = emptyList()
                firstDown = null
                emit(action, 0, points)
            }
        }
    }
}
