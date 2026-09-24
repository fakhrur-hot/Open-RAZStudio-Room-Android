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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.util.Xml
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmCurve
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmResponse
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.HslExtended
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawGradientBlendMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskToneRegions
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.SegmentTarget
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.LutLayer
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.EffectRegistry
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.io.StringWriter

/**
 * XML serializer / deserializer for a [List]<[RawAction]>.
 *
 * Format (version "1"):
 * ```xml
 * <rawActions version="1">
 *   <action id="…" label="…" tabIndex="1" isLocked="false" isVisible="true">
 *     <macro>
 *       <exposure>0.5</exposure>
 *       <contrast>20.0</contrast>
 *       …
 *       <toneCurve ch="0">0.0,0.25,0.5,0.75,1.0</toneCurve>
 *       …
 *     </macro>
 *   </action>
 * </rawActions>
 * ```
 *
 * The [RawAction.Original] sentinel is intentionally excluded from serialization;
 * callers must re-add it after deserialization if needed.
 */
internal object RawActionSerializer {

    private const val VERSION = "2"
    private val NS: String? = null

    // ── Serialization ─────────────────────────────────────────────────────────

    /**
     * @param stripMaskFields When true, photo-specific brush-mask adjustments
     * (maskBrightness/Contrast/Temperature/Tint/Saturation and contrastBoost) are
     * zeroed out before writing.  Use for export-to-file; leave false for auto-save.
     */
    fun serialize(actions: List<RawAction>, stripMaskFields: Boolean = false): String {
        val sw = StringWriter()
        val s = Xml.newSerializer()
        s.setOutput(sw)
        s.startDocument("UTF-8", true)
        s.startTag(NS, "rawActions")
        s.attribute(NS, "version", VERSION)
        for (action in actions) {
            if (action.id == RawAction.ORIGINAL_ID) continue
            val macro = if (stripMaskFields) action.macro.copy(
                contrastBoost         = 0f,
                maskBrightness        = 0f,
                maskContrast          = 0f,
                maskTemperature       = 0,
                maskTint              = 0f,
                maskSaturation        = 0f,
                maskClarity           = 0f,
                maskSharpness         = 0f,
                maskTone              = MaskToneRegions(),
                vignetteSegmentation  = SegmentTarget.All,
                gradientTopApplyTo    = SegmentTarget.All,
                gradientBottomApplyTo = SegmentTarget.All,
                gradientLeftApplyTo   = SegmentTarget.All,
                gradientRightApplyTo  = SegmentTarget.All,
                // Vignette center is photo-specific (tied to composition)
                vignetteCenterX = 0.5f,
                vignetteCenterY = 0.5f,
            ) else action.macro
            s.startTag(NS, "action")
            s.attribute(NS, "id",        action.id)
            s.attribute(NS, "label",     action.label)
            s.attribute(NS, "tabIndex",  action.tabIndex.toString())
            s.attribute(NS, "isLocked",  action.isLocked.toString())
            s.attribute(NS, "isVisible", action.isVisible.toString())
            // Persist the AUTO EXPO marker so presets (and batch) recompute
            // exposure per-file rather than freezing the editor photo's values.
            if (action.isAutoExposure) s.attribute(NS, "isAutoExposure", "true")
            if (action.isAutoBright)   s.attribute(NS, "isAutoBright", "true")
            // `maskPath` is photo-private (points to a PNG on this device); we
            // persist it so the per-photo auto-save can restore mask layers, but
            // skip it on export (external XMLs would carry a stale path).
            if (!stripMaskFields && action.maskPath != null) {
                s.attribute(NS, "maskPath", action.maskPath)
            }
            // Portable preset: a mask card that was produced by an auto-
            // segmentation button carries its class name so replay on a
            // different photo can re-derive the bitmap.
            if (action.maskClass != null) {
                s.attribute(NS, "maskClass", action.maskClass.name)
            }
            if (action.maskClasses.isNotEmpty()) {
                s.attribute(NS, "maskClasses",
                    action.maskClasses.joinToString(",") { it.name })
            }
            writeMacro(s, macro)
            s.endTag(NS, "action")
        }
        s.endTag(NS, "rawActions")
        s.endDocument()
        return sw.toString()
    }

