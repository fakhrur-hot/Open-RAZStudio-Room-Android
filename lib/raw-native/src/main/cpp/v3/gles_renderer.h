/*
 * StudioRoom — RAW Pipeline v3 — GLES 3.0 renderer.
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Owns an EGL display + context + window surface for one editor session.
 * Samples a single AHardwareBuffer (zero-copy) and draws it to the
 * SurfaceView with a pass-through fragment shader (M3). The full uber-shader
 * lands in M4–M5.
 */

#pragma once

#include <vector>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>          // PFNGLEGLIMAGETARGETTEXTURE2DOESPROC, GLeglImageOES
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <pthread.h>
#include <cstdint>   // uint8_t — explicit; the NDK headers above leak it on Android only

namespace raw_v3 {

// Uniform layout — must match Kotlin ShaderParams.toFloatArray and GLSL
// uniform declarations. 32 floats for M4 (offsets 0..31). M5.5 appends a 25-
// float XMP overlay block at 32..56, leaving M4 offsets stable. Total: 57.
struct ShaderParams {
    float exposure       = 0.f;   // [0]
    float contrast       = 0.f;   // [1]
    float highlights     = 0.f;   // [2]
    float shadows        = 0.f;   // [3]
    float whites         = 0.f;   // [4]
    float blacks         = 0.f;   // [5]
    float saturation     = 0.f;   // [6]
    float vibrance       = 0.f;   // [7]
    float whiteBalance   = 0.f;   // [8]
    float tint           = 0.f;   // [9]
    float hsl[18]        = {0};   // [10..27]  R/O/Y/G/A/B × (h,s,l)
    float hsl2[18]       = {0};   // [211..228] YG/SG/SB/Pu/Ma/Pi × (h,s,l) — Color Zones expansion
    float ditherStrength = 1.f;   // [28]
    // Purple-fringe pass mode: 0=Off, 1=Light (no-op in shader), 2=Strong (runs).
    // Wired from WorkspaceConfig.colorFringingMode.ordinal. Slot [449] —
    // must NOT share [199] (that is shadowsBackground sole owner).
    float purpleFringeMode = 0.f; // [449]
    float lutEnabled     = 0.f;   // [29]  0/1
    float gamutOut       = 0.f;   // [30]  0=sRGB 1=DisplayP3
    // Workspace + LUT-authored colour spaces (slots [235] / [236]).
    // workspaceSpace mirrors LibRawOutputColor.librawValue (1=sRGB,
    // 2=AdobeRGB, 4=ProPhoto, 7=DCI-P3, 8=Rec.2020). lutAuthoredSpace
    // mirrors LutInputSpace.ordinal (0=Rec.709, 1=ProPhoto, 2=ACES,
    // 3=DCI-P3). Both feed the LUT gamut-transform shader code.
    int   workspaceSpace    = 1;  // sRGB default — backward compat
    int   lutAuthoredSpace  = 0;  // Rec.709 default — matches most consumer LUTs
    float lutIntensity   = 1.f;   // [31]  0..1 mix between input and LUT-sampled colour
    int   lutBwForce     = 0;     // [450] B&W pack: mix from achromatic luma
    float filmicLuma     = 0.f;   // [451] luminance filmic S-curve 0..1
    float oklabHlChroma  = 0.f;   // [452] OKLab HL chroma compress 0..1
    float lutHighlightVibrancy = 0.f; // [200] -1..+1 — see ShaderParams.kt
    // Tonal-zone WB trims (LUT-tab "Highlight vibrancy" expandable group)
    float highlightTemperature = 0.f; // [201] -1..+1 (warm/cool in highlights)
    float highlightTint        = 0.f; // [202] -1..+1 (magenta/green in highlights)
    float shadowTemperature    = 0.f; // [203] -1..+1 (warm/cool in shadows)
    float shadowTint           = 0.f; // [204] -1..+1 (magenta/green in shadows)
    // Ambiance (Tonemap tab). Glamour Glow (slots 205-207, 210) removed.
    float ambiance       = 0.f; // [208] -1..+1
    float ortonStrength  = 0.f; // [209] 0..1 — Orton soft-focus bloom
    float mistTightness  = 0.55f; // [447] Pro-Mist mip1↔mip2 bias
    float mistHalation   = 0.f;   // [448] R/B channel offset 0..1
    float bloomRadius    = 8.f; // [205] 0..24  — Vogel-disc sample radius
    float bloomShape     = 1.f; // [206] 0.4..1.6 — anamorphic ratio (1 = circle)
    float filmRolloff    = 0.f; // [207] 0..1   — film-style highlight shoulder
    float gamutCompress  = 0.f; // [237] 0..1   — ACES-style gamut desat at gamut walls
    float bloomExcludeSubject = 0.f; // [238] 0/1 — separate subject bloom enable
    float subjectBloom        = 0.f; // [239] 0..1 — subject-only bloom strength
    // Color Grading wheels (RapidRAW-style, additive RGB tinting in 3 luma zones).
    // Slots [240..251]. Tint defaults 0.5 = neutral; sat 0 = inert.
    float cgShadowsR    = 0.5f; // [240]
    float cgShadowsG    = 0.5f; // [241]
    float cgShadowsB    = 0.5f; // [242]
    float cgShadowsSat  = 0.f;  // [243]
    float cgMidtonesR   = 0.5f; // [244]
    float cgMidtonesG   = 0.5f; // [245]
    float cgMidtonesB   = 0.5f; // [246]
    float cgMidtonesSat = 0.f;  // [247]
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
    float clarityAmount    = 0.f;  // slot 151 (detailClarity) — mid-radius local contrast
    float clarityLift      = 0.f;  // slot 408 (clarityLift) — img.ly midtone pop coupling
    float centerPop        = 0.f;  // [252] radial-masked clarity
    float toneCurveLumaMode = 0.f; // [210] 0=per-channel, 1=luma-only L curve

    // ── PREQ-Port additions ──────────────────────────────────────────────────
    float hslFull[24]      = {0};  // [253..276] 8 anchors × (h,s,l)
    float curveMaster[16]  = {0};  // [277..292] master curve 8 ctrl pts
    float curveR[16]       = {0};  // [293..308]
    float curveG[16]       = {0};  // [309..324]
    float curveB[16]       = {0};  // [325..340]
    float detailGrainRoughness = 0.f; // [341]
    float detailSharpenMask    = 0.f; // [342]
    float colorDensity         = 0.f; // [343]
    float skintoneWarm         = 0.f; // [344]
    float skintoneSmooth       = 0.f; // [345]
    float skintoneLuma         = 0.f; // [346]
    float midtoneDetails       = 0.f; // [347]
    float highlightRecovery    = 0.f; // [348] native pre-op only
    float pushPull             = 0.f; // [349]
    float lutColorDensity      = 0.f; // [350]
    float lutSkintoneBalance   = 0.f; // [351]
    float aberStrength         = 0.f; // [352]
    float aberFringeReduce     = 0.f; // [353]
    float fxGaussBlur          = 0.f; // [354]
    float fxDirBlurAmt         = 0.f; // [355]
    float fxDirBlurAngle       = 0.f; // [356]
    float fxRadBlurAmt         = 0.f; // [357]
    float fxRadBlurCx          = 0.5f;// [358]
    float fxRadBlurCy          = 0.5f;// [359]
    float fxZoomBlurAmt        = 0.f; // [360]
    float fxZoomBlurCx         = 0.5f;// [361]
    float fxZoomBlurCy         = 0.5f;// [362]
    float fxBlurStyle          = 0.f; // [363]
    float fxBlurExcludeSubject = 0.f; // [364]
    float fxMist               = 0.f; // [365]
    float fxMistWarmth         = 0.f; // [366]
    float fxDust               = 0.f; // [367]
    float fxDustSize           = 0.f; // [368]
    float fxVintageStrength    = 0.f; // [369]
    float fxVintageFade        = 0.f; // [370]
    float fxVintageVig         = 0.f; // [371]
    float fxGlowStrength       = 0.f; // [372]
    float fxGlowSpread         = 0.f; // [373]
    float fxGlowWarmth         = 0.f; // [374]

