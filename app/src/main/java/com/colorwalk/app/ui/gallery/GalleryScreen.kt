package com.colorwalk.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.colorwalk.app.data.db.ColorSummary
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.viewmodel.GalleryViewMode
import com.colorwalk.app.viewmodel.GalleryViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val folders by viewModel.colorFolders.collectAsState()
    val allPhotos by viewModel.allPhotos.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val selectedColor by viewModel.selectedColor.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState()

    // Full-screen viewer takes priority over everything
    if (viewerState != null) {
        PhotoViewerScreen(
            photos = viewerState!!.photos,
            initialIndex = viewerState!!.initialIndex,
            onClose = { viewModel.closePhoto() },
            onDelete = { viewModel.deletePhoto(it) }
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

        // Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
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
        }

        Spacer(Modifier.height(8.dp))

        val isEmpty = if (viewMode == GalleryViewMode.COLOR) folders.isEmpty() else allPhotos.isEmpty()

        if (isEmpty) {
            EmptyGallery()
        } else {
            when (viewMode) {
                GalleryViewMode.COLOR -> ColorFolderGrid(folders, onColorClick = { viewModel.selectColor(it) })
                GalleryViewMode.DATE -> DatePhotoList(
                    allPhotos,
                    onDelete = { viewModel.deletePhoto(it) },
                    onOpen = { viewModel.openPhoto(it, allPhotos) }
                )
            }
        }
    }
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
private fun DatePhotoList(photos: List<PhotoEntity>, onDelete: (PhotoEntity) -> Unit, onOpen: (PhotoEntity) -> Unit = {}) {
    // Group by "MMMM yyyy" descending
    val grouped = remember(photos) {
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        photos
            .groupBy { fmt.format(Date(it.dateTaken)) }
            .entries
            .sortedByDescending { it.value.first().dateTaken }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        grouped.forEach { (monthYear, monthPhotos) ->
            item {
                Text(
                    monthYear,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier.padding(bottom = 10.dp, top = 4.dp)
                )
            }
            items(monthPhotos, key = { it.id }) { photo ->
                DatePhotoRow(
                    photo = photo,
                    onDelete = { onDelete(photo) },
                    onOpen = { onOpen(photo) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DatePhotoRow(photo: PhotoEntity, onDelete: () -> Unit, onOpen: () -> Unit = {}) {
    val dateStr = remember(photo.dateTaken) {
        SimpleDateFormat("EEE, MMM d  •  h:mm a", Locale.getDefault()).format(Date(photo.dateTaken))
    }
    val accentColor = parseHexColor(photo.colorHex)
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

    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.height(88.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = android.net.Uri.parse(photo.filePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(88.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(photo.colorName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = accentColor)
                }
                Text(dateStr, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                if (photo.locationName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(photo.locationName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), maxLines = 1)
                    }
                }
            }
            // Delete button on right edge
            IconButton(
                onClick = { showConfirm = true },
                modifier = Modifier.padding(end = 4.dp).size(36.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun EmptyGallery() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("No photos yet", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f), fontSize = 18.sp)
            Text("Start your first color walk!", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f), fontSize = 14.sp)
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
