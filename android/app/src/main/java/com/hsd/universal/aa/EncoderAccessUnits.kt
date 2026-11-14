package com.hsd.universal.aa

import com.hsd.universal.AnnexB

/** Normalize a single MediaCodec encoder's output before sharing it with any transport. */
internal class EncoderAccessUnits(private val hevc: Boolean, private val onConfig: (ByteArray) -> Unit) {
    private val headerTypes = if (hevc) listOf(32, 33, 34) else listOf(7, 8)
    private val headers = mutableMapOf<Int, List<ByteArray>>()
    private var published: ByteArray? = null

    fun format(config: List<ByteArray>) {
        if (config.isEmpty()) return
        val combined = config.map(::annexB).reduce(AnnexB::glue)
        collectHeaders(combined, AnnexB.units(combined))
    }

    fun frame(bytes: ByteArray, configFlag: Boolean): ByteArray? {
        val data = annexB(bytes)
        val units = AnnexB.units(data)
        // Some encoders update parameter sets in a normal picture without the codec-config flag.
        if (units.any { it.type(data, hevc) in headerTypes }) collectHeaders(data, units)
        // Codec flags are a bitmask. A buffer carrying headers may also contain the first picture.
        if (configFlag && units.none { unit ->
            unit.type(data, hevc) in (if (hevc) 0..31 else 1..5)
        }) return null
        return AnnexB.normalize(data, units)
    }

    private fun collectHeaders(data: ByteArray, units: List<AnnexB.UnitRange>) {
        for ((type, group) in units.groupBy { it.type(data, hevc) }) {
            if (type in headerTypes) headers[type] = group.map { it.withStartCode(data) }
        }
        if (!headers.keys.containsAll(headerTypes)) return
        val complete = headerTypes.flatMap { headers.getValue(it) }.reduce(AnnexB::glue)
        if (published?.contentEquals(complete) == true) return
        published = complete
        onConfig(complete)
    }

    private fun annexB(bytes: ByteArray): ByteArray {
        // Annex B is Android's native representation. Other encoders use a 32-bit length per NAL.
        // Accept lengths only if every NAL has a valid header and they cover the whole buffer.
        if (bytes.size < 5 || (bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                bytes[2] == 0.toByte() && bytes[3] == 1.toByte())) return bytes
        var pos = 0
        while (pos < bytes.size) {
            if (bytes.size - pos < 5) return bytes
            val length = lengthAt(bytes, pos)
            if (length !in 1..(bytes.size - pos - 4)) return bytes
            val first = bytes[pos + 4].toInt()
            if (first < 0 || (if (hevc) length < 2 || bytes[pos + 5].toInt() and 7 == 0
                               else first and 31 !in 1..23)) return bytes
            pos += 4 + length
        }
        return bytes.copyOf().also { out ->
            var offset = 0
            while (offset < out.size) {
                val length = lengthAt(bytes, offset)
                out.fill(0, offset, offset + 3)
                out[offset + 3] = 1
                offset += 4 + length
            }
        }
    }

    private fun lengthAt(b: ByteArray, pos: Int) = (b[pos].toInt() shl 24) or
        ((b[pos + 1].toInt() and 255) shl 16) or ((b[pos + 2].toInt() and 255) shl 8) or
        (b[pos + 3].toInt() and 255)
}
