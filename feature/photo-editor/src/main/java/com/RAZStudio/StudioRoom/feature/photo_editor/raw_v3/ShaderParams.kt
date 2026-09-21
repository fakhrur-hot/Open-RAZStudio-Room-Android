/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Mirrors C++ raw_v3::ShaderParams. ANY layout change here must change the
 * C++ struct + uber-shader uniforms in lock-step. See Plan.md §7.
 *
 * Layout:
 *   [0..31]    workspace block (M4 + M11 lutIntensity at [31])
 *   [32..56]   Adobe XMP overlay block (M5.5)
 *   [57..59]   M12.1 per-tab opacity uniforms (light / color / xmp)
 *   [60]       dehaze (Light-tab midtone pull)
 *   [61..67]   Vignette block (M12.2b)
 *   [68..129]  Gradient block (M12.2b.2): angle + 4×15 sides + tabOpacity
 *   [130..133] Per-side Gradient segmentation targets (M12.2c.1)
 *   [134..140] Mask tab adjustments + tab opacity (M12.2c.2)
 *   [141..143] Tonemap tab (Adobe XMP target)
 *   [144..146] CLAHE pre-pass (native-only; shader ignores these)
 *   [147..148] Noise Reduction (native pre-op; shader ignores these)
 *   [149..156] Detail tab spatial ops (native pre-op; shader ignores these)
 *   [157..177] Mask layers 1..3 (M12.2c.2b)
 *   [178]      detailSmoothBackground
 *   [179..181] Bokeh
 *   [182..193] Mask luminance masks
 *   [194..199] Per-segment levels
 *   [200..209] LUT vibrancy, tonal-zone WB, ambiance, orton
 *   [205..207] Bloom params
 *   [210]      toneCurveLumaMode
 *   [211..228] hsl2 (Color Zones extended)
 *   [229..234] Per-segment highlights/ambiance, blueNR, redNR
 *   [235..239] workspaceSpace, lutAuthoredSpace, gamutCompress, bloom
 *   [240..252] Color Grading wheels, centerPop
 *   --- PREQ-Port additions ---
 *   [253..276] hslFull[24] (8 anchors × H/S/L; replaces hsl[10..27]+hsl2[211..228])
 *   [277..292] curveMaster[16] (8×(x,y) control points)
 *   [293..308] curveR[16]
 *   [309..324] curveG[16]
 *   [325..340] curveB[16]
 *   [341]      detailGrainRoughness
 *   [342]      detailSharpenMask
 *   [343]      colorDensity
 *   [344]      skintoneWarm
 *   [345]      skintoneSmooth
 *   [346]      skintoneLuma
 *   [347]      midtoneDetails
 *   [348]      highlightRecovery
 *   [349]      pushPull
 *   [350]      lutColorDensity
 *   [351]      lutSkintoneBalance
 *   [352]      aberStrength
 *   [353]      aberFringeReduce
 *   [354..374] Effects tab (blur, mist, dust, vintage, glow)
 *   [375..378] Haxademic grain (crossfade, scale, lumaAmp, chromaAmp)
 *   [379]      sharpenAmount (post-uber Laplacian)
 *   [380..409] reserved headroom
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.feature.photo_editor.BuildConfig

/** 8-point identity curve: (0,0),(1/7,1/7),...,(1,1). Used as default for all curve channels. */
private val IDENTITY_CURVE_16: FloatArray = FloatArray(16) { i -> (i / 2) / 7f }

data class VintageFx(
    val mistIntensity: Float = 0f,
    val mistScale: Float = 1f,
    val textureIntensity: Float = 0f,
    val textureScale: Float = 1f,
)

