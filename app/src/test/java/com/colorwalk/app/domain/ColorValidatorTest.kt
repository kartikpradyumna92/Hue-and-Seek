package com.colorwalk.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ColorValidator.
 *
 * The classifier core (validatePixels / classify) is pure Kotlin, so the REAL
 * classification and pass/fail logic is exercised here on the JVM with synthetic
 * pixel arrays. Only the thin Bitmap-scaling wrapper (validate) requires an
 * instrumented test.
 */
class ColorValidatorTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun walkColor(name: String): WalkColor = WALK_COLORS.first { it.name == name }

    /** Builds a width×height frame filled with [fill]. */
    private fun frame(width: Int, height: Int, fill: Int): IntArray =
        IntArray(width * height) { fill }

    // Reference pixels (sRGB)
    private val PURE_RED     = rgb(229, 57, 53)    // #E53935 — the app's Red swatch
    private val FERRARI_RED  = rgb(216, 11, 4)     // saturated dark-ish red
    private val SADDLE_BROWN = rgb(139, 69, 19)    // classic brown (h≈25, v≈0.55)
    private val TAN          = rgb(210, 180, 140)  // light muted brown (h≈34, s≈0.33, v≈0.82)
    private val BRIGHT_ORANGE = rgb(251, 140, 0)   // #FB8C00 — the app's Orange swatch
    private val LIGHT_PINK   = rgb(255, 185, 185)  // pale pink (s≈0.27) → reads pink; old pixel s≈0.20 is now neutral
    private val MAGENTA_PINK = rgb(233, 30, 99)    // #E91E63 — the app's Pink swatch
    private val GRASS_GREEN  = rgb(67, 160, 71)    // #43A047 — the app's Green swatch
    private val SKY_CYAN     = rgb(135, 206, 235)  // sky blue, h≈197 (old algorithm's gap)
    private val DEEP_BLUE    = rgb(30, 136, 229)   // #1E88E5 — the app's Blue swatch
    private val GRAY              = rgb(128, 128, 128)
    private val NEAR_BLACK        = rgb(20, 20, 20)
    private val WHITE             = rgb(250, 250, 250)
    // Earth tone (Orange hue, h≈19°): muted orange chroma≈0.45 ≤ 0.50 → Brown via Orange path
    private val SIENNA            = rgb(160, 82, 45)
    // Earth tone (Red hue, h≈9°): brick-like chroma≈0.50 in 0.35–0.60 window → Brown via Red path
    private val BRICK_RED         = rgb(178, 70, 50)
    // Earth tone (Red hue, h≈10°): terracotta chroma≈0.59 in 0.35–0.60 window → Brown via Red path
    private val TERRACOTTA        = rgb(204, 78, 53)
    // Medium-bright washed-out red: h≈7°, s≈0.24, chroma≈0.18 < 0.35 → Pink (not Brown)
    private val DUSTY_ROSE        = rgb(185, 145, 140)
    // Warm-tinted gray (incandescent light): s≈0.20 — between old 0.18 and new 0.22 thresholds
    private val WARM_GRAY         = rgb(185, 165, 150)

    // ── per-pixel classification (replaces the old order-dependent matching) ──

    @Test
    fun classify_appSwatches_mapToTheirOwnColor() {
        // Every official walk color swatch must classify as itself — this is the
        // direct regression test for the old first-match ordering bug (B1), where
        // the Brown swatch classified as Red/Orange.
        for (wc in WALK_COLORS) {
            val argb = (0xFF shl 24) or wc.hex.removePrefix("#").toInt(16)
            assertEquals("Swatch ${wc.name} (${wc.hex})", wc.name, ColorValidator.classify(argb)?.name)
        }
    }

    @Test
    fun classify_saddleBrown_isBrown_notOrangeOrRed() {
        assertEquals("Brown", ColorValidator.classify(SADDLE_BROWN)?.name)
    }

    @Test
    fun classify_tan_isBrown() {
        // Muted mid-light orange hues (wood, tan) read as brown to the eye
        assertEquals("Brown", ColorValidator.classify(TAN)?.name)
    }

    @Test
    fun classify_brightOrange_isOrange_notBrown() {
        assertEquals("Orange", ColorValidator.classify(BRIGHT_ORANGE)?.name)
    }

    @Test
    fun classify_pureRed_isRed() {
        assertEquals("Red", ColorValidator.classify(PURE_RED)?.name)
        assertEquals("Red", ColorValidator.classify(FERRARI_RED)?.name)
    }

    @Test
    fun classify_washedOutLightRed_isPink() {
        assertEquals("Pink", ColorValidator.classify(LIGHT_PINK)?.name)
    }

    @Test
    fun classify_magentaPink_isPink() {
        assertEquals("Pink", ColorValidator.classify(MAGENTA_PINK)?.name)
    }

    @Test
    fun classify_skyCyan_isBlue_hueGapClosed() {
        // h≈197 fell into the old 175–195 dead zone; full hue coverage assigns it to Blue
        assertEquals("Blue", ColorValidator.classify(SKY_CYAN)?.name)
        assertEquals("Blue", ColorValidator.classify(DEEP_BLUE)?.name)
    }

    @Test
    fun classify_green_isGreen() {
        assertEquals("Green", ColorValidator.classify(GRASS_GREEN)?.name)
    }

    @Test
    fun classify_neutrals_returnNull() {
        assertNull(ColorValidator.classify(GRAY))
        assertNull(ColorValidator.classify(NEAR_BLACK))
        assertNull(ColorValidator.classify(WHITE))
    }

    @Test
    fun classify_warmTintedGray_isNeutral() {
        // A gray wall under warm incandescent light shifts to s≈0.19 at h≈26°.
        // Old MIN_SATURATION=0.18 let it classify as Brown, polluting the histogram
        // on any non-Brown day (e.g. a mostly gray indoor room on Green day showed
        // "Brown Dominated"). New MIN_SATURATION=0.22 correctly rejects it.
        assertNull(ColorValidator.classify(WARM_GRAY))
    }

    @Test
    fun classify_sienna_isBrown_notOrange() {
        // h≈19°, s≈0.72, v≈0.63 — chroma=0.45 ≤ 0.50 → Brown via Orange/Brown path.
        assertEquals("Brown", ColorValidator.classify(SIENNA)?.name)
    }

    @Test
    fun classify_brick_isBrown_notRed() {
        // h≈9°, s≈0.72, v≈0.70, chroma≈0.50 — in the 0.35–0.60 window for Red-hue earth tones.
        // Old code classified every h<15° pixel as Red/Pink regardless of chroma, so a
        // brick wall on a Brown day would return "Red Dominated" and never pass.
        assertEquals("Brown", ColorValidator.classify(BRICK_RED)?.name)
    }

    @Test
    fun classify_terracotta_isBrown_notRed() {
        // h≈10°, s≈0.74, v≈0.80, chroma≈0.59 — just under the 0.60 upper bound.
        assertEquals("Brown", ColorValidator.classify(TERRACOTTA)?.name)
    }

    @Test
    fun classify_dustyRose_isPink_notRed_andNotBrown() {
        // h≈7°, s≈0.24, chroma≈0.18 — below the Brown lower bound (0.35), so it falls
        // through to the Pink branch (s<0.40, v≥0.55). Must not be misclassified as Brown.
        assertEquals("Pink", ColorValidator.classify(DUSTY_ROSE)?.name)
    }

    @Test
    fun validatePixels_terracottaSubject_passesOnBrownDay() {
        // A terracotta-filled frame (flower pots, terracotta tiles, adobe walls) on a Brown day.
        // Before the fix, every pixel classified as Red → "Red Dominated", never passing Brown.
        val pixels = IntArray(64) { TERRACOTTA }
        val result = ColorValidator.validatePixels(pixels, 8, 8, walkColor("Brown"))
        assertTrue("Terracotta subject must pass on a Brown day", result.passed)
        assertEquals("Brown", result.actualDominantColor)
    }

    @Test
    fun classify_everySaturatedHue_landsInExactlyOneBucket() {
        // Sweep the hue wheel at full saturation/value: no pixel may be unclassified
        // (B2: the old ranges had gaps at 15–20, 70–75, 175–195, 255–270).
        for (h in 0 until 360) {
            val argb = hsvToRgb(h.toFloat(), 0.9f, 0.9f)
            val name = ColorValidator.classify(argb)?.name
            assertTrue("Hue $h° classified as nothing", name != null)
        }
    }

    // ── frame-level dominance rules ───────────────────────────────────────────

    @Test
    fun validatePixels_fullRedFrame_passesOnRedDay() {
        val result = ColorValidator.validatePixels(frame(8, 8, PURE_RED), 8, 8, walkColor("Red"))
        assertTrue(result.passed)
        assertEquals("Red", result.actualDominantColor)
        assertTrue(result.matchPercent > 0.9f)
    }

    @Test
    fun validatePixels_fullRedFrame_failsOnBlueDay() {
        val result = ColorValidator.validatePixels(frame(8, 8, PURE_RED), 8, 8, walkColor("Blue"))
        assertFalse(result.passed)
        assertEquals("Red", result.actualDominantColor)
        assertEquals(0f, result.matchPercent, 0.001f)
    }

    @Test
    fun validatePixels_brownSubjectAgainstGreenery_passesOnBrownDay() {
        // The B1 scenario: a brown subject (tree trunk / wooden door) filling most
        // of the frame with green surroundings. Under the old algorithm brown
        // pixels counted as Orange, so Brown days were nearly impossible to pass.
        // Brown block 6×6 of an 8×8 frame (covers the whole ×2 center region).
        val pixels = IntArray(64) { i ->
            val x = i % 8; val y = i / 8
            if (x in 1..6 && y in 1..6) SADDLE_BROWN else GRASS_GREEN
        }
        val result = ColorValidator.validatePixels(pixels, 8, 8, walkColor("Brown"))
        assertTrue("Brown subject must pass on a Brown day", result.passed)
        assertEquals("Brown", result.actualDominantColor)
    }

    @Test
    fun validatePixels_tinyColorSpeckInGrayScene_failsAndShowsNeutralDominant() {
        // 3 red pixels in a corner of an otherwise gray (neutral) 10×10 frame.
        // Red's weighted share ≈ 2.2% < MIN_DOMINANT_SHARE (5%), so the result
        // must display "Neutral tones" rather than falsely claiming "Red dominated"
        // (the real-world bug: a gray rug under warm light showed "Red dominated").
        val pixels = frame(10, 10, GRAY)
        pixels[0] = PURE_RED; pixels[1] = PURE_RED; pixels[2] = PURE_RED
        val result = ColorValidator.validatePixels(pixels, 10, 10, walkColor("Red"))
        assertFalse(result.passed)
        assertEquals(ColorValidator.NEUTRAL_LABEL, result.actualDominantColor)
        assertTrue(result.matchPercent < ColorValidator.MIN_TARGET_SHARE)
    }

    @Test
    fun validatePixels_colorAboveDominantThreshold_showsColorNotNeutral() {
        // 10×10 frame: totalWeight = 125 (center 5×5 at weight 2, outer 75 at weight 1).
        // 8 edge pixels (weight 1 each) → share = 8/125 = 6.4% > MIN_DOMINANT_SHARE (5%):
        // the dominant label should name the color, not "Neutral tones".
        val pixels = frame(10, 10, GRAY)
        for (i in 0 until 8) pixels[i] = PURE_RED
        val result = ColorValidator.validatePixels(pixels, 10, 10, walkColor("Red"))
        assertFalse(result.passed) // still far below MIN_TARGET_SHARE (15%)
        assertEquals("Red", result.actualDominantColor)
    }

    @Test
    fun validatePixels_nearestColorHint_exposesHighestNonTargetColor() {
        // 30% green, 10% red frame on a Red day: Red fails (not dominant), and
        // the nearest-color hint should point to Green so the UI can tell the user
        // "30% Green was found — try something more purely Red."
        val pixels = IntArray(100) { i ->
            when {
                i < 30 -> GRASS_GREEN
                i < 40 -> PURE_RED
                else   -> GRAY
            }
        }
        val result = ColorValidator.validatePixels(pixels, 10, 10, walkColor("Red"))
        assertFalse(result.passed)
        assertEquals("Green", result.nearestColorName)
        assertTrue(result.nearestColorShare > 0.2f)
    }

    @Test
    fun validatePixels_targetBeatenByAnotherColor_fails() {
        // 60% green, 40% red on a Red day → Green dominates.
        val pixels = IntArray(100) { i -> if (i < 60) GRASS_GREEN else PURE_RED }
        val result = ColorValidator.validatePixels(pixels, 10, 10, walkColor("Red"))
        assertFalse(result.passed)
        assertEquals("Green", result.actualDominantColor)
    }

    @Test
    fun validatePixels_centerWeighting_subjectInCenterBeatsLargerBackdrop() {
        // 8×8: center 4×4 (16 px, weight 2 → 32) red; 20 green pixels on the edges
        // (weight 1 → 20). Center-weighted red wins even though green has more pixels.
        val pixels = frame(8, 8, GRAY)
        var greens = 0
        for (i in pixels.indices) {
            val x = i % 8; val y = i / 8
            if (x in 2..5 && y in 2..5) pixels[i] = PURE_RED
            else if (greens < 20) { pixels[i] = GRASS_GREEN; greens++ }
        }
        val result = ColorValidator.validatePixels(pixels, 8, 8, walkColor("Red"))
        assertTrue(result.passed)
        assertEquals("Red", result.actualDominantColor)
    }

    @Test
    fun validatePixels_allNeutralFrame_failsWithNeutralLabel() {
        val result = ColorValidator.validatePixels(frame(8, 8, GRAY), 8, 8, walkColor("Red"))
        assertFalse(result.passed)
        assertEquals(ColorValidator.NEUTRAL_LABEL, result.actualDominantColor)
        assertEquals(ColorValidator.NEUTRAL_LABEL, result.dominantName)
        assertEquals(0f, result.matchPercent, 0.001f)
    }

    @Test
    fun validatePixels_dominantHex_isAverageOfWinningBucket() {
        val result = ColorValidator.validatePixels(frame(4, 4, PURE_RED), 4, 4, walkColor("Red"))
        assertEquals("#E53935", result.dominantHex)
    }

    @Test
    fun validatePixels_matchPercent_isShareOfWholeFrame() {
        // Exactly half red, half gray, no center bias (red fills left half of every
        // row, so red gets the same share of center and edge weight): share = 0.5.
        val pixels = IntArray(64) { i -> if (i % 8 < 4) PURE_RED else GRAY }
        val result = ColorValidator.validatePixels(pixels, 8, 8, walkColor("Red"))
        assertEquals(0.5f, result.matchPercent, 0.01f)
        assertTrue(result.passed)
    }

    // ── WALK_COLORS integrity ─────────────────────────────────────────────────

    @Test
    fun walkColors_containsExpectedColorNames() {
        val names = WALK_COLORS.map { it.name }.toSet()
        val expected = setOf("Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Pink", "Brown")
        assertEquals(expected, names)
    }

    @Test
    fun walkColors_eachColorHasValidHexFormat() {
        val hexRegex = Regex("^#[0-9A-Fa-f]{6}$")
        for (wc in WALK_COLORS) {
            assertTrue("Color '${wc.name}' has invalid hex '${wc.hex}'", hexRegex.matches(wc.hex))
        }
    }

    @Test
    fun validationResult_equalityByValue() {
        val r1 = ColorValidator.ValidationResult(
            passed = true,
            dominantHex = "#FF0000",
            dominantName = "Red",
            matchPercent = 0.75f,
            actualDominantColor = "Red"
        )
        assertEquals(r1, r1.copy())
    }

    // ── pure HSV→RGB helper for the hue sweep test ────────────────────────────

    private fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2f - 1))
        val m = v - c
        val (rf, gf, bf) = when {
            h < 60f  -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else     -> Triple(c, 0f, x)
        }
        return rgb(((rf + m) * 255).toInt(), ((gf + m) * 255).toInt(), ((bf + m) * 255).toInt())
    }
}
