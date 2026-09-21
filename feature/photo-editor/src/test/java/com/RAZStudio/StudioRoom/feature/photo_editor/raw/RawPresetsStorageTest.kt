/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPresetsStorageTest {

    @Test
    fun removedBundledPresetNamesAreRetired() {
        listOf(
            "RAZDream",
            "Precisa Mod",
            "Provia Outdoor",
            "Moody",
            "Fuji Classic",
            "Auto Expose & Sharp",
            "Creamy & Soft",
            "Film Fade Warm",
            "Film Fade Cool",
            "Soft Portrait",
        ).forEach { name ->
            assertTrue(name, isRetiredBundledPresetName(name))
        }
    }

    @Test
    fun userPresetNamesAreNotRetired() {
        assertFalse(isRetiredBundledPresetName("My Portrait Grade"))
    }
}
