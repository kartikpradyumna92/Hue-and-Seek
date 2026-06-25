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
import androidx.core.app.ActivityCompat
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
import com.colorwalk.app.ui.newsfeed.NewsfeedScreen
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // No dialog of its own — granted silently alongside the photo
                        // permission, but MUST be requested or MediaStore redacts GPS
                        // EXIF and the location backfill finds nothing (A4).
                        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    } else {
                        // API 26-28: writing the gallery copy to Pictures/ColorWalk is a
                        // direct file write and needs the storage WRITE permission (A5).
                        // Same "Storage" dialog group as READ — no extra prompt.
                        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
            }

            val goHome = {
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
            // A4: ACCESS_MEDIA_LOCATION is silently granted alongside photo access but
            // must be explicitly requested. Deferred here so it never fires before the
            // onboarding or "Before we begin" permission rationale screen is shown.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val activity = context as? android.app.Activity
                LaunchedEffect(Unit) {
                    if (activity != null &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_MEDIA_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(
                            activity, arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), 0
                        )
                    }
                }
            }

            HomeScreen(
                onOpenCamera    = { navController.navigate("camera") },
                onOpenGallery   = { navController.navigate("gallery") },
                onOpenSettings  = { navController.navigate("settings") },
                onOpenStats     = { navController.navigate("stats") },
                onOpenNewsfeed  = { navController.navigate("newsfeed") }
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

        composable("newsfeed") {
            NewsfeedScreen(onBack = { navController.popBackStack() })
        }
    }
}
