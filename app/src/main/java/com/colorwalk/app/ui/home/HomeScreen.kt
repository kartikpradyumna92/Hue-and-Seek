package com.colorwalk.app.ui.home

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.colorForDay
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.ui.components.photoImageRequest
import com.colorwalk.app.viewmodel.CelebrationState
import com.colorwalk.app.viewmodel.HomeViewModel
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.compose.OnParticleSystemUpdateListener
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.PartySystem
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

private fun streakMessage(capturedToday: Boolean, streak: Int, colorName: String): String {
    return if (capturedToday) {
        when {
            streak >= 30 -> "30+ days! You have an extraordinary eye for color."
            streak >= 21 -> "${streak} days — the 30-day mark is close. $colorName was a great find."
            streak >= 14 -> "Two weeks strong! $colorName was a great find today."
            streak >= 7  -> "One full week! Your eye is getting sharper every day."
            streak >= 3  -> "Great work! $colorName captured. Keep the momentum!"
            streak == 1  -> "First walk complete! Come back tomorrow to build your streak."
            else         -> "Today's walk complete! See you tomorrow."
        }
    } else {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour < 10   -> "Morning light is perfect for finding $colorName. Go for a walk!"
            hour < 13   -> "Great time for a color walk! $colorName is all around you."
            hour < 17   -> "Afternoon light is golden — $colorName awaits outside."
            hour < 20   -> "Evening colors pop beautifully. Don't miss today's $colorName!"
            else        -> "Still time before midnight! Go find some $colorName."
        }
    }
}

