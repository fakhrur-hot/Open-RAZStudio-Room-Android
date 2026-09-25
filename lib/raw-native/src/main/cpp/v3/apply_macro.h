/*
 * StudioRoom — RAW Pipeline v3 — Shared per-pixel kernel (M8).
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  Single source of truth for the pixel math run by:
 *    • GLSL uber.frag at slider time (editor canvas)        — M4
 *    • This C++ kernel at Stage C export time (saved file)  — M8
 *
 *  The two MUST produce identical output for the same input + ShaderParams,
 *  or saved files won't match the preview. Any change here must change
 *  uber.frag in lock-step.
 *
 *  Layout matches gles_renderer.cpp's GLSL exactly:
 *    1. applyExposureContrast
 *    2. applyWbTint
 *    3. applyToneRegions
 *    4. applySaturationVibrance
 *    5. applyHslShifts
 *    6. clamp [0, 1]
 *    7. (if uXmpEnabled) XMP overlay
 *    8. 3D LUT trilinear sample (if uLutEnabled + LUT data present)
 *
 *  Spatial ops (NR, sharpness, vignette, clarity, film grain) are NOT in
 *  this header. They run in a deferred second pass (M7 stop-drag for the
 *  editor; future M10 for Stage C).
 *
 *  Dither: NOT in this header. M8 outputs TIFF-16 (16-bit per channel) so
 *  dither isn't needed. M9 will add 8-bit formats (JPG / PNG-8 / WebP /
 *  HEIC / AVIF) and will dither at quantization.
 * ─────────────────────────────────────────────────────────────────────────────
 */

#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace raw_v3 {

// 57-float ShaderParams blob (see gles_renderer.h + ShaderParams.kt).
struct ApplyMacroParams {
    float exposure;
    float contrast;
    float highlights;
    float shadows;
    float whites;
    float blacks;
    // Per-segment levels (Normalize for 3Dlut). Slider domain -100..+100;
    // gated by the subject mask in applyMacroPixelImpl.
    float whitesSubject = 0.f;
    float blacksSubject = 0.f;
    float whitesBackground = 0.f;
    float blacksBackground = 0.f;
    float shadowsSubject    = 0.f;
    float shadowsBackground = 0.f;
    // Per-segment highlights + ambiance (Auto Expo writes these by scaling
    // global values by each region's channel-clip percentage). Were preview-
    // only; export now mirrors the shader to fix preview-vs-saved parity.
    float highlightsSubject    = 0.f;
    float highlightsBackground = 0.f;
    float ambianceSubject      = 0.f;
    float ambianceBackground   = 0.f;
    float saturation;
    float vibrance;
    float whiteBalance;
    float tint;
    float hsl[18];           // [10..27] R/O/Y/G/A/B × (h, s, l), each [-1, +1]
    float hsl2[18];          // [211..228] YG/SG/SB/Pu/Ma/Pi × (h, s, l)
    bool  lutEnabled;
    float lutIntensity;      // [0, 1] mix between input and LUT-sampled colour
    int   lutBwForce = 0;    // [450] B&W pack: mix from achromatic luma
    float lutHighlightVibrancy = 0.f; // [-1, +1] — see ShaderParams.kt slot [200]
    float highlightTemperature = 0.f; // [201]
    float highlightTint        = 0.f; // [202]
    float shadowTemperature    = 0.f; // [203]
    float shadowTint           = 0.f; // [204]
    float glowStrength   = 0.f; // [205]
    float glowSaturation = 0.f; // [206]
    float glowWarmth     = 0.f; // [207]
    float ambiance       = 0.f; // [208]
    float ortonStrength        = 0.f; // [209] — Orton soft-focus bloom
    float bloomExcludeSubject  = 0.f; // [238] — 1 = route bg/subject to separate strengths
    float subjectBloom         = 0.f; // [239] — subject-only bloom strength
    float bloomRadius    = 8.f; // [205] — Karis-equivalent radius in px, 0..24
    float bloomShape     = 1.f; // [206] — anamorphic ratio, 0.4..1.6 (1 = circular)
    bool  xmpEnabled;
    float xmpExposure;
    float xmpContrast;
    float xmpHighlights;
    float xmpShadows;
    float xmpWhites;
    float xmpBlacks;
    float xmpHsl[18];
    // M12.1 — per-tab opacity (fan-out compositing). Defaults to 1.0.
    float lightTabOpacity;
    float colorTabOpacity;
    float xmpTabOpacity;
    float dehaze;
    // M12.2b vignette block
    float vigAmount;
    float vigCenterX;
    float vigCenterY;
    float vigFeather;
    float vigIntensity;
    float vigEffect;
    float vigTabOpacity;

