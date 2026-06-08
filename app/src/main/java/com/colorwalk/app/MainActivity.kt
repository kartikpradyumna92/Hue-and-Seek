package com.colorwalk.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
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
import com.colorwalk.app.ui.stats.StatsScreen
import com.colorwalk.app.ui.theme.ColorWalkTheme
import com.colorwalk.app.ui.theme.ThemeMode
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var _themeMode by mutableStateOf(ThemeMode.SYSTEM)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.createChannel(this)
        _themeMode = NotificationPrefs.getThemeMode(this)
        if (NotificationPrefs.isEnabled(this)) AlarmScheduler.scheduleBoth(this)

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

@Composable
private fun AppNavigation(onThemeChange: (ThemeMode) -> Unit) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val hasSeenOnboarding = remember { prefs.getBoolean("onboarding_seen", false) }

    // Route to permissions if camera is not actually granted — catches update installs
    // where SharedPreferences survive but the user hasn't granted permissions yet.
    val cameraGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    val startDestination = when {
        !hasSeenOnboarding -> "onboarding"
        !cameraGranted     -> "permissions"
        else               -> "home"
    }

    NavHost(navController, startDestination = startDestination) {

        composable("onboarding") {
            OnboardingScreen(
                onFinish = {
                    prefs.edit().putBoolean("onboarding_seen", true).apply()
                    navController.navigate("permissions") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        composable("permissions") {
            val permissionsToRequest = remember {
                buildList {
                    add(Manifest.permission.CAMERA)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.READ_MEDIA_IMAGES)
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
            }

            val goHome = {
                prefs.edit().putBoolean("permissions_requested", true).apply()
                navController.navigate("home") {
                    popUpTo("permissions") { inclusive = true }
                }
            }

            // Callback fires only after ALL permission dialogs are answered (grant or deny).
            // This ensures syncGalleryWithDatabase() runs after READ_MEDIA_IMAGES is decided.
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { _ -> goHome() }

            // Auto-skip: if all permissions are already granted (e.g. returning user), go straight home.
            val allAlreadyGranted = remember {
                permissionsToRequest.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
            }
            LaunchedEffect(Unit) {
                if (allAlreadyGranted) goHome()
            }

            PermissionRationaleScreen(
                onContinue = { launcher.launch(permissionsToRequest.toTypedArray()) }
            )
        }

        composable("home") {
            HomeScreen(
                onOpenCamera   = { navController.navigate("camera") },
                onOpenGallery  = { navController.navigate("gallery") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenStats    = { navController.navigate("stats") }
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
                onBack        = { navController.popBackStack() },
                onThemeChange = onThemeChange
            )
        }

        composable("stats") {
            StatsScreen(onBack = { navController.popBackStack() })
        }
    }
}
