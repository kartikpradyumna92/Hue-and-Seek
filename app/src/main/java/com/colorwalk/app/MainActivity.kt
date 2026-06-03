package com.colorwalk.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.colorwalk.app.notification.AlarmScheduler
import com.colorwalk.app.notification.NotificationHelper
import com.colorwalk.app.notification.NotificationPrefs
import com.colorwalk.app.ui.camera.CameraScreen
import com.colorwalk.app.ui.gallery.GalleryScreen
import com.colorwalk.app.ui.home.HomeScreen
import com.colorwalk.app.ui.onboarding.OnboardingScreen
import com.colorwalk.app.ui.permission.PermissionRationaleScreen
import com.colorwalk.app.ui.settings.SettingsScreen
import com.colorwalk.app.ui.theme.ColorWalkTheme
import com.colorwalk.app.ui.theme.ThemeMode
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var _themeMode by mutableStateOf(ThemeMode.SYSTEM)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.createChannel(this)
        _themeMode = NotificationPrefs.getThemeMode(this)
        if (NotificationPrefs.isEnabled(this)) AlarmScheduler.scheduleDaily(this)

        setContent {
            ColorWalkTheme(themeMode = _themeMode) {
                AppNavigation(
                    onThemeChange = { mode ->
                        NotificationPrefs.setThemeMode(this, mode)
                        _themeMode = mode
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun AppNavigation(onThemeChange: (ThemeMode) -> Unit) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val hasSeenOnboarding = remember { prefs.getBoolean("onboarding_seen", false) }
    val hasRequestedPermissions = remember { prefs.getBoolean("permissions_requested", false) }

    // Flow: onboarding → permissions → home
    // Returning users skip whichever steps are already done.
    val startDestination = when {
        !hasSeenOnboarding      -> "onboarding"
        !hasRequestedPermissions -> "permissions"
        else                    -> "home"
    }

    NavHost(navController, startDestination = startDestination) {

        composable("onboarding") {
            OnboardingScreen(
                onFinish = {
                    prefs.edit().putBoolean("onboarding_seen", true).apply()
                    // Always go to permissions next — the screen handles the case
                    // where they're already granted and skips itself automatically.
                    navController.navigate("permissions") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        composable("permissions") {
            val permissions = rememberMultiplePermissionsState(
                buildList {
                    add(Manifest.permission.CAMERA)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )

            val goHome = {
                prefs.edit().putBoolean("permissions_requested", true).apply()
                navController.navigate("home") {
                    popUpTo("permissions") { inclusive = true }
                }
            }

            // Skip rationale if permissions were granted by the OS already
            // (e.g. re-install on a device where grants survived).
            LaunchedEffect(permissions.allPermissionsGranted) {
                if (permissions.allPermissionsGranted) goHome()
            }

            PermissionRationaleScreen(
                onContinue = {
                    permissions.launchMultiplePermissionRequest()
                    goHome()
                }
            )
        }

        composable("home") {
            HomeScreen(
                onOpenCamera  = { navController.navigate("camera") },
                onOpenGallery = { navController.navigate("gallery") },
                onOpenSettings = { navController.navigate("settings") }
            )
        }

        composable("camera") {
            CameraScreen(onBack = { navController.popBackStack() })
        }

        composable("gallery") {
            GalleryScreen(onBack = { navController.popBackStack() })
        }

        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onThemeChange = onThemeChange
            )
        }
    }
}
