package com.colorwalk.app.ui.gallery

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    onRotate: (PhotoEntity, onDone: () -> Unit) -> Unit,
    onSaveDescription: (PhotoEntity, String?) -> Unit = { _, _ -> }
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
                    .pointerInput(scale) {
                        // Custom gesture handler so single-finger swipes at scale=1 reach
                        // HorizontalPager. Only consume events when pinching (2+ pointers)
                        // or panning while already zoomed in (scale > 1).
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var active = true
                            while (active) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    active = false
                                } else when {
                                    pressed.size >= 2 -> {
                                        // Pinch zoom + pan
                                        event.changes.forEach { it.consume() }
                                        val c1 = pressed[0]
                                        val c2 = pressed[1]
                                        val prevDist = (c1.previousPosition - c2.previousPosition).getDistance()
                                        val currDist = (c1.position - c2.position).getDistance()
                                        val zoomFactor = if (prevDist > 0f) currDist / prevDist else 1f
                                        val prevCentroid = (c1.previousPosition + c2.previousPosition) / 2f
                                        val currCentroid = (c1.position + c2.position) / 2f
                                        val panDelta = currCentroid - prevCentroid
                                        scale = (scale * zoomFactor).coerceIn(1f, 5f)
                                        val maxX = ((imageSize.width * (scale - 1)) / 2f).coerceAtLeast(0f)
                                        val maxY = ((imageSize.height * (scale - 1)) / 2f).coerceAtLeast(0f)
                                        offset = Offset(
                                            (offset.x + panDelta.x).coerceIn(-maxX, maxX),
                                            (offset.y + panDelta.y).coerceIn(-maxY, maxY)
                                        )
                                    }
                                    scale > 1f -> {
                                        // Single-finger pan while zoomed in
                                        event.changes.forEach { it.consume() }
                                        val change = pressed[0]
                                        val panDelta = change.position - change.previousPosition
                                        val maxX = ((imageSize.width * (scale - 1)) / 2f).coerceAtLeast(0f)
                                        val maxY = ((imageSize.height * (scale - 1)) / 2f).coerceAtLeast(0f)
                                        offset = Offset(
                                            (offset.x + panDelta.x).coerceIn(-maxX, maxX),
                                            (offset.y + panDelta.y).coerceIn(-maxY, maxY)
                                        )
                                    }
                                    // scale == 1f, single touch → don't consume; HorizontalPager handles the swipe
                                }
                            }
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

            // Metadata card — always 4 lines, fixed structure
            val accentColor = parseAccentHex(currentPhoto.colorHex)
            val dominantColor = parseAccentHex(currentPhoto.dominantColorHex)
            val dateStr = remember(currentPhoto.dateTaken) {
                SimpleDateFormat("EEEE, MMMM d yyyy  •  h:mm a", Locale.getDefault())
                    .format(Date(currentPhoto.dateTaken))
            }
            var captionExpanded by remember(currentPhoto.id) { mutableStateOf(false) }
            var captionOverflows by remember(currentPhoto.id) { mutableStateOf(false) }
            var editingNote by remember(currentPhoto.id) { mutableStateOf(false) }
            var draftNote by remember(currentPhoto.id) { mutableStateOf(currentPhoto.description ?: "") }
            val noteFocusRequester = remember(currentPhoto.id) { FocusRequester() }
            val focusManager = LocalFocusManager.current

            fun commitNote() {
                onSaveDescription(currentPhoto, draftNote)
                editingNote = false
                focusManager.clearFocus()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Line 1: ● ColorName   ● Dominant #hex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            currentPhoto.colorName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dominantColor)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "Dominant ${currentPhoto.dominantColorHex.uppercase()}",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.45f)
                        )
                    }
                }

                // Line 2: Date
                Text(
                    dateStr,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Line 3: Location (empty space reserved when absent)
                if (!currentPhoto.locationName.isNullOrBlank()) {
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
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    // Reserved line — keeps card height stable
                    Spacer(Modifier.height(16.dp))
                }

                // Line 4: Caption — editable inline, add prompt when empty
                if (editingNote) {
                    LaunchedEffect(Unit) { noteFocusRequester.requestFocus() }
                    BasicTextField(
                        value = draftNote,
                        onValueChange = { draftNote = it },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        ),
                        cursorBrush = SolidColor(accentColor),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commitNote() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(noteFocusRequester),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                if (draftNote.isEmpty()) {
                                    Text(
                                        "Write a note about this moment…",
                                        color = Color.White.copy(alpha = 0.3f),
                                        fontSize = 13.sp,
                                        fontStyle = FontStyle.Italic
                                    )
                                }
                                inner()
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { editingNote = false; focusManager.clearFocus() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = Color.White.copy(alpha = 0.45f)
                            )
                        ) { Text("Cancel", fontSize = 12.sp) }
                        TextButton(
                            onClick = { commitNote() },
                            colors = ButtonDefaults.textButtonColors(contentColor = accentColor)
                        ) { Text("Save", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                    }
                } else {
                    val caption = currentPhoto.description
                    if (!caption.isNullOrBlank()) {
                        Column {
                            Text(
                                text = caption,
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                lineHeight = 19.sp,
                                maxLines = if (captionExpanded) Int.MAX_VALUE else 1,
                                overflow = if (captionExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                                onTextLayout = { layout ->
                                    if (!captionExpanded) captionOverflows = layout.hasVisualOverflow
                                },
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { editingNote = true; draftNote = caption }
                            )
                            if (captionOverflows || captionExpanded) {
                                Text(
                                    text = if (captionExpanded) "Show less" else "Show more",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = accentColor,
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { captionExpanded = !captionExpanded }
                                )
                            }
                        }
                    } else {
                        // No note yet — tappable "Add a note…" prompt
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { editingNote = true; draftNote = "" }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Add a note…",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.3f),
                                fontStyle = FontStyle.Italic,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.EditNote,
                                contentDescription = "Add note",
                                tint = Color.White.copy(alpha = 0.2f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
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
