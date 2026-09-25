/*
 * EffectRegistry — Phase-4 single source of truth for scalar effects.
 *
 * Problem solved: adding a new float/int effect previously required touching
 * 4 separate files (UserMacro, mapMacroToShaderParams, writeMacro, macroFromMap).
 * This registry declares each effect once and drives both:
 *   • Preset serialization  — EffectRegistry.writeAll / readAll  (LIVE)
 *   • Stage B divergence    — entries with supportsStageB=false are meant to be
 *                             checked via [anyDiverging]. NOTE: the consumer for
 *                             that check (RendererCore) was deleted 2026-08-27 as
 *                             dead, unsafe scaffolding, so [anyDiverging] has no
 *                             production caller today. The serialization half is
 *                             live (RawActionSerializer) — do not delete this file.
 *
 * Non-goals (not replaced):
 *   • ShaderParams data class layout — keeps its named fields; no reflection magic
 *   • mapMacroToShaderParams — still hand-written for complex effects (CG wheels,
 *     HSL arrays, curves, gradients) that require multi-field packing
 *   • Spatial/native-only params — NR, sharpness, CLAHE, etc. skip the registry
 *     because they never go through GL or the bake path
 *
 * Usage pattern — serializer:
 *   writeMacro calls EffectRegistry.writeAll(s, macro) for the scalar block
 *   macroFromMap calls EffectRegistry.readAll(map, builder) for the scalar block
 *
 * Usage pattern — divergence check:
 *   EffectRegistry.anyDiverging(shaderParams) — currently unused (its intended
 *   caller, RendererCore, was deleted as dead code; see the header note).
 */
package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import org.xmlpull.v1.XmlSerializer

// ── Effect definition ────────────────────────────────────────────────────────

/**
 * Descriptor for a single scalar (Float) effect that flows from
 * [UserMacro] → serializer → [ShaderParams].
 *
 * @param key          XML element name used in the preset XML. Must be stable
 *                     across versions (rename = schema break).
 * @param default      Default value when the key is absent (v1 presets, new
 *                     effects added after a user's existing presets were saved).
 * @param get          Read the raw UI-range value from [UserMacro].
 * @param set          Return a copy of [UserMacro] with this field set to [v].
 * @param supportsStageB  True (default) = Stage B CPU kernel handles this effect.
 *                     False = effect lives only in the GL shader; any non-default
 *                     value is what [anyDiverging] reports (see header — that
 *                     check has no production caller since RendererCore was cut).
 * @param isActive     Returns true when the value is meaningfully non-default
 *                     (used for the diverge check). Defaults to `v != default`.
 */
data class ScalarEffectDef(
    val key: String,
    val default: Float = 0f,
    val get: (UserMacro) -> Float,
    val set: (UserMacro, Float) -> UserMacro,
    val supportsStageB: Boolean = true,
    val isActive: (Float) -> Boolean = { v -> v != 0f },
)

// ── Registry ─────────────────────────────────────────────────────────────────

/**
 * Complete list of scalar effects that are:
 *  1. Serialized/deserialized by [RawActionSerializer] via [writeAll]/[readAll]
 *  2. Checked for Stage B divergence by [anyDiverging]
 *
 * Ordered to match the XML write order (append-only — never reorder existing
 * entries; the key is the stable identity, not the position).
 *
 * When adding a new effect:
 *  1. Add the field to [UserMacro]
 *  2. Wire it in [RawV3ActionReplay.mapMacroToShaderParams]
 *  3. Add one [ScalarEffectDef] here
 *  Done. Serialization and divergence checking are automatic.
 */
object EffectRegistry {

