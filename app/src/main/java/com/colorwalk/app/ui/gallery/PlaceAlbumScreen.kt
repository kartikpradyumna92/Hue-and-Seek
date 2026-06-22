package com.colorwalk.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.colorwalk.app.ui.components.EmptyState
import com.colorwalk.app.ui.components.parseAccentHex
import com.colorwalk.app.ui.components.PhotoGridCard
import com.colorwalk.app.ui.components.ScreenHeader
import com.colorwalk.app.ui.theme.Spacing
import com.colorwalk.app.viewmodel.AlbumSortOrder
import com.colorwalk.app.viewmodel.GalleryViewModel

@Composable
fun PlaceAlbumScreen(
    locationName: String,
    viewModel: GalleryViewModel,
    onBack: () -> Unit
) {
    val photos        by viewModel.photosForPlace.collectAsState()
    val albumSortOrder by viewModel.albumSortOrder.collectAsState()

    // Auto-pop when the last photo is deleted
    var hasSeenPhotos by remember { mutableStateOf(false) }
    LaunchedEffect(photos) {
        if (photos.isNotEmpty()) hasSeenPhotos = true
        if (hasSeenPhotos && photos.isEmpty()) onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .pointerInput(onBack) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart  = { totalDrag = 0f },
                    onDragEnd    = { totalDrag = 0f },
                    onDragCancel = { totalDrag = 0f }
                ) { _, dragAmount ->
                    totalDrag += dragAmount
                    if (totalDrag > 80.dp.toPx()) { onBack(); totalDrag = 0f }
                }
            }
    ) {
        ScreenHeader(title = locationName, onBack = onBack) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                "${photos.size} photo${if (photos.size != 1) "s" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.s)
            )
        }

        // Sort chips
        Row(
            modifier = Modifier
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            AlbumSortOrder.entries.forEach { order ->
                FilterChip(
                    selected = albumSortOrder == order,
                    onClick = { viewModel.setAlbumSortOrder(order) },
                    label = { Text(order.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        if (photos.isEmpty()) {
            EmptyState(title = "No photos in $locationName")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                items(photos, key = { it.id }) { photo ->
                    PhotoGridCard(
                        photo = photo,
                        accentColor = parseAccentHex(photo.colorHex),
                        onOpen = { viewModel.openPhoto(photo, photos) },
                        onDelete = { viewModel.deletePhoto(photo) },
                        showColorName = true,
                        showDominant = false
                    )
                }
            }
        }
    }
}
