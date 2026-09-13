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
 * Index row for a cached thumbnail file.
 *
 * The `bytes` column is summed to evaluate cache usage against the cap
 * without walking the filesystem directory (Req 6.7c). The `lastAccessedAt`
 * column is the LRU key for eviction (Req 6.7).
 *
 * One row per photo; photoId is the primary key (no autoGenerate).
 *
 * Requirements: 11.3, 11.6, 6.7, 6.7c
 */
@Entity(
    tableName = "thumbnails",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ThumbnailEntity(
    /** Same as PhotoEntity.id — one thumbnail record per photo. */
    @PrimaryKey
    val photoId: Long,
    /** Absolute filesystem path to the .jpg thumbnail file. */
    val path: String,
    /** File size in bytes, stored for O(1) cache-usage aggregation (Req 6.7c). */
    val bytes: Long,
    val generatedAt: Long,
    /** Updated on every cache read; used as LRU key for eviction (Req 6.7). */
    val lastAccessedAt: Long,
)
