package com.colorwalk.app.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.colorwalk.app.ui.components.PhotoRevisions
import com.colorwalk.app.ui.components.rememberDayTick
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.colorwalk.app.R
import com.colorwalk.app.ui.components.colorDisplayName

private val TAB_LABELS = listOf(R.string.gallery_tab_color, R.string.gallery_tab_date, R.string.gallery_tab_place)

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
    // True only while Gallery is the settled, on-screen hub pane (gates system back).
    isActive: Boolean = true,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val viewerState        by viewModel.viewerState.collectAsState()
    val selectedColor      by viewModel.selectedColor.collectAsState()
    val selectedPlace      by viewModel.selectedPlace.collectAsState()
    val showingUntagged    by viewModel.showingUntagged.collectAsState()

    // BUG-063: the date filters ("This Week" …) are relative to today — refresh them
    // at midnight while the app stays open.
    val dayTick = rememberDayTick()
    LaunchedEffect(dayTick) { viewModel.onDayChanged(dayTick) }

    // BUG-029: tab grid scroll positions live up here, above the album/tab switch, so
    // they survive opening an album (and a photo) and coming back.
    val colorGridState = rememberLazyGridState()
    val dateGridState = rememberLazyGridState()
    val placeGridState = rememberLazyGridState()

    // BUG-007: system back walks back through Gallery's own layers — viewer (its own
    // handler, composed later, so it wins), then album/untagged — instead of jumping
    // the hub to Home. Only while Gallery is the settled pane: the hub keeps Gallery
    // composed while the user is elsewhere, and a stale album must not eat Home's back.
    BackHandler(enabled = isActive && (selectedColor != null || selectedPlace != null || showingUntagged)) {
        when {
            selectedColor != null -> viewModel.clearSelection()
            selectedPlace != null -> viewModel.clearPlaceSelection()
            else -> viewModel.closeUntagged()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // BUG-029: the album/tabs stay composed under the viewer — replacing them with
        // it threw their LazyGridState (scroll position) away on every photo opened.
        // Hidden from accessibility while covered.
        Box(
            Modifier
                .fillMaxSize()
                .then(if (viewerState != null) Modifier.clearAndSetSemantics { } else Modifier)
        ) {
            when {
                selectedColor != null -> ColorAlbumScreen(
                    colorName = selectedColor!!,
                    viewModel = viewModel,
                    onBack = { viewModel.clearSelection() }
                )
                selectedPlace != null -> PlaceAlbumScreen(
                    locationName = selectedPlace!!,
                    viewModel = viewModel,
                    onBack = { viewModel.clearPlaceSelection() }
                )
                showingUntagged -> UntaggedAlbumScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.closeUntagged() }
                )
                else -> GalleryTabs(
                    viewModel = viewModel,
                    onBack = onBack,
                    onEdgeDragStart = onEdgeDragStart,
                    onEdgeDrag = onEdgeDrag,
                    onEdgeDragEnd = onEdgeDragEnd,
                    colorGridState = colorGridState,
                    dateGridState = dateGridState,
                    placeGridState = placeGridState
                )
            }
        }

        viewerState?.let { vs ->
            val shareContext = LocalContext.current
            PhotoViewerScreen(
                photos = vs.photos,
                initialIndex = vs.initialIndex,
                backEnabled = isActive,
                onClose = { viewModel.closePhoto() },
                onDelete = { viewModel.deletePhoto(it) },
                onRotate = { photo, onDone ->
                    viewModel.rotatePhoto(photo) { ok ->
                        // BUG-030: refresh this photo on every screen (grids, feed, peek).
                        if (ok) PhotoRevisions.bump(photo.filePath)
                        // BUG-059: a failed rotation used to look like it silently worked.
                        else Toast.makeText(shareContext, R.string.error_rotate_photo, Toast.LENGTH_SHORT).show()
                        onDone(ok)
                    }
                },
                onSaveDescription = { photo, text -> viewModel.saveDescription(photo, text) },
                onPageChanged = { viewModel.onViewerPageChanged(it) },
                onShare = { photo ->
                    // M-12: always share a private file through the FileProvider — legacy
                    // content:// rows are copied/migrated by the repository first, since
                    // read grants can't be minted for MediaStore URIs the app doesn't own.
                    viewModel.prepareShare(photo) { file ->
                        if (file == null) {
                            // BUG-059: the tap used to do nothing at all.
                            Toast.makeText(shareContext, R.string.error_share_photo, Toast.LENGTH_SHORT).show()
                            return@prepareShare
                        }
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            shareContext, "${shareContext.packageName}.fileprovider", file
                        )
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            shareContext.startActivity(android.content.Intent.createChooser(intent, null))
                        } catch (_: Exception) { /* no share targets — never crash */ }
                    }
                }
            )
        }
    }
}

