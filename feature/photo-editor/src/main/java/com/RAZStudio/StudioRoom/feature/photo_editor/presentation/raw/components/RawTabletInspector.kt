/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.ui.theme.Elevation
import com.RAZStudio.StudioRoom.core.ui.theme.Spacing

@Composable
internal fun RawTabletInspector(
    gradedHistogram: IntArray?,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = Elevation.panel,
        shadowElevation = Elevation.panel,
    ) {
        Column(modifier = Modifier.padding(Spacing.section)) {
            Text(
                text = "Histogram",
                style = MaterialTheme.typography.titleSmall,
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.item)
                    .height(120.dp),
            ) {
                val bins = gradedHistogram
                if (bins == null || bins.isEmpty()) return@Canvas
                val peak = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
                val barW = size.width / bins.size
                bins.forEachIndexed { i, count ->
                    val h = size.height * (count / peak)
                    drawRect(
                        color = barColor,
                        topLeft = Offset(i * barW, size.height - h),
                        size = Size(barW, h),
                    )
                }
            }
        }
    }
}
