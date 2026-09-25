/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW Pipeline v3 — Action card flattening (M6).
 *
 *  The editor stores adjustments as a List<RawAction> (one card per tab
 *  history step). The legacy MacroProcessor folds them into a single
 *  UserMacro via [UserMacro.mergeWith] and walks the result through CPU
 *  passes. v3's renderer takes a flat ShaderParams instead.
 *
 *  This file is the one-way bridge:
 *    List<RawAction>  →  composed UserMacro  →  ShaderParams (32+25 floats)
 *
 *  Same flattener is used by:
 *    • The editor canvas (slider drag → Stage B render)
 *    • Stage C export (Plan.md M8/M9): same ShaderParams blob is handed to
 *      the NDK kernel so the saved file matches the canvas pixel-for-pixel.
 *
 *  Masks (RawAction.maskPath != null) and spatial ops (NR, sharpness,
 *  clarity, vignette, gradient, film grain) are NOT folded here — they
 *  land in M10 (mask replay) and the M7 stop-drag deferred pass.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

object RawV3ActionReplay {

    /**
     * Fraction of LibRaw's full auto-bright factor that the Smart Bright slider
     * applies at 100%. 1.0 = slider 100% applies the FULL auto-bright factor, so
     * the top-1% white point is driven to pure white (clips) — the LibRaw
     * `auto_bright_thr = 0.01` behaviour the slider is meant to expose. (Was 0.30,
     * a gentle ceiling; raised per user intent that 100% = a real highlight clip.
     * Note: this scales the slider's strength at EVERY position, not just 100%.)
     */
    private const val SMART_BRIGHT_MAX_STRENGTH = 1.0f

    /**
     * Smart Bright → exposure stops. The single source of truth for the Smart
     * Bright curve, shared by [flatten] (which bakes it into the editor preview
     * / export blob) and the headless coordinator (which applies it per-file
     * during batch export, where [flatten] ran with [autoBrightFactor] = 1).
     *
     *   effective multiplier = 1 + (AB − 1) · (slider / 4) · MAX_STRENGTH
     *   stops = log2(multiplier)
     *
     * Returns 0 when Smart Bright is off ([smartBright] ≤ 0) or there's no
     * auto-bright headroom ([autoBrightFactor] ≤ 1) — i.e. the slider is inert.
     */
    fun smartBrightStops(smartBright: Float, autoBrightFactor: Float): Float {
        if (smartBright <= 0f || autoBrightFactor <= 1f) return 0f
        val mult = 1f + (autoBrightFactor - 1f) * (smartBright / 4f) * SMART_BRIGHT_MAX_STRENGTH
        return (Math.log(mult.toDouble()) / Math.log(2.0)).toFloat()
    }

    /**
     * Fold a non-mask, visible action list into a single ShaderParams.
     *
     * Composition order matches the legacy MacroProcessor: the OLDEST visible
     * non-mask card wins for "first applied"; later cards' adjustments stack
     * on top via UserMacro.mergeWith (which sums numerics + clamps).
     *
     * @param actions   Editor's current action list (top of stack first by
     *                  convention). Filtering + reversal mirror the legacy
     *                  `RawBatchProcessor.buildMacroFromActions` so the
     *                  resulting macro is identical for the same input.
     * @param xmpBlob   Optional 25-float blob from RawV3Engine.parseAdobeXmp.
     *                  When `blob[0] > 0.5`, the XMP overlay block of the
     *                  returned ShaderParams is populated. Pass null or an
     *                  empty array to leave the overlay disabled.
     * @param ditherStrength UI-controlled dither amplitude, default 1.0 (full
     *                  ±0.5/255 noise). 0 disables.
     */
    fun flatten(
        actions: List<RawAction>,
        xmpBlob: FloatArray? = null,
        ditherStrength: Float = 1f,
        baseMacro: UserMacro = UserMacro(),
        purpleFringeMode: Int = 0,
        // Workspace + LUT-authored gamut hints. Caller threads these from
        // WorkspaceConfig.libRawOutputColor.librawValue and
        // WorkspaceConfig.lutInputSpace.ordinal so the GL renderer can
        // transform pixel data correctly around the LUT sample for non-
        // sRGB workspaces. Default = sRGB / Rec.709 (identity transform).
        workspaceSpace: Int = 1,
        lutAuthoredSpace: Int = 0,
        // Per-image LibRaw auto-bright factor (AB ≥ 1) for the Smart Bright
        // slider. 1.0 = no auto-bright available/needed → Smart Bright is inert.
        // Threaded from the editor/coordinator which computes it once per photo.
        autoBrightFactor: Float = 1f,
        // Per-channel auto-WB stats from Stage A thumbnail (normalized [0..1]).
        // FloatArray of 6 floats: [rMin, rMax, gMin, gMax, bMin, bMax]. Null = no stretch.
        smartWbStats: FloatArray? = null,
        // ML Extended Intelligence diagnostic slots (spec cr2-intelligence-
        // integration, Component 1) — 6 floats: [dualIsoRecoveryGain,
        // dualIsoBlendFactor, sceneDR, highlightHeadroom, diffractionComp,
        // bodyWbTrim]. These have no UserMacro representation (unlike the
        // reused NR/CA/detail slots, which flow through baseMacro above);
        // null = no orchestrator run this session, slots stay at default.
        extDiagnostics: FloatArray? = null,
    ): ShaderParams {
        val composed0 = composeMacro(actions, baseMacro)
        // Smart Bright [0..4]: interpolate toward the auto-bright factor and fold
        // the result into exposure (mapMacroToShaderParams clamps to ±4 EV).
        //   effective multiplier = 1 + (AB − 1) · (slider / 4) · MAX_STRENGTH
        // MAX_STRENGTH caps the slider's full scale: 100% applies only 30% of
        // LibRaw's full auto-bright factor — full AB blows highlights in normal
        // scenes, so 30% is the practical ceiling.
        val sbStops = smartBrightStops(composed0.smartBright, autoBrightFactor)
        val composed = if (sbStops != 0f) {
            composed0.copy(exposure = composed0.exposure + sbStops)
        } else composed0
        var params = mapMacroToShaderParams(composed, ditherStrength)
            .copy(
                purpleFringeMode = purpleFringeMode,
                workspaceSpace = workspaceSpace,
                lutAuthoredSpace = lutAuthoredSpace,
            ).let { p ->
                if (smartWbStats != null && smartWbStats.size >= 6) {
                    p.copy(
                        smartWbRMin = smartWbStats[0],
                        smartWbRMax = smartWbStats[1],
                        smartWbGMin = smartWbStats[2],
                        smartWbGMax = smartWbStats[3],
                        smartWbBMin = smartWbStats[4],
                        smartWbBMax = smartWbStats[5],
                    )
                } else p
            }.let { p ->
                if (extDiagnostics != null && extDiagnostics.size >= 6) {
                    p.copy(
                        extDualIsoRecoveryGain = extDiagnostics[0],
                        extDualIsoBlendFactor = extDiagnostics[1],
                        extSceneDR = extDiagnostics[2],
                        extHighlightHeadroom = extDiagnostics[3],
                        extDiffractionComp = extDiagnostics[4],
                        extBodyWbTrim = extDiagnostics[5],
                    )
                } else p
            }
        // Multi-layer mask: map up to 4 visible masked actions onto the 4
        // independent mask layers (bottommost = layer 0). Each layer carries
        // its own adjustment set so overlapping painted regions composite
        // additively in the shader / Stage C kernel, instead of all collapsing
        // onto the single topmost card.
        params = applyMaskLayers(params, maskLayers(actions))
        if (xmpBlob != null && xmpBlob.size >= ShaderParams.XMP_BLOB_FLOAT_COUNT &&
            xmpBlob[0] > 0.5f
        ) {
            params = params.withXmp(xmpBlob)
        }
        return params
    }

