# Changelog

All notable changes to Hue & Seek are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [1.9.0] - 2026-06-07

### Added
- **Swipe navigation** — swipe right from Home to open Camera, swipe left to open Gallery. Inside Gallery, swipe left (By Color) to switch to By Date, swipe right (By Date) to switch back to By Color, swipe right (By Color) to return to Home. Creates a linear navigation chain: Home ↔ By Color ↔ By Date.
- **About section in Settings** — shows a one-liner description of the app and the current version number. Appears at the top of the Settings screen.
- **Per-slot notification controls** — each daily reminder (Morning and Evening) can be individually toggled on/off and given its own custom time via an inline time picker. The master toggle still controls all reminders at once.
- **GPS location in Google Photos** — photos captured in the app now embed GPS EXIF tags in the system gallery copy, so Google Photos shows the location automatically.

### Changed
- **Daily reminders changed from one to two** — reminders now fire at 10:00 AM and 5:00 PM (configurable) instead of a single user-set time. Each slot fires only if the day's color walk has not yet been completed.

### Fixed
- **Portrait photos saved as landscape** — `proxy.imageInfo.rotationDegrees` is based on the static `targetRotation` value set at camera build time, which never updates as the device rotates. Switched to reading the EXIF orientation tag written directly into the JPEG bytes by the camera HAL at capture time; `rotationDegrees` is used only as a fallback when EXIF is absent.
- **Imported photos displayed without rotation correction** — `decodeBitmapFromUri` decoded the bitmap but never read or applied EXIF orientation. It now opens the stream a second time to read EXIF and applies the same rotation logic used for in-app captures.
- **Multiple photos on the same day not recovered after reinstall** — `syncGalleryWithDatabase()` used a day-index set to deduplicate MediaStore entries. After recovering the first photo for a given day it added that day to the set, causing all remaining photos from that day to be skipped. The dedup guard is now a millisecond-timestamp set so each distinct photo is independently recoverable.

---

## [1.8.0] - 2026-06-03

### Fixed
- **Gallery photos showing as pink/blank tiles** — Coil was given a raw `file://` URI string, which is parsed differently across OEM Android variants (symlink path mismatch between `/data/data/` and `/data/user/0/`). Photos are now stored as bare absolute paths and loaded via a typed `File` object; legacy `content://` entries are handled via `Uri.parse()`. Photos now display reliably on all devices.
- **Streak and photos lost on reinstall** — the permissions screen called `goHome()` immediately after launching the OS permission dialogs, so `syncGalleryWithDatabase()` ran before `READ_MEDIA_IMAGES` was granted. The MediaStore query silently returned nothing, meaning no photos were recovered and the streak appeared as zero. Navigation to home now fires only after every permission dialog is answered, so the gallery sync always runs with the correct permissions and fully recovers streak data.
- **Permissions screen skipped on update installs** — the `permissions_requested` flag in SharedPreferences survived update installs (e.g. Android Studio deploy), so returning users who had never granted camera access were sent straight to the home screen. Start destination now checks the actual OS camera grant status, not the flag.
- **Streak reminder notification missed in Doze mode** — `setWindow()` is deferred by Android's Doze battery optimisation, causing the 10 AM reminder to fire hours late or not at all. Changed to `setExactAndAllowWhileIdle()` with the `USE_EXACT_ALARM` permission (API 33+, auto-granted) so the alarm fires precisely at the scheduled time even when the device is idle.
- **Settings screen icons invisible in light mode** — `Column` with `.background()` modifier does not propagate `LocalContentColor` to children, so icons inherited the wrong color. Wrapped in `Surface` which sets both background color and content color correctly.
- **Silent data loss on database schema change** — `fallbackToDestructiveMigration()` was wiping the entire Room database (streak + all photo metadata) whenever the schema changed without a migration. Removed; the app now crashes loudly instead of silently destroying user data. Schema changes require an explicit `Migration` object going forward. `exportSchema = true` also enabled so schema diffs are tracked in version control.
- **Camera rebound on every zoom tap** — `AndroidView`'s `update` block captured `activeZoom`, triggering a full `unbindAll()` + camera rebind on every zoom change. Wrapped in `key(cameraSelector)` so the camera only rebinds when the user flips front/back; zoom is applied exclusively via `LaunchedEffect(activeZoom)` on the already-bound camera.

