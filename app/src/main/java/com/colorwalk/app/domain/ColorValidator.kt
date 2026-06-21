package com.colorwalk.app.domain

import android.graphics.Bitmap

/**
 * Validates that the day's target color genuinely dominates a photo.
 *
 * Algorithm — deterministic HSV-band histogram:
 *  1. Downscale to [SAMPLE_SIZE] × [SAMPLE_SIZE].
 *  2. Drop neutral pixels (too dark or too gray) — they never count toward any color.
 *  3. Classify every remaining pixel into exactly ONE walk color. The hue wheel is
 *     covered completely (no gaps, no overlapping ranges), so classification cannot
 *     depend on the order colors are checked in. Brown is separated from Orange by
 *     brightness — brown *is* dark/muted orange — and washed-out light reds count as
 *     Pink.
 *  4. Pixels in the central half of the frame are weighted ×2: the daily color should
 *     be the photo's subject, and subjects sit near the center.
 *  5. Pass only if the target color (a) has the highest weighted count of all walk
 *     colors AND (b) covers at least [MIN_TARGET_SHARE] of the whole weighted frame —
 *     beating the other colors in an otherwise gray scene is not enough; the color has
 *     to visibly pop.
 *
 * Everything below [validate] is pure Kotlin (no Android framework types) so the full
 * classification and pass/fail logic is unit-testable on the JVM.
 */
object ColorValidator {

    data class ValidationResult(
        val passed: Boolean,
        val dominantHex: String,
        val dominantName: String,
        val matchPercent: Float,         // target's weighted share of the WHOLE frame (0..1)
        val actualDominantColor: String, // walk color with the highest count (≥MIN_DOMINANT_SHARE), or NEUTRAL_LABEL
        val nearestColorName: String? = null,  // highest non-target color present (for failure hints)
        val nearestColorShare: Float = 0f      // its share of the total weighted frame
    )

    private const val SAMPLE_SIZE = 80

    /** Target must cover at least this fraction of the (center-weighted) frame to pass. */
    internal const val MIN_TARGET_SHARE = 0.15f

    /**
     * A color must hold at least this fraction of the total weighted frame to be
     * reported as the "dominant" color in a result card. Below this threshold the
     * scene is essentially neutral (e.g. a gray rug under warm lighting where 2–3%
     * of pixels happen to be slightly reddish) and "Neutral tones" is shown instead
     * of a misleading color name.
     */
    internal const val MIN_DOMINANT_SHARE = 0.05f

    // Neutral gates — pixels below these are shadow/gray/white and belong to no color.
    // MIN_SATURATION = 0.22: prevents warm-tinted near-neutral surfaces (gray walls/rugs
    // under incandescent light, s≈0.19-0.21) from polluting Orange/Brown buckets while
    // retaining genuinely pastel colors (baby blue s≈0.30, sage green s≈0.35).
    private const val MIN_VALUE = 0.15f
    private const val MIN_SATURATION = 0.22f

    internal const val NEUTRAL_LABEL = "Neutral tones"

    private val IDX_RED    = WALK_COLORS.indexOfFirst { it.name == "Red" }
    private val IDX_ORANGE = WALK_COLORS.indexOfFirst { it.name == "Orange" }
    private val IDX_YELLOW = WALK_COLORS.indexOfFirst { it.name == "Yellow" }
    private val IDX_GREEN  = WALK_COLORS.indexOfFirst { it.name == "Green" }
    private val IDX_BLUE   = WALK_COLORS.indexOfFirst { it.name == "Blue" }
    private val IDX_PURPLE = WALK_COLORS.indexOfFirst { it.name == "Purple" }
    private val IDX_PINK   = WALK_COLORS.indexOfFirst { it.name == "Pink" }
    private val IDX_BROWN  = WALK_COLORS.indexOfFirst { it.name == "Brown" }

    fun validate(bitmap: Bitmap, target: WalkColor): ValidationResult {
        val sample = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        sample.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        if (sample !== bitmap) sample.recycle()
        return validatePixels(pixels, SAMPLE_SIZE, SAMPLE_SIZE, target)
    }

