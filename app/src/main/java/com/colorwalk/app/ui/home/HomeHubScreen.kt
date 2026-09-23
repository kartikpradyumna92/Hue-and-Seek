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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import com.colorwalk.app.ui.camera.CameraScreen
import com.colorwalk.app.ui.gallery.GalleryScreen
import com.colorwalk.app.ui.newsfeed.NewsfeedScreen
import com.colorwalk.app.ui.settings.SettingsScreen
import com.colorwalk.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import kotlin.math.abs

// Travel (dp — BUG-042: was raw px, so density-dependent) before Home's gesture locks
// onto an axis. 4.5dp ≈ the original 12px on the ~2.75× screen it was tuned on.
private const val AXIS_LOCK_DP = 4.5f

/** Fraction of a page the hub may move off Camera before the live camera is torn down. */
private const val CAMERA_LIVE_BAND = 0.12f

/** -1/0/+1 when [offset] sits exactly on a page of size [pagePx]; null between pages. */
internal fun pageOf(offset: Float, pagePx: Int): Int? = when (offset) {
    0f -> 0
    pagePx.toFloat() -> 1
    -pagePx.toFloat() -> -1
    else -> null
}

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
 * Camera only shows its live preview at or very near rest (CAMERA_LIVE_BAND) — once a
 * drag or animation takes it further away, it renders solid black instead. CameraX's PreviewView draws via a hardware
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
    // BUG-042: flick threshold in px for THIS screen's density.
    val flickPx = SwipePhysics.flickVelocityPx(LocalDensity.current.density)

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

    // BUG-008: which pane the hub is settled on (-1/0/+1 per axis), saved across
    // activity recreation (dark-mode/font/locale change, split-screen resize) and
    // process death. The offsets themselves are plain Animatables and restart at Home.
    var paneX by rememberSaveable { mutableIntStateOf(0) }
    var paneY by rememberSaveable { mutableIntStateOf(0) }

    // BUG-041: Gallery and Newsfeed (and their ViewModels' full-table queries, thumbnail
    // decodes and location backfill) are only composed once the user first heads that
    // way — not at every app start whether visited or not.
    var galleryVisited by rememberSaveable { mutableStateOf(false) }
    var newsfeedVisited by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewportW, viewportH) {
        if (viewportW <= 0 || viewportH <= 0) return@LaunchedEffect
        // Restore (or, after a resize, re-align) BEFORE recording, so the initial 0/0
        // offsets never overwrite the saved pane.
        dragX.snapTo(paneX * viewportW.toFloat())
        dragY.snapTo(paneY * viewportH.toFloat())
        // Runs in this coroutine, not in composition — per-frame reads are cheap here.
        snapshotFlow { Triple(dragX.isRunning || dragY.isRunning, dragX.value, dragY.value) }
            .collect { (running, x, y) ->
                if (x < 0f) galleryVisited = true
                if (y < 0f) newsfeedVisited = true
                if (!running) {
                    // Only exact page positions count as "settled" — mid-drag values
                    // (snapTo, not running) are never page multiples in practice.
                    pageOf(x, viewportW)?.let { paneX = it }
                    pageOf(y, viewportH)?.let { paneY = it }
                }
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
    // has moved further past AXIS_LOCK_DP) then drives only that axis for the rest of the
    // gesture — never both.
    val homeDragModifier = Modifier.pointerInput(viewportW, viewportH) {
        if (viewportW <= 0 || viewportH <= 0) return@pointerInput
        var axis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical
        var totalX = 0f; var totalY = 0f // raw totals drive ONLY the axis decision
        val sessionX = SwipePhysics.OnePageDragSession(SwipePhysics.flickVelocityPx(density))
        val sessionY = SwipePhysics.OnePageDragSession(SwipePhysics.flickVelocityPx(density))
        val axisLockPx = AXIS_LOCK_DP.dp.toPx()
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
            if (axis == 0 && (abs(totalX) > axisLockPx || abs(totalY) > axisLockPx)) {
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
        val session = SwipePhysics.OnePageDragSession(SwipePhysics.flickVelocityPx(density))
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
        val session = SwipePhysics.OnePageDragSession(SwipePhysics.flickVelocityPx(density))
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

    // BUG-011: Settings now scrolls (it was cut off on small phones / large fonts). Its
    // list reports through nested scroll: whatever the list can't use — pulling up
    // past its bottom, or any pull when the content fits — drags the hub toward Home,
    // and once the hub is displaced it keeps every delta until release, then settles
    // with the same commit rules as every other hub swipe. The raw settingsDragModifier
    // below still serves the non-scrolling top bar.
    val settingsNestedScroll = remember(viewportH, flickPx) {
        object : NestedScrollConnection {
            private val rest get() = viewportH.toFloat()
            private var tracked: Float? = null   // hub offset while this gesture drives it

            private fun drive(dy: Float): Float {
                val from = tracked ?: rest
                val to = (from + dy).coerceIn(0f, rest)
                tracked = to
                scope.launch { dragY.snapTo(to) }
                return to - from
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Drag || tracked == null) return Offset.Zero
                return Offset(0f, drive(available.y))
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Drag || viewportH <= 0 || available.y >= 0f) return Offset.Zero
                return Offset(0f, drive(available.y))
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val position = tracked ?: return Velocity.Zero
                tracked = null
                val target = SwipePhysics.settleTarget(
                    start = rest, clampMin = 0f, clampMax = rest,
                    totalDelta = position - rest, velocity = available.y, viewportPx = viewportH,
                    flickVelocityPx = flickPx
                )
                settleY(rest, target, available.y)
                return available   // the hub took this gesture; the list must not fling
            }
        }
    }

    // Gallery and Newsfeed have their own nested scrollables, so — instead of a generic
    // drag modifier that would fight those internal gestures — they report drag deltas
    // (with event timestamps, for the velocity estimate) via callback from their own
    // proven edge-detection gestures.
    val gallerySession = remember(flickPx) { SwipePhysics.OnePageDragSession(flickPx) }
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

    val newsfeedSession = remember(flickPx) { SwipePhysics.OnePageDragSession(flickPx) }
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

    // BUG-010: both of these used to read dragX/dragY directly in composition, which
    // recomposed the entire hub on every frame of every swipe (contradicting M-1 above).
    // As derived state they invalidate only when the Boolean actually flips.
    val backEnabled by remember { derivedStateOf { dragX.value != 0f || dragY.value != 0f } }
    BackHandler(enabled = backEnabled) {
        if (dragX.value != 0f) animateXTo(0f) else animateYTo(0f)
    }

    // The live feed only runs near rest on Camera — see the class-level doc for why;
    // beyond the band it's replaced by a plain black box.
    // BUG-043: live within a small band around the Camera page, not only at exact rest:
    // a sideways nudge (e.g. a pinch whose first finger lands a moment early) used to
    // tear the camera down and rebind it. Tear-down still happens early in any real
    // swipe away, where the preview lagging the page transform would show.
    val showLiveCamera by remember {
        derivedStateOf {
            viewportW > 0 && abs(dragX.value - viewportW) <= viewportW * CAMERA_LIVE_BAND
        }
    }
    // Gallery handles system back itself (viewer/album layers) only while it's the
    // settled pane — BUG-007.
    val gallerySettled by remember {
        derivedStateOf { viewportW > 0 && !dragX.isRunning && dragX.value == -viewportW.toFloat() }
    }

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
            SettingsScreen(
                onBack = { animateYTo(0f) },
                onThemeChange = onThemeChange,
                nestedScrollConnection = settingsNestedScroll
            )
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
            if (galleryVisited) {
                GalleryScreen(
                    onBack = { animateXTo(0f) },
                    onEdgeDragStart = { onGalleryEdgeDragStart() },
                    onEdgeDrag = { dx, t -> onGalleryEdgeDrag(dx, t) },
                    onEdgeDragEnd = { onGalleryEdgeDragEnd() },
                    isActive = gallerySettled
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
        }

        // Newsfeed — below, revealed by swiping up.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = dragY.value.coerceIn(-maxY, maxY) + viewportH }
        ) {
            if (newsfeedVisited) {
                NewsfeedScreen(
                    onBack = { animateYTo(0f) },
                    onEdgeDragStart = { onNewsfeedEdgeDragStart() },
                    onEdgeDrag = { dy, t -> onNewsfeedEdgeDrag(dy, t) },
                    onEdgeDragEnd = { onNewsfeedEdgeDragEnd() }
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
        }
    }
}
