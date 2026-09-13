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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw_8bit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.arkivanov.decompose.ComponentContext
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.ui.utils.BaseComponent
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.Raw8BitSmokeTest
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.Raw8BitTonemapper
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.editor.Raw8BitActionApplier
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Backing component for the 8-bit RAW workspace screen.
 *
 * Phase 4.A foundation: in addition to the tonemap-and-save flow from
 * Phase 3, the component now owns:
 *
 *   • [baseBitmap]      — post-tonemap render, never mutated; reset target.
 *   • [previewBaseMat]  — downscaled CV_8UC3 BGR cache for fast slider previews.
 *   • [fullResBaseMat]  — full-resolution CV_8UC3 BGR; only touched on save.
 *   • [actions]         — committed [RawAction] stack (newest-first).
 *   • [workingMacro]    — in-flight slider values not yet committed.
 *
 * Each slider drag re-runs [Raw8BitActionApplier] on the *preview* Mat
 * with `actions + workingMacro`, converts the result to a Bitmap, and
 * publishes via [editorState]. On save, the same pipeline runs once at
 * full resolution to the SAF/Downloads JPG.
 *
 * Tabs (Light, Color, Tonemap, Curves, Details, LUT, plus Phase 4.B
 * local tabs) drive [updateWorkingMacro] with the partial macro
 * they're editing. When the user commits (tap "Apply" / done with a
 * slider gesture), [commitAction] folds the working macro into the
 * action stack and resets the working macro to default.
 */
