package com.hsd.universal.carplay

internal data class CarPlayAudioProfile(val stream: Long, val role: String, val bit: Long, val rate: Int,
    val aac: Boolean, val latencyMs: Long) {
    val framesPerPacket get() = if (aac) 1024 else 352
    companion object {
        fun candidates(info: Map<*, *>): List<CarPlayAudioProfile> {
            val formats = (info["audioFormats"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            val latencies = (info["audioLatencies"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            return buildList {
                for (role in listOf("media", "compatibility", "default")) {
                    for (stream in listOf(102L, 100L)) {
                        val matching = formats.filter { (it["type"] as? Number)?.toLong() == stream && it["audioType"] == role }
                        val mask = matching.fold(0L) { bits, f -> bits or ((f["audioOutputFormats"] as? Number)?.toLong() ?: 0L) }
                        val latency = latencies.firstOrNull { (it["type"] as? Number)?.toLong() == stream && it["audioType"] == role }
                        val ms = (latency?.get("outputLatencyMicros") as? Number)?.toLong()?.div(1000)?.takeIf { it in 1..5000 } ?: 250L
                        val bits = if (stream == 102L) listOf(23, 22, 15, 11) else listOf(15, 11, 23, 22)
                        for (n in bits) if (mask and (1L shl n) != 0L) {
                            add(CarPlayAudioProfile(stream, role, 1L shl n, if (n == 23 || n == 15) 48000 else 44100, n >= 22, ms))
                        }
                    }
                }
            }
        }
    }
}
