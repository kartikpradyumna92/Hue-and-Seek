# Hue & Seek

A daily mindful photography app for Android. Every day the app picks one color — Red, Orange, Yellow, Green, Blue, Purple, Pink, or Brown — and challenges you to photograph the world through that color's lens. Photos are validated to ensure the target color dominates at least 60% of the frame. Build a daily streak, grow your color gallery, and train your eye to notice everyday details.

> A single color photo walk is an excellent way to cure creative block. Instead of photographing everything, you narrow your focus to one hue and suddenly the world looks completely different.

---

## How It Works

Each day the app deterministically picks one of eight colors based on the calendar date — every device sees the same color on the same day. You open the camera, find something that matches, and take a photo. The app analyzes the image using pixel-level HSV color space analysis and requires **at least 60% of the pixels** to fall within the target color's hue range before the capture is accepted. A successful capture extends your daily streak.

---

## Features

### Home Screen
- Today's color displayed as a large animated circle with name and hex code
- Title "Hue & Seek" styled in the day's color
- Live clock with day, date, and time
- Background gradient shifts to reflect the day's color
- Streak counter with contextual messages — congratulatory when complete, time-aware nudges when not
- Quick access to Camera and Gallery

### Camera
- Live in-app camera with CameraX
- Color hint pill showing today's target (e.g. "Find Green")
- Zoom controls: `.5×  1×  1.5×  2×  3×  5×  10×  15×  20×`
- Front / back camera flip
- Import from Google Photos — validates EXIF date (must be today) + same 60% color check
- Result overlay shows match percentage on success or failure

### Color Validation
- Downsamples captured image to 80×80 pixels for fast on-device analysis
- Checks every pixel's HSV values against the target color's hue, saturation, and brightness bounds
- Requires **60% pixel match** to accept — rejected photos are not saved anywhere
- Failure message shows exact percentage detected (e.g. "47% detected — need 60%")

### Gallery
- **By Color** — 2-column grid of color folders, each showing the color swatch and hex code
- **By Date** — chronological list grouped by month-year, descending
- Full-screen photo viewer with swipe left/right navigation and zoom (1× 2× 3× 5×)
- Each photo shows date, time, and reverse-geocoded location (e.g. "Sunnyvale, California")
- Delete from gallery removes from both the app and the device gallery

### Streak
- Counts consecutive calendar days with at least one accepted photo
- Resets if a day is missed
- Persists across app reinstalls via Android Auto Backup to Google Drive
- Daily noon notification if the walk for the day is not yet complete

---

## Tech Stack

| Layer | Library |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + StateFlow |
| DI | Hilt |
| Database | Room |
| Camera | CameraX |
| Image loading | Coil |
| Color validation | Custom HSV pixel analysis + Palette API |
| Location | FusedLocationProvider |
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
│   ├── ColorValidator.kt      # HSV pixel-level photo validation
│   └── StreakCalculator.kt    # Consecutive-day streak logic
├── data/
│   ├── db/                    # Room database, DAO, entities
│   └── repository/            # PhotoRepository
├── notification/
│   ├── AlarmScheduler.kt      # Daily noon alarm setup
│   ├── StreakReminderReceiver  # Checks streak, fires notification
│   ├── BootReceiver.kt        # Reschedules alarm after reboot
│   └── NotificationHelper.kt  # Notification channel and builder
├── ui/
│   ├── home/                  # HomeScreen
│   ├── camera/                # CameraScreen
│   ├── gallery/               # GalleryScreen, ColorAlbumScreen, PhotoViewerScreen
│   └── theme/                 # Material 3 dark theme
├── viewmodel/                 # HomeViewModel, CameraViewModel, GalleryViewModel
└── di/                        # Hilt AppModule
```

---

## Screenshots

_Coming soon_

---

## Build Instructions

_Coming soon_

---

## Install

_See [Releases](../../releases) for the latest APK download_

---

## Contributing

_Coming soon_

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for full version history.
