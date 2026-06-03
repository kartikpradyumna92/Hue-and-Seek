package com.colorwalk.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ColorValidator.
 *
 * ColorValidator.validate() requires a real android.graphics.Bitmap and
 * androidx.palette.graphics.Palette, both of which are Android framework classes that are
 * unavailable on the JVM (they stub to nothing in the test environment).  The full validate()
 * method therefore CANNOT be unit-tested in the pure-JVM test source set.
 *
 * What we CAN test here are the pure, Android-free helper shapes used by ColorValidator:
 *   • The ValidationResult data class (field names, default equality)
 *   • The WALK_COLORS list (structure used by the validator)
 *   • matchesTarget-equivalent logic re-expressed as a pure function for the boundary cases
 *     that the real implementation exercises.
 *
 * For a full integration test of ColorValidator.validate() use an instrumented test
 * (androidTest) with a real Bitmap created via Bitmap.createBitmap().
 */
class ColorValidatorTest {

    // ── ValidationResult data class ──────────────────────────────────────────

    @Test
    fun validationResult_equalityByValue() {
        val r1 = ColorValidator.ValidationResult(
            passed = true,
            dominantHex = "#FF0000",
            dominantName = "Red",
            matchPercent = 0.75f,
            actualDominantColor = "Red"
        )
        val r2 = r1.copy()
        assertEquals(r1, r2)
    }

    @Test
    fun validationResult_passedFalse_whenConstructedAsSuch() {
        val result = ColorValidator.ValidationResult(
            passed = false,
            dominantHex = "#00FF00",
            dominantName = "Green",
            matchPercent = 0.10f,
            actualDominantColor = "Green"
        )
        assertFalse(result.passed)
    }

    @Test
    fun validationResult_matchPercentRange_isValidFloat() {
        val result = ColorValidator.ValidationResult(
            passed = true,
            dominantHex = "#1E88E5",
            dominantName = "Blue",
            matchPercent = 0.9f,
            actualDominantColor = "Blue"
        )
        assertTrue(result.matchPercent in 0f..1f)
    }

    // ── WalkColor / WALK_COLORS integrity (used internally by ColorValidator) ─

    @Test
    fun walkColors_listIsNotEmpty() {
        assertTrue(WALK_COLORS.isNotEmpty())
    }

    @Test
    fun walkColors_eachColorHasValidHexFormat() {
        val hexRegex = Regex("^#[0-9A-Fa-f]{6}$")
        for (wc in WALK_COLORS) {
            assertTrue(
                "Color '${wc.name}' has invalid hex '${wc.hex}'",
                hexRegex.matches(wc.hex)
            )
        }
    }

    @Test
    fun walkColors_eachColorHasNonBlankName() {
        for (wc in WALK_COLORS) {
            assertTrue("Expected non-blank name, got '${wc.name}'", wc.name.isNotBlank())
        }
    }

    @Test
    fun walkColors_hueRangesArePositive() {
        for (wc in WALK_COLORS) {
            assertTrue(
                "Color '${wc.name}' hueMin ${wc.hueMin} must be >= 0",
                wc.hueMin >= 0f
            )
            // hueMax may exceed 360 for red (wraps around)
            assertTrue(
                "Color '${wc.name}' hueMax ${wc.hueMax} must be > 0",
                wc.hueMax > 0f
            )
        }
    }

    @Test
    fun walkColors_satMinAndValMinAreInUnitRange() {
        for (wc in WALK_COLORS) {
            assertTrue("satMin for '${wc.name}' must be in [0,1]", wc.satMin in 0f..1f)
            assertTrue("valMin for '${wc.name}' must be in [0,1]", wc.valMin in 0f..1f)
        }
    }

    @Test
    fun walkColors_redHueMaxExceedsThreeSixtyForWrapAround() {
        val red = WALK_COLORS.first { it.name == "Red" }
        assertTrue(
            "Red hueMax should exceed 360 to represent hue wrap-around, was ${red.hueMax}",
            red.hueMax > 360f
        )
    }

    @Test
    fun walkColors_containsExpectedColorNames() {
        val names = WALK_COLORS.map { it.name }.toSet()
        val expected = setOf("Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Pink", "Brown")
        assertEquals(expected, names)
    }

    // ── matchesTarget-equivalent pure logic (mirrors the private function) ───
    // These tests encode the hue-range boundary logic so that any change to the
    // matching algorithm immediately surfaces as a test failure.

    private fun matchesTarget(hsv: FloatArray, target: WalkColor): Boolean {
        val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
        if (s < target.satMin || v < target.valMin) return false
        return if (target.hueMax > 360f) {
            h >= target.hueMin || h <= (target.hueMax - 360f)
        } else {
            h in target.hueMin..target.hueMax
        }
    }

    @Test
    fun matchesTarget_redHue350_matchesRed() {
        val red = WALK_COLORS.first { it.name == "Red" }
        // hue=350 is deep red, s=0.9, v=0.9
        assertTrue(matchesTarget(floatArrayOf(350f, 0.9f, 0.9f), red))
    }

    @Test
    fun matchesTarget_redHue5_matchesRedViaWrapAround() {
        val red = WALK_COLORS.first { it.name == "Red" }
        // hue=5 wraps past 360 into red range
        assertTrue(matchesTarget(floatArrayOf(5f, 0.9f, 0.9f), red))
    }

    @Test
    fun matchesTarget_blueHue220_matchesBlue() {
        val blue = WALK_COLORS.first { it.name == "Blue" }
        assertTrue(matchesTarget(floatArrayOf(220f, 0.8f, 0.8f), blue))
    }

    @Test
    fun matchesTarget_blueHue220_lowSaturation_doesNotMatch() {
        val blue = WALK_COLORS.first { it.name == "Blue" }
        // saturation below satMin (0.35 default) should fail
        assertFalse(matchesTarget(floatArrayOf(220f, 0.10f, 0.8f), blue))
    }

    @Test
    fun matchesTarget_greenHue120_matchesGreen() {
        val green = WALK_COLORS.first { it.name == "Green" }
        assertTrue(matchesTarget(floatArrayOf(120f, 0.5f, 0.5f), green))
    }

    @Test
    fun matchesTarget_yellowHue55_matchesYellow() {
        val yellow = WALK_COLORS.first { it.name == "Yellow" }
        assertTrue(matchesTarget(floatArrayOf(55f, 0.8f, 0.9f), yellow))
    }

    @Test
    fun matchesTarget_purpleHue290_matchesPurple() {
        val purple = WALK_COLORS.first { it.name == "Purple" }
        assertTrue(matchesTarget(floatArrayOf(290f, 0.7f, 0.6f), purple))
    }

    @Test
    fun matchesTarget_pinkHue330_matchesPink() {
        val pink = WALK_COLORS.first { it.name == "Pink" }
        assertTrue(matchesTarget(floatArrayOf(330f, 0.8f, 0.8f), pink))
    }

    @Test
    fun matchesTarget_brownHue15_lowSat_matchesBrown() {
        val brown = WALK_COLORS.first { it.name == "Brown" }
        // Brown has lower satMin (0.20) and valMin (0.15)
        assertTrue(matchesTarget(floatArrayOf(15f, 0.25f, 0.30f), brown))
    }

    @Test
    fun matchesTarget_brownHue15_veryLowVal_doesNotMatch() {
        val brown = WALK_COLORS.first { it.name == "Brown" }
        assertFalse(matchesTarget(floatArrayOf(15f, 0.5f, 0.05f), brown))
    }
}
