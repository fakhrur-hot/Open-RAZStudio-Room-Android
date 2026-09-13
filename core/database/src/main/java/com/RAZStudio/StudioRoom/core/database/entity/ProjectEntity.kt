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
import androidx.room.PrimaryKey

/**
 * Represents a user-created container (Project) that holds an ordered set
 * of imported photos and their edits.
 *
 * Requirements: 11.3
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** ID of the photo used as the project cover thumbnail. Null = use earliest-added. */
    val coverPhotoId: Long?,
    /** Sort position in the project list. */
    val sortOrder: Int,
    /** Persisted sort mode for the grid (Req 7.5). Stores Sort enum ordinal. */
    val gridSortMode: Int,
    /** Bitmask of active filter flags for the grid. */
    val gridFilterMask: Int,
    /** User-selected column count for the grid. */
    val gridColumns: Int,
)
