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
import com.RAZStudio.StudioRoom.core.database.entity.EditEntity

/**
 * Data-access object for the `edits` table.
 *
 * Requirements: 11.3, 5.6
 */
@Dao
interface EditDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(edit: EditEntity)

    @Update
    suspend fun update(edit: EditEntity)

    @Delete
    suspend fun delete(edit: EditEntity)

    /** Returns the edit record for a photo, or null if no sidecar has been committed yet. */
    @Query("SELECT * FROM edits WHERE photoId = :photoId LIMIT 1")
    suspend fun getByPhotoId(photoId: Long): EditEntity?
}
