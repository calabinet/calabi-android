package net.calabi.app

import net.calabi.app.rdp.RdpPointerMath
import net.calabi.app.rdp.RdpQuality
import net.calabi.app.rdp.RdpScale
import net.calabi.app.rdp.RdpViewportMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpUxLogicTest {
    @Test fun scaleResolvesComfortablyForPhoneDensities() {
        assertEquals(100, RdpScale.AUTO.resolve(3.0f, RdpQuality.ECONOMY, 2340))
        assertEquals(125, RdpScale.AUTO.resolve(3.0f, RdpQuality.BALANCED, 2340))
        assertEquals(150, RdpScale.AUTO.resolve(3.0f, RdpQuality.DETAIL, 2340))
        assertEquals(200, RdpScale.AUTO.resolve(3.0f, RdpQuality.ADAPTIVE, 2340))
        assertEquals(150, RdpScale.AUTO.resolve(2.0f, RdpQuality.ADAPTIVE, 1600))
        assertEquals(175, RdpScale.clampDesktopScale(175))
        assertEquals(100, RdpScale.clampDesktopScale(50))
        assertEquals(500, RdpScale.clampDesktopScale(900))
        assertEquals(150, RdpScale.SCALE_150.resolve(1.0f, RdpQuality.ECONOMY, 800))
    }

    @Test fun deviceScaleFactorMapsToValidRdpValues() {
        assertEquals(100, RdpScale.deviceScaleFactor(100))
        assertEquals(140, RdpScale.deviceScaleFactor(125))
        assertEquals(140, RdpScale.deviceScaleFactor(150))
        assertEquals(180, RdpScale.deviceScaleFactor(175))
        assertEquals(180, RdpScale.deviceScaleFactor(200))
    }

    @Test fun trackpadAccelerationSlowsPrecisionAndBoostsFlicks() {
        val slow = RdpPointerMath.trackpadGain(1f, 0f, 16L, 3.0f)
        val medium = RdpPointerMath.trackpadGain(24f, 0f, 16L, 3.0f)
        val fast = RdpPointerMath.trackpadGain(120f, 0f, 16L, 3.0f)
        assertTrue("Expected slow gain < 1.0, got $slow", slow in 0.65f..0.95f)
        assertTrue("Expected medium gain > slow, got $medium vs $slow", medium > slow)
        assertTrue("Expected fast gain > 1.8 and <= 2.4, got $fast", fast > 1.8f && fast <= 2.4f)
    }

    @Test fun flingDecayIsMonotonicAndBounded() {
        val step16 = RdpPointerMath.flingDecay(16L)
        val step32 = RdpPointerMath.flingDecay(32L)
        assertTrue(step16 in 0.85f..0.98f)
        assertTrue(step32 < step16 && step32 > 0.7f)
    }

    @Test fun imeTargetZoomPreservesReadableScaleWhenHeightShrinks() {
        // 1920x1080 remote on 2340x1080 landscape -> fit=1.0; IME shrinks height to 420 -> fit=420/1080=0.3889.
        val zoom = RdpViewportMath.imeTargetZoom(
            previousPixelScale = 1.0f,
            currentFit = 420f / 1080f,
            currentZoom = 1.0f,
            originalSize = false,
        )
        assertEquals(1080f / 420f, zoom, 0.01f)
        assertEquals(1.0f, (420f / 1080f) * zoom, 0.01f)
        // Original 1:1 mode keeps its current zoom unchanged.
        assertEquals(1.0f, RdpViewportMath.imeTargetZoom(1.0f, 1.0f, 1.0f, originalSize = true), 0.001f)
    }

    @Test fun focusPanKeepsTargetAtDesiredAnchorWithinBounds() {
        // Remote height 1080 at scale 1.0 inside 420px IME-shrunk view -> maxPan = (1080 - 420)/2 = 330.
        val panNearBottom = RdpViewportMath.focusPan(
            remoteCoord = 950f,
            remoteLength = 1080,
            viewLength = 420,
            pixelScale = 1.0f,
            anchorFraction = 0.62f,
        )
        assertEquals(-330f, panNearBottom, 0.01f)
        val panMiddle = RdpViewportMath.focusPan(
            remoteCoord = 540f,
            remoteLength = 1080,
            viewLength = 420,
            pixelScale = 1.0f,
            anchorFraction = 0.5f,
        )
        assertEquals(0f, panMiddle, 0.01f)
    }
}
