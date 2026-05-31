# Changelog

All notable changes to Hue & Seek are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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
