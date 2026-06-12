# Changelog

All notable changes to Hue & Seek are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [1.15.0] - 2026-06-12

Reliability release: reminder alarms, backup/restore, and capture responsiveness.

### Fixed
- **Play-policy risk on exact alarms** — the manifest declared `USE_EXACT_ALARM` (restricted by Google Play to alarm-clock/calendar apps, a review-rejection risk) with an invalid `minSdkVersion` attribute. Now declares the user-revocable `SCHEDULE_EXACT_ALARM` instead; the existing inexact-window fallback covers revocation. Side effect: on Android 12 (API 31–32), where neither permission was previously declared, reminders can now fire exactly on time.
- **Reminders died after an app update or clock/timezone change** — the boot receiver only handled device restarts. It now also re-arms the alarms on `MY_PACKAGE_REPLACED` (updates cancel all alarms), `TIME_SET`, and `TIMEZONE_CHANGED` (RTC alarms keep stale wall-clock times after those).
- **Backup could restore a broken database** — two problems: phone-to-phone transfers restored the database but not the private photo files it points at, and backing up the SQLite WAL sidecar files alongside the `.db` could restore an inconsistent snapshot. Device-to-device transfer now includes the photos directory (no size quota applies there), and the database runs in TRUNCATE journal mode so there is only ever one consistent file to back up. Cloud backup intentionally stays database+prefs only — photos would blow the 25 MB quota and fail the entire backup; cloud restores recover photos from the system gallery as before.
- **"Color Match!" card waited on the network** — after a capture or import, the success card was blocked while the place name was reverse-geocoded (seconds on a slow connection). The photo is now saved and the card shown immediately; the place name resolves in the background and the By Place gallery updates when it lands. Geocoding also uses the non-deprecated async API on Android 13+.

### Changed
- **versionCode** bumped from 10 → 11; **versionName** from 1.14.0 → 1.15.0.

---

## [1.14.0] - 2026-06-12

Major UI overhaul ("Bold & playful" direction) plus a fix for captures saving without GPS. No backend, database, or validation logic changed except the location fix.

### Added
- **Design system** — full Material 3 color schemes for dark and light themes (violet primary, teal secondary), a shared typography scale, shape scale (8–28 dp), and spacing tokens. All screens now draw from the same tokens instead of ad-hoc values.
- **Shared UI components** — unified photo grid tile, screen header (back + title + subtitle), empty states, confirm-delete dialog, and a common Coil image-request builder used across every screen.
- **Home: daily completion ring** — the hero ring now fills completely the moment today's photo is captured (and animates doing it), replacing the milestone-progress ring that could sit half-empty for weeks. Milestone countdown ("N days to 🔥 X") moved to a caption. The week history strip is tappable and opens Stats.
- **Gallery: By Color folder cards** — each color folder now shows its latest photo as the cover, tinted to the color, with a photo-count badge, sorted by photo count.
- **Gallery: swipe right on By Color goes Home** — a horizontal-swipe gesture on the first tab navigates back to Home (raw pointer observation, so the pager keeps working normally between tabs).
- **Stats: photo calendar** — calendar day cells now render the day's photo thumbnail ringed in that day's color, with a count badge for multi-photo days; added a milestone progress card and a "Share streak" button (shares the latest photo + streak via the system share sheet).
- **Camera: clearer result card** — bottom-anchored result with a target-vs-actual color swatch comparison and a progress bar showing how close the photo came to the 15% dominance threshold; shutter button shows the day's color in its center.
- **Settings: themed About card** — rainbow-gradient title across all eight walk colors, color-dot row, and a version pill (reads the real version at runtime).

### Fixed
- **Captures saved without GPS even with location permission granted** — the app only read the system's passive location cache (`lastLocation`), which is empty unless some other app recently requested a fix, and silently saved the photo with no coordinates. Captures now actively request a fix (`getCurrentLocation`, balanced power) concurrently with color validation, bounded at 5 seconds, falling back to the cache; a capture is never blocked or delayed by GPS. Pre-existing untagged photos have no recoverable coordinates — manual tagging remains the path for those.
- **Gallery tab indicator lagged behind swipes** — the indicator was driven by the view-mode state that only updates after a page settles; it now follows the pager's live scroll position.
- **Color album header** — the hex code now appears once under the color name in the header instead of being stamped on every photo tile.

### Changed
- **versionCode** bumped from 9 → 10; **versionName** from 1.13.0 → 1.14.0.

