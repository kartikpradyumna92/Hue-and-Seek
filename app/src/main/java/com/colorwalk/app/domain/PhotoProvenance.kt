package com.colorwalk.app.domain

/**
 * Capture provenance embedded in the EXIF UserComment of the public MediaStore copy.
 *
 * Reinstall recovery (sync Pass 2) previously fabricated both the walk color and the
 * dominant hex from colorForDay(dateTaken) — wrong whenever the capture happened
 * under a different color (timezone changes, the pre-1.26 year-boundary index skip)
 * and always wrong for the dominant swatch, which never came from the pixels (M-9).
 * Stamping the actual values at publish time lets recovery restore the photo to the
 * album it was really captured for.
 *
 * Pure Kotlin so the round-trip is JVM-testable. The format is deliberately tiny and
 * versioned by its prefix: "hueseek:color=Red;dominant=#AB1234".
 */
object PhotoProvenance {

    private const val PREFIX = "hueseek:"
    private val HEX_RE = Regex("^#[0-9A-Fa-f]{6}$")

    /** Data recovered from (or destined for) the EXIF UserComment tag. */
    data class Tag(val colorName: String, val dominantHex: String)

    fun encode(colorName: String, dominantHex: String): String =
        "${PREFIX}color=$colorName;dominant=$dominantHex"

    /**
     * Parses a UserComment written by [encode]. Returns null for anything else —
     * unknown color names are rejected (the value drives album bucketing), and a
     * missing/malformed dominant hex falls back to the color's reference swatch.
     */
    fun parse(comment: String?): Tag? {
        if (comment == null || !comment.startsWith(PREFIX)) return null
        val fields = comment.removePrefix(PREFIX).split(';')
            .mapNotNull { field ->
                field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }
            .toMap()
        val color = WALK_COLORS.firstOrNull { it.name == fields["color"] } ?: return null
        val dominant = fields["dominant"]?.takeIf { it.matches(HEX_RE) } ?: color.hex
        return Tag(color.name, dominant)
    }
}
