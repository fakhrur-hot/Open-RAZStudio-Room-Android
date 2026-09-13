/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * First-use consent dialog for the Online AI Editing feature (Requirement 7).
 *
 * Shown exactly once per install, before any anonymous Supabase session is
 * created or any photo is uploaded. Gated by
 * [com.RAZStudio.StudioRoom.core.settings.domain.OnlineAiConsentPrefs].
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.RAZStudio.StudioRoom.core.resources.R

/**
 * @param onContinue Called when the user accepts — the caller is responsible
 *   for persisting [com.RAZStudio.StudioRoom.core.settings.domain.OnlineAiConsentPrefs.hasSeenOnlineAiConsent]
 *   and then establishing the anonymous session before the first upload.
 * @param onCancel Called when the user declines — no session is created and
 *   no upload is sent (Requirement 7.3).
 */
@Composable
internal fun RawOnlineAiConsentDialog(
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.raw_online_ai_consent_title)) },
        text = { Text(stringResource(R.string.raw_online_ai_consent_body)) },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.raw_online_ai_consent_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.raw_online_ai_consent_cancel))
            }
        },
    )
}
