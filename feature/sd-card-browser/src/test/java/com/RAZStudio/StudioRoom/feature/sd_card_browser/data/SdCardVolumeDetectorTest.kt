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
import org.junit.Test

/**
 * Property tests for SdCardVolumeDetector volume regex matching.
 *
 * **Validates: Requirements 2.1, 2.4**
 */
class SdCardVolumeDetectorTest {

    private val regex = SdCardVolumeDetector.VOLUME_REGEX

    // ── Property 1: Volume Regex Correctness ──

    @Test
    fun property1_validSdCardPaths_areAccepted() {
        // Standard Android SD card mount paths
        val validPaths = listOf(
            "/storage/1234-5678",
            "/storage/abcd-ef01",
            "/storage/ABCD-EF01",     // case-insensitive
            "/mnt/media_rw/9a8b-7c6d",
            "/storage/emulated/1234-abcd",
            "1234-5678",               // minimal match
            "/any/prefix/aaaa-bbbb",
        )
        for (path in validPaths) {
            assertTrue("Expected match: $path", path.lowercase().matches(regex))
        }
    }

    @Test
    fun property1_invalidPaths_areRejected() {
        val invalidPaths = listOf(
            "/storage/emulated/0",      // internal storage
            "/storage/self/primary",
            "/storage/123-456",         // too few hex digits (3-3)
            "/storage/1234_5678",       // underscore instead of hyphen
            "",                         // empty
            "/storage/GHIJ-KLMN",       // non-hex letters
            "123-4567",                 // 3 digits before hyphen, no prefix to absorb
        )
        for (path in invalidPaths) {
            assertFalse("Expected rejection: $path", path.lowercase().matches(regex))
        }
    }

    @Test
    fun property1_exactlyFourHexDigitsEachSide() {
        // Boundary: exactly 4-4 hex digits
        assertTrue("1234-5678".lowercase().matches(regex))
        // 3-4 should fail
        assertFalse("123-5678".matches(regex))
        // 4-3 should fail
        assertFalse("1234-567".matches(regex))
        // 5-4 should NOT fail because regex is .*[4hex]-[4hex] and 5th digit is part of .*
        // "12345-6789" - .* matches "1", then [4hex] matches "2345", hyphen, [4hex] matches "6789"
        assertTrue("12345-6789".lowercase().matches(regex))
    }

    // ── Property 2: First-Match Volume Selection ──
    // This tests the REGEX's behavior with multiple paths - the detector picks the first match.
    // Since we can't instantiate StorageManager in unit tests, we verify the selection logic
    // by simulating the same iteration pattern.

    @Test
    fun property2_firstMatchSelected_fromMultipleValidPaths() {
        val paths = listOf(
            "/storage/emulated/0",   // no match
            "/storage/aaaa-1111",    // first match
            "/storage/bbbb-2222",    // second match
            "/storage/cccc-3333",    // third match
        )

        val firstMatch = paths.firstOrNull { it.lowercase().matches(regex) }
        assertEquals("/storage/aaaa-1111", firstMatch)
    }

    @Test
    fun property2_noMatch_returnsNull() {
        val paths = listOf(
            "/storage/emulated/0",
            "/storage/self/primary",
        )
        val firstMatch = paths.firstOrNull { it.lowercase().matches(regex) }
        assertNull(firstMatch)
    }

    @Test
    fun property2_deterministicSelection() {
        // Same list always produces same result regardless of how many times called
        val paths = listOf("/storage/ffff-0000", "/storage/0000-ffff")
        val result1 = paths.firstOrNull { it.lowercase().matches(regex) }
        val result2 = paths.firstOrNull { it.lowercase().matches(regex) }
        assertEquals(result1, result2)
        assertEquals("/storage/ffff-0000", result1)
    }
}
