package com.colorwalk.app.ui.components

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision

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
    // Intrinsic size of the loaded image — lets panning stop at the photo's real
    // edges rather than the (letterboxed) box's (BUG-058). Unknown while loading.
    var intrinsic by remember(model) { mutableStateOf(Size.Unspecified) }

    fun limits(): Offset {
        val box = Size(imageSize.width.toFloat(), imageSize.height.toFloat())
        val content = if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f && box.width > 0f) {
            val f = contentScale.computeScaleFactor(intrinsic, box)
            Size(intrinsic.width * f.scaleX, intrinsic.height * f.scaleY)
        } else box
        return panLimits(box.width, box.height, content.width, content.height, state.scale)
    }

    fun panBy(delta: Offset) {
        val max = limits()
        state.offset = Offset(
            (state.offset.x + delta.x).coerceIn(-max.x, max.x),
            (state.offset.y + delta.y).coerceIn(-max.y, max.y)
        )
    }

    // BUG-058: the base image is decoded at view (screen) size, so 3-5× zoom only
    // magnified screen pixels. While zoomed, a higher-resolution decode (longest side
    // ≤ HI_RES_PX — a hardware bitmap, not Java heap) is layered on top; it's
    // dropped again at 1×, so only the photo being inspected pays for it.
    val context = LocalContext.current
    val hiResModel = remember(model) { hiResRequestFor(context, model) }
    val zoomedIn by remember(state) { derivedStateOf { state.scale > minScale + 0.05f } }

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
                                panBy(panDelta)   // also re-clamps after a zoom-out
                            }
                            state.scale > minScale -> {
                                event.changes.forEach { it.consume() }
                                val change = pressed[0]
                                panBy(change.position - change.previousPosition)
                            }
                            else -> onUnzoomedDrag(pressed[0], pressed[0].positionChange())
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Lambda graphicsLayer: scale/offset are read in the draw phase, so a pinch
        // no longer recomposes this composable on every frame.
        val zoomTransform = Modifier.graphicsLayer {
            scaleX = state.scale
            scaleY = state.scale
            translationX = state.offset.x
            translationY = state.offset.y
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            onSuccess = { intrinsic = it.painter.intrinsicSize },
            modifier = Modifier.fillMaxSize().then(zoomTransform)
        )
        if (zoomedIn) {
            AsyncImage(
                model = hiResModel,
                contentDescription = null,   // the base image carries the description
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().then(zoomTransform)
            )
        }
    }
}

/** Longest-side cap for the zoomed-in decode (BUG-058). */
private const val HI_RES_PX = 4096

/**
 * The same image as [model], decoded up to [HI_RES_PX] instead of view size, under
 * its own cache key. No placeholder/error drawable: until (or unless) it loads, the
 * base image underneath stays visible.
 */
private fun hiResRequestFor(context: Context, model: Any?): ImageRequest {
    val base = model as? ImageRequest
    val builder = base?.newBuilder() ?: ImageRequest.Builder(context).data(model)
    base?.memoryCacheKey?.let { builder.memoryCacheKey("${it.key}::hi") }
    return builder
        .size(HI_RES_PX)
        .precision(Precision.INEXACT)
        .placeholder(null as Drawable?)
        .error(null as Drawable?)
        .crossfade(false)
        .build()
}
