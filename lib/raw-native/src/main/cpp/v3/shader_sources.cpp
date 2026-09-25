/*
 * shader_sources.cpp — definitions for the strings declared in
 * shader_sources.h. Moved VERBATIM out of gles_renderer.cpp's anonymous
 * namespace (which is why the section comments below still read as though
 * they sit next to the renderer — they did).
 *
 * No GL/EGL headers on purpose: this file is pure text and must compile for
 * both the Android target and the desktop razbatch target.
 */

#include "shader_sources.h"
#include <string>

// ── Vertex shaders — gl_VertexID-driven fullscreen quad ─────────────────────
//
//   `kVertSrcDisplay`: V-flipped texture coord. Compensates for the AHB
//   source rows being top-down (row 0 at top) vs GLES texture origin
//   convention (bottom-left). Used by the on-screen render path.
//
//   `kVertSrcSnapshot`: identity texture coord, no V-flip. Used when we
//   render to a destination AHB via FBO — the destination AHB stores rows
//   top-down too, so a V-flipped sample would produce an upside-down file.
//
//   Both share the same fragment shader.
const char* kVertSrcDisplay = R"(#version 300 es
precision highp float;
out vec2 vTexCoord;
void main() {
    vec2 p = vec2(float((gl_VertexID & 1)), float((gl_VertexID >> 1) & 1));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    vTexCoord = vec2(p.x, 1.0 - p.y);
}
)";
const char* kVertSrcSnapshot = R"(#version 300 es
precision highp float;
out vec2 vTexCoord;
void main() {
    vec2 p = vec2(float((gl_VertexID & 1)), float((gl_VertexID >> 1) & 1));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    vTexCoord = p;          // identity — no V-flip
}
)";
// Back-compat alias for older call sites.
const char* kVertSrc = kVertSrcDisplay;

// ── M4 uber-shader (Plan.md §6) ──────────────────────────────────────────────
// One fragment shader. One render pass. All adjustments composed in the
// linear domain, gamma-encoded once at the end, optional LUT, optional
// Bayer 8×8 ordered dither, output to the 8-bit framebuffer.
//
// The HSL pass and the dither matrix are non-trivial; see Plan.md §6 for
// the rationale. Spatial ops (NR, sharpness, vignette, film grain) live in
// a separate `uber_spatial.frag` deferred pass per §10.1 — not in this
// shader, on purpose, so Mali stays in tile memory at slider time.
// ── M4 uber-shader: split into three adjacent raw-string sections that the ──
// ── compiler concatenates at compile time (zero runtime overhead). ──────────
// ── Section 1 of 3: version, precision, uniforms ────────────────────────────
// ── Section 2 of 3: helper functions (linear-light, Lab, HSL, LUT, etc.) ───
// ── Section 3 of 3: void main() ──────────────────────────────────────────────
const char* kFragSrc =
// §1 — Uniforms & precision declarations
R"GLSL(#version 300 es
precision highp float;
precision highp sampler3D;

// ── Capability knobs (overridden by the renderer with a #define prefix when
// the device's GL_MAX_TEXTURE_IMAGE_UNITS can't hold every sampler; the
// defaults keep desktop/parity builds on the full feature set) ──
#ifndef RAZ_GLES_EXTRA_MASKS
#define RAZ_GLES_EXTRA_MASKS 3   // extra brush-mask layer samplers beyond layer 0 (0..3)
#endif
#ifndef RAZ_GLES_FX_VINTAGE
#define RAZ_GLES_FX_VINTAGE 1    // vintage-FX mist/film overlay samplers
#endif

in  vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTex;
uniform sampler3D uLutTex;       // bound to texture unit 1 when uLutEnabled
// Side length N of the bound 3D LUT. Used to compensate the half-texel
// offset GL adds when sampling a 3D texture: input domain [0..1] must map
// to texel indices [0..N-1], which means the sample coord has to be
// (lutIn * (N-1) + 0.5) / N. Without this, every shadow pixel under
// ~0.5/N reads exactly lut[0] (no interpolation), making the LUT crush
// blacks far harder than the CPU export path does. Defaults to 33 so the
// shader still renders sanely if the upload forgot to set the uniform.
uniform float uLutSize;
uniform vec3  uLutDomainMin;   // CUBE DOMAIN_MIN (default 0,0,0)
uniform vec3  uLutDomainMax;   // CUBE DOMAIN_MAX (default 1,1,1)

uniform float uExposure;
uniform float uContrast;
uniform float uHighlights;
uniform float uShadows;
uniform float uWhites;
uniform float uBlacks;
// Per-segment whites/blacks (Normalize for 3Dlut). Slider domain (-100..+100),
// /100 inside the shader. Gated by the U2Net subject mask: subject pixels see
// (subjectW, subjectB), background sees (bgW, bgB). All zero → inert.
uniform float uWhitesSubject;
uniform float uBlacksSubject;
uniform float uWhitesBackground;
uniform float uBlacksBackground;
// Per-segment shadows lift (silhouette class). [-1..+1].
uniform float uShadowsSubject;
uniform float uShadowsBackground;
// Per-segment highlights pull and ambiance lift. [-1..+1].
// Auto Expo writes these by scaling the global highlights / ambiance values
// by each region's channel-clip percentage so a hot sky doesn't drag the
// subject down with it.
uniform float uHighlightsSubject;
uniform float uHighlightsBackground;
uniform float uAmbianceSubject;
uniform float uAmbianceBackground;
uniform float uSaturation;
uniform float uVibrance;
uniform float uWhiteBalance;
uniform float uTint;

uniform vec3 uHslRed;
uniform vec3 uHslOrange;
uniform vec3 uHslYellow;
uniform vec3 uHslGreen;
uniform vec3 uHslAqua;
uniform vec3 uHslBlue;
// Six additional intermediate hue anchors (Color Zones expansion). Doubles
// the angular resolution of the HSL controls from 60° to 30° spacing, which
// fills the wide Yellow→Green and Aqua→Blue gaps in the original 6-anchor
// scheme. Each is a vec3(h, s, l) shift identical in meaning to the 6 above.
uniform vec3 uHslYellowGreen;  //  90°
uniform vec3 uHslSpringGreen;  // 150°
uniform vec3 uHslSkyBlue;      // 210°
uniform vec3 uHslPurple;       // 270°
uniform vec3 uHslMagenta;      // 300°
uniform vec3 uHslPink;         // 330°

uniform float uDitherStrength;
// Purple-fringe correction. 0 = off, 1 = light, 2 = strong. Drives the
// runtime desaturation pass that fires at the top of main(). Only mode 2
// (Strong) actually runs the desat — Light leaves it to LibRaw `aber[]`
// (which we found is a manual per-lens knob, not a free correction).
uniform int   uPurpleFringeMode;
uniform bool  uLutEnabled;
uniform float uLutIntensity;        // 0..1 — mix between input pixel and LUT-sampled pixel
uniform int   uLutBwForce;          // 1 = B&W pack: mix from achromatic luma (slot [450])
uniform float uLutHighlightVibrancy; // -1..+1 — see slot [200] in ShaderParams.kt
uniform float uHighlightTemperature; // -1..+1 — warm/cool trim in highlight luma zone
uniform float uHighlightTint;        // -1..+1 — magenta/green trim in highlight luma zone
uniform float uShadowTemperature;    // -1..+1 — warm/cool trim in shadow luma zone
uniform float uShadowTint;           // -1..+1 — magenta/green trim in shadow luma zone
// Glow / Glamour Glow uniforms removed.
uniform float uAmbiance;             // -1..+1 — Ambiance local-contrast + midtone sat
uniform int   uGamutOut;
// Working colour space of the input pixels in uTex (matches the LibRaw
// output_color setting at Stage A decode). Drives the LUT-input gamut
// transform so a Rec.709/sRGB-targeted LUT applied to ProPhoto-encoded
// pixels gets the input transformed to sRGB before sample, then back to
// ProPhoto after. Values mirror LibRawOutputColor.librawValue:
//   1 = sRGB (identity, no transform)
//   2 = AdobeRGB
//   4 = ProPhoto
//   7 = DCI-P3
//   8 = Rec.2020
// 0 / 5 / 6 (Raw / XYZ / ACES) → treated as sRGB (skip transform).
uniform int   uWorkspaceSpace;
// Authored space of the active LUT. Matches LutInputSpace.ordinal:
//   0 = Rec.709 / sRGB  (default — most consumer LUTs)
//   1 = ProPhoto RGB
//   2 = ACES
//   3 = Display P3
uniform int   uLutAuthoredSpace;

// ── M5.5: Adobe XMP overlay block (composes on top of workspace) ─────────
uniform bool  uXmpEnabled;
uniform float uXmpExposure;
uniform float uXmpContrast;
uniform float uXmpHighlights;
uniform float uXmpShadows;
uniform float uXmpWhites;
uniform float uXmpBlacks;
uniform vec3 uXmpHslRed;
uniform vec3 uXmpHslOrange;
uniform vec3 uXmpHslYellow;
uniform vec3 uXmpHslGreen;
uniform vec3 uXmpHslAqua;
uniform vec3 uXmpHslBlue;

// ── M12.1 per-tab opacity uniforms ──────────────────────────────────────
//   Master strength knob per tab — defaults to 1.0 (full effect, v2-compat)
//   in ShaderParams, dialed down by the editor's tab masks. Compositing is
//   `base + tabDelta * uTab_Opacity` per tab.
uniform float uLightTabOpacity;
uniform float uColorTabOpacity;
uniform float uXmpTabOpacity;
uniform float uDehaze;        // Light-tab midtone pull, [-1, +1]
// M12.2b — Vignette tab uniforms.
uniform float uVigAmount;
uniform vec2  uVigCenter;
uniform float uVigFeather;
uniform float uVigIntensity;
uniform int   uVigEffect;     // 0=All 1=SubjectOnly 2=BackgroundOnly
uniform float uVigTabOpacity;
// M12.2b.2 — Gradient tab uniforms.
//   Side block layout matches ShaderParams.kt:
//     [0..2]  intensity1, length1, feather1
//     [3..6]  tintRGB1, tintLum1
//     [7]     enable2 (0/1)
//     [8..10] intensity2, length2, feather2
//     [11..14] tintRGB2, tintLum2
uniform float uGradAngle;
uniform float uGradTop[15];
uniform float uGradBottom[15];
uniform float uGradLeft[15];
uniform float uGradRight[15];
uniform float uGradTabOpacity;
// M12.2c.1 — U2Net segmentation gating.
//   uSubjectMask: 320×320 GL_R8 single-channel probability texture (unit 2).
//   uSubjectMaskEnabled: 0 = mask not yet uploaded, treat target as All.
//   uVigSegTarget / uGrad*ApplyTo: SegmentTarget ordinal —
//     0 = All (no gating), 1 = Subject only, 2 = Background only.
uniform sampler2D uSubjectMask;
uniform int  uSubjectMaskEnabled;
//   uSubjectMaskRect: UV rectangle (u0, v0, u1, v1) inside the 320×320
//   subject mask where the live image lives. Lets the renderer remap
//   source UV into the letterboxed mask so aspect-ratio mismatches
//   between source and mask don't drift the subject silhouette.
uniform vec4 uSubjectMaskRect;
// Bokeh attenuation mask — 320×320 GL_R8 single-channel, holds
// max(sky, terrain) from Cityscapes segmentation. The bokeh block uses
// this to soften (not eliminate) blur in sky/ground regions so a portrait
// keeps a tasteful 25% blur there rather than the full background pull
// that would flatten distant scenery. 0 disabled, 1 active.
uniform sampler2D uBokehAttenuation;
uniform int       uBokehAttenuationEnabled;
// Depth map packed in uBokehAttenuation.g (GL_RG8 on unit 10). R stays
// Cityscapes sky/terrain attenuation. Same letterbox UV as subject mask.
// Focus depth = subject-median relative depth; CoC = |depth - focus|.
// Subject pixels stay sharp via subjectGate(2) (coc forced 0 on subject).
uniform int   uDepthMapEnabled;
uniform float uBokehFocusDepth;
// M12.2c.4 — Sobel edge mask (same 320×320 grid as subject mask), used
// to snap U2Net's soft silhouette to true image gradients (hair / feathers
// / fur). uEdgeSnapStrength controls how aggressively the mask is pushed
// toward 0/1 at high-contrast pixels.
uniform float uEdgeSnapStrength;
uniform sampler2D uSobelEdgeMask;
uniform float uEdgeSnapThreshold;
uniform int  uGradTopApplyTo;
uniform int  uGradBottomApplyTo;
uniform int  uGradLeftApplyTo;
uniform int  uGradRightApplyTo;
uniform int  uGradTopBlendMode;     // 0=Solid (light-leak screen+add), 1=Fused (overlay)
uniform int  uGradBottomBlendMode;
uniform int  uGradLeftBlendMode;
uniform int  uGradRightBlendMode;
// M12.2c.2 — Mask tab (brush-painted mask + adjustments).
// Multi-layer: up to 4 independent painted regions, each with its own
// adjustments, composited sequentially. uBrushMask0..3 on texture units 5..8.
// Layer 0 keeps the legacy single-mask uniforms for back-compat; layers 1..3
// use array uniforms. Each layer is gated by its own painted alpha.
uniform sampler2D uBrushMask;        // layer 0 (unit 3, legacy)
#if RAZ_GLES_EXTRA_MASKS >= 1
uniform sampler2D uBrushMask1;       // unit 5
#endif
#if RAZ_GLES_EXTRA_MASKS >= 2
uniform sampler2D uBrushMask2;       // unit 6
#endif
#if RAZ_GLES_EXTRA_MASKS >= 3
uniform sampler2D uBrushMask3;       // unit 7
#endif
uniform int   uBrushMaskEnabled;     // bit flags: bit i = layer i has a mask
uniform int   uShowMaskOverlay;      // 1 = tint masked region (Mask tab "Show")
uniform int   uMaskOverlayLayer;     // layer index to tint for the overlay; <0 = none
uniform float uMaskBrightness[4];    // [-100..+100] per layer
uniform float uMaskContrast[4];      // [-100..+100]
uniform float uMaskTemperature[4];   // Kelvin delta [-2000..+2000]
uniform float uMaskTint[4];          // [-150..+150]
uniform float uMaskSaturation[4];    // [-100..+100]
uniform float uMaskClarity[4];       // [-100..+100] local contrast
uniform float uMaskSharpness[4];     // [-100..+100] high-freq unsharp (masked)
uniform float uMaskHighlights[4];    // [-100..+100] tone-region highlights (masked)
uniform float uMaskShadows[4];       // [-100..+100] tone-region shadows (masked)
uniform float uMaskWhites[4];        // [-100..+100] tone-region whites (masked)
uniform float uMaskBlacks[4];        // [-100..+100] tone-region blacks (masked)
uniform float uMaskTabOpacity[4];    // per-layer opacity
// Luminance-range mask per layer. When uMaskLumSpread > 0 the layer's mask is
// GENERATED from the graded luma (a crosshair-sampled target tone), REPLACING
// any brush mask: pixels within +/- spread of target are selected, feathered.
//   uMaskLumTarget : target luma [0..1] (crosshair sample)
//   uMaskLumSpread : half-width of the selected band [0..1]; 0 = mask off
//   uMaskLumFeather: extra soft edge width [0..1]
uniform float uMaskLumTarget[4];
uniform float uMaskLumSpread[4];
uniform float uMaskLumFeather[4];
// How a live luma band combines with the layer's brush/object bitmap when BOTH
// are present: 0=luma wins (legacy), 1=luma×(1−bitmap) [luma base − objects],
// 2=bitmap×(1−luma) [object base − luma], 3=union, 4=intersect.
uniform int   uMaskLumCombine[4];
// Tonemap-tab tone region — additive on top of the Light-tab pass.
// Wholly separate state so XMP preset load can't trample the Light
// tab's AUTO EXPO values. Same applyExposureContrastP / applyToneRegionsP
// pipeline, just driven by independent uniforms.
uniform float uTonemapExposure;
uniform float uTonemapHighlights;
// Filmic highlight shoulder protection (0=off, 1=full).
// Set by AI Expose when blown-pixel fraction exceeds threshold.
//
// !! NOT IMPLEMENTED — this uniform is declared and uploaded (slot [383],
// computed as postBoostBlown*4 in RawAutoExposureModel.kt) but NO shader
// function reads it. The roll-off described below was never written. The
// value therefore has no effect on preview OR export.
//
// Do NOT "restore parity" by implementing a shoulder in apply_macro.cpp:
// the CPU kernel ignores [383] too, so the two sides already AGREE. Adding
// it on one side only would CREATE a preview != export divergence (hard
// rule #1). If this feature is ever wanted, it must land in BOTH
// gles_renderer.cpp and apply_macro.cpp in the same change.
//
// The highlight shoulder that IS implemented and IS mirrored on both sides
// is filmRolloff, slot [207] — see applyFilmRolloff() below and
// applyFilmRolloffP() in apply_macro.cpp.
//
// Intended behaviour, if implemented: exponential roll-off on pixels above
// 0.80 luma so global exposure boosts can't push them to pure white.
uniform float uFilmicHlProtect;
uniform float uTonemapShadows;

// ── Bokeh (GL real-time) ─────────────────────────────────────────────────
//   uBlurTex: downscaled separable-Gaussian blur of the source (unit 8).
//   uBokehBlur:  [0..1] how much of the blurred copy to mix into the
//                background (subject-mask gated, background only).
//   uBokehBalls: [0..1] highlight-bloom strength — bright blurred spots get
//                lifted to fake creamy specular-highlight "balls".
//   uBokehSpread: [0..1] drives the FBO blur radius on the CPU side; the
//                shader also uses it to widen the bloom threshold rolloff.
uniform float uBokehBlur;
    uniform sampler2D uBlurTex;
uniform float uBokehBalls;
uniform float uMaskBanding; // [0..1] flat smooth inside the painted mask
uniform float uBokehSpread;

// ── Orton Effect (bloom / softening) ─────────────────────────────────────
//   The Orton effect (Michael Orton, 1980s) is a Karis bloom screen-blend
//   over the original, gated by highlights so shadows stay clean.
//   Soft diffusion (density wrap) is pre-baked into uBloomTex by a
//   dedicated Gaussian plane — NOT sampled from uBlurTex (that unit is
//   Bokeh/Ambiance/Clarity/FX; sharing it killed mistHalation when Bokeh
//   won the radius). [0..1] strength: 0 = off, 1 = fully blended.
uniform float uOrtonStrength;
uniform float uBloomRadius;
uniform float uBloomShape;
uniform float uFilmRolloff;
// [451] luminance-preserving filmic S-curve (0..1). Auto 0.65 with depth+bokeh.
uniform float uFilmicLuma;
// [452] OKLab highlight chroma compression (0..1). Auto 0.70 with depth+bokeh.
uniform float uOklabHlChroma;
uniform float uGamutCompress;
// 0 = bloom applies everywhere. 1 = bloom is gated to background only
// via the U2Net subject mask, so the subject (person / main object)
// stays glow-free. Only takes effect when uSubjectMaskEnabled == 1.
uniform float uBloomExcludeSubject;
// Subject-only bloom strength (0..1). Mixed with uOrtonStrength per
// pixel using the subject mask: bg pixels get uOrtonStrength, subject
// pixels get uSubjectBloom. Edge feather between them.
uniform float uSubjectBloom;
// Pro-Mist / cinematic bloom (slots [447]/[448]). Tightness drives the Karis
// upsample mip mix on the CPU side; halation is the R/B channel offset when
// sampling uBloomTex (optical scatter — red spreads farther than blue).
uniform float uMistTightness;  // [447] 0..1
uniform float uMistHalation;   // [448] 0..1
uniform float uOpticalSpread;    // [461] 0..1
uniform float uOpticalHalation;  // [462] 0..1
uniform float uOpticalDirection; // [463] 0 Off, 1 Horizontal, 2 Radial
uniform float uHighlightStart;   // [484] default 0.78
uniform float uHighlightEnd;     // [485] default 0.98
uniform float uOpticalDensity;   // long-side curve, 1 = 2048 reference
// Clarity (mid-radius local contrast, RapidRAW-style). Log-space gain
// `final = c × exp2(log2(yc/yb) × amt)` with shadow+highlight protection.
// Negative values soften (lerp toward the blurred mid-frequency layer).
uniform float uClarityAmount;   // [-1..+1]
// img.ly-style clarity "pop": midtone exposure lift coupled to clarity. 0 =
// classic clarity (local contrast only). Mirrors raw_v3_detail.cpp CPU export.
uniform float uClarityLift;      // [0..1]
// Center-Pop single slider — radial mask × clarity tap. Positive boosts
// center clarity + a tiny vibrance bump; negative softens center.
uniform float uCenterPop;       // [-1..+1]
// Color Grading wheels — 4-way Lift / Gamma / Gain (+ Global offset), the
// DaVinci/ASC-CDL model. Each wheel: RGB tint (0.5 = neutral) + strength.
//   Shadows  → Lift   (offset, fades toward highlights)
//   Midtones → Gamma  (per-channel power, pivots on mids)
//   Highlights → Gain (per-channel multiply, biased to highlights)
//   Global   → Offset (uniform add to all tones)
uniform vec3  uCgShadowsTint;     // Lift tint, 0.5 = neutral
uniform float uCgShadowsSat;      // 0..1 strength
uniform vec3  uCgMidtonesTint;    // Gamma tint
uniform float uCgMidtonesSat;
uniform vec3  uCgHighlightsTint;  // Gain tint
uniform float uCgHighlightsSat;
uniform vec3  uCgGlobalTint;      // Offset (global) tint
uniform float uCgGlobalSat;
// Karis 6-mip bloom pyramid output (final mip 0, half-res of preview).
// Pre-computed by runKarisBloomPass() into bloomTex_[0] and bound on
// texture unit 11 right before the main fragment shader draws.
uniform sampler2D uBloomTex;

// ── Tone Curve ───────────────────────────────────────────────────────────
//   256×1 RGB8 LUT (unit 9). Each row index i = input intensity; the texel
//   holds the per-channel output (channel curve already composed with the
//   master curve in Kotlin). Applied per channel after grading + LUT.
uniform sampler2D uToneCurveTex;
uniform int   uToneCurveEnabled;
// Luma-mode: when 1, the master curve (LUT.r channel) is applied to luma
// only — chroma is preserved by scaling the per-channel offset from luma.
// When 0 (default), the existing per-channel master(channel(x)) path runs.
uniform int   uToneCurveLumaMode;
// Curves-tab highlight shoulder, 0..1. Applied to luma after the per-channel
// LUT so a warm highlight is not pushed toward green.
uniform float uFilmHighlightKnee;

// ── PREQ-Port uniforms ────────────────────────────────────────────────────
// HSL Full — 8 anchor vec3 array (hueShift, satShift, lumShift per anchor).
uniform vec3  uHslFull[8];
// Film response (LUT tab): Adobe legacy Recovery / FillLight + B&W GrayMixer.
uniform float uFilmRecovery;      // -1..1, >0 pulls highlights down
uniform float uFilmFillLight;     // -1..1, >0 lifts shadows
uniform int   uFilmMonochrome;    // Adobe ConvertToGrayscale; gates the mixer
uniform float uFilmGrayMix[8];    // R,O,Y,G,Aqua,B,Purple,Magenta each -1..1
// RGB Curves — 4× 1×256 GL_R16F LUT textures (units 17–20).
uniform sampler2D uCurveMasterTex;  // unit 12
uniform sampler2D uCurveRTex;       // unit 13
uniform sampler2D uCurveGTex;       // unit 14
uniform sampler2D uCurveBTex;       // unit 15
uniform int   uCurvesEnabled;       // 1 when any curve deviates from identity
// Detail
uniform float uDetailGrainRoughness;  // [341] Voronoi roughness blend 0..1
uniform float uDetailSharpenMask;     // [342] Sobel-gated sharpening mask 0..1
// Color
uniform float uColorDensity;          // [343] mid-band saturation −1..+1
uniform float uFilmSeparation;        // [500] OKLCh chroma separation −1..+1
uniform vec3  uSkintone;              // [344..346] (warm, smooth, luma)
// Tonal
uniform float uMidtoneDetails;        // [347] midtone contrast −1..+1
// uLowFreqMid removed — exceeds GL_MAX_TEXTURE_IMAGE_UNITS=16; LPF FBO wired later
// LUT extras
uniform float uPushPull;              // [349] push/pull EV shift for 3D LUT
uniform float uLutColorDensity;       // [350]
uniform float uLutSkintoneBalance;    // [351]
// Aberration
uniform float uAberStrength;          // [352]
uniform float uAberFringeReduce;      // [353]
// Effects
uniform int   uFxBlurStyle;           // [363] 0=off 1=Gauss 2=Dir 3=Rad 4=Zoom
uniform float uFxGaussBlur;
uniform float uFxDirBlurAmt;
uniform float uFxDirBlurAngle;
uniform float uFxRadBlurAmt;
uniform vec2  uFxRadBlurCenter;
uniform float uFxZoomBlurAmt;
uniform vec2  uFxZoomBlurCenter;
uniform float uFxBlurExcludeSubject;
// uFxBlurTex removed — exceeds GL_MAX_TEXTURE_IMAGE_UNITS=16; blur FBO wired later
uniform float uFxMist;
uniform float uFxMistWarmth;
uniform float uFxDust;
uniform float uFxDustSize;
uniform float uFxVintageStrength;
uniform float uFxVintageFade;
uniform float uFxVintageVig;
uniform float uFxVintageMistIntensity;
uniform float uFxVintageMistScale;
uniform float uFxVintageTextureIntensity;
uniform float uFxVintageTextureScale;
#if RAZ_GLES_FX_VINTAGE
uniform sampler2D uFxVintageMistTex;   // unit 16 — optional (18-sampler config)
uniform sampler2D uFxVintageFilmTex;   // unit 17
#endif
uniform float uFxGlowStrength;
uniform float uFxGlowSpread;
uniform float uFxGlowWarmth;
uniform float uLensFlareX;          // [400] -1..1
uniform float uLensFlareY;          // [401] -1..1
uniform float uLensFlareBrightness; // [409] 0..1
uniform float uLensFlareSize;       // [430] 0.1..3
uniform float uLensFlareSpread;     // [431] 0..1
uniform float uLensFlareWarmth;     // [435] 0=warm-white … 1=orange sunset
uniform float uLensFlareDistance;   // [464] 0 far .. 1 near. Default 1.
uniform float uLensFlareHood;       // [465] 0..1. Default 0.
uniform float uStarburst;            // [480] 0..1 spike brightness
uniform float uIrisBlades;           // [481] 0 = circle, else 5..8
uniform float uIrisRotation;         // [482] 0..1
uniform float uIrisRoundness;        // [483] 1 = circle
uniform float uSceneDistance;        // [466] 0 far .. 1 near
uniform float uShadowStrength;       // [467] 0..1. 0 skips the shadow.
uniform float uShadowSoftness;       // [468] 0..1 extra blur fraction
uniform float uShadowAspect;         // width / height, 1 if unknown
uniform float uColorShiftRedX;      // [432] -0.1..0.1 uv-fraction
uniform float uColorShiftGreenX;    // [433]
uniform float uColorShiftBlueX;     // [434]

