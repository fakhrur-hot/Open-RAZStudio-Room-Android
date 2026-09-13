/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import com.RAZStudio.StudioRoom.core.database.model.Keywords
import com.RAZStudio.StudioRoom.core.database.model.PhotoGridRow
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.resources.icons.Block
import com.RAZStudio.StudioRoom.core.resources.icons.CheckCircle
import com.RAZStudio.StudioRoom.core.resources.icons.EditAlt
import com.RAZStudio.StudioRoom.core.resources.icons.Info
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import com.RAZStudio.StudioRoom.core.resources.icons.Label
import com.RAZStudio.StudioRoom.core.resources.icons.History
import com.RAZStudio.StudioRoom.core.resources.icons.CompareArrows
import com.RAZStudio.StudioRoom.core.resources.icons.Star
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.GalleryProjectComponent
import com.RAZStudio.StudioRoom.feature.gallery_workspace.thumbnail.decodeRawEmbeddedPreview
import com.RAZStudio.StudioRoom.feature.gallery_workspace.thumbnail.decodeSubsampled
import com.RAZStudio.StudioRoom.feature.gallery_workspace.thumbnail.orientUpright
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long edge of the loupe decode. Bigger than a thumbnail, far cheaper than Stage A. */
private const val LOUPE_LONG_EDGE_PX = 2048

/**
 * Full-screen photo viewer over the grid's current (filtered, sorted) paging
 * list — the culling surface the app was missing.
 *
 * Why it exists: tapping a tile used to go straight into the RAW editor, which
 * runs a full Stage A decode (tens of seconds per RAW). Reviewing a shoot that
 * way is unusable, so the loupe shows the embedded preview instead: the 640 px
 * thumbnail appears instantly and a ~2048 px decode replaces it a moment later.
 * Editing stays one deliberate tap away.
 *
 * Gestures follow the platform, not Lightroom's: swipe = next/previous photo,
 * pinch = zoom (swiping is disabled while zoomed so a pan is never stolen),
 * double-tap = toggle 2× zoom, tap = show/hide the chrome. Rating and flagging
 * are buttons rather than swipes, because vertical swipe gestures on a zoomable
 * pager misfire constantly on a phone.
 *
 * [items] is the SAME LazyPagingItems the grid renders, so the viewer inherits
 * the active filter, search and sort with no second query and no drift.
 */
@Composable
internal fun LoupeViewer(
    items: LazyPagingItems<PhotoGridRow>,
    initialIndex: Int,
    component: GalleryProjectComponent,
    onDismiss: () -> Unit,
    onEdit: (PhotoGridRow) -> Unit,
    onShowInfo: (PhotoGridRow) -> Unit,
    onShowKeywords: (PhotoGridRow) -> Unit,
    onShowVersions: (PhotoGridRow) -> Unit,
    onCompare: (Int) -> Unit,
) {
    if (items.itemCount == 0) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (items.itemCount - 1).coerceAtLeast(0)),
        pageCount = { items.itemCount },
    )
    var chromeVisible by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }

    BackHandler { onDismiss() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            // A zoomed page owns the drag: without this the first pan flings to
            // the next photo instead of moving inside the current one.
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val row = items[page]
            if (row != null) {
                LoupePage(
                    row = row,
                    onTap = { chromeVisible = !chromeVisible },
                    onZoomChanged = { z -> if (page == pagerState.currentPage) zoomed = z },
                )
            }
        }

        val current = items.peek(pagerState.currentPage)
        AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxSize()) {
                LoupeTopBar(
                    title = current?.displayName.orEmpty(),
                    subtitle = current?.let { shotSummary(it) }.orEmpty(),
                    onDismiss = onDismiss,
                    onInfo = { current?.let(onShowInfo) },
                )
                Spacer(Modifier.weight(1f))
                current?.let { row ->
                    LoupeActionBar(
                        row = row,
                        onRate = { stars -> component.setRating(row.id, stars) },
                        onFlag = { component.toggleFlag(row.id) },
                        onReject = { component.toggleReject(row.id) },
                        onKeywords = { onShowKeywords(row) },
                        onVersions = { onShowVersions(row) },
                        onCompare = { onCompare(pagerState.currentPage) },
                        onEdit = { onEdit(row) },
                    )
                }
            }
        }
    }
}

/**
 * The photo's embedded preview at [LOUPE_LONG_EDGE_PX], decoded off the main
 * thread; null until it lands (callers show the thumbnail meanwhile). Shared by
 * the loupe and the compare view.
 */
@Composable
internal fun rememberPreviewBitmap(row: PhotoGridRow): android.graphics.Bitmap? {
    val context = LocalContext.current
    val preview by produceState<android.graphics.Bitmap?>(null, row.id) {
        value = withContext(Dispatchers.IO) {
            val uri = runCatching { android.net.Uri.parse(row.sourceUri) }.getOrNull()
                ?: return@withContext null
            val isRaw = row.sourceFormat == RAW_FORMAT_ORDINAL
            val bmp = if (isRaw) {
                decodeRawEmbeddedPreview(context, uri, LOUPE_LONG_EDGE_PX)
                    ?: decodeSubsampled(context, uri, LOUPE_LONG_EDGE_PX)
            } else {
                decodeSubsampled(context, uri, LOUPE_LONG_EDGE_PX)
                    ?: decodeRawEmbeddedPreview(context, uri, LOUPE_LONG_EDGE_PX)
            }
            bmp?.let { orientUpright(context, uri, it) }
        }
    }
    // No explicit recycle: the pager keeps neighbouring pages composed and a
    // Compose Image may still draw this bitmap in the frame the page leaves —
    // recycling under it is a hard crash. Pages hold one ~2048 px bitmap each
    // (≈16 MB) and the GC frees them as pages are dropped.
    return preview
}

