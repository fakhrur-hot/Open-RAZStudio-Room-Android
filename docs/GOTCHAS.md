# Gotchas, Preferences & Hard-Won Lessons

Read before touching the pipeline, masks, or anything user-visible.
Every entry here shipped (or nearly shipped) a bug.

## Owner preferences (product decisions — do not relitigate)

- **Color science**: warm per-channel WB gains beat perceptual correctness.
  CAT16/CIE-correct WB was tried and rejected ("ansel darktable look so
  awful"). Never "fix" color toward correctness without asking.
- **No library names in UI**: users see "camera and lens profile", never
  "Lensfun". Same spirit for other internals.
- **Batch = editor parity by construction**: when a headless path can't
  exactly reproduce an editor result, remove the option rather than ship a
  divergent approximation (this is why batch per-file Auto Expose no
  longer exists).
- Adjustment tabs auto-apply live (no Apply button) EXCEPT Mask.
- "AI Expose on Open" enabled hides the Smart Bright slider (Tone tab).
- The signing keystore is INCLUDED in the personal backup zip
  (`RAZStudioRoom-…-complete.zip`) — that archive must never leave the
  user's machines.

## Build / tooling traps

- **"Internal compiler error" / "Exception while generating code for … exportRawToGallery"
  is a StackOverflowError in the Kotlin JVM back-end**, not incremental-cache
  rot: `CoroutineTransformerMethodVisitor.addMonitorDepthToSuccs` recurses per
  basic block, and `RawV3Coordinator.exportRawToGallery` (~1000-line suspend
  fun) exceeds the daemon thread's 1 MB default stack. `--rerun-tasks` only
  "fixed" it by luck. `gradle.properties` now sets `-Xss16m` in
  `kotlin.daemon.jvmargs`; if it ever recurs, split that function rather than
  adding locals to it. (Ordinary Kotlin daemon threads inherit -Xss.)

- **Incremental Kotlin compiler**: "Internal compiler error" or "Exception
  while generating code for" on photo-editor → rerun that module's
  compile with `--rerun-tasks`. A failed assemble leaves the STALE APK in
  outputs — check timestamps before `adb install` or you'll "verify" old
  code (this happened; the fix appeared to not work).
- **Kotlin block comments NEST**: `/* … /* … */` inside KDoc (e.g. a glob
  `db/*.xml`) swallows the rest of the file with "Unclosed comment".
- Git Bash on Windows mangles `/sdcard/...` into `C:/Program Files/Git/...`
  for adb push/shell args — `export MSYS_NO_PATHCONV=1` first.
- adb over USB is flaky on big pushes ("failed to read copy response") —
  just retry.
- Native `__android_log_print` is NOT stripped by R8 — remove verbose
  native diagnostics before hardened builds (Kotlin Log is stripped by
  proguard-hardened.pro).

## Preview ↔ export parity (the #1 bug class)

- **Two LUT parsers existed, and only the native one understood `.smcube`.**
  The GL preview loads a LUT through native `raw_v3::parseCubeFile`, which
  sniffs the `SML1` magic and dispatches to `parseSmcube` (and also expands 1D
  `.cube`). The EXPORT went through Kotlin `RawV3LutStore.parseCubeFile`, an
  ASCII-3D-only reader, which threw `missing LUT_3D_SIZE` on the binary file,
  returned null, and Stage C ran with `lut=0x0x0 (off)` — the saved file lost
  the LUT while the editor showed it. 62 bundled LUTs are `.smcube` (all Sony
  log→709, DJI D-Log, RAZ Looks, Fuji sims), so every one of them exported
  wrong. `RawV3LutStore.parseCubeFile` now calls native `nativeParseLutFile`
  first and keeps the ASCII reader only as a fallback. `parseCubeCached` logs
  `LUT PARSE FAILED … export will save WITHOUT the LUT` at ERROR if it ever
  happens again. **Never add a second parser for a format the native side
  already reads** — route through JNI instead.

- **There is now a machine check: `razparity.exe`** (desktop, target
  `razparity` in `CMakeLists-desktop.txt`). It renders the same ShaderParams
  blob through the production uber-shader (headless ANGLE pbuffer) and
  through `apply_macro.cpp`, and fails when MaxAbsError ≥ 0.005 or
  PSNR ≤ 45 dB, dumping ×50-amplified diff PNGs. Run it before installing
  any pixel-affecting change to `shader_sources.cpp`, `apply_macro.cpp`, or
  `grading_uniforms.cpp`. Needs ANGLE DLLs at runtime (`RAZ_ANGLE_DIR`, exe
  dir, or a Chromium install — any Electron app dir works). Its first run
  (2026-08-29) caught three real divergences, all since fixed:
  1. GLSL `applyHslFull` had no neutral early-out — its RGB→HSL→RGB round
     trip mangled any pixel an earlier stage pushed out of [0,1] (drift up
     to 0.35 on saturated colors from a mere saturation/vibrance/dehaze move).
  2. CPU `smoothstep01`'s degenerate guard `b - a < 1e-6f` collapsed every
     inverted-edge call (legal in GLSL) into an inverted hard threshold —
     cg-shadows tinted the wrong luma zone on export, colorDensity was a
     silent export no-op, skintone/dust masks wrong.
  3. `grading_uniforms.cpp` (the "shared, parity-critical push") had drifted
     ~55 uniforms behind the preview's cached push — snapshot/headless
     renders silently dropped cg wheels, pushPull, hslFull, skintone, FX…
- A parity test where BOTH engines drop a param passes vacuously (that hid
  gap 3 behind gap 2 for colorDensity). After fixing a push/mapping, re-run
  the whole suite, not just the failing case.

- GL shader (`gles_renderer.cpp`) and CPU kernel (`apply_macro.cpp`) must
  stay op-for-op, order-for-order identical. The kernel was once parallel
  delta-sum vs the shader's sequential chain → brighter/flat/desat saves.
- Sequential-order also matters WITHIN an op set (dehaze atmospheric
  formula, Color Pop baked into Stage C apply_macro).
- Resolution-dependent ops must scale: clarity/sharpness USM radii scale
  by longSide/2560 in BOTH Stage B and C. À-trous texture scaling: add an
  octave, don't fractionalize dyadic steps (still TODO).
- If preview and export LOOK different but MAD says identical, suspect
  display color management (ZOrderOnTop SurfaceView isn't color-managed;
  window surface must be sRGB-tagged with GL_FRAMEBUFFER_SRGB disabled).
- UI overlays (mask selection tint) belong at the very END of the shader
  — anything earlier gets eaten by B&W LUTs / film sims.
- **B&W LUT intensity must not reintroduce colour.** Pack path under
  `luts/Black & White/` sets `lutBwForce` [450]; GL + `apply_macro` mix from
  `vec3(srcLuma)` toward the LUT (not from source colour). Colour packs keep
  the normal mix. Default pick intensity for that category is 100%.
- **Slot indices are an unchecked ABI.** Nothing links the Kotlin writer to
  the C++ reader, so a mismatch is silent wrong pixels, not a crash. Real
  case (fixed 2026-08-17): gradient blend modes are written at **391-394**,
  but `apply_macro.cpp` read **380-383** because the KDoc on
  `ShaderParams.gradTopBlendMode` said `[380]`. Slot 380 is `viewZoom`
  (default 1.0), so every CPU export silently forced blend mode "Fused"
  instead of "Solid". When touching a slot, check all THREE sites:
  `ShaderParams.toFloatArray`, `ShaderParams.fromFloatArray`, and
  `apply_macro.cpp::fromFloatArray` — plus `gles_renderer.cpp`. Trust the
  code, never the comment.
- **A uniform being declared and uploaded does NOT mean it is applied.**
  `uFilmicHlProtect` (slot [383], computed by AI Expose) is declared in the
  shader, has a uniform location, and is uploaded every frame — but no shader
  function ever reads it. `apply_macro.cpp` ignores it too, so the two sides
  already agree; "fixing" only the CPU side would CREATE a divergence. The
  highlight shoulder that IS real and IS mirrored is `filmRolloff` [207]
  (`applyFilmRolloff` / `applyFilmRolloffP`). Before mirroring anything,
  grep for a *read* of the value in a shader function body.
  **This has now bitten three times.** The other case: `kBloomDownFrag`
  declares six subject-exclusion uniforms (`uBloomDownExcludeSubject`,
  `uBloomDownSubjectEnabled`, `uBloomDownSubjectMask`, `uBloomDownSubjectRect`,
  `uBloomDownSobelMask`, `uBloomDownEdgeSnapThreshold`) and
  `gles_renderer.cpp:973-975` dutifully looks up and uploads them — but the
  shader body never reads any of them, so bloom subject-exclusion is NOT
  implemented. A byte-size diff against the offscreen copy looks alarming
  (1830 vs 4474 bytes) and is entirely comments + these inert declarations.
  Rule of thumb: `grep -c` the uniform name inside the shader string; **1
  occurrence means declaration only.**
- The GLSL lives in ONE place: `v3/shader_sources.{h,cpp}` (hoisted out of
  `gles_renderer.cpp`'s anonymous namespace, which is what previously forced
  `offscreen_save_renderer.cpp` to copy-paste it). Include the header; never
  re-inline a shader string.
- A value written by `toFloatArray` but not read back by `fromFloatArray` is
  silently dropped whenever `RawV3Coordinator` round-trips the blob
  (fromFloatArray → copy → toFloatArray) before export. `filmicHlProtect`
  had this bug.

## Mask system traps

- **The sidecar's workspace JSON only ever carried 13 of `WorkspaceConfig`'s 45
  fields** (until 2026-09-07). Every `lensfun*` field, `subjectDetectionEnabled`,
  `caCorrectionEnabled`, `aiColorEnhanceEnabled`, `useCameraColorProfile`,
  `colorFringingMode`, `filmProfile`, WB source, exposure shift … reverted to
  defaults on every project-photo reopen. Symptoms: "lens profile I set is gone",
  EXIF watermark lens blank, three-dots menu shows no profile, gallery paste's
  lens-correction category transplanted fields that were then dropped, Stage A
  cache fingerprint mismatch → silent re-decode without correction, and the
  `LENS-REPORT … lens=NONE (no lens selected)` line on a configured photo. The
  serializer now round-trips the whole class; when adding a `WorkspaceConfig`
  field, add it to BOTH `workspaceToJson` and `jsonToWorkspace` (Default-backed)
  — the macro side already does this per field, the workspace side did not.
- **Bundled LUTs exist on disk only after the LUT tab lists them.** The sidecar
  stores the absolute `files/lut_cache/<name>` path; on a fresh install / after
  Clear storage that file is missing until the LUT tab runs `resolveAssetToCache`,
  so a restored photo rendered with no LUT and `resolveTopmost` returned null
  silently. `RawV3LutChainResolver.resolveLutPath` now re-materialises a missing
  `lut_cache` file by basename from `assets/luts/*/` and `resolveTopmost` logs WHY
  it returned null (empty cubeUri vs unresolvable path).
- **A committed LUT card carries its LUT only in the TRANSIENT fields**
  (`lutSlot`, `lutEdited`, `lutCubeUri`, `lutIntensity`); `mergeWith` folds them
  into `lut1/lut2` only at compose time. `RawActionSerializer` writes them, but
  the sidecar's `macroToJson` wrote only the slots + derived `lutStack`, so a
  project-photo reopen restored a card labelled "LUT 1 · name" with NO LUT —
  Actions listed it, the canvas didn't show it, and the log read `LUT cleared`
  (2026-09-07). Both serializers now write the same four fields. Rule: the two
  macro serializers must stay field-for-field identical.
- **Project sidecar writes must be ONE complete detached snapshot.** The old
  design wrote a macro-only file after 500 ms on the store's writer scope and
  re-attached the action stack 700 ms later on the COMPONENT scope; closing the
  editor (back, export hand-off, destroy) within ~1.2 s cancelled the second job,
  and `restoreActionStackFromSidecar` treats an empty stack as "nothing to
  restore" → "all my edits are gone" + "copy edits to another photo does nothing"
  (copy reads the sidecar). Use `SidecarStore.flushSnapshotDetached` /
  `RawEditorComponent.flushProjectSidecarNow()`; it is now called on back, on
  `navigateToRawExport()` and in `doOnDestroy`.
- **`RawMaskStorage.deleteAll` at editor init wiped PROJECT masks too.** Mask PNGs
  are global (`filesDir/raw_masks/<actionId>.png`, UUID ids) and a project photo
  restores its Mask cards by path — every reopen turned painted masks into
  global adjustments. The wipe is now standalone-only (`projectContext == null`).
- **Regenerated thumbnails need a cache key, not just a new file.** The grid keyed
  Coil on `File(thumbPath)`; an edited thumbnail rewrites the SAME path, so the
  pre-edit tile kept coming out of Coil's caches. `PhotoGridRow.thumbGeneratedAt`
  (from `thumbnails.generatedAt`) is now part of the memory/disk cache key.

- `brushMaskLayers` (preview prop) already includes committed layers + the
  live bitmap; passing `brushMask` too duplicates the top committed layer
  as a phantom live layer → "second mask uses the first mask's shape".
  Keep `brushMask = null` at the main editor call site.
- Luma masks are parametric (GPU band), not bitmaps: subtracting objects
  from a luma base needs maskLumCombine=1, not bitmap ops. Inverting a
  luma mask BAKES its complement to a bitmap (bakeInvertedLuminance).
- Cross-category carve (fixed 2026-09): Luma/Chroma must NOT wipe the
  other category on Add, and their Remove must be enabled whenever ANY
  base exists (same as object rows). Bitmap base − luma uses
  maskLumCombine=2 (live in shader + export). Chroma carve arms
  ColorSelect subtract mode (tap to key colour out of the bitmap, or
  into the luma carve-set with combine=1). Object Add onto an active
  luma band must be additive and switch combine 0→3 (union) — legacy
  mode 0 makes luma win and hides the bitmap (looks like a no-op).
- Invert/Clear must treat luma-only selections as editable (no bitmap).
- Mask coverage lives in the bitmap's ALPHA channel; GL upload extracts
  alpha (`pixels ushr 24`) into R8 — don't read .r from Kotlin bitmaps.
- Compose won't recompose on same-reference bitmap writes — allocate a NEW
  Bitmap on fill operations (reference-equality state).
- Segmentation dropdown classes: enum `Buildings` but callback
  `onFillBuildingWall`; only DETECTED classes are listed.
- **The blue overlay and the adjustment slot are resolved by two separate
  code paths** — overlay = `overlayMaskRef` object identity within
  `brushMaskLayers`; adjustment = layer index derived from the count of
  committed masked actions in `RawV3ActionReplay.maskLayers`. If those two
  disagree, the user sees a blue selection that no slider affects. Keep the
  texture list and the params routing built from the SAME ordering — do not
  special-case the texture list (the old `isFreshMaskSession` shortcut
  uploaded the live bitmap to index 0 while its params went to slot N).
- `isFreshMaskSession` zeroing must SPARE the in-flight layer's slots.
  Zeroing all four killed every Mask-tab slider after a Cancel while the
  overlay still rendered.
- `isMaskEdit` must be true for luma-only masks too (`maskLumSpread > 0`):
  they have no bitmap and no fill job, so a bitmap-null check alone tags
  them `maskPath = null` → excluded from `maskLayers()` → they corrupt
  layer 0 via `composeMacro` instead of getting their own layer.
- `applyMaskLayers` must copy `maskLumTarget/Spread/Feather` per layer, not
  just tone fields — otherwise every luma band collapses onto layer 0.
  `maskLumCombine` [395] is still layer-0-only in both native parsers
  (carve mode on layers 1-3 is a known ABI limit).
- Committed mask cards must carry `maskLum*` (see
  `RawAdjustmentPanel.buildIndividualCards`) or Apply destroys the luma
  selection and a luma-base carve inverts to the removed region.

## Memory / OOM discipline

- Segmentation is LAZY and sequential with per-pass model unload; eager
  6-model chains once drove native heap to 5.7GB → lmkd kills. BiRefNet is
  double-gated: system RAM AND Java-heap headroom ≥340MB (batch fills the
  heap → DeepLab fallback, 40s→4s). ONNX CPU arena is disabled so memory
  actually returns after inference.
- **OrtSession / TFLite close vs run MUST be gated.** Coroutine cancel and
  `withTimeoutOrNull` do NOT interrupt blocking `OrtSession.run`. Calling
  `session.close()` while run is in flight → SIGABRT (FORTIFY mutex on
  destroyed lock, thread `RawV3.CityscapesSeg`) or SIGSEGV NPE (thread
  `raw-seg-u2net`). All mask/heal ONNX+TFLite holders use `OrtSessionGate`
  (`raw/segmentation/OrtSessionGate.kt`): close is deferred until in-flight
  hits zero; closed-session exceptions are soft-failures. Never reopen the
  old "drop ref without release on timeout" leak as a substitute.
- AHardwareBuffers: never close while a Default-dispatcher bake may still
  read them (UAF SIGSEGV in runStageBApplySpatialToAhb) — defer behind the
  bake-running gate.
- 42MP FP16 RGBA ≈ 336MB; geometry warps allocate one extra full buffer —
  fine transiently, don't stack more.

## USB OTG / SD Card Browser traps

- **Generic OTG mass-storage automount is not universal.** Don't assume
  `StorageManager.storageVolumes` will ever see a USB card reader — many
  phones never wire vold up for USB-host-mode OTG at all, regardless of
  filesystem (confirmed via `sm list-volumes` + `/proc/mounts` showing zero
  volume for an attached reader, even though the kernel had `exfat` in
  `/proc/filesystems`). Third-party file managers (CX File Explorer, etc.)
  work around this by claiming the raw USB device directly via `UsbManager`
  — that's why `feature/sd-card-browser` has both a SAF path and a raw-USB
  `libaums` path; expect the raw path to be the one that actually fires.
- If a card reader gets auto-launched into a DIFFERENT app on every plug-in
  with no chooser prompt, the user previously picked "always" for that app —
  `Settings → Apps → <app> → Open by default → Clear defaults` resets it.
  StudioRoom must have both a `USB_DEVICE_ATTACHED` intent-filter AND a
  matching `usb-device` `device_filter.xml` resource to even appear in that
  chooser; missing either means it silently never competes.
- **libaums (`me.jahnen.libaums:core`) is FAT12/16/32 only — no exFAT.** A
  modern SDXC card (≥32GB, the common default) will fail with "no readable
  partition" even though USB communication and partition-table parsing both
  succeeded. Windows' own formatting tools (`format`, Disk Management,
  `Format-Volume`) refuse FAT32 on volumes >32GB (hardcoded, not a real
  filesystem limit) — there is no clean native-Windows path to reformat a
  large card down to FAT32 for testing.