    // OpenShot lens flare (procedural additive).
    float lensFlareX           = -0.5f; // [400]
    float lensFlareY           = -0.5f; // [401]
    float lensFlareBrightness  = 0.f;   // [409]
    float lensFlareSize        = 1.f;   // [430]
    float lensFlareSpread      = 1.f;   // [431]
    float lensFlareWarmth      = 0.f;   // [435]
    // OpenShot ColorShift (horizontal RGB split).
    float colorShiftRedX       = 0.f;   // [432]
    float colorShiftGreenX     = 0.f;   // [433]
    float colorShiftBlueX      = 0.f;   // [434]

    // ── Haxademic grain + output sharpen (slots 375–379) ────────────────
    //   haxGrain: fract(sin(dot(uv*scale, vec2(17,180)))*2500 + seed)
    //   Separate from cinematicGrain (slots 153/154/156); additive layer.
    //   Defaults chosen so all-zero params produce identity (no grain,
    //   no sharpen). grainScale=1.0 and grainLumaAmp=1.0 are the
    //   neutral "if enabled" operating points.
    float haxGrainCrossfade    = 0.f;  // [375] 0..1 — blend weight; 0 = bypass
    float haxGrainScale        = 1.f;  // [376] UV multiplier; 1.0 = 1:1 pixel grain
    float haxGrainLumaAmp      = 1.f;  // [377] luma noise amplitude; 1.0 = full
    float haxGrainChromaAmp    = 0.f;  // [378] chroma noise amp; 0 = luma-only
    float sharpenAmount        = 0.f;  // [379] Laplacian 3×3 post-sharpen; 0 = off
    float viewZoom             = 1.f;  // [380] canvas zoom scale (1 = fit)
    float viewPanX             = 0.f;  // [381] canvas pan X in NDC units
    float viewPanY             = 0.f;  // [382] canvas pan Y in NDC units

    // ── M5.5: Adobe XMP overlay block ───────────────────────────────────
    float xmpEnabled     = 0.f;   // [32]  0/1
    float xmpExposure    = 0.f;   // [33]
    float xmpContrast    = 0.f;   // [34]
    float xmpHighlights  = 0.f;   // [35]
    float xmpShadows     = 0.f;   // [36]
    float xmpWhites      = 0.f;   // [37]
    float xmpBlacks      = 0.f;   // [38]
    float xmpHsl[18]     = {0};   // [39..56]

    // ── M12.1 per-tab opacity (fan-out compositing) ─────────────────────
    float lightTabOpacity = 1.f;  // [57]
    float colorTabOpacity = 1.f;  // [58]
    float xmpTabOpacity   = 1.f;  // [59]
    float dehaze          = 0.f;  // [60]  Light-tab midtone pull
    // ── M12.2b Vignette block ──────────────────────────────────────────
    float vigAmount       = 0.f;  // [61]  [-1..+1] negative darken, positive lighten
    float vigCenterX      = 0.5f; // [62]  [0..1] normalised
    float vigCenterY      = 0.5f; // [63]  [0..1] normalised
    float vigFeather      = 0.5f; // [64]  [0..1] mask softness
    float vigIntensity    = 1.f;  // [65]  [0..1] mask opacity
    float vigEffect       = 0.f;  // [66]  0=All 1=SubjectOnly 2=BackgroundOnly
    float vigTabOpacity   = 1.f;  // [67]  fan-out master strength

    // ── M12.2b.2 Gradient block ────────────────────────────────────────
    //   Per-side layout (15 floats, see ShaderParams.kt for slot mapping):
    //     0  intensity1   1  length1     2  feather1
    //     3  tintR1       4  tintG1      5  tintB1     6  tintLum1
    //     7  enable2      8  intensity2  9  length2    10 feather2
    //     11 tintR2       12 tintG2      13 tintB2     14 tintLum2
    float gradAngle       = 0.f;        // [68]   degrees [-180..+180]
    float gradTop[15]     = {0};        // [69..83]
    float gradBottom[15]  = {0};        // [84..98]
    float gradLeft[15]    = {0};        // [99..113]
    float gradRight[15]   = {0};        // [114..128]
    float gradTabOpacity  = 1.f;        // [129]
    // M12.2c.1 — per-side Gradient segmentation targets (SegmentTarget ordinal).
    float gradTopApplyTo    = 0.f;      // [130]  0=All 1=Subject 2=Background
    float gradBottomApplyTo = 0.f;      // [131]
    float gradLeftApplyTo   = 0.f;      // [132]
    float gradRightApplyTo  = 0.f;      // [133]
    // Per-side gradient tint blend mode: 0=Solid (light-leak screen+add), 1=Fused (overlay).
    float gradTopBlendMode    = 0.f;    // [391]
    float gradBottomBlendMode = 0.f;    // [392]
    float gradLeftBlendMode   = 0.f;    // [393]
    float gradRightBlendMode  = 0.f;    // [394]
    // M12.2c.2 — Mask tab adjustments. Applied where brushMask(uv) > 0.
    float maskBrightness    = 0.f;      // [134]
    float maskContrast      = 0.f;      // [135]
    float maskTemperature   = 0.f;      // [136]
    float maskTint          = 0.f;      // [137]
    float maskSaturation    = 0.f;      // [138]
    float maskClarity       = 0.f;      // [139]  reserved (CPU-only)
    float maskTabOpacity    = 1.f;      // [140]
    // Tonemap-tab tone region (Adobe XMP target). Additive on top of
    // Light-tab tone in the shader; kept independent so XMP preset
    // load can't clobber the Light tab's AUTO EXPO values.
    float tonemapExposure   = 0.f;      // [141]
    float tonemapHighlights = 0.f;      // [142]
    float tonemapShadows    = 0.f;      // [143]
    // Filmic highlight shoulder strength (0=off, 1=full).
    // Set by AI Expose when blown-pixel fraction is detected.
    float filmicHlProtect   = 0.f;

