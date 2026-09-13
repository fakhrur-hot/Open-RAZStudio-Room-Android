# RAW Pipeline v3 (Stage A → B → C)

The editor never edits the RAW directly. Everything flows through three
stages sharing one intermediate file.

## Stage A — decode (native, `v3/stage_a.cpp`, entry `v3_jni.cpp`)

RAW file → LibRaw unpack → optional pre-demosaic AI (HDR-recovery U-Net,
shadow-recovery U-Net, both TFLite-style .bin run natively) → demosaic
(default RAZAmaze dual AMaZE+VNG blend with auto contrast threshold; RCD and
LibRaw algorithms selectable) → post-demosaic steps in order:

1. **Lensfun lens correction** (devignette in linear light → distortion +
   TCA warp with auto-scale) — see docs/FEATURES.md. Same placement as
   RawTherapee (post-demosaic, pre-everything).
2. CLAHE highlight boost (when AI Level Reconstruct on).
3. LMMSE+USM enhancement / guided filter (AI Enhance, Route B only).

Output: **sRGB-encoded FP16 RGBA BigTIFF** (`A.tif`, cached per-SHA under
`cacheDir/raw_v3/<sha>/`). All later stages read this file — preview and
export therefore share identical upstream pixels. EXIF orientation is
applied here; metadata (make/model/lens/iso/focal/aperture/date) is parsed
into StageAResult JSON. Route A (Camera Color Profile) additionally bakes a
per-channel histogram-matched camera curve (from the embedded JPEG,
`CameraColorMatch.kt`) directly into A.tif.

Non-RAW sources (JPEG/PNG/…) go through a *synthetic* Stage A (decode
bitmap → same BigTIFF) so the rest of the pipeline is format-agnostic. The
legacy v2 decoder (`raw_decoder.cpp`, NativeRawDecoder) survives ONLY for
FullRes/dual-ISO paths.

## Stage B — interactive preview (native GL + Kotlin)

`A.tif` → downsample → AHardwareBuffer → `gles_renderer.cpp` uber-shader
renders every frame with live `ShaderParams`. Spatial ops (NR pre-pass,
detail) are baked into intermediate AHBs; per-slider params are uniforms.
Key facts:

- Params cross threads via a double-buffered FloatArray under `paramsLock`
  (RawV3GlSurfaceView) — an unlocked share caused flicker on fast drags.
- The window surface is sRGB-tagged with GL_FRAMEBUFFER_SRGB disabled
  (avoids double-encode); the SurfaceView is ZOrderOnTop and NOT
  color-managed, which once made preview look more vibrant than export —
  pixels were identical.
- Brush-mask textures: 4 layers (uBrushMask0..3, R8, alpha extracted from
  ARGB on upload). The blue selection overlay (uShowMaskOverlay /
  uMaskOverlayLayer) is drawn as the LAST op before fragColor so B&W LUTs
  can't desaturate it.

## Stage C — export

Two routes:
- `runStageC` (full path incl. NR) and the fast-path `stageCToBitmap` for
  JPG/WebP. Both apply grading via **`apply_macro.cpp`
  (applyMacroPixelImpl)** — the CPU mirror of the GL shader.
- The CPU kernel was once a parallel delta-sum while GL was sequential →
  brighter/flat saves; it is now sequential. **Keep every new shader op
  mirrored here, in the same order.**
- Shared-by-construction pieces: CLAHE (proportional tiles, tileCount=8),
  detail/clarity USM radii scaled by longSide/2560 (WYSIWYG across
  resolutions), 256-LUT tone curves, lensfun combine modes.
- **JPG/WebP grade at export size (2026-09-09):** preferred path is GPU
  `renderGradedOffscreen` at a working resolution whose long side matches
  `targetLongSide` (after crop/orient accounting). Stage A FP16 is
  bilinear-subsampled from the mmap before GL upload — no full-res texture
  for a 2048 save. Bloom tent / shader `textureSize` USM use **that**
  buffer's long side (same as preview of a native image at export size).
  Brush-mask layers (`decodeMaskLayers` → units 3/5/6/7 +
  `uBrushMaskEnabled`) must be uploaded on this path too — CPU Stage C
  already receives them; skipping them on GPU was a preview≠save trap.
  16-bit TIFF still grades full-res CPU Stage C. CPU JPG fallback
  pre-writes a reduced Stage A via `downsampleStageATiffToSize`.

## ShaderParams (slot-based FloatArray ABI)

`ShaderParams.kt` ↔ `gles_renderer.cpp` parse ↔ `apply_macro.cpp` parse.
FLOAT_COUNT = 410. Selected slots: [134..140] mask layer 0 adjustments,
[157..177] layers 1-3, [147/148] lumaNR/colorNR, [182..193] per-layer luma
mask target/spread/feather, [395] maskLumCombine (luma↔bitmap combine mode:
0 legacy, 1 luma−bitmap carve, 2 bitmap−luma, 3 union, 4 intersect),
[402..407] ML_6D extended-intelligence diagnostics (dual-ISO recovery
gain/blend, sceneDR, highlightHeadroom, diffraction comp, body WB trim —
written by `MLExtendedIntelligence.applyExtendedDefaults()`, see
docs/FEATURES.md), [396..401, 408..409] free. Adding a slot = edit
ShaderParams.kt (toFloatArray, fillFloatArray, fromFloatArray) + both native
parsers + RawV3ActionReplay mapping + RawActionSerializer if persisted.

