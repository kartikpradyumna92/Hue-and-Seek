package com.colorwalk.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.colorwalk.app.notification.AlarmScheduler
import com.colorwalk.app.notification.NotificationHelper
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.colorwalk.app.ui.camera.CameraScreen
import com.colorwalk.app.ui.gallery.GalleryScreen
import com.colorwalk.app.ui.home.HomeScreen
import com.colorwalk.app.ui.theme.ColorWalkTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.createChannel(this)
        AlarmScheduler.scheduleDailyNoon(this)
        setContent {
            ColorWalkTheme {
                PermissionGate {
                    AppNavigation()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val permissions = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_MEDIA_IMAGES
        )
    )
    LaunchedEffect(Unit) { permissions.launchMultiplePermissionRequest() }
    content()
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenCamera = { navController.navigate("camera") },
                onOpenGallery = { navController.navigate("gallery") }
            )
        }
        composable("camera") {
            CameraScreen(onBack = { navController.popBackStack() })
        }
        composable("gallery") {
            GalleryScreen(onBack = { navController.popBackStack() })
        }
    }
}
