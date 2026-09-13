/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import com.RAZStudio.StudioRoom.core.database.model.PhotoGridRow
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.resources.icons.Block
import com.RAZStudio.StudioRoom.core.resources.icons.CheckCircle
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowLeft
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowRight
import com.RAZStudio.StudioRoom.core.resources.icons.Star
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.GalleryProjectComponent
import java.io.File

/**
 * Side-by-side compare — the "which of these two do I keep" step culling always
 * ends in, and the reason people bounce between two photos in the loupe.
 *
 * The left pane is PINNED (the photo you pressed Compare on) and the right pane
 * steps through the rest of the grid with the arrows, so a candidate is measured
 * against one reference rather than against whatever came before it.
 *
 * **Zoom is shared.** Pinching moves both panes by the same scale and offset,
 * because the whole point is to look at the same part of two frames — matching
 * eyes, matching corner sharpness. Independent zoom would make every comparison
 * a manual alignment exercise.
 *
 * Rating and flagging act on the ACTIVE pane (tap a pane to switch), and the
 * active one carries a visible border so the buttons are never ambiguous.
 */
@Composable
internal fun CompareViewer(
    items: LazyPagingItems<PhotoGridRow>,
    pinnedIndex: Int,
    component: GalleryProjectComponent,
    onDismiss: () -> Unit,
) {
    val count = items.itemCount
    if (count == 0) return
    val left = items[pinnedIndex.coerceIn(0, count - 1)] ?: return
    var rightIndex by remember(pinnedIndex) {
        mutableStateOf(((pinnedIndex + 1).takeIf { it < count } ?: (pinnedIndex - 1)).coerceIn(0, count - 1))
    }
    val right = items[rightIndex]
    var leftActive by remember { mutableStateOf(false) }

    // One transform for both panes (see the doc above).
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    BackHandler { onDismiss() }

    // Falls back to the pinned pane while the candidate page is still loading,
    // so the rating buttons always have a target.
    val active: PhotoGridRow = if (leftActive) left else (right ?: left)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(left.id, right?.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    if (scale > 1.01f) {
                        val maxX = size.width * (scale - 1f) / 2f
                        val maxY = size.height * (scale - 1f) / 2f
                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                    } else { offsetX = 0f; offsetY = 0f }
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.ArrowBack, stringResource(R.string.exit), tint = Color.White)
                }
                Text(
                    stringResource(R.string.gallery_compare),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (scale > 1.01f) {
                    TextButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                        Text(stringResource(R.string.gallery_compare_reset_zoom), color = Color.White)
                    }
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                ComparePane(
                    row = left,
                    label = stringResource(R.string.gallery_compare_pinned),
                    isActive = leftActive,
                    scale = scale, offsetX = offsetX, offsetY = offsetY,
                    onActivate = { leftActive = true },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Spacer(Modifier.width(2.dp))
                if (right != null) {
                    ComparePane(
                        row = right,
                        label = "${rightIndex + 1} / $count",
                        isActive = !leftActive,
                        scale = scale, offsetX = offsetX, offsetY = offsetY,
                        onActivate = { leftActive = false },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }

            // Step the CANDIDATE pane, skipping the pinned photo so a comparison
            // never degenerates into a photo against itself.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .navigationBarsPadding()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        var i = rightIndex - 1
                        if (i == pinnedIndex) i--
                        if (i >= 0) rightIndex = i
                    },
                ) {
                    Icon(Icons.Rounded.ArrowLeft, stringResource(R.string.gallery_compare_prev), tint = Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (star in 1..5) {
                        IconButton(onClick = {
                            component.setRating(active.id, if (active.rating == star) 0 else star)
                        }) {
                            Icon(
                                Icons.Rounded.Star, "$star",
                                tint = if (star <= active.rating) Color.White else Color.White.copy(alpha = 0.35f),
                            )
                        }
                    }
                    IconButton(onClick = { component.toggleFlag(active.id) }) {
                        Icon(
                            Icons.Rounded.CheckCircle, stringResource(R.string.gallery_action_flag),
                            tint = if (active.flagState == 1) Color.White else Color.White.copy(alpha = 0.5f),
                        )
                    }
                    IconButton(onClick = { component.toggleReject(active.id) }) {
                        Icon(
                            Icons.Rounded.Block, stringResource(R.string.gallery_action_reject),
                            tint = if (active.flagState == 2) Color.White else Color.White.copy(alpha = 0.5f),
                        )
                    }
                }
                IconButton(
                    onClick = {
                        var i = rightIndex + 1
                        if (i == pinnedIndex) i++
                        if (i < count) rightIndex = i
                    },
                ) {
                    Icon(Icons.Rounded.ArrowRight, stringResource(R.string.gallery_compare_next), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ComparePane(
    row: PhotoGridRow,
    label: String,
    isActive: Boolean,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = rememberPreviewBitmap(row)
    Box(
        modifier
            .background(Color.Black)
            .border(
                width = if (isActive) 2.dp else 0.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
            .clickable(onClick = onActivate),
    ) {
        val layer = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offsetX; translationY = offsetY
            }
        val bmp = preview
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = row.displayName,
                contentScale = ContentScale.Fit,
                modifier = layer,
            )
        } else if (row.thumbPath != null) {
            AsyncImage(
                model = File(row.thumbPath!!),
                contentDescription = row.displayName,
                contentScale = ContentScale.Fit,
                modifier = layer,
            )
        }
        // Rating/flag readout per pane: comparing is pointless if you cannot see
        // what you just assigned without leaving the view.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                row.displayName,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(label)
                    if (row.rating > 0) append("  ").append("★".repeat(row.rating))
                    if (row.flagState == 1) append("  ✓")
                    if (row.flagState == 2) append("  ✗")
                },
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}
