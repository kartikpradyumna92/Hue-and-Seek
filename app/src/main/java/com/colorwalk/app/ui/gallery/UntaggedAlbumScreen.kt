package com.colorwalk.app.ui.gallery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.viewmodel.GalleryViewModel
import java.io.File as JavaFile
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun UntaggedAlbumScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit
) {
    val photos           by viewModel.untaggedPhotos.collectAsState()
    val photoBeingTagged by viewModel.photoBeingTagged.collectAsState()
    val existingPlaces   by viewModel.existingPlaceNames.collectAsState()

    // Auto-close once all photos are tagged
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
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Icon(
                Icons.Default.LocationOff,
                contentDescription = null,
                tint = Color(0xFFFFC107),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Tag Older Photos",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${photos.size} left",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                fontSize = 14.sp
            )
        }

        Text(
            "Tap a photo to set its location",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 10.dp)
        )

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
                        "All photos tagged!",
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
                        onOpen = { viewModel.startTagging(photo) },
                        onDelete = { viewModel.deletePhoto(photo) }
                    )
                }
            }
        }
    }

    if (photoBeingTagged != null) {
        TagLocationDialog(
            photo = photoBeingTagged!!,
            existingPlaces = existingPlaces,
            onConfirm = { name -> viewModel.submitTag(photoBeingTagged!!, name) },
            onDismiss = { viewModel.cancelTagging() }
        )
    }
}

@Composable
private fun TagLocationDialog(
    photo: PhotoEntity,
    existingPlaces: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val dateStr = remember(photo.dateTaken) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(photo.dateTaken))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(
                                if (photo.filePath.startsWith("/")) JavaFile(photo.filePath)
                                else Uri.parse(photo.filePath)
                            )
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Column {
                        Text(
                            photo.colorName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = parseHexColor(photo.colorHex)
                        )
                        Text(
                            dateStr,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Location", fontSize = 13.sp) },
                    placeholder = { Text("e.g. San Francisco, California", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                if (existingPlaces.isNotEmpty()) {
                    Text(
                        "Quick pick:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        existingPlaces.forEach { place ->
                            SuggestionChip(
                                onClick = { text = place },
                                label = {
                                    Text(
                                        place,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
