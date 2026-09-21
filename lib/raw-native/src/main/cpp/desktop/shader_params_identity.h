/*
 * shader_params_identity.h — GENERATED, do not hand-edit.
 *
 * The identity (all-defaults) ShaderParams blob, i.e. exactly what Kotlin
 * `ShaderParams().toFloatArray()` produces. Stage C rejects a null params
 * pointer ("params missing"), and a plain all-zero array is WRONG: ~39 slots
 * have non-zero defaults (tab opacities = 1, vignette/colour-grade centres =
 * 0.5, lutIntensity = 1, ...). Passing zeros there would, for example, set
 * every tab opacity to 0 and silently neuter the render.
 *
 * Regenerate with tools/gen_identity_params.js after changing ShaderParams.kt.
 * Source of truth: ShaderParams.kt (constructor defaults x toFloatArray slots).
 */
#ifndef RAZ_SHADER_PARAMS_IDENTITY_H
#define RAZ_SHADER_PARAMS_IDENTITY_H

namespace raz
{

        static constexpr int kShaderParamsCount = 451;

        // Non-zero defaults (slot: field = value):
        //   [28] ditherStrength=1, [31] lutIntensity=1, [57] lightTabOpacity=1
        //   [58] colorTabOpacity=1, [59] xmpTabOpacity=1, [62] vigCenterX=0.5
        //   [63] vigCenterY=0.5, [64] vigFeather=0.5, [65] vigIntensity=1
        //   [67] vigTabOpacity=1, [129] gradTabOpacity=1, [140] maskTabOpacity=1
        //   [148] colorNR=0.15, [154] detailFilmGrainSize=0.5, [163] mask1TabOpacity=1
        //   [170] mask2TabOpacity=1, [177] mask3TabOpacity=1, [205] bloomRadius=8
        //   [206] bloomShape=1, [235] workspaceSpace=1, [240] cgShadowsR=0.5
        //   [241] cgShadowsG=0.5, [242] cgShadowsB=0.5, [244] cgMidtonesR=0.5
        //   [245] cgMidtonesG=0.5, [246] cgMidtonesB=0.5, [248] cgHighlightsR=0.5
        //   [249] cgHighlightsG=0.5, [250] cgHighlightsB=0.5, [358] fxRadBlurCx=0.5
        //   [359] fxRadBlurCy=0.5, [361] fxZoomBlurCx=0.5, [362] fxZoomBlurCy=0.5
        //   [376] haxGrainScale=1, [377] haxGrainLumaAmp=1, [380] viewZoom=1
        //   [386] smartWbRMax=1, [388] smartWbGMax=1, [390] smartWbBMax=1

        static const float kShaderParamsIdentity[kShaderParamsCount] = {
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [0]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [8]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [16]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            1.0f, // [24]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [32]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [40]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [48]
            0.0f,
            1.0f,
            1.0f,
            1.0f,
            0.0f,
            0.0f,
            0.5f,
            0.5f, // [56]
            0.5f,
            1.0f,
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [64]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [72]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [80]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [88]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [96]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [104]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [112]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [120]
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [128]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            0.0f, // [136]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.15f,
            0.0f,
            0.0f,
            0.0f, // [144]
            0.0f,
            0.0f,
            0.5f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [152]
            0.0f,
            0.0f,
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [160]
            0.0f,
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [168]
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [176]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [184]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [192]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            8.0f,
            1.0f,
            0.0f, // [200]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [208]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [216]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [224]
            0.0f,
            0.0f,
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [232]
            0.5f,
            0.5f,
            0.5f,
            0.0f,
            0.5f,
            0.5f,
            0.5f,
            0.0f, // [240]
            0.5f,
            0.5f,
            0.5f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [248]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [256]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [264]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [272]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [280]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [288]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [296]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [304]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [312]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [320]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [328]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [336]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [344]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.5f,
            0.5f, // [352]
            0.0f,
            0.5f,
            0.5f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [360]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [368]
            1.0f,
            1.0f,
            0.0f,
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            0.0f, // [376]
            0.0f,
            0.0f,
            1.0f,
            0.0f,
            1.0f,
            0.0f,
            1.0f,
            0.0f, // [384]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [392]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [400]
            0.0f,
            0.0f, // [408]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [410]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [418]
            0.5f,
            0.5f,
            0.5f,
            0.0f,
            1.0f,
            1.0f,
            0.0f,
            0.0f, // [426]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f, // [434]
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.55f, // [442]
            0.0f,  // [450]
        };

} // namespace raz

#endif // RAZ_SHADER_PARAMS_IDENTITY_H
