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
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents one imported photo: its source URI, indexed metadata, and a
 * reference to its edit sidecar.
 *
 * Indexes cover every sort and filter column from Requirement 7, plus the
 * unique (projectId, sourceUri) constraint that prevents duplicate imports
 * (Req 3.5), and contentFingerprint for directory-relink matching (Req 10.7a).
 *
 * Requirements: 11.3, 11.4, 3.5, 10.7a
 */
@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Req 11.4 — index every sort/filter column
        Index("projectId"),
        Index(value = ["projectId", "capturedAt"]),
        Index(value = ["projectId", "addedAt"]),
        Index(value = ["projectId", "displayName"]),
        Index(value = ["projectId", "sourceFormat"]),
        Index(value = ["projectId", "rating"]),
        Index(value = ["projectId", "flagState"]),
        Index(value = ["projectId", "colorLabel"]),
        // Req 3.5 — prevents duplicate imports of the same URI into the same project
        Index(value = ["projectId", "sourceUri"], unique = true),
        // Req 10.7a — directory relink via Content_Fingerprint
        Index("contentFingerprint"),
        // Schema v3 — camera/lens filter chips and the library-wide ("All
        // photos") sorts, which cannot use the projectId-prefixed indices.
        Index(value = ["projectId", "cameraModel"]),
        Index(value = ["projectId", "lensModel"]),
        Index("capturedAt"),
        Index("addedAt"),
        // Schema v4 — the grid filters on deletedAt for EVERY query, and stacks
        // group by stackKey.
        Index(value = ["projectId", "deletedAt"]),
        Index(value = ["projectId", "stackKey"]),
    ],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectId: Long,
    /** Persistable SAF URI (or file URI for Copied_Originals) as a string. */
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    /** SourceFormat enum ordinal — the detected image format. */
    val sourceFormat: Int,
    val sizeBytes: Long,
    val widthPx: Int,
    val heightPx: Int,
    /** EXIF capture timestamp in epoch-milliseconds. Null when EXIF is absent. */
    val capturedAt: Long?,
    val addedAt: Long,
    /**
     * Whether a Persistable_Grant is currently held for this URI (Req 10.3).
     * Stored as a first-class column, not derived, because it must survive
     * process death and be queryable without opening the URI.
     */
    val uriPermissionOk: Boolean,
    /**
     * True when sourceUri points at a Copied_Original inside this project's
     * `originals/` directory (Req 16.10). Inverts the deletion rule: the app
     * owns this file, so removing the Photo_Entry MUST delete it.
     */
    val sourceIsCopy: Boolean,
    /**
     * Content_Fingerprint: "<sizeBytes>:<sha256 first 64KB>:<sha256 last 64KB>".
     * Null when it could not be computed (Req 3.4c). This is an identity hint,
     * NOT a cryptographic guarantee (Req 3.4b).
     */
    val contentFingerprint: String?,
    /** Star rating 0–5 (Req 7.10). */
    val rating: Int,
    /** Flag state: 0 = none, 1 = flagged, 2 = rejected (Req 7.8, 7.9). */
    val flagState: Int,
    /** Colour label: 0 = none, else palette index (Req 7.10). */
    val colorLabel: Int,

    // ── Schema v3: shot metadata, captured once at import ────────────────────
    // Read from EXIF by PhotoMetadataProbe and stored so the grid can search,
    // filter, sort and group on it without touching the files again (probing
    // 2 000 RAWs per keystroke is not viable). Empty string / 0 = unknown.
    val cameraMake: String = "",
    val cameraModel: String = "",
    val lensModel: String = "",
    val iso: Int = 0,
    val apertureF: Float = 0f,
    /** Exposure time in SECONDS (0.004 = 1/250 s). */
    val shutterSpeed: Float = 0f,
    val focalLengthMm: Float = 0f,
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
    /**
     * User keywords in [Keywords] wire form — `|tag|tag|`, lower-cased, so a
     * `LIKE '%|portrait|%'` matches a whole tag and never a substring of one.
     * Empty = untagged. Denormalised on purpose: a join table would need its
     * own DAO, migration and paging query for a feature whose whole job is a
     * text match.
     */
    val keywords: String = "",

    // ── Schema v4 ───────────────────────────────────────────────────────────
    /**
     * Soft delete: when the photo was moved to the trash, else null. Removing a
     * photo used to be immediate with only a snackbar to undo it — once that
     * snackbar expired a mis-tap was unrecoverable. Rows stay for
     * [TRASH_RETENTION_DAYS] and every grid query excludes them.
     */
    val deletedAt: Long? = null,
    /**
     * Stack identity: the filename WITHOUT its extension, lower-cased
     * ("dsc08956.arw" and "DSC08956.JPG" → "dsc08956"). Shooting RAW+JPEG puts
     * two tiles in the grid for one frame; collapsing on this key shows one.
     * Empty only for rows imported before v4 and not yet backfilled.
     */
    val stackKey: String = "",
)

/** Days a trashed photo is kept before it is purged for real. */
const val TRASH_RETENTION_DAYS = 30
