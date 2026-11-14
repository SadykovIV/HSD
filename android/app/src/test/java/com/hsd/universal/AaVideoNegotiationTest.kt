package com.hsd.universal

import aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import com.hsd.universal.aa.*
import com.hsd.universal.dex.EmbeddedDisplayPolicy
import com.hsd.universal.dex.VdmHostPolicy
import org.junit.Assert.*
import org.junit.Test

class AaVideoNegotiationTest {
    private fun config(id: Int) = VideoConfiguration.newBuilder().setCodecResolution(VideoCodecResolutionType.forNumber(id))
    private fun layout(id: Int) = AaVideoLayout.of(config(id).build(), 0, false)!!
    private val auto = DesktopVideoSelection(DesktopResolution.AUTO, 0)

    @Test fun qhdSafeAreaFollowsTheInstalledRenderersCapability() {
        val mode = config(4).setWidthMargin(256).setDensity(218).build()
        for ((code, declaresNative, expectedWidth) in listOf(
            Triple(335L, false, 1728), Triple(338L, false, 2304), Triple(350L, false, 1728),
            Triple(352L, false, 1728), Triple(353L, true, 2304), Triple(900L, false, 1728))) {
            val native = com.hsd.universal.dex.DesktopModePolicy.supportsNativeTiles(code, declaresNative)
            val layout = AaVideoLayout.of(mode, 0, !native)!!
            val height = if (native) 1440 else 1080
            assertEquals(expectedWidth to height, layout.desktopWidth to layout.desktopHeight)
            assertEquals(if (native) 218 else 164, layout.density)
            assertEquals(VideoViewport(128, 0, 2304, 1440), layout.content)
            assertEquals(expectedWidth - 1 to height - 1, layout.touch(2303, 1439, 2304, 1440, expectedWidth, height, false))
        }
    }

    @Test fun everyWireEnumKeepsItsAxesThroughEncoderParentTilesAndInput() {
        val sizes = listOf(800 to 480,1280 to 720,1920 to 1080,2560 to 1440,3840 to 2160,
            720 to 1280,1080 to 1920,1440 to 2560,2160 to 3840)
        assertEquals(sizes.size, VideoCodecResolutionType.values().size)
        sizes.forEachIndexed { i, (w,h) ->
            val l = layout(i+1)
            assertEquals(w to h, l.mode.width to l.mode.height)
            assertEquals(w to h, l.desktopWidth to l.desktopHeight)
            assertEquals(VideoViewport(0,0,w,h), l.content)
            assertEquals(30,l.fps)
            VdmHostPolicy.requireGeometry(w,h,l.density)
            EmbeddedDisplayPolicy.requireSize(w,h,l.density)
            EmbeddedDisplayPolicy.requirePixelBudget(0,w,h,fullscreen=true)
            assertEquals(0 to 0,l.touch(0,0,65535,65535,w,h,false))
            assertEquals(w-1 to h-1,l.touch(65534,65534,65535,65535,w,h,false))
        }
    }

    @Test fun skipsUnknownMissingOrEmptyGeometryAndNeverStartsExcludedIndex() {
        val missing = VideoConfiguration.getDefaultInstance()
        val unknown = VideoConfiguration.parseFrom(byteArrayOf(8,99))
        val bad = config(2).setHeightMargin(720).build()
        for (c in listOf(missing,unknown,bad)) assertNull(AaVideoLayout.of(c,0,false))
        val configs = listOf(missing, config(5).build(), config(6).build(), config(2).build())
        fun choose(indices: List<Int>, p: DesktopVideoSelection=auto) = AaVideoNegotiation.choose(configs,indices,p) {
            AaVideoLayout.of(it,0,false)?.mode?.takeIf { m -> m.aapId != 5 }
        }
        assertEquals(2,choose(emptyList()))
        assertEquals(3,choose(listOf(-1,8,1,3,2),auto))
        assertEquals(-1,choose(listOf(0,1,8)))
        assertEquals(2,choose(listOf(3,2),DesktopVideoSelection(DesktopResolution.R1080_1920,0)))
        assertEquals(-1,AaVideoNegotiation.choose(emptyList(),emptyList(),auto) { null })
    }

    @Test fun insetsReplaceTotalsAndInputTracksSafeAreaAndIndependentDigitizer() {
        val ui = UiConfig.newBuilder().setMargins(Insets.newBuilder().setLeft(110).setRight(70).setTop(20).setBottom(40))
            .setStableContentInsets(Insets.newBuilder().setLeft(60).setRight(16).setTop(40).setBottom(20))
        val c = config(2).setWidthMargin(48).setUiConfig(ui).setDensity(220)
        val l = AaVideoLayout.of(c.build(),0,false)!!
        assertEquals(VideoViewport(110,20,1100,660),l.visible)
        assertEquals(VideoViewport(170,60,1024,600),l.content)
        assertNull(l.touch(0,0,2200,1320,1024,600,false))
        assertEquals(0 to 0,l.touch(120,80,2200,1320,1024,600,false))
        assertEquals(1023 to 599,l.touch(2199,1319,2200,1320,1024,600,true))
        assertEquals(0 to 0,l.touch(60,40,1280,720,1024,600,false))
        assertEquals(220,l.density)
        val updated = AaVideoLayout.of(c.setUiConfig(ui.setContentInsets(Insets.getDefaultInstance())).build(),137,false)!!
        assertEquals(updated.visible,updated.content)
        assertEquals(137,updated.density)
    }

    @Test fun oddMarginsNonSquarePixelsAndOldCompanionUseExactCodedCanvas() {
        val c = config(2).setWidthMargin(255).setHeightMargin(119).build()
        val l = AaVideoLayout.of(c,0,false)!!
        assertEquals(VideoViewport(127,59,1025,601),l.visible)
        assertEquals(1026 to 602,l.desktopWidth to l.desktopHeight)
        val wide = AaVideoLayout.of(config(2).setPixelAspectRatioE4(15000).setFrameRate(VideoFrameRateType.VIDEO_FPS_60).build(),0,false)!!
        assertEquals(1920 to 720,wide.desktopWidth to wide.desktopHeight)
        assertEquals(60,wide.fps)
        val legacy = AaVideoLayout.of(config(9).build(),0,true)!!
        assertEquals(2160 to 3840,legacy.mode.width to legacy.mode.height)
        assertEquals(608 to 1080, legacy.desktopWidth to legacy.desktopHeight)
    }
}
