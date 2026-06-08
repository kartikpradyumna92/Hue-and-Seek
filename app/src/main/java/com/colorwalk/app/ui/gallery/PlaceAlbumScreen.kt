package com.colorwalk.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                locationName,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${photos.size} photo${if (photos.size != 1) "s" else ""}",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                fontSize = 14.sp
            )
        }

        // Sort chips
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AlbumSortOrder.entries.forEach { order ->
                FilterChip(
                    selected = albumSortOrder == order,
                    onClick = { viewModel.setAlbumSortOrder(order) },
                    label = { Text(order.label, fontSize = 13.sp) }
                )
            }
        }

        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No photos in $locationName",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(photos, key = { it.id }) { photo ->
                    PhotoCard(
                        photo = photo,
                        accentColor = parseHexColor(photo.colorHex),
                        onOpen = { viewModel.openPhoto(photo, photos) },
                        onDelete = { viewModel.deletePhoto(photo) }
                    )
                }
            }
        }
    }
}