    private fun writeMacro(s: org.xmlpull.v1.XmlSerializer, m: UserMacro) {
        s.startTag(NS, "macro")
        fun t(name: String, v: Any) {
            s.startTag(NS, name); s.text(v.toString()); s.endTag(NS, name)
        }
        // LUT: two committed slots + the transient editing fields. "lutEdited"
        // is always written (even "false") so the reader can detect the new
        // format and migrate older lutLayer-based presets.
        t("lut1CubeUri",   m.lut1CubeUri)
        t("lut1Intensity", m.lut1Intensity)
        t("lut2CubeUri",   m.lut2CubeUri)
        t("lut2Intensity", m.lut2Intensity)
        t("lutCubeUri",    m.lutCubeUri)
        t("lutIntensity",  m.lutIntensity)
        t("lutSlot",       m.lutSlot)
        t("lutEdited",     m.lutEdited)
        t("contrastBoost", m.contrastBoost)
        t("exposure",      m.exposure)
        t("smartBright",   m.smartBright)
        t("whiteBalance",  m.whiteBalance)
        t("tint",          m.tint)
        t("highlights",    m.highlights)
        t("shadows",       m.shadows)
        t("whites",        m.whites)
        t("blacks",        m.blacks)
        t("contrast",      m.contrast)
        t("saturation",    m.saturation)
        t("vibrance",      m.vibrance)
        t("clarity",       m.clarity)
        t("clarityLift",   m.clarityLift)
        t("dehaze",        m.dehaze)
        t("texture",       m.texture)
        t("sharpness",     m.sharpness)
        t("noiseReduction",m.noiseReduction)
        t("vignetteAmount",    m.vignetteAmount)
        t("vignetteFeather",   m.vignetteFeather)
        t("vignetteIntensity", m.vignetteIntensity)
        t("vignetteEffect",    m.vignetteEffect.name)
        t("vignetteCenterX",   m.vignetteCenterX)
        t("vignetteCenterY",   m.vignetteCenterY)
        m.toneCurvePoints.forEachIndexed { i, pts ->
            s.startTag(NS, "toneCurve")
            s.attribute(NS, "ch", i.toString())
            s.text(pts.joinToString(","))
            s.endTag(NS, "toneCurve")
        }
        t("filmCurveContrast",      m.filmCurve.contrast)
        t("filmCurvePivot",         m.filmCurve.pivot)
        t("filmCurveHighlightKnee", m.filmCurve.highlightKnee)
        t("filmCurveShadowToe",     m.filmCurve.shadowToe)
        t("hslRedHue",    m.hslRedHue);    t("hslRedSat",    m.hslRedSat);    t("hslRedLum",    m.hslRedLum)
        t("hslOrangeHue", m.hslOrangeHue); t("hslOrangeSat", m.hslOrangeSat); t("hslOrangeLum", m.hslOrangeLum)
        t("hslYellowHue", m.hslYellowHue); t("hslYellowSat", m.hslYellowSat); t("hslYellowLum", m.hslYellowLum)
        t("hslGreenHue",  m.hslGreenHue);  t("hslGreenSat",  m.hslGreenSat);  t("hslGreenLum",  m.hslGreenLum)
        t("hslAquaHue",   m.hslAquaHue);   t("hslAquaSat",   m.hslAquaSat);   t("hslAquaLum",   m.hslAquaLum)
        t("hslBlueHue",   m.hslBlueHue);   t("hslBlueSat",   m.hslBlueSat);   t("hslBlueLum",   m.hslBlueLum)
        // Color Zones expansion — 6 extra anchors. Optional in older blobs.
        t("hslYellowGreenHue", m.hslExt.yellowGreenHue); t("hslYellowGreenSat", m.hslExt.yellowGreenSat); t("hslYellowGreenLum", m.hslExt.yellowGreenLum)
        t("hslSpringGreenHue", m.hslExt.springGreenHue); t("hslSpringGreenSat", m.hslExt.springGreenSat); t("hslSpringGreenLum", m.hslExt.springGreenLum)
        t("hslSkyBlueHue",     m.hslExt.skyBlueHue);     t("hslSkyBlueSat",     m.hslExt.skyBlueSat);     t("hslSkyBlueLum",     m.hslExt.skyBlueLum)
        t("hslPurpleHue",      m.hslExt.purpleHue);      t("hslPurpleSat",      m.hslExt.purpleSat);      t("hslPurpleLum",      m.hslExt.purpleLum)
        t("hslMagentaHue",     m.hslExt.magentaHue);     t("hslMagentaSat",     m.hslExt.magentaSat);     t("hslMagentaLum",     m.hslExt.magentaLum)
        t("hslPinkHue",        m.hslExt.pinkHue);        t("hslPinkSat",        m.hslExt.pinkSat);        t("hslPinkLum",        m.hslExt.pinkLum)
        t("smartSharpness",     m.smartSharpness)
        t("smoothBackground",   m.smoothBackground)
        t("luminanceNR",        m.luminanceNR)
        t("colorNR",            m.colorNR)
        t("blueNR",             m.blueNR)
        t("redNR",              m.redNR)
        t("filmGrain",          m.filmGrain)
        t("filmGrainSize",      m.filmGrainSize)
        t("filmGrainWashOut",   m.filmGrainWashOut)
        t("bokehBlur",          m.bokehBlur.toFloat())
        t("bokehBalls",         m.bokehBalls.toFloat())
        t("bokehSpread",        m.bokehSpread)
        t("gradientAngle",             m.gradientAngle)
        t("gradientTopIntensity",      m.gradientTopIntensity)
        t("gradientTopLength",         m.gradientTopLength)
        t("gradientTopFeather",        m.gradientTopFeather)
        t("gradientTopTintColor",      m.gradientTopTintColor)
        t("gradientTopTintLuminosity", m.gradientTopTintLuminosity)
        t("gradientTopBlendMode",      m.gradientTopBlendMode.name)
        t("gradientBottomIntensity",      m.gradientBottomIntensity)
        t("gradientBottomLength",         m.gradientBottomLength)
        t("gradientBottomFeather",        m.gradientBottomFeather)
        t("gradientBottomTintColor",      m.gradientBottomTintColor)
        t("gradientBottomTintLuminosity", m.gradientBottomTintLuminosity)
        t("gradientBottomBlendMode",      m.gradientBottomBlendMode.name)
        t("gradientLeftIntensity",      m.gradientLeftIntensity)
        t("gradientLeftLength",         m.gradientLeftLength)
        t("gradientLeftFeather",        m.gradientLeftFeather)
        t("gradientLeftTintColor",      m.gradientLeftTintColor)
        t("gradientLeftTintLuminosity", m.gradientLeftTintLuminosity)
        t("gradientLeftBlendMode",      m.gradientLeftBlendMode.name)
        t("gradientRightIntensity",      m.gradientRightIntensity)
        t("gradientRightLength",         m.gradientRightLength)
        t("gradientRightFeather",        m.gradientRightFeather)
        t("gradientRightTintColor",      m.gradientRightTintColor)
        t("gradientRightTintLuminosity", m.gradientRightTintLuminosity)
        t("gradientRightBlendMode",      m.gradientRightBlendMode.name)
        t("maskBrightness",              m.maskBrightness)
        t("maskContrast",                m.maskContrast)
        t("maskTemperature",             m.maskTemperature)
        t("maskTint",                    m.maskTint)
        t("maskSaturation",              m.maskSaturation)
        t("maskClarity",                 m.maskClarity)
        t("maskSharpness",               m.maskSharpness)
        // Film response (LUT tab). The live preview is rebuilt by REPLAYING the
        // action stack, so anything missing here is written into a card and read
        // back as its default — the slider moves, the dot lights up, and the
        // picture never changes. That is exactly what happened when these were
        // first shipped (2026-09-07). Flat keys for a nested holder, same shape
        // as maskTone below.
        t("filmRecovery",                m.filmResponse.recovery)
        t("filmFillLight",               m.filmResponse.fillLight)
        t("filmMonochrome",              m.filmResponse.monochrome)
        t("filmGrayRed",                 m.filmResponse.grayRed)
        t("filmGrayOrange",              m.filmResponse.grayOrange)
        t("filmGrayYellow",              m.filmResponse.grayYellow)
        t("filmGrayGreen",               m.filmResponse.grayGreen)
        t("filmGrayAqua",                m.filmResponse.grayAqua)
        t("filmGrayBlue",                m.filmResponse.grayBlue)
        t("filmGrayPurple",              m.filmResponse.grayPurple)
        t("filmGrayMagenta",             m.filmResponse.grayMagenta)
        t("filmSeparation",              m.filmResponse.separation)
        t("maskHighlights",              m.maskTone.highlights)
        t("maskShadows",                 m.maskTone.shadows)
        t("maskWhites",                  m.maskTone.whites)
        t("maskBlacks",                  m.maskTone.blacks)
        // Luminance-range mask — photo-agnostic (a tone range, not a per-photo
        // brush PNG), so it is NOT stripped for presets: it transfers to any image.
        t("maskLumTarget",               m.maskLumTarget)
        t("maskLumSpread",               m.maskLumSpread)
        t("maskLumFeather",              m.maskLumFeather)
        t("maskLumCombine",              m.maskLumCombine)
        t("vignetteSegmentation",        m.vignetteSegmentation.name)
        t("gradientTopApplyTo",          m.gradientTopApplyTo.name)
        t("gradientBottomApplyTo",       m.gradientBottomApplyTo.name)
        t("gradientLeftApplyTo",         m.gradientLeftApplyTo.name)
        t("gradientRightApplyTo",        m.gradientRightApplyTo.name)
        t("outputColorSpace",            m.outputColorSpace.name)
        // ── CLAHE pre-pass (always-on now). claheEnabled deliberately
        //    not written: it's pinned to true by mergeWith / batch /
        //    action-replay and reading a stale `false` from a legacy
        //    XML would re-introduce the "Shadows/Highlights boost
        //    sliders don't work" bug we fixed earlier.
        t("claheShadowsBoost",   m.claheShadowsBoost)
        t("claheHighlightsBoost",m.claheHighlightsBoost)
        // Subject Pop — subject-masked CPU tonal grade (final fusion pass).
        t("subjectPopEnabled",    m.subjectPopEnabled)
        t("subjectPopShadow",     m.subjectPopShadow)
        t("subjectPopHighlight",  m.subjectPopHighlight)
        t("subjectPopSaturation", m.subjectPopSaturation)
        // ── LUT-tab tonal trims (post-LUT vibrancy + zone WB). All
        //    affect render but were missing from the serializer, so
        //    presets that included them rendered flatter on reload.
        t("lutHighlightVibrancy",m.lutHighlightVibrancy)
        t("highlightTemperature",m.highlightTemperature)
        t("highlightTint",       m.highlightTint)
        t("shadowTemperature",   m.shadowTemperature)
        t("shadowTint",          m.shadowTint)
        // ── Glamour Glow removed entirely
        // ── Tonemap-tab ambiance
        t("ambiance",            m.ambiance)
        t("ortonStrength",       m.ortonStrength)
        // Bloom "Protect subject" pair + FX-blur subject exclusion. Were not
        // persisted, so the action stack lost them on autosave/reopen.
        t("bloomExcludeSubject", if (m.bloomExcludeSubject) 1f else 0f)
        t("subjectBloom",        m.subjectBloom)
        t("fxBlurExcludeSubject", if (m.fxBlurExcludeSubject) 1f else 0f)
        t("toneCurveLumaMode",   if (m.toneCurveLumaMode) 1f else 0f)
        // Per-segment Auto-Expo outputs. Live in the macro so the render
        // matches; previously evaporated on close because they weren't here.
        t("whitesSubject",       m.whitesSubject)
        t("whitesBackground",    m.whitesBackground)
        t("blacksSubject",       m.blacksSubject)
        t("blacksBackground",    m.blacksBackground)
        t("shadowsSubject",      m.shadowsSubject)
        t("shadowsBackground",   m.shadowsBackground)
        t("highlightsSubject",   m.highlightsSubject)
        t("highlightsBackground",m.highlightsBackground)
        t("ambianceSubject",     m.ambianceSubject)
        t("ambianceBackground",  m.ambianceBackground)
        // ── Registry-driven scalar effects (v2+) ──────────────────────────
        // Adding a new effect? Add it to EffectRegistry — not here.
        EffectRegistry.writeAll(s, m, NS)
        s.endTag(NS, "macro")
    }

