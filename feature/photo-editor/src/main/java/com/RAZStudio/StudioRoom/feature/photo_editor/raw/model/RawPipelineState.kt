/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

import android.graphics.Bitmap
import android.net.Uri
import android.graphics.Color as AndroidColor

/** Emitted on the StateFlow observed by the workspace UI. */
sealed interface RawPipelineState {

    /** No file opened yet. */
    data object Idle : RawPipelineState

    /**
     * The user has picked a RAW file but has not yet confirmed the working-buffer
     * configuration. The UI shows the workspace selector dialog. No pipeline work
     * has started; canceling here returns to [Idle] without opening any file.
     *
     * [uri] is the picked file. [suggested] is the persisted selection from
     * `SharedPreferences("raw_workspace_prefs")`, pre-filled in the dialog.
     */
    data class AwaitingWorkspaceChoice(
        val uri: Uri,
        val suggested: WorkspaceConfig,
    ) : RawPipelineState

    /**
     * Preview pipeline is running. [stage] is 1-based (1=decode, 2=lens, 3=cube, 4=render).
     * [progress] is 0f–1f within that stage.
     */
    data class PreviewLoading(
        val stage: Int,
        val progress: Float,
        val stageName: String,
    ) : RawPipelineState

    /**
     * Preview is ready. The UI should render [previewBitmap] at workspace resolution.
     *
     * [previewBitmap] is sRGB, sized to [DeviceCapabilityDetector]-computed resolution.
     * [wideCubeFile] is the path to the ProPhoto-RGB .cube cache for full-res replay.
     * [metadata] carries all non-LUT metadata for edit-exif writing.
     * [userMacro] is the serialized form of all current user adjustments — the full-res
     * pipeline will replay this macro without any user interaction.
     */
    data class PreviewReady(
        val previewBitmap: Bitmap,
        val wideCubeFile: String,
        val metadata: RawMetadata,
        val userMacro: UserMacro,
    ) : RawPipelineState

    /**
     * Full-res pipeline is processing silently in the background.
     * The UI never observes this unless it explicitly subscribes to the full-res channel.
     * Exposed here for debug/dev builds only.
     */
    data class FullResProcessing(
        val progress: Float,
    ) : RawPipelineState

    /**
     * Full-res output is ready. Absolute path to the rendered file.
     * Written silently to the app's private cache; the edit-exif module will
     * pick it up and attach the preserved [metadata].
     */
    data class FullResReady(
        val outputPath: String,
        val metadata: RawMetadata,
    ) : RawPipelineState

    /** Unrecoverable error in either pipeline. */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : RawPipelineState
}

/** How the gradient tint colour is blended — Solid = cinematic light-leak
 *  (screen + soft add); Fused = detail-preserving overlay. */
enum class RawGradientBlendMode { Solid, Fused }

/**
 * A serialisable snapshot of all user adjustments at a given moment.
 * Both pipelines replay the same macro to guarantee pixel-identical output.
 *
 * [lutCubeUri] is the currently selected LUT preset URI or empty if none.
 * [lutIntensity] is the slider value in [0, 1].
 * [contrastBoost] is the S-curve strength in [0, 1] for subject-pop (mask required).
 */
/**
 * A single committed LUT layer in the action stack. Multiple LUT actions stack
 * in order — the pipeline applies them sequentially, top-to-bottom in action
 * stack order, so later layers blend on top of earlier ones.
 */
data class LutLayer(
    val cubeUri: String,
    val intensity: Float,
)

/**
 * Per-mask tone-region adjustments (mirror the global Tone tab), applied via
 * applyToneRegionsP gated by the mask selection. Kept in a nested holder so
 * [UserMacro] gains a single constructor parameter: UserMacro sits at the
 * Kotlin data-class synthetic-constructor (copy$default) size ceiling, and
 * adding four flat mask-tone fields made the dex verifier reject every caller
 * of UserMacro.copy (VerifyError: "expected N argument registers…"). All in
 * [-100, 100]; 0 = no change.
 */
data class MaskToneRegions(
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
)

/**
 * One color-grading wheel. RGB stored as UI offset from neutral (0 = no tint,
 * UI range [-50..+50] → /100), [sat] the wheel strength ([-100..+100] → /100).
 * Nested in a holder so [UserMacro] gains a single constructor parameter for the
 * 4th (Global/Offset) wheel — UserMacro is at the data-class copy() size ceiling
 * (see [MaskToneRegions]). The 3 original wheels stay as flat cg* fields.
 */
/**
 * The six INTERMEDIATE HSL anchors -- Yellow-Green, Spring Green, Sky Blue,
 * Purple, Magenta, Pink -- that sit between the six base ones ([UserMacro]'s
 * flat hslRed../hslBlue.. fields). Together the twelve are a superset of
 * Adobe's eight bands.
 *
 * Nested rather than flat for one hard reason: eighteen flat floats cost
 * eighteen argument registers in UserMacro's synthetic `copy$default`, and that
 * method is within a handful of registers of the dex range-invoke argument
 * count byte wrapping at 256 -- which ART reports as a VerifyError that crashes the
 * editor on load. The build guard in photo-editor/build.gradle.kts fails at
 * 250. Folding these eighteen into one holder bought back seventeen.
 *
 * Persisted JSON/sidecar KEYS keep their original flat names ("hslPurpleHue"
 * and friends) so sidecars written before this refactor still load.
 */
data class HslExtended(
    val yellowGreenHue: Float = 0f, val yellowGreenSat: Float = 0f, val yellowGreenLum: Float = 0f,
    val springGreenHue: Float = 0f, val springGreenSat: Float = 0f, val springGreenLum: Float = 0f,
    val skyBlueHue: Float = 0f, val skyBlueSat: Float = 0f, val skyBlueLum: Float = 0f,
    val purpleHue: Float = 0f, val purpleSat: Float = 0f, val purpleLum: Float = 0f,
    val magentaHue: Float = 0f, val magentaSat: Float = 0f, val magentaLum: Float = 0f,
    val pinkHue: Float = 0f, val pinkSat: Float = 0f, val pinkLum: Float = 0f,
) {
    /** Delta-wins merge, mirroring [ColorWheel.mergeWheel]. */
    fun mergeHslExt(d: HslExtended): HslExtended = HslExtended(
        yellowGreenHue = if (d.yellowGreenHue != 0f) d.yellowGreenHue else yellowGreenHue,
        yellowGreenSat = if (d.yellowGreenSat != 0f) d.yellowGreenSat else yellowGreenSat,
        yellowGreenLum = if (d.yellowGreenLum != 0f) d.yellowGreenLum else yellowGreenLum,
        springGreenHue = if (d.springGreenHue != 0f) d.springGreenHue else springGreenHue,
        springGreenSat = if (d.springGreenSat != 0f) d.springGreenSat else springGreenSat,
        springGreenLum = if (d.springGreenLum != 0f) d.springGreenLum else springGreenLum,
        skyBlueHue = if (d.skyBlueHue != 0f) d.skyBlueHue else skyBlueHue,
        skyBlueSat = if (d.skyBlueSat != 0f) d.skyBlueSat else skyBlueSat,
        skyBlueLum = if (d.skyBlueLum != 0f) d.skyBlueLum else skyBlueLum,
        purpleHue = if (d.purpleHue != 0f) d.purpleHue else purpleHue,
        purpleSat = if (d.purpleSat != 0f) d.purpleSat else purpleSat,
        purpleLum = if (d.purpleLum != 0f) d.purpleLum else purpleLum,
        magentaHue = if (d.magentaHue != 0f) d.magentaHue else magentaHue,
        magentaSat = if (d.magentaSat != 0f) d.magentaSat else magentaSat,
        magentaLum = if (d.magentaLum != 0f) d.magentaLum else magentaLum,
        pinkHue = if (d.pinkHue != 0f) d.pinkHue else pinkHue,
        pinkSat = if (d.pinkSat != 0f) d.pinkSat else pinkSat,
        pinkLum = if (d.pinkLum != 0f) d.pinkLum else pinkLum,
    )
}

data class ColorWheel(
    val r: Float = 0f,
    val g: Float = 0f,
    val b: Float = 0f,
    val sat: Float = 0f,
) {
    /**
     * Per-channel merge used by [UserMacro.mergeWith]: each channel takes the
     * delta's value when it is non-zero, else keeps this wheel's value. Mirrors
     * the field-by-field "latest non-zero wins" merge used for the other macro
     * fields so wheel edits survive the auto-apply card round-trip.
     */
    fun mergeWheel(delta: ColorWheel): ColorWheel = ColorWheel(
        r   = if (delta.r   != 0f) delta.r   else r,
        g   = if (delta.g   != 0f) delta.g   else g,
        b   = if (delta.b   != 0f) delta.b   else b,
        sat = if (delta.sat != 0f) delta.sat else sat,
    )
}

/**
 * Adobe legacy response controls — `Recovery` and `FillLight` (the "process
 * 2003" pair) — plus the 8-channel black-&-white `GrayMixer`.
 *
 * Nested rather than flat because [UserMacro] sits at the dex arg-register
 * ceiling; eleven flat fields here would trip the build guard in
 * photo-editor/build.gradle.kts (see [MaskToneRegions] / [ColorWheel]).
 *
 * **Recovery and FillLight are BIPOLAR here where Adobe's run 0..100.** Adobe
 * only ever pulls highlights down / lifts shadows. The positive half is that
 * behaviour verbatim, so an imported preset maps 1:1; the negative half (push
 * highlights up, deepen shadows) is the reverse, which is what turns the same
 * two controls into a "high contrast film" end opposite the "moody faded
 * negative" end. Presets never write the negative half.
 *
 * [monochrome] is Adobe's `ConvertToGrayscale`. The eight gray* values have NO
 * effect while it is false — Adobe's behaviour, not an oversight: the mixer
 * decides how each hue maps to grey, which only means anything once colour is
 * being discarded.
 *
 * Band centres follow Adobe's NON-uniform hue layout (0/30/60/120/180/240/285/
 * 330 degrees), not the uniform 45-degree anchors [UserMacro.hslFull] uses. The
 * two models disagree on purpose and must not be conflated.
 */
