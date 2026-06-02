package com.colorwalk.app

import android.Manifest
import android.content.Context
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
import com.colorwalk.app.ui.onboarding.OnboardingScreen
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
        AlarmScheduler.scheduleDaily(this)
        val hasSeenOnboarding = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getBoolean("onboarding_seen", false)

        setContent {
            ColorWalkTheme {
                PermissionGate {
                    AppNavigation(hasSeenOnboarding)
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
private fun AppNavigation(hasSeenOnboarding: Boolean) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    NavHost(navController, startDestination = if (hasSeenOnboarding) "home" else "onboarding") {
        composable("onboarding") {
            OnboardingScreen(
                onFinish = {
                    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("onboarding_seen", true).apply()
                    navController.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }
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
