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

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.RAZStudio.StudioRoom.core.database.entity.ThumbnailEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for the `thumbnails` table.
 *
 * Cache usage is evaluated by summing the `bytes` column — never by walking the
 * filesystem — so these queries are the primary interface for both the cap check
 * and fair-share eviction (Req 6.7c).
 *
 * Requirements: 11.3, 6.7, 6.7b, 6.7c
 */
@Dao
interface ThumbnailDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(thumbnail: ThumbnailEntity)

    @Update
    suspend fun update(thumbnail: ThumbnailEntity)

    @Delete
    suspend fun delete(thumbnail: ThumbnailEntity)

    /** Returns the thumbnail record for a photo, or null if not yet generated. */
    @Query("SELECT * FROM thumbnails WHERE photoId = :photoId LIMIT 1")
    suspend fun getByPhotoId(photoId: Long): ThumbnailEntity?

    /**
     * Live total of all cached thumbnail bytes across all projects (Req 6.7b, 6.7c).
     * Compared against the user-configurable cap to decide when eviction is needed.
     */
    @Query("SELECT COALESCE(SUM(bytes), 0) FROM thumbnails")
    fun getTotalBytes(): Flow<Long>

    /**
     * Returns LRU eviction candidates ordered by fair-share priority.
     *
     * Fair-share eviction selects from the project FURTHEST above its per-project
     * fair share (cap ÷ number of projects holding thumbnails), then by LRU
     * (oldest lastAccessedAt) within that project (Req 6.7).
     *
     * `projectFairShareBytes` is computed by the caller as:
     *   `totalCapBytes / COUNT(DISTINCT projectId in thumbnails)`
     *
     * Rows are ordered by (bytes in project − fairShare) DESC, then lastAccessedAt ASC
     * so the caller can take from the front until enough bytes are freed.
     */
    @Query(
        """
        SELECT t.*,
               proj_total.projectBytes - :projectFairShareBytes AS overShare
        FROM thumbnails t
        INNER JOIN (
            SELECT projectId, SUM(bytes) AS projectBytes
            FROM (
                SELECT p.projectId, t2.bytes
                FROM thumbnails t2
                INNER JOIN photos p ON t2.photoId = p.id
            )
            GROUP BY projectId
        ) proj_total ON (
            SELECT p.projectId FROM photos p WHERE p.id = t.photoId
        ) = proj_total.projectId
        ORDER BY overShare DESC, t.lastAccessedAt ASC
        """
    )
    suspend fun getLruVictims(projectFairShareBytes: Long): List<ThumbnailEntity>

    /** One-shot total, for the eviction check (the Flow variant is for the UI). */
    @Query("SELECT COALESCE(SUM(bytes), 0) FROM thumbnails")
    suspend fun getTotalBytesNow(): Long

    /**
     * Number of projects currently holding thumbnails — the divisor for the
     * per-project fair share (Req 6.7).
     */
    @Query(
        """
        SELECT COUNT(DISTINCT p.projectId) FROM thumbnails t
        INNER JOIN photos p ON p.id = t.photoId
        """
    )
    suspend fun countProjectsWithThumbnails(): Int
}
