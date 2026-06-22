package com.colorwalk.app.ui.gallery

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.ui.components.parseAccentHex
import com.colorwalk.app.ui.components.photoImageRequest
import java.io.File as JavaFile
import java.text.SimpleDateFormat
import java.util.*

private val ZOOM_LEVELS = listOf(1f, 2f, 3f, 5f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    photos: List<PhotoEntity>,
    initialIndex: Int,
    onClose: () -> Unit,
    onDelete: (PhotoEntity) -> Unit,
    onRotate: (PhotoEntity, onDone: () -> Unit) -> Unit
) {
    // Photos already arrive newest-first; page 0 = newest.
    // Swiping left → higher page index → older photo (standard photo-app convention).
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }

    // Reset zoom and pan whenever the visible page changes
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    // When a photo is deleted the list shrinks before the pager settles on the new
    // last page. Snap immediately so currentPage is never out-of-bounds.
    LaunchedEffect(photos.size) {
        if (photos.isNotEmpty()) {
            val clamped = pagerState.currentPage.coerceAtMost(photos.lastIndex)
            if (pagerState.currentPage != clamped) pagerState.scrollToPage(clamped)
        }
    }

    // Per-photo revision counter — incremented after each rotation to bust Coil's cache
    val rotationRevisions = remember { mutableStateMapOf<Long, Int>() }
    var isRotating by remember { mutableStateOf(false) }

    // Guard against the one-recomposition window where currentPage hasn't settled yet.
    val safeIndex = pagerState.currentPage.coerceAtMost(photos.lastIndex.coerceAtLeast(0))
    val currentPhoto = photos.getOrNull(safeIndex) ?: return
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete photo?") },
            text = { Text("This will remove it from the app and your device gallery.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete(currentPhoto) }) {
                    Text("Delete", color = Color(0xFFEF5350))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Pager swipe is disabled while zoomed in so pan gestures don't also flip pages.
        val context = LocalContext.current
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondBoundsPageCount = 1,
            userScrollEnabled = scale == 1f
        ) { page ->
            val photo = photos[page]
            val revision = rotationRevisions[photo.id] ?: 0
            val cacheKey = "${photo.filePath}::$revision"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged { imageSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            val maxX = ((imageSize.width * (scale - 1)) / 2f).coerceAtLeast(0f)
                            val maxY = ((imageSize.height * (scale - 1)) / 2f).coerceAtLeast(0f)
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = photoImageRequest(context, photo.filePath, cacheKey),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
        }

        // ── Top bar ──────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            if (photos.size > 1) {
                Text(
                    "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { sharePhoto(context, currentPhoto) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                }

                IconButton(
                    onClick = {
                        if (!isRotating) {
                            isRotating = true
                            onRotate(currentPhoto) {
                                // Clear old cache entries so thumbnails in album views also update
                                context.imageLoader.memoryCache?.remove(
                                    MemoryCache.Key("${currentPhoto.filePath}::${rotationRevisions[currentPhoto.id] ?: 0}")
                                )
                                context.imageLoader.diskCache?.remove(
                                    "${currentPhoto.filePath}::${rotationRevisions[currentPhoto.id] ?: 0}"
                                )
                                rotationRevisions[currentPhoto.id] = (rotationRevisions[currentPhoto.id] ?: 0) + 1
                                isRotating = false
                            }
                        }
                    },
                    enabled = !isRotating,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(Icons.Default.RotateRight, contentDescription = "Rotate", tint = Color.White)
                }

                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF9A9A))
                }
            }
        }

        // ── Bottom: zoom buttons + metadata ──────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Zoom buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ZOOM_LEVELS.forEach { level ->
                    val active = scale == level
                    val label = if (level == 1f) "1×" else "${level.toInt()}×"
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (active) Color.White else Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(
                            onClick = { scale = level; offset = Offset.Zero },
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (active) Color.Black else Color.White
                            )
                        }
                    }
                }
            }

            // Metadata card
            val accentColor = parseAccentHex(currentPhoto.colorHex)
            val dateStr = remember(currentPhoto.dateTaken) {
                SimpleDateFormat("EEEE, MMMM d yyyy  •  h:mm a", Locale.getDefault())
                    .format(Date(currentPhoto.dateTaken))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        currentPhoto.colorName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(dateStr, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
                if (currentPhoto.locationName != null) {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            currentPhoto.locationName,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(parseAccentHex(currentPhoto.dominantColorHex))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Dominant ${currentPhoto.dominantColorHex}",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

private fun sharePhoto(context: android.content.Context, photo: PhotoEntity) {
    val shareUri: Uri = if (photo.filePath.startsWith("/")) {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            JavaFile(photo.filePath)
        )
    } else {
        Uri.parse(photo.filePath)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