// ── Film grain (cinematic 3D noise — Matt DesLauriers / Martins Upitis) ──
//   Procedural 3D value noise: X,Y = a fixed reference grid (uImageSize, NOT
//   the live pixel dims, so preview + export sample identical coords), Z =
//   seed. Low-frequency noise offsets high-frequency noise → organic swirling
//   film crystals (no pixel block-snapping). Size scales the noise frequency.
uniform float uFilmGrain;        // amount [0..1]
uniform float uFilmGrainSize;    // size [0..1] (noise frequency)
uniform float uFilmGrainWash;    // wash-out [0..1]
// uFilmGrainUnif and uFilmGrainStyle removed.
uniform float uGrainSeed;        // fixed seed (same for preview + export)
uniform float uGrainEx[13];      // emulsion pack, slots [486..498]
uniform vec2  uImageSize;        // fixed reference grid (aspect-matched)

// ── Haxademic film grain extension (Req 8, slots 375–378) ──────────────
//   Additive second grain layer — cheaper sin-dot hash (no lattice interp).
//   Bypass: uHaxGrainCrossfade == 0.0 → no effect.
uniform float uHaxGrainCrossfade;  // [375] blend weight; 0 = bypass
uniform float uHaxGrainScale;      // [376] UV multiplier; 1.0 = 1:1 pixel grain
uniform float uHaxGrainLumaAmp;    // [377] luma noise amplitude
uniform float uHaxGrainChromaAmp;  // [378] chroma noise amp; 0 = luma-only

// Smart Color Enhancement [384..390] — GPU parity with SmartColorEnhancer.kt
uniform float uSmartColorEnhance; // 0 = off, 1 = on
uniform vec3  uSmartWbMin;        // per-channel min [0..1]; defaults 0,0,0
uniform vec3  uSmartWbMax;        // per-channel max [0..1]; defaults 1,1,1

// Bayer 8×8 ordered dither matrix.
const float bayer8[64] = float[64](
     0.0/64.0, 32.0/64.0,  8.0/64.0, 40.0/64.0,  2.0/64.0, 34.0/64.0, 10.0/64.0, 42.0/64.0,
    48.0/64.0, 16.0/64.0, 56.0/64.0, 24.0/64.0, 50.0/64.0, 18.0/64.0, 58.0/64.0, 26.0/64.0,
    12.0/64.0, 44.0/64.0,  4.0/64.0, 36.0/64.0, 14.0/64.0, 46.0/64.0,  6.0/64.0, 38.0/64.0,
    60.0/64.0, 28.0/64.0, 52.0/64.0, 20.0/64.0, 62.0/64.0, 30.0/64.0, 54.0/64.0, 22.0/64.0,
     3.0/64.0, 35.0/64.0, 11.0/64.0, 43.0/64.0,  1.0/64.0, 33.0/64.0,  9.0/64.0, 41.0/64.0,
    51.0/64.0, 19.0/64.0, 59.0/64.0, 27.0/64.0, 49.0/64.0, 17.0/64.0, 57.0/64.0, 25.0/64.0,
    15.0/64.0, 47.0/64.0,  7.0/64.0, 39.0/64.0, 13.0/64.0, 45.0/64.0,  5.0/64.0, 37.0/64.0,
    63.0/64.0, 31.0/64.0, 55.0/64.0, 23.0/64.0, 61.0/64.0, 29.0/64.0, 53.0/64.0, 21.0/64.0
);
float bayerThreshold(vec2 fragXY) {
    ivec2 ij = ivec2(mod(fragXY, 8.0));
    return bayer8[ij.y * 8 + ij.x];
}
)GLSL"  // end §1 — uniforms + sampler declarations

// §2 — Helper functions: linear-light, Lab, HSL, LUT sampling, gamut, effects
R"GLSL(
// ── Linear-light helpers ────────────────────────────────────────────────────
// Exposure and tone-region math is physically correct only in linear light.
// Stage A stores gamma-encoded sRGB (LibRaw gamm[0]=1/2.4, gamm[1]=12.92),
// so we linearise before these ops and re-encode after. All other ops
// (HSL, saturation, color grading, LUT, grain, gamut compress) stay in
// gamma-encoded space — that matches their authored domain.
vec3 srgbToLinear(vec3 c) {
    // IEC 61966-2-1 piecewise inverse. Clamp to [0,1] first so negative
    // values (from float rounding) don't produce NaN in pow().
    c = clamp(c, 0.0, 1.0);
    bvec3 lo = lessThanEqual(c, vec3(0.04045));
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, lo);
}
vec3 linearToSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    bvec3 lo = lessThanEqual(c, vec3(0.0031308));
    return mix(1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, c * 12.92, lo);
}

// ── Lab conversion helpers (D65 illuminant, XYZ↔linear-RGB) ──────────────────
vec3 linearRgbToXyz(vec3 c) {
    return mat3(0.4124564, 0.2126729, 0.0193339,
                0.3575761, 0.7151522, 0.1191920,
                0.1804375, 0.0721750, 0.9503041) * c;
}
vec3 xyzToLinearRgb(vec3 xyz) {
    return mat3( 3.2404542, -0.9692660,  0.0556434,
                -1.5371385,  1.8760108, -0.2040259,
                -0.4985314,  0.0415560,  1.0572252) * xyz;
}
float labF(float t) {
    float d = 6.0 / 29.0;
    return t > d * d * d ? pow(t, 1.0 / 3.0) : t / (3.0 * d * d) + 4.0 / 29.0;
}
vec3 xyzToLab(vec3 xyz) {
    xyz /= vec3(0.95047, 1.00000, 1.08883);
    vec3 f = vec3(labF(xyz.x), labF(xyz.y), labF(xyz.z));
    return vec3(116.0 * f.y - 16.0, 500.0 * (f.x - f.y), 200.0 * (f.y - f.z));
}
vec3 labToXyz(vec3 lab) {
    float fy = (lab.x + 16.0) / 116.0;
    float fx = lab.y / 500.0 + fy;
    float fz = fy - lab.z / 200.0;
    float d = 6.0 / 29.0;
    vec3 f = vec3(fx, fy, fz);
    vec3 r = mix(3.0 * d * d * (f - 4.0 / 29.0), f * f * f, step(vec3(d), f));
    return r * vec3(0.95047, 1.00000, 1.08883);
}
vec3 srgbToLab(vec3 srgb) { return xyzToLab(linearRgbToXyz(srgbToLinear(srgb))); }
vec3 labToSrgb(vec3 lab)  { return linearToSrgb(clamp(xyzToLinearRgb(labToXyz(lab)), 0.0, 1.0)); }

// Color-only vibrance. Lab L is not written. Chroma magnitude is scaled;
// hue (a/b direction) stays. Weak chroma moves more. Skin hue moves least.
vec3 applySmartColorEnhancement(vec3 c, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 lab = srgbToLab(clamp(c, 0.0, 1.0));
    float a = lab.y;
    float b = lab.z;
    float C = length(vec2(a, b));
    if (C < 0.5) return c;
    float hue = atan(b, a);
    float skin = smoothstep(0.15, 0.45, hue) * (1.0 - smoothstep(0.95, 1.25, hue));
    float weak = 1.0 - clamp(C / 80.0, 0.0, 1.0);
    float scale = 1.0 + 0.35 * s * weak * (1.0 - 0.75 * skin);
    float k = scale;
    lab.y = clamp(a * k, -128.0, 127.0);
    lab.z = clamp(b * k, -128.0, 127.0);
    vec3 outc = clamp(labToSrgb(lab), 0.0, 1.0);
    float y0 = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float y1 = dot(outc, vec3(0.2126, 0.7152, 0.0722));
    if (y1 > 1e-4) outc *= y0 / y1;
    return clamp(outc, 0.0, 1.0);
}

vec3 applyExposureContrastP(vec3 c, float expVal, float contrastVal) {
    // Operate in linear light: linearise → scale → re-encode.
    vec3 lin = srgbToLinear(c);
    lin *= exp2(expVal);
    // Contrast pivot at linear 0.18 (≈ 18% grey, standard photographic midtone).
    lin = mix(vec3(0.18), lin, 1.0 + contrastVal);
    return linearToSrgb(lin);
}
vec3 applyExposureContrast(vec3 c) {
    return applyExposureContrastP(c, uExposure, uContrast);
}

// ── White balance: a real colour-temperature model ────────────────────────
//
// uWhiteBalance is the Kelvin OFFSET from the sRGB reference white, normalised
// so ±1 = ±2500 K (ShaderParams slot [8]; the ABI is unchanged). The old model
// was a flat `R *= 1 + w*0.15, B *= 1 - w*0.15`, which is not a temperature at
// all: the same slider value produced the same gain whether the picture was lit
// at 2500 K or 9000 K, so "Kelvin" on the UI meant nothing and a Lightroom
// preset's Temperature could not be honoured. This computes the actual white
// point of the target temperature and adapts to it.
//
// Locus: CIE D-series for T ≥ 4000 K, Planckian (Kim et al. cubic) below, both
// standard published fits. xy → XYZ → linear sRGB, normalised to unit green so
// the adaptation changes colour without changing exposure.
vec3 wbWhitePointXyz(float T) {
    float t = clamp(T, 1667.0, 25000.0);
    float t2 = t * t, t3 = t2 * t;
    float x;
    if (t < 4000.0) {
        x = -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910;
    } else if (t <= 7000.0) {
        x = 0.244063 + 0.09911e3 / t + 2.9678e6 / t2 - 4.6070e9 / t3;
    } else {
        x = 0.237040 + 0.24748e3 / t + 1.9018e6 / t2 - 2.0064e9 / t3;
    }
    float y;
    if (t < 4000.0) {
        y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
    } else {
        y = -3.000 * x * x + 2.870 * x - 0.275;
    }
    y = max(y, 1e-4);
    return vec3(x / y, 1.0, (1.0 - x - y) / y);
}

vec3 wbXyzToLinearSrgb(vec3 c) {
    return vec3(
        dot(c, vec3( 3.2404542, -1.5371385, -0.4985314)),
        dot(c, vec3(-0.9692660,  1.8760108,  0.0415560)),
        dot(c, vec3( 0.0556434, -0.2040259,  1.0572252)));
}

/** Per-channel linear gain that renders a scene lit at [T] as neutral. */
vec3 wbGainForKelvin(float T) {
    vec3 w = wbXyzToLinearSrgb(wbWhitePointXyz(T));
    w = max(w, vec3(1e-4));
    return w / w.g;                    // unit green: colour only, no exposure shift
}

vec3 applyWbTintP(vec3 c, float wbVal, float tintVal) {
    // Applied in LINEAR light: a gain in gamma space pushes highlights harder
    // than shadows and skews hue with luminance.
    vec3 lin = srgbToLinear(c);
    if (abs(wbVal) > 1e-4) {
        // Reference 6500 K (sRGB D65). A POSITIVE slider warms the picture, so
        // it names a BLUER light to correct for — hence 6500 + offset, and the
        // gain is reference ÷ target.
        float targetK = clamp(6500.0 + wbVal * 2500.0, 1667.0, 25000.0);
        vec3 gain = wbGainForKelvin(6500.0) / wbGainForKelvin(targetK);
        lin *= gain / max(gain.g, 1e-4);
    }
    // Tint runs green↔magenta, perpendicular to the temperature axis. POSITIVE
    // is GREEN here (Adobe's sign is the opposite — the preset importer/exporter
    // converts, rather than flipping a value already stored in every sidecar).
    // Green moves alone and R/B share the reciprocal so luminance holds.
    if (abs(tintVal) > 1e-4) {
        float g = 1.0 + tintVal * 0.12;
        lin.g *= g;
        lin.r *= 1.0 / sqrt(max(g, 1e-4));
        lin.b *= 1.0 / sqrt(max(g, 1e-4));
    }
    return linearToSrgb(lin);
}
vec3 applyWbTint(vec3 c) { return applyWbTintP(c, uWhiteBalance, uTint); }

// Headroom-aware LUT sample coordinate (FEATURES.md retry rule).
// Identity on [0,1]; soft-compress ONLY channels > 1 into (0,1].
// Continuous at 1 (f(1)=1). Hard-clamp used to flatten every HDR value to
// the (1,1,1) LUT corner; a global 0.75 knee darkened in-range highlights
// and was reverted. Mirror: mapLutSampleCoordP in apply_macro.cpp.
float mapLutSampleCoord(float x) {
    if (x <= 0.0) return 0.0;
    if (x <= 1.0) return x;
    float e = x - 1.0;
    return 1.0 / (1.0 + e);
}

// Film-style highlight shoulder rolloff. Per-channel power compression:
//   out = 1 - (1 - clamp(c, 0, 1))^N    where N = 1 + strength * 4
// strength = 0 → N = 1 → identity (linear clip when c approaches 1).
// strength = 1 → N = 5 → soft compression; values approaching 1 are
//                       pushed back toward midtones.
// The pivot at midtones is preserved: f(0)=0, f(0.5) shifts only slightly,
// f(1)=1. Only the upper third of the range visibly bends. Operates per
// channel to match how film negative inversion responds to overexposure
// (each dye layer saturates independently).
//
// Derivation: this is Reinhard-family power compression applied to (1-c).
// No code copied from external projects.

// Depth-aware portrait look (chatgpt_prompt_idea): luminance-preserving
// filmic S-curve + OKLab highlight chroma compression. Applied when the
// matching strength > 0, OR auto at 0.65/0.70 when depth CoC bokeh is live
// (uDepthMapEnabled && bokeh). Preview = export (apply_macro mirrors).

float filmicLumaCurve(float x) {
    float v = clamp(x, 0.0, 1.0);
    // Gentle shadow lift
    float shadow = v + 0.025 * (1.0 - v);
    // Smoothstep S-contrast
    float sCurve = shadow * shadow * (3.0 - 2.0 * shadow);
    // Highlight compression (Reinhard-family shoulder)
    return clamp(sCurve / (sCurve + 0.18), 0.0, 1.0);
}

vec3 applyFilmicLuma(vec3 c, float strength) {
    if (strength <= 0.0) return c;
    float Y = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float mapped = filmicLumaCurve(Y);
    float scale = mapped / max(Y, 1e-4);
    vec3 outc = c * scale;
    return mix(c, outc, clamp(strength, 0.0, 1.0));
}

