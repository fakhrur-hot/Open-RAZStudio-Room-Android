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

@file:Suppress("UnstableApiUsage")

import java.util.Properties

plugins {
    alias(libs.plugins.image.toolbox.application)
    alias(libs.plugins.image.toolbox.hilt)
}

// Release signing — read from a git-ignored `keystore.properties` at the repo
// root so the keystore path + passwords never live in version control. Fill in:
//   storeFile=C:/path/to/release.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// When the file is absent/incomplete, the release build falls back to UNSIGNED
// (so CI/other devs still compile) — `signingReady` gates that below.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val signingReady = keystoreProps.getProperty("storeFile")?.let {
    file(it).exists()
} == true

android {
    val supportedAbi = arrayOf("arm64-v8a")

    namespace = "com.RAZStudio.StudioRoom"

    // Keep .tflite assets uncompressed so MappedByteBuffer can mmap them directly.
    androidResources { noCompress += listOf("tflite") }

    defaultConfig {
        vectorDrawables.useSupportLibrary = true

        applicationId = "com.RAZStudio.StudioRoom"

        versionCode = libs.versions.versionCode.get().toIntOrNull()
        versionName = System.getenv("VERSION_NAME") ?: libs.versions.versionName.get()

        // ABI filtering is handled by the splits block below — no ndk.abiFilters needed.
        // (AGP rejects having the same ABI in both abiFilters and splits.abi.include.)
    }

    androidResources {
        generateLocaleConfig = true
        // Exclude bulky editable source assets from the packaged APK; the app
        // reads the compact binary form at runtime.
        ignoreAssetsPatterns.add("*.cube")
    }

    signingConfigs {
        if (signingReady) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    flavorDimensions += "app"

    productFlavors {
        create("foss") {
            dimension = "app"
            versionNameSuffix = "-foss"
            extra.set("gmsEnabled", false)
        }
        create("market") {
            dimension = "app"
            extra.set("gmsEnabled", true)
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_launcher_name", "Open RAZStudio Room DEBUG")
            resValue("string", "file_provider", "com.RAZStudio.StudioRoom.fileprovider.debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resValue("string", "app_launcher_name", "Open RAZStudio Room")
            resValue("string", "file_provider", "com.RAZStudio.StudioRoom.fileprovider")
            // Sign with the release keystore when keystore.properties is present;
            // otherwise leave unsigned (build still succeeds for CI / other devs).
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        // ── Hardened (anti-reverse-engineering) build ─────────────────────────
        // Same R8 minify + resource shrink as release, PLUS proguard-hardened.pro
        // (log stripping + aggressive repackaging/inlining of the Kotlin bridge
        // layer). Library modules + the native .so resolve via the "release"
        // fallback, so the .so is the symbol-hardened Release build (hidden
        // visibility + --gc-sections + --exclude-libs, see raw-native CMakeLists).
        // Distinct applicationId suffix so it installs ALONGSIDE debug/release
        // for A/B comparison. Signed with the release key when available.
        create("hardened") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".hardened"
            versionNameSuffix = "-hardened"
            isMinifyEnabled = true
            isShrinkResources = true
            // initWith(release) already carried proguard-android-optimize.txt +
            // proguard-rules.pro — only APPEND the hardened-only rules.
            proguardFiles("proguard-hardened.pro")
            resValue("string", "app_launcher_name", "Open RAZStudio Room SECURE")
            resValue("string", "file_provider", "com.RAZStudio.StudioRoom.fileprovider.hardened")
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    splits {
        abi {
            // Detect app bundle and conditionally disable split abis
            // This is needed due to a "Sequence contains more than one matching element" error
            // present since AGP 8.9.0, for more info see:
            // https://issuetracker.google.com/issues/402800800

            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle =
                gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }

            // Disable split abis when building appBundle
            isEnable = !isBuildingBundle
            reset()
            //noinspection ChromeOsAbiSupport
            include(*supportedAbi)
            isUniversalApk = false
        }
    }

    lint {
        disable += "Instantiatable"
    }

    packaging {
        jniLibs {
            keepDebugSymbols.add("**/*.so")
            pickFirsts.add("lib/*/libcoder.so")
            pickFirsts.add("**/libc++_shared.so")
            pickFirsts.add("**/libdatstore_shared_counter.so")
            useLegacyPackaging = true
        }
        resources {
            excludes += "META-INF/"
            excludes += "kotlin/"
            excludes += "org/"
            excludes += ".properties"
            excludes += ".bin"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    buildFeatures {
        resValues = true
    }
}

base {
    archivesName = "Open_RAZStudio_Room-${android.defaultConfig.versionName}"
}

aboutLibraries {
    export.excludeFields.addAll("generated")
}

dependencies {
    implementation(projects.feature.root)
    // Direct dep so app/.../debug/RawV3SmokeActivity can reference the
    // raw_v3 engine + view classes by type. Removed at v3 milestone M12.
    implementation(projects.feature.photoEditor)
    implementation(projects.feature.mediaPicker)
    implementation(projects.feature.quickTiles)
    // canon-sync flows in transitively via feature:root which already
    // imports it for the navigation graph. Adding it again here would
    // double-register Hilt entry points.

    implementation(projects.lib.opencvTools)
    implementation(projects.lib.collages)
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        val flavorName = variantBuilder.productFlavors.firstOrNull()?.second.orEmpty()
        val flavorCap = flavorName.replaceFirstChar(Char::uppercase)

        val gmsEnabled = android.productFlavors
            .findByName(flavorName)
            ?.extra
            ?.get("gmsEnabled") == true

        tasks.configureEach {
            val isTargetTask = listOf("GoogleServices", "Crashlytics").any { marker ->
                name.contains(marker)
            } && name.contains(flavorCap)

            if (isTargetTask) {
                enabled = gmsEnabled
            }
        }
    }
}