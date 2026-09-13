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

package com.RAZStudio.StudioRoom.core.data.saving

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.documentfile.provider.DocumentFile
import coil3.ImageLoader
import com.t8rin.exif.ExifInterface
import com.RAZStudio.StudioRoom.core.data.coil.remove
import com.RAZStudio.StudioRoom.core.data.image.toMetadata
import com.RAZStudio.StudioRoom.core.data.saving.io.UriReadable
import com.RAZStudio.StudioRoom.core.data.saving.io.UriWriteable
import com.RAZStudio.StudioRoom.core.data.utils.cacheSize
import com.RAZStudio.StudioRoom.core.data.utils.clearCache
import com.RAZStudio.StudioRoom.core.data.utils.isExternalStorageWritable
import com.RAZStudio.StudioRoom.core.data.utils.openFileDescriptor
import com.RAZStudio.StudioRoom.core.domain.coroutines.AppScope
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.domain.image.Metadata
import com.RAZStudio.StudioRoom.core.domain.image.ShareProvider
import com.RAZStudio.StudioRoom.core.domain.image.clearAllAttributes
import com.RAZStudio.StudioRoom.core.domain.image.copyTo
import com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag
import com.RAZStudio.StudioRoom.core.domain.image.readOnly
import com.RAZStudio.StudioRoom.core.domain.json.JsonParser
import com.RAZStudio.StudioRoom.core.domain.resource.ResourceManager
import com.RAZStudio.StudioRoom.core.domain.saving.FileController
import com.RAZStudio.StudioRoom.core.domain.saving.FilenameCreator
import com.RAZStudio.StudioRoom.core.domain.saving.io.Writeable
import com.RAZStudio.StudioRoom.core.domain.saving.io.use
import com.RAZStudio.StudioRoom.core.domain.saving.model.ImageSaveTarget
import com.RAZStudio.StudioRoom.core.domain.saving.model.SaveResult
import com.RAZStudio.StudioRoom.core.domain.saving.model.SaveTarget
import com.RAZStudio.StudioRoom.core.domain.utils.FileMode
import com.RAZStudio.StudioRoom.core.domain.utils.runSuspendCatching
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.settings.domain.SettingsManager
import com.RAZStudio.StudioRoom.core.settings.domain.model.CopyToClipboardMode
import com.RAZStudio.StudioRoom.core.settings.domain.model.FilenameBehavior
import com.RAZStudio.StudioRoom.core.settings.domain.model.OneTimeSaveLocation
import com.RAZStudio.StudioRoom.core.utils.fileSize
import com.RAZStudio.StudioRoom.core.utils.filename
import com.RAZStudio.StudioRoom.core.utils.getPath
import com.RAZStudio.StudioRoom.core.utils.listFilesInDirectory
import com.RAZStudio.StudioRoom.core.utils.listFilesInDirectoryProgressive
import com.RAZStudio.StudioRoom.core.utils.makeLog
import com.RAZStudio.StudioRoom.core.utils.tryExtractOriginal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.reflect.KClass