// Compact linear sRGB ↔ OKLab (Björn Ottosson). Reuse existing
// srgbToLinear / linearToSrgb above — do NOT redefine (Mali S0023).
vec3 linearToOklab(vec3 c) {
    float l = 0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b;
    float m = 0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b;
    float s = 0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b;
    float l_ = pow(max(l, 0.0), 1.0 / 3.0);
    float m_ = pow(max(m, 0.0), 1.0 / 3.0);
    float s_ = pow(max(s, 0.0), 1.0 / 3.0);
    return vec3(
        0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
         1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
         0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_);
}
vec3 oklabToLinear(vec3 lab) {
    float l_ = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
    float m_ = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
    float s_ = lab.x - 0.0894789779 * lab.y - 1.2914855480 * lab.z;
    float l = l_ * l_ * l_;
    float m = m_ * m_ * m_;
    float s = s_ * s_ * s_;
    return vec3(
         4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}

// Midtone chroma boost + highlight chroma compression (brief table).
float oklabChromaMul(float L) {
    // Piecewise-ish via smoothsteps approximating:
    // 0.00→1.00, 0.40→1.08, 0.55→1.00, 0.75→0.82, 0.95→0.52, 1.00→0.40
    float midBoost = mix(1.0, 1.08, smoothstep(0.15, 0.40, L) * (1.0 - smoothstep(0.45, 0.60, L)));
    float hl = smoothstep(0.55, 1.0, L);
    float hlMul = mix(1.0, 0.40, hl);
    // Blend mid boost only where not yet in deep highlight
    return mix(midBoost, hlMul, hl);
}

// Film Separation. Scales OKLab chroma only (a,b equally, so hue is untouched).
// Weight: shadows ~0.33, midtones 1, highlights ~0.50. strength is −1..+1.
// MUST stay bit-mirrored by applyFilmSeparationP() in apply_macro.cpp.
float filmSeparationWeight(float L) {
    float mid = mix(0.33, 1.0, smoothstep(0.05, 0.45, L));
    return mix(mid, 0.50, smoothstep(0.55, 0.95, L));
}
vec3 applyFilmSeparation(vec3 c, float strength) {
    if (strength == 0.0) return c;
    vec3 lin = srgbToLinear(clamp(c, 0.0, 1.0));
    vec3 lab = linearToOklab(lin);
    float factor = max(0.0, 1.0 + strength * filmSeparationWeight(lab.x) * 0.75);
    lab.y *= factor;
    lab.z *= factor;
    return clamp(linearToSrgb(oklabToLinear(lab)), 0.0, 1.0);
}

vec3 applyOklabHlChroma(vec3 c, float strength) {
    if (strength <= 0.0) return c;
    vec3 lin = srgbToLinear(clamp(c, 0.0, 1.0));
    vec3 lab = linearToOklab(lin);
    float mul = oklabChromaMul(lab.x);
    lab.y *= mul;
    lab.z *= mul;
    vec3 outc = clamp(linearToSrgb(oklabToLinear(lab)), 0.0, 1.0);
    return mix(c, outc, clamp(strength, 0.0, 1.0));
}

vec3 applyFilmRolloff(vec3 c, float strength) {
    if (strength <= 0.0) return c;
    float N = 1.0 + strength * 4.0;
    // Headroom (c > 1, i.e. blown highlight data the FP16 pipeline
    // intentionally preserves above "white"): there is no sensible curve
    // shape here without also letting OUTPUT exceed 1.0 — clamping first
    // collapses EVERY headroom value to the identical target (1.0),
    // erasing real texture in bright clouds/highlights into a flat white
    // plateau with a visible seam against the still-graded sub-1.0 pixels
    // next to it ("sudden jump to total whiteout"). Pass headroom through
    // per-channel instead — target=c (identity) whenever a channel is
    // already >= 1, so mix() below leaves it completely unchanged.
    vec3 clamped = clamp(c, 0.0, 1.0);
    vec3 curved  = 1.0 - pow(1.0 - clamped, vec3(N));
    vec3 target  = mix(curved, c, step(1.0, c));
    return mix(c, target, clamp(strength, 0.0, 1.0));
}

// ── ACES-style gamut compression ────────────────────────────────────────
// Pulls saturated highlights toward grey as they approach the gamut wall,
// so blown specular pixels desaturate smoothly to white instead of
// producing the hue-shifting "one channel clips first" artefact.
//
// Algorithm (public-domain ACES gamut-compress, simplified):
//   1. achromatic = max(R, G, B)        the "luma-ish" anchor
//   2. distance_i = (achro - c_i) / achro    per-channel chroma distance
//   3. compressed_i = compressFunc(distance_i, threshold, limit)
//   4. c_i' = achro - compressed_i × achro    rebuild from compressed dist
//
// compressFunc uses a smoothed Reinhard variant:
//   above threshold τ:  d' = τ + (d - τ) / (1 + ((d - τ)/(L - τ))^N)^(1/N)
//   below threshold:    d' = d                    (pass-through, preserves
//                                                  non-saturated colours)
//
// τ (threshold) determines what fraction of the chroma range is left
//   untouched. We set τ = 0.815 + 0.085·(1 - strength). At strength=0
//   threshold = 0.9 → only the most saturated 10% of distance is
//   compressed (subtle); at strength=1 threshold = 0.815 → more of the
//   range is compressed (more aggressive).
//
// L (limit) is the maximum source distance the function will accept
//   without saturating; values > L are pushed to 1.0 asymptotically.
//   We use L = 1.147 (the ACES default; corresponds to ~+15% out-of-
//   gamut range tolerance).
//
// N controls the curve's sharpness; ACES default = 1.2.
// Gamut compression — pulls saturated pixels toward achromatic (grey
// of the same brightness) so blown specular highlights and pushed
// reds/blues don't produce the harsh "one channel clips first, then
// other channels follow" hue-shift artefact.
//
// Reformulated 2026-06-04 for SDR-bounded pipelines. The earlier ACES
// reference implementation used a threshold of 0.815..0.9 measured
// against the chroma distance `(max-c)/max`, which only fires on
// extreme HDR-extended pixels. With our pipeline clamping to [0,1]
// most of the time, the threshold was effectively unreachable and
// the slider had no visible effect.
//
// New formulation: measure saturation as `(max - min) / max`. This
// ranges 0 (pure grey) to 1 (one channel at zero). For each pixel:
//   • Below threshold τ (saturation 0..τ): pass through unchanged
//   • Above τ: smoothly pull saturation toward τ via Reinhard curve
//   • At strength=1, τ slides down to ~0.55 — anything ≥ 55% saturated
//     gets desaturated
//   • At strength=0, function early-returns (no-op)
//
// The pull-toward-grey preserves luminance (max stays the same) so
// only chroma changes — exactly what users expect from a saturation
// rolloff knob.
vec3 applyGamutCompress(vec3 c, float strength) {
    if (strength <= 0.0) return c;
    float s = clamp(strength, 0.0, 1.0);
    float achro = max(max(c.r, c.g), c.b);
    float darkest = min(min(c.r, c.g), c.b);
    if (achro <= 0.0001) return c;
    float sat = (achro - darkest) / achro;     // 0..1 saturation
    // Achromatic / near-grey pixel: no chroma to compress. Early-return
    // BEFORE the ratio division below (which would NaN at sat=0 and the
    // GPU's NaN→0 clamp would push the pixel to pitch black). Bug
    // observed at strength=0.01 on near-white window/sky regions where
    // sat ≈ 0.005.
    if (sat <= 0.0001) return c;

    // Re-tuned 2026-06-04 for visible effect on typical SDR pixels.
    // At strength=1 we ALSO pull a fraction of overall saturation toward
    // grey (the linear `sat *= 1 - s × 0.6` term below) so pixels with
    // any saturation see SOME desat, not just the most-saturated ones.
    // This matches user expectation of a "Gamut Compress" slider — at
    // 100% the image should look noticeably less saturated.
    //
    // The compression curve is now two-stage:
    //   1. Global desat: newSat = sat × (1 - 0.6 × s)
    //      — even mid-saturation pixels shrink at high strength.
    //   2. Highlight rolloff via Reinhard on the residual.
    //      — extreme saturations also asymptote to <1.0 so we never
    //        produce fully clipped chroma.
    float globalDesat = 1.0 - 0.6 * s;
    float linearSat = sat * globalDesat;
    // Reinhard the linear result; this gently caps the maximum
    // saturation a pixel can have post-compress.
    float capTarget = 1.0 - 0.4 * s;  // strength=1 caps sat at 0.6
    float compressedSat;
    if (linearSat <= 0.0001) {
        compressedSat = 0.0;
    } else {
        // Map [0, ∞) → [0, capTarget) via x' = capTarget × x / (capTarget + x)
        compressedSat = capTarget * linearSat / (capTarget + linearSat);
    }
    // Rebuild RGB at new sat, max channel preserved.
    float ratio = compressedSat / sat;
    return vec3(achro) - (vec3(achro) - c) * ratio;
}

vec3 applyToneRegionsP(vec3 c, float hi, float sh, float wh, float bl) {
    // Operate in linear light so highlight/shadow masks are perceptually correct.
    vec3 lin = srgbToLinear(c);
    // Luma in linear space for mask computation.
    float L = dot(lin, vec3(0.2627, 0.6780, 0.0593));
    // Highlight/shadow masks in linear space: adjusted thresholds
    // (0.5→0.18, 0.95→0.72 in linear ≈ 0.5→0.72 in gamma).
    float highlightMask = smoothstep(0.18, 0.72, L);
    float shadowMask    = 1.0 - smoothstep(0.003, 0.18, L);
    lin *= 1.0 + hi * highlightMask * 0.5;
    lin *= 1.0 + sh * shadowMask    * 0.8;
    // Whites / Blacks levels remap — apply in linear then re-encode.
    if (wh != 0.0 || bl != 0.0) {
        float wp = max(1.0 - wh * 0.25, 0.1);
        float bp = clamp(bl * 0.12, -0.15, 0.15);
        lin = bp + (lin / wp) * (1.0 - bp);
    }
    return linearToSrgb(lin);
}
vec3 applyToneRegions(vec3 c) {
    return applyToneRegionsP(c, uHighlights, uShadows, uWhites, uBlacks);
}

vec3 applySaturationVibranceP(vec3 c, float satVal, float vibVal) {
    float L = dot(c, vec3(0.2627, 0.6780, 0.0593));
    c = mix(vec3(L), c, 1.0 + satVal);
    if (vibVal != 0.0) {
        // Simple sat-weighted vibrance lift, mirrors apply_macro.cpp so
        // editor preview matches saved file. The RapidRAW skin-hue
        // dampener and asymmetric-negative branch were reverted because
        // they were editor-only and produced visible drift on save —
        // re-introduce only when both pipelines learn them in lockstep.
        float maxC = max(c.r, max(c.g, c.b));
        float minC = min(c.r, min(c.g, c.b));
        float curSat = (maxC - minC) / max(1e-5, maxC);
        float w = 1.0 - curSat;
        c = mix(vec3(L), c, 1.0 + vibVal * w);
    }
    return c;
}
vec3 applySaturationVibrance(vec3 c) {
    return applySaturationVibranceP(c, uSaturation, uVibrance);
}

// ── HSL helpers ────────────────────────────────────────────────────────────
//   Convert RGB → HSL, apply per-range hue/sat/lum shifts weighted by how
//   close the source hue is to each range center, then HSL → RGB.
vec3 rgbToHsl(vec3 c) {
    float maxC = max(c.r, max(c.g, c.b));
    float minC = min(c.r, min(c.g, c.b));
    float d = maxC - minC;
    float h = 0.0, s = 0.0, l = (maxC + minC) * 0.5;
    if (d > 1e-6) {
        s = (l > 0.5) ? d / (2.0 - maxC - minC) : d / (maxC + minC);
        if (maxC == c.r) {
            h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
        } else if (maxC == c.g) {
            h = (c.b - c.r) / d + 2.0;
        } else {
            h = (c.r - c.g) / d + 4.0;
        }
        h /= 6.0;
    }
    return vec3(h, s, l);
}

float hueToRgb(float p, float q, float t) {
    t = fract(t);
    if (t < 1.0/6.0) return p + (q - p) * 6.0 * t;
    if (t < 0.5)      return q;
    if (t < 2.0/3.0)  return p + (q - p) * (2.0/3.0 - t) * 6.0;
    return p;
}

vec3 hslToRgb(vec3 hsl) {
    float h = hsl.x, s = hsl.y, l = hsl.z;
    if (s < 1e-6) return vec3(l);
    float q = (l < 0.5) ? l * (1.0 + s) : l + s - l * s;
    float p = 2.0 * l - q;
    return vec3(
        hueToRgb(p, q, h + 1.0/3.0),
        hueToRgb(p, q, h),
        hueToRgb(p, q, h - 1.0/3.0)
    );
}

// Center hue for each range (0..1). 12-anchor "Color Zones" expansion: the
// original 6 anchors had uneven spacing (60° gaps between Y→G and A→B), so
// hue shifts in those bands fell off into a dead zone between tents. The 6
// new intermediate anchors fill those gaps to give uniform 30° spacing all
// the way around the hue circle.
const float HUE_RED          = 0.000;   //   0°
const float HUE_ORANGE       = 0.083;   //  30°
const float HUE_YELLOW       = 0.167;   //  60°
const float HUE_YELLOW_GREEN = 0.250;   //  90°  (new)
const float HUE_GREEN        = 0.333;   // 120°
const float HUE_SPRING_GREEN = 0.417;   // 150°  (new)
const float HUE_AQUA         = 0.500;   // 180°
const float HUE_SKY_BLUE     = 0.583;   // 210°  (new)
const float HUE_BLUE         = 0.667;   // 240°
const float HUE_PURPLE       = 0.750;   // 270°  (new)
const float HUE_MAGENTA      = 0.833;   // 300°  (new)
const float HUE_PINK         = 0.917;   // 330°  (new)

// Gaussian hue weight (RapidRAW-style). Half-width tuned to 60° (1/6 of
// circle) so adjacent anchors overlap smoothly. Falls off as
// exp(-1.5 * (dist/(width*0.5))^2) — gives a soft Gaussian roll-off rather
// than the older triangular tent, eliminating the visible edges where
// adjacent bands transition.
float hueWeight(float hue, float center) {
    float d  = abs(hue - center);
    float d2 = abs(hue - center - 1.0);
    float d3 = abs(hue - center + 1.0);
    float dist = min(d, min(d2, d3));
    // width = 1.0/6.0 (60°); half-width = 1.0/12.0; normalized squared dist.
    float n = dist * 12.0;  // dist / (width*0.5)
    return exp(-1.5 * n * n);
}

vec3 applyHslShiftsP(vec3 c,
                     vec3 rR,  vec3 rO,  vec3 rY,  vec3 rG,  vec3 rA,  vec3 rB,
                     vec3 rYG, vec3 rSG, vec3 rSB, vec3 rPu, vec3 rMa, vec3 rPi) {
    if (rR == vec3(0.0)  && rO == vec3(0.0)  && rY == vec3(0.0)  &&
        rG == vec3(0.0)  && rA == vec3(0.0)  && rB == vec3(0.0)  &&
        rYG == vec3(0.0) && rSG == vec3(0.0) && rSB == vec3(0.0) &&
        rPu == vec3(0.0) && rMa == vec3(0.0) && rPi == vec3(0.0)) {
        return c;
    }
    vec3 hsl = rgbToHsl(clamp(c, 0.0, 1.0));
    float h = hsl.x, s = hsl.y, l = hsl.z;

    // Six UI bands always sit in the basis. A hidden band (YG/SG/SB/Pu/Ma/Pi)
    // joins numerator and denominator only when its H/S/L shift is non-zero,
    // so a zero preset anchor cannot dilute the visible blend. A non-zero
    // Purple/Magenta/Pink still competes.
    float wR  = hueWeight(h, HUE_RED);
    float wO  = hueWeight(h, HUE_ORANGE);
    float wY  = hueWeight(h, HUE_YELLOW);
    float wYG = any(notEqual(rYG, vec3(0.0))) ? hueWeight(h, HUE_YELLOW_GREEN) : 0.0;
    float wG  = hueWeight(h, HUE_GREEN);
    float wSG = any(notEqual(rSG, vec3(0.0))) ? hueWeight(h, HUE_SPRING_GREEN) : 0.0;
    float wA  = hueWeight(h, HUE_AQUA);
    float wSB = any(notEqual(rSB, vec3(0.0))) ? hueWeight(h, HUE_SKY_BLUE) : 0.0;
    float wB  = hueWeight(h, HUE_BLUE);
    float wPu = any(notEqual(rPu, vec3(0.0))) ? hueWeight(h, HUE_PURPLE) : 0.0;
    float wMa = any(notEqual(rMa, vec3(0.0))) ? hueWeight(h, HUE_MAGENTA) : 0.0;
    float wPi = any(notEqual(rPi, vec3(0.0))) ? hueWeight(h, HUE_PINK) : 0.0;
    float totalW = max(1e-5,
        wR + wO + wY + wYG + wG + wSG + wA + wSB + wB + wPu + wMa + wPi);

    float hShift = (rR.x*wR + rO.x*wO + rY.x*wY + rYG.x*wYG + rG.x*wG + rSG.x*wSG +
                    rA.x*wA + rSB.x*wSB + rB.x*wB + rPu.x*wPu + rMa.x*wMa + rPi.x*wPi) / totalW;
    float sShift = (rR.y*wR + rO.y*wO + rY.y*wY + rYG.y*wYG + rG.y*wG + rSG.y*wSG +
                    rA.y*wA + rSB.y*wSB + rB.y*wB + rPu.y*wPu + rMa.y*wMa + rPi.y*wPi) / totalW;
    float lShift = (rR.z*wR + rO.z*wO + rY.z*wY + rYG.z*wYG + rG.z*wG + rSG.z*wSG +
                    rA.z*wA + rSB.z*wSB + rB.z*wB + rPu.z*wPu + rMa.z*wMa + rPi.z*wPi) / totalW;

    // Saturation mask — skip near-neutral pixels so HSL doesn't tint or darken
    // grays/whites. RapidRAW uses smoothstep(0.05, 0.20, sat). Applies to Hue,
    // Sat, and Luminance shifts so color-specific sliders never dull whites.
    float satMask = smoothstep(0.05, 0.20, s);
    h = fract(h + (hShift / 6.0) * satMask + 1.0);
    s = clamp(s + sShift * satMask, 0.0, 1.0);
    l = clamp(l + lShift * satMask, 0.0, 1.0);
    return hslToRgb(vec3(h, s, l));
}
vec3 applyHslShifts(vec3 c) {
    return applyHslShiftsP(c, uHslRed, uHslOrange, uHslYellow,
                           uHslGreen, uHslAqua, uHslBlue,
                           uHslYellowGreen, uHslSpringGreen, uHslSkyBlue,
                           uHslPurple, uHslMagenta, uHslPink);
}

// Color Grading — 4-way Lift/Gamma/Gain (+Global offset), DaVinci/ASC-CDL
// style. Each wheel contributes a signed per-channel push v = (tint-0.5)*2*sat
// ∈ [-sat, +sat] (0 = neutral). Applied per channel, in order:
//   x = x + lift·(1-x)        Lift   — raises shadows, fades to 0 at white
//   x = x + offset            Global — uniform offset across all tones
//   x = x · (1 + gain)        Gain   — multiply, dominates highlights
//   x = x^(1/(1+gamma))       Gamma  — per-channel power, pivots on midtones
// Mirrored exactly in apply_macro.cpp applyColorGradingP() for preview=export.
vec3 applyColorGrading(vec3 c) {
    if (uCgShadowsSat <= 0.0 && uCgMidtonesSat <= 0.0 &&
        uCgHighlightsSat <= 0.0 && uCgGlobalSat <= 0.0) {
        return c;
    }
    vec3 lift   = (uCgShadowsTint    - vec3(0.5)) * 2.0 * uCgShadowsSat;
    vec3 gammaV = (uCgMidtonesTint   - vec3(0.5)) * 2.0 * uCgMidtonesSat;
    vec3 gain   = (uCgHighlightsTint - vec3(0.5)) * 2.0 * uCgHighlightsSat;
    vec3 offset = (uCgGlobalTint     - vec3(0.5)) * 2.0 * uCgGlobalSat;
    vec3 x = c;
    x = x + lift * (1.0 - x);                       // Lift (shadows)
    x = x + offset;                                 // Global offset
    x = x * (vec3(1.0) + gain);                     // Gain (highlights)
    vec3 g = clamp(vec3(1.0) + gammaV, vec3(0.1), vec3(4.0));
    x = pow(max(x, vec3(0.0)), 1.0 / g);            // Gamma (midtones)
    return clamp(x, 0.0, 4.0);
}

// Local-contrast (Clarity / Structure) via log-space gain.
// `blurred` is a mid-large-radius Gaussian of the current image. Operates
// on luma so chroma stays put. Shadow + highlight protection masks keep
// noise in shadows and clip-headroom in whites from being amplified.
vec3 applyLocalContrast(vec3 c, vec3 blurred, float amt) {
    if (amt == 0.0) return c;
    float yc = max(dot(c,       vec3(0.2627, 0.6780, 0.0593)), 1e-4);
    float yb = max(dot(blurred, vec3(0.2627, 0.6780, 0.0593)), 1e-4);
    float logRatio = log2(yc / yb);
    float prot = smoothstep(0.0, 0.03, yc) * (1.0 - smoothstep(0.9, 1.0, yc));
    vec3 gained = c * exp2(logRatio * amt);
    return mix(c, gained, prot);
}

vec3 gammaEncodeSrgb(vec3 lin) {
    return mix(12.92 * lin,
               1.055 * pow(lin, vec3(1.0/2.4)) - 0.055,
               step(0.0031308, lin));
}

// ── PREQ-Port GLSL functions ─────────────────────────────────────────────

// HSL Full — 8 anchors uniformly spaced at 45° (0°..315°), 30°-wide bell each.
// Supersedes the old 12-anchor named-vec3 scheme when uHslFull has non-zero data.
vec3 applyHslFull(vec3 c) {
    // Early-out mirrors applyHslFullP's quick-exit in apply_macro.cpp. NOT an
    // optimisation: without it the neutral case still round-trips RGB→HSL→RGB,
    // and that trip MANGLES out-of-gamut intermediates (a saturated pixel that
    // an earlier stage pushed past [0,1] gets s>1, the in-loop clamp snaps it
    // to 1, and the reconstruction shifts ALL channels — the razparity harness
    // measured drifts up to 0.35 vs the CPU export on saturated colours).
    bool anyShift = false;
    for (int gi = 0; gi < 8; gi++) {
        if (uHslFull[gi] != vec3(0.0)) { anyShift = true; }
    }
    if (!anyShift) return c;
    vec3 hsl = rgbToHsl(c);
    float h = hsl.x;  // [0..1]
    float s = hsl.y;
    float l = hsl.z;
    float satMask = smoothstep(0.05, 0.20, s);
    // 8 anchors at 0,1/8,2/8,...,7/8 of the hue circle
    for (int i = 0; i < 8; i++) {
        float anchorH = float(i) / 8.0;
        float dist = abs(h - anchorH);
        dist = min(dist, 1.0 - dist);  // wrap-around
        float w = max(0.0, 1.0 - dist * 8.0);  // triangular, 1/8 width
        w = w * w * (3.0 - 2.0 * w);           // smoothstep shaping
        vec3 shift = uHslFull[i];
        h = fract(h + (shift.x / 6.0) * w * satMask + 1.0);
        s = clamp(s + shift.y * w * satMask, 0.0, 1.0);
        l = clamp(l + shift.z * w * satMask, 0.0, 1.0);
    }
    return hslToRgb(vec3(h, s, l));
}

// ── Film response: Adobe Recovery / FillLight + the B&W GrayMixer ────────
//
// Runs immediately AFTER the 3D LUT so it shapes the LUT's own output — that
// is the point of it: a baked film LUT (a DCP's ProfileToneCurve +
// ProfileLookTable) arrives with a fixed response, and these give the response
// back to the user without re-baking the cube. Recovery/FillLight push the
// tone-region ends, so >0 on both is the moody faded-negative end (rolled
// highlights over lifted shadows) and <0 on both is the high-contrast end.
//
// Shape is lifted verbatim from LrPresetConverter.applyBasicTone() so a slider
// at N does exactly what an imported preset's `Recovery`/`FillLight` of N does.
// Adobe's are 0..100 one-directional; the negative half is ours.
//
// MUST stay bit-mirrored by applyFilmResponseP() in apply_macro.cpp.

// Adobe's eight HSL band centres, in degrees. NON-uniform on purpose — these
// are NOT the uniform 45-degree anchors uHslFull uses. Do not conflate them.
const float kFilmBandDeg[8] = float[8](0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 285.0, 330.0);

// Weight of each band for one hue, cosine-blended between the two nearest
// centres so the eight weights always sum to 1. Assigning a pixel to ONE band
// leaves a hard seam wherever a hue crosses a boundary, which shows as banding
// across a sky or a face once a mixer channel is pushed hard.
void filmBandWeights(float hue01, out float w[8]) {
    float h = fract(hue01) * 360.0;
    for (int i = 0; i < 8; i++) w[i] = 0.0;
    int lo = 7;
    int hi = 0;
    for (int i = 0; i < 8; i++) {
        if (kFilmBandDeg[i] <= h) { lo = i; hi = (i + 1) % 8; }
    }
    if (h < kFilmBandDeg[0]) { lo = 7; hi = 0; }
    float cLo = kFilmBandDeg[lo];
    float cHi = kFilmBandDeg[hi] + (hi <= lo ? 360.0 : 0.0);
    float hh  = (h < cLo) ? h + 360.0 : h;
    float span = max(cHi - cLo, 1e-9);
    float t = clamp((hh - cLo) / span, 0.0, 1.0);
    float sm = t * t * (3.0 - 2.0 * t);
    w[lo] = 1.0 - sm;
    w[hi] = sm;
}

vec3 applyFilmResponse(vec3 c) {
    if (uFilmRecovery == 0.0 && uFilmFillLight == 0.0 && uFilmMonochrome == 0) {
        return c;
    }
    // Recovery / FillLight — per-channel tone-region push.
    if (uFilmRecovery != 0.0 || uFilmFillLight != 0.0) {
        vec3 wHi = clamp((c - vec3(0.5)) * 2.0, 0.0, 1.0);
        vec3 wSh = clamp((vec3(0.5) - c) * 2.0, 0.0, 1.0);
        c = clamp(c - uFilmRecovery * 0.25 * wHi + uFilmFillLight * 0.25 * wSh,
                  0.0, 1.0);
    }
    // GrayMixer — gated on monochrome, exactly as Adobe gates it on
    // ConvertToGrayscale: the mixer decides how each hue maps to grey, which
    // only means anything once colour is being discarded.
    if (uFilmMonochrome != 0) {
        vec3 hsl = rgbToHsl(clamp(c, 0.0, 1.0));
        float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float w[8];
        filmBandWeights(hsl.x, w);
        float mixAmt = 0.0;
        for (int i = 0; i < 8; i++) mixAmt += uFilmGrayMix[i] * w[i];
        // Scaled by saturation so a neutral pixel keeps its luminance — a grey
        // wall must not shift because "Red" was pulled down.
        y = clamp(y + mixAmt * hsl.y * 0.5, 0.0, 1.0);
        c = vec3(y);
    }
    return c;
}

// RGB Curves — sample per-channel 1×256 GL_R16F LUT textures.
float sampleCurve(sampler2D lut, float x) {
    return texture(lut, vec2(clamp(x, 0.0, 1.0), 0.5)).r;
}
vec3 applyCurves(vec3 c) {
    if (uCurvesEnabled == 0) return c;
    float mR = sampleCurve(uCurveMasterTex, c.r);
    float mG = sampleCurve(uCurveMasterTex, c.g);
    float mB = sampleCurve(uCurveMasterTex, c.b);
    return vec3(
        sampleCurve(uCurveRTex, mR),
        sampleCurve(uCurveGTex, mG),
        sampleCurve(uCurveBTex, mB)
    );
}

// Color Density — mid-band saturation using 3D LUT (placeholder: direct HSL push).
// Real implementation samples preq_color_density.png 64^3 LUT when uploaded.
vec3 applyColorDensity(vec3 c, float density) {
    if (density == 0.0) return c;
    vec3 hsl = rgbToHsl(c);
    float midMask = smoothstep(0.0, 0.3, hsl.y) * smoothstep(1.0, 0.6, hsl.y);
    hsl.y = clamp(hsl.y + density * 0.5 * midMask, 0.0, 1.0);
    return hslToRgb(hsl);
}

// Skintone — targeted adjustment in skin hue range (~15°-35° in HSL).
vec3 applySkintone(vec3 c, vec3 skintone) {
    if (skintone.x == 0.0 && skintone.y == 0.0 && skintone.z == 0.0) return c;
    vec3 hsl = rgbToHsl(c);
    // skin hue band: h in [0.02..0.12] (roughly 7°..43°)
    float skinMask = smoothstep(0.02, 0.05, hsl.x) * smoothstep(0.12, 0.08, hsl.x);
    skinMask *= smoothstep(0.1, 0.3, hsl.y);  // only saturated pixels
    hsl.x = fract(hsl.x + skintone.x * 0.05 * skinMask + 1.0);
    hsl.y = clamp(hsl.y - skintone.y * 0.3 * skinMask, 0.0, 1.0);
    hsl.z = clamp(hsl.z + skintone.z * 0.3 * skinMask, 0.0, 1.0);
    return hslToRgb(hsl);
}

// Midtone Details — luma-masked unsharp/blur for midtone contrast.
vec3 applyMidtoneDetails(vec3 c, vec2 uv) {
    if (uMidtoneDetails == 0.0) return c;
    float L = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float mask = smoothstep(0.0, 0.2, L) * smoothstep(1.0, 0.8, L);
    vec3 lf = c; // LPF FBO not yet bound — midtone details disabled until FBO wired
    vec3 hf = c - lf;
    float amt = uMidtoneDetails;
    float blendCap = (amt > 0.0) ? 0.8 : 0.6;
    vec3 result = c + hf * (amt * blendCap) * mask;
    return clamp(result, 0.0, 4.0);
}

// Mist overlay
vec3 applyMist(vec3 c, float strength, float warmth) {
    if (strength == 0.0) return c;
    vec3 mistColor = vec3(0.98 + warmth * 0.20, 0.96, 0.94 - warmth * 0.20);
    return mix(c, mistColor, strength * 0.4);
}

// Vintage — a clean tonal vintage look: desaturation, a mild warm/cool cast,
// gentle channel shift and faded (lifted) blacks. The old
// AnalogTape line artifacts (scanlines, rolling hum bands, bottom tracking
// stripe) were REMOVED at the user's request — no "streak lines". Tonal path
// stays in GL/CPU parity with applyVintageP. Mist/film overlays are GL-only
// until the CPU export kernel grows a texture path (grain stays Film Grain FX).
// Overlays must still run when strength==0 so Mist/Texture sliders work alone.
vec3 applyVintage(vec3 c, vec2 uv, float strength, float fade, float vig, float mistInt, float mistScale, float texInt, float texScale, float mistWarmth) {
    if (strength == 0.0 && mistInt <= 0.0 && texInt <= 0.0) return c;
    if (strength != 0.0) {
        float k = strength;
        float Y = dot(c, vec3(0.299, 0.587, 0.114));
        c = mix(c, vec3(Y), 0.35 * k);                       // desaturate
        c += vec3(0.020, -0.012, 0.028) * k;                 // mild warm/cool cast
        c.r += 0.020 * k;  c.b -= 0.015 * k;                 // gentle channel shift
        c = mix(c, vec3(0.12), fade * k * 0.6);       // fade / lift blacks
    }

#if RAZ_GLES_FX_VINTAGE
    // Mist Overlay (add). Warmth tints the PNG, not the film texture plate.
    if (mistInt > 0.0) {
        vec2 mistUV = (uv - 0.5) / max(mistScale, 1.0) + 0.5;
        vec3 mistSample = texture(uFxVintageMistTex, mistUV).rgb;
        mistSample *= vec3(1.0 + mistWarmth * 0.55, 1.0, 1.0 - mistWarmth * 0.55);
        c += mistSample * mistInt;
    }

    // Texture Overlay (add)
    if (texInt > 0.0) {
        vec2 texUV = (uv - 0.5) / max(texScale, 1.0) + 0.5;
        vec3 filmSample = texture(uFxVintageFilmTex, texUV).rgb;
        c += filmSample * texInt;
    }
#endif

    return clamp(c, 0.0, 4.0);
}

// Glow — highlight bloom tint.
vec3 applyGlow(vec3 c, vec3 bloomSample, float strength, float warmth) {
    if (strength == 0.0) return c;
    // Warmth coeff 0.55 (was 0.2) — UI ±50 mapped to ±0.5 needs a visible cast.
    vec3 warmBloom = bloomSample * vec3(1.0 + warmth * 0.55, 1.0, 1.0 - warmth * 0.55);
    return mix(c, c + warmBloom * strength, strength);
}

// PREQ-Port: Dust — Voronoi-like procedural particle overlay
float hash21(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
vec3 applyDust(vec3 c, vec2 uv, float amount, float size) {
    if (amount == 0.0) return c;
    float scale = mix(80.0, 20.0, size);
    vec2 cell = floor(uv * scale);
    float minDist = 1.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            vec2 nc = cell + vec2(float(dx), float(dy));
            vec2 pt = nc + vec2(hash21(nc), hash21(nc + 0.5));
            minDist = min(minDist, length(uv * scale - pt));
        }
    }
    float dust = smoothstep(0.05, 0.0, minDist - 0.45) * amount * 0.35;
    return clamp(c - vec3(dust), 0.0, 4.0);
}

// Filmic / Pro-Mist glow (2026-09-09). Old path was additive `c + glow*s²*1.2`
// which burned midtones into a sci-fi halo. Now: highlight-gated SCREEN blend
// (same family as Orton), optional desat via spread, warmth as a soft tint.
// MUST mirror applyGlowWithSpreadP in apply_macro.cpp.
vec3 applyGlowWithSpread(vec3 c, vec3 bloomSample, float strength, float spread, float warmth) {
    if (strength == 0.0) return c;
    float s = clamp(strength, 0.0, 1.0);
    vec3 glow = mix(bloomSample, vec3(dot(bloomSample, vec3(0.333))), clamp(spread, 0.0, 1.0) * 0.5);
    glow = max(glow, vec3(0.0));
    // Warmth coeff 0.55 (was 0.2) — must match apply_macro.cpp applyGlowWithSpreadP.
    glow *= vec3(1.0 + warmth * 0.55, 1.0, 1.0 - warmth * 0.55);
    float baseL = dot(c, vec3(0.2126, 0.7152, 0.0722));
    // Keep deep shadows rich — diffusion only wraps bright boundaries.
    float shadowMask = smoothstep(0.05, 0.35, baseL);
    vec3 screenC = 1.0 - (1.0 - clamp(c, 0.0, 1.0)) * (1.0 - clamp(glow, 0.0, 1.0));
    // 2× diffusion mix vs prior 0.55 (0=off still; full scale more aggressive).
    float amt = s * 1.10 * shadowMask;
    amt *= 1.0 - smoothstep(0.85, 1.0, baseL) * 0.55;
    return mix(c, screenC, amt);
}

