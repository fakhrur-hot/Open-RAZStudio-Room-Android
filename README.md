# Open RAZStudio Room

An open-source **camera-RAW photo processing** app for Android. This repository
is the **free, open-source edition** of RAZStudio Room: you can read every line,
build it yourself, install the result, and run it fully offline.

> Version: **1.0.1-alpha** · License: **Apache-2.0** · Package:
> `com.RAZStudio.StudioRoom` · Min Android: **8.0 (API 26)** · ABI:
> **arm64-v8a** (64-bit ARM).

---

## ✨ RAW Engine Highlights

### 📷 Advanced Hybrid RAW Processing

Built on a high-quality RAW development engine featuring:

- Hybrid dual-demosaic reconstruction pipeline
- Detail-preserving RAW processing workflow
- Advanced highlight recovery
- High-bit-depth processing pipeline
- Multi-stage RAW rendering architecture

Designed for photographers who prefer to extract maximum image quality instead of relying on one-tap presets.

### 🔬 Advanced Optical Corrections

Includes a comprehensive lens profile database with:

- Lens distortion correction
- Perspective-aware vignetting correction
- Advanced chromatic aberration correction
- Lens-specific optical compensation
- Profile-based RAW corrections

Supports a large collection of camera and lens combinations out of the box.

### 🎨 Photographer-Focused Color Engine

The FOSS build still includes the full professional color grading workflow:

- Color Wheels
- RGB Curves
- HSL Controls
- Split Toning
- Advanced tone controls
- Precision shadow/highlight adjustment

While RAW LUT and LUT Adj are disabled in this release, the underlying color engine remains fully functional.

### 🤖 AI-Assisted Local Adjustments

Integrated masking workflow featuring:

- Subject detection
- Multi-layer mask editing
- Selective local adjustments
- Portrait-aware editing foundation
- Non-destructive mask pipeline

Built on the same architecture used throughout the premium edition.

### ⚡ GPU Accelerated Rendering

Real-time editing powered by:

- OpenGL rendering pipeline
- GPU-assisted grading
- High-resolution RAW previews
- Accelerated export workflow
- Modern Android graphics stack

### 🌫️ Creative Photography Tools

Included in the FOSS edition:

- Depth-aware bokeh controls
- Bloom effects
- Vintage styling tools
- Selective adjustment workflows
- Portrait enhancement foundation

## 🚀 What Makes RAZStudio Room Different?

Most mobile editors focus on filters.

RAZStudio Room focuses on image reconstruction and photographer control.

Features include:

- ✅ Hybrid dual-demosaic RAW pipeline
- ✅ Highlight-preserving RAW development
- ✅ Advanced chromatic aberration correction
- ✅ Lens-profile optical corrections
- ✅ High-bit-depth editing workflow
- ✅ AI-assisted masking
- ✅ GPU-accelerated rendering
- ✅ Non-destructive local adjustments
- ✅ Professional color grading controls

Built for photographers who enjoy refining an image, not just applying a preset.

## 🚫 FOSS Edition Limitations

The following features remain exclusive to premium editions:

- RAW LUT
- LUT Adj
- Gallery Workspace
- Project Import / Export
- Sidecar Workflows
- Add To Project Actions
- Sony Project Integration
- Canon Project Integration

The core RAW engine, color controls, masks, optical corrections, and editing workflow remain available in this FOSS release.

LUT and LUT Adj tabs stay visible but disabled. Dedicated browsing, parsing, chaining, baking, and native-loader code is not in this repository. Short Video and LUT Creator are excluded.

---

## Technical specs

| | |
|---|---|
| Version | 1.0.1-alpha |
| Package | `com.RAZStudio.StudioRoom` |
| Min Android | 8.0 (API 26) |
| ABI | arm64-v8a only |
| JDK | 21 |
| compileSdk | 37 |
| Trial / expiry | none in this edition |

Optional AI features (subject masking, denoise, inpaint/heal, auto-exposure, low-light) run **on-device**. If a model is missing, that one feature turns itself off and everything else keeps working.

## Technology stack

- **Language / UI:** Kotlin, Jetpack Compose (Material 3), JVM target 21.
- **Architecture:** modular Gradle project (`:feature:*`, `:core:*`, `:lib:*`) with Decompose for navigation and Hilt for dependency injection.
- **Native (C/C++ via NDK):** LibRaw-based RAW decode, camera/lens profile correction kernels, and image processing. Ships for **arm64-v8a** only.
- **Imaging libraries:** OpenCV, ONNX Runtime (optional AI models), TensorFlow Lite (small models).
- **Persistence:** Room (SQLite) + DataStore for settings.

## Source, assets, and build

Because this is the open edition, some heavy or third-party assets are **not
included**. The app still builds and runs without them — the related feature
stays off until you add the asset yourself.

### Shipped in this repo

- All Open-edition application source code. Private LUT implementation, Gallery Workspace / project pipeline, and Short Video are excluded.
- Small, app-internal model/parameter files: `ae_tone_model.tflite`, `ae_scaler_mean.npy`, `ae_scaler_scale.npy`, `raw_hdr_recovery.bin`, `raw_shadow_recovery.bin`.
- The Gradle build, wrapper, and `build-logic`.

### Not shipped (you supply your own)