@Composable
fun HomeScreen(
    onOpenCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenNewsfeed: () -> Unit,
    // 1f at rest, fading to 0f as the up-swipe drags Newsfeed into view — driven live by
    // HomeHubScreen's pager offset so the peek strip dissolves instead of sitting duplicated
    // under the incoming "Your Walks" list mid-drag.
    newsfeedPeekAlpha: Float = 1f,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val recentPhotos by viewModel.recentPhotos.collectAsState()
    val color = state.colorOfDay
    val context = LocalContext.current

    // Refresh state whenever the screen resumes (e.g. returning from camera)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // In-app review — fires once after first 7-day streak
    val reviewManager = remember { ReviewManagerFactory.create(context) }
    LaunchedEffect(state.shouldShowReview) {
        if (state.shouldShowReview) {
            try {
                val reviewInfo = reviewManager.requestReviewFlow().await()
                (context as? Activity)?.let { reviewManager.launchReviewFlow(it, reviewInfo).await() }
            } catch (e: CancellationException) {
                throw e  // cancellation must propagate — don't mark as shown; will retry next session
            } catch (_: Exception) {
                // Play review unavailable (debug build, sideload, etc.) — mark shown so we don't retry
            }
            viewModel.onReviewShown()
        }
    }

    // Live clock — updates every minute, but ONLY while the app is actually visible:
    // repeatOnLifecycle suspends the ticker the moment the activity leaves RESUMED
    // (background/screen off) and restarts it with a fresh read on return, so the
    // clock never wakes the process while nobody is looking at it.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000L)
            }
        }
    }

    val dayName = remember(now) { SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now)) }
    val dateStr = remember(now) { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(now)) }
    val timeStr = remember(now) { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now)) }

    val animatedColor by animateColorAsState(
        targetValue = color?.composeColor ?: Color.Gray,
        animationSpec = tween(800),
        label = "colorAnim"
    )

    // Swiping to Camera/Gallery/Settings/Newsfeed is handled by HomeHubScreen, which
    // hosts this screen — no local gesture detector needed here.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(animatedColor.copy(alpha = 0.25f), MaterialTheme.colorScheme.background)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── App title ─────────────────────────────────────────────
            Text(
                "Hue & Seek",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineMedium.copy(
                    brush = Brush.horizontalGradient(
                        listOf(animatedColor, animatedColor.copy(alpha = 0.6f))
                    )
                )
            )

            Spacer(Modifier.height(20.dp))

            // Date / time header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    dayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    letterSpacing = 2.sp
                )
                Text(
                    dateStr,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                )
                Text(
                    timeStr,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "TODAY'S COLOR",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )

            Spacer(Modifier.height(12.dp))

            // Color hero: the daily circle wrapped in a completion ring that closes
            // fully when today's photo is captured — a daily win, never a half-empty bar.
            ColorHeroWithRing(
                color = animatedColor,
                streak = state.streak,
                capturedToday = state.capturedToday
            )

            Spacer(Modifier.height(14.dp))

            Text(
                color?.name ?: "Loading…",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(4.dp))

            Text(
                color?.hex ?: "",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            )

            Spacer(Modifier.height(20.dp))

            // Streak card — tappable to open Streaks & Stats
            Card(
                onClick = onOpenStats,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.capturedToday)
                        animatedColor.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            tint = Color(0xFFFF6D00),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (state.streak == 1) "1 day streak"
                            else "${state.streak} day streak",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        streakMessage(state.capturedToday, state.streak, color?.name ?: ""),
                        fontSize = 13.sp,
                        color = if (state.capturedToday)
                            animatedColor.copy(alpha = 0.9f)
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Past 14-day color history strip — tappable, opens Streaks & Stats
            ColorHistoryStrip(
                capturedDayIndices = state.capturedDayIndices,
                now = now,
                onClick = onOpenStats
            )

            Spacer(Modifier.weight(1f))

            // Action buttons — the capture CTA is framed as today's mission
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onOpenCamera,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = animatedColor),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.capturedToday) "Capture More" else "Find ${color?.name ?: "Color"}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onOpenGallery,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, animatedColor)
                ) {
                    Icon(Icons.Default.CollectionsBookmark, contentDescription = null, tint = animatedColor)
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery", color = animatedColor, style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Gesture affordance
            Text(
                "← gallery  •  camera →  •  ↑ walks  •  ↓ settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
            )

            // Space for the peek strip overlay below
            Spacer(Modifier.height(if (recentPhotos.isNotEmpty()) 116.dp else 20.dp))
        }

        // Confetti celebration overlay
        state.celebrationState?.let { celebration ->
            CelebrationOverlay(
                celebration = celebration,
                accentColor = animatedColor,
                onDone = { viewModel.onCelebrationDone() }
            )
        }

        // Settings gear
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 8.dp)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(28.dp)
            )
        }

        // Newsfeed peek strip — only shown when there are photos
        if (recentPhotos.isNotEmpty()) {
            NewsfeedPeekStrip(
                photos = recentPhotos,
                accentColor = animatedColor,
                backgroundColor = MaterialTheme.colorScheme.background,
                onClick = onOpenNewsfeed,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .alpha(newsfeedPeekAlpha)
            )
        }
    }
}

@Composable
private fun NewsfeedPeekStrip(
    photos: List<PhotoEntity>,
    accentColor: Color,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Strip total height: photos fill 96dp, bottom gradient eats 40dp, label sits on top.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clickable(onClick = onClick)
    ) {
        // Photo row — 3 panels, equal width, full height, cropped
        Row(modifier = Modifier.fillMaxSize()) {
            photos.forEach { photo ->
                AsyncImage(
                    model = photoImageRequest(context, photo.filePath, "peek_${photo.id}"),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            // Round the top corners of the first and last cell
                            when (photos.indexOf(photo)) {
                                0 -> Modifier.clip(RoundedCornerShape(topStart = 16.dp))
                                photos.lastIndex -> Modifier.clip(RoundedCornerShape(topEnd = 16.dp))
                                else -> Modifier
                            }
                        )
                )
            }
        }

        // Vertical gradient: transparent at top → opaque background at bottom
        // Creates the "peeking from below" illusion.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.55f to backgroundColor.copy(alpha = 0.15f),
                        1.0f to backgroundColor.copy(alpha = 0.92f)
                    )
                )
        )

        // Label row pinned to the top of the strip
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .background(
                    color = backgroundColor.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 14.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.CollectionsBookmark,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(13.dp)
            )
            Text(
                "Your walks",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = accentColor,
                letterSpacing = 0.3.sp
            )
        }
    }
}