### Added
- **105 unit tests** — full JVM test suite covering streak calculation (17 tests), color-of-day determinism (9 tests), color validation, HomeViewModel (12 tests), GalleryViewModel (14 tests), CameraViewModel (9 tests), and NotificationPrefs (16 tests). Android stubs return defaults in JVM tests via `unitTests.isReturnDefaultValues = true`. Instrumented Room and repository integration tests also added.
- **Gradle wrapper** — `gradlew` and `gradle-wrapper.jar` added to the repository so tests and builds can be run from the command line without relying on Android Studio.

---

## [1.7.0] - 2026-06-02

### Added
- **Light theme support** — Settings screen now offers Dark, Light, and System (default) theme options. All screens updated to use Material3 color scheme; warm gray background (#F2F2F2) and white cards in light mode, unchanged dark mode.
- **Settings screen** — accessible via the gear icon on the home screen. Contains a theme selector and notification controls (toggle on/off, custom reminder time) previously scattered across the app.
- **Streak milestone celebrations** — confetti fires on every daily capture (light burst from top). Milestone streaks (7, 21, 30, 50, 100, 150, 180, 200, 240, 300, 365 days) trigger a full multi-cannon confetti display plus an animated overlay card with emoji and personalised message. Fires once per calendar day; reinstall gallery recovery never triggers confetti.
- **Permission rationale screen** — shown once after onboarding before any OS permission dialogs. Each permission (Camera, Location, Notifications, Photo Library) gets a card with an icon, Required/Optional badge, and a plain-English explanation of exactly how it's used. Shown after onboarding so users understand the app before being asked for access.
- **Value-first permission flow** — onboarding runs before permission requests. Research-backed ordering: users who have seen the app's value grant permissions at a higher rate.
- **POST_NOTIFICATIONS runtime permission** — on Android 13+ the app now explicitly requests notification permission. Previously missing, which caused silent notification failures on modern devices.
- **App preferences backed up** — notification time, theme choice, and onboarding state are now included in Android Auto Backup and device-transfer rules so settings survive reinstall.

### Changed
- **Default notification time** changed from 12:00 PM to 10:00 AM.
- **build.gradle.kts** — versionName and versionCode now reflect the actual release (were stuck at "1.0" / 1 since launch).

### Fixed
- **Duplicate Room database instances** — `AppModule` was calling `Room.databaseBuilder().build()` independently, creating a separate DB connection from the one used by the widget and `StreakReminderReceiver`. All paths now share a single instance via `AppDatabase.getInstance()`.
- **Celebration confetti re-triggered after dismissal** — `HomeViewModel.load()` preserved stale celebration state across coroutine boundaries; the `else` branch now returns `null` and relies on the `lastCelebDay` SharedPreferences guard.
- **No Room database migration strategy** — added `.fallbackToDestructiveMigration()` as a safety net so schema changes cause a clean rebuild instead of a crash.
- **StreakReminderReceiver used orphaned CoroutineScope** — replaced `CoroutineScope(Dispatchers.IO)` with `GlobalScope.launch(Dispatchers.IO)`, the documented correct pattern for `BroadcastReceiver.goAsync()`.
- **Camera preview flickered on every zoom tap** — the `AndroidView` `update` block captured `activeZoom`, triggering `unbindAll()` and a full camera rebind on every zoom change. Zoom now applied via `LaunchedEffect(activeZoom)` directly on the bound camera without rebinding.
- **`ThemeMode.valueOf()` crash on corrupted preference** — wrapped in `try/catch` with `SYSTEM` fallback.
- **`BitmapFactory.decodeByteArray` null crash** — platform type can return null on decode failure; added early return before passing to `onPhotoCaptured`.
- **`openPhoto()` showed wrong photo when id not found** — `.coerceAtLeast(0)` silently opened the first photo; replaced with an `if (idx >= 0)` guard that no-ops instead.

---

## [1.6.0] - 2026-06-02

### Added
- **Onboarding** — first-time users see a 3-slide intro explaining the daily color concept, photo validation, and streak mechanics. Shown only once; Skip and Get Started both work correctly.
- **Custom notification time** — tap the gear icon on the home screen to pick your daily reminder time. Previously hardcoded to noon; now remembers your preference across launches and device reboots.
- **14-day color history strip** — a row of colored dots below the streak card shows the last 14 days at a glance. Filled = photo captured, dim = missed. Today is highlighted with a ring, and the strip updates automatically at midnight without needing to restart the app.
- **Home screen widget** — add Hue & Seek to your home screen to see today's color and current streak at a glance without opening the app. Updates daily.
- **In-app review prompt** — after your first 7-day streak the app asks for a Play Store rating. Shown once, tastefully timed, and silently skipped in debug/sideloaded builds.
- **Camera permission denied screen** — if camera access is denied, the camera screen now shows a clear explanation with "Grant Access" and "Open Settings" buttons instead of silently breaking.

### Fixed
- **`hasCapturedToday` accepted future-timestamped photos** — a photo saved with a wrong device clock could set capturedToday=true for the rest of that day. Query now bounded to `[today midnight, tomorrow midnight)`.
- **Rapid screen transitions could show stale streak** — `load()` launched a fire-and-forget coroutine on every call; a slower earlier call could overwrite a fresher result. Fixed with per-call job cancellation.
- **14-day history strip showed wrong day past midnight** — `todayIndex` was frozen at first composition; now keyed to the live clock so the strip updates correctly when the day rolls over.

---

## [1.5.0] - 2026-06-01

### Added
- **Streak and gallery survive app reinstalls** — when the app is reinstalled (e.g. for an update), all photos previously saved to the device gallery are automatically recovered on first launch. The database is rebuilt from the `Pictures/ColorWalk` folder in the background, restoring the full streak count and every gallery entry without any action required from the user.

### Fixed
- **Photos saved by the in-app camera had no `DATE_TAKEN` in MediaStore** — the save path never explicitly set this field, so photos captured via the camera screen showed a timestamp of 0 in MediaStore. The gallery sync now falls back to the date encoded in the filename (`ColorWalk_yyyyMMdd_HHmmss.jpg`) when `DATE_TAKEN` is missing, ensuring all photos — past and future — are recoverable. Going forward, `DATE_TAKEN` is also set explicitly on every save.
- **Gallery sync blocked streak display on cold start** — the sync previously ran before the initial `load()`, showing streak=0 until the MediaStore scan completed. The fix loads existing DB data immediately so the UI is responsive from the first frame, then syncs in the background and refreshes.

---

## [1.4.1] - 2026-05-30

### Fixed
- **"Still time before midnight" shown after goal already met** — `hasCapturedToday()` used SQLite's `DATE(..., 'unixepoch')` which operates in UTC. In non-UTC timezones, a photo's UTC date could differ from the local date, causing the check to always return false. Replaced with a direct millisecond timestamp comparison (`dateTaken >= midnightMs`), which is timezone-safe. Also fixed the same bug in `StreakReminderReceiver` so notifications correctly suppress when the goal is met.

---

## [1.4.0] - 2026-05-30

### Changed
- **Color validation logic** — replaced fixed 60% pixel threshold with dominant color check: photo passes if the target color has a higher pixel count than any other color in the frame. Encourages wider, more natural framing rather than filling the lens with one color.
- Failure message now clearly identifies which color actually dominated (e.g. "Blue dominated (22% was Green). Make Green the main subject.")

### Fixed
- Home screen streak message showed "go capture" nudge even after successfully completing today's walk — caused by `capturedToday` state not refreshing on returning from the camera. Fixed by reloading state on every `Lifecycle.ON_RESUME` event.

---

## [1.3.0] - 2026-05-30

### Added
- Daily noon notification if today's color walk is not yet complete
- Notification is time-aware, streak-aware, and includes the color of the day
- Notification reschedules automatically every day and survives device reboots
- Richer streak messages on home screen — congratulatory messages scaled to streak length when complete, time-aware nudges (morning / afternoon / evening / night) when not done
- Streak card background tints with the day's color when walk is complete

### Fixed
- **Critical:** Color of day mismatch between Home screen and Camera — caused by UTC vs local timezone difference at day boundary (e.g. 5 PM Pacific = midnight UTC). Now uses device local calendar day consistently across all screens.

---

## [1.2.0] - 2026-05-30

### Added
- Streak persists across app reinstalls via Android Auto Backup to Google Drive
- Backup covers Room database, photo metadata, and DataStore preferences
- Supports both Android 11 (fullBackupContent) and Android 12+ (dataExtractionRules)

---

## [1.1.0] - 2026-05-30

### Added
- **App name:** Hue & Seek
- **App icon:** Custom flame icon with orange-to-purple gradient on dark maroon background, inspired by the app's streak and color identity
- "Hue & Seek" title on home screen, styled with a horizontal gradient in the day's color
- Hex color code (e.g. `#43A047`) displayed under each color folder name in gallery

### Changed
- App previously named Huego; renamed to Hue & Seek

---

## [1.0.2] - 2026-05-30

### Added
- Tap any photo in gallery (color album or date view) to open full-screen photo viewer
- Full-screen viewer supports pinch-to-zoom and discrete zoom buttons (1× 2× 3× 5×)
- Swipe left / right to navigate between photos in the viewer
- Page counter (e.g. `2 / 7`) shown in top center of viewer
- Zoom resets to 1× automatically when swiping to a new photo
- Delete button in full-screen viewer with confirmation dialog

### Fixed
- Swipe navigation was blocked by gesture interceptors competing with HorizontalPager — fixed by removing conflicting `detectTransformGestures` and using `Modifier.transformable()`, then simplified further to pure pager with no gesture interceptors
- Zoomed photo bled into adjacent pages — fixed with `clipToBounds()` on each pager page

---

## [1.0.1] - 2026-05-30

### Added
- Discrete zoom buttons in camera (.5× 1× 1.5× 2× 3× 5× 10× 15× 20×) replacing the previous pinch-to-zoom slider
- Zoom buttons scroll horizontally; active level highlighted in the day's color
- Front / back camera flip button
- Import photo from Google Photos / device gallery directly from the camera screen
- Imported photos validated against same two rules: must be taken **today** + color must pass the 60% check
- Clear error messages for wrong-day imports ("Photo taken on May 28 — must be today")
- Gallery view toggle: **By Color** (folder grid) and **By Date** (grouped by month-year, descending)
- Date view shows photo thumbnail, color label, date + time, and location per row
- Delete photo from gallery in both Color view and Date view with confirmation dialog
- Date, day of week, and live clock shown on home screen
- Hex color code shown under color name on home screen

### Changed
- Zoom slider removed; replaced with discrete zoom level buttons
- Color validation failure message now shows actual detected percentage (e.g. "47% detected — need 60%")

### Fixed
- Misleading "Dominant was Green" failure message when color was correct but percentage too low

---

## [1.0.0] - 2026-05-30

### Added
- **Color of the day** — deterministic daily color pick (8 colors: Red, Orange, Yellow, Green, Blue, Purple, Pink, Brown) consistent across all devices using local calendar day
- **Home screen** — displays color of the day, color name, hex code, and daily streak counter
- **Camera screen** — full in-app CameraX camera with color hint pill showing today's target color
- **Color validation** — pixel-level HSV analysis; photo must have ≥ 60% of pixels matching the target color's hue range to be accepted
- **Rejected photos are not saved** — photos that fail color validation are discarded entirely; only accepted photos enter the gallery and device storage
- **Daily streak** — counts consecutive days with at least one accepted photo; resets if a day is missed
- **Gallery — By Color** — color folder grid; tap a folder to see all photos for that color with date, location, and dominant hex chip
- **Gallery — By Date** — all photos in descending order grouped by month-year
- **Full-screen photo viewer** — tap any gallery photo to view full screen with metadata (color, date, time, location)
- **Location metadata** — GPS coordinates reverse-geocoded to a readable label (e.g. "Sunnyvale, California") and stored with each photo
- **Photos saved to device gallery** — accepted photos saved to `Pictures/ColorWalk/` folder, visible in Google Photos
- **Import from Google Photos** — import a photo taken today; same color validation applied
- Room database for photo metadata, Hilt for dependency injection, Jetpack Compose UI, CameraX for camera, DataStore for preferences
