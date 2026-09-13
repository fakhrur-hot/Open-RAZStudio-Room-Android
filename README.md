# Open RAZStudio Room

An open-source **camera-RAW photo processing** app for Android. It decodes RAW
files (Canon CR2, Nikon NEF, and other formats via LibRaw), gives you a full
non-destructive editing pipeline — exposure, tone, color, curves, lens
correction, effects, subject masking — and exports to common image formats.

This repository is the **free, open-source edition** of RAZStudio Room. It is
meant to be transparent: you can read every line, build it yourself, install the
result, and run it fully offline. Nothing here phones home to make the app work.

> Version: **1.0.0-alpha** · License: **Apache-2.0** · Package:
> `com.RAZStudio.StudioRoom` · Min Android: **8.0 (API 26)** · ABI:
> **arm64-v8a** (64-bit ARM).

---

## Who made this (and why)

I'm not a professional photographer, not a photo editor, and
not a trained software engineer. I'm just an enthusiast who got hooked on photo
processing techniques and started learning **Camera RAW processing from January
2026**. This app is where I put what I learn: I read how the established tools
and papers do things (demosaic, tone mapping, lens correction, color science)
and try to re-implement those ideas myself.

So please treat this as a passionate hobby project, not a polished commercial
product. It's shared openly in case it's useful to someone else who's curious
about the same things.