data class FilmResponse(
    /** -100..+100. >0 pulls highlights down (Adobe `Recovery`), <0 pushes up. */
    val recovery: Float = 0f,
    /** -100..+100. >0 lifts shadows (Adobe `FillLight`), <0 deepens them. */
    val fillLight: Float = 0f,
    /** Adobe `ConvertToGrayscale`. Gates the eight gray* channels below. */
    val monochrome: Boolean = false,
    val grayRed: Float = 0f,
    val grayOrange: Float = 0f,
    val grayYellow: Float = 0f,
    val grayGreen: Float = 0f,
    val grayAqua: Float = 0f,
    val grayBlue: Float = 0f,
    val grayPurple: Float = 0f,
    val grayMagenta: Float = 0f,
    /** -100..+100. OKLCh chroma spread. Nested here so UserMacro gains no constructor param. */
    val separation: Float = 0f,
) {
    /** True when this holder would change nothing, so callers can skip work. */
    val isNeutral: Boolean
        get() = recovery == 0f && fillLight == 0f && !monochrome && separation == 0f

    /** The eight mixer channels in Adobe band order. */
    fun grayChannels(): FloatArray = floatArrayOf(
        grayRed, grayOrange, grayYellow, grayGreen,
        grayAqua, grayBlue, grayPurple, grayMagenta,
    )

    /** Delta-wins merge, mirroring [ColorWheel.mergeWheel]. */
    fun mergeFilm(delta: FilmResponse): FilmResponse = FilmResponse(
        recovery    = if (delta.recovery    != 0f) delta.recovery    else recovery,
        fillLight   = if (delta.fillLight   != 0f) delta.fillLight   else fillLight,
        // OR, never delta-wins: a false delta must not clear a true flag. That
        // is exactly what made Bloom's protect-subject checkbox inert.
        monochrome  = monochrome || delta.monochrome,
        grayRed     = if (delta.grayRed     != 0f) delta.grayRed     else grayRed,
        grayOrange  = if (delta.grayOrange  != 0f) delta.grayOrange  else grayOrange,
        grayYellow  = if (delta.grayYellow  != 0f) delta.grayYellow  else grayYellow,
        grayGreen   = if (delta.grayGreen   != 0f) delta.grayGreen   else grayGreen,
        grayAqua    = if (delta.grayAqua    != 0f) delta.grayAqua    else grayAqua,
        grayBlue    = if (delta.grayBlue    != 0f) delta.grayBlue    else grayBlue,
        grayPurple  = if (delta.grayPurple  != 0f) delta.grayPurple  else grayPurple,
        grayMagenta = if (delta.grayMagenta != 0f) delta.grayMagenta else grayMagenta,
        separation  = if (delta.separation  != 0f) delta.separation  else separation,
    )
}

/**
 * Parametric film curve (Curves tab) — sigmoid contrast + highlight knee +
 * shadow toe, baked into the tone-curve 1D LUT under the freehand points.
 * Nested so [UserMacro] spends one constructor param (dex register ceiling).
 *
 * UI ranges are 0..100 for amounts; [pivot] is 0..1. Distinct from
 * [UserMacro.filmRolloff] (Effects / LUT finishing, headroom-aware shoulder
 * after Whites) and from [FilmResponse] Recovery/FillLight.
 */
data class FilmCurve(
    /** Sigmoid S-curve amount [0..100]. 0 = identity. */
    val contrast: Float = 0f,
    /** Midtone pivot for the S-curve [0..1]. Default ~display mid-gray. */
    val pivot: Float = 0.45f,
    /** Highlight power-knee / shoulder [0..100]. 0 = identity. */
    val highlightKnee: Float = 0f,
    /** Shadow toe: soft black lift / fade [0..100]. 0 = identity. */
    val shadowToe: Float = 0f,
) {
    val isNeutral: Boolean
        get() = contrast == 0f && highlightKnee == 0f && shadowToe == 0f
                && pivot == 0.45f

    /** True when any parametric amount is active (pivot alone is not). */
    val isActive: Boolean
        get() = contrast > 0f || highlightKnee > 0f || shadowToe > 0f

    fun mergeFilmCurve(delta: FilmCurve): FilmCurve = FilmCurve(
        contrast      = if (delta.contrast      != 0f) delta.contrast      else contrast,
        pivot         = if (delta.contrast != 0f || delta.highlightKnee != 0f
            || delta.shadowToe != 0f || delta.pivot != 0.45f) delta.pivot else pivot,
        highlightKnee = if (delta.highlightKnee != 0f) delta.highlightKnee else highlightKnee,
        shadowToe     = if (delta.shadowToe     != 0f) delta.shadowToe     else shadowToe,
    )

    companion object {
        val FilmSCurve = FilmCurve(contrast = 55f, pivot = 0.45f, highlightKnee = 25f, shadowToe = 0f)
        val SoftContrast = FilmCurve(contrast = 30f, pivot = 0.50f, highlightKnee = 15f, shadowToe = 5f)
        val HighContrast = FilmCurve(contrast = 75f, pivot = 0.40f, highlightKnee = 20f, shadowToe = 0f)
        val CinematicRolloff = FilmCurve(contrast = 40f, pivot = 0.42f, highlightKnee = 70f, shadowToe = 10f)
    }
}

/**
 * Phase 1 cast shadow. One constructor param on UserMacro.
 * UI units are 0..100. Strength 0 skips the pass.
 */
data class SceneShadow(
    /** 0 = far, long shadow. 100 = near, short shadow. */
    val distance: Float = 50f,
    /** 0 skips offset, blur, and composite. */
    val strength: Float = 0f,
    /** Extra blur on top of the distance term. */
    val softness: Float = 50f,
)

/**
 * OpenShot-style procedural lens flare (ported from libopenshot LensFlare.cpp).
 * Additive core + glow + rings + halo + ghost line along the (x,y)→center axis.
 * Nested holder so [UserMacro] spends one constructor param (dex register
 * ceiling — see [MaskToneRegions]/[ColorWheel]).
 *
 * [x],[y] are the flare source in normalised screen space [-1..+1] (0 = centre,
 * OpenShot default -0.5,-0.5 = upper-left). [brightness] 0..1 gates + scales the
 * whole effect (0 = off). [size] 0.1..5 scales all radii. [spread] 0..1 pushes
 * the ghost reflections along the axis toward the opposite corner.
 */
data class LensFlare(
    val x: Float = -0.5f,
    val y: Float = -0.5f,
    val brightness: Float = 0f,
    val size: Float = 1f,
    val spread: Float = 1f,
    /** Flare colour temperature: 0 = warm-white, 1 = yellow→orange sunset. */
    val warmth: Float = 0f,
    /** 0 = far off-axis interaction, 1 = near. Grading abstraction, not meters. */
    val distance: Float = 1f,
    /** 0 = no hood, 1 = maximum. UI shows 0..100. */
    val hood: Float = 0f,
    /** Spike brightness. 0 hides the starburst. UI shows 0..100. */
    val starburst: Float = 0f,
    /** 0 = circle. 0.25..1 maps to 5..8 blades. */
    val blades: Float = 0f,
    /** Iris rotation. UI shows 0..100. */
    val rotation: Float = 0f,
    /** 1 keeps a circle. UI shows 0..100. */
    val roundness: Float = 1f,
)

/**
 * OpenShot-style ColorShift (RGB split / chromatic aberration look). Ported as
 * a HORIZONTAL per-channel offset only — the CPU export streams rows, so a
 * vertical offset can't sample other rows, whereas a horizontal offset works
 * within the row (and matches GL). Values are UI [-100..+100]; ActionReplay
 * maps to a fraction-of-width uv offset. Nested holder → 1 UserMacro param.
 */
data class ColorShift(
    val redX: Float = 0f,
    val greenX: Float = 0f,
    val blueX: Float = 0f,
)

/** JPEG Refine sliders (0–100). Nested so UserMacro.copy$default stays under the dex ceiling. */
data class JpegRefineMacro(
    val strength: Float = 0f,
    val clean: Float = 50f,
    val detail: Float = 50f,
    val touched: Boolean = false,
)