/** One page: thumbnail first, larger decode when it lands, pinch/double-tap zoom. */
@Composable
private fun LoupePage(
    row: PhotoGridRow,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var scale by remember(row.id) { mutableStateOf(1f) }
    var offsetX by remember(row.id) { mutableStateOf(0f) }
    var offsetY by remember(row.id) { mutableStateOf(0f) }

    val preview = rememberPreviewBitmap(row)

    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(row.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.01f) { scale = 1f; offsetX = 0f; offsetY = 0f } else scale = 2f
                    },
                )
            }
            .pointerInput(row.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    if (scale > 1.01f) {
                        // Pan budget grows with zoom; at 1× it collapses to zero
                        // so a stray drag cannot strand the photo off-centre.
                        val maxX = size.width * (scale - 1f) / 2f
                        val maxY = size.height * (scale - 1f) / 2f
                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                    } else { offsetX = 0f; offsetY = 0f }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val layer = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offsetX; translationY = offsetY
            }
        val bmp = preview
        if (bmp != null && !bmp.isRecycled) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = row.displayName,
                contentScale = ContentScale.Fit,
                modifier = layer,
            )
        } else {
            if (row.thumbPath != null) {
                AsyncImage(
                    model = File(row.thumbPath!!),
                    contentDescription = row.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = layer,
                )
            }
            CircularProgressIndicator(
                Modifier.width(28.dp).height(28.dp),
                strokeWidth = 2.dp,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun LoupeTopBar(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.ArrowBack, stringResource(R.string.exit), tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Color.White.copy(alpha = 0.75f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(onClick = onInfo) {
            Icon(Icons.Outlined.Info, stringResource(R.string.gallery_info), tint = Color.White)
        }
    }
}

@Composable
private fun LoupeActionBar(
    row: PhotoGridRow,
    onRate: (Int) -> Unit,
    onFlag: () -> Unit,
    onReject: () -> Unit,
    onKeywords: () -> Unit,
    onVersions: () -> Unit,
    onCompare: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .navigationBarsPadding()
            .padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tapping the star you already have clears the rating — the standard
            // "tap 3 on a 3-star photo = 0" behaviour every culling tool uses.
            for (star in 1..5) {
                IconButton(onClick = { onRate(if (row.rating == star) 0 else star) }) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = "$star",
                        tint = if (star <= row.rating) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
            }
        }
        // Six actions must fit a 360 dp-wide phone: icon over a one-word label,
        // never text buttons (those wrapped "Compare" and pushed Edit off screen
        // — the "I cannot edit the photo" report of 2026-09-07). Edit is filled
        // so it reads as the primary action.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoupeAction(
                icon = Icons.Rounded.CheckCircle,
                label = stringResource(R.string.gallery_action_flag),
                active = row.flagState == 1,
                onClick = onFlag,
            )
            LoupeAction(
                icon = Icons.Rounded.Block,
                label = stringResource(R.string.gallery_action_reject),
                active = row.flagState == 2,
                onClick = onReject,
            )
            LoupeAction(
                icon = Icons.Rounded.Label,
                label = stringResource(R.string.gallery_keywords),
                onClick = onKeywords,
            )
            LoupeAction(
                icon = Icons.Rounded.History,
                label = stringResource(R.string.gallery_versions),
                onClick = onVersions,
            )
            LoupeAction(
                icon = Icons.Rounded.CompareArrows,
                label = stringResource(R.string.gallery_compare),
                onClick = onCompare,
            )
            LoupeAction(
                icon = Icons.Rounded.EditAlt,
                label = stringResource(R.string.gallery_loupe_edit),
                primary = true,
                onClick = onEdit,
            )
        }
    }
}

/** PhotoFormat.Raw ordinal — kept local so the viewer needs no importer dependency. */
internal const val RAW_FORMAT_ORDINAL = 0

/** "ILCE-7M2 · 35 mm · f/1.8 · 1/250 · ISO 400" — blank parts are dropped. */
internal fun shotSummary(row: PhotoGridRow): String = listOfNotNull(
    row.cameraModel.takeIf { it.isNotBlank() },
    row.lensModel.takeIf { it.isNotBlank() },
    row.focalLengthMm.takeIf { it > 0f }?.let { "${it.toInt()} mm" },
    row.apertureF.takeIf { it > 0f }?.let { "f/" + trimNumber(it) },
    row.shutterSpeed.takeIf { it > 0f }?.let { shutterLabel(it) },
    row.iso.takeIf { it > 0 }?.let { "ISO $it" },
).joinToString(" · ")

internal fun shutterLabel(seconds: Float): String =
    if (seconds >= 1f) trimNumber(seconds) + "s" else "1/" + (1f / seconds).toInt()

internal fun trimNumber(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    else -> "$bytes B"
}

internal fun formatDate(epochMs: Long?): String =
    if (epochMs == null || epochMs <= 0L) ""
    else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMs))

/** Day label for the grid's floating date pill ("12 Aug 2026"). */
internal fun formatDay(epochMs: Long?): String =
    if (epochMs == null || epochMs <= 0L) ""
    else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMs))

internal fun keywordsOfRow(row: PhotoGridRow): List<String> = Keywords.decode(row.keywords)

/** One bottom-bar action: icon over a single-word label, ≥48 dp touch target. */
@Composable
private fun LoupeAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    primary: Boolean = false,
) {
    val tint = when {
        primary -> MaterialTheme.colorScheme.primary
        active -> Color.White
        else -> Color.White.copy(alpha = 0.75f)
    }
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .widthIn(min = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
