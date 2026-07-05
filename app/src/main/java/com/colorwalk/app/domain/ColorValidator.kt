package com.colorwalk.app.domain

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.min

/**
 * Validates that the day's target color genuinely stars in a photo.
 *
 * Two independent ways to pass:
 *
 * **Path 1 — global dominance** (the original rule, spatially refined):
 *  1. Downscale to [SAMPLE_SIZE] × [SAMPLE_SIZE].
 *  2. Drop neutral pixels (too dark or too gray) — they never count toward any color.
 *  3. Classify every remaining pixel into exactly ONE walk color. The hue wheel is
 *     covered completely (no gaps, no overlapping ranges), so classification cannot
 *     depend on the order colors are checked in. Brown is separated from Orange by
 *     brightness — brown *is* dark/muted orange — and washed-out light reds count as
 *     Pink.
 *  4. Every pixel is weighted by a smooth 2-D Gaussian centered on the frame
 *     (center ≈ ×3, edges ≈ ×1) — the subject of a photo sits near the middle, and a
 *     smooth falloff avoids the hard cliff of the old binary center-box ×2 scheme.
 *  5. Pass if the target color (a) has the highest weighted count of all walk colors
 *     AND (b) covers at least [MIN_TARGET_SHARE] of the whole weighted frame.
 *
 * **Path 2 — subject saliency** (small-subject isolation): a modest object — a red
 * mailbox against a whole green garden — should pass without the user cramming it
 * into the entire frame. Checked only when Path 1 fails:
 *  1. The target must still hold a non-trivial [MIN_SUBJECT_GLOBAL_SHARE] of the
 *     weighted frame, so a stray speck can never pass.
 *  2. Five focal windows are examined — frame center plus the four rule-of-thirds
 *     intersections, each a Gaussian window of radius [FOCAL_RADIUS_FRACTION]·min(w,h).
 *  3. Within a window, target pixels whose OKLAB ΔE to the day's reference swatch is
 *     tighter than [TIGHT_DELTA_E] earn a ×[TIGHT_MATCH_BONUS] weight — a shade that
 *     truly *is* the day's color counts harder than one that merely lands in the same
 *     hue bucket ([OkLab]).
 *  4. Pass if in ANY focal window the target holds ≥ [SUBJECT_FOCAL_SHARE] of the
 *     window's mass AND beats every other walk color there: the subject owns its
 *     focal point even though the background owns the frame.
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
        val nearestColorShare: Float = 0f,     // its share of the total weighted frame
        val subjectMatch: Boolean = false      // true when Path 2 (focal-point subject) granted the pass
    )

    private const val SAMPLE_SIZE = 80

    /** Target must cover at least this fraction of the (center-weighted) frame to pass globally. */
    internal const val MIN_TARGET_SHARE = 0.15f

    /**
     * A color must hold at least this fraction of the total weighted frame to be
     * reported as the "dominant" color in a result card. Below this threshold the
     * scene is essentially neutral (e.g. a gray rug under warm lighting where 2–3%
     * of pixels happen to be slightly reddish) and "Neutral tones" is shown instead
     * of a misleading color name.
     */
    internal const val MIN_DOMINANT_SHARE = 0.05f

    // ── Path 1: global Gaussian spatial weighting ────────────────────────────────
    // w(x,y) = 1 + GAIN·exp(−(nx²+ny²)/(2σ²)) in coordinates normalized to [−1,1].
    // Center weight = 1+GAIN = 3; corners ≈ 1. Mirrors the intent of the old binary
    // ×2 center box, but smooth — a subject straddling the old box edge no longer
    // loses half its weight to a one-pixel shift.
    private const val CENTER_GAIN = 2f
    private const val CENTER_SIGMA = 0.45f

    // ── Path 2: focal-point subject isolation ────────────────────────────────────
    /** Focal window radius as a fraction of min(width, height). */
    private const val FOCAL_RADIUS_FRACTION = 0.25f

    /** Floor on the target's global weighted share before Path 2 is even considered. */
    internal const val MIN_SUBJECT_GLOBAL_SHARE = 0.06f

    /** Share of a focal window's mass the target must own to count as its subject. */
    internal const val SUBJECT_FOCAL_SHARE = 0.5f

    /**
     * OKLAB ΔE under which a pixel is a *tight* match for the day's reference swatch.
     * ~6× the just-noticeable difference: same color family and clearly "that color"
     * to the eye, while tolerating real-world lighting shifts.
     */
    internal const val TIGHT_DELTA_E = 0.12f

    /** Focal-window weight multiplier for tight-ΔE target pixels. */
    private const val TIGHT_MATCH_BONUS = 1.5

    // Focal points: frame center + the four rule-of-thirds intersections.
    private val FOCAL_POINTS = arrayOf(
        floatArrayOf(0.5f, 0.5f),
        floatArrayOf(1f / 3f, 1f / 3f),
        floatArrayOf(2f / 3f, 1f / 3f),
        floatArrayOf(1f / 3f, 2f / 3f),
        floatArrayOf(2f / 3f, 2f / 3f),
    )

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
        val weights = DoubleArray(WALK_COLORS.size)
        // Per-bucket RGB sums so the displayed "dominant" hex is the actual average
        // shade the user photographed, not a reference swatch.
        val rSum = DoubleArray(WALK_COLORS.size)
        val gSum = DoubleArray(WALK_COLORS.size)
        val bSum = DoubleArray(WALK_COLORS.size)
        var totalWeight = 0.0

        // Per-pixel bucket, kept for the focal-window pass (-1 = neutral).
        val classIdx = IntArray(width * height)

        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        val halfW = width / 2f
        val halfH = height / 2f
        val twoSigmaSq = 2f * CENTER_SIGMA * CENTER_SIGMA

        val hsv = FloatArray(3)
        for (y in 0 until height) {
            val ny = (y - cy) / halfH
            for (x in 0 until width) {
                val i = y * width + x
                val px = pixels[i]
                val nx = (x - cx) / halfW
                val w = 1.0 + CENTER_GAIN * exp(-((nx * nx + ny * ny) / twoSigmaSq).toDouble())
                totalWeight += w
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val idx = classifyRgb(r, g, b, hsv)
                classIdx[i] = idx ?: -1
                if (idx == null) continue
                weights[idx] += w
                rSum[idx] += w * r
                gSum[idx] += w * g
                bSum[idx] += w * b
            }
        }

        val targetIdx = WALK_COLORS.indexOfFirst { it.name == target.name }
        val targetWeight = if (targetIdx >= 0) weights[targetIdx] else 0.0
        val maxWeight = weights.max()
        val targetShare = if (totalWeight > 0) (targetWeight / totalWeight).toFloat() else 0f

        val globalPass = targetWeight > 0 &&
                targetWeight == maxWeight &&
                targetShare >= MIN_TARGET_SHARE

        // Path 2 only matters when global dominance failed.
        val subjectMatch = !globalPass && targetIdx >= 0 &&
                targetShare >= MIN_SUBJECT_GLOBAL_SHARE &&
                hasFocalSubject(pixels, classIdx, width, height, targetIdx)

        val passed = globalPass || subjectMatch

        // A color must hold at least MIN_DOMINANT_SHARE of the total weighted frame
        // to be named "dominant". Without this gate, 2–3% of warm-tinted neutral
        // pixels (e.g. a gray rug under incandescent light) falsely claim dominance.
        val dominantShare = if (totalWeight > 0) (maxWeight / totalWeight).toFloat() else 0f
        val dominantIdx = if (maxWeight > 0 && dominantShare >= MIN_DOMINANT_SHARE)
            weights.indexOfFirst { it == maxWeight } else -1

        val dominantName: String
        val dominantHex: String
        if (dominantIdx >= 0) {
            dominantName = WALK_COLORS[dominantIdx].name
            val n = weights[dominantIdx]
            dominantHex = String.format(
                "#%02X%02X%02X",
                Math.round(rSum[dominantIdx] / n).toInt(),
                Math.round(gSum[dominantIdx] / n).toInt(),
                Math.round(bSum[dominantIdx] / n).toInt()
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
            val share = if (totalWeight > 0) (weights[i] / totalWeight).toFloat() else 0f
            if (share > nearestShare) { nearestShare = share; nearestName = WALK_COLORS[i].name }
        }

        return ValidationResult(
            passed = passed,
            dominantHex = dominantHex,
            dominantName = dominantName,
            matchPercent = targetShare,
            actualDominantColor = dominantName,
            nearestColorName = if (nearestShare >= MIN_DOMINANT_SHARE) nearestName else null,
            nearestColorShare = nearestShare,
            subjectMatch = subjectMatch
        )
    }

    /**
     * Path 2 core: does any focal window contain a cluster where the target owns
     * ≥ [SUBJECT_FOCAL_SHARE] of the mass and beats every other walk color?
     *
     * Window mass is Gaussian around the focal point (σ = radius/2), so pixels at the
     * exact focal point matter most and influence fades smoothly — an off-center
     * subject partially overlapping a window is scored proportionally, with no hard
     * cutoff. Target pixels within [TIGHT_DELTA_E] of the day's reference swatch in
     * OKLAB earn [TIGHT_MATCH_BONUS]; the bonus raises both the target's mass and the
     * window total, so a window share can never exceed 1.
     */
    private fun hasFocalSubject(
        pixels: IntArray,
        classIdx: IntArray,
        width: Int,
        height: Int,
        targetIdx: Int
    ): Boolean {
        val radius = FOCAL_RADIUS_FRACTION * min(width, height)
        if (radius < 1f) return false
        val sigma = radius / 2f
        val twoSigmaSq = (2f * sigma * sigma).toDouble()
        val reach = 2f * radius // beyond 4σ the Gaussian is ~0; bound the pixel loop

        val targetLab = WALK_COLORS[targetIdx].hex.removePrefix("#").toInt(16).let {
            OkLab.fromSrgb((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF)
        }
        val pixelLab = FloatArray(3)
        val colorMass = DoubleArray(WALK_COLORS.size)

        for (focal in FOCAL_POINTS) {
            val fx = focal[0] * (width - 1)
            val fy = focal[1] * (height - 1)
            val x0 = (fx - reach).toInt().coerceAtLeast(0)
            val x1 = (fx + reach).toInt().coerceAtMost(width - 1)
            val y0 = (fy - reach).toInt().coerceAtLeast(0)
            val y1 = (fy + reach).toInt().coerceAtMost(height - 1)

            java.util.Arrays.fill(colorMass, 0.0)
            var totalMass = 0.0

            for (y in y0..y1) {
                val dy = y - fy
                for (x in x0..x1) {
                    val dx = x - fx
                    val fw = exp(-((dx * dx + dy * dy) / twoSigmaSq))
                    if (fw < 1e-3) continue
                    val i = y * width + x
                    val idx = classIdx[i]
                    var contribution = fw
                    if (idx == targetIdx) {
                        val px = pixels[i]
                        OkLab.fromSrgb((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF, pixelLab)
                        if (OkLab.deltaE(pixelLab, targetLab) < TIGHT_DELTA_E) {
                            contribution *= TIGHT_MATCH_BONUS
                        }
                    }
                    totalMass += contribution
                    if (idx >= 0) colorMass[idx] += contribution
                }
            }

            if (totalMass <= 0.0) continue
            val targetMass = colorMass[targetIdx]
            val share = targetMass / totalMass
            if (share >= SUBJECT_FOCAL_SHARE && targetMass == colorMass.max()) return true
        }
        return false
    }

    /**
     * Live-viewfinder fast path: the target's Gaussian-weighted share of the frame —
     * the same spatial math as Path 1 of [validatePixels], stripped to a single
     * accumulator pair. Allocation-free by design (the caller passes a reusable [hsv]
     * scratch buffer): this runs on every analyzed camera frame, and any per-frame
     * allocation here would show up as GC-driven viewfinder stutter.
     */
    fun liveTargetShare(
        pixels: IntArray,
        width: Int,
        height: Int,
        target: WalkColor,
        hsv: FloatArray
    ): Float {
        // Indexed lookup, not indexOfFirst: this runs per camera frame and a list
        // iterator per call is exactly the kind of steady-state garbage the live
        // path exists to avoid.
        var targetIdx = -1
        for (i in 0 until WALK_COLORS.size) {
            if (WALK_COLORS[i].name == target.name) { targetIdx = i; break }
        }
        if (targetIdx < 0) return 0f

        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        val halfW = width / 2f
        val halfH = height / 2f
        val twoSigmaSq = 2f * CENTER_SIGMA * CENTER_SIGMA

        var targetWeight = 0.0
        var totalWeight = 0.0
        for (y in 0 until height) {
            val ny = (y - cy) / halfH
            for (x in 0 until width) {
                val px = pixels[y * width + x]
                val nx = (x - cx) / halfW
                val w = 1.0 + CENTER_GAIN * exp(-((nx * nx + ny * ny) / twoSigmaSq).toDouble())
                totalWeight += w
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                if (classifyRgb(r, g, b, hsv) == targetIdx) targetWeight += w
            }
        }
        return if (totalWeight > 0) (targetWeight / totalWeight).toFloat() else 0f
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
