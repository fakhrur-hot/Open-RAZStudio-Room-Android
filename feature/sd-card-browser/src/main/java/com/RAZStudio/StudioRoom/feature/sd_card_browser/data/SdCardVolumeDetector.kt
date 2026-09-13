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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.FileHandle
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.SdCardVolume
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects mounted SD card volumes via StorageManager enumeration and regex matching.
 * Uses MANAGE_EXTERNAL_STORAGE permission (already granted) to enumerate volumes.
 */
@Singleton
class SdCardVolumeDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        val VOLUME_REGEX = ".*[0-9a-f]{4}-[0-9a-f]{4}".toRegex(RegexOption.IGNORE_CASE)
    }

    /**
     * Returns the first mounted volume whose path matches the SD card regex,
     * or null if none found.
     */
    fun detectSdCardVolume(): SdCardVolume.Saf? {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val volumes = storageManager.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED }

        for (volume in volumes) {
            val path = volume.directory?.absolutePath ?: continue
            if (path.lowercase().matches(VOLUME_REGEX)) {
                val treeUri = buildSafTreeUri(volume)
                if (treeUri == Uri.EMPTY) continue
                val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: continue
                if (docFile.isDirectory) {
                    return SdCardVolume.Saf(path, treeUri, FileHandle.Saf(docFile))
                }
            }
        }
        return null
    }

    /**
     * Observes volume mount/unmount events and re-enumerates.
     * Emits null when no SD card is present.
     */
    fun observeVolume(): Flow<SdCardVolume.Saf?> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(detectSdCardVolume())
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }

        context.registerReceiver(receiver, filter)

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }.onStart {
        emit(detectSdCardVolume())
    }

    /**
     * Builds a SAF tree URI from a StorageVolume's UUID.
     * Returns [Uri.EMPTY] if the volume has no UUID.
     */
    private fun buildSafTreeUri(volume: StorageVolume): Uri {
        val uuid = volume.uuid ?: return Uri.EMPTY
        return DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "$uuid:"
        )
    }
}
