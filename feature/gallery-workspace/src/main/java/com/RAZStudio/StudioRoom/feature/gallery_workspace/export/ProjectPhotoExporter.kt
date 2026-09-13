/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.gallery_workspace.export

import android.content.Context
import android.net.Uri
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.SidecarOpsRepository
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.loadWatermarkPreset
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.toRawAction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Export photos" for a Gallery Workspace project (owner request 2026-09-07):
 * every photo's sidecar — workspace (incl. its camera+lens profile), committed
 * action stack, masks, LUT chain — is baked to a finished file through
 * [RawBatchProcessor.startItems], i.e. the exact Stage A→C path the editor's
 * Save uses. This replaces the old "export project bundle" as the card action;
 * the bundle exporter is still used by project import/relink.
 *
 * Photos without a sidecar export untouched (global prefs, Original only).
 * Progress is the processor's own [state] — one batch at a time app-wide.
 */
@Singleton
class ProjectPhotoExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoDao: PhotoDao,
    private val sidecarOps: SidecarOpsRepository,
    private val processor: RawBatchProcessor,
) {
    val state: StateFlow<RawBatchProcessor.BatchProgress> get() = processor.state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean get() = processor.state.value is RawBatchProcessor.BatchProgress.Running

    /**
     * Collect [projectId]'s photos + sidecars and hand them to the batch
     * processor. [onEmpty] fires (main thread) when the project has no photos;
     * a running batch makes this a no-op (callers check [isRunning] first).
     */
    fun start(
        projectId: Long,
        format: RawExportFormat = RawExportFormat.JPG,
        watermarkPresetName: String? = null,
        onEmpty: () -> Unit = {},
    ) {
        if (isRunning) return
        scope.launch {
            val photos = photoDao.getAllForProject(projectId)
            val items = photos.map { p ->
                val snap = runCatching { sidecarOps.loadSnapshot(p.id) }.getOrNull()
                val actions = snap?.actionStack.orEmpty().map { it.toRawAction() }.toMutableList()
                if (actions.none { it.id == RawAction.ORIGINAL_ID }) actions.add(RawAction.Original)
                RawBatchProcessor.SidecarBatchItem(
                    uri = Uri.parse(p.sourceUri),
                    displayName = p.displayName,
                    workspace = snap?.workspace,
                    actions = actions,
                )
            }
            if (items.isEmpty()) {
                withContext(Dispatchers.Main) { onEmpty() }
                return@launch
            }
            val wm = watermarkPresetName?.let { runCatching { loadWatermarkPreset(context, it) }.getOrNull() }
            android.util.Log.i(
                "ProjectPhotoExporter",
                "project=$projectId photos=${items.size} withSidecar=${items.count { it.workspace != null }} " +
                    "format=$format watermark=${watermarkPresetName ?: "none"}",
            )
            withContext(Dispatchers.Main) { processor.startItems(items, format, wm) }
        }
    }

    fun cancel() = processor.cancel()

    /** Clear a Done/Cancelled state so the progress card disappears. */
    fun dismiss() {
        if (!isRunning) processor.reset()
    }
}