    // M12.2c.2b — Multi-layer mask. Layer 0 mirrors the scalar mask fields
    // above ([134..140]). Layers 1..3 live in the 21-float tail appended to
    // the wire blob at [157..177] (AFTER the native-only CLAHE/NR/Detail
    // slots [144..156] that this struct deliberately doesn't model). Each
    // layer: brightness, contrast, temperature, tint, saturation, clarity,
    // opacity. The shader reads all 4 layers as array uniforms.
    static constexpr int kMaskLayers = 4;
    struct MaskLayer {
        float brightness  = 0.f;
        float contrast    = 0.f;
        float temperature = 0.f;
        float tint        = 0.f;
        float saturation  = 0.f;
        float clarity     = 0.f;
        float sharpness   = 0.f;  // [-100..100] masked high-freq unsharp; [396..399]
        // Tone regions (mirror global Tone tab), per layer. [-100..100];
        // applied via applyToneRegionsP inside the mask. Slots [410..425].
        float highlights  = 0.f;
        float shadows     = 0.f;
        float whites      = 0.f;
        float blacks      = 0.f;
        float opacity     = 1.f;
        // Luminance-range mask. spread > 0 → generate the mask from the graded
        // luma (target tone) instead of the brush. target/spread/feather [0..1].
        float lumTarget   = 0.f;
        float lumSpread   = 0.f;  // 0 = luminance mask off (use brush)
        float lumFeather  = 0.f;
        // How a live luma band combines with this layer's brush/object bitmap
        // when BOTH are present. 0=legacy (luma wins), 1=luma−bitmap (luma base,
        // bitmap carved out), 2=bitmap−luma, 3=union, 4=intersect. Lets a luma
        // base have object regions subtracted from it live. [395] (layer 0 only).
        int   lumCombine  = 0;
    };
    MaskLayer maskLayer[kMaskLayers];   // [0] mirrors [134..140]; [1..3] @ [157..177]

    // ── Bokeh (GL real-time, FBO blur pass) ─────────────────────────────
    //   Background out-of-focus blur + highlight bloom, composited in the
    //   uber-shader gated by the U2Net subject mask (background only). The
    //   blur source is a downscaled 2-pass separable Gaussian FBO bound on
    //   texture unit 8. detailSmoothBackground [178] stays the native bake.
    float bokehBlur   = 0.f;   // [179] [0..1] background blur strength
    float bokehBalls  = 0.f;   // [180] [0..1] highlight bloom strength
    float bokehSpread = 0.f;   // [181] [0..1] blur radius / bloom spread

    // ── Film grain (GL real-time, blue-noise) ───────────────────────────
    //   Moved off the CPU Stage-B bake into the uber-shader so grain previews
    //   live. Sampled from a tiling blue-noise tile (unit 10), keyed on
    //   normalised image UV so preview + export sample the same pattern.
    float filmGrain       = 0.f;   // [153] [0..1] amount
    float filmGrainSize   = 0.5f;  // [154] [0..1] block size
    float filmGrainWash   = 0.f;   // [156] [0..1] faded-film wash-out
    // slots 155 (uniformity) and 209 (style) removed.

    // ── Per-segment levels (Normalize for 3Dlut) ────────────────────────
    //   Subject + background whites/blacks (slider domain -100..+100),
    //   gated by the U2Net subject mask. All zero → no per-segment lift.
    float whitesSubject    = 0.f;  // [194]
    float blacksSubject    = 0.f;  // [195]
    float whitesBackground = 0.f;  // [196]
    float blacksBackground = 0.f;  // [197]
    float shadowsSubject    = 0.f; // [198]
    float shadowsBackground = 0.f; // [199] — sole owner; purpleFringeMode moved to [449]
    // Per-segment highlights + ambiance (Auto Expo writes these by scaling
    // global values by each region's channel-clip percentage).
    float highlightsSubject    = 0.f; // [229] -1..+1
    float highlightsBackground = 0.f; // [230] -1..+1
    float ambianceSubject      = 0.f; // [231] -1..+1
    float ambianceBackground   = 0.f; // [232] -1..+1

    // Smart Color Enhancement GL preview (slots [384..390]).
    // WB stats from Stage A thumbnail; defaults (0/1) = no stretch.
    float smartColorEnhance = 0.f; // [384] 0=off, 1=on
    float smartWbRMin       = 0.f; // [385]
    float smartWbRMax       = 1.f; // [386]
    float smartWbGMin       = 0.f; // [387]
    float smartWbGMax       = 1.f; // [388]
    float smartWbBMin       = 0.f; // [389]
    float smartWbBMax       = 1.f; // [390]

    // Wire-blob size — must stay in sync with ShaderParams.kt's
    // FLOAT_COUNT. Note: the native parser previously hardcoded 200 while
    // Kotlin emitted 235; the parser's getOr() guards against
    // out-of-range reads, so the mismatch was harmless until we added
    // slots > 200. Bumped to 240 to fit the new LUT-gamut entries at
    // [235]/[236] with headroom.
    // Kept in lockstep with ShaderParams.kt FLOAT_COUNT. This had drifted
    // to 410 while Kotlin was already sending 435, which is exactly the
    // kind of gap that makes a slot look free when it is not.
    static constexpr int FLOAT_COUNT = 453;  // highest used slot [452] oklabHlChroma
    static ShaderParams fromFloatArray(const float* arr, int count);
};

class GlesRenderer {
public:
    GlesRenderer();
    ~GlesRenderer();

    /**
     * Bind the renderer to [window] and import [ahb] as the source texture.
     * Returns true on success. The renderer owns the EGL surface; release()
     * destroys it.
     */
    bool init(ANativeWindow* window, AHardwareBuffer* ahb);

    /** Rebind the source texture without tearing down the EGL context. */
    bool updateAhb(AHardwareBuffer* ahb);

    /** Push slider values into the shader; safe to call from the render thread. */
    void setParams(const ShaderParams& p);

    /**
     * Parse a `.cube` LUT at [cubePath] and upload it as GL_TEXTURE_3D bound
     * to texture unit 1. Returns true on success. Replaces any previously
     * uploaded LUT. The shader's `uLutEnabled` is driven by the [lutEnabled]
     * field of the next ShaderParams push — call setParams() with
     * `lutEnabled=1.0f` after a successful upload to make the LUT take effect.
     */
    bool uploadLut3d(const char* cubePath);

    /** Discard the current LUT and free its texture. */
    void clearLut3d();

    /**
     * Build monotone-cubic 1×256 LUTs from 8 (x,y) control points and upload
     * to GL texture units 8–11. Each array is 16 floats: [x0,y0, x1,y1, …, x7,y7].
     */
    void uploadCurveLuts(const float* master, const float* r, const float* g, const float* b);

