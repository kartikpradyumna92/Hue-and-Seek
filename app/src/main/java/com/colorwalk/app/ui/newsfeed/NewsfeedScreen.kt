package com.colorwalk.app.ui.newsfeed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.ui.components.parseAccentHex
import com.colorwalk.app.ui.components.photoImageRequest
import com.colorwalk.app.viewmodel.NewsfeedViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsfeedScreen(
    onBack: () -> Unit,
    viewModel: NewsfeedViewModel = hiltViewModel()
) {
    val photos by viewModel.photos.collectAsState()
    val focusManager = LocalFocusManager.current

    // Single edit slot — only one card's description field is open at a time.
    var editingId by remember { mutableStateOf<Long?>(null) }
    var draftText by remember { mutableStateOf("") }

    // Full-frame viewer — null when no photo is open.
    var fullFramePhoto by remember { mutableStateOf<PhotoEntity?>(null) }

    fun commitDescription(photo: PhotoEntity) {
        viewModel.saveDescription(photo, draftText)
        editingId = null
        focusManager.clearFocus()
    }

    var swipeXDelta by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Your Walks",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->

            if (photos.isEmpty()) {
                EmptyNewsfeed(modifier = Modifier.padding(innerPadding))
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { swipeXDelta = 0f },
                            onDragEnd = {
                                if (swipeXDelta > 80.dp.toPx()) onBack()
                                swipeXDelta = 0f
                            },
                            onDragCancel = { swipeXDelta = 0f }
                        ) { change, dragAmount ->
                            change.consume()
                            swipeXDelta += dragAmount
                        }
                    },
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(photos, key = { _, p -> p.id }) { index, photo ->
                    val isEditing = editingId == photo.id

                    WalkCard(
                        photo = photo,
                        isEditing = isEditing,
                        draftText = if (isEditing) draftText else (photo.description ?: ""),
                        onDraftChange = { draftText = it },
                        onStartEdit = {
                            editingId = photo.id
                            draftText = photo.description ?: ""
                        },
                        onCommit = { commitDescription(photo) },
                        onCancelEdit = {
                            editingId = null
                            focusManager.clearFocus()
                        },
                        onPhotoClick = { fullFramePhoto = photo }
                    )

                    if (index < photos.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }

        // Full-frame photo overlay — mounts on top; LazyColumn keeps its scroll position.
        AnimatedVisibility(
            visible = fullFramePhoto != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            fullFramePhoto?.let { photo ->
                FullFrameViewer(
                    photo = photo,
                    onDismiss = { fullFramePhoto = null }
                )
            }
        }
    }
}

@Composable
private fun WalkCard(
    photo: PhotoEntity,
    isEditing: Boolean,
    draftText: String,
    onDraftChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCommit: () -> Unit,
    onCancelEdit: () -> Unit,
    onPhotoClick: () -> Unit
) {
    val context = LocalContext.current
    val accentColor = parseAccentHex(photo.dominantColorHex)
    val dateStr = remember(photo.dateTaken) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(photo.dateTaken))
    }
    val focusRequester = remember { FocusRequester() }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {

        // ── Header: color dot + name (left) · date (right) ──────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Text(
                    text = photo.colorName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = photo.colorHex.uppercase(),
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
            Text(
                text = dateStr,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }

        // ── Location row ─────────────────────────────────────────────────────
        if (!photo.locationName.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = photo.locationName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── Square photo ─────────────────────────────────────────────────────
        AsyncImage(
            model = photoImageRequest(context, photo.filePath, cacheKey = "feed_${photo.id}"),
            contentDescription = photo.colorName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onPhotoClick)
        )

        Spacer(Modifier.height(10.dp))

        // ── Description ───────────────────────────────────────────────────────
        if (isEditing) {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                BasicTextField(
                    value = draftText,
                    onValueChange = onDraftChange,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 14.sp,
                        lineHeight = 21.sp
                    ),
                    cursorBrush = SolidColor(accentColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    decorationBox = { innerField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            if (draftText.isEmpty()) {
                                Text(
                                    "Write a note about this moment…",
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                                    fontSize = 14.sp,
                                    fontStyle = FontStyle.Italic
                                )
                            }
                            innerField()
                        }
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onCancelEdit,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    ) { Text("Cancel", fontSize = 13.sp) }
                    TextButton(
                        onClick = onCommit,
                        colors = ButtonDefaults.textButtonColors(contentColor = accentColor)
                    ) { Text("Save", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                }
            }
        } else {
            val hasNote = !photo.description.isNullOrBlank()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onStartEdit
                    )
                    .background(
                        if (hasNote) Color.Transparent
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                    .padding(horizontal = if (hasNote) 0.dp else 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (hasNote) photo.description!! else "Add a note…",
                    color = if (hasNote) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                    fontSize = 14.sp,
                    fontStyle = if (hasNote) FontStyle.Normal else FontStyle.Italic,
                    lineHeight = 21.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.EditNote,
                    contentDescription = "Edit note",
                    tint = if (hasNote) accentColor.copy(alpha = 0.7f)
                           else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun FullFrameViewer(
    photo: PhotoEntity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var swipeYDelta by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { swipeYDelta = 0f },
                    onDragEnd = {
                        if (swipeYDelta > 80.dp.toPx()) onDismiss()
                        swipeYDelta = 0f
                    },
                    onDragCancel = { swipeYDelta = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    swipeYDelta += dragAmount.y
                }
            }
    ) {
        AsyncImage(
            model = photoImageRequest(context, photo.filePath, cacheKey = "full_${photo.id}"),
            contentDescription = photo.colorName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        // Back button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        // Swipe hint
        Text(
            text = "Swipe down to close",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        )
    }
}

@Composable
private fun EmptyNewsfeed(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "No walks yet",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Go on a color walk to fill your journal",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
    }
}