    // M12.2b.2 Gradient block — see ShaderParams.kt for slot layout.
    float gradAngle;
    float gradTop[15];
    float gradBottom[15];
    float gradLeft[15];
    float gradRight[15];
    float gradTabOpacity;
    // M12.2c.1 — per-side Gradient segmentation targets.
    //   Stored as SegmentTarget ordinal (0/1/2). Floats for blob symmetry.
    float gradTopApplyTo;
    float gradBottomApplyTo;
    float gradLeftApplyTo;
    float gradRightApplyTo;
    // Per-side gradient tint blend mode: 0=Solid (light-leak screen+add), 1=Fused (overlay).
    float gradTopBlendMode;
    float gradBottomBlendMode;
    float gradLeftBlendMode;
    float gradRightBlendMode;
    // M12.2c.2 — Mask tab adjustments + tab opacity. Layer 0 (legacy single
    // mask). Layers 1..3 live in maskLayer[] below. Kept as named scalars for
    // source compatibility with the existing per-pixel kernel; the multi-layer
    // Stage C path reads maskLayer[] (index 0 mirrors these).
    float maskBrightness;
    float maskContrast;
    float maskTemperature;
    float maskTint;
    float maskSaturation;
    float maskClarity;
    float maskBanding = 0.f; // [501] 0..1 flat smooth inside the painted mask
    float maskTabOpacity;
    // Tonemap-tab tone region (additive on top of Light-tab).
    float tonemapExposure;
    float tonemapHighlights;
    float tonemapShadows;

    // Phase-1 backport: effects previously GL-only (slots match ShaderParams.kt).
    float filmRolloff      = 0.f;  // [207] highlight shoulder 0..1
    float filmicLuma       = 0.f;  // [451] luminance filmic 0..1
    float oklabHlChroma    = 0.f;  // [452] OKLab HL chroma 0..1
    float gamutCompress    = 0.f;  // [237] gamut compress 0..1
    // Color grading wheels [240..251]. Tint defaults 0.5 = neutral.
    float cgShadowsR       = 0.5f; // [240]
    float cgShadowsG       = 0.5f; // [241]
    float cgShadowsB       = 0.5f; // [242]
    float cgShadowsSat     = 0.f;  // [243]
    float cgMidtonesR      = 0.5f; // [244]
    float cgMidtonesG      = 0.5f; // [245]
    float cgMidtonesB      = 0.5f; // [246]
    float cgMidtonesSat    = 0.f;  // [247]
    float cgHighlightsR    = 0.5f; // [248]
    float cgHighlightsG    = 0.5f; // [249]
    float cgHighlightsB    = 0.5f; // [250]
    float cgHighlightsSat  = 0.f;  // [251]

    // ── Film response (LUT tab) ───────────────────────────────────────
    // Adobe legacy Recovery / FillLight + the 8-channel B&W GrayMixer.
    // Bipolar: >0 is Adobe's own direction, <0 is the reverse.
    float filmRecovery  = 0.f;   // [436] -1..1
    float filmFillLight = 0.f;   // [437] -1..1
    int   filmMonochrome = 0;    // [438] Adobe ConvertToGrayscale
    float filmGrayMix[8] = {0,0,0,0,0,0,0,0}; // [439..446] R,O,Y,G,Aq,B,Pu,Ma

    float cgGlobalR        = 0.5f; // [426] Global/Offset wheel
    float cgGlobalG        = 0.5f; // [427]
    float cgGlobalB        = 0.5f; // [428]
    float cgGlobalSat      = 0.f;  // [429]
    float centerPop        = 0.f;  // [252]
    float hslFull[24]      = {};   // [253..276] 8 anchors × (hShift,sShift,lShift)
    float detailGrainRoughness = 0.f; // [341]
    float colorDensity     = 0.f;  // [343]
    float filmSeparation   = 0.f;  // [500] OKLCh chroma −1..+1
    float skintoneWarm     = 0.f;  // [344]
    float skintoneSmooth   = 0.f;  // [345]
    float skintoneLuma     = 0.f;  // [346]
    float pushPull         = 0.f;  // [349]

