package com.colorwalk.app.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.colorwalk.app.domain.WALK_COLORS
import com.colorwalk.app.domain.colorForDay
import com.colorwalk.app.ui.home.SwipePhysics
import com.colorwalk.app.ui.theme.DayTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Mirrors HomeHubScreen's COMMIT_SPRING/RETURN_SPRING exactly, so onboarding's swipe
// is not just "similar" but the identical physical feel as the rest of the app: firm
// on a commit, softly elastic snapping back to where the gesture started.
private val ONBOARDING_COMMIT_SPRING: AnimationSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 350f)
private val ONBOARDING_RETURN_SPRING: AnimationSpec<Float> = spring(dampingRatio = 0.72f, stiffness = 300f)

private data class Page(
    val title: String,
    val body: String,
    val icon: ImageVector? = null,
    val iconTint: Color = Color.White,
    val solidCircle: Boolean = false,
    val interactive: Boolean = false
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val accent = remember { colorForDay(System.currentTimeMillis()).composeColor }

    val pages = remember {
        listOf(
            Page(
                title = "A new color, every day",
                body = "Each day Hue & Seek gives you one color to hunt down in the real world. Step outside and find it.",
                solidCircle = true
            ),
            Page(
                title = "Isolate the color",
                body = "Drag today's color into the ring — that's the whole game. Out in the world, you'll frame it with your camera instead.",
                interactive = true
            ),
            Page(
                title = "Capture & validate",
                body = "Take a photo where today's color stars in the frame. The app checks automatically — no cheating!",
                icon = Icons.Default.CameraAlt,
                iconTint = accent
            ),
            Page(
                title = "Build your streak",
                body = "One photo per day keeps your streak alive. Miss a day and it resets to zero. How far can you go?",
                icon = Icons.Default.LocalFireDepartment,
                iconTint = Color(0xFFFF6D00)
            )
        )
    }

    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val isLast = pageIndex == pages.lastIndex
    val scope = rememberCoroutineScope()

    // Live paging drag — the exact same model as HomeHubScreen: pages live at FIXED
    // absolute positions (page i at i × width) on a strip whose offset is one
    // continuous Animatable that is never reset or re-keyed. dragX settled on page i
    // is exactly -i × width, so updating pageIndex after a commit changes NOTHING
    // visually — the previous implementation re-keyed content on pageIndex and
    // zeroed the offset in a separate step, and any frame between those two writes
    // rendered the new page a full width off-screen (the "half shown" bug).
    var viewportW by remember { mutableIntStateOf(0) }
    val dragX = remember { Animatable(0f) }

    // First measure / rotation: pin the strip to the current page instantly.
    LaunchedEffect(viewportW) {
        if (viewportW > 0) dragX.snapTo(-pageIndex * viewportW.toFloat())
    }

    fun settleTo(targetPage: Int, velocity: Float, committed: Boolean) {
        pageIndex = targetPage // positions are absolute — safe to update before animating
        scope.launch {
            dragX.animateTo(
                -targetPage * viewportW.toFloat(),
                if (committed) ONBOARDING_COMMIT_SPRING else ONBOARDING_RETURN_SPRING,
                initialVelocity = velocity
            )
        }
    }

    // Next/Get Started button — moves the strip through the identical commit spring
    // as a real swipe, so a tap and a swipe feel like the same physical system.
    fun goNext() {
        if (isLast) { onFinish(); return }
        settleTo(pageIndex + 1, velocity = 0f, committed = true)
    }

    val pagingDragModifier = Modifier.pointerInput(viewportW) {
        if (viewportW <= 0) return@pointerInput
        val stripMin = -(pages.lastIndex) * viewportW.toFloat()
        // Same per-gesture rule as the hub, via the shared session (I-2): at most one
        // page of travel from wherever THIS gesture started, intersected with the
        // strip's ends.
        val session = SwipePhysics.OnePageDragSession()
        detectHorizontalDragGestures(
            onDragStart = { session.begin(dragX.value, viewportW, stripMin, 0f) },
            onDragEnd = {
                val target = session.settleTarget(viewportW)
                val targetPage = (-target / viewportW).roundToInt().coerceIn(0, pages.lastIndex)
                settleTo(targetPage, session.releaseVelocity, committed = target != session.startValue)
            },
            onDragCancel = { settleTo(pageIndex, 0f, committed = false) }
        ) { change, dragAmount ->
            change.consume()
            val target = session.update(dragAmount, change.uptimeMillis)
            scope.launch { dragX.snapTo(target) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), MaterialTheme.colorScheme.background)))
            .then(pagingDragModifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Skip row
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 32.dp, end = 32.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (!isLast) {
                    TextButton(onClick = onFinish) {
                        Text("Skip", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f), fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Page strip: full-bleed so a page's width equals the swipe travel
            // distance exactly (each page carries its own horizontal padding), and
            // clipped so a neighbor peeking during the elastic snap-back stays a
            // clean pager peek. Only the current page and its real neighbors are
            // composed; each sits at its fixed absolute slot.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp)
                    .onSizeChanged { viewportW = it.width }
                    .clipToBounds()
            ) {
                val first = (pageIndex - 1).coerceAtLeast(0)
                val last = (pageIndex + 1).coerceAtMost(pages.lastIndex)
                for (i in first..last) {
                    key(i) {
                        OnboardingPageContent(
                            page = pages[i],
                            accent = accent,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationX = dragX.value + i * viewportW }
                                .padding(horizontal = 32.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Dot indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.indices.forEach { i ->
                    val active = i == pageIndex
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .size(if (active) 10.dp else 7.dp)
                            .background(if (active) accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f))
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { goNext() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Text(
                    if (isLast) "Get Started" else "Next",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    // WCAG-derived, not hardcoded white: white on a Yellow/Orange
                    // accent day was unreadable.
                    color = DayTheme.palette.onAccent
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun OnboardingPageContent(page: Page, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (page.interactive) {
            ColorCatchPlayground(accent = accent)
            Spacer(Modifier.height(20.dp))
        } else {
            PageCircle(accent = accent, page = page, size = 164.dp)
            Spacer(Modifier.height(48.dp))
        }
        Text(
            page.title,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            lineHeight = 32.sp
        )
        Spacer(Modifier.height(14.dp))
        Text(
            page.body,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
            textAlign = TextAlign.Center,
            lineHeight = 23.sp
        )
    }
}

// ── Gamified physics playground ───────────────────────────────────────────────

/** One draggable color dot: spring-animated position anchored to a home slot. */
private class DotState(val color: Color, val isTarget: Boolean, val home: Offset) {
    val position = Animatable(home, Offset.VectorConverter)
}

/**
 * The "isolate the color" mini-game: five dots, one in today's color; drag it into
 * the pulsing ring. Everything is physics-driven — dots track the finger 1:1 while
 * dragged, spring home with a soft bounce when released anywhere else (or when the
 * wrong color is dropped in the ring), and the right color snaps into the ring's
 * center with a satisfied overshoot. Teaches the app's one verb (isolate today's
 * color from a busy scene) by hand before the camera ever opens.
 */
@Composable
private fun ColorCatchPlayground(accent: Color, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var caught by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(250.dp)
    ) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val dotSize = 44.dp
        val dotRadiusPx = with(density) { dotSize.toPx() } / 2f
        val ringRadius = 48.dp
        val ringRadiusPx = with(density) { ringRadius.toPx() }
        val ringCenter = Offset(wPx / 2f, hPx * 0.30f)

        val dots = remember(wPx, hPx) {
            val decoys = WALK_COLORS.map { it.composeColor }.filter { it != accent }.take(4)
            // Fixed interleave keeps the target off the edges but not always center.
            val colors = listOf(decoys[0], decoys[1], accent, decoys[2], decoys[3])
            colors.mapIndexed { i, c ->
                DotState(
                    color = c,
                    isTarget = c == accent,
                    home = Offset(wPx * (0.14f + 0.18f * i), hPx * 0.82f)
                )
            }
        }

        // Ring breathes gently until fed the right color — a silent "put it here".
        val pulse by rememberInfiniteTransition(label = "ringPulse").animateFloat(
            initialValue = 1f,
            targetValue = 1.07f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "ringScale"
        )

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (ringCenter.x - ringRadiusPx).roundToInt(),
                        (ringCenter.y - ringRadiusPx).roundToInt()
                    )
                }
                .size(ringRadius * 2)
                .graphicsLayer {
                    if (!caught) { scaleX = pulse; scaleY = pulse }
                }
                .border(
                    width = if (caught) 4.dp else 3.dp,
                    color = if (caught) accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                    shape = CircleShape
                )
                .background(
                    if (caught) accent.copy(alpha = 0.15f) else Color.Transparent,
                    CircleShape
                )
        )

        dots.forEach { dot ->
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dot.position.value.x - dotRadiusPx).roundToInt(),
                            (dot.position.value.y - dotRadiusPx).roundToInt()
                        )
                    }
                    .size(dotSize)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(dot.color)
                    .pointerInput(dot) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch { dot.position.snapTo(dot.position.value + dragAmount) }
                            },
                            onDragEnd = {
                                val inRing = (dot.position.value - ringCenter).getDistance() < ringRadiusPx
                                scope.launch {
                                    if (inRing && dot.isTarget) {
                                        caught = true
                                        // Lands with a small overshoot — "click", not "thud".
                                        dot.position.animateTo(
                                            ringCenter,
                                            spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    } else {
                                        // Wrong color (or missed): bounce back home.
                                        dot.position.animateTo(
                                            dot.home,
                                            spring(
                                                dampingRatio = Spring.DampingRatioLowBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    dot.position.animateTo(dot.home, spring(stiffness = Spring.StiffnessMediumLow))
                                }
                            }
                        )
                    }
            )
        }

        if (caught) {
            Text(
                "That's the hunt!",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = with(density) { (ringCenter.y + ringRadiusPx).toDp() } + 10.dp)
            )
        }
    }
}

@Composable
private fun PageCircle(accent: Color, page: Page, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (page.solidCircle) accent else accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        page.icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = page.iconTint,
                modifier = Modifier.size(size * 0.42f)
            )
        }
    }
}
