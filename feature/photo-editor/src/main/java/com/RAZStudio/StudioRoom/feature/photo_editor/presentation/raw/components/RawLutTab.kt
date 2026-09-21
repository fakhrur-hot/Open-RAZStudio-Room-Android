/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowDropDown
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowDropUp
import com.RAZStudio.StudioRoom.core.resources.icons.Close
import com.RAZStudio.StudioRoom.core.resources.icons.Delete
import com.RAZStudio.StudioRoom.core.resources.icons.Search
import com.RAZStudio.StudioRoom.core.resources.icons.Star
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedAlertDialog
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedButton
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.LutEntry
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.RazClassicLook
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.RAZ_LOOKS_CATEGORY
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.USER_CUSTOM_CATEGORY
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.LutCategory
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.LutPickDefaults
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.lutPickDefaultsFor
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.rememberLocalLutRepository
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import kotlinx.coroutines.launch

/** Cap the inline search dropdown so a single-letter query doesn't render
 *  thousands of menu items at once. 50 is enough headroom that typical
 *  multi-letter queries always fit; tuned for keep-typing responsiveness. */
private const val MAX_SEARCH_RESULTS = 50

/** When true, show the "LUT sync (approximable)" panel under the intensity
 *  slider. Kept off so the fitter in LutAdjustmentApprox.kt can stay without
 *  exposing the adjustment UI in the LUT tab. */
