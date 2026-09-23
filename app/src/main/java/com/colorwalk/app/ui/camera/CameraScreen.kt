package com.colorwalk.app.ui.camera

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
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
import androidx.compose.ui.draw.alpha
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
import com.colorwalk.app.data.repository.NormalizedCrop
import com.colorwalk.app.domain.ColorValidator
import com.colorwalk.app.ui.theme.DayTheme
import com.colorwalk.app.ui.theme.ForceLightSystemBarIcons
import com.colorwalk.app.viewmodel.CameraViewModel
import com.colorwalk.app.viewmodel.CaptureState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import androidx.compose.ui.res.stringResource
import com.colorwalk.app.R
import com.colorwalk.app.ui.components.colorDisplayName
import android.content.Context
import com.colorwalk.app.ui.components.localizedDateFormat

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

/** Set whenever the camera permission dialog is requested (here or at onboarding). */
const val KEY_CAMERA_PERMISSION_REQUESTED = "camera_permission_requested"

// Process-lifetime executor for takePicture callbacks. A capture must be able to
// report back after the Camera pane has left composition (swipe-away, back,
// rotation), so its callback can't run on the pane-scoped analysis executor.
private val CaptureCallbackExecutor: Executor = Executors.newSingleThreadExecutor()

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    // Black viewfinder: dark status/nav icons would vanish on a light app theme (BUG-012).
    ForceLightSystemBarIcons()

    val context = LocalContext.current
    if (!cameraPermission.status.isGranted) {
        // BUG-049: after "Don't allow" twice (or "don't ask again"), the system shows no
        // dialog at all, so "Grant Access" silently did nothing. Asked before + no
        // rationale = permanently denied → only Settings can fix it.
        val prefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
        val permanentlyDenied = prefs.getBoolean(KEY_CAMERA_PERMISSION_REQUESTED, false) &&
            !cameraPermission.status.shouldShowRationale
        CameraPermissionDenied(
            permanentlyDenied = permanentlyDenied,
            onRequestPermission = {
                prefs.edit().putBoolean(KEY_CAMERA_PERMISSION_REQUESTED, true).apply()
                cameraPermission.launchPermissionRequest()
            },
            onBack = onBack
        )
        return
    }

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
    // BUG-043: lens and zoom live in the ViewModel, so a teardown (hub nudge) keeps them.
    var useFrontCamera by viewModel::useFrontCamera
    // requestedZoom: the level the user tapped; activeZoom: the ratio the camera
    // actually applied after clamping to the device's supported range. Only
    // activeZoom is used for chip highlighting so the UI always reflects reality.
    var requestedZoom by viewModel::requestedZoom
    var activeZoom by remember { mutableStateOf(1f) }
    // BUG-016: a failed bind / camera error is shown with a retry, not a black void.
    var cameraError by remember { mutableStateOf<String?>(null) }
    var bindAttempt by remember { mutableIntStateOf(0) }
    var hasFrontCamera by remember { mutableStateOf(true) }
    var minZoom by remember { mutableStateOf(1f) }
    var maxZoom by remember { mutableStateOf(20f) }
    val supportedLevels = remember(minZoom, maxZoom) {
        ZOOM_LEVELS.filter { it.ratio in minZoom..maxZoom }
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    // BUG-022: the first bind completes asynchronously (ProcessCameraProvider init).
    // Leaving before it lands found cameraProvider == null below, unbound nothing, and
    // the late bind then attached the camera to the ACTIVITY lifecycle with no pane on
    // screen — privacy indicator lit, battery draining. The bind checks this first.
    val paneActive = remember { AtomicBoolean(true) }

    // The use cases are bound to the ACTIVITY lifecycle, which stays RESUMED for the
    // whole session — leaving this pane does NOT stop the camera on its own. Unbind
    // explicitly on dispose (before shutting the analysis executor, so no frame is
    // ever posted to a dead executor), or the hardware, preview pipeline, and live
    // analyzer keep running — privacy indicator lit and battery burning — the entire
    // time the user is on Home/Gallery/Settings/Newsfeed (H-1).
    DisposableEffect(Unit) {
        onDispose {
            paneActive.set(false)
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
        // bindAttempt: "Retry" after a camera error recreates the view and rebinds.
        key(cameraSelector, bindAttempt) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { previewView ->
                        // COMPATIBLE (TextureView-backed) instead of the SurfaceView-backed
                        // default: a SurfaceView is composited on its own hardware layer and
                        // ignores the pager's translation during a swipe, so the preview pixels
                        // visibly lag/tear away from the rest of the page mid-drag.
                        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        cameraError = null
                        bindCamera(
                            ctx, lifecycleOwner, previewView, cameraSelector, liveAnalyzer,
                            cameraExecutor, paneActive::get,
                            onBindError = { e ->
                                imageCapture = null
                                cameraError = cameraErrorMessage(context, e)
                            },
                            onProvider = { provider ->
                                hasFrontCamera = try {
                                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                                } catch (_: Exception) { false }
                            }
                        ) { cap, cam, provider ->
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

        // BUG-016: a camera that errors AFTER binding (another app takes it, it's
        // disabled by policy, a fatal HAL error) surfaces here too.
        DisposableEffect(camera) {
            val info = camera?.cameraInfo
            val observer = androidx.lifecycle.Observer<CameraState> { state ->
                val error = state.error
                cameraError = when {
                    error == null -> null
                    // Recoverable errors CameraX retries itself — don't alarm the user.
                    error.type == CameraState.ErrorType.RECOVERABLE -> null
                    error.code == CameraState.ERROR_CAMERA_IN_USE ||
                        error.code == CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                        context.getString(R.string.camera_error_in_use)
                    error.code == CameraState.ERROR_CAMERA_DISABLED ->
                        context.getString(R.string.camera_error_disabled)
                    else -> context.getString(R.string.camera_error_stopped)
                }
            }
            info?.cameraState?.observe(lifecycleOwner, observer)
            onDispose { info?.cameraState?.removeObserver(observer) }
        }

        // BUG-015: CameraX only knows the DISPLAY rotation, and the activity is
        // portrait-locked — so a photo held in landscape was saved with portrait EXIF
        // and displayed sideways. Follow the physical orientation instead.
        DisposableEffect(imageCapture) {
            val capture = imageCapture
            val listener = object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN || capture == null) return
                    capture.targetRotation = when (orientation) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    }
                }
            }
            if (capture != null) listener.enable()
            onDispose { listener.disable() }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
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
                        stringResource(R.string.home_find_color, colorDisplayName(targetColor.name)),
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
                        contentDescription = context.resources.getQuantityString(
                            R.plurals.camera_live_share_desc, (liveShare * 100).toInt(), (liveShare * 100).toInt(),
                            context.colorDisplayName(targetColor.name)
                        )
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
                    stringResource(R.string.camera_percent, (liveShare * 100).toInt()),
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
                                contentDescription = context.getString(
                                    if (isActive) R.string.zoom_level_desc_selected else R.string.zoom_level_desc,
                                    level.label
                                )
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
                stringResource(R.string.camera_or_import),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            // BUG-044: while a capture/import is being processed, a second import or a
            // lens flip (which rebinds and aborts the capture) must not be possible.
            val idle = captureState !is CaptureState.Processing
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
                    enabled = idle,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .alpha(if (idle) 1f else 0.4f)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.camera_import), tint = Color.White, modifier = Modifier.size(24.dp))
                }

                // Shutter
                if (idle) {
                    val bound = imageCapture != null
                    IconButton(
                        onClick = {
                            val capture = imageCapture ?: return@IconButton
                            // Snapshot at the tap: the callback can land after the pane
                            // (and this state) is gone.
                            val mirror = useFrontCamera
                            viewModel.startCapture()
                            capture.takePicture(
                                // NOT cameraExecutor: that one is shut down when the pane
                                // leaves composition, which would strand the result and
                                // leave the shutter stuck in Processing (BUG-001).
                                CaptureCallbackExecutor,
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
                                        // BUG-014: the viewport crop (what the preview showed),
                                        // in the JPEG buffer's own orientation. The file is saved
                                        // whole; only validation looks at this region.
                                        val crop = proxy.cropRect.let {
                                            normalizedCropOf(it.left, it.top, it.right, it.bottom, proxy.width, proxy.height)
                                        }
                                        proxy.close()
                                        // Front captures arrive un-mirrored from the HAL; flag
                                        // them so the save mirrors the EXIF orientation and the
                                        // stored selfie matches what the preview showed (L-3).
                                        viewModel.onPhotoCaptured(bytes, mirror = mirror, crop = crop)
                                    }
                                    override fun onError(e: ImageCaptureException) {
                                        if (e.imageCaptureError == ImageCapture.ERROR_CAMERA_CLOSED) {
                                            // Pane left mid-capture: unbindAll aborts the
                                            // request. The user walked away — not an error.
                                            viewModel.onCaptureAborted()
                                        } else {
                                            Log.e("CameraScreen", "Capture error", e)
                                            viewModel.onCaptureError()
                                        }
                                    }
                                }
                            )
                        },
                        // BUG-016: no silent dead shutter — disabled (and dimmed) until
                        // the camera is actually bound.
                        enabled = bound,
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(5.dp, targetColor.composeColor, CircleShape)
                            .alpha(if (bound) 1f else 0.5f)
                            // BUG-023: the app's core action was announced as just "Button".
                            .semantics {
                                contentDescription = context.getString(
                                    R.string.camera_shutter_desc, context.colorDisplayName(targetColor.name)
                                )
                            }
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
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier
                            .size(56.dp)
                            .semantics { contentDescription = context.getString(R.string.camera_checking) }
                    )
                }

                // Flip — hidden on devices without a front camera (binding one failed
                // silently before); a spacer keeps the shutter centered.
                if (hasFrontCamera) {
                    IconButton(
                        onClick = {
                            useFrontCamera = !useFrontCamera
                            requestedZoom = 1f
                            activeZoom = 1f
                        },
                        enabled = idle,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .alpha(if (idle) 1f else 0.4f)
                    ) {
                        Icon(
                            Icons.Default.FlipCameraAndroid,
                            contentDescription = stringResource(
                                if (useFrontCamera) R.string.camera_switch_back else R.string.camera_switch_front
                            ),
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.size(52.dp))
                }
            }
        }

        // BUG-016: camera unavailable — say why and offer a retry instead of a black
        // preview with a dead shutter.
        cameraError?.let { message ->
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B22)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.ErrorOutline, contentDescription = null,
                        tint = Color(0xFFFF9800), modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.camera_unavailable), style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = {
                        // A missing lens: fall back to the back camera before retrying.
                        if (useFrontCamera && !hasFrontCamera) useFrontCamera = false
                        cameraError = null
                        bindAttempt++
                    }) {
                        Text(stringResource(R.string.action_try_again), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
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
                },
                // BUG-048: mirrored to the ViewModel so leaving the pane with a typed
                // note saves it instead of silently discarding it.
                onDraftChange = viewModel::onNoteDraftChanged
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
    onSave: (String) -> Unit,
    onDraftChange: (String) -> Unit
) {
    val accentColor = parseResultHex(state.dominantHex)
    var noteText by remember { mutableStateOf("") }
    LaunchedEffect(noteText) { onDraftChange(noteText) }
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
                        stringResource(R.string.camera_color_match),
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
                    stringResource(R.string.camera_note_title),
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
                                    stringResource(R.string.camera_note_hint),
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
                        Text(stringResource(R.string.action_skip), color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold)
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
                        Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold)
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
    isPaneActive: () -> Boolean,
    onBindError: (Exception) -> Unit,
    onProvider: (ProcessCameraProvider) -> Unit,
    onBound: (ImageCapture, Camera, ProcessCameraProvider) -> Unit
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        // Main-thread listener, same thread as the pane's onDispose: if the pane is
        // gone, it already ran — binding now would orphan the camera (BUG-022).
        if (!isPaneActive()) return@addListener
        val provider = try {
            future.get()
        } catch (e: Exception) {
            Log.e("CameraScreen", "Camera provider unavailable", e)
            onBindError(e)
            return@addListener
        }
        onProvider(provider)
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
            // BUG-014: bind all three use cases to the preview's viewport, so capture
            // and analysis report the same crop the user sees (FILL_CENTER on a tall
            // screen shows only the middle of the 4:3 sensor image). Before layout the
            // viewport is null — fall back to the full frame rather than not binding.
            val viewPort = previewView.viewPort
            val cam = if (viewPort != null) {
                val group = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(capture)
                    .addUseCase(analysis)
                    .build()
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, group)
            } else {
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, capture, analysis)
            }
            onBound(capture, cam, provider)
        } catch (e: Exception) {
            // e.g. no front camera, or the camera is held by another app.
            Log.e("CameraScreen", "Bind failed", e)
            onBindError(e)
        }
    }, ContextCompat.getMainExecutor(context))
}

