package com.colorwalk.app.ui.camera

import android.Manifest
import android.content.Intent
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.util.Size
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.colorwalk.app.domain.ColorValidator
import com.colorwalk.app.ui.theme.DayTheme
import com.colorwalk.app.viewmodel.CameraViewModel
import com.colorwalk.app.viewmodel.CaptureState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

private data class ZoomLevel(val label: String, val ratio: Float)

/**
 * Exponential smoothing rate (1/s) for the zoom ease — the time constant is
 * 1/RATE ≈ 70 ms: fast enough to feel directly connected to the fingers, slow
 * enough to swallow pinch jitter, close to Google Photos' dampening.
 */
private const val ZOOM_SMOOTHING_RATE = 14f

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

    // ON_RESUME alone can't cover the hub: the activity stays RESUMED for the whole
    // session, so crossing local midnight while foreground fired no refresh and the
    // camera kept validating against yesterday's color (H-4). Refresh on entering
    // the pane (it leaves composition whenever the hub isn't settled on Camera),
    // then re-arm for each midnight while the user stays here.
    LaunchedEffect(Unit) {
        viewModel.refreshTargetColor()
        while (true) {
            kotlinx.coroutines.delay(
                com.colorwalk.app.domain.StreakCalculator.millisUntilNextLocalMidnight() + 500L
            )
            viewModel.refreshTargetColor()
        }
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
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

    // The use cases are bound to the ACTIVITY lifecycle, which stays RESUMED for the
    // whole session — leaving this pane does NOT stop the camera on its own. Unbind
    // explicitly on dispose (before shutting the analysis executor, so no frame is
    // ever posted to a dead executor), or the hardware, preview pipeline, and live
    // analyzer keep running — privacy indicator lit and battery burning — the entire
    // time the user is on Home/Gallery/Settings/Newsfeed (H-1).
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
            // Leaving the pane with the note prompt still open counts as skipping it —
            // otherwise the stale AwaitingNote greets the user instead of the
            // viewfinder on their next swipe into Camera.
            viewModel.dismissNotePromptIfPending()
        }
    }

    val cameraSelector = if (useFrontCamera)
        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.onPhotoImported(uri) }

    // Live "how much of the frame is today's color" pipeline. The analyzer produces
    // into a CONFLATED channel from the camera thread and gates unchanged values at
    // the source, so everything arriving here is a real meter movement worth a
    // recomposition.
    val shareChannel = remember { Channel<Float>(Channel.CONFLATED) }
    val liveAnalyzer = remember { LiveColorAnalyzer(shareChannel) { viewModel.targetColor.value } }
    var liveShare by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        for (share in shareChannel) liveShare = share
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(minZoom, maxZoom) {
                // Only pinch (2+ fingers) is handled here. Single-finger drags are left
                // unconsumed so the outer pager (HomeHubScreen) can swipe back to Home.
                // The pinch only moves the TARGET zoom; the smoothing loop below eases
                // the hardware toward it, so raw finger jitter never reaches the lens.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            event.changes.forEach { it.consume() }
                            requestedZoom = (requestedZoom * com.colorwalk.app.ui.components.pinchScaleFactor(pressed[0], pressed[1]))
                                .coerceIn(minZoom, maxZoom)
                        }
                    }
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
                        // COMPATIBLE (TextureView-backed) instead of the SurfaceView-backed
                        // default: a SurfaceView is composited on its own hardware layer and
                        // ignores the pager's translation during a swipe, so the preview pixels
                        // visibly lag/tear away from the rest of the page mid-drag.
                        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        bindCamera(ctx, lifecycleOwner, previewView, cameraSelector, liveAnalyzer, cameraExecutor) { cap, cam, provider ->
                            imageCapture = cap
                            camera = cam
                            cameraProvider = provider
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

        // Golden grid — rule-of-thirds lines plus dots on the five focal points the
        // validator's subject-saliency path rewards, so composing "on the grid" is
        // literally composing for a pass. Geometry is computed once per size inside
        // drawWithCache and the draw block reads NO state: it lives purely in the
        // draw phase, so pinch-zooming (which recomposes chips/state) never invalidates
        // or redraws it — flat cost at any refresh rate.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val line = Color.White.copy(alpha = 0.20f)
                    val dot = Color.White.copy(alpha = 0.38f)
                    val stroke = 1.dp.toPx()
                    val dotR = 2.5.dp.toPx()
                    val xs = floatArrayOf(w / 3f, 2f * w / 3f)
                    val ys = floatArrayOf(h / 3f, 2f * h / 3f)
                    val focals = arrayOf(
                        Offset(w / 2f, h / 2f),
                        Offset(xs[0], ys[0]), Offset(xs[1], ys[0]),
                        Offset(xs[0], ys[1]), Offset(xs[1], ys[1]),
                    )
                    onDrawBehind {
                        for (x in xs) drawLine(line, Offset(x, 0f), Offset(x, h), stroke)
                        for (y in ys) drawLine(line, Offset(0f, y), Offset(w, y), stroke)
                        for (f in focals) drawCircle(dot, dotR, f)
                    }
                }
        )

        // Zoom smoothing: eases the hardware zoom toward requestedZoom with a
        // frame-clocked exponential curve in LOG space (zoom is perceptually
        // multiplicative — equal log steps look like equal zoom steps). collectLatest
        // restarts the ease from the current position whenever the target moves
        // (pinch stream, chip tap), and the loop exits once converged, so nothing
        // runs per-frame while idle. Snaps exactly onto the target at the end so the
        // chip highlight's equality check works.
        LaunchedEffect(camera) {
            val cam = camera ?: return@LaunchedEffect
            var current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
            activeZoom = current
            snapshotFlow { requestedZoom }.collectLatest { raw ->
                var lastNanos = 0L
                while (true) {
                    val state = cam.cameraInfo.zoomState.value ?: break
                    val target = raw.coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    if (current == target) break
                    val frameNanos = withFrameNanos { it }
                    val dt = if (lastNanos == 0L) 0.016f
                             else ((frameNanos - lastNanos) / 1e9f).coerceAtMost(0.1f)
                    lastNanos = frameNanos
                    val alpha = 1f - exp(-ZOOM_SMOOTHING_RATE * dt)
                    val lnC = ln(current)
                    val lnT = ln(target)
                    current = if (abs(lnT - lnC) < 0.004f) target
                              else exp(lnC + alpha * (lnT - lnC))
                    cam.cameraControl.setZoomRatio(current)
                    activeZoom = current
                }
            }
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
            // Live color meter — fills as more of the frame reads as today's color,
            // full at the 15% global pass threshold. Driven by the analyzer pipeline.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${(liveShare * 100).toInt()} percent of the frame is ${targetColor.name}"
                    }
            ) {
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.22f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((liveShare / ColorValidator.MIN_TARGET_SHARE).coerceIn(0f, 1f))
                            .background(targetColor.composeColor)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(liveShare * 100).toInt()}%",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

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
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) targetColor.composeColor
                                else Color.Black.copy(alpha = 0.55f)
                            )
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${level.label} zoom" + if (isActive) ", selected" else ""
                            }
                    ) {
                        TextButton(
                            onClick = { requestedZoom = level.ratio },
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                level.label,
                                fontSize = if (level.label.length > 3) 10.sp else 12.sp,
                                fontWeight = FontWeight.Bold,
                                // Active chip sits ON the day accent — content color must
                                // come from measured luminance (white is unreadable on a
                                // Yellow day), so it uses the WCAG-derived palette.
                                color = if (isActive) DayTheme.palette.onAccent
                                        else Color.White.copy(alpha = 0.75f)
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
                                        // Hand the HAL's original JPEG bytes straight to the
                                        // repository (L-2): they're written to disk verbatim, so
                                        // EXIF (orientation, capture time, device tags) survives
                                        // and there's no re-encode generation. Bounded decode for
                                        // validation (H-3) and decode-failure handling (H-2) live
                                        // in the repository with the rest of the save pipeline.
                                        val buffer = proxy.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        proxy.close()
                                        // Front captures arrive un-mirrored from the HAL; flag
                                        // them so the save mirrors the EXIF orientation and the
                                        // stored selfie matches what the preview showed (L-3).
                                        viewModel.onPhotoCaptured(bytes, mirror = useFrontCamera)
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
                // Skip must reset the state machine, not just navigate: the ViewModel
                // outlives this pane, so a lingering AwaitingNote re-mounts the note
                // prompt (instead of the viewfinder) on the NEXT swipe into Camera.
                onSkip = {
                    viewModel.resetState()
                    onBack()
                },
                onSave = { note ->
                    viewModel.saveNoteForPhoto(awaitingNote.photoId, note, onDone = onBack)
                }
            )
        }

        // Result overlay — bottom-anchored card in the thumb zone, arriving on a
        // gently bouncy spring (an interior element: overshoot here is charm, and
        // can't expose anything the way a page-level bounce could).
        AnimatedVisibility(
            visible = captureState !is CaptureState.Idle
                    && captureState !is CaptureState.Processing
                    && captureState !is CaptureState.AwaitingNote,
            enter = fadeIn() + slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = 350f,
                    visibilityThreshold = IntOffset.VisibilityThreshold
                ),
                initialOffsetY = { it / 2 }
            ),
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
    analyzer: ImageAnalysis.Analyzer,
    analysisExecutor: Executor,
    onBound: (ImageCapture, Camera, ProcessCameraProvider) -> Unit
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
        // Live color analysis: tiny frames (the analyzer samples 64×48 anyway),
        // KEEP_ONLY_LATEST so a slow pass drops frames instead of queueing them —
        // the preview can never stall behind analysis. Bound to the same lifecycle,
        // so leaving the camera page tears the analyzer down with the hardware.
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, capture, analysis)
            onBound(capture, cam, provider)
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
                        CaptureState.ImportTampered -> Pair(
                            "Can't Verify Photo",
                            "This photo's date metadata looks edited or inconsistent — it can't be counted for today's walk."
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