    /**
     * Upload the U2Net subject mask. Data is `width * height` bytes,
     * row-major, each byte the quantised subject probability ([0..255]
     * where 255 = certain subject). Bound to texture unit 2 as GL_R8.
     * Allocation is done once with glTexStorage2D; subsequent calls hit
     * glTexSubImage2D and reuse the storage. Pass width=height=0 to
     * tear down (called by clearSubjectMask).
     */
    bool uploadSubjectMask(const uint8_t* gray8, int width, int height);
    /**
     * Upload the bokeh attenuation mask (max(sky, terrain) from Cityscapes
     * segmentation). Same 320×320 grid as the subject mask, GL_R8 immutable
     * storage on unit 10. The shader's bokeh block scales bgGate by
     * (1 - 0.75 * mask) where this is non-zero, so sky/ground get 25% of
     * the user's bokeh strength instead of the full pull.
     */
    bool uploadBokehAttenuation(const uint8_t* gray8, int width, int height);
    /**
     * Upload relative depth (0..255 → [0,1] in .g of the unit-10 RG8 tex).
     * Packs beside attenuation (.r) so we stay within 16 texture units.
     * [focusDepth01] is the subject-median depth used as the CoC focus plane.
     */
    bool uploadDepthMap(const uint8_t* gray8, int width, int height, float focusDepth01);
    void clearDepthMap();
    /**
     * Upload the Sobel edge mask (same 320×320 grid as the subject
     * mask) on texture unit 4. Used by the fragment shader to snap
     * U2Net's soft silhouette to true image gradients.
     */
    bool uploadSobelEdgeMask(const uint8_t* gray8, int width, int height);
    void clearSobelEdgeMask();
    /**
     * Tune the edge-snap behaviour. `strength` 0 disables the snap;
     * 0.35 is a reasonable default. `threshold` is the minimum Sobel
     * magnitude (after normalisation) that triggers the snap.
     */
    void setEdgeSnap(float strength, float threshold);
    /**
     * Set the inner rectangle (UV [0..1]) where the live source image
     * lives within the letterboxed subject mask. Defaults to (0,0,1,1)
     * — full mask. The fragment shader remaps `(u, v)` into this rect
     * before sampling so a non-square source aligns 1:1 with the mask
     * regardless of how U2Net was fed.
     */
    void setSubjectMaskInnerRect(float u0, float v0, float u1, float v1);
    void clearSubjectMask();

    /**
     * Pan/zoom for the on-screen preview, applied to the FINAL window-surface
     * viewport only (intermediate FBO passes stay full-resolution). The fitted
     * photo viewport is scaled about its centre by [scale] and translated by
     * ([offsetX], [offsetY]) in surface pixels (offsetY uses Compose's
     * top-down sign; the renderer flips it for GL's bottom-up viewport).
     *
     * Doing zoom here — rather than via a Compose graphicsLayer on the
     * SurfaceView — keeps the surface pinned to its slot so the preview is
     * clipped to the letterbox by GL and can never paint over the chrome.
     */
    void setViewTransform(float scale, float offsetX, float offsetY) {
        viewScale_   = scale;
        viewOffsetX_ = offsetX;
        viewOffsetY_ = offsetY;
    }

    /**
     * Acquire the AHB write lock (blocking). Call before writing new pixel
     * data into the AHB so renderFrame() won't read a partially-written
     * buffer. Pair with unlockAhbForWrite() after the write completes.
     */
    void lockAhbForWrite()   { pthread_mutex_lock(&ahbWriteLock_); }
    void unlockAhbForWrite() { pthread_mutex_unlock(&ahbWriteLock_); }

    /**
     * Push native-only NR slot values (slots 147/148) into the renderer.
     * These drive the bilateral denoise spatial pre-pass. Values are raw
     * slider integers [0..100]; the renderer maps them to shader uniforms.
     * Both zero → NR pass is skipped entirely (no FBO overhead).
     */
    void setNrSlots(float slot147, float slot148) {
        if (slot147 != nrSlot147_ || slot148 != nrSlot148_) {
            nrPassDirty_ = true;
        }
        nrSlot147_ = slot147;
        nrSlot148_ = slot148;
    }

    /**
     * Upload the brush-painted Mask tab mask. [gray8] is `width*height`
     * row-major bytes, alpha in [0..255]. Bound to texture unit 3 as
     * GL_R8. Reuses the immutable-storage pattern of the subject mask.
     * Mask tab adjustments only apply where this mask is non-zero.
     */
    bool uploadBrushMask(int layer, const uint8_t* gray8, int width, int height);
    void clearBrushMask(int layer);
    /** Clear every mask layer (used when the action stack drops all masks). */
    void clearAllBrushMasks();

    /**
     * Mask tab "Show" preview. When enabled, the masked region (union of every
     * active brush + luminance layer) is tinted blue in the GL output so the
     * user can see the painted/selected area even with no adjustment applied.
     * The GL surface renders on top of the Compose UI (ZOrderOnTop), so this
     * preview has to be drawn in the shader — a Compose overlay would sit
     * behind the surface and never be visible.
     */
    void setShowMaskOverlay(bool show) { showMaskOverlay_ = show; }

    /**
     * Which mask layer the "Show" overlay tints blue. Only the layer currently
     * being edited is tinted, so committed layers (still uploaded for their
     * adjustments) don't leak their selection into a fresh layer's edit. Pass a
     * negative value when no layer is being edited (tint nothing).
     */
    void setMaskOverlayLayer(int layer) { maskOverlayLayer_ = layer; }

    /**
     * Upload the Tone Curve LUT: 256 RGB8 texels (768 bytes, interleaved
     * R,G,B), index = input intensity, value = per-channel curved output.
     * Bound to texture unit 9 as GL_RGB8 (256×1). Pass nullptr to clear.
     */
    bool uploadToneCurve(const uint8_t* rgb256);
    void clearToneCurve();

    /**
     * Render the current shader output (with the current uniforms + LUT)
     * into [dst]. dst must be an AHardwareBuffer the caller allocated with
     * RGBA_F16 + the same width/height as the source. Used at Apply time so
     * the serialized snapshot is the **graded** image, not the pristine
     * pre-shader Stage B base.
     */
    bool snapshotGradedToAhb(AHardwareBuffer* dst);

    /**
     * Render the current graded shader output into a small offscreen RGBA8
     * buffer ([side]×[side], aspect ignored — luma distribution is
     * scale-invariant) and bin BT.601 luma into [outHist] (256 ints).
     * Used to drive the Tone Curves graph backdrop histogram so it reflects
     * the live edited look (exposure/WB/LUT/CLAHE) minus the tone curve.
     * Runs on the GL thread. Returns false if EGL/GL setup fails.
     */
    bool histogramGraded(int side, int* outHist /*[256]*/);

    /**
     * Render the current graded shader output into a downscaled [outW]×[outH]
     * RGBA8 buffer (caller-allocated, row-major, top-down — the snapshot
     * program is identity-vert so the readback is already display-oriented).
     * Used by the RAW Export page to
     * show a graded preview that matches the live editor + the saved file,
     * instead of the ungraded Stage A thumbnail. Runs on the GL thread.
     */
    bool snapshotGradedToBitmap(int outW, int outH, uint8_t* outRgba);

    /** Draw one frame. Returns false if surface was lost and renderer self-released. */
    bool renderFrame();

    /** Tear down EGL resources. Safe to call multiple times. */
    void release();

private:
    bool initEgl(ANativeWindow* window);
    bool createProgram();
    bool importAhbAsTexture(AHardwareBuffer* ahb);

