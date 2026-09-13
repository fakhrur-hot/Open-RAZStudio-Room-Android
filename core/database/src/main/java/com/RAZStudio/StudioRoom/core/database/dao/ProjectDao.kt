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
import com.RAZStudio.StudioRoom.core.database.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for the `projects` table.
 *
 * Requirements: 11.7
 */
@Dao
interface ProjectDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)

    /** All projects ordered by their persisted sort order (Req 2.5). */
    @Query("SELECT * FROM projects ORDER BY sortOrder ASC")
    fun getAll(): Flow<List<ProjectEntity>>

    /** Observable single-project lookup; emits null when the row no longer exists. */
    @Query("SELECT * FROM projects WHERE id = :id")
    fun getById(id: Long): Flow<ProjectEntity?>
}
