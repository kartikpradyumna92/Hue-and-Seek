package com.colorwalk.app.domain

import androidx.compose.ui.graphics.Color
import java.util.Calendar

data class WalkColor(
    val name: String,
    val hex: String,
    val composeColor: Color,
    // HSV bounds for dominant-color validation
    val hueMin: Float,
    val hueMax: Float,
    val satMin: Float = 0.35f,
    val valMin: Float = 0.20f
)

val WALK_COLORS = listOf(
    WalkColor("Red",    "#E53935", Color(0xFFE53935), hueMin = 345f, hueMax = 360f + 15f, satMin = 0.40f),
    WalkColor("Orange", "#FB8C00", Color(0xFFFB8C00), hueMin = 20f,  hueMax = 45f),
    WalkColor("Yellow", "#FDD835", Color(0xFFFDD835), hueMin = 45f,  hueMax = 70f),
    WalkColor("Green",  "#43A047", Color(0xFF43A047), hueMin = 75f,  hueMax = 175f, satMin = 0.25f, valMin = 0.15f),
    WalkColor("Blue",   "#1E88E5", Color(0xFF1E88E5), hueMin = 195f, hueMax = 255f),
    WalkColor("Purple", "#8E24AA", Color(0xFF8E24AA), hueMin = 270f, hueMax = 310f),
    WalkColor("Pink",   "#E91E63", Color(0xFFE91E63), hueMin = 310f, hueMax = 345f),
    WalkColor("Brown",  "#6D4C41", Color(0xFF6D4C41), hueMin = 10f,  hueMax = 25f, satMin = 0.20f, valMin = 0.15f)
)

fun colorForDay(dayEpoch: Long): WalkColor {
    // Use local calendar day so the color never flips at UTC midnight
    val cal = Calendar.getInstance()   // local timezone
    cal.timeInMillis = dayEpoch
    val dayIndex = cal.get(Calendar.YEAR) * 366 + cal.get(Calendar.DAY_OF_YEAR)
    return WALK_COLORS[Math.floorMod(dayIndex, WALK_COLORS.size)]
}
