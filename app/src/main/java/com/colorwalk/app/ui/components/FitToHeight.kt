package com.colorwalk.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt

/**
 * Lays [content] out at its natural height and, only when that exceeds the space
 * available, scales it down uniformly (anchored top-center) so it fits instead of
 * pushing later siblings off-screen (BUG-013: small phones, landscape, large font).
 *
 * For fixed, non-scrolling panes that can't host a scroll container — e.g. Home,
 * whose every vertical drag belongs to the hub. The layer transform also applies
 * to hit-testing, so scaled content stays tappable where it's drawn.
 */
@Composable
fun FitToHeight(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = { Box { content() } }, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.single().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        )
        val scale = if (constraints.hasBoundedHeight && placeable.height > constraints.maxHeight)
            constraints.maxHeight.toFloat() / placeable.height else 1f
        val width = constraints.constrainWidth(placeable.width)
        val height = constraints.constrainHeight((placeable.height * scale).roundToInt())
        layout(width, height) {
            placeable.placeWithLayer((width - placeable.width) / 2, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
        }
    }
}
