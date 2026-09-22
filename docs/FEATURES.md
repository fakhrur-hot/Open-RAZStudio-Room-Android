# Feature Subsystems

## ML_6D CR2 Intelligence (sidecar consumption, `raw_v3/ml/`)

- **Contract**: ML_6D (companion Magic Lantern firmware, separate repo
  `C:\Users\Public\Kiro\ML_6D`, see `docs/RAZSTUDIO_CONTRACT.md` there) writes
  two files per SD card: `ML/data/SHOTS/{basename}.ml6d` (per-shot sidecar,
  JSON) and `ML/data/ml_export.json` (per-session). **Real, verified path is
  lowercase `data`** (confirmed against an actual card) — the contract doc's
  own history shows the sidecar filename itself was already fixed firmware-
  side from a double-extension bug (`IMG_NNNN.CR2.ml` → `IMG_NNNN.ml6d`).
- **`MlSidecarParser.kt`** (`feature/photo-editor/.../raw_v3/ml/`) discovers
  and parses the sidecar for a given CR2 Uri (both `file://` and SAF
  `content://`). Two real bugs found and fixed 2026-07-21 by diffing against
  a real card: (1) was looking for `.ml` not `.ml6d`; (2) was building the
  sidecar path from the *full* CR2 filename (`IMG_5496.CR2.ml6d`) instead of
  the basename (`IMG_5496.ml6d`) — reproducing the exact bug the firmware
  side had already fixed. Path segments (`ML`/`data`/`SHOTS`) are matched
  case-insensitively since SAF's `findFile()` only matches exact case and
  the real firmware directory is lowercase.
- **`MLExtendedIntelligence.applyExtendedDefaults()`** dispatches 9 modules
  (Picture Style, CA, Dual-ISO, Focus Depth, Flash Comp, Thermal Noise,
  Extended Histogram, Diffraction, Body WB Trim) in fixed order, sidecar
  values taking precedence over EXIF/MakerNote, each call wrapped in a
  `safe {}` that now logs (tag `MLExtended`) instead of silently swallowing
  exceptions. Writes reused UserMacro fields (maxOf semantics) plus 6 new
  diagnostics → ShaderParams [402..407] (see docs/PIPELINE.md).
- **Logging**: tags `MlSidecar` (discovery/validation), `MLExtended`
  (orchestrator), `RawEditorComponent.v3` (call site) — added specifically so
  `adb logcat` can confirm sidecar-found vs EXIF-fallback and non-zero slot
  values on-device, since the orchestrator previously had zero log output.

## SD Card Browser (USB OTG, `feature/sd-card-browser`)

- Screen id=75, browses/opens CR2s from an SD card via USB OTG card reader.
  Two independent transports unified behind `FileHandle` (Saf | Usb):
  - **SAF**: OS auto-mounted the reader as a native `StorageVolume`
    (`SdCardVolumeDetector`, regex-matches volume paths). Pure browse/open,
    no copying.
  - **Raw USB** (`UsbMsdVolumeDetector` + `libaums:core:0.10.0`, Apache-2.0):
    claims the device directly via `UsbManager` + bulk-only transport when
    vold never mounts it — **this is the normal case on phones**, since
    generic OTG mass-storage automount is not universally wired up in AOSP
    (confirmed empirically: `sm list-volumes`/`/proc/mounts` showed zero
    volume/mount for the reader on the test device, for *any* filesystem,
    even though the kernel itself has `exfat` in `/proc/filesystems` — vold
    just never attempts it for USB-host-mode OTG here). Opening a CR2 copies
    it (+ resolved `.ml6d` sidecar) into app cache and hands the editor a
    `file://` URI — safe since this is internal Decompose navigation, never
    an Android `Intent` leaving the process.
  - Requires `usb_device_filter.xml` (matches USB Mass Storage class
    generically: class=8/subclass=6/protocol=80) + a `USB_DEVICE_ATTACHED`
    intent-filter on the main activity so StudioRoom competes in the "open
    with" chooser other card-reader apps (e.g. CX File Explorer) use — if the
    user has "always" some other app for that reader, `adb shell pm clear-
    defaults <package>` (or Settings → Apps → that app → Open by default)
    resets the chooser.
  - **Known limitation — exFAT unsupported**: `libaums:core` is FAT12/16/32
    only, no exFAT `FileSystemCreator`, and no maintained exFAT-capable
    Android USB-MSD library was found. A card formatted exFAT (the modern
    SDXC default, ≥32GB) opens the device fine (permission, partition table)
    but fails to construct a filesystem → `"No readable partition on USB
    device"`. Not a bug — see design.md Known Limitations. Reformatting to
    FAT32 is blocked on cards >32GB by Windows' own tools (hard 32GB cap on
    `format`/Disk Management/`Format-Volume`, not a filesystem limit).
  - **Threading trap**: `UsbMassStorageDevice.init()` does blocking USB
    bulk-transfer I/O. The Decompose `componentScope` collecting the
    detector's flow runs on `Dispatchers.Main` — calling into libaums
    without `launch(dispatchers.ioDispatcher) { }` around each `tryOpen()`
    silently froze the main thread with zero log output (looked like
    "nothing detected", no crash, no error) until the reader was unplugged.
    A `withTimeoutOrNull(10_000L)` guard now converts a genuine hardware/
    driver hang into a visible error instead of an indefinite freeze.

