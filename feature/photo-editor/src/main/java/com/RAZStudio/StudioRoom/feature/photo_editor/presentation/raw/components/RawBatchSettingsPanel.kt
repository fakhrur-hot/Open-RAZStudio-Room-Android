/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons as AppIcons
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowDropDown
import com.RAZStudio.StudioRoom.core.settings.domain.RawBatchPrefs
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawPresetsStorage

/**
 * State holder for batch settings.
 * Format/quality/scale/sharpen come from the Settings page (same as single-save).
 * Preset selection, per-file auto-exposure, EXIF policy, and ICC embedding are
 * owned here.
 */
@Stable
class RawBatchSettings internal constructor(
    private val prefs: RawBatchPrefs,
) {
    // Preset identity is persisted by stable fileName (survives preset
    // add/remove/reorder); the list index is resolved via [reconcilePreset]
    // once the panel knows the live preset list.
    private var _selectedPresetIndex by mutableStateOf<Int?>(null)
    private var _selectedPresetFileName by mutableStateOf(prefs.batchPresetFileName.ifEmpty { null })
    /** Per-file auto-exposure. Fresh-install default OFF (matches editor). */
    private var _autoExposure by mutableStateOf(prefs.batchAutoExposure)
    /** Lens profile correction, auto-detected per file from its EXIF. Default ON. */
    private var _lensCorrection by mutableStateOf(prefs.batchLensCorrection)
    /** Pre-demosaic HDR+shadow U-Net recovery under Route B. Default ON. */
    private var _aiReconstruct by mutableStateOf(prefs.batchAiReconstruct)
    /** Same, under Route A (Camera Color Profile). Default OFF — mirrors the
     *  workspace selector's two independent Route A / Route B toggles. */
    private var _aiReconstructRouteA by mutableStateOf(prefs.batchAiReconstructRouteA)
    /** Post-demosaic LMMSE denoise + USM sharpen. Fresh-install default OFF. */
    private var _aiEnhance by mutableStateOf(prefs.batchAiEnhance)
    /** Edge-aware guided-filter smoothing. Standalone. Fresh-install default ON. */
    private var _guidedFilter by mutableStateOf(prefs.batchGuidedFilter)
    /** Route A: match in-camera colour, full RAW detail. Default OFF (route B). */
    private var _useCameraColorProfile by mutableStateOf(prefs.batchUseCameraColorProfile)
    /** Which source formats the folder batch runs on. Default RAW_AND_JPEG. */
    private var _formatFilter by mutableStateOf(
        RawBatchProcessor.FormatFilter.entries
            .getOrElse(prefs.batchFormatFilterOrdinal) { RawBatchProcessor.FormatFilter.RAW_AND_JPEG }
    )
    /** Which EXIF tags to retain in exported files. Default KeepAll. */
    private var _exifPolicy by mutableStateOf(
        RawBatchProcessor.ExifPolicy.entries
            .getOrElse(prefs.batchExifPolicyOrdinal) { RawBatchProcessor.ExifPolicy.KeepAll }
    )
    /** Embed an ICC profile in exported files. Fresh-install default OFF. */
    private var _saveIcc by mutableStateOf(prefs.batchSaveIcc)
    /** LibRaw adjust_maximum_thr. Off 0/Low .75/Standard .95/Strong .85. Default Strong. */
    private var _highlightProtection by mutableStateOf(prefs.batchHighlightProtection)

    val selectedPresetIndex: Int? get() = _selectedPresetIndex
    /** Batch NEVER runs per-file Auto Expose (removed 2026-07-10): the
     *  headless solve could not be made to match the editor's baked AE
     *  exactly, so defaults-parity is enforced by not offering it at all.
     *  Presets that carry a baked AE action still apply unchanged. */
    val autoExposure: Boolean get() = false
    val lensCorrection: Boolean get() = _lensCorrection
    /** Effective AI Level Reconstruct value for the CURRENT route — the
     *  checkbox and every downstream caller (RawBatchSection/RawEditorContent/
     *  Canon) read/write this one property; which backing pref it dispatches
     *  to depends on [useCameraColorProfile]. */
    val aiReconstruct: Boolean get() = if (_useCameraColorProfile) _aiReconstructRouteA else _aiReconstruct
    /** AI Enhance is Route-B-only; forced off under Camera Color Profile. */
    val aiEnhance: Boolean get() = if (_useCameraColorProfile) false else _aiEnhance
    val guidedFilter: Boolean get() = _guidedFilter
    val useCameraColorProfile: Boolean get() = _useCameraColorProfile
    val formatFilter: RawBatchProcessor.FormatFilter get() = _formatFilter
    val exifPolicy: RawBatchProcessor.ExifPolicy get() = _exifPolicy
    val saveIcc: Boolean get() = _saveIcc
    val highlightProtection: Float get() = _highlightProtection

    /** Select a preset by list [index]; [fileName] is its stable id (null = none). */
    fun setPreset(index: Int?, fileName: String?) {
        _selectedPresetIndex = index
        _selectedPresetFileName = fileName
        prefs.batchPresetFileName = fileName ?: ""
    }

    /** Resolve the persisted preset fileName to an index in [presets]. Called by
     *  the panel once the live list is known so the dropdown restores its choice. */
    fun reconcilePreset(presets: List<RawPresetsStorage.Preset>) {
        val fn = _selectedPresetFileName ?: return
        _selectedPresetIndex = presets.indexOfFirst { it.fileName == fn }.takeIf { it >= 0 }
    }

    fun setAutoExposure(value: Boolean)  { _autoExposure  = value; prefs.batchAutoExposure  = value }
    fun setLensCorrection(value: Boolean) { _lensCorrection = value; prefs.batchLensCorrection = value }
    fun setAiReconstruct(value: Boolean) {
        if (_useCameraColorProfile) { _aiReconstructRouteA = value; prefs.batchAiReconstructRouteA = value }
        else { _aiReconstruct = value; prefs.batchAiReconstruct = value }
    }
    fun setAiEnhance(value: Boolean)     { _aiEnhance     = value; prefs.batchAiEnhance     = value }
    fun setGuidedFilter(value: Boolean)  { _guidedFilter  = value; prefs.batchGuidedFilter  = value }
    fun setUseCameraColorProfile(value: Boolean) {
        _useCameraColorProfile = value; prefs.batchUseCameraColorProfile = value
    }
    fun setFormatFilter(value: RawBatchProcessor.FormatFilter) {
        _formatFilter = value; prefs.batchFormatFilterOrdinal = value.ordinal
    }
    fun setExifPolicy(value: RawBatchProcessor.ExifPolicy) {
        _exifPolicy = value; prefs.batchExifPolicyOrdinal = value.ordinal
    }
    fun setSaveIcc(value: Boolean)       { _saveIcc       = value; prefs.batchSaveIcc       = value }
    fun setHighlightProtection(value: Float) {
        _highlightProtection = value; prefs.batchHighlightProtection = value
    }
}

