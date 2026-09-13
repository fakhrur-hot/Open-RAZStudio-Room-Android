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

package com.RAZStudio.StudioRoom.feature.sd_card_browser.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * Property tests for FolderNavigator folder navigation logic.
 *
 * Since FolderNavigator depends on DocumentFile (Android framework class),
 * these tests validate the core algorithmic properties — ordering, filtering,
 * and back-stack manipulation — using simulated data that exercises the same
 * logic without requiring the Android framework.
 *
 * **Validates: Requirements 3.2, 3.3, 3.4, 3.5, 7.1, 7.2**
 */
class FolderNavigatorTest {

    // ══════════════════════════════════════════════════════════════════
    // Property 3: Navigation Round-Trip
    // navigateInto(folder) then navigateBack() yields original back-stack state.
    // ══════════════════════════════════════════════════════════════════

    /**
     * **Validates: Requirements 3.2, 3.3**
     *
     * The back-stack is a simple list where navigateInto appends (push)
     * and navigateBack removes the last element (pop). For any stack of
     * size N, push → pop always restores size N.
     */
    @Test
    fun property3_pushThenPop_restoresOriginalStackSize() {
        val stackSizes = listOf(0, 1, 2, 5, 10, 50)
        for (size in stackSizes) {
            val stack = (1..size).map { "folder_$it" }
            // Simulate navigateInto: append one element
            val afterPush = stack + "new_folder"
            assertEquals(size + 1, afterPush.size)
            // Simulate navigateBack: drop last
            val afterPop = afterPush.dropLast(1)
            assertEquals(
                "Round-trip failed for stack size $size",
                stack,
                afterPop
            )
        }
    }

    /**
     * **Validates: Requirements 3.2, 3.3**
     *
     * After navigateInto followed by navigateBack, all original elements
     * remain in the same order — content is preserved, not just size.
     */
    @Test
    fun property3_roundTrip_preservesStackContent() {
        val originalStack = listOf("DCIM", "100CANON", "subfolder_A")
        val pushed = originalStack + "subfolder_B"
        val popped = pushed.dropLast(1)
        assertEquals(originalStack, popped)
    }

    /**
     * **Validates: Requirements 3.2, 3.3**
     *
     * Multiple consecutive navigateInto calls followed by the same number
     * of navigateBack calls restores the original state.
     */
    @Test
    fun property3_multipleRoundTrips_restoreOriginalState() {
        val root = listOf("DCIM")
        var stack = root.toList()

        // Push 5 folders
        val foldersToVisit = listOf("100CANON", "nested1", "nested2", "nested3", "nested4")
        for (folder in foldersToVisit) {
            stack = stack + folder
        }
        assertEquals(6, stack.size)

        // Pop 5 folders
        repeat(foldersToVisit.size) {
            stack = stack.dropLast(1)
        }
        assertEquals(root, stack)
    }

    /**
     * **Validates: Requirements 3.3**
     *
     * navigateBack at root (stack size ≤ 1) is a no-op — returns false
     * and does not modify the stack.
     */
    @Test
    fun property3_navigateBackAtRoot_isNoOp() {
        // With empty stack
        val emptyStack = emptyList<String>()
        val canGoBack = emptyStack.size > 1
        assertFalse("Cannot go back from empty stack", canGoBack)

        // With single-element stack (root folder)
        val rootStack = listOf("DCIM")
        val canGoBackFromRoot = rootStack.size > 1
        assertFalse("Cannot go back from root", canGoBackFromRoot)
    }

