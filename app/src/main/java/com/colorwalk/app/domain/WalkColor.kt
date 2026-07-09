package com.colorwalk.app.domain

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId

data class WalkColor(
    val name: String,
    val hex: String,
    val composeColor: Color
)

val WALK_COLORS = listOf(
    WalkColor("Red",    "#E53935", Color(0xFFE53935)),
    WalkColor("Orange", "#FB8C00", Color(0xFFFB8C00)),
    WalkColor("Yellow", "#FDD835", Color(0xFFFDD835)),
    WalkColor("Green",  "#43A047", Color(0xFF43A047)),
    WalkColor("Blue",   "#1E88E5", Color(0xFF1E88E5)),
    WalkColor("Purple", "#8E24AA", Color(0xFF8E24AA)),
    WalkColor("Pink",   "#E91E63", Color(0xFFE91E63)),
    WalkColor("Brown",  "#6D4C41", Color(0xFF6D4C41))
)

fun colorForDay(dayEpoch: Long): WalkColor {
    // Local-calendar epoch day: flips at LOCAL midnight (never UTC), and the index is
    // continuous across year boundaries. The previous YEAR*366 + DAY_OF_YEAR formula
    // advanced by 2 across every non-leap New Year, silently skipping one color in
    // the 8-color rotation (H-5).
    val dayIndex = Instant.ofEpochMilli(dayEpoch)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toEpochDay()
    return WALK_COLORS[Math.floorMod(dayIndex, WALK_COLORS.size.toLong()).toInt()]
}