    /**
     * Up to [ShaderParams]-many (4) visible masked actions in layer order:
     * the bottommost masked card on the stack is layer 0, the next layer 1,
     * etc. Actions beyond the 4th visible masked card are dropped (the GL
     * renderer only has 4 brush-mask units). The editor uploads each PNG to
     * the matching layer index.
     */
    fun maskLayers(actions: List<RawAction>): List<RawAction> {
        val all = actions.filter { it.isVisible && it.maskPath != null }
        // The renderer has 4 brush units. A 5th mask is refused in the editor.
        // If a stack still has more, keep indexes 0..3 (do not reshuffle).
        if (all.size > ShaderParams.MASK_LAYER_COUNT) {
            android.util.Log.w(
                "RawV3ActionReplay",
                "mask cap ${ShaderParams.MASK_LAYER_COUNT}; ${all.size - ShaderParams.MASK_LAYER_COUNT} oldest layer(s) not rendered",
            )
        }
        // Safety only. The editor refuses a 5th mask before an inflight layer exists.
        return all.takeLast(ShaderParams.MASK_LAYER_COUNT)
    }

    /**
     * Overlay each masked action's Mask-tab adjustments onto its layer slot.
     * Layer 0 reuses the existing maskBrightness… fields; layers 1..3 use the
     * mask1/mask2/mask3 field groups. Non-mask params on [base] are preserved.
     */
    private fun applyMaskLayers(base: ShaderParams, layers: List<RawAction>): ShaderParams {
        var p = base
        layers.forEachIndexed { idx, action ->
            val m = action.macro
            // Luma-range fields are copied per layer alongside the tone
            // adjustments. Before this, only [flatten]'s composeMacro wrote
            // them — always into layer 0's slots [182..184] — so a second
            // Select-Luminance mask leaked its band onto layer 0 (modulated
            // by the OLDEST card's adjustments) while its own layer had
            // lumSpread=0 and rendered no blue overlay at all. Copying from
            // each action also inherently clears stale layer-0 luma when
            // layer 0's own card has none (m.maskLumSpread == 0).
            // Note: maskLumCombine (slot [395]) is layer-0-only in both
            // native parsers — carve mode on layers 1..3 is a known design
            // limit until the ABI grows per-layer combine slots.
            p = when (idx) {
                0 -> p.copy(
                    maskBrightness  = m.maskBrightness,
                    maskContrast    = m.maskContrast,
                    maskTemperature = m.maskTemperature.toFloat(),
                    maskTint        = m.maskTint,
                    maskSaturation  = m.maskSaturation,
                    maskClarity     = m.maskClarity,
                    cinematic       = p.cinematic.copyOf(25).also {
                        it[24] = (m.maskTone.banding / 100f).coerceIn(0f, 1f)
                    },
                    maskSharpness   = m.maskSharpness,
                    maskHighlights  = m.maskTone.highlights,
                    maskShadows     = m.maskTone.shadows,
                    maskWhites      = m.maskTone.whites,
                    maskBlacks      = m.maskTone.blacks,
                    maskTabOpacity  = 1f,
                    maskLumTarget   = m.maskLumTarget,
                    maskLumSpread   = m.maskLumSpread,
                    maskLumFeather  = m.maskLumFeather,
                    maskLumCombine  = m.maskLumCombine,
                )
                1 -> p.copy(
                    mask1Brightness  = m.maskBrightness,
                    mask1Contrast    = m.maskContrast,
                    mask1Temperature = m.maskTemperature.toFloat(),
                    mask1Tint        = m.maskTint,
                    mask1Saturation  = m.maskSaturation,
                    mask1Clarity     = m.maskClarity,
                    mask1Sharpness   = m.maskSharpness,
                    mask1Highlights  = m.maskTone.highlights,
                    mask1Shadows     = m.maskTone.shadows,
                    mask1Whites      = m.maskTone.whites,
                    mask1Blacks      = m.maskTone.blacks,
                    mask1TabOpacity  = 1f,
                    mask1LumTarget   = m.maskLumTarget,
                    mask1LumSpread   = m.maskLumSpread,
                    mask1LumFeather  = m.maskLumFeather,
                )
                2 -> p.copy(
                    mask2Brightness  = m.maskBrightness,
                    mask2Contrast    = m.maskContrast,
                    mask2Temperature = m.maskTemperature.toFloat(),
                    mask2Tint        = m.maskTint,
                    mask2Saturation  = m.maskSaturation,
                    mask2Clarity     = m.maskClarity,
                    mask2Sharpness   = m.maskSharpness,
                    mask2Highlights  = m.maskTone.highlights,
                    mask2Shadows     = m.maskTone.shadows,
                    mask2Whites      = m.maskTone.whites,
                    mask2Blacks      = m.maskTone.blacks,
                    mask2TabOpacity  = 1f,
                    mask2LumTarget   = m.maskLumTarget,
                    mask2LumSpread   = m.maskLumSpread,
                    mask2LumFeather  = m.maskLumFeather,
                )
                else -> p.copy(
                    mask3Brightness  = m.maskBrightness,
                    mask3Contrast    = m.maskContrast,
                    mask3Temperature = m.maskTemperature.toFloat(),
                    mask3Tint        = m.maskTint,
                    mask3Saturation  = m.maskSaturation,
                    mask3Clarity     = m.maskClarity,
                    mask3Sharpness   = m.maskSharpness,
                    mask3Highlights  = m.maskTone.highlights,
                    mask3Shadows     = m.maskTone.shadows,
                    mask3Whites      = m.maskTone.whites,
                    mask3Blacks      = m.maskTone.blacks,
                    mask3TabOpacity  = 1f,
                    mask3LumTarget   = m.maskLumTarget,
                    mask3LumSpread   = m.maskLumSpread,
                    mask3LumFeather  = m.maskLumFeather,
                )
            }
        }
        return p
    }