    void cacheUniformLocations();
    void pushUniforms();
    /** Re-resolve uniform locations against [prog] and push the current
     *  ShaderParams to it. Used by the snapshot path where [prog] is the
     *  snapshot (non-V-flipped) program, not the cached display program. */
    void pushUniformsForProgram(unsigned int prog);

    EGLDisplay  display_   = EGL_NO_DISPLAY;
    EGLContext  context_   = EGL_NO_CONTEXT;
    EGLSurface  surface_   = EGL_NO_SURFACE;
    EGLImageKHR ahbImage_  = EGL_NO_IMAGE_KHR;
    // Native refcount on the source buffer backing ahbImage_. Kotlin's
    // HardwareBuffer.close() only drops the Java ref; without this acquire the
    // buffer could be freed while the render thread still samples the EGLImage
    // over it (UAF — the RawV3PreviewComposable `previous?.close()` /
    // onDispose-closer race). Acquired in importAhbAsTexture, released when the
    // image over it is destroyed (swap or teardown).
    AHardwareBuffer* sourceAhb_ = nullptr;
    GLuint      texture_   = 0;
    GLuint      program_   = 0;          // display program (V-flipped vert shader)
    GLuint      programSnap_ = 0;        // snapshot program (identity vert shader);
                                         //   shares the same fragment shader
    GLuint      vao_       = 0;
    GLuint      vbo_       = 0;

    // ── Bokeh FBO blur pass (separable Gaussian at 1/4 res) ─────────────
    //   blurProg_ samples one source and blurs along uBlurDir (1px step
    //   scaled by uBlurRadius). renderFrame() runs it twice (H then V) into
    //   two ping-pong FBOs, then binds blurTexB_ as uBlurTex (unit 8) for
    //   the main uber-shader's background composite.
    GLuint blurProg_       = 0;
    GLuint blurFboA_       = 0;
    GLuint blurTexA_       = 0;
    GLuint blurFboB_       = 0;
    GLuint blurTexB_       = 0;
    int    blurW_          = 0;
    int    blurH_          = 0;
    GLint  uBlurSrcLoc_    = -1;
    GLint  uBlurDirLoc_    = -1;
    GLint  uBlurRadiusLoc_ = -1;
    bool   ensureBlurTargets(int srcW, int srcH);
    void   runBokehBlurPass(float radiusPx);

    // ── Soft-diffusion Gaussian (dedicated plane, not uBlurTex) ─────────
    //   Radius 2+bloomRadius*1.15. Written to softDiffTex_; composited into
    //   bloomTex_[0] so Orton/Glow keep mistHalation when Bokeh owns unit 8.
    GLuint softDiffFbo_  = 0;
    GLuint softDiffTex_  = 0;
    int    softDiffW_    = 0;
    int    softDiffH_    = 0;
    GLuint softDiffBloomProg_ = 0;
    GLuint softDiffBloomScratchFbo_ = 0;
    GLuint softDiffBloomScratchTex_ = 0;
    int    softDiffBloomScratchW_ = 0;
    int    softDiffBloomScratchH_ = 0;
    GLint  uSoftDiffBloomSrcLoc_ = -1;
    GLint  uSoftDiffSrcLoc_      = -1;
    GLint  uSoftDiffOrtonLoc_    = -1;
    GLint  uSoftDiffGlowLoc_     = -1;
    bool   ensureSoftDiffTarget(int srcW, int srcH);
    bool   ensureSoftDiffBloomScratch(int w, int h);
    void   runSoftDiffBlurPass(float radiusPx);
    void   runSoftDiffBloomComposite(float ortonStrength, float glowStrength);

    // ── Bilateral denoise spatial pre-pass (full-resolution RGBA8) ───
    //   Runs before the uber-shader when NR slots 147/148 are non-zero.
    //   Source AHB → nrTexA_ via bilateral filter; uber-shader then
    //   samples nrTexA_ instead of the raw source texture.
    GLuint nrProg_  = 0;
    GLuint nrFboA_  = 0;  GLuint nrTexA_ = 0;
    int    nrW_     = 0;  int    nrH_    = 0;
    float  nrSlot147_ = 0.f;  // luma NR (native-only slot, pushed via JNI)
    float  nrSlot148_ = 0.f;  // chroma NR (native-only slot, pushed via JNI)
    GLint  nrLocTex_       = -1;  // uniform sampler2D uTex
    GLint  nrLocSigma_     = -1;  // uniform float uSigma
    GLint  nrLocKSigma_    = -1;  // uniform float uKSigma
    GLint  nrLocThreshold_ = -1;  // uniform float uThreshold
    bool   ensureNrTargets(int srcW, int srcH);

    // ── Separable 9-tap Gaussian blur (Req 7) ────────────────────────
    //   H+V pair for Orton small-radius (≤8 px). Reuses existing
    //   blurFboA_/blurFboB_ — no new FBOs needed.
    GLuint gaussBlurHProg_     = 0;
    GLint  gaussBlurHLocTex_   = -1;  // sampler2D uTex
    GLint  gaussBlurHLocH_     = -1;  // float h  (1/texWidth)
    GLint  gaussBlurHLocScale_ = -1;  // float blurScale
    GLuint gaussBlurVProg_     = 0;
    GLint  gaussBlurVLocTex_   = -1;  // sampler2D uTex
    GLint  gaussBlurVLocV_     = -1;  // float v  (1/texHeight)
    GLint  gaussBlurVLocScale_ = -1;  // float blurScale

    // ── Laplacian sharpen post-pass (Req 6, slot 379) ────────────────
    //   3×3 kernel applied after the uber-shader when sharpenAmount > 0.
    //   Renders into sharpenFbo_/sharpenTex_; result drawn to window.
    GLuint sharpenProg_         = 0;
    GLuint sharpenFbo_          = 0;  GLuint sharpenTex_ = 0;
    int    sharpenW_            = 0;  int    sharpenH_   = 0;
    GLint  sharpenLocTex_       = -1;  // sampler2D uTex
    GLint  sharpenLocSharpness_ = -1;  // float uSharpness
    GLint  sharpenLocSubjectMask_ = -1;
    GLint  sharpenLocSubjectEn_   = -1;
    GLint  sharpenLocSubjectRect_ = -1;
    GLint  sharpenLocSubjectOnly_ = -1;
    GLint  sharpenLocTexelSize_ = -1;  // vec2 uTexelSize
    bool   ensureSharpenTargets(int w, int h);