## Actions / UserMacro model

Edits are a stack of `RawAction` cards, each holding a `UserMacro`
(RawPipelineState.kt). `RawV3ActionReplay.composeMacro` folds the stack →
ShaderParams. All adjustment tabs auto-apply live (replaceTabCards /
composeTabMacro) EXCEPT the Mask tab which keeps a legacy Apply. Presets
serialize via `RawActionSerializer` (XML; luma-mask fields are kept —
photo-agnostic; painted masks are per-photo PNGs via maskPath).

## Masks

- Sources: brush Draw/Erase, object segmentation (dropdown, add/remove per
  class), Chroma "Select Color" (HSV-cone), Luma "Select Luminance"
  (live GPU band, no bitmap). All bitmap sources merge into ONE ARGB
  maskBitmap (coverage in ALPHA); luma stays parametric.
- Base-carve workflow: first-selected segment is the base; every other
  row's Remove subtracts from it; base's Remove clears all. Cross-
  category carve is supported both ways:
  - Luma base − objects/chroma → maskLumCombine=1 (bitmap = carve set)
  - Bitmap/object base − luma → maskLumCombine=2 (live GPU band)
  - Bitmap base − chroma → ColorSelect subtract taps (alpha carve)
  - Luma/Chroma Add onto an existing base unions (combine=3 for luma)
    instead of wiping the selection.
- Layer lifecycle trap (fixed 2026-07-10): `brushMaskLayers` passed to the
  preview already contains committed layers + the live bitmap. NEVER also
  pass `brushMask` — the preview appends it and a duplicate committed
  layer becomes a phantom "live" layer (second mask edits through the
  first mask's shape).
- Mask tab gestures: 1 finger paints, 2 fingers always pan/zoom.

## Segmentation & AI models (all on-demand, RAM-gated)

- Chain (RawV3Coordinator.ensureSegmentation, sequential with per-pass
  model unload): face probe (routing only) → BiRefNet subject (1024²,
  gated by `hasMemoryForBiRefNet`; portraits relax availMem/Java-heap)
  → U2Net general saliency when BiRefNet skipped/empty → DeepLab
  person-union only if still no usable subject → multiclass/face/
  cityscapes (mask-tab only) → refined matte (guided-filter,
  `refinedMask`, preferred by AE). Soft BiRefNet alpha uses sigmoid k=4
  (rembg-style). First `_segmentationMasks` emission already includes
  refinedMask; empty mattes are never published.
- Lazy: triggered by Mask/Gradient tab or AI Expose — NOT eagerly at open
  (eager chain once caused lmkd kills at 5.7GB native heap).
- Auto exposure: `RawAutoExposure.analyse` (histogram; exposure driven by
  bgMean·0.70+globalMean·0.30 with subject P95 cap). The TFLite
  RawAutoExposureModel call in injectAe is DEAD (result unused).
  **Batch never runs per-file AE** (removed — could not match the editor's
  bake exactly; presets with baked AE still apply).
- Other models: Zero-DCE (AI Color Enhance at open, default on), RAZGAN
  inpaint = MI-GAN via ONNX Runtime (TELEA fallback), DnCNN denoise
  planned. ONNX arena allocator disabled so ~4.5GB frees after seg.

## Bloom subject protection (feathered inward)

`uBloomExcludeSubject` routes bloom strength per pixel between `uOrtonStrength`
(background) and `uSubjectBloom` (subject). The gate is a 5×5 box average of
the subject mask over ±0.02 image-UV followed by `smoothstep(0.55, 0.98)`, so
protection ramps in from the silhouette to ~one radius INSIDE the subject while
the background keeps full bloom. GL: bloom block in `shader_sources.cpp`;
CPU: the `bloomExcludeSubject` branch in `apply_macro.cpp` (must stay mirrored).

## Stage A correction order and the LENS-REPORT line

Order (2026-09-04): demosaic → **lens profile** (distortion + TCA + vignetting,
`lfa_correct_rgba_f16`) → **Rayxie CA** (span clip via hook, then guided
defringe) → highlight desaturate → CLAHE → enhance. The profile's TCA is the
calibrated geometric fix; Rayxie only sees the residual. The JPEG/PNG import
path applies the same two steps in the same order to the synthetic A.tif.

Every import logs one grep-able line, `LENS-REPORT [RAW WxH]: …` (native,
`stage_a.cpp`) or `LENS-REPORT [JPEG]: …` (Kotlin, `RawV3Coordinator`), with
the profile, confidence, calibration/body format match, focal/aperture used,
the source of each correction (`dist/tca/vig = profile|generic-cos4|none`),
auto-scale zoom, and the Rayxie state. `RawV3Engine.lensfunLastReport()` returns
the same text for the last call on the calling thread. Bump the `|lfa=N` tag on
the Stage A fingerprint whenever this math or order changes.
