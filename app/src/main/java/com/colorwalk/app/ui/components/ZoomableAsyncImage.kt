package com.colorwalk.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage

/** Holds pinch-zoom scale/pan for one [ZoomableAsyncImage]. Create with [rememberZoomState]. */
class ZoomState(initialScale: Float = 1f) {
    var scale by mutableFloatStateOf(initialScale)
    var offset by mutableStateOf(Offset.Zero)
    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

@Composable
fun rememberZoomState(resetKey: Any? = Unit): ZoomState =
    remember(resetKey) { ZoomState() }

/**
 * Google-Photos-style pinch-zoom-and-pan image, shared by every full-screen photo
 * viewer (gallery, newsfeed, stats). Two fingers always pinch/pan; one finger pans
 * only once already zoomed in — otherwise raw drag events are handed to
 * [onUnzoomedDrag]/[onUnzoomedGestureEnd] so the caller can drive its own gesture
 * (pager swipe, swipe-to-dismiss) without fighting this one for the pointer.
 *
 * The gesture detector is keyed on [state]'s identity, not its scale — restarting
 * the detector on every scale tick (keying on the scale value itself) is what
 * makes a pinch stall after its first frame, since Compose cancels and relaunches
 * `pointerInput` whenever its key changes.
 */
@Composable
fun ZoomableAsyncImage(
    model: Any?,
    contentDescription: String?,
    state: ZoomState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    minScale: Float = 1f,
    maxScale: Float = 5f,
    onGestureStart: () -> Unit = {},
    onUnzoomedDrag: (PointerInputChange, Offset) -> Unit = { _, _ -> },
    onUnzoomedGestureEnd: () -> Unit = {}
) {
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { imageSize = it }
            .pointerInput(state) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onGestureStart()
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (state.scale <= minScale) onUnzoomedGestureEnd()
                            break
                        }
                        when {
                            pressed.size >= 2 -> {
                                event.changes.forEach { it.consume() }
                                val c1 = pressed[0]
                                val c2 = pressed[1]
                                val zoomFactor = pinchScaleFactor(c1, c2)
                                val prevCentroid = (c1.previousPosition + c2.previousPosition) / 2f
                                val currCentroid = (c1.position + c2.position) / 2f
                                val panDelta = currCentroid - prevCentroid
                                state.scale = (state.scale * zoomFactor).coerceIn(minScale, maxScale)
                                val maxX = ((imageSize.width * (state.scale - 1)) / 2f).coerceAtLeast(0f)
                                val maxY = ((imageSize.height * (state.scale - 1)) / 2f).coerceAtLeast(0f)
                                state.offset = Offset(
                                    (state.offset.x + panDelta.x).coerceIn(-maxX, maxX),
                                    (state.offset.y + panDelta.y).coerceIn(-maxY, maxY)
                                )
                            }
                            state.scale > minScale -> {
                                event.changes.forEach { it.consume() }
                                val change = pressed[0]
                                val panDelta = change.position - change.previousPosition
                                val maxX = ((imageSize.width * (state.scale - 1)) / 2f).coerceAtLeast(0f)
                                val maxY = ((imageSize.height * (state.scale - 1)) / 2f).coerceAtLeast(0f)
                                state.offset = Offset(
                                    (state.offset.x + panDelta.x).coerceIn(-maxX, maxX),
                                    (state.offset.y + panDelta.y).coerceIn(-maxY, maxY)
                                )
                            }
                            else -> onUnzoomedDrag(pressed[0], pressed[0].positionChange())
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = state.scale,
                    scaleY = state.scale,
                    translationX = state.offset.x,
                    translationY = state.offset.y
                )
        )
    }
}