    // ── Karis 6-mip bloom pyramid ────────────────────────────────────
    //   Replaces the inline Vogel-disc bloom sampling (which capped at
    //   32 taps and banded at large radii) with the SIGGRAPH 2014 Karis
    //   "Next Gen Post Processing in CoD: Advanced Warfare" approach:
    //     1. Threshold-extract highlights into mip 0 (half-res of preview)
    //     2. Downsample 13-tap to mips 1..5
    //     3. Upsample with tent filter, additively combining from
    //        mip 5 → mip 4 → ... → mip 0
    //     4. Main shader samples the final mip 0 once as uBloomTex
    //   Result: smooth bloom at any radius, no banding, proper HDR-style
    //   highlight bleed. Costs ~11 FBO state switches vs Vogel's 0, but
    //   each pass is at progressively halved resolution so total fragment
    //   work is bounded.
    static constexpr int kBloomMipCount = 6;
    GLuint bloomDownProg_  = 0;
    GLuint bloomUpProg_    = 0;
    GLuint bloomTex_[kBloomMipCount]   = {0, 0, 0, 0, 0, 0};
    GLuint bloomFbo_[kBloomMipCount]   = {0, 0, 0, 0, 0, 0};
    int    bloomW_[kBloomMipCount]     = {0, 0, 0, 0, 0, 0};
    int    bloomH_[kBloomMipCount]     = {0, 0, 0, 0, 0, 0};
    int    bloomBaseW_     = 0;
    int    bloomBaseH_     = 0;
    GLint  uBloomDownSrcLoc_ = -1;
    GLint  uBloomDownThresholdLoc_ = -1;
    GLint  uBloomUpSrcLoc_   = -1;
    GLint  uBloomUpRadiusLoc_ = -1;
    GLint  uBloomUpWeightLoc_ = -1;
    GLint  uBloomTexLoc_     = -1;     // main shader sampler (unit 11)
    bool   ensureBloomTargets(int previewW, int previewH);
    void   runKarisBloomPass(float thresholdLuma, float upsampleRadiusPx,
                             float mistTightness = 0.55f);

    // Cached uniform locations (set once at program link).
    GLint uTexLoc_         = -1;
    GLint uExposureLoc_    = -1;
    GLint uContrastLoc_    = -1;
    GLint uHighlightsLoc_  = -1;
    GLint uShadowsLoc_     = -1;
    GLint uWhitesLoc_      = -1;
    GLint uBlacksLoc_      = -1;
    GLint uWhitesSubjectLoc_    = -1;
    GLint uBlacksSubjectLoc_    = -1;
    GLint uWhitesBackgroundLoc_ = -1;
    GLint uBlacksBackgroundLoc_ = -1;
    GLint uShadowsSubjectLoc_    = -1;
    GLint uShadowsBackgroundLoc_ = -1;
    GLint uHighlightsSubjectLoc_    = -1;
    GLint uHighlightsBackgroundLoc_ = -1;
    GLint uAmbianceSubjectLoc_      = -1;
    GLint uAmbianceBackgroundLoc_   = -1;
    GLint uSaturationLoc_  = -1;
    GLint uVibranceLoc_    = -1;
    GLint uWhiteBalanceLoc_= -1;
    GLint uTintLoc_        = -1;
    GLint uHslRedLoc_      = -1;
    GLint uHslOrangeLoc_   = -1;
    GLint uHslYellowLoc_   = -1;
    GLint uHslGreenLoc_    = -1;
    GLint uHslAquaLoc_     = -1;
    GLint uHslBlueLoc_     = -1;
    GLint uHslYellowGreenLoc_ = -1;
    GLint uHslSpringGreenLoc_ = -1;
    GLint uHslSkyBlueLoc_     = -1;
    GLint uHslPurpleLoc_      = -1;
    GLint uHslMagentaLoc_     = -1;
    GLint uHslPinkLoc_        = -1;
    GLint uDitherLoc_      = -1;
    GLint uPurpleFringeLoc_ = -1;
    GLint uLutEnabledLoc_  = -1;
    GLint uGamutOutLoc_    = -1;
    GLint uLutTexLoc_      = -1;
    GLint uLutIntensityLoc_ = -1;
    GLint uLutBwForceLoc_ = -1;
    GLint uLutSizeLoc_      = -1;
    GLint uLutDomainMinLoc_ = -1;
    GLint uLutDomainMaxLoc_ = -1;
    GLint uLutHighlightVibrancyLoc_ = -1;
    GLint uHighlightTemperatureLoc_ = -1;
    GLint uHighlightTintLoc_        = -1;
    GLint uShadowTemperatureLoc_    = -1;
    GLint uShadowTintLoc_           = -1;
    GLint uAmbianceLoc_       = -1;
    GLint uOrtonStrengthLoc_  = -1;
    GLint uMistTightnessLoc_  = -1;
    GLint uMistHalationLoc_   = -1;
    GLint uBloomRadiusLoc_    = -1;
    GLint uBloomShapeLoc_     = -1;
    GLint uFilmRolloffLoc_    = -1;
    // Film Response (LUT ADV) — Recovery / Fill Light / B&W mixer. Must be
    // uploaded on the LIVE preview path; export already pushes them via
    // pushGradingUniforms. Missing these made the ADV sliders no-ops on canvas.
    GLint uFilmRecoveryLoc_   = -1;
    GLint uFilmFillLightLoc_  = -1;
    GLint uFilmMonochromeLoc_ = -1;
    GLint uFilmGrayMixLoc_    = -1;
    GLint uGamutCompressLoc_  = -1;
    GLint uBloomExcludeSubjectLoc_ = -1;
    GLint uSubjectBloomLoc_        = -1;
    GLint uCgShadowsTintLoc_    = -1;
    GLint uCgShadowsSatLoc_     = -1;
    GLint uCgMidtonesTintLoc_   = -1;
    GLint uCgMidtonesSatLoc_    = -1;
    GLint uCgHighlightsTintLoc_ = -1;
    GLint uCgHighlightsSatLoc_  = -1;
    GLint uCgGlobalTintLoc_     = -1;
    GLint uCgGlobalSatLoc_      = -1;
    GLint uClarityAmountLoc_    = -1;
    GLint uClarityLiftLoc_      = -1;
    GLint uCenterPopLoc_        = -1;
    GLint uWorkspaceSpaceLoc_   = -1;
    GLint uLutAuthoredSpaceLoc_ = -1;
    // M12.1 per-tab opacity locations
    GLint uLightTabOpacityLoc_ = -1;
    GLint uColorTabOpacityLoc_ = -1;
    GLint uXmpTabOpacityLoc_   = -1;
    GLint uDehazeLoc_          = -1;
    GLint uVigAmountLoc_       = -1;
    GLint uVigCenterLoc_       = -1;
    GLint uVigFeatherLoc_      = -1;
    GLint uVigIntensityLoc_    = -1;
    GLint uVigEffectLoc_       = -1;
    GLint uVigTabOpacityLoc_   = -1;
    // M12.2b.2 — Gradient uniform locations.
    GLint uGradAngleLoc_       = -1;
    GLint uGradTopLoc_         = -1;   // float[15]
    GLint uGradBottomLoc_      = -1;
    GLint uGradLeftLoc_        = -1;
    GLint uGradRightLoc_       = -1;
    GLint uGradTabOpacityLoc_  = -1;
    bool   released_ = false;
    GLuint lutTexture_     = 0;
    bool   lutUploaded_    = false;
    // Side length (N) of the currently-uploaded 3D LUT. Needed by the shader
    // to compensate the half-texel offset when sampling. Default 33 matches
    // a standard .cube — kept sane if no LUT is bound (uLutEnabled=0 short-
    // circuits the lookup anyway).
    int    lutSize_        = 33;
    float  lutDomainMin_[3] = {0.f, 0.f, 0.f};
    float  lutDomainMax_[3] = {1.f, 1.f, 1.f};