---

## [1.13.0] - 2026-06-11

Bug-fix release from a full-codebase review: all 5 critical bugs plus 7 high-priority bugs fixed, color validation rewritten.

### Changed
- **Color validation rewritten — the daily color must now truly pop.** The validator is a deterministic HSV-band histogram: every pixel maps to exactly one color (full 0–360° hue coverage, no first-match ordering bias), neutral pixels (too dark/gray) count toward nothing, and pixels in the center of the frame weigh ×2. A photo passes only when the target color both beats every other walk color **and** covers ≥15% of the weighted frame — a mostly-gray scene with a sliver of the day's color no longer slips through (captures and imports alike). Brown is now recognized as dark/muted orange, washed-out light reds count as Pink, and cyan sky finally counts as Blue. Failure cards are more specific: "Green dominated (4% was Red)" vs "More Red Needed — fills only 9% of the frame".
- Removed the `androidx.palette` dependency — the displayed dominant hex now comes from the validator's own winning bucket (the average shade actually photographed).
- **versionCode** bumped from 8 → 9; **versionName** from 1.12.0 → 1.13.0.

### Fixed
- **Streak could never exceed 60 days** — the streak query was capped at 60 photo *rows* (fewer days when a day has several photos), making the 100–365-day milestones unreachable. Streak, home history strip, and reminder notifications now compute from all photo dates.
- **Deleted photos resurrected after reinstall** — after a reinstall the app can't delete MediaStore copies owned by the previous install, so gallery sync re-imported every deleted photo. Deletions are now recorded as tombstones (filename + timestamp, included in Auto Backup) that sync consults before re-importing; re-capturing or re-importing a photo clears its tombstone.
- **Photo permission auto-denied on Android 8–12** — `READ_EXTERNAL_STORAGE` was requested at runtime but never declared in the manifest, so the dialog never appeared and reinstall recovery / location backfill silently failed on API 26–32.
- **Location backfill found no GPS on Android 10+** — MediaStore redacts GPS EXIF unless the URI is opened via `setRequireOriginal()` with `ACCESS_MEDIA_LOCATION` actually granted. Both are now done; the permission is requested silently at startup for existing users (it has no dialog).
- **Gallery copy invisible on Android 8–9** — the pre-Android-10 publish path never invoked the media scanner, so photos never appeared in the system gallery and were invisible to sync recovery. Now scans the file, writes EXIF capture date (for a correct `DATE_TAKEN`), and requests `WRITE_EXTERNAL_STORAGE` on those API levels.
- **Brown days were nearly impossible to pass** — brown pixels were classified as Red/Orange by the old first-match ordering (see validator rewrite above).
- **Settings gear invisible in light theme** — was hardcoded white on a near-white background.
- **Day indexing could merge/skip days around DST** — day indices now use `java.time` local dates (`LocalDate.toEpochDay`) instead of dividing local-midnight millis by UTC day length; the Stats calendar no longer re-implements its own copy of the math. Verified with DST spring-forward/fall-back and UTC+13 regression tests.
- **Re-importing the same photo created duplicate gallery entries** — imports are now rejected up front with an "Already Imported" card; import filenames also carry a millisecond suffix so two photos taken in the same second can't overwrite each other.
- **Sync could duplicate photos when `DATE_TAKEN` lost millisecond precision** — the filename fallback parser now restores the millis suffix, and the dedup guard also matches second-truncated timestamps.
- **Manually tagging a photo's location could erase its real GPS fix** — tagging now keeps the photo's own coordinates whenever it has a usable fix and only inherits from a same-named photo when it has none.

### Tests
- 157 JVM unit tests (was 127): real classifier/dominance tests for the rewritten validator (the old core was untestable off-device), >60-day and multi-photo-per-day streak regressions, DST/timezone day-index regressions, import-duplicate mapping.
- New instrumented tests: deletion tombstones, location-tagging coordinate rules, uncapped day indices.

---

## [1.12.0] - 2026-06-08

### Added
- **Gallery By Place** — new view mode that clusters photos by GPS location into place cards (city/region name or coordinate bucket). Tapping a card opens a filtered album for that place.
- **Manual location tagging** — "Needs Location" card appears in By Place whenever photos lack a valid location. Tapping opens a tagging screen where each photo can be assigned a location name (free-text or quick-pick from existing places). Card and screen auto-disappear once all photos are tagged.
- **Swipe right to go back** — swiping right anywhere on a color album or place album screen navigates back to the folder grid, mirroring standard Android back-gesture behaviour.

