package com.colorwalk.app.domain

import androidx.compose.ui.graphics.Color
import java.util.Calendar

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
    // Use local calendar day so the color never flips at UTC midnight
    val cal = Calendar.getInstance()   // local timezone
    cal.timeInMillis = dayEpoch
    val dayIndex = cal.get(Calendar.YEAR) * 366 + cal.get(Calendar.DAY_OF_YEAR)
    return WALK_COLORS[Math.floorMod(dayIndex, WALK_COLORS.size)]
}