    // M12.2c.1 — U2Net subject mask (GL_R8, immutable storage, unit 2).
    GLuint subjectMaskTex_   = 0;
    int    subjectMaskW_     = 0;
    int    subjectMaskH_     = 0;
    bool   subjectMaskReady_ = false;
    float  subjectMaskRect_[4] = {0.f, 0.f, 1.f, 1.f};  // u0, v0, u1, v1
    GLint  uSubjectMaskLoc_  = -1;
    GLint  uSubjectMaskEnabledLoc_ = -1;
    GLint  uSubjectMaskRectLoc_    = -1;
    // ── AHB write lock (Task 4.2) ────────────────────────────────────────
    //   renderFrame() tries a non-blocking lock; if it fails the frame is
    //   skipped (not an error). commitProcessedBuffer calls the blocking
    //   lockAhbForWrite() / unlockAhbForWrite() pair.
    pthread_mutex_t ahbWriteLock_;

    // ── Bloom tier mode (Task 4.4) ────────────────────────────────────────
    enum BloomMode { BLOOM_KARIS = 0, BLOOM_GAUSSIAN = 1, BLOOM_DISABLED = 2 };
    bool      bloomEnabled_ = true;
    BloomMode bloomMode_    = BLOOM_KARIS;

    // Bokeh aux on unit 10 as GL_RG8: .r = Cityscapes sky/terrain atten,
    // .g = relative depth (Depth-Anything-V2-Small). Keeps us inside the
    // 16-unit GLES budget. CPU planes retained so either upload can rebuild.
    bool rebuildBokehAuxTexLocked(int width, int height);

    GLuint bokehAttenTex_       = 0;
    int    bokehAttenW_         = 0;
    int    bokehAttenH_         = 0;
    bool   bokehAttenReady_     = false;
    bool   depthMapReady_       = false;
    float  bokehFocusDepth_     = 0.5f;
    std::vector<uint8_t> bokehAttenPlane_;
    std::vector<uint8_t> depthPlane_;
    GLint  uBokehAttenLoc_      = -1;
    GLint  uBokehAttenEnabledLoc_ = -1;
    GLint  uDepthMapEnabledLoc_ = -1;
    GLint  uBokehFocusDepthLoc_ = -1;

    // M12.2c.4 — Sobel edge mask on unit 4 + edge-snap controls.
    GLuint sobelEdgeTex_         = 0;
    int    sobelEdgeW_           = 0;
    int    sobelEdgeH_           = 0;
    bool   sobelEdgeReady_       = false;
    float  edgeSnapStrength_     = 0.35f;
    float  edgeSnapThreshold_    = 0.20f;
    GLint  uSobelEdgeMaskLoc_      = -1;
    GLint  uEdgeSnapStrengthLoc_   = -1;
    GLint  uEdgeSnapThresholdLoc_  = -1;

    // M12.2c.2 — brush-painted Mask tab masks (GL_R8). Up to 4 layers.
    //   Layer 0 → texture unit 3 (uBrushMask, legacy name).
    //   Layers 1..3 → units 5,6,7 (uBrushMask1..3).
    static constexpr int kMaskLayers = 4;
    GLuint brushMaskTex_[kMaskLayers]   = {0, 0, 0, 0};
    int    brushMaskW_[kMaskLayers]     = {0, 0, 0, 0};
    int    brushMaskH_[kMaskLayers]     = {0, 0, 0, 0};
    bool   brushMaskReady_[kMaskLayers] = {false, false, false, false};
    GLint  uBrushMaskLoc_[kMaskLayers]  = {-1, -1, -1, -1}; // sampler locs
    GLint  uBrushMaskEnabledLoc_ = -1;                      // bitfield
    bool   showMaskOverlay_      = false;                   // Mask tab "Show" preview
    GLint  uShowMaskOverlayLoc_  = -1;
    int    maskOverlayLayer_     = -1;                      // layer the overlay tints; <0 = none
    GLint  uMaskOverlayLayerLoc_ = -1;
    GLint  uMaskBrightnessLoc_   = -1;  // array[4] base locs
    GLint  uMaskContrastLoc_     = -1;
    GLint  uMaskTemperatureLoc_  = -1;
    GLint  uMaskTintLoc_         = -1;
    GLint  uMaskSaturationLoc_   = -1;
    GLint  uMaskClarityLoc_      = -1;
    GLint  uMaskSharpnessLoc_    = -1;
    GLint  uMaskHighlightsLoc_   = -1;
    GLint  uMaskShadowsLoc_      = -1;
    GLint  uMaskWhitesLoc_       = -1;
    GLint  uMaskBlacksLoc_       = -1;
    GLint  uMaskTabOpacityLoc_   = -1;
    GLint  uMaskLumTargetLoc_    = -1;
    GLint  uMaskLumSpreadLoc_    = -1;
    GLint  uMaskLumFeatherLoc_   = -1;
    GLint  uMaskLumCombineLoc_   = -1;
    GLint  uTonemapExposureLoc_   = -1;
    GLint  uTonemapHighlightsLoc_ = -1;
    GLint  uTonemapShadowsLoc_    = -1;
    GLint  uFilmicHlProtectLoc_   = -1;
    GLint  uFilmicLumaLoc_        = -1;
    GLint  uOklabHlChromaLoc_     = -1;
    GLint  uGradTopApplyToLoc_     = -1;
    GLint  uGradBottomApplyToLoc_  = -1;
    GLint  uGradLeftApplyToLoc_    = -1;
    GLint  uGradRightApplyToLoc_   = -1;
    GLint  uGradTopBlendModeLoc_    = -1;
    GLint  uGradBottomBlendModeLoc_ = -1;
    GLint  uGradLeftBlendModeLoc_   = -1;
    GLint  uGradRightBlendModeLoc_  = -1;

    // M5.5 — XMP overlay uniforms.
    GLint uXmpEnabledLoc_     = -1;
    GLint uXmpExposureLoc_    = -1;
    GLint uXmpContrastLoc_    = -1;
    GLint uXmpHighlightsLoc_  = -1;
    GLint uXmpShadowsLoc_     = -1;
    GLint uXmpWhitesLoc_      = -1;
    GLint uXmpBlacksLoc_      = -1;
    GLint uXmpHslRedLoc_      = -1;
    GLint uXmpHslOrangeLoc_   = -1;
    GLint uXmpHslYellowLoc_   = -1;
    GLint uXmpHslGreenLoc_    = -1;
    GLint uXmpHslAquaLoc_     = -1;
    GLint uXmpHslBlueLoc_     = -1;

    // ── Bokeh uniforms in the main uber-shader ──────────────────────────
    GLint uBlurTexLoc_    = -1;   // sampler, unit 8
    GLint uBokehBlurLoc_  = -1;
    GLint uBokehBallsLoc_ = -1;
    GLint uBokehSpreadLoc_= -1;

    // ── Tone Curve LUT (256×1 RGB8, unit 9) ─────────────────────────────
    GLuint toneCurveTex_       = 0;
    bool   toneCurveReady_     = false;
    GLint  uToneCurveTexLoc_     = -1;
    GLint  uToneCurveEnabledLoc_ = -1;
    GLint  uToneCurveLumaModeLoc_ = -1;

