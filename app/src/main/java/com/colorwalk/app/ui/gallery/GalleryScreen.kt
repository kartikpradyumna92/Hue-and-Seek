package com.colorwalk.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.net.Uri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.colorwalk.app.data.db.ColorSummary
import java.io.File as JavaFile
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.viewmodel.AlbumSortOrder
import com.colorwalk.app.viewmodel.DateFilter
import com.colorwalk.app.viewmodel.GalleryViewMode
import com.colorwalk.app.viewmodel.GalleryViewModel
import com.colorwalk.app.viewmodel.PlaceSummary
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val folders            by viewModel.colorFolders.collectAsState()
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
    var swipeDelta         by remember { mutableFloatStateOf(0f) }

    // Full-screen viewer takes priority over everything
    if (viewerState != null) {
        PhotoViewerScreen(
            photos = viewerState!!.photos,
            initialIndex = viewerState!!.initialIndex,
            onClose = { viewModel.closePhoto() },
            onDelete = { viewModel.deletePhoto(it) },
            onRotate = { photo, onDone -> viewModel.rotatePhoto(photo, onDone) }
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
            .pointerInput(viewMode) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeDelta = 0f },
                    onDragEnd = {
                        val threshold = 80.dp.toPx()
                        when {
                            swipeDelta < -threshold && viewMode == GalleryViewMode.COLOR ->
                                viewModel.setViewMode(GalleryViewMode.DATE)
                            swipeDelta < -threshold && viewMode == GalleryViewMode.DATE ->
                                viewModel.setViewMode(GalleryViewMode.PLACE)
                            swipeDelta > threshold && viewMode == GalleryViewMode.DATE ->
                                viewModel.setViewMode(GalleryViewMode.COLOR)
                            swipeDelta > threshold && viewMode == GalleryViewMode.PLACE ->
                                viewModel.setViewMode(GalleryViewMode.DATE)
                            swipeDelta > threshold && viewMode == GalleryViewMode.COLOR ->
                                onBack()
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
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                "Gallery",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        // View mode tabs — scrollable so all 3 fit on small screens
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GalleryTabChip(
                label = "By Color",
                selected = viewMode == GalleryViewMode.COLOR,
                onClick = { viewModel.setViewMode(GalleryViewMode.COLOR) }
            )
            GalleryTabChip(
                label = "By Date",
                selected = viewMode == GalleryViewMode.DATE,
                onClick = { viewModel.setViewMode(GalleryViewMode.DATE) }
            )
            GalleryTabChip(
                label = "By Place",
                selected = viewMode == GalleryViewMode.PLACE,
                onClick = { viewModel.setViewMode(GalleryViewMode.PLACE) }
            )
        }

        // Filter / sort row per mode
        when (viewMode) {
            GalleryViewMode.COLOR -> ColorSearchBar(
                query = searchQuery,
                onQueryChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            GalleryViewMode.DATE -> {
                // Scrollable date filter chips
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DateFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = dateFilter == filter,
                            onClick = { viewModel.setDateFilter(filter) },
                            label = { Text(filter.label, fontSize = 13.sp) }
                        )
                    }
                }
                // Sort order row
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Sort:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    AlbumSortOrder.entries.forEach { order ->
                        FilterChip(
                            selected = dateSortOrder == order,
                            onClick = { viewModel.setDateSortOrder(order) },
                            label = { Text(order.label, fontSize = 12.sp) }
                        )
                    }
                }
            }
            GalleryViewMode.PLACE -> { /* no filter row for place */ }
        }

        val isEmpty = when (viewMode) {
            GalleryViewMode.COLOR -> folders.isEmpty()
            GalleryViewMode.DATE  -> allPhotos.isEmpty()
            GalleryViewMode.PLACE -> photosByPlace.isEmpty() && !hasUntaggedPhotos
        }

        if (isEmpty) {
            val (message, sub) = when {
                viewMode == GalleryViewMode.COLOR && searchQuery.isNotBlank() ->
                    Pair("No colors matching\n\"$searchQuery\"", "Try a different search term")
                viewMode == GalleryViewMode.DATE && dateFilter != DateFilter.ALL ->
                    Pair("No photos in this period", "Try a different time range")
                viewMode == GalleryViewMode.PLACE && allPhotos.isNotEmpty() ->
                    Pair("No location data", "Photos need location available at capture time to appear here")
                else ->
                    Pair("No photos yet", "Start your first color walk!")
            }
            EmptyGallery(message, sub)
        } else {
            when (viewMode) {
                GalleryViewMode.COLOR -> ColorFolderGrid(folders, onColorClick = { viewModel.selectColor(it) })
                GalleryViewMode.DATE  -> DatePhotoList(
                    photos = allPhotos,
                    sortOrder = dateSortOrder,
                    onDelete = { viewModel.deletePhoto(it) },
                    onOpen = { viewModel.openPhoto(it, allPhotos) }
                )
                GalleryViewMode.PLACE -> PlaceFolderGrid(
                    places = photosByPlace,
                    onPlaceClick = { viewModel.selectPlace(it) },
                    hasUntagged = hasUntaggedPhotos,
                    untaggedCount = untaggedPhotos.size,
                    untaggedThumbnail = untaggedPhotos.firstOrNull()?.filePath,
                    onUntaggedClick = { viewModel.openUntagged() }
                )
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
        placeholder = { Text("Search colors…", fontSize = 13.sp) },
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

@Composable
private fun GalleryTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    val textColor = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(label, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun ColorFolderGrid(folders: List<ColorSummary>, onColorClick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(folders) { folder ->
            ColorFolderCard(folder = folder, onClick = { onColorClick(folder.colorName) })
        }
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
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(places, key = { it.locationName }) { place ->
            PlaceFolderCard(place = place, context = context, onClick = { onPlaceClick(place.locationName) })
        }
        if (hasUntagged) {
            item(key = "untagged_card") {
                UntaggedFolderCard(
                    count = untaggedCount,
                    thumbnailPath = untaggedThumbnail,
                    context = context,
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
    context: android.content.Context,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnailPath != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(if (thumbnailPath.startsWith("/")) JavaFile(thumbnailPath) else Uri.parse(thumbnailPath))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f),
                            RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.LocationOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
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
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                        ),
                        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
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
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "$count photo${if (count != 1) "s" else ""}  •  Tap to tag",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceFolderCard(place: PlaceSummary, context: android.content.Context, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(
                        if (place.thumbnailPath.startsWith("/")) JavaFile(place.thumbnailPath)
                        else Uri.parse(place.thumbnailPath)
                    )
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
            )
            // Gradient overlay at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                        ),
                        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
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
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${place.photoCount} photo${if (place.photoCount != 1) "s" else ""}",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DatePhotoList(
    photos: List<PhotoEntity>,
    sortOrder: AlbumSortOrder,
    onDelete: (PhotoEntity) -> Unit,
    onOpen: (PhotoEntity) -> Unit = {}
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
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        grouped.forEach { (monthYear, monthPhotos) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    monthYear,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            items(monthPhotos, key = { it.id }) { photo ->
                DatePhotoCard(
                    photo = photo,
                    onOpen = { onOpen(photo) },
                    onDelete = { onDelete(photo) }
                )
            }
        }
    }
}

@Composable
private fun DatePhotoCard(photo: PhotoEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    val accentColor = parseHexColor(photo.colorHex)
    val dateStr = remember(photo.dateTaken) {
        SimpleDateFormat("MMM d  •  h:mm a", Locale.getDefault()).format(Date(photo.dateTaken))
    }
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Delete photo?") },
            text = { Text("This will remove it from the app and your device gallery.") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onDelete() }) {
                    Text("Delete", color = Color(0xFFEF5350))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }

    val context = LocalContext.current
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(if (photo.filePath.startsWith("/")) JavaFile(photo.filePath) else Uri.parse(photo.filePath))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                )
                IconButton(
                    onClick = { showConfirm = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color(0xFFEF9A9A),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(photo.colorName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accentColor)
                }
                Spacer(Modifier.height(2.dp))
                Text(dateStr, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                if (photo.locationName != null) {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            photo.locationName,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyGallery(
    message: String = "No photos yet",
    sub: String = "Start your first color walk!"
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            Text(
                sub,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ColorFolderCard(folder: ColorSummary, onClick: () -> Unit) {
    val color = parseHexColor(folder.colorHex)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.height(12.dp))
            Text(folder.colorName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                folder.colorHex.uppercase(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                letterSpacing = 1.sp
            )
        }
    }
}

internal fun parseHexColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
    } catch (e: Exception) {
        Color.Gray
    }
}
