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

package com.RAZStudio.StudioRoom.feature.sd_card_browser.domain

import androidx.documentfile.provider.DocumentFile
import me.jahnen.libaums.core.fs.UsbFile
import java.io.InputStream

/**
 * Uniform read-only file-tree node so the browser can operate over either a
 * SAF-mounted [DocumentFile] volume (native StorageManager auto-mount) or a
 * raw USB Mass Storage device opened directly via libaums — needed because
 * this hardware doesn't auto-mount OTG card readers as a StorageVolume, so
 * the OS never hands us a SAF tree for them (see the USB chooser
 * investigation this type was introduced to resolve).
 */
sealed interface FileHandle {
    val name: String
    val isDirectory: Boolean

    fun listChildren(): List<FileHandle>
    fun openInputStream(): InputStream?

    data class Saf(val documentFile: DocumentFile) : FileHandle {
        override val name: String get() = documentFile.name ?: ""
        override val isDirectory: Boolean get() = documentFile.isDirectory

        override fun listChildren(): List<FileHandle> =
            documentFile.listFiles().map { Saf(it) }

        override fun openInputStream(): InputStream? = null // SAF path reads via ContentResolver + Uri instead

        fun findChildCi(childName: String): Saf? =
            documentFile.listFiles().firstOrNull { it.name?.equals(childName, ignoreCase = true) == true }
                ?.let { Saf(it) }
    }

    data class Usb(val usbFile: UsbFile) : FileHandle {
        override val name: String get() = usbFile.name
        override val isDirectory: Boolean get() = usbFile.isDirectory

        override fun listChildren(): List<FileHandle> =
            runCatching { usbFile.listFiles().map { Usb(it) } }.getOrDefault(emptyList())

        override fun openInputStream(): InputStream =
            me.jahnen.libaums.core.fs.UsbFileInputStream(usbFile)

        fun findChildCi(childName: String): Usb? =
            listChildren().filterIsInstance<Usb>()
                .firstOrNull { it.usbFile.name.equals(childName, ignoreCase = true) }
    }
}