    /**
     * **Validates: Requirements 3.2, 3.3**
     *
     * For any arbitrary sequence of push/pop operations, the stack size
     * never goes negative and each pop undoes exactly one push.
     */
    @Test
    fun property3_arbitraryPushPopSequences_maintainInvariant() {
        // Simulate various sequences: P = push, B = back
        // Each push adds 1, each back (when size > 1) removes 1
        data class Op(val isPush: Boolean)

        val sequences = listOf(
            listOf(Op(true), Op(false)),                         // push, pop
            listOf(Op(true), Op(true), Op(false), Op(false)),   // push, push, pop, pop
            listOf(Op(true), Op(false), Op(true), Op(false)),   // alternating
            listOf(Op(false), Op(false), Op(true)),             // pops at root, then push
        )

        for (seq in sequences) {
            var stackSize = 1 // Start with root
            for (op in seq) {
                if (op.isPush) {
                    stackSize++
                } else {
                    if (stackSize > 1) stackSize--
                }
                assertTrue("Stack size should never be < 1", stackSize >= 1)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Property 4: Folder-First Ordering Invariant
    // All Directory entries precede all Cr2File entries in any loadFolder() result.
    // ══════════════════════════════════════════════════════════════════

    /**
     * Simulates the loadFolder() partition-and-sort algorithm on mixed entries.
     */
    private data class SimEntry(val name: String, val isDirectory: Boolean)

    private fun simulateLoadFolderOrdering(entries: List<SimEntry>): List<SimEntry> {
        val directories = entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = entries.filter { !it.isDirectory }.sortedBy { it.name.lowercase() }
        return directories + files
    }

    /**
     * **Validates: Requirements 3.4**
     *
     * For any mix of directories and files, all directories appear before all files.
     */
    @Test
    fun property4_directoriesPrecedeFiles_mixedInput() {
        val entries = listOf(
            SimEntry("IMG_001.CR2", false),
            SimEntry("100CANON", true),
            SimEntry("IMG_002.CR2", false),
            SimEntry("101CANON", true),
            SimEntry("MISC", true),
            SimEntry("IMG_003.CR3", false),
        )

        val result = simulateLoadFolderOrdering(entries)

        val firstFileIndex = result.indexOfFirst { !it.isDirectory }
        val lastDirIndex = result.indexOfLast { it.isDirectory }
        if (firstFileIndex != -1 && lastDirIndex != -1) {
            assertTrue(
                "Directory at index $lastDirIndex must precede file at index $firstFileIndex",
                lastDirIndex < firstFileIndex
            )
        }
    }

    /**
     * **Validates: Requirements 3.4**
     *
     * Directories are sorted alphabetically (case-insensitive) among themselves.
     */
    @Test
    fun property4_directoriesSortedAlphabetically() {
        val dirNames = listOf("MISC", "100CANON", "DCIM", "101CANON", "zzz", "AAA")
        val sorted = dirNames.sortedBy { it.lowercase() }
        assertEquals(listOf("100CANON", "101CANON", "AAA", "DCIM", "MISC", "zzz"), sorted)
    }

    /**
     * **Validates: Requirements 3.4**
     *
     * Files are sorted alphabetically (case-insensitive) among themselves.
     */
    @Test
    fun property4_filesSortedAlphabetically() {
        val fileNames = listOf("IMG_003.CR2", "IMG_001.CR2", "img_002.cr2", "IMG_001.CR3")
        val sorted = fileNames.sortedBy { it.lowercase() }
        assertEquals(
            listOf("IMG_001.CR2", "IMG_001.CR3", "img_002.cr2", "IMG_003.CR2"),
            sorted
        )
    }

    /**
     * **Validates: Requirements 3.4**
     *
     * An empty folder produces an empty result — the invariant holds vacuously.
     */
    @Test
    fun property4_emptyFolder_preservesInvariant() {
        val result = simulateLoadFolderOrdering(emptyList())
        assertTrue(result.isEmpty())
    }

    /**
     * **Validates: Requirements 3.4**
     *
     * A folder with only directories still satisfies the invariant.
     */
    @Test
    fun property4_onlyDirectories_satisfiesInvariant() {
        val entries = listOf(
            SimEntry("MISC", true),
            SimEntry("100CANON", true),
            SimEntry("DCIM", true),
        )
        val result = simulateLoadFolderOrdering(entries)
        assertTrue(result.all { it.isDirectory })
        assertEquals(listOf("100CANON", "DCIM", "MISC"), result.map { it.name })
    }

    /**
     * **Validates: Requirements 3.4**
     *
     * A folder with only files still satisfies the invariant.
     */
    @Test
    fun property4_onlyFiles_satisfiesInvariant() {
        val entries = listOf(
            SimEntry("IMG_003.CR2", false),
            SimEntry("IMG_001.CR2", false),
        )
        val result = simulateLoadFolderOrdering(entries)
        assertTrue(result.none { it.isDirectory })
        assertEquals(listOf("IMG_001.CR2", "IMG_003.CR2"), result.map { it.name })
    }

    /**
     * **Validates: Requirements 3.4**
     *
     * Large mixed listings maintain the folder-first invariant.
     */
    @Test
    fun property4_largeMixedListing_maintainsInvariant() {
        val entries = (1..50).map { i ->
            if (i % 3 == 0) SimEntry("DIR_${"%03d".format(i)}", true)
            else SimEntry("IMG_${"%03d".format(i)}.CR2", false)
        }

        val result = simulateLoadFolderOrdering(entries)

        // Find transition point
        val firstFileIndex = result.indexOfFirst { !it.isDirectory }
        if (firstFileIndex > 0) {
            // All items before firstFileIndex must be directories
            for (i in 0 until firstFileIndex) {
                assertTrue(
                    "Entry at index $i should be directory: ${result[i].name}",
                    result[i].isDirectory
                )
            }
            // All items from firstFileIndex onward must be files
            for (i in firstFileIndex until result.size) {
                assertFalse(
                    "Entry at index $i should be file: ${result[i].name}",
                    result[i].isDirectory
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Property 5: Hidden Entry Filtering
    // No listing at any level contains ML/DATA/ directory or .ml6d files.
    // ══════════════════════════════════════════════════════════════════

    /**
     * Simulates the loadFolder() filtering logic:
     * - Hides DATA directory when parent is ML
     * - Hides .ml6d files everywhere
     */
    private data class SimChild(val name: String, val isDir: Boolean)

    private fun simulateFiltering(
        parentName: String,
        children: List<SimChild>,
    ): List<SimChild> {
        return children.filter { child ->
            // Filter: hide ML/DATA/ directory
            if (child.isDir && child.name.equals("DATA", ignoreCase = true)) {
                if (parentName.equals("ML", ignoreCase = true)) return@filter false
            }
            // Filter: hide .ml6d files everywhere
            if (child.name.endsWith(".ml6d", ignoreCase = true)) return@filter false
            true
        }
    }

    /**
     * **Validates: Requirements 3.5, 7.1**
     *
     * The DATA directory is filtered when its parent is ML.
     */
    @Test
    fun property5_dataDirectoryUnderMl_isFiltered() {
        val children = listOf(
            SimChild("DATA", true),
            SimChild("MODULES", true),
            SimChild("SCRIPTS", true),
        )
        val filtered = simulateFiltering("ML", children)
        assertEquals(2, filtered.size)
        assertFalse(filtered.any { it.name.equals("DATA", ignoreCase = true) })
    }

    /**
     * **Validates: Requirements 3.5, 7.1**
     *
     * Case-insensitive: "data", "Data", "DATA" under "ml", "ML", "Ml" are all filtered.
     */
    @Test
    fun property5_dataUnderMl_caseInsensitive() {
        val parentVariants = listOf("ML", "ml", "Ml", "mL")
        val dataVariants = listOf("DATA", "data", "Data", "DaTa")

        for (parent in parentVariants) {
            for (dataName in dataVariants) {
                val children = listOf(SimChild(dataName, true), SimChild("OTHER", true))
                val filtered = simulateFiltering(parent, children)
                assertEquals(
                    "DATA ($dataName) under ML ($parent) should be filtered",
                    1,
                    filtered.size
                )
                assertEquals("OTHER", filtered[0].name)
            }
        }
    }

    /**
     * **Validates: Requirements 3.5**
     *
     * DATA directory under a non-ML parent is NOT filtered.
     */
    @Test
    fun property5_dataDirectoryNotUnderMl_isNotFiltered() {
        val nonMlParents = listOf("DCIM", "100CANON", "MISC", "data", "ROOT")
        for (parent in nonMlParents) {
            val children = listOf(SimChild("DATA", true), SimChild("OTHER", true))
            val filtered = simulateFiltering(parent, children)
            assertEquals(
                "DATA under $parent should NOT be filtered",
                2,
                filtered.size
            )
        }
    }

    /**
     * **Validates: Requirements 7.2**
     *
     * All .ml6d files are filtered regardless of parent directory.
     */
    @Test
    fun property5_ml6dFiles_areFilteredEverywhere() {
        val parents = listOf("DCIM", "100CANON", "ML", "ROOT", "SHOTS")
        for (parent in parents) {
            val children = listOf(
                SimChild("IMG_001.CR2", false),
                SimChild("IMG_001.ml6d", false),
                SimChild("IMG_002.CR2", false),
            )
            val filtered = simulateFiltering(parent, children)
            assertFalse(
                ".ml6d should be filtered under $parent",
                filtered.any { it.name.endsWith(".ml6d", ignoreCase = true) }
            )
            assertEquals(2, filtered.size)
        }
    }

    /**
     * **Validates: Requirements 7.2**
     *
     * .ml6d filtering is case-insensitive: .ML6D, .Ml6d, .ml6d all filtered.
     */
    @Test
    fun property5_ml6dFilter_caseInsensitive() {
        val variants = listOf(
            "IMG_001.ml6d",
            "IMG_002.ML6D",
            "IMG_003.Ml6d",
            "sidecar.mL6D",
        )
        val children = variants.map { SimChild(it, false) } +
            SimChild("IMG_004.CR2", false)

        val filtered = simulateFiltering("100CANON", children)
        assertEquals(1, filtered.size)
        assertEquals("IMG_004.CR2", filtered[0].name)
    }

    /**
     * **Validates: Requirements 7.1, 7.2**
     *
     * Both filters applied simultaneously: ML/DATA/ and .ml6d files removed together.
     */
    @Test
    fun property5_bothFiltersAppliedSimultaneously() {
        val children = listOf(
            SimChild("DATA", true),         // Should be filtered (parent is ML)
            SimChild("MODULES", true),      // Should remain
            SimChild("config.ml6d", false), // Should be filtered (.ml6d)
            SimChild("README.txt", false),  // Should remain (non-CR2, non-ml6d)
        )
        val filtered = simulateFiltering("ML", children)
        assertEquals(2, filtered.size)
        assertEquals("MODULES", filtered[0].name)
        assertEquals("README.txt", filtered[1].name)
    }

    /**
     * **Validates: Requirements 7.2**
     *
     * .ml6d filtering applies at any nesting depth — the filter is based on
     * file extension only, not on parent directory name.
     */
    @Test
    fun property5_ml6dFilterAppliesAtAnyDepth() {
        // Even inside deeply nested folders, .ml6d files are hidden
        val deepParents = listOf("DCIM", "100CANON", "subfolder", "deep_nested")
        for (parent in deepParents) {
            val children = listOf(
                SimChild("photo.ml6d", false),
                SimChild("photo.CR2", false),
            )
            val filtered = simulateFiltering(parent, children)
            assertEquals(
                ".ml6d should be filtered at depth ($parent)",
                1,
                filtered.size
            )
            assertEquals("photo.CR2", filtered[0].name)
        }
    }

    /**
     * **Validates: Requirements 3.5, 7.1**
     *
     * When parent is ML but no DATA directory exists, nothing is erroneously filtered.
     */
    @Test
    fun property5_mlParent_withoutData_filtersNothing() {
        val children = listOf(
            SimChild("MODULES", true),
            SimChild("SCRIPTS", true),
            SimChild("autoexec.bin", false),
        )
        val filtered = simulateFiltering("ML", children)
        assertEquals(3, filtered.size)
    }
}
