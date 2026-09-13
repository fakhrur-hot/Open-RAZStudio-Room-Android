package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guard against Film-Response-style drift: names listed in
 * kParityCriticalUniformNames must appear in BOTH the live cached push
 * (gles_renderer.cpp pushUniforms / cacheUniformLocations) and
 * pushGradingUniforms (grading_uniforms.cpp).
 *
 * Runs on JVM against the repo's C++ sources — no device / GL needed.
 */
class GradingUniformParityTest {

    // Keep in sync with kParityCriticalUniformNames in grading_uniforms.h
    private val critical = listOf(
        "uFilmRecovery",
        "uFilmFillLight",
        "uFilmMonochrome",
        "uFilmGrayMix",
        "uFilmRolloff",
        "uFilmGrain",
        "uFilmGrainSize",
        "uFilmGrainWash",
        "uCgShadowsTint",
        "uCgMidtonesTint",
        "uCgHighlightsTint",
        "uCgGlobalTint",
        "uClarityAmount",
        "uHslFull",
        "uColorDensity",
        "uSkintone",
        "uPushPull",
        "uCurvesEnabled",
        // uFilmicHlProtect omitted — dead slot; see grading_uniforms.h
    )

    @Test
    fun criticalUniformsPresentInLiveAndGradingPushSources() {
        val root = findRepoRoot()
        val gles = File(root, "lib/raw-native/src/main/cpp/v3/gles_renderer.cpp").readText()
        val grading = File(root, "lib/raw-native/src/main/cpp/v3/grading_uniforms.cpp").readText()
        val header = File(root, "lib/raw-native/src/main/cpp/v3/grading_uniforms.h").readText()

        for (name in critical) {
            assertTrue(
                "$name missing from kParityCriticalUniformNames (grading_uniforms.h)",
                header.contains("\"$name\""),
            )
            assertTrue(
                "$name missing from live gles_renderer.cpp (cache/pushUniforms)",
                gles.contains("\"$name\"") || gles.contains("\"$name[0]\""),
            )
            assertTrue(
                "$name missing from pushGradingUniforms (grading_uniforms.cpp)",
                grading.contains("\"$name\"") || grading.contains("\"$name[0]\""),
            )
        }
    }

    private fun findRepoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            val marker = File(dir, "lib/raw-native/src/main/cpp/v3/grading_uniforms.h")
            if (marker.isFile) return dir
            dir = dir.parentFile ?: error("repo root not found from ${System.getProperty("user.dir")}")
        }
        error("repo root not found")
    }
}
