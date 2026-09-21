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

plugins {
    alias(libs.plugins.image.toolbox.library)
    alias(libs.plugins.image.toolbox.feature)
    alias(libs.plugins.image.toolbox.hilt)
    alias(libs.plugins.image.toolbox.compose)
}

android.namespace = "com.RAZStudio.StudioRoom.feature.photo_editor"

android {
    androidResources {
        noCompress += listOf("onnx")
    }

    testOptions {
        // LrPresetConverter (and friends) call android.util.Log; unit tests need
        // no-op stubs so batch XMP→cube conversion can run on the JVM.
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        buildConfig = true
    }
    val useRawV2 = (project.findProperty("useRawV2") as? String).toBoolean()
    // v3-integration — feature flag gating the V3 GLES+NDK pipeline rebuild
    // (Plan.md @ .kiro/specs/raw-pipeline-v3-rebuild). Default OFF until v3
    // ships milestones M2–M11. Old `raw/` engine stays the production default
    // and continues to handle every RAW open until M12 flips this on.
    //
    // To produce a V3-on test APK once v3 is ready:
    //     ./gradlew :app:assembleFossDebug -PuseRawV3=true
    val useRawV3 = (project.findProperty("useRawV3") as? String).toBoolean()
    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "USE_RAW_V2", useRawV2.toString())
            buildConfigField("boolean", "USE_RAW_V3", useRawV3.toString())
        }
        getByName("release") {
            buildConfigField("boolean", "USE_RAW_V2", useRawV2.toString())
            buildConfigField("boolean", "USE_RAW_V3", useRawV3.toString())
        }
    }
}

// Workaround for a Kotlin JVM back-end crash ("Back-end (JVM) Internal error:
// Couldn't transform method … ") on large photo-editor files. The default
// `indy` string-concat lowering (invokedynamic makeConcatWithConstants) trips
// an IR codegen bug on some big methods in this module (e.g. RawV3Coordinator).
// `inline` emits classic StringBuilder concat instead — identical runtime
// behaviour, no pixel/semantic change — and sidesteps the crash.
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xstring-concat=inline")
    }
}

// Fix: on Windows, a CLASSPATH env var that includes PATH entries with spaces
// (e.g. "C:\Program Files\Git\bin") makes the forked test JVM treat
// "Files\Git\bin" as the main class. Clear it for every Test task.
// If tests still fail that way, launch Gradle with a short PATH (JDK + System32
// only) and --no-daemon — do NOT scrub PATH here (breaks tool lookups).
tasks.withType<Test>().configureEach {
    environment.remove("CLASSPATH")
}