// Lens flare — same Solid light-leak family as blendGradTint (screen + soft
// additive bloom). MUST mirror applyLensFlareP in apply_macro.cpp.
vec3 lfLeakBlend(vec3 dst, vec3 tint, float p) {
    p = clamp(p, 0.0, 1.0);
    if (p <= 0.0) return dst;
    vec3 screen = 1.0 - (1.0 - dst) * (1.0 - tint);
    vec3 add = dst + tint * (0.55 * p);
    vec3 leak = mix(screen, max(screen, add), 0.35);
    return mix(dst, leak, p);
}
vec3 applyLensFlare(vec3 c, vec2 uv, float fx, float fy,
                    float bright, float size, float spread, float warmth,
                    float distanceZ, float hood,
                    float starburst, float blades01, float irisRot, float roundness) {
    if (bright <= 0.0) return c;
    float z = clamp(distanceZ, 0.0, 1.0);
    if (!(z <= 1.0)) z = 1.0;
    float hd = clamp(hood, 0.0, 1.0);
    if (!(hd <= 1.0)) hd = 0.0;
    float zEff = z * z;
    float primaryK = mix(1.0, 0.60, hd);
    float ghostK = mix(1.0, 0.15, hd);
    float veilAmt = pow(1.0 - zEff, 2.0) * mix(1.0, 0.05, hd);
    // Hood cuts rays outside the field of view, so the ghost chain and veil
    // tighten. In-frame primary radii stay. Hood 0 leaves spread unchanged.
    // 0.45 is the floor: a matched hood cannot enter the picture, so it does
    // not collapse in-frame ghosts to a point.
    float spreadH = spread * mix(1.0, 0.45, hd);
    float ghostBoost = 1.0 + pow(1.0 - zEff, 1.5);
    float reach = mix(1.65, 1.0, zEff);
    float haloReach = mix(1.80, 1.0, zEff);
    vec2 flare = vec2(fx * 0.5 + 0.5, fy * 0.5 + 0.5);   // -1..1 → 0..1
    // Warmth 0 = soft warm-white; 1 = sunlight yellowish-orange → amber/peach.
    float w = clamp(warmth, 0.0, 1.0);
    vec3 tintCool = vec3(1.00, 0.97, 0.92);
    vec3 tintSun  = vec3(1.00, 0.72, 0.28);   // yellowish-orange sunlight
    vec3 tintHot  = vec3(1.00, 0.48, 0.18);   // deep amber
    vec3 col = mix(tintCool, mix(tintSun, tintHot, w), w);
    col = mix(col, vec3(1.00, 0.55, 0.42), w * 0.22); // soft peach/rose edge
    vec3 o = c;
    float d = distance(uv, flare);
    float scolor = 0.0375   * size * reach;
    float sglow  = 0.078125 * size * reach;
    float sinner = 0.1796875 * size * reach;
    float souter = 0.3359375 * size * reach;
    float shalo  = 0.084375  * size * haloReach;
    if (d < scolor) { float p = (scolor - d) / scolor; o = lfLeakBlend(o, col, p * p * primaryK); }
    if (d < sglow)  { float p = (sglow - d) / sglow;   o = lfLeakBlend(o, col, p * p * 0.6 * primaryK); }
    if (d < sinner) { float p = (sinner - d) / sinner; o = lfLeakBlend(o, col, p * p * 0.25 * primaryK); }
    if (d < souter) {
        float p = (souter - d) / souter;
        if (blades01 > 0.12 && roundness < 0.98) {
            int nb = int(clamp(floor(4.0 + blades01 * 12.0 + 0.5), 4.0, 16.0));
            vec2 rel = uv - flare;
            float ang = atan(rel.y, rel.x) - irisRot * 6.2831853;
            float sector = 6.2831853 / float(nb);
            float a = mod(ang + sector * 0.5, sector) - sector * 0.5;
            float poly = souter * cos(3.14159265 / float(nb)) / max(cos(a), 0.05);
            float inside = 1.0 - smoothstep(poly * 0.9, poly, d);
            p *= mix(inside, 1.0, clamp(roundness, 0.0, 1.0));
        }
        o = lfLeakBlend(o, col, p * 0.12 * primaryK);
    }
    { float p = 1.0 - clamp(abs(d - shalo) / (shalo * 0.15), 0.0, 1.0); o = lfLeakBlend(o, col, p * 0.2 * primaryK); }
    int blades = 0;
    if (blades01 > 0.12) blades = int(clamp(floor(4.0 + blades01 * 12.0 + 0.5), 4.0, 16.0));
    float rnd = clamp(roundness, 0.0, 1.0);
    if (blades >= 4 && rnd < 0.98 && starburst > 0.001) {
        float len = mix(0.025, 0.16, rnd) * max(size, 0.35);
        float maxWidth = len * 0.045;
        float rot = irisRot * 6.2831853;
        for (int k = 0; k < 16; k++) {
            if (k >= blades) break;
            float ang = rot + float(k) * 6.2831853 / float(blades);
            vec2 dir = vec2(cos(ang), sin(ang));
            vec2 rel = uv - flare;
            float along = dot(rel, dir);
            if (along <= 0.0 || along >= len) continue;
            float t = along / len;
            float width = maxWidth * (1.0 - t);
            float across = abs(rel.x * dir.y - rel.y * dir.x);
            float side = 1.0 - smoothstep(width * 0.15, max(width, 1e-4), across);
            float core = exp(-t * 2.0);
            float sp = core * side;
            o = lfLeakBlend(o, col, sp * starburst);
        }
    }
    if (veilAmt > 0.001) {
        float vr = souter * 1.6 * mix(1.0, 0.45, hd);
        float p = clamp(1.0 - d / vr, 0.0, 1.0);
        o = lfLeakBlend(o, col, p * p * 0.18 * veilAmt);
    }
    vec2 axis = (vec2(0.5) - flare) * spreadH;
    float coef[5] = float[5](-1.8, -0.8, 0.4, 1.2, 2.0);
    for (int i = 0; i < 5; i++) {
        vec2 gpos = flare + axis * coef[i];
        float gd = distance(uv, gpos);
        float gr = (0.015 + 0.008 * abs(coef[i])) * size;
        if (gd < gr) {
            float p = (gr - gd) / gr;
            // Warmth pulls ghosts toward peach/amber; cool alternate stays bluish at w=0.
            vec3 gtWarm = (mod(float(i), 2.0) < 0.5)
                ? mix(vec3(1.0, 0.85, 0.70), vec3(1.0, 0.62, 0.38), w)
                : mix(vec3(0.70, 0.85, 1.00), vec3(1.0, 0.58, 0.48), w);
            o = lfLeakBlend(o, gtWarm, p * p * 0.35 * ghostK * ghostBoost);
        }
    }
    return mix(c, o, bright);
}

float sampleSubjectAt(vec2 uv) {
    if (uSubjectMaskEnabled != 1) return 0.0;
    vec2 m = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, clamp(uv, 0.0, 1.0));
    return clamp(texture(uSubjectMask, m).r, 0.0, 1.0);
}

// Phase 1 cast shadow. Linear fractions of width. Background mask is 1 - subject.
vec3 applySceneShadow(vec3 c, vec2 uv) {
    if (uShadowStrength <= 0.001 || uSubjectMaskEnabled != 1) return c;
    float d = clamp(uSceneDistance, 0.0, 1.0);
    float s = clamp(uShadowStrength, 0.0, 1.0);
    float b = clamp(uShadowSoftness, 0.0, 1.0);
    vec2 light = vec2(uLensFlareX, uLensFlareY);
    if (length(light) < 0.0001) light = vec2(0.0, -1.0);
    vec2 shadowDir = normalize(-light);
    float aspect = uShadowAspect > 0.01 ? uShadowAspect : 1.0;
    float travel = mix(0.15, 0.02, d);
    float blurF = mix(0.03, 0.006, d) + b * 0.02;
    vec2 stepUv = vec2(shadowDir.x * travel, shadowDir.y * travel * aspect);
    vec2 origin = clamp(uv - stepUv, 0.0, 1.0);
    // One Gaussian around the projected point. Tap spacing is a third of the
    // blur radius so islands do not spawn a second shadow a full radius away.
    vec2 rad = vec2(blurF, blurF * aspect) / 3.0;
    float acc = sampleSubjectAt(origin) * 0.25;
    acc += sampleSubjectAt(origin + vec2(rad.x, 0.0)) * 0.125;
    acc += sampleSubjectAt(origin - vec2(rad.x, 0.0)) * 0.125;
    acc += sampleSubjectAt(origin + vec2(0.0, rad.y)) * 0.125;
    acc += sampleSubjectAt(origin - vec2(0.0, rad.y)) * 0.125;
    acc += sampleSubjectAt(origin + rad) * 0.0625;
    acc += sampleSubjectAt(origin + vec2(rad.x, -rad.y)) * 0.0625;
    acc += sampleSubjectAt(origin + vec2(-rad.x, rad.y)) * 0.0625;
    acc += sampleSubjectAt(origin - rad) * 0.0625;
    // Grow the mask back toward the subject so a tight segment still contacts.
    vec2 contact = clamp(mix(origin, uv, 0.55), 0.0, 1.0);
    float grown = max(acc, sampleSubjectAt(contact));
    float alpha = clamp(grown * s, 0.0, 1.0);
    return c * (1.0 - alpha);
}

// PREQ-Port: PushPull — EV shift applied after grading
vec3 applyPushPull(vec3 c, float ev) {
    if (ev == 0.0) return c;
    return clamp(c * pow(2.0, ev), 0.0, 4.0);
}

// PREQ-Port: Cubic Lens Distortion — barrel/pincushion UV remapping (Req 2)
vec2 computeUV(vec2 uv, float k, float kcube) {
    vec2 t = uv - 0.5;
    float r2 = dot(t, t);
    float f = (kcube == 0.0) ? (1.0 + r2 * k)
                              : (1.0 + r2 * (k + kcube * sqrt(r2)));
    return clamp(f * t + 0.5, 0.0, 1.0);
}

// PREQ-Port: Aberration — cubic per-channel barrel distortion (Req 2)
vec3 applyAberration(sampler2D tex, vec2 uv, float strength, float separation) {
    if (strength == 0.0) return texture(tex, uv).rgb;
    float k      = strength * 0.9  * separation;
    float kcube  = 0.5 * strength;
    float offset = strength * 0.05 * separation;
    float r = texture(tex, computeUV(uv, k + offset, kcube)).r;
    float g = texture(tex, computeUV(uv, k,          kcube)).g;
    float b = texture(tex, computeUV(uv, k - offset, kcube)).b;
    return vec3(r, g, b);
}

// PREQ-Port: Brightness/Contrast/Gamma correction helper (Req 4).
// Identity: brightness=0, contrast=1, gamma=1.
// Usable from uber-shader inline code and standalone pass programs.
vec3 bcgCorrect(vec3 c, float brightness, float contrast, float gamma) {
    c = (c - 0.5) * contrast + 0.5 + brightness;
    return pow(abs(c), vec3(gamma));  // abs() prevents pow domain error on negatives
}

// Haxademic radial vignette (Req 5).
// Identity: spread=1.0, darkness=0.0 → effectively no darkening for dist < 0.799.
// Does NOT replace parametric vignette (slots 61–67); targets slot 371 (FX tab creative).
// Alpha not affected — operates on vec3 RGB only.
vec3 applyHaxVignette(vec3 rgb, vec2 uv, float spread, float darkness) {
    float dist = distance(uv, vec2(0.5));
    // spread*0.799 < 0.8 ensures smoothstep range is always > 0
    rgb *= smoothstep(0.8, spread * 0.799, dist * (darkness + spread));
    return rgb;
}

// Haxademic film grain — fast sin-dot hash (Req 8).
// Separate from cinematicGrain (3D value-noise, slots 153/154/156).
float haxGrain(vec2 st, float seed) {
    return fract(sin(dot(st, vec2(17.0, 180.0))) * 2500.0 + seed);
}

// PREQ-Port: DetailGrain — screen-space noise roughness
vec3 applyDetailGrain(vec3 c, vec2 uv, float roughness) {
    if (roughness == 0.0) return c;
    float n = hash21(floor(uv * 120.0));
    return clamp(c + vec3((n - 0.5) * roughness * 0.04), 0.0, 4.0);
}

// ── M12.2b.2 Gradient helpers — solid band + smoothstep soft edge.
// Light-leak *color* lives in blendGradTint (screen+add); the mask itself
// must stay a classic Length/Feather control so the Feather slider is
// continuously visible at every Length (including 100%).
//   length  = how far from the edge the mask reaches (end of falloff).
//   feather = 0 → hard cut at length; 1 → fully soft ramp from edge to length.
//             Intermediate values keep a full-strength core then smoothstep.
float edgeFalloff(float dist, float length, float feather) {
    float L = clamp(length, 0.0, 1.0);
    float f = clamp(feather, 0.0, 1.0);
    if (L <= 0.0) return 0.0;
    if (f <= 0.0) return dist <= L ? 1.0 : 0.0;
    // Soft width eats into the solid band so Feather still works when L≈1
    // (the old (1-L)*feather taper collapsed to ~0 at Length 100%).
    float softStart = L * (1.0 - f);
    float softEnd   = max(L, softStart + 1e-4);
    return 1.0 - smoothstep(softStart, softEnd, dist);
}
float edgeFalloff2(float dist, float length1, float feather1,
                   float length2, float feather2) {
    // Layer-2 anchors at length1 — second tint bleeds through layer-1's
    // softening falloff (film light-leak feel). feather1 unused; taper
    // shape is owned by layer-2's own length2/feather2.
    if (dist < length1) return 0.0;
    return edgeFalloff(dist - length1, length2, feather2);
}
void sideFalloffs(float side[15], float dist, out float f1, out float f2) {
    float intensity1 = side[0];
    float length1    = side[1];
    float feather1   = side[2];
    float tintLum1   = side[6];
    float enable2    = side[7];
    float intensity2 = side[8];
    float length2_   = side[9];
    float feather2_  = side[10];
    float tintLum2   = side[14];
    f1 = (intensity1 != 0.0 || tintLum1 > 0.0)
        ? edgeFalloff(dist, length1, feather1) : 0.0;
    f2 = (enable2 > 0.5 && (intensity2 != 0.0 || tintLum2 > 0.0))
        ? edgeFalloff2(dist, length1, feather1, length2_, feather2_) : 0.0;
}
// mode 0 = Solid / light-leak: screen + soft additive bloom (warm edge wash,
// never muddy lerp). mode 1 = Fused: overlay for detail-preserving tint.
vec3 blendGradTint(vec3 c, vec3 tint, float tintLum, float falloff, int mode) {
    if (tintLum <= 0.0 || falloff <= 0.0) return c;
    float w = clamp(tintLum * falloff, 0.0, 1.0);
    if (mode == 1) {
        vec3 lo = 2.0 * c * tint;
        vec3 hi = 1.0 - 2.0 * (1.0 - c) * (1.0 - tint);
        vec3 ov = mix(lo, hi, step(vec3(0.5), c));
        // Bias fused toward screen in the tinted highlights so warm leaks
        // still glow instead of crushing midtones via the multiply branch.
        vec3 scr = 1.0 - (1.0 - c) * (1.0 - tint);
        ov = mix(ov, max(ov, scr), 0.45);
        return mix(c, ov, w);
    }
    // Cinematic light leak: screen (brightens, keeps underlying detail) +
    // a touch of additive amber bloom. Avoids flat solid-color overlays.
    vec3 screen = 1.0 - (1.0 - c) * (1.0 - tint);
    vec3 add = c + tint * (0.55 * w);
    vec3 leak = mix(screen, max(screen, add), 0.35);
    return mix(c, leak, w);
}

// M12.2c.1 — Semantic gating multiplier.
//   Returns 1.0 when the effect should apply at full strength, 0.0 when
//   it should be fully discarded, with smooth feathering at subject
//   boundaries. Squaring biases toward high-confidence pixels (sharp
//   silhouettes, suppressed background haze).
float subjectGate(int target) {
    if (target == 0 || uSubjectMaskEnabled == 0) return 1.0;
    vec2 maskUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord);
    float p = texture(uSubjectMask, maskUV).r;
    // M12.2c.4 — Edge-snap U2Net's soft silhouette using the Sobel edge
    // mask we already compute alongside it. At high-contrast pixels,
    // push the soft probability toward 0 or 1 so hair / feather / fur
    // boundaries crisp up without retraining the network. Below the
    // threshold the original soft value passes through, preserving
    // U2Net's anti-aliasing on smooth subject boundaries.
    if (uEdgeSnapStrength > 0.0) {
        float edge = texture(uSobelEdgeMask, maskUV).r;
        if (edge > uEdgeSnapThreshold) {
            float push = edge * uEdgeSnapStrength;
            p = (p > 0.5) ? min(1.0, p + push) : max(0.0, p - push);
        }
    }
    p = p * p;
    if (target == 1) return p;                // Subject only
    if (target == 2) return 1.0 - p;          // Background only
    return 1.0;
}

// Protect-subject halation kill — soft near-band outside the silhouette.
// Returns 1 far from subject (full R/B offset), 0 on subject interior with a
// soft ramp across ~0.10 * longSide pixels outside the mask. Only active when
// uBloomExcludeSubject is on and the subject mask is uploaded. Orton strength
// still uses the separate inward feather (kBloomFeather=0.02); this gate is
// halation-only so chromatic fringing does not hug the protected subject.
// MUST mirror apply_macro.cpp halationProtectScaleP().
float halationProtectScale() {
    if (uBloomExcludeSubject <= 0.5 || uSubjectMaskEnabled != 1) return 1.0;
    vec2 texPx = vec2(textureSize(uTex, 0));
    float longSide = max(texPx.x, texPx.y);
    float radiusPx = 0.10 * longSide;
    vec2 featherUV = vec2(radiusPx / max(texPx.x, 1.0),
                          radiusPx / max(texPx.y, 1.0));
    vec2 maskUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord);
    vec2 dMask  = (uSubjectMaskRect.zw - uSubjectMaskRect.xy) * (featherUV * 0.5);
    float acc = 0.0;
    for (int ty = -2; ty <= 2; ++ty) {
        for (int tx = -2; tx <= 2; ++tx) {
            vec2 o = maskUV + vec2(float(tx), float(ty)) * dMask;
            acc += clamp(texture(uSubjectMask, clamp(o, vec2(0.0), vec2(1.0))).r, 0.0, 1.0);
        }
    }
    float near = acc / 25.0;
    // Full exclude while near≥0.45 (subject + inner band); ramp to full halo
    // by near≤0.05 (outer edge of the dilated soft band).
    return 1.0 - smoothstep(0.05, 0.45, near);
}

// Luminance-range mask: builds a mask from a pixel's luma relative to a
// target. Full strength (1.0) within +/- `spread` of `target`, then feathered
// linearly to 0 over an extra `feather` width on each side. This GENERATES the
// selection (it isn't a gate on a painted brush) — every pixel near the target
// tone is selected. Used when a layer's luminance mask is active (spread > 0).
float lumMask(float L, float target, float spread, float feather) {
    float d = abs(L - target);
    float f = max(feather, 1e-4);
    // 1 inside the [0, spread] core, ramp down to 0 across [spread, spread+f].
    return clamp(1.0 - smoothstep(spread, spread + f, d), 0.0, 1.0);
}

// Sample a mask layer's brush/object bitmap alpha (GLSL can't index samplers).
// Layers compiled out by RAZ_GLES_EXTRA_MASKS report 0.0 — maskLayerAlpha then
// contributes nothing for that layer, which is exactly the low-end
// degradation we want (its luma band, being analytic, still works).
float sampleBrushLayer(int li) {
    if      (li == 0) return texture(uBrushMask,  vTexCoord).r;
#if RAZ_GLES_EXTRA_MASKS >= 1
    else if (li == 1) return texture(uBrushMask1, vTexCoord).r;
#endif
#if RAZ_GLES_EXTRA_MASKS >= 2
    else if (li == 2) return texture(uBrushMask2, vTexCoord).r;
#endif
#if RAZ_GLES_EXTRA_MASKS >= 3
    else if (li == 3) return texture(uBrushMask3, vTexCoord).r;
#endif
    else              return 0.0;
}

