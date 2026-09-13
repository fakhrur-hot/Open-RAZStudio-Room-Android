/*
 * StudioRoom — RAW Pipeline v3 — ShaderParams::fromFloatArray.
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Hoisted VERBATIM from gles_renderer.cpp (2026-08-29) so that headless /
 * desktop consumers (offscreen_save_renderer, the razparity harness) can
 * decode the Kotlin ShaderParams float blob without dragging in the
 * AHardwareBuffer/ANativeWindow-coupled live renderer. This TU touches no
 * GL and no EGL — it is pure array-to-struct mapping, and it is the ONLY
 * copy (a second mapping is exactly how preview != export drift starts).
 */

#include "gles_renderer.h"

namespace raw_v3 {

// ── ShaderParams ───────────────────────────────────────────────────────────
ShaderParams ShaderParams::fromFloatArray(const float* arr, int count) {
    ShaderParams p;
    if (!arr || count <= 0) return p;
    auto get = [&](int i) -> float { return (i < count) ? arr[i] : 0.f; };
    p.exposure     = get(0);
    p.contrast     = get(1);
    p.highlights   = get(2);
    p.shadows      = get(3);
    p.whites       = get(4);
    p.blacks       = get(5);
    p.saturation   = get(6);
    p.vibrance     = get(7);
    p.whiteBalance = get(8);
    p.tint         = get(9);
    for (int i = 0; i < 18; ++i) p.hsl[i] = get(10 + i);
    for (int i = 0; i < 18; ++i) p.hsl2[i] = get(211 + i);
    p.ditherStrength = get(28);
    // M12.1 — tab opacities default to 1 when the blob predates the field.
    auto getOr = [&](int i, float fallback) -> float {
        return (i < count) ? arr[i] : fallback;
    };
    // [449] purpleFringeMode — was wrongly sharing [199] with shadowsBackground.
    p.purpleFringeMode = getOr(449, 0.f);
    p.lutEnabled     = get(29);
    p.gamutOut       = get(30);
    p.lutIntensity   = get(31);
    p.lutBwForce     = getOr(450, 0.f) > 0.5f ? 1 : 0;
    p.lutHighlightVibrancy = getOr(200, 0.f);
    p.highlightTemperature = getOr(201, 0.f);
    p.highlightTint        = getOr(202, 0.f);
    p.shadowTemperature    = getOr(203, 0.f);
    p.shadowTint           = getOr(204, 0.f);
    // Slot [205] = bloomRadius (0..24). Must NOT be hardcoded — Pro-Mist tiers
    // write 4/6/8/10 and Stage C reads params[205] for the Karis tent. A stale
    // "slider removed → fixed 1.f" left preview at tent≈1.15 while export used
    // the real radius (classic preview≠export). Slot [206]=bloomShape,
    // [207]=filmRolloff (Glamour Glow slots retired).
    p.ambiance       = getOr(208, 0.f);
    p.ortonStrength  = getOr(209, 0.f);
    {
        const float br = getOr(205, 8.f);
        p.bloomRadius = (br < 0.f) ? 0.f : (br > 24.f ? 24.f : br);
    }
    p.bloomShape     = getOr(206, 1.f);
    p.mistTightness  = getOr(447, 0.55f);
    p.mistHalation   = getOr(448, 0.f);
    p.filmRolloff    = getOr(207, 0.f);
    p.gamutCompress  = getOr(237, 0.f);
    p.bloomExcludeSubject = getOr(238, 0.f);
    p.subjectBloom        = getOr(239, 0.f);
    p.cgShadowsR    = getOr(240, 0.5f);
    p.cgShadowsG    = getOr(241, 0.5f);
    p.cgShadowsB    = getOr(242, 0.5f);
    p.cgShadowsSat  = getOr(243, 0.f);
    p.cgMidtonesR   = getOr(244, 0.5f);
    p.cgMidtonesG   = getOr(245, 0.5f);
    p.cgMidtonesB   = getOr(246, 0.5f);
    p.cgMidtonesSat = getOr(247, 0.f);
    p.cgHighlightsR    = getOr(248, 0.5f);
    p.cgHighlightsG    = getOr(249, 0.5f);
    p.cgHighlightsB    = getOr(250, 0.5f);
    p.cgHighlightsSat  = getOr(251, 0.f);
    // [436..446]. Slot [435] is lensFlareWarmth (written from Kotlin).
    p.filmRecovery   = getOr(436, 0.f);
    p.filmFillLight  = getOr(437, 0.f);
    p.filmMonochrome = getOr(438, 0.f) > 0.5f ? 1 : 0;
    for (int i = 0; i < 8; ++i) p.filmGrayMix[i] = getOr(439 + i, 0.f);

    p.cgGlobalR        = getOr(426, 0.5f);
    p.cgGlobalG        = getOr(427, 0.5f);
    p.cgGlobalB        = getOr(428, 0.5f);
    p.cgGlobalSat      = getOr(429, 0.f);
    p.clarityAmount    = getOr(151, 0.f);
    p.clarityLift      = getOr(408, 0.f);
    p.centerPop        = getOr(252, 0.f);
    p.workspaceSpace   = (int)getOr(235, 1.f);  // 1 = sRGB default
    p.lutAuthoredSpace = (int)getOr(236, 0.f);  // 0 = Rec.709 default
    p.toneCurveLumaMode = getOr(210, 0.f);
    p.lightTabOpacity = getOr(57, 1.f);
    p.colorTabOpacity = getOr(58, 1.f);
    p.xmpTabOpacity   = getOr(59, 1.f);
    p.dehaze          = getOr(60, 0.f);
    p.vigAmount       = getOr(61, 0.f);
    p.vigCenterX      = getOr(62, 0.5f);
    p.vigCenterY      = getOr(63, 0.5f);
    p.vigFeather      = getOr(64, 0.5f);
    p.vigIntensity    = getOr(65, 1.f);
    p.vigEffect       = getOr(66, 0.f);
    p.vigTabOpacity   = getOr(67, 1.f);
    p.gradAngle       = getOr(68, 0.f);
    for (int i = 0; i < 15; ++i) p.gradTop[i]    = getOr(69  + i, 0.f);
    for (int i = 0; i < 15; ++i) p.gradBottom[i] = getOr(84  + i, 0.f);
    for (int i = 0; i < 15; ++i) p.gradLeft[i]   = getOr(99  + i, 0.f);
    for (int i = 0; i < 15; ++i) p.gradRight[i]  = getOr(114 + i, 0.f);
    p.gradTabOpacity  = getOr(129, 1.f);
    p.gradTopApplyTo    = getOr(130, 0.f);
    p.gradBottomApplyTo = getOr(131, 0.f);
    p.gradLeftApplyTo   = getOr(132, 0.f);
    p.gradRightApplyTo  = getOr(133, 0.f);
    p.gradTopBlendMode    = getOr(391, 0.f);
    p.gradBottomBlendMode = getOr(392, 0.f);
    p.gradLeftBlendMode   = getOr(393, 0.f);
    p.gradRightBlendMode  = getOr(394, 0.f);
    p.maskBrightness    = getOr(134, 0.f);
    p.maskContrast      = getOr(135, 0.f);
    p.maskTemperature   = getOr(136, 0.f);
    p.maskTint          = getOr(137, 0.f);
    p.maskSaturation    = getOr(138, 0.f);
    p.maskClarity       = getOr(139, 0.f);
    p.maskTabOpacity    = getOr(140, 1.f);
    p.tonemapExposure   = getOr(141, 0.f);
    p.tonemapHighlights = getOr(142, 0.f);
    p.tonemapShadows    = getOr(143, 0.f);
    p.filmicHlProtect   = getOr(383, 0.f);
    // Multi-layer mask. Layer 0 mirrors the scalar mask block at [134..140]
    // so legacy single-mask blobs keep working. Layers 1..3 live in the
    // 21-float tail at [144..164]; absent (old blob) → defaults (opacity 1).
    p.maskLayer[0].brightness  = p.maskBrightness;
    p.maskLayer[0].contrast    = p.maskContrast;
    p.maskLayer[0].temperature = p.maskTemperature;
    p.maskLayer[0].tint        = p.maskTint;
    p.maskLayer[0].saturation  = p.maskSaturation;
    p.maskLayer[0].clarity     = p.maskClarity;
    p.maskLayer[0].opacity     = p.maskTabOpacity;
    for (int li = 1; li < kMaskLayers; ++li) {
        const int b = 157 + (li - 1) * 7;
        p.maskLayer[li].brightness  = getOr(b + 0, 0.f);
        p.maskLayer[li].contrast    = getOr(b + 1, 0.f);
        p.maskLayer[li].temperature = getOr(b + 2, 0.f);
        p.maskLayer[li].tint        = getOr(b + 3, 0.f);
        p.maskLayer[li].saturation  = getOr(b + 4, 0.f);
        p.maskLayer[li].clarity     = getOr(b + 5, 0.f);
        p.maskLayer[li].opacity     = getOr(b + 6, 1.f);
    }
    // Per-layer masked sharpness appended at [396..399] (append-only ABI).
    for (int li = 0; li < kMaskLayers; ++li) {
        p.maskLayer[li].sharpness = getOr(396 + li, 0.f);
    }
    // Per-layer masked tone regions appended at [410..425] (append-only ABI):
    // highlights [410..413], shadows [414..417], whites [418..421],
    // blacks [422..425]. Old blobs shorter than 426 → 0 (inert).
    for (int li = 0; li < kMaskLayers; ++li) {
        p.maskLayer[li].highlights = getOr(410 + li, 0.f);
        p.maskLayer[li].shadows    = getOr(414 + li, 0.f);
        p.maskLayer[li].whites     = getOr(418 + li, 0.f);
        p.maskLayer[li].blacks     = getOr(422 + li, 0.f);
    }
    // Luminance mask per layer: [182..184] L0, [185..187] L1, [188..190] L2,
    // [191..193] L3 = target, spread, feather. spread 0 (old blob) → off.
    for (int li = 0; li < kMaskLayers; ++li) {
        const int b = 182 + li * 3;
        p.maskLayer[li].lumTarget  = getOr(b + 0, 0.f);
        p.maskLayer[li].lumSpread  = getOr(b + 1, 0.f);
        p.maskLayer[li].lumFeather = getOr(b + 2, 0.f);
    }
    // Luma↔bitmap combine mode for the mask-tab editing layer (layer 0) @ [395].
    p.maskLayer[0].lumCombine = (int)(getOr(395, 0.f) + 0.5f);
    // Bokeh (GL real-time). Absent in old blobs → 0 (no effect).
    p.bokehBlur   = getOr(179, 0.f);
    p.bokehBalls  = getOr(180, 0.f);
    p.bokehSpread = getOr(181, 0.f);
    // Film grain (GL real-time, blue-noise) — wire slots [153..156].
    p.filmGrain     = getOr(153, 0.f);
    p.filmGrainSize = getOr(154, 0.5f);
    p.filmGrainWash = getOr(156, 0.f);
    // slots 155 (uniformity) and 209 (style) removed.
    // Per-segment levels (Normalize for 3Dlut).
    p.whitesSubject    = getOr(194, 0.f);
    p.blacksSubject    = getOr(195, 0.f);
    p.whitesBackground = getOr(196, 0.f);
    p.blacksBackground = getOr(197, 0.f);
    p.shadowsSubject    = getOr(198, 0.f);
    p.shadowsBackground = getOr(199, 0.f);
    p.highlightsSubject    = getOr(229, 0.f);
    p.highlightsBackground = getOr(230, 0.f);
    p.ambianceSubject      = getOr(231, 0.f);
    p.ambianceBackground   = getOr(232, 0.f);
    // M5.5 XMP overlay block.
    p.xmpEnabled    = get(32);
    p.xmpExposure   = get(33);
    p.xmpContrast   = get(34);
    p.xmpHighlights = get(35);
    p.xmpShadows    = get(36);
    p.xmpWhites     = get(37);
    p.xmpBlacks     = get(38);
    for (int i = 0; i < 18; ++i) p.xmpHsl[i] = get(39 + i);
    // PREQ-Port additions
    for (int i = 0; i < 24; ++i) p.hslFull[i] = getOr(253 + i, 0.f);
    // Curve control points — default to identity if slot absent
    for (int i = 0; i < 16; ++i) {
        float def = (i / 2) / 7.f;
        p.curveMaster[i] = getOr(277 + i, def);
        p.curveR[i]      = getOr(293 + i, def);
        p.curveG[i]      = getOr(309 + i, def);
        p.curveB[i]      = getOr(325 + i, def);
    }
    p.detailGrainRoughness = getOr(341, 0.f);
    p.detailSharpenMask    = getOr(342, 0.f);
    p.colorDensity         = getOr(343, 0.f);
    p.skintoneWarm         = getOr(344, 0.f);
    p.skintoneSmooth       = getOr(345, 0.f);
    p.skintoneLuma         = getOr(346, 0.f);
    p.midtoneDetails       = getOr(347, 0.f);
    p.highlightRecovery    = getOr(348, 0.f);
    p.pushPull             = getOr(349, 0.f);
    p.lutColorDensity      = getOr(350, 0.f);
    p.lutSkintoneBalance   = getOr(351, 0.f);
    p.aberStrength         = getOr(352, 0.f);
    p.aberFringeReduce     = getOr(353, 0.f);
    p.fxGaussBlur          = getOr(354, 0.f);
    p.fxDirBlurAmt         = getOr(355, 0.f);
    p.fxDirBlurAngle       = getOr(356, 0.f);
    p.fxRadBlurAmt         = getOr(357, 0.f);
    p.fxRadBlurCx          = getOr(358, 0.5f);
    p.fxRadBlurCy          = getOr(359, 0.5f);
    p.fxZoomBlurAmt        = getOr(360, 0.f);
    p.fxZoomBlurCx         = getOr(361, 0.5f);
    p.fxZoomBlurCy         = getOr(362, 0.5f);
    p.fxBlurStyle          = getOr(363, 0.f);
    p.fxBlurExcludeSubject = getOr(364, 0.f);
    p.fxMist               = getOr(365, 0.f);
    p.fxMistWarmth         = getOr(366, 0.f);
    p.fxDust               = getOr(367, 0.f);
    p.fxDustSize           = getOr(368, 0.f);
    p.fxVintageStrength    = getOr(369, 0.f);
    p.fxVintageFade        = getOr(370, 0.f);
    p.fxVintageVig         = getOr(371, 0.f);
    p.fxGlowStrength       = getOr(372, 0.f);
    p.fxGlowSpread         = getOr(373, 0.f);
    p.fxGlowWarmth         = getOr(374, 0.f);
    p.lensFlareX           = getOr(400, -0.5f);
    p.lensFlareY           = getOr(401, -0.5f);
    p.lensFlareBrightness  = getOr(409, 0.f);
    p.lensFlareSize        = getOr(430, 1.f);
    p.lensFlareSpread      = getOr(431, 1.f);
    p.lensFlareWarmth      = getOr(435, 0.f);
    p.colorShiftRedX       = getOr(432, 0.f);
    p.colorShiftGreenX     = getOr(433, 0.f);
    p.colorShiftBlueX      = getOr(434, 0.f);
    // Haxademic grain extension + Laplacian sharpen (slots 375–379)
    p.haxGrainCrossfade    = getOr(375, 0.f);
    p.haxGrainScale        = getOr(376, 1.f);
    p.haxGrainLumaAmp      = getOr(377, 1.f);
    p.haxGrainChromaAmp    = getOr(378, 0.f);
    p.sharpenAmount        = getOr(379, 0.f);
    p.viewZoom             = getOr(380, 1.f);
    p.viewPanX             = getOr(381, 0.f);
    p.viewPanY             = getOr(382, 0.f);
    p.smartColorEnhance    = getOr(384, 0.f);
    p.smartWbRMin          = getOr(385, 0.f);
    p.smartWbRMax          = getOr(386, 1.f);
    p.smartWbGMin          = getOr(387, 0.f);
    p.smartWbGMax          = getOr(388, 1.f);
    p.smartWbBMin          = getOr(389, 0.f);
    p.smartWbBMax          = getOr(390, 1.f);
    return p;
}

}  // namespace raw_v3