    // FX tab (PREQ-Port, slots 352–374). Preview/export parity: these were
    // GL-only; Stage C now mirrors the uber-shader's Effects tab block.
    float aberStrength       = 0.f; // [352]
    float aberFringeReduce   = 0.f; // [353]
    float fxGaussBlur        = 0.f; // [354]
    float fxDirBlurAmt       = 0.f; // [355]
    float fxDirBlurAngle     = 0.f; // [356]
    float fxRadBlurAmt       = 0.f; // [357]
    float fxRadBlurCx        = 0.5f;// [358]
    float fxRadBlurCy        = 0.5f;// [359]
    float fxZoomBlurAmt      = 0.f; // [360]
    float fxZoomBlurCx       = 0.5f;// [361]
    float fxZoomBlurCy       = 0.5f;// [362]
    float fxBlurStyle        = 0.f; // [363] 0=off,1=Gauss,2=Dir,3=Rad,4=Zoom
    float fxBlurExcludeSubject = 0.f; // [364]
    float fxMist             = 0.f; // [365]
    float fxMistWarmth       = 0.f; // [366]
    float fxDust             = 0.f; // [367]
    float fxDustSize         = 0.f; // [368]
    float fxVintageStrength  = 0.f; // [369]
    float fxVintageFade      = 0.f; // [370]
    float fxVintageVig       = 0.f; // [371]
    float fxVintageMistIntensity = 0.f; // [454]
    float fxVintageMistScale     = 1.f; // [455]
    float fxVintageTextureIntensity = 0.f; // [456]
    float fxVintageTextureScale  = 1.f; // [457]
    float fxGlowStrength     = 0.f; // [372]
    float fxGlowSpread       = 0.f; // [373]
    float fxGlowWarmth       = 0.f; // [374]

    // Filmic / Pro-Mist bloom (append-only ABI).
    float mistTightness      = 0.55f; // [447] mip1↔mip2 bias
    float mistHalation       = 0.f;   // [448] R/B channel offset 0..1
    float opticalSpread      = 0.f;   // [461]
    float opticalHalation    = 0.f;   // [462]
    float opticalDirection   = 0.f;   // [463]
    float highlightStart     = 0.78f; // [484]
    float highlightEnd       = 0.98f; // [485]

    // OpenShot lens flare (procedural additive).
    float lensFlareX          = -0.5f; // [400]
    float lensFlareY          = -0.5f; // [401]
    float lensFlareBrightness = 0.f;   // [409]
    float lensFlareSize       = 1.f;   // [430]
    float lensFlareSpread     = 1.f;   // [431]
    float lensFlareWarmth     = 0.f;   // [435]
    float lensFlareDistance   = 1.f;   // [464] 0 far .. 1 near
    float lensFlareHood       = 0.f;   // [465] 0..1
    float sceneDistance       = 0.5f;  // [466] 0 far .. 1 near
    float shadowStrength      = 0.f;   // [467] 0..1
    float shadowSoftness      = 0.5f;  // [468] 0..1
    float starburst           = 0.f;   // [480] 0..1
    float irisBlades          = 0.f;   // [481]
    float irisRotation        = 0.f;   // [482]
    float irisRoundness       = 1.f;   // [483]
    // OpenShot ColorShift (horizontal RGB split, uv-fraction offset).
    float colorShiftRedX      = 0.f;   // [432]
    float colorShiftGreenX    = 0.f;   // [433]
    float colorShiftBlueX     = 0.f;   // [434]

    // Haxademic film grain extension (slots 375–378). Stage C CPU parity
    // with the GLSL uber-shader's haxGrain layer. Existing cinematicGrain
    // (detailGrainRoughness) path is unaffected.
    float haxGrainCrossfade = 0.f; // [375] blend strength, 0 = bypass
    float haxGrainScale     = 1.f; // [376] UV scale multiplier
    float haxGrainLumaAmp   = 1.f; // [377] luma grain amplitude
    float haxGrainChromaAmp = 0.f; // [378] chroma grain amplitude, 0 = mono