@Composable
fun rememberRawBatchSettings(): RawBatchSettings {
    val context = LocalContext.current
    return remember { RawBatchSettings(RawBatchPrefs(context.applicationContext)) }
}

/**
 * Batch settings panel: preset dropdown + per-file auto-exposure checkbox.
 * Everything else (format, quality, scale, sharpen) comes from the Settings page.
 */
@Composable
fun RawBatchSettingsPanel(
    settings: RawBatchSettings,
    presets: List<RawPresetsStorage.Preset>,
    enabled: Boolean = true,
    /** When false, the Camera Color Profile / RAZStudio RAW depth radio is hidden
     *  here (the folder batch renders it under the Source folder instead). The
     *  route-specific toggles below still appear/gate on the shared
     *  useCameraColorProfile (AI Level Reconstruct: both routes, own default
     *  each; AI Enhance + Guided Filter: Route B only). */
    showColorProfile: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var presetExpanded by remember { mutableStateOf(false) }

    // Restore the persisted preset selection once the live list is available.
    LaunchedEffect(presets) { settings.reconcilePreset(presets) }

    Column(modifier = modifier) {

        // ── Preset dropdown ───────────────────────────────────────────────
        SectionLabel("Preset")
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick  = { if (enabled) presetExpanded = true },
                enabled  = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text     = settings.selectedPresetIndex
                        ?.let { presets.getOrNull(it)?.name } ?: "No preset",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector        = AppIcons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier           = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded         = presetExpanded,
                onDismissRequest = { presetExpanded = false },
            ) {
                DropdownMenuItem(
                    text    = { Text("No preset") },
                    onClick = { settings.setPreset(null, null); presetExpanded = false },
                )
                presets.forEachIndexed { index, preset ->
                    DropdownMenuItem(
                        text    = { Text(preset.name) },
                        onClick = { settings.setPreset(index, preset.fileName); presetExpanded = false },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── Colour profile: route A vs B (mirrors workspace selector) ─────
        if (showColorProfile) {
            SectionLabel("Color")
            Spacer(Modifier.height(4.dp))
            BatchColorProfileRow(
                label      = "Camera Color Profile · full RAW detail",
                isSelected = settings.useCameraColorProfile,
                enabled    = enabled,
            ) { settings.setUseCameraColorProfile(true) }
            BatchColorProfileRow(
                label      = "RAZStudio RAW depth",
                isSelected = !settings.useCameraColorProfile,
                enabled    = enabled,
            ) { settings.setUseCameraColorProfile(false) }
            Spacer(Modifier.height(6.dp))
        }

        // ── Lens profile correction (per-file) — checkbox, same style as AI ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { settings.setLensCorrection(!settings.lensCorrection) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked         = settings.lensCorrection,
                onCheckedChange = { if (enabled) settings.setLensCorrection(it) },
                enabled         = enabled,
            )
            Text(
                text  = "Lens profile correction",
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // AI Level Reconstruct — Camera Color Profile path only (route A).
        // Route B / AI Enhance UI removed to match the workspace selector.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { settings.setAiReconstruct(!settings.aiReconstruct) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked         = settings.aiReconstruct,
                onCheckedChange = { if (enabled) settings.setAiReconstruct(it) },
                enabled         = enabled,
            )
            Text(
                text  = "AI Level Reconstruct",
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))

        // ── Highlight Protection (LibRaw adjust_maximum_thr) ──────────────
        // Applies at Stage A decode for BOTH routes, so it's always shown.
        // Same 4 levels as the workspace selector; Strong is the default.
        SectionLabel("Highlight Protection")
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Lower thr = more aggressive adjust_maximum. Strong 0.85 default.
            listOf(
                "Off"      to 0.00f,
                "Low"      to 0.75f,
                "Standard" to 0.95f,
                "Strong"   to 0.85f,
            ).forEach { (label, thr) ->
                val selected = kotlin.math.abs(settings.highlightProtection - thr) < 0.03f
                androidx.compose.material3.FilterChip(
                    selected = selected,
                    onClick  = { if (enabled) settings.setHighlightProtection(thr) },
                    enabled  = enabled,
                    label    = { Text(label, style = MaterialTheme.typography.bodySmall) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))

        // ── EXIF policy ───────────────────────────────────────────────────
        SectionLabel("EXIF")
        Spacer(Modifier.height(4.dp))
        var exifExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(
                onClick  = { if (enabled) exifExpanded = true },
                enabled  = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text     = exifPolicyLabel(settings.exifPolicy),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector        = AppIcons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier           = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded         = exifExpanded,
                onDismissRequest = { exifExpanded = false },
            ) {
                RawBatchProcessor.ExifPolicy.entries.forEach { policy ->
                    DropdownMenuItem(
                        text    = { Text(exifPolicyLabel(policy)) },
                        onClick = { settings.setExifPolicy(policy); exifExpanded = false },
                    )
                }
            }
        }

        // ── Embed ICC profile ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { settings.setSaveIcc(!settings.saveIcc) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked         = settings.saveIcc,
                onCheckedChange = { if (enabled) settings.setSaveIcc(it) },
                enabled         = enabled,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text  = "Embed ICC profile",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

private fun exifPolicyLabel(policy: RawBatchProcessor.ExifPolicy): String = when (policy) {
    RawBatchProcessor.ExifPolicy.KeepAll -> "Keep all metadata"
    RawBatchProcessor.ExifPolicy.StripSensitive -> "Strip location/camera serial"
    RawBatchProcessor.ExifPolicy.NoneExceptSoftware -> "None except software tag"
}

@Composable
private fun BatchColorProfileRow(
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onPick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = isSelected,
            onClick  = { if (enabled) onPick() },
            enabled  = enabled,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text  = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}