// Final selection alpha for a mask layer, combining a LIVE luma band with the
// layer's brush/object bitmap when both are present. This is what lets a luma
// base have object regions carved out of it live (mode 1), or the reverse
// (mode 2), fully WYSIWYG. When only one source is active it degrades to that
// source (legacy behaviour). Returns 0 when the layer contributes nothing.
float maskLayerAlpha(int li, vec3 img) {
    bool lumActive   = uMaskLumSpread[li] > 0.0;
    bool brushActive = (uBrushMaskEnabled & (1 << li)) != 0;
    if (!lumActive && !brushActive) return 0.0;
    float lf = 0.0;
    if (lumActive) {
        float gl = clamp(dot(clamp(img, 0.0, 1.0), vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
        lf = lumMask(gl, uMaskLumTarget[li], uMaskLumSpread[li], uMaskLumFeather[li]);
    }
    if (!lumActive)   return sampleBrushLayer(li);   // bitmap only
    if (!brushActive) return lf;                     // luma only
    float bf = sampleBrushLayer(li);
    int mode = uMaskLumCombine[li];
    if (mode == 1) return lf * (1.0 - bf);           // luma base − bitmap
    if (mode == 2) return bf * (1.0 - lf);           // bitmap base − luma
    if (mode == 3) return max(lf, bf);               // union
    if (mode == 4) return lf * bf;                   // intersect
    return lf;                                        // 0: legacy (luma wins)
}

// ── Cinematic film grain (Matt DesLauriers / Martins Upitis) ────────────
//   Compact 3D value noise (replaces the Ashima/glslify imports) — smooth
//   Hermite-interpolated lattice noise. Low-freq noise offsets high-freq
//   noise so the grain swirls organically like silver-halide crystals.
float grainHash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float noise3D(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);   // Hermite
    return mix(
        mix(mix(grainHash(i + vec3(0,0,0)), grainHash(i + vec3(1,0,0)), f.x),
            mix(grainHash(i + vec3(0,1,0)), grainHash(i + vec3(1,1,0)), f.x), f.y),
        mix(mix(grainHash(i + vec3(0,0,1)), grainHash(i + vec3(1,0,1)), f.x),
            mix(grainHash(i + vec3(0,1,1)), grainHash(i + vec3(1,1,1)), f.x), f.y), f.z);
}
// Returns grain in [-1, 1]. texCoord normalised [0,1]; resolution is the
// FIXED reference grid; size [0..1] scales crystal size; seed shifts Z.
float cinematicGrain(vec2 texCoord, vec2 resolution, float seed, float size) {
    float multiplier = 1.0 + size * 2.0;       // larger size → coarser crystals
    vec2  mult = texCoord * (resolution / multiplier);
    float offset = noise3D(vec3(mult / 2.5, seed));      // low-freq swirl
    float n1 = noise3D(vec3(mult, offset * 10.0));        // high-freq, displaced
    return n1 * 2.0 - 1.0;
}

vec2 grainGrid(vec2 resolution) {
    float ls = max(max(resolution.x, resolution.y), 1.0);
    float d = clamp(ls / 2048.0, 0.5, 2.5);
    return vec2(2048.0 * d * (resolution.x / ls), 2048.0 * d * (resolution.y / ls));
}

float grainOctave(vec2 p, float seed) {
    float offset = noise3D(vec3(p / 2.5, seed));
    return noise3D(vec3(p, offset * 10.0));
}

vec3 emulsionGrain(vec2 uv, vec2 grid, float seed, float size, float structure, float chroma, float cloud) {
    float multiplier = 1.0 + size * 2.0;
    vec2 p = uv * (grid / multiplier);
    float n1 = grainOctave(p, seed);
    float n2 = grainOctave(p * 2.1, seed + 19.0);
    float n3 = grainOctave(p * 4.3, seed + 47.0);
    float n = n1 * 0.55 + n2 * 0.30 + n3 * 0.15;
    float k = clamp(structure, 0.0, 1.0);
    float cell = grainOctave(p * mix(0.35, 0.08, k), seed + 101.0);
    float cluster = smoothstep(mix(0.35, 0.15, cloud), mix(0.75, 0.55, cloud), cell);
    n *= mix(1.0, cluster, k);
    float c = clamp(chroma, 0.0, 1.0);
    float nr = n;
    float ng = grainOctave(p * mix(1.0, 1.35, c), seed + 7.0);
    float nb = grainOctave(p * mix(1.0, 0.72, c) + vec2(c * 3.0, 0.0), seed + 13.0);
    return mix(vec3(n), vec3(nr, ng, nb), c) * 2.0 - 1.0;
}

// (Snapseed-style grain helpers removed — Cinematic 3D-noise path
//  is the only grain implementation now.)

// ── Tetrahedral 3D-LUT sampling ─────────────────────────────────────────────
//   GPU hardware trilinear interpolation (the default `texture(...)` on a
//   sampler3D) blends 8 corners of the enclosing cube cell. On smooth
//   gradients (skin tones, sky) this leaves subtle ringing at the LUT-cell
//   boundaries because the cube cell's interior isn't flat — it's a
//   trilinear bilerp surface that bows outward.
//
//   Tetrahedral interpolation splits each cube cell into 6 tetrahedra and
//   uses ONLY 4 corners (the tetrahedron's vertices) — a true affine
//   interpolation with no bowing. Resolve / Nuke / Premiere all use this.
//   Visibly cleaner on phone screens, no extra texture bandwidth (we still
//   sample 4 corners via individual texelFetch calls, vs the 8-corner
//   hardware lerp). Cheap.
// ── Gamut conversion matrices ────────────────────────────────────────────
//   All matrices are LINEAR-LIGHT (not sRGB-encoded). Constants from the
//   colour-matrix references at color.org and Bruce Lindbloom.
//
//   Conventions:
//     mat3() in GLSL is column-major. Each constructor block lists ROWS
//     for readability; the `transpose(mat3(...))` wrapper reinterprets
//     them as columns.
//
//   These convert a pixel in one working space INTO sRGB primaries, in
//   linear-light. To go the other way (sRGB → working space) we use the
//   inverse matrices below.
mat3 mProPhotoToSrgb() {
    return mat3(
        // row 0: (R sRGB) = 2.0345·Rp - 0.7275·Gp - 0.3070·Bp
         2.0345430, -0.7275032, -0.3070398,
        -0.2280561,  1.2317756, -0.0037195,
        -0.0085519, -0.1535428,  1.1620946
    );
}
mat3 mSrgbToProPhoto() {
    return mat3(
         0.5293115,  0.3300603,  0.1406282,
         0.0982103,  0.8734255,  0.0283642,
         0.0168743,  0.1176865,  0.8654391
    );
}
mat3 mAdobeRgbToSrgb() {
    return mat3(
         1.3982830, -0.3982830,  0.0000000,
         0.0000000,  1.0000000,  0.0000000,
         0.0000000, -0.0428147,  1.0428147
    );
}
mat3 mSrgbToAdobeRgb() {
    return mat3(
         0.7152055,  0.2847850,  0.0000000,
         0.0000000,  1.0000000,  0.0000000,
         0.0000000,  0.0410437,  0.9589563
    );
}
mat3 mDciP3ToSrgb() {
    return mat3(
         1.2249401, -0.2249404,  0.0000000,
        -0.0420569,  1.0420570,  0.0000000,
        -0.0196376, -0.0786361,  1.0982735
    );
}
mat3 mSrgbToDciP3() {
    return mat3(
         0.8225136,  0.1774864,  0.0000000,
         0.0331792,  0.9668208,  0.0000000,
         0.0170643,  0.0723898,  0.9105459
    );
}
mat3 mRec2020ToSrgb() {
    return mat3(
         1.6605000, -0.5876000, -0.0728000,
        -0.1246000,  1.1329000, -0.0083000,
        -0.0182000, -0.1006000,  1.1187000
    );
}
mat3 mSrgbToRec2020() {
    return mat3(
         0.6274040,  0.3292820,  0.0433136,
         0.0690970,  0.9195400,  0.0113612,
         0.0163914,  0.0880132,  0.8955950
    );
}

// Transform `c` from the current working space INTO sRGB. Caller knows
// the working space via uWorkspaceSpace. Identity when input is already
// sRGB or when the space is one we don't have a matrix for (Raw / XYZ /
// ACES — those user choices fall back to no-op for LUT sampling).
vec3 toSrgb(vec3 c, int space) {
    if (space == 2) return mAdobeRgbToSrgb() * c;
    if (space == 4) return mProPhotoToSrgb() * c;
    if (space == 7) return mDciP3ToSrgb()    * c;
    if (space == 8) return mRec2020ToSrgb()  * c;
    return c; // sRGB or unsupported — identity
}

// Inverse: transform sRGB pixel back into the working space.
vec3 fromSrgb(vec3 c, int space) {
    if (space == 2) return mSrgbToAdobeRgb() * c;
    if (space == 4) return mSrgbToProPhoto() * c;
    if (space == 7) return mSrgbToDciP3()    * c;
    if (space == 8) return mSrgbToRec2020()  * c;
    return c;
}

vec3 tetrahedralLut3D(sampler3D lut, vec3 rgb, float sizef) {
    float N = max(sizef, 2.0);
    vec3 idx = clamp(rgb, 0.0, 1.0) * (N - 1.0);     // [0, N-1]
    vec3 i0  = floor(idx);
    vec3 d   = idx - i0;                              // fractional [0,1)^3
    // Sample at (i + 0.5)/N to read the texel centre.
    float invN = 1.0 / N;
    vec3 base = (i0 + 0.5) * invN;
    vec3 step = vec3(invN);
    // 8 corners of the cube cell — we only need 4 depending on which
    // tetrahedron the (d.r, d.g, d.b) point falls into.
    // Tetrahedral case selection per Kirk & Vorhies, used by darktable
    // and most LUT processors.
    vec3 c000 = texture(lut, base                                   ).rgb;
    vec3 c111 = texture(lut, base + vec3(step.x, step.y, step.z)    ).rgb;
    vec3 result;
    if (d.r > d.g) {
        if (d.g > d.b) {                              // R > G > B
            vec3 c100 = texture(lut, base + vec3(step.x, 0.0, 0.0)).rgb;
            vec3 c110 = texture(lut, base + vec3(step.x, step.y, 0.0)).rgb;
            result = (1.0 - d.r) * c000 + (d.r - d.g) * c100
                   + (d.g - d.b) * c110 + d.b * c111;
        } else if (d.r > d.b) {                       // R > B >= G
            vec3 c100 = texture(lut, base + vec3(step.x, 0.0, 0.0)).rgb;
            vec3 c101 = texture(lut, base + vec3(step.x, 0.0, step.z)).rgb;
            result = (1.0 - d.r) * c000 + (d.r - d.b) * c100
                   + (d.b - d.g) * c101 + d.g * c111;
        } else {                                      // B >= R > G
            vec3 c001 = texture(lut, base + vec3(0.0, 0.0, step.z)).rgb;
            vec3 c101 = texture(lut, base + vec3(step.x, 0.0, step.z)).rgb;
            result = (1.0 - d.b) * c000 + (d.b - d.r) * c001
                   + (d.r - d.g) * c101 + d.g * c111;
        }
    } else {
        if (d.b > d.g) {                              // B > G >= R
            vec3 c001 = texture(lut, base + vec3(0.0, 0.0, step.z)).rgb;
            vec3 c011 = texture(lut, base + vec3(0.0, step.y, step.z)).rgb;
            result = (1.0 - d.b) * c000 + (d.b - d.g) * c001
                   + (d.g - d.r) * c011 + d.r * c111;
        } else if (d.b > d.r) {                       // G >= B > R
            vec3 c010 = texture(lut, base + vec3(0.0, step.y, 0.0)).rgb;
            vec3 c011 = texture(lut, base + vec3(0.0, step.y, step.z)).rgb;
            result = (1.0 - d.g) * c000 + (d.g - d.b) * c010
                   + (d.b - d.r) * c011 + d.r * c111;
        } else {                                      // G >= R >= B
            vec3 c010 = texture(lut, base + vec3(0.0, step.y, 0.0)).rgb;
            vec3 c110 = texture(lut, base + vec3(step.x, step.y, 0.0)).rgb;
            result = (1.0 - d.g) * c000 + (d.g - d.r) * c010
                   + (d.r - d.b) * c110 + d.b * c111;
        }
    }
    return result;
}
)GLSL"  // end §2 — helper functions

