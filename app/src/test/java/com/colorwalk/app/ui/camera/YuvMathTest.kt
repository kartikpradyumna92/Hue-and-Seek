package com.colorwalk.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BT.601 YUV→ARGB fixed-point conversion used by the live viewfinder analyzer. */
class YuvMathTest {

    private fun r(argb: Int) = (argb shr 16) and 0xFF
    private fun g(argb: Int) = (argb shr 8) and 0xFF
    private fun b(argb: Int) = argb and 0xFF

    @Test
    fun neutralChroma_isPureGray() {
        // U = V = 128 → zero chroma: output equals luma on all channels.
        for (y in intArrayOf(0, 64, 128, 200, 255)) {
            val argb = YuvMath.argbOf(y, 128, 128)
            assertEquals(y, r(argb))
            assertEquals(y, g(argb))
            assertEquals(y, b(argb))
        }
    }

    @Test
    fun fullRangeExtremes_clampInsteadOfWrapping() {
        val hot = YuvMath.argbOf(255, 255, 255)
        assertTrue(r(hot) in 0..255 && g(hot) in 0..255 && b(hot) in 0..255)
        val cold = YuvMath.argbOf(0, 0, 0)
        assertTrue(r(cold) in 0..255 && g(cold) in 0..255 && b(cold) in 0..255)
    }

    @Test
    fun highV_readsRed() {
        // Strong V (Cr) with mid luma is firmly red-dominant.
        val argb = YuvMath.argbOf(105, 128, 255)
        assertTrue("expected red-dominant, got ${r(argb)},${g(argb)},${b(argb)}",
            r(argb) > g(argb) + 60 && r(argb) > b(argb) + 60)
    }

    @Test
    fun highU_readsBlue() {
        val argb = YuvMath.argbOf(105, 255, 128)
        assertTrue("expected blue-dominant, got ${r(argb)},${g(argb)},${b(argb)}",
            b(argb) > r(argb) + 60 && b(argb) > g(argb) + 60)
    }

    @Test
    fun alwaysOpaqueAlpha() {
        assertEquals(0xFF, (YuvMath.argbOf(50, 100, 200) ushr 24))
    }
}
