package com.colorwalk.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the sRGB→OKLAB conversion against Björn Ottosson's published reference
 * values, and the perceptual properties the validator's ΔE gate depends on.
 */
class OkLabTest {

    private val EPS = 2e-3f

    @Test
    fun white_isLOneChromaZero() {
        val lab = OkLab.fromSrgb(255, 255, 255)
        assertEquals(1.000f, lab[0], EPS)
        assertEquals(0.000f, lab[1], EPS)
        assertEquals(0.000f, lab[2], EPS)
    }

    @Test
    fun black_isAllZero() {
        val lab = OkLab.fromSrgb(0, 0, 0)
        assertEquals(0f, lab[0], EPS)
        assertEquals(0f, lab[1], EPS)
        assertEquals(0f, lab[2], EPS)
    }

    @Test
    fun primaryRed_matchesReference() {
        // Ottosson's reference: sRGB (1,0,0) → L=0.62796, a=0.22486, b=0.12585
        val lab = OkLab.fromSrgb(255, 0, 0)
        assertEquals(0.62796f, lab[0], EPS)
        assertEquals(0.22486f, lab[1], EPS)
        assertEquals(0.12585f, lab[2], EPS)
    }

    @Test
    fun primaryGreen_matchesReference() {
        // sRGB (0,1,0) → L=0.86644, a=−0.23389, b=0.17950
        val lab = OkLab.fromSrgb(0, 255, 0)
        assertEquals(0.86644f, lab[0], EPS)
        assertEquals(-0.23389f, lab[1], EPS)
        assertEquals(0.17950f, lab[2], EPS)
    }

    @Test
    fun primaryBlue_matchesReference() {
        // sRGB (0,0,1) → L=0.45201, a=−0.03246, b=−0.31153
        val lab = OkLab.fromSrgb(0, 0, 255)
        assertEquals(0.45201f, lab[0], EPS)
        assertEquals(-0.03246f, lab[1], EPS)
        assertEquals(-0.31153f, lab[2], EPS)
    }

    @Test
    fun deltaE_identicalColors_isZero() {
        val a = OkLab.fromSrgb(229, 57, 53)
        val b = OkLab.fromSrgb(229, 57, 53)
        assertEquals(0f, OkLab.deltaE(a, b), 1e-6f)
    }

    @Test
    fun deltaE_isSymmetric() {
        val a = OkLab.fromSrgb(229, 57, 53)   // app Red swatch
        val b = OkLab.fromSrgb(30, 136, 229)  // app Blue swatch
        assertEquals(OkLab.deltaE(a, b), OkLab.deltaE(b, a), 1e-6f)
    }

    @Test
    fun deltaE_nearbyShades_tighterThanOpposingHues() {
        // The property the validator's TIGHT_DELTA_E gate rests on: two reds sit far
        // closer together than red vs. blue, by a wide margin.
        val appRed     = OkLab.fromSrgb(229, 57, 53)   // #E53935
        val ferrariRed = OkLab.fromSrgb(216, 11, 4)
        val appBlue    = OkLab.fromSrgb(30, 136, 229)
        val redToRed  = OkLab.deltaE(appRed, ferrariRed)
        val redToBlue = OkLab.deltaE(appRed, appBlue)
        assertTrue("similar shades must be close (was $redToRed)", redToRed < ColorValidator.TIGHT_DELTA_E)
        assertTrue("opposing hues must be far (was $redToBlue)", redToBlue > 3 * redToRed)
    }

    @Test
    fun deltaE_swatchToItself_underTightThreshold_forAllWalkColors() {
        // Every reference swatch must be a tight match for itself — the focal-window
        // bonus must always apply when the user photographs the literal day's color.
        for (wc in WALK_COLORS) {
            val rgb = wc.hex.removePrefix("#").toInt(16)
            val lab = OkLab.fromSrgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
            assertEquals(0f, OkLab.deltaE(lab, lab), 1e-6f)
        }
    }
}