    /**
     * Fold visible action cards via UserMacro.mergeWith — same recipe as
     * legacy for non-mask cards. The merged result seeds the non-mask
     * ShaderParams. Per-layer Mask-tab adjustments are NOT taken from this
     * merge any more: [flatten] overrides them via [applyMaskLayers] using
     * [maskLayers] so each of the (up to 4) masked actions keeps its own
     * independent adjustment set rather than collapsing onto one card.
     */
    fun composeMacro(
        actions: List<RawAction>,
        baseMacro: UserMacro = UserMacro(),
    ): UserMacro {
        val visible = actions.filter {
            it.id != RawAction.ORIGINAL_ID && it.isVisible
        }
        // [baseMacro] seeds the fold so callers that want a non-default
        // starting point (e.g. WorkspaceConfig.cameraStyleFinishEnabled
        // → UserMacro.CAMERA_STYLE_FINISH) get its adjustments folded
        // in at the BOTTOM of the action stack. User edits in the
        // editor's Light/Color tabs then merge additively on top via
        // UserMacro.mergeWith.
        return visible
            .reversed()
            .fold(baseMacro) { acc, action -> acc.mergeWith(action.macro) }
    }

    /**
     * Topmost visible mask card, or null. The editor calls this on every
     * action-list change so it knows which PNG to load + upload to the
     * brush-mask GL texture.
     */
    fun topmostMaskAction(actions: List<RawAction>): RawAction? =
        actions.lastOrNull { it.isVisible && it.maskPath != null }