## Lens correction (lensfun port) — 2026-07 subsystem

- **Native math**: `lib/raw-native/src/main/cpp/lensfun_android.cpp` is a
  faithful port of lensfun master (NOT the upstream library — no GLib).
  Focal-normalized coordinates (NormScale = 43.2666mm/crop/pixelDiag/
  realFocal), hugin coefficient rescaling with the PT `d` re-normalization,
  ptlens+poly3 distortion, poly3 TCA, PA devignetting applied as ×(1/Cd) in
  linear light, binary-search auto-scale (no black borders), row-parallel.
  Runs in Stage A post-demosaic on the sRGB FP16 buffer
  (linearize→gain→re-encode for vignetting).
- **Database**: `feature/photo-editor/src/main/assets/lensfun_db/` — 58
  XMLs = 1,056 cameras / 1,587 lenses (re-synced 2026-09-22 to lensfun master
  via scripts/lensfun_sync.py, which also re-applies
  the in-place vignetting patch from still-open PR #2004 Samyang AF 12/2;
  PR #2858 TTArtisan 40/2 Z has landed upstream and was dropped from PATCHES). Two files are NOT master: `zz-pending-upstream.xml` (open PRs
  #2781 Canon MP-E 65, #2408 7Artisans 25/1.8, issue #509 EF-S 60 Macro, plus
  the owner's Sigma 28/1.8 HSW II and EF 100/2 remap targets) and
  `zz-community-extras.xml` (profiles from outside lensfun: 7 Hasselblad XCD
  V/P lenses LCP-converted by jlnevill/Lens-Tools, GPL-3.0 data; PkmX's three
  FD/Adaptall + Lens Turbo II combos and the Nexus 5; martbetz's Promura 28/2.8).
  Deliberately skipped: PR #1588/#1039 (Sigma renames/aliases — our parser keys
  a lens on its FIRST <model>, so an alias is inert and a rename changes
  matching), issue #2836 Leica Q2M/Q3M (master already has "Q2 Mono"/"Q3 Mono"
  on the full Q2 lens profile), and any lens master already carries (Fuji XC
  35/2, Sigma 17-40 Art). Materialized to filesDir by
  `LensfunDatabase.ensureMaterialized`; bump `DB_VERSION` (now 8) whenever XML
  content changes without a file-count change. Other online sources are Adobe
  LCP (proprietary) and PTLens (paid) — master IS the canonical maximum, and the
  GitHub sweep found nothing else with real calibration data.
- **Matching**: `lfa_match_strict` — normKey (lowercase alnum) containment,
  numeric-token fallback for lenses; STRICT gate: no confident camera+lens
  match → no correction, import proceeds untouched. UI probe, Stage A and
  logs all go through this one matcher (WYSIWYG).
- **Special-case remaps** (`remapSpecialLens`, unconditional on the lens
  string): "EF28mm f/2.8" (user's custom-chipped Sigma High-Speed Wide
  28/1.8) → "Sigma 28mm f/1.8 EX DG" (only AF 28/1.8 with calibration;
  distortion-only, calibrated on 1.53× crop — rescale handles FF);
  "EF100mm f/2*" (no profile exists; excludes f/2.8 strings) → "Canon EF
  85mm f/1.8 USM" (same optical family, full calibration; prevents
  numeric-token false-match to the 100/2.8 Macro).
- **UI** (WorkspaceSelectorSheet "Lens Correction" card, default ON, both
  color routes): auto-detected Camera body / Sensor format / Lens type.
  Manual mix-and-match: camera-body autocomplete (≥3 chars, from
  `nativeLensfunListCameras`), brand-first lens picker (canonical brands
  via `LensfunDatabase.canonicalBrand` — collapses "Nikon Corporation",
  4× Olympus spellings, KMZ=Zenit etc.) then lens autocomplete; sensor
  format is a derived read-only line; focal-length field only when EXIF
  focal is 0 (manual/adapted lenses — the math needs focal). Manual picks
  ride the existing `lensfunCameraId/lensfunLensId` exact-match overrides;
  `lensfunFocalOverrideMm` plumbs UI→WorkspaceConfig→options→JNI→StageA.
- **Batch**: "Lens Correction (per-file)" checkbox (default ON, folder +
  Canon batch). Passes ONLY dbDir — no overrides — so Stage A auto-matches
  every file from its own EXIF.