// ─────────────────────────────────────────────────────────────────────────────
// Dex-register guard for the oversized data classes (UserMacro, ShaderParams).
//
// UserMacro is a huge Kotlin data class sitting right at the Dalvik register
// ceiling. Its synthetic `copy$default` is called all over the editor, and each
// call is an `invoke-static/range` whose out-register COUNT is stored in an
// 8-bit field — at 256 it wraps to 0 and ART's verifier rejects the whole class
// (`VerifyError: invalid arg count (0) in invoke-*/range`), crashing the app the
// instant RawEditorComponent loads. Lenient OEM ART and `cmd package compile -m
// verify` do NOT catch this; strict ART (e.g. Android 16 / SDK 36) does. It has
// shipped twice.
//
// This guard reads each guarded .class after every Kotlin compile, finds the
// longest `(L…<Cls>;…)L…<Cls>;` descriptor (that's copy$default), sums its
// argument registers, and FAILS the build before it can produce a crashing APK.
// When it trips, DON'T add flat fields — nest new state into a holder data
// class (see ColorWheel / MaskToneRegions) or, in ShaderParams, a FloatArray
// (see hslFull), so the class gains one param, not N.
//
// ShaderParams was added to the guard on 2026-09-07 after it shipped this exact
// VerifyError: the guard watched UserMacro ONLY, so four new film-response
// fields sailed past it and ART rejected RawEditorComponent on load — "photo
// cannot load to RAW editor". Any data class big enough to be near the ceiling
// belongs in this list; guarding one of them is not guarding the problem.
run {
    val hardLimit = 256   // dex wraps here
    val failAt = 250      // fail with margin, before the real cliff
    val warnAt = 244

    fun argRegsOf(desc: String): Int {
        var i = 1; var regs = 0
        while (desc[i] != ')') {
            when (desc[i]) {
                'J', 'D' -> { regs += 2; i++ }
                'L' -> { regs += 1; i = desc.indexOf(';', i) + 1 }
                '[' -> { regs += 1; i++; while (desc[i] == '[') i++
                         if (desc[i] == 'L') i = desc.indexOf(';', i) + 1 else i++ }
                else -> { regs += 1; i++ }   // B C F I S Z
            }
        }
        return regs
    }

    // Minimal .class constant-pool reader — pulls out every UTF8 string.
    fun utf8Constants(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        var p = 8 // magic(4) + minor(2) + major(2)
        val count = ((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF); p += 2
        var idx = 1
        while (idx < count) {
            val tag = bytes[p].toInt() and 0xFF; p += 1
            when (tag) {
                1 -> { // UTF8
                    val len = ((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF); p += 2
                    out.add(String(bytes, p, len, Charsets.UTF_8)); p += len
                }
                7, 8, 16, 19, 20 -> p += 2
                15 -> p += 3
                3, 4, 9, 10, 11, 12, 17, 18 -> p += 4
                5, 6 -> { p += 8; idx++ } // long/double take two pool slots
                else -> throw GradleException("UserMacro guard: unexpected constant tag $tag")
            }
            idx++
        }
        return out
    }

    fun verifyClass(cls: File, simpleName: String): Int {
        val bytes = cls.readBytes()
        val descs = utf8Constants(bytes).filter {
            it.startsWith("(L") && it.contains("$simpleName;") && it.endsWith("$simpleName;")
        }
        // copy$default is by far the longest such descriptor.
        val copyDefault = descs.maxByOrNull { it.length }
            ?: throw GradleException("$simpleName guard: copy\$default descriptor not found")
        return argRegsOf(copyDefault)
    }

    // Every data class close enough to the ceiling to matter, as
    // (class-file path relative to the output dir) to simple name.
    val guarded = mapOf(
        "com/RAZStudio/StudioRoom/feature/photo_editor/raw/model/UserMacro.class"
            to "UserMacro",
        "com/RAZStudio/StudioRoom/feature/photo_editor/raw_v3/ShaderParams.class"
            to "ShaderParams",
    )

    tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
        doLast {
            val dir = destinationDirectory.get().asFile
            for ((path, simpleName) in guarded) {
                val cls = File(dir, path)
                if (!cls.isFile) continue
                val regs = verifyClass(cls, simpleName)
                when {
                    regs >= failAt -> throw GradleException(
                        "$simpleName.copy\$default needs $regs argument registers — the dex " +
                        "range-invoke argument count byte wraps at $hardLimit and ART " +
                        "VerifyErrors (app crashes when RawEditorComponent loads). Do NOT add " +
                        "flat fields to $simpleName; nest new state into a holder data class " +
                        "(see ColorWheel / MaskToneRegions) or a FloatArray (see " +
                        "ShaderParams.hslFull). See docs/GOTCHAS.md.")
                    regs >= warnAt -> logger.warn(
                        "WARNING: $simpleName.copy\$default is at $regs argument registers " +
                        "(dex ceiling $hardLimit). Only ${hardLimit - regs} left - add new " +
                        "fields as nested holders or arrays, not flat fields.")
                    else -> logger.lifecycle(
                        "dex guard: $simpleName.copy\$default uses $regs/$hardLimit registers.")
                }
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")

    implementation(libs.toolbox.exif)
    implementation(libs.onnx.runtime)
    implementation(projects.feature.crop)
    implementation(projects.feature.eraseBackground)
    implementation(projects.feature.draw)
    implementation(projects.feature.filters)
    implementation(projects.feature.pickColor)
    implementation(projects.feature.compare)
    implementation(projects.lib.curves)
    implementation(projects.lib.cropper)
    implementation(projects.lib.opencvTools)
    // raw-native ships the libraw_decoder .so + NativeRawDecoder Kotlin bindings.
    // Built from source under lib/raw-native/{libraw-native,libxml2-native}/.
    // Replaces the prebuilt feature/photo-editor/src/main/jniLibs/.../libraw_decoder.so
    // shipped in earlier builds.
    implementation(projects.lib.rawNative)
    // Magic-Lantern dual-ISO blend kernel. Isolated GPL-2 module so its
    // license obligations don't leak into the rest of the (Apache-2)
    // codebase. We only call it through the standard JNI boundary.
    implementation(projects.lib.rawNativeDualiso)

    // MediaPipe Tasks-Vision — selfie-multiclass image segmentation
    // (6 classes: bg / hair / body-skin / face-skin / clothes /
    // accessories). Used by Smart Vignette + Mask tab class-picker.
    //
    // The .tflite asset is NOT committed yet — drop the MediaPipe-
    // hosted file at
    //   feature/photo-editor/src/main/assets/selfie_multiclass.tflite
    // before D2 wires up the inference path. (Model URL is in the
    // SelfieMulticlassSegmenter KDoc.)
    implementation(libs.mediapipeTasksVision)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.androidx.security.crypto)
}