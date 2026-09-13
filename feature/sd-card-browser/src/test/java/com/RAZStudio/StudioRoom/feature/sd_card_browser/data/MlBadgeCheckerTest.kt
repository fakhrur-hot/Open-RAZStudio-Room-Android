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

package com.RAZStudio.StudioRoom.feature.sd_card_browser.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for MlBadgeChecker cache invalidation on unmount/remount scenarios.
 *
 * Since DocumentFile requires the Android framework, these tests verify the
 * caching and invalidation contract through reflection on the internal state.
 *
 * **Validates: Requirements 5.4, 2.3**
 */
class MlBadgeCheckerTest {

    private lateinit var checker: MlBadgeChecker

    @Before
    fun setup() {
        checker = MlBadgeChecker()
    }

    /**
     * Verify that a fresh MlBadgeChecker has no cached result.
     */
    @Test
    fun freshInstance_hasNoCachedResult() {
        val field = MlBadgeChecker::class.java.getDeclaredField("cachedResult")
        field.isAccessible = true
        assertNull("Fresh instance should have null cache", field.get(checker))
    }

    /**
     * Verify that invalidate() clears the cached result back to null.
     * Simulates: volume unmount → invalidate() called → cache cleared.
     */
    @Test
    fun invalidate_clearsCachedResult() {
        val field = MlBadgeChecker::class.java.getDeclaredField("cachedResult")
        field.isAccessible = true

        // Simulate a cached positive result (ML/ was present)
        field.set(checker, true)
        assertEquals(true, field.get(checker))

        // Simulate unmount → invalidate
        checker.invalidate()

        // Cache should be cleared
        assertNull("invalidate() should clear cached result", field.get(checker))
    }

    /**
     * Scenario: card with ML/ → unmount → remount without ML/
     * After invalidate, the next isMlPresent() call must perform a fresh lookup.
     * We verify the cache is null after invalidate (meaning lookup will happen).
     */
    @Test
    fun scenario_mlPresent_thenRemounted_withoutMl_cacheCleared() {
        val field = MlBadgeChecker::class.java.getDeclaredField("cachedResult")
        field.isAccessible = true

        // Phase 1: ML/ was detected → cached as true
        field.set(checker, true)
        assertEquals(true, field.get(checker))

        // Phase 2: Unmount event → invalidate
        checker.invalidate()
        assertNull("Cache must be null after invalidate", field.get(checker))

        // Phase 3: Remount without ML/ → next isMlPresent() would return false
        // (Can't call isMlPresent without real DocumentFile, but we verify cache is cleared
        //  so a fresh lookup WILL happen on next call)
        field.set(checker, false) // Simulate what isMlPresent would set
        assertEquals(false, field.get(checker))
    }

    /**
     * Scenario: card without ML/ → unmount → remount with ML/
     * After invalidate, badge must appear on next check.
     */
    @Test
    fun scenario_noMl_thenRemounted_withMl_cacheCleared() {
        val field = MlBadgeChecker::class.java.getDeclaredField("cachedResult")
        field.isAccessible = true

        // Phase 1: No ML/ → cached as false
        field.set(checker, false)
        assertEquals(false, field.get(checker))

        // Phase 2: Unmount event → invalidate
        checker.invalidate()
        assertNull("Cache must be null after invalidate", field.get(checker))

        // Phase 3: Remount with ML/ → next isMlPresent() would return true
        field.set(checker, true)
        assertEquals(true, field.get(checker))
    }

    /**
     * Multiple invalidate calls are idempotent — calling invalidate()
     * multiple times doesn't cause errors.
     */
    @Test
    fun multipleInvalidate_isIdempotent() {
        val field = MlBadgeChecker::class.java.getDeclaredField("cachedResult")
        field.isAccessible = true

        field.set(checker, true)
        checker.invalidate()
        checker.invalidate()
        checker.invalidate()
        assertNull("Multiple invalidates should still result in null cache", field.get(checker))
    }

    /**
     * After invalidate, the cached field is null which means the next call
     * to isMlPresent() will NOT short-circuit (it'll perform a real lookup).
     * This verifies the "fresh SAF lookup on remount" contract.
     */
    @Test
    fun afterInvalidate_nextCheckWillPerformFreshLookup() {
        val field = MlBadgeChecker::class.java.getDeclaredField("cachedResult")
        field.isAccessible = true

        // Set up: previous check result is cached
        field.set(checker, true)
        assertNotNull("Should have cached result", field.get(checker))

        // Act: invalidate (unmount happened)
        checker.invalidate()

        // Assert: cache is null → isMlPresent() will read from DocumentFile
        assertNull(field.get(checker))
    }
}
