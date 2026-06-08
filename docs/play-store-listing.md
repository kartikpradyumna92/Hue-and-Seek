# Play Store Listing — Hue & Seek
### Written 2026-06-07, based on attached device screenshots

---

## Short description
**Limit: 80 characters. Shown in search results and at the top of the listing.**

```
Hunt today's color in the real world. One photo, one streak, every day.
```
(71 characters ✓)

---

## Full description
**Limit: 4,000 characters. Use plain text — no HTML. Blank lines become paragraph breaks.**

```
Every day, Hue & Seek gives you one color to find in the real world.

Step outside. Look around. When you spot it — a yellow bus, an orange carton, a patch of purple flowers — open the app, point the camera, and capture it. The app checks automatically: if your color dominates the frame, your streak grows by one day. Miss a day, and you start over.

That's the whole game. Simple on paper. Surprisingly hard to put down.

──────────────────────────
HOW IT WORKS
──────────────────────────
› Open the app to see today's color challenge
› Go out into the world and find it
› Capture a photo — the app validates the color on-device instantly
› Your streak grows. Come back tomorrow for a new color.

──────────────────────────
FEATURES
──────────────────────────
› 8 colors in rotation: Red, Orange, Yellow, Green, Blue, Purple, Pink, Brown
› Color validation — dominant-color algorithm checks your photo automatically, no manual judging
› Daily streak counter with milestone celebrations at 7, 21, 30, 50, 100+ days
› 14-day color history strip so you can see your recent walk at a glance
› Gallery organized by color folder and by date — your whole journey in one place
› Location tagging — each photo is geo-tagged and reverse-geocoded ("Sunnyvale, California")
› Import a photo from your gallery if you already captured the color earlier
› Two configurable daily reminders (morning + evening), each individually toggleable
› Full-screen photo viewer with pinch-to-zoom and swipe between photos
› Light and dark theme support
› Home screen widget showing today's color and current streak

──────────────────────────
PRIVACY
──────────────────────────
Everything stays on your device. No account required. No data uploaded. No ads.
Location is optional — the app works perfectly without it.

Privacy policy: https://kartikpradyumna92.github.io/Hue-and-Seek/
```

Character count: ~1,580 (well within 4,000 limit — room to expand if needed)

---

## Screenshots — recommended order and notes

Play Console allows up to 8 phone screenshots. Upload them in this order:

| # | Screen | From your set? | Notes |
|---|---|---|---|
| 1 | **Home screen** — streak card, color circle, history dots | ✅ Screenshot 3 | Best hero shot. Lead with this. |
| 2 | **Camera screen** — viewfinder with color hint pill | ❌ MISSING | Core feature — must capture and add. Instructions below. |
| 3 | **Gallery — By Color** — color folder grid | ✅ Screenshot 1 | Shows collection/discovery angle. |
| 4 | **Gallery — By Date** — actual photos in a grid | ✅ Screenshot 2 | Shows real photos and the memory aspect. |
| 5 | **Settings** — notifications section visible | ✅ Screenshot 8 | Shows polish and configurability. |
| 6 | **Onboarding — "A new color, every day"** | ✅ Screenshot 4 | Acceptable if camera screen unavailable; explains the concept. |
| 7 | **Onboarding — "Build your streak"** | ✅ Screenshot 6 | Shows the streak mechanic. |
| 8 | *(optional)* Onboarding — "Capture & validate" | ✅ Screenshot 5 | Explains validation. Use only if you need 8. |

**Do NOT use:**
- Screenshot 7 (Permissions "Before we begin") — users don't need to see a permission dialog as a selling point.

### How to capture the missing camera screenshot
1. Open the app on your phone
2. Navigate to the Camera screen
3. Aim at something clearly yellow (today's color) so the color hint pill is visible
4. Take a screenshot (power + volume down) **before** pressing the capture button
5. The color hint pill at the top, the zoom buttons at the bottom, and a yellow-dominant frame make a compelling screenshot

---

## App icon — 512×512 PNG
**Status: STILL NEEDED — must create manually.**

Requirements:
- 512 × 512 pixels, PNG, no alpha channel (flatten to white background if needed)
- No rounded corners — Google Play applies the shape mask automatically

The current launcher icon uses a flame on a dark maroon background with an orange-to-purple gradient.
To generate the 512×512 version:
- In Android Studio: `File → New → Image Asset` → set foreground to `ic_launcher_foreground`, background to `#4A1010` (dark maroon), export at 512×512
- Or: export the flame SVG asset from `res/drawable/` at 512×512 using any vector editor (Figma, Inkscape)

---

## Feature graphic — 1024×500 PNG
**Status: STILL NEEDED — must create manually.**

Requirements: 1024 × 500 pixels, JPEG or 24-bit PNG. Shown at the top of your Play Store listing on phones and at the top of the store page on tablets.

Suggested layout (simple, matches app aesthetic):
- Background: dark (`#0D0D0D` or the maroon `#4A1010`)
- Left half: app icon (flame, ~200×200px) centered vertically
- Right half: "Hue & Seek" in the gradient title font (or white bold), with tagline below in gray: "Hunt today's color. Build your streak."
- Accent: a horizontal row of 8 colored dots (the 8 walk colors) across the bottom third

Tools: Figma (free), Canva, or any image editor. Do not include text that duplicates the app name (Google may reject it if it's too small to read at thumbnail size).
