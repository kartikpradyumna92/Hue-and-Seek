package com.colorwalk.app.ui.camera

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
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
    val targetColor = viewModel.targetColor

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var activeZoom by remember { mutableStateOf(1f) }  // actual ratio selected
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }

    val cameraSelector = if (useFrontCamera)
        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.onPhotoImported(uri) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // key(cameraSelector) destroys and recreates the AndroidView only when the user
        // flips between front/back — not on every recomposition (zoom taps, state changes, etc.).
        // No update block needed: factory handles the bind, LaunchedEffect handles zoom.
        key(cameraSelector) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { previewView ->
                        bindCamera(ctx, lifecycleOwner, previewView, cameraSelector) { cap, cam ->
                            imageCapture = cap; camera = cam
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Apply zoom without rebinding the camera.
        // Only LaunchedEffect triggers applyZoom — onClick just updates activeZoom.
        LaunchedEffect(activeZoom) {
            camera?.let { applyZoom(it, activeZoom) }
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
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                items(ZOOM_LEVELS) { level ->
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
                            onClick = { activeZoom = level.ratio },
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
                            imageCapture?.takePicture(
                                cameraExecutor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(proxy: ImageProxy) {
                                        val buffer = proxy.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        proxy.close()
                                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                            ?: return
                                        viewModel.onPhotoCaptured(bmp)
                                    }
                                    override fun onError(e: ImageCaptureException) {
                                        Log.e("CameraScreen", "Capture error", e)
                                    }
                                }
                            )
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(4.dp, targetColor.composeColor, CircleShape)
                    ) {}
                } else {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(56.dp))
                }

                // Flip
                IconButton(
                    onClick = {
                        useFrontCamera = !useFrontCamera
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

        // Result overlay
        AnimatedVisibility(
            visible = captureState !is CaptureState.Idle && captureState !is CaptureState.Processing,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
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

private fun applyZoom(camera: Camera?, ratio: Float) {
    if (camera == null) return
    val state = camera.cameraInfo.zoomState.value ?: return
    val clamped = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
    camera.cameraControl.setZoomRatio(clamped)
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
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
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
    val (icon, title, subtitle, accent) = when (state) {
        is CaptureState.Success -> Quad(
            Icons.Default.CheckCircle,
            "Color Match!",
            "Dominant: ${state.dominantHex}",
            Color(0xFF4CAF50)
        )
        is CaptureState.Failed -> Quad(
            Icons.Default.ErrorOutline,
            "$targetColorName Isn't Dominant",
            "${state.actualDominant} dominated (${(state.matchPercent * 100).toInt()}% was $targetColorName). Make $targetColorName the main subject.",
            Color(0xFFFF5722)
        )
        is CaptureState.ImportWrongDay -> Quad(
            Icons.Default.ErrorOutline,
            "Wrong Day",
            "Photo taken on ${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(state.dateTaken))} — must be today.",
            Color(0xFFFF9800)
        )
        CaptureState.ImportNoDate -> Quad(
            Icons.Default.ErrorOutline,
            "No Date Info",
            "This photo has no date metadata — can't verify it was taken today.",
            Color(0xFFFF9800)
        )
        else -> Quad(Icons.Default.ErrorOutline, "Error", "Something went wrong.", Color(0xFFFF5722))
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier.padding(32.dp)
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state is CaptureState.Success) {
                    Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                        Text("Done")
                    }
                }
                OutlinedButton(onClick = onDismiss) {
                    Text(if (state is CaptureState.Success) "Take Another" else "Try Again")
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
