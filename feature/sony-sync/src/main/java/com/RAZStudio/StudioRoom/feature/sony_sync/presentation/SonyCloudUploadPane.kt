/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.ui.widget.other.QrCode
import com.RAZStudio.StudioRoom.core.ui.widget.other.QrCodeParams
import com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud.BroughtLens
import com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic.SonyCloudController

/**
 * Share-to-Cloud live-upload page. Before Start: pick RAZBatch preset + watermark
 * and confirm the shared folder. Start → after a couple of seconds the folder's
 * QR fills the screen as an anti-burn-in "screensaver" (the code doesn't move,
 * it just hops position — top/bottom in landscape, left/right in portrait — so
 * a static display doesn't burn in). Tap to leave the QR view; the page then
 * shows the running upload with a Stop button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonyCloudUploadPane(cloud: SonyCloudController, onExit: () -> Unit) {
    val live by cloud.liveActive.collectAsState()
    val uploaded by cloud.uploaded.collectAsState()
    val shareLink by cloud.shareLink.collectAsState()
    val status by cloud.status.collectAsState()
    val preset by cloud.presetName.collectAsState()
    val watermark by cloud.watermarkName.collectAsState()

    var screensaver by remember { mutableStateOf(false) }

    // A couple of seconds after Start, drop into the QR screensaver.
    LaunchedEffect(live) {
        if (live && shareLink.isNotBlank()) { delay(2500); screensaver = true } else screensaver = false
    }

    if (live && screensaver && shareLink.isNotBlank()) {
        QrScreensaver(link = shareLink, uploaded = uploaded, onTap = { screensaver = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onExit) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                title = { Text("Share to Cloud") },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (shareLink.isNotBlank()) shareLink
                        else "No shared folder yet — set one up in Cloud Settings first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // RAZBatch options — chosen before Start (applied to uploads).
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Processing (RAZBatch)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    val presets = remember { cloud.presetNames() }
                    val watermarks = remember { cloud.watermarkNames() }
                    Text("Preset", style = MaterialTheme.typography.labelMedium)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(selected = preset == null, onClick = { cloud.presetName.value = null }, label = { Text("None") })
                        presets.forEach { n ->
                            FilterChip(selected = preset == n, onClick = { cloud.presetName.value = n }, label = { Text(n) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Watermark", style = MaterialTheme.typography.labelMedium)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(selected = watermark == null, onClick = { cloud.watermarkName.value = null }, label = { Text("None") })
                        watermarks.forEach { n ->
                            FilterChip(selected = watermark == n, onClick = { cloud.watermarkName.value = n }, label = { Text(n) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Uploads are downsized to ~2 MP; the selected preset + watermark are " +
                            "applied to each photo before upload.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Lenses you brought — declare the shoot's kit; each incoming photo's
            // EXIF lens is matched to a row for correction + naming.
            LensKitCard(cloud)

            if (live) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Live upload running", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text("Uploaded: $uploaded photo(s). Each snap on the Camera Remote uploads automatically.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { screensaver = true }, enabled = shareLink.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Show QR") }
                    Button(
                        onClick = cloud::stop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                }
            } else {
                Button(
                    onClick = cloud::requestStart,   // quick folder + read/write check first
                    enabled = shareLink.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start") }
            }

            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Full-screen QR "screensaver": the code stays static but repositions on a timer
 * to avoid OLED burn-in — top/bottom in landscape, left/right in portrait. Tap
 * anywhere to leave (upload keeps running).
 */
@Composable
private fun QrScreensaver(link: String, uploaded: Int, onTap: () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onTap),
    ) {
        val landscape = maxWidth > maxHeight
        val slots = if (landscape) listOf(Alignment.TopCenter, Alignment.BottomCenter)
                    else listOf(Alignment.CenterStart, Alignment.CenterEnd)
        var slot by remember { mutableIntStateOf(0) }
        LaunchedEffect(landscape) {
            while (true) { delay(8000); slot = (slot + 1) % slots.size }
        }
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = slots[slot]) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(content = link, modifier = Modifier.size(260.dp), qrParams = QrCodeParams())
                Spacer(Modifier.height(8.dp))
                Text("Uploaded: $uploaded", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Text("Tap to exit", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * "Lenses you brought" — up to 10 rows, each: enable checkbox, a display-name
 * field (autocompleted from the watermark lens DB, used only for EXIF/watermark
 * naming) and a correction-profile field (autocompleted from the Lensfun DB,
 * drives the actual optical correction). One row may be flagged Manual — used
 * for photos that carry no lens EXIF (fully manual/adapted lens). Saved per
 * cloud folder.
 */
@Composable
private fun LensKitCard(cloud: SonyCloudController) {
    val kit by cloud.lensKit.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Lenses you brought", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tick the lenses you brought and set each one's name + correction profile. " +
                    "Each snapped photo's EXIF lens is matched to a row. Mark your manual/" +
                    "adapted lens as “Manual” — it's used for photos with no lens EXIF.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            kit.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                LensRow(
                    index = i, row = row,
                    onChange = { cloud.updateLens(i, it) },
                    nameSuggest = { q -> cloud.lensNameSuggestions(q) },
                    profileSuggest = { q -> cloud.lensProfileSuggestions(q) },
                )
            }
        }
    }
}

@Composable
private fun LensRow(
    index: Int,
    row: BroughtLens,
    onChange: (BroughtLens) -> Unit,
    nameSuggest: (String) -> List<String>,
    profileSuggest: (String) -> List<String>,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = row.enabled, onCheckedChange = { onChange(row.copy(enabled = it)) })
            Text(
                if (row.enabled) row.name.ifBlank { "Lens ${index + 1}" } else "Lens ${index + 1}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.enabled) {
            // Nested directly under the enable checkbox, indented slightly right.
            Row(
                modifier = Modifier.padding(start = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = row.manual, onCheckedChange = { onChange(row.copy(manual = it)) })
                Text("Manual / adapted lens (no EXIF)", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            AutocompleteField(
                value = row.name, label = "Display name (EXIF + watermark)",
                onValueChange = { onChange(row.copy(name = it)) }, suggest = nameSuggest,
            )
            Spacer(Modifier.height(6.dp))
            AutocompleteField(
                value = row.profile, label = "Correction profile",
                onValueChange = { onChange(row.copy(profile = it)) }, suggest = profileSuggest,
            )
        }
    }
}

/**
 * Outlined text field with a type-ahead suggestion list. Suggestions are fetched
 * off the main thread (the first call touches disk / the Lensfun JNI). Tapping a
 * suggestion commits it and dismisses the list.
 */
@Composable
private fun AutocompleteField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    suggest: (String) -> List<String>,
) {
    var query by remember(value) { mutableStateOf(value) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var showList by remember { mutableStateOf(false) }

    LaunchedEffect(query, showList) {
        if (!showList || query.length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        val q = query
        val res = withContext(Dispatchers.Default) { runCatching { suggest(q) }.getOrDefault(emptyList()) }
        // Ignore stale results if the query moved on.
        if (q == query) suggestions = res.filter { it != query }
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; showList = true; onValueChange(it) },
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showList && suggestions.isNotEmpty()) {
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            ) {
                Column {
                    suggestions.take(6).forEach { s ->
                        Text(
                            s,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    query = s; onValueChange(s); showList = false; suggestions = emptyList()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
