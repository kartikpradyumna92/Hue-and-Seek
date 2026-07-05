package com.colorwalk.app.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.colorwalk.app.ui.components.EmptyState
import com.colorwalk.app.ui.components.PhotoGridCard
import com.colorwalk.app.ui.components.parseAccentHex
import com.colorwalk.app.ui.components.photoImageRequest
import com.colorwalk.app.ui.theme.Spacing
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.viewmodel.AlbumSortOrder
import com.colorwalk.app.viewmodel.ColorFolderInfo
import com.colorwalk.app.viewmodel.DateFilter
import com.colorwalk.app.viewmodel.GalleryViewMode
import com.colorwalk.app.viewmodel.GalleryViewModel
import com.colorwalk.app.viewmodel.PlaceSummary
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val TAB_LABELS = listOf("By Color", "By Date", "By Place")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    // Swipe-right-to-Home from the first tab reports continuous per-frame deltas (with
    // the pointer event's timestamp, for release-velocity estimation) to the caller
    // instead of firing onBack() itself — HomeHubScreen owns the single clamped drag
    // value shared with Home/Camera, so it decides how far that's allowed to move and
    // whether it settles back to Gallery or through to Home.
    onEdgeDragStart: () -> Unit = {},
    onEdgeDrag: (dx: Float, uptimeMillis: Long) -> Unit = { _, _ -> },
    onEdgeDragEnd: () -> Unit = {},
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val colorFolderCards   by viewModel.colorFolderCards.collectAsState()
    val allPhotos          by viewModel.allPhotos.collectAsState()
    val photosByPlace      by viewModel.photosByPlace.collectAsState()
    val viewMode           by viewModel.viewMode.collectAsState()
    val selectedColor      by viewModel.selectedColor.collectAsState()
    val selectedPlace      by viewModel.selectedPlace.collectAsState()
    val viewerState        by viewModel.viewerState.collectAsState()
    val searchQuery        by viewModel.searchQuery.collectAsState()
    val dateFilter         by viewModel.dateFilter.collectAsState()
    val dateSortOrder      by viewModel.dateSortOrder.collectAsState()
    val hasUntaggedPhotos  by viewModel.hasUntaggedPhotos.collectAsState()
    val untaggedPhotos     by viewModel.untaggedPhotos.collectAsState()
    val showingUntagged    by viewModel.showingUntagged.collectAsState()

    // Full-screen viewer takes priority over everything
    if (viewerState != null) {
        PhotoViewerScreen(
            photos = viewerState!!.photos,
            initialIndex = viewerState!!.initialIndex,
            onClose = { viewModel.closePhoto() },
            onDelete = { viewModel.deletePhoto(it) },
            onRotate = { photo, onDone -> viewModel.rotatePhoto(photo, onDone) },
            onSaveDescription = { photo, text -> viewModel.saveDescription(photo, text) }
        )
        return
    }

    if (selectedColor != null) {
        ColorAlbumScreen(
            colorName = selectedColor!!,
            viewModel = viewModel,
            onBack = { viewModel.clearSelection() }
        )
        return
    }

    if (selectedPlace != null) {
        PlaceAlbumScreen(
            locationName = selectedPlace!!,
            viewModel = viewModel,
            onBack = { viewModel.clearPlaceSelection() }
        )
        return
    }

    if (showingUntagged) {
        UntaggedAlbumScreen(
            viewModel = viewModel,
            onBack = { viewModel.closeUntagged() }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                "Gallery",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = Spacing.xs)
            )
        }

        // Tabs + pager — standard pattern: tab tap and horizontal swipe stay in sync,
        // and swiping between tabs can no longer accidentally exit the screen.
        val pagerState = rememberPagerState(initialPage = viewMode.ordinal) { GalleryViewMode.entries.size }

        LaunchedEffect(pagerState.settledPage) {
            val mode = GalleryViewMode.entries[pagerState.settledPage]
            if (mode != viewMode) viewModel.setViewMode(mode)
        }
        LaunchedEffect(viewMode) {
            if (pagerState.currentPage != viewMode.ordinal && !pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage(viewMode.ordinal)
            }
        }

        // Indicator follows pagerState.currentPage (flips mid-swipe), not viewMode
        // (which only updates on settle) — otherwise the indicator lags the gesture.
        val scope = rememberCoroutineScope()
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            indicator = { tabPositions ->
                if (pagerState.currentPage < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        ) {
            GalleryViewMode.entries.forEachIndexed { index, _ ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = {
                        Text(
                            TAB_LABELS[index],
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                )
            }
        }

        // Swipe right past the first tab drags Home back into view. Nested-scroll hooks
        // are unreliable here — the pager and its edge overscroll consume the leftover
        // deltas internally — so this watches raw pointer events on the Initial pass
        // (delivered before the pager's own gesture handling) and reports the drag to
        // the caller. Never consumes, so tab paging is untouched. A gesture only counts
        // as an exit-pull if it STARTED with the pager settled on the first tab, so
        // finishing a Date→Color swipe can never overshoot home.
        val swipeHomeModifier = Modifier.pointerInput(pagerState) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val eligible = pagerState.currentPage == 0 &&
                    abs(pagerState.currentPageOffsetFraction) < 0.01f
                var totalX = 0f
                var totalY = 0f
                var reporting = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val dx = change.position.x - change.previousPosition.x
                    totalX += dx
                    totalY += change.position.y - change.previousPosition.y
                    val stillAtEdge = pagerState.currentPage == 0 &&
                        abs(pagerState.currentPageOffsetFraction) < 0.01f
                    // Mostly-horizontal rightward pull while the pager has nowhere
                    // to go → start reporting to Home.
                    if (!reporting && eligible && stillAtEdge && totalX > 0f && totalX > 2 * abs(totalY)) {
                        reporting = true
                        onEdgeDragStart()
                    }
                    if (reporting) onEdgeDrag(dx, change.uptimeMillis)
                }
                if (reporting) onEdgeDragEnd()
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .then(swipeHomeModifier),
            verticalAlignment = Alignment.Top
        ) { page ->
            when (GalleryViewMode.entries[page]) {
                GalleryViewMode.COLOR -> ColorTab(
                    folders = colorFolderCards,
                    searchQuery = searchQuery,
                    onQueryChange = { viewModel.setSearchQuery(it) },
                    onColorClick = { viewModel.selectColor(it) }
                )
                GalleryViewMode.DATE -> DateTab(
                    photos = allPhotos,
                    dateFilter = dateFilter,
                    sortOrder = dateSortOrder,
                    onFilterChange = { viewModel.setDateFilter(it) },
                    onSortChange = { viewModel.setDateSortOrder(it) },
                    onDelete = { viewModel.deletePhoto(it) },
                    onOpen = { viewModel.openPhoto(it, allPhotos) }
                )
                GalleryViewMode.PLACE -> PlaceTab(
                    places = photosByPlace,
                    hasUntagged = hasUntaggedPhotos,
                    untaggedCount = untaggedPhotos.size,
                    untaggedThumbnail = untaggedPhotos.firstOrNull()?.filePath,
                    anyPhotosExist = allPhotos.isNotEmpty(),
                    onPlaceClick = { viewModel.selectPlace(it) },
                    onUntaggedClick = { viewModel.openUntagged() }
                )
            }
        }
    }
}