    // ── Film grain (cinematic 3D noise — procedural, no texture) ────────
    GLint  uFilmGrainLoc_       = -1;
    GLint  uFilmGrainSizeLoc_   = -1;
    GLint  uFilmGrainWashLoc_   = -1;
    GLint  uGrainSeedLoc_       = -1;
    GLint  uImageSizeLoc_       = -1;

    // ── Haxademic film grain extension (Req 8, slots 375–378) ────────────
    GLint  locHaxGrainCrossfade_ = -1;
    GLint  locHaxGrainScale_     = -1;
    GLint  locHaxGrainLumaAmp_   = -1;
    GLint  locHaxGrainChromaAmp_ = -1;

    // ── PREQ-Port uniform locations ───────────────────────────────────────
    GLint  uHslFullLoc_             = -1;  // vec3[8] array
    // Curve LUT textures (1×256 GL_R16F each, units 8–11)
    GLuint curveMasterTex_          = 0;
    GLuint curveRTex_               = 0;
    GLuint curveGTex_               = 0;
    GLuint curveBTex_               = 0;
    GLint  uCurveMasterTexLoc_      = -1;
    GLint  uCurveRTexLoc_           = -1;
    GLint  uCurveGTexLoc_           = -1;
    GLint  uCurveBTexLoc_           = -1;
    GLint  uCurvesEnabledLoc_       = -1;
    // Detail
    GLint  uDetailGrainRoughnessLoc_ = -1;
    GLint  uDetailSharpenMaskLoc_    = -1;
    // Color
    GLint  uColorDensityLoc_        = -1;
    GLint  uSkintoneLoc_            = -1;  // vec3 (warm, smooth, luma)
    // Tonal
    GLint  uMidtoneDetailsLoc_      = -1;
    // LUT tab extras
    GLint  uPushPullLoc_            = -1;
    GLint  uLutColorDensityLoc_     = -1;
    GLint  uLutSkintoneBalanceLoc_  = -1;
    // Aberration
    GLint  uAberStrengthLoc_        = -1;
    GLint  uAberFringeReduceLoc_    = -1;
    // Effects
    GLint  uFxBlurStyleLoc_         = -1;
    GLint  uFxGaussBlurLoc_         = -1;
    GLint  uFxDirBlurAmtLoc_        = -1;
    GLint  uFxDirBlurAngleLoc_      = -1;
    GLint  uFxRadBlurAmtLoc_        = -1;
    GLint  uFxRadBlurCenterLoc_     = -1;  // vec2
    GLint  uFxZoomBlurAmtLoc_       = -1;
    GLint  uFxZoomBlurCenterLoc_    = -1;  // vec2
    GLint  uFxBlurExcludeSubjectLoc_= -1;
    GLint  uFxMistLoc_              = -1;
    GLint  uFxMistWarmthLoc_        = -1;
    GLint  uFxDustLoc_              = -1;
    GLint  uFxDustSizeLoc_          = -1;
    GLint  uFxVintageStrengthLoc_   = -1;
    GLint  uFxVintageFadeLoc_       = -1;
    GLint  uFxVintageVigLoc_        = -1;
    GLint  uFxGlowStrengthLoc_      = -1;
    GLint  uFxGlowSpreadLoc_        = -1;
    GLint  uFxGlowWarmthLoc_        = -1;
    GLint  uLensFlareXLoc_          = -1;
    GLint  uLensFlareYLoc_          = -1;
    GLint  uLensFlareBrightnessLoc_ = -1;
    GLint  uLensFlareSizeLoc_       = -1;
    GLint  uLensFlareSpreadLoc_     = -1;
    GLint  uLensFlareWarmthLoc_     = -1;
    GLint  uColorShiftRedXLoc_      = -1;
    GLint  uColorShiftGreenXLoc_    = -1;
    GLint  uColorShiftBlueXLoc_     = -1;
    GLint  uSmartColorEnhanceLoc_   = -1;
    GLint  uSmartWbMinLoc_          = -1;
    GLint  uSmartWbMaxLoc_          = -1;
    // Effects FBO (unit 16, shared blur output)
    GLuint fxBlurFbo_               = 0;
    GLuint fxBlurTex_               = 0;
    int    fxBlurW_                 = 0;
    int    fxBlurH_                 = 0;
    // Midtone details FBO (unit 14, low-freq LPF)
    GLuint midtoneLpfFbo_           = 0;
    GLuint midtoneLpfTex_           = 0;
    GLint  uLowFreqMidLoc_          = -1;
    GLint  uViewZoomLoc_            = -1;
    GLint  uViewPanLoc_             = -1;

    ShaderParams params_;

    // ── Solution A: Pre-pass dirty flags ──────────────────────────────────
    //   Each multi-pass operation (blur, bloom, NR) caches its output in
    //   FBO textures. The dirty flag is set only when the specific params
    //   that drive that pass change. renderFrame() skips re-running a pass
    //   whose inputs haven't changed — so dragging exposure with bokeh on
    //   no longer re-runs the Gaussian blur.
    bool blurPassDirty_   = true;  // bokeh/ambiance/fxBlur/clarity params changed
    bool softDiffPassDirty_ = true; // soft-diff Gaussian + bloom bake
    bool bloomPassDirty_  = true;  // orton/bloomRadius params changed
    bool nrPassDirty_     = true;  // NR slot 147/148 changed

    // Snapshot of the params that drive each pre-pass. When the live params
    // differ from these, the corresponding dirty flag is set.
    float lastBlurBokehBlur_   = 0.f;
    float lastBlurBokehSpread_ = 0.f;
    float lastBlurAmbiance_    = 0.f;
    float lastBlurFxGaussBlur_ = 0.f;
    float lastBlurFxDirBlurAmt_= 0.f;
    float lastBlurFxRadBlurAmt_= 0.f;
    float lastBlurFxZoomBlurAmt_=0.f;
    float lastBlurFxBlurStyle_ = 0.f;
    float lastBlurClarity_     = 0.f;
    float lastBlurCenterPop_   = 0.f;
    float lastBloomOrton_      = 0.f;
    float lastBloomRadius_     = 0.f;
    /** FX-tab Glow shares the bloom pyramid, so it must dirty the pass too. */
    float lastBloomGlow_       = 0.f;
    float lastBloomTight_      = -1.f;
    float lastBloomHalation_   = -1.f;
    float lastNrSlot147_       = 0.f;
    float lastNrSlot148_       = 0.f;

    /** Check param changes and set dirty flags. Called from setParams(). */
    void updateDirtyFlags(const ShaderParams& p);

    // Preview pan/zoom for the final window viewport (see setViewTransform).
    // Identity by default → no transform until the editor pushes a gesture.
    float       viewScale_   = 1.0f;
    float       viewOffsetX_ = 0.0f;   // surface px, +x = right
    float       viewOffsetY_ = 0.0f;   // surface px, +y = down (Compose sign)

    int         surfaceW_  = 0;
    int         surfaceH_  = 0;
    int         texW_      = 0;
    int         texH_      = 0;
};

}  // namespace raw_v3
