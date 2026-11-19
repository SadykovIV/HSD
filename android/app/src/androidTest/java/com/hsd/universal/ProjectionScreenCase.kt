package com.hsd.universal

import aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import kotlin.math.roundToInt

/** Receiver fixtures have independent expectations for START, desktop pixels and digitizer coordinates. */
internal class ProjectionScreenCase(
    val modes: List<VideoConfiguration>, val indices: List<Int>, val index: Int,
    val desktopW: Int, val desktopH: Int, val panelW: Int = desktopW, val panelH: Int = desktopH,
    val visibleW: Int = panelW, val visibleH: Int = panelH, val leftInset: Int = 0, val topInset: Int = 0,
    val contentW: Int = desktopW, val contentH: Int = desktopH, val update: Boolean = false,
) {
    fun point(x: Int, y: Int): Pair<Int, Int> {
        val px = leftInset + x.toDouble() * (contentW - 1) / (desktopW - 1)
        val py = topInset + y.toDouble() * (contentH - 1) / (desktopH - 1)
        return (px * (panelW - 1) / (visibleW - 1)).roundToInt() to
               (py * (panelH - 1) / (visibleH - 1)).roundToInt()
    }

    companion object {
        private val sizes = listOf(800 to 480, 1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160,
            720 to 1280, 1080 to 1920, 1440 to 2560, 2160 to 3840)
        private fun config(id: Int, fps: Int = 30) = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoCodecResolutionType.forNumber(id)).setDensity(160)
            .setFrameRate(if (fps == 60) VideoFrameRateType.VIDEO_FPS_60 else VideoFrameRateType.VIDEO_FPS_30).build()

        val newUi: UiConfig = UiConfig.newBuilder().setContentInsets(Insets.newBuilder().setLeft(40).setBottom(40)).build()

        fun named(name: String): ProjectionScreenCase {
            val number = name.substringBefore('-').toIntOrNull()
            if (number != null) {
                val (w,h) = sizes[number-1]
                return when (name.substringAfter('-', "native")) {
                    "native" -> ProjectionScreenCase(listOf(config(number)), listOf(0), 0, w, h)
                    "60" -> ProjectionScreenCase(listOf(config(number,60)), listOf(0), 0, w, h)
                    "unavailable" -> ProjectionScreenCase(listOf(config(number)), listOf(0), -1, 0, 0, w, h)
                    "fallback" -> {
                        val id = if (w > h) 3 else 7
                        val (fw,fh) = sizes[id-1]
                        ProjectionScreenCase(listOf(config(number), config(id)), listOf(0,1), 1, fw, fh)
                    }
                    else -> error("Unknown receiver fixture $name")
                }
            }
            val hd = config(2)
            return when (name) {
                "qhd-margins" -> ProjectionScreenCase(
                    listOf(config(4, 60).toBuilder().setWidthMargin(256).setDensity(218).build()), listOf(0), 0, 2304, 1440)
                "hd-margins-60" -> ProjectionScreenCase(
                    listOf(config(2, 60).toBuilder().setHeightMargin(32).setDensity(229).build(), config(1, 60)),
                    listOf(0, 1), 0, 1280, 688)
                "fhd-margins-60" -> ProjectionScreenCase(
                    listOf(config(3, 60).toBuilder().setHeightMargin(90).setDensity(176).build(),
                        config(2, 60), config(1, 60)), listOf(0, 1, 2), 0, 1920, 990)
                "subset" -> ProjectionScreenCase(listOf(config(1), config(6), hd), listOf(1), 1, 720, 1280)
                "fps" -> ProjectionScreenCase(listOf(config(3,60), config(3)), listOf(0,1), 1, 1920, 1080)
                "invalid" -> ProjectionScreenCase(listOf(hd), listOf(9), -1, 0, 0, 1280, 720)
                "wide" -> ProjectionScreenCase(listOf(hd.toBuilder().setWidthMargin(256).setHeightMargin(120).build()),
                    listOf(0), 0, 1024, 600, 1024, 600)
                "par" -> ProjectionScreenCase(listOf(hd.toBuilder().setPixelAspectRatioE4(15000).build()),
                    listOf(0), 0, 1920, 720, 1280, 720, contentW=1280)
                "insets" -> {
                    val ui = UiConfig.newBuilder().setMargins(Insets.newBuilder().setLeft(100).setTop(20).setRight(80).setBottom(40))
                        .setContentInsets(Insets.newBuilder().setLeft(60).setTop(40).setRight(16).setBottom(20))
                    ProjectionScreenCase(listOf(hd.toBuilder().setWidthMargin(56).setUiConfig(ui).build()),
                        listOf(0), 0, 1024, 600, 2200, 1320, 1100, 660, 60, 40, update=true)
                }
                else -> error("Unknown receiver fixture $name")
            }
        }
    }
}
