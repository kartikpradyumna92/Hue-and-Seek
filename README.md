# Hue & Seek

A daily color-hunt Android app. Every day a new color is assigned — go outside, find it, photograph it, and build your streak.

## How it works

Each day the app picks one of eight colors (Red, Orange, Yellow, Green, Blue, Purple, Pink, Brown) using a deterministic algorithm based on the calendar date, so every device sees the same color on the same day. You open the camera, find something that matches, and take a photo. The app analyzes the image using HSV color space and requires at least **60% of the pixels** to fall within the target color's hue range before the capture is accepted. A successful capture extends your streak.

## Features

### Home Screen
- Displays today's color as a large animated circle with its name and hex code
- Live clock with day, date, and time
- Background gradient shifts to reflect the day's color
- Streak counter showing consecutive days completed
- Quick access to Camera and Gallery

### Camera Screen
- Live camera preview with front/back camera toggle
- Zoom controls: `.5×  1×  1.5×  2×  3×  5×  10×  15×  20×`
- Shutter button captures and immediately validates the photo
- Import from device gallery (validates EXIF date — must be today's photo)
- Result overlay shows match percentage on success or failure with actionable feedback

### Gallery Screen
- **By Color** view — 2-column grid of color folders, each showing the color swatch and hex
- **By Date** view — chronological list grouped by month, with thumbnail, color dot, timestamp, and optional location name
- Tap any color folder to see all photos captured for that color
- Full-screen photo viewer with swipe support
- Delete with confirmation dialog (removes from app and device gallery)

### Color Validation
- Scales the captured bitmap down to 80×80 pixels for fast on-device analysis
- Checks each pixel's HSV values against the target color's hue/saturation/value bounds
- Uses Android Palette API to extract the dominant color hex for display
- 60% pixel match required to accept a capture

### Streak Tracking
- Consecutive-day streak computed from accepted photo timestamps
- Streak resets if no photo is captured for more than one day
- Home screen shows a fire icon on the color circle once today's color is captured

## Tech Stack

| Layer | Library |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + StateFlow |
| DI | Hilt |
| Database | Room |
| Camera | CameraX |
| Image Loading | Coil |
| Color Analysis | Android Palette API |
| Location | Google Play Services Location |
| Preferences | DataStore |
| Permissions | Accompanist Permissions |
| Language | Kotlin |

## Requirements

- Android 8.0+ (API 26)
- Camera hardware required
- Permissions: Camera, Location (optional, for tagging photos), Media access

## Project Structure

```
app/src/main/java/com/colorwalk/app/
├── domain/
│   ├── WalkColor.kt          # Color definitions and daily color picker
│   ├── ColorValidator.kt     # HSV-based photo validation logic
│   └── StreakCalculator.kt   # Consecutive-day streak computation
├── data/
│   ├── db/                   # Room database, DAO, entities
│   └── repository/           # PhotoRepository
├── ui/
│   ├── home/                 # HomeScreen
│   ├── camera/               # CameraScreen + result overlay
│   ├── gallery/              # GalleryScreen, ColorAlbumScreen, PhotoViewerScreen
│   └── theme/                # Material 3 theme
├── viewmodel/                # HomeViewModel, CameraViewModel, GalleryViewModel
└── di/                       # Hilt AppModule
```

## Screenshots

> Coming soon

## Building

Open the project in Android Studio (Hedgehog or later) and run on a physical device or emulator with API 26+.

```bash
./gradlew assembleDebug
```

## Author

**Karteek Pradyumna Bulusu**
