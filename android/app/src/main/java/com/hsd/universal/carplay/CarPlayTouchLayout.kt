package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.input.TouchReports.Contact

/** Compile absolute digitizer reports from HID short items. Offsets include padding and fields
 * belonging to other collections; each report ID has its own layout. Never reinterpret key reports. */
internal class CarPlayTouchLayout private constructor(private val reports: Map<Int, Report>) {
    private data class Field(val offset: Int, val bits: Int, val min: Long, val max: Long) {
        fun read(data: ByteArray, start: Int): Long {
            var n = 0L
            for (i in 0 until bits) {
                val bit = offset + i
                n = n or (((data[start + bit / 8].toInt() ushr (bit % 8)) and 1).toLong() shl i)
            }
            return if (min < 0 && n and (1L shl (bits - 1)) != 0L) n - (1L shl bits) else n
        }
        fun pixel(value: Long, dimension: Int): Int {
            val span = max - min
            return (((value.coerceIn(min, max) - min) * (dimension - 1) + span / 2) / span).toInt()
        }
    }
    private class Finger(var x: Field? = null, var y: Field? = null, var tip: Field? = null, var id: Field? = null)
    private class Report(var bits: Int = 0, val fingers: LinkedHashMap<Int, Finger> = linkedMapOf())
    private data class Globals(var page: Int = 0, var min: Long = 0, var max: Long = 0,
        var size: Int = 0, var count: Int = 0, var report: Int = 0)
    private data class Scope(val touch: Boolean, val group: Int)

    fun contacts(data: ByteArray, width: Int, height: Int): List<Contact>? {
        if (data.isEmpty() || width <= 0 || height <= 0) return null
        val start = if (0 in reports) 0 else 1
        val r = reports[if (start == 0) 0 else data[0].toInt() and 255] ?: return null
        if (r.bits > (data.size - start) * 8) return null
        return r.fingers.values.mapIndexed { index, finger ->
            val x = finger.x!!; val y = finger.y!!
            // Parallel reports have fixed independent slots. Serial reports identify the contact.
            val slot = if (r.fingers.size == 1) finger.id?.read(data, start)?.toInt() ?: 0 else index
            Contact(slot, x.pixel(x.read(data, start), width), y.pixel(y.read(data, start), height),
                finger.tip!!.read(data, start) != 0L)
        }
    }

    companion object {
        fun parse(bytes: ByteArray): CarPlayTouchLayout? {
            if (bytes.size !in 1..16384) return null
            var g = Globals()
            val globals = ArrayDeque<Globals>(); val scopes = ArrayDeque<Scope>()
            val reports = mutableMapOf<Int, Report>()
            val usages = mutableListOf<Int>(); var usageMin: Int? = null; var usageMax: Int? = null
            var pos = 0; var group = 0
            while (pos < bytes.size) {
                val prefix = bytes[pos++].toInt() and 255
                if (prefix == 254) {
                    if (pos + 2 > bytes.size) return null
                    val n = bytes[pos].toInt() and 255; pos += 2 + n
                    if (pos > bytes.size) return null
                    continue
                }
                val length = (prefix and 3).let { if (it == 3) 4 else it }
                if (pos + length > bytes.size) return null
                var value = 0L
                repeat(length) { value = value or ((bytes[pos++].toLong() and 255) shl (8 * it)) }
                fun signed() = if (length > 0 && value and (1L shl (8 * length - 1)) != 0L) value - (1L shl (8 * length)) else value
                val tag = prefix ushr 4
                fun usage() = if (length == 4) value.toInt() else (g.page shl 16) or value.toInt()
                when ((prefix ushr 2) and 3) {
                    1 -> when (tag) {
                        0 -> g.page = value.toInt()
                        1 -> g.min = signed()
                        2 -> g.max = if (g.min < 0) signed() else value
                        7 -> { if (value > 64) return null; g.size = value.toInt() }
                        8 -> { if (value !in 1..255) return null; g.report = value.toInt() }
                        9 -> { if (value > 4096) return null; g.count = value.toInt() }
                        10 -> globals.addLast(g.copy())
                        11 -> g = globals.removeLastOrNull() ?: return null
                    }
                    2 -> when (tag) { 0 -> usages.add(usage()); 1 -> usageMin = usage(); 2 -> usageMax = usage() }
                    0 -> {
                        val parent = scopes.lastOrNull() ?: Scope(false, 0)
                        when (tag) {
                            10 -> {
                                val u = usages.firstOrNull() ?: usageMin
                                val touch = parent.touch || u == 0x0d0004 || u == 0x0d0005
                                scopes.addLast(Scope(touch, if (touch && (u == 0x0d0022 || !parent.touch)) ++group else parent.group))
                            }
                            12 -> if (scopes.removeLastOrNull() == null) return null
                            8 -> {
                                val r = reports.getOrPut(g.report) { Report() }
                                if (r.bits + g.size * g.count > 65536) return null
                                if (parent.touch && value and 7L == 2L && g.size in 1..32) {
                                    val finger = r.fingers.getOrPut(parent.group) { Finger() }
                                    repeat(g.count) { i ->
                                        val u = usages.getOrNull(i) ?: usageMin?.let { (it + i).coerceAtMost(usageMax ?: it) } ?: usages.lastOrNull()
                                        val field = Field(r.bits + i * g.size, g.size, g.min, g.max)
                                        when (u) {
                                            0x010030 -> if (g.max > g.min) finger.x = field
                                            0x010031 -> if (g.max > g.min) finger.y = field
                                            0x0d0042 -> finger.tip = field
                                            0x0d0033 -> if (finger.tip == null) finger.tip = field
                                            0x0d0051 -> finger.id = field
                                        }
                                    }
                                }
                                r.bits += g.size * g.count
                            }
                        }
                        usages.clear(); usageMin = null; usageMax = null
                    }
                }
            }
            if (scopes.isNotEmpty() || globals.isNotEmpty()) return null
            reports.values.forEach { r -> r.fingers.entries.removeAll { it.value.x == null || it.value.y == null || it.value.tip == null } }
            val touch = reports.filterValues { it.fingers.isNotEmpty() }
            return if (touch.isEmpty()) null else CarPlayTouchLayout(touch)
        }
    }
}
