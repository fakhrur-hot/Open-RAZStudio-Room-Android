/*
 * grading_uniforms.cpp — body moved VERBATIM out of
 * GlesRenderer::pushUniformsForProgram, with only two mechanical rewrites:
 *     params_.<field>  ->  params.<field>
 *     <member>_        ->  in.<member>
 * No call was added, removed or reordered. The move was verified by comparing
 * the ordered (glUniform function, uniform name) sequence before and after.
 */

#include "grading_uniforms.h"

#include <GLES3/gl3.h>
#include <android/log.h>
#include <cstdio>

namespace raw_v3 {
namespace {
// The body refers to kMaskLayers unqualified; inside GlesRenderer that
// resolved to GlesRenderer::kMaskLayers. Both it and ShaderParams::kMaskLayers
// are 'static constexpr int = 4'; alias the ShaderParams one so the moved text
// compiles unchanged and stays tied to the params layout it indexes.
constexpr int kMaskLayers = ShaderParams::kMaskLayers;
}  // namespace

void pushGradingUniforms(unsigned int prog,
                         const ShaderParams& params,
                         const GradingInputs& in) {
    // PARITY with GlesRenderer::pushUniforms + kParityCriticalUniformNames —
    // see GOTCHAS (Film Response live drift). Offscreen may use glGetUniformLocation;
    // live must keep cached locs.
    GLuint p = GLuint(prog);
    auto L = [&](const char* n) { return glGetUniformLocation(p, n); };

    glUniform1f(L("uExposure"),     params.exposure);
    glUniform1f(L("uContrast"),     params.contrast);
    glUniform1f(L("uHighlights"),   params.highlights);
    glUniform1f(L("uShadows"),      params.shadows);
    glUniform1f(L("uWhites"),       params.whites);
    glUniform1f(L("uBlacks"),       params.blacks);
    glUniform1f(L("uWhitesSubject"),    params.whitesSubject);
    glUniform1f(L("uBlacksSubject"),    params.blacksSubject);
    glUniform1f(L("uWhitesBackground"), params.whitesBackground);
    glUniform1f(L("uBlacksBackground"), params.blacksBackground);
    glUniform1f(L("uShadowsSubject"),    params.shadowsSubject);
    glUniform1f(L("uShadowsBackground"), params.shadowsBackground);
    glUniform1f(L("uHighlightsSubject"),    params.highlightsSubject);
    glUniform1f(L("uHighlightsBackground"), params.highlightsBackground);
    glUniform1f(L("uAmbianceSubject"),      params.ambianceSubject);
    glUniform1f(L("uAmbianceBackground"),   params.ambianceBackground);
    glUniform1f(L("uSaturation"),   params.saturation);
    glUniform1f(L("uVibrance"),     params.vibrance);
    glUniform1f(L("uWhiteBalance"), params.whiteBalance);
    glUniform1f(L("uTint"),         params.tint);
    glUniform3fv(L("uHslRed"),    1, &params.hsl[0]);
    glUniform3fv(L("uHslOrange"), 1, &params.hsl[3]);
    glUniform3fv(L("uHslYellow"), 1, &params.hsl[6]);
    glUniform3fv(L("uHslGreen"),  1, &params.hsl[9]);
    glUniform3fv(L("uHslAqua"),   1, &params.hsl[12]);
    glUniform3fv(L("uHslBlue"),   1, &params.hsl[15]);
    glUniform3fv(L("uHslYellowGreen"), 1, &params.hsl2[0]);
    glUniform3fv(L("uHslSpringGreen"), 1, &params.hsl2[3]);
    glUniform3fv(L("uHslSkyBlue"),     1, &params.hsl2[6]);
    glUniform3fv(L("uHslPurple"),      1, &params.hsl2[9]);
    glUniform3fv(L("uHslMagenta"),     1, &params.hsl2[12]);
    glUniform3fv(L("uHslPink"),        1, &params.hsl2[15]);
    glUniform1f(L("uDitherStrength"), params.ditherStrength);
    glUniform1i(L("uPurpleFringeMode"), (int)params.purpleFringeMode);
    const bool effLut = (params.lutEnabled > 0.5f) && in.lutUploaded;
    glUniform1i(L("uLutEnabled"),     effLut ? 1 : 0);
    glUniform1f(L("uLutIntensity"),   params.lutIntensity);
    glUniform1i(L("uLutBwForce"),     params.lutBwForce);
    glUniform1f(L("uLutSize"),        float(in.lutSize));
    glUniform3fv(L("uLutDomainMin"), 1, in.lutDomainMin);
    glUniform3fv(L("uLutDomainMax"), 1, in.lutDomainMax);
    glUniform1f(L("uLutHighlightVibrancy"), params.lutHighlightVibrancy);
    glUniform1f(L("uHighlightTemperature"), params.highlightTemperature);
    glUniform1f(L("uHighlightTint"),        params.highlightTint);
    glUniform1f(L("uShadowTemperature"),    params.shadowTemperature);
    glUniform1f(L("uShadowTint"),           params.shadowTint);
    glUniform1f(L("uAmbiance"),       params.ambiance);
    glUniform1f(L("uOrtonStrength"),  params.ortonStrength);
    glUniform1f(L("uBloomRadius"),    params.bloomRadius);
    glUniform1f(L("uBloomShape"),     params.bloomShape);
    glUniform1f(L("uMistTightness"),  params.mistTightness);
    glUniform1f(L("uMistHalation"),   params.mistHalation);
    glUniform1f(L("uFilmRolloff"),    params.filmRolloff);
    glUniform1f(L("uFilmicLuma"),     params.filmicLuma);
    glUniform1f(L("uOklabHlChroma"),  params.oklabHlChroma);
    glUniform1f(L("uGamutCompress"),  params.gamutCompress);
    glUniform1f(L("uBloomExcludeSubject"), params.bloomExcludeSubject);
    glUniform1f(L("uSubjectBloom"), params.subjectBloom);
    glUniform1i(L("uWorkspaceSpace"),   params.workspaceSpace);
    glUniform1i(L("uLutAuthoredSpace"), params.lutAuthoredSpace);
    glUniform1f(L("uLightTabOpacity"), params.lightTabOpacity);
    glUniform1f(L("uColorTabOpacity"), params.colorTabOpacity);
    glUniform1f(L("uXmpTabOpacity"),   params.xmpTabOpacity);
    glUniform1f(L("uDehaze"),          params.dehaze);
    glUniform1f(L("uVigAmount"),       params.vigAmount);
    glUniform2f(L("uVigCenter"),       params.vigCenterX, params.vigCenterY);
    glUniform1f(L("uVigFeather"),      params.vigFeather);
    glUniform1f(L("uVigIntensity"),    params.vigIntensity);
    glUniform1i(L("uVigEffect"),       int(params.vigEffect));
    glUniform1f(L("uVigTabOpacity"),   params.vigTabOpacity);
    glUniform1f(L("uGradAngle"),       params.gradAngle);
    glUniform1fv(L("uGradTop[0]"),    15, params.gradTop);
    glUniform1fv(L("uGradBottom[0]"), 15, params.gradBottom);
    glUniform1fv(L("uGradLeft[0]"),   15, params.gradLeft);
    glUniform1fv(L("uGradRight[0]"),  15, params.gradRight);
    glUniform1f(L("uGradTabOpacity"), params.gradTabOpacity);
    glUniform1i(L("uSubjectMaskEnabled"), in.subjectMaskReady ? 1 : 0);
    glUniform4fv(L("uSubjectMaskRect"), 1, in.subjectMaskRect);
    glUniform1i(L("uBokehAttenuationEnabled"), in.bokehAttenReady ? 1 : 0);
    glUniform1i(L("uDepthMapEnabled"), in.depthMapReady ? 1 : 0);
    glUniform1f(L("uBokehFocusDepth"), in.bokehFocusDepth);
    glUniform1i(L("uGradTopApplyTo"),    int(params.gradTopApplyTo));
    glUniform1i(L("uGradBottomApplyTo"), int(params.gradBottomApplyTo));
    glUniform1i(L("uGradLeftApplyTo"),   int(params.gradLeftApplyTo));
    glUniform1i(L("uGradRightApplyTo"),  int(params.gradRightApplyTo));
    glUniform1i(L("uGradTopBlendMode"),    int(params.gradTopBlendMode));
    glUniform1i(L("uGradBottomBlendMode"), int(params.gradBottomBlendMode));
    glUniform1i(L("uGradLeftBlendMode"),   int(params.gradLeftBlendMode));
    glUniform1i(L("uGradRightBlendMode"),  int(params.gradRightBlendMode));
    // Mask tab — bitfield + per-layer arrays (mirrors pushUniforms()).
    {
        int maskBits = 0;
        float mBright[kMaskLayers], mCont[kMaskLayers], mTemp[kMaskLayers];
        float mTint[kMaskLayers], mSat[kMaskLayers], mClar[kMaskLayers], mOpac[kMaskLayers];
        float mSharp[kMaskLayers];
        float mHi[kMaskLayers], mShd[kMaskLayers], mWht[kMaskLayers], mBlk[kMaskLayers];
        float mLumTgt[kMaskLayers], mLumSpr[kMaskLayers], mLumFth[kMaskLayers];
        int   mLumCmb[kMaskLayers];
        for (int i = 0; i < kMaskLayers; ++i) {
            if (in.brushMaskReady[i]) maskBits |= (1 << i);
            mBright[i] = params.maskLayer[i].brightness;
            mSharp[i]  = params.maskLayer[i].sharpness;
            mCont[i]   = params.maskLayer[i].contrast;
            mTemp[i]   = params.maskLayer[i].temperature;
            mTint[i]   = params.maskLayer[i].tint;
            mSat[i]    = params.maskLayer[i].saturation;
            mClar[i]   = params.maskLayer[i].clarity;
            mHi[i]     = params.maskLayer[i].highlights;
            mShd[i]    = params.maskLayer[i].shadows;
            mWht[i]    = params.maskLayer[i].whites;
            mBlk[i]    = params.maskLayer[i].blacks;
            mOpac[i]   = params.maskLayer[i].opacity;
            mLumTgt[i] = params.maskLayer[i].lumTarget;
            mLumSpr[i] = params.maskLayer[i].lumSpread;
            mLumFth[i] = params.maskLayer[i].lumFeather;
            mLumCmb[i] = params.maskLayer[i].lumCombine;
        }
        glUniform1i(L("uBrushMaskEnabled"),    maskBits);
        glUniform1fv(L("uMaskBrightness"),  kMaskLayers, mBright);
        glUniform1fv(L("uMaskContrast"),    kMaskLayers, mCont);
        glUniform1fv(L("uMaskTemperature"), kMaskLayers, mTemp);
        glUniform1fv(L("uMaskTint"),        kMaskLayers, mTint);
        glUniform1fv(L("uMaskSaturation"),  kMaskLayers, mSat);
        glUniform1fv(L("uMaskClarity"),     kMaskLayers, mClar);
        glUniform1fv(L("uMaskSharpness"),   kMaskLayers, mSharp);
        glUniform1fv(L("uMaskHighlights"),  kMaskLayers, mHi);
        glUniform1fv(L("uMaskShadows"),     kMaskLayers, mShd);
        glUniform1fv(L("uMaskWhites"),      kMaskLayers, mWht);
        glUniform1fv(L("uMaskBlacks"),      kMaskLayers, mBlk);
        glUniform1fv(L("uMaskTabOpacity"),  kMaskLayers, mOpac);
        glUniform1fv(L("uMaskLumTarget"),   kMaskLayers, mLumTgt);
        glUniform1fv(L("uMaskLumSpread"),   kMaskLayers, mLumSpr);
        glUniform1fv(L("uMaskLumFeather"),  kMaskLayers, mLumFth);
        glUniform1iv(L("uMaskLumCombine"),  kMaskLayers, mLumCmb);
        // Sampler units for the 4 mask layers (units 3,5,6,7 — see render path).
        glUniform1i(L("uBrushMask"),  3);
        glUniform1i(L("uBrushMask1"), 5);
        glUniform1i(L("uBrushMask2"), 6);
        glUniform1i(L("uBrushMask3"), 7);
    }
    // ── 2026-08-29: uniforms the SNAPSHOT push had silently dropped ─────────
    // This function was moved verbatim from pushUniformsForProgram, which was
    // the snapshot-program push — and that push had drifted behind the live
    // preview's cached-location push (GlesRenderer::pushUniforms, lines
    // ~1585-1795). Every block below copies the cached path exactly (names,
    // types, transforms), closing a hard-rule-#1 gap the razparity harness
    // caught: snapshot/headless renders were missing color-grade wheels,
    // clarity coupling, hslFull, skintone, pushPull, FX, and more.
    glUniform3f(L("uCgShadowsTint"),
                params.cgShadowsR, params.cgShadowsG, params.cgShadowsB);
    glUniform1f(L("uCgShadowsSat"),  params.cgShadowsSat);
    glUniform3f(L("uCgMidtonesTint"),
                params.cgMidtonesR, params.cgMidtonesG, params.cgMidtonesB);
    glUniform1f(L("uCgMidtonesSat"), params.cgMidtonesSat);
    glUniform3f(L("uCgHighlightsTint"),
                params.cgHighlightsR, params.cgHighlightsG, params.cgHighlightsB);
    glUniform1f(L("uCgHighlightsSat"), params.cgHighlightsSat);
    // Film response (LUT tab).
    glUniform1f(L("uFilmRecovery"),   params.filmRecovery);
    glUniform1f(L("uFilmFillLight"),  params.filmFillLight);
    glUniform1i(L("uFilmMonochrome"), params.filmMonochrome);
    glUniform1fv(L("uFilmGrayMix"), 8, params.filmGrayMix);
    glUniform3f(L("uCgGlobalTint"),
                params.cgGlobalR, params.cgGlobalG, params.cgGlobalB);
    glUniform1f(L("uCgGlobalSat"), params.cgGlobalSat);
    glUniform1f(L("uClarityAmount"), params.clarityAmount);
    glUniform1f(L("uClarityLift"),   params.clarityLift);
    glUniform1f(L("uCenterPop"),     params.centerPop);
    glUniform3fv(L("uHslFull"), 8, params.hslFull);
    glUniform1f(L("uDetailGrainRoughness"), params.detailGrainRoughness);
    glUniform1f(L("uDetailSharpenMask"),    params.detailSharpenMask);
    glUniform1f(L("uColorDensity"),  params.colorDensity);
    glUniform3f(L("uSkintone"), params.skintoneWarm, params.skintoneSmooth,
                params.skintoneLuma);
    glUniform1f(L("uMidtoneDetails"), params.midtoneDetails);
    glUniform1f(L("uPushPull"),           params.pushPull);
    glUniform1f(L("uLutColorDensity"),    params.lutColorDensity);
    glUniform1f(L("uLutSkintoneBalance"), params.lutSkintoneBalance);
    glUniform1f(L("uSmartColorEnhance"),  params.smartColorEnhance);
    glUniform3f(L("uSmartWbMin"),
                params.smartWbRMin, params.smartWbGMin, params.smartWbBMin);
    glUniform3f(L("uSmartWbMax"),
                params.smartWbRMax, params.smartWbGMax, params.smartWbBMax);
    glUniform1f(L("uAberStrength"),     params.aberStrength);
    glUniform1f(L("uAberFringeReduce"), params.aberFringeReduce);
    glUniform1i(L("uFxBlurStyle"),   (int)params.fxBlurStyle);
    glUniform1f(L("uFxGaussBlur"),   params.fxGaussBlur);
    glUniform1f(L("uFxDirBlurAmt"),  params.fxDirBlurAmt);
    glUniform1f(L("uFxDirBlurAngle"),params.fxDirBlurAngle);
    glUniform1f(L("uFxRadBlurAmt"),  params.fxRadBlurAmt);
    glUniform2f(L("uFxRadBlurCenter"), params.fxRadBlurCx, params.fxRadBlurCy);
    glUniform1f(L("uFxZoomBlurAmt"), params.fxZoomBlurAmt);
    glUniform2f(L("uFxZoomBlurCenter"), params.fxZoomBlurCx, params.fxZoomBlurCy);
    glUniform1f(L("uFxBlurExcludeSubject"), params.fxBlurExcludeSubject);
    glUniform1f(L("uFxMist"),        params.fxMist);
    glUniform1f(L("uFxMistWarmth"),  params.fxMistWarmth);
    glUniform1f(L("uFxDust"),        params.fxDust);
    glUniform1f(L("uFxDustSize"),    params.fxDustSize);
    glUniform1f(L("uFxVintageStrength"), params.fxVintageStrength);
    glUniform1f(L("uFxVintageFade"),     params.fxVintageFade);
    glUniform1f(L("uFxVintageVig"),      params.fxVintageVig);
    glUniform1f(L("uFxVintageMistIntensity"), params.fxVintageMistIntensity);
    glUniform1f(L("uFxVintageMistScale"),     params.fxVintageMistScale);
    glUniform1f(L("uFxVintageTextureIntensity"), params.fxVintageTextureIntensity);
    glUniform1f(L("uFxVintageTextureScale"),  params.fxVintageTextureScale);
    glUniform1f(L("uFxGlowStrength"),    params.fxGlowStrength);
    glUniform1f(L("uFxGlowSpread"),      params.fxGlowSpread);
    glUniform1f(L("uFxGlowWarmth"),      params.fxGlowWarmth);
    glUniform1f(L("uLensFlareX"),          params.lensFlareX);
    glUniform1f(L("uLensFlareY"),          params.lensFlareY);
    glUniform1f(L("uLensFlareBrightness"), params.lensFlareBrightness);
    glUniform1f(L("uLensFlareSize"),       params.lensFlareSize);
    glUniform1f(L("uLensFlareSpread"),     params.lensFlareSpread);
    glUniform1f(L("uLensFlareWarmth"),     params.lensFlareWarmth);
    glUniform1f(L("uColorShiftRedX"),      params.colorShiftRedX);
    glUniform1f(L("uColorShiftGreenX"),    params.colorShiftGreenX);
    glUniform1f(L("uColorShiftBlueX"),     params.colorShiftBlueX);

    glUniform1f(L("uTonemapExposure"),   params.tonemapExposure);
    glUniform1f(L("uTonemapHighlights"), params.tonemapHighlights);
    glUniform1f(L("uTonemapShadows"),    params.tonemapShadows);
    // uFilmicHlProtect: dead slot [383] — never read in shader/CPU; do not push
    // (live program has no location → GradeParity noise if listed critical).
    glUniform1f(L("uEdgeSnapStrength"),
        in.sobelEdgeReady ? in.edgeSnapStrength : 0.f);
    glUniform1f(L("uEdgeSnapThreshold"), in.edgeSnapThreshold);
    glUniform1i(L("uGamutOut"),       int(params.gamutOut));

    // Bokeh (sampler unit 8 bound by the snapshot caller before draw).
    glUniform1i(L("uBlurTex"),    8);
    glUniform1f(L("uBokehBlur"),   params.bokehBlur);
    glUniform1f(L("uBokehBalls"),  params.bokehBalls);
    glUniform1f(L("uBokehSpread"), params.bokehSpread);

    // Tone Curve LUT (sampler unit 9 bound by the snapshot caller).
    glUniform1i(L("uToneCurveTex"),     9);
    glUniform1i(L("uToneCurveEnabled"), in.toneCurveReady ? 1 : 0);
    glUniform1i(L("uToneCurveLumaMode"), params.toneCurveLumaMode > 0.5f ? 1 : 0);

    // RGB curves (Fritsch-Carlson LUTs on units 12–15). Preview's cached
    // pushUniforms also sets this; offscreen must too or save drops curves.
    glUniform1i(L("uCurvesEnabled"), in.curvesEnabled ? 1 : 0);
    glUniform1i(L("uCurveMasterTex"), 12);
    glUniform1i(L("uCurveRTex"),      13);
    glUniform1i(L("uCurveGTex"),      14);
    glUniform1i(L("uCurveBTex"),      15);

    // Film grain (cinematic 3D noise — procedural).
    glUniform1f(L("uFilmGrain"),     params.filmGrain);
    glUniform1f(L("uFilmGrainSize"), params.filmGrainSize);
    glUniform1f(L("uFilmGrainWash"), params.filmGrainWash);
    glUniform1f(L("uGrainSeed"),     kGrainSeed);
    {
        const float REF = 2048.f;
        float aspect = (in.texH > 0) ? float(in.texW) / float(in.texH) : 1.f;
        glUniform2f(L("uImageSize"), REF * aspect, REF);
    }

    // Haxademic film grain extension (Req 8, slots 375–378).
    glUniform1f(L("uHaxGrainCrossfade"), params.haxGrainCrossfade);
    glUniform1f(L("uHaxGrainScale"),     params.haxGrainScale);
    glUniform1f(L("uHaxGrainLumaAmp"),   params.haxGrainLumaAmp);
    glUniform1f(L("uHaxGrainChromaAmp"), params.haxGrainChromaAmp);

    // M5.5 — XMP overlay block.
    glUniform1i(L("uXmpEnabled"),    params.xmpEnabled > 0.5f ? 1 : 0);
    glUniform1f(L("uXmpExposure"),   params.xmpExposure);
    glUniform1f(L("uXmpContrast"),   params.xmpContrast);
    glUniform1f(L("uXmpHighlights"), params.xmpHighlights);
    glUniform1f(L("uXmpShadows"),    params.xmpShadows);
    glUniform1f(L("uXmpWhites"),     params.xmpWhites);
    glUniform1f(L("uXmpBlacks"),     params.xmpBlacks);
    glUniform3fv(L("uXmpHslRed"),    1, &params.xmpHsl[0]);
    glUniform3fv(L("uXmpHslOrange"), 1, &params.xmpHsl[3]);
    glUniform3fv(L("uXmpHslYellow"), 1, &params.xmpHsl[6]);
    glUniform3fv(L("uXmpHslGreen"),  1, &params.xmpHsl[9]);
    glUniform3fv(L("uXmpHslAqua"),   1, &params.xmpHsl[12]);
    glUniform3fv(L("uXmpHslBlue"),   1, &params.xmpHsl[15]);
}

void logLiveGradingUniformParity(unsigned int liveProg) {
    // One-shot init log — catches the next Film-Response-style drift where
    // pushGradingUniforms gained a uniform but cacheUniformLocations / pushUniforms
    // did not. Cheap: runs once per renderer init, not per frame.
    static bool s_done = false;
    if (s_done) return;
    s_done = true;
    GLuint p = GLuint(liveProg);
    int missing = 0;
    for (int i = 0; i < kParityCriticalUniformCount; ++i) {
        const char* name = kParityCriticalUniformNames[i];
        GLint loc = glGetUniformLocation(p, name);
        if (loc < 0) {
            char indexed[64];
            std::snprintf(indexed, sizeof(indexed), "%s[0]", name);
            loc = glGetUniformLocation(p, indexed);
        }
        if (loc < 0) {
            ++missing;
            __android_log_print(ANDROID_LOG_ERROR, "RawV3.GradeParity",
                "LIVE PROGRAM MISSING uniform '%s' — add to cacheUniformLocations + pushUniforms "
                "(export pushGradingUniforms already sets it → preview≠save)",
                name);
        }
    }
    if (missing == 0) {
        __android_log_print(ANDROID_LOG_INFO, "RawV3.GradeParity",
            "live↔grading uniform parity OK (%d critical names)",
            kParityCriticalUniformCount);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, "RawV3.GradeParity",
            "live↔grading uniform parity FAILED — %d/%d critical names missing on live program",
            missing, kParityCriticalUniformCount);
    }
}

}  // namespace raw_v3
