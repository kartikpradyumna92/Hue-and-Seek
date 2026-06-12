package com.colorwalk.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing tokens — the only dp values screens should use for padding/gaps.
 * A consistent 4dp-based rhythm keeps vertical hierarchy deliberate.
 */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Shared component dimensions. */
object Dimens {
    /** Minimum touch target per accessibility guidelines. */
    val touchTarget = 48.dp

    /** Standard screen edge padding. */
    val screenEdge = 20.dp
}
