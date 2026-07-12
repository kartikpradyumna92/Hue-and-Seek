package com.colorwalk.app.ui.components

import androidx.compose.ui.input.pointer.PointerInputChange

/**
 * Scale factor of a two-pointer pinch between the previous and current frame —
 * shared by the camera zoom and every photo viewer (I-2: the same distance-ratio
 * math was previously duplicated in both). Returns 1f for a degenerate gesture
 * (coincident previous positions).
 */
fun pinchScaleFactor(a: PointerInputChange, b: PointerInputChange): Float {
    val prevDist = (a.previousPosition - b.previousPosition).getDistance()
    val currDist = (a.position - b.position).getDistance()
    return if (prevDist > 0f) currDist / prevDist else 1f
}