// ── By Color tab ──────────────────────────────────────────────────────────────

@Composable
private fun ColorTab(
    folders: List<ColorFolderInfo>,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onColorClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ColorSearchBar(
            query = searchQuery,
            onQueryChange = onQueryChange,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)
        )
        if (folders.isEmpty()) {
            if (searchQuery.isNotBlank()) {
                EmptyState(
                    title = "No colors matching \"$searchQuery\"",
                    subtitle = "Try a different search term"
                )
            } else {
                EmptyState(title = "No photos yet", subtitle = "Start your first color walk!")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.m)
            ) {
                items(folders, key = { it.colorName }) { folder ->
                    ColorFolderCard(folder = folder, onClick = { onColorClick(folder.colorName) })
                }
            }
        }
    }
}

/**
 * A color folder is the user's actual latest photo behind a tint of its color —
 * photos are the visual currency, not abstract swatches.
 */
@Composable
private fun ColorFolderCard(folder: ColorFolderInfo, onClick: () -> Unit) {
    val color = parseAccentHex(folder.colorHex)
    val context = LocalContext.current
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = photoImageRequest(context, folder.thumbnailPath),
                contentDescription = "${folder.colorName} album, ${folder.photoCount} photos",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Color identity tint + readable bottom scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color.copy(alpha = 0.18f))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                        )
                    )
                    .padding(horizontal = Spacing.m, vertical = Spacing.m)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        folder.colorName,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Text(
                        "${folder.photoCount}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Search colors…", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null,
                modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search",
                        modifier = Modifier.size(18.dp))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(50)
    )
}

// ── By Date tab ───────────────────────────────────────────────────────────────

