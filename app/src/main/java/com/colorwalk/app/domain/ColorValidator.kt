package com.colorwalk.app.domain

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.palette.graphics.Palette

object ColorValidator {

    data class ValidationResult(
        val passed: Boolean,
        val dominantHex: String,
        val dominantName: String,
        val matchPercent: Float   // 0..1
    )

    private const val PASS_THRESHOLD = 0.60f
    private const val SAMPLE_SIZE = 80  // 80×80 = 6,400 pixel samples — fast enough on-device

    fun validate(bitmap: Bitmap, target: WalkColor): ValidationResult {
        val sample = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        sample.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)

        val hsv = FloatArray(3)
        var matchCount = 0
        for (px in pixels) {
            AColor.colorToHSV(px, hsv)
            if (matchesTarget(hsv, target)) matchCount++
        }

        val percent = matchCount.toFloat() / pixels.size
        val passed = percent >= PASS_THRESHOLD

        // Dominant hex for display (use Palette on the sample for speed)
        val palette = Palette.from(sample).maximumColorCount(8).generate()
        val dominant = palette.dominantSwatch
        val hexStr = dominant?.rgb?.let { String.format("#%06X", 0xFFFFFF and it) } ?: "#808080"
        val dominantName = dominant?.rgb?.let { closestColorName(it) } ?: "Unknown"

        return ValidationResult(passed, hexStr, dominantName, percent)
    }

    private fun matchesTarget(hsv: FloatArray, target: WalkColor): Boolean {
        val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
        if (s < target.satMin || v < target.valMin) return false
        return if (target.hueMax > 360f) {
            h >= target.hueMin || h <= (target.hueMax - 360f)
        } else {
            h in target.hueMin..target.hueMax
        }
    }

    private fun closestColorName(rgb: Int): String {
        val hsv = FloatArray(3)
        AColor.colorToHSV(rgb, hsv)
        val h = hsv[0]
        return when {
            hsv[1] < 0.15f -> if (hsv[2] > 0.8f) "White" else "Gray"
            hsv[2] < 0.15f -> "Black"
            (h >= 345 || h < 15) -> "Red"
            h < 45  -> "Orange"
            h < 70  -> "Yellow"
            h < 165 -> "Green"
            h < 255 -> "Blue"
            h < 310 -> "Purple"
            h < 345 -> "Pink"
            else    -> "Unknown"
        }
    }
}
