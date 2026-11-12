package com.hsd.universal

import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import com.hsd.universal.aa.DesktopResolution
import com.hsd.universal.aa.DesktopVideoSelection

internal object AaVideoNegotiation {
    fun choose(configs: List<VideoConfiguration>, offered: List<Int>, prefs: DesktopVideoSelection,
               supported: (VideoConfiguration) -> DesktopResolution?): Int {
        val indices = if (offered.isEmpty()) configs.indices.toList() else offered
        val wanted = prefs.initialGeometry()
        var best = -1
        var distance = Long.MAX_VALUE
        for (index in indices) {
            val config = configs.getOrNull(index) ?: continue
            val mode = supported(config) ?: continue
            if (prefs.resolution == DesktopResolution.AUTO) return index
            val dw = (mode.width - wanted.outW).toLong()
            val dh = (mode.height - wanted.outH).toLong()
            val score = dw * dw + dh * dh
            if (score < distance) {
                distance = score
                best = index
            }
        }
        return best
    }
}
