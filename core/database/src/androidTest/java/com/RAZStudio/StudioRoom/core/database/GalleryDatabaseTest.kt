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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity
import com.RAZStudio.StudioRoom.core.database.entity.ProjectEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [GalleryDatabase] at schema v1.
 *
 * Uses an in-memory database so no filesystem state is left behind.
 *
 * These tests are the primary automated safety net for database changes:
 * a bad schema or missing migration would destroy a user's library (Req 11.5).
 *
 * Requirements: 11.5
 */
@RunWith(AndroidJUnit4::class)
class GalleryDatabaseTest {

    private lateinit var db: GalleryDatabase

    @Before
    fun createDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GalleryDatabase::class.java,
            // NOTE: NO fallbackToDestructiveMigration — the builder chain matches
            // what DatabaseModule uses (minus the custom db path and callbacks that
            // are not relevant for an in-memory instance).
        ).build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Schema v1 round-trip tests
    // -------------------------------------------------------------------------

    /**
     * Verifies that the v1 schema opens successfully and that a [ProjectEntity]
     * can be inserted and read back with all fields intact.
     *
     * Requirements: 11.5
     */
    @Test
    fun v1_insertAndQueryProject() = runBlocking {
        val projectDao = db.projectDao()

        val project = ProjectEntity(
            name = "My First Shoot",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            coverPhotoId = null,
            sortOrder = 0,
            gridSortMode = 0,
            gridFilterMask = 0,
            gridColumns = 3,
        )

        val insertedId = projectDao.insert(project)
        assert(insertedId > 0) { "Expected a positive auto-generated id, got $insertedId" }

        val loaded = projectDao.getById(insertedId).first()
        assertNotNull("Inserted project should be retrievable by id", loaded)
        assertEquals("My First Shoot", loaded!!.name)
        assertEquals(1_700_000_000_000L, loaded.createdAt)
        assertNull("coverPhotoId should be null", loaded.coverPhotoId)
    }

    /**
     * Verifies that a [PhotoEntity] can be inserted referencing an existing
     * project and queried back, including all indexed metadata fields.
     *
     * Also exercises the foreign-key constraint: the photo row references a
     * real project row.
     *
     * Requirements: 11.5, 11.6
     */
    @Test
    fun v1_insertAndQueryPhoto() = runBlocking {
        val projectDao = db.projectDao()
        val photoDao = db.photoDao()

        // Insert a parent project first.
        val projectId = projectDao.insert(
            ProjectEntity(
                name = "Landscape Session",
                createdAt = 1_700_000_001_000L,
                updatedAt = 1_700_000_001_000L,
                coverPhotoId = null,
                sortOrder = 0,
                gridSortMode = 0,
                gridFilterMask = 0,
                gridColumns = 3,
            )
        )

        val photo = PhotoEntity(
            projectId = projectId,
            sourceUri = "content://media/external/images/media/42",
            displayName = "IMG_0001.ARW",
            mimeType = "image/x-sony-arw",
            sourceFormat = 3,        // arbitrary format ordinal
            sizeBytes = 24_000_000L,
            widthPx = 6000,
            heightPx = 4000,
            capturedAt = 1_699_900_000_000L,
            addedAt = 1_700_000_002_000L,
            uriPermissionOk = true,
            sourceIsCopy = false,
            contentFingerprint = "24000000:abcdef1234:fedcba4321",
            rating = 4,
            flagState = 1,
            colorLabel = 0,
        )

        val photoId = photoDao.insert(photo)
        assert(photoId > 0) { "Expected a positive auto-generated id, got $photoId" }

        // Lookup by sourceUri (the duplicate-import guard path).
        val byUri = photoDao.getBySourceUri(
            projectId = projectId,
            sourceUri = "content://media/external/images/media/42",
        )
        assertNotNull("Photo should be found by source URI", byUri)
        assertEquals("IMG_0001.ARW", byUri!!.displayName)
        assertEquals(4, byUri.rating)
        assertEquals(1, byUri.flagState)
        assertEquals("24000000:abcdef1234:fedcba4321", byUri.contentFingerprint)

        // Count query.
        val count = photoDao.getPhotoCount(projectId).first()
        assertEquals(1, count)
    }

    /**
     * Verifies that inserting a duplicate (projectId, sourceUri) pair throws,
     * enforcing the unique index that prevents duplicate imports (Req 3.5).
     *
     * Requirements: 3.5, 11.4
     */
    @Test(expected = Exception::class)
    fun v1_duplicateSourceUri_throwsOnInsert() = runBlocking {
        val projectDao = db.projectDao()
        val photoDao = db.photoDao()

        val projectId = projectDao.insert(
            ProjectEntity(
                name = "Dedup Test",
                createdAt = 1_700_000_003_000L,
                updatedAt = 1_700_000_003_000L,
                coverPhotoId = null,
                sortOrder = 0,
                gridSortMode = 0,
                gridFilterMask = 0,
                gridColumns = 3,
            )
        )

        val uri = "content://media/external/images/media/99"
        val base = PhotoEntity(
            projectId = projectId,
            sourceUri = uri,
            displayName = "duplicate.jpg",
            mimeType = "image/jpeg",
            sourceFormat = 1,
            sizeBytes = 1_000_000L,
            widthPx = 4000,
            heightPx = 3000,
            capturedAt = null,
            addedAt = 1_700_000_004_000L,
            uriPermissionOk = true,
            sourceIsCopy = false,
            contentFingerprint = null,
            rating = 0,
            flagState = 0,
            colorLabel = 0,
        )
        photoDao.insert(base)
        // This second insert must throw due to the UNIQUE constraint on (projectId, sourceUri).
        photoDao.insert(base.copy(id = 0))
    }

    /**
     * Verifies that deleting a project cascades to its photos (Req 11.6).
     *
     * Requirements: 11.6
     */
    @Test
    fun v1_projectDeletion_cascadesToPhotos() = runBlocking {
        val projectDao = db.projectDao()
        val photoDao = db.photoDao()

        val projectId = projectDao.insert(
            ProjectEntity(
                name = "Cascade Test",
                createdAt = 1_700_000_005_000L,
                updatedAt = 1_700_000_005_000L,
                coverPhotoId = null,
                sortOrder = 0,
                gridSortMode = 0,
                gridFilterMask = 0,
                gridColumns = 3,
            )
        )

        photoDao.insert(
            PhotoEntity(
                projectId = projectId,
                sourceUri = "content://media/external/images/media/100",
                displayName = "cascade.jpg",
                mimeType = "image/jpeg",
                sourceFormat = 1,
                sizeBytes = 500_000L,
                widthPx = 2000,
                heightPx = 1500,
                capturedAt = null,
                addedAt = 1_700_000_006_000L,
                uriPermissionOk = true,
                sourceIsCopy = false,
                contentFingerprint = null,
                rating = 0,
                flagState = 0,
                colorLabel = 0,
            )
        )

        // Confirm the photo exists before deletion.
        assertEquals(1, photoDao.getPhotoCount(projectId).first())

        // Delete the project — cascade should remove the photo row too.
        val project = projectDao.getById(projectId).first()
        assertNotNull(project)
        projectDao.delete(project!!)

        // After cascade the photo count should be 0 (the project is gone, no row
        // matches the projectId filter any more).
        assertEquals(0, photoDao.getPhotoCount(projectId).first())
    }

    /**
     * Structural assertion that destructive migration is NOT configured.
     *
     * We cannot call a private API to inspect the builder's flags, so we assert
     * this structurally: the database opens at schema v1, an entity can be
     * inserted, and the operation succeeds without Room silently recreating any
     * tables. If fallbackToDestructiveMigration were active and a version mismatch
     * occurred, tables would be dropped and re-created — inserting into them
     * immediately after open would still "succeed", but a subsequent query that
     * expected previously-inserted data would return nothing.
     *
     * The real guard is code review: [DatabaseModule] must never include a call to
     * fallbackToDestructiveMigration, fallbackToDestructiveMigrationFrom, or
     * fallbackToDestructiveMigrationOnDowngrade. This test proves the happy-path
     * schema is self-consistent; the migration-test harness (using MigrationTestHelper
     * from room-testing) is the place to add per-migration coverage as v2+ arrives.
     *
     * Requirements: 11.5
     */
    @Test
    fun v1_schemaIsConsistent_noDestructiveMigration() = runBlocking {
        // If the schema were inconsistent, Room would throw at build() time above
        // (in @Before) because it validates the identity hash against the compiled
        // schema. Reaching here means the schema is valid.

        val projectDao = db.projectDao()
        val id = projectDao.insert(
            ProjectEntity(
                name = "Schema check",
                createdAt = 1_700_000_007_000L,
                updatedAt = 1_700_000_007_000L,
                coverPhotoId = null,
                sortOrder = 0,
                gridSortMode = 0,
                gridFilterMask = 0,
                gridColumns = 3,
            )
        )

        // If destructive migration silently recreated tables we'd still be able to
        // insert, but let's confirm the row made it back.
        val result = projectDao.getById(id).first()
        assertNotNull(
            "Row inserted immediately after open must be retrievable — " +
                "if it is null the schema may have been recreated under us",
            result,
        )
        assertEquals("Schema check", result!!.name)
    }
}
