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

package com.RAZStudio.StudioRoom.core.resources.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons

val Icons.Outlined.AutoDelete: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Outlined.AutoDelete",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(280f, 240f)
            verticalLineToRelative(520f)
            verticalLineToRelative(-520f)
            close()
            moveTo(280f, 840f)
            quadToRelative(-33f, 0f, -56.5f, -23.5f)
            reflectiveQuadTo(200f, 760f)
            verticalLineToRelative(-520f)
            horizontalLineToRelative(-1f)
            quadToRelative(-17f, 0f, -28f, -11.5f)
            reflectiveQuadTo(160f, 200f)
            quadToRelative(0f, -17f, 11.5f, -28.5f)
            reflectiveQuadTo(200f, 160f)
            horizontalLineToRelative(160f)
            quadToRelative(0f, -17f, 11.5f, -28.5f)
            reflectiveQuadTo(400f, 120f)
            horizontalLineToRelative(160f)
            quadToRelative(17f, 0f, 28.5f, 11.5f)
            reflectiveQuadTo(600f, 160f)
            horizontalLineToRelative(160f)
            quadToRelative(17f, 0f, 28.5f, 11.5f)
            reflectiveQuadTo(800f, 200f)
            quadToRelative(0f, 17f, -11.5f, 28.5f)
            reflectiveQuadTo(760f, 240f)
            verticalLineToRelative(140f)
            quadToRelative(0f, 17f, -11.5f, 28.5f)
            reflectiveQuadTo(720f, 420f)
            quadToRelative(-17f, 0f, -28.5f, -11.5f)
            reflectiveQuadTo(680f, 380f)
            verticalLineToRelative(-140f)
            lineTo(280f, 240f)
            verticalLineToRelative(520f)
            horizontalLineToRelative(107f)
            quadToRelative(17f, 0f, 28.5f, 11.5f)
            reflectiveQuadTo(427f, 800f)
            quadToRelative(0f, 16f, -11.5f, 28f)
            reflectiveQuadTo(387f, 840f)
            lineTo(280f, 840f)
            close()
            moveTo(371.5f, 331.5f)
            quadTo(360f, 343f, 360f, 360f)
            verticalLineToRelative(280f)
            quadToRelative(0f, 17f, 11.5f, 28.5f)
            reflectiveQuadTo(400f, 680f)
            quadToRelative(17f, 0f, 28.5f, -11.5f)
            reflectiveQuadTo(440f, 640f)
            verticalLineToRelative(-280f)
            quadToRelative(0f, -17f, -11.5f, -28.5f)
            reflectiveQuadTo(400f, 320f)
            quadToRelative(-17f, 0f, -28.5f, 11.5f)
            close()
            moveTo(531.5f, 331.5f)
            quadTo(520f, 343f, 520f, 360f)
            verticalLineToRelative(60f)
            quadToRelative(0f, 17f, 11.5f, 28.5f)
            reflectiveQuadTo(560f, 460f)
            quadToRelative(17f, 0f, 28.5f, -11.5f)
            reflectiveQuadTo(600f, 420f)
            verticalLineToRelative(-60f)
            quadToRelative(0f, -17f, -11.5f, -28.5f)
            reflectiveQuadTo(560f, 320f)
            quadToRelative(-17f, 0f, -28.5f, 11.5f)
            close()
            moveTo(538.5f, 821.5f)
            quadTo(480f, 763f, 480f, 680f)
            reflectiveQuadToRelative(58.5f, -141.5f)
            quadTo(597f, 480f, 680f, 480f)
            quadToRelative(41f, 0f, 77.5f, 16f)
            reflectiveQuadToRelative(63.5f, 43f)
            quadToRelative(27f, 27f, 43f, 63.5f)
            reflectiveQuadToRelative(16f, 77.5f)
            quadToRelative(0f, 83f, -58.5f, 141.5f)
            reflectiveQuadTo(680f, 880f)
            quadToRelative(-83f, 0f, -141.5f, -58.5f)
            close()
            moveTo(700f, 672f)
            verticalLineToRelative(-92f)
            quadToRelative(0f, -8f, -6f, -14f)
            reflectiveQuadToRelative(-14f, -6f)
            quadToRelative(-8f, 0f, -14f, 6f)
            reflectiveQuadToRelative(-6f, 14f)
            verticalLineToRelative(91f)
            quadToRelative(0f, 8f, 3f, 15.5f)
            reflectiveQuadToRelative(9f, 13.5f)
            lineToRelative(60f, 60f)
            quadToRelative(6f, 6f, 14f, 6f)
            reflectiveQuadToRelative(14f, -6f)
            quadToRelative(6f, -6f, 6f, -14f)
            reflectiveQuadToRelative(-6f, -14f)
            lineToRelative(-60f, -60f)
            close()
        }
    }.build()
}
