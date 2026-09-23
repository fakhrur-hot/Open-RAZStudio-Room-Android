/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */
package com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation

import com.arkivanov.decompose.ComponentContext
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class UnstableFeatureScreensImpl @Inject constructor() {
    fun lutCreator(
        componentContext: ComponentContext,
        onGoBack: () -> Unit,
        onNavigate: (Screen) -> Unit,
    ): NavigationChild = NavigationChild.Unavailable

    fun videoEditor(
        componentContext: ComponentContext,
        onGoBack: () -> Unit,
        onNavigate: (Screen) -> Unit,
    ): NavigationChild = NavigationChild.Unavailable
}