// §3 — Main fragment entry point
R"GLSL(
void main() {
    // Source geometry sample — ColorShift (OpenShot RGB split) + cubic CA
    // (slots 352/353). Dual-on ordering (GOTCHAS):
    //   Physical (ML/user cubic CA + ColorShift): ColorShift → CA, then grade.
    //   FX film-CA look: ColorShift alone (OpenShot RGB split) — no cubic.
    //   Do NOT use ColorShift→CA order for ColorShift-only; do NOT drop
    //   ColorShift when cubic CA is on (old bug: CA branch skipped shift).
    // Stage C mirrors this in pre-passes before apply_macro.
    vec3 c;
    // NOT `const` — Mali GLES rejects const vars initialized from uniforms
    // (S0013). Desktop ANGLE accepted this; Infinix/PowerVR fail shader compile
    // → bootRenderer returns 0 → preview blank + every LUT upload fails.
    bool caOn = uAberStrength > 0.0;
    bool csOn = uColorShiftRedX != 0.0 || uColorShiftGreenX != 0.0 || uColorShiftBlueX != 0.0;
    if (caOn && csOn) {
        // Physical: shift UV per channel, then cubic CA distortion per channel.
        float k      = uAberStrength * 0.9  * uAberFringeReduce;
        float kcube  = 0.5 * uAberStrength;
        float offset = uAberStrength * 0.05 * uAberFringeReduce;
        c.r = texture(uTex, computeUV(vTexCoord + vec2(uColorShiftRedX,   0.0), k + offset, kcube)).r;
        c.g = texture(uTex, computeUV(vTexCoord + vec2(uColorShiftGreenX, 0.0), k,          kcube)).g;
        c.b = texture(uTex, computeUV(vTexCoord + vec2(uColorShiftBlueX,  0.0), k - offset, kcube)).b;
    } else if (caOn) {
        c = applyAberration(uTex, vTexCoord, uAberStrength, uAberFringeReduce);
    } else if (csOn) {
        // FX film-CA look (ColorShift alone) — intentional chromatic split.
        c.r = texture(uTex, vTexCoord + vec2(uColorShiftRedX,   0.0)).r;
        c.g = texture(uTex, vTexCoord + vec2(uColorShiftGreenX, 0.0)).g;
        c.b = texture(uTex, vTexCoord + vec2(uColorShiftBlueX,  0.0)).b;
    } else {
        c = texture(uTex, vTexCoord).rgb;
    }

    // ── Purple-fringe desaturation pass ─────────────────────────────────
    //   Fast runtime CA cleanup based on mjambon/purple-fringe. Two-condition
    //   gate per pixel:
    //     (1) The pixel itself looks PURPLE/VIOLET: max(R,B) > G by a
    //         meaningful margin AND R+B dominates over G (chroma points
    //         to the magenta–violet hue range).
    //     (2) There's a CLIPPED HIGHLIGHT in a small neighbourhood (so the
    //         purple is plausibly lens fringing, not a real purple object
    //         like a violet flower). We sample 4 diagonals at ~3 px and
    //         check if any reaches near-white.
    //   When both fire, we pull the pixel's chroma toward its own luma
    //   (desat-toward-luma — same trick the RCD highlight reconstruction
    //   uses). Strength scales with how purple AND how close to clip, so
    //   real magenta details further from highlights are untouched.
    //
    //   Gated by uPurpleFringeMode == 2 (Strong). Light/Off skip entirely.
    if (uPurpleFringeMode == 2) {
        // (1) Is this pixel purple? Decompose into "magenta strength":
        //   m = (R + B)/2 - G   → > 0 means chroma points away from green
        //   v = min(R, B)       → reject pure red or pure blue (we want both)
        float mag = (c.r + c.b) * 0.5 - c.g;
        float v   = min(c.r, c.b);
        // purpleScore in [0,1] — > 0 only when both R and B exceed G by a
        // meaningful amount, scaled so real purple objects (which usually
        // have mag < 0.1) score low. The 8.0 multiplier ramps quickly.
        float purpleScore = clamp(mag * 8.0, 0.0, 1.0) *
                            clamp((v - c.g) * 8.0, 0.0, 1.0);

        if (purpleScore > 0.0) {
            // (2) Is there a near-clipped pixel close by? Sample 4 diagonals
            // at ~3 px in each direction (resolution-relative). If the max
            // neighbour luma exceeds 0.92, the area is plausibly a clipped
            // highlight rolloff — exactly where lens CA fringes appear.
            vec2 px = 3.0 / vec2(textureSize(uTex, 0));
            vec3 n0 = texture(uTex, vTexCoord + vec2( px.x,  px.y)).rgb;
            vec3 n1 = texture(uTex, vTexCoord + vec2(-px.x,  px.y)).rgb;
            vec3 n2 = texture(uTex, vTexCoord + vec2( px.x, -px.y)).rgb;
            vec3 n3 = texture(uTex, vTexCoord + vec2(-px.x, -px.y)).rgb;
            float maxN = max(max(max(n0.r, n0.g), max(n0.b, n1.r)),
                       max(max(n1.g, n1.b), max(n2.r, n2.g)));
            maxN = max(maxN, max(max(n2.b, n3.r), max(n3.g, n3.b)));
            // Soft gate — 0 below 0.85, 1 above 0.98.
            float clipNear = smoothstep(0.85, 0.98, maxN);

            // Strength: both gates multiplied. Real purple flowers far
            // from clipped pixels see clipNear ≈ 0 → untouched.
            float strength = purpleScore * clipNear;
            if (strength > 0.0) {
                // Desat toward this pixel's own luma (Rec.709 weights).
                float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                c = mix(c, vec3(luma), strength);
            }
        }
    }

    // ── Domain note (Stage A change) ─────────────────────────────────────
    //   Stage A now writes the FP16 cache GAMMA-ENCODED in sRGB transfer
    //   (LibRaw's gamm = (1/2.4, 12.92) + autobright). The shader samples
    //   gamma-encoded pixels, applies its math in the same domain, then
    //   passes straight through to the 8-bit sRGB framebuffer with NO
    //   additional gamma encode. The math (exposure as exp2(), tone
    //   regions on a luma proxy, HSL via RGB↔HSL) is well-behaved in this
    //   domain for the range of values cameras actually capture.
    // ── M12.1 layered compositing — SEQUENTIAL PIPELINE ─────────────────
    //   Previously v3 used a FAN-OUT model: every tab computed against the
    //   raw baseRGB and we summed deltas at the end. That caused visible
    //   problems when stacking effects:
    //     • Curves "lift shadows" + Tonemap "crush shadows" cancelled out
    //       instead of one applying after the other.
    //     • Same slider twice (Clarity → Clarity) doubled the delta against
    //       the raw base, not against the already-sharpened pixel.
    //     • Mask layers (subject + sky + face) each read baseRGB, so
    //       overlapping regions produced compounding-but-wrong stacks.
    //     • LUT-on-LUT composited in parallel instead of chained.
    //
    //   New model: each tab reads the RUNNING image and writes it back via
    //       img = mix(img, effect(img), opacity)
    //   so the next tab sees the previous tab's output. Mask layers also
    //   chain off this running image. Result: stacking is well-defined,
    //   conflicts resolve as "last writer wins" within the tab order, and
    //   re-applying the same effect compounds correctly.
    vec3 baseRGB = c;
    vec3 img    = baseRGB;  // running pipeline state

    // ── Light tab — stacked exposure / WB / tone regions / dehaze ──────
    //   Tonemap-tab tone values are stacked on top of the Light-tab pass
    //   inside this same lightTab buffer. Independent uniforms keep XMP
    //   preset load from clobbering AUTO EXPO. uContrast / uWhites /
    //   uBlacks live on the Tonemap tab only (no Light-tab duplicate),
    //   so they apply once here.
    vec3 lightTab = img;
    // Bloom auto-exposure compensation. Karis bloom is additive
    // (`c + bloom * strength`) so a strong bloom inevitably lifts the
    // image's overall light level. To keep the perceived exposure stable
    // we pre-subtract one EV stop per unit of bloom strength (slider 0..1).
    // Subject-only bloom counts via max() so the strongest active bloom
    // drives the compensation.
    // bloomExpoComp removed: the highlight-protect gate (highlightProtect) already
    // prevents bloom from lifting highlights past white. Pre-darkening the whole
    // image to compensate was making shadows darker even where no bloom lands.
    lightTab = applyExposureContrastP(lightTab, uExposure, 0.0);
    if (uTonemapExposure != 0.0) {
        lightTab = applyExposureContrastP(lightTab, uTonemapExposure, 0.0);
    }
    lightTab = applyWbTintP(lightTab, uWhiteBalance, uTint);
    lightTab = applyToneRegionsP(lightTab, uHighlights, uShadows, uWhites, uBlacks);
    // Per-segment levels (Normalize for 3Dlut). Compute a subject-targeted
    // levels remap and a background-targeted one, then blend by the U2Net
    // subject probability. Runs only when a subject mask is uploaded AND at
    // least one of the four sliders is non-zero — otherwise inert.
    if (uSubjectMaskEnabled == 1 &&
        (uWhitesSubject != 0.0 || uBlacksSubject != 0.0 ||
         uWhitesBackground != 0.0 || uBlacksBackground != 0.0 ||
         uShadowsSubject != 0.0 || uShadowsBackground != 0.0 ||
         uHighlightsSubject != 0.0 || uHighlightsBackground != 0.0)) {
        // applyToneRegionsP(c, hi, sh, wh, bl): hi + sh slots now BOTH carry
        // per-segment values so Auto Expo's percentage-scaled highlight pull
        // can apply differently to subject vs background.
        vec3 subjTab = applyToneRegionsP(lightTab, uHighlightsSubject,
            uShadowsSubject, uWhitesSubject, uBlacksSubject);
        vec3 bgTab   = applyToneRegionsP(lightTab, uHighlightsBackground,
            uShadowsBackground, uWhitesBackground, uBlacksBackground);
        vec2 maskUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord);
        float p = clamp(texture(uSubjectMask, maskUV).r, 0.0, 1.0);
        lightTab = mix(bgTab, subjTab, p);
    }
    if (uTonemapHighlights != 0.0 || uTonemapShadows != 0.0) {
        lightTab = applyToneRegionsP(lightTab, uTonemapHighlights, uTonemapShadows, 0.0, 0.0);
    }
    // Film-style highlight shoulder. Applied AFTER tone regions so the
    // user's Whites / Highlights slider lifts come through, then get
    // softly compressed by the rolloff curve. At strength=0 this is a
    // no-op; at strength=1 the upper-third of the range bends back
    // toward midtones for the soft analog-negative look.
    lightTab = applyFilmRolloff(lightTab, uFilmRolloff);
    // Filmic luma (brief): preserve chroma, tone-map Y only.
    {
        float fl = uFilmicLuma;
                // auto filmic removed
        lightTab = applyFilmicLuma(lightTab, fl);
    }
    // ── Ambiance ────────────────────────────────────────────────────────
    //   Edge-aware local-contrast + midtone-only saturation lift. Snapseed's
    //   secret-sauce slider; approximated here with a Gaussian-blurred
    //   reference layer (uBlurTex is already populated for bokeh/glow).
    //     detail = lightTab - lowpass
    //     lightTab = lowpass + detail * (1 + 0.6*a)
    //   followed by S *= 1 + 0.4*a*midtoneWeight in HSV-equivalent space.
    // Effective ambiance per fragment: starts with the global slider; if a
    // subject mask is present AND a per-segment override is non-zero, blend
    // toward (subject|background)-specific value using the mask probability.
    // Auto Expo writes per-segment values scaled by each region's channel-clip
    // percentage so a cluttered background can receive more ambiance lift
    // without flattening the subject.
    float effAmb = uAmbiance;
    if (uSubjectMaskEnabled == 1 &&
        (uAmbianceSubject != 0.0 || uAmbianceBackground != 0.0)) {
        vec2 maskUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord);
        float p = clamp(texture(uSubjectMask, maskUV).r, 0.0, 1.0);
        // Add per-segment delta on top of the global slider so the user's
        // manual Ambiance slider is never discarded when AE has written
        // per-segment values. AE zeroes the global itself when distributing
        // into segments, so there's no double-count in the AE-only case.
        effAmb = uAmbiance + mix(uAmbianceBackground, uAmbianceSubject, p);
    }
    // ── Tonal-match blur tap ───────────────────────────────────────────
    //   uBlurTex is a Gaussian blur of the RAW source (texture_), so
    //   subtracting it directly leaks the exposure / WB / tone-regions
    //   offset into the local-contrast `detail`. Run the same Light-tab
    //   ops on the blur sample so it lives in the SAME tonal space as
    //   `lightTab`. Now `detail = lightTab - tonalBlur` is just the
    //   high-frequency content; Ambiance / Clarity / CenterPop can boost
    //   or attenuate it without dragging exposure with them. Computed
    //   once and shared across the three passes below.
    bool needTonalBlur = (effAmb != 0.0) || (uClarityAmount != 0.0) || (uCenterPop != 0.0);
    vec3 tonalBlur = vec3(0.0);
    if (needTonalBlur) {
        tonalBlur = texture(uBlurTex, vTexCoord).rgb;
        tonalBlur = applyExposureContrastP(tonalBlur, uExposure, 0.0);
        if (uTonemapExposure != 0.0) {
            tonalBlur = applyExposureContrastP(tonalBlur, uTonemapExposure, 0.0);
        }
        tonalBlur = applyWbTintP(tonalBlur, uWhiteBalance, uTint);
        tonalBlur = applyToneRegionsP(tonalBlur, uHighlights, uShadows, uWhites, uBlacks);
    }
    if (effAmb != 0.0) {
        vec3 detail = lightTab - tonalBlur;
        lightTab = tonalBlur + detail * clamp(1.0 + 0.6 * effAmb, 0.0, 3.0);
        // Snapseed-style adaptive shadow fill — the "atmosphere" that evens the
        // lighting. Lift where the LOCAL neighbourhood (tonalBlur) is dark, and
        // gently pull where it's bright, so ambiance balances the tonal range
        // rather than only crisping local contrast. Highlight-safe: the lift is
        // scaled by (1 - lightTab) so whites never clip, and the local-luma gate
        // keeps it out of already-bright regions. This is the piece our ambiance
        // lacked vs Snapseed's Ambiance. Mirrored in apply_macro.cpp.
        float bLuma = dot(tonalBlur, vec3(0.2627, 0.6780, 0.0593));
        float shadowMask = 1.0 - smoothstep(0.0, 0.5, bLuma);   // 1 in deep local shadow → 0 by midtone
        float highMask   = smoothstep(0.55, 1.0, bLuma);        // 0 until midtone → 1 in local highlight
        // Saturation-preserving shadow fill via per-channel gamma (anchors 0
        // and 1, so blacks stay black and whites stay white; per-channel power
        // on dark colours lifts brightness AND keeps/boosts chroma — the
        // additive-toward-white form washed colour out). g>0 lifts shadows.
        float g = clamp(effAmb * 1.0 * shadowMask, -0.8, 3.0);
        lightTab = pow(clamp(lightTab, 0.0, 1.0), vec3(1.0 / (1.0 + g)));
        // Gentle highlight compression (multiplicative → chroma-preserving).
        lightTab *= (1.0 - effAmb * 0.12 * highMask);
        lightTab = clamp(lightTab, 0.0, 1.0);
        float aLuma = dot(lightTab, vec3(0.2627, 0.6780, 0.0593));
        // Midtone weight: triangle peaking at luma 0.5.
        float midW = 1.0 - abs(aLuma - 0.5) * 2.0;
        midW = clamp(midW, 0.0, 1.0);
        float sScale = 1.0 + 0.4 * effAmb * midW;
        lightTab = mix(vec3(aLuma), lightTab, clamp(sScale, 0.0, 2.0));
    }
    // Contrast applies last so it acts on the post-tone-region result
    // (matches Lightroom's behaviour — contrast pinches around midtone
    // *after* highlight/shadow rolloff).
    lightTab = applyExposureContrastP(lightTab, 0.0, uContrast);
    // Dehaze — atmospheric scattering model (RapidRAW-derived but with a
    // NEUTRAL atmospheric light A=(1,1,1) instead of RapidRAW's cool-biased
    // (0.95,0.97,1.0). The cool A inverts to a warm/magenta push at bright
    // pixels because `(c - A)/t` skews positive on R first when A leans blue
    // — that produced visible pink highlights with the default Rec.709
    // workspace. Neutral A preserves R=G=B for white inputs.
    //   J(x) = (I(x) - A) / t(x) + A
    // Dark-channel proxy: min(R,G,B) approximates how much haze the pixel
    // sees (haze lifts the darkest channel). t clamps to 0.15 to prevent
    // divide-by-noise.
    // Skip the pass entirely on bright pixels where the haze model isn't
    // physically meaningful (the scene already has no haze to remove).
    // Negative `uDehaze` re-introduces haze (interpolate toward A).
    if (uDehaze != 0.0) {
        vec3 A = vec3(1.0, 1.0, 1.0);
        if (uDehaze > 0.0) {
            float darkCh = min(min(lightTab.r, lightTab.g), lightTab.b);
            float luma   = dot(lightTab, vec3(0.2627, 0.6780, 0.0593));
            // Noise-robust dark-channel proxy. A raw min(R,G,B) latches onto
            // the per-channel NOISE TROUGH in shadows, so the transmission map
            // `t` becomes speckled pixel-to-pixel and `(c-A)/t` stamps that
            // speckle straight into the output — the "white noise in black".
            // Averaging the min with luma cancels most of the per-channel noise
            // in `t` while still tracking the dark-channel prior for real haze.
            float dark = mix(darkCh, luma, 0.5);
            // Highlight-gate: fade dehaze out as the proxy approaches 1
            // (highlights have no haze to recover, just clip-headroom).
            float hiGate = 1.0 - smoothstep(0.75, 0.98, dark);
            float t = max(1.0 - uDehaze * (dark / (dark + 0.2)) * 0.85 * hiGate, 0.15);
            vec3 dehazed = (lightTab - A) / t + A;
            // Shadow-protect. The (·)/t contrast stretch amplifies shadow noise
            // by 1/t, so smooth near-black regions turn grainy. Fade the dehaze
            // back toward the original as luma approaches black — haze physically
            // lifts blacks into the midtones anyway, so genuinely hazy areas sit
            // above this gate and still get dehazed; clean/noisy blacks stay put.
            float shadowGate = smoothstep(0.02, 0.22, luma);
            lightTab = mix(lightTab, dehazed, shadowGate);
        } else {
            lightTab = mix(lightTab, A, clamp(-uDehaze, 0.0, 1.0) * 0.5);
        }
    }
    // Clarity — luma-only, midtone-gated local contrast. Switched (2026-08-29)
    // from the old log-space applyLocalContrast() to the SAME method the Mask
    // tab and the CPU export use (raw_v3_detail.cpp lines ~332-342): compute a
    // luma delta against the tonal-matched blur, gate to midtones (4·lum·(1-lum)
    // so shadows/highlights stay put), soft-clip to ±0.20 to avoid posterising
    // flat skies, and broadcast the scalar to R/G/B so chromatic edges stay
    // clean. This closes a real preview≠export gap: the export bake already
    // used this form while the GL preview used log-space local contrast.
    // Skipped at amt=0.
    if (uClarityAmount != 0.0) {
        float lum     = clamp(dot(lightTab,  vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
        float blurLum = dot(tonalBlur, vec3(0.299, 0.587, 0.114));
        float midZone = 4.0 * lum * (1.0 - lum);
        float wgt     = uClarityAmount * midZone * 1.2;
        float dRaw    = wgt * (lum - blurLum);
        const float L = 0.20;
        float dL      = dRaw / (1.0 + abs(dRaw) / L);
        lightTab = clamp(lightTab + vec3(dL), 0.0, 1.0);
        // img.ly-style midtone "pop": exposure lift gated by the same midtone
        // mask as clarity (untouched shadows/highlights). Mirrors the CPU
        // export path in raw_v3_detail.cpp for preview=export parity. The
        // Clarity-Pop UI slider was removed (2026-08-29) so uClarityLift is
        // normally 0; kept here as a no-op for back-compat with saved sidecars.
        if (uClarityLift > 0.0) {
            lightTab *= pow(2.0, uClarityAmount * 0.27 * midZone * uClarityLift);
        }
    }
    // Center-Pop — radial-masked clarity. Boosts center sharpness/contrast
    // with a single slider, no manual mask. Signed: positive pops center,
    // negative pushes attention to edges by softening the center.
    if (uCenterPop != 0.0) {
        float aspect = uImageSize.x / max(uImageSize.y, 1.0);
        vec2 d2 = (vTexCoord - vec2(0.5)) * 2.0 * vec2(1.0, aspect);
        float d = length(d2) * 0.5;
        float mask = 1.0 - smoothstep(0.025, 0.775, d);
        float strength = uCenterPop * (2.0 * mask - 1.0) * 0.9;
        lightTab = applyLocalContrast(lightTab, tonalBlur, strength);
    }
    img = mix(img, lightTab, clamp(uLightTabOpacity, 0.0, 1.0));

    // ── Color tab — stacked saturation / vibrance / HSL shifts ──────────
    vec3 colorTab = img;
    colorTab = applySaturationVibranceP(colorTab, uSaturation, uVibrance);
    colorTab = applyHslShiftsP(colorTab, uHslRed, uHslOrange, uHslYellow,
                               uHslGreen, uHslAqua, uHslBlue,
                               uHslYellowGreen, uHslSpringGreen, uHslSkyBlue,
                               uHslPurple, uHslMagenta, uHslPink);
    // Color Grading wheels — last in the Color tab so wheel tints layer on
    // top of HSL hue/sat targeting. Modulated by uColorTabOpacity below.
    colorTab = applyColorGrading(colorTab);
    // PREQ-Port: HSL Full (8-anchor) and Color Density / Skintone
    colorTab = applyHslFull(colorTab);
    colorTab = applyColorDensity(colorTab, uColorDensity);
    colorTab = applySkintone(colorTab, uSkintone);
    img = mix(img, colorTab, clamp(uColorTabOpacity, 0.0, 1.0));
    // ── XMP overlay tab (Adobe CRS round-trip) ──────────────────────────
    vec3 xmpTab = img;
    if (uXmpEnabled) {
        xmpTab = applyExposureContrastP(xmpTab, uXmpExposure, uXmpContrast);
        xmpTab = applyToneRegionsP(xmpTab, uXmpHighlights, uXmpShadows, uXmpWhites, uXmpBlacks);
        xmpTab = applyHslShiftsP(xmpTab, uXmpHslRed, uXmpHslOrange, uXmpHslYellow,
                                 uXmpHslGreen, uXmpHslAqua, uXmpHslBlue,
                                 vec3(0.0), vec3(0.0), vec3(0.0),
                                 vec3(0.0), vec3(0.0), vec3(0.0));
    }
    if (uXmpEnabled) img = mix(img, xmpTab, clamp(uXmpTabOpacity, 0.0, 1.0));

    // PREQ-Port: RGB Curves (post-grading, pre-LUT-extras)
    img = applyCurves(img);
    // PREQ-Port: PushPull EV shift
    img = applyPushPull(img, uPushPull);
    // PREQ-Port: DetailGrain roughness
    img = applyDetailGrain(img, vTexCoord, uDetailGrainRoughness);
    // PREQ-Port: Midtone Details (LPF FBO stubbed — no-op until FBO wired)
    img = applyMidtoneDetails(img, vTexCoord);

// ── M12.2b — Vignette tab (radial darken/lighten) ───────────────────
    //   Builds a smoothstep radial mask centred on uVigCenter, then
    //   blends a darkened/lightened copy of baseRGB into the fan-out.
    //   Aspect-correct: we treat the canvas as a unit square so the
    //   mask is a circle, not an ellipse, regardless of image aspect.
    //   Effect=1 (SubjectOnly) and =2 (BackgroundOnly) are documented
    //   gates for M12.2d when the mask sampler lands; until then they
    //   behave the same as Effect=0 (All).
    vec3 vigTab = img;
    // Block vignette when targeting Subject/Background but mask not yet loaded —
    // prevents it from wrongly affecting the entire frame until the mask arrives.
    bool vigPendingMask = (uVigEffect % 10 != 0) && (uSubjectMaskEnabled == 0);
    float vigGate = vigPendingMask ? 0.0 : subjectGate(uVigEffect % 10);
    if (uVigAmount != 0.0 && uVigIntensity > 0.0 && vigGate > 0.0) {
        float r = distance(vTexCoord, uVigCenter);
        float softness = 1.0 - clamp(uVigFeather, 0.0, 1.0);
        float innerR = mix(0.7, 0.0, softness);
        float outerR = 0.7071068;
        float edge = smoothstep(innerR, outerR, r);
        float mask = (uVigEffect >= 10 ? (1.0 - edge) : edge) * clamp(uVigIntensity, 0.0, 1.0);
        // Negative amount = darken (multiply <1); positive = lighten.
        // Per-channel multiplier keeps colour balance.
        // Gate the mask itself so Subject/Background fade smoothly.
        mask *= vigGate;
        float mul = 1.0 + uVigAmount * mask;
        vigTab = img * mul;
    }
    img = mix(img, vigTab, clamp(uVigTabOpacity, 0.0, 1.0));

    // ── M12.2b.2 — Gradient tab (4-sided edge gradients) ────────────────
    //   Mirror of MacroProcessor.applyEdgeGradients: rotate UV by uGradAngle,
    //   compute per-side falloffs (2 layers each), accumulate darkness as
    //   exp2(-darkness*1.4) multiply, then blend per-side tint colours.
    vec3 gradTab = img;
    bool gradAny =
        uGradTop[0]    != 0.0 || uGradBottom[0] != 0.0 ||
        uGradLeft[0]   != 0.0 || uGradRight[0]  != 0.0 ||
        uGradTop[6]    > 0.0  || uGradBottom[6] > 0.0  ||
        uGradLeft[6]   > 0.0  || uGradRight[6]  > 0.0  ||
        (uGradTop[7]    > 0.5 && (uGradTop[8]    != 0.0 || uGradTop[14]    > 0.0)) ||
        (uGradBottom[7] > 0.5 && (uGradBottom[8] != 0.0 || uGradBottom[14] > 0.0)) ||
        (uGradLeft[7]   > 0.5 && (uGradLeft[8]   != 0.0 || uGradLeft[14]   > 0.0)) ||
        (uGradRight[7]  > 0.5 && (uGradRight[8]  != 0.0 || uGradRight[14]  > 0.0));
    if (gradAny) {
        float rad = uGradAngle * 3.14159265 / 180.0;
        float cosA = cos(rad), sinA = sin(rad);
        float dx = vTexCoord.x - 0.5;
        float dy = vTexCoord.y - 0.5;
        float rx = clamp(dx * cosA - dy * sinA + 0.5, 0.0, 1.0);
        float ry = clamp(dx * sinA + dy * cosA + 0.5, 0.0, 1.0);
        float tF1, tF2, bF1, bF2, lF1, lF2, rF1, rF2;
        sideFalloffs(uGradTop,    ry,        tF1, tF2);
        sideFalloffs(uGradBottom, 1.0 - ry,  bF1, bF2);
        sideFalloffs(uGradLeft,   rx,        lF1, lF2);
        sideFalloffs(uGradRight,  1.0 - rx,  rF1, rF2);
        // Per-side semantic gating — fold the gate into the falloff so the
        // tint blend and darkness scale together, preserving feathering.
        // Block Subject/Background targets when mask not yet loaded so the
        // gradient doesn't incorrectly apply to the full frame.
        float gT  = (uGradTopApplyTo    != 0 && uSubjectMaskEnabled == 0) ? 0.0 : subjectGate(uGradTopApplyTo);
        float gB  = (uGradBottomApplyTo != 0 && uSubjectMaskEnabled == 0) ? 0.0 : subjectGate(uGradBottomApplyTo);
        float gL  = (uGradLeftApplyTo   != 0 && uSubjectMaskEnabled == 0) ? 0.0 : subjectGate(uGradLeftApplyTo);
        float gR_ = (uGradRightApplyTo  != 0 && uSubjectMaskEnabled == 0) ? 0.0 : subjectGate(uGradRightApplyTo);
        tF1 *= gT; tF2 *= gT;
        bF1 *= gB; bF2 *= gB;
        lF1 *= gL; lF2 *= gL;
        rF1 *= gR_; rF2 *= gR_;
        float darkness =
            uGradTop[0]    * tF1 + uGradBottom[0] * bF1 +
            uGradLeft[0]   * lF1 + uGradRight[0]  * rF1 +
            uGradTop[8]    * tF2 + uGradBottom[8] * bF2 +
            uGradLeft[8]   * lF2 + uGradRight[8]  * rF2;
        if (darkness != 0.0) {
            gradTab *= exp2(-darkness * 1.4);
        }
        // Layer-1 tints.
        gradTab = blendGradTint(gradTab,
                    vec3(uGradTop[3],    uGradTop[4],    uGradTop[5]),    uGradTop[6],    tF1, uGradTopBlendMode);
        gradTab = blendGradTint(gradTab,
                    vec3(uGradBottom[3], uGradBottom[4], uGradBottom[5]), uGradBottom[6], bF1, uGradBottomBlendMode);
        gradTab = blendGradTint(gradTab,
                    vec3(uGradLeft[3],   uGradLeft[4],   uGradLeft[5]),   uGradLeft[6],   lF1, uGradLeftBlendMode);
        gradTab = blendGradTint(gradTab,
                    vec3(uGradRight[3],  uGradRight[4],  uGradRight[5]),  uGradRight[6],  rF1, uGradRightBlendMode);
        // Layer-2 tints (gated by enable2).
        if (uGradTop[7]    > 0.5) gradTab = blendGradTint(gradTab,
                    vec3(uGradTop[11],    uGradTop[12],    uGradTop[13]),    uGradTop[14],    tF2, uGradTopBlendMode);
        if (uGradBottom[7] > 0.5) gradTab = blendGradTint(gradTab,
                    vec3(uGradBottom[11], uGradBottom[12], uGradBottom[13]), uGradBottom[14], bF2, uGradBottomBlendMode);
        if (uGradLeft[7]   > 0.5) gradTab = blendGradTint(gradTab,
                    vec3(uGradLeft[11],   uGradLeft[12],   uGradLeft[13]),   uGradLeft[14],   lF2, uGradLeftBlendMode);
        if (uGradRight[7]  > 0.5) gradTab = blendGradTint(gradTab,
                    vec3(uGradRight[11],  uGradRight[12],  uGradRight[13]),  uGradRight[14],  rF2, uGradRightBlendMode);
        gradTab = max(gradTab, vec3(0.0));
    }
    img = mix(img, gradTab, clamp(uGradTabOpacity, 0.0, 1.0));

    // ── M12.2c.2 — Mask tab (paintable brush + 6 local adjustments) ─────
    //   Adjustments apply only where the painted brush mask is non-zero.
    //   The mask itself is sampled at vTexCoord (same domain as the AHB
    //   source, so the brush canvas pixel positions line up). Tab math
    //   is stacked internally (exposure→contrast→tone regions→satvib),
    //   then mixed with baseRGB by the mask alpha before fan-out.
    // Mask layers stack ON TOP of the running pipeline image. Each layer:
    //   • reads `img` (post all prior tabs and prior mask layers),
    //   • computes its locally-adjusted result `m = effect(img)`,
    //   • composites back with `img = mix(img, m, alpha × layerOpacity)`.
    // Luminance masks key off the CURRENT graded pixel so the crosshair
    // sees the same tone the user does.
    for (int li = 0; li < 4; ++li) {
        float a = maskLayerAlpha(li, img);
        if (a <= 0.0) continue;
        float ev   = uMaskBrightness[li]  / 100.0;
        float cont = uMaskContrast[li]    / 100.0;
        float wb   = clamp(uMaskTemperature[li] / 2500.0, -1.0, 1.0);
        float tint = clamp(uMaskTint[li]  / 200.0, -1.0, 1.0);
        float sat  = uMaskSaturation[li]  / 100.0;
        float clar = uMaskClarity[li]     / 100.0;
        float shrp = uMaskSharpness[li]   / 100.0;
        // Tone regions (mirror the global Tone tab): normalise -100..100 → ~-1..1
        // so applyToneRegionsP behaves identically to the global controls.
        float hi   = uMaskHighlights[li]  / 100.0;
        float shd  = uMaskShadows[li]     / 100.0;
        float wht  = uMaskWhites[li]      / 100.0;
        float blk  = uMaskBlacks[li]      / 100.0;
        if (ev == 0.0 && cont == 0.0 && wb == 0.0 && tint == 0.0 && sat == 0.0 &&
            clar == 0.0 && shrp == 0.0 &&
            hi == 0.0 && shd == 0.0 && wht == 0.0 && blk == 0.0) continue;
        vec3 m = img;
        m = applyExposureContrastP(m, ev, cont);
        // Tone regions before WB/sat so highlight/shadow masks key off the
        // exposure-adjusted tone (matches the global pipeline ordering).
        if (hi != 0.0 || shd != 0.0 || wht != 0.0 || blk != 0.0) {
            m = applyToneRegionsP(m, hi, shd, wht, blk);
        }
        m = applyWbTintP(m, wb, tint);
        m = applySaturationVibranceP(m, sat, 0.0);
        if (clar != 0.0) {
            // Luma-only clarity (matches the smooth Details-tab clarity
            // path in raw_v3_detail.cpp). The previous per-channel form
            // (`m + (m - lo) * clar * 2.0`) amplified R/G/B noise and
            // produced visible posterising bands on smooth skies / walls
            // when output was rounded to 8-bit. Three safety nets:
            //   1. Compute delta in luma space, broadcast scalar to R/G/B
            //      → R/G/B stay locked, no chromatic fringes.
            //   2. Soft-clip via x / (1 + |x|/L) at L=0.20 → no hard cliff
            //      that crushes flat gradients into posterised chunks.
            //   3. Mid-zone gate (4·L·(1-L)) so shadows/highlights aren't
            //      crushed.
            //   4. Strength softened ×2.0 → ×1.2 (matches Details path).
            vec2 px = 2.0 / vec2(textureSize(uTex, 0));
            vec3 lo = texture(uTex, vTexCoord).rgb;
            lo += texture(uTex, vTexCoord + vec2( px.x,  px.y)).rgb;
            lo += texture(uTex, vTexCoord + vec2(-px.x,  px.y)).rgb;
            lo += texture(uTex, vTexCoord + vec2( px.x, -px.y)).rgb;
            lo += texture(uTex, vTexCoord + vec2(-px.x, -px.y)).rgb;
            lo *= 0.2;
            float lum     = clamp(dot(m,  vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
            float blurLum = dot(lo, vec3(0.299, 0.587, 0.114));
            float midZone = 4.0 * lum * (1.0 - lum);
            float wgt     = clar * midZone * 1.2;
            float dRaw    = wgt * (lum - blurLum);
            const float L = 0.20;
            float dL      = dRaw / (1.0 + abs(dRaw) / L);
            m = clamp(m + vec3(dL), 0.0, 1.0);
        }
        if (shrp != 0.0) {
            // Masked high-frequency sharpen (unsharp mask). Tight 1px 5-tap
            // low-pass of the source (uTex) as the blur reference; luma delta
            // broadcast to R/G/B so no chroma fringes. Mirrors the CPU export
            // pass applyMaskedSharpness() (stage_c_export.cpp) — keep in sync.
            vec2 px1 = 1.0 / vec2(textureSize(uTex, 0));
            vec3 blr = texture(uTex, vTexCoord).rgb;
            blr += texture(uTex, vTexCoord + vec2( px1.x,  px1.y)).rgb;
            blr += texture(uTex, vTexCoord + vec2(-px1.x,  px1.y)).rgb;
            blr += texture(uTex, vTexCoord + vec2( px1.x, -px1.y)).rgb;
            blr += texture(uTex, vTexCoord + vec2(-px1.x, -px1.y)).rgb;
            blr *= 0.2;
            float sLum = clamp(dot(m,   vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
            float bLum = dot(blr, vec3(0.299, 0.587, 0.114));
            m = clamp(m + vec3(shrp * (sLum - bLum)), 0.0, 1.0);
        }
        float w = a * clamp(uMaskTabOpacity[li], 0.0, 1.0);
        img = mix(img, m, w);
    }

    // ── Mask overlay preview (Mask tab "Show") ──────────────────────────
    //   Tint the masked region blue so the user can see the painted /
    //   selected area before any adjustment is applied. Drawn here (not as a
    //   Compose overlay) because the GL surface is ZOrderOnTop and would hide
    //   any Compose layer behind it.
    //   Tints ONLY the layer currently being edited (uMaskOverlayLayer), NOT
    //   every active layer: committed mask layers stay uploaded so their
    //   adjustments keep applying, but their blue selection tint must not leak
    //   into a fresh layer's edit session. uMaskOverlayLayer < 0 → tint nothing
    //   (no layer is being edited — e.g. a new layer with nothing selected yet).
    // NOTE: the blue mask-selection overlay is applied at the very END of the
    // shader (just before fragColor) — NOT here. Tinting at this stage let
    // every downstream colour op process the tint: a black-and-white LUT or
    // film sim desaturated the blue to invisible grey. The selection alpha is
    // still keyed off the graded `img` here (matching the adjustment loop).

    c = clamp(img, 0.0, 1.0);

    // ── Bokeh (background OOF blur + highlight bloom) ────────────────────
    //   Composited on the graded pixel `c`. The blur source (uBlurTex) is a
    //   downscaled separable-Gaussian of the *ungraded* base, which is fine:
    //   the background is being thrown out of focus anyway, so its exact
    //   grade is visually irrelevant. Gated to the BACKGROUND via the U2Net
    //   subject mask (subjectGate(2) → 1 on bg, 0 on subject) so the subject
    //   stays tack-sharp. When no mask is present, subjectGate returns 1
    //   (blur the whole frame — graceful degrade to a full-frame soft blur).
    // Skip bokeh entirely when no subject mask is present — never blur the
    // whole frame with no subject to protect.
    if ((uBokehBlur > 0.0 || uBokehBalls > 0.0) && uSubjectMaskEnabled == 1) {
        // Phase 4: hard subject lock — blur must NOT spread onto the subject.
        // subjectGate(2) alone uses (1-p^2) and still blurs mid-mask pixels.
        // Feather the *mask protect zone* outward; subject core stays CoC=0.
        vec2 maskUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord);
        float pSub = clamp(texture(uSubjectMask, maskUV).r, 0.0, 1.0);
        if (uEdgeSnapStrength > 0.0) {
            float edge = texture(uSobelEdgeMask, maskUV).r;
            if (edge > uEdgeSnapThreshold) {
                float push = edge * uEdgeSnapStrength;
                pSub = (pSub > 0.5) ? min(1.0, pSub + push) : max(0.0, pSub - push);
            }
        }
        // Dilate protect ~0.6% of frame so large discs don't crawl onto silhouette.
        float pDil = pSub;
        {
            float d = 0.006;
            pDil = max(pDil, texture(uSubjectMask, clamp(maskUV + vec2( d, 0.0), 0.0, 1.0)).r);
            pDil = max(pDil, texture(uSubjectMask, clamp(maskUV + vec2(-d, 0.0), 0.0, 1.0)).r);
            pDil = max(pDil, texture(uSubjectMask, clamp(maskUV + vec2(0.0,  d), 0.0, 1.0)).r);
            pDil = max(pDil, texture(uSubjectMask, clamp(maskUV + vec2(0.0, -d), 0.0, 1.0)).r);
        }
        // Hard zero on subject (dilated). Soft gate only on clear background.
        float bgGate = 0.0;
        if (pDil <= 0.42) {
            float bg = 1.0 - pSub;
            bgGate = bg * bg; // fringe only where mask is already bg-ish
        }
        // Sky / Terrain attenuation (bg only).
        if (uBokehAttenuationEnabled == 1 && bgGate > 0.0) {
            vec2 attUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord);
            float att = clamp(texture(uBokehAttenuation, attUV).r, 0.0, 1.0);
            bgGate *= (1.0 - 0.75 * att);
        }
        // Depth → CoC. LOCKED abs gate for golden 8/8:
        //   cocAbs = abs(depth - focus); bgGate *= smoothstep(0.02, 0.55, cocAbs)
        // Phase 3: signed CoC near/far paths
        float nearGate = 0.0;
        float farGate  = 0.0;
        if (uDepthMapEnabled == 1 && bgGate > 0.0) {
            vec2 dUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord);
            float depth = clamp(texture(uBokehAttenuation, dUV).g, 0.0, 1.0);
            float cocSigned = depth - uBokehFocusDepth;
            float coc = clamp(abs(cocSigned), 0.0, 1.0); // LOCKED abs
            bgGate *= smoothstep(0.02, 0.55, coc);
            if (cocSigned >= 0.0) farGate = bgGate;
            else                  nearGate = bgGate;
        } else {
            farGate = bgGate;
        }
        if (bgGate > 0.0) {
            // Grade transfer onto ungraded samples (same as before): keep
            // background colour while only focus changes.
            vec3 gradeRatio = clamp(c / max(baseRGB, vec3(1.0 / 255.0)),
                                    vec3(0.25), vec3(4.0));
            vec3 blurC;
            // Phase 2+3: CoC Vogel disc (near/far). Quiet kernel — no whole-frame lift.
            if (uDepthMapEnabled == 1) {
                float blurAmt = clamp(uBokehBlur, 0.0, 1.0);
                float spread  = clamp(uBokehSpread, 0.0, 1.0);
                float rFar  = (0.022 + 0.100 * spread) * farGate  * blurAmt;
                float rNear = (0.018 + 0.080 * spread) * nearGate * blurAmt;
                float rUV = max(rFar, rNear);
                vec3 acc = texture(uTex, vTexCoord).rgb;
                vec3 soft = texture(uBlurTex, vTexCoord).rgb;
                acc = mix(acc, soft, 0.50 * smoothstep(0.0, 0.012, rUV));
                if (rUV > 1e-5) {
                    float radiusPx = rUV * float(max(textureSize(uTex, 0).x, textureSize(uTex, 0).y));
                    int N = (radiusPx > 10.0) ? 48 : 24;
                    const float GOLDEN = 2.399963229728653;
                    vec2 pixel = floor(vTexCoord * vec2(textureSize(uTex, 0)));
                    float seed = fract(sin(dot(pixel, vec2(12.9898, 78.233))) * 43758.5453);
                    float rotation = seed * 6.28318530718;
                    float wSum = 1.0; // center already in acc (subject-free: we're on bg)
                    for (int i = 1; i < N; ++i) {
                        float fi = float(i);
                        float rr = rUV * sqrt(fi / float(N - 1));
                        float ang = fi * GOLDEN + rotation;
                        vec2 off = vec2(cos(ang), sin(ang)) * rr;
                        vec2 uv = clamp(vTexCoord + off, 0.0, 1.0);
                        vec2 sUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, uv);
                        float sp = texture(uSubjectMask, sUV).r;
                        float sw = 1.0 - smoothstep(0.20, 0.42, sp);
                        if (sw > 1e-4) {
                            acc += mix(texture(uTex, uv).rgb, texture(uBlurTex, uv).rgb, 0.45) * sw;
                            wSum += sw;
                        }
                    }
                    acc /= max(wSum, 1e-4);
                }
                blurC = clamp(acc * gradeRatio, 0.0, 1.0);
                float mixW = smoothstep(0.0, 0.008, rUV);
                c = mix(c, blurC, mixW);
            } else {
                blurC = clamp(texture(uBlurTex, vTexCoord).rgb * gradeRatio,
                              0.0, 1.0);
                c = mix(c, blurC, clamp(uBokehBlur, 0.0, 1.0) * bgGate);
            }
            // Shaped highlight discs. A filled regular hexagon (the same
            // outline-then-fill aperture kernel ImageToolbox uses for bokeh)
            // is stamped on highlights brighter than the threshold. The stamp
            // is allowed only where bgGate is already background AND depth CoC
            // has opened that gate (uDepthMapEnabled).
            float balls = uBokehBalls;
            if (balls > 0.0 && uDepthMapEnabled == 1) {
                float thr = mix(0.72, 0.50, clamp(uBokehSpread, 0.0, 1.0));
                float rad = (0.006 + 0.018 * clamp(uBokehSpread, 0.0, 1.0));
                const float SECTOR = 1.04719755; // 2π / 6
                const float COS_HALF = 0.8660254; // cos(sector/2), blades = 6
                vec3 shaped = vec3(0.0);
                float wsum = 0.0;
                for (int y = -3; y <= 3; ++y) {
                    for (int x = -3; x <= 3; ++x) {
                        vec2 off = vec2(float(x), float(y)) * (rad / 3.0);
                        vec3 s = texture(uTex, clamp(vTexCoord + off, 0.0, 1.0)).rgb;
                        float l = dot(s, vec3(0.2627, 0.6780, 0.0593));
                        float h = smoothstep(thr, 1.0, l);
                        if (h <= 0.0) continue;
                        vec2 d = -off;
                        float a = mod(atan(d.y, d.x), SECTOR) - SECTOR * 0.5;
                        float limit = rad * COS_HALF / max(cos(a), 0.001);
                        if (dot(d, d) <= limit * limit) {
                            shaped += s * h;
                            wsum += h;
                        }
                    }
                }
                if (wsum > 0.0) {
                    vec3 lift = (shaped / wsum) * balls * bgGate;
                    c = 1.0 - (1.0 - c) * (1.0 - lift);
                }
            }
        }
    }
    // Mask-tab banding: painted pixels only, and only where the neighborhood is flat.
    if (uMaskBanding > 0.001 && (uBrushMaskEnabled & 1) != 0) {
        float aBand = texture(uBrushMask, vTexCoord).r * uMaskBanding;
        if (aBand > 0.001) {
            vec2 texel = 1.0 / vec2(textureSize(uTex, 0));
            vec3 s1 = texture(uTex, clamp(vTexCoord + vec2(texel.x, 0.0), 0.0, 1.0)).rgb;
            vec3 s2 = texture(uTex, clamp(vTexCoord - vec2(texel.x, 0.0), 0.0, 1.0)).rgb;
            vec3 s3 = texture(uTex, clamp(vTexCoord + vec2(0.0, texel.y), 0.0, 1.0)).rgb;
            vec3 s4 = texture(uTex, clamp(vTexCoord - vec2(0.0, texel.y), 0.0, 1.0)).rgb;
            vec3 hi = max(max(max(s1, s2), max(s3, s4)), c);
            vec3 lo = min(min(min(s1, s2), min(s3, s4)), c);
            float range = max(max(hi.r - lo.r, hi.g - lo.g), hi.b - lo.b);
            float cutoff = mix(0.02, 0.08, uMaskBanding);
            if (range < cutoff) {
                vec3 mean = (s1 + s2 + s3 + s4 + c) / 5.0;
                c = mix(c, mean, aBand);
            }
        }
    }
    // ── Orton-Bokeh bloom (Karis 6-mip pyramid composite) ───────────────
    //   Replaces the prior 32-tap inline Vogel-disc convolution with the
    //   SIGGRAPH 2014 Karis "Next Gen Post Processing in CoD: Advanced
    //   Warfare" 6-mip pyramid. runKarisBloomPass() ran before this draw
    //   and left the final composited bloom in bloomTex_[0] (bound to
    //   unit 11 as uBloomTex). Here we just sample once + composite.
    //
    //   Why mip pyramid over inline Vogel?
    //     - No banding at large radii (Vogel's 32 taps under-sample when
    //       uBloomRadius > 12)
    //     - Smooth spread to arbitrary radius (controlled by tent
    //       upsample radius in the pre-pass, not a shader loop count)
    //     - Cheaper per-pixel cost in the main shader (1 tap vs 32)
    //
    //   uBloomRadius is mapped to the upsample tent radius at the CPU
    //   side (renderFrame), so this shader stays simple. uBloomShape
    //   (anamorphic ratio) is also applied CPU-side via an asymmetric
    //   upsample radius.
    //
    //   Compositing matches the prior Vogel implementation so the look
    //   feels familiar:
    //     dynamic = c·bloom·1.8   (multiplicative — boosts contrast)
    //     glow    = bloom·gate    (additive — ethereal highlight lift)
    //   then linearly mixes against the original by uOrtonStrength.
    // Subject-exclusion-pending gate: when the user has Separate Subject
    // Bloom enabled but U2Net inference hasn't completed yet (mask not
    // uploaded → uSubjectMaskEnabled == 0), DON'T render bloom this frame.
    // Otherwise the bloom briefly applies to the whole frame (subject
    // included) until U2Net finishes, then snaps to "subject excluded" —
    // the visible flap the user reported. Holding bloom off entirely until
    // the mask is ready trades ~150-200ms of bloom-delay for a stable,
    // correct render. Bloom resumes the moment subjectMaskReady_ flips
    // to true on the GL thread.
    bool bloomBlockedPendingMask =
        uBloomExcludeSubject > 0.5 && uSubjectMaskEnabled == 0;
    if (uOrtonStrength > 0.0 && uBloomRadius > 0.0 && !bloomBlockedPendingMask) {
        // Filmic / Black Pro-Mist composite (2026-09-09).
        // Goals vs the old Glamour-Glow screen path:
        //   1. Optical scatter: R samples slightly offset from B (halation).
        //   2. Shadows stay rich — multiply glow by smoothstep(0.05,0.35,luma).
        //   3. Soft screen, capped so highlights keep core detail.
        // Pyramid itself is already tight-mip biased (see runKarisBloomPass).
        // MUST mirror apply_macro.cpp applyOrtonP (+ halation sample).
        // Optical scatter — coeff 0.005 (the prior 0.015 / 3× made 1/8 density
        // look like ~1/3 of frame). MUST mirror apply_macro.cpp.
        // Protect subject: kill / soft-ramp halation on subject + ~10% long-side
        // near band (halationProtectScale); Orton strength still uses inward feather.
        float hOff = uMistHalation * 0.02 * halationProtectScale();
        vec2 texelH = vec2(hOff, 0.0);
        float bR = mix(texture(uBloomTex, vTexCoord).r, texture(uBloomTex, vTexCoord + texelH).r, 0.55);
        float bG = texture(uBloomTex, vTexCoord).g;
        float bB = mix(texture(uBloomTex, vTexCoord).b, texture(uBloomTex, vTexCoord - texelH).b, 0.55);
        // Soft diffusion is pre-baked into uBloomTex (dedicated Gaussian plane
        // composited after Karis — see runSoftDiffBloomComposite). Sampling
        // uBlurTex here previously replaced chromatic/mist halation whenever
        // Bokeh owned the shared blur FBO.
        vec3 bloomSample = vec3(bR, bG, bB);

        float bloomLuma = dot(bloomSample, vec3(0.2126, 0.7152, 0.0722));
        bloomSample = mix(bloomSample, vec3(bloomLuma), 0.55);

        float warmMag = (bloomSample.r + bloomSample.g) * 0.5 - bloomSample.b;
        float warmGate = clamp(warmMag * 3.0, 0.0, 1.0);
        bloomSample.r *= 1.0 + 0.45 * warmGate;
        bloomSample.g *= 1.0 + 0.30 * warmGate;
        bloomSample.r *= 1.0 + uFxGlowWarmth;
        bloomSample.b *= 1.0 - uFxGlowWarmth;

        bloomSample = max(bloomSample, vec3(0.0));

        float strengthHere = clamp(uOrtonStrength, 0.0, 1.0);
        if (uBloomExcludeSubject > 0.5 && uSubjectMaskEnabled == 1) {
            // 2026-09-04 — feather INWARD (owner request: the old
            // smoothstep(0.20,0.50) on the raw sample gave a hard cut at the
            // silhouette). Box-average the mask over ±kBloomFeather of image
            // UV (5×5 taps at half-radius spacing), then ramp protection from
            // 0.55 → 0.98 of that average: at the silhouette the average is
            // ~0.5 (no protection yet, glow still bleeds a little into the
            // rim), rising to full protection ~one feather radius INSIDE the
            // subject. Outside the silhouette the average stays < 0.5, so
            // the background keeps full bloom — the roll-off lives on the
            // subject side only. MUST mirror apply_macro.cpp bloomSubjectGate().
            const float kBloomFeather = 0.02;   // image-UV radius
            vec2 maskUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord);
            vec2 dMask  = (uSubjectMaskRect.zw - uSubjectMaskRect.xy) * (kBloomFeather * 0.5);
            float acc = 0.0;
            for (int ty = -2; ty <= 2; ++ty) {
                for (int tx = -2; tx <= 2; ++tx) {
                    vec2 o = maskUV + vec2(float(tx), float(ty)) * dMask;
                    acc += clamp(texture(uSubjectMask, clamp(o, vec2(0.0), vec2(1.0))).r, 0.0, 1.0);
                }
            }
            float subjectFeathered = acc / 25.0;
            float subjectSharp = smoothstep(0.55, 0.98, subjectFeathered);
            float bg = clamp(uOrtonStrength, 0.0, 1.0);
            float sub = clamp(uSubjectBloom, 0.0, 1.0);
            strengthHere = mix(bg, sub, subjectSharp);
        }
        if (uBokehAttenuationEnabled == 1) {
            vec2 attUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord);
            float skyAmt = clamp(texture(uBokehAttenuation, attUV).r, 0.0, 1.0);
            float blueExcess = c.b - max(c.r, c.g);
            float colorSkyGate = clamp(blueExcess * 8.0, 0.0, 1.0);
            float skyGate = smoothstep(0.75, 0.95, skyAmt) * colorSkyGate;
            strengthHere *= (1.0 - skyGate);
        }
        vec3 glowSample = clamp(bloomSample, 0.0, 1.0);
        vec3 baseC = clamp(c, 0.0, 1.0);
        vec3 screenC = 1.0 - (1.0 - baseC) * (1.0 - glowSample);
        float origL = dot(baseC, vec3(0.2126, 0.7152, 0.0722));
        float shadowMask = smoothstep(0.05, 0.35, origL);
        // 2× prior Orton mix (was 0.50).
        float glowAmt = clamp(strengthHere, 0.0, 1.0) * 1.00 * shadowMask;
        glowAmt *= 1.0 - smoothstep(0.82, 1.0, origL) * 0.65;
        c = mix(c, screenC, glowAmt);
    }

    // ── Wider-gamut intermediate ────────────────────────────────────────
    //   Stash the pre-clamp HDR colour so the post-LUT pipeline can read
    //   extended-range info if it wants (we re-expand headroom inside the
    //   LUT block via the v>0 branch). Mix base is clamped to [0,1]; LUT
    //   *sample coords* use headroom-aware mapping (identity on [0,1],
    //   compress only channels > 1) — see mapLutSampleCoord / FEATURES.md.
    // Film Separation — after wheels / the rest of the grade, before the cube.
    c = applyFilmSeparation(c, uFilmSeparation);
    vec3 hdrC = c;
    c = clamp(c, 0.0, 1.0);

    // ── M5: 3D LUT lookup (tab #4) ──────────────────────────────────────
    //   Sample coords: headroom-aware map (always when LUT on).
    //   uLutHighlightVibrancy ∈ [-1..+1] is a creative overlay:
    //     v = 0  → headroom map only (default protect path).
    //     v > 0  → boost LUT-result saturation in highlight territory so
    //              cube LUTs don't desaturate bright tones at the (1,1,1)
    //              corner. v = +1.0 ≈ full strength; the "good but
    //              60 %" prior fix corresponds to ~v = 0.6.
    //     v < 0  → blend toward full Reinhard on HDR (duller creative knob).
    if (uLutEnabled) {
        float v = clamp(uLutHighlightVibrancy, -1.0, 1.0);
        // B&W LUT: sample along the neutral grey diagonal (Y,Y,Y) using
        // BT.2020 luma of the pre-clamp pixel. This collapses all shadow
        // pixels to the same axis regardless of their hue or how HSL shifted
        // them, eliminating the patchy luma artifacts that appear when
        // hue-selective HSL luminance shifts produce slightly different RGB
        // values in dark shadows that then land at different LUT grid cells.
        vec3 lutSrc = hdrC;
        if (uLutBwForce != 0) {
            float bwY = dot(clamp(hdrC, 0.0, 1.0), vec3(0.2627, 0.6780, 0.0593));
            lutSrc = vec3(bwY);
        }
        // Identity on [0,1]; soft-compress ONLY values >1 into (0,1].
        // Continuous at 1: f(1)=1. Must mirror apply_macro.cpp.
        vec3 lutIn = vec3(
            mapLutSampleCoord(lutSrc.r),
            mapLutSampleCoord(lutSrc.g),
            mapLutSampleCoord(lutSrc.b)
        );
        if (v < 0.0) {
            // Negative side blends between headroom map (v=0) and full
            // Reinhard compress (v=-1).
            float k = -v;
            vec3 reinhard = vec3(
                lutSrc.r / (1.0 + lutSrc.r),
                lutSrc.g / (1.0 + lutSrc.g),
                lutSrc.b / (1.0 + lutSrc.b)
            );
            lutIn = mix(lutIn, reinhard, k);
        }
        // Gamut transform around the LUT sample. The pipeline pixels in
        // `uTex` are encoded in the working space the user picked via
        // `WorkspaceConfig.libRawOutputColor` (sRGB / AdobeRGB / ProPhoto
        // / DCI-P3 / Rec.2020). Consumer LUTs from Film-Luts and most
        // online .cube packs were AUTHORED against sRGB-encoded pixels.
        // Feeding ProPhoto-encoded input through them produces washed-out
        // / wrong colours because the matrix transform was never applied.
        //
        // Fix: convert pixel from workspace → LUT's authored space before
        // sampling, then back to workspace after. When both spaces match
        // (most common: workspace=sRGB, LUT=sRGB) both transforms are
        // identity → zero overhead.
        //
        // We pivot through sRGB as a common intermediate so we don't need
        // an N×N matrix grid. Pivot fidelity is fine for 3×3 matrices in
        // FP16/FP32 arithmetic.
        // Canonicalise both encodings to LibRaw values (1=sRGB, 2=AdobeRGB,
        // 4=ProPhoto, 7=DCI-P3, 8=Rec.2020). uLutAuthoredSpace is an
        // ordinal (0=Rec.709, 1=ProPhoto, 2=ACES, 3=DCI-P3) — map it to
        // LibRaw values:
        //   0 (Rec.709)  → 1 (sRGB)  ← both share Rec.709 primaries
        //   1 (ProPhoto) → 4
        //   2 (ACES)     → 1 (no matrix available; treat as sRGB no-op)
        //   3 (DCI-P3)   → 7
        int lutSpaceLibraw =
            uLutAuthoredSpace == 1 ? 4 :
            uLutAuthoredSpace == 3 ? 7 : 1;
        vec3 lutIn2 = lutIn;
        bool needsTransform = uWorkspaceSpace != lutSpaceLibraw;
        if (needsTransform) {
            // workspace → sRGB → LUT-authored-space.
            lutIn2 = toSrgb(lutIn, uWorkspaceSpace);
            lutIn2 = fromSrgb(lutIn2, lutSpaceLibraw);
            lutIn2 = clamp(lutIn2, 0.0, 1.0);
        }
        // DOMAIN_MIN/MAX remapping — normalize input into LUT's native range.
        lutIn2 = clamp((lutIn2 - uLutDomainMin) / max(uLutDomainMax - uLutDomainMin, vec3(1e-6)), 0.0, 1.0);
        // Tetrahedral interpolation (replaces hardware trilinear). The
        // function does its own half-texel-offset and clamp internally so
        // shadows don't get crushed at the texel-0 boundary.
        vec3 lutColor = tetrahedralLut3D(uLutTex, lutIn2, uLutSize);
        if (needsTransform) {
            // LUT-authored-space → sRGB → workspace.
            lutColor = toSrgb(lutColor, lutSpaceLibraw);
            lutColor = fromSrgb(lutColor, uWorkspaceSpace);
        }
        if (v > 0.0) {
            float maxHdr = max(max(hdrC.r, hdrC.g), hdrC.b);
            float hiAmt = clamp(maxHdr - 1.0, 0.0, 1.0) * v;
            if (hiAmt > 0.0) {
                float lutLuma = dot(lutColor, vec3(0.2627, 0.6780, 0.0593));
                lutColor = mix(lutColor, lutLuma + (lutColor - vec3(lutLuma)) * 1.5, hiAmt);
            }
        }
        // B&W pack chroma lock (slot [450]): intensity blends from source
        // *luma* toward the LUT colour so original chroma never returns when
        // intensity < 1. Toned mono LUTs (sepia/cyanotype) keep their process
        // colour at the LUT end. Colour LUTs use the normal colour mix.
        float lutT = clamp(uLutIntensity, 0.0, 1.0);
        if (uLutBwForce != 0) {
            float srcY = dot(c, vec3(0.2627, 0.6780, 0.0593));
            c = mix(vec3(srcY), lutColor, lutT);
        } else {
            c = mix(c, lutColor, lutT);
        }
        if (v > 0.0) {
            // Re-expand clipped headroom modestly so the tone-mapper has
            // somewhere to roll off. Scales with v.
            vec3 excess = max(hdrC - vec3(1.0), vec3(0.0));
            c += excess * lutT * 0.3 * v;
        }
    }

    // Film response — after the LUT so it reshapes the LUT's own output,
    // before the WB trims so those still act as the last finishing touch.
    // Mirrored at the same point in apply_macro.cpp.
    c = applyFilmResponse(c);
    // OKLab highlight chroma compression — creamy HL, rich mids.
    {
        float oc = uOklabHlChroma;
                // auto oklab removed
        c = applyOklabHlChroma(c, oc);
    }

    // ── Tonal-zone WB trims (LUT-tab "Highlight vibrancy" group) ────────
    //   Smoothstep-weighted RGB shifts gated to the highlight vs shadow
    //   luma zones. Temperature shifts R/B inversely (warm = +R/-B, cool
    //   = -R/+B); tint shifts G against magenta (+ = -G boosts R+B, - =
    //   +G). Scale 0.15 caps the maximum colour-cast even at ±1 so the
    //   slider behaves like a finishing trim, not a destructive cast.
    {
        float luma = dot(c, vec3(0.2627, 0.6780, 0.0593));
        float hiW  = smoothstep(0.55, 0.95, luma);          // highlight weight
        float shW  = 1.0 - smoothstep(0.05, 0.45, luma);    // shadow weight
        float K = 0.15;
        // Highlights
        c.r += uHighlightTemperature * hiW * K;
        c.b -= uHighlightTemperature * hiW * K;
        c.g -= uHighlightTint        * hiW * K;
        c.r += uHighlightTint        * hiW * K * 0.5;
        c.b += uHighlightTint        * hiW * K * 0.5;
        // Shadows
        c.r += uShadowTemperature * shW * K;
        c.b -= uShadowTemperature * shW * K;
        c.g -= uShadowTint        * shW * K;
        c.r += uShadowTint        * shW * K * 0.5;
        c.b += uShadowTint        * shW * K * 0.5;
    }

    // Glow / Glamour Glow pass removed — the Snapseed-derived bloom
    // produced unusable results in practice, so the entire branch is
    // gone. Reinstate from git if a future tab needs it.

    // ── Tone Curve (per-channel 256-LUT dependent read) ─────────────────
    //   Each channel indexes the LUT by its own value; the texel's matching
    //   component is the curved output. The LUT already folds the master
    //   (composite) curve over each channel curve, so a single read per
    //   channel reproduces GPUImage's master(channel(x)) result.
    if (uToneCurveEnabled == 1) {
        if (uToneCurveLumaMode == 1) {
            // True L curve: remap luma only, scale chroma to preserve hue.
            // L' = master(L); per-channel out = c + (L' - L). Avoids the
            // per-channel desaturation that the All curve produces at the
            // top/bottom of its range.
            float L  = dot(c, vec3(0.2126, 0.7152, 0.0722));
            float Lp = texture(uToneCurveTex, vec2(clamp(L, 0.0, 1.0), 0.5)).r;
            float dL = Lp - L;
            c += vec3(dL);
            c = max(c, vec3(0.0));
        } else {
            c.r = texture(uToneCurveTex, vec2(clamp(c.r, 0.0, 1.0), 0.5)).r;
            c.g = texture(uToneCurveTex, vec2(clamp(c.g, 0.0, 1.0), 0.5)).g;
            c.b = texture(uToneCurveTex, vec2(clamp(c.b, 0.0, 1.0), 0.5)).b;
        }
    }
    if (uFilmHighlightKnee > 0.001) {
        float L = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float start = clamp(0.62 - uFilmHighlightKnee * 0.22, 0.35, 0.70);
        float Lp = L;
        if (L > start) {
            float t = clamp((L - start) / (1.0 - start), 0.0, 1.0);
            Lp = start + (1.0 - start) * pow(t, 1.0 + uFilmHighlightKnee * 2.4);
        }
        c += vec3(Lp - L);
        c = max(c, vec3(0.0));
    }

    // Color Pop on the graded pixel, after the tone curve. Strength scales
    // the lift itself. The frozen Stage A min/max stretch is not applied.
    if (uSmartColorEnhance > 0.0) {
        c = applySmartColorEnhancement(c, uSmartColorEnhance);
    }

    // ── Film grain (blue-noise) + wash-out ──────────────────────────────
    //   Replaces the old CPU value-hash grain. Blue noise reads like organic
    //   film stock (no low-frequency clumping). Keyed on normalised image UV
    //   snapped to grain blocks → resolution-independent (preview == export).
    if (uFilmGrain > 0.0) {
        float amount = pow(clamp(uFilmGrain, 0.0, 1.0), 0.85);
        float maxGrain = amount * (40.0 / 255.0);
        vec2 grid = grainGrid(uImageSize);
        float seed = uGrainEx[5] > 0.5 ? uGrainEx[5] : uGrainSeed;
        float structure = uGrainEx[0];
        float chroma = uGrainEx[1];
        float hiSup = uGrainEx[2];
        float shBoost = uGrainEx[3];
        float edgeBias = uGrainEx[4];
        float cloud = uGrainEx[6];
        float shCurve = max(uGrainEx[7], 0.0);
        float midCurve = max(uGrainEx[8], 0.0);
        float hiCurve = max(uGrainEx[9], 0.0);
        float lightInf = uGrainEx[10];
        vec3 g;
        bool emulsion = structure > 0.001 || chroma > 0.001 || cloud > 0.001;
        if (emulsion) {
            g = emulsionGrain(vTexCoord, grid, seed, clamp(uFilmGrainSize, 0.0, 1.0), structure, chroma, cloud);
            if (hiSup > 0.001) {
                vec3 blurG = emulsionGrain(vTexCoord + vec2(0.004, 0.0), grid, seed, uFilmGrainSize, structure, chroma, cloud)
                           + emulsionGrain(vTexCoord - vec2(0.004, 0.0), grid, seed, uFilmGrainSize, structure, chroma, cloud)
                           + emulsionGrain(vTexCoord + vec2(0.0, 0.004), grid, seed, uFilmGrainSize, structure, chroma, cloud)
                           + emulsionGrain(vTexCoord - vec2(0.0, 0.004), grid, seed, uFilmGrainSize, structure, chroma, cloud);
                blurG *= 0.25;
                float hs = uHighlightStart;
                float he = uHighlightEnd;
                if (he <= hs + 0.001) { hs = 0.78; he = 0.98; }
                float lumNow = clamp(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
                float hiGate = smoothstep(hs, he, lumNow);
                g = mix(g, blurG, hiGate * hiSup);
            }
        } else {
            g = vec3(cinematicGrain(vTexCoord, grid, seed, clamp(uFilmGrainSize, 0.0, 1.0)));
        }
        float lum = clamp(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
        float md = clamp(1.0 - (lum - 0.5) * (lum - 0.5) * 4.0, 0.0, 1.0);
        float shadow = smoothstep(0.45, 0.05, lum);
        float hs2 = uHighlightStart;
        float he2 = uHighlightEnd;
        if (he2 <= hs2 + 0.001) { hs2 = 0.78; he2 = 0.98; }
        float highlight = smoothstep(hs2, he2, lum);
        float vis = md * midCurve;
        vis = mix(vis, max(vis, shadow * shCurve), shBoost);
        vis *= mix(1.0, 1.0 - highlight * hiCurve, hiSup);
        float eL = dot(texture(uTex, vTexCoord + vec2(0.004, 0.0)).rgb, vec3(0.2126, 0.7152, 0.0722));
        float wL = dot(texture(uTex, vTexCoord - vec2(0.004, 0.0)).rgb, vec3(0.2126, 0.7152, 0.0722));
        float nL = dot(texture(uTex, vTexCoord + vec2(0.0, 0.004)).rgb, vec3(0.2126, 0.7152, 0.0722));
        float sL = dot(texture(uTex, vTexCoord - vec2(0.0, 0.004)).rgb, vec3(0.2126, 0.7152, 0.0722));
        float contrast = max(max(eL, wL), max(nL, sL)) - min(min(eL, wL), min(nL, sL));
        float edge = smoothstep(0.03, 0.16, contrast);
        vis *= mix(1.0, mix(0.35, 1.0, edge), edgeBias);
        float bloomL = texture(uBloomTex, vTexCoord).r;
        float bloomMask = smoothstep(0.02, 0.35, bloomL);
        vis *= mix(1.0, 1.0 - bloomMask, hiSup);
        vis *= mix(1.0, 1.0 - bloomMask * uOpticalSpread * 0.7, hiSup);
        vis *= mix(1.0, 1.0 - max(bloomL - texture(uBloomTex, vTexCoord).g, 0.0), hiSup);
        if (lightInf > 0.001 && uSubjectMaskEnabled == 1) {
            vec2 lightDir = normalize(vec2(uLensFlareX, uLensFlareY) + vec2(0.0001, 0.0001));
            vec2 pixDir = normalize(vTexCoord - vec2(0.5));
            float falloff = clamp(dot(pixDir, lightDir) * 0.5 + 0.5, 0.0, 1.0);
            float lightMask = falloff * sampleSubjectAt(vTexCoord);
            vis *= mix(1.0, 1.0 - lightMask, lightInf);
        }
        float tone = clamp((1.0 - lum) * (1.0 - lum) * 0.80 + md * 1.0 + lum * lum * 0.55, 0.0, 2.0);
        if (shBoost < 0.001 && hiSup < 0.001 && edgeBias < 0.001 && lightInf < 0.001) vis = tone;
        c += g * maxGrain * vis;
    }
    if (uFilmGrainWash > 0.0) {
        float lift   = uFilmGrainWash * (60.0 / 255.0);
        float wscale = 1.0 - uFilmGrainWash * 0.3;
        c = lift + c * wscale;
    }
    // ── Haxademic film grain extension (Req 8) ──────────────────────────
    //   Fast sin-dot hash grain. Bypass when crossfade == 0.
    if (uHaxGrainCrossfade > 0.0) {
        vec2  gUV  = vTexCoord * uHaxGrainScale;
        float gVal = haxGrain(gUV, uGrainSeed) * uHaxGrainLumaAmp;
        vec3  grain = vec3(gVal);
        if (uHaxGrainChromaAmp > 0.0) {
            grain.r = haxGrain(gUV + vec2(0.1, 0.0), uGrainSeed) * uHaxGrainChromaAmp;
            grain.b = haxGrain(gUV + vec2(0.0, 0.1), uGrainSeed) * uHaxGrainChromaAmp;
        }
        c = mix(c, grain, uHaxGrainCrossfade);
    }
    // ACES-style gamut compression — applied LAST so any saturation pushed
    // by HSL / vibrance / LUT / curves gets a final desaturation toward
    // grey at the gamut walls instead of clipping with a hue shift.
    c = applyGamutCompress(c, uGamutCompress);
    c = clamp(c, 0.0, 1.0);

    // (LUT pass — M5; uLutEnabled stays false until then.)
    // (XMP overlay — M5.5; uXmpEnabled stays false until then.)
    // (Gamut-clip soft-proof — only when uShowGamutClip true; M9.)
    // For uGamutOut: 0=sRGB tag, 1=DisplayP3 tag — the framebuffer is sRGB,
    // wider-gamut tagging happens only at export time, not here.

    // Pre-encode highlight soft-knee — touches only top ~5% of range.
    // Verified 2026-06-07 to NOT be the cause of LUT-active divergence
    // (MAD identical with/without). Helps no-LUT photos (22→10) at no cost
    // to LUT photos. Kept on.
    {
        const float knee = 0.90;
        vec3 over = max(c - vec3(knee), vec3(0.0));
        vec3 head = vec3(1.0 - knee);
        c = min(c, vec3(knee)) + head * (over / (over + head));
    }

    // ── PREQ-Port: Effects tab ────────────────────────────────────────────
    // Aberration is sampled at the TOP of main() (parity with Stage C's
    // applyCubicCA_cpu pre-pass) — do not re-apply here.
    // Blur — uBlurTex (unit 8) is populated by the pre-pass when fxBlurStyle>0
    if (uFxBlurStyle > 0) {
        vec2 texelSize = 1.0 / vec2(textureSize(uBlurTex, 0));
        vec3 blurSample;
        if (uFxBlurStyle == 1) {
            // Gaussian — direct sample of pre-blurred FBO
            blurSample = texture(uBlurTex, vTexCoord).rgb;
        } else if (uFxBlurStyle == 2) {
            // Directional — 8-tap along angle direction in screen space
            vec2 dir = vec2(cos(uFxDirBlurAngle), sin(uFxDirBlurAngle)) * texelSize * uFxDirBlurAmt * 24.0;
            blurSample = vec3(0.0);
            for (int i = -4; i <= 4; i++)
                blurSample += texture(uBlurTex, vTexCoord + dir * float(i)).rgb;
            blurSample /= 9.0;
        } else if (uFxBlurStyle == 3) {
            // Radial — 8 samples along radial direction from center
            vec2 toCenter = uFxRadBlurCenter - vTexCoord;
            blurSample = vec3(0.0);
            for (int i = 0; i < 8; i++)
                blurSample += texture(uBlurTex, vTexCoord + toCenter * (float(i) / 7.0) * uFxRadBlurAmt * 0.5).rgb;
            blurSample /= 8.0;
        } else {
            // Zoom — 8 samples along zoom vector from center
            vec2 toCenter = uFxZoomBlurCenter - vTexCoord;
            blurSample = vec3(0.0);
            for (int i = 0; i < 8; i++)
                blurSample += texture(uBlurTex, vTexCoord + toCenter * (float(i) / 7.0) * uFxZoomBlurAmt * 0.3).rgb;
            blurSample /= 8.0;
        }
        float blurAmt = 1.0;
        float blurExclude = 0.0;
        if (uFxBlurExcludeSubject > 0.5 && uSubjectMaskEnabled == 1) {
            vec2 maskUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord);
            blurExclude = clamp(texture(uSubjectMask, maskUV).r, 0.0, 1.0);
        }
        c = mix(c, mix(c, blurSample, blurAmt), 1.0 - blurExclude);
    }
    // Mist
    c = applyMist(c, uFxMist, 0.0);
    // Vintage
    c = applyVintage(c, vTexCoord, uFxVintageStrength, uFxVintageFade, uFxVintageVig, uFxVintageMistIntensity, uFxVintageMistScale, uFxVintageTextureIntensity, uFxVintageTextureScale, uFxMistWarmth);
    // Haxademic radial vignette (Req 5) — creative FX vignette via slot 371.
    if (uFxVintageVig > 0.0) {
        c = applyHaxVignette(c, vTexCoord, 1.0, uFxVintageVig);
    }
    // Glow with spread (reuses bloom texture) — filmic screen + halation.
    // Same protect-subject near-band gate as Orton (mistHalation is shared).
    // Halation coeff 0.005 — must match Orton block / apply_macro.cpp.
    if (uFxGlowStrength > 0.0) {
        float hOff = uMistHalation * 0.005 * halationProtectScale();
        float gR = texture(uBloomTex, vTexCoord + vec2(hOff, 0.0)).r;
        float gG = texture(uBloomTex, vTexCoord).g;
        float gB = texture(uBloomTex, vTexCoord - vec2(hOff, 0.0)).b;
        // Soft diffusion pre-baked into uBloomTex (see Orton block comment).
        vec3 glowBloom = vec3(gR, gG, gB);
        c = applyGlowWithSpread(c, glowBloom, uFxGlowStrength, uFxGlowSpread, uFxGlowWarmth);
    }
    // Optical Spread contribution. Skipped entirely when both sliders are 0
    // so the base bloom path takes no extra samples. Direction radii apply
    // only here. MUST match opticalSpreadAdd in bloom_filmic.h.
    if (uOpticalSpread > 0.001 || uOpticalHalation > 0.001) {
        float amt = clamp(uOpticalSpread, 0.0, 1.0);
        float hal = max(clamp(uOpticalHalation, 0.0, 1.0), clamp(uMistHalation, 0.0, 1.0));
        float rx = 0.004;
        float ry = 0.006;
        if (amt > 0.001) {
            if (uOpticalDirection > 1.5) { rx = 0.008; ry = 0.008; }
            else if (uOpticalDirection > 0.5) { rx = 0.014; ry = 0.0035; }
            float reach = 0.35 + amt * 2.65;
            rx *= reach;
            ry *= reach;
        }
        float dens = uOpticalDensity;
        rx *= dens;
        ry *= dens;
        vec3 lumaW = vec3(0.2126, 0.7152, 0.0722);
        vec3 hiSample = texture(uTex, vTexCoord).rgb;
        float hiL = dot(hiSample, lumaW);
        float eL = dot(texture(uTex, vTexCoord + vec2(0.004, 0.0)).rgb, lumaW);
        float wL = dot(texture(uTex, vTexCoord - vec2(0.004, 0.0)).rgb, lumaW);
        float nL = dot(texture(uTex, vTexCoord + vec2(0.0, 0.004)).rgb, lumaW);
        float sL = dot(texture(uTex, vTexCoord - vec2(0.0, 0.004)).rgb, lumaW);
        float contrast = max(max(eL, wL), max(nL, sL)) - min(min(eL, wL), min(nL, sL));
        float hs = uHighlightStart;
        float he = uHighlightEnd;
        if (he <= hs + 0.001) { hs = 0.78; he = 0.98; }
        float importance = mix(0.08, 1.0, smoothstep(0.03, 0.16, contrast));
        float halaMask = smoothstep(0.45, 0.85, importance) * smoothstep(hs, he, hiL);
        vec3 tight = hiSample * smoothstep(hs, he, hiL) * importance;
        vec3 wide = vec3(0.0);
        vec2 offs[4] = vec2[4](vec2(rx, 0.0), vec2(-rx, 0.0), vec2(0.0, ry), vec2(0.0, -ry));
        for (int i = 0; i < 4; i++) {
            vec3 s = texture(uTex, vTexCoord + offs[i]).rgb;
            wide += s * smoothstep(hs, he, dot(s, lumaW)) * importance;
        }
        wide *= 0.25;
        vec3 mid = max(tight - wide, vec3(0.0));
        vec3 low = max(wide - mid * 0.65, vec3(0.0));
        vec3 glow = low * amt + vec3(wide.r, wide.g * 0.45, wide.b * 0.15) * hal * halaMask;
        float srcL = dot(c, lumaW);
        float bloomL = dot(tight, lumaW);
        float local = clamp(abs(srcL - bloomL) * 3.0, 0.0, 1.0);
        float hg = smoothstep(0.45, 0.85, srcL);
        glow *= max(local, hg);
        c += glow * 0.65;
    }
    // Dust
    c = applyDust(c, vTexCoord, uFxDust, uFxDustSize);
    // Lens flare (procedural additive, above dust)
    c = applySceneShadow(c, vTexCoord);
    if (uLensFlareBrightness > 0.0 && uSubjectMaskEnabled == 1) {
        vec3 flared = applyLensFlare(c, vTexCoord, uLensFlareX, uLensFlareY,
                           uLensFlareBrightness, uLensFlareSize, uLensFlareSpread,
                           uLensFlareWarmth, uLensFlareDistance, uLensFlareHood,
                           uStarburst, uIrisBlades, uIrisRotation, uIrisRoundness);
        float bg = 1.0 - sampleSubjectAt(vTexCoord);
        c = mix(c, flared, bg);
    }

    // Bayer ordered dither — hides 8-bit banding on smooth gradients. Per-channel
    // decorrelated phases (offsets 0,0 / 2,5 / 5,2) break CHROMA banding (log-JPEG
    // skies), not just luma. MUST mirror stage_c_export.cpp bayerShakeCh offsets.
    if (uDitherStrength > 0.0) {
        vec2 f = gl_FragCoord.xy;
        vec3 t = vec3(bayerThreshold(f),
                      bayerThreshold(f + vec2(2.0, 5.0)),
                      bayerThreshold(f + vec2(5.0, 2.0)));
        c += (t - vec3(0.5)) / 255.0 * uDitherStrength;
    }

    // ── Mask selection overlay (UI affordance — LAST, after ALL colour ops) ──
    //   Drawn onto the final pixel so no downstream transform can eat it: a
    //   black-and-white LUT / film sim used to desaturate the blue tint to
    //   invisible grey when this ran before the LUT stage. The selection
    //   alpha keys off the GRADED pre-LUT pixel (`img`), matching what the
    //   mask adjustment loop sees — WYSIWYG for luma bands and carves.
    if (uShowMaskOverlay == 1 && uMaskOverlayLayer >= 0 && uMaskOverlayLayer < 4) {
        float a = maskLayerAlpha(uMaskOverlayLayer, img);
        c = mix(c, vec3(0.10, 0.45, 1.0), a * 0.45);
    }

    fragColor = vec4(c, 1.0);
}
)GLSL";  // end §3 — void main() / end of uber-shader

