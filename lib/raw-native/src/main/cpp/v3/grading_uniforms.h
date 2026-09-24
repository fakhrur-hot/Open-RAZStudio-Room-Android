/*
 * grading_uniforms — the parity-critical uniform push, as a CONTEXT-FREE
 * function.
 *
 * WHY THIS IS SPLIT OUT
 * ---------------------
 * These 133 glUniform calls are the contract between ShaderParams and the
 * uber-shader. The headless/desktop export path (OffscreenSaveRenderer, incl.
 * Windows via ANGLE) has to push the SAME uniforms in the SAME order as the
 * live preview, or exports drift from what the user saw — hard rule #1.
 *
 * Duplicating them was rejected for the same reason the shader text was
 * de-duplicated into shader_sources.h: a second copy is a divergence waiting
 * to happen.
 *
 * DELIBERATELY NOT a "grading core" class. GlesRenderer keeps ownership of
 * every piece of GL state (textures, FBOs, EGL surface, AHardwareBuffer) and
 * simply calls this function. That matters for verification: because the
 * preview path is otherwise untouched, neutrality is provable by STATIC
 * analysis (the emitted glUniform call sequence is unchanged), instead of
 * requiring a live on-device GL context to measure.
 *
 * This TU needs no EGL and no Android headers — only GLES entry points.
 */

#ifndef RAZ_V3_GRADING_UNIFORMS_H
#define RAZ_V3_GRADING_UNIFORMS_H

#include "gles_renderer.h"   // ShaderParams

namespace raw_v3 {

/**
 * Film-grain PRNG seed, pushed as uGrainSeed.
 *
 * Lives here rather than in gles_renderer.cpp because BOTH the shared uniform
 * push and GlesRenderer's own cached-location fast path (uGrainSeedLoc_) send
 * it. Two copies of a constant that feeds the same uniform is a drift risk, so
 * there is exactly one definition.
 */
constexpr float kGrainSeed = 37.0f;

/**
 * Renderer state (outside ShaderParams) that the uniform push needs.
 *
 * These were GlesRenderer members; they are bundled here so the function has
 * no implicit dependency on a renderer instance. Defaults mirror the member
 * initialisers in gles_renderer.h, so a default-constructed GradingInputs
 * behaves like a freshly-initialised renderer.
 */
struct GradingInputs {
    int   texW = 0;                 // source texture dimensions, in pixels
    int   texH = 0;

    bool  lutUploaded = false;      // 3D LUT bound on unit for uLutTex?
    int   lutSize = 33;             // cube edge length
    float lutDomainMin[3] = {0.f, 0.f, 0.f};
    float lutDomainMax[3] = {1.f, 1.f, 1.f};

    bool  subjectMaskReady = false;
    float subjectMaskRect[4] = {0.f, 0.f, 1.f, 1.f};   // u0, v0, u1, v1

    // Unit-10 RG8: .r atten, .g depth (depth→CoC bokeh).
    bool  bokehAttenReady = false;
    bool  depthMapReady = false;
    float bokehFocusDepth = 0.5f;

    bool  sobelEdgeReady = false;
    float edgeSnapStrength = 0.35f;
    float edgeSnapThreshold = 0.20f;

    bool  toneCurveReady = false;
    bool  curvesEnabled = false;    // RGB tone curves (units 12–15) non-identity
    bool  brushMaskReady[ShaderParams::kMaskLayers] = {false, false, false, false};
};

/**
 * Push every ShaderParams-derived uniform into [prog].
 *
 * Requires a current GL context and [prog] already linked; looks its own
 * uniform locations up via glGetUniformLocation. Uniforms absent from the
 * program get location -1, which the GL spec makes a silent no-op — that is
 * how one function serves several programs built from the same shader.
 */
void pushGradingUniforms(unsigned int prog,
                         const ShaderParams& params,
                         const GradingInputs& in);

/**
 * Names that BOTH the live cached push (GlesRenderer::pushUniforms) and
 * pushGradingUniforms must upload. Film Response (uFilmRecovery etc.) shipped
 * as canvas no-ops when live lagged export — see GOTCHAS.
 *
 * When adding a grading uniform: append here, cacheUniformLocations,
 * pushUniforms, AND pushGradingUniforms. Live must keep cached locs (do NOT
 * call glGetUniformLocation per frame).
 *
 * [logLiveGradingUniformParity] runs once after cacheUniformLocations and
 * LOGE's any name missing from the live program.
 */
inline constexpr const char* kParityCriticalUniformNames[] = {
    // Film Response / ADV (2026-09 drift class)
    "uFilmRecovery",
    "uFilmFillLight",
    "uFilmMonochrome",
    "uFilmGrayMix",
    "uFilmRolloff",
    "uFilmGrain",
    "uFilmGrainSize",
    "uFilmGrainWash",
    // Color-grade wheels (2026-08-29 snapshot drift)
    "uCgShadowsTint",
    "uCgShadowsSat",
    "uCgMidtonesTint",
    "uCgMidtonesSat",
    "uCgHighlightsTint",
    "uCgHighlightsSat",
    "uCgGlobalTint",
    "uCgGlobalSat",
    // Other historically dropped snapshot uniforms
    "uClarityAmount",
    "uClarityLift",
    "uCenterPop",
    "uHslFull",
    "uColorDensity",
    "uFilmSeparation",
    "uSkintone",
    "uPushPull",
    "uCurvesEnabled",
    // Mask Tone All (Highlights/Shadows/Whites/Blacks) — live pushUniforms
    // once cached locs but never assigned/uploaded them (snapshot path was
    // fine). Keep these on the parity list so the drift can't regress.
    "uMaskHighlights",
    "uMaskShadows",
    "uMaskWhites",
    "uMaskBlacks",
    // Diffusion / Pro-Mist (2026-09) — Glow historically unbound bloom FBO when
    // Orton=0; keep these on the list so live+grading stay aligned.
    "uOrtonStrength",
    "uBloomRadius",
    "uMistHalation",
    "uFxGlowStrength",
    // uFilmicHlProtect deliberately omitted: slot [383] is declared/uploaded
    // but never read in the shader or CPU kernel (dead). GL strips the unused
    // uniform from the live program → GradeParity ERROR on every open. Do not
    // re-add until a body lands in BOTH shader_sources.cpp and apply_macro.cpp.
    // Same class (declared, pushed, never read): uLutColorDensity [350],
    // uLutSkintoneBalance [351], uDetailSharpenMask [342] — no live UI.
};

inline constexpr int kParityCriticalUniformCount =
    int(sizeof(kParityCriticalUniformNames) / sizeof(kParityCriticalUniformNames[0]));

/** One-shot: verify [liveProg] resolves every kParityCriticalUniformNames entry.
 *  Implemented in gles_renderer.cpp (Android log); no-op stub not required on
 *  desktop — only the live preview path calls it after cacheUniformLocations. */
void logLiveGradingUniformParity(unsigned int liveProg);

}  // namespace raw_v3

#endif  // RAZ_V3_GRADING_UNIFORMS_H
