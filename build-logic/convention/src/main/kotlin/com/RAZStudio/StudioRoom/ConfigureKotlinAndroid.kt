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

package com.RAZStudio.StudioRoom

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension,
    createFlavors: Boolean = true
) {
    commonExtension.apply {
        compileSdk = libs.versions.androidCompileSdk.get().toIntOrNull()
        compileSdkExtension = libs.versions.androidCompileSdkExtension.get().toIntOrNull()

        defaultConfig.apply {
            minSdk = libs.versions.androidMinSdk.get().toIntOrNull()
        }

        if (createFlavors) {
            flavorDimensions += "app"

            productFlavors.apply {
                create("foss") {
                    dimension = "app"
                }
                create("market") {
                    dimension = "app"
                }
            }
        }

        compileOptions.apply {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
            isCoreLibraryDesugaringEnabled = true
        }

        buildFeatures.apply {
            compose = false
            aidl = false
            shaders = false
            buildConfig = false
            resValues = false
        }

        packaging.apply {
            resources {
                excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            }
        }

        lint.apply {
            disable += "UsingMaterialAndMaterial3Libraries"
            disable += "ModifierParameter"
        }
    }

    configureKotlin<KotlinAndroidProjectExtension>()

    dependencies {
        coreLibraryDesugaring(libs.desugaring)
    }
}

val Project.javaVersion: JavaVersion
    get() = JavaVersion.toVersion(
        libs.versions.jvmTarget.get()
    )

/**
 * Configure base Kotlin options
 */
private inline fun <reified T : KotlinBaseExtension> Project.configureKotlin() = configure<T> {
    jvmToolchain(javaVersion.majorVersion.toInt())
    val args = listOf(
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        "-opt-in=androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi",
        "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
        "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        "-opt-in=androidx.compose.ui.unit.ExperimentalUnitApi",
        "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        "-opt-in=kotlinx.coroutines.FlowPreview",
        "-opt-in=androidx.compose.material.ExperimentalMaterialApi",
        "-opt-in=coil3.annotation.ExperimentalCoilApi",
        "-opt-in=coil3.annotation.DelicateCoilApi",
        "-opt-in=kotlin.contracts.ExperimentalContracts",
        "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
        "-opt-in=kotlinx.coroutines.DelicateCoroutinesApi",
        // The param-property annotation default-target is requested via the stable
        // flag below. On Kotlin 2.3.x the internal language flag
        // "-XXLanguage:+PropertyParamAnnotationDefaultTargetMode" is redundant and
        // only produces the "unsafe internal compiler arguments" warning, so it has
        // been removed. Re-add only if downgrading below the Kotlin version where
        // -Xannotation-default-target is honored on its own.
        "-Xannotation-default-target=param-property"
    )
    // Treat all Kotlin warnings as errors (disabled by default)
    // Override by setting warningsAsErrors=true in your ~/.gradle/gradle.properties
    val warningsAsErrors: String? by project
    when (this) {
        is KotlinAndroidProjectExtension -> compilerOptions
        is KotlinJvmProjectExtension -> compilerOptions
        else -> error("Unsupported project extension $this ${T::class}")
    }.apply {
        jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
        allWarningsAsErrors = warningsAsErrors.toBoolean()
        freeCompilerArgs.addAll(args)
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.addAll(args)
    }
}