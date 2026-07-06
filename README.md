# Hue & Seek

A daily mindful photography app for Android. Every day the app picks one color — Red, Orange, Yellow, Green, Blue, Purple, Pink, or Brown — and challenges you to photograph the world through that color's lens. Photos are validated with perceptual color analysis, so a capture only counts when today's color genuinely stars in the frame. Build a daily streak, grow your color gallery, and train your eye to notice everyday details.

> A single color photo walk is an excellent way to cure creative block. Instead of photographing everything, you narrow your focus to one hue and suddenly the world looks completely different.

---

## How It Works

Each day the app deterministically picks one of eight colors based on the calendar date — every device sees the same color on the same day. You open the camera, find something that matches, and take a photo. The app validates the shot with pixel-level analysis (see [Color Validation](#color-validation)); a successful capture extends your daily streak and lands in your gallery and walk journal.

---

## Navigation

The app is a swipe hub centered on the Home screen, driven by custom elastic swipe physics (velocity-aware flicks, spring settles, strictly one screen per gesture):

```
                 ↑ Your Walks (newsfeed journal)
Gallery  ←    Home    →  Camera
                 ↓ Settings
```

Every transition tracks your finger 1:1 and either commits with a firm spring or snaps back elastically. Streaks & Stats opens from the Home streak counter.

---

## Features

### Home
- Today's color as a large animated circle with name and hex code
- Dynamic chromatic theme — the whole app's accent palette is derived from the day's color and cross-fades at midnight
- Live clock (lifecycle-aware — stops ticking in the background)
- Streak counter with contextual, time-aware messages
- "Your walks" peek strip: the three most recent photos peek from the bottom edge and fade as you swipe up into the journal

### Camera
- Live in-app viewfinder (CameraX) with golden-ratio composition grid
- **Live color meter** — a real-time bar shows how much of the current frame matches today's color *before* you shoot, powered by a zero-allocation YUV frame analyzer
- Smooth pinch-to-zoom (log-space exponential smoothing) plus zoom chips from .5× to 20×
- Front / back camera flip
- Import from the device gallery — validates the EXIF date (must be today), runs the same color check, and rejects files with forged or inconsistent metadata (content sniffing + timestamp integrity checks)
- Post-capture note prompt — add an optional caption before returning Home
- Result card with match percentage on success or failure

### Color Validation
- Downsampled pixel analysis in HSV with a Gaussian center weight — what's in the middle of your frame matters most
- Two ways to pass:
  1. **Dominance** — today's color is the strongest color family in the frame and covers at least ~15% of the center-weighted frame
  2. **Subject saliency** — the color is a clear *subject*: concentrated around the center or a rule-of-thirds focal point, with an OKLAB perceptual bonus for near-exact hue matches (robust in low light)
- Rejected photos are never saved; the failure card shows the exact share detected

### Your Walks (Newsfeed Journal)
- Swipe up from Home for a newest-first journal of every walk photo
- Each card shows the color name + hex, date, reverse-geocoded location, the photo, and an editable note
- Tapping a photo morphs it from its card into a full-screen viewer (and morphs back on dismiss)

### Gallery
- Three tabs with 1:1 draggable pager transitions: **By Color**, **By Date**, **By Place**
- By Place clusters photos into ~11 km coordinate cells with reverse-geocoded labels; photos without coordinates land in a "Needs location" album
- Full-screen viewer: swipe between photos, pinch-to-zoom with pan, rotate, share, delete, and inline note editing
- Notes persist in the database *and* are written to the JPEG's EXIF `ImageDescription`, so they survive export and Google Photos backup

### Streaks & Stats
- Consecutive-day streak with per-color capture counts
- Streak persists across reinstalls via Android Auto Backup
- Configurable morning and evening reminder notifications (with a Settings banner + deep link if the OS notification permission is revoked)

### Onboarding
- Swipeable intro pages using the same spring physics as the rest of the app, including an interactive "catch the color" mini-game

---

## Tech Stack

| Layer | Library |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + StateFlow |
| DI | Hilt |
| Database | Room (schema v2) |
| Camera | CameraX (Preview, ImageCapture, ImageAnalysis) |
| Image loading | Coil |
| Color science | Custom HSV analysis + OKLAB perceptual distance + WCAG contrast |
| Motion | Custom swipe physics (velocity-EMA flick detection, spring settles) |
| Location | FusedLocationProvider + Geocoder |
| Notifications | AlarmManager + BroadcastReceiver |
| Backup | Android Auto Backup |
| Language | Kotlin |

---

## Requirements

- Android 8.0+ (API 26)
- Physical camera required
- Permissions: Camera, Location (optional, for photo tagging), Media access, Notifications

---

## Project Structure

```
app/src/main/java/com/colorwalk/app/
├── domain/
│   ├── WalkColor.kt           # Color definitions and daily picker
│   ├── ColorValidator.kt      # Center-weighted HSV + saliency validation
│   ├── OkLab.kt               # OKLAB perceptual color space
│   ├── ExifIntegrity.kt       # Import forgery checks (sniffing, timestamps)
│   └── StreakCalculator.kt    # Consecutive-day streak logic
├── data/
│   ├── db/                    # Room database, DAO, entities
│   └── repository/            # PhotoRepository (save, import, notes, location)
├── notification/              # Alarm scheduling, reminder receiver, prefs
├── ui/
│   ├── home/                  # HomeScreen, HomeHubScreen (swipe hub), SwipePhysics
│   ├── camera/                # CameraScreen, LiveColorAnalyzer
│   ├── gallery/               # Gallery tabs, albums, full-screen viewer
│   ├── newsfeed/              # Your Walks journal
│   ├── stats/                 # Streaks & Stats
│   ├── settings/              # Settings (incl. version, notifications)
│   ├── onboarding/            # Swipeable onboarding + mini-game
│   ├── permission/            # Permission rationale screen
│   ├── components/            # Shared composables (zoomable image, cards…)
│   └── theme/                 # Material 3 theme, ChromaticTheme, WCAG helpers
├── viewmodel/                 # Home, Camera, Gallery, Newsfeed, Stats
└── di/                        # Hilt AppModule
```

---

## Build Instructions

```bash
# Requires JDK 17+ (Android Studio's bundled JBR works)
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests
./gradlew lintDebug            # lint
```

Release builds are signed via `keystore.properties` — copy `keystore.properties.template` and fill in your keystore details. CI runs build + tests on every push (`.github/workflows/android-ci.yml`).

---

## Install

_See [Releases](../../releases) for the latest APK download_

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for full version history. Current version: **1.25.0**.
