/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 */

package com.RAZStudio.StudioRoom.feature.root.presentation.screenLogic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIntentPolicyTest {

    @Test
    fun mainLauncherIntentResetsNavigationToMain() {
        assertTrue(
            shouldResetToMain(
                isMainAction = true,
                hasLauncherCategory = true,
            ),
        )
    }

    @Test
    fun deepLinksAndSharesDoNotResetNavigation() {
        assertFalse(shouldResetToMain(isMainAction = false, hasLauncherCategory = true))
        assertFalse(shouldResetToMain(isMainAction = true, hasLauncherCategory = false))
        assertFalse(shouldResetToMain(isMainAction = false, hasLauncherCategory = false))
    }
}