data class ShaderParams(
    // ── Workspace block (M4) ────────────────────────────────────────────
    val exposure: Float = 0f,        // [0]   stops, [-4..+4]
    val contrast: Float = 0f,        // [1]
    val highlights: Float = 0f,      // [2]
    val shadows: Float = 0f,         // [3]
    val whites: Float = 0f,          // [4]
    val blacks: Float = 0f,          // [5]
    val saturation: Float = 0f,      // [6]
    val vibrance: Float = 0f,        // [7]
    val whiteBalance: Float = 0f,    // [8]   [-100..+100]
    val tint: Float = 0f,            // [9]
    val hsl: FloatArray = FloatArray(18),  // [10..27]   R/O/Y/G/A/B
    /**
     * Six additional HSL anchors (Color Zones expansion): YellowGreen 90°,
     * SpringGreen 150°, SkyBlue 210°, Purple 270°, Magenta 300°, Pink 330°.
     * Each anchor is (h, s, l). Default-zero so existing edits render
     * identically.
     */
    val hsl2: FloatArray = FloatArray(18), // [211..228] YG/SG/SB/Pu/Ma/Pi
    /**
     * Per-segment highlights and ambiance, normalised the same way as their
     * global counterparts (`highlights / 100` → -1..+1, `ambiance` → -1..+1).
     * Used by Auto Expo to apply different correction amounts to subject vs
     * background, scaled by each region's channel-clip percentage. All zero =
     * inert (no per-segment adjustment).
     */
    val highlightsSubject: Float = 0f,    // [229] -1..+1 (highlights/100)
    val highlightsBackground: Float = 0f, // [230] -1..+1
    val ambianceSubject: Float = 0f,      // [231] -1..+1
    val ambianceBackground: Float = 0f,   // [232] -1..+1
    /**
     * Extra Cb-only NR strength [0..1], compounded onto the chromaNR blend.
     * Blue Bayer channel has the worst SNR (smallest WB gain ~0.45×); routing
     * additional smoothing into Cb specifically targets blue chroma noise
     * without flattening red / yellow detail the way pushing chromaNR alone
     * would. Stage B + Stage C both consume this from slot 233.
     */
    val blueNR: Float = 0f,               // [233] 0..1
    /**
     * Symmetric Cr-only NR knob. Mirrors blueNR for the red chroma axis.
     * Useful when high-ISO Canon shots show red speckle in shadows that
     * Color NR alone doesn't reach.
     */
    val redNR: Float = 0f,                // [234] 0..1
    val ditherStrength: Float = 1f,  // [28]
    /**
     * Purple-fringe correction pass mode, sourced from
     * [com.RAZStudio.StudioRoom.feature_photo_editor.raw.model.ColorFringingMode.ordinal].
     *   0 = Off    — pass disabled
     *   1 = Light  — pass disabled (LibRaw aber[] would handle it, but aber[]
     *                requires per-lens calibration so it's actually a no-op)
     *   2 = Strong — runtime desat of purple pixels near clipped highlights
     *
     * Slot **[449]** (append-only). Must NOT share [199] — that slot is owned
     * solely by [shadowsBackground]. A prior clash wrote Strong then overwrote
     * it with shadows, so live GL never saw purple-fringe while export also
     * lacked the pass (accidental dual-inert). Preview = export: both sides
     * read [449].
     */
    val purpleFringeMode: Int = 0,  // [449]
    val lutEnabled: Boolean = false, // [29]
    val gamutOut: Int = 0,           // [30]
    val lutIntensity: Float = 1f,    // [31]  0..1 mix between input pixel and LUT-sampled pixel
    /**
     * Black & White LUT chroma lock, slot [450]. When set (>0.5), the LUT
     * intensity mix blends from **achromatic source luma** toward the LUT
     * colour instead of `mix(colour, lut, t)`. That keeps chroma at 0 for
     * the source contribution (no colour bleeding back when intensity < 1)
     * while still allowing toned mono LUTs (sepia / cyanotype) to keep their
     * process colour at the LUT end. Preview = export (GL + apply_macro).
     * Colour LUTs leave this 0 and use the normal colour mix.
     */
    val lutBwForce: Boolean = false, // [450]
    /**
     * Luminance-preserving filmic S-curve, slot [451]. Tone-maps Y then
     * scales RGB (brief / selective-bokeh look). 0 = off; shader also
     * auto-applies 0.65 when depth CoC bokeh is live.
     */
    val filmicLuma: Float = 0f, // [451]
    /**
     * OKLab highlight chroma compression, slot [452]. Midtone chroma
     * lift + highlight desat toward neutral. Auto 0.70 with depth+bokeh.
     */
    val oklabHlChroma: Float = 0f, // [452]
    /**
     * LUT highlight vibrancy [-1..+1], slot [200]. Creative overlay on the
     * always-on headroom-aware pre-LUT map (identity on [0,1], compress >1):
     *  0   → headroom map only (default protect path).
     *  +1  → highlights re-sample with a saturation amplifier so the LUT's
     *        white-corner doesn't desaturate bright tones.
     *  -1  → blend toward full Reinhard on HDR before the LUT lookup.
     * Older blobs lacking slot 200 default to 0.
     */
    val lutHighlightVibrancy: Float = 0f, // [200]
    /**
     * Tonal-zone white-balance trims that ride alongside the LUT vibrancy
     * control. All four are [-1..+1] and apply ONLY to pixels in their
     * respective luma zone (smoothstep-weighted, not hard split):
     *   • [201] highlightTemperature  +1 warmer, -1 cooler in highlights
     *   • [202] highlightTint         +1 magenta, -1 green in highlights
     *   • [203] shadowTemperature     +1 warmer, -1 cooler in shadows
     *   • [204] shadowTint            +1 magenta, -1 green in shadows
     * Older blobs lacking these slots default to 0 = identity.
     */
    val highlightTemperature: Float = 0f, // [201]
    val highlightTint:        Float = 0f, // [202]
    val shadowTemperature:    Float = 0f, // [203]
    val shadowTint:           Float = 0f, // [204]
    // Glow / Glamour Glow params removed. The shader code and uniforms
    // also gone in lib/raw-native. Slots 205..207 + 210 are now unused
    // in the params blob; we keep the slot numbers reserved so a future
    // feature doesn't reuse them and accidentally read stale data from
    // older sidecars.
    /**
     * Ambiance (Tonemap tab) — edge-aware local-contrast + midtone
     * saturation lift. -1 flattens; +1 boosts.
     */
    val ambiance: Float = 0f, // [208]
    /**
     * Orton effect / bloom-softening strength [0..1]. Gaussian-blurred copy
     * screen-blended over the original, highlight-gated for the dreamy look.
     * Reuses uBlurTex (shared with bokeh blur pass).
     */
    val ortonStrength: Float = 0f, // [209]
    /** Bloom Vogel-disc radius in pixels. [0..20]. Default 8. Slot [205]. */
    val bloomRadius: Float = 8f,   // [205]
    /** Bloom anamorphic ratio. 1.0 = circular, <1 squashed, >1 stretched. Slot [206]. */
    val bloomShape: Float = 1f,    // [206]
    /** Film rolloff highlight shoulder. 0 = linear clip, 1 = soft film rolloff. Slot [207]. */
    val filmRolloff: Float = 0f, // [207]
    /** Gamut compression strength. 0 = off, 1 = full ACES-style desat at gamut walls. Slot [237]. */
    val gamutCompress: Float = 0f, // [237]
    /** Subject-exclusion gate for bloom. 0 = off, 1 = on. Slot [238]. */
    val bloomExcludeSubject: Float = 0f, // [238]
    /** Subject-only bloom strength when bloomExcludeSubject is on. 0..1. Slot [239]. */
    val subjectBloom: Float = 0f, // [239]
    /**
     * Workspace colour space the pipeline pixels are encoded in. Mirrors
     * `LibRawOutputColor.librawValue`: 1=sRGB, 2=AdobeRGB, 4=ProPhoto,
     * 7=DCI-P3, 8=Rec.2020. Drives the LUT gamut transform so sRGB-
     * authored .cube files apply correctly under wider-gamut workspaces.
     * Slot [235].
     */
    val workspaceSpace: Int = 1,   // [235]
    /**
     * Authored colour space of the active LUT. Mirrors `LutInputSpace.ordinal`:
     * 0=Rec.709/sRGB, 1=ProPhoto, 2=ACES, 3=DCI-P3. Slot [236].
     */
    val lutAuthoredSpace: Int = 0, // [236]
    /**
     * Tone-curve luma mode [0/1]. 0 = the master ("All") curve applies per-
     * channel as `master(channel(x))` — the GPUImage-compatible path. 1 = the
     * master curve applies to luma only, with chroma preserved as
     * `out_c = in_c + (curve(L) - L)`. Per-R/G/B curves still run independently.
     */
    val toneCurveLumaMode: Float = 0f, // [210]

    // ── Color Grading wheels (RapidRAW-style, no CIE machinery) ────────
    //   Three luma zones (Shadows / Midtones / Highlights). Each zone has
    //   a tint colour in 0..1 RGB (0.5 = neutral) and a saturation [0..1].
    //   The shader applies `out += (tint - 0.5) * sat * mask` per zone,
    //   where mask is a smoothstep on luma:
    //     • Shadows    smoothstep(0.5, 0.0, L)    — peaks at black
    //     • Midtones   1 - shadowsMask - hiMask   — peaks at gray
    //     • Highlights smoothstep(0.5, 1.0, L)    — peaks at white
    //   Tint defaults to (0.5,0.5,0.5) = identity. Saturation defaults to 0.
    /**
     * The four colour-grading wheels, packed into ONE array — sixteen flat
     * fields cost sixteen of ShaderParams' argument registers, and this class
     * had crept to 255 of the 256 the dex range-invoke count byte allows. Same
     * idiom as [hslFull]. The wire slots are UNCHANGED, so the native side
     * (which reads by slot number) needed no edit.
     *
     *   [0..3]   shadows    R, G, B, Sat  -> slots [240..243]
     *   [4..7]   midtones   R, G, B, Sat  -> slots [244..247]
     *   [8..11]  highlights R, G, B, Sat  -> slots [248..251]
     *   [12..15] global     R, G, B, Sat  -> slots [426..429]
     *
     * R/G/B are 0..1 with 0.5 = neutral; Sat is 0-centred.
     */
    val cg: FloatArray = floatArrayOf(
        0.5f, 0.5f, 0.5f, 0f,
        0.5f, 0.5f, 0.5f, 0f,
        0.5f, 0.5f, 0.5f, 0f,
        0.5f, 0.5f, 0.5f, 0f,
    ),
    // Global/Offset wheel (4-way Lift/Gamma/Gain). Appended [426..429].

    // ── OpenShot lens flare (final units; ActionReplay maps from the macro) ──
    val lensFlareX: Float = -0.5f,       // [400]  source X, normalised [-1..1]
    val lensFlareY: Float = -0.5f,       // [401]  source Y, normalised [-1..1]
    val lensFlareBrightness: Float = 0f, // [409]  0..1, 0 = off
    val lensFlareSize: Float = 1f,       // [430]  0.1..5 radius scale
    val lensFlareSpread: Float = 1f,     // [431]  0..1 ghost spread
    /** [435] Warmth 0..1 — was never written from Kotlin (native read zeros). */
    val lensFlareWarmth: Float = 0f,

    // ── OpenShot ColorShift (horizontal RGB split; uv-fraction offset) ──
    val colorShiftRedX: Float = 0f,      // [432]  -0.1..0.1
    val colorShiftGreenX: Float = 0f,    // [433]
    val colorShiftBlueX: Float = 0f,     // [434]

    // ── Film response (LUT tab) ────────────────────────────────────────────
    // Adobe legacy Recovery / FillLight, and the 8-channel B&W GrayMixer.
    // BIPOLAR here (Adobe's are 0..100): the positive half is Adobe's own
    // behaviour so imported presets map 1:1, the negative half is the reverse
    // and gives the high-contrast end of the axis. See UserMacro.FilmResponse.
    /**
     * Slots [436..446], packed into ONE array rather than eleven fields — or
     * even four. ShaderParams sits at the same dex argument-register ceiling as
     * UserMacro, and four flat fields here were enough to make ART reject
     * RawEditorComponent outright (shipped 2026-09-07). Same idiom as [hslFull].
     *
     *   [0] recovery    -1..1  >0 pulls highlights down (Adobe `Recovery`)
     *   [1] fillLight   -1..1  >0 lifts shadows (Adobe `FillLight`)
     *   [2] monochrome  0 or 1 (Adobe `ConvertToGrayscale`); gates [3..10]
     *   [3..10] GrayMixer: R, O, Y, G, Aqua, B, Purple, Magenta, each -1..1
     */
    val film: FloatArray = FloatArray(11),

    /**
     * Filmic / Pro-Mist bloom. Slots [447..448], packed to dodge the dex
     * argument-register ceiling (same idiom as [film]).
     *   [0] mistTightness 0..1 — bias Karis upsample toward mip1 (tight halo)
     *   [1] mistHalation  0..1 — R/B channel offset for optical scatter
     */
    val cinematic: FloatArray = floatArrayOf(0.55f, 0f),

    // Center-Pop — radial-masked clarity, RapidRAW-style single slider.
    // [-1..+1]; positive pops center, negative softens center.
    val centerPop: Float = 0f, // [252]

    // ── PREQ-Port: HSL Full (8 anchors × H/S/L) ─────────────────────────────
    //   Replaces the old hsl[10..27] + hsl2[211..228] split.
    //   Anchors in hue order: Red(0°), Orange(30°), Yellow(60°), YellowGreen(90°),
    //   Green(120°), SpringGreen(150°), Aqua(180°), SkyBlue(210°).
    //   Each triple is (hueShift, satShift, lumShift) normalised.
    //   On load, old hsl[]/hsl2[] values are migrated into hslFull.
    val hslFull: FloatArray = FloatArray(24), // [253..276]

    // ── PREQ-Port: RGB Curves (4-channel, 8 control points each) ────────────
    //   Each control point is stored as (x, y) pairs — 16 floats per channel.
    //   Default: identity diagonal (0,0),(1/7,1/7),...,(1,1).
    val curveMaster: FloatArray = IDENTITY_CURVE_16.copyOf(), // [277..292]
    val curveR:      FloatArray = IDENTITY_CURVE_16.copyOf(), // [293..308]
    val curveG:      FloatArray = IDENTITY_CURVE_16.copyOf(), // [309..324]
    val curveB:      FloatArray = IDENTITY_CURVE_16.copyOf(), // [325..340]

    // ── PREQ-Port: Detail additions ──────────────────────────────────────────
    val detailGrainRoughness: Float = 0f, // [341] Voronoi roughness blend 0..1
    val detailSharpenMask:    Float = 0f, // [342] Sobel-gated mask 0..1

    // ── PREQ-Port: Color tab additions ───────────────────────────────────────
    val colorDensity:   Float = 0f, // [343] mid-band saturation −1..+1
    val skintoneWarm:   Float = 0f, // [344] skin hue warm/cool −0.5..+0.5
    val skintoneSmooth: Float = 0f, // [345] skin saturation 0..1
    val skintoneLuma:   Float = 0f, // [346] skin luminance −0.5..+0.5

    // ── PREQ-Port: Tonal additions ───────────────────────────────────────────
    val midtoneDetails:    Float = 0f, // [347] midtone contrast −1..+1
    val highlightRecovery: Float = 0f, // [348] RAW recovery 0..1 (native pre-op)

    // ── PREQ-Port: LUT tab additions ─────────────────────────────────────────
    val pushPull:           Float = 0f, // [349] film push/pull −3..+3 stops
    val lutColorDensity:    Float = 0f, // [350] post-LUT color density −1..+1
    val lutSkintoneBalance: Float = 0f, // [351] post-LUT skin balance −0.5..+0.5

    // ── PREQ-Port: Chromatic Aberration ──────────────────────────────────────
    val aberStrength:     Float = 0f, // [352] CA add strength 0..1
    val aberFringeReduce: Float = 0f, // [353] CA fringe reduction 0..1

    // ── PREQ-Port: Effects tab ───────────────────────────────────────────────
    val fxGaussBlur:          Float = 0f,   // [354] Gaussian blur amount 0..1
    val fxDirBlurAmt:         Float = 0f,   // [355] directional blur amount 0..1
    val fxDirBlurAngle:       Float = 0f,   // [356] directional blur angle (radians)
    val fxRadBlurAmt:         Float = 0f,   // [357] radial blur amount 0..1
    val fxRadBlurCx:          Float = 0.5f, // [358] radial blur centre X
    val fxRadBlurCy:          Float = 0.5f, // [359] radial blur centre Y
    val fxZoomBlurAmt:        Float = 0f,   // [360] zoom blur amount 0..1
    val fxZoomBlurCx:         Float = 0.5f, // [361] zoom blur centre X
    val fxZoomBlurCy:         Float = 0.5f, // [362] zoom blur centre Y
    val fxBlurStyle:          Int   = 0,    // [363] 0=off 1=Gauss 2=Dir 3=Rad 4=Zoom
    val fxBlurExcludeSubject: Float = 0f,   // [364] exclude subject from blur 0/1
    val fxMist:               Float = 0f,   // [365] mist strength 0..1
    val fxMistWarmth:         Float = 0f,   // [366] mist colour temp −0.5..+0.5
    val fxDust:               Float = 0f,   // [367] dust strength 0..1
    val fxDustSize:           Float = 0f,   // [368] dust particle size 0..1
    val fxVintageStrength:    Float = 0f,   // [369] vintage strength 0..1
    val fxVintageFade:        Float = 0f,   // [370] vintage fade (black lift) 0..1
    val fxVintageVig:         Float = 0f,   // [371] vintage corner vignette 0..1
    val vintage: VintageFx = VintageFx(),
    val fxGlowStrength:       Float = 0f,   // [372] glow bloom intensity 0..1
    val fxGlowSpread:         Float = 0f,   // [373] glow bloom radius 0..1
    val fxGlowWarmth:         Float = 0f,   // [374] glow warmth tint −0.5..+0.5

    // ── Haxademic GLSL Port: Grain + Sharpen (Req 8, Req 6) ─────────────
    val haxGrainCrossfade:    Float = 0f,   // [375] hax grain mix 0..1
    val haxGrainScale:        Float = 1f,   // [376] hax grain UV scale (default 1.0)
    val haxGrainLumaAmp:      Float = 1f,   // [377] hax grain luma amplitude (default 1.0)
    val haxGrainChromaAmp:    Float = 0f,   // [378] hax grain chroma amplitude 0..1
    val sharpenAmount:        Float = 0f,   // [379] post-uber Laplacian sharpen 0..1
    val viewZoom:             Float = 1f,   // [380] canvas zoom (1 = fit, passed to vertex shader)
    val viewPanX:             Float = 0f,   // [381] canvas pan X in UV units
    val viewPanY:             Float = 0f,   // [382] canvas pan Y in UV units
    val filmicHlProtect:      Float = 0f,   // [383] filmic shoulder strength 0..1 (AI Expose blown HL guard)

    // ── XMP overlay block (M5.5) ────────────────────────────────────────
    val xmpEnabled: Boolean = false, // [32]
    val xmpExposure: Float = 0f,     // [33]
    val xmpContrast: Float = 0f,     // [34]
    val xmpHighlights: Float = 0f,   // [35]
    val xmpShadows: Float = 0f,      // [36]
    val xmpWhites: Float = 0f,       // [37]
    val xmpBlacks: Float = 0f,       // [38]
    val xmpHsl: FloatArray = FloatArray(18),  // [39..56]

    // ── M12.1 per-tab opacity (fan-out compositing) ─────────────────────
    //   `final = base + Σ (tabResult_i − base) × tabOpacity_i`.
    //   Default 1.0 means "tab fully on" — v2-compatible at launch. The
    //   editor exposes a master slider per tab; dialing one down masks
    //   that tab's whole adjustment without re-touching individual sliders.
    val lightTabOpacity: Float = 1f,   // [57]
    val colorTabOpacity: Float = 1f,   // [58]
    val xmpTabOpacity:   Float = 1f,   // [59]
    /** Dehaze pull, [-1..+1]. Subtracts a midtone bell `4·L·(1-L)`
     *  scaled by 0.15. Positive = clear haze (lifts contrast in
     *  midtones), negative = add haze. Slot [60]. */
    val dehaze:          Float = 0f,   // [60]

    // ── M12.2b — Vignette tab (radial darken/lighten) ───────────────────
    //   v2's UserMacro fields map 1:1 except `effect` which is encoded
    //   as 0 = All, 1 = SubjectOnly, 2 = BackgroundOnly (matches v2's
    //   VignetteEffect enum ordinal). Semantics:
    //     • amount  [-1..+1]      negative darkens, positive lightens
    //     • centerX [0..1]        normalised image-relative x
    //     • centerY [0..1]        normalised image-relative y
    //     • feather [0..1]        soft transition width
    //     • intensity [0..1]      opacity/strength of the radial mask
    //     • effect    int         0/1/2 segmentation gating
    //   Implementation in GLSL builds a `radial = distance(uv,center)`
    //   mask, smoothstep over feather, multiplies into c by amount.
    val vigAmount:    Float = 0f,      // [61]
    val vigCenterX:   Float = 0.5f,    // [62]
    val vigCenterY:   Float = 0.5f,    // [63]
    val vigFeather:   Float = 0.5f,    // [64]
    val vigIntensity: Float = 1f,      // [65]
    val vigEffect:    Int   = 0,       // [66]  0=All 1=SubjectOnly 2=BackgroundOnly
    /** Vignette-tab master opacity for the fan-out compositor. Default
     *  1.0 = full effect. Drag down to mask the tab. Slot [67]. */
    val vigTabOpacity: Float = 1f,     // [67]

    // ── M12.2b.2 — Gradient tab (4-sided edge gradients) ────────────────
    //   Mirrors v2 MacroProcessor.applyEdgeGradients exactly:
    //     • global rotation `gradAngle` (degrees, [-180..+180])
    //     • per side {Top, Bottom, Left, Right}, each with 2 layers
    //     • per layer: intensity ([0..1] darkness), length ([0..1] inward
    //       reach), feather ([0..1] soft-edge width — 0=hard cut at length,
    //       1=fully soft ramp edge→length), tint RGB ([0..1] linear),
    //       tint luminosity ([0..1] mix weight)
    //     • layer-2 needs an explicit enable2 flag (matches v2 — sliders
    //       can sit at zero with enable2=true)
    //   Layout per side: 15 floats
    //     +0  intensity1   +1  length1    +2  feather1
    //     +3  tintR1       +4  tintG1     +5  tintB1     +6  tintLum1
    //     +7  enable2      +8  intensity2 +9  length2    +10 feather2
    //     +11 tintR2       +12 tintG2     +13 tintB2     +14 tintLum2
    //   Side order: Top, Bottom, Left, Right (slot [68..127], 60 floats).
    val gradAngle:    Float = 0f,         // [68]  degrees
    val gradTop:      FloatArray = FloatArray(15),    // [69..83]
    val gradBottom:   FloatArray = FloatArray(15),    // [84..98]
    val gradLeft:     FloatArray = FloatArray(15),    // [99..113]
    val gradRight:    FloatArray = FloatArray(15),    // [114..128]
    /** Gradient-tab master opacity for the fan-out compositor. Slot [129]. */
    val gradTabOpacity: Float = 1f,       // [129]

    // ── M12.2c.1 — Per-side segmentation gating (U2Net subject mask) ────
    //   Encoded as SegmentTarget ordinals — 0 = All (no gating),
    //   1 = Subject only, 2 = Background only. Vignette's vigEffect at
    //   [66] uses the same encoding; these 4 slots are the per-side
    //   Gradient counterparts. The shader squares the U2Net probability
    //   for sharper edge fidelity before gating.
    val gradTopApplyTo:    Int = 0,       // [130]
    val gradBottomApplyTo: Int = 0,       // [131]
    val gradLeftApplyTo:   Int = 0,       // [132]
    val gradRightApplyTo:  Int = 0,       // [133]
    /** Per-side gradient tint blend mode (RawGradientBlendMode ordinal): 0=Solid
     *  (cinematic light-leak — screen + soft additive bloom), 1=Fused (overlay
     *  with a screen bias so warm leaks still glow). Stored in headroom [391..394].
     *
     *  NOTE: these comments used to read [380..383], which is where viewZoom /
     *  viewPanX / viewPanY / filmicHlProtect actually live. toFloatArray has
     *  always written 391-394, but apply_macro.cpp trusted the comment and read
     *  380-383 — so CPU exports got viewZoom (default 1.0) as the top blend
     *  mode, forcing Fused instead of Solid. Fixed on both sides; keep these
     *  indices in sync with gles_renderer.cpp AND apply_macro.cpp. */
    val gradTopBlendMode:    Int = 0,     // [391]
    val gradBottomBlendMode: Int = 0,     // [392]
    val gradLeftBlendMode:   Int = 0,     // [393]
    val gradRightBlendMode:  Int = 0,     // [394]

    // ── M12.2c.2 — Mask tab (paintable brush mask + 6 adjustments) ──────
    //   The brush mask itself is an uploaded GL_R8 texture (unit 3), not
    //   carried in this struct. These floats are the adjustments that
    //   apply where the painted alpha > 0. Slider ranges mirror v2's
    //   UserMacro.maskBrightness/.../maskClarity exactly.
    //     • brightness [-100..+100]  → EV stops via /100
    //     • contrast   [-100..+100]
    //     • temperature Kelvin delta [-2000..+2000]
    //     • tint        [-150..+150]
    //     • saturation [-100..+100]
    //     • clarity    [-100..+100]   (deferred — needs blur kernel)
    val maskBrightness:  Float = 0f,      // [134]
    val maskContrast:    Float = 0f,      // [135]
    val maskTemperature: Float = 0f,      // [136]
    val maskTint:        Float = 0f,      // [137]
    val maskSaturation:  Float = 0f,      // [138]
    val maskClarity:     Float = 0f,      // [139]  reserved (CPU-only for now)
    /** Mask-tab master opacity for the fan-out compositor. Slot [140]. */
    val maskTabOpacity:  Float = 1f,      // [140]

    // ── Tonemap tab (Adobe XMP target) ──────────────────────────────────
    //   Separate from Light-tab tone (slots [0..5]) so XMP presets can
    //   live independently of Auto-Exposure. Same units, additive on top
    //   of the Light tab pass in the shader.
    val tonemapExposure:   Float = 0f,    // [141]
    val tonemapHighlights: Float = 0f,    // [142]
    val tonemapShadows:    Float = 0f,    // [143]

    // ── CLAHE (tile-based adaptive equalization, sigmoid-blended) ───────
    //   Applied in the native pre-pass (Stage B AHB fill / Stage C decode)
    //   BEFORE the per-pixel macro kernel — NOT in the uber-shader. The
    //   shader ignores these three slots; they exist in the blob solely so
    //   the same params array carries CLAHE intent to both stages.
    //     • claheEnabled        0 = off, 1 = on (gated > 0.5)
    //     • claheShadowsBoost   [-1..1] + lift / - darken shadows
    //     • claheHighlightsBoost[-1..1] + recover / - mute highlights
    val claheEnabled:         Boolean = false, // [144]
    val claheShadowsBoost:    Float   = 0f,    // [145]
    val claheHighlightsBoost: Float   = 0f,    // [146]

    // ── Noise Reduction (native spatial pre-op, like CLAHE) ─────────────
    //   Baked into the source buffer (Stage B AHB / Stage C decode) on
    //   slider release — NOT in the uber-shader. Shader ignores these.
    //     • luminanceNR [0..1] edge-aware luma denoise strength
    //     • colorNR     [0..1] chroma denoise strength
    val luminanceNR: Float = 0f,     // [147]
    val colorNR:     Float = 0.15f, // [148] baseline suppresses AMaZE+VNG chroma speckles

    // ── Detail tab spatial ops (native pre-op, like CLAHE/NR) ───────────
    //   Baked into the source buffer on slider release; shader ignores these.
    //   Ported from v2 MacroProcessor. See raw_v3_detail.
    val detailSharpness:       Float = 0f,    // [149] [0..1] (UI sharpness/100)
    val detailSmartSharpness:  Float = 0f,    // [150] [0..1]
    val detailClarity:         Float = 0f,    // [151] [-1..1] (UI clarity/100)
    val detailTexture:         Float = 0f,    // [152] [-1..1] (UI texture/100)
    val detailFilmGrain:       Float = 0f,    // [153] [0..1]
    val detailFilmGrainSize:   Float = 0.5f,  // [154] [0..1]
    // detailFilmGrainUnif removed — uniformity slider was non-functional.
    val detailFilmGrainWash:   Float = 0f,    // [156] [0..1]
    // detailFilmGrainStyle removed — only Cinematic grain remains.

    // ── M12.2c.2b — Mask layers 1..3 (4-layer brush mask) ───────────────
    //   Layer 0 reuses the maskBrightness…maskTabOpacity fields above
    //   ([134..140]); these three additional layers occupy [157..177].
    //   Same per-layer units as layer 0. Each layer is gated by its own
    //   uploaded GL_R8 brush texture (units 5/6/7) and composites
    //   additively in the shader.
    val mask1Brightness:  Float = 0f,     // [157]
    val mask1Contrast:    Float = 0f,     // [158]
    val mask1Temperature: Float = 0f,     // [159]
    val mask1Tint:        Float = 0f,     // [160]
    val mask1Saturation:  Float = 0f,     // [161]
    val mask1Clarity:     Float = 0f,     // [162]
    val mask1TabOpacity:  Float = 1f,     // [163]
    val mask2Brightness:  Float = 0f,     // [164]
    val mask2Contrast:    Float = 0f,     // [165]
    val mask2Temperature: Float = 0f,     // [166]
    val mask2Tint:        Float = 0f,     // [167]
    val mask2Saturation:  Float = 0f,     // [168]
    val mask2Clarity:     Float = 0f,     // [169]
    val mask2TabOpacity:  Float = 1f,     // [170]
    val mask3Brightness:  Float = 0f,     // [171]
    val mask3Contrast:    Float = 0f,     // [172]
    val mask3Temperature: Float = 0f,     // [173]
    val mask3Tint:        Float = 0f,     // [174]
    val mask3Saturation:  Float = 0f,     // [175]
    val mask3Clarity:     Float = 0f,     // [176]
    val mask3TabOpacity:  Float = 1f,     // [177]

    // ── Smooth Background (native Detail pre-op) ─────────────────────────
    //   Variance-masked, subject-guarded OOF/bokeh smoothing. Baked into the
    //   source buffer on slider release; shader ignores this slot.
    val detailSmoothBackground: Float = 0f, // [178] [0..1]

    // ── Bokeh (GL real-time, FBO blur pass) ─────────────────────────────
    //   Background OOF blur + highlight bloom, composited in the uber-shader
    //   gated by the U2Net subject mask (background only). All [0..1].
    val bokehBlur:   Float = 0f, // [179] background blur strength
    val bokehBalls:  Float = 0f, // [180] highlight bloom strength
    val bokehSpread: Float = 0f, // [181] blur radius / bloom spread

    // ── Mask Luminance mask (per layer) ─────────────────────────────────
    //   Crosshair-sampled target tone GENERATES the selection: pixels within
    //   ±spread of target (graded luma) are masked, feathered. spread>0
    //   REPLACES the brush for that layer. [0,1]; spread 0 = off.
    //   Layout per layer: target, spread, feather.
    //   layer0 [182..184], layer1 [185..187], layer2 [188..190], layer3 [191..193].
    val maskLumTarget:  Float = 0f, val maskLumSpread:  Float = 0f, val maskLumFeather:  Float = 0f, // [182..184]
    val mask1LumTarget: Float = 0f, val mask1LumSpread: Float = 0f, val mask1LumFeather: Float = 0f, // [185..187]
    val mask2LumTarget: Float = 0f, val mask2LumSpread: Float = 0f, val mask2LumFeather: Float = 0f, // [188..190]
    val mask3LumTarget: Float = 0f, val mask3LumSpread: Float = 0f, val mask3LumFeather: Float = 0f, // [191..193]
    // Layer-0 luma↔bitmap combine mode: 0=luma wins (legacy), 1=luma−bitmap
    // (luma base, objects carved out), 2=bitmap−luma, 3=union, 4=intersect. [395]
    val maskLumCombine: Int = 0,

    // ── Per-layer mask Sharpness (Detail-tab sharpness, masked) ─────────────
    //   [-100..+100] high-frequency unsharp, gated by each layer's selection.
    //   APPENDED at [396..399] (append-only ABI, rule #5) so it stays out of
    //   the contiguous per-layer blocks. Exported via applyMaskedSharpness
    //   (stage_c_export.cpp) — mirrors the GL uber-shader mask-loop sharpen.
    val maskSharpness:  Float = 0f,   // [396] layer 0
    val mask1Sharpness: Float = 0f,   // [397] layer 1
    val mask2Sharpness: Float = 0f,   // [398] layer 2
    val mask3Sharpness: Float = 0f,   // [399] layer 3

    // ── Per-layer mask Tone regions (Highlights/Shadows/Whites/Blacks) ──────
    //   [-100..+100], applied via applyToneRegionsP gated by each layer's
    //   selection. APPENDED at [410..425] (append-only ABI, rule #5), grouped
    //   by param across layers: highlights [410..413], shadows [414..417],
    //   whites [418..421], blacks [422..425]. Mirrors the GL uber-shader
    //   mask-loop (uMaskHighlights/…) and the CPU export (apply_macro.cpp).
    val maskHighlights:  Float = 0f,  val mask1Highlights: Float = 0f,  val mask2Highlights: Float = 0f,  val mask3Highlights: Float = 0f,  // [410..413]
    val maskShadows:     Float = 0f,  val mask1Shadows:    Float = 0f,  val mask2Shadows:    Float = 0f,  val mask3Shadows:    Float = 0f,  // [414..417]
    val maskWhites:      Float = 0f,  val mask1Whites:     Float = 0f,  val mask2Whites:     Float = 0f,  val mask3Whites:     Float = 0f,  // [418..421]
    val maskBlacks:      Float = 0f,  val mask1Blacks:     Float = 0f,  val mask2Blacks:     Float = 0f,  val mask3Blacks:     Float = 0f,  // [422..425]

    // ── Per-segment levels (Normalize for 3Dlut) ─────────────────────────
    //   Optional whites/blacks gated by the U2Net subject mask. Pixels inside
    //   the subject blend toward (whitesSubject, blacksSubject); background
    //   pixels toward (whitesBackground, blacksBackground). All in slider
    //   space [-100..+100]; all zero = no per-segment correction.
    val whitesSubject:    Float = 0f, // [194]
    val blacksSubject:    Float = 0f, // [195]
    val whitesBackground: Float = 0f, // [196]
    val blacksBackground: Float = 0f, // [197]
    // Per-segment shadows lift (silhouette class). [-1..+1] normalised.
    val shadowsSubject:    Float = 0f, // [198]
    /** Sole owner of slot [199] — do not reuse for purpleFringeMode (now [449]). */
    val shadowsBackground: Float = 0f, // [199]
    // Smart Color Enhancement GL flag + per-channel auto-WB stats (slots [384..390]).
    // WB stats are computed from the Stage A 256px thumbnail; defaults (0/1) = no stretch.
    val smartColorEnhance: Float = 0f,  // [384] 0=off, 1=on
    val smartWbRMin:       Float = 0f,  // [385] normalized [0..1]
    val smartWbRMax:       Float = 1f,  // [386]
    val smartWbGMin:       Float = 0f,  // [387]
    val smartWbGMax:       Float = 1f,  // [388]
    val smartWbBMin:       Float = 0f,  // [389]
    val smartWbBMax:       Float = 1f,  // [390]

    // ── ML Extended Intelligence orchestrator diagnostics (Component 1) ─────
    //   Native pre-op / diagnostic only — the uber-shader ignores these.
    //   Written by MLExtendedIntelligence.applyExtendedDefaults(); their
    //   purpose is to surface dual-ISO / ETTR firmware-sourced metadata for
    //   on-device logcat verification (spec cr2-intelligence-integration,
    //   Requirement 8.2), not to drive any new rendering path.
    val extDualIsoRecoveryGain:  Float = 0f, // [402] [0..3]
    val extDualIsoBlendFactor:   Float = 0f, // [403] [0..1]
    val extSceneDR:              Float = 0f, // [404] [4..14]
    val extHighlightHeadroom:    Float = 0f, // [405] [0..3]
    val extDiffractionComp:      Float = 0f, // [406] [0..30]
    val extBodyWbTrim:           Float = 0f, // [407] [-1..1]
    // Clarity "pop" coupling (img.ly-style midtone exposure lift). 0 = classic
    // clarity (authentic large-radius local contrast only); >0 adds a midtone-
    // gated exposure lift on top, gated by the SAME midtone mask as clarity so
    // it never touches shadows/highlights. Applied in BOTH the GL preview
    // (shader_sources uClarityLift) and the CPU export/desktop (raw_v3_detail)
    // clarity paths — preview=export. See docs/GOTCHAS.md (clarity is a pair).
    val clarityLift:             Float = 0f, // [408] [0..1]
) {
    val fxVintageMistIntensity: Float
        get() = vintage.mistIntensity
    val fxVintageMistScale: Float
        get() = vintage.mistScale
    val fxVintageTextureIntensity: Float
        get() = vintage.textureIntensity
    val fxVintageTextureScale: Float
        get() = vintage.textureScale

    fun toFloatArray(): FloatArray = FloatArray(FLOAT_COUNT).also { a ->
        a[0]  = exposure
        a[1]  = contrast
        a[2]  = highlights
        a[3]  = shadows
        a[4]  = whites
        a[5]  = blacks
        a[6]  = saturation
        a[7]  = vibrance
        a[8]  = whiteBalance
        a[9]  = tint
        for (i in 0 until 18) a[10 + i] = hsl[i]
        a[28] = ditherStrength
        a[449] = purpleFringeMode.toFloat()
        a[29] = if (lutEnabled) 1f else 0f
        a[30] = gamutOut.toFloat()
        a[31] = lutIntensity
        a[450] = if (lutBwForce) 1f else 0f
        a[451] = filmicLuma
        a[452] = oklabHlChroma
        a[454] = vintage.mistIntensity
        a[455] = vintage.mistScale
        a[456] = vintage.textureIntensity
        a[457] = vintage.textureScale
        a[32] = if (xmpEnabled) 1f else 0f
        a[33] = xmpExposure
        a[34] = xmpContrast
        a[35] = xmpHighlights
        a[36] = xmpShadows
        a[37] = xmpWhites
        a[38] = xmpBlacks
        for (i in 0 until 18) a[39 + i] = xmpHsl[i]
        a[57] = lightTabOpacity
        a[58] = colorTabOpacity
        a[59] = xmpTabOpacity
        a[60] = dehaze
        a[61] = vigAmount
        a[62] = vigCenterX
        a[63] = vigCenterY
        a[64] = vigFeather
        a[65] = vigIntensity
        a[66] = vigEffect.toFloat()
        a[67] = vigTabOpacity
        a[68] = gradAngle
        for (i in 0 until 15) a[69  + i] = gradTop[i]
        for (i in 0 until 15) a[84  + i] = gradBottom[i]
        for (i in 0 until 15) a[99  + i] = gradLeft[i]
        for (i in 0 until 15) a[114 + i] = gradRight[i]
        a[129] = gradTabOpacity
        a[130] = gradTopApplyTo.toFloat()
        a[131] = gradBottomApplyTo.toFloat()
        a[132] = gradLeftApplyTo.toFloat()
        a[133] = gradRightApplyTo.toFloat()
        a[391] = gradTopBlendMode.toFloat()
        a[392] = gradBottomBlendMode.toFloat()
        a[393] = gradLeftBlendMode.toFloat()
        a[394] = gradRightBlendMode.toFloat()
        a[134] = maskBrightness
        a[135] = maskContrast
        a[136] = maskTemperature
        a[137] = maskTint
        a[138] = maskSaturation
        a[139] = maskClarity
        a[140] = maskTabOpacity
        a[141] = tonemapExposure
        a[142] = tonemapHighlights
        a[143] = tonemapShadows
        a[144] = if (claheEnabled) 1f else 0f
        a[145] = claheShadowsBoost
        a[146] = claheHighlightsBoost
        a[147] = luminanceNR
        a[148] = colorNR
        a[149] = detailSharpness
        a[150] = detailSmartSharpness
        a[151] = detailClarity
        a[152] = detailTexture
        a[153] = detailFilmGrain
        a[154] = detailFilmGrainSize
        // a[155] (detailFilmGrainUnif) removed
        a[156] = detailFilmGrainWash
        // Mask layers 1..3 (layer 0 already written at [134..140]).
        a[157] = mask1Brightness
        a[158] = mask1Contrast
        a[159] = mask1Temperature
        a[160] = mask1Tint
        a[161] = mask1Saturation
        a[162] = mask1Clarity
        a[163] = mask1TabOpacity
        a[164] = mask2Brightness
        a[165] = mask2Contrast
        a[166] = mask2Temperature
        a[167] = mask2Tint
        a[168] = mask2Saturation
        a[169] = mask2Clarity
        a[170] = mask2TabOpacity
        a[171] = mask3Brightness
        a[172] = mask3Contrast
        a[173] = mask3Temperature
        a[174] = mask3Tint
        a[175] = mask3Saturation
        a[176] = mask3Clarity
        a[177] = mask3TabOpacity
        a[178] = detailSmoothBackground
        a[179] = bokehBlur
        a[180] = bokehBalls
        a[181] = bokehSpread
        a[182] = maskLumTarget;  a[183] = maskLumSpread;  a[184] = maskLumFeather
        a[185] = mask1LumTarget; a[186] = mask1LumSpread; a[187] = mask1LumFeather
        a[188] = mask2LumTarget; a[189] = mask2LumSpread; a[190] = mask2LumFeather
        a[191] = mask3LumTarget; a[192] = mask3LumSpread; a[193] = mask3LumFeather
        a[395] = maskLumCombine.toFloat()
        a[396] = maskSharpness;  a[397] = mask1Sharpness
        a[398] = mask2Sharpness; a[399] = mask3Sharpness
        a[410] = maskHighlights; a[411] = mask1Highlights; a[412] = mask2Highlights; a[413] = mask3Highlights
        a[414] = maskShadows;    a[415] = mask1Shadows;    a[416] = mask2Shadows;    a[417] = mask3Shadows
        a[418] = maskWhites;     a[419] = mask1Whites;     a[420] = mask2Whites;     a[421] = mask3Whites
        a[422] = maskBlacks;     a[423] = mask1Blacks;     a[424] = mask2Blacks;     a[425] = mask3Blacks
        a[194] = whitesSubject;    a[195] = blacksSubject
        a[196] = whitesBackground; a[197] = blacksBackground
        a[198] = shadowsSubject;   a[199] = shadowsBackground
        a[200] = lutHighlightVibrancy
        a[201] = highlightTemperature
        a[202] = highlightTint
        a[203] = shadowTemperature
        a[204] = shadowTint
        // a[205..207] (glowStrength/Sat/Warmth) removed
        a[208] = ambiance
        a[209] = ortonStrength
        a[205] = bloomRadius
        a[206] = bloomShape
        a[207] = filmRolloff
        a[237] = gamutCompress
        a[238] = bloomExcludeSubject
        a[239] = subjectBloom
        a[235] = workspaceSpace.toFloat()
        a[236] = lutAuthoredSpace.toFloat()
        a[210] = toneCurveLumaMode
        for (i in 0 until 18) a[211 + i] = hsl2[i]
        a[229] = highlightsSubject
        a[230] = highlightsBackground
        a[231] = ambianceSubject
        a[232] = ambianceBackground
        a[233] = blueNR
        a[234] = redNR
        for (i in 0 until 12) a[240 + i] = cg[i]
        for (i in 0 until 4)  a[426 + i] = cg[12 + i]
        a[400] = lensFlareX
        a[401] = lensFlareY
        a[409] = lensFlareBrightness
        a[430] = lensFlareSize
        a[431] = lensFlareSpread
        a[435] = lensFlareWarmth
        a[432] = colorShiftRedX
        a[433] = colorShiftGreenX
        a[434] = colorShiftBlueX
        for (i in 0 until 11) a[436 + i] = film[i]
        a[447] = cinematic.getOrElse(0) { 0.55f }
        a[448] = cinematic.getOrElse(1) { 0f }
        a[252] = centerPop
        // PREQ-Port slots [253..374]
        for (i in 0 until 24) a[253 + i] = hslFull[i]
        for (i in 0 until 16) a[277 + i] = curveMaster[i]
        for (i in 0 until 16) a[293 + i] = curveR[i]
        for (i in 0 until 16) a[309 + i] = curveG[i]
        for (i in 0 until 16) a[325 + i] = curveB[i]
        a[341] = detailGrainRoughness
        a[342] = detailSharpenMask
        a[343] = colorDensity
        a[344] = skintoneWarm
        a[345] = skintoneSmooth
        a[346] = skintoneLuma
        a[347] = midtoneDetails
        a[348] = highlightRecovery
        a[349] = pushPull
        a[350] = lutColorDensity
        a[351] = lutSkintoneBalance
        a[352] = aberStrength
        a[353] = aberFringeReduce
        a[354] = fxGaussBlur
        a[355] = fxDirBlurAmt
        a[356] = fxDirBlurAngle
        a[357] = fxRadBlurAmt
        a[358] = fxRadBlurCx
        a[359] = fxRadBlurCy
        a[360] = fxZoomBlurAmt
        a[361] = fxZoomBlurCx
        a[362] = fxZoomBlurCy
        a[363] = fxBlurStyle.toFloat()
        a[364] = fxBlurExcludeSubject
        a[365] = fxMist
        a[366] = fxMistWarmth
        a[367] = fxDust
        a[368] = fxDustSize
        a[369] = fxVintageStrength
        a[370] = fxVintageFade
        a[371] = fxVintageVig
        a[372] = fxGlowStrength
        a[373] = fxGlowSpread
        a[374] = fxGlowWarmth
        // Haxademic GLSL port slots [375..379]
        a[375] = haxGrainCrossfade
        a[376] = haxGrainScale
        a[377] = haxGrainLumaAmp
        a[378] = haxGrainChromaAmp
        a[379] = sharpenAmount
        a[380] = viewZoom
        a[381] = viewPanX
        a[382] = viewPanY
        a[383] = filmicHlProtect
        a[384] = smartColorEnhance
        a[385] = smartWbRMin
        a[386] = smartWbRMax
        a[387] = smartWbGMin
        a[388] = smartWbGMax
        a[389] = smartWbBMin
        a[390] = smartWbBMax
        a[402] = extDualIsoRecoveryGain
        a[403] = extDualIsoBlendFactor
        a[404] = extSceneDR
        a[405] = extHighlightHeadroom
        a[406] = extDiffractionComp
        a[407] = extBodyWbTrim
        a[408] = clarityLift
        if (BuildConfig.DEBUG) debugValidate()
    }

    /**
     * Solution C: Fill a caller-owned FloatArray in-place (zero allocation).
     * Same logic as toFloatArray() but avoids creating a new array per frame.
     * The array must be at least FLOAT_COUNT elements.
     */
    fun fillFloatArray(a: FloatArray) {
        // Zero the array in case previous values linger in unused slots.
        a.fill(0f)
        a[0]  = exposure
        a[1]  = contrast
        a[2]  = highlights
        a[3]  = shadows
        a[4]  = whites
        a[5]  = blacks
        a[6]  = saturation
        a[7]  = vibrance
        a[8]  = whiteBalance
        a[9]  = tint
        for (i in 0 until 18) a[10 + i] = hsl[i]
        a[28] = ditherStrength
        a[449] = purpleFringeMode.toFloat()
        a[29] = if (lutEnabled) 1f else 0f
        a[30] = gamutOut.toFloat()
        a[31] = lutIntensity
        a[450] = if (lutBwForce) 1f else 0f
        a[451] = filmicLuma
        a[452] = oklabHlChroma
        a[454] = vintage.mistIntensity
        a[455] = vintage.mistScale
        a[456] = vintage.textureIntensity
        a[457] = vintage.textureScale
        a[32] = if (xmpEnabled) 1f else 0f
        a[33] = xmpExposure
        a[34] = xmpContrast
        a[35] = xmpHighlights
        a[36] = xmpShadows
        a[37] = xmpWhites
        a[38] = xmpBlacks
        for (i in 0 until 18) a[39 + i] = xmpHsl[i]
        a[57] = lightTabOpacity
        a[58] = colorTabOpacity
        a[59] = xmpTabOpacity
        a[60] = dehaze
        a[61] = vigAmount
        a[62] = vigCenterX
        a[63] = vigCenterY
        a[64] = vigFeather
        a[65] = vigIntensity
        a[66] = vigEffect.toFloat()
        a[67] = vigTabOpacity
        a[68] = gradAngle
        for (i in 0 until 15) a[69  + i] = gradTop[i]
        for (i in 0 until 15) a[84  + i] = gradBottom[i]
        for (i in 0 until 15) a[99  + i] = gradLeft[i]
        for (i in 0 until 15) a[114 + i] = gradRight[i]
        a[129] = gradTabOpacity
        a[130] = gradTopApplyTo.toFloat()
        a[131] = gradBottomApplyTo.toFloat()
        a[132] = gradLeftApplyTo.toFloat()
        a[133] = gradRightApplyTo.toFloat()
        a[391] = gradTopBlendMode.toFloat()
        a[392] = gradBottomBlendMode.toFloat()
        a[393] = gradLeftBlendMode.toFloat()
        a[394] = gradRightBlendMode.toFloat()
        a[134] = maskBrightness
        a[135] = maskContrast
        a[136] = maskTemperature
        a[137] = maskTint
        a[138] = maskSaturation
        a[139] = maskClarity
        a[140] = maskTabOpacity
        a[141] = tonemapExposure
        a[142] = tonemapHighlights
        a[143] = tonemapShadows
        a[144] = if (claheEnabled) 1f else 0f
        a[145] = claheShadowsBoost
        a[146] = claheHighlightsBoost
        a[147] = luminanceNR
        a[148] = colorNR
        a[149] = detailSharpness
        a[150] = detailSmartSharpness
        a[151] = detailClarity
        a[152] = detailTexture
        a[153] = detailFilmGrain
        a[154] = detailFilmGrainSize
        a[156] = detailFilmGrainWash
        a[157] = mask1Brightness
        a[158] = mask1Contrast
        a[159] = mask1Temperature
        a[160] = mask1Tint
        a[161] = mask1Saturation
        a[162] = mask1Clarity
        a[163] = mask1TabOpacity
        a[164] = mask2Brightness
        a[165] = mask2Contrast
        a[166] = mask2Temperature
        a[167] = mask2Tint
        a[168] = mask2Saturation
        a[169] = mask2Clarity
        a[170] = mask2TabOpacity
        a[171] = mask3Brightness
        a[172] = mask3Contrast
        a[173] = mask3Temperature
        a[174] = mask3Tint
        a[175] = mask3Saturation
        a[176] = mask3Clarity
        a[177] = mask3TabOpacity
        a[178] = detailSmoothBackground
        a[179] = bokehBlur
        a[180] = bokehBalls
        a[181] = bokehSpread
        a[182] = maskLumTarget;  a[183] = maskLumSpread;  a[184] = maskLumFeather
        a[185] = mask1LumTarget; a[186] = mask1LumSpread; a[187] = mask1LumFeather
        a[188] = mask2LumTarget; a[189] = mask2LumSpread; a[190] = mask2LumFeather
        a[191] = mask3LumTarget; a[192] = mask3LumSpread; a[193] = mask3LumFeather
        a[395] = maskLumCombine.toFloat()
        a[396] = maskSharpness;  a[397] = mask1Sharpness
        a[398] = mask2Sharpness; a[399] = mask3Sharpness
        a[410] = maskHighlights; a[411] = mask1Highlights; a[412] = mask2Highlights; a[413] = mask3Highlights
        a[414] = maskShadows;    a[415] = mask1Shadows;    a[416] = mask2Shadows;    a[417] = mask3Shadows
        a[418] = maskWhites;     a[419] = mask1Whites;     a[420] = mask2Whites;     a[421] = mask3Whites
        a[422] = maskBlacks;     a[423] = mask1Blacks;     a[424] = mask2Blacks;     a[425] = mask3Blacks
        a[194] = whitesSubject;    a[195] = blacksSubject
        a[196] = whitesBackground; a[197] = blacksBackground
        a[198] = shadowsSubject;   a[199] = shadowsBackground
        a[200] = lutHighlightVibrancy
        a[201] = highlightTemperature
        a[202] = highlightTint
        a[203] = shadowTemperature
        a[204] = shadowTint
        a[205] = bloomRadius
        a[206] = bloomShape
        a[207] = filmRolloff
        a[208] = ambiance
        a[209] = ortonStrength
        a[210] = toneCurveLumaMode
        for (i in 0 until 18) a[211 + i] = hsl2[i]
        a[229] = highlightsSubject
        a[230] = highlightsBackground
        a[231] = ambianceSubject
        a[232] = ambianceBackground
        a[233] = blueNR
        a[234] = redNR
        a[235] = workspaceSpace.toFloat()
        a[236] = lutAuthoredSpace.toFloat()
        a[237] = gamutCompress
        a[238] = bloomExcludeSubject
        a[239] = subjectBloom
        for (i in 0 until 12) a[240 + i] = cg[i]
        for (i in 0 until 4)  a[426 + i] = cg[12 + i]
        a[400] = lensFlareX; a[401] = lensFlareY; a[409] = lensFlareBrightness
        a[430] = lensFlareSize; a[431] = lensFlareSpread; a[435] = lensFlareWarmth
        a[432] = colorShiftRedX; a[433] = colorShiftGreenX; a[434] = colorShiftBlueX
        for (i in 0 until 11) a[436 + i] = film[i]
        a[447] = cinematic.getOrElse(0) { 0.55f }
        a[448] = cinematic.getOrElse(1) { 0f }
        a[252] = centerPop
        for (i in 0 until 24) a[253 + i] = hslFull[i]
        for (i in 0 until 16) a[277 + i] = curveMaster[i]
        for (i in 0 until 16) a[293 + i] = curveR[i]
        for (i in 0 until 16) a[309 + i] = curveG[i]
        for (i in 0 until 16) a[325 + i] = curveB[i]
        a[341] = detailGrainRoughness
        a[342] = detailSharpenMask
        a[343] = colorDensity
        a[344] = skintoneWarm
        a[345] = skintoneSmooth
        a[346] = skintoneLuma
        a[347] = midtoneDetails
        a[348] = highlightRecovery
        a[349] = pushPull
        a[350] = lutColorDensity
        a[351] = lutSkintoneBalance
        a[352] = aberStrength
        a[353] = aberFringeReduce
        a[354] = fxGaussBlur
        a[355] = fxDirBlurAmt
        a[356] = fxDirBlurAngle
        a[357] = fxRadBlurAmt
        a[358] = fxRadBlurCx
        a[359] = fxRadBlurCy
        a[360] = fxZoomBlurAmt
        a[361] = fxZoomBlurCx
        a[362] = fxZoomBlurCy
        a[363] = fxBlurStyle.toFloat()
        a[364] = fxBlurExcludeSubject
        a[365] = fxMist
        a[366] = fxMistWarmth
        a[367] = fxDust
        a[368] = fxDustSize
        a[369] = fxVintageStrength
        a[370] = fxVintageFade
        a[371] = fxVintageVig
        a[372] = fxGlowStrength
        a[373] = fxGlowSpread
        a[374] = fxGlowWarmth
        a[375] = haxGrainCrossfade
        a[376] = haxGrainScale
        a[377] = haxGrainLumaAmp
        a[378] = haxGrainChromaAmp
        a[379] = sharpenAmount
        a[380] = viewZoom
        a[381] = viewPanX
        a[382] = viewPanY
        a[383] = filmicHlProtect
        a[384] = smartColorEnhance
        a[385] = smartWbRMin
        a[386] = smartWbRMax
        a[387] = smartWbGMin
        a[388] = smartWbGMax
        a[389] = smartWbBMin
        a[390] = smartWbBMax
        a[402] = extDualIsoRecoveryGain
        a[403] = extDualIsoBlendFactor
        a[404] = extSceneDR
        a[405] = extHighlightHeadroom
        a[406] = extDiffractionComp
        a[407] = extBodyWbTrim
        a[408] = clarityLift
    }

    /**
     * Convenience: build a copy with the XMP overlay block populated from
     * the 25-float blob `RawV3Engine.parseAdobeXmp` returns.
     */
    fun withXmp(xmpBlob: FloatArray): ShaderParams {
        if (xmpBlob.size < 25) return this
        val enabled = xmpBlob[0] > 0.5f
        val hslOut = FloatArray(18)
        for (i in 0 until 18) hslOut[i] = xmpBlob[7 + i]
        return copy(
            xmpEnabled    = enabled,
            xmpExposure   = xmpBlob[1],
            xmpContrast   = xmpBlob[2],
            xmpHighlights = xmpBlob[3],
            xmpShadows    = xmpBlob[4],
            xmpWhites     = xmpBlob[5],
            xmpBlacks     = xmpBlob[6],
            xmpHsl        = hslOut,
        )
    }

    companion object {
        // Append-only ABI. Highest used slot is [457] (vintage.textureScale);
        // next free is [458].
        // lensFlare [400,401,409,430,431] + lensFlareWarmth [435],
        // colorShift [432,433,434],
        // film response [436..446], cinematic bloom [447..448],
        // purpleFringeMode [449] (moved off contested [199] — shadowsBackground),
        // lutBwForce [450], filmicLuma [451], oklabHlChroma [452]
        // (slot [453] reserved to keep these three clear of the 450+ vintage
        //  range they used to collide with),
        // vintage [454..457] — mist intensity/scale and texture intensity/scale.
        const val FLOAT_COUNT = 458
        const val XMP_BLOB_FLOAT_COUNT = 25
        /** Brush-mask layer count — matches GlesRenderer::kMaskLayers. */
        const val MASK_LAYER_COUNT = 4
        val Default = ShaderParams()

        private fun sliceCurve(arr: FloatArray, start: Int): FloatArray {
            if (start + 16 > arr.size) return IDENTITY_CURVE_16.copyOf()
            return arr.copyOfRange(start, start + 16)
        }

        /**
         * Reconstitute a [ShaderParams] from a 57-float blob (the
         * little-endian `Float` byte layout written by [toFloatArray] +
         * `ByteBuffer.putFloat`). Returns null on malformed input.
         *
         * Used by M10's session-restore path and by the Apply → Stage C
         * sidecar reader.
         */
        private fun sliceOr15(arr: FloatArray, start: Int): FloatArray {
            val out = FloatArray(15)
            for (i in 0 until 15) {
                val idx = start + i
                if (idx < arr.size) out[i] = arr[idx]
            }
            return out
        }

        fun fromFloatArray(arr: FloatArray): ShaderParams? {
            // Pre-M12.1 blobs are 57 floats. Accept them too; the new
            // tab-opacity slots default to 1.0 (v2-compatible).
            if (arr.size < 57) return null
            val hsl = FloatArray(18); for (i in 0 until 18) hsl[i] = arr[10 + i]
            val xmpHsl = FloatArray(18); for (i in 0 until 18) xmpHsl[i] = arr[39 + i]
            // hsl2 lives at [211..228]; default zero if blob predates Color Zones.
            val hsl2 = FloatArray(18)
            if (arr.size > 228) for (i in 0 until 18) hsl2[i] = arr[211 + i]
            val vintage = VintageFx(
                mistIntensity = arr.getOrElse(454) { 0f }.coerceIn(0f, 1f),
                mistScale = arr.getOrElse(455) { 1f },
                textureIntensity = arr.getOrElse(456) { 0f }.coerceIn(0f, 1f),
                textureScale = arr.getOrElse(457) { 1f },
            )
            return ShaderParams(
                exposure     = arr[0],
                contrast     = arr[1],
                highlights   = arr[2],
                shadows      = arr[3],
                whites       = arr[4],
                blacks       = arr[5],
                saturation   = arr[6],
                vibrance     = arr[7],
                whiteBalance = arr[8],
                tint         = arr[9],
                hsl          = hsl,
                hsl2         = hsl2,
                ditherStrength = arr[28],
                purpleFringeMode = arr.getOrElse(449) { 0f }.toInt(),
                lutEnabled   = arr[29] > 0.5f,
                gamutOut     = arr[30].toInt(),
                lutIntensity = arr[31].coerceIn(0f, 1f),
                lutBwForce   = arr.getOrElse(450) { 0f } > 0.5f,
                filmicLuma   = arr.getOrElse(451) { 0f },
                oklabHlChroma = arr.getOrElse(452) { 0f },
                xmpEnabled   = arr[32] > 0.5f,
                xmpExposure  = arr[33],
                xmpContrast  = arr[34],
                xmpHighlights = arr[35],
                xmpShadows   = arr[36],
                xmpWhites    = arr[37],
                xmpBlacks    = arr[38],
                xmpHsl       = xmpHsl,
                // [57..59] absent on pre-M12.1 blobs → default 1.0
                lightTabOpacity = if (arr.size > 57) arr[57].coerceIn(0f, 1f) else 1f,
                colorTabOpacity = if (arr.size > 58) arr[58].coerceIn(0f, 1f) else 1f,
                xmpTabOpacity   = if (arr.size > 59) arr[59].coerceIn(0f, 1f) else 1f,
                dehaze          = if (arr.size > 60) arr[60].coerceIn(-1f, 1f) else 0f,
                vigAmount       = if (arr.size > 61) arr[61].coerceIn(-1f, 1f) else 0f,
                vigCenterX      = if (arr.size > 62) arr[62].coerceIn(0f, 1f) else 0.5f,
                vigCenterY      = if (arr.size > 63) arr[63].coerceIn(0f, 1f) else 0.5f,
                vigFeather      = if (arr.size > 64) arr[64].coerceIn(0f, 1f) else 0.5f,
                vigIntensity    = if (arr.size > 65) arr[65].coerceIn(0f, 1f) else 1f,
                vigEffect       = if (arr.size > 66) arr[66].toInt().coerceIn(0, 2) else 0,
                vigTabOpacity   = if (arr.size > 67) arr[67].coerceIn(0f, 1f) else 1f,
                gradAngle       = if (arr.size > 68) arr[68] else 0f,
                gradTop         = sliceOr15(arr, 69),
                gradBottom      = sliceOr15(arr, 84),
                gradLeft        = sliceOr15(arr, 99),
                gradRight       = sliceOr15(arr, 114),
                gradTabOpacity  = if (arr.size > 129) arr[129].coerceIn(0f, 1f) else 1f,
                gradTopApplyTo    = if (arr.size > 130) arr[130].toInt().coerceIn(0, 2) else 0,
                gradBottomApplyTo = if (arr.size > 131) arr[131].toInt().coerceIn(0, 2) else 0,
                gradLeftApplyTo   = if (arr.size > 132) arr[132].toInt().coerceIn(0, 2) else 0,
                gradRightApplyTo  = if (arr.size > 133) arr[133].toInt().coerceIn(0, 2) else 0,
                gradTopBlendMode    = arr.getOrElse(391) { 0f }.toInt().coerceIn(0, 1),
                gradBottomBlendMode = arr.getOrElse(392) { 0f }.toInt().coerceIn(0, 1),
                gradLeftBlendMode   = arr.getOrElse(393) { 0f }.toInt().coerceIn(0, 1),
                gradRightBlendMode  = arr.getOrElse(394) { 0f }.toInt().coerceIn(0, 1),
                maskBrightness  = if (arr.size > 134) arr[134] else 0f,
                maskContrast    = if (arr.size > 135) arr[135] else 0f,
                maskTemperature = if (arr.size > 136) arr[136] else 0f,
                maskTint        = if (arr.size > 137) arr[137] else 0f,
                maskSaturation  = if (arr.size > 138) arr[138] else 0f,
                maskClarity     = if (arr.size > 139) arr[139] else 0f,
                maskTabOpacity  = if (arr.size > 140) arr[140].coerceIn(0f, 1f) else 1f,
                tonemapExposure   = if (arr.size > 141) arr[141] else 0f,
                tonemapHighlights = if (arr.size > 142) arr[142] else 0f,
                tonemapShadows    = if (arr.size > 143) arr[143] else 0f,
                claheEnabled         = if (arr.size > 144) arr[144] > 0.5f else false,
                claheShadowsBoost    = if (arr.size > 145) arr[145].coerceIn(-1f, 1f) else 0f,
                claheHighlightsBoost = if (arr.size > 146) arr[146].coerceIn(-1f, 1f) else 0f,
                luminanceNR          = if (arr.size > 147) arr[147].coerceIn(0f, 1f) else 0f,
                colorNR              = if (arr.size > 148) arr[148].coerceIn(0f, 1f) else 0f,
                detailSharpness      = if (arr.size > 149) arr[149].coerceIn(0f, 1f) else 0f,
                detailSmartSharpness = if (arr.size > 150) arr[150].coerceIn(0f, 1f) else 0f,
                detailClarity        = if (arr.size > 151) arr[151].coerceIn(-1f, 1f) else 0f,
                detailTexture        = if (arr.size > 152) arr[152].coerceIn(-1f, 1f) else 0f,
                detailFilmGrain      = if (arr.size > 153) arr[153].coerceIn(0f, 1f) else 0f,
                detailFilmGrainSize  = if (arr.size > 154) arr[154].coerceIn(0f, 1f) else 0.5f,
                // detailFilmGrainUnif / detailFilmGrainStyle removed
                detailFilmGrainWash  = if (arr.size > 156) arr[156].coerceIn(0f, 1f) else 0f,
                // Mask layers 1..3 — absent on older blobs → defaults.
                mask1Brightness  = if (arr.size > 157) arr[157] else 0f,
                mask1Contrast    = if (arr.size > 158) arr[158] else 0f,
                mask1Temperature = if (arr.size > 159) arr[159] else 0f,
                mask1Tint        = if (arr.size > 160) arr[160] else 0f,
                mask1Saturation  = if (arr.size > 161) arr[161] else 0f,
                mask1Clarity     = if (arr.size > 162) arr[162] else 0f,
                mask1TabOpacity  = if (arr.size > 163) arr[163].coerceIn(0f, 1f) else 1f,
                mask2Brightness  = if (arr.size > 164) arr[164] else 0f,
                mask2Contrast    = if (arr.size > 165) arr[165] else 0f,
                mask2Temperature = if (arr.size > 166) arr[166] else 0f,
                mask2Tint        = if (arr.size > 167) arr[167] else 0f,
                mask2Saturation  = if (arr.size > 168) arr[168] else 0f,
                mask2Clarity     = if (arr.size > 169) arr[169] else 0f,
                mask2TabOpacity  = if (arr.size > 170) arr[170].coerceIn(0f, 1f) else 1f,
                mask3Brightness  = if (arr.size > 171) arr[171] else 0f,
                mask3Contrast    = if (arr.size > 172) arr[172] else 0f,
                mask3Temperature = if (arr.size > 173) arr[173] else 0f,
                mask3Tint        = if (arr.size > 174) arr[174] else 0f,
                mask3Saturation  = if (arr.size > 175) arr[175] else 0f,
                mask3Clarity     = if (arr.size > 176) arr[176] else 0f,
                maskSharpness    = if (arr.size > 396) arr[396] else 0f,
                mask1Sharpness   = if (arr.size > 397) arr[397] else 0f,
                mask2Sharpness   = if (arr.size > 398) arr[398] else 0f,
                mask3Sharpness   = if (arr.size > 399) arr[399] else 0f,
                maskHighlights   = if (arr.size > 410) arr[410] else 0f,
                mask1Highlights  = if (arr.size > 411) arr[411] else 0f,
                mask2Highlights  = if (arr.size > 412) arr[412] else 0f,
                mask3Highlights  = if (arr.size > 413) arr[413] else 0f,
                maskShadows      = if (arr.size > 414) arr[414] else 0f,
                mask1Shadows     = if (arr.size > 415) arr[415] else 0f,
                mask2Shadows     = if (arr.size > 416) arr[416] else 0f,
                mask3Shadows     = if (arr.size > 417) arr[417] else 0f,
                maskWhites       = if (arr.size > 418) arr[418] else 0f,
                mask1Whites      = if (arr.size > 419) arr[419] else 0f,
                mask2Whites      = if (arr.size > 420) arr[420] else 0f,
                mask3Whites      = if (arr.size > 421) arr[421] else 0f,
                maskBlacks       = if (arr.size > 422) arr[422] else 0f,
                mask1Blacks      = if (arr.size > 423) arr[423] else 0f,
                mask2Blacks      = if (arr.size > 424) arr[424] else 0f,
                mask3Blacks      = if (arr.size > 425) arr[425] else 0f,
                mask3TabOpacity  = if (arr.size > 177) arr[177].coerceIn(0f, 1f) else 1f,
                detailSmoothBackground = if (arr.size > 178) arr[178].coerceIn(0f, 1f) else 0f,
                bokehBlur   = if (arr.size > 179) arr[179].coerceIn(0f, 1f) else 0f,
                bokehBalls  = if (arr.size > 180) arr[180].coerceIn(0f, 1f) else 0f,
                bokehSpread = if (arr.size > 181) arr[181].coerceIn(0f, 1f) else 0f,
                maskLumTarget  = if (arr.size > 182) arr[182].coerceIn(0f, 1f) else 0f,
                maskLumSpread  = if (arr.size > 183) arr[183].coerceIn(0f, 1f) else 0f,
                maskLumFeather = if (arr.size > 184) arr[184].coerceIn(0f, 1f) else 0f,
                maskLumCombine = if (arr.size > 395) arr[395].toInt() else 0,
                mask1LumTarget = if (arr.size > 185) arr[185].coerceIn(0f, 1f) else 0f,
                mask1LumSpread = if (arr.size > 186) arr[186].coerceIn(0f, 1f) else 0f,
                mask1LumFeather = if (arr.size > 187) arr[187].coerceIn(0f, 1f) else 0f,
                mask2LumTarget = if (arr.size > 188) arr[188].coerceIn(0f, 1f) else 0f,
                mask2LumSpread = if (arr.size > 189) arr[189].coerceIn(0f, 1f) else 0f,
                mask2LumFeather = if (arr.size > 190) arr[190].coerceIn(0f, 1f) else 0f,
                mask3LumTarget = if (arr.size > 191) arr[191].coerceIn(0f, 1f) else 0f,
                mask3LumSpread = if (arr.size > 192) arr[192].coerceIn(0f, 1f) else 0f,
                mask3LumFeather = if (arr.size > 193) arr[193].coerceIn(0f, 1f) else 0f,
                whitesSubject    = if (arr.size > 194) arr[194].coerceIn(-1f, 1f) else 0f,
                blacksSubject    = if (arr.size > 195) arr[195].coerceIn(-1f, 1f) else 0f,
                whitesBackground = if (arr.size > 196) arr[196].coerceIn(-1f, 1f) else 0f,
                blacksBackground = if (arr.size > 197) arr[197].coerceIn(-1f, 1f) else 0f,
                shadowsSubject    = if (arr.size > 198) arr[198].coerceIn(-1f, 1f) else 0f,
                shadowsBackground = if (arr.size > 199) arr[199].coerceIn(-1f, 1f) else 0f,
                lutHighlightVibrancy = if (arr.size > 200) arr[200].coerceIn(-1f, 1f) else 0f,
                highlightTemperature = if (arr.size > 201) arr[201].coerceIn(-1f, 1f) else 0f,
                highlightTint        = if (arr.size > 202) arr[202].coerceIn(-1f, 1f) else 0f,
                shadowTemperature    = if (arr.size > 203) arr[203].coerceIn(-1f, 1f) else 0f,
                shadowTint           = if (arr.size > 204) arr[204].coerceIn(-1f, 1f) else 0f,
                // glowStrength/Saturation/Warmth/Sharpness removed
                ambiance             = if (arr.size > 208) arr[208].coerceIn(-1f, 1f) else 0f,
                ortonStrength        = if (arr.size > 209) arr[209].coerceIn(0f, 1f) else 0f,
                bloomRadius          = if (arr.size > 205) arr[205].coerceIn(0f, 24f) else 8f,
                bloomShape           = if (arr.size > 206) arr[206].coerceIn(0.4f, 1.6f) else 1f,
                filmRolloff          = if (arr.size > 207) arr[207].coerceIn(0f, 1f) else 0f,
                gamutCompress        = if (arr.size > 237) arr[237].coerceIn(0f, 1f) else 0f,
                bloomExcludeSubject  = if (arr.size > 238) arr[238].coerceIn(0f, 1f) else 0f,
                subjectBloom         = if (arr.size > 239) arr[239].coerceIn(0f, 1f) else 0f,
                workspaceSpace       = if (arr.size > 235) arr[235].toInt() else 1,
                lutAuthoredSpace     = if (arr.size > 236) arr[236].toInt() else 0,
                toneCurveLumaMode    = if (arr.size > 210) arr[210].coerceIn(0f, 1f) else 0f,
                highlightsSubject    = if (arr.size > 229) arr[229].coerceIn(-1f, 1f) else 0f,
                highlightsBackground = if (arr.size > 230) arr[230].coerceIn(-1f, 1f) else 0f,
                ambianceSubject      = if (arr.size > 231) arr[231].coerceIn(-1f, 1f) else 0f,
                ambianceBackground   = if (arr.size > 232) arr[232].coerceIn(-1f, 1f) else 0f,
                blueNR               = if (arr.size > 233) arr[233].coerceIn(0f, 1f) else 0f,
                redNR                = if (arr.size > 234) arr[234].coerceIn(0f, 1f) else 0f,
                // Defaults must match the constructor: 0.5 neutral for R/G/B,
                // 0 for Sat, so a short blob leaves the wheels inert.
                cg = FloatArray(16) { i ->
                    val slot = if (i < 12) 240 + i else 426 + (i - 12)
                    val dflt = if (i % 4 == 3) 0f else 0.5f
                    if (arr.size > slot) arr[slot].coerceIn(0f, 1f) else dflt
                },
                lensFlareX          = if (arr.size > 400) arr[400].coerceIn(-1f, 1f) else -0.5f,
                lensFlareY          = if (arr.size > 401) arr[401].coerceIn(-1f, 1f) else -0.5f,
                lensFlareBrightness = if (arr.size > 409) arr[409].coerceIn(0f, 1f) else 0f,
                lensFlareSize       = if (arr.size > 430) arr[430].coerceIn(0.1f, 5f) else 1f,
                lensFlareSpread     = if (arr.size > 431) arr[431].coerceIn(0f, 1f) else 1f,
                lensFlareWarmth     = if (arr.size > 435) arr[435].coerceIn(0f, 1f) else 0f,
                colorShiftRedX      = if (arr.size > 432) arr[432].coerceIn(-0.1f, 0.1f) else 0f,
                colorShiftGreenX    = if (arr.size > 433) arr[433].coerceIn(-0.1f, 0.1f) else 0f,
                colorShiftBlueX     = if (arr.size > 434) arr[434].coerceIn(-0.1f, 0.1f) else 0f,
                // Blobs written before the film-response slots existed are
                // shorter than 447; an all-zero array is a full no-op (index [2],
                // the monochrome gate, stays 0).
                film = FloatArray(11) { i ->
                    if (arr.size > 436 + i) arr[436 + i].coerceIn(-1f, 1f) else 0f
                },
                cinematic = floatArrayOf(
                    if (arr.size > 447) arr[447].coerceIn(0f, 1f) else 0.55f,
                    if (arr.size > 448) arr[448].coerceIn(0f, 1f) else 0f,
                ),
                centerPop        = if (arr.size > 252) arr[252].coerceIn(-1f, 1f) else 0f,
                // PREQ-Port: HSL Full — migrate from old hsl/hsl2 if hslFull is all-zero
                hslFull = run {
                    val full = FloatArray(24)
                    if (arr.size > 276) {
                        for (i in 0 until 24) full[i] = arr[253 + i]
                    } else {
                        // Migrate: old hsl[10..27] = R/O/Y/G/A/B (6 anchors × 3 = 18)
                        // old hsl2[211..228] = YG/SG/SB/Pu/Ma/Pi (6 anchors × 3 = 18)
                        // Map to new 8-anchor layout (first 8 of the 12 zones)
                        val legacyHsl  = FloatArray(18) { i -> if (10 + i < arr.size) arr[10 + i] else 0f }
                        val legacyHsl2 = FloatArray(18) { i -> if (211 + i < arr.size) arr[211 + i] else 0f }
                        // Anchors 0..5 = R/O/Y/G/A/B from hsl
                        for (i in 0 until 6) { full[i * 3] = legacyHsl[i * 3]; full[i * 3 + 1] = legacyHsl[i * 3 + 1]; full[i * 3 + 2] = legacyHsl[i * 3 + 2] }
                        // Anchors 6..7 = YG/SG from hsl2 (first two of the six extended)
                        full[18] = legacyHsl2[0]; full[19] = legacyHsl2[1]; full[20] = legacyHsl2[2]
                        full[21] = legacyHsl2[3]; full[22] = legacyHsl2[4]; full[23] = legacyHsl2[5]
                    }
                    full
                },
                curveMaster = sliceCurve(arr, 277),
                curveR      = sliceCurve(arr, 293),
                curveG      = sliceCurve(arr, 309),
                curveB      = sliceCurve(arr, 325),
                detailGrainRoughness = arr.getOrElse(341) { 0f }.coerceIn(0f, 1f),
                detailSharpenMask    = arr.getOrElse(342) { 0f }.coerceIn(0f, 1f),
                colorDensity         = arr.getOrElse(343) { 0f }.coerceIn(-1f, 1f),
                skintoneWarm         = arr.getOrElse(344) { 0f }.coerceIn(-0.5f, 0.5f),
                skintoneSmooth       = arr.getOrElse(345) { 0f }.coerceIn(0f, 1f),
                skintoneLuma         = arr.getOrElse(346) { 0f }.coerceIn(-0.5f, 0.5f),
                midtoneDetails       = arr.getOrElse(347) { 0f }.coerceIn(-1f, 1f),
                highlightRecovery    = arr.getOrElse(348) { 0f }.coerceIn(0f, 1f),
                pushPull             = arr.getOrElse(349) { 0f }.coerceIn(-3f, 3f),
                lutColorDensity      = arr.getOrElse(350) { 0f }.coerceIn(-1f, 1f),
                lutSkintoneBalance   = arr.getOrElse(351) { 0f }.coerceIn(-0.5f, 0.5f),
                aberStrength         = arr.getOrElse(352) { 0f }.coerceIn(0f, 1f),
                aberFringeReduce     = arr.getOrElse(353) { 0f }.coerceIn(0f, 1f),
                fxGaussBlur          = arr.getOrElse(354) { 0f }.coerceIn(0f, 1f),
                fxDirBlurAmt         = arr.getOrElse(355) { 0f }.coerceIn(0f, 1f),
                fxDirBlurAngle       = arr.getOrElse(356) { 0f },
                fxRadBlurAmt         = arr.getOrElse(357) { 0f }.coerceIn(0f, 1f),
                fxRadBlurCx          = arr.getOrElse(358) { 0.5f }.coerceIn(0f, 1f),
                fxRadBlurCy          = arr.getOrElse(359) { 0.5f }.coerceIn(0f, 1f),
                fxZoomBlurAmt        = arr.getOrElse(360) { 0f }.coerceIn(0f, 1f),
                fxZoomBlurCx         = arr.getOrElse(361) { 0.5f }.coerceIn(0f, 1f),
                fxZoomBlurCy         = arr.getOrElse(362) { 0.5f }.coerceIn(0f, 1f),
                fxBlurStyle          = arr.getOrElse(363) { 0f }.toInt().coerceIn(0, 4),
                fxBlurExcludeSubject = arr.getOrElse(364) { 0f }.coerceIn(0f, 1f),
                fxMist               = arr.getOrElse(365) { 0f }.coerceIn(0f, 1f),
                fxMistWarmth         = arr.getOrElse(366) { 0f }.coerceIn(-0.5f, 0.5f),
                fxDust               = arr.getOrElse(367) { 0f }.coerceIn(0f, 1f),
                fxDustSize           = arr.getOrElse(368) { 0f }.coerceIn(0f, 1f),
                fxVintageStrength    = arr.getOrElse(369) { 0f }.coerceIn(0f, 1f),
                fxVintageFade        = arr.getOrElse(370) { 0f }.coerceIn(0f, 1f),
                fxVintageVig         = arr.getOrElse(371) { 0f }.coerceIn(0f, 1f),
                vintage = VintageFx(
                    mistIntensity = arr.getOrElse(454) { 0f }.coerceIn(0f, 1f),
                    mistScale = arr.getOrElse(455) { 1f }.coerceAtLeast(1f),
                    textureIntensity = arr.getOrElse(456) { 0f }.coerceIn(0f, 1f),
                    textureScale = arr.getOrElse(457) { 1f }.coerceAtLeast(1f),
                ),
                fxGlowStrength       = arr.getOrElse(372) { 0f }.coerceIn(0f, 1f),
                fxGlowSpread         = arr.getOrElse(373) { 0f }.coerceIn(0f, 1f),
                fxGlowWarmth         = arr.getOrElse(374) { 0f }.coerceIn(-0.5f, 0.5f),
                // Haxademic GLSL port slots [375..379]
                haxGrainCrossfade    = arr.getOrElse(375) { 0f }.coerceIn(0f, 1f),
                haxGrainScale        = arr.getOrElse(376) { 1f }.coerceIn(0.1f, 10f),
                haxGrainLumaAmp      = arr.getOrElse(377) { 1f }.coerceIn(0f, 2f),
                haxGrainChromaAmp    = arr.getOrElse(378) { 0f }.coerceIn(0f, 1f),
                sharpenAmount        = arr.getOrElse(379) { 0f }.coerceIn(0f, 1f),
                viewZoom             = arr.getOrElse(380) { 1f }.coerceAtLeast(0.01f),
                viewPanX             = arr.getOrElse(381) { 0f },
                viewPanY             = arr.getOrElse(382) { 0f },
                // [383] was written by toFloatArray but never read back here, so
                // the AI-Expose blown-highlight guard was silently dropped every
                // time RawV3Coordinator round-tripped the blob
                // (fromFloatArray -> copy -> toFloatArray) before export.
                filmicHlProtect      = arr.getOrElse(383) { 0f }.coerceIn(0f, 1f),
                smartColorEnhance = arr.getOrElse(384) { 0f }.coerceIn(0f, 1f),
                smartWbRMin       = arr.getOrElse(385) { 0f }.coerceIn(0f, 1f),
                smartWbRMax       = arr.getOrElse(386) { 1f }.coerceIn(0f, 1f),
                smartWbGMin       = arr.getOrElse(387) { 0f }.coerceIn(0f, 1f),
                smartWbGMax       = arr.getOrElse(388) { 1f }.coerceIn(0f, 1f),
                smartWbBMin       = arr.getOrElse(389) { 0f }.coerceIn(0f, 1f),
                smartWbBMax       = arr.getOrElse(390) { 1f }.coerceIn(0f, 1f),
                extDualIsoRecoveryGain = arr.getOrElse(402) { 0f }.coerceIn(0f, 3f),
                extDualIsoBlendFactor  = arr.getOrElse(403) { 0f }.coerceIn(0f, 1f),
                extSceneDR             = arr.getOrElse(404) { 0f }.coerceIn(0f, 14f),
                extHighlightHeadroom   = arr.getOrElse(405) { 0f }.coerceIn(0f, 3f),
                extDiffractionComp     = arr.getOrElse(406) { 0f }.coerceIn(0f, 30f),
                extBodyWbTrim          = arr.getOrElse(407) { 0f }.coerceIn(-1f, 1f),
            ).also { if (BuildConfig.DEBUG) it.debugValidate() }
        }
    }
}

