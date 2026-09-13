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

package com.RAZStudio.StudioRoom.core.database.di

import android.content.Context
import androidx.room.Room
import com.RAZStudio.StudioRoom.core.database.GalleryDatabase
import com.RAZStudio.StudioRoom.core.database.dao.EditDao
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.dao.ProjectDao
import com.RAZStudio.StudioRoom.core.database.dao.ThumbnailDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Hilt module that provides the [GalleryDatabase] singleton and all DAO instances.
 *
 * Database path: `filesDir/gallery/gallery.db`, built as a [File] and handed to
 * Room as an absolute path. `context.getDatabasePath()` does NOT work here — it
 * accepts only a bare filename and throws on any '/'.
 *
 * **fallbackToDestructiveMigration is intentionally absent.** A missing migration
 * will throw and crash the app, which is the correct behaviour — a crash is
 * recoverable; a silently wiped library is not (Req 11.5).
 *
 * Requirements: 11.1, 11.2, 11.5
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideGalleryDatabase(
        @ApplicationContext context: Context,
    ): GalleryDatabase {
        // The spec puts the database at filesDir/gallery/gallery.db, alongside
        // the per-project thumbnail and sidecar directories, so the whole
        // library is one subtree.
        //
        // getDatabasePath() CANNOT be used for that: it only accepts a bare
        // filename and throws IllegalArgumentException("contains a path
        // separator") on anything containing '/'. Build the File directly and
        // hand Room the absolute path instead.
        val dbFile = File(context.filesDir, "gallery/gallery.db")
        dbFile.parentFile?.mkdirs()
        val dbPath = dbFile.absolutePath

        return Room.databaseBuilder(
            context = context,
            klass = GalleryDatabase::class.java,
            name = dbPath,
        )
            // NO fallbackToDestructiveMigration — every schema change must have
            // an explicit migration. Absence of a migration is a loud crash.
            .addCallback(GalleryDatabase.loggingCallback)
            .addCallback(GalleryDatabase.makeMigrationBackupCallback(dbPath))
            // At v1 there are no migrations yet; list is empty but the
            .addMigrations(GalleryDatabase.MIGRATION_1_2,
                GalleryDatabase.MIGRATION_2_3,
                GalleryDatabase.MIGRATION_3_4)
            .build()
    }

    @Provides
    fun provideProjectDao(db: GalleryDatabase): ProjectDao = db.projectDao()

    @Provides
    fun providePhotoDao(db: GalleryDatabase): PhotoDao = db.photoDao()

    @Provides
    fun provideEditDao(db: GalleryDatabase): EditDao = db.editDao()

    @Provides
    fun provideThumbnailDao(db: GalleryDatabase): ThumbnailDao = db.thumbnailDao()
}
