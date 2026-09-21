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

import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import com.RAZStudio.StudioRoom.core.resources.Icons
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.Bookmark
import com.RAZStudio.StudioRoom.core.resources.icons.BookmarkOff
import com.RAZStudio.StudioRoom.core.resources.icons.BookmarkRemove
import com.RAZStudio.StudioRoom.core.resources.icons.ContentPaste
import com.RAZStudio.StudioRoom.core.resources.icons.ContentPasteOff
import com.RAZStudio.StudioRoom.core.resources.icons.LayersSearchOutline
import com.RAZStudio.StudioRoom.core.resources.icons.SearchOff
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState
import com.RAZStudio.StudioRoom.core.ui.utils.helper.AppToastHost
import com.RAZStudio.StudioRoom.core.ui.utils.helper.clipList
import com.RAZStudio.StudioRoom.core.ui.utils.helper.rememberClipboardData
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedBadge
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedButton
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedFloatingActionButton
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedFloatingActionButtonType
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedIconButton
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.enhancedFlingBehavior
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.other.BoxAnimatedVisibility
import com.RAZStudio.StudioRoom.core.ui.widget.preferences.PreferenceItemOverload
import com.RAZStudio.StudioRoom.core.utils.getString

@Composable
internal fun RowScope.ScreenPreferenceSelection(
    currentScreenList: List<Screen>,
    showScreenSearch: Boolean,
    screenSearchKeyword: String,
    isGrid: Boolean,
    isSheetSlideable: Boolean,
    onGetClipList: (List<Uri>) -> Unit,
    onNavigationBarItemChange: (Int) -> Unit,
    onNavigateToScreenWithPopUpTo: (Screen) -> Unit,
    onChangeShowScreenSearch: (Boolean) -> Unit,
    onToggleFavorite: (Screen) -> Unit,
    showNavRail: Boolean,
) {
    val settingsState = LocalSettingsState.current
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val canSearchScreens = settingsState.screensSearchEnabled
    val isSearching =
        showScreenSearch && screenSearchKeyword.isNotEmpty() && canSearchScreens
    val isScreenSelectionLauncherMode = settingsState.isScreenSelectionLauncherMode
    val showFavoriteControls =
        !settingsState.groupOptionsByTypes || settingsState.showFavoriteToolsInGroupedMode

    AnimatedContent(
        modifier = Modifier
            .weight(1f)
            .widthIn(min = 1.dp),
        targetState = remember(currentScreenList, isSearching, settingsState.favoriteScreenList) {
            Triple(
                currentScreenList.isNotEmpty(),
                isSearching,
                settingsState.favoriteScreenList.isEmpty()
            )
        },
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        }
    ) { (hasScreens, isSearching, noFavorites) ->
        if (hasScreens) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                currentScreenList.forEach { screen ->
                    val wired = com.RAZStudio.StudioRoom.core.ui.edition.EditionCapabilities.isHomeWired(screen)
                    val unstableNote =
                        (screen is Screen.LutCreator || screen is Screen.VideoEditor) &&
                            com.RAZStudio.StudioRoom.core.ui.edition.EditionCapabilities.includeOnHome(screen)
                    PreferenceItemOverload(
                        onClick = { if (wired) onNavigateToScreenWithPopUpTo(screen) },
                        enabled = wired,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        resultModifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        shape = ShapeDefaults.default,
                        title = stringResource(screen.title),
                        subtitle = buildString {
                            append(stringResource(screen.subtitle))
                            if (unstableNote) {
                                append('\n')
                                append(stringResource(R.string.edition_not_in_release))
                            }
                        },
                        startIcon = {
                            screen.icon?.let {
                                Icon(imageVector = it, contentDescription = null)
                            }
                        }
                    )
                }
            }
        } else {
            if (!isSearching && noFavorites) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(Modifier.weight(1f))
                    val trialActive by rememberTrialActive()
                    Text(
                        text = stringResource(
                            if (trialActive) R.string.no_favorite_options_selected
                            else R.string.alpha_trial_expired
                        ),
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(
                            start = 24.dp,
                            end = 24.dp,
                            top = 8.dp,
                            bottom = 8.dp
                        )
                    )
                    Icon(
                        imageVector = Icons.Outlined.BookmarkOff,
                        contentDescription = null,
                        modifier = Modifier
                            .weight(2f)
                            .sizeIn(maxHeight = 140.dp, maxWidth = 140.dp)
                            .fillMaxSize()
                    )
                    Spacer(Modifier.weight(1f))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.nothing_found_by_search),
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(
                            start = 24.dp,
                            end = 24.dp,
                            top = 8.dp,
                            bottom = 8.dp
                        )
                    )
                    Icon(
                        imageVector = Icons.Outlined.SearchOff,
                        contentDescription = null,
                        modifier = Modifier
                            .weight(2f)
                            .sizeIn(maxHeight = 140.dp, maxWidth = 140.dp)
                            .fillMaxSize()
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