    // LUT gamut: workspace colour space (libraw value) and the space the LUT
    // was authored in. Matches slots 235/236 in the GLSL shader.
    // workspaceSpace: 1=sRGB, 2=AdobeRGB, 4=ProPhoto, 7=DCI-P3, 8=Rec.2020
    // lutAuthoredSpace ordinal: 0=Rec.709(sRGB), 1=ProPhoto, 2=ACES(→sRGB no-op), 3=DCI-P3
    int workspaceSpace    = 1; // [235]
    int lutAuthoredSpace  = 0; // [236]

    // Multi-layer mask (M12.2c.2b). 4 layers; [0] mirrors the scalars above.
    // Each layer: brightness, contrast, temperature, tint, saturation, clarity,
    // opacity (7 floats). Stage C applies each gated by its own PNG mask.
    struct MaskLayer {
        float brightness = 0.f;
        float contrast   = 0.f;
        float temperature= 0.f;
        float tint       = 0.f;
        float saturation = 0.f;
        float clarity    = 0.f;
        float sharpness  = 0.f;  // [-100..100] masked high-freq unsharp; slots [396..399]
        // Tone-region adjustments (mirror the global Tone tab), per layer.
        // [-100..100]; applied via applyToneRegionsP inside the mask. Slots:
        // highlights [410..413], shadows [414..417], whites [418..421],
        // blacks [422..425] (append-only ABI).
        float highlights = 0.f;
        float shadows    = 0.f;
        float whites     = 0.f;
        float blacks     = 0.f;
        float opacity    = 1.f;
        // Luminance-range mask. spread > 0 → generate mask from graded luma
        // (target tone), replacing the brush. target/spread/feather [0..1].
        float lumTarget  = 0.f;
        float lumSpread  = 0.f;  // 0 = off (use brush)
        float lumFeather = 0.f;
        // Combine mode for a live luma band + this layer's bitmap when BOTH are
        // present. Mirrors gles_renderer MaskLayer::lumCombine (keep in sync):
        // 0=luma wins, 1=luma−bitmap, 2=bitmap−luma, 3=union, 4=intersect. [395].
        int   lumCombine = 0;
    };
    static constexpr int kMaskLayers = 4;
    MaskLayer maskLayer[kMaskLayers];

    // Smart Color Enhancement ("Color Pop") — slots [384..390]. enable flag +
    // per-channel auto-WB stretch min/max derived from the Stage A 256px
    // thumbnail. Defaults (0 / 1) = identity stretch. Applied FIRST per pixel,
    // mirroring the GL preview (gles_renderer main()), so the save matches the
    // canvas. See applySmartColorEnhanceP.
    float smartColorEnhance = 0.f;   // [384]
    float smartWbRMin = 0.f;         // [385]
    float smartWbRMax = 1.f;         // [386]
    float smartWbGMin = 0.f;         // [387]
    float smartWbGMax = 1.f;         // [388]
    float smartWbBMin = 0.f;         // [389]
    float smartWbBMax = 1.f;         // [390]

    // Purple-fringe Strong mode — slot [449]. 0/1 = off, 2 = run.
    // Neighbour clip gate needs srcBuf on the full applyMacroPixel overload.
    float purpleFringeMode = 0.f;    // [449]

    /** Decode from the wire layout (back-compat for older blob sizes). */
    static ApplyMacroParams fromFloatArray(const float* arr, int count);

    // 256-entry per-channel tone curve LUT (768 bytes: R0,G0,B0, R1,G1,B1 ...).
    // Applied after tonal-zone WB trims, BEFORE gamutCompress + highlight knee,
    // matching GL shader order: toneregions → WB-trims → toneCurve → grain →
    // gamutCompress → clamp → knee. Not part of the ShaderParams float blob;
    // set by Stage C directly from StageCOptions.toneCurveLut. nullptr = skip.
    const uint8_t* toneCurveLut = nullptr;
    // Slot [210]. false = per-channel curve (each channel indexes its own LUT
    // column); true = LUMA curve — remap L only and shift all three channels by
    // the same delta, which keeps chroma instead of desaturating at the ends.
    // The GL shader has had this branch since the curve tab shipped; the CPU
    // export kernel did not, so a photo curved in Luma mode saved with visibly
    // different saturation than the preview (fixed 2026-09-07).
    bool toneCurveLumaMode = false;
    // Slot [499]. Curves-tab highlight shoulder, 0..1. Applied to luma after
    // the tone LUT. 0 skips it.
    float filmHighlightKnee = 0.f;
};