If you find it useful — or you'd like me to keep working on features you want —
**donations are welcome** (see [Support this project](#support-this-project)
below). There's no pressure and no paywall: everything in this repo stays free.

---

## What this app does (the pipeline, simply)

Think of it as a small pipeline that takes a RAW photo and turns it into a
finished image:

1. **Decode** — Open the RAW file and turn the sensor data into a real image
   (demosaic). Handled by the native LibRaw-based decoder.
2. **Stage A (base image)** — Produce a high-quality 16-bit linear-light image,
   optionally applying lens correction (distortion / vignetting / chromatic
   aberration) if a Lensfun database is present.
3. **Edit (non-destructive)** — You adjust Light, Color, Tone Curves, Effects,
   Details, Gradients, Vignette, and Masks. Your edits are stored as a stack of
   actions + a sidecar, so the original is never overwritten.
4. **Preview** — A live GPU (OpenGL) preview shows your edits in real time.
5. **Export** — Bake the edits and encode to your JPEG format.

Optional **AI features** (subject masking, denoise, inpaint/heal, auto-exposure,
low-light) run **on-device** using small neural networks. They are strictly
optional — if a model isn't present, that one feature just turns itself off and
everything else keeps working.

### Tech stack (short version)

- **Language / UI:** Kotlin, Jetpack Compose (Material 3), JVM target 21.
- **Architecture:** modular Gradle project (many `:feature:*`, `:core:*`,
  `:lib:*` modules) with Decompose for navigation and Hilt for dependency
  injection.
- **Native (C/C++ via NDK):** LibRaw-based RAW decode, a self-contained Lensfun
  correction port, and image kernels. Ships for **arm64-v8a** only.
- **Imaging libraries:** OpenCV, ONNX Runtime (for the optional AI models),
  TensorFlow Lite (for a couple of small models).
- **Persistence:** Room (SQLite) + DataStore for settings; sidecar files for
  edit history.

---

## What is shipped vs. NOT shipped

Because this is the open edition, some heavy or third-party assets are **not
included**. The app is designed so it still builds and runs without them — the
related feature simply stays off until you add the asset yourself.

### ✅ Shipped in this repo
- All application source code (every module).
- Small, app-internal model/parameter files:
  `ae_tone_model.tflite`, `ae_scaler_mean.npy`, `ae_scaler_scale.npy`,
  `raw_hdr_recovery.bin`, `raw_shadow_recovery.bin`.
- The Gradle build, wrapper, and `build-logic`.

### ❌ NOT shipped (you supply your own — see below)
- **Large / third-party AI models** (BiRefNet, U²-Net, DeepLabV3+, SegFormer,
  MobileSAM, MI-GAN/"RAZGAN", face detection, DnCNN, Zero-DCE). These are big and
  each carries its own upstream license, so this repo does not redistribute them.
- **The Lensfun lens-correction database** (the `data/db/*.xml` files). Lens
  correction is off until you add them.
- **The short video editor** — removed entirely from this edition.
- **Signing keys** (`*.jks`, `keystore.properties`) — never committed. The debug
  build signs with the standard Android debug key automatically.

---

## What you need to build/fetch yourself, and how

Everything below is optional. Skip any of it and the app still builds and runs;
you just won't have that specific feature.

### 1. AI models
Drop each model into
`feature/photo-editor/src/main/assets/models/` using the exact filename the app
expects, then rebuild. Full list, purposes, and upstream sources are in
[`feature/photo-editor/src/main/assets/models/README.md`](feature/photo-editor/src/main/assets/models/README.md).

Quick reference for the most-asked ones:

| Filename | Feature | Get it from (original source) |
|---|---|---|
| `birefnet_lite.onnx` | Subject/background masking | BiRefNet — https://github.com/ZhengPeng7/BiRefNet (export to ONNX) |
| `u2net.onnx` | Saliency mask, heal base | U²-Net — https://github.com/xuebinqin/U-2-Net |
| `mobile_sam_encoder.onnx`, `mobile_sam_decoder.onnx` | Edge-precise masking | MobileSAM — https://github.com/ChaoningZhang/MobileSAM |
| `razgan.onnx` | Deep inpaint (heal/erase) | MI-GAN — https://github.com/Picsart-AI-Research/MI-GAN (MIT) → ONNX |

Please fetch each model from its **original** project and follow **its** license.

### 2. Lensfun database (for lens correction)
Get the XML database from https://github.com/lensfun/lensfun (`data/db/*.xml`),
copy the files into
`feature/photo-editor/src/main/assets/lensfun_db/`, and rebuild. Details in
[that folder's README](feature/photo-editor/src/main/assets/lensfun_db/README.md).

### 3. Build the app
```bash
# 1) Point Gradle at your Android SDK:
cp local.properties.template local.properties
#    then edit local.properties → sdk.dir=<your Android SDK path>

# 2) Build the debug APK (the open / FOSS flavor):
./gradlew :app:assembleFossDebug        # (gradlew.bat on Windows)

# 3) Install to a connected device:
adb install app/build/outputs/apk/foss/debug/Open_RAZStudio_Room-1.0.0-alpha-foss-arm64-v8a-debug.apk
```
Requirements: JDK 21, the Android SDK (compileSdk 37), and the Android NDK/CMake
for the native RAW/imaging code. A 64-bit ARM device or emulator (arm64-v8a).

---

## Is it safe? Is it legal?

**Safe to run offline.** The app is built to work without any internet
connection. Core RAW decoding, editing, preview, and export are all fully local
and on-device. The open edition has **no trial/expiry gate** and does **not**
require a network check to function. AI features run locally on any models you
provide.

**No hidden data collection in this edition.** The open (FOSS) flavor is built
without Google Play Services / analytics wiring. That said — you are encouraged
to verify this yourself. That's the whole point of it being open source: read
the code, build it, and confirm it behaves.

**Permissions** are only what the features need (reading images you open, saving
your exports, and — if you use them — camera-sync features). Nothing here
uploads your photos anywhere.

**Legal / licensing.** The app's own source code is licensed under the
**Apache License 2.0** (see [`LICENSE`](LICENSE)) — free to use, modify, and
redistribute with attribution.

Third-party components each keep their own licenses, and I've tried to be careful
about compliance:
- **Library dependencies** (OpenCV, ONNX Runtime, TensorFlow Lite, LibRaw,
  Compose, Decompose, etc.) are pulled from their official package repositories
  at build time under their respective licenses.
- **LibRaw / Lensfun / models** are not re-hosted here precisely so this repo
  doesn't redistribute anything under a license that would require it. You fetch
  those from their origin and comply with their terms.
- Code adapted conceptually from other projects (e.g. the Lensfun correction
  math, MI-GAN for inpainting) is attributed in the source files where it's used.

If you spot a licensing or attribution mistake,
please open an issue.

---

## Support this project

If Open RAZStudio Room is useful to you, or you'd like me to keep building
features you'd find handy, a small donation genuinely helps and keeps me
motivated to work on it. It's completely optional — the app stays free either
way.

**Touch 'n Go eWallet (Malaysia):** scan the QR code below.

![Donation QR (Touch 'n Go eWallet)](docs/donation-tng-qr.jpg)

If the QR doesn't work for you, or you'd like another method, open an issue and
we'll sort something out. And even without donating — bug reports, feature
ideas, and testing on different cameras/devices are hugely appreciated.

---

## Licensing

This repository is provided primarily for **transparency** — so you can read,
audit, and build the app yourself. It is **not** positioned as a
general-purpose open-source project.

- **Upstream / third-party components** (OpenCV, ONNX Runtime, TensorFlow Lite,
  LibRaw, Lensfun, Compose, Decompose, any adapted model code, etc.) remain
  under their **original open-source licenses** (Apache-2.0, MIT, GPL, BSD, …).
  Those are unaffected by anything here and may be used under their own terms.
- **Original RAZStudio modules** authored solely by me are covered by the
  **RAZStudio Transparency License** ([`LICENSE-RAZStudio.txt`](LICENSE-RAZStudio.txt)):
  you may view, study, and build them for personal use; redistribution or
  derivative works need my written permission.
- The top-level [`LICENSE`](LICENSE) is **Apache-2.0**, and many source files
  still carry Apache-2.0 headers.

**Honest note:** where a file's own header says Apache-2.0 (or another
open-source license), that header's grant governs that file — the Transparency
License states my intent/request for my original modules but does not revoke an
open-source grant already written into a file. If you plan to redistribute or
modify a specific file, check that file's header first. I'm an enthusiast, not a
lawyer; if something here is inconsistent or unclear, open an issue and I'll fix
it.

---

## A note on expectations

This is an alpha (1.0.0-alpha) built by one hobbyist who's still learning RAW
processing. Expect rough edges, missing pieces, and the occasional bug. Feedback
is welcome and I read all of it. Thanks for taking a look. 