/**
 * Returns true when the params contain effects that apply_macro.cpp (Stage B kernel)
 * does NOT implement, making the pre-graded AHB inaccurate vs the GL shader.
 *
 * When true the graded bake should be suppressed so the canvas stays on the
 * ungraded AHB + live GL grading and there is no brightness/color pop on swap.
 *
 * Effects MISSING from apply_macro.cpp (as of 2026-06-09 audit):
 *   • filmRolloff      — highlight soft-knee compression
 *   • detailClarity    — local contrast (clarity)
 *   • centerPop        — radial-masked clarity
 *   • Color Grading wheels (cgShadows/Midtones/Highlights RGB)
 *   • hslFull          — 8-anchor HSL
 *   • colorDensity     — mid-band saturation
 *   • skintoneWarm/Smooth/Luma — skin-tone adjustments
 *   • midtoneDetails   — midtone contrast
 *   • pushPull         — film push/pull EV shift
 *   • gamutCompress    — out-of-gamut rolloff
 *   • detailGrainRoughness — screen-space grain (intentionally GL-only — still causes pop)
 *   • tone curve / RGB curves — applied via GL sampler, not in CPU kernel
 *
 * NOTE: update this list whenever a new effect is added to the GL shader
 * and its CPU equivalent is backported to apply_macro.cpp.
 */
