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

package com.RAZStudio.StudioRoom.core.database.model

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Builds the grid's SQL from persisted sort + filter state
 * (Requirements 7.2, 7.3, 7.4).
 *
 * Sorting and filtering are SQL so they run over the indexes and stay paged. The
 * spec forbids loading a project into memory to sort it — at project scale that
 * is the difference between a constant-cost query and a whole-library read.
 *
 * Only the ORDER BY column comes from code, and it comes from a closed enum
 * ([GridSort.column]) rather than any caller-supplied string. Every value the
 * user can influence is bound as an argument.
 */
object GridQueryBuilder {

    private const val SELECT = """
        SELECT p.id, p.projectId, p.displayName, p.sourceUri, p.sourceFormat,
               p.capturedAt, p.addedAt, p.uriPermissionOk, p.sourceIsCopy,
               p.rating, p.flagState, p.colorLabel,
               p.widthPx, p.heightPx, p.sizeBytes,
               p.cameraModel, p.lensModel, p.iso, p.apertureF,
               p.shutterSpeed, p.focalLengthMm, p.keywords, p.deletedAt,
               t.path AS thumbPath,
               t.generatedAt AS thumbGeneratedAt,
               CASE WHEN e.photoId IS NULL OR e.isNeutral = 1 THEN 0 ELSE 1 END AS hasEdits,
               e.sidecarBesideOriginal AS sidecarBesideOriginal
        FROM photos p
        LEFT JOIN thumbnails t ON t.photoId = p.id
        LEFT JOIN edits e ON e.photoId = p.id
    """

    fun grid(
        projectId: Long?,
        sort: GridSort,
        descending: Boolean,
        filter: GridFilter,
        search: String = "",
        trashed: Boolean = false,
        stacked: Boolean = false,
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val where = scopeClause(projectId, args, trashed)
        appendFilter(where, args, filter)
        appendSearch(where, args, search)

        val direction = if (descending) "DESC" else "ASC"
        // Tie-break on id so paging is stable: without it, rows with equal sort
        // keys can reorder between page loads and items duplicate or vanish.
        val order = "ORDER BY ${sort.column} $direction, p.id $direction"

        return SimpleSQLiteQuery("$SELECT $where $order", args.toTypedArray())
    }

    /**
     * Ids of every photo matching [filter], in display order — feeds
     * "select all matching filter" (Requirement 8.7). Same WHERE/ORDER logic
     * as [grid] so the selection is exactly what the user is looking at.
     */
    fun ids(
        projectId: Long?,
        sort: GridSort,
        descending: Boolean,
        filter: GridFilter,
        search: String = "",
        trashed: Boolean = false,
        stacked: Boolean = false,
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val where = scopeClause(projectId, args, trashed)
        appendFilter(where, args, filter)
        appendSearch(where, args, search)
        val direction = if (descending) "DESC" else "ASC"
        return SimpleSQLiteQuery(
            "SELECT p.id FROM photos p $where ORDER BY ${sort.column} $direction, p.id $direction",
            args.toTypedArray(),
        )
    }

    fun count(
        projectId: Long?,
        filter: GridFilter,
        search: String = "",
        trashed: Boolean = false,
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val where = scopeClause(projectId, args, trashed)
        appendFilter(where, args, filter)
        appendSearch(where, args, search)
        return SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM photos p $where",
            args.toTypedArray(),
        )
    }

    /**
     * `WHERE` seed. A null [projectId] is the library-wide "All photos" scope —
     * `1 = 1` keeps every later clause an unconditional ` AND `, so no caller
     * has to know whether it is writing the first predicate.
     */
    private fun scopeClause(
        projectId: Long?,
        args: MutableList<Any>,
        trashed: Boolean = false,
    ): StringBuilder {
        val where = if (projectId == null) StringBuilder("WHERE 1 = 1")
            else StringBuilder("WHERE p.projectId = ?").also { args.add(projectId) }
        // Schema v4: trashed rows are invisible everywhere EXCEPT the trash view.
        where.append(if (trashed) " AND p.deletedAt IS NOT NULL" else " AND p.deletedAt IS NULL")
        return where
    }

    /**
     * Free-text search over filename, camera, lens and keywords (schema v3).
     * Space-separated terms are ANDed — "sony 35" finds Sony frames shot at
     * 35 mm, which is how people actually narrow a shoot down. Each term is a
     * substring match, and `\` escapes the SQL wildcards so a literal % or _
     * typed by the user cannot match everything.
     */
    private fun appendSearch(where: StringBuilder, args: MutableList<Any>, search: String) {
        val terms = search.trim().split(' ').filter { it.isNotBlank() }.take(6)
        for (term in terms) {
            val like = "%" + term.lowercase().replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%"
            where.append(
                " AND (LOWER(p.displayName) LIKE ? ESCAPE '\\'" +
                    " OR LOWER(p.cameraModel) LIKE ? ESCAPE '\\'" +
                    " OR LOWER(p.lensModel) LIKE ? ESCAPE '\\'" +
                    " OR p.keywords LIKE ? ESCAPE '\\')"
            )
            repeat(4) { args.add(like) }
        }
    }

    private fun appendFilter(
        where: StringBuilder,
        args: MutableList<Any>,
        filter: GridFilter,
    ) {
        // Flag states: an empty selection means "no flag filter", not "show
        // nothing" — a filter that hides everything by default would look like a
        // bug the first time the user opened the panel.
        val states = buildList {
            if (filter.showUnflagged) add(0)
            if (filter.showFlagged) add(1)
            if (filter.showRejected) add(2)
        }
        if (states.isNotEmpty() && states.size < 3) {
            where.append(" AND p.flagState IN (")
            where.append(states.joinToString(",") { "?" })
            where.append(")")
            args.addAll(states)
        }

        if (filter.minRating > 0) {
            where.append(" AND p.rating >= ?")
            args.add(filter.minRating)
        }

        if (filter.colorLabel > 0) {
            where.append(" AND p.colorLabel = ?")
            args.add(filter.colorLabel)
        }
    }
}
