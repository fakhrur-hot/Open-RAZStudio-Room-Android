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

package com.RAZStudio.StudioRoom.core.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Uniform 4dp grid for pages, panels, dialogs, and toasts.
 * 4 micro / 8 item / 16 section / 24 card / 32 page / 48 major.
 */
object Spacing {
    val micro = 4.dp
    val item = 8.dp
    val section = 16.dp
    val card = 24.dp
    val page = 32.dp
    val major = 48.dp
}

object Radius {
    val control = 12.dp
    val card = 20.dp
    val sheet = 24.dp
    val toast = 28.dp
}

/**
 * Four elevation layers for the photo editor. Extra card shadows
 * should map to one of these instead of inventing a new height.
 */
object Elevation {
    val canvas = 0.dp
    val panel = 2.dp
    val sheet = 8.dp
    val dialog = 24.dp
}
