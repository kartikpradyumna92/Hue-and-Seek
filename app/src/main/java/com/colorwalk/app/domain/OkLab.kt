package com.colorwalk.app.domain

import kotlin.math.cbrt
import kotlin.math.sqrt

/**
 * sRGB → OKLAB conversion and perceptual color distance (ΔE), pure Kotlin so it runs
 * (and tests run) on the JVM without Android.
 *
 * OKLAB is a perceptually uniform color space: the Euclidean distance between two
 * OKLAB coordinates tracks how different the colors *look*, which HSV distance does
 * not (equal hue steps look wildly unequal, and saturation/value interact). The
 * validator uses ΔE against the day's reference swatch to decide whether a pixel is a
 * *tight* match for the target color — e.g. a fire-truck red vs. a brick red on a Red
 * day — which coarse hue-band bucketing can't express.
 *
 * Reference: Björn Ottosson, "A perceptual color space for image processing" (2020).
 * The matrix constants below are his published sRGB↔OKLAB coefficients.
 */
internal object OkLab {

    /** Converts 8-bit sRGB channels to OKLAB, writing (L, a, b) into [out]. */
    fun fromSrgb(r8: Int, g8: Int, b8: Int, out: FloatArray) {
        val r = srgbToLinear(r8 / 255f)
        val g = srgbToLinear(g8 / 255f)
        val b = srgbToLinear(b8 / 255f)

        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)

        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** Convenience wrapper allocating a fresh (L, a, b) array. */
    fun fromSrgb(r8: Int, g8: Int, b8: Int): FloatArray =
        FloatArray(3).also { fromSrgb(r8, g8, b8, it) }

    /** Euclidean distance between two OKLAB coordinates — perceptual ΔE. */
    fun deltaE(lab1: FloatArray, lab2: FloatArray): Float {
        val dl = lab1[0] - lab2[0]
        val da = lab1[1] - lab2[1]
        val db = lab1[2] - lab2[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f
        else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
}