- Plumbing chain for options: WorkspaceConfig → RawExportBridge.toV3Options
  → RawV3WorkspaceOptions → RawV3Engine.stageADecode (positional JNI,
  params appended at END) → StageAOptions.

## Canon Sync (PTP/IP tethering, `feature/canon-sync`)

- Protocol: camera = PTP/IP server on :15740. Pairing: InitCommandRequest
  (type 0x01, client GUID + UTF-16LE name) → Ack (connId) → 2nd socket
  InitEventRequest/Ack → GetDeviceInfo → OpenSession. **Unpaired cameras
  advertise all-zeros or `…0001-ffffffffffff` GUID — substitute the
  persisted hostGuid or the on-body pair prompt never fires.**
- Discovery race: mDNS `_ptp._tcp` + SSDP/UPnP + subnet TCP port-scan of
  :15740 (scans ALL phone interfaces — works on hotspot AND home Wi-Fi).
- Topologies: phone hotspot (6D: 2.4GHz ch 1-11 only — band-steering
  routers/ch 12-13 cause intermittent discovery) and home-Wi-Fi
  infrastructure (NetworkBinder.findWifiNetwork binds the WIFI-transport
  network even when cellular is the OS default; scan loop + status pill
  treat internet-Wi-Fi as camera-routable).
- Session keep-alive: FGS (CONNECTED_DEVICE|DATA_SYNC) +
  WIFI_MODE_FULL_HIGH_PERF WifiLock + EOS_KeepDeviceOn (0x911D) heartbeat
  every 3s (NOT PCHDDCapacity — 6D rejects it). 3 consecutive failures →
  auto-reconnect supervisor (backoff 500ms→30s, rescans + MAC-matches for
  fresh DHCP IP, skips cached IPs not on the current subnet, keeps the FGS
  alive across the reconnect via `_reconnecting`).
- Downloads: chunked 0x9107 into SAF `.part` staging → rename; 128KB
  buffered streams; orphan `.part` swept once per session. Thumbs via
  0x910A. Batch download+process reuses RawBatchProcessor.PerFileContext.
- Intermittent disconnects with a solid heartbeat = the 6D's weak 2.4GHz
  radio dropping off the AP, not the app. Advise: camera Auto power off =
  Disable; split 2.4/5GHz SSIDs.

## Batch processing (`RawBatchProcessor` + RawBatchSection/SettingsPanel)

- Same pipeline as single edit: WorkspaceConfig.fromPrefs → toV3Options →
  exportRawToGallery per file. No Stage B (A→C directly).
- **Per-file Auto Expose is REMOVED** (getter hard-returns false): the
  headless solve never exactly matched the editor's baked
  Auto-Expose-on-Open, so parity is enforced by not offering it. Presets
  carrying a baked AE action still apply.
- Route A/B selector mirrors the editor; Route A forces Reconstruct/
  Enhance off. Subject segmentation still runs per file when needed
  (LUT/bokeh/segment-scoped presets) — BiRefNet is Java-heap-gated and
  falls to DeepLab (~4s) in batch.
- **A.tif cache is route-poisonable** — the cache key is content-SHA only,
  but a `.camprofile`-marked A.tif has the Route-A camera curve baked into
  its PIXELS. `exportRawToGallery`/`prewarmStageA` therefore treat a marked
  A.tif as invalid for a Route-B export and force a re-decode. Never
  reintroduce a bare `stageATif.exists()` cache check.
- **Render-time segmentation trigger** (`paramsNeedSubjectMask`): the AE
  branch only runs segmentation for the auto-expose SOLVE, so presets with
  baked AE (or no AE) used to render bokeh / per-segment tone / subject
  vignette / semantic gradients / smart sharpness with a NULL mask — they
  silently gated to zero while the editor preview showed them working.
  `prepareContext.needsSubject` must stay in sync with that predicate.
- **Two-stage pipeline**: file N+1's Stage A is prefetched
  (`prewarmStageA`) while file N runs Stage C/encode. `stageADecodeMutex`
  guarantees only one native LibRaw demosaic at a time, and
  `prefetchMemoryOk()` disables the overlap under memory pressure — the
  loop then degrades to the original strictly-sequential behaviour.
- Batch-invariant work is hoisted onto the coordinator: HDR/shadow model
  assets and the resolved LUT chain `.cube` are loaded/parsed once, not
  per file (the LUT chain itself already resolved once per batch).
- Sticky prefs in `core/settings RawBatchPrefs` (only persisted on user
  click — changing a DEFAULT affects untouched installs).
- Known remaining parity gaps (batch vs single edit): `smartWbStats`
  (Color Pop) and ML_6D `extDiagnostics` are never passed to
  `buildExportOptions` → zeroed; batch's base macro is
  `CAMERA_STYLE_FINISH` only, lacking the editor's per-photo
  highlight-protection baseline; Remove Shadows is an editor-only
  post-pass; Canon Sync's batch sub-path passes no LUT chain.

