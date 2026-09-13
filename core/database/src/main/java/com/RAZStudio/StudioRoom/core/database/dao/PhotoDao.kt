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

package com.RAZStudio.StudioRoom.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.RAZStudio.StudioRoom.core.database.entity.EditEntity
import com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity
import com.RAZStudio.StudioRoom.core.database.entity.ThumbnailEntity
import com.RAZStudio.StudioRoom.core.database.model.PhotoGridRow
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for the `photos` table.
 *
 * The paged query is the primary read path for the Project_Grid — a whole-project
 * List is never returned (Req 11.7).
 *
 * Requirements: 11.7, 3.5, 10.7a
 */
@Dao
interface PhotoDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: PhotoEntity): Long

    @Update
    suspend fun update(photo: PhotoEntity)

    @Delete
    suspend fun delete(photo: PhotoEntity)

    /**
     * Paged, observable grid query. Sort and filter are SQL, never in-memory
     * list operations (Req 7.4). The caller builds and passes the SQL string
     * through a RawQuery/SupportSQLiteQuery wrapper in the repository layer,
     * which generates the appropriate ORDER BY / WHERE clauses from the
     * persisted grid state.
     *
     * Sorted by addedAt descending as the default; the repository overrides
     * this via SupportSQLiteQuery for dynamic sort/filter.
     */
    @Query("SELECT * FROM photos WHERE projectId = :projectId ORDER BY addedAt DESC")
    fun getPagedPhotos(projectId: Long): PagingSource<Int, PhotoEntity>

    /** Live count of photos in a project (used for the delete-confirmation dialog). */
    @Query("SELECT COUNT(*) FROM photos WHERE projectId = :projectId")
    fun getPhotoCount(projectId: Long): Flow<Int>

    /**
     * Duplicate-import guard (Req 3.5). Returns the existing row if a photo
     * with this URI is already in the target project.
     */
    @Query("SELECT * FROM photos WHERE projectId = :projectId AND sourceUri = :sourceUri LIMIT 1")
    suspend fun getBySourceUri(projectId: Long, sourceUri: String): PhotoEntity?

    /** All unreachable photos in a project, used by the relink scan (Req 10.1). */
    @Query("SELECT * FROM photos WHERE projectId = :projectId AND uriPermissionOk = 0")
    suspend fun getUnreachable(projectId: Long): List<PhotoEntity>

    /** Live count of unreachable photos, so the UI can offer relink only when needed. */
    @Query("SELECT COUNT(*) FROM photos WHERE projectId = :projectId AND uriPermissionOk = 0")
    fun observeUnreachableCount(projectId: Long): Flow<Int>

    /**
     * The grid's real read path (Requirements 7.1–7.4): a PAGED query whose
     * sort and filter are SQL, built by `GridQueryBuilder`.
     *
     * `@RawQuery` because ORDER BY and WHERE cannot be parameter-bound in Room's
     * generated SQL — and Requirement 7.4 forbids doing the work in memory, which
     * is the only alternative if the query is static.
     *
     * `observedEntities` tells Room which tables to watch for invalidation. All
     * three matter: a rating change is `photos`, a finished thumbnail is
     * `thumbnails`, and a first edit is `edits` — miss one and the grid silently
     * stops refreshing for that class of change.
     */
    @RawQuery(observedEntities = [PhotoEntity::class, ThumbnailEntity::class, EditEntity::class])
    fun getGridPaged(query: SupportSQLiteQuery): PagingSource<Int, PhotoGridRow>

    /** Row count for the same filter, so an empty result can be distinguished. */
    @RawQuery(observedEntities = [PhotoEntity::class, ThumbnailEntity::class, EditEntity::class])
    fun observeGridCount(query: SupportSQLiteQuery): Flow<Int>

    /** Candidate lookup for fingerprint-based relink (Req 10.7a). */
    @Query("SELECT * FROM photos WHERE projectId = :projectId AND contentFingerprint = :fingerprint")
    suspend fun getByFingerprint(projectId: Long, fingerprint: String): List<PhotoEntity>

    // ── Batch operations (Requirement 8) ────────────────────────────────────
    // One UPDATE per batch, not one per photo: a 200-photo cull is a single
    // statement and a single invalidation tick for the paged grid.

    @Query("UPDATE photos SET flagState = :state WHERE id IN (:ids)")
    suspend fun setFlagStateBatch(ids: List<Long>, state: Int)

    @Query("UPDATE photos SET rating = :stars WHERE id IN (:ids)")
    suspend fun setRatingBatch(ids: List<Long>, stars: Int)

    @Query("UPDATE photos SET colorLabel = :label WHERE id IN (:ids)")
    suspend fun setColorLabelBatch(ids: List<Long>, label: Int)

    /**
     * Move a photo to another project keeping its row identity, so the
     * photoId-keyed edit and thumbnail rows travel with it for free (Req 8.4).
     * File relocation (copied originals, fallback sidecars, thumbnail files)
     * is the repository's job — this only re-homes the row.
     */
    @Query("UPDATE photos SET projectId = :destProjectId, sourceUri = :sourceUri WHERE id = :photoId")
    suspend fun reparent(photoId: Long, destProjectId: Long, sourceUri: String)

    /**
     * Ids matching the active grid filter, in display order — the
     * "select all matching filter" source (Req 8.7). Built by
     * `GridQueryBuilder.ids`; raw for the same reason as [getGridPaged].
     */
    @RawQuery
    suspend fun getGridIds(query: SupportSQLiteQuery): List<Long>

    /**
     * Bounded observable photo list for a project.
     *
     * INTERIM for the reveal-after-add flow (Req 16.5). Task 7 replaces the grid
     * with [getPagedPhotos] plus dynamic sort/filter; this exists so a project
     * has a destination to navigate to, and is bounded so it can never become an
     * unbounded whole-project load.
     */
    @Query(
        "SELECT * FROM photos WHERE projectId = :projectId ORDER BY addedAt DESC LIMIT :limit"
    )
    fun observePhotos(projectId: Long, limit: Int): Flow<List<PhotoEntity>>

    /**
     * Earliest-added photo in a project, used as the cover fallback when
     * `projects.coverPhotoId` is unset (Req 2.10).
     */
    @Query("SELECT id FROM photos WHERE projectId = :projectId ORDER BY addedAt ASC LIMIT 1")
    suspend fun getEarliestAddedId(projectId: Long): Long?

    /** Single row by id. */
    @Query("SELECT * FROM photos WHERE id = :photoId LIMIT 1")
    suspend fun getById(photoId: Long): PhotoEntity?

    // ── Schema v3: shot metadata + keywords ──────────────────────────────────

    /**
     * Rows imported before schema v3 (or whose EXIF read failed): every metadata
     * field is at its "unknown" default. Feeds the one-shot backfill so an
     * existing library gains search/filter/date headers without a re-import.
     * `capturedAt IS NULL` is deliberately NOT a condition — plenty of files
     * genuinely have no timestamp and would be re-probed forever.
     */
    @Query(
        """
        SELECT * FROM photos
        WHERE cameraMake = '' AND cameraModel = '' AND lensModel = ''
          AND iso = 0 AND apertureF = 0 AND focalLengthMm = 0
        LIMIT :limit
        """
    )
    suspend fun getMissingExif(limit: Int): List<PhotoEntity>

    @Query(
        """
        UPDATE photos SET cameraMake = :make, cameraModel = :model, lensModel = :lens,
               iso = :iso, apertureF = :aperture, shutterSpeed = :shutter,
               focalLengthMm = :focal, gpsLat = :lat, gpsLon = :lon,
               capturedAt = COALESCE(capturedAt, :capturedAt),
               widthPx = CASE WHEN widthPx > 0 THEN widthPx ELSE :widthPx END,
               heightPx = CASE WHEN heightPx > 0 THEN heightPx ELSE :heightPx END
        WHERE id = :photoId
        """
    )
    suspend fun updateExif(
        photoId: Long, make: String, model: String, lens: String, iso: Int,
        aperture: Float, shutter: Float, focal: Float,
        lat: Double?, lon: Double?, capturedAt: Long?, widthPx: Int, heightPx: Int,
    )

    @Query("UPDATE photos SET keywords = :keywords WHERE id = :photoId")
    suspend fun setKeywords(photoId: Long, keywords: String)

    /** Every keyword string in scope; the caller splits and counts (see Keywords). */
    @Query("SELECT keywords FROM photos WHERE (:projectId IS NULL OR projectId = :projectId) AND keywords != ''")
    suspend fun allKeywordStrings(projectId: Long?): List<String>

    /** Camera bodies present in scope, most-used first — the filter chips. */
    @Query(
        """
        SELECT cameraModel FROM photos
        WHERE (:projectId IS NULL OR projectId = :projectId) AND cameraModel != ''
        GROUP BY cameraModel ORDER BY COUNT(*) DESC LIMIT 12
        """
    )
    suspend fun distinctCameras(projectId: Long?): List<String>

    @Query(
        """
        SELECT lensModel FROM photos
        WHERE (:projectId IS NULL OR projectId = :projectId) AND lensModel != ''
        GROUP BY lensModel ORDER BY COUNT(*) DESC LIMIT 12
        """
    )
    suspend fun distinctLenses(projectId: Long?): List<String>

    // ── Library-wide ("All photos") + storage ────────────────────────────────

    @Query("SELECT COUNT(*) FROM photos")
    fun getPhotoCountAll(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE uriPermissionOk = 0")
    fun observeUnreachableCountAll(): Flow<Int>

    /** Bytes of ORIGINALS the app itself owns (copied into a project). */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM photos WHERE sourceIsCopy = 1")
    suspend fun copiedOriginalsBytes(): Long

    /** Bytes of linked (not copied) sources — counted for information only. */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM photos WHERE sourceIsCopy = 0")
    suspend fun linkedSourceBytes(): Long

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<PhotoEntity>

    // ── Schema v4: trash + stacks ────────────────────────────────────────────

    @Query("UPDATE photos SET deletedAt = :now WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<Long>, now: Long)

    @Query("UPDATE photos SET deletedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreFromTrash(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM photos WHERE deletedAt IS NOT NULL AND (:projectId IS NULL OR projectId = :projectId)")
    fun observeTrashCount(projectId: Long?): Flow<Int>

    /** Trashed longer than the retention window — purged for real on open. */
    @Query("SELECT * FROM photos WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun getExpiredTrash(cutoff: Long): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE deletedAt IS NOT NULL AND (:projectId IS NULL OR projectId = :projectId)")
    suspend fun getTrashed(projectId: Long?): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE stackKey = '' LIMIT :limit")
    suspend fun getMissingStackKey(limit: Int): List<PhotoEntity>

    @Query("UPDATE photos SET stackKey = :key WHERE id = :photoId")
    suspend fun setStackKey(photoId: Long, key: String)

    /** Every photo sharing one stack, so expanding a collapsed tile is one query. */
    @Query("SELECT * FROM photos WHERE projectId = :projectId AND stackKey = :key AND deletedAt IS NULL")
    suspend fun getStackMembers(projectId: Long, key: String): List<PhotoEntity>

    /**
     * Every photo id in a project. Used only by bulk maintenance paths (orphan
     * sweeps, project export) — never to populate the grid, which pages.
     */
    @Query("SELECT id FROM photos WHERE projectId = :projectId")
    suspend fun getAllIds(projectId: Long): List<Long>

    /**
     * Every photo row in a project. Used only by bulk maintenance paths (the
     * relink readability scan, Req 10.1) — never to populate the grid, which
     * pages.
     */
    @Query("SELECT * FROM photos WHERE projectId = :projectId")
    suspend fun getAllForProject(projectId: Long): List<PhotoEntity>

    /**
     * Un-edited photos in a project matching a camera + lens pair, for the
     * "apply lens profile to matching photos" bulk action (Req 15.11). Joins
     * against `edits` so photos that already carry a committed configuration are
     * excluded rather than being silently overwritten.
     */
    @Query(
        """
        SELECT p.* FROM photos p
        LEFT JOIN edits e ON e.photoId = p.id
        WHERE p.projectId = :projectId AND e.photoId IS NULL
        """
    )
    suspend fun getUnedited(projectId: Long): List<PhotoEntity>
}