/** Optional U2Net subject mask for Stage C. Row-major [0,1] floats.
 *  Caller leaves data=nullptr when no segmentation is available; the
 *  kernel falls back to All gating.
 *
 *  rectU0/V0/U1/V1: letterbox rect (same semantics as GL uSubjectMaskRect).
 *  The 320×320 mask was generated from a square-padded copy of the image;
 *  these coords record where the actual image content lives inside that square.
 *  sampleSubjectMask* remaps pixel (u,v) through this rect before sampling,
 *  matching mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord) in
 *  the GL shader. Defaults to (0,0,1,1) = identity (square source or unset). */
struct ApplyMacroSubjectMask {
    const float* data = nullptr;
    int w = 0;
    int h = 0;
    float rectU0 = 0.f;
    float rectV0 = 0.f;
    float rectU1 = 1.f;
    float rectV1 = 1.f;
};

/** M12.2c.2b — Up to 4 brush-mask layers for Stage C export. Each layer is
 *  a row-major [0,1] alpha buffer (one mask PNG, alpha channel decoded to
 *  float). Layers with data=nullptr are skipped. The kernel samples each
 *  layer bilinearly at (u,v) and applies maskLayer[i] gated by that alpha,
 *  mirroring the GLSL multi-layer compositing. */
struct ApplyMacroMaskLayer {
    const float* data = nullptr;   // [w×h] alpha in [0,1], row-major
    int w = 0;
    int h = 0;
};
struct ApplyMacroMaskLayers {
    static constexpr int kCount = 4;
    ApplyMacroMaskLayer layer[kCount];
};

/** Optional 3D LUT bound by the export pipeline. Sample order matches GLES
 *  GL_TEXTURE_3D: r-stride-1, g-stride-size, b-stride-size² (B outermost). */
struct ApplyMacroLut {
    const float* data = nullptr;   // size³ × 3 floats, RGB
    int size = 0;
    float domainMin[3] = {0.f, 0.f, 0.f};
    float domainMax[3] = {1.f, 1.f, 1.f};
};

/**
 * Run the per-pixel kernel on a single linear-light gamma-encoded sRGB
 * triplet. All channels in [0, 1] (slightly outside ok pre-clamp). Output
 * also in [0, 1] post-clamp.
 *
 *  in_out[0]  red
 *  in_out[1]  green
 *  in_out[2]  blue
 */
/**
 * Per-pixel kernel.
 *
 * @param in_out RGB triplet, [0..1].
 * @param u Normalised x in [0..1] (image-relative). Required by the
 *          vignette pass; pass any value when vignette is unused.
 * @param v Normalised y in [0..1].
 */
void applyMacroPixel(float* in_out,
                     float u, float v,
                     const ApplyMacroParams& p,
                     const ApplyMacroLut* lut);

/**
 * Back-compat overload — defaults UV to (0.5, 0.5). Callers that don't
 * use vignette can keep their existing call site unchanged.
 */
void applyMacroPixel(float* in_out,
                     const ApplyMacroParams& p,
                     const ApplyMacroLut* lut);

/**
 * M12.2c.1 — UV-aware kernel WITH subject mask. Used by Stage C export
 * when segmentation is available. Bilinearly samples [mask] at (u,v),
 * squares the result, and uses it to gate Vignette + Gradient per the
 * SegmentTarget ordinals stored in [p].
 */
void applyMacroPixel(float* in_out,
                     float u, float v,
                     const ApplyMacroParams& p,
                     const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask);

