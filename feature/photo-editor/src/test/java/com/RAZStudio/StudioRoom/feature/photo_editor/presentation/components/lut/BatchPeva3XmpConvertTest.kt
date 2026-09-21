/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * One-shot JVM harness: convert peva3 Lightroom XMP presets to ASCII .cube
 * using the production LrPresetConverter.
 *
 *   ./gradlew :feature:photo-editor:testFossDebugUnitTest --tests "*.BatchPeva3XmpConvertTest"
 *
 * Reads  _tmp_peva3/Presets/<srcCat>/ (XMP files)
 * Writes _tmp_peva3_cubes/<destCat>/ (.cube files)
 * Then run scripts/merge_peva3_luts.py to dedupe + smcube into assets/luts.
 */
package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut

import org.junit.Test
import java.io.File

class BatchPeva3XmpConvertTest {

    /** peva3 folder → StudioRoom assets category (merge targets). */
    private val categoryMap = mapOf(
        "Color-Negative" to "Negative Color",
        "Black-White" to "Black & White",
        "Slide" to "Color Slide",
        "Creative" to "Cinematic",
        "Video" to "Cinematic",
        "Utility" to "Correction",
        "Alternative-Process" to "Alternative Process",
        "Geographic" to "Landscape",
        "Photographer" to "Moody",
        "Seasonal" to "Lifestyle & Commercial",
        "Decade" to "Instant Consumer",
        // Genre is split by filename keywords in merge_peva3_luts.py — dump here first.
        "Genre" to "_GenreStaging",
        // Mobile social filters skipped (noisy / low signal for RAW grading).
    )

    @Test
    fun convertAllMappedPresets() {
        val startDir = File(System.getProperty("user.dir"))
        val root = generateSequence(startDir) { it.parentFile }
            .firstOrNull { dir ->
                File(dir, "settings.gradle.kts").isFile ||
                    File(dir, "gradlew").isFile ||
                    File(dir, "_tmp_peva3").isDirectory
            } ?: startDir
        val srcRoot = File(root, "_tmp_peva3/Presets")
        val outRoot = File(root, "_tmp_peva3_cubes")
        if (!srcRoot.isDirectory) {
            println("SKIP missing preset dataset: $srcRoot")
            return
        }
        outRoot.mkdirs()

        var ok = 0
        var fail = 0
        var skip = 0
        for ((srcCat, destCat) in categoryMap) {
            val srcDir = File(srcRoot, srcCat)
            if (!srcDir.isDirectory) {
                println("SKIP missing peva3 category: $srcCat")
                continue
            }
            val destDir = File(outRoot, destCat).also { it.mkdirs() }
            srcDir.listFiles { f -> f.isFile && f.extension.equals("xmp", true) }
                ?.sortedBy { it.name }
                ?.forEach { xmp ->
                    val cubeName = sanitizeFilename(xmp.nameWithoutExtension) + ".cube"
                    val out = File(destDir, cubeName)
                    if (out.exists() && out.length() > 100) {
                        skip++
                        return@forEach
                    }
                    val text = xmp.readText(Charsets.UTF_8)
                    val cube = LrPresetConverter.convertToCubeString(text, cubeSize = 33)
                    if (cube == null) {
                        println("FAIL ${xmp.name}")
                        fail++
                    } else {
                        // Retitle for the library browser.
                        val titled = cube.replaceFirst(
                            Regex("TITLE \".*\""),
                            "TITLE \"${xmp.nameWithoutExtension.replace("\"", "")}\"",
                        )
                        out.writeText(titled)
                        ok++
                        if (ok % 25 == 0) println("… converted $ok")
                    }
                }
        }
        println("DONE ok=$ok fail=$fail skipExisting=$skip → ${outRoot.absolutePath}")
        if (ok == 0 && fail == 0) {
            println("SKIP no convertible presets found in dataset: ${srcRoot.absolutePath}")
            return
        }
        assert(ok + skip > 0) { "No presets converted" }
        assert(fail < ok / 2) { "Too many failures: fail=$fail ok=$ok" }
    }

    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}