- **Large / third-party AI models** (BiRefNet, U²-Net, DeepLabV3+, SegFormer, MobileSAM, MI-GAN/"RAZGAN", face detection, DnCNN, Zero-DCE). Fetch each from its original project and follow **its** license.
- **Camera and lens profile XML database.** Optical correction stays off until you add the profiles.
- **Signing keys** (`*.jks`, `keystore.properties`). Debug builds sign with the standard Android debug key.

### AI models

Drop each model into `feature/photo-editor/src/main/assets/models/` using the exact filename the app expects, then rebuild. Full list: [`feature/photo-editor/src/main/assets/models/README.md`](feature/photo-editor/src/main/assets/models/README.md).

| Filename | Feature | Original source |
|---|---|---|
| `birefnet_lite.onnx` | Subject/background masking | [BiRefNet](https://github.com/ZhengPeng7/BiRefNet) (export to ONNX) |
| `u2net.onnx` | Saliency mask, heal base | [U²-Net](https://github.com/xuebinqin/U-2-Net) |
| `mobile_sam_encoder.onnx`, `mobile_sam_decoder.onnx` | Edge-precise masking | [MobileSAM](https://github.com/ChaoningZhang/MobileSAM) |
| `razgan.onnx` | Deep inpaint (heal/erase) | [MI-GAN](https://github.com/Picsart-AI-Research/MI-GAN) (MIT) → ONNX |

### Camera and lens profiles

Copy the XML profile files into `feature/photo-editor/src/main/assets/lensfun_db/` and rebuild. Details in [that folder's README](feature/photo-editor/src/main/assets/lensfun_db/README.md).

### Build the app

```bash
# 1) Point Gradle at your Android SDK:
cp local.properties.template local.properties
#    then edit local.properties → sdk.dir=<your Android SDK path>

# 2) Build the debug APK (the open / FOSS flavor):
./gradlew :app:assembleFossDebug        # gradlew.bat on Windows

# 3) Install to a connected device:
adb install app/build/outputs/apk/foss/debug/Open_RAZStudio_Room-1.0.1-alpha-foss-arm64-v8a-debug.apk
```

Requirements: JDK 21, the Android SDK (compileSdk 37), and the Android NDK/CMake for the native RAW/imaging code. A 64-bit ARM device or emulator (arm64-v8a).

## Privacy

**Safe to run offline.** Core RAW decoding, editing, preview, and export are fully local and on-device. The open edition has **no trial/expiry gate** and does **not** require a network check to function. AI features run locally on any models you provide.

**No hidden data collection in this edition.** The FOSS flavor is built without Google Play Services / analytics wiring. You are encouraged to verify this yourself: read the code, build it, and confirm it behaves.

**Permissions** are only what the features need (reading images you open, saving your exports, and — if you use them — camera-sync features). Nothing here uploads your photos anywhere.

## Legal

The app's own source code is licensed under the **Apache License 2.0** (see [`LICENSE`](LICENSE)) — free to use, modify, and redistribute with attribution.

Third-party components keep their own licenses:

- **Library dependencies** (OpenCV, ONNX Runtime, TensorFlow Lite, LibRaw, Compose, Decompose, etc.) are pulled from their official package repositories at build time.
- Large models and the camera/lens profile database are **not re-hosted here**. You fetch those from their origin and comply with their terms.
- Code adapted conceptually from other projects is attributed in the source files where it is used.

If you spot a licensing or attribution mistake, please open an issue.

### Licensing (transparency)

This repository is provided primarily for **transparency** — so you can read, audit, and build the app yourself. It is **not** positioned as a general-purpose open-source project.

- **Upstream / third-party components** remain under their **original open-source licenses** (Apache-2.0, MIT, GPL, BSD, …).
- **Original RAZStudio modules** authored solely by me are covered by the **RAZStudio Transparency License** ([`LICENSE-RAZStudio.txt`](LICENSE-RAZStudio.txt)): you may view, study, and build them for personal use; redistribution or derivative works need my written permission.
- The top-level [`LICENSE`](LICENSE) is **Apache-2.0**, and many source files still carry Apache-2.0 headers.

**Honest note:** where a file's own header says Apache-2.0 (or another open-source license), that header's grant governs that file — the Transparency License states my intent/request for my original modules but does not revoke an open-source grant already written into a file. If you plan to redistribute or modify a specific file, check that file's header first.

## Support this project

If Open RAZStudio Room is useful to you, or you'd like me to keep building features you'd find handy, a small donation genuinely helps. It's completely optional — the app stays free either way.

**Touch 'n Go eWallet (Malaysia):** scan the QR code below.

![Donation QR (Touch 'n Go eWallet)](docs/donation-tng-qr.jpg)

If the QR doesn't work for you, or you'd like another method, open an issue. Bug reports, feature ideas, and testing on different cameras/devices are hugely appreciated.

## Who made this

I'm not a professional photographer, not a photo editor, and not a trained software engineer. I'm an enthusiast who got hooked on photo processing techniques and started learning **Camera RAW processing from January 2026**. This app is where I put what I learn.

Please treat this as a passionate hobby project, not a polished commercial product.

## A note on expectations

This is an alpha (1.0.1-alpha) built by one hobbyist who's still learning RAW processing. Expect rough edges, missing pieces, and the occasional bug. Feedback is welcome and I read all of it. Thanks for taking a look.
