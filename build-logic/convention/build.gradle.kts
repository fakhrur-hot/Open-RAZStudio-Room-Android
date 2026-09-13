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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

group = "com.RAZStudio.StudioRoom.buildlogic"

// Configure the build-logic plugins to target JDK 21
// This matches the JDK used to build the project, and is not related to what is running on device.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    compileOnly(libs.agp.gradle)
    compileOnly(libs.kotlin.gradle)
    compileOnly(libs.detekt.gradle)
    compileOnly(libs.compose.compiler.gradle)
    // FIXME: Gradle does not put the generated version-catalog accessor class on the
    // build-logic classpath by default. This reflection workaround loads the catalog jar
    // so that ProjectExtensions.kt can reference LibrariesForLibs. Revisit once Gradle
    // exposes generated catalog accessors to included builds natively
    // (https://github.com/gradle/gradle/issues/15383).
    compileOnly(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}

gradlePlugin {
    // register the convention plugin
    plugins {
        register("imageToolboxLibrary") {
            id = "image.toolbox.library"
            implementationClass = "StudioRoomLibraryPlugin"
        }
        register("imageToolboxHiltPlugin") {
            id = "image.toolbox.hilt"
            implementationClass = "StudioRoomHiltPlugin"
        }
        register("imageToolboxLibraryFeature") {
            id = "image.toolbox.feature"
            implementationClass = "StudioRoomLibraryFeaturePlugin"
        }
        register("imageToolboxLibraryComposePlugin") {
            id = "image.toolbox.compose"
            implementationClass = "StudioRoomLibraryComposePlugin"
        }
        register("imageToolboxApplicationPlugin") {
            id = "image.toolbox.application"
            implementationClass = "StudioRoomApplicationPlugin"
        }
    }
}