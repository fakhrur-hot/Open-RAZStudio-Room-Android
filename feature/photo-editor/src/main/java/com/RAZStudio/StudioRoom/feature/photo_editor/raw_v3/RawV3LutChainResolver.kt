package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.net.Uri
import com.RAZStudio.StudioRoom.core.utils.AppLog
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Resolves the effective LUT (single layer or chained multi-layer) for an action
 * list. Used by both the single-photo preview and the folder-tree batch processor
 * so they share the same LUT semantics and cache.
 */
object RawV3LutChainResolver {

    private const val TAG = "RawV3LutChainResolver"

    /**
     * Result of resolving an action list's LUT chain.
     *
     * @param file readable .cube file, or null when the actions carry no LUT.
     * @param intensity intensity to apply in the shader. For chained LUTs this is
     *   always 1 because each layer's intensity is already baked in.
     * @param isChained true when [file] contains multiple layers merged into one cube.
     */
    data class Result(
        val file: File?,
        val intensity: Float,
        val isChained: Boolean,
    )

    /**
     * Resolve the topmost LUT layer only. Fast and suitable for quick preview when
     * the full chain is being computed in the background.
     */
    fun resolveTopmost(
        context: Context,
        actions: List<RawAction>,
        cacheDir: File = File(context.cacheDir, "raw_v3_editor_luts"),
    ): Result {
        val layer = RawV3ActionReplay.effectiveLutLayer(actions)
            ?: return Result(null, 1f, false)          // no LUT card → nothing to show, not an error
        val uri = layer.cubeUri.takeIf { it.isNotEmpty() } ?: run {
            AppLog.w(TAG, "resolveTopmost: LUT layer present but cubeUri is EMPTY — card lost its LUT")
            return Result(null, 1f, false)
        }
        val path = resolveLutPath(context, uri, cacheDir) ?: run {
            AppLog.w(TAG, "resolveTopmost: could not resolve LUT '$uri' to a file — preview will show NO LUT")
            return Result(null, 1f, false)
        }
        return Result(File(path), layer.intensity.coerceIn(0f, 1f), false)
    }

    /**
     * Resolve and, if necessary, chain every visible LUT layer from [actions] into
     * a single cube. Runs synchronously — callers that need it off the main thread
     * should wrap the call in a background coroutine.
     */
    fun resolveChain(
        context: Context,
        actions: List<RawAction>,
        cacheDir: File = File(context.cacheDir, "raw_v3_editor_luts"),
    ): Result {
        val composedMacro = RawV3ActionReplay.composeMacro(actions)
        val allLayers = buildList {
            addAll(composedMacro.lutStack)
            if (composedMacro.lutCubeUri.isNotEmpty()) {
                add(com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.LutLayer(
                    cubeUri = composedMacro.lutCubeUri,
                    intensity = composedMacro.lutIntensity,
                ))
            }
        }
        if (allLayers.isEmpty()) {
            return Result(null, 1f, false)
        }

        val topResolved = resolveLutPath(context, allLayers.last().cubeUri, cacheDir)
            ?: return Result(null, 1f, false)

        if (allLayers.size == 1) {
            return Result(
                File(topResolved),
                allLayers.first().intensity.coerceIn(0f, 1f),
                false,
            )
        }

        val resolvedLayers = allLayers.mapNotNull { layer ->
            val path = resolveLutPath(context, layer.cubeUri, cacheDir) ?: return@mapNotNull null
            path to layer.intensity.coerceIn(0f, 1f)
        }
        if (resolvedLayers.size < 2) {
            return Result(
                File(topResolved),
                allLayers.last().intensity.coerceIn(0f, 1f),
                false,
            )
        }

        val parsedLayers = resolvedLayers.mapNotNull { (path, intensity) ->
            val cube = RawV3LutStore.parseCubeFile(File(path))
            if (cube == null) {
                AppLog.w(TAG, "resolveChain: failed to parse $path")
                return Result(null, 1f, false)
            }
            Triple(
                cube,
                intensity,
                com.RAZStudio.StudioRoom.feature.photo_editor.presentation
                    .components.lut.isBlackAndWhiteLutPath(path),
            )
        }
        if (parsedLayers.size < 2) {
            return Result(
                File(topResolved),
                allLayers.last().intensity.coerceIn(0f, 1f),
                false,
            )
        }

        val chain = runCatching {
            RawV3LutStore.chainLutsBw(parsedLayers)
        }.onFailure {
            AppLog.w(TAG, "resolveChain: chainLuts failed", it)
        }.getOrNull() ?: return Result(null, 1f, false)

        val key = resolvedLayers.joinToString("|") { "${it.first}@${it.second}" }
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        cacheDir.mkdirs()
        val outFile = File(cacheDir, "chain_$sha.cube")
        if (!outFile.exists() || outFile.length() == 0L) {
            runCatching {
                RawV3LutStore.writeCubeFile(chain, outFile)
            }.onFailure {
                AppLog.w(TAG, "resolveChain: writeCubeFile failed", it)
                return Result(null, 1f, false)
            }
        }
        return Result(outFile, 1f, true)
    }