/**
 * The daily color circle wrapped in a DAILY COMPLETION ring: empty track until
 * today's photo is captured, then it sweeps fully closed — a small, satisfying
 * win every single day. (A milestone-progress ring was tried first and felt
 * demotivating: it sat half-empty for weeks. Milestone countdown stays as the
 * caption text below instead.)
 */
@Composable
private fun ColorHeroWithRing(
    color: Color,
    streak: Int,
    capturedToday: Boolean
) {
    val nextMilestone = remember(streak) {
        com.colorwalk.app.viewmodel.HomeViewModel.MILESTONE_STREAKS.filter { it > streak }.minOrNull()
    }
    val animatedProgress by animateFloatAsState(
        targetValue = if (capturedToday) 1f else 0f,
        animationSpec = tween(900),
        label = "ringProgress"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(192.dp)) {
            val trackColor = color.copy(alpha = 0.18f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 9.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = trackColor,
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
                if (animatedProgress > 0f) {
                    drawArc(
                        color = color,
                        startAngle = -90f, sweepAngle = 360f * animatedProgress, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(156.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                if (capturedToday) {
                    Icon(
                        Icons.Default.LocalFireDepartment,
                        contentDescription = "Captured today",
                        tint = Color.White,  // always white — sits on the accent color circle
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }
        if (nextMilestone != null && streak > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${nextMilestone - streak} day${if (nextMilestone - streak != 1) "s" else ""} to ${milestoneEmoji(nextMilestone)} $nextMilestone",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )
        }
    }
}

private data class DayHistoryEntry(
    val dayIndex: Int,
    val color: Color,
    val colorName: String,
    val weekdayLabel: String,
    val weekdayFullName: String
)

@Composable
private fun ColorHistoryStrip(
    capturedDayIndices: Set<Int>,
    now: Long,
    onClick: () -> Unit
) {
    // Keyed to `now` so the strip updates when the live clock crosses midnight
    val todayIndex = remember(now) { StreakCalculator.epochMillisToDayIndex(now) }

    // Pre-compute all 14 day timestamps once per day (not per item per recomposition)
    val dayData = remember(todayIndex) {
        val weekdayFmt = SimpleDateFormat("EEEEE", Locale.getDefault())
        val fullWeekdayFmt = SimpleDateFormat("EEEE", Locale.getDefault())
        (0..13).map { offset ->
            val dayOffset = offset - 13
            val millis = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 12) // noon — unambiguous across all timezones
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }.timeInMillis
            val walkColor = colorForDay(millis)
            DayHistoryEntry(
                dayIndex = todayIndex + dayOffset,
                color = walkColor.composeColor,
                colorName = walkColor.name,
                weekdayLabel = weekdayFmt.format(Date(millis)),
                weekdayFullName = fullWeekdayFmt.format(Date(millis))
            )
        }
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Open streaks and stats", onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        items(14) { offset ->
            val entry = dayData[offset]
            val captured = entry.dayIndex in capturedDayIndices
            val isTodayEntry = entry.dayIndex == todayIndex
            val statusLabel = when {
                isTodayEntry && captured -> "today, captured"
                isTodayEntry -> "today, not captured yet"
                captured -> "captured"
                else -> "not captured"
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(20.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${entry.weekdayFullName}, ${entry.colorName}, $statusLabel"
                    }
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (captured) entry.color else entry.color.copy(alpha = 0.18f))
                        .then(
                            if (isTodayEntry) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), CircleShape)
                            else Modifier
                        )
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    entry.weekdayLabel,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(
                        alpha = if (isTodayEntry) 0.8f else 0.4f
                    ),
                    fontWeight = if (isTodayEntry) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ── Celebration overlay ───────────────────────────────────────────────────────

@Composable
private fun CelebrationOverlay(
    celebration: CelebrationState,
    accentColor: Color,
    onDone: () -> Unit
) {
    val isMilestone = celebration is CelebrationState.Milestone
    val accentArgb = accentColor.toArgb()
    val parties = remember(celebration) {
        if (isMilestone) milestoneParties(accentArgb) else dailyParties(accentArgb)
    }

    KonfettiView(
        modifier = Modifier.fillMaxSize(),
        parties = parties,
        updateListener = object : OnParticleSystemUpdateListener {
            override fun onParticleSystemEnded(system: PartySystem, activeSystems: Int) {
                if (activeSystems == 0) onDone()
            }
        }
    )

    if (isMilestone) {
        val days = (celebration as CelebrationState.Milestone).days
        var cardVisible by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(2800)
            cardVisible = false
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = cardVisible,
                enter = fadeIn(tween(300)) + scaleIn(
                    tween(300),
                    initialScale = 0.8f
                ),
                exit = fadeOut(tween(400)) + scaleOut(tween(400))
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1A1A1A).copy(alpha = 0.95f)
                    ),
                    modifier = Modifier.padding(40.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(milestoneEmoji(days), fontSize = 52.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "$days Day Streak!",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            milestoneMessage(days),
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.65f),
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

private fun milestoneEmoji(days: Int) = when (days) {
    7    -> "🔥"
    21   -> "⭐"
    30   -> "🎯"
    50   -> "💎"
    100  -> "🏆"
    150  -> "🌟"
    180  -> "🎨"
    200  -> "👑"
    240  -> "🦋"
    300  -> "🌈"
    365  -> "🎊"
    else -> "🎉"
}

private fun milestoneMessage(days: Int) = when (days) {
    7    -> "One full week of color walks!"
    21   -> "Three weeks strong. This is a habit now."
    30   -> "A full month. You have a true eye for color."
    50   -> "50 days. Extraordinary commitment."
    100  -> "100 days. You are a legend."
    150  -> "150 days. Half a year of seeing the world in color."
    180  -> "6 months. Your eye for color is truly refined."
    200  -> "200 days. An artist's dedication."
    240  -> "8 months. Remarkable. Absolutely remarkable."
    300  -> "300 days. You have transformed how you see the world."
    365  -> "One full year. 365 days of color walks. This is who you are now."
    else -> "Incredible streak!"
}

private fun dailyParties(accentArgb: Int): List<Party> = listOf(
    Party(
        speed = 0f,
        maxSpeed = 20f,
        damping = 0.9f,
        spread = 60,
        colors = listOf(accentArgb, 0xFFFFFFFF.toInt(), 0xFFFFD700.toInt(), 0xFFFF69B4.toInt()),
        emitter = Emitter(duration = 600, TimeUnit.MILLISECONDS).perSecond(80),
        position = Position.Relative(0.5, -0.05)
    )
)

private fun milestoneParties(accentArgb: Int): List<Party> {
    val colors = listOf(
        accentArgb,
        0xFFFFD700.toInt(),
        0xFFFF69B4.toInt(),
        0xFF00CED1.toInt(),
        0xFFFF6347.toInt(),
        0xFF9370DB.toInt()
    )
    val base = Party(
        speed = 5f,
        maxSpeed = 35f,
        damping = 0.85f,
        colors = colors,
        emitter = Emitter(duration = 1, TimeUnit.MILLISECONDS).perSecond(1)
    )
    return listOf(
        base.copy(
            spread = 360,
            emitter = Emitter(duration = 1500, TimeUnit.MILLISECONDS).perSecond(180),
            position = Position.Relative(0.5, -0.05)
        ),
        base.copy(
            angle = 45,
            spread = 70,
            emitter = Emitter(duration = 1200, TimeUnit.MILLISECONDS).perSecond(80),
            position = Position.Relative(0.0, 0.4)
        ),
        base.copy(
            angle = 135,
            spread = 70,
            emitter = Emitter(duration = 1200, TimeUnit.MILLISECONDS).perSecond(80),
            position = Position.Relative(1.0, 0.4)
        )
    )
}
