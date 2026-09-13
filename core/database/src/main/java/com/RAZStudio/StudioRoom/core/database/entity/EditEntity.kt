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

package com.RAZStudio.StudioRoom.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Stores the resolved location and last-update timestamp of a photo's
 * Edit_Sidecar. The DB is an INDEX of edits; it is NEVER the authoritative
 * store of edit content (Req 11.5 / 5.6).
 *
 * One row per photo; photoId is the primary key (no autoGenerate).
 *
 * Requirements: 11.3, 11.6, 5.6
 */
@Entity(
    tableName = "edits",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EditEntity(
    /** Same as PhotoEntity.id — one edit record per photo. */
    @PrimaryKey
    val photoId: Long,
    /** Absolute filesystem path to the .xmp sidecar file. */
    val sidecarPath: String,
    /**
     * True when the sidecar lives beside the original (Folder_Write_Grant
     * granted). False when it lives in the per-project fallback location.
     * Used to determine the confirmation copy text in the context menu (Req 14.29).
     */
    val sidecarBesideOriginal: Boolean,
    val revisionCount: Int,
    val updatedAt: Long,
    /**
     * True when the sidecar's CURRENT state carries no adjustments — i.e. the
     * user reset the photo's edits (Req 14.23). The sidecar file itself is
     * never deleted on reset (Req 14.22, its history stays restorable), so
     * this row cannot simply be dropped; this flag is what lets the grid's
     * has-edits indicator clear while the edit record survives. Any edit or
     * paste flips it back to false. Added in DB v2.
     */
    val isNeutral: Boolean = false,
)