    // ── Deserialization ────────────────────────────────────────────────────────

    fun deserialize(xml: String): List<RawAction> = runCatching {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val result   = mutableListOf<RawAction>()
        val macroMap = mutableMapOf<String, String>()
        val curveMap = mutableMapOf<Int, List<Float>>()
        val lutLayers = mutableListOf<LutLayer>()

        var id        = ""
        var label     = ""
        var tabIndex  = 0
        var isLocked  = false
        var isVisible = true
        var isAutoExposure = false
        var isAutoBright = false
        var maskPath: String? = null
        var maskClass: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass? = null
        var maskClasses: List<com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass> = emptyList()
        var currentTag = ""
        var currentLutLayerIntensity = 1f
        var inMacro    = false
        var inAction   = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "rawActions" -> {
                        val v = parser.getAttributeValue(NS, "version") ?: "1"
                        if (v != VERSION) android.util.Log.i("RawActionSerializer",
                            "Loading preset schema v$v (current=$VERSION); Phase-1 effects will default to 0")
                    }
                    "action" -> {
                        inAction  = true
                        id        = parser.getAttributeValue(NS, "id")       ?: java.util.UUID.randomUUID().toString()
                        label     = parser.getAttributeValue(NS, "label")    ?: ""
                        tabIndex  = parser.getAttributeValue(NS, "tabIndex") ?.toIntOrNull() ?: 0
                        isLocked  = parser.getAttributeValue(NS, "isLocked") ?.toBooleanStrictOrNull() ?: false
                        isVisible = parser.getAttributeValue(NS, "isVisible")?.toBooleanStrictOrNull() ?: true
                        isAutoExposure = parser.getAttributeValue(NS, "isAutoExposure")?.toBooleanStrictOrNull() ?: false
                        isAutoBright = parser.getAttributeValue(NS, "isAutoBright")?.toBooleanStrictOrNull() ?: false
                        maskPath  = parser.getAttributeValue(NS, "maskPath")
                        maskClass = parser.getAttributeValue(NS, "maskClass")?.let { name ->
                            runCatching {
                                com.RAZStudio.StudioRoom.feature.photo_editor.raw
                                    .model.MaskClass.valueOf(name)
                            }.getOrNull()
                        }
                        maskClasses = parser.getAttributeValue(NS, "maskClasses")
                            ?.split(",")
                            ?.mapNotNull { name ->
                                runCatching {
                                    com.RAZStudio.StudioRoom.feature.photo_editor.raw
                                        .model.MaskClass.valueOf(name.trim())
                                }.getOrNull()
                            } ?: emptyList()
                        macroMap.clear(); curveMap.clear(); lutLayers.clear()
                    }
                    "macro"    -> inMacro = true
                    "toneCurve" -> currentTag = "toneCurve:${parser.getAttributeValue(NS, "ch") ?: "0"}"
                    "lutLayer" -> {
                        currentTag = "lutLayer"
                        currentLutLayerIntensity = parser.getAttributeValue(NS, "intensity")?.toFloatOrNull() ?: 1f
                    }
                    else       -> currentTag = parser.name
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        when {
                            currentTag.startsWith("toneCurve:") -> {
                                val ch = currentTag.substringAfter(":").toIntOrNull() ?: 0
                                curveMap[ch] = text.split(",").mapNotNull { it.trim().toFloatOrNull() }
                            }
                            currentTag == "lutLayer" -> {
                                lutLayers += LutLayer(cubeUri = text, intensity = currentLutLayerIntensity)
                            }
                            inMacro -> macroMap[currentTag] = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "action" -> if (inAction) {
                        result += RawAction(
                            id        = id,
                            label     = label,
                            tabIndex  = tabIndex,
                            isLocked  = isLocked,
                            isVisible = isVisible,
                            isAutoExposure = isAutoExposure,
                            isAutoBright = isAutoBright,
                            maskPath  = maskPath,
                            maskClass = maskClass,
                            maskClasses = maskClasses,
                            macro     = macroFromMap(macroMap, List(4) { i ->
                                curveMap[i] ?: UserMacro.DEFAULT_CURVE_POINTS[i]
                            }, lutLayers.toList()),
                        )
                        inAction = false
                        maskPath = null
                        maskClass = null
                        maskClasses = emptyList()
                    }
                    "macro" -> inMacro = false
                    else    -> currentTag = ""
                }
            }
            event = parser.next()
        }
        result
    }.getOrDefault(emptyList())

    private fun macroFromMap(
        m: Map<String, String>,
        curves: List<List<Float>>,
        lutStack: List<LutLayer>,
    ): UserMacro {
        fun f(k: String, d: Float = 0f) = m[k]?.toFloatOrNull() ?: d
        fun i(k: String, d: Int   = 0) = m[k]?.toIntOrNull()   ?: d
        fun blend(k: String) = runCatching { RawGradientBlendMode.valueOf(m[k] ?: "") }
            .getOrDefault(RawGradientBlendMode.Solid)
        fun seg(k: String) = runCatching { SegmentTarget.valueOf(m[k] ?: "") }
            .getOrDefault(SegmentTarget.All)
        val W = android.graphics.Color.WHITE
        // LUT slots. New presets write the slot fields (detected by the always-
        // present "lutEdited" key). Older presets carried an unbounded lutStack +
        // an editing lutCubeUri; migrate those into the two slots (first→1,
        // second→2, any extras dropped).
        val newLutFormat = m.containsKey("lutEdited")
        val lut1u: String; val lut1i: Float; val lut2u: String; val lut2i: Float
        if (newLutFormat) {
            lut1u = m["lut1CubeUri"] ?: ""; lut1i = f("lut1Intensity", 1f)
            lut2u = m["lut2CubeUri"] ?: ""; lut2i = f("lut2Intensity", 1f)
        } else {
            val migrated = buildList {
                addAll(lutStack)
                val oc = m["lutCubeUri"] ?: ""
                if (oc.isNotEmpty()) add(LutLayer(oc, f("lutIntensity", 1f)))
            }
            lut1u = migrated.getOrNull(0)?.cubeUri ?: ""; lut1i = migrated.getOrNull(0)?.intensity ?: 1f
            lut2u = migrated.getOrNull(1)?.cubeUri ?: ""; lut2i = migrated.getOrNull(1)?.intensity ?: 1f
        }
        return UserMacro(
            lut1CubeUri   = lut1u,
            lut1Intensity = lut1i,
            lut2CubeUri   = lut2u,
            lut2Intensity = lut2i,
            lutCubeUri    = if (newLutFormat) (m["lutCubeUri"] ?: "") else "",
            lutIntensity  = f("lutIntensity", 1f),
            lutSlot       = (m["lutSlot"]?.toIntOrNull() ?: 1).coerceIn(1, 2),
            lutEdited     = if (newLutFormat) (m["lutEdited"]?.toBoolean() ?: false) else false,
            contrastBoost = f("contrastBoost"),
            exposure      = f("exposure"),
            smartBright   = f("smartBright"),
            whiteBalance  = i("whiteBalance"),
            tint          = f("tint"),
            highlights    = f("highlights"),
            shadows       = f("shadows"),
            whites        = f("whites"),
            blacks        = f("blacks"),
            contrast      = f("contrast"),
            saturation    = f("saturation"),
            vibrance      = f("vibrance"),
            clarity       = f("clarity"),
            clarityLift   = f("clarityLift"),
            dehaze        = f("dehaze").coerceIn(-25f, 25f),
            texture       = f("texture"),
            sharpness     = f("sharpness"),
            noiseReduction = f("noiseReduction"),
            vignetteAmount    = f("vignetteAmount"),
            vignetteFeather   = f("vignetteFeather").takeIf { it != 0f } ?: 0.5f,
            vignetteIntensity = f("vignetteIntensity").takeIf { it != 0f } ?: 1f,
            vignetteEffect    = runCatching {
                com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.VignetteEffect.valueOf(m["vignetteEffect"] ?: "")
            }.getOrDefault(com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.VignetteEffect.All),
            vignetteCenterX   = f("vignetteCenterX").takeIf { it != 0f } ?: 0.5f,
            vignetteCenterY   = f("vignetteCenterY").takeIf { it != 0f } ?: 0.5f,
            toneCurvePoints = curves,
            filmCurve = FilmCurve(
                contrast      = f("filmCurveContrast"),
                pivot         = f("filmCurvePivot", 0.45f),
                highlightKnee = f("filmCurveHighlightKnee"),
                shadowToe     = f("filmCurveShadowToe"),
            ),
            hslRedHue    = f("hslRedHue"),    hslRedSat    = f("hslRedSat"),    hslRedLum    = f("hslRedLum"),
            hslOrangeHue = f("hslOrangeHue"), hslOrangeSat = f("hslOrangeSat"), hslOrangeLum = f("hslOrangeLum"),
            hslYellowHue = f("hslYellowHue"), hslYellowSat = f("hslYellowSat"), hslYellowLum = f("hslYellowLum"),
            hslGreenHue  = f("hslGreenHue"),  hslGreenSat  = f("hslGreenSat"),  hslGreenLum  = f("hslGreenLum"),
            hslAquaHue   = f("hslAquaHue"),   hslAquaSat   = f("hslAquaSat"),   hslAquaLum   = f("hslAquaLum"),
            hslBlueHue   = f("hslBlueHue"),   hslBlueSat   = f("hslBlueSat"),   hslBlueLum   = f("hslBlueLum"),
            hslExt = HslExtended(
                yellowGreenHue = f("hslYellowGreenHue"),
                yellowGreenSat = f("hslYellowGreenSat"),
                yellowGreenLum = f("hslYellowGreenLum"),
                springGreenHue = f("hslSpringGreenHue"),
                springGreenSat = f("hslSpringGreenSat"),
                springGreenLum = f("hslSpringGreenLum"),
                skyBlueHue = f("hslSkyBlueHue"),
                skyBlueSat = f("hslSkyBlueSat"),
                skyBlueLum = f("hslSkyBlueLum"),
                purpleHue = f("hslPurpleHue"),
                purpleSat = f("hslPurpleSat"),
                purpleLum = f("hslPurpleLum"),
                magentaHue = f("hslMagentaHue"),
                magentaSat = f("hslMagentaSat"),
                magentaLum = f("hslMagentaLum"),
                pinkHue = f("hslPinkHue"),
                pinkSat = f("hslPinkSat"),
                pinkLum = f("hslPinkLum"),
            ),
            smartSharpness     = f("smartSharpness"),
            smoothBackground   = f("smoothBackground"),
            luminanceNR        = f("luminanceNR"),
            colorNR            = f("colorNR"),
            blueNR             = f("blueNR"),
            redNR              = f("redNR"),
            filmGrain          = f("filmGrain"),
            filmGrainSize      = f("filmGrainSize", 0.5f),
            filmGrainWashOut   = f("filmGrainWashOut"),
            bokehBlur          = Math.round(f("bokehBlur")),
            bokehBalls         = Math.round(f("bokehBalls")),
            bokehSpread        = f("bokehSpread"),
            gradientAngle              = f("gradientAngle"),
            gradientTopIntensity       = f("gradientTopIntensity"),
            gradientTopLength          = f("gradientTopLength",   0.3f),
            gradientTopFeather         = f("gradientTopFeather",  0.5f),
            gradientTopTintColor       = i("gradientTopTintColor", W),
            gradientTopTintLuminosity  = f("gradientTopTintLuminosity"),
            gradientTopBlendMode       = blend("gradientTopBlendMode"),
            gradientBottomIntensity    = f("gradientBottomIntensity"),
            gradientBottomLength       = f("gradientBottomLength", 0.3f),
            gradientBottomFeather      = f("gradientBottomFeather", 0.5f),
            gradientBottomTintColor    = i("gradientBottomTintColor", W),
            gradientBottomTintLuminosity = f("gradientBottomTintLuminosity"),
            gradientBottomBlendMode    = blend("gradientBottomBlendMode"),
            gradientLeftIntensity      = f("gradientLeftIntensity"),
            gradientLeftLength         = f("gradientLeftLength",  0.3f),
            gradientLeftFeather        = f("gradientLeftFeather", 0.5f),
            gradientLeftTintColor      = i("gradientLeftTintColor", W),
            gradientLeftTintLuminosity = f("gradientLeftTintLuminosity"),
            gradientLeftBlendMode      = blend("gradientLeftBlendMode"),
            gradientRightIntensity     = f("gradientRightIntensity"),
            gradientRightLength        = f("gradientRightLength",  0.3f),
            gradientRightFeather       = f("gradientRightFeather", 0.5f),
            gradientRightTintColor     = i("gradientRightTintColor", W),
            gradientRightTintLuminosity = f("gradientRightTintLuminosity"),
            gradientRightBlendMode     = blend("gradientRightBlendMode"),
            maskBrightness    = f("maskBrightness"),
            maskContrast      = f("maskContrast"),
            maskTemperature   = i("maskTemperature"),
            maskTint          = f("maskTint"),
            maskSaturation    = f("maskSaturation"),
            maskClarity       = f("maskClarity"),
            maskSharpness     = f("maskSharpness"),
            filmResponse      = FilmResponse(
                recovery    = f("filmRecovery"),
                fillLight   = f("filmFillLight"),
                monochrome  = m["filmMonochrome"]?.toBooleanStrictOrNull() ?: false,
                grayRed     = f("filmGrayRed"),
                grayOrange  = f("filmGrayOrange"),
                grayYellow  = f("filmGrayYellow"),
                grayGreen   = f("filmGrayGreen"),
                grayAqua    = f("filmGrayAqua"),
                grayBlue    = f("filmGrayBlue"),
                grayPurple  = f("filmGrayPurple"),
                grayMagenta = f("filmGrayMagenta"),
                separation  = f("filmSeparation"),
            ),
            maskTone          = MaskToneRegions(
                highlights = f("maskHighlights"),
                shadows    = f("maskShadows"),
                whites     = f("maskWhites"),
                blacks     = f("maskBlacks"),
            ),
            maskLumTarget     = f("maskLumTarget"),
            maskLumSpread     = f("maskLumSpread"),
            maskLumFeather    = f("maskLumFeather"),
            maskLumCombine    = f("maskLumCombine").toInt(),
            vignetteSegmentation  = seg("vignetteSegmentation"),
            gradientTopApplyTo    = seg("gradientTopApplyTo"),
            gradientBottomApplyTo = seg("gradientBottomApplyTo"),
            gradientLeftApplyTo   = seg("gradientLeftApplyTo"),
            gradientRightApplyTo  = seg("gradientRightApplyTo"),
            outputColorSpace = runCatching { RawColorSpace.valueOf(m["outputColorSpace"] ?: "") }
                .getOrDefault(RawColorSpace.SRGB),
            // CLAHE pre-pass. claheEnabled is force-true at merge/replay
            // time so we don't read it from XML — legacy macros that had
            // it false would otherwise silently neuter the always-on
            // Shadows/Highlights boost sliders.
            claheShadowsBoost    = f("claheShadowsBoost"),
            claheHighlightsBoost = f("claheHighlightsBoost"),
            // Subject Pop — missing keys default to off / 0 (no effect).
            subjectPopEnabled    = m["subjectPopEnabled"]?.toBooleanStrictOrNull() ?: false,
            subjectPopShadow     = f("subjectPopShadow"),
            subjectPopHighlight  = f("subjectPopHighlight"),
            subjectPopSaturation = f("subjectPopSaturation"),
            // LUT-tab tonal trims — missing keys default to 0 (no effect),
            // matching the UserMacro defaults.
            lutHighlightVibrancy = f("lutHighlightVibrancy"),
            highlightTemperature = f("highlightTemperature"),
            highlightTint        = f("highlightTint"),
            shadowTemperature    = f("shadowTemperature"),
            shadowTint           = f("shadowTint"),
            ambiance             = f("ambiance"),
            ortonStrength        = f("ortonStrength"),
            bloomExcludeSubject  = f("bloomExcludeSubject") > 0.5f,
            subjectBloom         = f("subjectBloom"),
            fxBlurExcludeSubject = f("fxBlurExcludeSubject") > 0.5f,
            toneCurveLumaMode    = f("toneCurveLumaMode") > 0.5f,
            whitesSubject        = f("whitesSubject"),
            whitesBackground     = f("whitesBackground"),
            blacksSubject        = f("blacksSubject"),
            blacksBackground     = f("blacksBackground"),
            shadowsSubject       = f("shadowsSubject"),
            shadowsBackground    = f("shadowsBackground"),
            highlightsSubject    = f("highlightsSubject"),
            highlightsBackground = f("highlightsBackground"),
            ambianceSubject      = f("ambianceSubject"),
            ambianceBackground   = f("ambianceBackground"),
        ).let { base ->
            // ── Registry-driven scalar effects (v2+) ──────────────────────
            // Adding a new effect? Add it to EffectRegistry — not here.
            EffectRegistry.readAll(m, base)
        }
    }
}