- **Never call blocking USB I/O (`UsbMassStorageDevice.init()`, any
  `UsbFile` read) inline inside a Decompose `componentScope`-collected
  flow** — that scope runs on `Dispatchers.Main`. It silently freezes the
  UI with zero exception and zero log output (looks exactly like "nothing
  detected") until something else (a timeout, a detach) breaks the stall.
  Always `launch(dispatchers.ioDispatcher) { }` around the libaums call, and
  wrap it in `withTimeoutOrNull` so a genuinely stuck reader still surfaces
  an error instead of hanging forever.
- Sidecar path traps (shared with the ML_6D orchestrator, see
  docs/FEATURES.md): real firmware directory is lowercase `ML/data/SHOTS/`
  (not `DATA`), and the sidecar filename is the CR2 *basename* + `.ml6d`
  (not the full filename + `.ml6d`) — both bit the same code twice
  (`MlSidecarParser.kt` and `feature/sd-card-browser`'s `FileOpener.kt`)
  before being caught by diffing against a real card.

## Canon Sync field notes

- Unpaired-GUID sentinel (all-zeros AND `…ffff`) must be replaced with the
  persisted hostGuid or the camera never shows its pair prompt.
- KeepDeviceOn 0x911D every 3s is the only ping the 6D respects
  (PCHDDCapacity returns OperationNotSupported and doesn't reset the idle
  timer).
- Solid heartbeat + drops anyway = camera radio fell off the AP (6D 2.4GHz,
  ch 1-11 only). Not fixable in app; reconnect is hardened instead.
- `raceDiscovery` re-discovers and can override a tapped camera's IP —
  the tapped IP is a hint (fine single-camera).

## Misc editor lessons

- **LUT Creator's identity-shrink was an absolute pseudo-count, so the fitted
  LUT was nearly a no-op.** `PairLutFitter` blends the fitted cubic toward
  identity by `w = support / (support + K)` where `support` is the raw sample
  count at a 33³ node. With `K = 200` and only ~55 k samples per pair spread
  over 35 937 nodes, per-node counts are single digits → `w ≈ 0.02`, i.e. 98 %
  identity. It was also inverted in spirit: MORE colour coverage in the pairs
  meant fewer samples per node and a WEAKER LUT. `K` is now
  `SUPPORT_FRACTION × (inliers / occupied nodes)`, measured before the support
  blur (the blur bleeds into empty neighbours and would drag the reference to
  zero). Verified with an offline port of the file against a known grade:
  recovery went 1 %→75 % (full-gamut pairs) and 35 %→83 % (photo-like). The
  mapping DIRECTION (before→after), the 20-term cubic solve and the red-fastest
  `.cube` ordering were all already correct — don't "fix" those.

- **PixelCopy captures the ZOOMED canvas.** The editor's pinch zoom/pan is
  applied GL-side by scaling the *window viewport* in
  `GlesRenderer::renderFrame` (`setViewTransform` → `viewScale_`), not by a
  Compose `graphicsLayer`. So the Export handoff's `PixelCopy` — which reads
  those window pixels — handed the Export page a zoomed crop, and the
  centre-crop to `imageAspect` that follows (it assumes a letterbox-fit photo)
  compounded it. `onExportToEditor` now pushes identity, waits a frame,
  captures, then restores the gesture. Do NOT "fix" this by resetting the
  Compose `canvasScale`/`canvasOffset`: their `LaunchedEffect` only re-pushes
  on change, so a silent reset strands the renderer at identity until the next
  pinch. Note `uViewZoom` / `viewZoom` [380] and `viewPanX/Y` [381/382] are
  DEAD — declared in `ShaderParams` and `gles_renderer.h`, never read by any
  shader; the viewport path is the only real zoom.

- **Settings drawer open at launch and BACK exits the app** (2026-09-04):
  `rememberDrawerState` is *saveable*, so a drawer left open when the process
  died (e.g. `adb install -r` mid-session) was restored OPEN and would not
  dismiss. `MainContent` now uses a plain `remember { DrawerState(Closed) }`
  and `MainDrawerContent` adds a `BackHandler` fallback beside the predictive
  observer. Related hardening: `setContentWithWindowSizeClass` derives the size
  class from `LocalConfiguration` dp and logs `WINDOW-SIZE-CLASS: … (library
  said …)` — docked side-pane mode (`isSheetSlideable=false`) has no back
  handler, so a wrong size class would reproduce the same symptom.

- Straighten/rotate: bake on slider RELEASE; live feedback via
  graphicsLayer rotation with cover-scale (per-tick bitmap rotate
  flickers). GraphicsLayer pan needs ×scale.
- Heal pinch-zoom must be centroid-anchored + clamped (center-zoom flew
  off-screen); brush→source mapping is zoom-independent.
- Speckle at 100% zoom in saves = sensor noise the preview downscale
  averages away — not a pipeline bug.
- Dehaze amplifies shadow noise via 1/t stretch — luma-robust dark proxy +
  shadow-protect gate exist in BOTH GL and CPU paths.
- FX bokeh must grade-transfer per fragment (blur of the UNGRADED base
  mixed into graded pixels shifts background color).
- EXIF date for RAW comes from LibRaw `other.timestamp` (time_t →
  strftime), not `other.desc`.
- Workspace-default cards (`_ai_color_enhance`, `_ae_baked`, …) are created
  at open; a UI toggle that only FLIPS such a card dead-clicks whenever the
  card wasn't created (legacy sidecar, decode hiccup). Toggles must create
  on demand when missing (see setAiColorEnhance) — but never auto-create at
  OPEN over a legacy card, or old edits change look on reopen.
- **`UserMacro` is at the dex register ceiling.** It has ~224 constructor
  params; `copy$default`'s `invoke-*/range` currently needs **247** out-regs and
  the count byte overflows at **256** → `VerifyError: invalid arg count (0) in
  invoke-*/range` / `expected 0 argument registers` at RawEditorComponent class
  load (crash on opening the editor). Adding ANY flat field to `UserMacro` risks
  re-breaking this. **Add new fields as a nested holder data class** (see
  `MaskToneRegions`, `ColorWheel`) so `UserMacro` gains 1 param, not N. When out
  of headroom, consolidate an existing flat group into a holder (next target:
  the 18 HSL `hsl*Hue/Sat/Lum` fields).
- **On-device verify is NOT a reliable gate for the above.** `adb shell cmd
  package compile -m verify` and lenient OEM ART (older test devices) can return
  `Success` on a build that VerifyError-crashes on stricter ART (e.g. Android 16
  / SDK 36). **Verify deterministically:** unzip `classes*.dex`, run
  `build-tools/*/dexdump.exe -d classesN.dex`, and check every method's `outs`
  stays `<256` (max is `UserMacro.copy$default`).

- **`UserMacro.mergeWith` booleans must OR, never "delta wins".** The
  preview/export fold (`RawV3ActionReplay.composeMacro`) merges visible cards
  newest-first, oldest-LAST — so the open-time `_ai_color_enhance` card (or any
  Tone card) is merged after the FX card and, with `flag = delta.flag`, reset
  `bloomExcludeSubject`/`subjectPopEnabled` to false every rebuild. Symptom:
  Bloom "Protect subject" was plumbed end-to-end (checkbox → slot 238 →
  `uBloomExcludeSubject` → shader mix → CPU mirror) yet never protected
  anything. Toggling OFF is still correct with OR because `replaceTabCards`
  removes the tab's cards, so no visible card asserts `true`. Also: a field
  missing from the `UserMacro(...)` constructor call inside `mergeWith` is
  reset to default on every merge (`fxBlurExcludeSubject` was), and a field
  missing from `RawActionSerializer` is lost on autosave/reopen
  (`bloomExcludeSubject`, `subjectBloom` were). When adding a UserMacro field,
  touch all three: `mergeWith`, `RawActionSerializer` write+read, and the
  per-tab card in `RawAdjustmentPanel.buildIndividualCards`.
- Subject detection has ONE source: `RawV3Coordinator.segmentationMasks`
  (BiRefNet/U2Net → guided-filter refined). The Mask tab's Subject/Background
  fills, FX Bloom protect, Bokeh, per-segment Vignette/Gradient and the CPU
  export all read it. Every CPU consumer must take the mask from
  `RawV3SegmentationMasks.bestMask()` (refined 1024px image-aspect mask when
  present, else raw 320²) — the same texture the GL preview uploads. The
  native Stage B/C entry points take `subjectMaskSize` (width) + `subjectMaskH`
  (0 = square) for this; passing the raw 320² mask to export while the preview
  sampled the refined one gave a softer subject edge on save (fixed 2026-09-04).
  The batch exporters (`RawV3Exporter`, `RawV3Export`) still pass NO subject
  mask at all — per-segment effects are inert on that path.
- **An EMPTY subject mask is not a subject.** BiRefNet only runs with ≥4 GB
  free RAM (portraits: relaxed to ~3.2 GB when a cheap face probe finds faces)
  and has a 90 s cap it blows on mid-range phones (measured: skipped at 3.4 GB
  free, timed out at 103 s on the 12 GB Infinix). The chain is now
  BiRefNet → **U2Net general saliency (320², ~1 s, `u2netSubjectMasks`)** →
  DeepLab PERSON-class union, and the publish log names the source. Empty
  BiRefNet/U2Net mattes are NOT published (so they can't block the next
  fallback). Before the U2Net step existed, every mask on that device was the
  person-only union, which is blank for any non-person subject. A blank-but-
  "ready" mask made Bokeh blur the whole frame and Bloom "Protect subject"
  protect nothing. Gate every subject consumer on
  `RawV3SegmentationMasks.hasSubject` (coverage ≥ 0.5%) via `gatingMask()`;
  the coordinator logs `coverage=…% hasSubject=…` and the mask SOURCE
  (BiRefNet / U2Net / DeepLab) at publish — read that line first when "the
  subject isn't protected". Export Heal "Remove subject" uses the same
  BiRefNet→U2Net matte → RAZGAN (separate from "Remove people" = DeepLab).
- **Compose gesture lambdas go stale under `pointerInput(Unit)`.** The block
  captures the FIRST composition's callbacks; a callback that closes over
  `macro` then re-emits the old field values forever (colour-wheel disc drags
  reset Strength). Read callbacks via `rememberUpdatedState` inside
  `pointerInput(Unit)`, or key the modifier on them.

## Where knowledge lives

- `CLAUDE.md` + `docs/` (this set) — cross-agent.
- `.kiro/specs/<feature>/design.md|tasks.md` — feature plans (existing:
  film-simulation-pipeline, unified-stage-b-renderer,
  watermark-exif-alignment, lensfun-manual-selection).
- `CHANGELOG.md` — per-release deltas.
- Claude Code private memory (per-machine) holds the same facts with
  session history; these docs are the shareable distillation.
- **Non-RAW Stage A has TWO entry points.** `openRawFile` (editor) and
  `exportRawToGallery` (headless: Gallery "Export photos", folder batch, sync)
  both synthesise A.tif for JPEG/PNG. Until 2026-09-07 only the editor path ran
  the lens profile + Rayxie step, so a JPEG exported without a prior editor
  open silently lost lens correction (the editor's own export cache-hit the
  corrected A.tif, hiding it). Both now call `synthStageAWithLensPipeline` —
  keep any future non-RAW import step inside that helper.
- **Every save/export must be modal.** Owner rule (2026-09-07): while a file
  is being written the user sees the app's `LoadingDialog` (core/ui) — spinner
  or N/M progress — and cannot back out or navigate; the only exit is its
  Cancel prompt. Conventional editors already did this; the RAW Export page
  save (`cancelSaveToGallery`), the folder batch (hidden while the screensaver
  overlay is up) and Gallery "Export photos" (`ProjectPhotoExportBlocking`) now
  do too. New save flows must reuse `LoadingDialog`, not an inline progress bar.
- **Export-page Border used to save at preview size.** The page pre-composed
  the frame onto the ~1280 px preview and passed it as `overrideBitmap`, which
  takes the direct-encode path (measured: `exportBitmapDirect OK 1482x1074`).
  Border is now `ExportOptions.borderThickness/borderColorArgb`, framed by the
  coordinator after the full-res watermark burn (photo → watermark → border);
  bordered PNG-16/TIFF-16 route through the F16-bitmap encoder like HEIC-16
  because the native file encoder cannot grow the canvas. Only the heal/cloud
  "bake" override legitimately saves at preview size.
- **razparity CAN be built on this machine** (the "no toolchain" note was
  wrong). `tools/build-razparity.bat <build-dir>` does it: VS 18 Community's
  bundled CMake + Ninja + clang-cl, driven from a shim `CMakeLists.txt` that
  sets `RAZ_CPP_ROOT` and `include()`s `CMakeLists-desktop.txt`. Two traps:
  `vcvars64.bat` dies with "\Bitvise was unexpected at this time" on this
  machine's PATH, so the script resets PATH to a minimal one first; and the
  Android SDK's CMake 3.22 is too old for the VS 18 generator (use the VS one).
  At runtime ANGLE comes from `RAZ_ANGLE_DIR` — VS Code ships usable DLLs
  (`C:/Program Files/Microsoft VS Code/<hash>/`). 2026-09-07 run: 20/20.
- **The parity suite had NO tone-curve case until 2026-09-07**, which is how
  `apply_macro.cpp` came to be missing the shader's luma-curve branch entirely
  (a photo curved in Luma mode exported with per-channel desaturation the
  preview never showed). Two cases now cover it. When adding a case that needs
  a curve, set `TestCase::toneCurve` — there is NO params slot that enables the
  curve (GL reads `GradingInputs.toneCurveReady`, CPU reads
  `ApplyMacroParams::toneCurveLut`), and slot [208] is ambiance, which is
  blur-dependent and deliberately out of the harness's scope.
- **Lens DB "in-place patches" must be scripted.** The Samyang 12mm AF
  vignetting (PR #2004) was documented as patched into `mil-samyang.xml` for
  months but never was — nothing checked. `scripts/lensfun_sync.py` now owns
  the master re-sync AND the patch list; run it, bump `DB_VERSION`, done. Also:
  the native parser keeps only the FIRST `<model>` of a lens (cameras keep a
  second as alias), so upstream alias PRs do nothing here and model renames
  change matching — vet those by hand.
- **Nothing can be captured from the GL view while the Export page is up.**
  `RawV3GlSurfaceView` uses `setZOrderOnTop(true)`, so it must be INVISIBLE
  behind the page, and hiding it fires `surfaceDestroyed`, which RELEASES the
  renderer. `PixelCopy.request` then THROWS IllegalArgumentException ("Surface
  isn't valid") rather than returning a failure code — that crashed Save on
  2026-09-07 — and the native snapshot fails its eglMakeCurrent. Both paths in
  `captureGradedCanvas` are now guarded (surface validity + runCatching). For a
  preview that reflects a preset change on the page, use
  `RawEditorComponent.renderExportProxy` (1280 px through the real export
  kernel, direct to a cache file) — slower, but preview == save by construction.
- **Never `recycle()` a bitmap a composable may still be drawing.** Compose
  draws on the next frame from whatever the state held; recycling in a setter or
  a `DisposableEffect` races that draw and crashes with "Canvas: trying to use a
  recycled bitmap" (hit twice 2026-09-07: `setGradedPreview` replacing the Export
  page preview, and the loupe pager dropping a page). Drop the reference and let
  the GC reclaim it; only recycle bitmaps that never reached a composable.
- **Tile tap in Gallery Workspace = open the editor**, not a viewer. A tap that
  lands on a rating screen reads as a dead end to the owner; culling lives under
  ⋮ → View and Compare. Its bottom bar is icon-over-label (six actions must fit
  360 dp; text buttons wrapped and pushed Edit off screen).
- **White balance: three separate defects, fixed 2026-09-07.**
  1. *The model was not a temperature.* `applyWbTintP` was a flat
     `R *= 1 + w*0.15, B *= 1 - w*0.15`, so the same slider value produced the
     same gain at 2500 K and at 9000 K — "Kelvin" on the UI meant nothing.
     It now derives the actual white point (CIE D-series ≥ 4000 K, Planckian
     below) and adapts reference ÷ target. Mirrored in `apply_macro.cpp`;
     razparity 20/20.
  2. *The weaker source won on RAW.* Stage A inverse-looks-up the shot's own
     `cam_mul`, but the Kotlin side only used it `if (_asShotKelvin == 0)`, and
     the parallel ExifInterface probe usually landed first — so a maker's
     nominal "ColorTemperature" tag overrode the real one. LibRaw is now
     authoritative. Stage A also no longer reports `WBCT_Coeffs[0][0]` (the
     body's lowest-CCT preset row, a constant) as if it were the shot's CCT.
  3. *Kelvin was shown for files that have none.* A developed JPEG/PNG has no
     as-shot WB, so any Kelvin figure is invented. Non-RAW sources now get a
     relative −100…0…+100 Temperature slider (`isNonRawSource` in
     `RawColorTab`); RAW keeps absolute Kelvin. The stored macro field is still
     a Kelvin delta, so no ABI or sidecar change.
  Tint stays "positive = green" INTERNALLY (Adobe's sign is the opposite);
  flipping it would silently invert every existing sidecar, so the preset
  importer/exporter converts at the boundary instead.
- **FX-tab Glow / Bloom are filmic Pro-Mist style (2026-09-09).** Karis
  upsample chokes mips 4–5 and biases toward mip1/mip2 (`bloom_filmic.h`);
  Orton + FX Glow use highlight-gated SCREEN blend + R/B halation offsets
  (slots [447] mistTightness, [448] mistHalation). Additive glow burn is gone.
  `CinematicBloomProcessor` maps density chips (1/16…1/2) onto those params.
  Anything new that samples `uBloomTex` must stay on `bloomTexNeeded` /
  `bloomPassDirty_` (includes mistTightness).
- **FX-tab Glow did nothing in the PREVIEW (worked in the save).** Glow samples
  the same Karis bloom pyramid as Orton (`uBloomTex`), but `gles_renderer.cpp`
  gated both the pyramid pre-pass and the texture bind on `ortonActive` alone —
  a comment there even asserted "the shader gates on uOrtonStrength so a 0 bind
  is never sampled", an invariant the later OpenShot FX Glow port broke by
  sampling `uBloomTex` on its own. With Bloom at 0 the unit was unbound, Glow
  read black and added nothing, while Stage C export already honoured
  `ortonOn || fxGlowOn` and DID glow — a silent preview↔save divergence.
  Fixed via `bloomTexNeeded = (ortonActive && bloomRadius > 0) || glowActive`,
  and `fxGlowStrength` now marks the bloom pass dirty (otherwise the first glow
  edit had nothing to sample). **Anything new that samples a shared FBO must be
  added to that FBO's gate AND its dirty check.**
- **Optical Spread is off unless Amount or Halation is above 0.** Direction
  does not change the base Pro-Mist oval. The contribution samples happen
  only inside that branch (GL and `apply_macro.cpp`). JPEG share must not
  fold this into dual reconstruction.
- **LUT ADV Film Response (Recovery / Fill Light) did nothing on the canvas.**
  Live preview still uses `GlesRenderer::pushUniforms()` (cached locations);
  export/snap uses `pushGradingUniforms()` which already uploaded
  `uFilmRecovery` / `uFilmFillLight` / mono / grayMix. The live path never
  looked those uniforms up or set them — sliders moved the float blob (and
  `[GL-ADJ] FILM` logged values) but the shader kept zeros. Fixed by wiring
  the four film uniforms into `cacheLocations` + `pushUniforms`. When adding
  a grading uniform, update **both** push paths (or delete the live duplicate
  and only call `pushUniformsForProgram`).
  **Guard (2026-09):** `kParityCriticalUniformNames` in `grading_uniforms.h`
  lists the drift-prone names (Film Response, CG wheels, …). After
  `cacheUniformLocations`, `logLiveGradingUniformParity` runs once and
  `LOGE`s any name the live program cannot resolve (tag `RawV3.Gles` /
  `GradeParity`). Do **not** replace live `pushUniforms` with per-frame
  `glGetUniformLocation` — keep cached locs for speed; extend the name list
  + both push sites instead.
- **The dex register guard watched ONE class, so the crash shipped from the
  other.** `UserMacro` had a build guard; `ShaderParams` did not — and
  `ShaderParams` had quietly crept to **255 of 256** registers. Four new
  film-response fields tipped it over and ART rejected `RawEditorComponent` on
  load: "photo cannot load to RAW editor", surfacing as
  `IllegalStateException: Can't process the event due to a previous failure`
  with a `VerifyError` cause. The guard now covers both classes (see the
  `guarded` map in photo-editor/build.gradle.kts) and PRINTS the count on every
  build, so the margin is visible instead of discovered by crashing. **Any data
  class near the ceiling belongs in that map — guarding one is not guarding the
  problem.**
  Relief comes from packing, never from raising `failAt`: sixteen colour-grading
  wheel floats became one `cg: FloatArray(16)` (−15) and the eleven film-response
  values one `film: FloatArray(11)` (−10 vs flat). Wire SLOTS are unchanged, so
  the native side — which reads by slot number — needed no edit at all. Current:
  UserMacro 232/256, ShaderParams 241/256.
- **Slot `[199]` is `shadowsBackground` sole owner.** `purpleFringeMode` lives
  at **`[449]`** (FLOAT_COUNT 450). A prior clash wrote Strong then overwrote
  the same index with shadows, so live purple-fringe never saw Strong while
  export also lacked the pass. When touching a slot, census Kotlin + both
  native parsers — never trust the KDoc alone.

- **CA + ColorShift dual-on order (locked 2026-09-09):** ML/user cubic CA
  (slots 352/353) + ColorShift → **ColorShift then CA** on the source sample
  (GL early main + Stage C pre-passes). ColorShift alone is the FX film-CA
  look (OpenShot RGB split) — do not force ColorShift→CA onto ColorShift-only,
  and do not drop ColorShift when cubic CA is on.

- **Orton sky attenuation is preview=export:** GL `uBokehAttenuation` +
  blue-excess gate must be mirrored in `apply_macro` with the Cityscapes
  max(sky,terrain) plane passed into Stage C (same letterbox rect as subject).

- **JPG/WebP grade at export size, not full-res → downscale (2026-09-09).**
  Preferred path: `computeExportGradeWorkDims` → GPU `renderGradedOffscreen`
  at that W×H (Stage A subsampled from mmap; bloom-at-work-size preserved).
  Log: `export path = GPU offscreen graded (canvas-matched) WxH` with W×H ≈
  target after crop. **Radius contract:** bloom tent and shader USM
  (`textureSize`) scale to the **graded buffer's** long side — a 2048 export
  looks like a preview of a 2048-long image, not like full-res grade then
  Lanczos. Do not reintroduce full-res GPU grade for sized JPG/WebP (OOM +
  slow). 16-bit TIFF stays full-res CPU Stage C. CPU JPG fallback must
  `downsampleStageATiffToSize` before Stage C — never rely on ignored
  `targetWidth` in `runStageC`.

- **GPU offscreen export must upload ALL brush-mask layers (fixed 2026-09-09).**
  The grade-at-`targetLongSide` JPG/WebP path (`OffscreenSaveRenderer::
  renderGradedToRgba8`) initially only wired subject mask + ShaderParams;
  units 3/5/6/7 stayed black and `uBrushMaskEnabled=0`. Live preview showed
  multi-layer Mask-tab edits; the saved file dropped every bitmap mask
  (luma-only layers still worked via params). CPU Stage C already got
  `decodeMaskLayers` — the GPU path must get the same bundle. Log:
  `brush masks uploaded n=N WxH bits=0x…` + Coordinator `brushMasks=N`.
  Never add a new export texture path without the Mask-tab brushes.

- **razparity can pass while testing nothing.** Its `kParamCount` was still 410
  while Kotlin had reached 435: a case writing `a[436]` overran
  `float blob[kParamCount]` on the stack, and every slot above 409 read back 0 —
  both renderers no-op'd and therefore "agreed". Three new cases reported
  metrics BYTE-IDENTICAL to `neutral-passthrough` and still said PASS. **If a
  case's maxAbs/meanAbs/PSNR exactly match the neutral case, it is measuring
  nothing** — check `kParamCount` and the slot wiring before believing it.
- **Android phone DNGs looked PINK — LibRaw runs no DNG opcodes.** Camera2 DNGs
  ship their lens-shading correction as `OpcodeList2` GainMaps (one per CFA
  phase) and leave the Bayer data uncorrected; LibRaw only executes opcodes when
  built against the Adobe DNG SDK, which we do not bundle. Measured on the
  owner's Infinix X6873 file: gains reach 3.0–3.2× in the corners and the
  per-plane means DIFFER (R 1.589, G 1.661/1.653, B 1.617), so dropping them
  left red relatively strong away from centre — neutral in the middle, R/G ×1.08
  and brightness ×0.31 in the corners, i.e. dark magenta edges. `v3/dng_gainmap.cpp`
  now parses OpcodeList2 and applies the maps to the mosaic right after
  `unpack()`, before every other RAW-domain pass. It gains the BLACK-SUBTRACTED
  signal and re-offsets — gaining the pedestal would lift the black point.
  **`MapSpacing`/`MapOrigin` are NORMALISED to the opcode's own rectangle, not
  pixels.** A 17-point map spans its rectangle with spacing `1/16 = 0.0625`. The
  first cut fed a raw pixel row straight into `(y - originV) / spacingV`, so
  `3000 / 0.0625 = 48000` clamped to the last index and EVERY pixel received the
  bottom-right CORNER gain: a flat ~3× lift that clipped everything above ~24%
  to white, with no shading correction at all. That is what the owner saw as
  "hazy magenta" AFTER the maps were wired in. Normalise to the rectangle
  first: `((y - top) / (bottom - top) - originV) / spacingV`. Opcode rectangles
  are also ACTIVE-AREA coordinates while `raw_image` covers the whole sensor
  plane, so shift by `sizes.left_margin`/`top_margin` (which also keeps the CFA
  phase right when a margin is odd).
  Matrix selection was investigated and CLEARED: this file writes
  `CalibrationIlluminant1 = D65 (21)`, `2 = Standard A (17)` — the REVERSE of
  the usual convention — but the logged `rgb_cam` reproduces ColorMatrix1 to
  0.0002, i.e. LibRaw already picks the D65 matrix here. Don't re-chase it.
- **Log every outcome of an optional native pass, not just the failures you
  expect.** `applyDngGainMaps` logged only when maps were found-but-unapplied,
  so a run that found nothing was indistinguishable from a build where the code
  never shipped — that ambiguity cost a whole debugging round. Every early
  return now logs.
