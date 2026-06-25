package com.colorwalk.app.ui.camera

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.colorwalk.app.viewmodel.CameraViewModel
import com.colorwalk.app.viewmodel.CaptureState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

private data class ZoomLevel(val label: String, val ratio: Float)

private val ZOOM_LEVELS = listOf(
    ZoomLevel(".5×", 0.5f),
    ZoomLevel("1×",  1f),
    ZoomLevel("1.5×",1.5f),
    ZoomLevel("2×",  2f),
    ZoomLevel("3×",  3f),
    ZoomLevel("5×",  5f),
    ZoomLevel("10×", 10f),
    ZoomLevel("15×", 15f),
    ZoomLevel("20×", 20f),
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    if (!cameraPermission.status.isGranted) {
        CameraPermissionDenied(
            onRequestPermission = { cameraPermission.launchPermissionRequest() },
            onBack = onBack
        )
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val captureState by viewModel.captureState.collectAsState()
    val targetColor by viewModel.targetColor.collectAsState()

    // Refresh the target color on every resume so the camera never validates
    // against yesterday's color when the app is kept alive past midnight.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshTargetColor()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var useFrontCamera by remember { mutableStateOf(false) }
    // requestedZoom: the level the user tapped; activeZoom: the ratio the camera
    // actually applied after clamping to the device's supported range. Only
    // activeZoom is used for chip highlighting so the UI always reflects reality.
    var requestedZoom by remember { mutableStateOf(1f) }
    var activeZoom by remember { mutableStateOf(1f) }
    var minZoom by remember { mutableStateOf(1f) }
    var maxZoom by remember { mutableStateOf(20f) }
    val supportedLevels = remember(minZoom, maxZoom) {
        ZOOM_LEVELS.filter { it.ratio in minZoom..maxZoom }
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }

    val cameraSelector = if (useFrontCamera)
        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.onPhotoImported(uri) }

    var swipeXDelta by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeXDelta = 0f },
                    onDragEnd = {
                        if (swipeXDelta < -80.dp.toPx()) onBack()
                        swipeXDelta = 0f
                    },
                    onDragCancel = { swipeXDelta = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    swipeXDelta += dragAmount
                }
            }
    ) {

        // key(cameraSelector) destroys and recreates the AndroidView only when the user
        // flips between front/back — not on every recomposition (zoom taps, state changes, etc.).
        // No update block needed: factory handles the bind, LaunchedEffect handles zoom.
        key(cameraSelector) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { previewView ->
                        bindCamera(ctx, lifecycleOwner, previewView, cameraSelector) { cap, cam ->
                            imageCapture = cap
                            camera = cam
                            cam.cameraInfo.zoomState.value?.let {
                                minZoom = it.minZoomRatio
                                maxZoom = it.maxZoomRatio
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Apply zoom without rebinding the camera. Fires when the user taps a chip
        // (requestedZoom changes) or when the camera is rebound after a flip (camera
        // changes). The clamped value is written back to activeZoom so the highlighted
        // chip always matches what the hardware is actually showing.
        LaunchedEffect(requestedZoom, camera) {
            val cam = camera ?: return@LaunchedEffect
            val state = cam.cameraInfo.zoomState.value ?: return@LaunchedEffect
            val clamped = requestedZoom.coerceIn(state.minZoomRatio, state.maxZoomRatio)
            cam.cameraControl.setZoomRatio(clamped)
            activeZoom = clamped
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(targetColor.composeColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Find ${targetColor.name}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom level buttons
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 14.dp)
            ) {
                items(supportedLevels) { level ->
                    val isActive = activeZoom == level.ratio
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) targetColor.composeColor
                                else Color.Black.copy(alpha = 0.55f)
                            )
                    ) {
                        TextButton(
                            onClick = { requestedZoom = level.ratio },
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                level.label,
                                fontSize = if (level.label.length > 3) 9.sp else 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isActive) Color.White else Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }

            Text(
                "or import from gallery",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Import
                IconButton(
                    onClick = {
                        importLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Import", tint = Color.White, modifier = Modifier.size(24.dp))
                }

                // Shutter
                if (captureState !is CaptureState.Processing) {
                    IconButton(
                        onClick = {
                            val capture = imageCapture ?: return@IconButton
                            viewModel.startCapture()
                            capture.takePicture(
                                cameraExecutor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(proxy: ImageProxy) {
                                        val buffer = proxy.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        val cameraXRotation = proxy.imageInfo.rotationDegrees
                                        proxy.close()
                                        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                            ?: return
                                        // Prefer EXIF orientation embedded in the JPEG by the camera HAL
                                        // (reflects actual device orientation at capture time). Fall back
                                        // to CameraX rotationDegrees only if EXIF tag is absent.
                                        val exifOrientation = try {
                                            ExifInterface(bytes.inputStream())
                                                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                                        } catch (_: Exception) { ExifInterface.ORIENTATION_UNDEFINED }
                                        val rotation = when (exifOrientation) {
                                            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                            ExifInterface.ORIENTATION_NORMAL     -> 0f
                                            else -> cameraXRotation.toFloat()
                                        }
                                        if (rotation != 0f) {
                                            val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
                                            val rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                                            bmp.recycle()
                                            bmp = rotated
                                        }
                                        viewModel.onPhotoCaptured(bmp)
                                    }
                                    override fun onError(e: ImageCaptureException) {
                                        Log.e("CameraScreen", "Capture error", e)
                                        viewModel.onCaptureError()
                                    }
                                }
                            )
                        },
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(5.dp, targetColor.composeColor, CircleShape)
                    ) {
                        // Center dot in the target color — the shutter IS the mission
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(targetColor.composeColor.copy(alpha = 0.85f))
                        )
                    }
                } else {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(56.dp))
                }

                // Flip
                IconButton(
                    onClick = {
                        useFrontCamera = !useFrontCamera
                        requestedZoom = 1f
                        activeZoom = 1f
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }

        // Note prompt — full overlay so the keyboard has room to appear
        val awaitingNote = captureState as? CaptureState.AwaitingNote
        if (awaitingNote != null) {
            NotePromptCard(
                state = awaitingNote,
                onSkip = onBack,
                onSave = { note ->
                    viewModel.saveNoteForPhoto(awaitingNote.photoId, note, onDone = onBack)
                }
            )
        }

        // Result overlay — bottom-anchored card in the thumb zone
        AnimatedVisibility(
            visible = captureState !is CaptureState.Idle
                    && captureState !is CaptureState.Processing
                    && captureState !is CaptureState.AwaitingNote,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 120.dp)
        ) {
            ResultCard(
                state = captureState,
                targetColorName = targetColor.name,
                onDismiss = { viewModel.resetState() },
                onBack = onBack
            )
        }
    }
}