private const val SHOW_LUT_APPROX_SYNC = false

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun RawLutTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    modifier: Modifier = Modifier,
    subjectMaskReady: Boolean = false,
    // The LUT picker + presets browser (shown in the LUT 1 / LUT 2 tabs). The
    // caller shims macro.lutCubeUri/lutIntensity to the slot it owns and remaps
    // writes back to that slot, so this composable stays slot-agnostic.
    showPicker: Boolean = true,
    // The finishing trims (CLAHE / zone WB / skintone / bloom / dehaze), shown
    // in the Curves tab. Independent of the LUT picker.
    showFinishing: Boolean = true,
    // Bake the current edit into a LUT (saved to User's Lut); returns the saved
    // name or null. Null hides the "Save Edit as LUT" button.
    onSaveEditAsLut: (suspend (String) -> String?)? = null,
) {
    val scope = rememberCoroutineScope()
    val repo = rememberLocalLutRepository()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.VintageFxAssets.ensureLoaded(context)
    }
    var categories by remember { mutableStateOf<List<LutCategory>>(emptyList()) }
    LaunchedEffect(repo) { categories = repo.getCategories() }
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }
    var isResolving by remember { mutableStateOf(false) }
    var favoriteKeys by remember { mutableStateOf(repo.getFavoriteKeys()) }
    var pendingRemoveFavoriteKey by remember { mutableStateOf<String?>(null) }
    var pendingDeleteCustomEntry by remember { mutableStateOf<LutEntry?>(null) }
    // A .cube picked from Files but not yet saved into the User's Lut category.
    // While set, the active-LUT row shows a green Save button. Cleared once the
    // user saves it (it then lives in the persistent category) or picks another.
    var pendingPickedPath by remember { mutableStateOf<String?>(null) }
    var pendingPickedName by remember { mutableStateOf("") }

    // ── Inline search state ───────────────────────────────────────────────────
    // Toggle the magnifying-glass icon to swap the active-LUT label with a
    // textfield. Typing filters every LUT across categories by name and shows
    // a dropdown of matches; tapping a match selects it via the same
    // `selectEntry` path the chips use.
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // ── HaldCLUT export naming dialog ─────────────────────────────────────────
    // The user names the identity Hald before every export; the repo appends
    // _1, _2… if the name already exists in the destination folder.
    var showHaldNameDialog by remember { mutableStateOf(false) }
    var haldName by remember { mutableStateOf("identity_hald") }

    // ── "Save current edit as LUT" naming dialog ──────────────────────────────
    var showSaveEditDialog by remember { mutableStateOf(false) }
    var saveEditName by remember { mutableStateOf("My Edit LUT") }
    var savingEdit by remember { mutableStateOf(false) }

    /**
     * Apply a LUT pick. Headroom-aware pre-LUT mapping in the native path
     * protects highlights by default (identity on [0,1]) — do NOT force-zero
     * [UserMacro.lutHighlightVibrancy] here (that used to disable the
     * Reinhard/vibrancy creative knob). Category-aware intensity / modest
     * filmRolloff are applied by [selectEntry].
     */
    val applyLutChange: (UserMacro) -> UserMacro = { updated ->
        updated.copy(claheEnabled = true)
    }

    fun defaultsForPick(categoryName: String?, path: String): LutPickDefaults =
        lutPickDefaultsFor(categoryName, path)

    fun withPickDefaults(
        base: UserMacro,
        path: String,
        categoryName: String?,
        optical: com.RAZStudio.StudioRoom.feature.photo_editor.presentation
            .lut_creator.LutOpticalSidecar.Params? = null,
    ): UserMacro {
        val d = defaultsForPick(categoryName, path)
        val rolloff = if (base.filmRolloff == 0f && d.autoFilmRolloff > 0f) d.autoFilmRolloff
                      else base.filmRolloff
        val withLut = if (optical != null && !optical.isEmpty) {
            base.copy(
                lutCubeUri = path,
                lutIntensity = d.intensity,
                filmRolloff = rolloff,
                filmGrain = optical.filmGrain,
                filmGrainSize = optical.filmGrainSize,
                filmGrainWashOut = optical.filmGrainWashOut,
                fxGlowStrength = optical.fxGlowStrength,
                fxGlowSpread = optical.fxGlowSpread,
                fxGlowWarmth = optical.fxGlowWarmth,
            )
        } else {
            base.copy(
                lutCubeUri = path,
                lutIntensity = d.intensity,
                filmRolloff = rolloff,
            )
        }
        return applyLutChange(withLut)
    }

    // .cube file picker — stages the file for live preview WITHOUT saving. The
    // user previews the look, then taps the green Save button to persist it
    // into the User's Lut category (survives app exit).
    val lutPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isResolving = true
                val staged = repo.stageCubeForPreview(uri)
                if (staged != null) {
                    val (path, name) = staged
                    pendingPickedPath = path
                    pendingPickedName = name
                    onMacroChange(applyLutChange(macro.copy(lutCubeUri = path)))
                } else {
                    // Fallback: load directly from URI without staging/saving
                    pendingPickedPath = null
                    pendingPickedName = ""
                    onMacroChange(applyLutChange(macro.copy(lutCubeUri = uri.toString())))
                }
                isResolving = false
            }
        }
    }

    fun savePendingPicked() {
        val path = pendingPickedPath ?: return
        scope.launch {
            isResolving = true
            val entry = repo.saveAsUserLut(path, pendingPickedName)
            if (entry != null) {
                categories = repo.getCategories()
                // Re-point the active LUT at the now-persistent file.
                onMacroChange(applyLutChange(macro.copy(lutCubeUri = entry.filePath!!)))
                pendingPickedPath = null
                pendingPickedName = ""
            }
            isResolving = false
        }
    }

    fun entryKey(categoryName: String, entry: LutEntry) = "$categoryName/${entry.name}"

    fun isEntrySelected(entry: LutEntry): Boolean {
        if (RazClassicLook.isSentinel(entry)) return RazClassicLook.isApplied(macro)
        if (macro.lutCubeUri.isEmpty()) return false
        val assetFile = entry.assetPath?.substringAfterLast('/')
        val customFile = entry.filePath?.takeIf { it.isNotEmpty() }?.substringAfterLast('/')
        return (assetFile != null && macro.lutCubeUri.endsWith(assetFile)) ||
               (customFile != null && macro.lutCubeUri.endsWith(customFile))
    }

    val activeCategoryHint: String? = remember(macro.lutCubeUri, categories) {
        if (macro.lutCubeUri.isEmpty()) return@remember null
        fun selected(entry: LutEntry): Boolean {
            val assetFile = entry.assetPath?.substringAfterLast('/')
            val customFile = entry.filePath?.takeIf { it.isNotEmpty() }?.substringAfterLast('/')
            return (assetFile != null && macro.lutCubeUri.endsWith(assetFile)) ||
                (customFile != null && macro.lutCubeUri.endsWith(customFile))
        }
        val cat = categories.firstOrNull { c -> c.entries.any { selected(it) } }?.categoryName
        lutPickDefaultsFor(cat, macro.lutCubeUri).categoryHint
    }

    fun selectEntry(entry: LutEntry, categoryName: String? = null) {
        // Selecting any catalogued preset clears a pending (unsaved) pick.
        pendingPickedPath = null
        pendingPickedName = ""
        if (RazClassicLook.isSentinel(entry)) {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.VintageFxAssets.ensureLoaded(context)
            onMacroChange(
                RazClassicLook.apply(
                    macro,
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.VintageFxAssets.hasMist,
                )
            )
            return
        }
        scope.launch {
            isResolving = true
            val path = when {
                entry.assetPath != null -> repo.resolveAssetToCache(entry.assetPath)
                entry.filePath  != null -> entry.filePath
                else -> null
            }
            if (path != null) {
                val cat = categoryName
                    ?: entry.originalKey?.substringBefore('/')
                    ?: categories.firstOrNull { c -> c.entries.any { it.name == entry.name } }?.categoryName
                val optical = com.RAZStudio.StudioRoom.feature.photo_editor.presentation
                    .lut_creator.LutOpticalSidecar.readBesideCube(path)
                onMacroChange(withPickDefaults(macro, path, cat, optical))
            }
            isResolving = false
        }
    }

    fun toggleFavorite(key: String) {
        if (key in favoriteKeys) {
            repo.removeFavoriteKey(key)
            favoriteKeys = favoriteKeys - key
        } else if (repo.addFavoriteKey(key)) {
            favoriteKeys = favoriteKeys + key
        }
    }

    fun deleteCustomEntry(entry: LutEntry) {
        // If currently selected, clear it
        if (isEntrySelected(entry)) {
            onMacroChange(applyLutChange(macro.copy(lutCubeUri = "", lutIntensity = 1f)))
        }
        // Remove from favorites if present
        val key = entryKey(USER_CUSTOM_CATEGORY, entry)
        if (key in favoriteKeys) {
            repo.removeFavoriteKey(key)
            favoriteKeys = favoriteKeys - key
        }
        repo.removeUserCustomLut(entry.filePath ?: return)
        scope.launch { categories = repo.getCategories() }
    }

    // Ordered list of (key, entry) pairs for the favorites section
    val favoriteEntries: List<Pair<String, LutEntry>> = remember(favoriteKeys, categories) {
        favoriteKeys.mapNotNull { key ->
            val catName = key.substringBefore('/')
            val entName = key.substringAfter('/')
            categories.firstOrNull { it.categoryName == catName }
                ?.entries?.firstOrNull { it.name == entName }
                ?.let { key to it }
        }
    }

    val anyFavoriteSelected = favoriteEntries.any { (_, e) -> isEntrySelected(e) }

    val isCustomLut = macro.lutCubeUri.isNotEmpty() &&
        categories.isNotEmpty() &&
        !anyFavoriteSelected &&
        categories.none { cat -> cat.entries.any { isEntrySelected(it) } }

    // Flatten every (categoryName, entry) pair and filter by the search
    // query — case-insensitive substring match on the entry name. Empty
    // query yields an empty dropdown (so the user sees suggestions only
    // once they start typing, not the entire library on focus).
    val searchResults: List<Pair<String, LutEntry>> = remember(searchQuery, categories) {
        val q = searchQuery.trim()
        if (q.isEmpty()) emptyList()
        else categories
            .flatMap { cat -> cat.entries.map { cat.categoryName to it } }
            .filter { (_, e) -> e.name.contains(q, ignoreCase = true) }
            .take(MAX_SEARCH_RESULTS)
    }

    Column(modifier = modifier) {

      if (showPicker) {
        // ── Active LUT row ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (searchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.raw_lut_search_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.raw_lut_search_clear),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .wrapContentHeight(),
                    )
                    DropdownMenu(
                        expanded = searchResults.isNotEmpty(),
                        onDismissRequest = { /* keep open while typing */ },
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                        ),
                    ) {
                        searchResults.forEach { (catName, entry) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = catName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                onClick = {
                                    selectEntry(entry, catName)
                                    searchQuery = ""
                                    searchActive = false
                                },
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (macro.lutCubeUri.isEmpty())
                            stringResource(R.string.raw_lut_none)
                        else {
                            // Hide format extension (.smcube / .cube / …) in the label field
                            val raw = macro.lutCubeUri
                                .substringAfterLast('/')
                                .substringAfterLast('%')
                            val lower = raw.lowercase()
                            val noExt = when {
                                lower.endsWith(".smcube") -> raw.dropLast(7)
                                lower.endsWith(".cube") -> raw.dropLast(5)
                                lower.endsWith(".3dl") -> raw.dropLast(4)
                                lower.endsWith(".xmp") -> raw.dropLast(4)
                                lower.endsWith(".lrtemplate") -> raw.dropLast(11)
                                lower.endsWith(".dng") -> raw.dropLast(4)
                                else -> raw
                            }
                            noExt.take(28)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isResolving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (pendingPickedPath != null) {
                Button(
                    onClick = { savePendingPicked() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(stringResource(R.string.raw_lut_save))
                }
            }
            IconButton(
                onClick = {
                    searchActive = !searchActive
                    if (!searchActive) searchQuery = ""
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (searchActive) Icons.Rounded.Close else Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.raw_lut_search_toggle),
                    modifier = Modifier.size(20.dp),
                )
            }
            Button(onClick = {
                lutPicker.launch(arrayOf(
                    "application/octet-stream", // .cube, .3dl (often typeless)
                    "application/x-cube",
                    "text/plain",
                    "application/xml",
                    "text/xml",
                    "application/rdf+xml",
                    "image/png",   // HaldCLUT bridge
                    "image/jpeg",
                    "image/tiff",  // 16-bit HaldCLUT
                ))
            }) {
                Text(stringResource(R.string.raw_lut_pick))
            }
        }
        // "Save current edit as LUT" and "Export identity HaldCLUT" removed per
        // owner request (2026-08-29) — the naming dialogs below are now unused but
        // kept dormant (no trigger) to avoid churn; safe to delete later.

        if (macro.lutCubeUri.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    onMacroChange(applyLutChange(macro.copy(lutCubeUri = "", lutIntensity = 1f)))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.raw_lut_remove))
            }
        }

        // ── Favorites section ──────────────────────────────────────────────────
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.raw_lut_favorites),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
        if (favoriteEntries.isEmpty()) {
            Text(
                text = stringResource(R.string.raw_lut_no_favorites_hint),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                favoriteEntries.forEach { (key, entry) ->
                    LutPresetChip(
                        name = entry.name,
                        selected = isEntrySelected(entry),
                        isFavorite = true,
                        onClick = { selectEntry(entry, key.substringBefore('/')) },
                        onLongClick = { pendingRemoveFavoriteKey = key },
                    )
                }
            }
            if (anyFavoriteSelected) {
                Spacer(Modifier.height(8.dp))
                RawSliderRow(
                    label = stringResource(R.string.raw_lut_intensity),
                    value = macro.lutIntensity,
                    valueRange = 0f..1f,
                    onValueChange = { onMacroChange(macro.copy(lutIntensity = it)) },
                    displayValue = "${(macro.lutIntensity * 100).toInt()}%",
                )
                activeCategoryHint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                    )
                }
            }
        }

        if (macro.lutCubeUri.isNotEmpty()) {
            val anyExpandedCategoryShowsIntensity = categories.any { cat ->
                (expandedCategories[cat.categoryName] ?: false) &&
                    cat.entries.any { isEntrySelected(it) }
            }
            val showTopLevelIntensity =
                !anyFavoriteSelected && !anyExpandedCategoryShowsIntensity
            if (showTopLevelIntensity) {
                Spacer(Modifier.height(8.dp))
                RawSliderRow(
                    label = stringResource(R.string.raw_lut_intensity),
                    value = macro.lutIntensity,
                    valueRange = 0f..1f,
                    onValueChange = { onMacroChange(macro.copy(lutIntensity = it)) },
                    displayValue = "${(macro.lutIntensity * 100).toInt()}%",
                )
                activeCategoryHint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                    )
                }
                Spacer(modifier.height(4.dp))
            }
            if (SHOW_LUT_APPROX_SYNC) {
                LutApproximableSyncPanel(
                    macro = macro,
                    onMacroChange = { onMacroChange(applyLutChange(it)) },
                    context = context,
                )
            }
        }
      }  // ── end showPicker (active LUT row + favorites + intensity + remove) ──

      if (showFinishing) {
        Spacer(Modifier.height(12.dp))

        // ── LUT Adjustments (flat — no card box, no dropdown) ─────────────
        // CLAHE Shadows/Highlights UI removed (2026-09) — fields remain for
        // sidecars. Film Response Recovery / Fill Light / B&W moved to Tone.
        Text(
            text = "LUT Adjustments",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
                        Spacer(Modifier.height(8.dp))
                        RawSliderRow(
                            label = "Highlights temp",
                            value = macro.highlightTemperature,
                            valueRange = -1f..1f,
                            step = 0.01f,
                            onValueChange = { onMacroChange(macro.copy(highlightTemperature = it)) },
                            displayValue = "${Math.round(macro.highlightTemperature * 100)}",
                        )
                        Spacer(Modifier.height(4.dp))
                        RawSliderRow(
                            label = "Highlights tint",
                            value = macro.highlightTint,
                            valueRange = -1f..1f,
                            step = 0.01f,
                            onValueChange = { onMacroChange(macro.copy(highlightTint = it)) },
                            displayValue = "${Math.round(macro.highlightTint * 100)}",
                        )
                        Spacer(Modifier.height(4.dp))
                        RawSliderRow(
                            label = "Shadows temp",
                            value = macro.shadowTemperature,
                            valueRange = -1f..1f,
                            step = 0.01f,
                            onValueChange = { onMacroChange(macro.copy(shadowTemperature = it)) },
                            displayValue = "${Math.round(macro.shadowTemperature * 100)}",
                        )
                        Spacer(Modifier.height(4.dp))
                        RawSliderRow(
                            label = "Shadows tint",
                            value = macro.shadowTint,
                            valueRange = -1f..1f,
                            step = 0.01f,
                            onValueChange = { onMacroChange(macro.copy(shadowTint = it)) },
                            displayValue = "${Math.round(macro.shadowTint * 100)}",
                        )
                        Spacer(modifier.height(8.dp))
                        // Bipolar creative overlay on the always-on headroom map:
                        // negative = Reinhard blend, positive = highlight vibrancy boost.
                        RawSliderRow(
                            label = "Vibrancy",
                            value = macro.lutHighlightVibrancy,
                            valueRange = -1f..1f,
                            step = 0.01f,
                            onValueChange = { onMacroChange(macro.copy(lutHighlightVibrancy = it)) },
                            displayValue = "${Math.round(macro.lutHighlightVibrancy * 100)}",
                        )
                        Text(
                            text = "0 = headroom protect · − = Reinhard · + = vibrancy",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                        )
                        Spacer(modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Spacer(modifier.height(8.dp))
                        // Color Density removed here — it lives in the Color tab only
                        // (a single source of truth; the duplicate here conflicted).
                        Text(
                            text = "Skin Tone",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        // Skin Tone — HSV detection at ~20° peach I-line hue.
                        // UserMacro stores UI values; ActionReplay divides by 100.
                        RawSliderRow(
                            label = "Skin warm",
                            value = macro.skintoneWarm,
                            valueRange = -50f..50f,
                            step = 1f,
                            onValueChange = { onMacroChange(macro.copy(skintoneWarm = it)) },
                            displayValue = "${macro.skintoneWarm.toInt()}",
                        )
                        Spacer(Modifier.height(4.dp))
                        RawSliderRow(
                            label = "Skin smooth",
                            value = macro.skintoneSmooth,
                            valueRange = 0f..100f,
                            step = 1f,
                            onValueChange = { onMacroChange(macro.copy(skintoneSmooth = it)) },
                            displayValue = "${macro.skintoneSmooth.toInt()}",
                        )
                        Spacer(Modifier.height(4.dp))
                        RawSliderRow(
                            label = "Skin luma",
                            value = macro.skintoneLuma,
                            valueRange = -50f..50f,
                            step = 1f,
                            onValueChange = { onMacroChange(macro.copy(skintoneLuma = it)) },
                            displayValue = "${macro.skintoneLuma.toInt()}",
                        )
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Spacer(Modifier.height(8.dp))
                        // Ambiance → Details tab; Bloom → FX tab.
                        // Dehaze — midtone-bell pull. Negative adds haze,
                        // positive removes it. Capped to ±25 (was ±100) so
                        // the LUT-ADJ finishing trim stays a subtle nudge.
                        RawSliderRow(
                            label = stringResource(R.string.raw_dehaze),
                            value = macro.dehaze,
                            valueRange = -25f..25f,
                            onValueChange = { onMacroChange(macro.copy(dehaze = it.coerceIn(-25f, 25f))) },
                        )
                        // "Subject Pop" hidden per request (2026-06-20). The
                        // equivalent live GL controls live in the Adjustments
                        // panel as "Hi Subj" / "Sh Subj".

        Spacer(Modifier.height(6.dp))

        TabResetButton(RawTabId.LutAdj, macro, onMacroChange, label = "LUT Adj")
      }  // ── end showFinishing (LUT Adjustments, flat inline) ──

      if (showPicker) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )

        // ── Presets browser ───────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.raw_lut_presets),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        )

        if (categories.isEmpty()) {
            Text(
                text = stringResource(R.string.raw_lut_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        categories.forEach { category ->
            val isUserCustom = category.categoryName == USER_CUSTOM_CATEGORY
            val isRazLooks = category.categoryName == RAZ_LOOKS_CATEGORY
            val isExpanded = expandedCategories[category.categoryName] ?: isRazLooks
            val showIntensityHere = !anyFavoriteSelected &&
                category.entries.any { isEntrySelected(it) }

            TextButton(
                onClick = { expandedCategories[category.categoryName] = !isExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = category.categoryName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    color = if (isUserCustom || isRazLooks)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        category.entries.forEach { entry ->
                            val key = entryKey(category.categoryName, entry)
                            if (isUserCustom) {
                                UserCustomLutChip(
                                    name = entry.name,
                                    selected = isEntrySelected(entry),
                                    isFavorite = key in favoriteKeys,
                                    onClick = { selectEntry(entry, category.categoryName) },
                                    onToggleFavorite = { toggleFavorite(key) },
                                    onDelete = { pendingDeleteCustomEntry = entry },
                                )
                            } else {
                                LutPresetChip(
                                    name = entry.name,
                                    selected = isEntrySelected(entry),
                                    isFavorite = key in favoriteKeys,
                                    onClick = { selectEntry(entry, category.categoryName) },
                                    onLongClick = { toggleFavorite(key) },
                                )
                            }
                        }
                    }
                    if (showIntensityHere) {
                        Spacer(Modifier.height(8.dp))
                        RawSliderRow(
                            label = stringResource(R.string.raw_lut_intensity),
                            value = macro.lutIntensity,
                            valueRange = 0f..1f,
                            onValueChange = { onMacroChange(macro.copy(lutIntensity = it)) },
                            displayValue = "${(macro.lutIntensity * 100).toInt()}%",
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        activeCategoryHint?.let { hint ->
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            )
        }
      }  // ── end showPicker (presets browser) ──
    }

    // ── Remove from Favorites dialog ──────────────────────────────────────────
    pendingRemoveFavoriteKey?.let { key ->
        val entryName = favoriteEntries.firstOrNull { it.first == key }?.second?.name ?: key.substringAfter('/')
        EnhancedAlertDialog(
            visible = true,
            onDismissRequest = { pendingRemoveFavoriteKey = null },
            title = { Text(stringResource(R.string.raw_lut_remove_favorite_title)) },
            text = { Text(stringResource(R.string.raw_lut_remove_favorite_body, entryName)) },
            confirmButton = {
                EnhancedButton(
                    onClick = {
                        toggleFavorite(key)
                        pendingRemoveFavoriteKey = null
                    },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) {
                    Text(stringResource(R.string.raw_lut_remove))
                }
            },
            dismissButton = {
                EnhancedButton(
                    onClick = { pendingRemoveFavoriteKey = null },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // ── HaldCLUT export naming dialog ─────────────────────────────────────────
    if (showHaldNameDialog) {
        EnhancedAlertDialog(
            visible = true,
            onDismissRequest = { showHaldNameDialog = false },
            title = { Text("Name the HaldCLUT") },
            text = {
                Column {
                    Text(
                        text = "Saved to your default folder, under HaldCLUT/. " +
                            "If the name already exists, a number is appended (_1, _2…).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = haldName,
                        onValueChange = { haldName = it },
                        singleLine = true,
                        label = { Text("File name") },
                        suffix = { Text(".png") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                EnhancedButton(
                    onClick = {
                        val chosen = haldName
                        showHaldNameDialog = false
                        scope.launch {
                            val path = repo.exportIdentityHald(name = chosen)
                            android.widget.Toast.makeText(
                                context,
                                if (path != null) "Identity HaldCLUT saved to:\n$path" else "Hald export failed",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    enabled = haldName.isNotBlank(),
                ) {
                    Text("Export")
                }
            },
            dismissButton = {
                EnhancedButton(
                    onClick = { showHaldNameDialog = false },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // ── "Save current edit as LUT" dialog ─────────────────────────────────────
    if (showSaveEditDialog && onSaveEditAsLut != null) {
        EnhancedAlertDialog(
            visible = true,
            onDismissRequest = { if (!savingEdit) showSaveEditDialog = false },
            title = { Text("Save edit as LUT") },
            text = {
                Column {
                    Text(
                        text = "Bakes your current color edit into a .cube LUT saved under " +
                            "\"User's Lut\". Captures color only — not blur, grain, masks, or " +
                            "other local effects.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = saveEditName,
                        onValueChange = { saveEditName = it },
                        singleLine = true,
                        enabled = !savingEdit,
                        label = { Text("LUT name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                EnhancedButton(
                    enabled = !savingEdit && saveEditName.isNotBlank(),
                    onClick = {
                        val chosen = saveEditName
                        scope.launch {
                            savingEdit = true
                            val saved = runCatching { onSaveEditAsLut(chosen) }.getOrNull()
                            savingEdit = false
                            showSaveEditDialog = false
                            if (saved != null) {
                                categories = repo.getCategories()
                                android.widget.Toast.makeText(context, "Saved \"$saved\" to User's Lut", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                android.widget.Toast.makeText(context, "Couldn't create a LUT from this edit", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ) { Text(if (savingEdit) "Saving…" else "Save") }
            },
            dismissButton = {
                EnhancedButton(
                    enabled = !savingEdit,
                    onClick = { showSaveEditDialog = false },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    // ── Delete User's Custom LUT dialog ───────────────────────────────────────
    pendingDeleteCustomEntry?.let { entry ->
        EnhancedAlertDialog(
            visible = true,
            onDismissRequest = { pendingDeleteCustomEntry = null },
            title = { Text(stringResource(R.string.raw_lut_delete_custom_title)) },
            text = { Text(stringResource(R.string.raw_lut_delete_custom_body, entry.name)) },
            confirmButton = {
                EnhancedButton(
                    onClick = {
                        deleteCustomEntry(entry)
                        pendingDeleteCustomEntry = null
                    },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) {
                    Text(stringResource(R.string.raw_lut_delete))
                }
            },
            dismissButton = {
                EnhancedButton(
                    onClick = { pendingDeleteCustomEntry = null },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** Chip for User's Custom LUTs — has explicit star (favorite) and red delete buttons. */
@Composable
private fun UserCustomLutChip(
    name: String,
    selected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                         else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                       else MaterialTheme.colorScheme.onSurface

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
        border = if (!selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)) else null,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 4.dp),
            )
            // Star — toggle favorite
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = if (isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp),
                )
            }
            // Red delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.raw_lut_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Chip that supports both tap (select) and long-press (toggle favorite). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LutPresetChip(
    name: String,
    selected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                         else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                       else MaterialTheme.colorScheme.onSurface

    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        border = if (!selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)) else null,
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (isFavorite) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size(10.dp),
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Approximable LUT sync — Temp/Tint/Contrast/H-S/Sat (+ optional film shoulder)
 * in StudioRoom UI units. "Match from LUT" fits the active cube with kernels that
 * mirror apply_macro; bloom/sharpness/true RAW Kelvin are intentionally absent.
 */
@Composable
private fun LutApproximableSyncPanel(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    context: android.content.Context,
) {
    val scope = rememberCoroutineScope()
    var analyzing by remember { mutableStateOf(false) }
    var lastNote by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(true) }

    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LUT sync (approximable)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
            contentDescription = null,
        )
    }
    AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
        Column {
            Text(
                text = "LUTs remap colour only. Temp/Tint here are display approximations " +
                    "(not sensor Kelvin). Contrast · Highlights · Shadows · Saturation map to " +
                    "Light/Color sliders. Bloom, blur, and sharpness stay on Effects/Detail.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (analyzing) return@OutlinedButton
                        analyzing = true
                        lastNote = null
                        scope.launch {
                            val path = macro.lutCubeUri
                            val file = resolveLutFile(context, path)
                            val result = if (file != null) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                        .LutAdjustmentApprox.analyzeFile(file, macro.lutIntensity)
                                }
                            } else null
                            analyzing = false
                            if (result == null) {
                                lastNote = "Could not read LUT"
                                return@launch
                            }
                            onMacroChange(result.applyTo(macro, reduceLutIntensity = false))
                            lastNote = "Matched · explained ${(result.explained * 100).toInt()}% · " +
                                "RMS ${"%.3f".format(result.residualRms)} — Intensity unchanged " +
                                "(sliders run before the LUT; lower Intensity if the look doubles)."
                        }
                    },
                    enabled = !analyzing && macro.lutCubeUri.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    if (analyzing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text("Match from LUT")
                }
                OutlinedButton(
                    onClick = {
                        if (analyzing) return@OutlinedButton
                        analyzing = true
                        scope.launch {
                            val file = resolveLutFile(context, macro.lutCubeUri)
                            val result = if (file != null) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                        .LutAdjustmentApprox.analyzeFile(file, macro.lutIntensity)
                                }
                            } else null
                            analyzing = false
                            if (result == null) {
                                lastNote = "Could not read LUT"
                                return@launch
                            }
                            onMacroChange(result.applyTo(macro, reduceLutIntensity = true))
                            lastNote = "Transferred · Intensity → ${(result.explained.let { e ->
                                (macro.lutIntensity * (1f - e * 0.85f) * 100).toInt()
                            })}% · residual creative look stays in the cube."
                        }
                    },
                    enabled = !analyzing && macro.lutCubeUri.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Transfer")
                }
            }
            lastNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            // Precision sliders — same UserMacro fields as Light/Color tabs.
            RawSliderRow(
                label = "Temperature",
                value = macro.whiteBalance.toFloat(),
                valueRange = -2500f..2500f,
                step = 25f,
                displayValue = if (macro.whiteBalance >= 0) "+${macro.whiteBalance} K"
                               else "${macro.whiteBalance} K",
                onValueChange = { onMacroChange(macro.copy(whiteBalance = it.toInt())) },
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = "Tint",
                value = macro.tint,
                valueRange = -150f..150f,
                step = 1f,
                displayValue = "${macro.tint.toInt()}",
                onValueChange = { onMacroChange(macro.copy(tint = it)) },
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = "Contrast",
                value = macro.contrast,
                valueRange = -100f..100f,
                step = 1f,
                displayValue = "${macro.contrast.toInt()}",
                onValueChange = { onMacroChange(macro.copy(contrast = it)) },
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = "Highlights",
                value = macro.highlights,
                valueRange = -100f..100f,
                step = 1f,
                displayValue = "${macro.highlights.toInt()}",
                onValueChange = { onMacroChange(macro.copy(highlights = it)) },
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = "Shadows",
                value = macro.shadows,
                valueRange = -100f..100f,
                step = 1f,
                displayValue = "${macro.shadows.toInt()}",
                onValueChange = { onMacroChange(macro.copy(shadows = it)) },
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = "Saturation",
                value = macro.saturation,
                valueRange = -100f..100f,
                step = 1f,
                displayValue = "${macro.saturation.toInt()}",
                onValueChange = { onMacroChange(macro.copy(saturation = it)) },
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = "Rolloff",
                value = macro.filmRolloff,
                valueRange = 0f..1f,
                step = 0.01f,
                displayValue = "${(macro.filmRolloff * 100).toInt()}",
                onValueChange = { onMacroChange(macro.copy(filmRolloff = it)) },
            )
        }
    }
}

/** Resolve an absolute path or content URI staged for preview to a readable File. */
private fun resolveLutFile(context: android.content.Context, uriOrPath: String): java.io.File? {
    if (uriOrPath.isEmpty()) return null
    val asFile = java.io.File(uriOrPath)
    if (asFile.isFile) return asFile
    return runCatching {
        val uri = Uri.parse(uriOrPath)
        val tmp = java.io.File(context.cacheDir, "lut_sync_probe.cube")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        tmp.takeIf { it.length() > 0L }
    }.getOrNull()
}