## Watermark / EXIF

- `RawWatermarkSheet` burns via `burnCombinedWatermarkOnto` — the preview
  burns the SAME function onto a source copy (never hand-mirror a Canvas
  overlay; it drifted).
- EXIF sources: RAW = Stage A native metadata (incl. date from
  `other.timestamp`, NOT desc); JPEG/HEIC fallback = ExifInterface.
  Device model → marketing name via `device_names.txt` (25k entries,
  Google Play CSV) at the burn layer. Brand logos = white-on-transparent
  PNGs in core/resources `brand_logos/`.
- Lens field autocomplete (manual/legacy lenses) from `lens_database.txt`
  (~1010 entries) — separate from lensfun_db. Watermark-layer remap:
  "EF28mm f/2.8" shot faster than f/2.8 displays as the Sigma 28/1.8 II
  (`correctLensModel`) — the lens-correction remap is its pipeline twin.

## Presets / LUTs

### LUT Creator (homepage)

Three tabs → User's Lut library:
- **Before/After pairs** — `PairLutFitter` poly fit (aligned crops).
- **Match from photo** — Lab colour + CDF tone + film-tone analytics
  (black lift / highlight shoulder / shadow·highlight casts), residual
  lattice at 17³ upsampled to 33³, strength sliders.
- **Look from reference** — reference-only residual look (no source photo).

Colour/tone bake into `.cube` only. Optional grain + glow live in a
`.look.json` sidecar beside the cube and apply when the entry is selected
in the RAW LUT tab (`filmGrain*` / `fxGlow*`). Never bake grain into the
cube; no multi-LUT neural blend.

**FX Bloom / Glow (filmic Pro-Mist, 2026-09-09):** Karis pyramid upsample is
re-weighted toward tight mips (chokes 4–5); Orton + Glow use highlight-gated
screen blend with optional R/B halation. FX tab chips map 1/16…1/2 densities via
`CinematicBloomProcessor` → slots [447]/[448]. Preview = export (same weights in
`bloom_filmic.h` for live GL and Stage C).

### LUT headroom map + stock pick defaults (2026-09)

**Headroom-aware pre-LUT map:** sample coords are identity on the whole [0,1]
range and soft-compress ONLY channels > 1 (`1/(1+(c-1))`, continuous at 1).
Mirrored in `shader_sources.cpp` / `apply_macro.cpp`. Default when a LUT is on.
`lutHighlightVibrancy` creative overlay remains (0 = headroom map only, − =
blend toward full Reinhard, + = highlight vibrancy boost).

**Stock pick defaults (runtime, no re-bake):**
- Black & White → intensity **1.0** + `lutBwForce` chroma lock (see below).
- Sony Log Custom / Official → intensity **0.55** + one-line hint
  ("Log→Rec.709 technical LUT — not a creative look; grade after").
- Other film-style packs (CC0 / Color Slide / RAZ Looks / Negative / Cinematic
  / …) → intensity **0.85** + light auto filmRolloff ≈0.12 when rolloff is 0.
- Generic / user cubes → intensity **1.0**, no auto rolloff.
Auto filmRolloff never overwrites a non-zero user value; FX Pro-Mist bloom is
untouched.

### Fujifilm film simulations (removed 2026-09-11)

The stock **Fujifilm Film Simulation** pack (`assets/luts/Fujifilm Film
Simulation/`: Provia / Velvia / Astia / Classic Chrome / Eterna / Pro Neg*)
was deleted from the APK. Prefer Fuji looks under **Fuji XTRANS III** (colour)
and **Black & White** (Acros / Neopan / FP-3000B, etc.). Runtime pick defaults
no longer special-case that folder.

### Black & White LUT intensity + chroma lock (2026-09-11)

Stock **Black & White** picks default to intensity **100%**.

When a B&W-pack LUT is active (`lutBwForce` slot **[450]**, inferred from a
path under `luts/Black & White/`), the intensity mix is:

`mix(vec3(srcLuma), lutColor, intensity)`

instead of the normal colour mix `mix(srcColour, lutColor, intensity)`.
Original chroma never returns when the user lowers intensity; toned mono
LUTs (sepia / cyanotype / gold) still keep their process colour at the LUT
end. Mirrored in `shader_sources.cpp` and `apply_macro.cpp` (preview =
export). Colour LUT intensity is unchanged.

Misfiled mono cubes (Ilford / T-Max / Lith / Platinum / Moody B&W looks /
Fuji Acros XTrans / FP-3000B / Polaroid 665 / …) were moved into
`assets/luts/Black & White/`. No-op Correction presets (WB / Dehaze / Grain /
Exposure / Lens Correction / NR / Sharpening / Reset / Vignette) were
removed.

