/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.feature.photo_editor.data.network.BeautifyStrength
import com.RAZStudio.StudioRoom.feature.photo_editor.data.network.OnlineAiEditResult
import kotlinx.coroutines.delay

/**
 * Online AI Editing sheet — AI Beautify only (Requirement 2.2, v1 scope).
 * Structurally modeled on [RawHealSheet]: full-screen sheet, before/after
 * preview swap, Apply/Cancel actions, a busy indicator while the network
 * call is in flight.
 *
 * Unlike Heal, there is no local gesture/brush loop here — the "edit" is a
 * single cloud round-trip per strength selection, debounced so dragging
 * across Low/Medium/High doesn't fire a request per intermediate value
 * (Requirement 2.5).
 *
 * @param source The preview bitmap to beautify (already the 512-long-side
 *   preview reused for on-device segmentation — Requirement 3.5).
 * @param onDismiss Called on Cancel/back — the source photo is left
 *   unchanged (Requirement 6.4 note: no partial result is ever applied).
 * @param onSubmit Suspends until the cloud result is known for the given
 *   [BeautifyStrength]. Supplied by the caller ([RawExportScreen]) as a
 *   thin wrapper around `RawEditorComponent.submitOnlineAiBeautify`, keeping
 *   this composable free of a direct `RawEditorComponent` dependency (same
 *   separation [RawHealSheet] already uses).
 * @param onApplied Called on Apply with the committed result bitmap and the
 *   Worker's job id (threaded through to the Cloud_Edit_Tag EXIF write at
 *   export time — Requirement 8.1).
 * @param onUseHealInstead Called when the user taps the quota-exceeded
 *   fallback action (Requirement 6.2) — the caller dismisses this sheet and
 *   opens [RawHealSheet] in its place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RawOnlineAiEditSheet(
    source: Bitmap,
    onDismiss: () -> Unit,
    onSubmit: suspend (BeautifyStrength) -> OnlineAiEditResult,
    onApplied: (Bitmap, String) -> Unit,
    onUseHealInstead: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    var strength by remember { mutableStateOf(BeautifyStrength.Medium) }
    var isSubmitting by remember { mutableStateOf(false) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultJobId by remember { mutableStateOf("") }
    var quotaExceeded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Debounced re-submission on strength change (Requirement 2.5). Keyed on
    // `strength` so changing the selection cancels any in-flight debounce
    // wait and restarts it; also runs once on first composition (Medium).
    LaunchedEffect(strength) {
        delay(400)
        isSubmitting = true
        quotaExceeded = false
        errorMessage = null
        when (val result = onSubmit(strength)) {
            is OnlineAiEditResult.Success -> {
                resultBitmap = result.resultBitmap
                resultJobId = result.jobId
            }

            is OnlineAiEditResult.QuotaExceeded -> {
                quotaExceeded = true
            }

            is OnlineAiEditResult.Failure -> {
                errorMessage = result.reason
            }
        }
        isSubmitting = false
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(stringResource(R.string.raw_online_ai_sheet_title)) },
                actions = {
                    Button(
                        enabled = resultBitmap != null && !isSubmitting,
                        onClick = {
                            resultBitmap?.let { onApplied(it, resultJobId) }
                        },
                    ) {
                        Text(stringResource(R.string.raw_online_ai_apply))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Image(
                    bitmap = (resultBitmap ?: source).asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (isSubmitting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (quotaExceeded) {
                Text(
                    text = stringResource(R.string.raw_online_ai_quota_message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedButton(onClick = onUseHealInstead, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.raw_online_ai_use_heal_instead))
                }
            } else if (errorMessage != null) {
                Text(
                    text = "${stringResource(R.string.raw_online_ai_generic_error)} ($errorMessage)",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BeautifyStrength.entries.forEach { level ->
                    val label = when (level) {
                        BeautifyStrength.Low -> stringResource(R.string.raw_online_ai_strength_low)
                        BeautifyStrength.Medium -> stringResource(R.string.raw_online_ai_strength_medium)
                        BeautifyStrength.High -> stringResource(R.string.raw_online_ai_strength_high)
                    }
                    if (strength == level) {
                        Button(
                            onClick = { strength = level },
                            enabled = !isSubmitting,
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { strength = level },
                            enabled = !isSubmitting,
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.raw_online_ai_cancel))
                }
            }
        }
    }
}
