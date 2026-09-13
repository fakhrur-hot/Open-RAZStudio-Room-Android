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

package com.RAZStudio.StudioRoom.core.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.RoomDatabase
import com.RAZStudio.StudioRoom.core.database.dao.EditDao
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.dao.ProjectDao
import com.RAZStudio.StudioRoom.core.database.dao.ThumbnailDao
import com.RAZStudio.StudioRoom.core.database.entity.EditEntity
import com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity
import com.RAZStudio.StudioRoom.core.database.entity.ProjectEntity
import com.RAZStudio.StudioRoom.core.database.entity.ThumbnailEntity
import java.io.File

/**
 * Room database for the Gallery Workspace.
 *
 * Schema is exported to `core/database/schemas/` via the KSP argument
 * `room.schemaLocation` in build.gradle.kts. Every schema version change
 * requires an explicit migration — **fallbackToDestructiveMigration is
 * deliberately absent**. A missing migration must crash loudly; a crash is
 * recoverable, a wiped library is not (Req 11.5).
 *
 * The database file lives at `filesDir/gallery/gallery.db`; the path is
 * resolved in [DatabaseModule] via `context.getDatabasePath("gallery/gallery.db")`.
 *
 * Requirements: 11.1, 11.2, 11.3, 11.5
 */
@Database(
    entities = [
        ProjectEntity::class,
        PhotoEntity::class,
        EditEntity::class,
        ThumbnailEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun photoDao(): PhotoDao
    abstract fun editDao(): EditDao
    abstract fun thumbnailDao(): ThumbnailDao

    companion object {

        private const val TAG = "GalleryDatabase"

        /**
         * v1 → v2: `edits.isNeutral` (Req 14.23 — reset clears the has-edits
         * indicator without deleting the sidecar or its row). Additive column
         * with a default, so existing rows keep meaning "has edits".
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE edits ADD COLUMN isNeutral INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 → v3: shot metadata + keywords on `photos` (search, camera/lens
         * filters, date headers, the loupe info panel). All additive with
         * defaults, so existing rows read as "unknown" until re-probed —
         * [com.RAZStudio.StudioRoom.core.database.dao.PhotoDao.getMissingExif]
         * feeds the one-shot backfill the gallery runs on open.
         *
         * Index names must match what Room generates for the @Index entries on
         * PhotoEntity (`index_<table>_<col>_<col>`), or validation fails on the
         * next open with an identity-hash mismatch.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE photos ADD COLUMN cameraMake TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE photos ADD COLUMN cameraModel TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE photos ADD COLUMN lensModel TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE photos ADD COLUMN iso INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE photos ADD COLUMN apertureF REAL NOT NULL DEFAULT 0",
                    "ALTER TABLE photos ADD COLUMN shutterSpeed REAL NOT NULL DEFAULT 0",
                    "ALTER TABLE photos ADD COLUMN focalLengthMm REAL NOT NULL DEFAULT 0",
                    "ALTER TABLE photos ADD COLUMN gpsLat REAL",
                    "ALTER TABLE photos ADD COLUMN gpsLon REAL",
                    "ALTER TABLE photos ADD COLUMN keywords TEXT NOT NULL DEFAULT ''",
                    "CREATE INDEX IF NOT EXISTS `index_photos_projectId_cameraModel` ON `photos` (`projectId`, `cameraModel`)",
                    "CREATE INDEX IF NOT EXISTS `index_photos_projectId_lensModel` ON `photos` (`projectId`, `lensModel`)",
                    "CREATE INDEX IF NOT EXISTS `index_photos_capturedAt` ON `photos` (`capturedAt`)",
                    "CREATE INDEX IF NOT EXISTS `index_photos_addedAt` ON `photos` (`addedAt`)",
                ).forEach(db::execSQL)
                Log.i(TAG, "migrated 2 → 3 (shot metadata + keywords)")
            }
        }

        /**
         * v3 → v4: `photos.deletedAt` (trash with recovery) and `photos.stackKey`
         * (RAW+JPEG collapsing). Both additive; existing rows read as "not
         * deleted" and "not yet stacked", and the gallery backfills stackKey on
         * open.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE photos ADD COLUMN deletedAt INTEGER",
                    "ALTER TABLE photos ADD COLUMN stackKey TEXT NOT NULL DEFAULT ''",
                    "CREATE INDEX IF NOT EXISTS `index_photos_projectId_deletedAt` ON `photos` (`projectId`, `deletedAt`)",
                    "CREATE INDEX IF NOT EXISTS `index_photos_projectId_stackKey` ON `photos` (`projectId`, `stackKey`)",
                ).forEach(db::execSQL)
                Log.i(TAG, "migrated 3 → 4 (trash + stacks)")
            }
        }

        /**
         * Callback that logs the Room version and identity hash when the
         * database is first created or subsequently opened.
         *
         * Requirements: 11.5
         */
        val loggingCallback = object : Callback() {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                Log.i(TAG, "onCreate — version=${db.version}")
            }

            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // The identity hash is stored in the room_master_table by Room.
                val cursor = db.query("SELECT identity_hash FROM room_master_table LIMIT 1")
                val hash = if (cursor.moveToFirst()) cursor.getString(0) else "<unknown>"
                cursor.close()
                Log.i(TAG, "onOpen — version=${db.version} identityHash=$hash")
            }
        }

        /**
         * Pre-migration backup callback.
         *
         * Copies the `.db` file to `<db>.bak` before any migration runs so that
         * a failed migration leaves the user's data recoverable. The backup is
         * written in [onOpen] (which fires before migrations are applied when the
         * version has changed) and deleted in [onOpen] once the open succeeds
         * (meaning all migrations ran cleanly).
         *
         * At schema v1 there are no migrations yet, but the infrastructure is
         * wired so adding v2 → v1 requires only a Migration object and the backup
         * is handled automatically.
         *
         * Requirements: 11.5
         */
        fun makeMigrationBackupCallback(dbPath: String): Callback = object : Callback() {

            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                val dbFile = File(dbPath)
                val bakFile = File("$dbPath.bak")

                // Only write the backup when the db version matches our current
                // compiled version — if they differ a migration is about to run
                // (Room opens the DB before migrating), so we want the backup.
                // When already at the current version the backup from a prior
                // migration run can be cleaned up.
                if (db.version < DATABASE_VERSION) {
                    // About to migrate: back up now.
                    try {
                        if (dbFile.exists()) {
                            dbFile.copyTo(bakFile, overwrite = true)
                            Log.i(TAG, "Pre-migration backup written to ${bakFile.absolutePath}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write pre-migration backup", e)
                    }
                } else if (bakFile.exists()) {
                    // Migration completed cleanly; remove the backup.
                    bakFile.delete()
                    Log.i(TAG, "Migration completed cleanly; removed backup ${bakFile.absolutePath}")
                }
            }
        }

        /**
         * Matches the @Database(version = ...) value above. Was left at 1 when
         * the schema went to 2, which silently disabled the pre-migration
         * backup (`db.version < DATABASE_VERSION` was never true).
         */
        const val DATABASE_VERSION = 4
    }
}
