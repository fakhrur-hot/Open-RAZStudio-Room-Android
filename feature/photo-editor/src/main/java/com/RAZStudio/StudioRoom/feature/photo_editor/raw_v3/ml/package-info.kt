/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

/**
 * # Magic Lantern CR2 Intelligence Integration (`ml` package)
 *
 * This package implements Magic Lantern (ML) CR2 intelligence for StudioRoom's
 * RAW v3 pipeline. It uses only CR2 EXIF metadata as input — no external
 * sidecar files or network calls required.
 *
 * ## Data Provenance
 *
 * - **lens_tune.tbl**: Derived from the Magic Lantern project's sensor/lens
 *   characterization data. Each row maps a Canon lens ID to finishing trims
 *   (contrast, saturation, color tone, EV bias, WB red/blue scales).
 *   Validated across Canon EOS bodies running ML firmware.
 *
 * - **Noise curve**: Hardcoded ISO → NR strength mapping based on DIGIC 4/5/6
 *   sensor noise measurements from the Magic Lantern dual-ISO and raw-video
 *   modules. Provides luminance and chrominance NR defaults that scale with
 *   sensor readout noise.
 *
 * - **WB scene table**: Maps color temperature (Kelvin) ranges to white-balance
 *   and tint bias values, tuned from ML auto-WB scene detection data.
 *
 * - **ETTR bias model**: Maps RAW histogram light levels to exposure EV bias,
 *   reproducing Magic Lantern's Expose-To-The-Right algorithm for shadow recovery.
 *
 * ## Architecture
 *
 * All lookups in this package are **pure Kotlin** with no Android-specific
 * dependencies, with one exception:
 *
 * - [MLLensTuneTable] requires an Android `Context` to load the `lens_tune.tbl`
 *   asset from the APK's bundled assets directory.
 *
 * The remaining modules ([MLNoiseCurve], [MLWbSceneTable], [MLEttrBias]) use
 * hardcoded step curves and need no Context or I/O.
 *
 * ## Features
 *
 * - **Lens-aware finishing**: Per-lens contrast/saturation/tone trims from
 *   lens_tune.tbl, applied as overridable defaults at CR2 open time.
 *
 * - **ISO-aware noise reduction**: Automatic luminance/chrominance NR strength
 *   defaults scaled to the image's capture ISO.
 *
 * - **WB scene bias**: White-balance and tint seeding based on EXIF color
 *   temperature, so daylight/tungsten/shade scenes get appropriate initial WB.
 *
 * - **ETTR exposure bias**: Additive EV compensation derived from the RAW
 *   histogram to recover shadow detail in underexposed frames.
 *
 * ## Integration
 *
 * All ML intelligence is applied at CR2 open time as non-destructive,
 * user-overridable defaults via the existing ShaderParams infrastructure.
 * The `_smart_defaults` hidden action card provides a single toggle for
 * all ML-derived adjustments.
 *
 * Canon-first, universal fallback: lens ID 0 = neutral. Non-Canon RAW files
 * receive zero offsets and no ML adjustments are applied.
 */
package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml
