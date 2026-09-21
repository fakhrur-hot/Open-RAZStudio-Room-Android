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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.ColorFringingMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.DemosaicAlgorithm
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmGrainLevel
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmProfile
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.LibRawOutputColor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.LutInputSpace
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.HighlightRecoveryMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.LutLayer
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.OutputBitDepthMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawGradientBlendMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.SegmentTarget
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.ColorShift
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmCurve
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmResponse
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.HslExtended
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.ColorWheel
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.LensFlare
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskToneRegions
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.VignetteEffect
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceBitDepth
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v2-integration §7.4 — XMP sidecar round-trip.
 *
 * Output layout is a minimal XMP wrapper that DPP / exiftool can parse, with the bulk of
 * the payload carried as a JSON string inside a single `raz:payload` element. The
 * trade-off: XMP's verbose RDF structure is overkill for our ~80 macro fields, JSON keeps
 * the file ~3× smaller and round-trips reliably without an XPath dependency.
 *
 * Parsing tolerates unknown JSON keys (forward-compat). The macro JSON deliberately uses
 * the same field names as the Kotlin data class so `git diff`s on a sidecar are readable.
 */
object SidecarXmpSerializer {

    private const val NS_RAZ = "raz"
    private const val NS_RAZ_URI = "https://razstudio.app/xmp/1.0/"

    // ── Serialize ──────────────────────────────────────────────────────────────

