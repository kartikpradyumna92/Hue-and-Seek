package com.colorwalk.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange

/**
 * Scale factor of a two-pointer pinch between the previous and current frame —
 * shared by the camera zoom and every photo viewer (I-2: the same distance-ratio
 * math was previously duplicated in both). Returns 1f for a degenerate gesture
 * (coincident previous positions).
 */
/**
 * Largest pan offset per axis for content of on-screen size [contentW]×[contentH]
 * (as fitted in a [boxW]×[boxH] box) zoomed by [scale] about the box center — the
 * image edge stops at the box edge instead of panning into the letterbox bars
 * (BUG-058). An axis where the zoomed content is still smaller than the box can't pan.
 */
fun panLimits(boxW: Float, boxH: Float, contentW: Float, contentH: Float, scale: Float): Offset =
    Offset(
        ((contentW * scale - boxW) / 2f).coerceAtLeast(0f),
        ((contentH * scale - boxH) / 2f).coerceAtLeast(0f)
    )

fun pinchScaleFactor(a: PointerInputChange, b: PointerInputChange): Float {
    val prevDist = (a.previousPosition - b.previousPosition).getDistance()
    val currDist = (a.position - b.position).getDistance()
    return if (prevDist > 0f) currDist / prevDist else 1f
}
