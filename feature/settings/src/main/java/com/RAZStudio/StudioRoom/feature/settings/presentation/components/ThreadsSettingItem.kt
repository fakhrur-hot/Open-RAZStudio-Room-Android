/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.settings.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.domain.THREADS_LINK
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.AlternateEmail
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.preferences.PreferenceRow

@Composable
fun ThreadsSettingItem(
    shape: Shape = ShapeDefaults.center,
    modifier: Modifier = Modifier.padding(horizontal = 8.dp)
) {
    val linkHandler = LocalUriHandler.current
    PreferenceRow(
        shape = shape,
        onClick = { linkHandler.openUri(THREADS_LINK) },
        startIcon = Icons.Rounded.AlternateEmail,
        title = stringResource(R.string.threads_follow),
        subtitle = stringResource(R.string.threads_follow_sub),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
    )
}
