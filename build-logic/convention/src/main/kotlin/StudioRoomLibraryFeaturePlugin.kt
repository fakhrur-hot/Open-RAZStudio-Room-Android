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

import com.RAZStudio.StudioRoom.configureDetekt
import com.RAZStudio.StudioRoom.core
import com.RAZStudio.StudioRoom.crash
import com.RAZStudio.StudioRoom.data
import com.RAZStudio.StudioRoom.di
import com.RAZStudio.StudioRoom.domain
import com.RAZStudio.StudioRoom.implementation
import com.RAZStudio.StudioRoom.projects
import com.RAZStudio.StudioRoom.resources
import com.RAZStudio.StudioRoom.settings
import com.RAZStudio.StudioRoom.shouldApplyDetekt
import com.RAZStudio.StudioRoom.ui
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

@Suppress("UNUSED")
class StudioRoomLibraryFeaturePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            if (shouldApplyDetekt()) {
                configureDetekt(extensions.getByType<DetektExtension>())
            }
            dependencies {
                implementation(projects.core.data)
                implementation(projects.core.ui)
                implementation(projects.core.domain)
                implementation(projects.core.resources)
                implementation(projects.core.settings)
                implementation(projects.core.di)
                implementation(projects.core.crash)
            }
        }
    }
}