/**
 * M12.2c.2b — full kernel WITH subject mask AND up to 4 brush-mask layers.
 * Used by Stage C export so the saved file matches the GL preview's
 * multi-layer Mask tab. Each [maskLayers->layer[i]] (when present) is
 * sampled bilinearly at (u,v); maskLayer[i]'s adjustments are applied to a
 * copy of the base pixel and the delta is accumulated, weighted by the
 * sampled alpha × the layer opacity — exactly the GLSL loop.
 */
void applyMacroPixel(float* in_out,
                     float u, float v,
                     const ApplyMacroParams& p,
                     const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers);

/**
 * Ambiance/Orton-aware overload. [blurRgb] is the corresponding 3-float
 * pixel from a Gaussian-blurred copy of the input — same blur the shader's
 * uBlurTex provides. Skip with nullptr to fall back to the legacy
 * no-ambiance/no-Orton behaviour.
 */
void applyMacroPixel(float* in_out,
                     float u, float v,
                     const ApplyMacroParams& p,
                     const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb);

/**
 * Editor-equivalent overload that accepts a separate Karis pyramid bloom
 * reference for Orton (matching the GL editor's uBloomTex), while ambiance
 * keeps using the Gaussian [blurRgb]. Pass [bloomRgb]=nullptr to fall back
 * to the legacy Gaussian-for-everything behaviour.
 */
void applyMacroPixel(float* in_out,
                     float u, float v,
                     const ApplyMacroParams& p,
                     const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb,
                     const float* bloomRgb);

/**
 * Full editor-equivalent overload that also exposes the complete Gaussian
 * blur buffer for multi-tap FX blur effects (directional/radial/zoom).
 * [blurBuf] is a packed RGBF32 image of size [blurW]x[blurH]; pass nullptr
 * to fall back to single-tap Gaussian style.
 */
void applyMacroPixel(float* in_out,
                     float u, float v,
                     const ApplyMacroParams& p,
                     const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb,
                     const float* bloomRgb,
                     const float* blurBuf,
                     int blurW, int blurH);

/** Same as above, plus full bloom buffer for Pro-Mist R/B halation sampling. */
void applyMacroPixel(float* in_out,
                     float u, float v,
                     const ApplyMacroParams& p,
                     const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb,
                     const float* bloomRgb,
                     const float* blurBuf,
                     int blurW, int blurH,
                     const float* bloomBuf);

/** Full overload + Orton/bokeh sky-attenuation mask (Cityscapes max(sky,terrain)).
 *  [atten] uses the same letterbox rect as the subject mask. nullptr = off. */
void applyMacroPixel(float* in_out,
                     float u, float v,
                     const ApplyMacroParams& p,
                     const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb,
                     const float* bloomRgb,
                     const float* blurBuf,
                     int blurW, int blurH,
                     const float* bloomBuf,
                     const ApplyMacroSubjectMask* atten);

/** Same + explicit source plane for purple-fringe neighbour samples (slot [449]). */
void applyMacroPixel(float* in_out,
                     float u, float v,
                     const ApplyMacroParams& p,
                     const ApplyMacroLut* lut,
                     const ApplyMacroSubjectMask* mask,
                     const ApplyMacroMaskLayers* maskLayers,
                     const float* blurRgb,
                     const float* bloomRgb,
                     const float* blurBuf,
                     int blurW, int blurH,
                     const float* bloomBuf,
                     const ApplyMacroSubjectMask* atten,
                     const float* srcBuf,
                     int srcW, int srcH);

/** Process-wide baked vintage overlays (RGBA8). Filled from Kotlin PNG decode. */
struct VintageOverlayBake {
    std::vector<uint8_t> rgba;
    int w = 0;
    int h = 0;
    bool empty() const { return rgba.empty() || w <= 0 || h <= 0; }
};
struct VintageFxBake {
    VintageOverlayBake mist;
    VintageOverlayBake film;
};
VintageFxBake& vintageFxBake();
void setVintageFxBake(bool film, const uint8_t* bytes, int nbytes, int width, int height);

/** In-place RGB flare for a caller-owned bitmap. Intensity 0 is a no-op. */
void applyLensFlareImage(float* rgb, int w, int h,
                         float fx, float fy, float bright, float size, float spread,
                         float warmth, float distanceZ, float hood,
                         float starburst = 0.f, float blades01 = 0.f,
                         float irisRot = 0.f, float roundness = 1.f);

}  // namespace raw_v3
