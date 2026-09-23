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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.colorwalk.app.ui.components.FitToHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.colorwalk.app.R
import com.colorwalk.app.ui.components.colorDisplayName
import android.content.Context
import com.colorwalk.app.ui.components.localizedDateFormat
import com.colorwalk.app.ui.components.formatClockTime

private fun streakMessage(context: Context, capturedToday: Boolean, streak: Int, colorName: String): String {
    return if (capturedToday) {
        when {
            streak >= 30 -> context.getString(R.string.home_msg_30_plus)
            streak >= 21 -> context.resources.getQuantityString(R.plurals.home_msg_21_plus, streak, streak, colorName)
            streak >= 14 -> context.getString(R.string.home_msg_14_plus, colorName)
            streak >= 7  -> context.getString(R.string.home_msg_7_plus)
            streak >= 3  -> context.getString(R.string.home_msg_3_plus, colorName)
            streak == 1  -> context.getString(R.string.home_msg_first)
            else         -> context.getString(R.string.home_msg_done)
        }
    } else {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour < 10   -> context.getString(R.string.home_msg_morning, colorName)
            hour < 13   -> context.getString(R.string.home_msg_midday, colorName)
            hour < 17   -> context.getString(R.string.home_msg_afternoon, colorName)
            hour < 20   -> context.getString(R.string.home_msg_evening, colorName)
            else        -> context.getString(R.string.home_msg_night, colorName)
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
    // HomeHubScreen's drag offset. A lambda, not a Float: the value changes every frame
    // of a vertical drag, so it's read inside graphicsLayer (draw phase only) and never
    // triggers recomposition (M-1).
    newsfeedPeekAlpha: () -> Float = { 1f },
    // True only when Home is the settled, on-screen hub pane. The hub keeps Home
    // composed while the user is on Camera/Gallery/etc., and the capture that earns
    // the confetti happens on the Camera pane — without this gate the celebration
    // would mount, play, and clear itself entirely off-screen before the user
    // returns Home.
    celebrationVisible: Boolean = true,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val recentPhotos by viewModel.recentPhotos.collectAsState()
    val color = state.colorOfDay
    val context = LocalContext.current

    // (I-3: no ON_RESUME reload anymore — captures/deletes reach the ViewModel via
    // Room emissions, and day rollover — including resuming after midnight — is
    // caught by the minute ticker below, which repeatOnLifecycle restarts with a
    // fresh timestamp on every resume.)
    val lifecycleOwner = LocalLifecycleOwner.current

    // In-app review — fires once after first 7-day streak. BUG-038: only once Home is
    // the settled pane and any celebration has finished — Home stays composed while
    // the user is on Camera, so the 7th capture used to pop the Play sheet over the
    // result card. Leaving mid-flow cancels it (not marked shown; retried later).
    val reviewManager = remember { ReviewManagerFactory.create(context) }
    val reviewReady = state.shouldShowReview && celebrationVisible && state.celebrationState == null
    LaunchedEffect(reviewReady) {
        if (reviewReady) {
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
                // Sleep to the next minute BOUNDARY, not a fixed 60s — a fixed period
                // started mid-minute leaves the displayed time up to 59s stale (L-1).
                delay(60_000L - (now % 60_000L))
            }
        }
    }

    // Day rollover while foreground: the hub keeps this activity RESUMED all session,
    // so no lifecycle event fires at midnight — but the minute ticker above does. The
    // moment `now` crosses into a new local day, reload so the color of the day, the
    // theme, and the "captured today" state all flip without an app restart (H-4).
    val todayIndex = com.colorwalk.app.domain.StreakCalculator.epochMillisToDayIndex(now)
    LaunchedEffect(todayIndex) { viewModel.load() }

    val dayName = remember(now) { SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now)) }
    val dateStr = remember(now) { localizedDateFormat("yMMMMd").format(Date(now)) }
    // BUG-040: honour the device's 12/24-hour setting.
    val timeStr = remember(now) { formatClockTime(context, Date(now)) }

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
            // BUG-013: everything above the action buttons shrinks to fit when it's
            // taller than the space left (small phone, landscape, large font) instead of
            // pushing the buttons off-screen. Home can't scroll: every vertical drag here
            // belongs to the hub (Settings above, Newsfeed below).
            FitToHeight(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // ── App title ─────────────────────────────────────────────
                    Text(
                        stringResource(R.string.app_name),
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
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
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
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Text(
                        stringResource(R.string.home_todays_color),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
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
                        color?.let { colorDisplayName(it.name) } ?: stringResource(R.string.home_loading),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        color?.hex ?: "",
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
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
                                    pluralStringResource(R.plurals.home_day_streak, state.streak, state.streak),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                streakMessage(
                                    LocalContext.current, state.capturedToday, state.streak,
                                    color?.let { colorDisplayName(it.name) } ?: ""
                                ),
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
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action buttons — the capture CTA is framed as today's mission. Min (not
            // fixed) height + 2 lines so large font sizes grow the button instead of
            // clipping its label (BUG-013).
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onOpenCamera,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = animatedColor),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.capturedToday) stringResource(R.string.home_capture_more)
                        else stringResource(
                            R.string.home_find_color,
                            color?.let { colorDisplayName(it.name) } ?: stringResource(R.string.home_color_fallback)
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                OutlinedButton(
                    onClick = onOpenGallery,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, animatedColor)
                ) {
                    Icon(Icons.Default.CollectionsBookmark, contentDescription = null, tint = animatedColor)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.gallery_title),
                        color = animatedColor,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Gesture affordance
            Text(
                stringResource(R.string.home_gesture_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )

            // Space for the peek strip overlay below, above the navigation bar (BUG-033)
            Spacer(
                Modifier
                    .navigationBarsPadding()
                    .height(if (recentPhotos.isNotEmpty()) 116.dp else 20.dp)
            )
        }

        // Confetti celebration overlay — mounted only while Home is actually the
        // visible pane, so a celebration earned on the Camera pane stays pending and
        // plays the moment the user lands back on Home.
        if (celebrationVisible) {
            state.celebrationState?.let { celebration ->
                CelebrationOverlay(
                    celebration = celebration,
                    accentColor = animatedColor,
                    onDone = { viewModel.onCelebrationDone() }
                )
                // BUG-038: swiping away mid-confetti unmounts the overlay without
                // onDone — it then replayed from the start on every return. Leaving
                // counts as seen. (Idempotent with the normal onDone path.)
                DisposableEffect(celebration) {
                    onDispose { viewModel.onCelebrationDone() }
                }
            }
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
                contentDescription = stringResource(R.string.settings_title),
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
                    .navigationBarsPadding()   // BUG-033: not half-hidden under 3-button nav
                    .graphicsLayer { alpha = newsfeedPeekAlpha() }
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
                stringResource(R.string.home_your_walks),
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
                        contentDescription = stringResource(R.string.home_captured_today),
                        tint = Color.White,  // always white — sits on the accent color circle
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }
        if (nextMilestone != null && streak > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                pluralStringResource(
                    R.plurals.home_days_to_milestone, nextMilestone - streak,
                    nextMilestone - streak, milestoneEmoji(nextMilestone), nextMilestone
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
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
            // Touch anywhere on the strip opens Stats. Pointer-only: for TalkBack each
            // day cell carries the action itself (BUG-039 — focusing a cell and
            // double-tapping used to do nothing).
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(vertical = 4.dp)
    ) {
        items(14) { offset ->
            val entry = dayData[offset]
            val captured = entry.dayIndex in capturedDayIndices
            val isTodayEntry = entry.dayIndex == todayIndex
            val statusLabel = stringResource(
                when {
                    isTodayEntry && captured -> R.string.home_day_today_captured
                    isTodayEntry -> R.string.home_day_today_pending
                    captured -> R.string.home_day_captured
                    else -> R.string.home_day_not_captured
                }
            )
            val cellColorName = colorDisplayName(entry.colorName)
            val openStatsLabel = stringResource(R.string.home_open_stats)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(20.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${entry.weekdayFullName}, $cellColorName, $statusLabel"
                        onClick(label = openStatsLabel) { onClick(); true }
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
                    fontSize = 11.sp,   // BUG-039: 9sp was below legible size
                    color = MaterialTheme.colorScheme.onBackground.copy(
                        alpha = if (isTodayEntry) 0.9f else 0.7f
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
                            pluralStringResource(R.plurals.home_milestone_title, days, days),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            milestoneMessage(LocalContext.current, days),
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

private fun milestoneMessage(context: Context, days: Int) = context.getString(
    when (days) {
        7    -> R.string.milestone_7
        21   -> R.string.milestone_21
        30   -> R.string.milestone_30
        50   -> R.string.milestone_50
        100  -> R.string.milestone_100
        150  -> R.string.milestone_150
        180  -> R.string.milestone_180
        200  -> R.string.milestone_200
        240  -> R.string.milestone_240
        300  -> R.string.milestone_300
        365  -> R.string.milestone_365
        else -> R.string.milestone_other
    }
)

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
