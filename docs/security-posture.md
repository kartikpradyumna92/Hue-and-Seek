# Hue & Seek — Security & Privacy Posture

Established during the 2026-07 full-code audit (verified as of v1.27.0+,
2026-07-12). These are **invariants**: properties the codebase currently holds
and that future changes should preserve. If a change breaks one, that's not
automatically wrong — but it should be a deliberate decision, not an accident.

## Attack surface

- **No exported components beyond the launcher activity.** The only other
  exported component is `BootReceiver`, whose intent-filter contains solely
  protected system broadcasts (BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET,
  TIMEZONE_CHANGED, SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) that only
  the system can send. `StreakReminderReceiver` is `exported="false"`.
- **No network code.** The app makes zero outbound calls of its own; the only
  external communication is via OS/Google infrastructure (Geocoder, Auto
  Backup, Play Review SDK). Adding any networking library is a posture change
  that requires revisiting the Play data-safety form
  (`docs/play-data-safety.md`).
- **FileProvider is least-privilege**: `res/xml/file_paths.xml` maps ONLY
  `files/photos` — the sole content ever shared. Do not add broader roots
  (external-path, cache-path, …) without a concrete caller.

## Data handling

- **SQL injection surface: none.** All Room queries are parameterized; no
  `@RawQuery`, no string-built SQL.
- **Path traversal: closed.** Every write into the private photo store goes
  through `PhotoFileStore.safeDestination()` — a canonical-path containment
  check — even though filenames come only from our own generator or MediaStore
  `DISPLAY_NAME`.
- **MediaStore mutations are album-scoped.** Deletes and EXIF writes match
  `DISPLAY_NAME` *and* the `Pictures/ColorWalk` path, never filename alone.
- **Deletion tombstones** (`DeletionTombstones`) prevent post-reinstall photo
  resurrection; they are pruned only when their MediaStore counterpart is
  gone (existence-based — age-based pruning would re-open the bug).
- **Import forgery checks** (`ExifIntegrity`) are tamper-EVIDENT, not
  tamper-proof: magic-byte sniffing restricted to real image brands plus
  timestamp cross-validation. They stop casual date editing; never present
  them as verification.
- **Auto Backup is explicitly scoped** (`backup_rules.xml` /
  `data_extraction_rules.xml`): the Room DB and `app_prefs.xml` only — photo
  files are deliberately excluded (quota) and recovered via MediaStore sync.
- **Destructive Room migration is banned** — see the SCHEMA CHANGE RULE
  comment in `AppDatabase`; never add `fallbackToDestructiveMigration`.

## Privacy behaviors

- **Camera runs only while the Camera pane is visible** — CameraX use cases
  are explicitly unbound when the pane leaves composition (the privacy
  indicator must go out when the user swipes away).
- **Location** is read once per capture (5s-bounded active fix), stored
  locally, embedded as GPS EXIF in the user's own gallery copy, and never
  transmitted by the app.
- **User notes** live in the local DB and the JPEGs' EXIF `ImageDescription`;
  the provenance `UserComment` tag carries only the walk color + dominant hex.

## Build & release hygiene

- **Secrets never in git**: `keystore.properties`, `*.jks`, `local.properties`
  are gitignored; verified never committed in history.
- **Release builds** ship with R8 minification + resource shrinking.
- **Accessibility/contrast**: accent-surface text colors are chosen by WCAG
  relative-luminance math (`ui/theme/Wcag.kt`, ≥4.5:1) — don't hardcode
  black/white on day-colored surfaces.

## Performance invariants worth keeping

- The live camera analyzer loop is allocation-free at steady state
  (`LiveColorAnalyzer`, `ColorValidator.liveTargetShare`).
- Hub drag offsets are never read in the composition phase — only inside
  `graphicsLayer` blocks or via `derivedStateOf` (see M-1 in the audit).
- All bitmap decodes are bounded at 4096px (`PhotoFileStore`); saved JPEGs are
  written byte-verbatim (EXIF preserved, no re-encode generation).
