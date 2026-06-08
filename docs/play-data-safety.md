# Play Console — Data Safety Form: Hue & Seek
### Verified against codebase on 2026-06-07

Use this as your reference while filling in the form at
Play Console → App content → Data safety.

---

## Audit findings (code-verified)

| Claim | Status | Evidence |
|---|---|---|
| Zero outbound network calls from app code | ✅ Confirmed | grep for http/URL/Retrofit/OkHttp returned no results |
| Location read once per capture, stored locally | ✅ Confirmed | `getLastLocation()` called only inside `savePhoto()` / `importPhoto()` |
| Geocoder used for reverse-geocoding | ⚠️ Note | `android.location.Geocoder` — OS API, may contact Google servers on-device |
| Photos saved to private storage + device gallery | ✅ Confirmed | `saveToPrivateStorage()` + `publishToSystemGallery()` |
| Play Review SDK triggers in-app review dialog | ✅ Confirmed | `ReviewManagerFactory.create()` → `launchReviewFlow()` |
| Android Auto Backup enabled | ✅ Confirmed | `android:allowBackup="true"` in manifest |
| No analytics, crash reporting, or ad SDKs | ✅ Confirmed | No such dependency in build.gradle.kts |

---

## Section 1 — Data collection and security

### "Does your app collect or share any of the required user data types?"
**→ YES**

### "Is all of the user data collected by your app sent encrypted in transit?"
**→ YES**
Justification: the app itself sends no data. The only external communication is:
- Android Auto Backup (encrypted by Google's backup infrastructure)
- `Geocoder` OS API (encrypted by Android platform, not your code)
- Play Review SDK (Google's own encrypted infrastructure)

### "Do you provide a way for users to request that their data is deleted?"
**→ YES — check this box**
Justification: Users can delete individual photos (and their associated location metadata)
from the gallery. Since there are no accounts and no server-side data, on-device
deletion via the gallery UI is sufficient. When the app is uninstalled, all Room DB
and SharedPreferences data is automatically erased.

---

## Section 2 — Data types

Fill in **only** the rows below. Leave every other data type unchecked.

---

### 📍 Location → Precise location

| Field | Answer |
|---|---|
| Collected? | **Yes** |
| Shared? | **No** |
| Required or optional? | **Optional** — app works without it; photos have no location label if denied |
| Processed ephemerally? | **No** — stored in Room DB |
| Purpose(s) | **App functionality** |

**Explanation to enter in the form:**
> GPS coordinates are read once at the moment a photo is captured and used to
> (1) generate a human-readable location label shown in the gallery, and
> (2) embed GPS EXIF tags in the copy saved to the device gallery so Google Photos
> can display the location. Coordinates and the derived label are stored in the
> app's on-device Room database. They are never transmitted to any server.

---

### 📍 Location → Approximate location

| Field | Answer |
|---|---|
| Collected? | **Yes** |
| Shared? | **No** |
| Required or optional? | **Optional** |
| Processed ephemerally? | **No** |
| Purpose(s) | **App functionality** |

**Explanation:**
> `ACCESS_COARSE_LOCATION` is declared because Android's FusedLocationProviderClient
> requires both fine and coarse location permissions. The app uses only the result of
> `getLastLocation()` (precise GPS when available, coarse otherwise) for the same
> purpose as precise location above.

---

### 📷 Photos and videos → Photos

| Field | Answer |
|---|---|
| Collected? | **Yes** |
| Shared? | **No** |
| Required or optional? | **Required** — core app function is capturing photos |
| Processed ephemerally? | **No** — saved to device gallery and private app storage |
| Purpose(s) | **App functionality** |

**Explanation:**
> Photos captured by the in-app camera or imported from the device gallery are
> saved to the app's private storage directory and optionally to the device gallery
> (`Pictures/ColorWalk`). Photos are never uploaded or transmitted. Photo metadata
> (file path, color label, timestamp, location) is stored in a local Room database.

---

### 📱 App activity → App interactions

| Field | Answer |
|---|---|
| Collected? | **Yes** |
| Shared? | **No** |
| Required or optional? | **Optional** |
| Processed ephemerally? | **No** |
| Purpose(s) | **App functionality** |

**Explanation:**
> The app stores a daily streak count and the day index of the last celebration
> (both in SharedPreferences) to drive streak tracking and prevent duplicate
> confetti animations. No behavioral or interaction analytics are collected.
> This data never leaves the device.

> **Note:** This entry is also required because the **Google Play In-App Review SDK**
> (`com.google.android.play:review:2.0.1`) collects "App interactions" on Google's
> behalf to process and display the in-app review prompt. See SDK section below.

---

### 🆔 Device or other IDs

| Field | Answer |
|---|---|
| Collected? | **Yes** (SDK-driven) |
| Shared? | **Yes** (with Google, via Play Review SDK) |
| Required or optional? | **Optional** |
| Processed ephemerally? | **No** |
| Purpose(s) | **App functionality** |

**Explanation:**
> The app code itself does not read any device identifier. This entry is required
> because the **Google Play In-App Review SDK** uses device identifiers internally
> to manage review eligibility and deduplication. Data handling is governed by
> Google Play's own privacy policy.

---

## Section 3 — SDK declarations

The form has a separate step to declare third-party SDKs. Fill these in exactly:

### SDK 1: Google Play In-App Review
- **SDK name:** Google Play In-App Review
- **Version:** 2.0.1
- **Data collected by SDK:** App interactions, Device or other IDs
- **Purpose:** App functionality (showing the native Play Store review dialog)
- **Data shared outside app:** Yes — processed by Google Play
- Source: [Play SDK Index entry](https://play.google.com/sdks/details/com.google.android.play.review)

### SDK 2: Google Play Services — Location
- **SDK name:** Google Play Services — Location  
- **Version:** 21.3.0
- **Data collected by SDK on SDK provider's behalf:** None
- **Note:** This SDK is a conduit to device location hardware. Your app's own
  location collection (declared above under Precise/Approximate location) covers it.
- Source: [Play SDK Index entry](https://play.google.com/sdks/details/com.google.android.gms.location)

---

## Section 4 — Data NOT collected (leave unchecked)

Confirm these are all unchecked in the form:

- ❌ Name, Email, User IDs, Address, Phone number
- ❌ Race/ethnicity, Political/religious beliefs, Sexual orientation
- ❌ Financial info, Health and fitness
- ❌ Messages, Contacts, Calendar
- ❌ Audio files, Files and docs
- ❌ Web browsing history
- ❌ Crash logs / Diagnostics / App performance data
  *(The app has no crash reporting SDK — crashes surface only through standard
  Android system logs that the user controls via developer options.)*
- ❌ Installed apps

---

## Potential gap to address before submission

**Data deletion link** — Google Play now recommends (and may soon require) a
public-facing URL where users can request data deletion, separate from the
in-app delete flow. Since all data is on-device, a simple FAQ page that explains
"uninstalling the app deletes all data" satisfies this requirement.
Consider adding a "Delete my data" section to the privacy policy page at
`https://kartikpradyumna92.github.io/Hue-and-Seek/`.