// The live preview uses the Sobel and blur planes. The offscreen export path
// has no separate edge plane in its JNI contract and must stay within devices'
// GL_MAX_TEXTURE_IMAGE_UNITS=16 limit. Keep one canonical shader source, but
// compile a narrowly degraded export variant: edge snapping is neutral and
// blur-only effects fall back to the source texture instead of failing the
// whole program link on Mali-class devices.
const char* kFragSrcOffscreen = []() -> const char* {
    static std::string source;
    if (source.empty()) {
        source = kFragSrc;
        const std::string sobelDecl = "uniform sampler2D uSobelEdgeMask;";
        const std::string blurDecl = "uniform sampler2D uBlurTex;";
        source.replace(source.find(sobelDecl), sobelDecl.size(), "");
        source.replace(source.find(blurDecl), blurDecl.size(), "");
        const std::string sobelSample = "texture(uSobelEdgeMask, maskUV).r";
        size_t pos = 0;
        while ((pos = source.find(sobelSample, pos)) != std::string::npos) {
            source.replace(pos, sobelSample.size(), "0.0");
            pos += 3;
        }
        const std::string blurName = "uBlurTex";
        pos = 0;
        while ((pos = source.find(blurName, pos)) != std::string::npos) {
            source.replace(pos, blurName.size(), "uTex");
            pos += 4;
        }
    }
    return source.c_str();
}();