### Preset import (universal container support)

`LrPresetConverter` turns a Lightroom preset into a 33³ cube. Accepted
containers: **`.xmp`** (XML), **`.lrtemplate`** (Lua), **`.dng`** (a preset DNG —
the same XMP packet in TIFF tag 700, lifted by `extractXmpPacket`), plus
`.cube`, **`.smcube`** (byte-copied; the app ships and exports this format and
could not previously re-import it), `.3dl` and HaldCLUT images (png/jpg/tif).

Verified 2026-09-07 with one Kodak preset supplied as all three Adobe
containers: the three produce **bit-identical** cubes and pixel-identical
renders of IMG_1919.JPG (`adobe_xmp/kodak/generated/`).

Three correctness fixes found while verifying:
- **Camera Calibration was dead.** The converter read
  `CameraCalibrationRedPrimaryHue`-style keys; Adobe writes `RedHue`,
  `RedSaturation`, `GreenHue`, `GreenSaturation`, `BlueHue`, `BlueSaturation`,
  `ShadowTint`. The whole panel silently did nothing on every preset.
- **Color Grading (PV11 / Lightroom 2020+) was unsupported.** Adobe mirrors the
  shadow/highlight hue+sat into the legacy `SplitToning*` keys, so the luminance
  sliders, the **midtone** wheel and the **global** wheel were being dropped
  silently on every modern preset. Now read, with `ColorGradeBlending` honoured.
- **HSL used hard hue bins.** A pixel took exactly one of the 8 bands, seaming
  wherever a hue crossed a boundary (the Kodak preset's Purple −63 made it
  obvious). Bands now blend between adjacent centres, weighted by saturation so
  near-greys are left alone, and Luminance moves toward white/black instead of
  scaling (which clipped).

