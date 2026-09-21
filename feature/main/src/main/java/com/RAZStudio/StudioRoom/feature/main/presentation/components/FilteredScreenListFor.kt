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

package com.RAZStudio.StudioRoom.feature.main.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ContextUtils.getStringLocalized
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.core.utils.appContext
import java.util.Locale

/**
 * Alpha-trial gate result, exposed as Compose state so the main page can react
 * once the online date probe lands. The initial value uses the cached/blocking
 * decision (system clock); the LaunchedEffect refreshes it with the online
 * result if available. See [TrialExpiry] for the date-resolution policy.
 */
@Composable
internal fun rememberTrialActive(): State<Boolean> {
    var active by remember { mutableStateOf(TrialExpiry.isActiveBlocking()) }
    LaunchedEffect(Unit) {
        active = TrialExpiry.isActive()
    }
    return remember { derivedStateOf { active } }
}

@Composable
internal fun filteredScreenListFor(
    screenSearchKeyword: String,
    selectedNavigationItem: Int,
    showScreenSearch: Boolean
): State<List<Screen>> {
    val settingsState = LocalSettingsState.current
    val canSearchScreens = settingsState.screensSearchEnabled
    val trialActive by rememberTrialActive()

    val screenList by remember(settingsState.screenList, trialActive) {
        derivedStateOf {
            if (!trialActive) {
                emptyList()
            } else {
                (settingsState.screenList.mapNotNull {
                    Screen.entries.find { s -> s.id == it }
                }.takeIf { it.isNotEmpty() } ?: Screen.entries)
                    .filter { it.id in Screen.MAIN_PAGE_SCREEN_IDS }
                    // Order by the MAIN_PAGE_SCREEN_IDS declaration rather than
                    // by the persisted screenList.
                    //
                    // The persisted order cannot express "new screen goes first":
                    // RootComponent reconciles unknown ids by APPENDING them, so
                    // a newly added screen always lands at the bottom of the main
                    // page no matter where it sits in typedEntries. That is why
                    // Gallery Workspace (id 77) appeared last despite being the
                    // first entry of the Edit group.
                    //
                    // MAIN_PAGE_SCREEN_IDS is a setOf(...), i.e. insertion
                    // ordered, so it can carry both membership and order — one
                    // source of truth for what the main page shows and in what
                    // sequence.
                    .sortedBy { screen ->
                        Screen.MAIN_PAGE_SCREEN_IDS.indexOfFirst { it == screen.id }
                    }
                    .filter { com.RAZStudio.StudioRoom.core.ui.edition.EditionCapabilities.includeOnHome(it) }
            }
        }
    }

    return remember(
        settingsState.groupOptionsByTypes,
        settingsState.showFavoriteToolsInGroupedMode,
        settingsState.favoriteScreenList,
        screenSearchKeyword,
        screenList,
        selectedNavigationItem,
        showScreenSearch
    ) {
        derivedStateOf {
            screenList.let { screens ->
                if (screenSearchKeyword.isNotEmpty() && canSearchScreens) {
                    screens.filter {
                        val string =
                            appContext.getString(it.title) + " " + appContext.getString(it.subtitle)
                        val stringEn = appContext.getStringLocalized(it.title, Locale.ENGLISH)
                            .plus(" ")
                            .plus(appContext.getStringLocalized(it.subtitle, Locale.ENGLISH))
                        stringEn.contains(other = screenSearchKeyword, ignoreCase = true).or(
                            string.contains(other = screenSearchKeyword, ignoreCase = true)
                        )
                    }
                } else screens
            }
        }
    }
}
