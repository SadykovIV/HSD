package com.hsd.universal.aa

import com.hsd.universal.DesktopTransport

import kotlin.math.roundToInt

/** Limits apply only to a parent desktop; child map buffers have their own larger budget. */
object DesktopGeometryPolicy {
    const val MIN_WIDTH = 640
    const val MAX_WIDTH = 1920
    const val MIN_HEIGHT = 480
    const val MAX_HEIGHT = 1080
    const val MIN_DENSITY = 96
    const val MAX_DENSITY = 640
    fun accepts(width: Int, height: Int): Boolean =
        width in MIN_WIDTH..MAX_WIDTH && height in MIN_HEIGHT..MAX_HEIGHT &&
            width >= height && width % 2 == 0 && height % 2 == 0
    /** Negotiated projection uses the head unit's axes. Manual CarLife settings retain their own limits. */
    fun acceptsProjection(width: Int, height: Int) = minOf(width, height) in 240..2160 &&
        maxOf(width, height) in 320..3840 && width % 2 == 0 && height % 2 == 0

    fun acceptsSetting(width: Int, height: Int) = if (DesktopTransport.universal) acceptsProjection(width, height) else accepts(width, height)

    fun requireProjection(width: Int, height: Int) {
        if (com.hsd.universal.DesktopTransport.universal) require(acceptsProjection(width, height)) {
            "Размер проекции не поддерживается: ${width}×${height}"
        } else requireDimensions(width, height)
    }

    fun requireDimensions(width: Int, height: Int) {
        require(accepts(width, height)) {
            "Нужен горизонтальный размер ${MIN_WIDTH}–${MAX_WIDTH} × ${MIN_HEIGHT}–${MAX_HEIGHT} с чётными сторонами"
        }
    }
    fun requireDensity(density: Int) {
        require(density in MIN_DENSITY..MAX_DENSITY) { "Плотность рабочего стола должна быть от $MIN_DENSITY до $MAX_DENSITY DPI" }
    }
    // Retain the previous automatic scale for existing 480p/720p/900p profiles.
    fun density(height: Int): Int = (160.0 * height / 900).roundToInt().coerceIn(120, MAX_DENSITY)
}

enum class DesktopResolution(val title: String, val width: Int, val height: Int, val aapId: Int = 0) {
    AUTO("Авто — как просит магнитола", 1280, 720),
    R1024_768("1024 × 768", 1024, 768),
    R1280_720("1280 × 720", 1280, 720, 2),
    R1600_900("1600 × 900", 1600, 900),
    CUSTOM("Своё разрешение", 1280, 720),
    R800_480("800 × 480", 800, 480, 1),
    R1920_1080("1920 × 1080", 1920, 1080, 3),
    R2560_1440("2560 × 1440", 2560, 1440, 4),
    R3840_2160("3840 × 2160", 3840, 2160, 5),
    R720_1280("720 × 1280", 720, 1280, 6),
    R1080_1920("1080 × 1920", 1080, 1920, 7),
    R1440_2560("1440 × 2560", 1440, 2560, 8),
    R2160_3840("2160 × 3840", 2160, 3840, 9);
    companion object {
        val DEFAULT = R1280_720
        val aapModes = entries.filter { it.aapId > 0 }.sortedBy { it.aapId }
        fun choices(androidAuto: Boolean) = if (androidAuto)
            listOf(AUTO) + aapModes + CUSTOM
        else listOf(AUTO, R1024_768, R1280_720, R1600_900, CUSTOM)
        fun restore(value: String?): DesktopResolution = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/** Raw Surface, encoded pixels and touch space have identical dimensions. */
data class NativeDesktopGeometry(override val outW: Int, override val outH: Int) : VideoGeometry {
    init { DesktopGeometryPolicy.requireProjection(outW, outH) }
    override val aaW = outW
    override val aaH = outH
    override val aaResolution = "${outW}x$outH"
    override val marginW = 0
    override val marginH = 0
    override val density = DesktopGeometryPolicy.density(outH)
    override val bitrate = DesktopVideoQuality.BITRATE_CEILING_BPS
    override val label = "Desktop ${outW}x$outH"
    override fun describe(densityOverride: Int): String =
        "Desktop: Surface ${outW}x$outH -> ${DesktopTransport.name} ${outW}x$outH, dpi ${densityFor(densityOverride)}, без кропа и масштаба"
}

/** Immutable preference snapshot; first INIT (or START fallback) locks one transport session. */
class DesktopVideoSelection(val resolution: DesktopResolution, val densityOverride: Int,
    val customWidth: Int = DesktopResolution.DEFAULT.width, val customHeight: Int = DesktopResolution.DEFAULT.height) {
    init {
        if (densityOverride != 0) DesktopGeometryPolicy.requireDensity(densityOverride)
        if (resolution == DesktopResolution.CUSTOM) DesktopGeometryPolicy.requireProjection(customWidth, customHeight)
    }
    private var selected: NativeDesktopGeometry? = null
    fun initialGeometry() = if (resolution == DesktopResolution.CUSTOM) NativeDesktopGeometry(customWidth, customHeight)
        else NativeDesktopGeometry(resolution.width, resolution.height)
    fun resolve(width: Int = 0, height: Int = 0): NativeDesktopGeometry = checkNotNull(resolve(width, height) { w, h ->
        if (DesktopGeometryPolicy.accepts(w, h)) NativeDesktopGeometry(w, h) else initialGeometry()
    })

    @Synchronized fun resolve(width: Int, height: Int, automatic: (Int, Int) -> NativeDesktopGeometry?): NativeDesktopGeometry? =
        selected ?: (if (resolution == DesktopResolution.AUTO) automatic(width, height) else initialGeometry())?.also { selected = it }
    // The legacy ADB path uses the AA R720 geometry, whose AA preset density is not our desktop AUTO scale.
    fun densityFor(mode: VideoGeometry): Int = if (densityOverride == 0) DesktopGeometryPolicy.density(mode.outH) else densityOverride
}


/** CarLife returns the actual size in INIT_DONE. Keep the HU's axes and aspect ratio when reducing it. */
internal fun carLifeGeometry(width: Int, height: Int, supported: (Int, Int) -> Boolean): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val long = maxOf(width, height)
    val short = minOf(width, height)
    var edge = minOf(long.toLong(), 3840L, 2160L * long / short).toInt() and -2
    while (edge >= 320) {
        val side = (edge.toDouble() * short / long / 2).roundToInt() * 2
        if (side < 240) break
        val w = if (width >= height) edge else side
        val h = if (width >= height) side else edge
        if (DesktopGeometryPolicy.acceptsProjection(w, h) && supported(w, h)) return w to h
        edge = (edge - 1) and -2
    }
    return null
}
