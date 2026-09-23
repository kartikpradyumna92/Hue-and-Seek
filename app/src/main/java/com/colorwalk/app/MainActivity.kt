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
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.unit.IntOffset
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.colorwalk.app.notification.AlarmScheduler
import com.colorwalk.app.notification.NotificationHelper
import com.colorwalk.app.notification.NotificationPrefs
import com.colorwalk.app.ui.home.HomeHubScreen
import com.colorwalk.app.ui.onboarding.OnboardingScreen
import com.colorwalk.app.ui.permission.PermissionRationaleScreen
import com.colorwalk.app.ui.stats.StatsScreen
import com.colorwalk.app.ui.theme.ColorWalkTheme
import com.colorwalk.app.ui.theme.ThemeMode
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint

// Stats slides over the hub on a physical spring — damping just under critical for a
// hint of life at the end of travel, no scripted duration.
private val NAV_SLIDE_SPRING = spring(
    dampingRatio = 0.85f,
    stiffness = 380f,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

private val LOCATION_PERMISSIONS = listOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

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
    // BUG-035: someone who already answered the permission dialog (and declined) got
    // the rationale screen again on EVERY launch — and with a permanent denial its
    // Continue does nothing. Once asked, go Home; the Camera pane owns recovery
    // (Grant Access / Open Settings).
    val cameraAskedBefore = remember {
        prefs.getBoolean(com.colorwalk.app.ui.camera.KEY_CAMERA_PERMISSION_REQUESTED, false)
    }

    val startDestination = when {
        !hasSeenOnboarding                  -> "onboarding"
        !cameraGranted && !cameraAskedBefore -> "permissions"
        else                                -> "home"
    }

    NavHost(navController, startDestination = startDestination) {

        composable("onboarding") {
            OnboardingScreen(
                onFinish = {
                    prefs.edit().putBoolean("onboarding_seen", true).apply()
                    navController.navigate("permissions") {
                        popUpTo("onboarding") { inclusive = true }
                        // BUG-034: a double tap on "Get Started" stacked two permission
                        // screens, so Back from Home led to the rationale again.
                        launchSingleTop = true
                    }
                }
            )
        }

        composable("permissions") {
            val permissionsToRequest = remember {
                buildList {
                    add(Manifest.permission.CAMERA)
                    // Android 12+ silently ignores a FINE-only request from apps
                    // targeting S+; COARSE must accompany it (the user may still pick
                    // "approximate", which getFreshLocation handles).
                    addAll(LOCATION_PERMISSIONS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.READ_MEDIA_IMAGES)
                        // Requested together, per the Android 14 partial-access model
                        // (the manifest declares it — see BUG-027).
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        }
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
                    launchSingleTop = true   // BUG-034: auto-skip + tap can both fire
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
                onContinue = {
                    // This request already includes COARSE, so the home route's one-time
                    // location re-ask must not fire right after a deliberate denial here.
                    prefs.edit()
                        .putBoolean("location_requested_v2", true)
                        // Lets the Camera pane tell "never asked" from "permanently
                        // denied" (BUG-049).
                        .putBoolean(com.colorwalk.app.ui.camera.KEY_CAMERA_PERMISSION_REQUESTED, true)
                        .apply()
                    launcher.launch(permissionsToRequest.toTypedArray())
                }
            )
        }

        composable("home") {
            // A4: ACCESS_MEDIA_LOCATION is silently granted alongside photo access but
            // must be explicitly requested. Deferred here so it never fires before the
            // onboarding or "Before we begin" permission rationale screen is shown.
            //
            // Location: installs that onboarded on Android 12+ before the COARSE fix
            // were never shown a location dialog at all (FINE-only was ignored), so
            // ask them once here. Both requests go in ONE call — a second concurrent
            // requestPermissions is dropped by the platform.
            val activity = context as? android.app.Activity
            LaunchedEffect(Unit) {
                if (activity == null) return@LaunchedEffect
                fun granted(p: String) =
                    ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
                val toRequest = mutableListOf<String>()
                // L-9: ask at most once — this LaunchedEffect re-fires on every return
                // to the home route, and a hard-denied request would repeat forever.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !prefs.getBoolean("aml_requested", false) &&
                    !granted(Manifest.permission.ACCESS_MEDIA_LOCATION)
                ) {
                    prefs.edit().putBoolean("aml_requested", true).apply()
                    toRequest += Manifest.permission.ACCESS_MEDIA_LOCATION
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !prefs.getBoolean("location_requested_v2", false) &&
                    LOCATION_PERMISSIONS.none(::granted)
                ) {
                    prefs.edit().putBoolean("location_requested_v2", true).apply()
                    toRequest += LOCATION_PERMISSIONS
                }
                if (toRequest.isNotEmpty()) {
                    ActivityCompat.requestPermissions(activity, toRequest.toTypedArray(), 0)
                }
            }

            // Camera, Gallery, Settings, and Newsfeed are all swipe-neighbors of Home,
            // hosted inside this single route — see HomeHubScreen for why none of this
            // uses NavHost destinations.
            HomeHubScreen(
                // BUG-034: a double tap on the streak card pushed Stats twice (Back x2).
                onOpenStats    = { navController.navigate("stats") { launchSingleTop = true } },
                onThemeChange  = onThemeChange
            )
        }

        composable(
            "stats",
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, NAV_SLIDE_SPRING) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, NAV_SLIDE_SPRING) }
        ) {
            StatsScreen(onBack = { navController.popBackStack() })
        }
    }
}
