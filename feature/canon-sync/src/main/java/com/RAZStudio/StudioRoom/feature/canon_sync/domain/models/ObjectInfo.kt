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

package com.RAZStudio.StudioRoom.feature.canon_sync.domain.models

/**
 * Minimal projection of the PTP ObjectInfo dataset returned by GetObjectInfo
 * (0x1008), plus what we get inline from EOS_ObjectAddedEx (0xC181).
 *
 * Only fields we actually act on are kept here. The full PTP ObjectInfo blob has
 * thumbnail metadata, capture date, etc., which we'd parse later if useful.
 */
data class ObjectInfo(
    val handle: Int,
    val storageId: Int,
    /** PTP object format code, e.g. [PtpIpConstants.FMT_JPEG], [PtpIpConstants.FMT_CANON_CR3]. */
    val format: Int,
    val filename: String,
    /** Uncompressed size from the wire. CR3 frames can exceed 4 GB → Long, not Int. */
    val sizeBytes: Long,
)

/**
 * A single user-facing photo as seen in the Browse grid. Bundles the
 * RAW + JPEG siblings of the same shot together so the UI shows one
 * tile per capture instead of two.
 *
 * 6D in RAW+JPEG mode writes each shot as `IMG_xxxx.CR2` and
 * `IMG_xxxx.JPG` side by side. Since the 6D in EOS Utility mode
 * silently ignores `GetObjectInfo` and `EOS_GetObjectInfoEx` on
 * image handles, we can't read filenames or format codes — but the
 * camera assigns sequential handles in a deterministic pattern:
 * paired handles always differ by exactly 1 in the low nibble
 * (e.g. `0x9190fb61` + `0x9190fb62`), and the lower (odd-suffix)
 * handle is the RAW. So we can pair them by handle arithmetic and
 * tag the format without ever asking the camera.
 *
 *  - [rawHandle]: CR2 handle, or null if the camera was in JPEG-only mode
 *  - [jpegHandle]: JPG handle, or null if the camera was in RAW-only mode
 *  - [displayName]: synthetic `IMG_xxxx` label for the tile
 *
 * At least one of [rawHandle]/[jpegHandle] is non-null.
 */
data class CameraPhoto(
    val displayName: String,
    val rawHandle: Int?,
    val jpegHandle: Int?,
    val storageId: Int,
    /**
     * Short uppercase format token for the tile badge + Format sort, e.g.
     * "CR2", "CR3", "JPG", "ARW", "NEF", "DNG". Derived at pairing time from
     * the PTP object-format code + filename extension; "" when unknown.
     */
    val formatLabel: String = "",
    /**
     * EXIF capture metadata (Stage 2). Lazily populated from each photo's
     * embedded EXIF; all null until that photo's EXIF has been read. Drives the
     * ISO / Aperture / Lens sort options + the thumbnail overlay text.
     */
    val isoValue: Int? = null,
    val apertureF: Float? = null,
    val lensModel: String? = null,
    val captureDateText: String? = null,
) {
    /** True if this photo has a RAW sibling we could route to RAWEditor. */
    val hasRaw: Boolean get() = rawHandle != null
    /** True if this photo has a JPG sibling we can use for fast preview. */
    val hasJpeg: Boolean get() = jpegHandle != null
    /**
     * Best handle for the preview canvas. Prefer the RAW sibling
     * because Canon CR2 files carry a full 1620×1080 embedded
     * JPEG preview that the 6D's `EOS_GetThumbEx (0x910A)` returns
     * as ~95 KB of decodable JPEG bytes. The JPG sibling's 0x910A
     * response is only the EXIF metadata header (no image scan
     * data) — BitmapFactory can't render it. Falls back to JPG
     * if the user shoots JPEG-only.
     */
    val previewHandle: Int get() = rawHandle ?: jpegHandle!!
    /**
     * Best handle for Edit — the RAW (real sensor data) if available,
     * otherwise the JPG.
     */
    val editHandle: Int get() = rawHandle ?: jpegHandle!!
}

/**
 * Sort order for the SD-card browser grid.
 *  - [DATE_NEWEST]/[DATE_OLDEST] use capture order (Canon object handles
 *    increment per shot — instant, no EXIF needed).
 *  - [FORMAT] groups by [CameraPhoto.formatLabel] (instant).
 *  - [ISO]/[APERTURE]/[LENS] use EXIF fields, lazily populated; photos whose
 *    EXIF hasn't loaded yet sort last.
 */
enum class BrowserSortMode(val label: String) {
    DATE_NEWEST("Newest first"),
    DATE_OLDEST("Oldest first"),
    FORMAT("Format"),
    ISO("ISO"),
    APERTURE("Aperture"),
    LENS("Lens"),
}
