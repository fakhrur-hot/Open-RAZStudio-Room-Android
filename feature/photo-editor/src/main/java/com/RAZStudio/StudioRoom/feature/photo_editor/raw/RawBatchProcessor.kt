/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.RAZStudio.StudioRoom.core.settings.domain.SettingsProvider
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3LutChainResolver
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Coordinator
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sequentially processes all RAW files found in a folder tree URI using the
 * same pipeline as the single-image RAW editor: AMaZE+LMMSE dual demosaic,
 * Stage A → Stage B → Stage C, action cards as preset, watermark preset, and
 * export format/quality/sizing from the user's Settings page.
 */
@Singleton
class RawBatchProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsProvider: SettingsProvider,
) {

    /** Policy for handling an existing file with the same name in the target folder. */
    enum class FilenameCollisionPolicy {
        Overwrite,
        AddDateSuffix,
    }

    /** Policy for which EXIF metadata to retain in the exported file. */
    enum class ExifPolicy {
        KeepAll,
        StripSensitive,
        NoneExceptSoftware,
    }

    sealed interface BatchProgress {
        data object Idle : BatchProgress
        data class Running(
            val currentIndex: Int,
            val totalCount: Int,
            val currentFileName: String,
        ) : BatchProgress
        data class Done(
            val successCount: Int,
            val failCount: Int,
            /** True when at least one file's configured save folder was
             *  unavailable and output fell back to the default folder. */
            val folderWasReset: Boolean = false,
        ) : BatchProgress
        data class Cancelled(val successCount: Int) : BatchProgress
    }

    private val _state = MutableStateFlow<BatchProgress>(BatchProgress.Idle)
    val state: StateFlow<BatchProgress> = _state

    private var batchScope: CoroutineScope? = null

    private val v3Coordinator by lazy {
        com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .RawV3Coordinator(context)
    }

    /**
     * Pre-computed per-batch context. Built once in [start] and reused for
     * every file. Also exposed so Canon Sync's streamed pipeline can build
     * it once via [prepareContext] and feed files through [processOneFile].
     */
    data class PerFileContext(
        val v3Options: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .RawV3Coordinator.ExportOptions,
        val outputUri: String?,
    )

    /**
     * Build a [PerFileContext] from the action stack and watermark config.
     * Workspace comes from [WorkspaceConfig.fromPrefs] (same as the single-
     * image editor). Format/quality/scale/sharpen come from [SettingsProvider].
     * Identical to what [start] does internally — exposed so Canon Sync and
     * other callers can build it once and stream individual files.
     *
     * [lutCubeFile] / [lutIntensity]: the resolved .cube file for the preset's
     * LUT. Folder-tree batch resolves and chains multi-layer LUTs in [start]
     * via [RawV3LutChainResolver]; Canon Sync passes null here until it opts
     * into the same resolution path.
     */
    fun prepareContext(
        actions: List<RawAction>,
        autoExposure: Boolean = false,  // match editor Route-A default (AE on Open = OFF)
        aiReconstruct: Boolean? = null,   // null = use WorkspaceConfig prefs value
        aiEnhance: Boolean? = null,       // null = use WorkspaceConfig prefs value
        guidedFilter: Boolean? = null,    // null = use WorkspaceConfig prefs value
        useCameraColorProfile: Boolean = false, // route A — match camera colour
        watermarkConfig: com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
            .CombinedWatermarkConfig? = null,
        lutCubeFile: java.io.File? = null,
        lutIntensity: Float = 1f,
        outputUri: String? = null,
        exifPolicy: ExifPolicy = ExifPolicy.KeepAll,
        saveIcc: Boolean = true,
        format: RawExportFormat? = null,
        targetLongSide: Int = 0,
        qualityPct: Int = -1,
        nrLevel: RawV3Coordinator.NrLevel = RawV3Coordinator.NrLevel.None,
        aeSubjectProtection: Float = 0.95f,
        autoBrightFactor: Float = 1f,
        highlightProtection: Float? = null,   // null = use WorkspaceConfig prefs value
        lensCorrection: Boolean = true,       // Lensfun per-file auto-detect (matches editor default ON)
        /**
         * Per-photo workspace (Gallery Workspace sidecar). When set it is used
         * VERBATIM — the same object the editor hands `buildExportOptions` on
         * the Export page — so the photo's own demosaic/AI/lens-profile choices
         * bake instead of the global prefs. Only the profile database directory
         * is re-materialised (the stored path may predate a reinstall).
         */
        workspaceOverride: WorkspaceConfig? = null,
    ): PerFileContext {
        val settings = settingsProvider.settingsState.value
        val baseConfig = workspaceOverride ?: WorkspaceConfig.fromPrefs(context)
        // Lensfun lens correction: batch passes ONLY the db dir — no camera/lens
        // overrides — so Stage A auto-matches each file from its OWN EXIF. A
        // file whose camera or lens isn't confidently matched simply imports
        // without correction (same strict gate as the single editor).
        val lensfunDir = if (lensCorrection) {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.LensfunDatabase
                .ensureMaterialized(context).orEmpty()
        } else ""
        // Route A (Camera Color Profile) = camera-matched curve + optional AI
        // Level Reconstruct (matches the workspace selector, which restored
        // this toggle to Route A, off by default there). AI Enhance + Guided
        // Filter stay Route-B-only — force those OFF under Route A.
        val effectiveConfig = if (workspaceOverride != null) baseConfig.copy(
            lensfunDbDir = if (lensCorrection) lensfunDir else "",
        ) else baseConfig.copy(
            hdrRecovery           = aiReconstruct ?: baseConfig.hdrRecovery,
            shadowRecovery        = aiReconstruct ?: baseConfig.shadowRecovery,
            enhanceEnabled        = if (useCameraColorProfile) false else (aiEnhance     ?: baseConfig.enhanceEnabled),
            enhanceGuidedFilter   = if (useCameraColorProfile) false else (guidedFilter  ?: baseConfig.enhanceGuidedFilter),
            useCameraColorProfile = useCameraColorProfile,
            highlightProtection   = highlightProtection ?: baseConfig.highlightProtection,
            lensfunDbDir          = lensfunDir,
            lensfunCameraId       = "",
            lensfunLensId         = "",
            lensfunFocalOverrideMm = 0f,
        )

        val hasLut = actions.any { it.isVisible && it.macro.lutCubeUri.isNotEmpty() }
        val hasBokeh = actions.any {
            it.isVisible && it.maskPath == null &&
                (it.macro.bokehBlur > 0 || it.macro.bokehBalls > 0 || it.macro.bokehSpread > 0f)
        }
        // Subject-scoped preset content: per-segment tone slots, subject/
        // background vignette routing, smart sharpness. These render with a
        // null mask (silently gating to zero — vignette "disappearing" etc.)
        // unless subject detection is forced on so the coordinator's
        // render-time segmentation trigger can fire per file.
        val hasSubjectScoped = actions.any { a ->
            val m = a.macro
            a.isVisible && (
                m.highlightsSubject != 0f || m.whitesSubject != 0f ||
                    m.blacksSubject != 0f || m.shadowsSubject != 0f ||
                    m.ambianceSubject != 0f ||
                    m.highlightsBackground != 0f || m.whitesBackground != 0f ||
                    m.blacksBackground != 0f || m.shadowsBackground != 0f ||
                    m.ambianceBackground != 0f ||
                    m.vignetteEffect != com.RAZStudio.StudioRoom.feature.photo_editor
                        .raw.model.VignetteEffect.All ||
                    m.smartSharpness > 0f ||
                    // Protect-subject flags count as subject-scoped too: without
                    // them the file exported with the subject unprotected while
                    // the editor preview showed it protected (fixed 2026-09-07,
                    // mirrors RawV3Coordinator.paramsNeedSubjectMask).
                    m.bloomExcludeSubject || m.subjectBloom > 0f ||
                    m.fxBlurExcludeSubject || m.subjectPopEnabled
                )
        }
        // Subject detection: always on when auto-exposure is enabled (uses subject
        // masks for per-segment tone moves), or when preset has LUT/bokeh/any
        // subject-scoped adjustment.
        val needsSubject = autoExposure || hasLut || hasBokeh || hasSubjectScoped

        // Mirror the single editor's aeBaked logic: if the preset action stack
        // already contains a baked AE action, skip re-analysis in the coordinator
        // (same as RawEditorComponent.exportToGallery).
        val hasBakedAe = actions.any { it.isAutoExposure }
        // Auto Expose on Open is available in BOTH routes (matches the workspace
        // selector: Route A / Camera Color Profile exposes ONLY this; Route B adds
        // Reconstruct + Enhance, forced off above for Route A). So honour the
        // autoExposure toggle regardless of route.
        val effectiveAutoExposure = autoExposure || hasBakedAe
        val effectiveAeBaked = hasBakedAe

        val v3Options = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
            .RawEditorExportPipeline.buildExportOptions(
                actions = actions,
                workspace = effectiveConfig,
                settings = settings,
                format = format,
                targetLongSide = targetLongSide,
                qualityPct = qualityPct,
                exifPolicy = exifPolicy,
                saveIcc = saveIcc,
                outputUri = outputUri,
                autoExposure = effectiveAutoExposure,
                aeBaked = effectiveAeBaked,
                aeSubjectProtection = aeSubjectProtection,
                nrLevel = nrLevel,
                resolvedLutFile = lutCubeFile,
                resolvedLutIntensity = lutIntensity,
                watermarkConfig = watermarkConfig,
                forceSubjectDetection = needsSubject,
                autoBrightFactor = autoBrightFactor,
            )
        return PerFileContext(v3Options = v3Options, outputUri = outputUri)
    }

    /**
     * Process a single source file with a pre-built [PerFileContext].
     * Canon Sync uses this for its streamed Download & Process pipeline.
     */
    suspend fun processOneFile(
        context: PerFileContext,
        fileUri: Uri,
    ): com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
        .RawV3Coordinator.ExportResult =
        v3Coordinator.exportRawToGallery(fileUri, context.v3Options)

    /**
     * Start batch processing all RAW/image files in [folderTreeUri].
     *
     * Uses the same pipeline as the single-image RAW editor:
     *  - Workspace: [WorkspaceConfig.fromPrefs] (user's demosaic/WB/NR prefs)
     *  - Format/quality/scale/sharpen: from [SettingsProvider.settingsState]
     *  - Preset: [actions] folded via [buildMacroFromActions]
     *  - Watermark: [watermarkConfig]
     */
    /** Which source formats a folder batch should process. */
    enum class FormatFilter { RAW_AND_JPEG, RAW_ONLY, JPEG_ONLY }

    fun start(
        folderTreeUri: Uri,
        actions: List<RawAction>,
        autoExposure: Boolean = false,  // match editor Route-A default (AE on Open = OFF)
        aiReconstruct: Boolean? = null,
        aiEnhance: Boolean? = null,
        guidedFilter: Boolean? = null,
        useCameraColorProfile: Boolean = false,
        formatFilter: FormatFilter = FormatFilter.RAW_AND_JPEG,
        watermarkConfig: com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
            .CombinedWatermarkConfig? = null,
        exifPolicy: ExifPolicy = ExifPolicy.KeepAll,
        saveIcc: Boolean = true,
        highlightProtection: Float? = null,
        lensCorrection: Boolean = true,
    ) {
        if (_state.value is BatchProgress.Running) return

        android.util.Log.i(
            "RawBatchProcessor",
            "start: folder=$folderTreeUri actions=${actions.size}",
        )
        runCatching {
            java.io.File(context.cacheDir, "raw_pipeline").deleteRecursively()
        }
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        batchScope = scope

        scope.launch {
            val rawUris = withContext(Dispatchers.IO) { listRawFiles(folderTreeUri, formatFilter) }
            android.util.Log.i("RawBatchProcessor", "discovered ${rawUris.size} files")
            if (rawUris.isEmpty()) {
                _state.value = BatchProgress.Done(successCount = 0, failCount = 0)
                scope.cancel()
                return@launch
            }

            // Resolve the full LUT chain (multi-layer presets are folded into a
            // single in-memory cube) once per batch, on a background thread.
            val lutChain = withContext(Dispatchers.IO) {
                RawV3LutChainResolver.resolveChain(context, actions)
            }

            val perFileCtx = prepareContext(
                actions = actions,
                autoExposure = autoExposure,
                aiReconstruct = aiReconstruct,
                aiEnhance = aiEnhance,
                guidedFilter = guidedFilter,
                useCameraColorProfile = useCameraColorProfile,
                watermarkConfig = watermarkConfig,
                lutCubeFile = lutChain.file,
                lutIntensity = lutChain.intensity,
                outputUri = null,
                exifPolicy = exifPolicy,
                saveIcc = saveIcc,
                highlightProtection = highlightProtection,
                lensCorrection = lensCorrection,
            )

            runEntries(
                scope = scope,
                entries = rawUris.map { (u, n) -> Entry(u, n, perFileCtx) },
                watermarkConfig = watermarkConfig,
            )
        }
    }

    /** One unit of work for [runEntries]. */
    private data class Entry(
        val uri: Uri,
        val name: String,
        val ctx: PerFileContext,
        /**
         * Lens name to stamp in the EXIF watermark instead of the file's own
         * LensModel tag — set when a sidecar carries a chosen camera+lens
         * profile (adapted lenses report a wrong/blank tag).
         */
        val lensLabelOverride: String? = null,
    )

    /**
     * One photo of a Gallery Workspace project, carrying its OWN sidecar state.
     * [workspace] null = photo never edited → global prefs; [actions] should
     * already include the Original sentinel.
     */
    data class SidecarBatchItem(
        val uri: Uri,
        val displayName: String,
        val workspace: WorkspaceConfig?,
        val actions: List<RawAction>,
    )

    /**
     * Gallery Workspace "Export photos": bake every [items] entry with its own
     * sidecar workspace + action stack (LUT chain, masks, lens profile, AE card)
     * through the same Stage A→C export the editor's Save uses, into the user's
     * default save folder. Progress/cancel/reset share [state] with the folder
     * batch, so only one batch runs at a time.
     */
    fun startItems(
        items: List<SidecarBatchItem>,
        format: RawExportFormat? = RawExportFormat.JPG,
        watermarkConfig: com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
            .CombinedWatermarkConfig? = null,
        exifPolicy: ExifPolicy = ExifPolicy.KeepAll,
        saveIcc: Boolean = true,
        targetLongSide: Int = 0,
    ) {
        if (_state.value is BatchProgress.Running) return
        android.util.Log.i("RawBatchProcessor", "startItems: ${items.size} project photos, format=$format")
        if (items.isEmpty()) {
            _state.value = BatchProgress.Done(successCount = 0, failCount = 0)
            return
        }
        runCatching { java.io.File(context.cacheDir, "raw_pipeline").deleteRecursively() }
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        batchScope = scope
        scope.launch {
            _state.value = BatchProgress.Running(0, items.size, items.first().displayName)
            val entries = withContext(Dispatchers.IO) {
                items.map { item ->
                    val lutChain = RawV3LutChainResolver.resolveChain(context, item.actions)
                    val ws = item.workspace
                    val ctx = prepareContext(
                        actions = item.actions,
                        useCameraColorProfile = ws?.useCameraColorProfile ?: false,
                        watermarkConfig = watermarkConfig,
                        lutCubeFile = lutChain.file,
                        lutIntensity = lutChain.intensity,
                        exifPolicy = exifPolicy,
                        saveIcc = saveIcc,
                        format = format,
                        targetLongSide = targetLongSide,
                        workspaceOverride = ws,
                    )
                    Entry(item.uri, item.displayName, ctx, ws?.lensfunLensId?.takeIf { it.isNotBlank() })
                }
            }
            runEntries(scope, entries, watermarkConfig)
        }
    }

    /** Shared per-file loop for [start] and [startItems]. Runs inside [scope]. */
    private suspend fun runEntries(
        scope: CoroutineScope,
        entries: List<Entry>,
        watermarkConfig: com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
            .CombinedWatermarkConfig?,
    ) {
        with(scope) {
                var successCount = 0
                var failCount = 0
                var folderResetSeen = false
                // Two-stage pipeline: while file N runs Stage C + encode + save,
                // file N+1's Stage A (the dominant 15-26s LibRaw decode) runs
                // concurrently via prewarmStageA, so N+1's export cache-hits.
                // The coordinator's stageADecodeMutex guarantees at most ONE
                // native demosaic at a time (prefetch overlaps Stage C only),
                // and the memory gate skips prefetching entirely on pressured
                // devices — degrading back to the old strictly-sequential loop.
                var prefetchJob: kotlinx.coroutines.Job? = null
                for ((index, entry) in entries.withIndex()) {
                    val fileUri = entry.uri
                    val fileName = entry.name
                    val perFileCtx = entry.ctx
                    android.util.Log.i("RawBatchProcessor", "[$index/${entries.size}] $fileName")
                    _state.value = BatchProgress.Running(
                        currentIndex    = index + 1,
                        totalCount      = entries.size,
                        currentFileName = fileName,
                    )
                    // The prefetch launched during the PREVIOUS iteration was for
                    // THIS file — wait for it so the A.tif write is complete
                    // before exportRawToGallery checks the cache.
                    prefetchJob?.join()
                    prefetchJob = if (index + 1 < entries.size && prefetchMemoryOk()) {
                        val next = entries[index + 1]
                        launch(Dispatchers.IO) {
                            runCatching { v3Coordinator.prewarmStageA(next.uri, next.ctx.v3Options) }
                        }
                    } else null
                    // Attach per-file EXIF to the watermark config so each photo gets its own
                    // camera/lens/exposure metadata instead of the same baked values.
                    val fileCtx = if (watermarkConfig != null) {
                        val fileMeta = withContext(Dispatchers.IO) {
                            readExifFromUri(fileUri)
                        }
                        val perFileWm = if (fileMeta != null) {
                            val attached = com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components
                                .attachExifConfig(context, fileMeta, watermarkConfig)
                            // In batch, make/model/lens must always come from the file's own EXIF —
                            // the preset may have baked the values from whichever photo was open when
                            // the preset was saved. Force-replace them with the real per-file values.
                            val exif = attached.exif
                            if (exif != null) {
                                attached.copy(
                                    exif = exif.copy(
                                        make      = fileMeta.cameraMake,
                                        model     = fileMeta.cameraModel,
                                        lensModel = entry.lensLabelOverride ?: fileMeta.lensInfo,
                                    )
                                )
                            } else attached
                        } else watermarkConfig
                        perFileCtx.copy(v3Options = perFileCtx.v3Options.copy(watermarkConfig = perFileWm))
                    } else perFileCtx
                    try {
                        when (val r = processOneFile(fileCtx, fileUri)) {
                            is com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                .RawV3Coordinator.ExportResult.Success -> {
                                successCount++
                                if (r.savedToFallbackFolder) folderResetSeen = true
                                android.util.Log.i("RawBatchProcessor",
                                    "[$index] OK: $fileName → ${r.savedAt} " +
                                    "(${r.widthPx}×${r.heightPx}, ${r.bytes / 1024} KB, ${r.totalMs} ms" +
                                    (if (r.savedToFallbackFolder) ", FOLDER RESET→default" else "") + ")")
                            }
                            is com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                .RawV3Coordinator.ExportResult.Skipped -> {
                                failCount++
                                android.util.Log.w("RawBatchProcessor",
                                    "[$index] skipped: $fileName — ${r.reason}")
                            }
                            is com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                .RawV3Coordinator.ExportResult.Failure -> {
                                failCount++
                                android.util.Log.w("RawBatchProcessor",
                                    "[$index] FAILED: $fileName — ${r.error}")
                            }
                        }
                    } catch (e: CancellationException) {
                        android.util.Log.i("RawBatchProcessor", "cancelled at [$index] $fileName")
                        _state.value = BatchProgress.Cancelled(successCount)
                        scope.cancel()
                        return
                    } catch (e: Exception) {
                        failCount++
                        android.util.Log.e("RawBatchProcessor",
                            "[$index] EXCEPTION on $fileName: ${e::class.simpleName}: ${e.message}", e)
                    }
                }
                android.util.Log.i("RawBatchProcessor",
                    "DONE: success=$successCount fail=$failCount of ${entries.size}" +
                        (if (folderResetSeen) " (output folder was reset to default)" else ""))
                _state.value = BatchProgress.Done(successCount, failCount, folderResetSeen)
                scope.cancel()
        }
    }

    /**
     * Gate for the Stage A prefetch pipeline: only overlap the next file's
     * LibRaw decode with the current file's Stage C when the device has real
     * memory headroom. On pressured devices this returns false and the batch
     * degrades to the original strictly-sequential loop (peak RAM ≈ one
     * editor open), preserving the lmkd-safety property the sequential
     * design was chosen for.
     */
    private fun prefetchMemoryOk(): Boolean {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager ?: return false
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return !mi.lowMemory && mi.availMem > 1_200L * 1024 * 1024
    }

    fun cancel() {
        val current = _state.value
        val done = if (current is BatchProgress.Running) current.currentIndex - 1 else 0
        batchScope?.cancel()
        _state.value = BatchProgress.Cancelled(done)
    }

    fun reset() {
        batchScope?.cancel()
        _state.value = BatchProgress.Idle
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun listRawFiles(treeUri: Uri, formatFilter: FormatFilter): List<Pair<Uri, String>> {
        val results = mutableListOf<Pair<Uri, String>>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        ) ?: return results

        cursor.use {
            val idCol   = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (it.moveToNext()) {
                val docId = it.getString(idCol) ?: continue
                val name  = it.getString(nameCol) ?: continue
                val mime  = it.getString(mimeCol) ?: ""
                if (isRawFile(name, mime)) {
                    val isRaw = isRawPhoto(name, mime)
                    val include = when (formatFilter) {
                        FormatFilter.RAW_AND_JPEG -> true
                        FormatFilter.RAW_ONLY     -> isRaw
                        FormatFilter.JPEG_ONLY    -> !isRaw
                    }
                    if (include) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        results.add(docUri to name)
                    }
                }
            }
        }
        return results
    }

    private fun isRawFile(name: String, mime: String): Boolean {
        if (mime.startsWith("image/x-")) return true
        if (mime == "image/jpeg" || mime == "image/png" ||
            mime == "image/webp" || mime == "image/bmp" ||
            mime == "image/tiff") return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in RAW_EXTENSIONS || ext in NON_RAW_IMAGE_EXTENSIONS
    }

    /** True if the file is a camera RAW (vs a non-RAW image like JPEG/PNG). Used
     *  by the folder-batch format filter. mime `image/x-…` and the RAW extension
     *  set are RAW; everything else supported is treated as the "JPEG" category. */
    private fun isRawPhoto(name: String, mime: String): Boolean {
        if (mime.startsWith("image/x-")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in RAW_EXTENSIONS
    }

    /** Read EXIF fields needed for watermark stamping from a SAF Uri without running Stage A. */
    private fun readExifFromUri(uri: Uri): com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = android.media.ExifInterface(stream)
                com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata.EMPTY.copy(
                    sourceUri    = uri.toString(),
                    cameraMake   = exif.getAttribute(android.media.ExifInterface.TAG_MAKE) ?: "",
                    cameraModel  = exif.getAttribute(android.media.ExifInterface.TAG_MODEL) ?: "",
                    lensInfo     = exif.getAttribute("LensModel") ?: "",
                    iso          = exif.getAttributeInt("PhotographicSensitivity", 0),
                    shutterSpeed = run {
                        val exp = exif.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME) ?: return@run 0f
                        val parts = exp.split("/")
                        if (parts.size == 2) parts[0].toFloatOrNull()?.div(parts[1].toFloatOrNull() ?: 1f) ?: 0f
                        else exp.toFloatOrNull() ?: 0f
                    },
                    aperture     = exif.getAttributeDouble(android.media.ExifInterface.TAG_F_NUMBER, 0.0).toFloat(),
                    focalLength  = run {
                        val fl = exif.getAttribute(android.media.ExifInterface.TAG_FOCAL_LENGTH) ?: return@run 0f
                        val parts = fl.split("/")
                        if (parts.size == 2) parts[0].toFloatOrNull()?.div(parts[1].toFloatOrNull() ?: 1f) ?: 0f
                        else fl.toFloatOrNull() ?: 0f
                    },
                    dateTimeOriginal = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL) ?: "",
                )
            }
        }.getOrNull()

    companion object {
        const val MAX_LONG_SIDE = 2560

        private val RAW_EXTENSIONS = setOf(
            "dng", "cr2", "cr3", "crw", "nef", "nrw",
            "arw", "sr2", "srf", "raf", "rw2", "raw",
            "orf", "pef", "srw", "x3f", "erf", "3fr",
            "fff", "dcr", "k25", "kdc", "mrw", "rwl",
            "mef", "iiq", "ari", "r3d", "gpr", "braw",
        )

        private val NON_RAW_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "jpe", "png", "webp", "bmp", "tif", "tiff",
        )
    }
}
