package com.hsd.universal.aa

import org.junit.Assert.*
import org.junit.Test

class DesktopVideoSelectionTest {
    @Test fun carLifeHonoursPortraitAndWideOffersAndEncoderLimits() {
        for ((w, h) in listOf(720 to 1280, 1920 to 720, 1080 to 1920, 1024 to 600)) {
            assertEquals(w to h, carLifeGeometry(w, h) { _, _ -> true })
        }
        assertEquals(1152 to 2048, carLifeGeometry(2160, 3840) { w, h -> w <= 2048 && h <= 2048 })
        assertNull(carLifeGeometry(800, 480) { _, _ -> false })
        assertNull(carLifeGeometry(0, 720) { _, _ -> true })
    }

    @Test fun unavailableAutoOfferDoesNotLockAnUnrelatedFallback() {
        val selection = DesktopVideoSelection(DesktopResolution.AUTO, 0)
        assertNull(selection.resolve(720, 1280) { _, _ -> null })
        val compatible = selection.resolve(1024, 600) { w, h -> NativeDesktopGeometry(w, h) }
        assertEquals(1024 to 600, compatible?.let { it.outW to it.outH })
        assertSame(compatible, selection.resolve(1920, 720) { _, _ -> error("Already negotiated") })
    }

    @Test fun autoLocksHuNativeGeometryUntilANewTransportSession() {
        val first = DesktopVideoSelection(DesktopResolution.AUTO, 0)
        val mode = first.resolve(1024, 768)
        assertEquals(1024 to 768, mode.outW to mode.outH)
        assertSame(mode, first.resolve(1600, 900)) // repeated INIT / software reconnect
        assertEquals(137, first.densityFor(mode))
        val next = DesktopVideoSelection(DesktopResolution.AUTO, 0).resolve(1280, 720)
        assertEquals(1280 to 720, next.outW to next.outH)
    }
    @Test fun invalidOrMissingInitLocksSafe720FallbackEvenIfValidInitArrivesLater() {
        for ((w, h) in listOf(0 to 0, -2 to 720, 2560 to 1440, 1281 to 720, 720 to 1280, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val selection = DesktopVideoSelection(DesktopResolution.AUTO, 0)
            val fallback = selection.resolve(w, h)
            assertEquals(1280 to 720, fallback.outW to fallback.outH)
            assertSame(fallback, selection.resolve(1024, 768))
        }
    }
    @Test fun fixedPresetsIgnoreHuRequestsAndRemainNativeWithoutCroppingOrTouchScaling() {
        for (preset in DesktopResolution.choices(com.hsd.universal.DesktopTransport.universal).filter { it != DesktopResolution.AUTO && it != DesktopResolution.CUSTOM }) {
            val selection = DesktopVideoSelection(preset, 200)
            val mode = selection.resolve(800, 480)
            assertEquals(preset.width to preset.height, mode.outW to mode.outH)
            assertEquals(mode.outW to mode.outH, mode.aaW to mode.aaH)
            assertEquals(mode.outW - 1 to mode.outH - 1, mode.mapTouch(mode.outW - 1, mode.outH - 1))
            assertTrue(mode.crop.isNone)
            assertTrue(DesktopVideoGeometry.matchesNative(mode))
            assertEquals(200, selection.densityFor(mode))
        }
    }
    @Test fun customFullHdSnapshotPreservesEveryPixelAndRequestedDensity() {
        val selection = DesktopVideoSelection(DesktopResolution.CUSTOM, 640, 1920, 1080)
        val mode = selection.resolve(1280, 720)
        assertEquals(1920 to 1080, mode.outW to mode.outH)
        assertEquals(mode.outW to mode.outH, mode.aaW to mode.aaH)
        assertEquals(1919 to 1079, mode.mapTouch(1919, 1079))
        assertEquals(640, selection.densityFor(mode))
        assertSame(mode, selection.resolve(800, 480))
    }
    @Test fun invalidCustomInputsAreRejectedRatherThanRoundedOrClamped() {
        for ((w, h) in listOf(1921 to 1080, 1920 to 1081, 639 to 480, 640 to 479, 800 to (if (com.hsd.universal.DesktopTransport.universal) 4000 else 900))) {
            assertThrows(IllegalArgumentException::class.java) { DesktopVideoSelection(DesktopResolution.CUSTOM, 160, w, h) }
        }
        for (dpi in listOf(-1, 95, 641, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { DesktopVideoSelection(DesktopResolution.CUSTOM, dpi, 1920, 1080) }
        }
    }
    @Test fun proportionalDpiAndBoundedParentPolicyDoNotAdoptLargeChildBuffers() {
        assertEquals(128, DesktopGeometryPolicy.density(720))
        assertEquals(137, DesktopGeometryPolicy.density(768))
        assertEquals(160, DesktopGeometryPolicy.density(900))
        assertTrue(DesktopGeometryPolicy.accepts(640, 480))
        assertTrue(DesktopGeometryPolicy.accepts(1600, 900))
        assertTrue(DesktopGeometryPolicy.accepts(1920, 1080))
        assertFalse(DesktopGeometryPolicy.accepts(3200, 1800))
        assertFalse(DesktopGeometryPolicy.accepts(1023, 768))
        assertFalse(DesktopGeometryPolicy.accepts(800, 902))
        assertFalse(DesktopGeometryPolicy.accepts(480, 640))
        assertEquals(DesktopResolution.R1280_720, DesktopResolution.restore(null))
        assertEquals(DesktopResolution.R1280_720, DesktopResolution.restore("corrupt"))
    }
}