@Composable
private fun DateTab(
    photos: List<PhotoEntity>,
    dateFilter: DateFilter,
    sortOrder: AlbumSortOrder,
    onFilterChange: (DateFilter) -> Unit,
    onSortChange: (AlbumSortOrder) -> Unit,
    onDelete: (PhotoEntity) -> Unit,
    onOpen: (PhotoEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .padding(horizontal = Spacing.l, vertical = Spacing.xs)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            DateFilter.entries.forEach { filter ->
                FilterChip(
                    selected = dateFilter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
        Row(
            modifier = Modifier
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Sort:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AlbumSortOrder.entries.forEach { order ->
                FilterChip(
                    selected = sortOrder == order,
                    onClick = { onSortChange(order) },
                    label = { Text(order.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        if (photos.isEmpty()) {
            if (dateFilter != DateFilter.ALL) {
                EmptyState(title = "No photos in this period", subtitle = "Try a different time range")
            } else {
                EmptyState(title = "No photos yet", subtitle = "Start your first color walk!")
            }
        } else {
            DatePhotoGrid(photos = photos, sortOrder = sortOrder, onDelete = onDelete, onOpen = onOpen)
        }
    }
}

@Composable
private fun DatePhotoGrid(
    photos: List<PhotoEntity>,
    sortOrder: AlbumSortOrder,
    onDelete: (PhotoEntity) -> Unit,
    onOpen: (PhotoEntity) -> Unit
) {
    val grouped = remember(photos, sortOrder) {
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val entries = photos
            .groupBy { fmt.format(Date(it.dateTaken)) }
            .entries
        when (sortOrder) {
            AlbumSortOrder.NEWEST -> entries.sortedByDescending { it.value.first().dateTaken }
            AlbumSortOrder.OLDEST -> entries.sortedBy { it.value.first().dateTaken }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        grouped.forEach { (monthYear, monthPhotos) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    monthYear,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.xs)
                )
            }
            items(monthPhotos, key = { it.id }) { photo ->
                PhotoGridCard(
                    photo = photo,
                    accentColor = parseAccentHex(photo.colorHex),
                    onOpen = { onOpen(photo) },
                    onDelete = { onDelete(photo) },
                    showColorName = true,
                    showTime = true,
                    showDominant = false
                )
            }
        }
    }
}

// ── By Place tab ──────────────────────────────────────────────────────────────

@Composable
private fun PlaceTab(
    places: List<PlaceSummary>,
    hasUntagged: Boolean,
    untaggedCount: Int,
    untaggedThumbnail: String?,
    anyPhotosExist: Boolean,
    onPlaceClick: (String) -> Unit,
    onUntaggedClick: () -> Unit
) {
    if (places.isEmpty() && !hasUntagged) {
        if (anyPhotosExist) {
            EmptyState(
                title = "No location data",
                subtitle = "Photos need location available at capture time to appear here"
            )
        } else {
            EmptyState(title = "No photos yet", subtitle = "Start your first color walk!")
        }
    } else {
        PlaceFolderGrid(
            places = places,
            onPlaceClick = onPlaceClick,
            hasUntagged = hasUntagged,
            untaggedCount = untaggedCount,
            untaggedThumbnail = untaggedThumbnail,
            onUntaggedClick = onUntaggedClick
        )
    }
}

@Composable
private fun PlaceFolderGrid(
    places: List<PlaceSummary>,
    onPlaceClick: (String) -> Unit,
    hasUntagged: Boolean = false,
    untaggedCount: Int = 0,
    untaggedThumbnail: String? = null,
    onUntaggedClick: () -> Unit = {}
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        items(places, key = { it.locationName }) { place ->
            PlaceFolderCard(place = place, onClick = { onPlaceClick(place.locationName) })
        }
        if (hasUntagged) {
            item(key = "untagged_card") {
                UntaggedFolderCard(
                    count = untaggedCount,
                    thumbnailPath = untaggedThumbnail,
                    onClick = onUntaggedClick
                )
            }
        }
    }
}

@Composable
private fun UntaggedFolderCard(
    count: Int,
    thumbnailPath: String?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnailPath != null) {
                AsyncImage(
                    model = photoImageRequest(context, thumbnailPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.LocationOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                        )
                    )
                    .padding(horizontal = Spacing.m, vertical = Spacing.m)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOff,
                            contentDescription = null,
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "Needs Location",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "$count photo${if (count != 1) "s" else ""}  •  Tap to tag",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceFolderCard(place: PlaceSummary, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = photoImageRequest(context, place.thumbnailPath),
                contentDescription = "${place.locationName}, ${place.photoCount} photos",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Gradient overlay at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                        )
                    )
                    .padding(horizontal = Spacing.m, vertical = Spacing.m)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            place.locationName,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${place.photoCount} photo${if (place.photoCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