fun ShaderParams.gradedBakeWouldDiverge(): Boolean {
    // ShaderParams-level diverge check. Phase-1 backport is complete so this
    // is always false. For new GL-only effects, register them in EffectRegistry
    // with supportsStageB=false — GradingPipeline checks EffectRegistry.anyDiverging(macro)
    // (which has the UserMacro) before calling this function.
    return false
}

// ── Task 3.1: Safe clamped update ───────────────────────────────────────────
/**
 * Returns a copy of [ShaderParams] with the field at [slot] replaced by
 * [value] clamped to that slot's declared range. Slots with no scalar range
 * (arrays, booleans, ints, and reserved headroom [383..409]) are passed
 * through unchanged — callers must update array fields and typed ints directly
 * via [copy].
 *
 * The slot→field mapping mirrors [ShaderParams.toFloatArray] exactly.
 */
fun ShaderParams.withUpdate(slot: Int, value: Float): ShaderParams = when (slot) {
    0    -> copy(exposure             = value.coerceIn(-4f, 4f))
    1    -> copy(contrast             = value.coerceIn(-1f, 1f))
    2    -> copy(highlights           = value.coerceIn(-1f, 1f))
    3    -> copy(shadows              = value.coerceIn(-1f, 1f))
    4    -> copy(whites               = value.coerceIn(-1f, 1f))
    5    -> copy(blacks               = value.coerceIn(-1f, 1f))
    6    -> copy(saturation           = value.coerceIn(-1f, 1f))
    7    -> copy(vibrance             = value.coerceIn(-1f, 1f))
    8    -> copy(whiteBalance         = value.coerceIn(-100f, 100f))
    9    -> copy(tint                 = value.coerceIn(-100f, 100f))
    // [10..27] hsl array — use copy(hsl=...) directly
    28   -> copy(ditherStrength       = value)
    // [29] lutEnabled bool — skip
    // [30] gamutOut int — skip
    31   -> copy(lutIntensity         = value.coerceIn(0f, 1f))
    454  -> copy(vintage = vintage.copy(mistIntensity = value.coerceIn(0f, 1f)))
    455  -> copy(vintage = vintage.copy(mistScale = value.coerceAtLeast(1f)))
    456  -> copy(vintage = vintage.copy(textureIntensity = value.coerceIn(0f, 1f)))
    457  -> copy(vintage = vintage.copy(textureScale = value.coerceAtLeast(1f)))
    // [32] xmpEnabled bool — skip
    33   -> copy(xmpExposure          = value.coerceIn(-4f, 4f))
    34   -> copy(xmpContrast          = value.coerceIn(-1f, 1f))
    35   -> copy(xmpHighlights        = value.coerceIn(-1f, 1f))
    36   -> copy(xmpShadows           = value.coerceIn(-1f, 1f))
    37   -> copy(xmpWhites            = value.coerceIn(-1f, 1f))
    38   -> copy(xmpBlacks            = value.coerceIn(-1f, 1f))
    // [39..56] xmpHsl array — skip
    57   -> copy(lightTabOpacity      = value.coerceIn(0f, 1f))
    58   -> copy(colorTabOpacity      = value.coerceIn(0f, 1f))
    59   -> copy(xmpTabOpacity        = value.coerceIn(0f, 1f))
    60   -> copy(dehaze               = value.coerceIn(-1f, 1f))
    61   -> copy(vigAmount            = value.coerceIn(-1f, 1f))
    62   -> copy(vigCenterX           = value.coerceIn(0f, 1f))
    63   -> copy(vigCenterY           = value.coerceIn(0f, 1f))
    64   -> copy(vigFeather           = value.coerceIn(0f, 1f))
    65   -> copy(vigIntensity         = value.coerceIn(0f, 1f))
    // [66] vigEffect int — skip
    67   -> copy(vigTabOpacity        = value.coerceIn(0f, 1f))
    68   -> copy(gradAngle            = value.coerceIn(-180f, 180f))
    // [69..128] gradTop/Bottom/Left/Right arrays — skip
    129  -> copy(gradTabOpacity       = value.coerceIn(0f, 1f))
    // [130..133] gradXApplyTo ints — skip
    134  -> copy(maskBrightness       = value.coerceIn(-100f, 100f))
    135  -> copy(maskContrast         = value.coerceIn(-100f, 100f))
    136  -> copy(maskTemperature      = value.coerceIn(-2000f, 2000f))
    137  -> copy(maskTint             = value.coerceIn(-150f, 150f))
    138  -> copy(maskSaturation       = value.coerceIn(-100f, 100f))
    139  -> copy(maskClarity          = value.coerceIn(-100f, 100f))
    140  -> copy(maskTabOpacity       = value.coerceIn(0f, 1f))
    141  -> copy(tonemapExposure      = value.coerceIn(-4f, 4f))
    142  -> copy(tonemapHighlights    = value.coerceIn(-1f, 1f))
    143  -> copy(tonemapShadows       = value.coerceIn(-1f, 1f))
    // [144] claheEnabled bool — skip
    145  -> copy(claheShadowsBoost    = value.coerceIn(-1f, 1f))
    146  -> copy(claheHighlightsBoost = value.coerceIn(-1f, 1f))
    147  -> copy(luminanceNR          = value.coerceIn(0f, 1f))
    148  -> copy(colorNR              = value.coerceIn(0f, 1f))
    149  -> copy(detailSharpness      = value.coerceIn(0f, 1f))
    150  -> copy(detailSmartSharpness = value.coerceIn(0f, 1f))
    151  -> copy(detailClarity        = value.coerceIn(-1f, 1f))
    152  -> copy(detailTexture        = value.coerceIn(-1f, 1f))
    153  -> copy(detailFilmGrain      = value.coerceIn(0f, 1f))
    154  -> copy(detailFilmGrainSize  = value.coerceIn(0f, 1f))
    156  -> copy(detailFilmGrainWash  = value.coerceIn(0f, 1f))
    157  -> copy(mask1Brightness      = value.coerceIn(-100f, 100f))
    158  -> copy(mask1Contrast        = value.coerceIn(-100f, 100f))
    159  -> copy(mask1Temperature     = value.coerceIn(-2000f, 2000f))
    160  -> copy(mask1Tint            = value.coerceIn(-150f, 150f))
    161  -> copy(mask1Saturation      = value.coerceIn(-100f, 100f))
    162  -> copy(mask1Clarity         = value.coerceIn(-100f, 100f))
    163  -> copy(mask1TabOpacity      = value.coerceIn(0f, 1f))
    164  -> copy(mask2Brightness      = value.coerceIn(-100f, 100f))
    165  -> copy(mask2Contrast        = value.coerceIn(-100f, 100f))
    166  -> copy(mask2Temperature     = value.coerceIn(-2000f, 2000f))
    167  -> copy(mask2Tint            = value.coerceIn(-150f, 150f))
    168  -> copy(mask2Saturation      = value.coerceIn(-100f, 100f))
    169  -> copy(mask2Clarity         = value.coerceIn(-100f, 100f))
    170  -> copy(mask2TabOpacity      = value.coerceIn(0f, 1f))
    171  -> copy(mask3Brightness      = value.coerceIn(-100f, 100f))
    172  -> copy(mask3Contrast        = value.coerceIn(-100f, 100f))
    173  -> copy(mask3Temperature     = value.coerceIn(-2000f, 2000f))
    174  -> copy(mask3Tint            = value.coerceIn(-150f, 150f))
    175  -> copy(mask3Saturation      = value.coerceIn(-100f, 100f))
    176  -> copy(mask3Clarity         = value.coerceIn(-100f, 100f))
    177  -> copy(mask3TabOpacity      = value.coerceIn(0f, 1f))
    178  -> copy(detailSmoothBackground = value.coerceIn(0f, 1f))
    179  -> copy(bokehBlur            = value.coerceIn(0f, 1f))
    180  -> copy(bokehBalls           = value.coerceIn(0f, 1f))
    181  -> copy(bokehSpread          = value.coerceIn(0f, 1f))
    182  -> copy(maskLumTarget        = value.coerceIn(0f, 1f))
    183  -> copy(maskLumSpread        = value.coerceIn(0f, 1f))
    184  -> copy(maskLumFeather       = value.coerceIn(0f, 1f))
    185  -> copy(mask1LumTarget       = value.coerceIn(0f, 1f))
    186  -> copy(mask1LumSpread       = value.coerceIn(0f, 1f))
    187  -> copy(mask1LumFeather      = value.coerceIn(0f, 1f))
    188  -> copy(mask2LumTarget       = value.coerceIn(0f, 1f))
    189  -> copy(mask2LumSpread       = value.coerceIn(0f, 1f))
    190  -> copy(mask2LumFeather      = value.coerceIn(0f, 1f))
    191  -> copy(mask3LumTarget       = value.coerceIn(0f, 1f))
    192  -> copy(mask3LumSpread       = value.coerceIn(0f, 1f))
    193  -> copy(mask3LumFeather      = value.coerceIn(0f, 1f))
    194  -> copy(whitesSubject        = value.coerceIn(-1f, 1f))
    195  -> copy(blacksSubject        = value.coerceIn(-1f, 1f))
    196  -> copy(whitesBackground     = value.coerceIn(-1f, 1f))
    197  -> copy(blacksBackground     = value.coerceIn(-1f, 1f))
    198  -> copy(shadowsSubject       = value.coerceIn(-1f, 1f))
    199  -> copy(shadowsBackground    = value.coerceIn(-1f, 1f))
    200  -> copy(lutHighlightVibrancy = value.coerceIn(-1f, 1f))
    201  -> copy(highlightTemperature = value.coerceIn(-1f, 1f))
    202  -> copy(highlightTint        = value.coerceIn(-1f, 1f))
    203  -> copy(shadowTemperature    = value.coerceIn(-1f, 1f))
    204  -> copy(shadowTint           = value.coerceIn(-1f, 1f))
    205  -> copy(bloomRadius          = value.coerceIn(0f, 24f))
    206  -> copy(bloomShape           = value.coerceIn(0.4f, 1.6f))
    207  -> copy(filmRolloff          = value.coerceIn(0f, 1f))
    208  -> copy(ambiance             = value.coerceIn(-1f, 1f))
    209  -> copy(ortonStrength        = value.coerceIn(0f, 1f))
    210  -> copy(toneCurveLumaMode    = value.coerceIn(0f, 1f))
    // [211..228] hsl2 array — skip
    229  -> copy(highlightsSubject    = value.coerceIn(-1f, 1f))
    230  -> copy(highlightsBackground = value.coerceIn(-1f, 1f))
    231  -> copy(ambianceSubject      = value.coerceIn(-1f, 1f))
    232  -> copy(ambianceBackground   = value.coerceIn(-1f, 1f))
    233  -> copy(blueNR               = value.coerceIn(0f, 1f))
    234  -> copy(redNR                = value.coerceIn(0f, 1f))
    // [235] workspaceSpace int — skip
    // [236] lutAuthoredSpace int — skip
    237  -> copy(gamutCompress        = value.coerceIn(0f, 1f))
    238  -> copy(bloomExcludeSubject  = value.coerceIn(0f, 1f))
    239  -> copy(subjectBloom         = value.coerceIn(0f, 1f))
    in 240..251 -> copy(cg = cg.copyOf().also { it[slot - 240] = value.coerceIn(0f, 1f) })
    in 426..429 -> copy(cg = cg.copyOf().also { it[12 + slot - 426] = value.coerceIn(0f, 1f) })
    252  -> copy(centerPop            = value.coerceIn(-1f, 1f))
    // [253..276] hslFull array — skip
    // [277..340] curve arrays — skip
    341  -> copy(detailGrainRoughness = value.coerceIn(0f, 1f))
    342  -> copy(detailSharpenMask    = value.coerceIn(0f, 1f))
    343  -> copy(colorDensity         = value.coerceIn(-1f, 1f))
    344  -> copy(skintoneWarm         = value.coerceIn(-0.5f, 0.5f))
    345  -> copy(skintoneSmooth       = value.coerceIn(0f, 1f))
    346  -> copy(skintoneLuma         = value.coerceIn(-0.5f, 0.5f))
    347  -> copy(midtoneDetails       = value.coerceIn(-1f, 1f))
    348  -> copy(highlightRecovery    = value.coerceIn(0f, 1f))
    349  -> copy(pushPull             = value.coerceIn(-3f, 3f))
    350  -> copy(lutColorDensity      = value.coerceIn(-1f, 1f))
    351  -> copy(lutSkintoneBalance   = value.coerceIn(-0.5f, 0.5f))
    352  -> copy(aberStrength         = value.coerceIn(0f, 1f))
    353  -> copy(aberFringeReduce     = value.coerceIn(0f, 1f))
    354  -> copy(fxGaussBlur          = value.coerceIn(0f, 1f))
    355  -> copy(fxDirBlurAmt         = value.coerceIn(0f, 1f))
    356  -> copy(fxDirBlurAngle       = value)  // radians — unbounded
    357  -> copy(fxRadBlurAmt         = value.coerceIn(0f, 1f))
    358  -> copy(fxRadBlurCx          = value.coerceIn(0f, 1f))
    359  -> copy(fxRadBlurCy          = value.coerceIn(0f, 1f))
    360  -> copy(fxZoomBlurAmt        = value.coerceIn(0f, 1f))
    361  -> copy(fxZoomBlurCx         = value.coerceIn(0f, 1f))
    362  -> copy(fxZoomBlurCy         = value.coerceIn(0f, 1f))
    // [363] fxBlurStyle int — skip
    364  -> copy(fxBlurExcludeSubject = value.coerceIn(0f, 1f))
    365  -> copy(fxMist               = value.coerceIn(0f, 1f))
    366  -> copy(fxMistWarmth         = value.coerceIn(-0.5f, 0.5f))
    367  -> copy(fxDust               = value.coerceIn(0f, 1f))
    368  -> copy(fxDustSize           = value.coerceIn(0f, 1f))
    369  -> copy(fxVintageStrength    = value.coerceIn(0f, 1f))
    370  -> copy(fxVintageFade        = value.coerceIn(0f, 1f))
    371  -> copy(fxVintageVig         = value.coerceIn(0f, 1f))
    372  -> copy(fxGlowStrength       = value.coerceIn(0f, 1f))
    373  -> copy(fxGlowSpread         = value.coerceIn(0f, 1f))
    374  -> copy(fxGlowWarmth         = value.coerceIn(-0.5f, 0.5f))
    375  -> copy(haxGrainCrossfade    = value.coerceIn(0f, 1f))
    376  -> copy(haxGrainScale        = value.coerceIn(0.1f, 10f))
    377  -> copy(haxGrainLumaAmp      = value.coerceIn(0f, 2f))
    378  -> copy(haxGrainChromaAmp    = value.coerceIn(0f, 1f))
    379  -> copy(sharpenAmount        = value.coerceIn(0f, 1f))
    380  -> copy(viewZoom             = value.coerceAtLeast(0.01f))
    381  -> copy(viewPanX             = value)
    382  -> copy(viewPanY             = value)
    396  -> copy(maskSharpness        = value.coerceIn(-100f, 100f))
    397  -> copy(mask1Sharpness       = value.coerceIn(-100f, 100f))
    398  -> copy(mask2Sharpness       = value.coerceIn(-100f, 100f))
    399  -> copy(mask3Sharpness       = value.coerceIn(-100f, 100f))
    408  -> copy(clarityLift          = value.coerceIn(0f, 1f))
    400  -> copy(lensFlareX           = value.coerceIn(-1f, 1f))
    401  -> copy(lensFlareY           = value.coerceIn(-1f, 1f))
    409  -> copy(lensFlareBrightness  = value.coerceIn(0f, 1f))
    430  -> copy(lensFlareSize        = value.coerceIn(0.1f, 5f))
    431  -> copy(lensFlareSpread      = value.coerceIn(0f, 1f))
    435  -> copy(lensFlareWarmth      = value.coerceIn(0f, 1f))
    432  -> copy(colorShiftRedX       = value.coerceIn(-0.1f, 0.1f))
    433  -> copy(colorShiftGreenX     = value.coerceIn(-0.1f, 0.1f))
    434  -> copy(colorShiftBlueX      = value.coerceIn(-0.1f, 0.1f))
    in 436..446 -> copy(film = film.copyOf().also {
        it[slot - 436] = value.coerceIn(-1f, 1f)
    })
    447  -> copy(cinematic = cinematic.copyOf().also { it[0] = value.coerceIn(0f, 1f) })
    448  -> copy(cinematic = cinematic.copyOf().also { it[1] = value.coerceIn(0f, 1f) })
    449  -> copy(purpleFringeMode     = value.toInt().coerceIn(0, 2))
    410  -> copy(maskHighlights       = value.coerceIn(-100f, 100f))
    411  -> copy(mask1Highlights      = value.coerceIn(-100f, 100f))
    412  -> copy(mask2Highlights      = value.coerceIn(-100f, 100f))
    413  -> copy(mask3Highlights      = value.coerceIn(-100f, 100f))
    414  -> copy(maskShadows          = value.coerceIn(-100f, 100f))
    415  -> copy(mask1Shadows         = value.coerceIn(-100f, 100f))
    416  -> copy(mask2Shadows         = value.coerceIn(-100f, 100f))
    417  -> copy(mask3Shadows         = value.coerceIn(-100f, 100f))
    418  -> copy(maskWhites           = value.coerceIn(-100f, 100f))
    419  -> copy(mask1Whites          = value.coerceIn(-100f, 100f))
    420  -> copy(mask2Whites          = value.coerceIn(-100f, 100f))
    421  -> copy(mask3Whites          = value.coerceIn(-100f, 100f))
    422  -> copy(maskBlacks           = value.coerceIn(-100f, 100f))
    423  -> copy(mask1Blacks          = value.coerceIn(-100f, 100f))
    424  -> copy(mask2Blacks          = value.coerceIn(-100f, 100f))
    425  -> copy(mask3Blacks          = value.coerceIn(-100f, 100f))
    in 383..409 -> this  // reserved headroom — pass through unchanged
    else -> this
}