internal class AndroidFileController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
    private val shareProvider: ShareProvider,
    private val filenameCreator: FilenameCreator,
    private val jsonParser: JsonParser,
    private val appScope: AppScope,
    private val dataStore: DataStore<Preferences>,
    private val imageLoader: ImageLoader,
    dispatchersHolder: DispatchersHolder,
    resourceManager: ResourceManager,
) : DispatchersHolder by dispatchersHolder,
    ResourceManager by resourceManager,
    FileController {

    private val _settingsState = settingsManager.settingsState
    private val settingsState get() = _settingsState.value

    override fun getSize(uri: String): Long? = uri.toUri().fileSize()

    override val defaultSavingPath: String
        get() = settingsState.saveFolderUri.getPath(context)

    override suspend fun save(
        saveTarget: SaveTarget,
        keepOriginalMetadata: Boolean,
        oneTimeSaveLocationUri: String?,
    ): SaveResult {
        val result = saveImpl(
            saveTarget = saveTarget,
            keepOriginalMetadata = keepOriginalMetadata,
            oneTimeSaveLocationUri = oneTimeSaveLocationUri
        )

        Triple(
            first = result,
            second = keepOriginalMetadata,
            third = oneTimeSaveLocationUri
        ).makeLog("File Controller save")

        return result
    }

    private suspend fun saveImpl(
        saveTarget: SaveTarget,
        keepOriginalMetadata: Boolean,
        oneTimeSaveLocationUri: String?,
    ): SaveResult = withContext(ioDispatcher) {
        if (!context.isExternalStorageWritable()) {
            return@withContext SaveResult.Error.MissingPermissions
        }

        val data = if (saveTarget is ImageSaveTarget && saveTarget.readFromUriInsteadOfData) {
            readBytes(saveTarget.originalUri)
        } else {
            saveTarget.data
        }

        val savingPath = oneTimeSaveLocationUri?.getPath(context) ?: defaultSavingPath

        runSuspendCatching {
            if (settingsState.copyToClipboardMode is CopyToClipboardMode.Enabled) {
                val clipboardManager = context.getSystemService<ClipboardManager>()

                shareProvider.cacheByteArray(
                    byteArray = data,
                    filename = filenameCreator.constructRandomFilename(saveTarget.extension)
                )?.toUri()?.let { uri ->
                    clipboardManager?.setPrimaryClip(
                        ClipData.newUri(
                            context.contentResolver,
                            "IMAGE",
                            uri
                        )
                    )
                }
            }

            if (settingsState.copyToClipboardMode is CopyToClipboardMode.Enabled.WithoutSaving) {
                return@withContext SaveResult.Success(
                    message = getString(R.string.copied),
                    savingPath = savingPath
                )
            }

            val originalUri = saveTarget.originalUri.toUri()

            if (saveTarget is ImageSaveTarget && saveTarget.canSkipIfLarger && settingsState.allowSkipIfLarger) {
                val originalSize = originalUri.fileSize()
                val newSize = data.size

                if (originalSize != null && newSize > originalSize) {
                    return@withContext SaveResult.Skipped(saveTarget.originalUri)
                }
            }

            if (settingsState.filenameBehavior is FilenameBehavior.Overwrite) {
                val providedMetadata = (saveTarget as? ImageSaveTarget)?.metadata
                val targetMetadata = if (keepOriginalMetadata) {
                    readMetadata(originalUri.toString()) ?: providedMetadata
                } else {
                    providedMetadata
                }

                runCatching {
                    if (originalUri == Uri.EMPTY) throw IllegalStateException()

                    context.openFileDescriptor(
                        uri = originalUri,
                        mode = FileMode.WriteTruncate
                    )
                }.getOrNull()?.use { parcel ->
                    FileOutputStream(parcel.fileDescriptor).use { out ->
                        out.write(data)

                        copyMetadata(
                            initialExif = targetMetadata,
                            fileUri = originalUri,
                            keepOriginalMetadata = keepOriginalMetadata,
                            originalUri = originalUri
                        )
                    }

                    imageLoader.apply {
                        memoryCache?.remove(originalUri.toString())
                        diskCache?.remove(originalUri.toString())
                    }

                    return@withContext SaveResult.Success(
                        message = getString(
                            R.string.saved_to_original,
                            originalUri.filename(context).toString()
                        ),
                        isOverwritten = true,
                        savingPath = savingPath,
                        savedUri = originalUri.toString(),
                    )
                }
            } else {
                var folderWasReset = false
                val requestedTreeUri = (oneTimeSaveLocationUri ?: settingsState.saveFolderUri).takeIf {
                    !it.isNullOrEmpty()
                }

                var documentFile: DocumentFile? = if (requestedTreeUri != null) {
                    runCatching {
                        requestedTreeUri.toUri().let {
                            if (DocumentFile.isDocumentUri(context, it)) {
                                DocumentFile.fromSingleUri(context, it)
                            } else DocumentFile.fromTreeUri(context, it)
                        }
                    }.getOrNull()
                } else null

                var treeUri = requestedTreeUri

                if (requestedTreeUri != null && (documentFile == null || documentFile?.exists() != true)) {
                    // The configured folder is gone — a stale / revoked SAF grant.
                    // This is the typical fresh-install / reinstall case: the saved
                    // URI string survives in settings but the persisted permission
                    // does not. Rather than FAIL the save (which silently loses
                    // this file and only the *next* one falls back), drop the dead
                    // location and fall back to the default folder right here, and
                    // flag a one-time notice so the UI can tell the user to re-pick
                    // it. AndroidFileController is the authority and "takes over"
                    // with the default whenever the saved field is invalid.
                    if (oneTimeSaveLocationUri == null) {
                        settingsManager.setSaveFolderUri(null)
                    } else {
                        settingsManager.setOneTimeSaveLocations(
                            settingsState.oneTimeSaveLocations.let { locations ->
                                (locations - locations.find { it.uri == oneTimeSaveLocationUri }).filterNotNull()
                            }
                        )
                    }
                    folderWasReset = true
                    treeUri = null
                    documentFile = null
                }

                var initialExif: Metadata? = null

                val newSaveTarget = if (saveTarget is ImageSaveTarget) {
                    initialExif = saveTarget.metadata

                    saveTarget.copy(
                        filename = filenameCreator.constructImageFilename(
                            saveTarget = saveTarget,
                            forceNotAddSizeInFilename = saveTarget.imageInfo.height <= 0 || saveTarget.imageInfo.width <= 0
                        )
                    )
                } else saveTarget

                val savingFolder = SavingFolder.getInstance(
                    context = context,
                    treeUri = treeUri?.toUri(),
                    saveTarget = newSaveTarget
                ) ?: throw IllegalArgumentException(getString(R.string.error_while_saving))

                savingFolder.use {
                    it.writeBytes(data)
                }

                copyMetadata(
                    initialExif = initialExif,
                    fileUri = savingFolder.fileUri,
                    keepOriginalMetadata = keepOriginalMetadata,
                    originalUri = saveTarget.originalUri.toUri()
                )

                val filename = newSaveTarget.filename
                    ?: throw IllegalArgumentException(getString(R.string.filename_is_not_set))

                oneTimeSaveLocationUri?.let {
                    if (documentFile?.isDirectory == true) {
                        val currentLocation =
                            settingsState.oneTimeSaveLocations.find { it.uri == oneTimeSaveLocationUri }

                        settingsManager.setOneTimeSaveLocations(
                            currentLocation?.let {
                                settingsState.oneTimeSaveLocations.toMutableList().apply {
                                    remove(currentLocation)
                                    add(
                                        currentLocation.copy(
                                            uri = oneTimeSaveLocationUri,
                                            date = System.currentTimeMillis(),
                                            count = currentLocation.count + 1
                                        )
                                    )
                                }
                            } ?: settingsState.oneTimeSaveLocations.plus(
                                OneTimeSaveLocation(
                                    uri = oneTimeSaveLocationUri,
                                    date = System.currentTimeMillis(),
                                    count = 1
                                )
                            )
                        )
                    }
                }

                // When we fell back, the real destination is the default folder,
                // not the (now-cleared) configured path computed up top.
                val effectiveSavingPath = if (folderWasReset) "".getPath(context) else savingPath

                val baseMessage = if (effectiveSavingPath.isNotEmpty()) {
                    val isFile =
                        (documentFile?.isDirectory != true && oneTimeSaveLocationUri != null && !folderWasReset)
                    if (isFile) {
                        getString(R.string.saved_to_custom)
                    } else if (filename.isNotEmpty()) {
                        getString(
                            R.string.saved_to,
                            effectiveSavingPath,
                            filename
                        )
                    } else {
                        getString(
                            R.string.saved_to_without_filename,
                            effectiveSavingPath
                        )
                    }
                } else null

                return@withContext SaveResult.Success(
                    message = if (folderWasReset && baseMessage != null) {
                        "$baseMessage — your chosen folder was unavailable and was reset to default"
                    } else baseMessage,
                    savingPath = effectiveSavingPath,
                    savedToFallbackFolder = folderWasReset,
                    savedUri = savingFolder.fileUri.toString(),
                )
            }
        }.onFailure {
            return@withContext SaveResult.Error.Exception(it)
        }

        SaveResult.Error.Exception(
            SaveException(
                message = getString(R.string.something_went_wrong)
            )
        )
    }

    override fun clearCache(
        onComplete: (Long) -> Unit,
    ) {
        appScope.launch {
            context.clearCache()
            onComplete(getCacheSize())
            "cache cleared".makeLog("AndroidFileController")
        }
    }

    override fun getCacheSize(): Long = context.cacheSize()

    override suspend fun readBytes(
        uri: String,
    ): ByteArray = withContext(ioDispatcher) {
        runSuspendCatching {
            context.contentResolver.openInputStream(uri.toUri())?.use {
                it.buffered().readBytes()
            }
        }.onFailure {
            uri.makeLog("File Controller read")
            it.makeLog("File Controller read")
        }.getOrNull() ?: ByteArray(0)
    }

    override suspend fun writeBytes(
        uri: String,
        block: suspend (Writeable) -> Unit,
    ): SaveResult = withContext(ioDispatcher) {
        runSuspendCatching {
            block(
                UriWriteable(
                    uri = uri.toUri(),
                    context = context
                )
            )
        }.onSuccess {
            return@withContext SaveResult.Success(
                message = null,
                savingPath = ""
            )
        }.onFailure {
            uri.makeLog("File Controller write")
            it.makeLog("File Controller write")
            return@withContext SaveResult.Error.Exception(it)
        }

        return@withContext SaveResult.Error.Exception(IllegalStateException())
    }

    override suspend fun transferBytes(
        fromUri: String,
        toUri: String
    ): SaveResult = transferBytes(
        fromUri = fromUri,
        to = UriWriteable(
            uri = toUri.toUri(),
            context = context
        )
    )

    override suspend fun transferBytes(
        fromUri: String,
        to: Writeable
    ): SaveResult = withContext(ioDispatcher) {
        runSuspendCatching {
            UriReadable(
                uri = fromUri.toUri(),
                context = context
            ).copyTo(to)
        }.onSuccess {
            return@withContext SaveResult.Success(
                message = null,
                savingPath = ""
            )
        }.onFailure {
            to.makeLog("File Controller write")
            it.makeLog("File Controller write")
            return@withContext SaveResult.Error.Exception(it)
        }

        return@withContext SaveResult.Error.Exception(IllegalStateException())
    }

    override suspend fun <O : Any> saveObject(
        key: String,
        value: O,
    ): Boolean = withContext(ioDispatcher) {
        "saveObject value = $value".makeLog(key)
        runCatching {
            dataStore.edit {
                it[stringPreferencesKey("fast_$key")] =
                    jsonParser.toJson(value, value::class.java)!!
            }
        }.onSuccess {
            "saveObject success".makeLog(key)
            return@withContext true
        }.onFailure {
            it.makeLog("saveObject $key")
            return@withContext false
        }

        return@withContext false
    }

    override suspend fun <O : Any> restoreObject(
        key: String,
        kClass: KClass<O>,
    ): O? = withContext(ioDispatcher) {
        runCatching {
            "restoreObject".makeLog(key)
            jsonParser.fromJson<O>(
                json = dataStore.data.first()[stringPreferencesKey("fast_$key")].orEmpty(),
                type = kClass.java
            )
        }.onFailure {
            it.makeLog("restoreObject $key")
        }.onSuccess {
            "restoreObject success value = $it".makeLog(key)
        }.getOrNull()
    }

    override suspend fun writeMetadata(
        imageUri: String,
        metadata: Metadata?
    ) {
        copyMetadata(
            initialExif = metadata,
            fileUri = imageUri.toUri(),
            keepOriginalMetadata = false,
            originalUri = imageUri.toUri()
        )
    }

    override suspend fun readMetadata(
        imageUri: String
    ): Metadata? = runSuspendCatching {
        val uri = imageUri.toUri()
        if (uri.scheme == "file") {
            val path = uri.path ?: return@runSuspendCatching null
            ExifInterface(path).toMetadata().readOnly().makeLog("readMetadata")
        } else {
            context.contentResolver.openInputStream(uri.tryExtractOriginal())?.use {
                ExifInterface(it).toMetadata().readOnly().makeLog("readMetadata")
            }
        }
    }.getOrNull()

    override suspend fun listFilesInDirectory(
        treeUri: String
    ): List<String> = withContext(ioDispatcher) {
        treeUri.toUri().listFilesInDirectory().map { it.toString() }
    }

    override fun listFilesInDirectoryAsFlow(
        treeUri: String
    ): Flow<String> = treeUri.toUri().listFilesInDirectoryProgressive().map {
        it.toString()
    }.flowOn(ioDispatcher)

    private suspend fun copyMetadata(
        initialExif: Metadata?,
        fileUri: Uri,
        keepOriginalMetadata: Boolean,
        originalUri: Uri
    ) = runSuspendCatching {
        if (initialExif != null) {
            openFileDescriptor(fileUri)?.use {
                initialExif.makeLog("initialMetadata")
                    .copyTo(it.fileDescriptor.toMetadata().makeLog("dstMetadata"))
            }
        } else if (keepOriginalMetadata) {
            if (fileUri != originalUri) {
                openFileDescriptor(fileUri)?.use {
                    readMetadata(originalUri.toString()).makeLog("srcMetadata")?.copyTo(
                        it.fileDescriptor.toMetadata().makeLog("dstMetadata")
                    )
                }
            } else {
                "Nothing, copying from self to self is pointless".makeLog("copyMetadata")
            }
        } else {
            openFileDescriptor(fileUri)?.use {
                it.fileDescriptor.toMetadata().apply {
                    clearAllAttributes()

                    if (settingsState.keepDateTime) {
                        readMetadata(originalUri.toString()).makeLog("srcMetadata")
                            ?.copyTo(
                                metadata = this,
                                tags = MetadataTag.dateEntries
                            )
                    } else {
                        saveAttributes()
                    }
                }
            }.makeLog("metadataCleared")
        }
    }

    private fun openFileDescriptor(
        imageUri: Uri
    ) = context.openFileDescriptor(
        uri = imageUri.tryExtractOriginal(),
        mode = FileMode.ReadWrite
    )
}