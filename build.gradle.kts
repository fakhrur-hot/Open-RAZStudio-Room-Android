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

buildscript {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }

    dependencies {
        classpath(libs.kotlinx.serialization.gradle)
        classpath(libs.ksp.gradle)
        classpath(libs.agp.gradle)
        classpath(libs.kotlin.gradle)
        classpath(libs.hilt.gradle)
        classpath(libs.gms.gradle)
        classpath(libs.firebase.crashlytics.gradle)
        classpath(libs.baselineprofile.gradle)
        classpath(libs.detekt.gradle)
        classpath(libs.aboutlibraries.gradle)
        classpath(libs.compose.compiler.gradle)
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// Kiro IDE's Gradle test executor forks a JVM that inherits the system CLASSPATH
// env var, which leaks unrelated jars onto the forked test JVM's classpath and
// causes intermittent test-execution failures (compilation is unaffected —
// this is specific to the forked test process, not javac/kotlinc). Gradle
// already builds an explicit classpath for the test JVM from project
// dependencies, so the inherited CLASSPATH env var is never needed and only
// causes conflicts.
subprojects {
    tasks.withType<Test>().configureEach {
        environment.remove("CLASSPATH")
    }
}