### Fixed
- **GPS EXIF not read for imported photos** — added `ACCESS_MEDIA_LOCATION` to the manifest; Android 10+ silently redacts GPS coordinates from MediaStore files without it, causing location backfill to always miss.
- **"Near 0.0°N, 0.0°E" shown for new photos** — `FusedLocationProvider` can return a `Location(0, 0)` for a bad GPS fix; this was stored and displayed as a real place. Now filtered as invalid in both the coordinate label and the backfill query.
- **Empty album when tapping coordinate-label place card** — `photosForPlace` only matched photos by `locationName`; photos with no name but valid GPS (shown as "Near X°N, Y°E") never appeared in their album. Fixed by also matching on the computed coordinate label.

### Changed
- **versionCode** bumped from 7 → 8; **versionName** from 1.11.1 → 1.12.0.

---

## [1.11.1] - 2026-06-08

### Fixed
- **Blank photo tiles after reinstall** — after a fresh reinstall where Android Auto Backup restores the Room database but wipes `filesDir/photos/`, `syncGalleryWithDatabase()` Pass 2 was building `existingFilenames` from all DB rows regardless of whether the file existed on disk. This caused MediaStore entries to be skipped even when their private file was missing, so every photo tile loaded as a blank placeholder. Fixed by partitioning DB rows into existing-on-disk vs. missing-from-disk; missing rows are now recovered from MediaStore via `copyMediaUriToPrivateStorage()` and the existing DB row is updated with `dao.updateFilePath()` instead of inserting a duplicate.

---

## [1.11.0] - 2026-06-08

### Added
- **Splash screen** — Android 12+ SplashScreen API via `core-splashscreen` compat library. Shows a white camera icon on a black background during cold start; transitions automatically to the app theme. Works on all API levels via the compat library.
- **Gallery search (By Color)** — search bar at the top of the Color tab filters color folders by name in real time (case-insensitive). Clear button appears when the field is non-empty. Empty state updates contextually ("No colors matching 'X'").
- **Gallery date filter (By Date)** — filter chips (All / This Month / Last 3 Months) on the Date tab narrow photos by capture date. Empty state reflects the active filter ("No photos in this period").
- **Color album sort order** — Newest / Oldest sort chips inside each color album. Sort is applied in the ViewModel; changing it does not re-query Room.
- **Adaptive launcher icon** — `ic_launcher_foreground.xml` (white camera + 4 colour palette dots, safe-zone-aware on 108dp canvas) and `ic_launcher_background.xml` (deep indigo `#3949AB`). `mipmap-anydpi-v26/` entries pick up the adaptive icon on Android 8+; PNG mipmaps remain as fallbacks for older devices.

### Changed
- **versionCode** bumped from 4 → 6; **versionName** from 1.9.0 → 1.11.0 (1.10.0 was previously released).

---

## [1.10.0] - 2026-06-07

### Added
- **Privacy policy** — hosted at `https://kartikpradyumna92.github.io/Hue-and-Seek/` via GitHub Pages (`docs/index.html`). Covers camera, location, photo library, and notifications; confirms no server uploads or third-party sharing.

### Fixed
- **Gallery shows every photo twice** — `syncGalleryWithDatabase()` deduped MediaStore entries by millisecond-exact timestamp. Some Android OEMs return `DATE_TAKEN` in seconds, causing the filename-fallback parser to produce a second-aligned timestamp that didn't match the DB value, so the same photo was re-inserted on every sync. Added a filename-based dedup guard (`existingFilenames` set built from the current DB snapshot) and a Pass 0 SQL dedup (`DELETE … WHERE id NOT IN (SELECT MIN(id) … GROUP BY filePath)`) that cleans existing duplicate rows on first launch.
- **Deleted photos reappear after next launch** — `deletePhoto` checked `Uri.parse(filePath).scheme == "file"` to detect private files, but bare absolute paths (the common case since v1.8.0) have a null scheme, so the private file was never actually deleted. The orphaned private file and MediaStore entry caused Pass 2 to re-insert the photo on the next sync. Fixed by branching on `path.startsWith("/")` for absolute paths, deleting via `File.delete()`, and calling a new `deleteFromMediaStore(filename)` helper to remove the MediaStore copy.

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
