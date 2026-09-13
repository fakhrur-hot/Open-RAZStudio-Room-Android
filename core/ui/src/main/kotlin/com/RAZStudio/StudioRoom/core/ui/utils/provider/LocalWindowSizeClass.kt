/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2024 RAZStudio (Fakhrurraze)
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

package com.RAZStudio.StudioRoom.core.ui.utils.provider

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import android.util.Log
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ProvidesValue

val LocalWindowSizeClass = compositionLocalOf<WindowSizeClass> { error("SizeClass not present") }

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun ComponentActivity.setContentWithWindowSizeClass(
    content: @Composable () -> Unit
) = setContent {
    // Derive the size class from the OS Configuration (screenWidthDp/HeightDp)
    // rather than the library's window-metrics path. 2026-09-04: on the Infinix
    // X6873 (1224 px @ 480 dpi = 408 dp, Compact) the library returned a
    // non-Compact width, which switched the main screen's Settings into the
    // DOCKED side-pane layout — the drawer opened on launch, could not be
    // dismissed, and BACK exited the app (docked mode has no back handler).
    // Configuration dp is exactly what the system uses for resource
    // qualifiers (sw408dp/w408dp in dumpsys), so it cannot disagree with the
    // device. Falls back to the library value only if the configuration is
    // unusable (0 dp — never seen, kept defensively).
    val cfg = LocalConfiguration.current
    val fromLibrary = calculateWindowSizeClass(this)
    val value = if (cfg.screenWidthDp > 0 && cfg.screenHeightDp > 0) {
        WindowSizeClass.calculateFromSize(DpSize(cfg.screenWidthDp.dp, cfg.screenHeightDp.dp))
    } else fromLibrary
    // One line per configuration change — grep "WINDOW-SIZE-CLASS".
    remember(cfg.screenWidthDp, cfg.screenHeightDp, cfg.densityDpi, cfg.fontScale) {
        Log.i("WindowSizeClass",
            "WINDOW-SIZE-CLASS: config=${cfg.screenWidthDp}x${cfg.screenHeightDp}dp " +
            "density=${cfg.densityDpi} fontScale=${cfg.fontScale} -> width=${value.widthSizeClass} " +
            "(library said ${fromLibrary.widthSizeClass})")
        Unit
    }
    LocalWindowSizeClass.ProvidesValue(
        value = value,
        content = content
    )
}
