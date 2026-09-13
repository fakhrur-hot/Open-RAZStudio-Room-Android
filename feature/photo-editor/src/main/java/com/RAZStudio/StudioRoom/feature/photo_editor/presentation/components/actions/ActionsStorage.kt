/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.actions

import android.content.Context
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.blur.BlurSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.details.DetailsSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.GradientApplyTo
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.GradientBlendMode
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.LayersSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.LinearGradientSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.LinearGradientSide
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.VignetteSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.GlobalPhotoAdjustments
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.LutSelection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists and retrieves named [ActionsSet] instances as JSON files on disk.
 *
 * Each set is stored as `{filesDir}/action_sets/{name}.json`. The [ActionCard.Original]
 * sentinel card is never serialised; it is re-injected automatically at runtime.
 *
 * Thread-safety: all public methods are synchronised on the instance. For coroutine callers
 * prefer dispatching to [kotlinx.coroutines.Dispatchers.IO].
 */
class ActionsStorage(private val context: Context) {

    // -------------------------------------------------------------------------
    // Directory helpers
    // -------------------------------------------------------------------------

    private val storageDir: File
        get() = File(context.filesDir, "action_sets").also { it.mkdirs() }

    private fun fileFor(name: String): File = File(storageDir, "${name}.json")

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Serialises [set] and writes it to `{name}.json`.
     * Overwrites any previously saved set with the same name.
     */
    @Synchronized
    fun saveActionsSet(set: ActionsSet) {
        val json = serialiseActionsSet(set)
        fileFor(set.name).writeText(json, Charsets.UTF_8)
    }