@Composable
private fun NotePromptCard(
    state: CaptureState.AwaitingNote,
    onSkip: () -> Unit,
    onSave: (String) -> Unit
) {
    val accentColor = parseResultHex(state.dominantHex)
    var noteText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B22)),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Color swatch + success indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        "Color Match!",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "Add a note to this photo",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )

                Spacer(Modifier.height(12.dp))

                // Text field
                BasicTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    cursorBrush = SolidColor(accentColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        if (noteText.isNotBlank()) onSave(noteText) else onSkip()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            if (noteText.isEmpty()) {
                                Text(
                                    "What did you find? Where was it?",
                                    color = Color.White.copy(alpha = 0.3f),
                                    fontSize = 15.sp,
                                    fontStyle = FontStyle.Italic
                                )
                            }
                            inner()
                        }
                    }
                )

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            1.dp, Color.White.copy(alpha = 0.25f)
                        )
                    ) {
                        Text("Skip", color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            onSave(noteText)
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        enabled = noteText.isNotBlank()
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun bindCamera(
    context: android.content.Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    cameraSelector: CameraSelector,
    onBound: (ImageCapture, Camera) -> Unit
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, capture)
            onBound(capture, cam)
        } catch (e: Exception) {
            Log.e("CameraScreen", "Bind failed", e)
        }
    }, ContextCompat.getMainExecutor(context))
}

@Composable
private fun CameraPermissionDenied(
    onRequestPermission: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "Camera access required",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "Hue & Seek needs camera access to capture your daily color walk photos. Your photos are saved only to your device.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )

            Spacer(Modifier.height(36.dp))

            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Grant Access", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Open Settings", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ResultCard(
    state: CaptureState,
    targetColorName: String,
    onDismiss: () -> Unit,
    onBack: () -> Unit
) {
    val targetSwatch = com.colorwalk.app.domain.WALK_COLORS
        .firstOrNull { it.name == targetColorName }?.composeColor ?: Color.Gray

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B22)),
        modifier = Modifier.padding(horizontal = 24.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state) {
                is CaptureState.Failed -> {
                    val pct = (state.matchPercent * 100).toInt()
                    val needPct = (com.colorwalk.app.domain.ColorValidator.MIN_TARGET_SHARE * 100).toInt()
                    val targetLeads = state.actualDominant == targetColorName
                    val actualSwatch = com.colorwalk.app.domain.WALK_COLORS
                        .firstOrNull { it.name == state.actualDominant }?.composeColor
                        ?: Color(0xFF9E9E9E) // neutral tones

                    Text(
                        if (targetLeads) "More $targetColorName Needed" else "$targetColorName Isn't Dominant",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    // Target vs actual — show the user exactly what the camera saw
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ResultSwatch(color = targetSwatch, label = targetColorName, sub = "Target")
                        Spacer(Modifier.width(28.dp))
                        ResultSwatch(color = actualSwatch, label = state.actualDominant, sub = "Dominated")
                    }
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { state.matchPercent.coerceIn(0f, 1f) },
                        color = targetSwatch,
                        trackColor = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$pct% of the frame was $targetColorName — needs $needPct%+. " +
                            if (targetLeads) "Get closer so it pops!" else "Make it the main subject.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    // Hint when a nearby color (e.g. Pink on a Red day) was found —
                    // helps the user understand what the camera actually detected.
                    if (!targetLeads && state.nearestColorName != null) {
                        val nearPct = (state.nearestColorShare * 100).toInt()
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tip: ${nearPct}% ${state.nearestColorName} was found — try something more purely $targetColorName.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onDismiss) {
                        Text("Try Again", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }

                else -> {
                    val (title, subtitle) = when (state) {
                        is CaptureState.ImportWrongDay -> Pair(
                            "Wrong Day",
                            "Photo taken on ${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(state.dateTaken))} — must be today."
                        )
                        CaptureState.ImportNoDate -> Pair(
                            "No Date Info",
                            "This photo has no date metadata — can't verify it was taken today."
                        )
                        CaptureState.ImportDuplicate -> Pair(
                            "Already Imported",
                            "This photo is already in your gallery."
                        )
                        else -> Pair("Error", "Something went wrong.")
                    }
                    Icon(
                        Icons.Default.ErrorOutline, contentDescription = null,
                        tint = Color(0xFFFF9800), modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onDismiss) {
                        Text("Try Again", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultSwatch(color: Color, label: String, sub: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color)
                .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape)
        )
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
    }
}

private fun parseResultHex(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) { Color.Gray }
