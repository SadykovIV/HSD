package com.hsd.universal

/** Read AudioSpecificConfig rather than assume every Android ELD encoder emits 480 samples. */
internal object AacFrameFormat {
    fun samples(csd: ByteArray): Int {
        var position = 0
        fun bits(n: Int): Int {
            require(position + n <= csd.size * 8) { "Truncated AAC AudioSpecificConfig" }
            var value = 0
            repeat(n) { value = (value shl 1) or ((csd[position / 8].toInt() ushr (7 - position % 8)) and 1); position++ }
            return value
        }
        var objectType = bits(5)
        if (objectType == 31) objectType = 32 + bits(6)
        if (bits(4) == 15) bits(24)
        bits(4) // channelConfiguration
        val shortFrame = bits(1) == 1
        return when (objectType) {
            2 -> if (shortFrame) 960 else 1024
            39 -> if (shortFrame) 480 else 512
            else -> error("Unsupported AAC object type $objectType")
        }
    }
}
