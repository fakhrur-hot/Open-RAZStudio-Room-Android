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

import com.android.build.api.dsl.LibraryExtension
import com.RAZStudio.StudioRoom.configureCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.getByType

@Suppress("UNUSED")
class StudioRoomLibraryComposePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // Do NOT apply "com.android.library" here; this plugin is meant to be
            // applied on top of the base library plugin (image.toolbox.library).
            apply(plugin = "org.jetbrains.kotlin.plugin.compose")

            configureCompose(extensions.getByType<LibraryExtension>())
        }
    }
}
