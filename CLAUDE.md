# RAZStudio Room (StudioRoom)

Android photo suite (Jetpack Compose) whose centerpiece is a professional RAW
editor with a custom native pipeline. Package `com.RAZStudio.StudioRoom`,
version 1.04. Single developer; APKs are built + adb-installed directly to a
test device — there is no CI.

Deep-dive docs (read the one matching your task before editing):

- **docs/PIPELINE.md** — RAW pipeline (Stage A/B/C), ShaderParams slots, GL
  preview vs CPU export parity rules, masks, AI/segmentation models.
- **docs/FEATURES.md** — lens correction (lensfun port), Canon Sync (PTP/IP),
  batch processing, watermark/EXIF, presets/LUTs.
- **docs/GOTCHAS.md** — hard-won lessons, user preferences, and known traps.
  **Read this before any pipeline or mask change.**

## Module map (52 Gradle modules — the ones that matter)

- `app` — shell. Flavors: `foss` (the real one) / `market` (R8+Hilt broken,
  ignore). Build types: `debug`, `hardened` (R8-minified, native
  symbol-hidden release; separate package suffix `.hardened`).
- `feature/photo-editor` — the RAW editor. Kotlin pipeline glue in
  `raw_v3/` (RawV3Coordinator, RawV3Engine, ShaderParams, RawV3ActionReplay),
  Compose UI in `presentation/raw/` (RawEditorContent ~3k lines,
  WorkspaceSelectorSheet = the "start page", RawMaskTab, batch panels),
  models in `raw/model/` (UserMacro in RawPipelineState.kt, WorkspaceConfig).
  Assets: `lensfun_db/` (lens profiles), `lens_database.txt` (watermark
  autocomplete), `device_names.txt`, `presets/`, ML models.
- `lib/raw-native` — C++ core (one `raw_decoder` .so): LibRaw + demosaic +
  `v3/stage_a.cpp` (decode→FP16 BigTIFF), `v3/gles_renderer.cpp` (GL preview
  uber-shader), `v3/apply_macro.cpp` (CPU export kernel — must mirror the
  shader), `v3/v3_jni.cpp`, `lensfun_android.cpp` (faithful lensfun math
  port).
- `feature/canon-sync` — Canon PTP/IP Wi-Fi tethering (discovery, pairing,
  download/process, remote shoot).
- Open RAZStudio Room is **export-only** (`tools/export-open.ps1` → `_foss_export`); new work stays in this tree.
- `core/settings` — prefs incl. `RawBatchPrefs`.
- Everything else (filters, draw, crop, collages, …) is conventional and
  rarely touched.

## Build & install

```bash
./gradlew :app:assembleFossDebug        # daily build
./gradlew :app:assembleFossHardened     # release (R8 + hidden symbols)
# install: adb install -r "app/build/outputs/apk/foss/<type>/*.apk"
# adb lives at $LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe (Windows)
```

- **Known compiler glitch**: Kotlin incremental compilation intermittently
  fails with "Internal compiler error" / "Exception while generating code
  for" on the big photo-editor files. Fix:
  `./gradlew :feature:photo-editor:compileFossDebugKotlin --rerun-tasks`,
  then assemble. **A failed build leaves the previous APK in place — check
  the APK timestamp before installing.**
- Verification is on-device (adb logcat + screencaps); there is no test
  suite for the pipeline.

## Hard rules (violations have shipped bugs before)

1. **Preview = export.** Any pixel-affecting change to the GL shader
   (`gles_renderer.cpp`) must be mirrored in the CPU export kernel
   (`apply_macro.cpp`) and vice versa. See docs/PIPELINE.md.
2. **Never say "Lensfun" (or other library names) in user-facing UI text** —
   say "camera and lens profile". Library names in logs/comments are fine.
3. **Kotlin block comments nest** — a glob like `dir/*.xml` inside a KDoc
   opens a nested comment and swallows the file.
4. **Heavy ops on gesture commit, live feedback via graphicsLayer** (crop
   straighten pattern) — never re-render full bitmaps per gesture tick.
5. ShaderParams slots are append-only ABI: next free slot is documented in
   `ShaderParams.kt` (FLOAT_COUNT 410, headroom from [396]); slot [395] =
   maskLumCombine.
6. The user prefers warm per-channel WB gains over perceptually-correct
   color science (CAT16 etc. was rejected as "awful") — don't "fix" color
   toward correctness.
