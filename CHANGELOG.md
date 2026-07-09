# Changelog

All notable changes to Hue & Seek are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [1.26.0] - 2026-07-08

Audit-driven hardening release: all five High-severity findings (plus two performance findings) from a full code/security review fixed. Minor version bump because the daily color rotation shifts once (see below).

### Fixed
- **Camera stayed on after leaving the Camera pane** — CameraX use cases are bound to the activity lifecycle, which stays RESUMED for the whole session; nothing unbound them when the pane was swiped away, so the camera hardware, preview pipeline, and live analyzer kept running (green privacy indicator lit, battery draining) while the user browsed Home/Gallery/Walks/Settings. The camera provider is now unbound the moment the pane leaves composition, before the analysis executor is shut down.
- **Infinite spinner if the captured JPEG failed to decode** — the shutter callback silently returned while the state was still Processing, leaving an unusable camera. Decode failure now logs and surfaces the storage-error card with Try Again.
- **Out-of-memory risk at the shutter on high-megapixel devices** — the capture path decoded the full-sensor JPEG unbounded and allocated a second full-size bitmap for rotation (hundreds of MB peak on 50MP+ sensors). It now uses the same bounded two-pass decode as the import path (dimensions first, then `inSampleSize` capping at 4096px).
- **Stale color of the day when the app stayed open across midnight** — the target color only refreshed on activity resume, and the swipe hub keeps one activity resumed all session, so captures after midnight validated against yesterday's color. New DST-safe `millisUntilNextLocalMidnight()` helper (unit-tested); the Camera pane refreshes its target on entry and re-arms at each midnight; Home reloads when its minute ticker crosses into a new local day — color, theme, streak, and "captured today" all roll over without a restart.
- **Color rotation skipped a color every non-leap New Year** — the day index (`YEAR*366 + DAY_OF_YEAR`) advanced by 2 across Dec 31 → Jan 1. Now uses the continuous local-zone epoch day, with leap- and non-leap-year boundary regression tests. **Note:** this shifts which color falls on which date once, at update time; existing photos keep their stored color.

### Performance
- **Hub no longer recomposes every frame during swipes** — the peek-strip fade is read inside the draw phase (`graphicsLayer` lambda) and the confetti visibility gate is `derivedStateOf`, so drag offsets never trigger composition-phase invalidation.
- **Home no longer fully reloads on every DB write** — the refresh trigger reacts only when the photo count or newest timestamp changes; note saves and background geocode updates skip the streak recompute, while captures and deletes still refresh (and still fire confetti).

### Tests
- 242 unit tests green. New: two `millisUntilNextLocalMidnight` boundary tests, two year-boundary `colorForDay` regression tests; three `ColorForDayTest` cases rewritten against the epoch-day formula.

---

## [1.25.2] - 2026-07-07

Restores the confetti celebration after a successful capture.

### Fixed
- **Confetti no longer appeared after a validated photo** — the celebration fires in `HomeViewModel.load()` when "captured today" flips, but the only post-startup trigger was a lifecycle observer that relied on navigation rebuilding the Home screen. Since the swipe-hub redesign Home stays permanently composed, so returning from Camera fired no event and `load()` never re-ran — this also left the streak counter and progress ring stale until the next app restart. Home now observes the photo database directly: Room re-emits on every insert, so a saved capture refreshes Home and fires the confetti no matter how the user navigates back.
- **Celebration resilience** — emissions during startup gallery sync still never celebrate (reinstall recovery stays silent), and follow-up DB writes right after a capture (async location resolution, note save) no longer wipe an in-flight celebration mid-confetti. Once-per-day dedupe and milestone variants (7/21/30… days) unchanged.

