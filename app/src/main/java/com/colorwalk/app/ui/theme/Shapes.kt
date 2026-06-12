package com.colorwalk.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Generous rounding everywhere — part of the bold & playful identity.
 * Components get these automatically via MaterialTheme; custom clips should
 * use MaterialTheme.shapes rather than ad-hoc RoundedCornerShape values.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),    // chips, small badges
    small = RoundedCornerShape(12.dp),        // photo tiles, text fields
    medium = RoundedCornerShape(16.dp),       // cards
    large = RoundedCornerShape(20.dp),        // hero cards, dialogs
    extraLarge = RoundedCornerShape(28.dp)    // bottom sheets, result cards
)
