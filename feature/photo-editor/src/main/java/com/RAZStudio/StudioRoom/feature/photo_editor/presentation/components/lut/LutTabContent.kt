/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowRight
import com.RAZStudio.StudioRoom.core.resources.icons.AutoFixHigh
import com.RAZStudio.StudioRoom.core.resources.icons.Close
import com.RAZStudio.StudioRoom.core.resources.icons.FolderOpen
import com.RAZStudio.StudioRoom.core.resources.icons.KeyboardArrowDown
import com.RAZStudio.StudioRoom.core.resources.icons.Star
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState
import com.RAZStudio.StudioRoom.core.domain.model.mimeTypeOf
import com.RAZStudio.StudioRoom.core.ui.utils.content_pickers.rememberFilePicker
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedButton
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.hapticsClickable
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.container
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.io.U2NetMaskAdapter
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.segmentation.OfflineFusionSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import kotlin.math.roundToInt

@Composable
fun LutTabContent(
    repository: LocalLutRepository,
    categories: List<LutCategory>,
    onCategoriesChanged: () -> Unit,
    onLutSelectionChanged: (LutSelection?) -> Unit,
    modifier: Modifier = Modifier,
    /** True when U2Net mask is available — greys out AI controls when false. */
    aiMaskAvailable: Boolean = false,
    /** Original (pre-filter) bitmap for debug histogram. Null = hide histogram. */
    debugOriginalBitmap: Bitmap? = null,
    /** Current preview bitmap (after LUT + AI adjustments) for debug histogram. */
    debugPreviewBitmap: Bitmap? = null,
    /** Subject mask for histogram segmentation. */
    debugSubjectMask: FloatArray? = null,
    /** Edge mask for histogram segmentation. */
    debugEdgeMask: FloatArray? = null,
    /** Processor used to compute histograms on the background thread. */
    debugAiProcessor: AiLutProcessor? = null,
    /** Bake the current edit into a LUT (saved to User's Lut); returns the saved
     *  name or null. Null hides the "Save Edit as LUT" button (e.g. non-RAW). */
    onSaveEditAsLut: (suspend (String) -> String?)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // "Save current edit as LUT" dialog state.
    var showSaveEditDialog by remember { mutableStateOf(false) }
    var saveEditName by remember { mutableStateOf("My Edit LUT") }
    var savingEdit by remember { mutableStateOf(false) }

    // Offline Fusion Segmenter for sharp exposure metering
    val fusionSegmenter = remember {
        OfflineFusionSegmenter(context)
    }

    DisposableEffect(fusionSegmenter) {
        onDispose {
            fusionSegmenter.close()
        }
    }

    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var strength by rememberSaveable { mutableStateOf(1f) }
    var aiLutEnabled by rememberSaveable { mutableStateOf(false) }
    var subjectPop by rememberSaveable { mutableStateOf(0f) }
    var bgBrightness by rememberSaveable { mutableStateOf(0f) }
    var shadowBoost by rememberSaveable { mutableStateOf(0f) }
    var highlightBoost by rememberSaveable { mutableStateOf(0f) }
    var edgeBlackClip by rememberSaveable { mutableStateOf(0f) }
    var subjectTemperature by rememberSaveable { mutableStateOf(0f) }
    var bgTemperature by rememberSaveable { mutableStateOf(0f) }
    var globalAdjustments by remember { mutableStateOf(GlobalPhotoAdjustments()) }
    var subjectTint by rememberSaveable { mutableStateOf(0f) }
    var bgTint by rememberSaveable { mutableStateOf(0f) }
    var highlightTemperature by rememberSaveable { mutableStateOf(0f) }
    var highlightTint by rememberSaveable { mutableStateOf(0f) }

    // ── Debug histograms ──────────────────────────────────────────────────────
    // Null = not yet computed. Each IntArray is a 256-bin luminance histogram.
    val isDebugMode = LocalSettingsState.current.isDebugMode
    var origHistograms by remember { mutableStateOf<Triple<IntArray, IntArray, IntArray>?>(null) }
    var previewHistograms by remember { mutableStateOf<Triple<IntArray, IntArray, IntArray>?>(null) }

    LaunchedEffect(debugOriginalBitmap, debugSubjectMask, debugEdgeMask, isDebugMode) {
        if (!isDebugMode || debugOriginalBitmap == null || debugAiProcessor == null) {
            origHistograms = null
            return@LaunchedEffect
        }
        origHistograms = withContext(Dispatchers.Default) {
            try {
                // Get fusion mask for sharp histogram (U2Net → SAM offline pipeline)
                val adapter = U2NetMaskAdapter(context)
                val mat = Mat()
                Utils.bitmapToMat(debugOriginalBitmap, mat)
                val preview = adapter.buildPreviewBitmap(
                    mat,
                    maxLongSide = 512
                )
                mat.release()

                val fusionMask = fusionSegmenter.detectBlemishMask(
                    preview,
                    debugOriginalBitmap.width,
                    debugOriginalBitmap.height
                )
                preview.recycle()
                adapter.close()

                Log.i("LutFusion", "Histograms computed with ${if (fusionMask != null) "fusion" else "generic"} mask")

                // Convert fusion mask to FloatArray if available
                val subjectMask = if (fusionMask != null) {
                    val float32 = FloatArray(fusionMask.cols() * fusionMask.rows())
                    fusionMask.get(0, 0, float32)
                    fusionMask.release()
                    float32
                } else {
                    debugSubjectMask
                }

                debugAiProcessor.computeHistograms(debugOriginalBitmap, subjectMask, debugEdgeMask)
            } catch (e: Exception) {
                Log.e("LutFusion", "Fusion histogram failed: ${e.message}", e)
                debugAiProcessor.computeHistograms(debugOriginalBitmap, debugSubjectMask, debugEdgeMask)
            }
        }
    }

    LaunchedEffect(debugPreviewBitmap, debugSubjectMask, debugEdgeMask, isDebugMode) {
        if (!isDebugMode || debugPreviewBitmap == null || debugAiProcessor == null) {
            previewHistograms = null
            return@LaunchedEffect
        }
        previewHistograms = withContext(Dispatchers.Default) {
            try {
                // Get fusion mask for sharp histogram
                val adapter = U2NetMaskAdapter(context)
                val mat = Mat()
                Utils.bitmapToMat(debugPreviewBitmap, mat)
                val preview = adapter.buildPreviewBitmap(
                    mat,
                    maxLongSide = 512
                )
                mat.release()

                val fusionMask = fusionSegmenter.detectBlemishMask(
                    preview,
                    debugPreviewBitmap.width,
                    debugPreviewBitmap.height
                )
                preview.recycle()
                adapter.close()

                // Convert fusion mask to FloatArray if available
                val subjectMask = if (fusionMask != null) {
                    val float32 = FloatArray(fusionMask.cols() * fusionMask.rows())
                    fusionMask.get(0, 0, float32)
                    fusionMask.release()
                    float32
                } else {
                    debugSubjectMask
                }

                debugAiProcessor.computeHistograms(debugPreviewBitmap, subjectMask, debugEdgeMask)
            } catch (e: Exception) {
                Log.e("LutFusion", "Fusion histogram failed: ${e.message}", e)
                debugAiProcessor.computeHistograms(debugPreviewBitmap, debugSubjectMask, debugEdgeMask)
            }
        }
    }

    // ── Favorites ─────────────────────────────────────────────────────────────
    // Stored as an ordered list of "CategoryName/entryName" keys. Max MAX_FAVORITES.
    var favoriteKeys by remember {
        mutableStateOf(repository.getFavoriteKeys())
    }

    fun toggleFavorite(key: String) {
        if (favoriteKeys.contains(key)) {
            repository.removeFavoriteKey(key)
            favoriteKeys = repository.getFavoriteKeys()
        } else if (favoriteKeys.size < MAX_FAVORITES) {
            repository.addFavoriteKey(key)
            favoriteKeys = repository.getFavoriteKeys()
        }
    }

    // Build the Favorite synthetic category from the current favoriteKeys + all known entries.
    // The order follows favoriteKeys (insertion order = oldest first).
    val favoriteCategory: LutCategory? = remember(favoriteKeys, categories) {
        if (favoriteKeys.isEmpty()) return@remember null
        val allEntries: Map<String, LutEntry> = buildMap {
            categories.forEach { cat ->
                cat.entries.forEach { entry ->
                    put("${cat.categoryName}/${entry.name}", entry)
                }
            }
        }
        val entries = favoriteKeys.mapNotNull { key ->
            allEntries[key]?.copy(originalKey = key)
        }
        if (entries.isEmpty()) null
        else LutCategory(categoryName = FAVORITE_CATEGORY, entries = entries)
    }

    // Final ordered list: Favorite (if any) → User's Custom (if any) → rest
    // `categories` is already returned with User's Custom first by the repository.
    val orderedCategories: List<LutCategory> = remember(favoriteCategory, categories) {
        buildList {
            favoriteCategory?.let { add(it) }
            addAll(categories)
        }
    }

    fun notifySelection() {
        val path = selectedPath ?: return onLutSelectionChanged(null)
        onLutSelectionChanged(
            LutSelection(
                path = path,
                strength = strength,
                globalAdjustments = globalAdjustments,
                aiLutEnabled = aiLutEnabled,
                subjectPop = subjectPop,
                backgroundBrightness = bgBrightness,
                shadowBoost = shadowBoost,
                highlightBoost = highlightBoost,
                edgeBlackClip = edgeBlackClip,
                subjectTemperature = subjectTemperature,
                backgroundTemperature = bgTemperature,
                subjectTint = subjectTint,
                backgroundTint = bgTint,
                highlightTemperature = highlightTemperature,
                highlightTint = highlightTint,
            )
        )
    }

    fun resetAll() {
        strength = 1f; aiLutEnabled = false; subjectPop = 0f; bgBrightness = 0f
        shadowBoost = 0f; highlightBoost = 0f; edgeBlackClip = 0f
        subjectTemperature = 0f; bgTemperature = 0f
        globalAdjustments = GlobalPhotoAdjustments(); subjectTint = 0f; bgTint = 0f
        highlightTemperature = 0f; highlightTint = 0f
    }

    val browseLauncher = rememberFilePicker(
        mimeType = mimeTypeOf(
            "application/octet-stream",
            "text/plain",
            "application/rdf+xml",
        ),
        onSuccess = { uri: Uri ->
            android.util.Log.i("LutTabContent", "FILE_PICKER_CALLBACK: uri=$uri")
            scope.launch {
                android.util.Log.i("LutTabContent", "FILE_PICKER_LAUNCH: calling addUserCustomLut")
                val entry = repository.addUserCustomLut(uri) ?: return@launch.also {
                    android.util.Log.e("LutTabContent", "FILE_PICKER_ENTRY_NULL")
                }
                onCategoriesChanged()
                val rawPath = entry.filePath ?: return@launch
                val path = if (rawPath.startsWith("file://") || rawPath.startsWith("content://"))
                    rawPath else "file://$rawPath"
                selectedKey = "${USER_CUSTOM_CATEGORY}/${entry.name}"
                selectedPath = path
                resetAll()
                notifySelection()
            }
        }
    )

    if (showSaveEditDialog && onSaveEditAsLut != null) {
        AlertDialog(
            onDismissRequest = { if (!savingEdit) showSaveEditDialog = false },
            title = { Text("Save edit as LUT") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        "Bakes your current color edit into a .cube LUT saved under \"User's Lut\". " +
                            "Captures color only — not blur, grain, masks, or other local effects.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = saveEditName,
                        onValueChange = { saveEditName = it },
                        label = { Text("LUT name") },
                        singleLine = true,
                        enabled = !savingEdit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !savingEdit && saveEditName.isNotBlank(),
                    onClick = {
                        scope.launch {
                            savingEdit = true
                            val saved = runCatching { onSaveEditAsLut(saveEditName) }.getOrNull()
                            savingEdit = false
                            showSaveEditDialog = false
                            if (saved != null) {
                                onCategoriesChanged()
                                android.widget.Toast.makeText(context, "Saved \"$saved\" to User's Lut", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                android.widget.Toast.makeText(context, "Couldn't create LUT from this edit", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ) { Text(if (savingEdit) "Saving…" else "Save") }
            },
            dismissButton = {
                TextButton(enabled = !savingEdit, onClick = { showSaveEditDialog = false }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "browse_button") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onSaveEditAsLut != null) {
                    EnhancedButton(
                        onClick = { showSaveEditDialog = true },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(imageVector = Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(text = "Save Edit as LUT", fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                EnhancedButton(
                    onClick = { browseLauncher.pickFile() },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(imageVector = Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(text = stringResource(R.string.browse), fontSize = 13.sp)
                }
            }
        }

        if (isDebugMode && (origHistograms != null || previewHistograms != null)) {
            item(key = "debug_histogram") {
                DebugHistogramPanel(
                    origHistograms = origHistograms,
                    previewHistograms = previewHistograms,
                )
            }
        }

        if (orderedCategories.isEmpty()) {
            item(key = "empty_hint") {
                Text(
                    text = "No LUTs found.\nAdd .cube files to assets/luts/ or browse a file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
                )
            }
        }

        items(items = orderedCategories, key = { it.categoryName }) { category ->
            LutCategorySection(
                category = category,
                selectedKey = selectedKey,
                strength = strength,
                aiLutEnabled = aiLutEnabled,
                subjectPop = subjectPop,
                bgBrightness = bgBrightness,
                shadowBoost = shadowBoost,
                highlightBoost = highlightBoost,
                edgeBlackClip = edgeBlackClip,
                subjectTemperature = subjectTemperature,
                bgTemperature = bgTemperature,
                globalAdjustments = globalAdjustments,
                subjectTint = subjectTint,
                bgTint = bgTint,
                highlightTemperature = highlightTemperature,
                highlightTint = highlightTint,
                aiMaskAvailable = aiMaskAvailable,
                favoriteKeys = favoriteKeys,
                onToggleFavorite = { key -> toggleFavorite(key) },
                onEntrySelected = { entry, catName ->
                    scope.launch {
                        // Use originalKey for Favorite entries so path resolution is correct.
                        val resolvedKey = entry.originalKey ?: "${catName}/${entry.name}"

                        val rawPath = when {
                            entry.filePath != null -> entry.filePath
                            entry.assetPath != null -> repository.resolveAssetToCache(entry.assetPath)
                            else -> null
                        } ?: return@launch
                        val path = if (rawPath.startsWith("file://") || rawPath.startsWith("content://"))
                            rawPath else "file://$rawPath"

                        if (selectedKey == resolvedKey) {
                            selectedKey = null; selectedPath = null; onLutSelectionChanged(null)
                        } else {
                            selectedKey = resolvedKey; selectedPath = path
                            resetAll()
                            notifySelection()
                        }
                    }
                },
                onStrengthChange = { v -> strength = v; notifySelection() },
                onAiLutToggle = { v -> aiLutEnabled = v; notifySelection() },
                onSubjectPopChange = { v -> subjectPop = v; notifySelection() },
                onBgBrightnessChange = { v -> bgBrightness = v; notifySelection() },
                onShadowBoostChange = { v -> shadowBoost = v; notifySelection() },
                onHighlightBoostChange = { v -> highlightBoost = v; notifySelection() },
                onEdgeBlackClipChange = { v -> edgeBlackClip = v; notifySelection() },
                onSubjectTempChange = { v -> subjectTemperature = v; notifySelection() },
                onBgTempChange = { v -> bgTemperature = v; notifySelection() },
                onGlobalAdjustmentsChange = { v -> globalAdjustments = v; notifySelection() },
                onSubjectTintChange = { v -> subjectTint = v; notifySelection() },
                onBgTintChange = { v -> bgTint = v; notifySelection() },
                onHighlightTempChange = { v -> highlightTemperature = v; notifySelection() },
                onHighlightTintChange = { v -> highlightTint = v; notifySelection() },
                // No delete button in Favorite category; User's Custom gets delete.
                onDeleteCustom = if (category.categoryName == USER_CUSTOM_CATEGORY) {
                    { entry ->
                        entry.filePath?.let { fp ->
                            val key = "${USER_CUSTOM_CATEGORY}/${entry.name}"
                            if (selectedPath == fp) { selectedKey = null; selectedPath = null; onLutSelectionChanged(null) }
                            // Also remove from favorites if it was favorited
                            if (favoriteKeys.contains(key)) {
                                repository.removeFavoriteKey(key)
                                favoriteKeys = repository.getFavoriteKeys()
                            }
                            repository.removeUserCustomLut(fp)
                            onCategoriesChanged()
                        }
                    }
                } else null,
            )
        }
    }
}

// ── Category section ──────────────────────────────────────────────────────────

@Composable
private fun LutCategorySection(
    category: LutCategory,
    selectedKey: String?,
    strength: Float,
    aiLutEnabled: Boolean,
    subjectPop: Float,
    bgBrightness: Float,
    shadowBoost: Float,
    highlightBoost: Float,
    edgeBlackClip: Float,
    subjectTemperature: Float,
    bgTemperature: Float,
    globalAdjustments: GlobalPhotoAdjustments,
    subjectTint: Float,
    bgTint: Float,
    highlightTemperature: Float,
    highlightTint: Float,
    aiMaskAvailable: Boolean,
    favoriteKeys: List<String>,
    onToggleFavorite: (String) -> Unit,
    onEntrySelected: (LutEntry, catName: String) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onAiLutToggle: (Boolean) -> Unit,
    onSubjectPopChange: (Float) -> Unit,
    onBgBrightnessChange: (Float) -> Unit,
    onShadowBoostChange: (Float) -> Unit,
    onHighlightBoostChange: (Float) -> Unit,
    onEdgeBlackClipChange: (Float) -> Unit,
    onSubjectTempChange: (Float) -> Unit,
    onBgTempChange: (Float) -> Unit,
    onGlobalAdjustmentsChange: (GlobalPhotoAdjustments) -> Unit,
    onSubjectTintChange: (Float) -> Unit,
    onBgTintChange: (Float) -> Unit,
    onHighlightTempChange: (Float) -> Unit,
    onHighlightTintChange: (Float) -> Unit,
    onDeleteCustom: ((LutEntry) -> Unit)?,
) {
    var expanded by rememberSaveable(category.categoryName) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().container(shape = MaterialTheme.shapes.medium, resultPadding = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().hapticsClickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = category.categoryName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                color = when (category.categoryName) {
                    FAVORITE_CATEGORY -> Color(0xFFFFC107)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(text = "${category.entries.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                category.entries.forEachIndexed { index, entry ->
                    // For Favorite entries, the key stored in selectedKey is the originalKey.
                    val key = entry.originalKey ?: "${category.categoryName}/${entry.name}"
                    val isSelected = selectedKey == key
                    val isFavorited = favoriteKeys.contains(key)
                    LutEntryRow(
                        entry = entry,
                        isSelected = isSelected,
                        isFavorited = isFavorited,
                        favoritesAtMax = favoriteKeys.size >= MAX_FAVORITES,
                        strength = if (isSelected) strength else 1f,
                        aiLutEnabled = if (isSelected) aiLutEnabled else false,
                        subjectPop = if (isSelected) subjectPop else 0f,
                        bgBrightness = if (isSelected) bgBrightness else 0f,
                        shadowBoost = if (isSelected) shadowBoost else 0f,
                        highlightBoost = if (isSelected) highlightBoost else 0f,
                        edgeBlackClip = if (isSelected) edgeBlackClip else 0f,
                        subjectTemperature = if (isSelected) subjectTemperature else 0f,
                        bgTemperature = if (isSelected) bgTemperature else 0f,
                        globalAdjustments = if (isSelected) globalAdjustments else GlobalPhotoAdjustments(),
                        subjectTint = if (isSelected) subjectTint else 0f,
                        backgroundTint = if (isSelected) bgTint else 0f,
                        highlightTemperature = if (isSelected) highlightTemperature else 0f,
                        highlightTint = if (isSelected) highlightTint else 0f,
                        aiMaskAvailable = aiMaskAvailable,
                        onClick = { onEntrySelected(entry, category.categoryName) },
                        onToggleFavorite = { onToggleFavorite(key) },
                        onStrengthChange = onStrengthChange,
                        onAiLutToggle = onAiLutToggle,
                        onSubjectPopChange = onSubjectPopChange,
                        onBgBrightnessChange = onBgBrightnessChange,
                        onShadowBoostChange = onShadowBoostChange,
                        onHighlightBoostChange = onHighlightBoostChange,
                        onEdgeBlackClipChange = onEdgeBlackClipChange,
                        onSubjectTempChange = onSubjectTempChange,
                        onBgTempChange = onBgTempChange,
                        onGlobalAdjustmentsChange = onGlobalAdjustmentsChange,
                        onSubjectTintChange = onSubjectTintChange,
                        onBgTintChange = onBgTintChange,
                        onHighlightTempChange = onHighlightTempChange,
                        onHighlightTintChange = onHighlightTintChange,
                        onDelete = onDeleteCustom?.let { del -> { del(entry) } },
                    )
                    if (index < category.entries.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

// ── Preset row ────────────────────────────────────────────────────────────────

@Composable
private fun LutEntryRow(
    entry: LutEntry,
    isSelected: Boolean,
    isFavorited: Boolean,
    favoritesAtMax: Boolean,
    strength: Float,
    aiLutEnabled: Boolean,
    subjectPop: Float,
    bgBrightness: Float,
    shadowBoost: Float,
    highlightBoost: Float,
    edgeBlackClip: Float,
    subjectTemperature: Float,
    bgTemperature: Float,
    globalAdjustments: GlobalPhotoAdjustments,
    subjectTint: Float,
    backgroundTint: Float,
    highlightTemperature: Float,
    highlightTint: Float,
    aiMaskAvailable: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onStrengthChange: (Float) -> Unit,
    onAiLutToggle: (Boolean) -> Unit,
    onSubjectPopChange: (Float) -> Unit,
    onBgBrightnessChange: (Float) -> Unit,
    onShadowBoostChange: (Float) -> Unit,
    onHighlightBoostChange: (Float) -> Unit,
    onEdgeBlackClipChange: (Float) -> Unit,
    onSubjectTempChange: (Float) -> Unit,
    onBgTempChange: (Float) -> Unit,
    onGlobalAdjustmentsChange: (GlobalPhotoAdjustments) -> Unit,
    onSubjectTintChange: (Float) -> Unit,
    onBgTintChange: (Float) -> Unit,
    onHighlightTempChange: (Float) -> Unit,
    onHighlightTintChange: (Float) -> Unit,
    onDelete: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().hapticsClickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Delete button — only for User's Custom entries (not shown in Favorite category)
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Remove",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // Star button — always shown; yellow when favorited
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(32.dp),
                // Dim (but still show) when favorites are full and this entry is not yet favorited
                enabled = isFavorited || !favoritesAtMax,
            ) {
                Icon(
                    imageVector = if (isFavorited) Icons.Rounded.Star else Icons.Outlined.Star,
                    contentDescription = if (isFavorited) "Remove from favorites" else "Add to favorites",
                    modifier = Modifier.size(18.dp),
                    tint = if (isFavorited) Color(0xFFFFC107)
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                               alpha = if (favoritesAtMax) 0.38f else 1f
                           ),
                )
            }
        }

        AnimatedVisibility(visible = isSelected, enter = expandVertically(), exit = shrinkVertically()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 1. Strength
                LabeledSlider(
                    label = "Strength",
                    value = strength,
                    valueRange = 0f..1f,
                    onValueChange = onStrengthChange,
                    enabled = true,
                    displayValue = "${(strength * 100).roundToInt()}%",
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Global Adjustments section (collapsed by default)
                GlobalAdjSection(
                    adjustments = globalAdjustments,
                    onAdjustmentsChange = onGlobalAdjustmentsChange,
                    edgeBlackClip = edgeBlackClip,
                    onEdgeBlackClipChange = onEdgeBlackClipChange,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // 5. LUT Control checkbox row
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LUT Control",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (aiMaskAvailable) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                        Text(
                            text = if (!aiMaskAvailable) "Waiting for AI mask…"
                                   else "Subject · background · tone adjustments",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(
                        checked = aiLutEnabled,
                        onCheckedChange = { v -> if (aiMaskAvailable) onAiLutToggle(v) },
                        enabled = aiMaskAvailable,
                    )
                }

                // 6. LUT Control expanded panel
                AnimatedVisibility(visible = aiLutEnabled, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledSlider(
                            label = "Subject POP",
                            value = subjectPop,
                            valueRange = -1f..1f,
                            onValueChange = onSubjectPopChange,
                            enabled = aiMaskAvailable,
                            displayValue = formatBipolar(subjectPop),
                        )
                        TemperatureRow(
                            label = "Subject Temp",
                            value = subjectTemperature,
                            onValueChange = onSubjectTempChange,
                            enabled = aiMaskAvailable,
                        )
                        TintRow(
                            label = "Subject Tint",
                            value = subjectTint,
                            onValueChange = onSubjectTintChange,
                            enabled = aiMaskAvailable,
                        )
                        LabeledSlider(
                            label = "Background Brightness",
                            value = bgBrightness,
                            valueRange = -1f..1f,
                            onValueChange = onBgBrightnessChange,
                            enabled = aiMaskAvailable,
                            displayValue = formatBipolar(bgBrightness),
                        )
                        TemperatureRow(
                            label = "Background Temp",
                            value = bgTemperature,
                            onValueChange = onBgTempChange,
                            enabled = aiMaskAvailable,
                        )
                        TintRow(
                            label = "Background Tint",
                            value = backgroundTint,
                            onValueChange = onBgTintChange,
                            enabled = aiMaskAvailable,
                        )
                        LabeledSlider(
                            label = "Shadow Boost",
                            value = shadowBoost,
                            valueRange = -1f..1f,
                            onValueChange = onShadowBoostChange,
                            enabled = aiMaskAvailable,
                            displayValue = formatBipolar(shadowBoost),
                        )
                        LabeledSlider(
                            label = "Highlight Boost",
                            value = highlightBoost,
                            valueRange = -1f..1f,
                            onValueChange = onHighlightBoostChange,
                            enabled = aiMaskAvailable,
                            displayValue = formatBipolar(highlightBoost),
                        )
                        TemperatureRow(
                            label = "Highlight Temp",
                            value = highlightTemperature,
                            onValueChange = onHighlightTempChange,
                            enabled = aiMaskAvailable,
                        )
                        TintRow(
                            label = "Highlight Tint",
                            value = highlightTint,
                            onValueChange = onHighlightTintChange,
                            enabled = aiMaskAvailable,
                        )

                    }
                }

                Spacer(Modifier.height(0.dp))
            }
        }
    }
}

// ── Global Adjustments section ────────────────────────────────────────────────

@Composable
private fun GlobalAdjSection(
    adjustments: GlobalPhotoAdjustments,
    onAdjustmentsChange: (GlobalPhotoAdjustments) -> Unit,
    edgeBlackClip: Float,
    onEdgeBlackClipChange: (Float) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var hslExpanded by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().hapticsClickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Global Adjustments",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // Exposure: -2..+2 EV
        val exposureDisplay = run {
            val ev = (adjustments.exposure * 10).roundToInt() / 10f
            when {
                ev > 0f -> "+$ev EV"
                ev < 0f -> "$ev EV"
                else -> "0 EV"
            }
        }
        LabeledSlider(
            label = "Exposure",
            value = adjustments.exposure,
            valueRange = -2f..2f,
            onValueChange = { onAdjustmentsChange(adjustments.copy(exposure = it)) },
            enabled = true,
            displayValue = exposureDisplay,
        )

        LabeledSlider(
            label = "Contrast",
            value = adjustments.contrast,
            valueRange = -1f..1f,
            onValueChange = { onAdjustmentsChange(adjustments.copy(contrast = it)) },
            enabled = true,
            displayValue = formatBipolar(adjustments.contrast),
        )

        LabeledSlider(
            label = "Saturation",
            value = adjustments.saturation,
            valueRange = -1f..1f,
            onValueChange = { onAdjustmentsChange(adjustments.copy(saturation = it)) },
            enabled = true,
            displayValue = formatBipolar(adjustments.saturation),
        )

        LabeledSlider(
            label = "Vibrance",
            value = adjustments.vibrance,
            valueRange = -1f..1f,
            onValueChange = { onAdjustmentsChange(adjustments.copy(vibrance = it)) },
            enabled = true,
            displayValue = formatBipolar(adjustments.vibrance),
        )

        LabeledSlider(
            label = "Clarity",
            value = edgeBlackClip,
            valueRange = 0f..0.15f,
            onValueChange = onEdgeBlackClipChange,
            enabled = true,
            displayValue = "${"%.1f".format(edgeBlackClip * 100)}",
        )

        // HSL group — collapsed by default
        Row(
            modifier = Modifier.fillMaxWidth().hapticsClickable { hslExpanded = !hslExpanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "HSL — Color Mixer",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (hslExpanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }

        AnimatedVisibility(visible = hslExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HslHueSection(hueName = "Red", adj = adjustments.hslRed) { onAdjustmentsChange(adjustments.copy(hslRed = it)) }
                HslHueSection(hueName = "Orange", adj = adjustments.hslOrange) { onAdjustmentsChange(adjustments.copy(hslOrange = it)) }
                HslHueSection(hueName = "Yellow", adj = adjustments.hslYellow) { onAdjustmentsChange(adjustments.copy(hslYellow = it)) }
                HslHueSection(hueName = "Green", adj = adjustments.hslGreen) { onAdjustmentsChange(adjustments.copy(hslGreen = it)) }
                HslHueSection(hueName = "Cyan", adj = adjustments.hslCyan) { onAdjustmentsChange(adjustments.copy(hslCyan = it)) }
                HslHueSection(hueName = "Blue", adj = adjustments.hslBlue) { onAdjustmentsChange(adjustments.copy(hslBlue = it)) }
                HslHueSection(hueName = "Purple", adj = adjustments.hslPurple) { onAdjustmentsChange(adjustments.copy(hslPurple = it)) }
                HslHueSection(hueName = "Magenta", adj = adjustments.hslMagenta) { onAdjustmentsChange(adjustments.copy(hslMagenta = it)) }
            }
        }

        } // end inner Column
        } // end AnimatedVisibility
    }
}

@Composable
private fun HslHueSection(
    hueName: String,
    adj: HslAdjustment,
    onAdjChange: (HslAdjustment) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = hueName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        LabeledSlider(
            label = "Hue Shift",
            value = adj.hueShift,
            valueRange = -1f..1f,
            onValueChange = { onAdjChange(adj.copy(hueShift = it)) },
            enabled = true,
            displayValue = formatHueDegrees(adj.hueShift),
        )
        LabeledSlider(
            label = "Saturation",
            value = adj.saturation,
            valueRange = -1f..1f,
            onValueChange = { onAdjChange(adj.copy(saturation = it)) },
            enabled = true,
            displayValue = formatBipolar(adj.saturation),
        )
        LabeledSlider(
            label = "Luminance",
            value = adj.luminance,
            valueRange = -1f..1f,
            onValueChange = { onAdjChange(adj.copy(luminance = it)) },
            enabled = true,
            displayValue = formatBipolar(adj.luminance),
        )
    }
}

private fun formatHueDegrees(v: Float): String {
    val deg = (v * 180).roundToInt()
    return when {
        deg > 0 -> "+${deg}°"
        deg < 0 -> "${deg}°"
        else -> "0°"
    }
}

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
    displayValue: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            Text(text = displayValue, style = MaterialTheme.typography.labelSmall, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}

private fun formatBipolar(v: Float): String {
    val pct = (v * 100).roundToInt()
    return when { pct > 0 -> "+$pct"; pct < 0 -> "$pct"; else -> "0" }
}

/** Temperature −/0/+ row with compact minus icon, slider, and plus icon. */
@Composable
private fun TemperatureRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val valueColor = if (enabled) {
        when {
            value > 0.05f -> Color(0xFFFF8C00)  // warm amber
            value < -0.05f -> Color(0xFF4FC3F7)  // cool blue
            else -> MaterialTheme.colorScheme.primary
        }
    } else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = color)
            Text(
                text = when {
                    value > 0.05f -> "Warm ${formatBipolar(value)}"
                    value < -0.05f -> "Cool ${formatBipolar(value)}"
                    else -> "Neutral"
                },
                style = MaterialTheme.typography.labelSmall,
                color = valueColor,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = -1f..1f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Tint row on the green–magenta axis. Positive = magenta, negative = green. */
@Composable
private fun TintRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val tintAbs = (value * 100).roundToInt()
    val valueColor = if (enabled) {
        when {
            value > 0.05f -> Color(0xFFE040FB)  // magenta
            value < -0.05f -> Color(0xFF66BB6A)  // green
            else -> MaterialTheme.colorScheme.primary
        }
    } else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = color)
            Text(
                text = when {
                    value > 0.05f -> "Magenta +$tintAbs"
                    value < -0.05f -> "Green $tintAbs"
                    else -> "Neutral"
                },
                style = MaterialTheme.typography.labelSmall,
                color = valueColor,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = -1f..1f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Two-column debug histogram panel: Original | Preview, 3 rows per segment. */
@Composable
private fun DebugHistogramPanel(
    origHistograms: Triple<IntArray, IntArray, IntArray>?,
    previewHistograms: Triple<IntArray, IntArray, IntArray>?,
) {
    val segments = listOf("Subject", "Background", "Edge")
    Column(
        modifier = Modifier.fillMaxWidth()
            .container(shape = MaterialTheme.shapes.medium, resultPadding = 0.dp)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Debug Histograms", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("L 0→255", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                Text("Original", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            }
            Box(modifier = Modifier.weight(1f)) {
                Text("Preview", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            }
        }

        segments.forEachIndexed { idx, name ->
            val origHist = when (idx) { 0 -> origHistograms?.first; 1 -> origHistograms?.second; else -> origHistograms?.third }
            val prevHist = when (idx) { 0 -> previewHistograms?.first; 1 -> previewHistograms?.second; else -> previewHistograms?.third }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                    MiniHistogram(hist = origHist, color = Color(0xFF64B5F6))
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                    MiniHistogram(hist = prevHist, color = Color(0xFFA5D6A7))
                }
            }
        }
    }
}

/** 256-bar luminance histogram drawn on Canvas. Height = 36dp, bars are 1px wide at max width. */
@Composable
private fun MiniHistogram(hist: IntArray?, color: Color) {
    val barColor = color
    val bgColor = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
        drawRect(bgColor)
        if (hist == null) return@Canvas
        val maxVal = hist.maxOrNull()?.takeIf { it > 0 }?.toFloat() ?: return@Canvas
        val barW = size.width / 256f
        for (bin in 0..255) {
            val normalized = hist[bin] / maxVal
            val barH = normalized * size.height
            drawLine(
                color = barColor,
                start = Offset(bin * barW + barW / 2f, size.height),
                end = Offset(bin * barW + barW / 2f, size.height - barH),
                strokeWidth = barW.coerceAtLeast(1f),
            )
        }
    }
}