### Tests
- New `celebration_dbEmissionAfterCapture_triggersCelebration` regression test (capture emission fires confetti; follow-up emission doesn't cut it short); `celebration_fromSyncLoad_doesNotTriggerCelebration` reworked to isolate the fromSync guard now that loads preserve an active celebration. 239 tests total.

---

## [1.25.1] - 2026-07-06

Two bug fixes for the Your Walks journal.

### Fixed
- **Crash when opening a photo from Your Walks** — the full-frame viewer's open morph uses an underdamped spring that briefly overshoots 1.0, and that raw progress value was passed as the scrim's color alpha; Compose throws for alpha > 1, so every photo tap crashed the app mid-animation ("Hue & Seek keeps stopping"). The morph progress is now clamped to [0, 1] before anything reads it.
- **Your Walks opened on the second photo after a new capture** — the keyed LazyColumn anchors scroll to the first visible item's *key*, so when a just-captured photo was prepended the list stayed pinned to the previous newest photo and the fresh one sat above the viewport. The list now snaps to the top when a new photo arrives while the user is at (or within one card of) the top; a scroll position deep in history is left untouched.

### Docs
- README rewritten to match the current app: two-path color validation (center-weighted dominance + subject saliency), swipe-hub navigation diagram, Your Walks / Stats / Settings / onboarding sections, live color meter, EXIF import integrity checks, configurable morning/evening reminders, updated project structure and build instructions.

---

## [1.25.0] - 2026-07-04

Elastic swipe navigation overhaul, live color meter, saliency-aware color matching, EXIF forgery protection, dynamic chromatic theming, gamified swipeable onboarding, and performance/battery hardening.

### Added
- **Live color meter in Camera** — a real-time meter shows how much of the frame matches today's color before you shoot. Powered by a new `LiveColorAnalyzer` (`ImageAnalysis` at 320×240, `KEEP_ONLY_LATEST`) that downsamples each YUV frame onto a fixed 64×48 grid with zero steady-state allocation, converts via fixed-point BT.601 (`YuvMath`), and streams shares through a conflated channel with a producer-side 0.5% change gate.
- **Subject-saliency color matching (Path 2)** — photos where the target color is a clear *subject* (concentrated around the center or a rule-of-thirds focal point) now validate even when it isn't globally dominant. Five focal probes, subject-share and global-floor thresholds, plus an OKLAB perceptual tight-match bonus (ΔE ≤ 0.12 → ×1.5) so near-exact hues count more.
- **OKLAB color space (`OkLab`)** — perceptual color difference (Ottosson constants) used by the saliency bonus; robust in low light where HSV buckets get noisy.
- **EXIF forgery protection for imports (`ExifIntegrity`)** — imported photos are checked via magic-byte content sniffing, original-vs-digitized timestamp consistency (2 min), file-mtime-before-capture (1 h), and future-timestamp (10 min) rules; tampered files are rejected with a dedicated `ImportTampered` result card.
- **Golden-ratio composition grid in Camera** — subtle guide lines drawn with `drawWithCache` (no per-frame state reads).
- **Dynamic chromatic theme** — the app's Material primary family is derived from today's walk color and cross-fades over 800 ms at midnight/day change (`ChromaticTheme`, `DayPalette`). All accent-surface text picks black/white via WCAG relative-luminance contrast (`Wcag`, ≥4.5:1).
- **Gamified onboarding** — new interactive "catch the color" mini-game page with spring physics; onboarding pages are now swipeable with the exact same physics as the rest of the app, with the Next button preserved.
- **Camera swipe with black-hold** — swipe right from Home opens the Camera; the viewfinder stays black until the pane is fully settled, eliminating preview tearing during the transition.
- **Container morph for Newsfeed photos** — tapping a photo morphs it from its card bounds to full screen (crop→fit crossfade), and reverses on every dismissal path.
- **CI workflow** (`.github/workflows/android-ci.yml`), Play Store asset pipeline (`docs/store-assets/`), `keystore.properties.template`, and checked-in Gradle wrapper for reproducible builds.

### Changed
- **Swipe navigation rebuilt on hand-rolled elastic physics (`HomeHubScreen` + `SwipePhysics`)** — replaced Pager-based navigation with a clamped one-page-per-gesture drag model: a gesture can never cross more than one screen, commits happen at 30% travel or a ≥1600 px/s flick (with ≥8% minimum travel), velocity is EMA-smoothed to suppress single-frame spikes, and settles inherit gesture velocity into springs (firm commit spring, elastic snap-back spring).
- **Pinch-to-zoom smoothing** — camera zoom now interpolates exponentially in log space per frame (`withFrameNanos`), so pinch and zoom-chip changes glide instead of stepping.
- **Onboarding swipe re-architected** — pages live at fixed absolute positions on a continuous clipped strip (offset never reset), fixing the "half-shown page" glitch; per-gesture one-page clamp, same commit/return springs as the hub.
- **Home clock now lifecycle-aware** — ticks only while `RESUMED` (`repeatOnLifecycle`), stopping background battery drain.
- **Newsfeed peek strip fades during the up-swipe** to Your Walks and reverses on the way back.

### Fixed
- **Live meter stuck at 0%** — chroma-plane reads could land one byte past `ByteBuffer.limit` (a known YUV_420_888 quirk); since the sample grid hits the same index every frame, every frame threw and was silently skipped. Reads are now clamped to each plane's limit, and analyzer failures log once instead of never.
- **Onboarding "half shown" page** — the old implementation re-keyed content on page index and reset the offset in a separate step; any frame between the two writes rendered the new page a full width off-screen.
- **Camera preview tearing during swipe** — `SurfaceView` ignores transforms; switched `PreviewView` to `COMPATIBLE` mode and added the black-hold so the live feed only renders when the camera pane is fully at rest.
- **Multi-page drag-through** — one long drag could previously sail across two screens (e.g. Your Walks → past Home → Settings); the per-gesture ±1-viewport clamp makes that structurally impossible.
- **Velocity estimator oversensitivity** — a single spiky frame could trigger a flick commit; EMA gain retuned (0.85/0.15).

### Performance
- Zero-steady-state-allocation analyzer loop and allocation-free `ColorValidator.liveTargetShare` (manual indexed lookup, preallocated HSV scratch).
- Golden grid and swipe rendering avoid per-frame recomposition (draw-phase-only reads, `graphicsLayer` offsets).

### Tests
- 238 unit tests green. New suites: `ColorValidatorTest` subject-saliency + low-light OKLAB cases (45 total), `OkLabTest` (9), `ExifIntegrityTest` (14), `WcagTest` (8), `SwipePhysicsTest` (11, incl. spike-suppression and transition-boundary cases), `YuvMathTest` (5).

---

## [1.24.0] - 2026-06-25

Newsfeed journal, photo notes, swipe navigation overhaul, gallery viewer improvements, and permission timing fix.

### Added
- **Newsfeed journal** — swipe up from Home (or tap the peek strip) opens a LazyColumn journal view showing all walk photos newest-first. Each card displays the color name + hex code, date, reverse-geocoded location, a square photo, and an inline note. Swipe right anywhere to return to Home.
- **Newsfeed peek strip** — three most-recent photo thumbnails peek at the bottom of the Home screen as a "Your walks" affordance, making the newsfeed discoverable without reading accessibility text.
- **Photo notes / descriptions** — add a freeform note to any photo from the newsfeed *or* from the gallery full-frame viewer. Notes persist in the Room DB (`description` column, schema v2) and are simultaneously written to the JPEG EXIF `ImageDescription` tag so they survive in Google Photos and any export.
- **Post-capture note prompt** — after a photo validates as a color match, a note prompt appears before returning to Home. Users can type a note and tap Save, or skip. The prompt auto-focuses the keyboard and uses the same EXIF write path as the newsfeed editor.
- **Gallery full-frame viewer: inline note editing** — Line 4 of the metadata card shows the note when present (tap to edit) or an "Add a note…" placeholder (tap to add). Edits are reflected immediately in the viewer without leaving the screen; Room re-emits the change to all other views automatically.
- **Gallery full-frame viewer: swipe between photos** — replaced `detectTransformGestures` (which consumed all touch events) with a custom `awaitEachGesture` handler. Single-finger swipes at `scale == 1×` now pass through to `HorizontalPager` so left/right swipe navigates between photos. Pinch-to-zoom and single-finger pan while zoomed still work.
- **Gallery metadata card redesign** — fixed 4-line layout: (1) color name + dominant hex on the same row, (2) date, (3) location reserved space, (4) note with "Show more / Show less" for long captions.
- **4-direction swipe gestures on Home** — swipe right → camera, left → gallery, up → newsfeed, down → settings. Dominant axis is determined by comparing absolute X/Y delta so diagonal swipes don't mis-fire.
- **Back gestures** — swipe right on Newsfeed returns to Home; swipe up on Settings returns to Home; swipe left on Camera returns to Home.

### Fixed
- **Streak message wrong at 21–29 days** — "Two weeks strong" was shown all the way through day 29. Added a `streak >= 21` branch that shows the actual day count and a nudge toward the 30-day mark.
- **Google Photos not reflecting notes added after backup** — writing EXIF only to the private file didn't update the MediaStore copy Google Photos had already indexed. Fix: set `IS_PENDING = 1`, open the MediaStore URI as a file descriptor, write EXIF, then clear `IS_PENDING = 0`.
- **Permission dialog appearing before onboarding** — `ACCESS_MEDIA_LOCATION` was requested in `Activity.onCreate`, firing before Compose rendered the first frame. Moved into a `LaunchedEffect(Unit)` inside the `"home"` route so it only fires after the user completes onboarding and the "Before we begin" permissions screen.
- **Note not reflected immediately in gallery viewer** — `_viewerState` held a snapshot of the photo list; saving a note updated Room but the viewer never saw the new `description`. Fixed by optimistically patching `_viewerState.photos` after `repo.saveDescription()` returns, using the same trim/blank→null logic the repository applies.

### Schema
- **DB version bumped 1 → 2** — `ALTER TABLE photos ADD COLUMN description TEXT`; existing rows get `NULL` (shows "Add a note…" placeholder).

### Code quality
- Removed dead `CaptureState.Success` sealed class variant and its unreachable `ResultCard` branch — success now always transitions to `AwaitingNote`.
- Removed duplicate `import androidx.compose.ui.draw.clip` in `HomeScreen.kt`.
- Replaced fully-qualified `BorderStroke`, `LocalFocusManager`, `TextOverflow`, `ClickableText`, `buildAnnotatedString`, and `SpanStyle` references with proper imports across `CameraScreen` and `PhotoViewerScreen`.
- Replaced deprecated `ClickableText` (Material3) with `Text + Modifier.clickable` in the gallery viewer's "Show more / Show less" control.

### Tests
- **`CameraViewModelTest`** — updated all `SaveResult.Success` calls to include `photoId`; replaced `CaptureState.Success` assertions with `AwaitingNote`; added tests for `saveNoteForPhoto` (saves description + calls `onDone`, handles missing photo ID gracefully); added import/wrong-day/no-date ViewModel tests.
- **`NewsfeedViewModelTest`** (new) — 6 tests covering photos flow, `saveDescription` delegation, and swipe-right threshold direction logic.
- **`PhotoDaoTest`** — 5 new tests for `updateDescription`: sets value, clears with null, overwrites, isolates to correct row, new inserts default to null.
- **`PhotoRepositoryIntegrationTest`** — 4 new tests for `saveDescription` DB layer: persists text, stores null for blank/null input, overwrites previous note.

---

## [1.23.0] - 2026-06-21

Photo viewer pan + share, notification permission banner, coordinate bucketing fix, and D-section code quality pass.

### Added
- **Photo viewer: pan while zoomed** — after pinching in, the image can now be dragged to inspect any part of the frame. Pan is clamped to the image bounds so the image never slides off-screen. The pager's swipe-between-photos gesture is disabled while zoomed in, preventing accidental page flips mid-pan; it re-enables the moment scale returns to 1×.
- **Photo viewer: share button** — a share icon appears in the top-right toolbar alongside the existing rotate/delete/location buttons. Tapping it sends the photo via `ACTION_SEND` using `FileProvider` so any receiving app can read the file.
- **Settings: notification-blocked banner** — when the OS-level notification permission has been revoked, a `errorContainer` card appears above the notification toggles explaining that reminders are silenced and offering a one-tap "Open" button that deep-links directly to the app's system notification settings page. The check fires on every `RESUMED` lifecycle event so the banner disappears immediately after the user enables notifications without leaving the screen.

### Fixed
- **"By Place" clustering broke for photos in the Southern/Western hemispheres** — `coordinateLabel()` applied `Math.floor` to the absolute value of latitude/longitude before re-attaching the N/S/E/W suffix. For negative coordinates (S or W) this placed photos from two different 11 km cells into the same bucket and photos at the cell boundary into the wrong one. The fix floors the signed value, then formats the absolute value with the direction suffix. Added `Locale.US` to the `String.format` call to prevent comma decimal separators (e.g. German locale "33,8") from corrupting map keys.
- **Notification icon rendered as a colored silhouette on Android 5+** — `android.R.drawable.ic_menu_camera` is an unstable platform drawable with color fills; the system notification bar requires a fully monochrome (alpha-channel-only) icon and tints it automatically. The old icon appeared as a solid white square on many devices. Replaced with a purpose-built monochrome camera vector at `res/drawable/ic_stat_notification.xml`.
- **`backup_rules.xml` and `data_extraction_rules.xml` used `domain="cache"`** — `cache` is not a valid backup domain (valid values: `file`, `database`, `sharedpref`, `external`, `root`, `device_*`). The invalid rules caused 4 fatal lint errors that blocked `assembleRelease`. The `<exclude domain="cache">` lines were redundant because cache directories are excluded from backup by default; both lines removed.

### Code quality (D-section)
- **Removed unused Gradle dependencies** — `androidx.datastore:datastore-preferences` (Jetpack DataStore API never used; app uses `SharedPreferences` throughout) and `androidx.camera:camera-extensions` (no `CameraExtensionsSelector` import anywhere in the codebase). Replaced `camera-extensions` slot with `androidx.exifinterface:exifinterface:1.3.7` (see below).
- **Removed dead DAO and repository methods** — `PhotoDao.countByColor`, `PhotoDao.getPhotoIdForDay`, `PhotoDao.getFavouriteColor`, and `PhotoRepository.getFavouriteColor` had zero callers; deleted.
- **Removed unused SharedPreferences write** — `MainActivity` wrote `"permissions_requested" = true` in the `goHome` lambda but no code ever read that key; write removed.
- **Consolidated duplicate hex-parse and image-request helpers** — `StatsScreen.parseStatsHexColor` and `GalleryScreen.parseHexColor` were both identical one-liners duplicating `parseAccentHex` in `PhotoImage.kt`. All callers now import `parseAccentHex` directly. The two inline `filePath.startsWith("/")` `ImageRequest.Builder` blocks in `PhotoViewerScreen` and `StatsScreen` were replaced with the existing `photoImageRequest()` helper from `PhotoImage.kt`.
- **Extracted `StreakCalculator.todayMidnightMs()`** — the four-line `Calendar` boilerplate for computing local midnight was copy-pasted in `PhotoRepository.hasCapturedToday` and `StreakReminderReceiver`. Extracted to a single public helper; both callers updated.
- **Switched to `androidx.exifinterface`** — `android.media.ExifInterface` (deprecated; mishandles several EXIF orientation tags) replaced with `androidx.exifinterface.media.ExifInterface` in `PhotoRepository` and `CameraScreen`.
- **Enabled R8 shrinking for release builds** — `isMinifyEnabled = true`, `isShrinkResources = true`, and `proguardFiles()` now active in the `release` build type. Added `proguard-rules.pro` with keeps for Room entities/DAOs (reflection-instantiated), `@HiltViewModel` constructors, Kotlin coroutines dispatcher factories, and `*Annotation*`/`Signature` attributes required by Hilt/KSP.
- **versionCode** bumped from 18 → 19; **versionName** from 1.22.0 → 1.23.0.

### Tests
- 168 JVM unit tests, 0 failures (net −5 from v1.22.0: removed the three `StreakCalculatorTest` helpers that depended on the deleted `getPhotoIdForDay` path; existing streak and DST tests unaffected).

---

## [1.22.0] - 2026-06-21

Test coverage: F-section gaps closed, backfill crash fix on missing permissions.

### Fixed
- **`backfillLocationData` could crash with `SecurityException` when `READ_MEDIA_IMAGES` was denied** — the MediaStore query was not wrapped in a try-catch, unlike the identical query in `syncGalleryWithDatabase` Pass 2. A SecurityException from a denied permission would propagate uncaught and crash the Gallery. The query is now wrapped in `try/catch` and returns `null` on failure, making the backfill a graceful no-op when media permissions are missing (F5 / A3-A4 interaction).

### Tests
- **F2 — import-dedup (B5) DAO coverage**: Three new `PhotoDaoTest` cases for `countByDateTaken`: exact-millis match returns 1, off-by-one millis returns 0, two adjacent timestamps count independently. The `AlreadyImported` ViewModel path was already covered by `CameraViewModelTest`.
- **F5 — `backfillLocationData` coverage**: Five new instrumented tests in `PhotoRepositoryIntegrationTest`: no-photos noop, real-GPS photos not queued, missing-GPS photos marked attempted, second call exits early (C1 idempotency), and delete+reinsert triggers a fresh attempt (C1 round-trip).
- **versionCode** bumped from 17 → 18; **versionName** from 1.21.0 → 1.22.0.

---

## [1.21.0] - 2026-06-21

Performance: import OOM protection, lossless photo rotation, stale stats, and sync transaction batching.

### Fixed
- **Importing a high-megapixel photo could cause OOM** — `decodeBitmapFromUri()` decoded the full source image with no downsampling. A 50 MP photo from a modern camera produces a 200–400 MB raw `ARGB_8888` bitmap, exceeding the heap limit on many devices and causing an OOM crash during import. A two-pass `BitmapFactory` approach now reads dimensions first (`inJustDecodeBounds`), computes the largest power-of-2 `inSampleSize` that keeps the decode within 4096×4096 px, then decodes at that reduced size — sufficient for both color classification and private-storage saves.
- **Photo rotation re-encoded JPEG pixels on every tap, accumulating quality loss** — `rotatePhoto()` decoded the full JPEG to a raw `Bitmap`, applied a 90° rotation matrix, and re-compressed at quality 95. Repeated rotations degraded sharpness and consumed 20–40 MB of heap per tap. Rotation now updates the EXIF orientation tag in place via `ExifInterface.saveAttributes()` with no pixel decode or re-encode. Coil 2.x applies the EXIF orientation automatically when loading, so the displayed image is always correct.
- **Bitmap from camera capture and gallery import was never recycled** — the caller-provided `Bitmap` passed to `savePhoto()` and the one decoded inside `importPhoto()` were compressed (to private storage and/or MediaStore) but never recycled. On rapid captures or imports on low-RAM devices the unreleased native memory could accumulate until GC ran, increasing OOM risk. `bitmap.recycle()` is now called after every code path (validation failure, storage error, and success) in both functions.
- **`backfillLocationData` re-scanned all ColorWalk MediaStore EXIF on every Gallery open** — if any photo permanently lacked GPS coordinates (common for photo-picker imports and pre-fix captures), `missing` was never empty, so the full MediaStore query and per-file EXIF stream-open ran on every `GalleryViewModel` init. Per-photo "attempted" markers are now persisted in `app_prefs` SharedPreferences. After a photo's GPS backfill is attempted (whether coordinates were found or not), subsequent Gallery opens skip it entirely. The MediaStore scan only fires when there are genuinely un-attempted missing photos.

### Changed
- **`syncGalleryWithDatabase` and `backfillLocationData` now batch DB writes in single transactions** — individual `dao.insert()` / `dao.updateFilePath()` / `dao.updateLocation()` calls inside loops each committed a separate transaction, causing many small journal flushes. All writes in each pass are now collected (with file I/O still running outside the transaction) and applied in a single `db.withTransaction {}` block, reducing commit overhead at the cost of no behavioral change.
- **versionCode** bumped from 16 → 17; **versionName** from 1.20.0 → 1.21.0.

### Tests
- 173 JVM unit tests — no new tests added (C-section fixes are in `PhotoRepository` which requires Android instrumented tests for full coverage; C1/C2/C3/C4 are covered by the existing integration test suite and device testing).

---

## [1.20.0] - 2026-06-21

Camera reliability, import tolerance, and zoom accuracy fixes.

### Fixed
- **Camera capture error gave no user feedback** — when the camera HAL returned an error (out of storage, hardware failure), `onError()` only logged to Logcat and the shutter button silently re-appeared with no indication anything went wrong. The shutter now transitions immediately to a Processing state on tap (so the spinner shows while the camera capture is in flight), and any hardware error sets the same "Error — Something went wrong / Try Again" result card as a storage failure.
- **Importing a travel-day photo showed "Wrong Day"** — `isToday()` used strict calendar-day equality in the current device timezone, so a photo taken at 11 pm in the departure timezone (still "today" where you were) could map to "yesterday" in the arrival timezone and be rejected. Some OEM devices also store `DATE_TAKEN` as local-naive milliseconds without a UTC offset, shifting the timestamp by the full UTC offset (up to ±14 h). A ±4-hour grace window around local midnight now accepts both cases while still blocking clearly wrong-day imports (photos from two days ago, etc.).
- **Zoom chip highlighted the wrong level when the camera couldn't reach the requested ratio** — tapping "20×" on a device whose camera tops out at 8× highlighted the 20× chip while displaying 8× footage. Similarly ".5×" stayed highlighted on devices without an ultra-wide lens where the minimum zoom is 1×. The chip list is now filtered to only show levels the active camera physically supports (`minZoomRatio`–`maxZoomRatio` from `cameraInfo.zoomState`). The applied zoom is read back after clamping and written to the highlighted-chip state so the UI always mirrors the hardware.

### Changed
- **versionCode** bumped from 15 → 16; **versionName** from 1.19.0 → 1.20.0.

### Tests
- 173 JVM unit tests (was 170): `startCapture_setsProcessingState`, `onCaptureError_setsStorageErrorState`, `onCaptureError_fromProcessing_setsStorageError`.

---

## [1.19.0] - 2026-06-21

Photo viewer stability fix and live stats.

### Fixed
- **Photo viewer went black after deleting the last-page photo** — when viewing a photo that was not the first in the pager (e.g. page 4 of 5) and deleting it, the pager's internal page position stayed at the old out-of-bounds index for one recomposition frame before settling. During that frame, `photos.getOrNull(currentPage)` returned null and the composable returned early, hiding the Close button, zoom controls, and metadata panel entirely. On slower devices the black screen could persist. The index is now clamped to `lastIndex` immediately so the previous photo is shown without interruption, and a `LaunchedEffect` snaps the pager to the correct page once the list updates.
- **Stats screen showed stale data after capturing a photo** — `StatsViewModel` loaded all stats once on construction via a one-shot `getAllPhotosSnapshot()` call and never re-queried. Navigating to the camera, capturing a photo, and returning to Stats still showed the streak, photo count, and active-days count from before the capture — undermining trust in the core gamification loop. The ViewModel now subscribes to a live `getAllPhotos()` Room Flow via `collectLatest`; stats recompute automatically whenever a photo is added or deleted.

### Changed
- **versionCode** bumped from 14 → 15; **versionName** from 1.18.0 → 1.19.0.

### Tests
- All 170 JVM unit tests updated: `StatsViewModelTest` mocks replaced from `coEvery { getAllPhotosSnapshot() }` to `every { getAllPhotos() } returns flowOf(...)` to match the live-Flow implementation.

---

## [1.18.0] - 2026-06-21

Color classifier accuracy overhaul — four systemic misclassification bugs fixed.

### Fixed
- **Warm-lit near-neutral surfaces polluted non-Orange days** — gray walls, rugs, and tiles under incandescent light develop a slight warm tint (s≈0.19–0.21) that slipped past the old saturation gate (0.18). On a Green or Blue day in an otherwise neutral indoor room, 10–20% of frame pixels could land in the Orange/Brown bucket and trigger "Orange Dominated" even when no orange object was in frame. Raised `MIN_SATURATION` to 0.22; genuinely pastel colors (baby blue s≈0.30, sage green s≈0.35) are unaffected.
- **Earth-tone oranges (sienna, muted burnt-orange) classified as Orange instead of Brown** — the old boundary (v≤0.55 or s≤0.45) missed medium-dark muted oranges like sienna (s≈0.72, v≈0.63, chroma≈0.45). On a Brown day, shooting a sienna wall or wooden door returned "Orange Dominated" instead of passing. Replaced the two-threshold condition with a chroma-based gate: any orange-hue pixel with `saturation × value ≤ 0.50` or `value ≤ 0.65` now goes to Brown. Bright vibrant oranges (chroma ≥ 0.80) correctly remain Orange.
- **Medium-brightness washed-out reds classified as Red instead of Pink** — the old Pink detection at the Red hue (345–15°) required both low saturation (s<0.40) AND high brightness (v≥0.75). Dusty rose, blush, and faded-rose tones at v=0.55–0.74 went to Red instead of Pink, making Pink days harder to pass with the most common "pinkish" real-world objects. Lowered the brightness gate to v≥0.55. Dark muted reds (maroon, burgundy, s>0.40 or v<0.55) are unaffected.
- **Brick and terracotta classified as Red instead of Brown** — brick (h≈9°, chroma≈0.50) and terracotta (h≈10°, chroma≈0.59) both fall within the Red hue band (345–15°). The old code sent every pixel in that band to Red or Pink, making Brown-day photos of brick walls, terracotta pots, and adobe floors always fail. The Red branch now checks first: if `h≥5°` (offset toward orange, away from pure red) **and** chroma is in the 0.35–0.60 range (above pale-pink territory, below vivid-red territory), the pixel is Brown. Pure reds cluster at h<5° and are unaffected; pale pinks have chroma<0.35 and fall through to the existing Pink check.

### Changed
- **versionCode** bumped from 12 → 14; **versionName** from 1.16.0 → 1.18.0.

### Tests
- 170 JVM unit tests (was 162): `classify_warmTintedGray_isNeutral`, `classify_sienna_isBrown_notOrange`, `classify_brick_isBrown_notRed`, `classify_terracotta_isBrown_notRed`, `classify_dustyRose_isPink_notRed_andNotBrown`, `validatePixels_terracottaSubject_passesOnBrownDay`. Updated `LIGHT_PINK` test pixel from rgb(244,194,194) to rgb(255,185,185).

---

## [1.16.0] - 2026-06-21

Color validation accuracy fix.

### Fixed
- **Gray / neutral scenes falsely reported a color as "Dominant"** — under warm indoor lighting, a tiny fraction (2–4%) of gray pixels pick up a slight warm tint and slip past the saturation gate, just enough for one color bucket to "win" with near-zero real pixels. The result card then showed e.g. "Red Dominated" on a scene that had no visible red at all (screenshot: gray rug). A color must now hold ≥5% of the center-weighted frame to be named as dominant; below that threshold the card correctly says "Neutral tones".
- **Failure card gave no hint when an adjacent color was found** — on a Red day, pointing at a vivid fuchsia/pink sign correctly failed (the sign is Pink, not Red), but the card only said "1% Red — Blue dominated" with no clue that 12%+ Pink was present. The failure card now shows a tip line: "Tip: N% Pink was found — try something more purely Red." This applies to any color mismatch where a non-target color is dominant.

### Changed
- **versionCode** bumped from 11 → 12; **versionName** from 1.15.0 → 1.16.0.

### Tests
- 162 JVM unit tests (was 159): new tests for the MIN_DOMINANT_SHARE gate (neutral vs. named dominant at the threshold boundary) and for the nearest-color hint field.

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