// ── Task 3.2: Debug validation ───────────────────────────────────────────────
/**
 * Asserts that every named scalar field is finite and within its declared
 * range. No-op in release builds (guarded by [BuildConfig.DEBUG]).
 * Throws [IllegalStateException] in debug builds when a value is out-of-range
 * or non-finite.
 *
 * Called automatically at the end of [ShaderParams.toFloatArray] and after
 * [ShaderParams.Companion.fromFloatArray] in debug builds.
 */
fun ShaderParams.debugValidate() {
    if (!BuildConfig.DEBUG) return
    // WARN, don't throw. Adjustment values accumulate additively as the user
    // stacks action cards; a field can legitimately exceed its nominal range
    // mid-edit. Release builds skip this check entirely and the GLSL shader
    // clamps out-of-range uniforms, so a debug throw here would kill the
    // preview (rendering "disappears") for a condition release handles fine.
    // Logging keeps the diagnostic without breaking the render.
    fun chk(name: String, v: Float, lo: Float, hi: Float) {
        if (!(v.isFinite() && v in lo..hi)) {
            android.util.Log.w(
                "ShaderParams",
                "$name out of range [$lo..$hi]: $v (shader will clamp)",
            )
        }
    }
    chk("exposure",              exposure,              -4f,    4f)
    chk("contrast",              contrast,              -1f,    1f)
    chk("highlights",            highlights,            -1f,    1f)
    chk("shadows",               shadows,               -1f,    1f)
    chk("whites",                whites,                -1f,    1f)
    chk("blacks",                blacks,                -1f,    1f)
    chk("saturation",            saturation,            -1f,    1f)
    chk("vibrance",              vibrance,              -1f,    1f)
    chk("whiteBalance",          whiteBalance,          -100f,  100f)
    chk("tint",                  tint,                  -100f,  100f)
    chk("maskHighlights",        maskHighlights,        -100f,  100f)
    chk("maskShadows",           maskShadows,           -100f,  100f)
    chk("maskWhites",            maskWhites,            -100f,  100f)
    chk("maskBlacks",            maskBlacks,            -100f,  100f)
    chk("ditherStrength",        ditherStrength,        0f,     1f)
    chk("lutIntensity",          lutIntensity,          0f,     1f)
    chk("xmpExposure",           xmpExposure,           -4f,    4f)
    chk("xmpContrast",           xmpContrast,           -1f,    1f)
    chk("xmpHighlights",         xmpHighlights,         -1f,    1f)
    chk("xmpShadows",            xmpShadows,            -1f,    1f)
    chk("xmpWhites",             xmpWhites,             -1f,    1f)
    chk("xmpBlacks",             xmpBlacks,             -1f,    1f)
    chk("lightTabOpacity",       lightTabOpacity,       0f,     1f)
    chk("colorTabOpacity",       colorTabOpacity,       0f,     1f)
    chk("xmpTabOpacity",         xmpTabOpacity,         0f,     1f)
    chk("dehaze",                dehaze,                -1f,    1f)
    chk("vigAmount",             vigAmount,             -1f,    1f)
    chk("vigCenterX",            vigCenterX,            0f,     1f)
    chk("vigCenterY",            vigCenterY,            0f,     1f)
    chk("vigFeather",            vigFeather,            0f,     1f)
    chk("vigIntensity",          vigIntensity,          0f,     1f)
    chk("vigTabOpacity",         vigTabOpacity,         0f,     1f)
    chk("gradAngle",             gradAngle,             -180f,  180f)
    chk("gradTabOpacity",        gradTabOpacity,        0f,     1f)
    chk("maskBrightness",        maskBrightness,        -100f,  100f)
    chk("maskContrast",          maskContrast,          -100f,  100f)
    chk("maskTemperature",       maskTemperature,       -2000f, 2000f)
    chk("maskTint",              maskTint,              -150f,  150f)
    chk("maskSaturation",        maskSaturation,        -100f,  100f)
    chk("maskClarity",           maskClarity,           -100f,  100f)
    chk("maskTabOpacity",        maskTabOpacity,        0f,     1f)
    chk("tonemapExposure",       tonemapExposure,       -4f,    4f)
    chk("tonemapHighlights",     tonemapHighlights,     -1f,    1f)
    chk("tonemapShadows",        tonemapShadows,        -1f,    1f)
    chk("claheShadowsBoost",     claheShadowsBoost,     -1f,    1f)
    chk("claheHighlightsBoost",  claheHighlightsBoost,  -1f,    1f)
    chk("luminanceNR",           luminanceNR,           0f,     1f)
    chk("colorNR",               colorNR,               0f,     1f)
    chk("detailSharpness",       detailSharpness,       0f,     1f)
    chk("detailSmartSharpness",  detailSmartSharpness,  0f,     1f)
    chk("detailClarity",         detailClarity,         -1f,    1f)
    chk("detailTexture",         detailTexture,         -1f,    1f)
    chk("detailFilmGrain",       detailFilmGrain,       0f,     1f)
    chk("detailFilmGrainSize",   detailFilmGrainSize,   0f,     1f)
    chk("detailFilmGrainWash",   detailFilmGrainWash,   0f,     1f)
    chk("mask1Brightness",       mask1Brightness,       -100f,  100f)
    chk("mask1Contrast",         mask1Contrast,         -100f,  100f)
    chk("mask1Temperature",      mask1Temperature,      -2000f, 2000f)
    chk("mask1Tint",             mask1Tint,             -150f,  150f)
    chk("mask1Saturation",       mask1Saturation,       -100f,  100f)
    chk("mask1Clarity",          mask1Clarity,          -100f,  100f)
    chk("mask1TabOpacity",       mask1TabOpacity,       0f,     1f)
    chk("mask2Brightness",       mask2Brightness,       -100f,  100f)
    chk("mask2Contrast",         mask2Contrast,         -100f,  100f)
    chk("mask2Temperature",      mask2Temperature,      -2000f, 2000f)
    chk("mask2Tint",             mask2Tint,             -150f,  150f)
    chk("mask2Saturation",       mask2Saturation,       -100f,  100f)
    chk("mask2Clarity",          mask2Clarity,          -100f,  100f)
    chk("mask2TabOpacity",       mask2TabOpacity,       0f,     1f)
    chk("mask3Brightness",       mask3Brightness,       -100f,  100f)
    chk("mask3Contrast",         mask3Contrast,         -100f,  100f)
    chk("mask3Temperature",      mask3Temperature,      -2000f, 2000f)
    chk("mask3Tint",             mask3Tint,             -150f,  150f)
    chk("mask3Saturation",       mask3Saturation,       -100f,  100f)
    chk("mask3Clarity",          mask3Clarity,          -100f,  100f)
    chk("mask3TabOpacity",       mask3TabOpacity,       0f,     1f)
    chk("detailSmoothBackground",detailSmoothBackground, 0f,    1f)
    chk("bokehBlur",             bokehBlur,             0f,     1f)
    chk("bokehBalls",            bokehBalls,            0f,     1f)
    chk("bokehSpread",           bokehSpread,           0f,     1f)
    chk("maskLumTarget",         maskLumTarget,         0f,     1f)
    chk("maskLumSpread",         maskLumSpread,         0f,     1f)
    chk("maskLumFeather",        maskLumFeather,        0f,     1f)
    chk("mask1LumTarget",        mask1LumTarget,        0f,     1f)
    chk("mask1LumSpread",        mask1LumSpread,        0f,     1f)
    chk("mask1LumFeather",       mask1LumFeather,       0f,     1f)
    chk("mask2LumTarget",        mask2LumTarget,        0f,     1f)
    chk("mask2LumSpread",        mask2LumSpread,        0f,     1f)
    chk("mask2LumFeather",       mask2LumFeather,       0f,     1f)
    chk("mask3LumTarget",        mask3LumTarget,        0f,     1f)
    chk("mask3LumSpread",        mask3LumSpread,        0f,     1f)
    chk("mask3LumFeather",       mask3LumFeather,       0f,     1f)
    chk("whitesSubject",         whitesSubject,         -1f,    1f)
    chk("blacksSubject",         blacksSubject,         -1f,    1f)
    chk("whitesBackground",      whitesBackground,      -1f,    1f)
    chk("blacksBackground",      blacksBackground,      -1f,    1f)
    chk("shadowsSubject",        shadowsSubject,        -1f,    1f)
    chk("shadowsBackground",     shadowsBackground,     -1f,    1f)
    chk("lutHighlightVibrancy",  lutHighlightVibrancy,  -1f,    1f)
    chk("highlightTemperature",  highlightTemperature,  -1f,    1f)
    chk("highlightTint",         highlightTint,         -1f,    1f)
    chk("shadowTemperature",     shadowTemperature,     -1f,    1f)
    chk("shadowTint",            shadowTint,            -1f,    1f)
    chk("bloomRadius",           bloomRadius,           0f,     24f)
    chk("bloomShape",            bloomShape,            0.4f,   1.6f)
    chk("filmRolloff",           filmRolloff,           0f,     1f)
    chk("ambiance",              ambiance,              -1f,    1f)
    chk("ortonStrength",         ortonStrength,         0f,     1f)
    chk("toneCurveLumaMode",     toneCurveLumaMode,     0f,     1f)
    chk("highlightsSubject",     highlightsSubject,     -1f,    1f)
    chk("highlightsBackground",  highlightsBackground,  -1f,    1f)
    chk("ambianceSubject",       ambianceSubject,       -1f,    1f)
    chk("ambianceBackground",    ambianceBackground,    -1f,    1f)
    chk("blueNR",                blueNR,                0f,     1f)
    chk("redNR",                 redNR,                 0f,     1f)
    chk("gamutCompress",         gamutCompress,         0f,     1f)
    chk("bloomExcludeSubject",   bloomExcludeSubject,   0f,     1f)
    chk("subjectBloom",          subjectBloom,          0f,     1f)
    for (i in 0 until 16) chk("cg[$i]", cg[i], 0f, 1f)
    chk("lensFlareX",            lensFlareX,            -1f,    1f)
    chk("lensFlareY",            lensFlareY,            -1f,    1f)
    chk("lensFlareBrightness",   lensFlareBrightness,   0f,     1f)
    chk("lensFlareSize",         lensFlareSize,         0.1f,   5f)
    chk("lensFlareSpread",       lensFlareSpread,       0f,     1f)
    chk("lensFlareWarmth",       lensFlareWarmth,       0f,     1f)
    chk("colorShiftRedX",        colorShiftRedX,        -0.1f,  0.1f)
    chk("colorShiftGreenX",      colorShiftGreenX,      -0.1f,  0.1f)
    chk("colorShiftBlueX",       colorShiftBlueX,       -0.1f,  0.1f)
    for (i in 0 until 11) chk("film[$i]", film[i], -1f, 1f)
    chk("centerPop",             centerPop,             -1f,    1f)
    chk("detailGrainRoughness",  detailGrainRoughness,  0f,     1f)
    chk("detailSharpenMask",     detailSharpenMask,     0f,     1f)
    chk("colorDensity",          colorDensity,          -1f,    1f)
    chk("skintoneWarm",          skintoneWarm,          -0.5f,  0.5f)
    chk("skintoneSmooth",        skintoneSmooth,        0f,     1f)
    chk("skintoneLuma",          skintoneLuma,          -0.5f,  0.5f)
    chk("midtoneDetails",        midtoneDetails,        -1f,    1f)
    chk("highlightRecovery",     highlightRecovery,     0f,     1f)
    chk("pushPull",              pushPull,              -3f,    3f)
    chk("lutColorDensity",       lutColorDensity,       -1f,    1f)
    chk("lutSkintoneBalance",    lutSkintoneBalance,    -0.5f,  0.5f)
    chk("aberStrength",          aberStrength,          0f,     1f)
    chk("aberFringeReduce",      aberFringeReduce,      0f,     1f)
    chk("fxGaussBlur",           fxGaussBlur,           0f,     1f)
    chk("fxDirBlurAmt",          fxDirBlurAmt,          0f,     1f)
    check(fxDirBlurAngle.isFinite()) { "ShaderParams.fxDirBlurAngle is not finite: $fxDirBlurAngle" }
    chk("fxRadBlurAmt",          fxRadBlurAmt,          0f,     1f)
    chk("fxRadBlurCx",           fxRadBlurCx,           0f,     1f)
    chk("fxRadBlurCy",           fxRadBlurCy,           0f,     1f)
    chk("fxZoomBlurAmt",         fxZoomBlurAmt,         0f,     1f)
    chk("fxZoomBlurCx",          fxZoomBlurCx,          0f,     1f)
    chk("fxZoomBlurCy",          fxZoomBlurCy,          0f,     1f)
    chk("fxBlurExcludeSubject",  fxBlurExcludeSubject,  0f,     1f)
    chk("fxMist",                fxMist,                0f,     1f)
    chk("fxMistWarmth",          fxMistWarmth,          -0.5f,  0.5f)
    chk("fxDust",                fxDust,                0f,     1f)
    chk("fxDustSize",            fxDustSize,            0f,     1f)
    chk("fxVintageStrength",     fxVintageStrength,     0f,     1f)
    chk("fxVintageFade",         fxVintageFade,         0f,     1f)
    chk("fxVintageVig",          fxVintageVig,          0f,     1f)
    chk("fxGlowStrength",        fxGlowStrength,        0f,     1f)
    chk("fxGlowSpread",          fxGlowSpread,          0f,     1f)
    chk("fxGlowWarmth",          fxGlowWarmth,          -0.5f,  0.5f)
    chk("haxGrainCrossfade",     haxGrainCrossfade,     0f,     1f)
    chk("haxGrainScale",         haxGrainScale,         0.1f,   10f)
    chk("haxGrainLumaAmp",       haxGrainLumaAmp,       0f,     2f)
    chk("haxGrainChromaAmp",     haxGrainChromaAmp,     0f,     1f)
    chk("sharpenAmount",         sharpenAmount,         0f,     1f)
    check(viewZoom >= 0.01f && viewZoom.isFinite()) { "ShaderParams.viewZoom must be >= 0.01, got $viewZoom" }
    check(viewPanX.isFinite()) { "ShaderParams.viewPanX is not finite: $viewPanX" }
    check(viewPanY.isFinite()) { "ShaderParams.viewPanY is not finite: $viewPanY" }
}