class Raw8BitEditorComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val initialUri: Uri?,
    @Assisted val onGoBack: () -> Unit,
    @Assisted val onNavigate: (Screen) -> Unit,
    @ApplicationContext private val appContext: Context,
    dispatchersHolder: DispatchersHolder,
) : BaseComponent(dispatchersHolder, componentContext) {

    sealed interface UiState {
        data object Empty : UiState
        data class Processing(val stage: String) : UiState
        /**
         * Editor surface. [displayBitmap] is what the screen draws —
         * the downscaled preview after all committed actions + the
         * in-flight workingMacro. Tabs read [actions] + [workingMacro]
         * for slider seeding.
         */
        data class Editing(
            val displayBitmap: Bitmap,
            val sourcePath: String,
            val actions: List<RawAction>,
            val workingMacro: UserMacro,
        ) : UiState
        data class Saved(val displayBitmap: Bitmap, val outFile: File) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Empty)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val applier = Raw8BitActionApplier()

    private var fullResBaseMat: Mat? = null
    private var previewBaseMat: Mat? = null
    private var stagedFile: File? = null

    /** Committed action stack. Newest-first, like the 16-bit editor. */
    private var actions: List<RawAction> = emptyList()

    /** In-flight macro from whichever tab the user is dragging on. */
    private var workingMacro: UserMacro = UserMacro()

    /**
     * Coalesces rapid slider updates so we don't queue 60 reapply jobs
     * per second of drag. The latest update wins; in-flight job is
     * cancelled if a newer one arrives.
     */
    private var previewJob: Job? = null

    init {
        initialUri?.let { openFile(it) }
    }

    fun openFile(uri: Uri) {
        Log.i(TAG, "openFile: $uri")
        releaseMatsAndBitmap()
        _state.value = UiState.Processing(stage = "Staging file…")
        componentScope.launch {
            val cached = stageUriToCache(uri)
            if (cached == null) {
                _state.value = UiState.Error("Could not read selected file.")
                return@launch
            }
            stagedFile = cached
            _state.value = UiState.Processing(stage = "Decoding RAW + tonemap…")
            val bitmap = Raw8BitTonemapper(appContext).use { tm ->
                tm.tonemap(cached.absolutePath, halfSize = false)
            }
            if (bitmap == null) {
                _state.value = UiState.Error("Tonemap failed — see logcat.")
                return@launch
            }
            // Cache the post-tonemap result as the editor's base. We keep
            // both a full-res Mat (for save) and a downscaled preview
            // Mat (for slider drags). The Bitmap from the tonemapper is
            // what the UI shows initially, before any edits land.
            val (fullRes, preview) = buildBaseMats(bitmap)
            fullResBaseMat = fullRes
            previewBaseMat = preview
            actions = emptyList()
            workingMacro = UserMacro()
            _state.value = UiState.Editing(
                displayBitmap = bitmap,
                sourcePath = cached.absolutePath,
                actions = actions,
                workingMacro = workingMacro,
            )
        }
    }

    /**
     * Tabs call this on every slider tick. The component coalesces
     * rapid updates and re-renders the preview Mat once per coalesce
     * window. Pass [macro] = `UserMacro()` to clear the in-flight edit.
     */
    fun updateWorkingMacro(macro: UserMacro) {
        workingMacro = macro
        // Publish the new working macro immediately so sliders stay
        // responsive — but defer the actual preview render to the
        // coalescing job below.
        publishEditingState()
        previewJob?.cancel()
        previewJob = componentScope.launch { renderPreviewBitmap() }
    }

    /**
     * Fold the working macro into the action stack as a new [RawAction]
     * card and reset the in-flight macro. Tabs call this on slider
     * release (or on "Apply" tap for tabs that batch edits).
     */
    fun commitAction(label: String, tabIndex: Int) {
        if (workingMacro == UserMacro()) return // nothing to commit
        val action = RawAction(
            label = label,
            tabIndex = tabIndex,
            macro = workingMacro,
        )
        actions = listOf(action) + actions
        workingMacro = UserMacro()
        publishEditingState()
    }

    /** Undo the most recent action. */
    fun undo() {
        if (actions.isEmpty()) return
        actions = actions.drop(1)
        publishEditingState()
        previewJob?.cancel()
        previewJob = componentScope.launch { renderPreviewBitmap() }
    }

    fun save() {
        val current = _state.value
        if (current !is UiState.Editing) return
        _state.value = UiState.Processing(stage = "Saving JPG…")
        componentScope.launch {
            // Phase 4.A: still uses the smoke-test path which re-runs
            // the tonemap and saves it. Phase 4 follow-up will apply
            // the action stack to fullResBaseMat and save that.
            // TODO Phase 4.A.7 — replace with full-res action replay.
            val src = stagedFile?.absolutePath ?: current.sourcePath
            val outFile = Raw8BitSmokeTest.run(
                context = appContext,
                rawFilePath = src,
            )
            if (outFile == null) {
                _state.value = UiState.Error("Save failed — see logcat.")
            } else {
                _state.value = UiState.Saved(
                    displayBitmap = current.displayBitmap,
                    outFile = outFile,
                )
            }
        }
    }

    private fun publishEditingState() {
        val s = _state.value
        if (s !is UiState.Editing) return
        _state.value = s.copy(actions = actions, workingMacro = workingMacro)
    }

    /**
     * Rebuild the display bitmap from the preview Mat + current actions
     * + working macro. Runs on a background dispatcher so the slider
     * gesture stays smooth.
     */
    private suspend fun renderPreviewBitmap() = withContext(Dispatchers.Default) {
        val preview = previewBaseMat ?: return@withContext
        val t0 = System.currentTimeMillis()
        val rendered = applier.apply(preview, actions)
        // Apply the working macro on top of the committed stack so the
        // slider preview matches what `commitAction` would produce.
        applier.applyMacro(rendered, workingMacro)
        val rgba = Mat()
        Imgproc.cvtColor(rendered, rgba, Imgproc.COLOR_BGR2RGBA)
        rendered.release()
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        val dt = System.currentTimeMillis() - t0
        Log.v(TAG, "renderPreviewBitmap: ${bmp.width}x${bmp.height} in ${dt}ms")

        val current = _state.value
        if (current is UiState.Editing) {
            current.displayBitmap.recycle()
            _state.value = current.copy(displayBitmap = bmp)
        } else {
            bmp.recycle()
        }
    }

    /**
     * Convert the tonemap-output Bitmap into:
     *   1) a full-res CV_8UC3 BGR Mat (for save replay), and
     *   2) a downscaled (≤[PREVIEW_LONG_SIDE] px long side) CV_8UC3 BGR
     *      Mat (for fast slider previews).
     * The input bitmap is NOT recycled — the caller still uses it as
     * the initial displayBitmap.
     */
    private fun buildBaseMats(bitmap: Bitmap): Pair<Mat, Mat> {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val fullRes = Mat()
        Imgproc.cvtColor(rgba, fullRes, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        val longSide = maxOf(fullRes.cols(), fullRes.rows())
        val preview = if (longSide <= PREVIEW_LONG_SIDE) {
            fullRes.clone()
        } else {
            val scale = PREVIEW_LONG_SIDE.toDouble() / longSide.toDouble()
            val pw = (fullRes.cols() * scale).toInt().coerceAtLeast(1)
            val ph = (fullRes.rows() * scale).toInt().coerceAtLeast(1)
            val out = Mat()
            Imgproc.resize(
                fullRes, out,
                Size(pw.toDouble(), ph.toDouble()),
                0.0, 0.0,
                Imgproc.INTER_AREA,
            )
            out
        }
        return fullRes to preview
    }

    private fun releaseMatsAndBitmap() {
        previewJob?.cancel()
        previewJob = null
        fullResBaseMat?.release(); fullResBaseMat = null
        previewBaseMat?.release(); previewBaseMat = null
        when (val s = _state.value) {
            is UiState.Editing -> s.displayBitmap.recycle()
            is UiState.Saved -> s.displayBitmap.recycle()
            else -> { /* nothing to recycle */ }
        }
    }

    private fun stageUriToCache(uri: Uri): File? = runCatching {
        val mime = appContext.contentResolver.getType(uri)
            ?.substringAfterLast('/')?.lowercase() ?: "raw"
        val dst = File(appContext.cacheDir, "raw8bit_${System.nanoTime()}.$mime")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { input.copyTo(it) }
        } ?: return@runCatching null
        dst
    }.getOrElse {
        Log.e(TAG, "stageUriToCache failed", it)
        null
    }

    @AssistedFactory
    interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            initialUri: Uri?,
            onGoBack: () -> Unit,
            onNavigate: (Screen) -> Unit,
        ): Raw8BitEditorComponent
    }

    private companion object {
        private const val TAG = "Raw8BitEditor"
        /** Long side of the preview Mat. 1024 keeps slider feedback under 100 ms. */
        private const val PREVIEW_LONG_SIDE = 1024
    }
}