    /** Pure-Kotlin core of [validate] — exposed internally for JVM unit tests. */
    internal fun validatePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        target: WalkColor
    ): ValidationResult {
        val weights = LongArray(WALK_COLORS.size)
        // Per-bucket RGB sums so the displayed "dominant" hex is the actual average
        // shade the user photographed, not a reference swatch.
        val rSum = LongArray(WALK_COLORS.size)
        val gSum = LongArray(WALK_COLORS.size)
        val bSum = LongArray(WALK_COLORS.size)
        var totalWeight = 0L

        val cxMin = width / 4
        val cxMax = width * 3 / 4
        val cyMin = height / 4
        val cyMax = height * 3 / 4

        val hsv = FloatArray(3)
        for (y in 0 until height) {
            val centerRow = y in cyMin until cyMax
            for (x in 0 until width) {
                val px = pixels[y * width + x]
                val w = if (centerRow && x in cxMin until cxMax) 2L else 1L
                totalWeight += w
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val idx = classifyRgb(r, g, b, hsv) ?: continue
                weights[idx] += w
                rSum[idx] += w * r
                gSum[idx] += w * g
                bSum[idx] += w * b
            }
        }

        val targetIdx = WALK_COLORS.indexOfFirst { it.name == target.name }
        val targetWeight = if (targetIdx >= 0) weights[targetIdx] else 0L
        val maxWeight = weights.max()
        val targetShare = if (totalWeight > 0) targetWeight.toFloat() / totalWeight else 0f

        val passed = targetWeight > 0 &&
                targetWeight == maxWeight &&
                targetShare >= MIN_TARGET_SHARE

        // A color must hold at least MIN_DOMINANT_SHARE of the total weighted frame
        // to be named "dominant". Without this gate, 2–3% of warm-tinted neutral
        // pixels (e.g. a gray rug under incandescent light) falsely claim dominance.
        val dominantShare = if (totalWeight > 0) maxWeight.toFloat() / totalWeight else 0f
        val dominantIdx = if (maxWeight > 0 && dominantShare >= MIN_DOMINANT_SHARE)
            weights.indexOfFirst { it == maxWeight } else -1

        val dominantName: String
        val dominantHex: String
        if (dominantIdx >= 0) {
            dominantName = WALK_COLORS[dominantIdx].name
            val n = weights[dominantIdx]
            dominantHex = String.format(
                "#%02X%02X%02X",
                (rSum[dominantIdx] / n).toInt(),
                (gSum[dominantIdx] / n).toInt(),
                (bSum[dominantIdx] / n).toInt()
            )
        } else {
            dominantName = NEUTRAL_LABEL
            dominantHex = "#808080"
        }

        // Find the highest non-target color so failure cards can hint what WAS found
        // (e.g. "12% Pink found" when a fuchsia sign is shot on a Red day).
        var nearestName: String? = null
        var nearestShare = 0f
        for (i in weights.indices) {
            if (i == targetIdx) continue
            val share = if (totalWeight > 0) weights[i].toFloat() / totalWeight else 0f
            if (share > nearestShare) { nearestShare = share; nearestName = WALK_COLORS[i].name }
        }

        return ValidationResult(
            passed = passed,
            dominantHex = dominantHex,
            dominantName = dominantName,
            matchPercent = targetShare,
            actualDominantColor = dominantName,
            nearestColorName = if (nearestShare >= MIN_DOMINANT_SHARE) nearestName else null,
            nearestColorShare = nearestShare
        )
    }

    /** Classifies one ARGB pixel into a walk color, or null for neutral pixels. */
    internal fun classify(argb: Int): WalkColor? {
        val idx = classifyRgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, FloatArray(3))
        return if (idx != null) WALK_COLORS[idx] else null
    }

    /**
     * Hue bands cover the full wheel so every sufficiently-colorful pixel lands in
     * exactly one bucket:
     *   Red    345–15   (h≥5° + mid chroma → Brown; washed-out → Pink)
     *   Orange  15–45   (dark or muted → Brown)
     *   Yellow  45–70
     *   Green   70–175
     *   Blue   175–260
     *   Purple 260–315
     *   Pink   315–345
     */
    private fun classifyRgb(r: Int, g: Int, b: Int, hsv: FloatArray): Int? {
        rgbToHsv(r, g, b, hsv)
        val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
        if (v < MIN_VALUE || s < MIN_SATURATION) return null
        return when {
            h >= 345f || h < 15f -> {
                val chroma = s * v
                when {
                    // Orange-red earth tones (brick, terracotta, rust): hue is offset toward
                    // orange (h≥5°) and chroma sits in the mid-range of fired clay / mineral
                    // pigments (0.35–0.60). Pure reds cluster at h<5° (excluded by h≥5f).
                    // Pale pinks have chroma<0.35 (excluded by the lower bound), so dusty
                    // rose / blush reach the Pink branch below rather than landing here.
                    h >= 5f && chroma in 0.35f..0.60f -> IDX_BROWN
                    // Washed-out light reds (dusty rose, blush) at medium-to-high brightness
                    s < 0.40f && v >= 0.55f -> IDX_PINK
                    else -> IDX_RED
                }
            }
            h < 45f -> {
                // Brown is "dark/muted orange". Chroma (s×v) is the best single discriminator:
                // vibrant oranges have chroma≥0.50; earth tones (sienna, wood, tan) are below.
                val chroma = s * v
                if (v <= 0.65f || chroma <= 0.50f) IDX_BROWN else IDX_ORANGE
            }
            h < 70f  -> IDX_YELLOW
            h < 175f -> IDX_GREEN
            h < 260f -> IDX_BLUE
            h < 315f -> IDX_PURPLE
            else     -> IDX_PINK
        }
    }

    /** Pure-Kotlin RGB→HSV so the classifier runs (and tests run) without Android. */
    private fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min

        var h = when {
            delta == 0f -> 0f
            max == rf   -> 60f * (((gf - bf) / delta) % 6f)
            max == gf   -> 60f * ((bf - rf) / delta + 2f)
            else        -> 60f * ((rf - gf) / delta + 4f)
        }
        if (h < 0f) h += 360f

        out[0] = h
        out[1] = if (max == 0f) 0f else delta / max
        out[2] = max
    }
}
