package com.colorwalk.app.ui.newsfeed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.colorwalk.app.data.db.PhotoEntity
import com.colorwalk.app.ui.components.ZoomableAsyncImage
import com.colorwalk.app.ui.components.parseAccentHex
import com.colorwalk.app.ui.components.photoImageRequest
import com.colorwalk.app.ui.components.rememberZoomState
import com.colorwalk.app.viewmodel.NewsfeedViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsfeedScreen(
    onBack: () -> Unit,
    // Swipe-down-to-Home from the top photo reports continuous per-frame deltas (with
    // the pointer event's timestamp, for release-velocity estimation) to the caller
    // instead of firing onBack() itself — HomeHubScreen owns the single clamped drag
    // value shared with Home/Settings, so it decides how far that's allowed to move
    // and whether it settles back to Newsfeed or through to Home.
    onEdgeDragStart: () -> Unit = {},
    onEdgeDrag: (dy: Float, uptimeMillis: Long) -> Unit = { _, _ -> },
    onEdgeDragEnd: () -> Unit = {},
    viewModel: NewsfeedViewModel = hiltViewModel()
) {
    val photos by viewModel.photos.collectAsState()
    val focusManager = LocalFocusManager.current

    // Single edit slot — only one card's description field is open at a time.
    var editingId by remember { mutableStateOf<Long?>(null) }
    var draftText by remember { mutableStateOf("") }

    // Full-frame viewer — null when no photo is open. viewerOrigin remembers the
    // tapped card's photo bounds so the viewer morphs open from (and closes back
    // into) that exact rectangle.
    var fullFramePhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    var viewerOrigin by remember { mutableStateOf<Rect?>(null) }

    fun commitDescription(photo: PhotoEntity) {
        viewModel.saveDescription(photo, draftText)
        editingId = null
        focusManager.clearFocus()
    }

    val listState = rememberLazyListState()

    // A keyed LazyColumn anchors scroll to the first visible item's KEY, so when a
    // just-captured photo is prepended the list stays pinned to the previous newest
    // photo and the new one sits above the viewport. If the user is at (or within one
    // card of) the top, snap to the real top so the newest photo is what they see.
    val newestId = photos.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
    }

    // Swipe down from the top photo drags Home back into view. Compose's default
    // overscroll/glow effect can absorb a drag that goes past the list's own top edge
    // before it ever reaches an ancestor's nested-scroll dispatch, so — same fix
    // GalleryScreen already uses for its same-axis pager nesting — this watches raw
    // pointer events on the Initial pass (before the list's own gesture handling) and
    // reports the drag to the caller once it's a clear, dominantly-downward pull with
    // nowhere left for the list itself to scroll. Never consumes, so normal list
    // scrolling elsewhere is untouched.
    val swipeHomeModifier = Modifier.pointerInput(listState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val eligible = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            var totalX = 0f
            var totalY = 0f
            var reporting = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val dy = change.position.y - change.previousPosition.y
                totalX += change.position.x - change.previousPosition.x
                totalY += dy
                val stillAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (!reporting && eligible && stillAtTop && totalY > 0f && totalY > 2 * abs(totalX)) {
                    reporting = true
                    onEdgeDragStart()
                }
                if (reporting) onEdgeDrag(dy, change.uptimeMillis)
            }
            if (reporting) onEdgeDragEnd()
        }
    }

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
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .then(swipeHomeModifier),
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
                        onPhotoClick = { bounds ->
                            viewerOrigin = bounds
                            fullFramePhoto = photo
                        }
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

        // Full-frame photo overlay — mounts on top; LazyColumn keeps its scroll
        // position. Enter/exit visuals are the viewer's own container morph, not an
        // AnimatedVisibility fade.
        fullFramePhoto?.let { photo ->
            FullFrameViewer(
                photo = photo,
                originBounds = viewerOrigin,
                onDismiss = { fullFramePhoto = null }
            )
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
    onPhotoClick: (Rect) -> Unit
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
        // Its on-screen bounds ride along with the click so the full-frame viewer
        // can morph open FROM this exact rectangle (continuous container transform).
        var photoBounds by remember { mutableStateOf(Rect.Zero) }
        AsyncImage(
            model = photoImageRequest(context, photo.filePath, cacheKey = "feed_${photo.id}"),
            contentDescription = photo.colorName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .onGloballyPositioned { photoBounds = it.boundsInRoot() }
                .clickable(onClick = { onPhotoClick(photoBounds) })
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
    originBounds: Rect?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var swipeYDelta by remember { mutableFloatStateOf(0f) }
    val zoomState = rememberZoomState(resetKey = photo.id)
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 80.dp.toPx() }

    // Container morph: 0 = the card's photo rectangle, 1 = full screen. Opens with a
    // firm spring on mount; every dismissal path reverses the morph back into the
    // card before actually leaving, so the surface reads as ONE container changing
    // bounds — no cut, no crossfade-from-nowhere.
    val morph = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { morph.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 400f)) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            zoomState.reset() // never morph a zoomed/panned photo — reset first
            morph.animateTo(0f, spring(dampingRatio = 1f, stiffness = 550f))
            onDismiss()
        }
    }
    BackHandler { close() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val fullRect = Rect(0f, 0f, wPx, hPx)
        // The open spring is underdamped and overshoots 1 — Color(alpha > 1) throws,
        // so everything downstream must see the morph clamped to [0, 1].
        val p = morph.value.coerceIn(0f, 1f)
        val rect = lerp(originBounds ?: fullRect, fullRect, p)
        // The full (letterboxed, zoomable) rendition fades in over the morphing crop
        // during the last stretch of travel, once the container is nearly full-screen.
        val contentAlpha = ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)

        // Scrim — darkens with the morph.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = p))
        )

        // Full viewer content underneath the morphing crop, fading in late.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha }
        ) {
            // Pinch always zooms/pans; a single finger pans while zoomed in, and
            // otherwise drives the swipe-down-to-close gesture below.
            ZoomableAsyncImage(
                model = photoImageRequest(context, photo.filePath, cacheKey = "full_${photo.id}"),
                contentDescription = photo.colorName,
                state = zoomState,
                modifier = Modifier.fillMaxSize(),
                onGestureStart = { swipeYDelta = 0f },
                onUnzoomedDrag = { change, dragAmount ->
                    change.consume()
                    swipeYDelta += dragAmount.y
                },
                onUnzoomedGestureEnd = {
                    if (swipeYDelta > dismissThresholdPx) close()
                    swipeYDelta = 0f
                }
            )

            // Back button
            IconButton(
                onClick = { close() },
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

        // The morphing container itself: the card's cropped rendition (same cache
        // key, so it draws instantly from memory) animating bounds card → screen,
        // corners rounding off en route. Hidden once the full content has taken over.
        if (contentAlpha < 1f) {
            Box(
                Modifier
                    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                    .graphicsLayer { alpha = 1f - contentAlpha }
                    .clip(RoundedCornerShape(12.dp * (1f - p)))
            ) {
                AsyncImage(
                    model = photoImageRequest(context, photo.filePath, cacheKey = "feed_${photo.id}"),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
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