White Balance is deliberately NOT baked (raw-domain; a LUT that guessed would
fight the editor's own WB control), and a warning names any spatial control
(Clarity/Texture/Dehaze/Grain/Vignette) the preset carries that a 3D LUT
structurally cannot represent.

**Coverage audited 2026-09-07** against the authoritative `LrDevelopController`
parameter list in [varunkumar/lightroom-mcp] and the LUT/profile formats in
[abpy/FujifilmCameraProfiles]. Three more classes of preset were found to be
silently unsupported and are now handled:

- **Black & White.** `ConvertToGrayscale` + the 8-channel `GrayMixer*` were not
  read at all, so a monochrome preset produced a COLOUR LUT. The mix runs before
  toning, so split-toned monochrome (sepia/selenium/duotone) tints the grey.
- **Legacy process versions (PV2003/PV2010).** Presets from before 2012 carry
  `Exposure`/`Brightness`/`Contrast`/`Recovery`/`FillLight` and a `ToneCurve`
  point curve — none of which were read, so such a preset baked an IDENTITY LUT.
  Each now falls back to the nearest 2012 control.
- **Named point curves.** `ToneCurveName2012 = "Medium Contrast"` (or Strong)
  with no explicit points lost its curve; ACR's classic control points are now
  synthesised.

Also verified: the Fujifilm repo's `.cube` files are **size 32** with no DOMAIN
lines (native parser accepts 2–256 ✓) and its HaldCLUT PNGs are **216×216**
(= level 6 → 36³ cube; `HaldClutConverter` accepts levels 2–64 ✓), so both
import as-is. A preset that merely REFERENCES a profile Look now logs a warning —
that table lives in a separate .dcp/.xmp and cannot be reproduced from the
preset text. `.dcp` camera profiles remain out of scope (raw-domain).

[varunkumar/lightroom-mcp]: https://github.com/varunkumar/lightroom-mcp
[abpy/FujifilmCameraProfiles]: https://github.com/abpy/FujifilmCameraProfiles

- Presets: XML per-tab cards via RawActionSerializer; bundled RAZDream
  seeded once (marker-guarded, deletable, never re-seeds). LUT cache paths
  inside presets are rewritten to the local filesDir and bundled cubes
  materialized on load.
- LUT import: .cube/.xmp/.lrtemplate/Hald PNG-JPEG-TIFF/.3dl parsed
  directly; .drx/.look/.dtstyle go through a HaldCLUT bridge — do NOT
  write direct parsers for those (proprietary/fragile).
- GL LUT sampling: tetrahedral, gamut-transformed around the sample when
  workspace ≠ LUT-authored space.
- LUT files: ASCII `.cube` AND binary `.smcube` (smol-cube, MIT) both load —
  native `parseCubeFile` sniffs the `SML1` magic and dispatches to `parseSmcube`
  (fp16/f32, 3ch 3D, filter=none). `.smcube` is ~4.5× smaller; convert with
  `scripts/cube_to_smcube.py <dir> --replace`. The bundled Sony Log / RAZ Looks /
  Sony PP7 categories ship as `.smcube`.
- **Stock creative library (peva3):** ~380 additional looks from
  [peva3/Lightroom-Presets](https://github.com/peva3/Lightroom-Presets) (MIT),
  baked via `LrPresetConverter` → `.smcube`. Folded into existing categories
  (Negative Color, Black & White, Color Slide, Cinematic, Correction, Landscape,
  Moody, Lifestyle & Commercial, Instant Consumer, Portrait) plus one new folder
  `Alternative Process`. `Negative Old` / `Negative  New` were consolidated into
  `Negative Color` (exposure ± variants deduped). Re-run:
  `BatchPeva3XmpConvertTest` then
  `python scripts/merge_peva3_luts.py --consolidate-negatives`.

### Film × paper print LUTs (2026-09-09)

Stock looks that model a **negative (or cine) stock printed onto a paper /
print-film**, not bare “film sim” cubes. Baked offline with upstream
[spektrafilm](https://github.com/andreavolpato/spektrafilm) (Andrea Volpato)
`BundleBuilder` — **sRGB → sRGB**, 33³, `lut_mode` (grain / halation / diffusion
forced off; optical FX stay as StudioRoom FX / sidecars if wanted). Converted to
`.smcube` for APK size.

**License decision (critical):**
- Upstream **engine** (Python / Spektrafilm-android C++) is **GPLv3** — **not**
  copied into this Apache tree.
- Film/paper **profiles and baked LUTs** are **CC BY-SA 4.0** with the author’s
  preamble (`SPEKTRAFILM_LICENSE.txt`). Derived cubes stay CC BY-SA 4.0.
- Attribution + license text ship under
  `assets/luts/_licenses/spektrafilm/` (no UI category — folder has no cubes).
- User-facing names never say “Spektrafilm” (same spirit as never saying
  “Lensfun” in UI).

**Reseated into existing categories** (no new folder):

| StudioRoom name | Category |
|---|---|
| Portra 160 Soft Print | Portrait |
| Portra 400 Warm Print | Portrait |
| Portra 800 Dim Print | Portrait |
| Pro 400H Pastel Print | Portrait |
| Ektar Ultra Print / Ektar Supra Print | Landscape |
| Superia Cool Print | Landscape |
| Vision3 250D / 200T / 500T Cinema Print | Cinematic |
| Vision3 50D Premier Print | Cinematic |
| Verita 200D Cinema Print | Cinematic |
| Gold 200 / UltraMax Minilab Print | Negative Color |
| Fuji C200 Everyday Print | Negative Color |
| Portra 800 Push1 / Push2 Print | Negative Color |

**Skipped as duplicates / non-bakeable:** Velvia / Provia / Ektachrome /
Kodachrome scan-path presets (already in Color Slide); Pro-Mist / matte /
bleach-bypass / wide-gamut glow FX (grain/mist don’t bake, or we already ship
equivalents); Neutral Clean Baseline.

Re-bake (dev machine, upstream checkout + bake stubs on `PYTHONPATH`):
`python scripts/bake_spektrafilm_print_luts.py --resolution 33`
then `python scripts/cube_to_smcube.py _tmp_spektra_bake/out --replace` and copy
into `assets/luts/<Category>/`.

- Dither (slot 28 `ditherStrength`, default 1.0) is PER-CHANNEL ordered Bayer
  (decorrelated R/G/B phases) in BOTH the GL shader and the CPU export (8- and
  16-bit) — breaks chroma banding on 8-bit log-JPEG gradients, not just luma.
  Pixel-affecting: run `razparity` after any change (hard rule #1).
- Sony Log LUTs split into TWO auto-discovered categories:
  - `assets/luts/Sony Log Custom/` — mathematically generated from Sony's
    published transfer functions (colour-science constants), NOT scraped:
    S-Log2/S-Gamut (A7 II PP7), S-Log3/S-Gamut3, S-Log3/S-Gamut3.Cine,
    S-Log2/S-Gamut3.Cine, and HLG/BT.2020 (PP10, relative SDR method).
    Regenerate via `scripts/gen_sony_luts.py <outdir>` (numpy); anchor-
    validated (18% gray decodes to Sony's 0.18 linear). NOTE: S-Gamut3
    shares S-Gamut primaries, so S-Log*/S-Gamut and S-Log*/S-Gamut3 are
    IDENTICAL — don't add both.
  - `assets/luts/Sony Log Official/` — Sony's own free "Look Profile for
    Resolve" .cube files (LC-709, LC-709 Type A, Cine+709 [65³], SLog2-709).
    Sony copyright, user-supplied; bundling is the developer's licensing call.
- Original creative looks: `assets/luts/RAZ Looks/` — first-principles
  tone/colour math (teal-orange, warm film, cool cinematic, bleach bypass,
  vintage fade), no third-party IP. `scripts/gen_creative_luts.py <outdir>`.
  Recommended workflow: technical LUT FIRST, then a creative look / grading,
  then export (sRGB/Rec.709).
- Output target is Rec.709 ONLY (no Display P3). The whole editor pipeline
  is 8-bit `ARGB_8888` / sRGB (preview = sRGB-tagged EGL surface, export =
  ARGB_8888), so a P3-output LUT would render WRONG (P3 values read as
  sRGB = desaturated/hue-shifted) and can't be delivered. Real P3 needs
  RGBA_F16 buffers + wide-gamut EGL surface (gated on
  `isScreenWideColorGamut`) + P3-tagged export + the CPU-export mirror
  (hard rule #1) — deferred as not worth it for a preview-only nicety.

### Lens correction: generic natural-vignetting fallback

Many lensfun profiles are distortion-only (no `<vignetting>` block) — e.g. the
Canon EF 28-105mm f/3.5-4.5 II USM used as a stand-in for an adapted Sigma
28mm f/1.8. `lfa_correct_rgba_f16` then logs `vig=0` and corners stay dark.
When the matched LENS has no vignetting calibration at all, the correction now
applies a cos⁴ natural-falloff compensation (r = tanθ in the modifier's
coordinate system, strength `clamp(2.8/f-number, 0.5, 1)`, gain cap ×2.5) and
logs `vig=2`. Profiles that DO carry vignetting data are untouched (`vig=1`),
including cases where their data fails to interpolate at the shot's aperture.
The "Adapted lens (ignore mount)" checkbox sits directly under the main lens
correction switch.

### Gallery Workspace: per-photo camera + lens profile (grid menu)

Three-dots menu → **Camera and lens profile** (subtitle shows the current
profile or "No profile set") opens `LensProfileSheet`: same profile database
and autocomplete catalogues as the editor's Lens Correction section, prefilled
from the sidecar or from the top suggestion for the photo's EXIF camera/lens,
adapted-lens toggle, focal override, and "also apply to photos with the same
camera and lens". Writes go through `SidecarOpsRepository.updateWorkspace`,
which changes only the decode workspace and preserves the photo's edits (a new
revision is pushed). The editor-side bulk apply (`createInitialSidecar`) is
NEUTRAL-only and must not be used for edited photos.

### Gallery Workspace: Export photos (bake sidecars via the batch processor)

The project card's export action and the project screen's export icon run
`ProjectPhotoExporter` (`feature/gallery-workspace/export/`): every photo's
sidecar (workspace incl. its lens profile, committed action stack → `RawAction`
via `SidecarActionEntry.toRawAction()`, masks, LUT chain) is fed to
`RawBatchProcessor.startItems`, a per-item variant of the folder batch that
passes each photo's own `WorkspaceConfig` (`prepareContext(workspaceOverride=)`
uses it verbatim, only re-materialising the profile DB dir) and resolves the LUT
chain per photo. The loop is shared with the folder batch (`runEntries`), so
prefetch, cancel, progress (`BatchProgress`) and the per-file EXIF watermark are
identical; a sidecar with a chosen lens profile stamps that lens name in the
watermark instead of the file's LensModel tag. Options = format (JPG default) +
watermark preset; quality/scale/folder come from Settings like the Export page.
Photos with no sidecar export untouched. Crop from the Export page is
session-only (not in the sidecar) and therefore not baked. The old
"export project bundle" dialog was removed from the card (bundle export code
remains for import compatibility).

### Export page: Share the saved photo

`SaveResult.Success.savedUri` (core/domain) now carries the written file's URI
(`AndroidFileController` fills it from `SavingFolder.fileUri` / the overwrite
target); `RawV3Coordinator.publishToResult` forwards it as
`ExportResult.Success.savedUri` (previously always null — the shadow-removal
post-pass fell back to "newest MediaStore image"). `RawEditorComponent.lastSavedUri`
holds the latest one (cleared when a new source opens) and the Export page
bottom bar shows a **Share** button beside Save while it is set
(`shareLastSaved` → ACTION_SEND chooser, MIME from the resolver).

### Export page: size chips, preset live preview, border default

- Dimensions: the "Use full resolution" checkbox is gone; **Original** (full
  res, default) · Social 1350 · HD lite 1600 · HD 1920 · 4K 3840 chips with
  small labels in a horizontally scrolling row; manual W/H fields appear when
  a non-Original size is active.
- **JPG/WebP grade at `targetLongSide`** (not full-res → linear downscale):
  GPU offscreen (and CPU fallback) runs the canvas-matched uber-shader at a
  working size derived from the export size option after crop/orient.
  Log line: `export path = GPU offscreen graded (canvas-matched) WxH`.
  Radius scaling follows the graded buffer's long side (preview contract at
  that output size). TIFF 16-bit remains full-res Stage C. Export proxy
  (1280) benefits from the same path — no more full-res grade then shrink.
- Saved presets on the Export page now refresh the photo canvas: the page shows
  a graded bitmap captured at hand-off, so on every `shaderParamsFlow` change
  while the page is showing (debounced 400 ms) `RawEditorContent` calls
  `renderExportProxy` — a 1280 px render through the real Stage A→C export into
  a cache file (a screen grab is impossible there, see GOTCHAS). Cosmetic
  crop/heal previews applied BEFORE a preset swap are not re-derived (edge case).
- Border default thickness = 0.075 (50% of `MAX_BORDER_FRACTION`).

### Gallery Workspace: loupe, metadata schema (v3), keywords, versions

Schema **v3** (`MIGRATION_2_3`) adds shot metadata + keywords to `photos`:
`cameraMake/cameraModel/lensModel/iso/apertureF/shutterSpeed/focalLengthMm/
gpsLat/gpsLon/keywords`, plus indices `projectId+cameraModel`,
`projectId+lensModel`, `capturedAt`, `addedAt`. `PhotoMetadataProbe.readShot()`
fills them at import; `GalleryProjectComponent.backfillExif()` re-probes pre-v3
rows in 200-row passes on open (ISO -1 = "probed, nothing there", so a file
without EXIF is not re-probed forever). `DATABASE_VERSION` was stale at 1 —
which had silently disabled the pre-migration backup — and is now 3.

What the schema unlocks, all on the one grid screen:
- **Search** — `GridQueryBuilder.appendSearch` ANDs space-separated terms over
  filename/camera/lens/keywords with escaped LIKE; top-bar field.
- **All photos** — `projectId = null` scope (`GalleryProjectComponent.ALL_PHOTOS_ID
  = -1`), entry at the head of the project list. Same screen, one predicate less.
- **Date pill** — floating day label for the first visible row (a true sticky
  header per day cannot be built over PagingData without walking loaded rows).
- **Info sheet** — reads only the grid row, so no file I/O.
- **Keywords** — `|tag|tag|` wire form (`core.database.model.Keywords`), per-photo
  or per-selection merge, with a reuse chip cloud.

**Loupe viewer** (`LoupeViewer.kt`): ⋮ → **View** opens a full-screen pager over
the SAME `LazyPagingItems` (inherits sort/filter/search) — a plain TAP on a tile
opens the RAW editor (owner decision 2026-09-07: "what is the point I clicked?";
the viewer as tap target was reverted the same day), thumbnail first then a
2048 px decode through ThumbnailWorker's own decode helpers (now `internal`);
pinch/double-tap zoom with paging disabled while zoomed, star/flag/reject,
keywords, versions, and Edit → the RAW editor. Culling no longer costs a Stage A
decode per frame.

**Versions**: `SidecarOpsRepository.revisionsOf/restoreRevision` expose the
sidecar history the editor already wrote. Restore pushes the current state first
(so it is itself undoable) and writes the restored macro as ONE synthetic action
card — an empty stack would render the photo as unedited.

**Selection**: drag-select is active in selection mode only — the tile releases
long-press there so the grid's `detectDragGesturesAfterLongPress` can own it
(a child that consumed the press stopped the drag from starting). Batch bar
gained keywords and camera+lens profile (`applyLensProfileToSelection`).

**Storage card** on the project list: thumbnails / copied originals / decode
cache as a stacked bar with device free space and a cache purge; totals come
from DB columns, only the caches are measured on disk.

### Gallery Workspace: trash, stacks, compare (schema v4)

Schema **v4** (`MIGRATION_3_4`) adds `photos.deletedAt` and `photos.stackKey`.

- **Trash with recovery.** Remove now soft-deletes (`BatchOperationsRepository.
  moveToTrash`); every grid query carries `deletedAt IS NULL` via
  `GridQueryBuilder.scopeClause`, and the trash view flips that predicate.
  Restore is free (thumbnails and sidecars are left in place); "Delete
  permanently" and the 30-day retention sweep both go through the original
  `stageRemove`/`commitRemove` path. Entry point: overflow menu → Deleted.
- **Stacks.** `stackKey` = filename without extension, lower-cased, written at
  import and backfilled on open. Collapsed mode groups on it and keeps the RAW
  (`MIN(sourceFormat)` + SQLite's bare-column rule), badging the tile with the
  pair count. Rows with no key yet group on their own id so nothing collapses
  into one bucket. Toggle: overflow menu → Stack RAW + JPEG.
- **Compare** (`CompareViewer.kt`): pinned photo on the left, candidate stepping
  through the grid on the right, **shared** zoom/pan (the point is the same
  region of two frames), rating/flag on the tapped pane.
- Search is debounced 250 ms (LIKE cannot use an index); sort/filter stay
  instant. Facet chips (camera / lens / keyword, most-used first) write the
  search box rather than adding a parallel filter.
