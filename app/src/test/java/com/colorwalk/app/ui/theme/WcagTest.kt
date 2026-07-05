package com.colorwalk.app.ui.theme

import com.colorwalk.app.domain.WALK_COLORS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.1 math behind the dynamic chromatic theme. These tests are the proof of
 * the theme's accessibility claim: EVERY possible day accent must yield an
 * on-accent content color meeting AA for normal text.
 */
class WcagTest {

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()

    @Test
    fun luminance_referencePoints() {
        assertEquals(0.0, Wcag.relativeLuminance(BLACK), 1e-6)
        assertEquals(1.0, Wcag.relativeLuminance(WHITE), 1e-6)
        // Pure sRGB red: standard WCAG worked example = 0.2126
        assertEquals(0.2126, Wcag.relativeLuminance(0xFFFF0000.toInt()), 1e-4)
        // Pure green carries the largest coefficient
        assertEquals(0.7152, Wcag.relativeLuminance(0xFF00FF00.toInt()), 1e-4)
    }

    @Test
    fun contrast_blackOnWhite_is21() {
        assertEquals(21.0, Wcag.contrastRatio(BLACK, WHITE), 1e-6)
        assertEquals(21.0, Wcag.contrastRatio(WHITE, BLACK), 1e-6) // symmetric
    }

    @Test
    fun contrast_identicalColors_is1() {
        assertEquals(1.0, Wcag.contrastRatio(0xFF43A047.toInt(), 0xFF43A047.toInt()), 1e-6)
    }

    @Test
    fun contentColor_lightAccents_getBlack() {
        // White text on Yellow was the app's real pre-existing accessibility bug.
        val yellow = 0xFFFDD835.toInt()
        assertEquals(BLACK, Wcag.contentColorFor(yellow))
    }

    @Test
    fun contentColor_darkAccents_getWhite() {
        val brown = 0xFF6D4C41.toInt()
        val purple = 0xFF8E24AA.toInt()
        assertEquals(WHITE, Wcag.contentColorFor(brown))
        assertEquals(WHITE, Wcag.contentColorFor(purple))
    }

    @Test
    fun contentColor_everyWalkColor_meetsAaNormalText() {
        // The theme's core guarantee: whatever color the calendar serves, on-accent
        // text clears WCAG AA (4.5:1). If a future palette tweak breaks this, the
        // build fails here instead of shipping an unreadable day.
        for (wc in WALK_COLORS) {
            val accent = (0xFF shl 24) or wc.hex.removePrefix("#").toInt(16)
            val content = Wcag.contentColorFor(accent)
            val ratio = Wcag.contrastRatio(content, accent)
            assertTrue(
                "${wc.name} (${wc.hex}): content contrast $ratio < ${Wcag.AA_NORMAL_TEXT}",
                ratio >= Wcag.AA_NORMAL_TEXT
            )
        }
    }

    @Test
    fun blend_endpoints_andMidpoint() {
        val a = 0xFF204060.toInt()
        val b = 0xFF80A0C0.toInt()
        assertEquals(a, Wcag.blend(a, b, 0f))
        assertEquals(b, Wcag.blend(a, b, 1f))
        val mid = Wcag.blend(a, b, 0.5f)
        assertEquals(0x50, (mid shr 16) and 0xFF) // (0x20+0x80)/2
        assertEquals(0x70, (mid shr 8) and 0xFF)  // (0x40+0xA0)/2
        assertEquals(0x90, mid and 0xFF)          // (0x60+0xC0)/2
    }

    @Test
    fun blend_clampsFractionOutOfRange() {
        val a = 0xFF000000.toInt()
        val b = 0xFFFFFFFF.toInt()
        assertEquals(a, Wcag.blend(a, b, -1f))
        assertEquals(b, Wcag.blend(a, b, 2f))
    }
}
