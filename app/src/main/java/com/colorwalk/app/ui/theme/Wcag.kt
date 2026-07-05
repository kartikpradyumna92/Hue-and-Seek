package com.colorwalk.app.ui.theme

/**
 * WCAG 2.1 relative-luminance and contrast-ratio math on plain ARGB ints — pure
 * Kotlin so the accessibility guarantees are provable in JVM unit tests.
 *
 * The chromatic theme engine retints the app with the walk color of the day, which
 * can be anything from near-black Brown to blazing Yellow. Text/icon colors painted
 * on top of the daily accent therefore CANNOT be hardcoded (white on a Yellow day is
 * illegible); they must be derived from the accent's measured luminance every day.
 */
object Wcag {

    /** WCAG AA minimum contrast for normal text. */
    const val AA_NORMAL_TEXT = 4.5

    /** WCAG 2.1 relative luminance of an sRGB color, 0.0 (black) … 1.0 (white). */
    fun relativeLuminance(argb: Int): Double {
        fun channel(c8: Int): Double {
            val c = c8 / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG contrast ratio between two colors: 1.0 (identical) … 21.0 (black/white). */
    fun contrastRatio(argb1: Int, argb2: Int): Double {
        val l1 = relativeLuminance(argb1)
        val l2 = relativeLuminance(argb2)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Picks black or white content color for text/icons drawn on [background]:
     * white when it clears AA for normal text, otherwise whichever of the two
     * contrasts harder. Every walk color yields ≥ AA with this rule (unit-tested),
     * so on-accent content is always legible regardless of the day.
     */
    fun contentColorFor(background: Int): Int {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        if (contrastRatio(white, background) >= AA_NORMAL_TEXT) return white
        return if (contrastRatio(white, background) >= contrastRatio(black, background)) white else black
    }

    /** Linear per-channel blend of [from] toward [to] by [fraction] (0 = from, 1 = to). */
    fun blend(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun mix(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + ((b - a) * f)).toInt().coerceIn(0, 255)
        }
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
