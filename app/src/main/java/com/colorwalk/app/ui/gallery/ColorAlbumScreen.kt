package com.colorwalk.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.colorwalk.app.ui.components.EmptyState
import com.colorwalk.app.ui.components.parseAccentHex
import com.colorwalk.app.ui.components.PhotoGridCard
import com.colorwalk.app.ui.components.ScreenHeader
import com.colorwalk.app.ui.theme.Spacing
import com.colorwalk.app.viewmodel.AlbumSortOrder
import com.colorwalk.app.viewmodel.GalleryViewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.colorwalk.app.R
import com.colorwalk.app.ui.components.colorDisplayName

@Composable
fun ColorAlbumScreen(
    colorName: String,
    viewModel: GalleryViewModel,
    onBack: () -> Unit
) {
    val photos        by viewModel.photosForColor.collectAsState()
    val albumSortOrder by viewModel.albumSortOrder.collectAsState()
    val accentColor = photos.firstOrNull()?.colorHex?.let { parseAccentHex(it) } ?: Color.Gray

    // Auto-pop when the last photo is deleted so the empty album doesn't linger.
    // Guard: only trigger after we've seen at least one photo (avoids popping on initial load).
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
        ScreenHeader(
            title = colorDisplayName(colorName),
            onBack = onBack,
            titleColor = accentColor,
            subtitle = photos.firstOrNull()?.colorHex?.uppercase()
        ) {
            Text(
                pluralStringResource(R.plurals.photo_count, photos.size, photos.size),
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
                    label = { Text(stringResource(order.label), style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        if (photos.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.album_color_empty_title, colorDisplayName(colorName)),
                subtitle = stringResource(R.string.album_color_empty_body)
            )
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
                        accentColor = accentColor,
                        onOpen = { viewModel.openPhoto(photo, photos) },
                        onDelete = { viewModel.deletePhoto(photo) },
                        // The album header already states the color + hex once —
                        // repeating it on every tile was pure noise.
                        showDominant = false
                    )
                }
            }
        }
    }
}