    /**
     * Map a composed UserMacro onto a ShaderParams, normalizing every slider
     * to the shader's [-1, +1] domain. See the per-field table in the source
     * for the conversions used.
     */
    fun mapMacroToShaderParams(
        m: UserMacro,
        ditherStrength: Float = 1f,
    ): ShaderParams {
        // ─── Range conversions ─────────────────────────────────────────────
        //   exposure        EV stops, [-4..+4]   → shader exp2(uExposure)
        //                   pass through (no scale; shader uses raw EV)
        //   contrast etc.   [-100..+100]         → / 100
        //   whiteBalance    Kelvin delta (-2500..+2500 typical) → / 2500
        //   tint            [-200..+200]         → / 200
        //   hslXxxHue       degrees [-180..+180] → / 180  (shader treats as ±1)
        //   hslXxxSat/Lum   [-100..+100]         → / 100

        val hsl = floatArrayOf(
            m.hslRedHue / 180f,    m.hslRedSat / 100f,    m.hslRedLum / 100f,
            m.hslOrangeHue / 180f, m.hslOrangeSat / 100f, m.hslOrangeLum / 100f,
            m.hslYellowHue / 180f, m.hslYellowSat / 100f, m.hslYellowLum / 100f,
            m.hslGreenHue / 180f,  m.hslGreenSat / 100f,  m.hslGreenLum / 100f,
            m.hslAquaHue / 180f,   m.hslAquaSat / 100f,   m.hslAquaLum / 100f,
            m.hslBlueHue / 180f,   m.hslBlueSat / 100f,   m.hslBlueLum / 100f,
        )
        // 6 new Color-Zones anchors. Same encoding as the 6 above (hue / 180,
        // sat & lum / 100). Order: YellowGreen, SpringGreen, SkyBlue, Purple,
        // Magenta, Pink.
        val hsl2 = floatArrayOf(
            m.hslExt.yellowGreenHue / 180f, m.hslExt.yellowGreenSat / 100f, m.hslExt.yellowGreenLum / 100f,
            m.hslExt.springGreenHue / 180f, m.hslExt.springGreenSat / 100f, m.hslExt.springGreenLum / 100f,
            m.hslExt.skyBlueHue / 180f,     m.hslExt.skyBlueSat / 100f,     m.hslExt.skyBlueLum / 100f,
            m.hslExt.purpleHue / 180f,      m.hslExt.purpleSat / 100f,      m.hslExt.purpleLum / 100f,
            m.hslExt.magentaHue / 180f,     m.hslExt.magentaSat / 100f,     m.hslExt.magentaLum / 100f,
            m.hslExt.pinkHue / 180f,        m.hslExt.pinkSat / 100f,        m.hslExt.pinkLum / 100f,
        )

        return ShaderParams(
            // Clamp to the shader's supported EV range. The macro exposure is a
            // sum of additive deltas (manual + AI Expose + Auto Bright), so the
            // total can exceed ±4 even when each part is in range.
            exposure       = m.exposure.coerceIn(-4f, 4f),     // raw EV, clamped
            contrast       = m.contrast       / 100f,
            highlights     = m.highlights     / 100f,
            shadows        = m.shadows        / 100f,
            whites         = m.whites         / 100f,
            blacks         = m.blacks         / 100f,
            // Per-segment levels (Normalize for 3Dlut). Normalised to [-1..+1]
            // here so the shader/Stage C math matches the existing whites/blacks
            // convention (consume directly, no further /100).
            whitesSubject    = m.whitesSubject    / 100f,
            blacksSubject    = m.blacksSubject    / 100f,
            whitesBackground = m.whitesBackground / 100f,
            blacksBackground = m.blacksBackground / 100f,
            shadowsSubject    = m.shadowsSubject    / 100f,
            shadowsBackground = m.shadowsBackground / 100f,
            highlightsSubject    = (m.highlightsSubject    / 100f).coerceIn(-1f, 1f),
            highlightsBackground = (m.highlightsBackground / 100f).coerceIn(-1f, 1f),
            ambianceSubject      = m.ambianceSubject.coerceIn(-1f, 1f),
            ambianceBackground   = m.ambianceBackground.coerceIn(-1f, 1f),
            saturation     = m.saturation     / 100f,
            vibrance       = m.vibrance       / 100f,
            // Kelvin → ±1. UserMacro stores Kelvin DELTA from as-shot (0 = no
            // shift). Cap at ±2500 K so the slider domain matches Lightroom's
            // "Temperature offset" widget at the extremes.
            whiteBalance   = (m.whiteBalance.toFloat() / 2500f).coerceIn(-1f, 1f),
            tint           = (m.tint / 200f).coerceIn(-1f, 1f),
            hsl            = hsl,
            hsl2           = hsl2,
            ditherStrength = ditherStrength,
            lutEnabled     = m.lutStack.isNotEmpty() || m.lutCubeUri.isNotEmpty(),
            // M12.1 follow-up: lutIntensity now comes from the topmost
            // committed layer (or the editing macro's lutIntensity when
            // nothing is committed yet). When multiple LUT layers are
            // stacked, the editor bakes them into a single chained LUT
            // with every layer's own intensity already applied, so the
            // shader must sample that baked LUT at 1.0 to avoid double-
            // applying the topmost intensity.
            lutIntensity   = if (m.lutStack.size + (if (m.lutCubeUri.isNotEmpty()) 1 else 0) > 1) {
                1f
            } else {
                effectiveLutLayer(m)?.intensity
                    ?.coerceIn(0f, 1f)
                    ?: m.lutIntensity.coerceIn(0f, 1f)
            },
            // B&W pack: chroma-locked intensity mix (slot [450]). Infer from
            // the effective cube path so favorites / sidecars keep the lock
            // without a UserMacro field (dex register ceiling).
            lutBwForce     = run {
                val uri = effectiveLutLayer(m)?.cubeUri ?: m.lutCubeUri
                com.RAZStudio.StudioRoom.feature.photo_editor.presentation
                    .components.lut.isBlackAndWhiteLutPath(uri)
            },
            gamutOut       = 0,    // M6 always renders to sRGB; gamut output picked at Stage C export
            // Dehaze: UserMacro stores [-25..+25] (LUT-ADJ finishing trim),
            // shader expects [-1..+1]. /100 keeps prior Lightroom-style scale
            // so ±25 → ±0.25 in the atmospheric model.
            dehaze         = (m.dehaze.coerceIn(-25f, 25f) / 100f).coerceIn(-1f, 1f),
            // M12.2b Vignette. UserMacro vignetteAmount is [-100..+100];
            // we scale by /100 to match the shader's [-1..+1] domain.
            // Centers / feather / intensity already in [0..1] so they pass
            // through. Effect enum's ordinal maps 0=All, 1=Subject, 2=Bg.
            vigAmount      = (m.vignetteAmount / 100f).coerceIn(-1f, 1f),
            vigCenterX     = m.vignetteCenterX.coerceIn(0f, 1f),
            vigCenterY     = m.vignetteCenterY.coerceIn(0f, 1f),
            vigFeather     = m.vignetteFeather.coerceIn(0f, 1f),
            vigIntensity   = m.vignetteIntensity.coerceIn(0f, 1f),
            vigEffect      = m.vignetteSegmentation.ordinal + if (m.vignetteInvert) 10 else 0,
            // ── M12.2b.2 Gradient ───────────────────────────────────────
            gradAngle      = m.gradientAngle,
            gradTop        = packGradientSide(
                intensity1 = m.gradientTopIntensity,
                length1    = m.gradientTopLength,
                feather1   = m.gradientTopFeather,
                tintColor1 = m.gradientTopTintColor,
                tintLum1   = m.gradientTopTintLuminosity,
                enable2    = m.gradientTopEnable2,
                intensity2 = m.gradientTopIntensity2,
                length2    = m.gradientTopLength2,
                feather2   = m.gradientTopFeather2,
                tintColor2 = m.gradientTopTintColor2,
                tintLum2   = m.gradientTopTintLuminosity2,
            ),
            gradBottom     = packGradientSide(
                intensity1 = m.gradientBottomIntensity,
                length1    = m.gradientBottomLength,
                feather1   = m.gradientBottomFeather,
                tintColor1 = m.gradientBottomTintColor,
                tintLum1   = m.gradientBottomTintLuminosity,
                enable2    = m.gradientBottomEnable2,
                intensity2 = m.gradientBottomIntensity2,
                length2    = m.gradientBottomLength2,
                feather2   = m.gradientBottomFeather2,
                tintColor2 = m.gradientBottomTintColor2,
                tintLum2   = m.gradientBottomTintLuminosity2,
            ),
            gradLeft       = packGradientSide(
                intensity1 = m.gradientLeftIntensity,
                length1    = m.gradientLeftLength,
                feather1   = m.gradientLeftFeather,
                tintColor1 = m.gradientLeftTintColor,
                tintLum1   = m.gradientLeftTintLuminosity,
                enable2    = m.gradientLeftEnable2,
                intensity2 = m.gradientLeftIntensity2,
                length2    = m.gradientLeftLength2,
                feather2   = m.gradientLeftFeather2,
                tintColor2 = m.gradientLeftTintColor2,
                tintLum2   = m.gradientLeftTintLuminosity2,
            ),
            gradRight      = packGradientSide(
                intensity1 = m.gradientRightIntensity,
                length1    = m.gradientRightLength,
                feather1   = m.gradientRightFeather,
                tintColor1 = m.gradientRightTintColor,
                tintLum1   = m.gradientRightTintLuminosity,
                enable2    = m.gradientRightEnable2,
                intensity2 = m.gradientRightIntensity2,
                length2    = m.gradientRightLength2,
                feather2   = m.gradientRightFeather2,
                tintColor2 = m.gradientRightTintColor2,
                tintLum2   = m.gradientRightTintLuminosity2,
            ),
            // ── M12.2c.1 segmentation gating ────────────────────────────
            gradTopApplyTo    = m.gradientTopApplyTo.ordinal,
            gradBottomApplyTo = m.gradientBottomApplyTo.ordinal,
            gradLeftApplyTo   = m.gradientLeftApplyTo.ordinal,
            gradRightApplyTo  = m.gradientRightApplyTo.ordinal,
            // Per-side tint blend mode (Solid=0 light-leak screen+add, Fused=1 overlay).
            gradTopBlendMode    = m.gradientTopBlendMode.ordinal,
            gradBottomBlendMode = m.gradientBottomBlendMode.ordinal,
            gradLeftBlendMode   = m.gradientLeftBlendMode.ordinal,
            gradRightBlendMode  = m.gradientRightBlendMode.ordinal,
            // ── M12.2c.2 Mask tab ───────────────────────────────────────
            // v2 stores temperature as Int Kelvin delta; brightness/contrast
            // /tint/saturation/clarity in their native -100..+100 (or -150
            // ..+150 for tint) domains. The shader normalises further when
            // it consumes them (e.g. /100 for EV, /2500 for WB).
            maskBrightness  = m.maskBrightness,
            maskContrast    = m.maskContrast,
            maskTemperature = m.maskTemperature.toFloat(),
            maskTint        = m.maskTint,
            maskSaturation  = m.maskSaturation,
            maskClarity     = m.maskClarity,
            maskSharpness   = m.maskSharpness,
            maskHighlights  = m.maskTone.highlights,
            maskShadows     = m.maskTone.shadows,
            maskWhites      = m.maskTone.whites,
            maskBlacks      = m.maskTone.blacks,
            // Luminance-range mask (layer 0). Already [0,1]; the shader +
            // export kernel generate the feathered selection from these.
            maskLumTarget   = m.maskLumTarget,
            maskLumSpread   = m.maskLumSpread,
            maskLumFeather  = m.maskLumFeather,
            maskLumCombine  = m.maskLumCombine,
            // ── Tonemap tab (XMP target) ──────────────────────────────
            // Same /100 normalisation as the Light tab; exposure is in
            // raw EV stops. Independent macro fields preserve the
            // Light-tab AUTO EXPO under XMP overlay.
            tonemapExposure   = m.tonemapExposure,
            tonemapHighlights = m.tonemapHighlights / 100f,
            tonemapShadows    = m.tonemapShadows    / 100f,
            // ── CLAHE pre-pass (native-only) ──────────────────────────
            // Boosts pass through untouched: UserMacro already stores them
            // in the [0..1] range the native kernel expects.
            // CLAHE is always-on now (the user-facing toggle was removed).
            // The Shadows/Highlights boost sliders are the only knobs left,
            // and they need the bake to run unconditionally — pinning the
            // flag to true here also handles legacy macro snapshots that
            // were saved with claheEnabled=false before the UI change.
            claheEnabled         = true,
            claheShadowsBoost    = m.claheShadowsBoost,
            claheHighlightsBoost = m.claheHighlightsBoost,
            jpegRefine = JpegRefine(
                strength = (m.jpegRefine.strength / 100f).coerceIn(0f, 1f),
                clean    = (m.jpegRefine.clean / 100f).coerceIn(0f, 1f),
                detail   = (m.jpegRefine.detail / 100f).coerceIn(0f, 1f),
            ),
            lutHighlightVibrancy = m.lutHighlightVibrancy,
            highlightTemperature = m.highlightTemperature,
            highlightTint        = m.highlightTint,
            shadowTemperature    = m.shadowTemperature,
            shadowTint           = m.shadowTint,
            // glowStrength/Saturation/Warmth/Sharpness removed.
            ambiance             = m.ambiance,
            ortonStrength        = m.ortonStrength.coerceIn(0f, 1f),
            bloomRadius          = m.bloomRadius.coerceIn(0f, 24f),
            cinematic            = floatArrayOf(
                (m.mistTightness / 100f).coerceIn(0f, 1f),
                (m.mistHalation / 100f).coerceIn(0f, 1f),
                m.lensFlare.distance.coerceIn(0f, 1f),
                m.lensFlare.hood.coerceIn(0f, 1f),
                (m.sceneShadow.distance / 100f).coerceIn(0f, 1f),
                (m.sceneShadow.strength / 100f).coerceIn(0f, 1f),
                (m.sceneShadow.softness / 100f).coerceIn(0f, 1f),
                m.highlightStart.coerceIn(0.2f, 0.98f),
                m.highlightEnd.coerceIn(0.3f, 1f),
                m.grainEmulsion.getOrElse(0) { 0f },
                m.grainEmulsion.getOrElse(1) { 0f },
                m.grainEmulsion.getOrElse(2) { 0f },
                m.grainEmulsion.getOrElse(3) { 0f },
                m.grainEmulsion.getOrElse(4) { 0f },
                m.grainEmulsion.getOrElse(5) { 0f },
                m.grainEmulsion.getOrElse(6) { 0f },
                m.grainEmulsion.getOrElse(7) { 1f },
                m.grainEmulsion.getOrElse(8) { 1f },
                m.grainEmulsion.getOrElse(9) { 1f },
                m.grainEmulsion.getOrElse(10) { 0f },
                m.grainEmulsion.getOrElse(11) { 1f },
                m.grainEmulsion.getOrElse(12) { 0f },
                (m.filmCurve.highlightKnee / 100f).coerceIn(0f, 1f),
                (m.filmResponse.separation / 100f).coerceIn(-1f, 1f),
            ),
            optical              = floatArrayOf(
                (m.opticalSpread() / 100f).coerceIn(0f, 1f),
                (m.opticalHalation() / 100f).coerceIn(0f, 1f),
                m.opticalDirection().coerceIn(0f, 2f),
                m.lensFlare.starburst.coerceIn(0f, 1f),
                m.lensFlare.blades.coerceIn(0f, 1f),
                m.lensFlare.rotation.coerceIn(0f, 1f),
                m.lensFlare.roundness.coerceIn(0f, 1f),
            ),
            bloomShape           = m.bloomShape.coerceIn(0.4f, 1.6f),
            filmRolloff          = m.filmRolloff.coerceIn(0f, 1f),
            gamutCompress        = m.gamutCompress.coerceIn(0f, 1f),
            bloomExcludeSubject  = if (m.bloomExcludeSubject) 1f else 0f,
            subjectBloom         = m.subjectBloom.coerceIn(0f, 1f),
            toneCurveLumaMode    = if (m.toneCurveLumaMode) 1f else 0f,
            // ── Noise Reduction (native pre-op) ───────────────────────
            luminanceNR = m.luminanceNR,
            colorNR     = m.colorNR,
            blueNR      = m.blueNR.coerceIn(0f, 1f),
            redNR       = m.redNR.coerceIn(0f, 1f),
            // ── Detail tab (native pre-op) — normalise UI ranges ──────
            detailSharpness      = (m.sharpness / 100f).coerceIn(0f, 1f),
            detailSmartSharpness = m.smartSharpness.coerceIn(0f, 1f),
            detailClarity        = (m.clarity / 100f).coerceIn(-1f, 1f),
            clarityLift          = m.clarityLift.coerceIn(0f, 1f),
            // Film response (LUT tab), packed per ShaderParams.film's layout.
            // UI is -100..100; the shader wants -1..1.
            film = FloatArray(11).also { f ->
                f[0] = (m.filmResponse.recovery  / 100f).coerceIn(-1f, 1f)
                f[1] = (m.filmResponse.fillLight / 100f).coerceIn(-1f, 1f)
                f[2] = if (m.filmResponse.monochrome) 1f else 0f
                val g = m.filmResponse.grayChannels()
                for (i in 0 until 8) f[3 + i] = (g[i] / 100f).coerceIn(-1f, 1f)
            },
            detailTexture        = (m.texture / 100f).coerceIn(-1f, 1f),
            detailFilmGrain      = m.filmGrain.coerceIn(0f, 1f),
            detailFilmGrainSize  = m.filmGrainSize.coerceIn(0f, 1f),
            detailFilmGrainWash  = m.filmGrainWashOut.coerceIn(0f, 1f),
            detailSmoothBackground = m.smoothBackground.coerceIn(0f, 1f),
            // ── Bokeh (GL real-time) — normalise UI ranges to [0..1] ──
            bokehBlur   = (m.bokehBlur  / 50f).coerceIn(0f, 1f),  // UI 0..50 → full strength
            bokehBalls  = (m.bokehBalls / 50f).coerceIn(0f, 1f),  // UI 0..50 match blur
            bokehSpread = m.bokehSpread.coerceIn(0f, 1f),
            // ── PREQ-Port mappings ─────────────────────────────────────────
            detailGrainRoughness = (m.grainRoughness / 100f).coerceIn(0f, 1f),
            detailSharpenMask    = (m.sharpenMask    / 100f).coerceIn(0f, 1f),
            colorDensity         = (m.colorDensity   / 100f).coerceIn(-1f, 1f),
            skintoneWarm         = (m.skintoneWarm   / 100f).coerceIn(-0.5f, 0.5f),
            skintoneSmooth       = (m.skintoneSmooth / 100f).coerceIn(0f, 1f),
            skintoneLuma         = (m.skintoneLuma   / 100f).coerceIn(-0.5f, 0.5f),
            // Color grading wheels, packed per ShaderParams.cg's layout. UI stores
            // an offset from neutral; the shader wants 0.5 + offset/100 for the
            // colour channels and a 0-centred value for saturation.
            cg = FloatArray(16).also { w ->
                val wheels = listOf(m.cgShadows, m.cgMidtones, m.cgHighlights, m.cgGlobal)
                wheels.forEachIndexed { k, wh ->
                    w[k * 4 + 0] = 0.5f + (wh.r / 100f).coerceIn(-0.5f, 0.5f)
                    w[k * 4 + 1] = 0.5f + (wh.g / 100f).coerceIn(-0.5f, 0.5f)
                    w[k * 4 + 2] = 0.5f + (wh.b / 100f).coerceIn(-0.5f, 0.5f)
                    w[k * 4 + 3] = (wh.sat / 100f).coerceIn(-1f, 1f)
                }
            },
            centerPop     = (m.centerPop / 100f).coerceIn(-1f, 1f),
            midtoneDetails       = (m.midtoneDetails    / 100f).coerceIn(-1f, 1f),
            highlightRecovery    = (m.highlightRecovery / 100f).coerceIn(0f, 1f),
            pushPull             = m.pushPull.coerceIn(-3f, 3f),
            lutColorDensity      = (m.lutColorDensity    / 100f).coerceIn(-1f, 1f),
            lutSkintoneBalance   = (m.lutSkintoneBalance / 100f).coerceIn(-0.5f, 0.5f),
            aberStrength         = (m.aberStrength     / 100f).coerceIn(0f, 1f),
            aberFringeReduce     = (m.aberFringeReduce / 100f).coerceIn(0f, 1f),
            fxBlurStyle          = m.fxBlurStyle,
            fxGaussBlur          = (m.fxGaussBlur  / 100f).coerceIn(0f, 1f),
            fxDirBlurAmt         = (m.fxDirBlurAmt / 100f).coerceIn(0f, 1f),
            fxDirBlurAngle       = Math.toRadians(m.fxDirBlurAngle.toDouble()).toFloat(),
            fxRadBlurAmt         = (m.fxRadBlurAmt / 100f).coerceIn(0f, 1f),
            fxRadBlurCx          = m.fxRadBlurCx.coerceIn(0f, 1f),
            fxRadBlurCy          = m.fxRadBlurCy.coerceIn(0f, 1f),
            fxZoomBlurAmt        = (m.fxZoomBlurAmt / 100f).coerceIn(0f, 1f),
            fxZoomBlurCx         = m.fxZoomBlurCx.coerceIn(0f, 1f),
            fxZoomBlurCy         = m.fxZoomBlurCy.coerceIn(0f, 1f),
            fxBlurExcludeSubject = if (m.fxBlurExcludeSubject) 1f else 0f,
            fxMist               = (m.fxMist       / 100f).coerceIn(0f, 1f),
            fxMistWarmth         = (m.fxMistWarmth / 100f).coerceIn(-0.5f, 0.5f),
            fxDust               = (m.fxDust     / 100f).coerceIn(0f, 1f),
            fxDustSize           = (m.fxDustSize / 100f).coerceIn(0f, 1f),
            fxVintageStrength    = (m.fxVintageStrength / 100f).coerceIn(0f, 1f),
            fxVintageFade        = (m.fxVintageFade     / 100f).coerceIn(0f, 1f),
            fxVintageVig         = (m.fxVintageVig      / 100f).coerceIn(0f, 1f),
            vintage = VintageFx(
                mistIntensity = (m.fxVintageMistIntensity / 100f).coerceIn(0f, 1f),
                mistScale = m.fxVintageMistScale.coerceAtLeast(1f),
                textureIntensity = (m.fxVintageTextureIntensity / 100f).coerceIn(0f, 1f),
                textureScale = m.fxVintageTextureScale.coerceAtLeast(1f),
            ),
            fxGlowStrength       = (m.fxGlowStrength / 100f).coerceIn(0f, 1f),
            fxGlowSpread         = (m.fxGlowSpread   / 100f).coerceIn(0f, 1f),
            fxGlowWarmth         = (m.fxGlowWarmth   / 100f).coerceIn(-0.5f, 0.5f),
            // Lens flare — the holder already stores final units.
            lensFlareX           = m.lensFlare.x.coerceIn(-1f, 1f),
            lensFlareY           = m.lensFlare.y.coerceIn(-1f, 1f),
            lensFlareBrightness  = m.lensFlare.brightness.coerceIn(0f, 1f),
            lensFlareSize        = m.lensFlare.size.coerceIn(0.1f, 5f),
            lensFlareSpread      = m.lensFlare.spread.coerceIn(0f, 1f),
            lensFlareWarmth      = m.lensFlare.warmth.coerceIn(0f, 1f),
            // Color shift: UI [-100..100] → uv-fraction offset [-0.1..0.1].
            colorShiftRedX       = (m.colorShift.redX   / 1000f).coerceIn(-0.1f, 0.1f),
            colorShiftGreenX     = (m.colorShift.greenX / 1000f).coerceIn(-0.1f, 0.1f),
            colorShiftBlueX      = (m.colorShift.blueX  / 1000f).coerceIn(-0.1f, 0.1f),
            filmicHlProtect      = m.filmicHlProtect.coerceIn(0f, 1f),
            smartColorEnhance    = m.smartColorEnhance.coerceIn(0f, 1f),
        )
    }

