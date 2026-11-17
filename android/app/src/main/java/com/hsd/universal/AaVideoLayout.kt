package com.hsd.universal

import aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import com.hsd.universal.aa.DesktopGeometryPolicy
import com.hsd.universal.aa.DesktopResolution
import com.hsd.universal.aa.VideoViewport
import kotlin.math.roundToInt

/** AAP distinguishes coded pixels, the visible panel, the safe UI area and physical pixel aspect. */
internal data class AaVideoLayout(
    val mode: DesktopResolution, val fps: Int, val visible: VideoViewport, val content: VideoViewport,
    val desktopWidth: Int, val desktopHeight: Int, val density: Int,
) {
    fun touch(x: Int, y: Int, panelWidth: Int, panelHeight: Int, targetWidth: Int, targetHeight: Int,
              clamp: Boolean): Pair<Int, Int>? {
        // Panels expressed in video pixels report offsets from the visible origin. Separate
        // absolute digitizers use their own declared coordinate range (often 32767 per axis).
        val videoPixels = panelWidth == mode.width && panelHeight == mode.height
        fun axis(raw: Int, declared: Int, span: Int, inset: Int, contentSpan: Int, target: Int): Int? {
            val extent = if (videoPixels || declared < 2) span else declared
            val pixel = raw.coerceIn(0, extent - 1).toDouble() * (span - 1) / (extent - 1).coerceAtLeast(1) - inset
            if (!clamp && (pixel < -0.5 || pixel >= contentSpan - 0.5)) return null
            return (pixel.coerceIn(0.0, contentSpan - 1.0) * (target - 1) / (contentSpan - 1).coerceAtLeast(1)).roundToInt()
        }
        val tx = axis(x, panelWidth, visible.width, content.left - visible.left, content.width, targetWidth) ?: return null
        val ty = axis(y, panelHeight, visible.height, content.top - visible.top, content.height, targetHeight) ?: return null
        return tx to ty
    }

    companion object {
        private val modes = DesktopResolution.aapModes.associateBy { it.aapId }

        fun of(config: VideoConfiguration, overrideDpi: Int, legacyTiles: Boolean): AaVideoLayout? {
            if (!config.hasCodecResolution()) return null
            val mode = modes[config.codecResolution.number] ?: return null
            val frame = VideoViewport(0, 0, mode.width, mode.height)
            val ui = config.uiConfig
            val visible = if (ui.hasMargins()) inset(frame, ui.margins) else {
                val dx = config.widthMargin.coerceAtLeast(0)
                val dy = config.heightMargin.coerceAtLeast(0)
                VideoViewport(dx / 2, dy / 2, frame.width - dx, frame.height - dy)
            }
            if (!visible.fits(mode.width, mode.height)) return null
            val content = when {
                ui.hasContentInsets() -> inset(visible, ui.contentInsets)
                ui.hasStableContentInsets() -> inset(visible, ui.stableContentInsets)
                else -> visible
            }
            if (!content.fits(mode.width, mode.height)) return null
            val par = if (config.pixelAspectRatioE4 > 0) config.pixelAspectRatioE4 / 10000.0 else 1.0
            val w = content.width * par
            val h = content.height.toDouble()
            val maxW = if (legacyTiles) 1920 else if (w >= h) 3840 else 2160
            val maxH = if (legacyTiles) 1080 else if (w >= h) 2160 else 3840
            val largest = minOf(maxW / w, maxH / h)
            val smallest = maxOf(240 / minOf(w, h), 320 / maxOf(w, h))
            if (smallest > largest) return null
            val scale = 1.0.coerceIn(smallest, largest)
            fun even(px: Double) = (px * scale / 2).roundToInt() * 2
            val desktopW = even(w)
            val desktopH = even(h)
            if (!DesktopGeometryPolicy.acceptsProjection(desktopW, desktopH)) return null
            // The receiver's density describes its native AA UI. A projected Android desktop
            // uses HSD's logical scale, just like CarLife/CarPlay. In particular a 1080p
            // coded frame with a 720px content area must not inflate all app/map symbols.
            val automatic = DesktopGeometryPolicy.density(desktopW, desktopH)
            val dpi = if (overrideDpi > 0) overrideDpi else automatic.coerceIn(96, 640)
            val fps = if (config.hasFrameRate() && config.frameRate.number == 1) 60 else 30
            return AaVideoLayout(mode, fps, visible, content, desktopW, desktopH, dpi)
        }

        private fun inset(rect: VideoViewport, sides: Insets): VideoViewport {
            // uint32 sentinels appear as negative Ints. Bound arithmetic even for malformed peers.
            val left = sides.left.coerceIn(0, rect.width)
            val top = sides.top.coerceIn(0, rect.height)
            val right = sides.right.coerceIn(0, rect.width)
            val bottom = sides.bottom.coerceIn(0, rect.height)
            return VideoViewport(rect.left + left, rect.top + top, rect.width - left - right, rect.height - top - bottom)
        }
    }
}
