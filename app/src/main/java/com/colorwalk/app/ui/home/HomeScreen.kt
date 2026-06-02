package com.colorwalk.app.ui.home

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.colorForDay
import com.colorwalk.app.notification.AlarmScheduler
import com.colorwalk.app.notification.NotificationPrefs
import com.colorwalk.app.viewmodel.HomeViewModel
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
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

    // Live clock — updates every minute
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L)
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

    // Notification time picker state
    var showTimePicker by remember { mutableStateOf(false) }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = NotificationPrefs.getHour(context),
            initialMinute = NotificationPrefs.getMinute(context)
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.widthIn(min = 280.dp),
            title = { Text("Daily reminder time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    NotificationPrefs.set(context, timePickerState.hour, timePickerState.minute)
                    AlarmScheduler.scheduleDaily(context)
                    showTimePicker = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(animatedColor.copy(alpha = 0.25f), Color(0xFF121212))
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
                    color = Color.White.copy(alpha = 0.5f),
                    letterSpacing = 2.sp
                )
                Text(
                    dateStr,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Text(
                    timeStr,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "Today's Color",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(16.dp))

            // Big color circle
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(animatedColor),
                contentAlignment = Alignment.Center
            ) {
                if (state.capturedToday) {
                    Icon(
                        Icons.Default.LocalFireDepartment,
                        contentDescription = "Captured",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                color?.name ?: "Loading…",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(Modifier.height(6.dp))

            Text(
                color?.hex ?: "",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.45f),
                letterSpacing = 2.sp
            )

            Spacer(Modifier.height(28.dp))

            // Streak card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.capturedToday)
                        animatedColor.copy(alpha = 0.15f)
                    else
                        Color.White.copy(alpha = 0.08f)
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
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        streakMessage(state.capturedToday, state.streak, color?.name ?: ""),
                        fontSize = 13.sp,
                        color = if (state.capturedToday)
                            animatedColor.copy(alpha = 0.9f)
                        else
                            Color.White.copy(alpha = 0.65f),
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Past 14-day color history strip
            ColorHistoryStrip(capturedDayIndices = state.capturedDayIndices, now = now)

            Spacer(Modifier.weight(1f))

            // Action buttons
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
                    Text("Camera", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onOpenGallery,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, animatedColor)
                ) {
                    Icon(Icons.Default.CollectionsBookmark, contentDescription = null, tint = animatedColor)
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery", color = animatedColor, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(32.dp))
        }

        // Settings gear — opens notification time picker
        IconButton(
            onClick = { showTimePicker = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 8.dp)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Notification settings",
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ColorHistoryStrip(capturedDayIndices: Set<Int>, now: Long) {
    // Keyed to `now` so the strip updates when the live clock crosses midnight
    val todayIndex = remember(now) { StreakCalculator.epochMillisToDayIndex(now) }

    // Pre-compute all 14 day timestamps once per day (not per item per recomposition)
    val dayData = remember(todayIndex) {
        (0..13).map { offset ->
            val dayOffset = offset - 13
            val millis = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 12) // noon — unambiguous across all timezones
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }.timeInMillis
            Pair(todayIndex + dayOffset, colorForDay(millis).composeColor)
        }
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(14) { offset ->
            val (dayIndex, dayColor) = dayData[offset]
            val captured = dayIndex in capturedDayIndices
            val isToday = dayIndex == todayIndex

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (captured) dayColor else dayColor.copy(alpha = 0.18f))
                        .then(
                            if (isToday) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                            else Modifier
                        )
                )
            }
        }
    }
}
