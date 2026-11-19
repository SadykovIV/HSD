package com.hsd.universal.carplay

internal data class CarPlayAudioProfile(val stream: Long, val role: String, val bit: Long, val rate: Int,
    val aac: Boolean, val latencyMs: Long, val channels: Int = 2, val aacProfile: Int = 2) {
    val framesPerPacket get() = if (!aac) 352 else if (aacProfile == 39) 480 else 1024
    companion object {
        fun candidates(info: Map<*, *>, navigation: Boolean = false): List<CarPlayAudioProfile> {
            val formats = (info["audioFormats"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            val latencies = (info["audioLatencies"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            return buildList {
                for (role in if (navigation) listOf("default", "alert") else listOf("media", "compatibility", "default")) {
                    for (stream in if (navigation) listOf(101L) else listOf(102L, 100L)) {
                        val matching = formats.filter { (it["type"] as? Number)?.toLong() == stream && it["audioType"] == role }
                        val mask = matching.fold(0L) { bits, f -> bits or ((f["audioOutputFormats"] as? Number)?.toLong() ?: 0L) }
                        val latency = latencies.firstOrNull { (it["type"] as? Number)?.toLong() == stream && it["audioType"] == role }
                        val ms = (latency?.get("outputLatencyMicros") as? Number)?.toLong()?.div(1000)?.takeIf { it in 1..5000 } ?: 250L
                        val bits = if (navigation) listOf(14, 15, 10, 11, 6, 7, 4, 5, 23, 22, 25, 24, 27, 26, 32, 31)
                            else if (stream == 102L) listOf(23, 22, 15, 11) else listOf(15, 11, 23, 22)
                        for (n in bits) if (mask and (1L shl n) != 0L) {
                            val rate = when (n) {
                                4, 5, 26 -> 16000
                                6, 7, 27 -> 24000
                                10, 11, 22, 24, 31 -> 44100
                                else -> 48000
                            }
                            val channels = if (n in listOf(4, 6, 10, 14, 26, 27, 31, 32)) 1 else 2
                            add(CarPlayAudioProfile(stream, role, 1L shl n, rate, n >= 22, ms, channels,
                                if (n >= 24) 39 else 2))
                        }
                    }
                }
            }
        }
    }
}