    val effects: List<ScalarEffectDef> = listOf(

        // ── Light tab ─────────────────────────────────────────────────────────
        ScalarEffectDef("filmRolloff",
            get = { it.filmRolloff },
            set = { m, v -> m.copy(filmRolloff = v) },
        ),
        // Pro-Mist / cinematic bloom (Effects tab). Defaults match UserMacro.
        ScalarEffectDef("mistTightness", default = 55f,
            get = { it.mistTightness },
            set = { m, v -> m.copy(mistTightness = v) },
            isActive = { it != 55f },
        ),
        ScalarEffectDef("mistHalation",
            get = { it.mistHalation },
            set = { m, v -> m.copy(mistHalation = v) },
        ),
        ScalarEffectDef("opticalSpread",
            get = { it.opticalSpread() },
            set = { m, v -> m.withOptical(spread = v) },
        ),
        ScalarEffectDef("opticalHalation",
            get = { it.opticalHalation() },
            set = { m, v -> m.withOptical(halation = v) },
        ),
        ScalarEffectDef("opticalDirection",
            get = { it.opticalDirection() },
            set = { m, v -> m.withOptical(direction = v) },
        ),
        // Chromatic aberration add (Lens / FX). UI 0..100.
        ScalarEffectDef("aberStrength",
            get = { it.aberStrength },
            set = { m, v -> m.copy(aberStrength = v) },
        ),
        ScalarEffectDef("aberFringeReduce",
            get = { it.aberFringeReduce },
            set = { m, v -> m.copy(aberFringeReduce = v) },
        ),

        // ── Color tab ─────────────────────────────────────────────────────────
        ScalarEffectDef("colorDensity",
            get = { it.colorDensity },
            set = { m, v -> m.copy(colorDensity = v) },
            isActive = { it != 0f },
        ),
        ScalarEffectDef("skintoneWarm",
            get = { it.skintoneWarm },
            set = { m, v -> m.copy(skintoneWarm = v) },
        ),
        ScalarEffectDef("skintoneSmooth",
            get = { it.skintoneSmooth },
            set = { m, v -> m.copy(skintoneSmooth = v) },
        ),
        ScalarEffectDef("skintoneLuma",
            get = { it.skintoneLuma },
            set = { m, v -> m.copy(skintoneLuma = v) },
        ),

        // Color Grading wheels — stored as UI offset from neutral (0 = no tint)
        // ShaderParams maps them to 0.5 + offset/100 in mapMacroToShaderParams
        // Effect NAMES are the serialization keys (sidecar/preset/action-replay)
        // and must stay stable; only the accessors route through the nested
        // ColorWheel holders now (see UserMacro.cgShadows/Midtones/Highlights).
        ScalarEffectDef("cgShadowsR",   get = { it.cgShadows.r },   set = { m, v -> m.copy(cgShadows = m.cgShadows.copy(r = v)) }),
        ScalarEffectDef("cgShadowsG",   get = { it.cgShadows.g },   set = { m, v -> m.copy(cgShadows = m.cgShadows.copy(g = v)) }),
        ScalarEffectDef("cgShadowsB",   get = { it.cgShadows.b },   set = { m, v -> m.copy(cgShadows = m.cgShadows.copy(b = v)) }),
        ScalarEffectDef("cgShadowsSat", get = { it.cgShadows.sat }, set = { m, v -> m.copy(cgShadows = m.cgShadows.copy(sat = v)) }),
        ScalarEffectDef("cgMidtonesR",   get = { it.cgMidtones.r },   set = { m, v -> m.copy(cgMidtones = m.cgMidtones.copy(r = v)) }),
        ScalarEffectDef("cgMidtonesG",   get = { it.cgMidtones.g },   set = { m, v -> m.copy(cgMidtones = m.cgMidtones.copy(g = v)) }),
        ScalarEffectDef("cgMidtonesB",   get = { it.cgMidtones.b },   set = { m, v -> m.copy(cgMidtones = m.cgMidtones.copy(b = v)) }),
        ScalarEffectDef("cgMidtonesSat", get = { it.cgMidtones.sat }, set = { m, v -> m.copy(cgMidtones = m.cgMidtones.copy(sat = v)) }),
        ScalarEffectDef("cgHighlightsR",   get = { it.cgHighlights.r },   set = { m, v -> m.copy(cgHighlights = m.cgHighlights.copy(r = v)) }),
        ScalarEffectDef("cgHighlightsG",   get = { it.cgHighlights.g },   set = { m, v -> m.copy(cgHighlights = m.cgHighlights.copy(g = v)) }),
        ScalarEffectDef("cgHighlightsB",   get = { it.cgHighlights.b },   set = { m, v -> m.copy(cgHighlights = m.cgHighlights.copy(b = v)) }),
        ScalarEffectDef("cgHighlightsSat", get = { it.cgHighlights.sat }, set = { m, v -> m.copy(cgHighlights = m.cgHighlights.copy(sat = v)) }),
        // Global/Offset wheel (nested holder).
        ScalarEffectDef("cgGlobalR",   get = { it.cgGlobal.r },   set = { m, v -> m.copy(cgGlobal = m.cgGlobal.copy(r = v)) }),
        ScalarEffectDef("cgGlobalG",   get = { it.cgGlobal.g },   set = { m, v -> m.copy(cgGlobal = m.cgGlobal.copy(g = v)) }),
        ScalarEffectDef("cgGlobalB",   get = { it.cgGlobal.b },   set = { m, v -> m.copy(cgGlobal = m.cgGlobal.copy(b = v)) }),
        ScalarEffectDef("cgGlobalSat", get = { it.cgGlobal.sat }, set = { m, v -> m.copy(cgGlobal = m.cgGlobal.copy(sat = v)) }),

        // ── Light tab — Center Pop ────────────────────────────────────────────
        ScalarEffectDef("centerPop",
            get = { it.centerPop },
            set = { m, v -> m.copy(centerPop = v) },
            isActive = { it != 0f },
        ),

        // ── Detail tab ────────────────────────────────────────────────────────
        ScalarEffectDef("grainRoughness",
            get = { it.grainRoughness },
            set = { m, v -> m.copy(grainRoughness = v) },
        ),

        // ── LUT tab ───────────────────────────────────────────────────────────
        ScalarEffectDef("pushPull",
            get = { it.pushPull },
            set = { m, v -> m.copy(pushPull = v) },
            isActive = { it != 0f },
        ),

        // ── Gamut tab / final pass ────────────────────────────────────────────
        ScalarEffectDef("gamutCompress",
            get = { it.gamutCompress },
            set = { m, v -> m.copy(gamutCompress = v) },
        ),

        // ── Effects tab — GL-only multi-pass FBO effects ──────────────────────
        // These require image-wide passes (blur, convolution, compositing) that
        // cannot run in a single-pixel CPU kernel. supportsStageB=false so the
        // diverge guard (Task 8.1) blocks a graded bake whenever any of
        // these is active — renderer stays on ungraded source + live GL grading.
        ScalarEffectDef("fxGaussBlur",      supportsStageB = false,
            get = { it.fxGaussBlur },       set = { m, v -> m.copy(fxGaussBlur = v) }),
        ScalarEffectDef("fxDirBlurAmt",     supportsStageB = false,
            get = { it.fxDirBlurAmt },      set = { m, v -> m.copy(fxDirBlurAmt = v) }),
        ScalarEffectDef("fxRadBlurAmt",     supportsStageB = false,
            get = { it.fxRadBlurAmt },      set = { m, v -> m.copy(fxRadBlurAmt = v) }),
        ScalarEffectDef("fxZoomBlurAmt",    supportsStageB = false,
            get = { it.fxZoomBlurAmt },     set = { m, v -> m.copy(fxZoomBlurAmt = v) }),
        ScalarEffectDef("fxMist",           supportsStageB = false,
            get = { it.fxMist },            set = { m, v -> m.copy(fxMist = v) }),
        ScalarEffectDef("fxDust",           supportsStageB = false,
            get = { it.fxDust },            set = { m, v -> m.copy(fxDust = v) }),
        ScalarEffectDef("fxVintageStrength", supportsStageB = false,
            get = { it.fxVintageStrength }, set = { m, v -> m.copy(fxVintageStrength = v) }),
        ScalarEffectDef("fxGlowStrength",   supportsStageB = false,
            get = { it.fxGlowStrength },    set = { m, v -> m.copy(fxGlowStrength = v) }),
        // Lens flare — procedural additive light (holder-backed).
        ScalarEffectDef("lensFlareX",          supportsStageB = false,
            get = { it.lensFlare.x },          set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(x = v)) }),
        ScalarEffectDef("lensFlareY",          supportsStageB = false,
            get = { it.lensFlare.y },          set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(y = v)) }),
        ScalarEffectDef("lensFlareBrightness", supportsStageB = false,
            get = { it.lensFlare.brightness }, set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(brightness = v)) }),
        ScalarEffectDef("lensFlareSize",       supportsStageB = false,
            get = { it.lensFlare.size },       set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(size = v)) }),
        ScalarEffectDef("lensFlareSpread",     supportsStageB = false,
            get = { it.lensFlare.spread },     set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(spread = v)) }),
        ScalarEffectDef("lensFlareWarmth",     supportsStageB = false,
            get = { it.lensFlare.warmth },     set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(warmth = v)) }),
        ScalarEffectDef("lensFlareDistance",   supportsStageB = false,
            get = { it.lensFlare.distance },   set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(distance = v)) },
            isActive = { it != 1f }),
        ScalarEffectDef("lensFlareHood",       supportsStageB = false,
            get = { it.lensFlare.hood },       set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(hood = v)) }),
        ScalarEffectDef("lensFlareStarburst",  supportsStageB = false,
            get = { it.lensFlare.starburst },  set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(starburst = v)) }),
        ScalarEffectDef("lensFlareBlades",     supportsStageB = false,
            get = { it.lensFlare.blades },     set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(blades = v)) }),
        ScalarEffectDef("lensFlareRotation",   supportsStageB = false,
            get = { it.lensFlare.rotation },   set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(rotation = v)) }),
        ScalarEffectDef("lensFlareRoundness",  supportsStageB = false,
            get = { it.lensFlare.roundness },  set = { m, v -> m.copy(lensFlare = m.lensFlare.copy(roundness = v)) },
            isActive = { it != 1f }),
        // ColorShift RGB split (holder-backed, horizontal).
        ScalarEffectDef("colorShiftRedX",      supportsStageB = false,
            get = { it.colorShift.redX },      set = { m, v -> m.copy(colorShift = m.colorShift.copy(redX = v)) }),
        ScalarEffectDef("colorShiftGreenX",    supportsStageB = false,
            get = { it.colorShift.greenX },    set = { m, v -> m.copy(colorShift = m.colorShift.copy(greenX = v)) }),
        ScalarEffectDef("colorShiftBlueX",     supportsStageB = false,
            get = { it.colorShift.blueX },     set = { m, v -> m.copy(colorShift = m.colorShift.copy(blueX = v)) }),
        // Orton/bloom — multi-pass screen blend, GL-only.
        ScalarEffectDef("ortonStrength",    supportsStageB = false,
            get = { it.ortonStrength },     set = { m, v -> m.copy(ortonStrength = v) }),
        // Bokeh — bilateral blur + subject-mask gated, GL-only.
        ScalarEffectDef("bokehBlur",        supportsStageB = false,
            get = { it.bokehBlur.toFloat() }, set = { m, v -> m.copy(bokehBlur = v.toInt()) },
            isActive = { it > 0f }),
        ScalarEffectDef("bokehBalls",       supportsStageB = false,
            get = { it.bokehBalls.toFloat() }, set = { m, v -> m.copy(bokehBalls = v.toInt()) },
            isActive = { it > 0f }),
    )

    // Fast lookup by key — used by readAll
    private val byKey: Map<String, ScalarEffectDef> = effects.associateBy { it.key }

    // ── Serialization helpers ─────────────────────────────────────────────────

    /**
     * Write all registered scalar effects as XML child elements.
     * Call from [RawActionSerializer.writeMacro] instead of individual `t()` calls.
     */
    fun writeAll(s: XmlSerializer, macro: UserMacro, ns: String? = null) {
        for (e in effects) {
            val v = e.get(macro)
            s.startTag(ns, e.key)
            s.text(v.toString())
            s.endTag(ns, e.key)
        }
    }

    /**
     * Read all registered scalar effects from the parsed XML map and return an
     * updated [UserMacro]. Keys absent in [map] fall back to [ScalarEffectDef.default].
     * Call from [RawActionSerializer.macroFromMap] after constructing the base macro.
     */
    fun readAll(map: Map<String, String>, macro: UserMacro): UserMacro {
        var m = macro
        for ((key, def) in byKey) {
            val v = map[key]?.toFloatOrNull() ?: def.default
            m = def.set(m, v)
        }
        return m
    }

    // ── Divergence check ─────────────────────────────────────────────────────

    /**
     * Returns true if any registered effect with [supportsStageB]=false is
     * currently active in [macro]. A true result means the graded bake would
     * produce pixels different from the GL shader — the pipeline should stay on
     * ungraded until all active effects can run in Stage B.
     *
     * Currently always returns false (all Phase-1 effects are in apply_macro.cpp).
     * This is the hook for future GL-only effects: add them with supportsStageB=false
     * and the bake guard activates automatically.
     */
    fun anyDiverging(macro: UserMacro): Boolean =
        effects.any { !it.supportsStageB && it.isActive(it.get(macro)) }
}
