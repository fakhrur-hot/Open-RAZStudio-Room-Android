/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * These four setting items are backed by the `batch_protection_prefs`
 * SharedPreferences file (same name used by
 * feature/photo-editor/.../BatchProtectionPrefs.kt). The keys and enum names
 * are duplicated here intentionally to avoid making feature/settings depend
 * on feature/photo-editor.
 */

package com.RAZStudio.StudioRoom.feature.settings.presentation.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.BatchPrediction
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.preferences.PreferenceItem
import com.RAZStudio.StudioRoom.core.ui.widget.preferences.PreferenceRow

private const val PREFS_NAME    = "batch_protection_prefs"
private const val KEY_SCREEN    = "screen_protection_enabled"
private const val KEY_BRIGHT    = "brightness_level"
private const val KEY_MUSIC     = "music_enabled"
private const val KEY_VOLUME    = "volume_level"

private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

@Composable
fun BatchScreenProtectionSettingItem(
    shape: Shape = ShapeDefaults.center,
    modifier: Modifier = Modifier.padding(horizontal = 8.dp),
) {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(prefs(context).getBoolean(KEY_SCREEN, true))
    }
    PreferenceRow(
        shape = shape,
        modifier = modifier,
        startIcon = Icons.Outlined.BatchPrediction,
        title = stringResource(R.string.batch_screen_protection),
        subtitle = stringResource(R.string.batch_screen_protection_sub),
        onClick = {
            enabled = !enabled
            prefs(context).edit().putBoolean(KEY_SCREEN, enabled).apply()
        },
        endContent = {
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                prefs(context).edit().putBoolean(KEY_SCREEN, it).apply()
            })
        },
    )
}

private data class LabeledOption(val key: String, val label: String)

@Composable
fun BatchBrightnessSettingItem(
    shape: Shape = ShapeDefaults.center,
    modifier: Modifier = Modifier.padding(horizontal = 8.dp),
) {
    val context = LocalContext.current
    val options = listOf(
        LabeledOption("Lowest",    stringResource(R.string.batch_brightness_lowest)),
        LabeledOption("Low",       stringResource(R.string.batch_brightness_low)),
        LabeledOption("Medium",    stringResource(R.string.batch_brightness_medium)),
        LabeledOption("NeverHigh", stringResource(R.string.batch_brightness_never_high)),
    )
    var current by remember {
        mutableStateOf(prefs(context).getString(KEY_BRIGHT, null) ?: "Low")
    }
    val selectedLabel = options.find { it.key == current }?.label
    PreferenceItem(
        title = stringResource(R.string.batch_brightness),
        subtitle = selectedLabel ?: stringResource(R.string.batch_brightness_sub),
        startIcon = Icons.Outlined.BatchPrediction,
        shape = shape,
        modifier = modifier,
        placeBottomContentInside = true,
        bottomContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 8.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    options.forEach { opt ->
                        FilterChip(
                            selected = current == opt.key,
                            onClick = {
                                current = opt.key
                                prefs(context).edit().putString(KEY_BRIGHT, opt.key).apply()
                            },
                            label = { Text(opt.label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun BatchMusicEnabledSettingItem(
    shape: Shape = ShapeDefaults.center,
    modifier: Modifier = Modifier.padding(horizontal = 8.dp),
) {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(prefs(context).getBoolean(KEY_MUSIC, true))
    }
    PreferenceRow(
        shape = shape,
        modifier = modifier,
        startIcon = Icons.Outlined.BatchPrediction,
        title = stringResource(R.string.batch_music_enabled),
        subtitle = stringResource(R.string.batch_music_enabled_sub),
        onClick = {
            enabled = !enabled
            prefs(context).edit().putBoolean(KEY_MUSIC, enabled).apply()
        },
        endContent = {
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                prefs(context).edit().putBoolean(KEY_MUSIC, it).apply()
            })
        },
    )
}

@Composable
fun BatchMusicVolumeSettingItem(
    shape: Shape = ShapeDefaults.center,
    modifier: Modifier = Modifier.padding(horizontal = 8.dp),
) {
    val context = LocalContext.current
    val options = listOf(
        LabeledOption("Lowest", stringResource(R.string.batch_volume_lowest)),
        LabeledOption("Low",    stringResource(R.string.batch_volume_low)),
        LabeledOption("Medium", stringResource(R.string.batch_volume_medium)),
        LabeledOption("High",   stringResource(R.string.batch_volume_high)),
    )
    var current by remember {
        mutableStateOf(prefs(context).getString(KEY_VOLUME, null) ?: "Medium")
    }
    val selectedLabel = options.find { it.key == current }?.label
    PreferenceItem(
        title = stringResource(R.string.batch_music_volume),
        subtitle = selectedLabel ?: stringResource(R.string.batch_music_volume_sub),
        startIcon = Icons.Outlined.BatchPrediction,
        shape = shape,
        modifier = modifier,
        placeBottomContentInside = true,
        bottomContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 8.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    options.forEach { opt ->
                        FilterChip(
                            selected = current == opt.key,
                            onClick = {
                                current = opt.key
                                prefs(context).edit().putString(KEY_VOLUME, opt.key).apply()
                            },
                            label = { Text(opt.label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }
        },
    )
}