    /**
     * Resolve a LUT URI (file path OR content:// SAF) into a readable filesystem
     * path. SAF URIs are copied into [cacheDir] once and reused via a stable key.
     */
    /**
     * Find [fileName] under `assets/luts/<folder>/` and copy it to
     * `files/lut_cache/<fileName>` (the same location + naming the LUT tab uses,
     * so the two never diverge). Returns the path, or null when no bundled LUT
     * of that name exists (e.g. a user-imported LUT whose file was deleted).
     */
    private fun rematerializeBundledByName(context: Context, fileName: String): String? {
        if (fileName.isEmpty()) return null
        val am = context.assets
        val folders = runCatching { am.list("luts") }.getOrNull() ?: return null
        for (folder in folders) {
            val files = runCatching { am.list("luts/$folder") }.getOrNull() ?: continue
            if (fileName !in files) continue
            val out = File(File(context.filesDir, "lut_cache").apply { mkdirs() }, fileName)
            val ok = runCatching {
                am.open("luts/$folder/$fileName").use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }
            }.isSuccess
            if (ok && out.length() > 0L) {
                AppLog.i(TAG, "re-materialised bundled LUT '$fileName' from assets/luts/$folder → ${out.path}")
                return out.absolutePath
            }
        }
        AppLog.w(TAG, "no bundled LUT named '$fileName' in assets/luts — cannot restore")
        return null
    }

    fun resolveLutPath(context: Context, uri: String, cacheDir: File): String? {
        if (uri.startsWith("/") || uri.startsWith("file:")) {
            val path = uri.removePrefix("file://")
            val file = File(path)
            if (file.exists()) return file.absolutePath
            // Bundled LUTs are materialised from assets into files/lut_cache/<name>
            // only when the LUT tab LISTS them (LocalLutRepository.resolveAssetToCache)
            // and the sidecar stores that absolute path. On a fresh install / after
            // "Clear storage" the file is absent until the user happens to open the
            // LUT tab — so a restored project photo silently rendered without its
            // LUT (owner observation 2026-09-07: "the LUT only shows after I scroll
            // to the LUT tab"). Re-materialise by basename from assets/luts/*/.
            rematerializeBundledByName(context, file.name)?.let { return it }
            return null
        }
        return runCatching {
            val androidUri = Uri.parse(uri)
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(uri.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            cacheDir.mkdirs()
            val out = File(cacheDir, "$sha.cube")
            if (!out.exists() || out.length() == 0L) {
                context.contentResolver.openInputStream(androidUri)?.use { ins ->
                    FileOutputStream(out).use { o -> ins.copyTo(o) }
                } ?: return null
            }
            out.absolutePath
        }.onFailure { AppLog.w(TAG, "resolveLutPath failed for $uri", it) }.getOrNull()
    }
}
