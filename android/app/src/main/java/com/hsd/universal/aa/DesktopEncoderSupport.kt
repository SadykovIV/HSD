package com.hsd.universal.aa

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/** Codec declarations are checked before allocating a desktop producer; no geometry/FPS fallback. */
object DesktopEncoderSupport {
    private const val MIME = "video/avc"

    internal data class Candidate(val name: String, val supports: (Int, Int, Int, Int) -> Boolean)

    internal fun select(candidates: List<Candidate>, width: Int, height: Int, fps: Int, bitrate: Int): String {
        DesktopGeometryPolicy.requireProjection(width, height)
        require(fps in 1..DesktopVideoQuality.MAX_FPS) { "Частота рабочего стола должна быть от 1 до 60 кадров/с" }
        require(bitrate > 0)
        return candidates.firstOrNull { runCatching { it.supports(width, height, fps, bitrate) }.getOrDefault(false) }?.name
            ?: throw IllegalArgumentException("Телефон не поддерживает выбранный кодек при ${width}×$height и $fps кадрах/с. Выберите меньший размер или частоту")
    }

    fun requireSupported(width: Int, height: Int, fps: Int) {
        encoderName(width, height, fps, DesktopVideoQuality.BITRATE_CEILING_BPS)
    }

    data class Encoding(val name: String, val minBitrate: Int, val maxBitrate: Int, val maxFps: Int = DesktopVideoQuality.MAX_FPS)

    /** The user's bitrate is a ceiling. A smaller codec maximum still permits the advertised mode. */
    fun projectionEncoder(width: Int, height: Int, fps: Int, ceiling: Int, mime: String): Encoding? {
        return surfaceEncoders(mime, ceiling)(width, height, fps)?.takeIf { it.maxFps >= fps }
    }

    private fun encodersFor(mime: String) = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
        .sortedByDescending { it.isHardwareAccelerated }

    /** Snapshot the available Surface encoders once for an entire negotiation. */
    internal fun surfaceEncoders(mime: String, ceiling: Int): (Int, Int, Int) -> Encoding? {
        val encoders = encodersFor(mime).mapNotNull { info ->
            runCatching {
                val caps = info.getCapabilitiesForType(mime)
                val video = caps.videoCapabilities
                if (video == null || MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats ||
                    video.bitrateRange.lower > ceiling) null
                else video to Encoding(info.name, video.bitrateRange.lower, minOf(ceiling, video.bitrateRange.upper))
            }.getOrNull()
        }
        return lookup@{ width, height, fps ->
            var best: Encoding? = null
            for ((caps, encoder) in encoders) {
                val rates = runCatching { caps.getSupportedFrameRatesFor(width, height) }.getOrNull() ?: continue
                val maxFps = minOf(rates.upper.toInt(), DesktopVideoQuality.MAX_FPS)
                val rate = minOf(fps, maxFps)
                if (rate <= 0 || rate < rates.lower) continue
                if (maxFps >= fps) return@lookup encoder.copy(maxFps = maxFps)
                if (best == null || best.maxFps < maxFps) best = encoder.copy(maxFps = maxFps)
            }
            best
        }
    }

    internal fun encoderName(width: Int, height: Int, fps: Int, bitrate: Int, mime: String = MIME): String = select(
        encodersFor(mime).map { info -> Candidate(info.name) { w, h, rate, bps -> supports(info, w, h, rate, bps, mime) } },
        width, height, fps, bitrate,
    )

    internal fun requireSupported(info: MediaCodecInfo, width: Int, height: Int, fps: Int, bitrate: Int, mime: String = MIME) {
        select(listOf(Candidate(info.name) { w, h, rate, bps -> supports(info, w, h, rate, bps, mime) }),
            width, height, fps, bitrate)
    }

    private fun supports(info: MediaCodecInfo, width: Int, height: Int, fps: Int, bitrate: Int, mime: String): Boolean {
        val capabilities = info.getCapabilitiesForType(mime)
        if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in capabilities.colorFormats) return false
        val video = capabilities.videoCapabilities ?: return false
        return video.areSizeAndRateSupported(width, height, fps.toDouble()) && video.bitrateRange.contains(bitrate)
    }
}