    fun toXmp(snapshot: SidecarSnapshot): String {
        val payload = JSONObject().apply {
            put("schema", snapshot.schemaVersion)
            put("appVersion", snapshot.appVersion)
            put("lastEditedEpochMs", snapshot.lastEditedEpochMs)
            put("workspace", workspaceToJson(snapshot.workspace))
            put("macro", macroToJson(snapshot.macro))
            put("history", JSONArray().also { array ->
                for (rev in snapshot.revisions) {
                    array.put(JSONObject().apply {
                        put("ts", rev.timestampEpochMs)
                        put("macro", macroToJson(rev.macro))
                    })
                }
            })
            // M10 — committed action stack. Empty array on schema v1 reads.
            put("actions", JSONArray().also { array ->
                for (a in snapshot.actionStack) {
                    array.put(JSONObject().apply {
                        put("id", a.id)
                        put("label", a.label)
                        put("tabIndex", a.tabIndex)
                        put("visible", a.isVisible)
                        put("locked", a.isLocked)
                        if (a.maskPath != null) put("maskPath", a.maskPath)
                        if (a.maskClass != null) put("maskClass", a.maskClass)
                        if (a.maskClasses.isNotEmpty()) {
                            put("maskClasses", a.maskClasses.joinToString(","))
                        }
                        if (a.isAutoExposure) put("auto", true)
                        put("macro", macroToJson(a.macro))
                    })
                }
            })
        }
        val jsonText = payload.toString()
        val iso = isoTimestamp(snapshot.lastEditedEpochMs)
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<x:xmpmeta xmlns:x="adobe:ns:meta/">""").append('\n')
            append("""  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""").append('\n')
            append("""    <rdf:Description xmlns:$NS_RAZ="$NS_RAZ_URI">""").append('\n')
            append("""      <$NS_RAZ:schemaVersion>${snapshot.schemaVersion}</$NS_RAZ:schemaVersion>""").append('\n')
            append("""      <$NS_RAZ:lastEdited>$iso</$NS_RAZ:lastEdited>""").append('\n')
            append("""      <$NS_RAZ:payload>""")
            append(escapeXmlText(jsonText))
            append("""</$NS_RAZ:payload>""").append('\n')
            append("""    </rdf:Description>""").append('\n')
            append("""  </rdf:RDF>""").append('\n')
            append("""</x:xmpmeta>""").append('\n')
        }
    }

    // ── Parse ──────────────────────────────────────────────────────────────────

    fun fromXmp(xmp: String): SidecarSnapshot? {
        val payloadJson = extractPayload(xmp) ?: return null
        return runCatching {
            val obj = JSONObject(payloadJson)
            val workspace = jsonToWorkspace(obj.optJSONObject("workspace") ?: JSONObject())
            val macro = jsonToMacro(obj.optJSONObject("macro") ?: JSONObject())
            val revisions = mutableListOf<SidecarRevision>()
            val histArr = obj.optJSONArray("history")
            if (histArr != null) {
                for (i in 0 until histArr.length()) {
                    val rev = histArr.optJSONObject(i) ?: continue
                    val ts = rev.optLong("ts", 0L)
                    val macroJson = rev.optJSONObject("macro") ?: continue
                    revisions += SidecarRevision(ts, jsonToMacro(macroJson))
                }
            }
            // M10 — actionStack is empty on v1 sidecars (forward-compat).
            val actions = mutableListOf<SidecarActionEntry>()
            val actArr = obj.optJSONArray("actions")
            if (actArr != null) {
                for (i in 0 until actArr.length()) {
                    val a = actArr.optJSONObject(i) ?: continue
                    val id = a.optString("id", "").takeIf { it.isNotEmpty() } ?: continue
                    actions += SidecarActionEntry(
                        id        = id,
                        label     = a.optString("label", ""),
                        tabIndex  = a.optInt("tabIndex", -1),
                        macro     = a.optJSONObject("macro")?.let { jsonToMacro(it) }
                                    ?: UserMacro(),
                        isVisible = a.optBoolean("visible", true),
                        isLocked  = a.optBoolean("locked", false),
                        maskPath  = a.optString("maskPath", "").takeIf { it.isNotEmpty() },
                        maskClass = a.optString("maskClass", "").takeIf { it.isNotEmpty() },
                        maskClasses = a.optString("maskClasses", "")
                            .takeIf { it.isNotEmpty() }
                            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?: emptyList(),
                        isAutoExposure = a.optBoolean("auto", false),
                    )
                }
            }
            SidecarSnapshot(
                workspace = workspace,
                macro = macro,
                revisions = revisions,
                actionStack = actions,
                schemaVersion = obj.optInt("schema", SidecarSnapshot.CURRENT_SCHEMA_VERSION),
                appVersion = obj.optString("appVersion", ""),
                lastEditedEpochMs = obj.optLong("lastEditedEpochMs", System.currentTimeMillis()),
            )
        }.getOrNull()
    }

    private fun extractPayload(xmp: String): String? {
        val open = "<$NS_RAZ:payload>"
        val close = "</$NS_RAZ:payload>"
        val start = xmp.indexOf(open)
        if (start < 0) return null
        val end = xmp.indexOf(close, startIndex = start + open.length)
        if (end < 0) return null
        return unescapeXmlText(xmp.substring(start + open.length, end))
    }

    // ── Workspace ⇄ JSON ───────────────────────────────────────────────────────

    private fun workspaceToJson(ws: WorkspaceConfig): JSONObject = JSONObject().apply {
        put("bitDepth", ws.bitDepth.name)
        put("colorGamut", ws.colorGamut.name)
        put("demosaicAlgorithm", ws.demosaicAlgorithm.name)
        put("highlightRecovery", ws.highlightRecovery.name)
        put("nrEnabled", ws.nrEnabled)
        put("nrLuma", ws.nrLuma)
        put("nrChroma", ws.nrChroma)
        put("dcpProfileId", ws.dcpProfileId)
        put("outputBitDepthMode", ws.outputBitDepthMode.name)
        put("sidecarEnabled", ws.sidecarEnabled)
        put("dualContrastThreshold", ws.dualContrastThreshold.toDouble())
        put("dualAutoContrast", ws.dualAutoContrast)
        put("smartDefaultsEnabled", ws.smartDefaultsEnabled)
        // ── Everything below was MISSING until 2026-09-07. The 13 keys above
        // were the whole round-trip, so a project photo's lens profile
        // (lensfun*), subject detection, CA, AI-enhance, film profile, camera
        // colour profile, WB source, exposure shift … all reverted to defaults
        // on every reopen — and the gallery's copy/paste of "lens correction"
        // transplanted fields that were then dropped on write. Owner report:
        // "lens profile I set per photo is gone / missing from the EXIF
        // watermark / not shown in the three-dots menu". Read side mirrors
        // these with WorkspaceConfig.Default fallbacks, so old sidecars
        // without the keys still load unchanged.
        put("caCorrectionEnabled", ws.caCorrectionEnabled)
        put("lensfunDbDir", ws.lensfunDbDir)
        put("lensfunCameraId", ws.lensfunCameraId)
        put("lensfunLensId", ws.lensfunLensId)
        put("lensfunFocalOverrideMm", ws.lensfunFocalOverrideMm.toDouble())
        put("liftTau", ws.liftTau.toDouble())
        put("lensfunMatchConfidence", ws.lensfunMatchConfidence)
        put("lensfunAdaptedMode", ws.lensfunAdaptedMode)
        put("wbSourceOrdinal", ws.wbSourceOrdinal)
        put("exposureShiftEv", ws.exposureShiftEv.toDouble())
        put("fbddNoise", ws.fbddNoise)
        put("blackLevelDelta", ws.blackLevelDelta.toDouble())
        put("whiteLevelDelta", ws.whiteLevelDelta.toDouble())
        put("clipThreshold", ws.clipThreshold.toDouble())
        put("subjectDetectionEnabled", ws.subjectDetectionEnabled)
        put("cameraStyleFinishEnabled", ws.cameraStyleFinishEnabled)
        put("libRawOutputColor", ws.libRawOutputColor.name)
        put("lutInputSpace", ws.lutInputSpace.name)
        put("sensorCalibrationEnabled", ws.sensorCalibrationEnabled)
        put("colorFringingMode", ws.colorFringingMode.name)
        put("aiColorEnhanceEnabled", ws.aiColorEnhanceEnabled)
        put("useCameraColorProfile", ws.useCameraColorProfile)
        put("cameraProfileGuidedFilter", ws.cameraProfileGuidedFilter)
        put("aeSubjectProtection", ws.aeSubjectProtection.toDouble())
        put("filmProfile", ws.filmProfile.name)
        put("filmGrainLevel", ws.filmGrainLevel.name)
        put("hdrRecovery", ws.hdrRecovery)
        put("shadowRecovery", ws.shadowRecovery)
        put("enhanceEnabled", ws.enhanceEnabled)
        put("enhanceGuidedFilter", ws.enhanceGuidedFilter)
        put("claheHighlightsBoost", ws.claheHighlightsBoost.toDouble())
        put("defaultExportFormat", ws.defaultExportFormat)
        put("highlightProtection", ws.highlightProtection.toDouble())
    }

    private fun jsonToWorkspace(obj: JSONObject): WorkspaceConfig {
        val d = WorkspaceConfig.Default
        return WorkspaceConfig(
            bitDepth = enumOrDefault(obj, "bitDepth", d.bitDepth) { WorkspaceBitDepth.valueOf(it) },
            colorGamut = enumOrDefault(obj, "colorGamut", d.colorGamut) { RawColorSpace.valueOf(it) },
            demosaicAlgorithm = enumOrDefault(obj, "demosaicAlgorithm", d.demosaicAlgorithm) {
                DemosaicAlgorithm.valueOf(it)
            },
            highlightRecovery = enumOrDefault(obj, "highlightRecovery", d.highlightRecovery) {
                HighlightRecoveryMode.valueOf(it)
            },
            nrEnabled = obj.optBoolean("nrEnabled", d.nrEnabled),
            nrLuma = obj.optInt("nrLuma", d.nrLuma).coerceIn(0, 100),
            nrChroma = obj.optInt("nrChroma", d.nrChroma).coerceIn(0, 100),
            dcpProfileId = obj.optString("dcpProfileId", d.dcpProfileId),
            outputBitDepthMode = enumOrDefault(obj, "outputBitDepthMode", d.outputBitDepthMode) {
                OutputBitDepthMode.valueOf(it)
            },
            sidecarEnabled = obj.optBoolean("sidecarEnabled", d.sidecarEnabled),
            dualContrastThreshold = obj.optDouble("dualContrastThreshold", d.dualContrastThreshold.toDouble())
                .toFloat().coerceIn(0f, 1f),
            dualAutoContrast = obj.optBoolean("dualAutoContrast", d.dualAutoContrast),
            smartDefaultsEnabled = obj.optBoolean("smartDefaultsEnabled", d.smartDefaultsEnabled),
            // Mirrors of the write side above — every key optional, Default-backed.
            caCorrectionEnabled = obj.optBoolean("caCorrectionEnabled", d.caCorrectionEnabled),
            lensfunDbDir = obj.optString("lensfunDbDir", d.lensfunDbDir),
            lensfunCameraId = obj.optString("lensfunCameraId", d.lensfunCameraId),
            lensfunLensId = obj.optString("lensfunLensId", d.lensfunLensId),
            lensfunFocalOverrideMm = obj.optFloat("lensfunFocalOverrideMm", d.lensfunFocalOverrideMm),
            liftTau = obj.optFloat("liftTau", d.liftTau),
            lensfunMatchConfidence = obj.optInt("lensfunMatchConfidence", d.lensfunMatchConfidence),
            lensfunAdaptedMode = obj.optBoolean("lensfunAdaptedMode", d.lensfunAdaptedMode),
            wbSourceOrdinal = obj.optInt("wbSourceOrdinal", d.wbSourceOrdinal),
            exposureShiftEv = obj.optFloat("exposureShiftEv", d.exposureShiftEv),
            fbddNoise = obj.optInt("fbddNoise", d.fbddNoise),
            blackLevelDelta = obj.optFloat("blackLevelDelta", d.blackLevelDelta),
            whiteLevelDelta = obj.optFloat("whiteLevelDelta", d.whiteLevelDelta),
            clipThreshold = obj.optFloat("clipThreshold", d.clipThreshold),
            subjectDetectionEnabled = obj.optBoolean("subjectDetectionEnabled", d.subjectDetectionEnabled),
            cameraStyleFinishEnabled = obj.optBoolean("cameraStyleFinishEnabled", d.cameraStyleFinishEnabled),
            libRawOutputColor = enumOrDefault(obj, "libRawOutputColor", d.libRawOutputColor) { LibRawOutputColor.valueOf(it) },
            lutInputSpace = enumOrDefault(obj, "lutInputSpace", d.lutInputSpace) { LutInputSpace.valueOf(it) },
            sensorCalibrationEnabled = obj.optBoolean("sensorCalibrationEnabled", d.sensorCalibrationEnabled),
            colorFringingMode = enumOrDefault(obj, "colorFringingMode", d.colorFringingMode) { ColorFringingMode.valueOf(it) },
            aiColorEnhanceEnabled = obj.optBoolean("aiColorEnhanceEnabled", d.aiColorEnhanceEnabled),
            useCameraColorProfile = obj.optBoolean("useCameraColorProfile", d.useCameraColorProfile),
            cameraProfileGuidedFilter = obj.optBoolean("cameraProfileGuidedFilter", d.cameraProfileGuidedFilter),
            aeSubjectProtection = obj.optFloat("aeSubjectProtection", d.aeSubjectProtection),
            filmProfile = enumOrDefault(obj, "filmProfile", d.filmProfile) { FilmProfile.valueOf(it) },
            filmGrainLevel = enumOrDefault(obj, "filmGrainLevel", d.filmGrainLevel) { FilmGrainLevel.valueOf(it) },
            hdrRecovery = obj.optBoolean("hdrRecovery", d.hdrRecovery),
            shadowRecovery = obj.optBoolean("shadowRecovery", d.shadowRecovery),
            enhanceEnabled = obj.optBoolean("enhanceEnabled", d.enhanceEnabled),
            enhanceGuidedFilter = obj.optBoolean("enhanceGuidedFilter", d.enhanceGuidedFilter),
            claheHighlightsBoost = obj.optFloat("claheHighlightsBoost", d.claheHighlightsBoost),
            defaultExportFormat = obj.optString("defaultExportFormat", d.defaultExportFormat),
            highlightProtection = obj.optFloat("highlightProtection", d.highlightProtection),
        )
    }

    // ── Macro ⇄ JSON ──────────────────────────────────────────────────────────
    //
    // ~80 fields, each serialized verbatim by name. Defaults are pulled from a fresh
    // `UserMacro()` so missing keys round-trip to the data class default — crucial for
    // forward-compat when we add new macro fields later.

    private fun macroToJson(m: UserMacro): JSONObject = JSONObject().apply {
        // Two committed LUT slots (current format). lutStack is still written as a
        // derived mirror for any legacy reader; jsonToMacro prefers lut1/lut2.
        put("lut1CubeUri", m.lut1CubeUri)
        put("lut1Intensity", m.lut1Intensity.toDouble())
        put("lut2CubeUri", m.lut2CubeUri)
        put("lut2Intensity", m.lut2Intensity.toDouble())
        // The LUT tab commits its card with the TRANSIENT editing fields only
        // (lutSlot + lutEdited + lutCubeUri/lutIntensity); mergeWith folds them
        // into slot 1/2 at COMPOSE time, so on a committed card lut1/lut2 are
        // empty. Not writing these dropped the LUT from every project-photo
        // reopen: the card came back labelled "LUT 1 · <name>" but carrying no
        // LUT, the resolver found no file and the GL side logged "LUT cleared"
        // (owner report 2026-09-07). RawActionSerializer already writes them.
        put("lutCubeUri", m.lutCubeUri)
        put("lutIntensity", m.lutIntensity.toDouble())
        put("lutSlot", m.lutSlot)
        put("lutEdited", m.lutEdited)
        put("lutStack", JSONArray().also { arr ->
            for (layer in m.lutStack) {
                arr.put(JSONObject().apply {
                    put("uri", layer.cubeUri)
                    put("intensity", layer.intensity.toDouble())
                })
            }
        })
        put("contrastBoost", m.contrastBoost.toDouble())
        put("exposure", m.exposure.toDouble())
        put("whiteBalance", m.whiteBalance)
        put("tint", m.tint.toDouble())
        put("highlights", m.highlights.toDouble())
        put("shadows", m.shadows.toDouble())
        put("whites", m.whites.toDouble())
        put("blacks", m.blacks.toDouble())
        put("contrast", m.contrast.toDouble())
        put("saturation", m.saturation.toDouble())
        put("vibrance", m.vibrance.toDouble())
        put("clarity", m.clarity.toDouble())
        put("clarityLift", m.clarityLift.toDouble())
        put("dehaze", m.dehaze.toDouble())
        put("texture", m.texture.toDouble())
        put("sharpness", m.sharpness.toDouble())
        put("noiseReduction", m.noiseReduction.toDouble())
        put("vignetteAmount", m.vignetteAmount.toDouble())
        put("vignetteFeather", m.vignetteFeather.toDouble())
        put("vignetteIntensity", m.vignetteIntensity.toDouble())
        put("vignetteEffect", m.vignetteEffect.name)
        put("vignetteCenterX", m.vignetteCenterX.toDouble())
        put("vignetteCenterY", m.vignetteCenterY.toDouble())
        put("toneCurvePoints", JSONArray().also { outer ->
            for (channel in m.toneCurvePoints) {
                outer.put(JSONArray().also { inner -> for (v in channel) inner.put(v.toDouble()) })
            }
        })
        put("filmCurveContrast", m.filmCurve.contrast.toDouble())
        put("filmCurvePivot", m.filmCurve.pivot.toDouble())
        put("filmCurveHighlightKnee", m.filmCurve.highlightKnee.toDouble())
        put("filmCurveShadowToe", m.filmCurve.shadowToe.toDouble())
        put("hslRedHue", m.hslRedHue.toDouble())
        put("hslRedSat", m.hslRedSat.toDouble())
        put("hslRedLum", m.hslRedLum.toDouble())
        put("hslOrangeHue", m.hslOrangeHue.toDouble())
        put("hslOrangeSat", m.hslOrangeSat.toDouble())
        put("hslOrangeLum", m.hslOrangeLum.toDouble())
        put("hslYellowHue", m.hslYellowHue.toDouble())
        put("hslYellowSat", m.hslYellowSat.toDouble())
        put("hslYellowLum", m.hslYellowLum.toDouble())
        put("hslGreenHue", m.hslGreenHue.toDouble())
        put("hslGreenSat", m.hslGreenSat.toDouble())
        put("hslGreenLum", m.hslGreenLum.toDouble())
        put("hslAquaHue", m.hslAquaHue.toDouble())
        put("hslAquaSat", m.hslAquaSat.toDouble())
        put("hslAquaLum", m.hslAquaLum.toDouble())
        put("hslBlueHue", m.hslBlueHue.toDouble())
        put("hslBlueSat", m.hslBlueSat.toDouble())
        put("hslBlueLum", m.hslBlueLum.toDouble())
        put("smartSharpness", m.smartSharpness.toDouble())
        put("smoothBackground", m.smoothBackground.toDouble())
        put("luminanceNR", m.luminanceNR.toDouble())
        put("colorNR", m.colorNR.toDouble())
        put("filmGrain", m.filmGrain.toDouble())
        put("filmGrainSize", m.filmGrainSize.toDouble())
        put("filmGrainWashOut", m.filmGrainWashOut.toDouble())
        put("gradientAngle", m.gradientAngle.toDouble())
        putGradient(this, "Top", m.gradientTopIntensity, m.gradientTopLength,
            m.gradientTopFeather, m.gradientTopTintColor, m.gradientTopTintLuminosity,
            m.gradientTopBlendMode)
        putGradient(this, "Bottom", m.gradientBottomIntensity, m.gradientBottomLength,
            m.gradientBottomFeather, m.gradientBottomTintColor, m.gradientBottomTintLuminosity,
            m.gradientBottomBlendMode)
        putGradient(this, "Left", m.gradientLeftIntensity, m.gradientLeftLength,
            m.gradientLeftFeather, m.gradientLeftTintColor, m.gradientLeftTintLuminosity,
            m.gradientLeftBlendMode)
        putGradient(this, "Right", m.gradientRightIntensity, m.gradientRightLength,
            m.gradientRightFeather, m.gradientRightTintColor, m.gradientRightTintLuminosity,
            m.gradientRightBlendMode)
        put("maskBrightness", m.maskBrightness.toDouble())
        put("maskContrast", m.maskContrast.toDouble())
        put("maskTemperature", m.maskTemperature)
        put("maskTint", m.maskTint.toDouble())
        put("maskSaturation", m.maskSaturation.toDouble())
        put("maskClarity", m.maskClarity.toDouble())
        put("maskHighlights", m.maskTone.highlights.toDouble())
        put("maskShadows", m.maskTone.shadows.toDouble())
        put("maskWhites", m.maskTone.whites.toDouble())
        put("maskBlacks", m.maskTone.blacks.toDouble())
        put("vignetteSegmentation", m.vignetteSegmentation.name)
        put("gradientTopApplyTo", m.gradientTopApplyTo.name)
        put("gradientBottomApplyTo", m.gradientBottomApplyTo.name)
        put("gradientLeftApplyTo", m.gradientLeftApplyTo.name)
        put("gradientRightApplyTo", m.gradientRightApplyTo.name)
        put("outputColorSpace", m.outputColorSpace.name)
        put("stripGps", m.stripGps)
        // PREQ-Port fields
        put("grainRoughness", m.grainRoughness.toDouble())
        put("sharpenMask",    m.sharpenMask.toDouble())
        put("colorDensity",   m.colorDensity.toDouble())
        put("skintoneWarm",   m.skintoneWarm.toDouble())
        put("skintoneSmooth", m.skintoneSmooth.toDouble())
        put("skintoneLuma",   m.skintoneLuma.toDouble())
        put("midtoneDetails",    m.midtoneDetails.toDouble())
        put("highlightRecovery", m.highlightRecovery.toDouble())
        put("pushPull",           m.pushPull.toDouble())
        put("lutColorDensity",    m.lutColorDensity.toDouble())
        put("lutSkintoneBalance", m.lutSkintoneBalance.toDouble())
        put("aberStrength",     m.aberStrength.toDouble())
        put("aberFringeReduce", m.aberFringeReduce.toDouble())
        put("fxBlurStyle",      m.fxBlurStyle)
        put("fxGaussBlur",      m.fxGaussBlur.toDouble())
        put("fxDirBlurAmt",     m.fxDirBlurAmt.toDouble())
        put("fxDirBlurAngle",   m.fxDirBlurAngle.toDouble())
        put("fxRadBlurAmt",     m.fxRadBlurAmt.toDouble())
        put("fxZoomBlurAmt",    m.fxZoomBlurAmt.toDouble())
        put("fxMist",           m.fxMist.toDouble())
        put("fxMistWarmth",     m.fxMistWarmth.toDouble())
        put("fxDust",           m.fxDust.toDouble())
        put("fxDustSize",       m.fxDustSize.toDouble())
        put("fxVintageStrength", m.fxVintageStrength.toDouble())
        put("fxVintageFade",     m.fxVintageFade.toDouble())
        put("fxVintageVig",      m.fxVintageVig.toDouble())
        put("fxVintageMistIntensity", m.fxVintageMistIntensity.toDouble())
        put("fxVintageMistScale",     m.fxVintageMistScale.toDouble())
        put("fxVintageTextureIntensity", m.fxVintageTextureIntensity.toDouble())
        put("fxVintageTextureScale",     m.fxVintageTextureScale.toDouble())
        put("fxGlowStrength",   m.fxGlowStrength.toDouble())
        put("fxGlowSpread",     m.fxGlowSpread.toDouble())
        put("fxGlowWarmth",     m.fxGlowWarmth.toDouble())
        put("lensFlareX",          m.lensFlare.x.toDouble())
        put("lensFlareY",          m.lensFlare.y.toDouble())
        put("lensFlareBrightness", m.lensFlare.brightness.toDouble())
        put("lensFlareSize",       m.lensFlare.size.toDouble())
        put("lensFlareSpread",     m.lensFlare.spread.toDouble())
        put("lensFlareWarmth",     m.lensFlare.warmth.toDouble())
        put("colorShiftRedX",      m.colorShift.redX.toDouble())
        put("colorShiftGreenX",    m.colorShift.greenX.toDouble())
        put("colorShiftBlueX",     m.colorShift.blueX.toDouble())
        // ── Fields added 2026-08-28 (sidecar reopen-dark bug) ────────────────
        // Every UserMacro field the serializer had silently been DROPPING —
        // 124 of 243, incl. CLAHE boosts, colour grading, ambiance, crop,
        // subject-weighted AE, extended HSL, second gradients, mask adjust,
        // bloom/orton, tonemap. A card built on any of these round-tripped to
        // a NO-OP, so reopening a photo rendered differently than the session
        // that saved it. Keep this list in lockstep with UserMacro.
        put("claheEnabled", m.claheEnabled)
        put("claheShadowsBoost", m.claheShadowsBoost.toDouble())
        put("claheHighlightsBoost", m.claheHighlightsBoost.toDouble())
        put("smartColorEnhance", m.smartColorEnhance.toDouble())
        put("lutHighlightVibrancy", m.lutHighlightVibrancy.toDouble())
        put("highlightTemperature", m.highlightTemperature.toDouble())
        put("highlightTint", m.highlightTint.toDouble())
        put("shadowTemperature", m.shadowTemperature.toDouble())
        put("shadowTint", m.shadowTint.toDouble())
        put("ambiance", m.ambiance.toDouble())
        put("ortonStrength", m.ortonStrength.toDouble())
        put("bloomRadius", m.bloomRadius.toDouble())
        put("bloomShape", m.bloomShape.toDouble())
        put("bloomExcludeSubject", m.bloomExcludeSubject)
        put("subjectBloom", m.subjectBloom.toDouble())
        put("subjectPopEnabled", m.subjectPopEnabled)
        put("subjectPopShadow", m.subjectPopShadow.toDouble())
        put("subjectPopHighlight", m.subjectPopHighlight.toDouble())
        put("subjectPopSaturation", m.subjectPopSaturation.toDouble())
        put("filmRolloff", m.filmRolloff.toDouble())
        put("gamutCompress", m.gamutCompress.toDouble())
        put("cropL", m.cropL.toDouble())
        put("cropT", m.cropT.toDouble())
        put("cropR", m.cropR.toDouble())
        put("cropB", m.cropB.toDouble())
        put("cropRotationDeg", m.cropRotationDeg.toDouble())
        put("cropRotate90", m.cropRotate90)
        put("cropFlipH", m.cropFlipH)
        put("cropFlipV", m.cropFlipV)
        put("smartBright", m.smartBright.toDouble())
        put("whitesSubject", m.whitesSubject.toDouble())
        put("blacksSubject", m.blacksSubject.toDouble())
        put("whitesBackground", m.whitesBackground.toDouble())
        put("blacksBackground", m.blacksBackground.toDouble())
        put("shadowsSubject", m.shadowsSubject.toDouble())
        put("shadowsBackground", m.shadowsBackground.toDouble())
        put("highlightsSubject", m.highlightsSubject.toDouble())
        put("highlightsBackground", m.highlightsBackground.toDouble())
        put("ambianceSubject", m.ambianceSubject.toDouble())
        put("ambianceBackground", m.ambianceBackground.toDouble())
        put("aeSubjectProtection", m.aeSubjectProtection.toDouble())
        put("tonemapExposure", m.tonemapExposure.toDouble())
        put("tonemapHighlights", m.tonemapHighlights.toDouble())
        put("tonemapShadows", m.tonemapShadows.toDouble())
        put("filmicHlProtect", m.filmicHlProtect.toDouble())
        put("vignetteCenterAutoSnapped", m.vignetteCenterAutoSnapped)
        put("toneCurveLumaMode", m.toneCurveLumaMode)
        put("hslYellowGreenHue", m.hslExt.yellowGreenHue.toDouble())
        put("hslYellowGreenSat", m.hslExt.yellowGreenSat.toDouble())
        put("hslYellowGreenLum", m.hslExt.yellowGreenLum.toDouble())
        put("hslSpringGreenHue", m.hslExt.springGreenHue.toDouble())
        put("hslSpringGreenSat", m.hslExt.springGreenSat.toDouble())
        put("hslSpringGreenLum", m.hslExt.springGreenLum.toDouble())
        put("hslSkyBlueHue", m.hslExt.skyBlueHue.toDouble())
        put("hslSkyBlueSat", m.hslExt.skyBlueSat.toDouble())
        put("hslSkyBlueLum", m.hslExt.skyBlueLum.toDouble())
        put("hslPurpleHue", m.hslExt.purpleHue.toDouble())
        put("hslPurpleSat", m.hslExt.purpleSat.toDouble())
        put("hslPurpleLum", m.hslExt.purpleLum.toDouble())
        put("hslMagentaHue", m.hslExt.magentaHue.toDouble())
        put("hslMagentaSat", m.hslExt.magentaSat.toDouble())
        put("hslMagentaLum", m.hslExt.magentaLum.toDouble())
        put("hslPinkHue", m.hslExt.pinkHue.toDouble())
        put("hslPinkSat", m.hslExt.pinkSat.toDouble())
        put("hslPinkLum", m.hslExt.pinkLum.toDouble())
        put("blueNR", m.blueNR.toDouble())
        put("redNR", m.redNR.toDouble())
        put("removeShadows", m.removeShadows)
        put("removeFaceShadows", m.removeFaceShadows)
        put("bokehBlur", m.bokehBlur)
        put("bokehBalls", m.bokehBalls)
        put("bokehSpread", m.bokehSpread.toDouble())
        put("gradientTopEnable2", m.gradientTopEnable2)
        put("gradientTopIntensity2", m.gradientTopIntensity2.toDouble())
        put("gradientTopLength2", m.gradientTopLength2.toDouble())
        put("gradientTopFeather2", m.gradientTopFeather2.toDouble())
        put("gradientTopTintColor2", m.gradientTopTintColor2)
        put("gradientTopTintLuminosity2", m.gradientTopTintLuminosity2.toDouble())
        put("gradientBottomEnable2", m.gradientBottomEnable2)
        put("gradientBottomIntensity2", m.gradientBottomIntensity2.toDouble())
        put("gradientBottomLength2", m.gradientBottomLength2.toDouble())
        put("gradientBottomFeather2", m.gradientBottomFeather2.toDouble())
        put("gradientBottomTintColor2", m.gradientBottomTintColor2)
        put("gradientBottomTintLuminosity2", m.gradientBottomTintLuminosity2.toDouble())
        put("gradientLeftEnable2", m.gradientLeftEnable2)
        put("gradientLeftIntensity2", m.gradientLeftIntensity2.toDouble())
        put("gradientLeftLength2", m.gradientLeftLength2.toDouble())
        put("gradientLeftFeather2", m.gradientLeftFeather2.toDouble())
        put("gradientLeftTintColor2", m.gradientLeftTintColor2)
        put("gradientLeftTintLuminosity2", m.gradientLeftTintLuminosity2.toDouble())
        put("gradientRightEnable2", m.gradientRightEnable2)
        put("gradientRightIntensity2", m.gradientRightIntensity2.toDouble())
        put("gradientRightLength2", m.gradientRightLength2.toDouble())
        put("gradientRightFeather2", m.gradientRightFeather2.toDouble())
        put("gradientRightTintColor2", m.gradientRightTintColor2)
        put("gradientRightTintLuminosity2", m.gradientRightTintLuminosity2.toDouble())
        put("maskSharpness", m.maskSharpness.toDouble())
        put("maskLumTarget", m.maskLumTarget.toDouble())
        put("maskLumSpread", m.maskLumSpread.toDouble())
        put("maskLumFeather", m.maskLumFeather.toDouble())
        put("maskLumCombine", m.maskLumCombine)
        put("cgShadowsR", m.cgShadows.r.toDouble())
        put("cgShadowsG", m.cgShadows.g.toDouble())
        put("cgShadowsB", m.cgShadows.b.toDouble())
        put("cgShadowsSat", m.cgShadows.sat.toDouble())
        put("cgMidtonesR", m.cgMidtones.r.toDouble())
        put("cgMidtonesG", m.cgMidtones.g.toDouble())
        put("cgMidtonesB", m.cgMidtones.b.toDouble())
        put("cgMidtonesSat", m.cgMidtones.sat.toDouble())
        put("cgHighlightsR", m.cgHighlights.r.toDouble())
        put("cgHighlightsG", m.cgHighlights.g.toDouble())
        put("cgHighlightsB", m.cgHighlights.b.toDouble())
        put("cgHighlightsSat", m.cgHighlights.sat.toDouble())
        // Film response (LUT tab). Persisted field-by-field rather than as a
        // blob so an older reader simply misses them instead of failing.
        put("filmRecovery", m.filmResponse.recovery.toDouble())
        put("filmFillLight", m.filmResponse.fillLight.toDouble())
        put("filmMonochrome", m.filmResponse.monochrome)
        put("filmGrayRed", m.filmResponse.grayRed.toDouble())
        put("filmGrayOrange", m.filmResponse.grayOrange.toDouble())
        put("filmGrayYellow", m.filmResponse.grayYellow.toDouble())
        put("filmGrayGreen", m.filmResponse.grayGreen.toDouble())
        put("filmGrayAqua", m.filmResponse.grayAqua.toDouble())
        put("filmGrayBlue", m.filmResponse.grayBlue.toDouble())
        put("filmGrayPurple", m.filmResponse.grayPurple.toDouble())
        put("filmGrayMagenta", m.filmResponse.grayMagenta.toDouble())
        put("cgGlobalR", m.cgGlobal.r.toDouble())
        put("cgGlobalG", m.cgGlobal.g.toDouble())
        put("cgGlobalB", m.cgGlobal.b.toDouble())
        put("cgGlobalSat", m.cgGlobal.sat.toDouble())
        put("centerPop", m.centerPop.toDouble())
        put("fxRadBlurCx", m.fxRadBlurCx.toDouble())
        put("fxRadBlurCy", m.fxRadBlurCy.toDouble())
        put("fxZoomBlurCx", m.fxZoomBlurCx.toDouble())
        put("fxZoomBlurCy", m.fxZoomBlurCy.toDouble())
        put("fxBlurExcludeSubject", m.fxBlurExcludeSubject)
        m.scenePreset?.let { put("scenePreset", it) }
    }

    private fun putGradient(
        obj: JSONObject, side: String,
        intensity: Float, length: Float, feather: Float,
        tintColor: Int, tintLuminosity: Float, blend: RawGradientBlendMode,
    ) {
        obj.put("gradient${side}Intensity", intensity.toDouble())
        obj.put("gradient${side}Length", length.toDouble())
        obj.put("gradient${side}Feather", feather.toDouble())
        obj.put("gradient${side}TintColor", tintColor)
        obj.put("gradient${side}TintLuminosity", tintLuminosity.toDouble())
        obj.put("gradient${side}BlendMode", blend.name)
    }

    private fun jsonToMacro(obj: JSONObject): UserMacro {
        val d = UserMacro()
        // LUT: prefer the new two-slot fields; migrate an older lutStack / editing
        // LUT (first→slot 1, second→slot 2, extras dropped).
        val oldLayers = (obj.optJSONArray("lutStack")?.let { arr ->
            List(arr.length()) { i ->
                val o = arr.optJSONObject(i)
                LutLayer(o?.optString("uri", "") ?: "", o?.optDouble("intensity", 1.0)?.toFloat() ?: 1f)
            }
        }.orEmpty().filter { it.cubeUri.isNotEmpty() }) + run {
            val oc = obj.optString("lutCubeUri", "")
            if (oc.isNotEmpty()) listOf(LutLayer(oc, obj.optDouble("lutIntensity", 1.0).toFloat()))
            else emptyList()
        }
        val hasNew = obj.has("lut1CubeUri") || obj.has("lut2CubeUri")
        return UserMacro(
            lut1CubeUri   = if (hasNew) obj.optString("lut1CubeUri", "") else (oldLayers.getOrNull(0)?.cubeUri ?: ""),
            lut1Intensity = if (hasNew) obj.optDouble("lut1Intensity", 1.0).toFloat() else (oldLayers.getOrNull(0)?.intensity ?: 1f),
            lut2CubeUri   = if (hasNew) obj.optString("lut2CubeUri", "") else (oldLayers.getOrNull(1)?.cubeUri ?: ""),
            lut2Intensity = if (hasNew) obj.optDouble("lut2Intensity", 1.0).toFloat() else (oldLayers.getOrNull(1)?.intensity ?: 1f),
            // Transient editing fields — see macroToJson. In the legacy (!hasNew)
            // branch lutCubeUri was already folded into oldLayers above, so only
            // the new format restores it verbatim; that keeps a restored LUT card
            // byte-identical to the one the LUT tab committed in-session.
            lutCubeUri    = if (hasNew) obj.optString("lutCubeUri", "") else "",
            lutIntensity  = if (hasNew) obj.optDouble("lutIntensity", 1.0).toFloat() else 1f,
            lutSlot       = obj.optInt("lutSlot", d.lutSlot),
            lutEdited     = if (hasNew) obj.optBoolean("lutEdited", false) else false,
            contrastBoost = obj.optDouble("contrastBoost", d.contrastBoost.toDouble()).toFloat(),
            exposure = obj.optDouble("exposure", d.exposure.toDouble()).toFloat(),
            whiteBalance = obj.optInt("whiteBalance", d.whiteBalance),
            tint = obj.optDouble("tint", d.tint.toDouble()).toFloat(),
            highlights = obj.optDouble("highlights", d.highlights.toDouble()).toFloat(),
            shadows = obj.optDouble("shadows", d.shadows.toDouble()).toFloat(),
            whites = obj.optDouble("whites", d.whites.toDouble()).toFloat(),
            blacks = obj.optDouble("blacks", d.blacks.toDouble()).toFloat(),
            contrast = obj.optDouble("contrast", d.contrast.toDouble()).toFloat(),
            saturation = obj.optDouble("saturation", d.saturation.toDouble()).toFloat(),
            vibrance = obj.optDouble("vibrance", d.vibrance.toDouble()).toFloat(),
            clarity = obj.optDouble("clarity", d.clarity.toDouble()).toFloat(),
            clarityLift = obj.optDouble("clarityLift", d.clarityLift.toDouble()).toFloat(),
            dehaze = obj.optDouble("dehaze", d.dehaze.toDouble()).toFloat().coerceIn(-25f, 25f),
            texture = obj.optDouble("texture", d.texture.toDouble()).toFloat(),
            sharpness = obj.optDouble("sharpness", d.sharpness.toDouble()).toFloat(),
            noiseReduction = obj.optDouble("noiseReduction", d.noiseReduction.toDouble()).toFloat(),
            vignetteAmount = obj.optDouble("vignetteAmount", d.vignetteAmount.toDouble()).toFloat(),
            vignetteFeather = obj.optDouble("vignetteFeather", d.vignetteFeather.toDouble()).toFloat(),
            vignetteIntensity = obj.optDouble("vignetteIntensity", d.vignetteIntensity.toDouble()).toFloat(),
            vignetteEffect = enumOrDefault(obj, "vignetteEffect", d.vignetteEffect) {
                VignetteEffect.valueOf(it)
            },
            vignetteCenterX = obj.optDouble("vignetteCenterX", d.vignetteCenterX.toDouble()).toFloat(),
            vignetteCenterY = obj.optDouble("vignetteCenterY", d.vignetteCenterY.toDouble()).toFloat(),
            toneCurvePoints = obj.optJSONArray("toneCurvePoints")?.let { outer ->
                List(outer.length()) { i ->
                    val inner = outer.optJSONArray(i) ?: return@let d.toneCurvePoints
                    List(inner.length()) { j -> inner.optDouble(j, 0.0).toFloat() }
                }
            } ?: d.toneCurvePoints,
            filmCurve = FilmCurve(
                contrast = obj.optFloat("filmCurveContrast", d.filmCurve.contrast),
                pivot = obj.optFloat("filmCurvePivot", d.filmCurve.pivot),
                highlightKnee = obj.optFloat("filmCurveHighlightKnee", d.filmCurve.highlightKnee),
                shadowToe = obj.optFloat("filmCurveShadowToe", d.filmCurve.shadowToe),
            ),
            hslRedHue = obj.optFloat("hslRedHue", d.hslRedHue),
            hslRedSat = obj.optFloat("hslRedSat", d.hslRedSat),
            hslRedLum = obj.optFloat("hslRedLum", d.hslRedLum),
            hslOrangeHue = obj.optFloat("hslOrangeHue", d.hslOrangeHue),
            hslOrangeSat = obj.optFloat("hslOrangeSat", d.hslOrangeSat),
            hslOrangeLum = obj.optFloat("hslOrangeLum", d.hslOrangeLum),
            hslYellowHue = obj.optFloat("hslYellowHue", d.hslYellowHue),
            hslYellowSat = obj.optFloat("hslYellowSat", d.hslYellowSat),
            hslYellowLum = obj.optFloat("hslYellowLum", d.hslYellowLum),
            hslGreenHue = obj.optFloat("hslGreenHue", d.hslGreenHue),
            hslGreenSat = obj.optFloat("hslGreenSat", d.hslGreenSat),
            hslGreenLum = obj.optFloat("hslGreenLum", d.hslGreenLum),
            hslAquaHue = obj.optFloat("hslAquaHue", d.hslAquaHue),
            hslAquaSat = obj.optFloat("hslAquaSat", d.hslAquaSat),
            hslAquaLum = obj.optFloat("hslAquaLum", d.hslAquaLum),
            hslBlueHue = obj.optFloat("hslBlueHue", d.hslBlueHue),
            hslBlueSat = obj.optFloat("hslBlueSat", d.hslBlueSat),
            hslBlueLum = obj.optFloat("hslBlueLum", d.hslBlueLum),
            smartSharpness = obj.optFloat("smartSharpness", d.smartSharpness),
            smoothBackground = obj.optFloat("smoothBackground", d.smoothBackground),
            luminanceNR = obj.optFloat("luminanceNR", d.luminanceNR),
            colorNR = obj.optFloat("colorNR", d.colorNR),
            filmGrain = obj.optFloat("filmGrain", d.filmGrain),
            filmGrainSize = obj.optFloat("filmGrainSize", d.filmGrainSize),
            filmGrainWashOut = obj.optFloat("filmGrainWashOut", d.filmGrainWashOut),
            gradientAngle = obj.optFloat("gradientAngle", d.gradientAngle),
            gradientTopIntensity = obj.optFloat("gradientTopIntensity", d.gradientTopIntensity),
            gradientTopLength = obj.optFloat("gradientTopLength", d.gradientTopLength),
            gradientTopFeather = obj.optFloat("gradientTopFeather", d.gradientTopFeather),
            gradientTopTintColor = obj.optInt("gradientTopTintColor", d.gradientTopTintColor),
            gradientTopTintLuminosity = obj.optFloat("gradientTopTintLuminosity", d.gradientTopTintLuminosity),
            gradientTopBlendMode = enumOrDefault(obj, "gradientTopBlendMode", d.gradientTopBlendMode) {
                RawGradientBlendMode.valueOf(it)
            },
            gradientBottomIntensity = obj.optFloat("gradientBottomIntensity", d.gradientBottomIntensity),
            gradientBottomLength = obj.optFloat("gradientBottomLength", d.gradientBottomLength),
            gradientBottomFeather = obj.optFloat("gradientBottomFeather", d.gradientBottomFeather),
            gradientBottomTintColor = obj.optInt("gradientBottomTintColor", d.gradientBottomTintColor),
            gradientBottomTintLuminosity = obj.optFloat("gradientBottomTintLuminosity", d.gradientBottomTintLuminosity),
            gradientBottomBlendMode = enumOrDefault(obj, "gradientBottomBlendMode", d.gradientBottomBlendMode) {
                RawGradientBlendMode.valueOf(it)
            },
            gradientLeftIntensity = obj.optFloat("gradientLeftIntensity", d.gradientLeftIntensity),
            gradientLeftLength = obj.optFloat("gradientLeftLength", d.gradientLeftLength),
            gradientLeftFeather = obj.optFloat("gradientLeftFeather", d.gradientLeftFeather),
            gradientLeftTintColor = obj.optInt("gradientLeftTintColor", d.gradientLeftTintColor),
            gradientLeftTintLuminosity = obj.optFloat("gradientLeftTintLuminosity", d.gradientLeftTintLuminosity),
            gradientLeftBlendMode = enumOrDefault(obj, "gradientLeftBlendMode", d.gradientLeftBlendMode) {
                RawGradientBlendMode.valueOf(it)
            },
            gradientRightIntensity = obj.optFloat("gradientRightIntensity", d.gradientRightIntensity),
            gradientRightLength = obj.optFloat("gradientRightLength", d.gradientRightLength),
            gradientRightFeather = obj.optFloat("gradientRightFeather", d.gradientRightFeather),
            gradientRightTintColor = obj.optInt("gradientRightTintColor", d.gradientRightTintColor),
            gradientRightTintLuminosity = obj.optFloat("gradientRightTintLuminosity", d.gradientRightTintLuminosity),
            gradientRightBlendMode = enumOrDefault(obj, "gradientRightBlendMode", d.gradientRightBlendMode) {
                RawGradientBlendMode.valueOf(it)
            },
            maskBrightness = obj.optFloat("maskBrightness", d.maskBrightness),
            maskContrast = obj.optFloat("maskContrast", d.maskContrast),
            maskTemperature = obj.optInt("maskTemperature", d.maskTemperature),
            maskTint = obj.optFloat("maskTint", d.maskTint),
            maskSaturation = obj.optFloat("maskSaturation", d.maskSaturation),
            maskClarity = obj.optFloat("maskClarity", d.maskClarity),
            maskTone = MaskToneRegions(
                highlights = obj.optFloat("maskHighlights", d.maskTone.highlights),
                shadows = obj.optFloat("maskShadows", d.maskTone.shadows),
                whites = obj.optFloat("maskWhites", d.maskTone.whites),
                blacks = obj.optFloat("maskBlacks", d.maskTone.blacks),
            ),
            vignetteSegmentation = enumOrDefault(obj, "vignetteSegmentation", d.vignetteSegmentation) {
                SegmentTarget.valueOf(it)
            },
            gradientTopApplyTo = enumOrDefault(obj, "gradientTopApplyTo", d.gradientTopApplyTo) {
                SegmentTarget.valueOf(it)
            },
            gradientBottomApplyTo = enumOrDefault(obj, "gradientBottomApplyTo", d.gradientBottomApplyTo) {
                SegmentTarget.valueOf(it)
            },
            gradientLeftApplyTo = enumOrDefault(obj, "gradientLeftApplyTo", d.gradientLeftApplyTo) {
                SegmentTarget.valueOf(it)
            },
            gradientRightApplyTo = enumOrDefault(obj, "gradientRightApplyTo", d.gradientRightApplyTo) {
                SegmentTarget.valueOf(it)
            },
            outputColorSpace = enumOrDefault(obj, "outputColorSpace", d.outputColorSpace) {
                RawColorSpace.valueOf(it)
            },
            stripGps = obj.optBoolean("stripGps", d.stripGps),
            // PREQ-Port fields
            grainRoughness      = obj.optFloat("grainRoughness",      d.grainRoughness),
            sharpenMask         = obj.optFloat("sharpenMask",         d.sharpenMask),
            colorDensity        = obj.optFloat("colorDensity",        d.colorDensity),
            skintoneWarm        = obj.optFloat("skintoneWarm",        d.skintoneWarm),
            skintoneSmooth      = obj.optFloat("skintoneSmooth",      d.skintoneSmooth),
            skintoneLuma        = obj.optFloat("skintoneLuma",        d.skintoneLuma),
            midtoneDetails      = obj.optFloat("midtoneDetails",      d.midtoneDetails),
            highlightRecovery   = obj.optFloat("highlightRecovery",   d.highlightRecovery),
            pushPull            = obj.optFloat("pushPull",            d.pushPull),
            lutColorDensity     = obj.optFloat("lutColorDensity",     d.lutColorDensity),
            lutSkintoneBalance  = obj.optFloat("lutSkintoneBalance",  d.lutSkintoneBalance),
            aberStrength        = obj.optFloat("aberStrength",        d.aberStrength),
            aberFringeReduce    = obj.optFloat("aberFringeReduce",    d.aberFringeReduce),
            fxBlurStyle         = obj.optInt("fxBlurStyle",           d.fxBlurStyle),
            fxGaussBlur         = obj.optFloat("fxGaussBlur",         d.fxGaussBlur),
            fxDirBlurAmt        = obj.optFloat("fxDirBlurAmt",        d.fxDirBlurAmt),
            fxDirBlurAngle      = obj.optFloat("fxDirBlurAngle",      d.fxDirBlurAngle),
            fxRadBlurAmt        = obj.optFloat("fxRadBlurAmt",        d.fxRadBlurAmt),
            fxZoomBlurAmt       = obj.optFloat("fxZoomBlurAmt",       d.fxZoomBlurAmt),
            fxMist              = obj.optFloat("fxMist",              d.fxMist),
            fxMistWarmth        = obj.optFloat("fxMistWarmth",        d.fxMistWarmth),
            fxDust              = obj.optFloat("fxDust",              d.fxDust),
            fxDustSize          = obj.optFloat("fxDustSize",          d.fxDustSize),
            fxVintageStrength   = obj.optFloat("fxVintageStrength",   d.fxVintageStrength),
            fxVintageFade       = obj.optFloat("fxVintageFade",       d.fxVintageFade),
            fxVintageVig        = obj.optFloat("fxVintageVig",        d.fxVintageVig),
            fxVintageMistIntensity = obj.optFloat("fxVintageMistIntensity", d.fxVintageMistIntensity),
            fxVintageMistScale     = obj.optFloat("fxVintageMistScale",     d.fxVintageMistScale),
            fxVintageTextureIntensity = obj.optFloat("fxVintageTextureIntensity", d.fxVintageTextureIntensity),
            fxVintageTextureScale     = obj.optFloat("fxVintageTextureScale",     d.fxVintageTextureScale),
            fxGlowStrength      = obj.optFloat("fxGlowStrength",      d.fxGlowStrength),
            fxGlowSpread        = obj.optFloat("fxGlowSpread",        d.fxGlowSpread),
            fxGlowWarmth        = obj.optFloat("fxGlowWarmth",        d.fxGlowWarmth),
            lensFlare = LensFlare(
                x          = obj.optFloat("lensFlareX",          d.lensFlare.x),
                y          = obj.optFloat("lensFlareY",          d.lensFlare.y),
                brightness = obj.optFloat("lensFlareBrightness", d.lensFlare.brightness),
                size       = obj.optFloat("lensFlareSize",       d.lensFlare.size),
                spread     = obj.optFloat("lensFlareSpread",     d.lensFlare.spread),
                warmth     = obj.optFloat("lensFlareWarmth",     d.lensFlare.warmth),
            ),
            colorShift = ColorShift(
                redX   = obj.optFloat("colorShiftRedX",   d.colorShift.redX),
                greenX = obj.optFloat("colorShiftGreenX", d.colorShift.greenX),
                blueX  = obj.optFloat("colorShiftBlueX",  d.colorShift.blueX),
            ),
            // ── Fields added 2026-08-28 — see matching block in macroToJson ──
            claheEnabled = obj.optBoolean("claheEnabled", d.claheEnabled),
            claheShadowsBoost = obj.optFloat("claheShadowsBoost", d.claheShadowsBoost),
            claheHighlightsBoost = obj.optFloat("claheHighlightsBoost", d.claheHighlightsBoost),
            smartColorEnhance = obj.optFloat("smartColorEnhance", d.smartColorEnhance),
            lutHighlightVibrancy = obj.optFloat("lutHighlightVibrancy", d.lutHighlightVibrancy),
            highlightTemperature = obj.optFloat("highlightTemperature", d.highlightTemperature),
            highlightTint = obj.optFloat("highlightTint", d.highlightTint),
            shadowTemperature = obj.optFloat("shadowTemperature", d.shadowTemperature),
            shadowTint = obj.optFloat("shadowTint", d.shadowTint),
            ambiance = obj.optFloat("ambiance", d.ambiance),
            ortonStrength = obj.optFloat("ortonStrength", d.ortonStrength),
            bloomRadius = obj.optFloat("bloomRadius", d.bloomRadius),
            bloomShape = obj.optFloat("bloomShape", d.bloomShape),
            bloomExcludeSubject = obj.optBoolean("bloomExcludeSubject", d.bloomExcludeSubject),
            subjectBloom = obj.optFloat("subjectBloom", d.subjectBloom),
            subjectPopEnabled = obj.optBoolean("subjectPopEnabled", d.subjectPopEnabled),
            subjectPopShadow = obj.optFloat("subjectPopShadow", d.subjectPopShadow),
            subjectPopHighlight = obj.optFloat("subjectPopHighlight", d.subjectPopHighlight),
            subjectPopSaturation = obj.optFloat("subjectPopSaturation", d.subjectPopSaturation),
            filmRolloff = obj.optFloat("filmRolloff", d.filmRolloff),
            gamutCompress = obj.optFloat("gamutCompress", d.gamutCompress),
            cropL = obj.optFloat("cropL", d.cropL),
            cropT = obj.optFloat("cropT", d.cropT),
            cropR = obj.optFloat("cropR", d.cropR),
            cropB = obj.optFloat("cropB", d.cropB),
            cropRotationDeg = obj.optFloat("cropRotationDeg", d.cropRotationDeg),
            cropRotate90 = obj.optInt("cropRotate90", d.cropRotate90),
            cropFlipH = obj.optBoolean("cropFlipH", d.cropFlipH),
            cropFlipV = obj.optBoolean("cropFlipV", d.cropFlipV),
            smartBright = obj.optFloat("smartBright", d.smartBright),
            whitesSubject = obj.optFloat("whitesSubject", d.whitesSubject),
            blacksSubject = obj.optFloat("blacksSubject", d.blacksSubject),
            whitesBackground = obj.optFloat("whitesBackground", d.whitesBackground),
            blacksBackground = obj.optFloat("blacksBackground", d.blacksBackground),
            shadowsSubject = obj.optFloat("shadowsSubject", d.shadowsSubject),
            shadowsBackground = obj.optFloat("shadowsBackground", d.shadowsBackground),
            highlightsSubject = obj.optFloat("highlightsSubject", d.highlightsSubject),
            highlightsBackground = obj.optFloat("highlightsBackground", d.highlightsBackground),
            ambianceSubject = obj.optFloat("ambianceSubject", d.ambianceSubject),
            ambianceBackground = obj.optFloat("ambianceBackground", d.ambianceBackground),
            aeSubjectProtection = obj.optFloat("aeSubjectProtection", d.aeSubjectProtection),
            tonemapExposure = obj.optFloat("tonemapExposure", d.tonemapExposure),
            tonemapHighlights = obj.optFloat("tonemapHighlights", d.tonemapHighlights),
            tonemapShadows = obj.optFloat("tonemapShadows", d.tonemapShadows),
            filmicHlProtect = obj.optFloat("filmicHlProtect", d.filmicHlProtect),
            vignetteCenterAutoSnapped = obj.optBoolean("vignetteCenterAutoSnapped", d.vignetteCenterAutoSnapped),
            toneCurveLumaMode = obj.optBoolean("toneCurveLumaMode", d.toneCurveLumaMode),
            hslExt = HslExtended(
                yellowGreenHue = obj.optFloat("hslYellowGreenHue", d.hslExt.yellowGreenHue),
                yellowGreenSat = obj.optFloat("hslYellowGreenSat", d.hslExt.yellowGreenSat),
                yellowGreenLum = obj.optFloat("hslYellowGreenLum", d.hslExt.yellowGreenLum),
                springGreenHue = obj.optFloat("hslSpringGreenHue", d.hslExt.springGreenHue),
                springGreenSat = obj.optFloat("hslSpringGreenSat", d.hslExt.springGreenSat),
                springGreenLum = obj.optFloat("hslSpringGreenLum", d.hslExt.springGreenLum),
                skyBlueHue = obj.optFloat("hslSkyBlueHue", d.hslExt.skyBlueHue),
                skyBlueSat = obj.optFloat("hslSkyBlueSat", d.hslExt.skyBlueSat),
                skyBlueLum = obj.optFloat("hslSkyBlueLum", d.hslExt.skyBlueLum),
                purpleHue = obj.optFloat("hslPurpleHue", d.hslExt.purpleHue),
                purpleSat = obj.optFloat("hslPurpleSat", d.hslExt.purpleSat),
                purpleLum = obj.optFloat("hslPurpleLum", d.hslExt.purpleLum),
                magentaHue = obj.optFloat("hslMagentaHue", d.hslExt.magentaHue),
                magentaSat = obj.optFloat("hslMagentaSat", d.hslExt.magentaSat),
                magentaLum = obj.optFloat("hslMagentaLum", d.hslExt.magentaLum),
                pinkHue = obj.optFloat("hslPinkHue", d.hslExt.pinkHue),
                pinkSat = obj.optFloat("hslPinkSat", d.hslExt.pinkSat),
                pinkLum = obj.optFloat("hslPinkLum", d.hslExt.pinkLum),
            ),
            blueNR = obj.optFloat("blueNR", d.blueNR),
            redNR = obj.optFloat("redNR", d.redNR),
            removeShadows = obj.optBoolean("removeShadows", d.removeShadows),
            removeFaceShadows = obj.optBoolean("removeFaceShadows", d.removeFaceShadows),
            bokehBlur = obj.optInt("bokehBlur", d.bokehBlur),
            bokehBalls = obj.optInt("bokehBalls", d.bokehBalls),
            bokehSpread = obj.optFloat("bokehSpread", d.bokehSpread),
            gradientTopEnable2 = obj.optBoolean("gradientTopEnable2", d.gradientTopEnable2),
            gradientTopIntensity2 = obj.optFloat("gradientTopIntensity2", d.gradientTopIntensity2),
            gradientTopLength2 = obj.optFloat("gradientTopLength2", d.gradientTopLength2),
            gradientTopFeather2 = obj.optFloat("gradientTopFeather2", d.gradientTopFeather2),
            gradientTopTintColor2 = obj.optInt("gradientTopTintColor2", d.gradientTopTintColor2),
            gradientTopTintLuminosity2 = obj.optFloat("gradientTopTintLuminosity2", d.gradientTopTintLuminosity2),
            gradientBottomEnable2 = obj.optBoolean("gradientBottomEnable2", d.gradientBottomEnable2),
            gradientBottomIntensity2 = obj.optFloat("gradientBottomIntensity2", d.gradientBottomIntensity2),
            gradientBottomLength2 = obj.optFloat("gradientBottomLength2", d.gradientBottomLength2),
            gradientBottomFeather2 = obj.optFloat("gradientBottomFeather2", d.gradientBottomFeather2),
            gradientBottomTintColor2 = obj.optInt("gradientBottomTintColor2", d.gradientBottomTintColor2),
            gradientBottomTintLuminosity2 = obj.optFloat("gradientBottomTintLuminosity2", d.gradientBottomTintLuminosity2),
            gradientLeftEnable2 = obj.optBoolean("gradientLeftEnable2", d.gradientLeftEnable2),
            gradientLeftIntensity2 = obj.optFloat("gradientLeftIntensity2", d.gradientLeftIntensity2),
            gradientLeftLength2 = obj.optFloat("gradientLeftLength2", d.gradientLeftLength2),
            gradientLeftFeather2 = obj.optFloat("gradientLeftFeather2", d.gradientLeftFeather2),
            gradientLeftTintColor2 = obj.optInt("gradientLeftTintColor2", d.gradientLeftTintColor2),
            gradientLeftTintLuminosity2 = obj.optFloat("gradientLeftTintLuminosity2", d.gradientLeftTintLuminosity2),
            gradientRightEnable2 = obj.optBoolean("gradientRightEnable2", d.gradientRightEnable2),
            gradientRightIntensity2 = obj.optFloat("gradientRightIntensity2", d.gradientRightIntensity2),
            gradientRightLength2 = obj.optFloat("gradientRightLength2", d.gradientRightLength2),
            gradientRightFeather2 = obj.optFloat("gradientRightFeather2", d.gradientRightFeather2),
            gradientRightTintColor2 = obj.optInt("gradientRightTintColor2", d.gradientRightTintColor2),
            gradientRightTintLuminosity2 = obj.optFloat("gradientRightTintLuminosity2", d.gradientRightTintLuminosity2),
            maskSharpness = obj.optFloat("maskSharpness", d.maskSharpness),
            maskLumTarget = obj.optFloat("maskLumTarget", d.maskLumTarget),
            maskLumSpread = obj.optFloat("maskLumSpread", d.maskLumSpread),
            maskLumFeather = obj.optFloat("maskLumFeather", d.maskLumFeather),
            maskLumCombine = obj.optInt("maskLumCombine", d.maskLumCombine),
            cgShadows = ColorWheel(
                r = obj.optFloat("cgShadowsR", d.cgShadows.r),
                g = obj.optFloat("cgShadowsG", d.cgShadows.g),
                b = obj.optFloat("cgShadowsB", d.cgShadows.b),
                sat = obj.optFloat("cgShadowsSat", d.cgShadows.sat),
            ),
            cgMidtones = ColorWheel(
                r = obj.optFloat("cgMidtonesR", d.cgMidtones.r),
                g = obj.optFloat("cgMidtonesG", d.cgMidtones.g),
                b = obj.optFloat("cgMidtonesB", d.cgMidtones.b),
                sat = obj.optFloat("cgMidtonesSat", d.cgMidtones.sat),
            ),
            filmResponse = FilmResponse(
                recovery    = obj.optFloat("filmRecovery", d.filmResponse.recovery),
                fillLight   = obj.optFloat("filmFillLight", d.filmResponse.fillLight),
                monochrome  = obj.optBoolean("filmMonochrome", d.filmResponse.monochrome),
                grayRed     = obj.optFloat("filmGrayRed", d.filmResponse.grayRed),
                grayOrange  = obj.optFloat("filmGrayOrange", d.filmResponse.grayOrange),
                grayYellow  = obj.optFloat("filmGrayYellow", d.filmResponse.grayYellow),
                grayGreen   = obj.optFloat("filmGrayGreen", d.filmResponse.grayGreen),
                grayAqua    = obj.optFloat("filmGrayAqua", d.filmResponse.grayAqua),
                grayBlue    = obj.optFloat("filmGrayBlue", d.filmResponse.grayBlue),
                grayPurple  = obj.optFloat("filmGrayPurple", d.filmResponse.grayPurple),
                grayMagenta = obj.optFloat("filmGrayMagenta", d.filmResponse.grayMagenta),
            ),
            cgHighlights = ColorWheel(
                r = obj.optFloat("cgHighlightsR", d.cgHighlights.r),
                g = obj.optFloat("cgHighlightsG", d.cgHighlights.g),
                b = obj.optFloat("cgHighlightsB", d.cgHighlights.b),
                sat = obj.optFloat("cgHighlightsSat", d.cgHighlights.sat),
            ),
            cgGlobal = ColorWheel(
                r = obj.optFloat("cgGlobalR", d.cgGlobal.r),
                g = obj.optFloat("cgGlobalG", d.cgGlobal.g),
                b = obj.optFloat("cgGlobalB", d.cgGlobal.b),
                sat = obj.optFloat("cgGlobalSat", d.cgGlobal.sat),
            ),
            centerPop = obj.optFloat("centerPop", d.centerPop),
            fxRadBlurCx = obj.optFloat("fxRadBlurCx", d.fxRadBlurCx),
            fxRadBlurCy = obj.optFloat("fxRadBlurCy", d.fxRadBlurCy),
            fxZoomBlurCx = obj.optFloat("fxZoomBlurCx", d.fxZoomBlurCx),
            fxZoomBlurCy = obj.optFloat("fxZoomBlurCy", d.fxZoomBlurCy),
            fxBlurExcludeSubject = obj.optBoolean("fxBlurExcludeSubject", d.fxBlurExcludeSubject),
            scenePreset = obj.optString("scenePreset", "").takeIf { it.isNotEmpty() } ?: d.scenePreset,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified E : Enum<E>> enumOrDefault(
        obj: JSONObject, key: String, default: E, parse: (String) -> E,
    ): E {
        val raw = obj.opt(key) as? String ?: return default
        return runCatching { parse(raw) }.getOrNull() ?: default
    }

    private fun JSONObject.optFloat(key: String, default: Float): Float =
        optDouble(key, default.toDouble()).toFloat()

    private fun isoTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }

    private fun escapeXmlText(text: String): String {
        val sb = StringBuilder(text.length + 32)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescapeXmlText(text: String): String {
        return text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}