    /**
     * Pack one side of the gradient (layer-1 + layer-2) into the 15-float
     * slot layout consumed by the shader & CPU kernel.
     *   [0]  intensity1   [1]  length1    [2]  feather1
     *   [3]  tintR1       [4]  tintG1     [5]  tintB1     [6]  tintLum1
     *   [7]  enable2      [8]  intensity2 [9]  length2    [10] feather2
     *   [11] tintR2       [12] tintG2     [13] tintB2     [14] tintLum2
     *
     * Tint colours are stored as Android Color Ints (sRGB 8-bit). We
     * gamma-decode to linear (pow 2.2) so the shader/CPU kernel can blend
     * in the same domain Stage A wrote (gamma-encoded sRGB; v2 uses pow 2.2
     * here too — see MacroProcessor.decodeTintLinear).
     */
    private fun packGradientSide(
        intensity1: Float, length1: Float, feather1: Float,
        tintColor1: Int,   tintLum1: Float,
        enable2: Boolean,
        intensity2: Float, length2: Float, feather2: Float,
        tintColor2: Int,   tintLum2: Float,
    ): FloatArray {
        val (t1r, t1g, t1b) = decodeTintLinear(tintColor1)
        val (t2r, t2g, t2b) = decodeTintLinear(tintColor2)
        val out = FloatArray(15)
        out[0]  = intensity1
        out[1]  = length1
        out[2]  = feather1
        out[3]  = t1r; out[4] = t1g; out[5] = t1b
        out[6]  = tintLum1
        out[7]  = if (enable2) 1f else 0f
        out[8]  = intensity2
        out[9]  = length2
        out[10] = feather2
        out[11] = t2r; out[12] = t2g; out[13] = t2b
        out[14] = tintLum2
        return out
    }