/** The By Color / By Date / By Place tabs with their pager (Gallery's root layer). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryTabs(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onEdgeDragStart: () -> Unit,
    onEdgeDrag: (dx: Float, uptimeMillis: Long) -> Unit,
    onEdgeDragEnd: () -> Unit,
    colorGridState: LazyGridState,
    dateGridState: LazyGridState,
    placeGridState: LazyGridState
) {
    val colorFolderCards   by viewModel.colorFolderCards.collectAsState()
    val allPhotos          by viewModel.allPhotos.collectAsState()
    val photosByMonth      by viewModel.photosByMonth.collectAsState()
    val photosByPlace      by viewModel.photosByPlace.collectAsState()
    val viewMode           by viewModel.viewMode.collectAsState()
    val searchQuery        by viewModel.searchQuery.collectAsState()
    val dateFilter         by viewModel.dateFilter.collectAsState()
    val dateSortOrder      by viewModel.dateSortOrder.collectAsState()
    val hasUntaggedPhotos  by viewModel.hasUntaggedPhotos.collectAsState()
    val untaggedPhotos     by viewModel.untaggedPhotos.collectAsState()

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
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                stringResource(R.string.gallery_title),
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
                            stringResource(TAB_LABELS[index]),
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
                    gridState = colorGridState,
                    folders = colorFolderCards,
                    searchQuery = searchQuery,
                    onQueryChange = { viewModel.setSearchQuery(it) },
                    onColorClick = { viewModel.selectColor(it) }
                )
                GalleryViewMode.DATE -> DateTab(
                    gridState = dateGridState,
                    photos = allPhotos,
                    photosByMonth = photosByMonth,
                    dateFilter = dateFilter,
                    sortOrder = dateSortOrder,
                    onFilterChange = { viewModel.setDateFilter(it) },
                    onSortChange = { viewModel.setDateSortOrder(it) },
                    onDelete = { viewModel.deletePhoto(it) },
                    onOpen = { viewModel.openPhoto(it, allPhotos) }
                )
                GalleryViewMode.PLACE -> PlaceTab(
                    gridState = placeGridState,
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
    gridState: LazyGridState,
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
                    title = stringResource(R.string.gallery_search_empty_title, searchQuery),
                    subtitle = stringResource(R.string.gallery_search_empty_body)
                )
            } else {
                EmptyState(
                title = stringResource(R.string.empty_no_photos_title),
                subtitle = stringResource(R.string.empty_no_photos_body)
            )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
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
                contentDescription = pluralStringResource(
                    R.plurals.gallery_color_album_desc, folder.photoCount,
                    colorDisplayName(folder.colorName), folder.photoCount
                ),
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
                        colorDisplayName(folder.colorName),
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
        placeholder = { Text(stringResource(R.string.gallery_search_hint), style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null,
                modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.gallery_search_clear),
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
    gridState: LazyGridState,
    photos: List<PhotoEntity>,
    photosByMonth: List<Pair<String, List<PhotoEntity>>>,
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
                    label = { Text(stringResource(filter.label), style = MaterialTheme.typography.labelMedium) }
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
                stringResource(R.string.gallery_sort_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AlbumSortOrder.entries.forEach { order ->
                FilterChip(
                    selected = sortOrder == order,
                    onClick = { onSortChange(order) },
                    label = { Text(stringResource(order.label), style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        if (photos.isEmpty()) {
            if (dateFilter != DateFilter.ALL) {
                EmptyState(
                    title = stringResource(R.string.gallery_period_empty_title),
                    subtitle = stringResource(R.string.gallery_period_empty_body)
                )
            } else {
                EmptyState(
                title = stringResource(R.string.empty_no_photos_title),
                subtitle = stringResource(R.string.empty_no_photos_body)
            )
            }
        } else {
            DatePhotoGrid(gridState = gridState, grouped = photosByMonth, onDelete = onDelete, onOpen = onOpen)
        }
    }
}

@Composable
private fun DatePhotoGrid(
    gridState: LazyGridState,
    // Grouped and ordered in GalleryViewModel.photosByMonth, off the main thread (BUG-041).
    grouped: List<Pair<String, List<PhotoEntity>>>,
    onDelete: (PhotoEntity) -> Unit,
    onOpen: (PhotoEntity) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        contentPadding = PaddingValues(Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        grouped.forEach { (monthYear, monthPhotos) ->
            // Typed keys: headers can never collide with photo ids (Long) — and a
            // stable key lets the restored scroll position land on the same item.
            item(key = "month:$monthYear", span = { GridItemSpan(maxLineSpan) }) {
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
    gridState: LazyGridState,
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
                title = stringResource(R.string.gallery_place_empty_title),
                subtitle = stringResource(R.string.gallery_place_empty_body)
            )
        } else {
            EmptyState(
                title = stringResource(R.string.empty_no_photos_title),
                subtitle = stringResource(R.string.empty_no_photos_body)
            )
        }
    } else {
        PlaceFolderGrid(
            gridState = gridState,
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
    gridState: LazyGridState,
    places: List<PlaceSummary>,
    onPlaceClick: (String) -> Unit,
    hasUntagged: Boolean = false,
    untaggedCount: Int = 0,
    untaggedThumbnail: String? = null,
    onUntaggedClick: () -> Unit = {}
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        contentPadding = PaddingValues(Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        // BUG-061: keys are namespaced by item type — a place the user named
        // "untagged_card" collided with the untagged card's fixed key and crashed
        // the grid (duplicate LazyGrid key).
        items(places, key = { "place:${it.locationName}" }) { place ->
            PlaceFolderCard(place = place, onClick = { onPlaceClick(place.locationName) })
        }
        if (hasUntagged) {
            item(key = "untagged:card") {
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
                            stringResource(R.string.gallery_needs_location),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        pluralStringResource(R.plurals.gallery_untagged_count, count, count),
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
                contentDescription = pluralStringResource(
                    R.plurals.gallery_place_album_desc, place.photoCount,
                    place.locationName, place.photoCount
                ),
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
                        pluralStringResource(R.plurals.photo_count, place.photoCount, place.photoCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
