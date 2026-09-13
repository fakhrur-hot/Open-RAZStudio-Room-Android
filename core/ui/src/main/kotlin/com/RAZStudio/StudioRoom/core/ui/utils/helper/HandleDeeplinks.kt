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

package com.RAZStudio.StudioRoom.core.ui.utils.helper

import android.content.Intent
import android.net.Uri
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.domain.BACKUP_FILE_EXT
import com.RAZStudio.StudioRoom.core.domain.TEMPLATE_EXT
import com.RAZStudio.StudioRoom.core.domain.model.ExtraDataType
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.Error
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ContextUtils.getScreenExtra
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ContextUtils.getScreenOpeningShortcut
import com.RAZStudio.StudioRoom.core.ui.utils.helper.IntentUtils.parcelable
import com.RAZStudio.StudioRoom.core.ui.utils.helper.IntentUtils.parcelableArrayList
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.core.utils.appContext
import com.RAZStudio.StudioRoom.core.utils.filename

fun Intent?.handleDeeplinks(
    onStart: () -> Unit,
    onColdStart: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onGetUris: (List<Uri>) -> Unit,
    onHasExtraDataType: (ExtraDataType) -> Unit,
    isHasUris: Boolean,
    onWantGithubReview: () -> Unit,
    isOpenEditInsteadOfPreview: Boolean,
) {
    val intent = this ?: return

    onStart()
    val type = intent.type
    if (type != null && !isHasUris) onColdStart()

    val action = intent.action

    if (action == Intent.ACTION_BUG_REPORT) {
        onWantGithubReview()
        return
    }

    // Sony camera NFC one-touch: the A7 II N-Mark tag fires NDEF_DISCOVERED with
    // MIME application/x-sony-pmm. Jump straight to the Sony Sync page, mirroring
    // how PlayMemories opens its Wi-Fi connect screen on tap. (The tag's NDEF
    // Wi-Fi credentials ride in EXTRA_NDEF_MESSAGES for a future auto-join; the
    // page's connect flow handles the join for now.)
    if (action == "android.nfc.action.NDEF_DISCOVERED" &&
        type == "application/x-sony-pmm"
    ) {
        onNavigate(Screen.SonySync)
        return
    }

    if (intent.getScreenOpeningShortcut(onNavigate)) return

    val data = intent.data
    val clipData = intent.clipData

    runCatching {
        fun String?.isEndsWith(ext: String): Boolean = this?.lowercase().orEmpty().endsWith(ext)

        fun Uri?.isMarkupProject(): Boolean = this?.toString().isEndsWith(".itp") ||
                this?.filename().isEndsWith(".itp")

        fun Uri?.isTemplate(): Boolean = this?.toString().isEndsWith(TEMPLATE_EXT) ||
                this?.filename().isEndsWith(TEMPLATE_EXT)

        val startsWithImage = type?.startsWith("image/") == true
        val hasExtraFormats = clipData?.clipList()
            ?.any {
                it.toString().endsWith(".jxl") ||
                        it.toString().endsWith(".qoi") ||
                        it.toString().endsWith(".itp")
            } == true

        // MIME type is not a reliable way to recognise a photo on the way in.
        // Plenty of file managers and cloud apps share a RAW (and sometimes a
        // TIFF) as "application/octet-stream" or "*/*", which lands in the
        // generic `type != null` branch far below and skips the image handling
        // entirely. Recognising by EXTENSION as well means every format the
        // editor can actually open takes the same route.
        //
        // Kept local to this file on purpose: the canonical list lives in
        // photo-editor's sourceFormatOf(), and core/ui cannot depend on a
        // feature module. Keep the two in sync when adding a format.
        fun Uri?.isPhotoFile(): Boolean {
            val name = (this?.filename() ?: this?.toString().orEmpty()).lowercase()
            val ext = name.substringAfterLast('.', "")
            return ext in setOf(
                // raster
                "jpg", "jpeg", "jpe", "png", "webp", "bmp", "tif", "tiff",
                "heic", "heif", "avif", "jxl", "qoi", "gif",
                // LibRaw-supported RAW
                "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "sr2", "srf",
                "raf", "rw2", "raw", "orf", "pef", "srw", "x3f", "erf", "3fr",
                "fff", "dcr", "k25", "kdc", "mrw", "rwl", "mef", "iiq", "ari",
                "r3d", "gpr", "braw",
            )
        }

        // For SEND / SEND_MULTIPLE the payload is in EXTRA_STREAM, not in
        // intent.data, so the extension test has to look there.
        val streamUris: List<Uri> = when (action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.parcelableArrayList<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        val streamIsPhoto = streamUris.isNotEmpty() && streamUris.all { it.isPhotoFile() }
        val clipIsPhoto = clipData?.clipList()?.let { list ->
            list.isNotEmpty() && list.all { it.isPhotoFile() }
        } == true
        val dataHasExtraFormats = data.toString().let {
            it.endsWith(".jxl") || it.endsWith(".qoi") || it.endsWith(".itp")
        }

        when {
            data.isTemplate() -> onHasExtraDataType(ExtraDataType.Template(data.toString()))

            data.isMarkupProject() -> onNavigate(Screen.MarkupLayers(data))

            // `streamIsPhoto` / `clipIsPhoto` / `data.isPhotoFile()` are what let a
            // RAW or TIFF shared as application/octet-stream reach the image
            // handling instead of falling through to the generic file branch.
            startsWithImage || hasExtraFormats || dataHasExtraFormats ||
                    streamIsPhoto || clipIsPhoto || data.isPhotoFile() -> {
                when (action) {
                    Intent.ACTION_VIEW -> {
                        val uris =
                            clipData?.clipList() ?: data?.let { listOf(it) } ?: return@runCatching

                        if (isOpenEditInsteadOfPreview) {
                            onGetUris(uris)
                        } else {
                            onNavigate(Screen.ImagePreview(uris))
                        }
                    }

                    Intent.ACTION_SEND -> {
                        intent.parcelable<Uri>(Intent.EXTRA_STREAM)?.let {
                            when (intent.getScreenExtra()) {
                                is Screen.PickColorFromImage -> onNavigate(
                                    Screen.PickColorFromImage(
                                        it
                                    )
                                )

                                is Screen.PaletteTools -> onNavigate(Screen.PaletteTools(it))
                                else -> {
                                    if (type?.contains("gif") == true) {
                                        // GIFs keep going straight to the editor:
                                        // ExtraDataType.Gif arms a dedicated editor
                                        // flow, and routing via preview would set
                                        // that flag with no URIs handed over.
                                        onHasExtraDataType(ExtraDataType.Gif)
                                        onGetUris(listOf(it))
                                    } else if (isOpenEditInsteadOfPreview) {
                                        onGetUris(listOf(it))
                                    } else {
                                        // Share-to-app now lands on Image Preview
                                        // first, the same as ACTION_VIEW. Before,
                                        // SEND ignored openEditInsteadOfPreview
                                        // altogether and always jumped into the
                                        // editor, so the setting appeared to do
                                        // nothing for the share flow while VIEW
                                        // respected it.
                                        onNavigate(Screen.ImagePreview(listOf(it)))
                                    }
                                }
                            }
                        }
                    }

                    Intent.ACTION_SEND_MULTIPLE -> {
                        intent.parcelableArrayList<Uri>(Intent.EXTRA_STREAM)?.let {
                            if (type?.contains("gif") == true) {
                                onHasExtraDataType(ExtraDataType.Gif)
                                it.firstOrNull()?.let { uri ->
                                    onGetUris(listOf(uri))
                                }
                            } else if (isOpenEditInsteadOfPreview) {
                                onGetUris(it)
                            } else {
                                // Same rule as single-image SEND above; Image
                                // Preview already takes a list, and a multi-image
                                // share is exactly where seeing what arrived
                                // before committing to an edit is most useful.
                                onNavigate(Screen.ImagePreview(it))
                            }
                        }
                    }

                    Intent.ACTION_EDIT,
                    Intent.ACTION_INSERT,
                    Intent.ACTION_INSERT_OR_EDIT -> {
                        val uris =
                            clipData?.clipList() ?: data?.let { listOf(it) } ?: return@runCatching
                        if (type?.contains("gif") == true) {
                            onHasExtraDataType(ExtraDataType.Gif)
                        }
                        onGetUris(uris)
                    }

                    else -> {
                        data?.let {
                            if (type?.contains("gif") == true) {
                                onHasExtraDataType(ExtraDataType.Gif)
                            }
                            onGetUris(listOf(it))
                        }
                    }
                }
            }

            type != null -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)

                if (text != null) {
                    onHasExtraDataType(ExtraDataType.Text(text))
                    onGetUris(listOf())
                } else {
                    val isPdf = type.contains("pdf")
                    val isAudio = type.startsWith("audio/")

                    when (action) {
                        Intent.ACTION_SEND_MULTIPLE -> {
                            intent.parcelableArrayList<Uri>(Intent.EXTRA_STREAM)?.let {
                                when {
                                    isAudio -> {
                                        onHasExtraDataType(ExtraDataType.Audio)
                                        onGetUris(it)
                                    }

                                    isPdf -> {
                                        onHasExtraDataType(ExtraDataType.Pdf)
                                        onGetUris(it)
                                    }

                                    else -> onNavigate(Screen.Zip(it))
                                }
                            }
                        }

                        Intent.ACTION_SEND -> {
                            intent.parcelable<Uri>(Intent.EXTRA_STREAM)?.let {
                                if (it.isMarkupProject()) {
                                    onNavigate(Screen.MarkupLayers(it))
                                    return
                                }

                                if (it.isTemplate()) {
                                    onHasExtraDataType(ExtraDataType.Template(it.toString()))
                                    return
                                }

                                if (it.toString().contains(BACKUP_FILE_EXT, true)) {
                                    onHasExtraDataType(ExtraDataType.Backup(it.toString()))
                                    return
                                }
                                when {
                                    isAudio -> onHasExtraDataType(ExtraDataType.Audio)
                                    isPdf -> onHasExtraDataType(ExtraDataType.Pdf)
                                    else -> onHasExtraDataType(ExtraDataType.File)
                                }

                                onGetUris(listOf(it))
                            }
                        }

                        Intent.ACTION_VIEW -> {
                            val uris =
                                clipData?.clipList() ?: data?.let { listOf(it) }
                                ?: listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))

                            if (uris.size == 1) {
                                val uri = uris.first()

                                if (uri.isMarkupProject()) {
                                    onNavigate(Screen.MarkupLayers(uri))
                                    return
                                }

                                if (uri.isTemplate()) {
                                    onHasExtraDataType(ExtraDataType.Template(uri.toString()))
                                    return
                                }

                                if (uri.toString().contains(BACKUP_FILE_EXT, true)) {
                                    onHasExtraDataType(ExtraDataType.Backup(uri.toString()))
                                    return
                                }

                                when {
                                    isPdf -> {
                                        onNavigate(Screen.PdfTools.Preview(uri))
                                        return
                                    }

                                    isAudio -> {
                                        onHasExtraDataType(ExtraDataType.Audio)
                                    }

                                    else -> {
                                        onHasExtraDataType(ExtraDataType.File)
                                    }
                                }

                                onGetUris(uris)
                            } else if (uris.isNotEmpty()) {
                                when {
                                    isPdf -> {
                                        onHasExtraDataType(ExtraDataType.Pdf)
                                        onGetUris(uris)
                                    }

                                    isAudio -> {
                                        onHasExtraDataType(ExtraDataType.Audio)
                                        onGetUris(uris)
                                    }

                                    else -> {
                                        onNavigate(Screen.Zip(uris))
                                    }
                                }
                            } else {
                                Unit
                            }
                        }

                        else -> null
                    } ?: AppToastHost.showToast(
                        message = appContext.getString(R.string.unsupported_type, type),
                        icon = Icons.Outlined.Error
                    )
                }
            }

            else -> Unit
        }
    }.getOrNull() ?: AppToastHost.showToast(
        message = appContext.getString(R.string.something_went_wrong),
        icon = Icons.Outlined.Error
    )
}