    private fun decodeTintLinear(colorInt: Int): Triple<Float, Float, Float> {
        val r = ((colorInt shr 16) and 0xFF) / 255f
        val g = ((colorInt shr 8)  and 0xFF) / 255f
        val b = ( colorInt         and 0xFF) / 255f
        return Triple(
            Math.pow(r.toDouble(), 2.2).toFloat(),
            Math.pow(g.toDouble(), 2.2).toFloat(),
            Math.pow(b.toDouble(), 2.2).toFloat(),
        )
    }

    /**
     * The LUT layer the preview should upload right now. v3's shader has
     * one LUT sampler; we pick the most recent (top-of-stack) visible
     * layer, then fall back to the currently-editing macro's lutCubeUri
     * if nothing is committed yet. Returns null when no LUT is wired.
     */
    fun effectiveLutLayer(m: UserMacro): com.RAZStudio.StudioRoom
        .feature.photo_editor.raw.model.LutLayer? {
        val top = m.lutStack.lastOrNull()
        if (top != null && top.cubeUri.isNotEmpty()) return top
        if (m.lutCubeUri.isNotEmpty()) {
            return com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.LutLayer(
                cubeUri = m.lutCubeUri,
                intensity = m.lutIntensity,
            )
        }
        return null
    }

    /** Convenience: composeMacro + effectiveLutLayer in one call so the
     *  editor doesn't recompose twice per slider tick. */
    fun effectiveLutLayer(actions: List<RawAction>):
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.LutLayer? =
        effectiveLutLayer(composeMacro(actions))
}
