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

package com.RAZStudio.StudioRoom.feature.sd_card_browser.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.settings.domain.SettingsManager
import com.RAZStudio.StudioRoom.core.utils.AppLog
import com.RAZStudio.StudioRoom.feature.canon_sync.data.SafCaptureTarget
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.CaptureTarget
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.FileHandle
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.FolderEntry
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.OpenState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.InputStream
import javax.inject.Inject

private const val TAG = "SdCardFileOpener"

/**
 * Copies the selected photo from the SD card into the app's Default Output
 * folder and hands the resulting SAF Uri to the RAW editor.
 *
 * This is deliberately the *same* write pipeline Canon Sync's
 * `CanonSyncRepository.downloadCameraPhoto` uses ([SafCaptureTarget]: staging
 * `.part` file + atomic rename + buffered SAF sink, session-dated subfolder
 * under the one shared Default Output folder). The two import features are
 * only different at the source-read end: Canon Sync streams camera bytes
 * over PTP/IP Wi-Fi; this reads from a raw USB Mass Storage device or a
 * SAF-mounted card. Once bytes leave the source, both pipelines are
 * identical — same destination, same staging/rename, same Uri type handed
 * to `RawEditor`. No sidecar files are copied or resolved.
 */
class FileOpener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
    private val dispatchers: DispatchersHolder,
) {

    fun openCr2(
        cr2Entry: FolderEntry.Cr2File,
        parentTreeUri: Uri?,
        volumeRoot: FileHandle,
    ): Flow<OpenState> = flow {
        val target = resolveCaptureTarget()
        if (target == null) {
            AppLog.w(TAG, "openCr2: no Default Output folder set")
            emit(OpenState.Error("Set a Default Output folder in Settings first", Uri.EMPTY))
            return@flow
        }

        emit(OpenState.Blending(0f))

        val source: InputStream? = when (val handle = cr2Entry.handle) {
            is FileHandle.Saf -> context.contentResolver.openInputStream(handle.documentFile.uri)
            is FileHandle.Usb -> handle.openInputStream()
        }
        if (source == null) {
            AppLog.e(TAG, "openCr2: could not open source stream for '${cr2Entry.name}'")
            emit(OpenState.Error("Failed to read '${cr2Entry.name}' from card", Uri.EMPTY))
            return@flow
        }

        val finalUri = withContext(dispatchers.ioDispatcher) {
            val allocation = runCatching {
                target.open(requestedFilename = cr2Entry.name, mimeType = CR2_MIME_TYPE)
            }.getOrElse { e ->
                AppLog.e(TAG, "openCr2: could not allocate sink for '${cr2Entry.name}'", e)
                source.runCatching { close() }
                null
            } ?: return@withContext null

            runCatching {
                source.use { input ->
                    input.source().buffer().use { okioSource ->
                        allocation.sink.writeAll(okioSource)
                    }
                }
                allocation.finalize()
            }.getOrElse { e ->
                AppLog.e(TAG, "openCr2: copy failed for '${cr2Entry.name}'", e)
                allocation.runCatching { discard() }
                null
            }
        }

        if (finalUri == null) {
            emit(OpenState.Error("Failed to copy '${cr2Entry.name}'", Uri.EMPTY))
            return@flow
        }

        emit(OpenState.Blending(1f))
        AppLog.i(TAG, "openCr2: ready '${cr2Entry.name}' at $finalUri")
        emit(OpenState.Ready(finalUri, sidecarUri = null))
    }.flowOn(dispatchers.defaultDispatcher)

    /**
     * Same session-subfolder convention as Canon Sync's
     * `ensureSessionSubfolder`/`makeTargetForArmedFolder` (`"{Source}-yyyyMMdd"`
     * under the shared Default Output folder), just with a fixed "SDCard"
     * label in place of the sanitized camera model name.
     */
    private suspend fun resolveCaptureTarget(): CaptureTarget? {
        val rootUriString = settingsManager.settingsState.first().saveFolderUri
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val rootUri = runCatching { Uri.parse(rootUriString) }.getOrNull()
            ?.takeIf { it.scheme == "content" }
            ?: return null

        return withContext(dispatchers.ioDispatcher) {
            val root = runCatching { DocumentFile.fromTreeUri(context, rootUri) }.getOrNull()
                ?: return@withContext null
            if (!root.isDirectory || !root.canWrite()) return@withContext null

            val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(java.util.Date())
            val subName = "SDCard-$date"
            val sub = root.findFile(subName)?.takeIf { it.isDirectory }
                ?: root.createDirectory(subName)
                ?: run {
                    AppLog.w(TAG, "resolveCaptureTarget: createDirectory('$subName') failed, using root")
                    root
                }
            SafCaptureTarget.forDirectory(context, sub.uri)
        }
    }

    private companion object {
        const val CR2_MIME_TYPE = "image/x-canon-cr2"
    }
}
