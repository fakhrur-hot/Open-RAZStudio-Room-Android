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

package com.RAZStudio.StudioRoom.core.database.model

/**
 * One grid tile's data, assembled in SQL.
 *
 * Everything a tile shows comes from a single joined row (Requirement 7.5).
 * The alternative — fetching the photo then looking up its thumbnail and edit
 * state per tile — is an N+1 query per screenful, and it makes the has-edits and
 * sidecar-location indicators impossible to sort or filter on.
 */
data class PhotoGridRow(
    val id: Long,
    val projectId: Long,
    val displayName: String,
    val sourceUri: String,
    val sourceFormat: Int,
    val capturedAt: Long?,
    val addedAt: Long,
    val uriPermissionOk: Boolean,
    val sourceIsCopy: Boolean,
    val rating: Int,
    val flagState: Int,
    val colorLabel: Int,
    /** Joined from `thumbnails`; null until the worker has produced one. */
    val thumbPath: String?,
    /** thumbnails.generatedAt — changes when an edited thumbnail replaces the file at the SAME path. */
    val thumbGeneratedAt: Long?,
    /** 1 when an `edits` row exists for this photo. */
    val hasEdits: Int,
    /**
     * From `edits`. Null when there is no edit row at all; 0 means the sidecar
     * sits in the per-project fallback rather than beside the original, which
     * the tile surfaces (Requirement 4.7).
     */
    val sidecarBesideOriginal: Int?,

    // ── Schema v3 (info panel, date headers, camera/lens chips) ─────────────
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val sizeBytes: Long = 0L,
    val cameraModel: String = "",
    val lensModel: String = "",
    val iso: Int = 0,
    val apertureF: Float = 0f,
    val shutterSpeed: Float = 0f,
    val focalLengthMm: Float = 0f,
    val keywords: String = "",
    /** Schema v4 — set while the photo is in the trash. */
    val deletedAt: Long? = null,
    /**
     * How many photos share this row's [stackKey], counted only when the grid
     * is collapsing stacks; 1 otherwise. Drives the "+1" badge on the tile.
     */
    val stackCount: Int = 1,
)

/** Sort orders the grid offers (Requirement 7.2). Ordinal is persisted. */
enum class GridSort {
    CapturedDate, AddedDate, FileName, Format, Rating;

    /**
     * The ORDER BY column. `capturedAt` is nullable — EXIF-less files sort last
     * in either direction rather than clumping at whichever end NULL lands.
     */
    val column: String
        get() = when (this) {
            CapturedDate -> "COALESCE(p.capturedAt, p.addedAt)"
            AddedDate -> "p.addedAt"
            FileName -> "p.displayName COLLATE NOCASE"
            Format -> "p.sourceFormat"
            Rating -> "p.rating"
        }
}

/**
 * Grid filters (Requirement 7.3), packed into `projects.gridFilterMask`.
 *
 * Flag states are a bitmask so several can be shown at once; rating and colour
 * occupy their own bit ranges in the same int, which keeps the persisted schema
 * to the one column it already has.
 */
@JvmInline
value class GridFilter(val mask: Int) {

    val showUnflagged: Boolean get() = mask and BIT_UNFLAGGED != 0
    val showFlagged: Boolean get() = mask and BIT_FLAGGED != 0
    val showRejected: Boolean get() = mask and BIT_REJECTED != 0

    /** Minimum star rating, 0..5. 0 means no rating filter. */
    val minRating: Int get() = (mask shr SHIFT_RATING) and 0x7

    /** Colour label to require, 0 = any. */
    val colorLabel: Int get() = (mask shr SHIFT_COLOR) and 0xF

    /** True when nothing is being filtered out. */
    val isEmpty: Boolean
        get() = !showUnflagged && !showFlagged && !showRejected &&
            minRating == 0 && colorLabel == 0

    fun withFlagStates(unflagged: Boolean, flagged: Boolean, rejected: Boolean) = GridFilter(
        (mask and (BIT_UNFLAGGED or BIT_FLAGGED or BIT_REJECTED).inv()) or
            (if (unflagged) BIT_UNFLAGGED else 0) or
            (if (flagged) BIT_FLAGGED else 0) or
            (if (rejected) BIT_REJECTED else 0)
    )

    fun withMinRating(stars: Int) = GridFilter(
        (mask and (0x7 shl SHIFT_RATING).inv()) or ((stars.coerceIn(0, 5)) shl SHIFT_RATING)
    )

    fun withColorLabel(label: Int) = GridFilter(
        (mask and (0xF shl SHIFT_COLOR).inv()) or ((label.coerceIn(0, 15)) shl SHIFT_COLOR)
    )

    companion object {
        const val BIT_UNFLAGGED = 1 shl 0
        const val BIT_FLAGGED = 1 shl 1
        const val BIT_REJECTED = 1 shl 2
        const val SHIFT_RATING = 3
        const val SHIFT_COLOR = 6

        val NONE = GridFilter(0)
    }
}
