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

package com.RAZStudio.StudioRoom.core.ui.widget.utils

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.RAZStudio.StudioRoom.core.domain.model.ExtraDataType
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ContextUtils.getExtension
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen

@Composable
internal fun List<Uri>.screenList(
    extraDataType: ExtraDataType?
): State<Pair<List<Screen>, List<Screen>>> {
    val uris = this

    fun Uri?.type(
        vararg extensions: String
    ): Boolean {
        if (this == null) return false

        val extension = getExtension() ?: return false

        return extensions.any(extension::contains)
    }

    val filesAvailableScreens by remember(uris) {
        derivedStateOf {
            if (uris.size > 1) {
                listOf(Screen.Zip(uris))
            } else {
                listOf(
                    Screen.Cipher(uris.firstOrNull()),
                    Screen.ChecksumTools(uris.firstOrNull()),
                    Screen.Zip(uris)
                )
            }
        }
    }
    val audioAvailableScreens by remember(uris) {
        derivedStateOf {
            listOf(
                Screen.AudioCoverExtractor(uris)
            ) + filesAvailableScreens
        }
    }
    val gifAvailableScreens by remember(uris) {
        derivedStateOf {
            listOf(
                Screen.GifTools(
                    Screen.GifTools.Type.GifToImage(
                        uris.firstOrNull()
                    )
                ),
                Screen.GifTools(
                    Screen.GifTools.Type.GifToJxl(uris)
                ),
                Screen.GifTools(
                    Screen.GifTools.Type.GifToWebp(uris)
                )
            ) + filesAvailableScreens
        }
    }
    val pdfAvailableScreens by remember(uris) {
        derivedStateOf {
            val multiplePdf = Screen.PdfTools.Merge(uris.takeIf { it.isNotEmpty() })

            val pdfScreens = if (uris.size == 1) {
                listOf(
                    Screen.PdfTools.Preview(
                        uris.firstOrNull()
                    ),
                    Screen.PdfTools.ExtractPages(
                        uris.firstOrNull()
                    ),
                    multiplePdf,
                    Screen.PdfTools.Split(uris.firstOrNull()),
                    Screen.PdfTools.RemovePages(uris.firstOrNull()),
                    Screen.PdfTools.Rotate(uris.firstOrNull()),
                    Screen.PdfTools.Rearrange(uris.firstOrNull()),
                    Screen.PdfTools.Crop(uris.firstOrNull()),
                    Screen.PdfTools.PageNumbers(uris.firstOrNull()),
                    Screen.PdfTools.Watermark(uris.firstOrNull()),
                    Screen.PdfTools.Signature(uris.firstOrNull()),
                    Screen.PdfTools.Compress(uris.firstOrNull()),
                    Screen.PdfTools.RemoveAnnotations(uris.firstOrNull()),
                    Screen.PdfTools.Flatten(uris.firstOrNull()),
                    Screen.PdfTools.Print(uris.firstOrNull()),
                    Screen.PdfTools.Grayscale(uris.firstOrNull()),
                    Screen.PdfTools.Repair(uris.firstOrNull()),
                    Screen.PdfTools.Protect(uris.firstOrNull()),
                    Screen.PdfTools.Unlock(uris.firstOrNull()),
                    Screen.PdfTools.Metadata(uris.firstOrNull()),
                    Screen.PdfTools.ExtractImages(uris.firstOrNull()),
                    Screen.PdfTools.OCR(uris.firstOrNull()),
                    Screen.PdfTools.ZipConvert(uris.firstOrNull()),
                )
            } else {
                listOf(multiplePdf)
            }

            pdfScreens + filesAvailableScreens
        }
    }
    // This app is a RAW-only editor — the share-sheet should not advertise the
    // dozens of legacy ImageResizerShrinker tools. Only Single RAWEditor accepts
    // a shared image, and RAWEditor is single-image so we feed only the first URI.
    // (Was a 35-item list per image arity until 2026-05-24.)
    val singleImageScreens by remember(uris) {
        derivedStateOf {
            listOf(Screen.RawEditor(uris.firstOrNull()))
        }
    }
    val multipleImageScreens by remember(uris) {
        derivedStateOf {
            listOf(Screen.RawEditor(uris.firstOrNull()))
        }
    }
    val imageScreens by remember(uris) {
        derivedStateOf {
            if (uris.size == 1) singleImageScreens
            else multipleImageScreens
        }
    }

    val textAvailableScreens by remember(extraDataType) {
        derivedStateOf {
            val text = (extraDataType as? ExtraDataType.Text)?.text ?: ""
            listOf(
                Screen.ScanQrCode(text),
                Screen.LoadNetImage(text)
            )
        }
    }

    val settingsState = LocalSettingsState.current
    val favoriteScreens = settingsState.favoriteScreenList
    val hiddenForShareScreens = settingsState.hiddenForShareScreens
    val screenOrder = settingsState.screenList

    return remember(
        favoriteScreens,
        extraDataType,
        uris,
        pdfAvailableScreens,
        audioAvailableScreens,
        imageScreens,
        hiddenForShareScreens
    ) {
        derivedStateOf {
            val baseScreens = when (extraDataType) {
                is ExtraDataType.Backup -> filesAvailableScreens
                is ExtraDataType.Template -> filesAvailableScreens
                is ExtraDataType.Text -> textAvailableScreens
                ExtraDataType.Audio -> audioAvailableScreens
                ExtraDataType.File -> filesAvailableScreens
                ExtraDataType.Gif -> gifAvailableScreens
                ExtraDataType.Pdf -> pdfAvailableScreens
                null -> imageScreens
            }

            val orderIndex = screenOrder.withIndex().associate { it.value to it.index }
            val favSet = favoriteScreens.toSet()

            val allScreens = baseScreens
                .sortedWith(
                    compareByDescending<Screen> { it.id in favSet }
                        .thenBy { orderIndex[it.id] ?: Int.MAX_VALUE }
                )

            allScreens.partition { it.id !in hiddenForShareScreens }
        }
    }
}