// ── Bokeh separable-Gaussian blur (run twice: H then V) ──────────────────
//   Identity vertex shader (we render into an FBO with top-down rows, like
//   the snapshot path). 9-tap Gaussian; tap step = uBlurDir * uBlurRadius
//   in texel units. The source is the full-frame uTex sampled at the FBO's
//   1/4 resolution, so a small tap count yields a wide, smooth blur cheaply.
const char* kBokehBlurVert = R"(#version 300 es
precision highp float;
out vec2 vTexCoord;
void main() {
    vec2 p = vec2(float((gl_VertexID & 1)), float((gl_VertexID >> 1) & 1));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    vTexCoord = p;          // identity — FBO rows are top-down
}
)";
const char* kBokehBlurFrag = R"(#version 300 es
precision highp float;
in  vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uBlurSrc;
uniform vec2  uBlurDir;     // (1,0) horizontal pass, (0,1) vertical pass
uniform float uBlurRadius;  // texel step multiplier
void main() {
    vec2 texel = 1.0 / vec2(textureSize(uBlurSrc, 0));
    vec2 step  = uBlurDir * texel * uBlurRadius;
    // Normalised 9-tap Gaussian (sigma ~ 2.0).
    const float w0 = 0.227027;
    const float w1 = 0.194594;
    const float w2 = 0.121622;
    const float w3 = 0.054054;
    const float w4 = 0.016216;
    vec3 acc = texture(uBlurSrc, vTexCoord).rgb * w0;
    acc += texture(uBlurSrc, vTexCoord + step * 1.0).rgb * w1;
    acc += texture(uBlurSrc, vTexCoord - step * 1.0).rgb * w1;
    acc += texture(uBlurSrc, vTexCoord + step * 2.0).rgb * w2;
    acc += texture(uBlurSrc, vTexCoord - step * 2.0).rgb * w2;
    acc += texture(uBlurSrc, vTexCoord + step * 3.0).rgb * w3;
    acc += texture(uBlurSrc, vTexCoord - step * 3.0).rgb * w3;
    acc += texture(uBlurSrc, vTexCoord + step * 4.0).rgb * w4;
    acc += texture(uBlurSrc, vTexCoord - step * 4.0).rgb * w4;
    fragColor = vec4(acc, 1.0);
}
)";

// ── Karis 6-mip bloom downsample (13 bilinear taps) ──────────────────────
//   Source: SIGGRAPH 2014 "Next Gen Post Processing in Call of Duty
//   Advanced Warfare" by Jorge Jimenez. Public-domain technique.
//
//   13-tap pattern weights (renormalised to sum=1):
//     Centre block (red diamond, 4 taps × 0.5): contributes 0.5
//     Outer corners (4 taps × 0.125):           contributes 0.5 × 0.5 = 0.25
//     Cardinal mid-points (4 taps × 0.125):     contributes 0.25
//
//   Plus a "threshold-extract" branch only on mip 0: pixels below a luma
//   threshold get pushed down toward zero so the bloom only fires on
//   bright pixels. The threshold is soft (Karis's "knee" function).
const char* kBloomDownVert = R"(#version 300 es
precision highp float;
out vec2 vTexCoord;
void main() {
    vec2 p = vec2(float((gl_VertexID & 1)), float((gl_VertexID >> 1) & 1));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    vTexCoord = p;
}
)";
const char* kBloomDownFrag = R"(#version 300 es
precision highp float;
in  vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uBloomDownSrc;
uniform float     uBloomDownThreshold; // < 0 = no threshold, >= 0 = mip-0 highlight extract
uniform float     uHighlightStart;
uniform float     uHighlightEnd;
// Subject-exclusion: when > 0.5 AND uBloomDownSubjectEnabled == 1 AND
// the mip-0 threshold pass is active, multiply the extracted highlights
// by (1 - subject_alpha) so subject pixels contribute ZERO to the
// pyramid. The downstream upsample chain then carries only bg light.
uniform float     uBloomDownExcludeSubject;
uniform int       uBloomDownSubjectEnabled; // 1 if a U2Net mask is uploaded
uniform sampler2D uBloomDownSubjectMask;    // U2Net subject mask (unit 1 here)
uniform vec4      uBloomDownSubjectRect;    // (u0,v0,u1,v1) crop mapping
uniform sampler2D uBloomDownSobelMask;      // edge mask for snap (unit 2)
uniform float     uBloomDownEdgeSnapThreshold; // matches main pass
void main() {
    vec2 texel = 1.0 / vec2(textureSize(uBloomDownSrc, 0));
    // 13-tap Karis pattern. The 4 centre taps share the inner 2×2 box;
    // the 8 outer taps cover the 4×4 neighbourhood.
    vec3 A = texture(uBloomDownSrc, vTexCoord + texel * vec2(-1.0, -1.0)).rgb;
    vec3 B = texture(uBloomDownSrc, vTexCoord + texel * vec2( 0.0, -1.0)).rgb;
    vec3 C = texture(uBloomDownSrc, vTexCoord + texel * vec2( 1.0, -1.0)).rgb;
    vec3 D = texture(uBloomDownSrc, vTexCoord + texel * vec2(-1.0,  0.0)).rgb;
    vec3 E = texture(uBloomDownSrc, vTexCoord                          ).rgb;
    vec3 F = texture(uBloomDownSrc, vTexCoord + texel * vec2( 1.0,  0.0)).rgb;
    vec3 G = texture(uBloomDownSrc, vTexCoord + texel * vec2(-1.0,  1.0)).rgb;
    vec3 H = texture(uBloomDownSrc, vTexCoord + texel * vec2( 0.0,  1.0)).rgb;
    vec3 I = texture(uBloomDownSrc, vTexCoord + texel * vec2( 1.0,  1.0)).rgb;
    vec3 J = texture(uBloomDownSrc, vTexCoord + texel * vec2(-0.5, -0.5)).rgb;
    vec3 K = texture(uBloomDownSrc, vTexCoord + texel * vec2( 0.5, -0.5)).rgb;
    vec3 L = texture(uBloomDownSrc, vTexCoord + texel * vec2(-0.5,  0.5)).rgb;
    vec3 M = texture(uBloomDownSrc, vTexCoord + texel * vec2( 0.5,  0.5)).rgb;
    // Weights from Karis 2014 slides: centre 4-tap block 0.5, outer
    // corners 0.125 (split as 4×0.03125), cardinal mids 0.125 (split
    // as 4×0.03125 + edge mid 0.125 × 0.5). Renormalised to sum 1.
    vec3 acc =
        (J + K + L + M) * 0.5     * 0.25 +  // centre block: 0.125 weight × 4
        (A + C + G + I) * 0.125   * 0.25 +  // outer corners: 0.03125 × 4
        (B + H)         * 0.125   * 0.5  +  // top/bottom mid: 0.0625 × 2
        (D + F)         * 0.125   * 0.5  +  // left/right mid: 0.0625 × 2
         E              * 0.125;             // centre: 0.125
    // Mip-0 threshold extract — luma-zoned weight. < 0 disables (used on
    // mips 1..5).
    //
    // Weighting curve (per user request, 2026-06-04):
    //   • Shadows  (luma < 0.20)         → 0          (no bloom — preserve
    //                                                  deep shadows)
    //   • Midtones (luma 0.20 .. 0.65)   → 0.50       (half-strength bloom)
    //   • Highlights (luma > 0.65)       → ramp to 1  (full bloom at white)
    //
    // The ramps use smoothstep so the transitions are gentle. Final
    // contribution is broadcast scalar — each RGB channel is scaled by
    // the same amount so the bloom carries the source pixel's COLOUR,
    // not just its luma (otherwise we'd get a desaturated wash).
    if (uBloomDownThreshold >= 0.0) {
        // Pro-Mist extract (2026-09-09): keep shadows out, starve midtones,
        // fire mostly on specular / hot highlights. The old 0.5 midtone floor
        // was the milky wash; optical diffusion wraps light, it doesn't lift
        // the whole frame.
        float lum = dot(acc, vec3(0.2126, 0.7152, 0.0722));
        float hs = uHighlightStart;
        float he = uHighlightEnd;
        if (he <= hs + 0.001) { hs = 0.78; he = 0.98; }
        float weight = smoothstep(hs, he, lum);
        float maxL = lum;
        float minL = lum;
        float la = dot(A, vec3(0.2126, 0.7152, 0.0722));
        float lb = dot(B, vec3(0.2126, 0.7152, 0.0722));
        float lc = dot(C, vec3(0.2126, 0.7152, 0.0722));
        float ld = dot(D, vec3(0.2126, 0.7152, 0.0722));
        maxL = max(maxL, max(max(la, lb), max(lc, ld)));
        minL = min(minL, min(min(la, lb), min(lc, ld)));
        float importance = mix(0.08, 1.0, smoothstep(0.03, 0.16, maxL - minL));
        acc *= weight * importance;

        // Subject-exclusion in the pyramid pre-pass was REMOVED (2026-06-04)
        // in favour of compositing-time gating. The new behaviour: the
        // pyramid carries FULL-FRAME bloom (no subject zeroing here), and
        // the main shader's composite chooses per-pixel between bg
        // bloom strength and subject bloom strength using the U2Net
        // mask. That way the user can independently dial subject vs bg
        // bloom (via the "Subject Bloom" slider) using the same single
        // pyramid.
    }
    fragColor = vec4(max(acc, vec3(0.0)), 1.0);
}
)";

// ── Karis bloom upsample (9-tap tent filter, additive) ──────────────────
//   Reads a smaller mip and a same-resolution destination accumulator
//   (passed via blending), spreads the source via a 3×3 tent kernel
//   sized by uBloomUpRadius. Additive blend in caller adds the result
//   onto the larger mip in-place.
const char* kBloomUpVert = R"(#version 300 es
precision highp float;
out vec2 vTexCoord;
void main() {
    vec2 p = vec2(float((gl_VertexID & 1)), float((gl_VertexID >> 1) & 1));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    vTexCoord = p;
}
)";
const char* kBloomUpFrag = R"(#version 300 es
precision highp float;
in  vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uBloomUpSrc;
// Pixel-space radii (x,y). Vertical oval: ry > rx → taller soft glow.
uniform vec2      uBloomUpRadius;
// Filmic / Pro-Mist: scales this mip's additive contribution. Deep mips
// (4–5) are choked near zero so the pyramid stays a tight optical halo
// instead of a wide sci-fi glow. Set by runKarisBloomPass / computeKarisBloom.
uniform float     uBloomUpWeight;
void main() {
    vec2 texel = 1.0 / vec2(textureSize(uBloomUpSrc, 0));
    // Soft 5×5 Gaussian (approx σ≈1.1) — blurrier edges than a hard 3×3 tent.
    vec2 r = texel * uBloomUpRadius;
    float w00 = 1.0, w01 = 4.0, w02 = 7.0;
    float w11 = 16.0, w12 = 26.0, w22 = 41.0;
    float wsum = w00*4.0 + w01*8.0 + w02*4.0 + w11*4.0 + w12*4.0 + w22;
    vec3 acc = vec3(0.0);
    for (int j = -2; j <= 2; ++j) {
        for (int i = -2; i <= 2; ++i) {
            int ax = i < 0 ? -i : i;
            int ay = j < 0 ? -j : j;
            float w;
            if (ax == 0 && ay == 0) w = w22;
            else if ((ax == 1 && ay == 0) || (ax == 0 && ay == 1)) w = w12;
            else if (ax == 1 && ay == 1) w = w11;
            else if ((ax == 2 && ay == 0) || (ax == 0 && ay == 2)) w = w02;
            else if ((ax == 2 && ay == 1) || (ax == 1 && ay == 2)) w = w01;
            else w = w00;
            acc += texture(uBloomUpSrc, vTexCoord + vec2(float(i), float(j)) * r).rgb * w;
        }
    }
    fragColor = vec4((acc / wsum) * uBloomUpWeight, 1.0);
}
)";

// Soft-diffusion → bloom composite. Samples Karis bloom + dedicated soft-diff
// Gaussian; writes enhanced bloom. Orton/Glow strength scales match the
// former uber-shader softDiff blocks (0.65 / 0.55).
const char* kSoftDiffBloomVert = R"(#version 300 es
precision highp float;
out vec2 vTexCoord;
void main() {
    vec2 p = vec2(float((gl_VertexID & 1)), float((gl_VertexID >> 1) & 1));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    vTexCoord = p;
}
)";
const char* kSoftDiffBloomFrag = R"(#version 300 es
precision highp float;
in  vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uBloomSrc;
uniform sampler2D uSoftDiffSrc;
uniform float uOrtonStrength;
uniform float uGlowStrength;
void main() {
    vec3 bloom = texture(uBloomSrc, vTexCoord).rgb;
    vec3 soft  = texture(uSoftDiffSrc, vTexCoord).rgb;
    float softL = dot(soft, vec3(0.2126, 0.7152, 0.0722));
    float softGate = smoothstep(0.25, 0.70, softL);
    if (uOrtonStrength > 0.0) {
        float softAmt = clamp(uOrtonStrength, 0.0, 1.0) * 0.65 * softGate;
        bloom = mix(bloom, max(bloom, soft), softAmt);
    }
    if (uGlowStrength > 0.0) {
        float softAmt = clamp(uGlowStrength, 0.0, 1.0) * 0.55 * softGate;
        bloom = mix(bloom, max(bloom, soft), softAmt);
    }
    fragColor = vec4(max(bloom, vec3(0.0)), 1.0);
}
)";