/**
 * [left]..[bottom] (a capture's cropRect, px) as fractions of a [width]×[height]
 * image — or null when it covers the whole frame or is unusable, so validation just
 * uses the full image (BUG-014). Pure: JVM-tested.
 */
internal fun normalizedCropOf(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): NormalizedCrop? {
    if (width <= 0 || height <= 0) return null
    val l = left.coerceIn(0, width); val t = top.coerceIn(0, height)
    val r = right.coerceIn(0, width); val b = bottom.coerceIn(0, height)
    if (r - l <= 0 || b - t <= 0) return null
    if (l == 0 && t == 0 && r == width && b == height) return null
    return NormalizedCrop(
        l.toFloat() / width, t.toFloat() / height, r.toFloat() / width, b.toFloat() / height
    )
}

/** User-facing text for a failed camera bind (BUG-016). */
private fun cameraErrorMessage(context: Context, e: Exception): String = when (e) {
    is IllegalArgumentException -> context.getString(R.string.camera_error_no_lens)
    else -> context.getString(R.string.camera_error_bind)
}

@Composable
private fun CameraPermissionDenied(
    permanentlyDenied: Boolean,
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
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
                stringResource(R.string.camera_permission_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(
                    if (permanentlyDenied) R.string.camera_permission_denied_body
                    else R.string.camera_permission_body
                ),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )

            Spacer(Modifier.height(36.dp))

            val openSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                )
            }
            if (permanentlyDenied) {
                // The system won't show a dialog any more — Settings is the only way.
                Button(
                    onClick = openSettings,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_open_settings), fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.camera_grant_access), fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = openSettings,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_open_settings), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                }
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
    val targetLabel = colorDisplayName(targetColorName)

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
                        stringResource(
                            if (targetLeads) R.string.camera_fail_more_needed else R.string.camera_fail_not_dominant,
                            targetLabel
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    // Target vs actual — show the user exactly what the camera saw
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ResultSwatch(color = targetSwatch, label = targetLabel, sub = stringResource(R.string.camera_swatch_target))
                        Spacer(Modifier.width(28.dp))
                        ResultSwatch(
                            color = actualSwatch,
                            label = colorDisplayName(state.actualDominant),
                            sub = stringResource(R.string.camera_swatch_dominated)
                        )
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
                        stringResource(R.string.camera_fail_share, pct, targetLabel, needPct) + " " +
                            stringResource(
                                if (targetLeads) R.string.camera_fail_get_closer else R.string.camera_fail_main_subject
                            ),
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
                            stringResource(
                                R.string.camera_fail_tip, nearPct,
                                colorDisplayName(state.nearestColorName), targetLabel
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_try_again), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }

                else -> {
                    val (title, subtitle) = when (state) {
                        is CaptureState.ImportWrongDay -> Pair(
                            stringResource(R.string.camera_import_wrong_day),
                            stringResource(
                                R.string.camera_import_wrong_day_body,
                                localizedDateFormat("MMMd").format(Date(state.dateTaken))
                            )
                        )
                        CaptureState.ImportNoDate -> Pair(
                            stringResource(R.string.camera_import_no_date),
                            stringResource(R.string.camera_import_no_date_body)
                        )
                        CaptureState.ImportDuplicate -> Pair(
                            stringResource(R.string.camera_import_duplicate),
                            stringResource(R.string.camera_import_duplicate_body)
                        )
                        CaptureState.ImportTampered -> Pair(
                            stringResource(R.string.camera_import_tampered),
                            stringResource(R.string.camera_import_tampered_body)
                        )
                        // BUG-049: say what actually went wrong and what to do.
                        CaptureState.CaptureFailed -> Pair(
                            stringResource(R.string.camera_capture_failed),
                            stringResource(R.string.camera_capture_failed_body)
                        )
                        CaptureState.StorageFull -> Pair(
                            stringResource(R.string.camera_storage_full),
                            stringResource(R.string.camera_storage_full_body)
                        )
                        CaptureState.ImportUnreadable -> Pair(
                            stringResource(R.string.camera_import_unreadable),
                            stringResource(R.string.camera_import_unreadable_body)
                        )
                        else -> Pair(
                            stringResource(R.string.camera_save_failed),
                            stringResource(R.string.camera_save_failed_body)
                        )
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
                        Text(stringResource(R.string.action_try_again), color = Color.White, style = MaterialTheme.typography.labelLarge)
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

