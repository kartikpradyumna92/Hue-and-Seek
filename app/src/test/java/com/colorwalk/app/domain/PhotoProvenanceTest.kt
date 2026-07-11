package com.colorwalk.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoProvenanceTest {

    @Test
    fun encode_parse_roundTripsColorAndDominant() {
        val tag = PhotoProvenance.parse(PhotoProvenance.encode("Red", "#AB1234"))
        assertEquals("Red", tag?.colorName)
        assertEquals("#AB1234", tag?.dominantHex)
    }

    @Test
    fun parse_everyWalkColor_roundTrips() {
        for (color in WALK_COLORS) {
            val tag = PhotoProvenance.parse(PhotoProvenance.encode(color.name, color.hex))
            assertEquals(color.name, tag?.colorName)
            assertEquals(color.hex, tag?.dominantHex)
        }
    }

    @Test
    fun parse_nullOrForeignComment_returnsNull() {
        assertNull(PhotoProvenance.parse(null))
        assertNull(PhotoProvenance.parse(""))
        assertNull(PhotoProvenance.parse("Shot on MyPhone 12"))
        assertNull(PhotoProvenance.parse("color=Red;dominant=#AB1234")) // missing prefix
    }

    @Test
    fun parse_unknownColorName_returnsNull() {
        // The color drives album bucketing — never accept a name outside the palette.
        assertNull(PhotoProvenance.parse("hueseek:color=Turquoise;dominant=#00FFFF"))
    }

    @Test
    fun parse_missingOrMalformedDominant_fallsBackToReferenceSwatch() {
        val blueRef = WALK_COLORS.first { it.name == "Blue" }.hex
        assertEquals(blueRef, PhotoProvenance.parse("hueseek:color=Blue")?.dominantHex)
        assertEquals(blueRef, PhotoProvenance.parse("hueseek:color=Blue;dominant=oops")?.dominantHex)
        assertEquals(blueRef, PhotoProvenance.parse("hueseek:color=Blue;dominant=#12345")?.dominantHex)
    }

    @Test
    fun parse_ignoresUnknownFields() {
        val tag = PhotoProvenance.parse("hueseek:color=Green;dominant=#012345;future=stuff")
        assertEquals("Green", tag?.colorName)
        assertEquals("#012345", tag?.dominantHex)
    }
}
