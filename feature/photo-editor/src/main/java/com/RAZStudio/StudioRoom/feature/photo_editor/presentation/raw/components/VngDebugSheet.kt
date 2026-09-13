/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * VngDebugSheet — on-device harness for the three VNG dual-decode self-tests.
 *
 * Invocation: long-press the "Workspace Selector" header inside
 * WorkspaceSelectorSheet (debug builds only — gated on BuildConfig.DEBUG).
 *
 * Tests 1 and 2 run immediately on sheet open (no file I/O — fast).
 * Test 3 runs only when the user taps "Run dual-decode stats" after
 * selecting a RAW file via the file picker (requires a real file path).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bottom-sheet debug harness for VNG dual-decode self-tests.
 *
 * @param onDismiss called when the sheet is closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VngDebugSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Test results state ────────────────────────────────────────────────────
    var test1Result by remember { mutableStateOf<String?>(null) }
    var test2Result by remember { mutableStateOf<String?>(null) }
    var test3Result by remember { mutableStateOf<String?>(null) }
    var test3Running by remember { mutableStateOf(false) }
    var selectedRawUri by remember { mutableStateOf<Uri?>(null) }

    // ── Run tests 1 + 2 immediately on open ──────────────────────────────────
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            test1Result = RawV3Engine.vngSelfTestLiveness()
            test2Result = RawV3Engine.vngSelfTestStrips()
        }
    }

    // ── File picker for test 3 ────────────────────────────────────────────────
    val rawPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> selectedRawUri = uri }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header
            Text(
                text = "VNG Dual-Decode Debug",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Internal self-test harness — debug build only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            // ── Test 1 — Liveness ─────────────────────────────────────────────
            DebugSectionLabel("Test 1 — Liveness (synthetic 256×256 CFA)")
            DebugResultRow(result = test1Result, running = test1Result == null)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ── Test 2 — Strip consistency ────────────────────────────────────
            DebugSectionLabel("Test 2 — Strip consistency (determinism check)")
            DebugResultRow(result = test2Result, running = test2Result == null)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ── Test 3 — Dual-decode stats on a real RAW ─────────────────────
            DebugSectionLabel("Test 3 — Dual-decode stats (real RAW file)")
            Text(
                text = "Runs Stage A with RAZ_AMAZE_VNG on a real file. " +
                       "Returns output dimensions and auto-resolved contrast threshold.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            // File picker row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { rawPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (selectedRawUri == null) "Select RAW file" else "Change file")
                }
                Button(
                    onClick = {
                        val uri = selectedRawUri ?: return@Button
                        scope.launch {
                            test3Running = true
                            test3Result = null
                            test3Result = withContext(Dispatchers.IO) {
                                val rawPath = uriToPath(context, uri)
                                    ?: return@withContext "ERROR: could not resolve URI to path"
                                val outTif = File(context.cacheDir, "vng_debug_test3.tif")
                                    .absolutePath
                                RawV3Engine.vngSelfTestDualStats(rawPath, outTif)
                            }
                            test3Running = false
                        }
                    },
                    enabled = selectedRawUri != null && !test3Running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Run dual-decode stats")
                }
            }

            // Selected file name
            selectedRawUri?.let { uri ->
                Text(
                    text = uri.lastPathSegment ?: uri.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            DebugResultRow(result = test3Result, running = test3Running)
        }
    }
}

// ── Internal composables ───────────────────────────────────────────────────────

@Composable
private fun DebugSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun DebugResultRow(result: String?, running: Boolean) {
    when {
        running -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp,
                modifier = Modifier.height(16.dp).padding(0.dp))
            Text("Running…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        result == null -> Text(
            "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            val pass = result.startsWith("PASS")
            Text(
                text = result,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (pass) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

/**
 * Resolve a content URI to a real filesystem path by copying to the app
 * cache when necessary. Returns null on failure.
 * This is needed because LibRaw's `open_file` takes a path, not an fd.
 */
private fun uriToPath(context: Context, uri: Uri): String? {
    return runCatching {
        // Fast path: file:// URI — strip the scheme.
        if (uri.scheme == "file") return uri.path

        // Content URI — copy to a temp file in cache and return that path.
        val ext = context.contentResolver
            .getType(uri)
            ?.substringAfterLast('/')
            ?.let { ".$it" } ?: ".raw"
        val tmp = File(context.cacheDir, "vng_debug_input$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        tmp.absolutePath
    }.getOrNull()
}
