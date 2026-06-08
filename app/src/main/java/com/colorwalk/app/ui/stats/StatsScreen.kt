package com.colorwalk.app.ui.stats

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.viewmodel.StatsUiState
import com.colorwalk.app.viewmodel.StatsViewModel
import java.io.File as JavaFile
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    "Streaks & Stats",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            StatsSection(state = state)

            Spacer(Modifier.height(8.dp))
            Text(
                "YOUR WALK HISTORY",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            MonthCalendar(
                photosByDayIndex = state.photosByDayIndex,
                onDayClick = { viewModel.selectDay(it) }
            )
            Spacer(Modifier.height(32.dp))
        }

        // Bottom sheet for tapped day's photos
        if (state.selectedDayPhotos.isNotEmpty()) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.selectDay(emptyList()) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                PhotoDetailSheet(photos = state.selectedDayPhotos)
            }
        }
    }
}

// ── Stats summary ─────────────────────────────────────────────────────────────

@Composable
private fun StatsSection(state: StatsUiState) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.LocalFireDepartment,
                iconTint = Color(0xFFFF6D00),
                value = "${state.currentStreak}",
                unit = "days",
                label = "Current streak"
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.EmojiEvents,
                iconTint = Color(0xFFFFD700),
                value = "${state.bestStreak}",
                unit = "days",
                label = "Best streak"
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.CameraAlt,
                iconTint = MaterialTheme.colorScheme.primary,
                value = "${state.totalPhotos}",
                unit = null,
                label = "Photos taken"
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.CalendarMonth,
                iconTint = MaterialTheme.colorScheme.tertiary,
                value = "${state.totalActiveDays}",
                unit = null,
                label = "Active days"
            )
        }
        if (state.favouriteColorName != null) {
            Spacer(Modifier.height(12.dp))
            val favColor = state.favouriteColorHex?.let { parseStatsHexColor(it) } ?: Color.Gray
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = favColor.copy(alpha = 0.13f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(favColor)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "FAVOURITE COLOR",
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                        Text(
                            state.favouriteColorName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = favColor
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        tint = favColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    icon: ImageVector,
    iconTint: Color,
    value: String,
    unit: String?,
    label: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (unit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        unit,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

// ── Monthly calendar ──────────────────────────────────────────────────────────

private data class DayCell(val dayOfMonth: Int, val dayIndex: Int)

@Composable
private fun MonthCalendar(
    photosByDayIndex: Map<Int, List<PhotoEntity>>,
    onDayClick: (List<PhotoEntity>) -> Unit
) {
    val todayDayIndex = remember { StreakCalculator.epochMillisToDayIndex(System.currentTimeMillis()) }
    var displayYear by remember { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var displayMonth by remember { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH)) }
    var swipeDelta by remember { mutableFloatStateOf(0f) }

    val weeks = remember(displayYear, displayMonth) { buildMonthGrid(displayYear, displayMonth) }
    val monthLabel = remember(displayYear, displayMonth) {
        SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            .format(Calendar.getInstance().also { it.set(displayYear, displayMonth, 1) }.time)
    }

    fun prevMonth() {
        val cal = Calendar.getInstance().apply { set(displayYear, displayMonth, 1) }
        cal.add(Calendar.MONTH, -1)
        displayYear = cal.get(Calendar.YEAR)
        displayMonth = cal.get(Calendar.MONTH)
    }

    fun nextMonth() {
        val cal = Calendar.getInstance().apply { set(displayYear, displayMonth, 1) }
        cal.add(Calendar.MONTH, 1)
        displayYear = cal.get(Calendar.YEAR)
        displayMonth = cal.get(Calendar.MONTH)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .pointerInput(displayYear, displayMonth) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeDelta = 0f },
                    onDragEnd = {
                        val threshold = 60.dp.toPx()
                        when {
                            swipeDelta < -threshold -> nextMonth()
                            swipeDelta > threshold  -> prevMonth()
                        }
                        swipeDelta = 0f
                    },
                    onDragCancel = { swipeDelta = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    swipeDelta += dragAmount
                }
            }
    ) {
        // Month navigation header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { prevMonth() }) {
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = "Previous month",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                monthLabel,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            IconButton(onClick = { nextMonth() }) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Next month",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // Day-of-week header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { d ->
                Text(
                    d,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                )
            }
        }

        // Calendar grid rows
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    CalendarDayCell(
                        cell = cell,
                        photos = cell?.let { photosByDayIndex[it.dayIndex] },
                        isToday = cell?.dayIndex == todayDayIndex,
                        onDayClick = onDayClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    cell: DayCell?,
    photos: List<PhotoEntity>?,
    isToday: Boolean,
    onDayClick: (List<PhotoEntity>) -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        if (cell == null) return@Box

        val firstPhoto = photos?.firstOrNull()
        if (firstPhoto != null) {
            val color = parseStatsHexColor(firstPhoto.colorHex)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (isToday) Modifier.border(2.dp, Color.White, CircleShape) else Modifier
                    )
                    .clickable { onDayClick(photos!!) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.LocalFireDepartment,
                    contentDescription = "${firstPhoto.colorName} captured",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .then(
                        if (isToday) Modifier.border(
                            1.5.dp,
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            CircleShape
                        ) else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${cell.dayOfMonth}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(
                        alpha = if (isToday) 0.85f else 0.35f
                    ),
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

private fun buildMonthGrid(year: Int, month: Int): List<List<DayCell?>> {
    val cal = Calendar.getInstance().apply {
        set(year, month, 1, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1  // 0 = Sunday
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

    val cells = mutableListOf<DayCell?>()
    repeat(firstDow) { cells.add(null) }

    for (day in 1..daysInMonth) {
        cal.set(Calendar.DAY_OF_MONTH, day)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        val dayIndex = TimeUnit.MILLISECONDS.toDays(cal.timeInMillis).toInt()
        cells.add(DayCell(day, dayIndex))
    }

    while (cells.size % 7 != 0) cells.add(null)
    return cells.chunked(7)
}

// ── Photo detail bottom sheet ─────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoDetailSheet(photos: List<PhotoEntity>) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { photos.size })
    val currentPhoto = photos[pagerState.currentPage]
    val accentColor = parseStatsHexColor(currentPhoto.colorHex)
    val dateStr = remember(currentPhoto.dateTaken) {
        SimpleDateFormat("EEEE, MMMM d, yyyy  •  h:mm a", Locale.getDefault())
            .format(Date(currentPhoto.dateTaken))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val photo = photos[page]
            val pageAccent = parseStatsHexColor(photo.colorHex)
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(
                        if (photo.filePath.startsWith("/")) JavaFile(photo.filePath)
                        else Uri.parse(photo.filePath)
                    )
                    .crossfade(true)
                    .build(),
                contentDescription = "${photo.colorName} photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(pageAccent.copy(alpha = 0.15f))
            )
        }

        // Page indicator — only shown when the day has multiple photos
        if (photos.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(photos.size) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (pagerState.currentPage == index) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (pagerState.currentPage == index)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                            )
                    )
                }
            }
        }

        // Metadata — updates as the user swipes to each photo
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    currentPhoto.colorName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Spacer(Modifier.weight(1f))
                Text(
                    currentPhoto.colorHex.uppercase(),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    letterSpacing = 1.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                dateStr,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            if (currentPhoto.locationName != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        currentPhoto.locationName,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Dominant  ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(parseStatsHexColor(currentPhoto.dominantColorHex))
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    currentPhoto.dominantColorHex.uppercase(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

private fun parseStatsHexColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
} catch (_: Exception) { Color.Gray }