data class UserMacro(
    // ── 3D LUT — exactly two committed slots ("LUT 1" and "LUT 2"), each applied
    //    once. At render time both are chained into a single cube (the existing
    //    chain pipeline), so the GL/export path is unchanged. ──
    /** Committed LUT slot 1 (cube URI; empty = none) + blend intensity [0,1]. */
    val lut1CubeUri: String = "",
    val lut1Intensity: Float = 1f,
    /** Committed LUT slot 2 (cube URI; empty = none) + blend intensity [0,1]. */
    val lut2CubeUri: String = "",
    val lut2Intensity: Float = 1f,
    /** Transient in-flight LUT edit (drives live preview before Apply). [lutSlot]
     *  (1 or 2) selects which committed slot this edit targets; [lutEdited] marks
     *  that the delta carries a LUT change — a pick, an intensity move, OR a clear
     *  (empty [lutCubeUri]). Non-LUT deltas leave [lutEdited] false so [mergeWith]
     *  doesn't touch the slots. Always cleared on a merged result. */
    val lutCubeUri: String = "",
    val lutIntensity: Float = 1f,
    val lutSlot: Int = 1,
    val lutEdited: Boolean = false,
    // CLAHE (tile-based adaptive histogram equalization with sigmoid shadows/highlights
    // weighting). Baked into the source AHB on toggle/slider release, NOT applied per
    // frame in the shader. See [RawV3Clahe].
    val claheEnabled: Boolean = true,
    // [-1..1]: positive = open/lift via CLAHE; negative = darken shadows /
    // mute highlights.
    val claheShadowsBoost: Float = 0f,
    val claheHighlightsBoost: Float = 0f,
    // JPEG Refine (Dual Reconstruction Lite). UI 0–100. Strength 0 = no-op.
    val jpegRefine: JpegRefineMacro = JpegRefineMacro(),
    // Color Pop strength in [0..1]. UI slider is 0..100. Chroma only.
    val smartColorEnhance: Float = 0f,
    /**
     * Lives next to the CLAHE controls in the UI but routes through the
     * LUT pipeline at slot [200]. Range [-1..+1]:
     *   0  → honest LUT sample, no extra processing.
     *   +1 → saturation-boost the LUT output in highlight territory so
     *        cube LUTs don't desaturate bright tones.
     *   -1 → Reinhard soft-knee the HDR pixel into 0..1 before sampling
     *        (duller — kept as a creative knob).
     */
    val lutHighlightVibrancy: Float = 0f,
    /**
     * Tonal-zone WB trims, ride alongside [lutHighlightVibrancy]. All
     * [-1..+1]; smoothstep-weighted in the shader to highlights/shadows.
     */
    val highlightTemperature: Float = 0f,
    val highlightTint:        Float = 0f,
    val shadowTemperature:    Float = 0f,
    val shadowTint:           Float = 0f,
    // Glamour Glow removed — was an attempted Snapseed port that didn't
    // produce a usable result. Tab + sliders + shader path all gone.

    /** Ambiance -1..+1. Tonemap-tab local-contrast + midtone-sat lift. */
    val ambiance: Float = 0f,
    /**
     * Orton effect [0..1]. Dreamy soft-focus bloom: Gaussian-blurred copy
     * screen-blended over the original, highlight-gated. Placed in the
     * Tonemap tab next to Ambiance (both reuse the bokeh blur infrastructure).
     */
    val ortonStrength: Float = 0f,
    /**
     * Bloom disc-bokeh radius in pixels [0..20]. Drives the Vogel-spiral
     * sample distribution radius in the Orton-bokeh fragment pass. Higher
     * = wider, dreamier bloom shapes; lower = subtle softening. Default 8.
     * Renderer skips the Vogel pass entirely when [ortonStrength] == 0
     * so this field is inert until bloom is enabled.
     */
    val bloomRadius: Float = 8f,
    /**
     * Pro-Mist mip bias UI 0..100 → ShaderParams cinematic[0] /100.
     * Higher = tighter halo (mip1). Default 55.
     */
    val mistTightness: Float = 55f,
    /**
     * Optical halation UI 0..100 → ShaderParams cinematic[1] /100.
     * R/B channel offset when sampling the bloom pyramid.
     */
    val mistHalation: Float = 0f,
    /**
     * Optical Spread grade. UI 0..100 for spread and halation. Direction is
     * 0 Off, 1 Horizontal, 2 Radial. Packed so the data-class constructor
     * stays under the dex register ceiling. Defaults are off.
     */
    val optical: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** Bloom and spread highlight knee. Defaults match the fixed 0.78–0.98 gate. */
    val highlightStart: Float = 0.78f,
    val highlightEnd: Float = 0.98f,
    /**
     * Emulsion grain pack. 0 structure, 1 chroma, 2 highlight suppression,
     * 3 shadow boost, 4 edge bias, 5 seed, 6 cloudiness,
     * 7 shadow curve, 8 mid curve, 9 highlight curve,
     * 10 light influence, 11 shadow response, 12 relight suppression.
     */
    val grainEmulsion: FloatArray = floatArrayOf(
        0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 0f, 1f, 0f,
    ),
    /**
     * Last Pro-Mist preset chip (0=Off/manual … 4=1/2). UI only; native
     * reads [mistTightness]/[mistHalation]/orton/glow.
     */
    val cinematicMistTier: Int = 0,
    /**
     * Bloom anamorphic ratio [0.4..1.6]. 1.0 = perfect circles; <1 squashes
     * the discs vertically (cinematic anamorphic ovals); >1 stretches them
     * horizontally. Default 1.0 (circular). Inert until bloom is enabled.
     */
    val bloomShape: Float = 1f,
    /**
     * Exclude the U2Net-detected subject region from the bloom pass. When
     * true, bloom is gated to bg-only (subject stays glow-free); when
     * false, bloom applies to the entire frame. Defaults to false so
     * existing behaviour is preserved. Inert unless a subject mask is
     * actually available for the photo.
     */
    /**
     * When true, the subject gets its OWN bloom strength via
     * [subjectBloom] instead of receiving the same bloom as the
     * background. When false (default), bloom is uniform across the
     * frame.
     */
    val bloomExcludeSubject: Boolean = false,
    /**
     * Independent bloom strength applied to the subject region when
     * [bloomExcludeSubject] is true. 0..1, default 0 (subject stays
     * fully clean). Independent from the global bloom (`ortonStrength`)
     * so the user can have strong bg bloom + zero subject bloom for
     * separation looks.
     */
    val subjectBloom: Float = 0f,
    /**
     * Subject Pop — a subject-masked tonal grade applied as a final CPU pass
     * via the native fusion tone-mapper (U2Net→SAM mask). When enabled, the
     * subject receives the full shadow/highlight/saturation adjustment while
     * the background is only lightly touched (background floor), giving a
     * crisp "pop" with no tonal bleeding across the boundary. Inert unless a
     * subject mask is available for the photo. Default off.
     */
    val subjectPopEnabled: Boolean = false,
    /** Subject Pop shadow lift (+) / crush (−), [-1..1], default 0. */
    val subjectPopShadow: Float = 0f,
    /** Subject Pop highlight lift (+) / pull (−), [-1..1], default 0. */
    val subjectPopHighlight: Float = 0f,
    /** Subject Pop saturation gain (+) / desat (−), [-1..1], default 0. */
    val subjectPopSaturation: Float = 0f,
    /**
     * Film-style highlight shoulder rolloff [0..1]. 0 = linear clip
     * (current behaviour for high-key scenes), 1 = aggressive rolloff
     * (gentle compression that mimics analog film negative inversion).
     * Applied as a per-channel power curve on the upper luma zone after
     * the Whites/Highlights stage so it composes cleanly with existing
     * tone adjustments. Implementation derived from textbook tone-
     * mapping (Reinhard-family power compression) — not ported from any
     * GPL source.
     */
    val filmRolloff: Float = 0f,
    /**
     * Gamut compression strength [0..1]. 0 = off (current behaviour;
     * saturated highlights clip with hue-shift). 1 = full ACES-style
     * desaturation toward grey at the gamut wall, so blown highlights
     * roll off to white instead of producing chromatic edges.
     *
     * Useful for sunsets, neon signs, blown sky around the sun, and any
     * scene with deep saturated colour approaching display limits.
     * Derived from ACES gamut-compress public-domain math (Jed Smith
     * reference), original implementation.
     */
    val gamutCompress: Float = 0f,
    // ── Crop (Snapseed-style) ──────────────────────────────────────────
    //   Normalized [0..1] rect against the SOURCE image (post-rotate-EXIF).
    //   Default (0,0,1,1) = no crop. Stage A's region-of-interest loader
    //   honors this so downstream stages run on cropped pixels only.
    //   cropRotationDeg is the Snapseed "Straighten" angle in degrees
    //   (-45..+45 typical range, but unbounded). Applied via the renderer
    //   geometry, BEFORE the crop rect is intersected with the rotated
    //   image bounds.
    val cropL: Float = 0f,
    val cropT: Float = 0f,
    val cropR: Float = 1f,
    val cropB: Float = 1f,
    val cropRotationDeg: Float = 0f,
    // Discrete orientation (img.ly-style TRANSFORM). Applied to the SOURCE
    // BEFORE the straighten rotate + crop rect, in this order: flip → rotate90.
    //   cropRotate90: number of clockwise 90° quarter-turns, 0..3.
    //   cropFlipH / cropFlipV: mirror horizontally / vertically.
    // The normalized crop rect above is expressed against the already-oriented
    // (flipped + 90°-rotated + straightened) image, so export must re-apply the
    // same orientation in the same order for the rect to map correctly.
    val cropRotate90: Int = 0,
    val cropFlipH: Boolean = false,
    val cropFlipV: Boolean = false,
    val contrastBoost: Float = 0f,
    val exposure: Float = 0f,
    /**
     * Smart Bright [0..4] — fractional LibRaw auto-bright. 0 = off (no gain),
     * 4 = full auto-bright. The effective brightness multiplier is
     * 1 + (AB − 1) · (smartBright / 4), where AB is the per-image auto-bright
     * factor computed from the decoded thumbnail. Folded into the exposure
     * uniform at flatten() time so it tracks each photo (preset-portable).
     */
    val smartBright: Float = 0f,
    val whiteBalance: Int = 0,         // Kelvin; 0 = from camera
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    // ── Per-segment levels (Normalize for 3Dlut, when a subject is detected) —
    //   the GL shader + Stage C apply these in addition to the global
    //   whites/blacks above, gated by the U2Net subject mask: pixels inside
    //   the subject blend toward subjectW/B, background blends toward bgW/B.
    //   All zero = no per-segment correction (the global w/b path runs alone).
    val whitesSubject: Float = 0f,
    val blacksSubject: Float = 0f,
    val whitesBackground: Float = 0f,
    val blacksBackground: Float = 0f,
    // Per-segment shadows lift (Normalize for 3Dlut, silhouette class).
    // Used when a segment has CRUSHED shadows (p1 ≈ 0) but the white-point
    // is already at ceiling — redirect the lift into the shadows region.
    val shadowsSubject: Float = 0f,
    val shadowsBackground: Float = 0f,
    // Per-segment Highlights / Ambiance. Used by Auto Exposure to scale the
    // global highlight pull-down and ambiance lift to each region's actual
    // channel-clip percentage, so a hot sky doesn't drag the subject down
    // with it and a flat subject doesn't get extra ambiance just because the
    // background needs it. Defaults zero so existing sidecars render
    // identically. Range matches the global counterparts.
    val highlightsSubject: Float = 0f,
    val highlightsBackground: Float = 0f,
    val ambianceSubject: Float = 0f,
    val ambianceBackground: Float = 0f,
    // Auto Exposure subject-protection level (0..1).
    // 0 = no cap (background drives freely), 0.5 = mean cap only,
    // 1.0 = mean + P95 percentile cap. Stored in the macro so the
    // slider position is preserved when the AE action is re-applied.
    val aeSubjectProtection: Float = 0.95f,
    val contrast: Float = 0f,
    /**
     * Tonemap-tab tone deltas. Separate from Light-tab [exposure],
     * [highlights], [shadows] so an Adobe XMP preset (which writes
     * into the Tonemap tab via the XMP loader) cannot overwrite the
     * Light-tab values written by the AUTO EXPO action. Applied
     * additively on top of the Light-tab tone region in the shader.
     * Range: same as the Light tab counterparts.
     */
    val tonemapExposure: Float = 0f,
    val tonemapHighlights: Float = 0f,
    val tonemapShadows: Float = 0f,
    // Transient shader-only: filmic shoulder strength set by AI Expose when blown pixels detected.
    // Not persisted in macros or action stack — always 0 unless AI Expose is active.
    val filmicHlProtect: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val clarity: Float = 0f,
    // Clarity Pop [0..1] — img.ly-style midtone exposure lift coupled to
    // clarity (slot 408). Scales clarity's midZone-gated pop; does nothing
    // while clarity == 0.
    val clarityLift: Float = 0f,
    val dehaze: Float = 0f,
    val texture: Float = 0f,
    val sharpness: Float = 0f,
    val noiseReduction: Float = 0f,
    val vignetteAmount: Float = 0f,     // [-100, 100]; negative = dark, positive = light
    val vignetteFeather: Float = 0.5f,  // [0, 1]; softness of the vignette edge transition
    val vignetteIntensity: Float = 1f,  // [0, 1]; how solid/opaque the vignette darkening is
    val vignetteEffect: VignetteEffect = VignetteEffect.All,
    val vignetteCenterX: Float = 0.5f,  // [0, 1]; normalized horizontal center (0=left, 1=right)
    val vignetteCenterY: Float = 0.5f,  // [0, 1]; normalized vertical center (0=top, 1=bottom)
    /**
     * One-shot sentinel for the subject-aware auto-snap behavior. When the
     * user moves `vignetteAmount` away from 0 for the first time, the tab
     * snaps the center to the subject centroid (if segmentation is available)
     * and flips this flag. Subsequent amount changes don't re-snap — the user
     * can drag the center freely from that point using 'Move center'.
     */
    val vignetteCenterAutoSnapped: Boolean = false,
    /** When true, vignette amount falls off in the center instead of the corners. */
    val vignetteInvert: Boolean = false,
    // Interactive tone curves — 4 channels [L, R, G, B], each 5 y-values at x=0,0.25,0.5,0.75,1
    // Default (identity diagonal) means no curve adjustment.
    val toneCurvePoints: List<List<Float>> = DEFAULT_CURVE_POINTS,
    /**
     * When true, the "All" channel of the tone curve is interpreted as a
     * luma-only L curve — preserves chroma instead of running per-channel.
     * Per-R/G/B curves still apply independently. Default off for backwards
     * compatibility with existing sidecars.
     */
    val toneCurveLumaMode: Boolean = false,
    /**
     * Parametric film curve under the freehand [toneCurvePoints] — see [FilmCurve].
     * Baked into the same 256-RGB8 tone-curve LUT (preview = export).
     */
    val filmCurve: FilmCurve = FilmCurve(),
    // HSL per colour range (Red, Orange, Yellow, Green, Aqua, Blue)
    val hslRedHue: Float = 0f,    val hslRedSat: Float = 0f,    val hslRedLum: Float = 0f,
    val hslOrangeHue: Float = 0f, val hslOrangeSat: Float = 0f, val hslOrangeLum: Float = 0f,
    val hslYellowHue: Float = 0f, val hslYellowSat: Float = 0f, val hslYellowLum: Float = 0f,
    val hslGreenHue: Float = 0f,  val hslGreenSat: Float = 0f,  val hslGreenLum: Float = 0f,
    val hslAquaHue: Float = 0f,   val hslAquaSat: Float = 0f,   val hslAquaLum: Float = 0f,
    val hslBlueHue: Float = 0f,   val hslBlueSat: Float = 0f,   val hslBlueLum: Float = 0f,
    // Color Zones expansion — 6 additional anchors halve the spacing on the
    // hue wheel from 60° to 30°, fixing the wide gaps the original 6-anchor
    // scheme had (Yellow→Green and Aqua→Blue). Defaults zero, sidecar-safe.
    /** The six intermediate HSL anchors -- see [HslExtended]. */
    val hslExt: HslExtended = HslExtended(),
    // Details
    val smartSharpness: Float = 0f,     // [0, 1]
    val smoothBackground: Float = 0f,   // [0, 1] OOF/bokeh smoothing
    val luminanceNR: Float = 0f,        // [0, 1]
    val colorNR: Float = 0f,            // [0, 1]
    /**
     * Extra Cb-only (blue-channel) NR strength [0..1] applied on top of
     * [colorNR]. Bayer blue channel has the lowest SNR (smallest WB gain),
     * so pushing only this slider clobbers blue speckle without flattening
     * red/yellow chroma detail.
     */
    val blueNR: Float = 0f,             // [0, 1]
    /**
     * Symmetric Cr-only NR knob. Mirrors [blueNR] for the red chroma axis.
     * Useful when high-ISO Canon shots show red speckle in shadows.
     */
    val redNR: Float = 0f,              // [0, 1]
    val filmGrain: Float = 0f,          // [0, 1]
    val filmGrainSize: Float = 0.5f,    // [0, 1]
    val filmGrainWashOut: Float = 0f,   // [0, 1]
    val removeShadows: Boolean = false,     // ML shadow removal (G1+G2 ONNX, general scenes)
    val removeFaceShadows: Boolean = false, // ML face shadow removal (take-off-eyeglasses CVPR 2022)
    // Bokeh (background blur + bokeh balls + bloom) — baked on the bitmap path,
    // subject-mask gated. See RawV3Bokeh.
    val bokehBlur: Int = 0,             // [0..100] bilateral blur on highlights
    val bokehBalls: Int = 0,            // [0..100] disc-blur bokeh balls
    val bokehSpread: Float = 0f,        // [0..1] highlight bloom (0 = off)
    // 4-sided linear gradient — each side fades from its edge inward
    val gradientAngle: Float = 0f,                  // global rotation [-180, 180°]
    val gradientTopIntensity: Float = 0f,           // [0, 1] darkening at top edge
    val gradientTopLength: Float = 0.3f,            // [0, 1] how far inward
    val gradientTopFeather: Float = 0.5f,           // [0, 1] softness; when 2nd color is on, this is the tint1→tint2 transition width
    val gradientTopTintColor: Int = AndroidColor.WHITE,
    val gradientTopTintLuminosity: Float = 0f,
    val gradientTopBlendMode: RawGradientBlendMode = RawGradientBlendMode.Solid,
    /** Top: enable a second tint that linearly continues from the first color further inward. */
    val gradientTopEnable2: Boolean = false,
    val gradientTopIntensity2: Float = 0f,
    val gradientTopLength2: Float = 0.3f,
    val gradientTopFeather2: Float = 0.5f,
    val gradientTopTintColor2: Int = AndroidColor.WHITE,
    val gradientTopTintLuminosity2: Float = 0f,
    val gradientBottomIntensity: Float = 0f,
    val gradientBottomLength: Float = 0.3f,
    val gradientBottomFeather: Float = 0.5f,
    val gradientBottomTintColor: Int = AndroidColor.WHITE,
    val gradientBottomTintLuminosity: Float = 0f,
    val gradientBottomBlendMode: RawGradientBlendMode = RawGradientBlendMode.Solid,
    val gradientBottomEnable2: Boolean = false,
    val gradientBottomIntensity2: Float = 0f,
    val gradientBottomLength2: Float = 0.3f,
    val gradientBottomFeather2: Float = 0.5f,
    val gradientBottomTintColor2: Int = AndroidColor.WHITE,
    val gradientBottomTintLuminosity2: Float = 0f,
    val gradientLeftIntensity: Float = 0f,
    val gradientLeftLength: Float = 0.3f,
    val gradientLeftFeather: Float = 0.5f,
    val gradientLeftTintColor: Int = AndroidColor.WHITE,
    val gradientLeftTintLuminosity: Float = 0f,
    val gradientLeftBlendMode: RawGradientBlendMode = RawGradientBlendMode.Solid,
    val gradientLeftEnable2: Boolean = false,
    val gradientLeftIntensity2: Float = 0f,
    val gradientLeftLength2: Float = 0.3f,
    val gradientLeftFeather2: Float = 0.5f,
    val gradientLeftTintColor2: Int = AndroidColor.WHITE,
    val gradientLeftTintLuminosity2: Float = 0f,
    val gradientRightIntensity: Float = 0f,
    val gradientRightLength: Float = 0.3f,
    val gradientRightFeather: Float = 0.5f,
    val gradientRightTintColor: Int = AndroidColor.WHITE,
    val gradientRightTintLuminosity: Float = 0f,
    val gradientRightBlendMode: RawGradientBlendMode = RawGradientBlendMode.Solid,
    val gradientRightEnable2: Boolean = false,
    val gradientRightIntensity2: Float = 0f,
    val gradientRightLength2: Float = 0.3f,
    val gradientRightFeather2: Float = 0.5f,
    val gradientRightTintColor2: Int = AndroidColor.WHITE,
    val gradientRightTintLuminosity2: Float = 0f,
    // Brush mask local adjustments — applied only to pixels with mask alpha > 0
    val maskBrightness: Float = 0f,    // [-100, 100] → EV exposure multiplier
    val maskContrast: Float = 0f,      // [-100, 100]
    val maskTemperature: Int = 0,      // Kelvin delta (positive = warmer)
    val maskTint: Float = 0f,          // [-150, 150]
    val maskSaturation: Float = 0f,    // [-100, 100]
    val maskClarity: Float = 0f,       // [-100, 100] local contrast via unsharp mask
    val maskSharpness: Float = 0f,     // [-100, 100] masked high-freq unsharp (Detail sharpness)
    // Tone-region adjustments inside the mask (mirror the global Tone tab),
    // nested in ONE holder so UserMacro gains a single constructor parameter.
    // (UserMacro is at the Kotlin data-class copy()/copy$default synthetic-
    // constructor size ceiling — four flat fields pushed the dex verifier over,
    // so these live in [MaskToneRegions]. All [-100, 100].)
    val maskTone: MaskToneRegions = MaskToneRegions(),
    /** Recovery / FillLight / B&W GrayMixer -- see [FilmResponse]. */
    val filmResponse: FilmResponse = FilmResponse(),
    // Luminance-range mask ("Select Luminance"): pixels within ±spread of the
    // target graded luma are selected, with a smooth [feather] falloff (halo-free,
    // computed per-pixel in the shader + export kernel). spread > 0 ACTIVATES it
    // and replaces the brush texture for that layer. All in [0,1].
    val maskLumTarget: Float = 0f,     // target tone (0 = black … 1 = white)
    val maskLumSpread: Float = 0f,     // solid-selection half-width; 0 = off
    val maskLumFeather: Float = 0f,    // smooth falloff beyond the band
    // How the live luma band combines with the object/brush bitmap when both
    // are present: 0=luma wins (legacy), 1=luma−bitmap (luma base, objects
    // carved out live), 2=bitmap−luma, 3=union, 4=intersect.
    val maskLumCombine: Int = 0,
    // Segmentation targeting — ignored when segmentation masks are unavailable
    val vignetteSegmentation: SegmentTarget = SegmentTarget.All,
    val gradientTopApplyTo: SegmentTarget = SegmentTarget.All,
    val gradientBottomApplyTo: SegmentTarget = SegmentTarget.All,
    val gradientLeftApplyTo: SegmentTarget = SegmentTarget.All,
    val gradientRightApplyTo: SegmentTarget = SegmentTarget.All,
    val outputColorSpace: RawColorSpace = RawColorSpace.SRGB,
    // v2 §6 — when true, GPS tags are stripped from the embedded EXIF on save.
    // Defaults off so existing user files keep their geotags by default.
    val stripGps: Boolean = false,

    // ── PREQ-Port additions ───────────────────────────────────────────────────
    // Detail: Grain Roughness (Voronoi blend) and Sharpen Mask (Sobel-gated).
    val grainRoughness: Float = 0f,   // [0..100] UI → /100 to [0..1]
    val sharpenMask:    Float = 0f,   // [0..100] UI → /100

    // Color tab: Color Density and Skintone cluster.
    val colorDensity:   Float = 0f,   // [-100..+100] UI → /100 to [-1..+1]
    val skintoneWarm:   Float = 0f,   // [-50..+50] UI → /100 to [-0.5..+0.5]
    val skintoneSmooth: Float = 0f,   // [0..100] UI → /100
    val skintoneLuma:   Float = 0f,   // [-50..+50] UI → /100 to [-0.5..+0.5]

    // Color tab: 4-way Color Grading wheels (Lift/Gamma/Gain/Offset). Each wheel
    // is a nested [ColorWheel] holder: RGB as [-50..+50] UI offset from neutral
    // (→ /100), sat as wheel strength [-100..+100] (→ /100). All four are nested
    // (not flat r/g/b/sat fields) so UserMacro spends 4 constructor params here
    // instead of 16 — UserMacro is at the data-class copy() register ceiling and
    // flat fields overflow the dex verifier (see [MaskToneRegions]).
    val cgShadows:    ColorWheel = ColorWheel(),  // lift
    val cgMidtones:   ColorWheel = ColorWheel(),  // gamma
    val cgHighlights: ColorWheel = ColorWheel(),  // gain
    val cgGlobal:     ColorWheel = ColorWheel(),  // offset

    // FX tab: OpenShot lens flare (nested holder → 1 constructor param).
    val lensFlare:    LensFlare = LensFlare(),
    val sceneShadow:  SceneShadow = SceneShadow(),
    // FX tab: OpenShot ColorShift RGB split (horizontal, nested holder).
    val colorShift:   ColorShift = ColorShift(),

    // Light tab: Center Pop (midtone punch).
    val centerPop: Float = 0f,        // [-100..+100] UI → /100 to [-1..+1]

    // Tonal tab: Midtone Details and Highlight Recovery.
    val midtoneDetails:    Float = 0f, // [-100..+100] UI → /100
    val highlightRecovery: Float = 0f, // [0..100] UI → /100

    // LUT tab: Push/Pull, LUT Color Density, LUT Skintone Balance.
    val pushPull:           Float = 0f, // [-3..+3] EV stops (raw, no scaling)
    val lutColorDensity:    Float = 0f, // [-100..+100] UI → /100
    val lutSkintoneBalance: Float = 0f, // [-50..+50] UI → /100

    // LUT tab: Chromatic Aberration.
    val aberStrength:     Float = 0f, // [0..100] UI → /100
    val aberFringeReduce: Float = 0f, // [0..100] UI → /100

    // Effects tab.
    val fxBlurStyle:          Int   = 0,    // 0=off 1=Gauss 2=Dir 3=Rad 4=Zoom
    val fxGaussBlur:          Float = 0f,   // [0..100] UI → /100
    val fxDirBlurAmt:         Float = 0f,   // [0..100] UI → /100
    val fxDirBlurAngle:       Float = 0f,   // degrees; converted to radians at shader
    val fxRadBlurAmt:         Float = 0f,   // [0..100] UI → /100
    val fxRadBlurCx:          Float = 0.5f, // [0..1]
    val fxRadBlurCy:          Float = 0.5f, // [0..1]
    val fxZoomBlurAmt:        Float = 0f,   // [0..100] UI → /100
    val fxZoomBlurCx:         Float = 0.5f, // [0..1]
    val fxZoomBlurCy:         Float = 0.5f, // [0..1]
    val fxBlurExcludeSubject: Boolean = false,
    val fxMist:               Float = 0f,   // [0..100] UI → /100
    val fxMistWarmth:         Float = 0f,   // [-50..+50] UI → /100
    val fxDust:               Float = 0f,   // [0..100] UI → /100
    val fxDustSize:           Float = 0f,   // [0..100] UI → /100
    val fxVintageStrength:    Float = 0f,   // [0..100] UI → /100
    val fxVintageFade:        Float = 0f,   // [0..100] UI → /100
    val fxVintageVig:         Float = 0f,   // [0..100] UI → /100
    val fxVintageMistIntensity: Float = 0f,
    val fxVintageMistScale:     Float = 1f,
    val fxVintageTextureIntensity: Float = 0f,
    val fxVintageTextureScale:  Float = 1f,
    val fxVintageTextureStyle:  Int = 0,
    val fxGlowStrength:       Float = 0f,   // [0..100] UI → /100
    val fxGlowSpread:         Float = 0f,   // [0..100] UI → /100
    val fxGlowWarmth:         Float = 0f,   // [-50..+50] UI → /100
    /**
     * Scene-preset tag written by [createSmartDefaults] when the scene detector
     * infers a condition from the image histogram (e.g. "Golden Hour", "Night
     * Scene"). Null when smart defaults were not applied or no condition was
     * detected. Has no rendering effect — purely informational.
     */
    val scenePreset: String? = null,
) {
    companion object {
        val DEFAULT_CURVE_POINTS: List<List<Float>> = List(4) {
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        }

        /**
         * Camera-style finish macro — modest tonal/color punch that
         * mimics what an in-camera JPG processor adds (Canon Standard
         * Picture Style, Sony Standard Creative Style, etc.). Applied
         * at the **bottom** of the action stack so any user edits in
         * Light/Color tabs stack additively on top.
         *
         * Values are in standard UI units (sliders' raw range, before
         * RawV3ActionReplay's `/ 100` normalisation):
         *
         *   shadows   +10  — slight shadow lift, doesn't crush mids
         *   contrast  +10  — gentle global S-curve
         *   saturation +15  — modest colour bump, well short of cartoon
         *   clarity    +5  — light midtone micro-contrast
         *   sharpness +10  — small-radius unsharp mask
         *
         * Each one alone is invisible; together they close the gap to
         * a camera JPG's baked-in tonal punch without producing the
         * HDR-overcooked look you get when several aggressive stages
         * stack.
         *
         * Gated by [WorkspaceConfig.cameraStyleFinishEnabled] (default
         * on). Toggle in the Workspace Selector to disable.
         */
        /**
         * CAMERA_STYLE_FINISH used to fold shadows=+10, contrast=+5,
         * saturation=+15, clarity=+5, sharpness=+10, claheHighlightsBoost=-0.30
         * into every macro on photo-open. Per user direction
         * ("do not add any other unrelated adjustment in Stage B if not
         * enable in the adjustment settings in editor page"), this baseline
         * is now an empty UserMacro — the editor opens with the raw decoded
         * look and any adjustment must come from explicit slider movement.
         *
         * The cameraStyleFinishEnabled workspace flag still gates this
         * (callers check it before folding), so if a future preset needs
         * a default tone, it can land here without changing call sites.
         */
        val CAMERA_STYLE_FINISH: UserMacro = UserMacro()

        /**
         * Scene-adaptive smart defaults — histogram heuristics applied once when
         * a new RAW file is opened (gated by [WorkspaceConfig.smartDefaultsEnabled]).
         *
         * Analyses a downscaled version of [bitmap] (from Stage A decode), classifies
         * the scene, and returns a [UserMacro] delta with sensible starting values for
         * saturation, vibrance, whiteBalance, tint, and the CLAHE shadow/highlight
         * boosts.  LUT slots are left empty; the user's own LUT selection is unaffected.
         *
         * Returns an unmodified [UserMacro] if no strong scene signature is detected
         * (the "Neutral" preset) so the pipeline still records the applied preset tag.
         */
        fun createSmartDefaults(bitmap: android.graphics.Bitmap): UserMacro {
            val metrics = com.RAZStudio.StudioRoom.feature.photo_editor.raw.scene
                .ImageMetrics.compute(bitmap)
            val result = com.RAZStudio.StudioRoom.feature.photo_editor.raw.scene
                .SceneDetector.detect(metrics)
            return UserMacro(
                saturation           = result.recommendedSaturation,
                vibrance             = result.recommendedVibrance,
                whiteBalance         = result.recommendedWhiteBalanceDelta,
                tint                 = result.recommendedTintDelta,
                claheShadowsBoost    = result.recommendedClaheShadowsBoost,
                claheHighlightsBoost = result.recommendedClaheHighlightsBoost,
                scenePreset          = result.presetName,
            )
        }

        /**
         * AI Color Enhance — the AI-fusion replacement for [createSmartDefaults].
         * Instant global tier: fuses the neural **Zero-DCE** low-light score
         * ([RawV3ZeroDceLightProbe.probeAverageLift], higher = darker scene that
         * would benefit from more lift) with histogram metrics ([ImageMetrics])
         * into a single global auto-enhance macro. No scene-classifier model —
         * the learned Zero-DCE curve magnitude replaces the brittle avgLuma
         * threshold that the heuristic path used to gate its low-light branch.
         *
         * The response is CONTINUOUS in a fused low-light confidence rather than
         * bucketed into named presets, so it degrades gracefully across the whole
         * lighting range instead of snapping between "Night" and "Neutral".
         *
         * @param zeroDceScore mean A-map lift from Zero-DCE, or null when the
         *   model is missing / inference failed — then we fall back to a pure
         *   luma estimate so the feature still produces sensible defaults.
         *
         * WB stays gentle and warm-biased per the project's colour-science
         * preference (we cool only genuinely blue scenes; we never neutralise
         * warm/golden light).
         */
        fun createAiColorEnhance(
            bitmap: android.graphics.Bitmap,
            zeroDceScore: Float?,
        ): UserMacro {
            val m = com.RAZStudio.StudioRoom.feature.photo_editor.raw.scene
                .ImageMetrics.compute(bitmap)

            fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

            // Neural low-light confidence from Zero-DCE: the probe doc calibrates
            // ~0.15 = bright, ~0.40 = very dark. Map that window to 0..1.
            val dceLowLight = zeroDceScore?.let { ((it - 0.15f) / (0.40f - 0.15f)).coerceIn(0f, 1f) }
            // Histogram fallback / stabiliser: dark mean luma → high confidence.
            val lumaLowLight = ((150f - m.avgLuma) / 150f).coerceIn(0f, 1f)
            // Fuse: trust the learned signal when present, blend in luma for
            // robustness; pure-luma when Zero-DCE is unavailable.
            val lowLight = if (dceLowLight != null) {
                0.65f * dceLowLight + 0.35f * lumaLowLight
            } else {
                lumaLowLight
            }.coerceIn(0f, 1f)

            // Flatness → extra micro-contrast via CLAHE shadows; wide-DR scenes
            // already have punch so we add less.
            val flatness = ((90f - m.lumaRange) / 90f).coerceIn(0f, 1f)

            // Warm-biased WB: cool only clearly blue light; leave warm scenes be.
            val wbDelta = when {
                m.colorTempKelvin > 6800 -> 220   // blue/shade → warm it
                m.colorTempKelvin > 6000 -> 90
                m.colorTempKelvin < 3200 -> -80    // extreme incandescent → tiny pull
                else                     -> 0
            }

            return UserMacro(
                saturation           = lerp(6f, 16f, lowLight),
                vibrance             = lerp(12f, 32f, lowLight),
                whiteBalance         = wbDelta,
                tint                 = 0f,
                claheShadowsBoost    = lerp(0.05f, 0.28f, lowLight) + 0.10f * flatness,
                claheHighlightsBoost = lerp(-0.03f, -0.15f, lowLight),
                scenePreset          = "AI Color Enhance",
            )
        }
    }

    /**
     * Committed LUT layers in chain order (slot 1 then slot 2), empties filtered.
     * The whole render path (preview, export, batch chain resolver, shader-param
     * mapping) reads this — keeping the name means none of those change when the
     * storage moved from an unbounded stack to two fixed slots.
     */
    val lutStack: List<LutLayer>
        get() = buildList {
            if (lut1CubeUri.isNotEmpty()) add(LutLayer(lut1CubeUri, lut1Intensity))
            if (lut2CubeUri.isNotEmpty()) add(LutLayer(lut2CubeUri, lut2Intensity))
        }

    /**
     * Additive merge: returns a new [UserMacro] that represents applying [delta] on top of [this].
     * Numeric adjustment fields are summed and clamped. LUT and tone curves in [delta] override
     * [this] only if they are non-default. Gradient tint color in [delta] overrides [this] only
     * if it differs from the default (WHITE).
     */
    fun mergeWith(delta: UserMacro): UserMacro {
        val d = DEFAULT_CURVE_POINTS
        val useDeltaCurves = delta.toneCurvePoints != d
        // ── LUT slots ──────────────────────────────────────────────────────
        // Start from this macro's two committed slots. A delta may (a) carry its
        // own committed slots — e.g. a loaded baseline / preset merged in — which
        // override when non-empty, and/or (b) carry one in-flight slot edit
        // (lutEdited) that writes its target slot, supporting clear-to-empty.
        var s1u = lut1CubeUri; var s1i = lut1Intensity
        var s2u = lut2CubeUri; var s2i = lut2Intensity
        if (delta.lut1CubeUri.isNotEmpty()) { s1u = delta.lut1CubeUri; s1i = delta.lut1Intensity }
        if (delta.lut2CubeUri.isNotEmpty()) { s2u = delta.lut2CubeUri; s2i = delta.lut2Intensity }
        if (delta.lutEdited) {
            if (delta.lutSlot == 2) { s2u = delta.lutCubeUri; s2i = delta.lutIntensity }
            else                    { s1u = delta.lutCubeUri; s1i = delta.lutIntensity }
        }
        return UserMacro(
            lut1CubeUri    = s1u,
            lut1Intensity  = s1i,
            lut2CubeUri    = s2u,
            lut2Intensity  = s2i,
            lutCubeUri     = "",
            lutIntensity   = 1f,
            lutSlot        = 1,
            lutEdited      = false,
            // CLAHE: always-on now (toggle removed). The Shadows/Highlights
            // boost sliders are the ONLY knobs left, so the delta's values
            // always win — regardless of any stale `claheEnabled=false` that
            // may have been loaded from a pre-flip macro snapshot.
            claheEnabled         = true,
            claheShadowsBoost    = if (delta.claheShadowsBoost    != 0f) delta.claheShadowsBoost    else claheShadowsBoost,
            claheHighlightsBoost = if (delta.claheHighlightsBoost != 0f) delta.claheHighlightsBoost else claheHighlightsBoost,
            jpegRefine = if (delta.jpegRefine.touched) delta.jpegRefine.copy(touched = false) else jpegRefine,
            // LUT vibrancy is a LUT-tab field; latest non-zero wins so the
            // delta overrides the base when the user adjusts it.
            lutHighlightVibrancy = if (delta.lutHighlightVibrancy != 0f) delta.lutHighlightVibrancy else lutHighlightVibrancy,
            highlightTemperature = if (delta.highlightTemperature != 0f) delta.highlightTemperature else highlightTemperature,
            highlightTint        = if (delta.highlightTint        != 0f) delta.highlightTint        else highlightTint,
            shadowTemperature    = if (delta.shadowTemperature    != 0f) delta.shadowTemperature    else shadowTemperature,
            shadowTint           = if (delta.shadowTint           != 0f) delta.shadowTint           else shadowTint,
            // glowStrength/Saturation/Warmth/Sharpness removed.
            ambiance             = if (delta.ambiance             != 0f) delta.ambiance             else ambiance,
            // Orton bloom (Tonemap-tab "Bloom" slider). Latest non-zero
            // wins so the delta overrides the base when the user adjusts.
            // Without this entry the field always defaulted to 0 even
            // when the slider was at 100, and the GL bloom pass did
            // nothing visible.
            ortonStrength        = if (delta.ortonStrength        != 0f) delta.ortonStrength        else ortonStrength,
            mistTightness        = if (delta.mistTightness        != 55f) delta.mistTightness        else mistTightness,
            mistHalation         = if (delta.mistHalation         != 0f) delta.mistHalation         else mistHalation,
            optical              = if (delta.optical.any { it != 0f }) delta.optical.copyOf() else optical.copyOf(),
            grainEmulsion        = if (grainPackActive(delta.grainEmulsion)) delta.grainEmulsion.copyOf() else grainEmulsion.copyOf(),
            sceneShadow          = if (delta.sceneShadow.strength != 0f ||
                                        delta.sceneShadow.distance != 50f ||
                                        delta.sceneShadow.softness != 50f) delta.sceneShadow else sceneShadow,
            highlightStart       = if (delta.highlightStart != 0.78f) delta.highlightStart else highlightStart,
            highlightEnd         = if (delta.highlightEnd != 0.98f) delta.highlightEnd else highlightEnd,
            cinematicMistTier    = if (delta.cinematicMistTier    != 0) delta.cinematicMistTier    else cinematicMistTier,
            // Bloom radius / shape — "non-default" means user actually
            // touched the slider. Compare against the constructor defaults
            // (8f / 1f) so they round-trip exactly through merges.
            bloomRadius          = if (delta.bloomRadius           != 8f) delta.bloomRadius           else bloomRadius,
            bloomShape           = if (delta.bloomShape            != 1f) delta.bloomShape            else bloomShape,
            // Booleans: OR, like every other on/off flag here. "Delta always
            // wins" was WRONG under the fold: composeMacro merges the cards
            // oldest-LAST, so the open-time _ai_color_enhance / any Tone card
            // (all default false) merged after the FX card and silently reset
            // the flag every frame — "Protect subject" never reached the shader
            // (bloomExcludeSubject=0 → uniform bloom over the subject). Turning
            // the toggle off is still honoured: replaceTabCards drops the FX
            // card that carried `true`, so no visible card asserts it.
            bloomExcludeSubject  = bloomExcludeSubject || delta.bloomExcludeSubject,
            subjectBloom         = if (delta.subjectBloom          != 0f) delta.subjectBloom          else subjectBloom,
            subjectPopEnabled    = subjectPopEnabled || delta.subjectPopEnabled,
            // Color Pop strength: the latest card carrying a non-zero level wins;
            // an absent card (level 0 / "Off" → no card) leaves the base untouched.
            smartColorEnhance    = if (delta.smartColorEnhance != 0f) delta.smartColorEnhance else smartColorEnhance,
            subjectPopShadow     = if (delta.subjectPopShadow      != 0f) delta.subjectPopShadow      else subjectPopShadow,
            subjectPopHighlight  = if (delta.subjectPopHighlight   != 0f) delta.subjectPopHighlight   else subjectPopHighlight,
            subjectPopSaturation = if (delta.subjectPopSaturation  != 0f) delta.subjectPopSaturation  else subjectPopSaturation,
            filmRolloff          = if (delta.filmRolloff           != 0f) delta.filmRolloff           else filmRolloff,
            gamutCompress        = if (delta.gamutCompress         != 0f) delta.gamutCompress         else gamutCompress,
            // Crop rect: delta is "active" when it's not the default
            // (0,0,1,1). Latest active rect wins.
            cropL = if (delta.isCropActive()) delta.cropL else cropL,
            cropT = if (delta.isCropActive()) delta.cropT else cropT,
            cropR = if (delta.isCropActive()) delta.cropR else cropR,
            cropB = if (delta.isCropActive()) delta.cropB else cropB,
            cropRotationDeg = if (delta.cropRotationDeg != 0f) delta.cropRotationDeg else cropRotationDeg,
            // Orientation: latest non-identity wins (mirrors the crop-rect rule).
            cropRotate90 = if (delta.cropRotate90 != 0) delta.cropRotate90 else cropRotate90,
            cropFlipH    = if (delta.cropFlipH) true else cropFlipH,
            cropFlipV    = if (delta.cropFlipV) true else cropFlipV,
            contrastBoost  = (contrastBoost + delta.contrastBoost).coerceIn(0f, 1f),
            exposure       = exposure + delta.exposure,
            // Smart Bright is a strength, not an additive delta: latest non-zero
            // wins so it doesn't accumulate across stacked actions.
            smartBright    = if (delta.smartBright != 0f) delta.smartBright else smartBright,
            whiteBalance   = whiteBalance + delta.whiteBalance,
            tint           = (tint + delta.tint).coerceIn(-200f, 200f),
            highlights     = (highlights + delta.highlights).coerceIn(-100f, 100f),
            shadows        = (shadows + delta.shadows).coerceIn(-100f, 100f),
            whites         = (whites + delta.whites).coerceIn(-100f, 100f),
            blacks         = (blacks + delta.blacks).coerceIn(-100f, 100f),
            whitesSubject    = (whitesSubject    + delta.whitesSubject   ).coerceIn(-100f, 100f),
            blacksSubject    = (blacksSubject    + delta.blacksSubject   ).coerceIn(-100f, 100f),
            whitesBackground = (whitesBackground + delta.whitesBackground).coerceIn(-100f, 100f),
            blacksBackground = (blacksBackground + delta.blacksBackground).coerceIn(-100f, 100f),
            shadowsSubject    = (shadowsSubject    + delta.shadowsSubject   ).coerceIn(-100f, 100f),
            shadowsBackground = (shadowsBackground + delta.shadowsBackground).coerceIn(-100f, 100f),
            highlightsSubject    = (highlightsSubject    + delta.highlightsSubject   ).coerceIn(-100f, 100f),
            highlightsBackground = (highlightsBackground + delta.highlightsBackground).coerceIn(-100f, 100f),
            ambianceSubject      = (ambianceSubject      + delta.ambianceSubject     ).coerceIn(-1f, 1f),
            ambianceBackground   = (ambianceBackground   + delta.ambianceBackground  ).coerceIn(-1f, 1f),
            contrast       = (contrast + delta.contrast).coerceIn(-100f, 100f),
            tonemapExposure   = tonemapExposure   + delta.tonemapExposure,
            tonemapHighlights = (tonemapHighlights + delta.tonemapHighlights).coerceIn(-100f, 100f),
            tonemapShadows    = (tonemapShadows    + delta.tonemapShadows).coerceIn(-100f, 100f),
            saturation     = (saturation + delta.saturation).coerceIn(-100f, 100f),
            vibrance       = (vibrance + delta.vibrance).coerceIn(-100f, 100f),
            clarity        = (clarity + delta.clarity).coerceIn(-100f, 100f),
            clarityLift    = (clarityLift + delta.clarityLift).coerceIn(0f, 1f),
            dehaze         = (dehaze + delta.dehaze).coerceIn(-25f, 25f),
            texture        = (texture + delta.texture).coerceIn(-100f, 100f),
            sharpness      = (sharpness + delta.sharpness).coerceIn(0f, 100f),
            noiseReduction = (noiseReduction + delta.noiseReduction).coerceIn(0f, 100f),
            vignetteAmount    = (vignetteAmount + delta.vignetteAmount).coerceIn(-100f, 100f),
            vignetteFeather   = if (delta.vignetteAmount != 0f) delta.vignetteFeather   else vignetteFeather,
            vignetteIntensity = if (delta.vignetteAmount != 0f) delta.vignetteIntensity else vignetteIntensity,
            vignetteEffect    = if (delta.vignetteEffect != VignetteEffect.All) delta.vignetteEffect else vignetteEffect,
            vignetteCenterX   = if (delta.vignetteAmount != 0f) delta.vignetteCenterX else vignetteCenterX,
            vignetteCenterY   = if (delta.vignetteAmount != 0f) delta.vignetteCenterY else vignetteCenterY,
            // Sticky: once the auto-snap has fired (delta or base has it set),
            // keep the flag set so subsequent merges don't re-snap.
            vignetteCenterAutoSnapped = vignetteCenterAutoSnapped || delta.vignetteCenterAutoSnapped,
            vignetteInvert = if (delta.vignetteAmount != 0f || delta.vignetteInvert) delta.vignetteInvert else vignetteInvert,
            toneCurvePoints = if (useDeltaCurves) delta.toneCurvePoints else toneCurvePoints,
            // Whole-holder replace when the delta carries any non-default film
            // parametric (same pattern as toneCurvePoints).
            filmCurve = if (delta.filmCurve != FilmCurve()) delta.filmCurve else filmCurve,
            hslRedHue    = (hslRedHue    + delta.hslRedHue   ).coerceIn(-180f, 180f),
            hslRedSat    = (hslRedSat    + delta.hslRedSat   ).coerceIn(-100f, 100f),
            hslRedLum    = (hslRedLum    + delta.hslRedLum   ).coerceIn(-100f, 100f),
            hslOrangeHue = (hslOrangeHue + delta.hslOrangeHue).coerceIn(-180f, 180f),
            hslOrangeSat = (hslOrangeSat + delta.hslOrangeSat).coerceIn(-100f, 100f),
            hslOrangeLum = (hslOrangeLum + delta.hslOrangeLum).coerceIn(-100f, 100f),
            hslYellowHue = (hslYellowHue + delta.hslYellowHue).coerceIn(-180f, 180f),
            hslYellowSat = (hslYellowSat + delta.hslYellowSat).coerceIn(-100f, 100f),
            hslYellowLum = (hslYellowLum + delta.hslYellowLum).coerceIn(-100f, 100f),
            hslGreenHue  = (hslGreenHue  + delta.hslGreenHue ).coerceIn(-180f, 180f),
            hslGreenSat  = (hslGreenSat  + delta.hslGreenSat ).coerceIn(-100f, 100f),
            hslGreenLum  = (hslGreenLum  + delta.hslGreenLum ).coerceIn(-100f, 100f),
            hslAquaHue   = (hslAquaHue   + delta.hslAquaHue  ).coerceIn(-180f, 180f),
            hslAquaSat   = (hslAquaSat   + delta.hslAquaSat  ).coerceIn(-100f, 100f),
            hslAquaLum   = (hslAquaLum   + delta.hslAquaLum  ).coerceIn(-100f, 100f),
            hslBlueHue   = (hslBlueHue   + delta.hslBlueHue  ).coerceIn(-180f, 180f),
            hslBlueSat   = (hslBlueSat   + delta.hslBlueSat  ).coerceIn(-100f, 100f),
            hslBlueLum   = (hslBlueLum   + delta.hslBlueLum  ).coerceIn(-100f, 100f),
            smartSharpness    = (smartSharpness    + delta.smartSharpness   ).coerceIn(0f, 1f),
            smoothBackground  = (smoothBackground  + delta.smoothBackground ).coerceIn(0f, 1f),
            luminanceNR       = (luminanceNR       + delta.luminanceNR      ).coerceIn(0f, 1f),
            colorNR           = (colorNR           + delta.colorNR          ).coerceIn(0f, 1f),
            blueNR            = (blueNR            + delta.blueNR           ).coerceIn(0f, 1f),
            redNR             = (redNR             + delta.redNR            ).coerceIn(0f, 1f),
            filmGrain         = (filmGrain         + delta.filmGrain        ).coerceIn(0f, 1f),
            filmGrainSize     = if (delta.filmGrain > 0f) delta.filmGrainSize     else filmGrainSize,
            filmGrainWashOut  = (filmGrainWashOut  + delta.filmGrainWashOut ).coerceIn(0f, 1f),
            removeShadows     = removeShadows     || delta.removeShadows,
            removeFaceShadows = removeFaceShadows || delta.removeFaceShadows,
            bokehBlur         = (bokehBlur         + delta.bokehBlur         ).coerceIn(0, 100),
            bokehBalls        = (bokehBalls        + delta.bokehBalls        ).coerceIn(0, 100),
            bokehSpread       = (bokehSpread       + delta.bokehSpread       ).coerceIn(0f, 1f),
            gradientAngle              = gradientAngle + delta.gradientAngle,
            gradientTopIntensity       = (gradientTopIntensity    + delta.gradientTopIntensity   ).coerceIn(0f, 1f),
            // Length/feather/blendMode are decoupled from intensity: adopt them
            // when the side is active (intensity OR tint) OR the field itself was
            // changed in the delta — so the Length slider and the Solid/Fused
            // toggle work on their own, not only while Intensity > 0.
            gradientTopLength          = if (delta.gradientTopIntensity > 0f || delta.gradientTopTintLuminosity > 0f || delta.gradientTopLength != 0.3f) delta.gradientTopLength    else gradientTopLength,
            gradientTopFeather         = if (delta.gradientTopIntensity > 0f || delta.gradientTopTintLuminosity > 0f || delta.gradientTopFeather != 0.5f) delta.gradientTopFeather   else gradientTopFeather,
            gradientTopTintColor       = if (delta.gradientTopTintColor != AndroidColor.WHITE) delta.gradientTopTintColor else gradientTopTintColor,
            gradientTopTintLuminosity  = (gradientTopTintLuminosity + delta.gradientTopTintLuminosity).coerceIn(0f, 1f),
            gradientTopBlendMode       = if (delta.gradientTopIntensity > 0f || delta.gradientTopTintLuminosity > 0f || delta.gradientTopBlendMode != RawGradientBlendMode.Solid) delta.gradientTopBlendMode else gradientTopBlendMode,
            gradientBottomIntensity    = (gradientBottomIntensity + delta.gradientBottomIntensity).coerceIn(0f, 1f),
            gradientBottomLength       = if (delta.gradientBottomIntensity > 0f || delta.gradientBottomTintLuminosity > 0f || delta.gradientBottomLength != 0.3f) delta.gradientBottomLength  else gradientBottomLength,
            gradientBottomFeather      = if (delta.gradientBottomIntensity > 0f || delta.gradientBottomTintLuminosity > 0f || delta.gradientBottomFeather != 0.5f) delta.gradientBottomFeather else gradientBottomFeather,
            gradientBottomTintColor    = if (delta.gradientBottomTintColor != AndroidColor.WHITE) delta.gradientBottomTintColor else gradientBottomTintColor,
            gradientBottomTintLuminosity = (gradientBottomTintLuminosity + delta.gradientBottomTintLuminosity).coerceIn(0f, 1f),
            gradientBottomBlendMode    = if (delta.gradientBottomIntensity > 0f || delta.gradientBottomTintLuminosity > 0f || delta.gradientBottomBlendMode != RawGradientBlendMode.Solid) delta.gradientBottomBlendMode else gradientBottomBlendMode,
            gradientLeftIntensity      = (gradientLeftIntensity  + delta.gradientLeftIntensity  ).coerceIn(0f, 1f),
            gradientLeftLength         = if (delta.gradientLeftIntensity > 0f || delta.gradientLeftTintLuminosity > 0f || delta.gradientLeftLength != 0.3f) delta.gradientLeftLength    else gradientLeftLength,
            gradientLeftFeather        = if (delta.gradientLeftIntensity > 0f || delta.gradientLeftTintLuminosity > 0f || delta.gradientLeftFeather != 0.5f) delta.gradientLeftFeather   else gradientLeftFeather,
            gradientLeftTintColor      = if (delta.gradientLeftTintColor != AndroidColor.WHITE) delta.gradientLeftTintColor else gradientLeftTintColor,
            gradientLeftTintLuminosity = (gradientLeftTintLuminosity + delta.gradientLeftTintLuminosity).coerceIn(0f, 1f),
            gradientLeftBlendMode      = if (delta.gradientLeftIntensity > 0f || delta.gradientLeftTintLuminosity > 0f || delta.gradientLeftBlendMode != RawGradientBlendMode.Solid) delta.gradientLeftBlendMode else gradientLeftBlendMode,
            gradientRightIntensity     = (gradientRightIntensity + delta.gradientRightIntensity ).coerceIn(0f, 1f),
            gradientRightLength        = if (delta.gradientRightIntensity > 0f || delta.gradientRightTintLuminosity > 0f || delta.gradientRightLength != 0.3f) delta.gradientRightLength   else gradientRightLength,
            gradientRightFeather       = if (delta.gradientRightIntensity > 0f || delta.gradientRightTintLuminosity > 0f || delta.gradientRightFeather != 0.5f) delta.gradientRightFeather  else gradientRightFeather,
            gradientRightTintColor     = if (delta.gradientRightTintColor != AndroidColor.WHITE) delta.gradientRightTintColor else gradientRightTintColor,
            gradientRightTintLuminosity = (gradientRightTintLuminosity + delta.gradientRightTintLuminosity).coerceIn(0f, 1f),
            gradientRightBlendMode     = if (delta.gradientRightIntensity > 0f || delta.gradientRightTintLuminosity > 0f || delta.gradientRightBlendMode != RawGradientBlendMode.Solid) delta.gradientRightBlendMode else gradientRightBlendMode,
            // Second-tint forwarding (sticky: once enabled by either base or delta,
            // stays enabled). Each second-color field follows the same delta-takes-
            // precedence pattern as the first color.
            gradientTopEnable2         = gradientTopEnable2 || delta.gradientTopEnable2,
            gradientTopIntensity2      = (gradientTopIntensity2    + delta.gradientTopIntensity2   ).coerceIn(0f, 1f),
            gradientTopLength2         = if (delta.gradientTopIntensity2 > 0f) delta.gradientTopLength2    else gradientTopLength2,
            gradientTopFeather2        = if (delta.gradientTopIntensity2 > 0f) delta.gradientTopFeather2   else gradientTopFeather2,
            gradientTopTintColor2      = if (delta.gradientTopTintColor2 != AndroidColor.WHITE) delta.gradientTopTintColor2 else gradientTopTintColor2,
            gradientTopTintLuminosity2 = (gradientTopTintLuminosity2 + delta.gradientTopTintLuminosity2).coerceIn(0f, 1f),
            gradientBottomEnable2      = gradientBottomEnable2 || delta.gradientBottomEnable2,
            gradientBottomIntensity2   = (gradientBottomIntensity2 + delta.gradientBottomIntensity2).coerceIn(0f, 1f),
            gradientBottomLength2      = if (delta.gradientBottomIntensity2 > 0f) delta.gradientBottomLength2  else gradientBottomLength2,
            gradientBottomFeather2     = if (delta.gradientBottomIntensity2 > 0f) delta.gradientBottomFeather2 else gradientBottomFeather2,
            gradientBottomTintColor2   = if (delta.gradientBottomTintColor2 != AndroidColor.WHITE) delta.gradientBottomTintColor2 else gradientBottomTintColor2,
            gradientBottomTintLuminosity2 = (gradientBottomTintLuminosity2 + delta.gradientBottomTintLuminosity2).coerceIn(0f, 1f),
            gradientLeftEnable2        = gradientLeftEnable2 || delta.gradientLeftEnable2,
            gradientLeftIntensity2     = (gradientLeftIntensity2   + delta.gradientLeftIntensity2  ).coerceIn(0f, 1f),
            gradientLeftLength2        = if (delta.gradientLeftIntensity2 > 0f) delta.gradientLeftLength2    else gradientLeftLength2,
            gradientLeftFeather2       = if (delta.gradientLeftIntensity2 > 0f) delta.gradientLeftFeather2   else gradientLeftFeather2,
            gradientLeftTintColor2     = if (delta.gradientLeftTintColor2 != AndroidColor.WHITE) delta.gradientLeftTintColor2 else gradientLeftTintColor2,
            gradientLeftTintLuminosity2 = (gradientLeftTintLuminosity2 + delta.gradientLeftTintLuminosity2).coerceIn(0f, 1f),
            gradientRightEnable2       = gradientRightEnable2 || delta.gradientRightEnable2,
            gradientRightIntensity2    = (gradientRightIntensity2  + delta.gradientRightIntensity2 ).coerceIn(0f, 1f),
            gradientRightLength2       = if (delta.gradientRightIntensity2 > 0f) delta.gradientRightLength2   else gradientRightLength2,
            gradientRightFeather2      = if (delta.gradientRightIntensity2 > 0f) delta.gradientRightFeather2  else gradientRightFeather2,
            gradientRightTintColor2    = if (delta.gradientRightTintColor2 != AndroidColor.WHITE) delta.gradientRightTintColor2 else gradientRightTintColor2,
            gradientRightTintLuminosity2 = (gradientRightTintLuminosity2 + delta.gradientRightTintLuminosity2).coerceIn(0f, 1f),
            // Mask fields intentionally NOT merged here: each RawAction with a
            // non-null maskPath owns its own mask + adjustments and is applied as
            // a separate render pass on top of the baseline. The baseline merge
            // covers only global, non-localized edits. See [RawPipelineCoordinator]
            // for the per-layer apply sequence.
            // v3: surface the inflight macro's mask sliders to the live
            // shader. v2 left these stranded on the action card (the
            // coordinator applied them per-layer at Apply time), but v3
            // composites at render time so the delta's values must win
            // whenever the user is dialing them right now.
            maskBrightness  = if (delta.maskBrightness  != 0f) delta.maskBrightness  else maskBrightness,
            maskContrast    = if (delta.maskContrast    != 0f) delta.maskContrast    else maskContrast,
            maskTemperature = if (delta.maskTemperature != 0)  delta.maskTemperature else maskTemperature,
            maskTint        = if (delta.maskTint        != 0f) delta.maskTint        else maskTint,
            maskSaturation  = if (delta.maskSaturation  != 0f) delta.maskSaturation  else maskSaturation,
            maskClarity     = if (delta.maskClarity     != 0f) delta.maskClarity     else maskClarity,
            maskSharpness   = if (delta.maskSharpness   != 0f) delta.maskSharpness   else maskSharpness,
            maskTone        = MaskToneRegions(
                highlights = if (delta.maskTone.highlights != 0f) delta.maskTone.highlights else maskTone.highlights,
                shadows    = if (delta.maskTone.shadows    != 0f) delta.maskTone.shadows    else maskTone.shadows,
                whites     = if (delta.maskTone.whites     != 0f) delta.maskTone.whites     else maskTone.whites,
                blacks     = if (delta.maskTone.blacks     != 0f) delta.maskTone.blacks     else maskTone.blacks,
            ),
            // Luminance mask is a unit gated by spread: when the delta activates
            // it (spread > 0), take all three; else keep the base (target 0 =
            // "shadows" is valid, so don't merge target/feather field-by-field).
            maskLumTarget   = if (delta.maskLumSpread != 0f) delta.maskLumTarget  else maskLumTarget,
            maskLumSpread   = if (delta.maskLumSpread != 0f) delta.maskLumSpread  else maskLumSpread,
            maskLumFeather  = if (delta.maskLumSpread != 0f) delta.maskLumFeather else maskLumFeather,
            maskLumCombine  = if (delta.maskLumSpread != 0f) delta.maskLumCombine else maskLumCombine,
            vignetteSegmentation  = if (delta.vignetteSegmentation  != SegmentTarget.All) delta.vignetteSegmentation  else vignetteSegmentation,
            gradientTopApplyTo    = if (delta.gradientTopApplyTo    != SegmentTarget.All) delta.gradientTopApplyTo    else gradientTopApplyTo,
            gradientBottomApplyTo = if (delta.gradientBottomApplyTo != SegmentTarget.All) delta.gradientBottomApplyTo else gradientBottomApplyTo,
            gradientLeftApplyTo   = if (delta.gradientLeftApplyTo   != SegmentTarget.All) delta.gradientLeftApplyTo   else gradientLeftApplyTo,
            gradientRightApplyTo  = if (delta.gradientRightApplyTo  != SegmentTarget.All) delta.gradientRightApplyTo  else gradientRightApplyTo,
            outputColorSpace      = if (delta.outputColorSpace != RawColorSpace.SRGB) delta.outputColorSpace else outputColorSpace,
            // Sticky: once the user opts in to GPS stripping, it stays on across merges.
            stripGps              = stripGps || delta.stripGps,
            // PREQ-Port fields — latest non-zero wins (additive intent for effects).
            grainRoughness      = if (delta.grainRoughness      != 0f) delta.grainRoughness      else grainRoughness,
            sharpenMask         = if (delta.sharpenMask         != 0f) delta.sharpenMask         else sharpenMask,
            colorDensity        = if (delta.colorDensity        != 0f) delta.colorDensity        else colorDensity,
            skintoneWarm        = if (delta.skintoneWarm        != 0f) delta.skintoneWarm        else skintoneWarm,
            skintoneSmooth      = if (delta.skintoneSmooth      != 0f) delta.skintoneSmooth      else skintoneSmooth,
            skintoneLuma        = if (delta.skintoneLuma        != 0f) delta.skintoneLuma        else skintoneLuma,
            midtoneDetails      = if (delta.midtoneDetails      != 0f) delta.midtoneDetails      else midtoneDetails,
            highlightRecovery   = if (delta.highlightRecovery   != 0f) delta.highlightRecovery   else highlightRecovery,
            pushPull            = if (delta.pushPull            != 0f) delta.pushPull            else pushPull,
            lutColorDensity     = if (delta.lutColorDensity     != 0f) delta.lutColorDensity     else lutColorDensity,
            lutSkintoneBalance  = if (delta.lutSkintoneBalance  != 0f) delta.lutSkintoneBalance  else lutSkintoneBalance,
            aberStrength        = if (delta.aberStrength        != 0f) delta.aberStrength        else aberStrength,
            aberFringeReduce    = if (delta.aberFringeReduce    != 0f) delta.aberFringeReduce    else aberFringeReduce,
            fxBlurStyle         = if (delta.fxBlurStyle         != 0)  delta.fxBlurStyle         else fxBlurStyle,
            fxGaussBlur         = if (delta.fxGaussBlur         != 0f) delta.fxGaussBlur         else fxGaussBlur,
            // Was missing from the fold entirely → always reset to false.
            fxBlurExcludeSubject = fxBlurExcludeSubject || delta.fxBlurExcludeSubject,
            fxDirBlurAmt        = if (delta.fxDirBlurAmt        != 0f) delta.fxDirBlurAmt        else fxDirBlurAmt,
            fxDirBlurAngle      = if (delta.fxDirBlurAngle      != 0f) delta.fxDirBlurAngle      else fxDirBlurAngle,
            fxRadBlurAmt        = if (delta.fxRadBlurAmt        != 0f) delta.fxRadBlurAmt        else fxRadBlurAmt,
            fxZoomBlurAmt       = if (delta.fxZoomBlurAmt       != 0f) delta.fxZoomBlurAmt       else fxZoomBlurAmt,
            fxMist              = if (delta.fxMist              != 0f) delta.fxMist              else fxMist,
            fxMistWarmth        = if (delta.fxMistWarmth        != 0f) delta.fxMistWarmth        else fxMistWarmth,
            fxDust              = if (delta.fxDust              != 0f) delta.fxDust              else fxDust,
            fxDustSize          = if (delta.fxDustSize          != 0f) delta.fxDustSize          else fxDustSize,
            fxVintageStrength   = if (delta.fxVintageStrength   != 0f) delta.fxVintageStrength   else fxVintageStrength,
            fxVintageFade       = if (delta.fxVintageFade       != 0f) delta.fxVintageFade       else fxVintageFade,
            fxVintageVig        = if (delta.fxVintageVig        != 0f) delta.fxVintageVig        else fxVintageVig,
            fxVintageMistIntensity = if (delta.fxVintageMistIntensity != 0f) delta.fxVintageMistIntensity else fxVintageMistIntensity,
            fxVintageMistScale     = if (delta.fxVintageMistScale     != 1f) delta.fxVintageMistScale     else fxVintageMistScale,
            fxVintageTextureIntensity = if (delta.fxVintageTextureIntensity != 0f) delta.fxVintageTextureIntensity else fxVintageTextureIntensity,
            fxVintageTextureScale     = if (delta.fxVintageTextureScale     != 1f) delta.fxVintageTextureScale     else fxVintageTextureScale,
            fxGlowStrength      = if (delta.fxGlowStrength      != 0f) delta.fxGlowStrength      else fxGlowStrength,
            fxGlowSpread        = if (delta.fxGlowSpread        != 0f) delta.fxGlowSpread        else fxGlowSpread,
            fxGlowWarmth        = if (delta.fxGlowWarmth        != 0f) delta.fxGlowWarmth        else fxGlowWarmth,
            // Transient: take the stronger value so AI Expose shoulder survives composition.
            filmicHlProtect     = maxOf(filmicHlProtect, delta.filmicHlProtect),
            // Color Grading wheels — per-channel latest-non-zero-wins, like the
            // mask-tone regions above. Without this the auto-apply round-trip
            // (buildIndividualCards → mergeWith → composeTabMacro) drops every
            // wheel edit and the controlled sliders/disc snap back (frozen UI).
            cgShadows    = cgShadows.mergeWheel(delta.cgShadows),
            cgMidtones   = cgMidtones.mergeWheel(delta.cgMidtones),
            cgHighlights = cgHighlights.mergeWheel(delta.cgHighlights),
            cgGlobal     = cgGlobal.mergeWheel(delta.cgGlobal),
            filmResponse = filmResponse.mergeFilm(delta.filmResponse),
            hslExt       = hslExt.mergeHslExt(delta.hslExt),
            // Lens flare: brightness gates the whole holder (x/y default -0.5 are
            // non-zero, so per-field "non-zero wins" would misfire). When the
            // delta turns the flare on, take its full config; else keep base.
            lensFlare    = if (delta.lensFlare.brightness != 0f) delta.lensFlare else lensFlare,
            // Color shift: per-channel latest-non-zero-wins.
            colorShift   = ColorShift(
                redX   = if (delta.colorShift.redX   != 0f) delta.colorShift.redX   else colorShift.redX,
                greenX = if (delta.colorShift.greenX != 0f) delta.colorShift.greenX else colorShift.greenX,
                blueX  = if (delta.colorShift.blueX  != 0f) delta.colorShift.blueX  else colorShift.blueX,
            ),
            // Informational tag: carry forward the most recent non-null scene label.
            scenePreset         = delta.scenePreset ?: scenePreset,
        )
    }

    /**
     * True when this macro carries a non-default crop rect — used by
     * mergeWith to decide whether the delta should override the
     * baseline's crop. Tolerance is tight (0.001) so floating-point
     * drift on identity rects doesn't accidentally count as active.
     */
    fun isCropActive(): Boolean =
        cropL > 0.001f || cropT > 0.001f || cropR < 0.999f || cropB < 0.999f

    /** True when a discrete orientation (90° rotate or flip) is set. Kept
     *  separate from [isCropActive] because orientation is not a region-of-
     *  interest — the Stage A ROI loader keys off the rect only. */
    fun isOrientationActive(): Boolean =
        cropRotate90 != 0 || cropFlipH || cropFlipV

    /**
     * Details Sharpness slider: Smart Sharp stays in the same kernels at 1:2.
     * UI sharpness 15 → [sharpness]=15, [smartSharpness]=0.30 (display 30).
     */
    fun withLinkedSharpness(sharpnessUi: Float): UserMacro {
        val s = sharpnessUi.coerceIn(0f, 100f)
        return copy(sharpness = s, smartSharpness = (s * 2f / 100f).coerceIn(0f, 1f))
    }

    fun opticalSpread(): Float = optical.getOrElse(0) { 0f }

    private fun grainPackActive(g: FloatArray): Boolean {
        val d = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 0f, 1f, 0f)
        for (i in d.indices) {
            val v = g.getOrElse(i) { d[i] }
            if (kotlin.math.abs(v - d[i]) > 1e-4f) return true
        }
        return false
    }
    fun opticalHalation(): Float = optical.getOrElse(1) { 0f }
    fun opticalDirection(): Float = optical.getOrElse(2) { 0f }

    fun withOptical(
        spread: Float? = null,
        halation: Float? = null,
        direction: Float? = null,
    ): UserMacro {
        val next = optical.copyOf(7)
        if (next.size > 6 && optical.size < 7) next[6] = 1f
        if (spread != null) next[0] = spread
        if (halation != null) next[1] = halation
        if (direction != null) next[2] = direction
        return copy(optical = next)
    }
}
