package com.colorwalk.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.colorwalk.app.ui.camera.CameraScreen
import com.colorwalk.app.ui.gallery.GalleryScreen
import com.colorwalk.app.ui.newsfeed.NewsfeedScreen
import com.colorwalk.app.ui.settings.SettingsScreen
import com.colorwalk.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import kotlin.math.abs

// Touch-slop-ish threshold (px) before Home's gesture locks onto an axis.
private const val AXIS_LOCK_PX = 12f

/**
 * Committing to a neighbor: firm and quick, damping just under critical so the
 * landing has life without a visible bounce (any overshoot past a page boundary is
 * additionally clipped by the render clamp below).
 */
private val COMMIT_SPRING: AnimationSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 350f)

/**
 * Springing back to where the gesture started: softly elastic — the page returns
 * with a small, playful bounce. Safe here because the neighbors are real rendered
 * layers, so a brief overshoot around a settled position shows actual content, and
 * the render clamp keeps the outermost edges hard.
 */
private val RETURN_SPRING: AnimationSpec<Float> = spring(dampingRatio = 0.72f, stiffness = 300f)

/**
 * Home plus its four swipe-neighbors: Camera (left, revealed by swiping right), Gallery
 * (right, swipe left), Settings (above, swipe down), Newsfeed (below, swipe up).
 *
 * None of this uses a real Pager. A Pager tracks `currentPage` continuously as a drag
 * crosses each 50% boundary — so one long, fast, unbroken swipe could walk straight
 * through Home and land on a page two steps away before the finger even lifts, no matter
 * how tightly a fling/snap distance is capped afterward (that only bounds the *fling*,
 * not the raw drag). Instead this hand-rolls two `dragX`/`dragY` values representing
 * Home's offset, each clamped every gesture to at most one page's width/height from
 * wherever THAT gesture started — so it's structurally impossible to land on, or pass
 * through to, a page that isn't the immediate neighbor of whichever page the touch began
 * on. Home's own gesture picks an axis (X or Y) once the drag clears touch slop and drives
 * only that axis for the rest of the gesture, so a diagonal touch can't smear two
 * transitions together.
 *
 * Motion design: settles are physical, not scripted. Release velocity is estimated per
 * gesture ([SwipePhysics.VelocityEstimator]), feeds the commit decision (a genuine flick
 * commits without a full 30% drag — but only with real travel behind it, see
 * [SwipePhysics.settleTarget]), and is passed into the settle spring as its initial
 * velocity so the animation inherits the finger's momentum instead of restarting from
 * zero. Commits land firmly ([COMMIT_SPRING]); aborted gestures bounce softly home
 * ([RETURN_SPRING]). The rendered translation is clamped to the page strip's bounds, so
 * spring physics can overshoot mathematically without ever exposing blank space beyond
 * the outermost pages.
 *
 * Camera only ever shows its live preview once fully settled and idle — while dragging or
 * animating, it renders solid black instead. CameraX's PreviewView draws via a hardware
 * layer that doesn't reliably keep pace with a live transform (even in COMPATIBLE mode),
 * so the feed can visibly tear mid-drag; not animating the live feed at all sidesteps that
 * rather than trying to out-tune it. Gallery/Settings/Newsfeed have no such constraint and
 * show live content throughout the drag.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeHubScreen(
    onOpenStats: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit
) {
    var viewportW by remember { mutableIntStateOf(0) }
    var viewportH by remember { mutableIntStateOf(0) }
    val dragX = remember { Animatable(0f) } // + = toward Camera (left), - = toward Gallery (right)
    val dragY = remember { Animatable(0f) } // + = toward Settings (above), - = toward Newsfeed (below)
    val scope = rememberCoroutineScope()

    // M-1: never read dragX/dragY .value or .isRunning directly in composition — they
    // change every frame of a drag/settle and would recompose the whole hub per frame.
    // The peek alpha is passed as a lambda (read inside HomeScreen's draw phase); the
    // celebration gate is derived state that only invalidates when the Boolean flips.
    val newsfeedPeekAlpha: () -> Float = {
        val h = viewportH
        if (h > 0) 1f - (-dragY.value / h).coerceIn(0f, 1f) else 1f
    }
    val hubSettledOnHome by remember {
        derivedStateOf {
            !dragX.isRunning && !dragY.isRunning && dragX.value == 0f && dragY.value == 0f
        }
    }

    fun settleX(start: Float, target: Float, velocity: Float) {
        scope.launch {
            dragX.animateTo(target, if (target == start) RETURN_SPRING else COMMIT_SPRING, initialVelocity = velocity)
        }
    }

    fun settleY(start: Float, target: Float, velocity: Float) {
        scope.launch {
            dragY.animateTo(target, if (target == start) RETURN_SPRING else COMMIT_SPRING, initialVelocity = velocity)
        }
    }

    // Programmatic navigation (buttons, back) — a commit with no finger momentum.
    fun animateXTo(target: Float) { scope.launch { dragX.animateTo(target, COMMIT_SPRING) } }
    fun animateYTo(target: Float) { scope.launch { dragY.animateTo(target, COMMIT_SPRING) } }

    // Home is reachable from all four directions, so its gesture picks an axis (whichever
    // has moved further past AXIS_LOCK_PX) then drives only that axis for the rest of the
    // gesture — never both.
    val homeDragModifier = Modifier.pointerInput(viewportW, viewportH) {
        if (viewportW <= 0 || viewportH <= 0) return@pointerInput
        var axis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical
        var totalX = 0f; var totalY = 0f // raw totals drive ONLY the axis decision
        val sessionX = SwipePhysics.OnePageDragSession()
        val sessionY = SwipePhysics.OnePageDragSession()
        detectDragGestures(
            onDragStart = {
                axis = 0
                totalX = 0f; totalY = 0f
                sessionX.begin(dragX.value, viewportW, -viewportW.toFloat(), viewportW.toFloat())
                sessionY.begin(dragY.value, viewportH, -viewportH.toFloat(), viewportH.toFloat())
            },
            onDragEnd = {
                when (axis) {
                    1 -> settleX(sessionX.startValue, sessionX.settleTarget(viewportW), sessionX.releaseVelocity)
                    2 -> settleY(sessionY.startValue, sessionY.settleTarget(viewportH), sessionY.releaseVelocity)
                }
            },
            onDragCancel = {
                when (axis) {
                    1 -> settleX(sessionX.startValue, sessionX.startValue, 0f)
                    2 -> settleY(sessionY.startValue, sessionY.startValue, 0f)
                }
            }
        ) { change, dragAmount ->
            change.consume()
            totalX += dragAmount.x
            totalY += dragAmount.y
            if (axis == 0 && (abs(totalX) > AXIS_LOCK_PX || abs(totalY) > AXIS_LOCK_PX)) {
                axis = if (abs(totalX) > abs(totalY)) 1 else 2
                // Fold the pre-lock travel into the chosen session so the page
                // doesn't jump by the slop distance (velocity baseline only —
                // the estimator ignores its first sample).
                when (axis) {
                    1 -> scope.launch { dragX.snapTo(sessionX.update(totalX - dragAmount.x, change.uptimeMillis)) }
                    2 -> scope.launch { dragY.snapTo(sessionY.update(totalY - dragAmount.y, change.uptimeMillis)) }
                }
            }
            when (axis) {
                1 -> scope.launch { dragX.snapTo(sessionX.update(dragAmount.x, change.uptimeMillis)) }
                2 -> scope.launch { dragY.snapTo(sessionY.update(dragAmount.y, change.uptimeMillis)) }
            }
        }
    }

    // Camera/Settings only have one valid direction back to Home and no scrollable
    // content of their own, so they read the raw pointer stream directly.
    val cameraDragModifier = Modifier.pointerInput(viewportW) {
        if (viewportW <= 0) return@pointerInput
        val session = SwipePhysics.OnePageDragSession()
        detectHorizontalDragGestures(
            onDragStart = { session.begin(dragX.value, viewportW, -viewportW.toFloat(), viewportW.toFloat()) },
            onDragEnd = { settleX(session.startValue, session.settleTarget(viewportW), session.releaseVelocity) },
            onDragCancel = { settleX(session.startValue, session.startValue, 0f) }
        ) { change, dragAmount ->
            change.consume()
            val target = session.update(dragAmount, change.uptimeMillis)
            scope.launch { dragX.snapTo(target) }
        }
    }

    val settingsDragModifier = Modifier.pointerInput(viewportH) {
        if (viewportH <= 0) return@pointerInput
        val session = SwipePhysics.OnePageDragSession()
        detectVerticalDragGestures(
            onDragStart = { session.begin(dragY.value, viewportH, -viewportH.toFloat(), viewportH.toFloat()) },
            onDragEnd = { settleY(session.startValue, session.settleTarget(viewportH), session.releaseVelocity) },
            onDragCancel = { settleY(session.startValue, session.startValue, 0f) }
        ) { change, dragAmount ->
            change.consume()
            val target = session.update(dragAmount, change.uptimeMillis)
            scope.launch { dragY.snapTo(target) }
        }
    }

    // Gallery and Newsfeed have their own nested scrollables, so — instead of a generic
    // drag modifier that would fight those internal gestures — they report drag deltas
    // (with event timestamps, for the velocity estimate) via callback from their own
    // proven edge-detection gestures.
    val gallerySession = remember { SwipePhysics.OnePageDragSession() }
    fun onGalleryEdgeDragStart() {
        gallerySession.begin(dragX.value, viewportW, -viewportW.toFloat(), viewportW.toFloat())
    }
    fun onGalleryEdgeDrag(dx: Float, uptimeMillis: Long) {
        val target = gallerySession.update(dx, uptimeMillis)
        scope.launch { dragX.snapTo(target) }
    }
    fun onGalleryEdgeDragEnd() {
        settleX(gallerySession.startValue, gallerySession.settleTarget(viewportW), gallerySession.releaseVelocity)
    }

    val newsfeedSession = remember { SwipePhysics.OnePageDragSession() }
    fun onNewsfeedEdgeDragStart() {
        newsfeedSession.begin(dragY.value, viewportH, -viewportH.toFloat(), viewportH.toFloat())
    }
    fun onNewsfeedEdgeDrag(dy: Float, uptimeMillis: Long) {
        val target = newsfeedSession.update(dy, uptimeMillis)
        scope.launch { dragY.snapTo(target) }
    }
    fun onNewsfeedEdgeDragEnd() {
        settleY(newsfeedSession.startValue, newsfeedSession.settleTarget(viewportH), newsfeedSession.releaseVelocity)
    }

    BackHandler(enabled = dragX.value != 0f || dragY.value != 0f) {
        if (dragX.value != 0f) animateXTo(0f) else animateYTo(0f)
    }

    // Only ever show the live camera feed once fully settled on Camera and idle — see
    // the class-level doc for why. The moment any drag or settle-animation touches dragX,
    // this flips false and the feed is replaced by a plain black box.
    val cameraRestX = viewportW.toFloat()
    val showLiveCamera = viewportW > 0 && !dragX.isRunning && dragX.value == cameraRestX

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewportW = it.width; viewportH = it.height }
    ) {
        // Rendered translations clamp to the strip's outer bounds: the springs may
        // overshoot mathematically (that's their charm on snap-backs), but the
        // outermost page edges stay pinned — no blank space, no black flashes.
        val maxX = viewportW.toFloat()
        val maxY = viewportH.toFloat()

        // Camera — to the left, revealed by swiping right.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = dragX.value.coerceIn(-maxX, maxX) - viewportW }
                .then(cameraDragModifier)
        ) {
            if (showLiveCamera) {
                CameraScreen(onBack = { animateXTo(0f) })
            } else {
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }

        // Settings — above, revealed by swiping down.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = dragY.value.coerceIn(-maxY, maxY) - viewportH }
                .then(settingsDragModifier)
        ) {
            SettingsScreen(onBack = { animateYTo(0f) }, onThemeChange = onThemeChange)
        }

        // Home — center, reachable in all four directions.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = dragX.value.coerceIn(-maxX, maxX)
                    translationY = dragY.value.coerceIn(-maxY, maxY)
                }
                .then(homeDragModifier)
        ) {
            HomeScreen(
                onOpenCamera   = { animateXTo(viewportW.toFloat()) },
                onOpenGallery  = { animateXTo(-viewportW.toFloat()) },
                onOpenSettings = { animateYTo(viewportH.toFloat()) },
                onOpenStats    = onOpenStats,
                onOpenNewsfeed = { animateYTo(-viewportH.toFloat()) },
                newsfeedPeekAlpha = newsfeedPeekAlpha,
                // Confetti earned on the Camera pane must not play off-screen: only
                // mount the celebration once the hub is settled on Home.
                celebrationVisible = hubSettledOnHome
            )
        }

        // Gallery — to the right, revealed by swiping left.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = dragX.value.coerceIn(-maxX, maxX) + viewportW }
        ) {
            GalleryScreen(
                onBack = { animateXTo(0f) },
                onEdgeDragStart = { onGalleryEdgeDragStart() },
                onEdgeDrag = { dx, t -> onGalleryEdgeDrag(dx, t) },
                onEdgeDragEnd = { onGalleryEdgeDragEnd() }
            )
        }

        // Newsfeed — below, revealed by swiping up.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = dragY.value.coerceIn(-maxY, maxY) + viewportH }
        ) {
            NewsfeedScreen(
                onBack = { animateYTo(0f) },
                onEdgeDragStart = { onNewsfeedEdgeDragStart() },
                onEdgeDrag = { dy, t -> onNewsfeedEdgeDrag(dy, t) },
                onEdgeDragEnd = { onNewsfeedEdgeDragEnd() }
            )
        }
    }
}