    /**
     * Reads and deserialises every `*.json` file in the storage directory.
     * Files that fail to parse are silently skipped.
     */
    @Synchronized
    fun loadAllActionsSets(): List<ActionsSet> {
        val dir = storageDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { deserialiseActionsSet(file.readText(Charsets.UTF_8)) }.getOrNull()
            }
            ?: emptyList()
    }

    /**
     * Deletes the persisted set identified by [name].
     * No-op if the file does not exist.
     */
    @Synchronized
    fun deleteActionsSet(name: String) {
        fileFor(name).takeIf { it.exists() }?.delete()
    }

    // -------------------------------------------------------------------------
    // Serialisation
    // -------------------------------------------------------------------------

    private fun serialiseActionsSet(set: ActionsSet): String {
        val root = JSONObject()
        root.put("name", set.name)
        root.put("savedAt", set.savedAt)
        val cardsArray = JSONArray()
        // Original is never stored
        set.cards
            .filterNot { it is ActionCard.Original }
            .forEach { card -> cardsArray.put(serialiseCard(card)) }
        root.put("cards", cardsArray)
        return root.toString(2)
    }

    private fun serialiseCard(card: ActionCard): JSONObject {
        val obj = JSONObject()
        obj.put("id", card.id)
        obj.put("displayName", card.displayName)
        obj.put("isVisible", card.isVisible)
        when (card) {
            is ActionCard.LutCard -> {
                obj.put("type", "LUT")
                obj.put("path", card.selection.path)
                obj.put("strength", card.selection.strength)
                obj.put("aiLutEnabled", card.selection.aiLutEnabled)
                obj.put("subjectPop", card.selection.subjectPop)
                obj.put("backgroundBrightness", card.selection.backgroundBrightness)
                obj.put("shadowBoost", card.selection.shadowBoost)
                obj.put("highlightBoost", card.selection.highlightBoost)
                obj.put("edgeBlackClip", card.selection.edgeBlackClip)
                obj.put("subjectTemperature", card.selection.subjectTemperature)
                obj.put("backgroundTemperature", card.selection.backgroundTemperature)
                obj.put("subjectTint", card.selection.subjectTint)
                obj.put("backgroundTint", card.selection.backgroundTint)
                obj.put("highlightTemperature", card.selection.highlightTemperature)
                obj.put("highlightTint", card.selection.highlightTint)
                obj.put("zeroDce", card.selection.zeroDce)
                obj.put("zeroDceStrength", card.selection.zeroDceStrength)
                // Global adjustments (HSL skipped — stored as 0)
                val ga = card.selection.globalAdjustments
                val gaObj = JSONObject()
                gaObj.put("exposure", ga.exposure)
                gaObj.put("contrast", ga.contrast)
                gaObj.put("saturation", ga.saturation)
                gaObj.put("vibrance", ga.vibrance)
                obj.put("globalAdjustments", gaObj)
            }

            is ActionCard.BlurCard -> {
                obj.put("type", "BLUR")
                val sel = card.selection
                // Store the filter class name and its numeric value so the card round-trips
                // without pulling in serialisation infrastructure for UiFilter<*>.
                obj.put("filterClass", sel.filter::class.java.simpleName)
                obj.put("filterValue", sel.filter.value.toString())
                obj.put("aiEnabled", sel.aiEnabled)
                obj.put("edgeBlur", sel.edgeBlur)
                obj.put("bokehEdges", sel.bokehEdges)
                obj.put("dofMode", sel.dofMode.name)
            }

            is ActionCard.DetailsCard -> {
                obj.put("type", "DETAILS")
                val sel = card.selection
                obj.put("smartSharpness", sel.smartSharpness)
                obj.put("luminanceNR", sel.luminanceNR)
                obj.put("colorNR", sel.colorNR)
                obj.put("filmGrain", sel.filmGrain)
                obj.put("filmGrainSize", sel.filmGrainSize)
                obj.put("filmGrainUniformity", sel.filmGrainUniformity)
                obj.put("filmGrainWashOut", sel.filmGrainWashOut)
            }

            is ActionCard.LayersCard -> {
                obj.put("type", "LAYERS")
                val vig = card.selection.vignette
                val vigObj = JSONObject()
                vigObj.put("intensity", vig.intensity)
                vigObj.put("feather", vig.feather)
                vigObj.put("roundness", vig.roundness)
                vigObj.put("midpoint", vig.midpoint)
                vigObj.put("centerX", vig.centerX)
                vigObj.put("centerY", vig.centerY)
                vigObj.put("includeSubject", vig.includeSubject)
                obj.put("vignette", vigObj)

                val lg = card.selection.linearGradient
                val lgObj = JSONObject()
                lgObj.put("angle", lg.angle)
                lgObj.put("applyTo", lg.applyTo.name)
                lgObj.put("top", serialiseGradientSide(lg.top))
                lgObj.put("bottom", serialiseGradientSide(lg.bottom))
                lgObj.put("left", serialiseGradientSide(lg.left))
                lgObj.put("right", serialiseGradientSide(lg.right))
                obj.put("linearGradient", lgObj)
            }

            is ActionCard.VignetteCard -> {
                obj.put("type", "VIGNETTE")
                val vig = card.selection
                val vigObj = JSONObject()
                vigObj.put("intensity", vig.intensity)
                vigObj.put("feather", vig.feather)
                vigObj.put("roundness", vig.roundness)
                vigObj.put("midpoint", vig.midpoint)
                vigObj.put("centerX", vig.centerX)
                vigObj.put("centerY", vig.centerY)
                vigObj.put("includeSubject", vig.includeSubject)
                obj.put("vignette", vigObj)
            }

            is ActionCard.LinearGradientCard -> {
                obj.put("type", "LINEAR_GRADIENT")
                val lg = card.selection
                val lgObj = JSONObject()
                lgObj.put("angle", lg.angle)
                lgObj.put("applyTo", lg.applyTo.name)
                lgObj.put("top", serialiseGradientSide(lg.top))
                lgObj.put("bottom", serialiseGradientSide(lg.bottom))
                lgObj.put("left", serialiseGradientSide(lg.left))
                lgObj.put("right", serialiseGradientSide(lg.right))
                obj.put("linearGradient", lgObj)
            }

            is ActionCard.ToneCurvesCard -> {
                obj.put("type", "TONE_CURVES")
                val outerArray = JSONArray()
                card.controlPoints.forEach { curve ->
                    val innerArray = JSONArray()
                    curve.forEach { v -> innerArray.put(v) }
                    outerArray.put(innerArray)
                }
                obj.put("controlPoints", outerArray)
            }

            is ActionCard.Original -> {
                // Should never be reached — Original is filtered out before this call
                obj.put("type", "ORIGINAL")
            }
        }
        return obj
    }

    private fun serialiseGradientSide(side: LinearGradientSide): JSONObject {
        val obj = JSONObject()
        obj.put("intensity", side.intensity)
        obj.put("length", side.length)
        obj.put("feather", side.feather)
        obj.put("tintColor", side.tintColor)
        obj.put("tintLuminosity", side.tintLuminosity)
        obj.put("tintApplyTo", side.tintApplyTo.name)
        obj.put("blendMode", side.blendMode.name)
        return obj
    }

    // -------------------------------------------------------------------------
    // Deserialisation
    // -------------------------------------------------------------------------

    private fun deserialiseActionsSet(json: String): ActionsSet {
        val root = JSONObject(json)
        val name = root.getString("name")
        val savedAt = root.optLong("savedAt", System.currentTimeMillis())
        val cardsArray = root.getJSONArray("cards")
        val cards = (0 until cardsArray.length()).mapNotNull { i ->
            runCatching { deserialiseCard(cardsArray.getJSONObject(i)) }.getOrNull()
        }
        return ActionsSet(name = name, cards = cards, savedAt = savedAt)
    }

    private fun deserialiseCard(obj: JSONObject): ActionCard? {
        val id = obj.getString("id")
        val displayName = obj.getString("displayName")
        val isVisible = obj.optBoolean("isVisible", true)

        return when (val type = obj.getString("type")) {
            "LUT" -> {
                val ga = obj.optJSONObject("globalAdjustments")
                val globalAdjustments = if (ga != null) {
                    GlobalPhotoAdjustments(
                        exposure = ga.optDouble("exposure", 0.0).toFloat(),
                        contrast = ga.optDouble("contrast", 0.0).toFloat(),
                        saturation = ga.optDouble("saturation", 0.0).toFloat(),
                        vibrance = ga.optDouble("vibrance", 0.0).toFloat(),
                        // HSL panels not persisted — kept at default zero values
                    )
                } else {
                    GlobalPhotoAdjustments()
                }
                ActionCard.LutCard(
                    id = id,
                    displayName = displayName,
                    isVisible = isVisible,
                    selection = LutSelection(
                        path = obj.getString("path"),
                        strength = obj.optDouble("strength", 1.0).toFloat(),
                        globalAdjustments = globalAdjustments,
                        aiLutEnabled = obj.optBoolean("aiLutEnabled", false),
                        subjectPop = obj.optDouble("subjectPop", 0.0).toFloat(),
                        backgroundBrightness = obj.optDouble("backgroundBrightness", 0.0).toFloat(),
                        shadowBoost = obj.optDouble("shadowBoost", 0.0).toFloat(),
                        highlightBoost = obj.optDouble("highlightBoost", 0.0).toFloat(),
                        edgeBlackClip = obj.optDouble("edgeBlackClip", 0.0).toFloat(),
                        subjectTemperature = obj.optDouble("subjectTemperature", 0.0).toFloat(),
                        backgroundTemperature = obj.optDouble("backgroundTemperature", 0.0).toFloat(),
                        subjectTint = obj.optDouble("subjectTint", 0.0).toFloat(),
                        backgroundTint = obj.optDouble("backgroundTint", 0.0).toFloat(),
                        highlightTemperature = obj.optDouble("highlightTemperature", 0.0).toFloat(),
                        highlightTint = obj.optDouble("highlightTint", 0.0).toFloat(),
                        zeroDce = obj.optBoolean("zeroDce", false),
                        zeroDceStrength = obj.optDouble("zeroDceStrength", 1.0).toFloat(),
                    ),
                )
            }

            "BLUR" -> {
                // BlurSelection requires a UiFilter<*> instance. Restoring a concrete
                // UiFilter from just a class name requires the filter registry, which is
                // outside the scope of plain JSON storage. We persist the metadata so the
                // card can at least be identified; callers that need a live UiFilter should
                // restore from the surrounding ViewModel state instead of re-hydrating here.
                // Return null so the broken card is silently dropped on load.
                null
            }

            "DETAILS" -> {
                ActionCard.DetailsCard(
                    id = id,
                    displayName = displayName,
                    isVisible = isVisible,
                    selection = DetailsSelection(
                        smartSharpness = obj.optDouble("smartSharpness", 0.0).toFloat(),
                        luminanceNR = obj.optDouble("luminanceNR", 0.0).toFloat(),
                        colorNR = obj.optDouble("colorNR", 0.0).toFloat(),
                        filmGrain = obj.optDouble("filmGrain", 0.0).toFloat(),
                        filmGrainSize = obj.optDouble("filmGrainSize", 0.5).toFloat(),
                        filmGrainUniformity = obj.optDouble("filmGrainUniformity", 0.0).toFloat(),
                        filmGrainWashOut = obj.optDouble("filmGrainWashOut", 0.0).toFloat(),
                    ),
                )
            }

            "LAYERS" -> {
                val vigObj = obj.optJSONObject("vignette")
                val vignette = if (vigObj != null) {
                    VignetteSelection(
                        intensity = vigObj.optDouble("intensity", 0.0).toFloat(),
                        feather = vigObj.optDouble("feather", 0.5).toFloat(),
                        roundness = vigObj.optDouble("roundness", 0.0).toFloat(),
                        midpoint = vigObj.optDouble("midpoint", 0.5).toFloat(),
                        centerX = vigObj.optDouble("centerX", 0.5).toFloat(),
                        centerY = vigObj.optDouble("centerY", 0.5).toFloat(),
                        includeSubject = vigObj.optBoolean("includeSubject", true),
                    )
                } else {
                    VignetteSelection()
                }

                val lgObj = obj.optJSONObject("linearGradient")
                val linearGradient = if (lgObj != null) {
                    val applyTo = runCatching {
                        GradientApplyTo.valueOf(lgObj.optString("applyTo", "All"))
                    }.getOrDefault(GradientApplyTo.All)
                    LinearGradientSelection(
                        angle = lgObj.optDouble("angle", 0.0).toFloat(),
                        applyTo = applyTo,
                        top = deserialiseGradientSide(lgObj.optJSONObject("top")),
                        bottom = deserialiseGradientSide(lgObj.optJSONObject("bottom")),
                        left = deserialiseGradientSide(lgObj.optJSONObject("left")),
                        right = deserialiseGradientSide(lgObj.optJSONObject("right")),
                    )
                } else {
                    LinearGradientSelection()
                }

                ActionCard.LayersCard(
                    id = id,
                    displayName = displayName,
                    isVisible = isVisible,
                    selection = LayersSelection(
                        vignette = vignette,
                        linearGradient = linearGradient,
                    ),
                )
            }

            "VIGNETTE" -> {
                val vigObj = obj.optJSONObject("vignette")
                val vignette = if (vigObj != null) {
                    VignetteSelection(
                        intensity = vigObj.optDouble("intensity", 0.0).toFloat(),
                        feather = vigObj.optDouble("feather", 0.5).toFloat(),
                        roundness = vigObj.optDouble("roundness", 0.0).toFloat(),
                        midpoint = vigObj.optDouble("midpoint", 0.5).toFloat(),
                        centerX = vigObj.optDouble("centerX", 0.5).toFloat(),
                        centerY = vigObj.optDouble("centerY", 0.5).toFloat(),
                        includeSubject = vigObj.optBoolean("includeSubject", true),
                    )
                } else {
                    VignetteSelection()
                }

                ActionCard.VignetteCard(
                    id = id,
                    displayName = displayName,
                    isVisible = isVisible,
                    selection = vignette,
                )
            }

            "LINEAR_GRADIENT" -> {
                val lgObj = obj.optJSONObject("linearGradient")
                val linearGradient = if (lgObj != null) {
                    val applyTo = runCatching {
                        GradientApplyTo.valueOf(lgObj.optString("applyTo", "All"))
                    }.getOrDefault(GradientApplyTo.All)
                    LinearGradientSelection(
                        angle = lgObj.optDouble("angle", 0.0).toFloat(),
                        applyTo = applyTo,
                        top = deserialiseGradientSide(lgObj.optJSONObject("top")),
                        bottom = deserialiseGradientSide(lgObj.optJSONObject("bottom")),
                        left = deserialiseGradientSide(lgObj.optJSONObject("left")),
                        right = deserialiseGradientSide(lgObj.optJSONObject("right")),
                    )
                } else {
                    LinearGradientSelection()
                }

                ActionCard.LinearGradientCard(
                    id = id,
                    displayName = displayName,
                    isVisible = isVisible,
                    selection = linearGradient,
                )
            }

            "TONE_CURVES" -> {
                val outerArray = obj.optJSONArray("controlPoints")
                val controlPoints: List<List<Float>> = if (outerArray != null) {
                    (0 until outerArray.length()).map { i ->
                        val innerArray = outerArray.optJSONArray(i)
                        if (innerArray != null) {
                            (0 until innerArray.length()).map { j -> innerArray.optDouble(j, 0.0).toFloat() }
                        } else {
                            emptyList()
                        }
                    }
                } else {
                    emptyList()
                }
                ActionCard.ToneCurvesCard(
                    id = id,
                    displayName = displayName,
                    isVisible = isVisible,
                    controlPoints = controlPoints,
                )
            }

            else -> null // Unknown type — silently skip
        }
    }

    private fun deserialiseGradientSide(obj: JSONObject?): LinearGradientSide {
        if (obj == null) return LinearGradientSide()
        val blendMode = runCatching {
            GradientBlendMode.valueOf(obj.optString("blendMode", "Solid"))
        }.getOrDefault(GradientBlendMode.Solid)
        return LinearGradientSide(
            intensity = obj.optDouble("intensity", 0.0).toFloat(),
            length = obj.optDouble("length", 0.3).toFloat(),
            feather = obj.optDouble("feather", 0.5).toFloat(),
            tintColor = obj.optInt("tintColor", android.graphics.Color.WHITE),
            tintLuminosity = obj.optDouble("tintLuminosity", 0.0).toFloat(),
            tintApplyTo = runCatching {
                GradientApplyTo.valueOf(obj.optString("tintApplyTo", "All"))
            }.getOrDefault(GradientApplyTo.All),
            blendMode = blendMode,
        )
    }
}
