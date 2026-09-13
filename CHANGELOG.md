# RAZStudio Room — Changelog

## v1.04 Alpha — 2026-07-21 (ML_6D CR2 Intelligence + SD Card Browser)

### 🤖 ML_6D CR2 Intelligence — sidecar consumption

- **`MLExtendedIntelligence` orchestrator** dispatches 9 metadata-driven
  modules (Picture Style, Chromatic Aberration, Dual-ISO, Focus Depth, Flash
  Compensation, Thermal Noise, Extended Histogram, Diffraction Compensation,
  Body WB Trim) on RAW import, in fixed order, with sidecar values taking
  precedence over EXIF/MakerNote where available. Writes 6 new diagnostic
  slots (ShaderParams [402..407]) alongside reused macro fields (maxOf
  semantics — never regresses an existing adjustment).
- **`.ml6d` per-shot sidecar + `ml_export.json` session file** (written by the
  companion ML_6D Magic Lantern firmware) are discovered and parsed for both
  SAF (`content://`) and direct (`file://`) import paths, with graceful
  EXIF-only fallback on any missing/malformed/wrong-version file.
- **Fixed: two real sidecar-path bugs** found by diffing against an actual
  ML_6D card, both silently defeating discovery on real hardware: wrong
  extension (`.ml` instead of the firmware's `.ml6d`) and a double-extension
  path bug (`IMG_5496.CR2.ml6d` instead of the correct `IMG_5496.ml6d`).
  Also made the `ML/data/SHOTS/` path segments match case-insensitively — the
  deployed firmware writes a lowercase `data` directory.
- **Logging added throughout** (`MLExtended`, `MlSidecar` tags) so `adb
  logcat` can confirm the orchestrator ran without exceptions, which sidecar
  fields were used, and the resulting slot values — previously silent.

### 💾 SD Card Browser — NEW feature (`feature/sd-card-browser`, screen id=75)

- Browse and open CR2 files directly from an SD card connected via USB OTG
  card reader, with embedded-JPEG thumbnails, an ML badge when the card has
  ML_6D data, and automatic dual-ISO filename detection.
- **Two independent transports**, unified behind a `FileHandle` abstraction:
  a SAF path for hardware where Android auto-mounts the reader as a native
  volume, and a raw USB Mass Storage path (`libaums`, bulk-only transport)
  for hardware where it doesn't — confirmed empirically to be the common
  case on phones, since generic OTG automount is not universally wired up
  in AOSP. StudioRoom now registers for the USB "open with" chooser
  (`usb_device_filter.xml` + `USB_DEVICE_ATTACHED` intent-filter) so it can
  claim the device directly instead of always losing it to a third-party
  file manager.
- **Known limitation**: the raw USB path currently supports FAT12/16/32 only
  (a `libaums` library limitation — no exFAT `FileSystemCreator` exists in
  any published release). An exFAT-formatted card (the common SDXC default,
  ≥32GB) surfaces a clear "no readable partition" message rather than
  silently failing; see `docs/FEATURES.md` and the spec's design.md.

## v1.04 hardened — 2026-07-10 (vs. 2026-07-03 hardened baseline)

33 source files changed (+2,332 / −222) · 57 new database assets · package `com.RAZStudio.StudioRoom.hardened`

### 🔭 Lens Correction — NEW subsystem

- **Automatic lens profile correction** (distortion + vignetting + chromatic
  aberration) applied during RAW import, for **both** color routes (Camera
  Color Profile and RAZStudio RAW depth). Runs post-demosaic in Stage A — the
  same pipeline placement RawTherapee and darktable use — so preview and
  export share corrected pixels. Vignetting is corrected in linear light.
- **Profile database bundled** (57 XML files, ~5 MB): a snapshot of lensfun
  master **plus** community calibrations not yet merged upstream — Canon
  MP-E 65mm Macro, Canon EF-S 60mm f/2.8 Macro USM, 7Artisans 25mm f/1.8,
  Leica SL3-P, Samyang 12mm AF vignetting, improved Viltrox AF 9mm.
- **Start-page card** with a default-ON switch showing the auto-detected
  Camera body, Sensor format, and Lens type. Strict gate: correction runs
  only when camera AND lens are confidently matched — otherwise the import
  proceeds untouched with a clear notice.
- **Manual mix-and-match override**: every field is editable when auto-detect
  fails (or to override it). Camera body autocompletes from the database
  after 3 typed characters; lens selection is brand-first (canonical brand
  dropdown with counts), then a lens autocomplete field; sensor format is
  derived read-only from the chosen body. A focal-length field appears only
  for files with no EXIF focal (manual/adapted lenses), which the correction
  math requires.
- **Special-case lens remaps** for known kit quirks: "EF28mm f/2.8" (a
  custom-chipped Sigma High-Speed Wide 28/1.8) always resolves to the Sigma
  28mm f/1.8 EX DG profile, never the EF 28/2.8; "EF 100mm f/2 USM" (no
  profile exists) resolves to the optically-matching EF 85mm f/1.8 USM
  instead of false-matching the 100/2.8 Macro.
- **Batch: per-file lens correction** — a single "Lens Correction (per-file)"
  checkbox (default ON, both routes, folder batch + Canon Sync batch). Each
  photo is auto-detected from its own EXIF; unrecognised files process
  unchanged. No prompts.

### 🎭 RAW Editor — Masking

- **"Select Color" (Chroma)** and **"Select Luminance" (Luma)** range masks
  (Lightroom-style tap-to-sample + tolerance / tonal band + feather).
- **Detected-objects dropdown** with per-row Add + Remove (replaces per-class
  split buttons); Luma + Chroma live in a separated top category.
- **Universal Remove / base-carve workflow**: the first-selected segment is
  the base; every other segment's Remove carves its region out of that base;
  the base row's Remove clears the whole mask.
- **Live luma-base carve** (new engine capability): object regions can be
  carved out of a live Luma/Chroma base with full preview↔export parity
  (new per-layer combine mode in the GL shader + CPU export kernel,
  ShaderParams slot 395).
- **Fixed: second mask reused the first mask's drawing.** A duplicate of the
  topmost committed mask was uploaded as a phantom "live" layer, so a new
  mask session's adjustments applied through the previous mask's shape.
- **Fixed: selection overlay invisible under black-and-white LUTs.** The blue
  tint was applied before the LUT/film-sim stages, which desaturated it;
  it now draws as the final shader operation and stays blue under any look.
- Two-finger pan/zoom now always works, including in Draw/Erase modes.
- Removed the non-functional Tap-Select tool.

### 📷 Canon Sync

- **Home Wi-Fi (infrastructure) support**: phone and camera can both join the
  same router (EOS Utility mode) — no phone hotspot required; works with
  mobile data on (binds the Wi-Fi network explicitly instead of the OS
  default).
- **Fixed pairing prompt not firing**: unpaired cameras advertising an
  all-zeros/sentinel GUID now receive the persisted host GUID; tapping a
  discovered camera connects to its actual IP.
- **Faster, more resilient reconnects**: the keep-alive service (Wi-Fi lock +
  heartbeat) survives transient drops instead of tearing down mid-reconnect;
  stale cached IPs from other networks are skipped (removes a ~5 s stall).
- **Orphan `.part` staging files** from hard-kills are swept once per session.

### ⚖️ Batch processing

- **Fixed: batch output consistently overexposed vs. single-editor saves.**
  The headless per-file Auto Expose solve could not be made to match the
  editor's baked Auto-Expose-on-Open exactly (even after aligning defaults
  and passing the identical refined segmentation masks), so the batch
  "Auto Expose on Open (per-file)" option was removed entirely — batch
  never runs its own exposure analysis. Presets that carry a baked Auto
  Exposure action still apply unchanged, and batch output now matches a
  defaults-untouched editor save.

### 🖼️ Watermark / EXIF

- Additional manual/legacy lenses in the watermark lens autocomplete
  database.

### 🔒 Build

- R8-minified, native-symbol-hidden, release-signed. Adds ~5 MB of lens
  profile database assets vs. the 2026-07-03 build.
