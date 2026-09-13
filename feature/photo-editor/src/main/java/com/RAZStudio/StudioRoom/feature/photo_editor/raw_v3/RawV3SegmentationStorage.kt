/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW v3 — Segmentation cache (M12.2a).
 *
 *  Persists [RawV3SegmentationMasks] to the existing per-SHA session
 *  directory under [RawV3Cache]. The layout mirrors v2 except we live
 *  under `cacheDir/raw_v3/<sha>/segmentation/` instead of
 *  `filesDir/raw_segmentation/<sha>/`, so cache eviction and Stage A
 *  purge stay coordinated.
 *
 *  Layout:
 *    cacheDir/raw_v3/<sha>/segmentation/
 *      subject.bin   — 320×320 float32 little-endian (409,600 bytes)
 *      edge.bin      — 320×320 float32 little-endian (409,600 bytes)
 *
 *  Both files are required for a cache hit; either missing or wrong-sized
 *  evicts the whole entry. Same hygiene rules as v2.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object RawV3SegmentationStorage {

    private const val TAG = "RawV3.SegStorage"

    private val MASK_N     = RawV3SegmentationMasks.MASK_SIZE * RawV3SegmentationMasks.MASK_SIZE
    private val MASK_BYTES = MASK_N * 4  // float32

    fun load(context: Context, sha256: String): RawV3SegmentationMasks? {
        if (sha256.isEmpty()) return null
        return runCatching {
            val dir = segDir(context, sha256)
            val subjectFile = File(dir, "subject.bin")
            val edgeFile    = File(dir, "edge.bin")
            val metaFile    = File(dir, "meta.bin")

            if (!subjectFile.exists() || !edgeFile.exists()) return null
            if (subjectFile.length() != MASK_BYTES.toLong() ||
                edgeFile.length()    != MASK_BYTES.toLong()) {
                Log.w(TAG, "Corrupted entry for ${sha256.take(8)} — evicting")
                subjectFile.delete()
                edgeFile.delete()
                metaFile.delete()
                return null
            }
            // Inner rect = 4 floats (u0, v0, u1, v1) written by the v2 cache
            // shape. Missing meta means the entry predates the letterbox
            // fix — evict it so the producer recomputes.
            if (!metaFile.exists() || metaFile.length() != 16L) {
                Log.w(TAG, "Stale entry for ${sha256.take(8)} (no innerRect meta) — evicting")
                subjectFile.delete()
                edgeFile.delete()
                metaFile.delete()
                return null
            }
            val meta = ByteBuffer.wrap(metaFile.readBytes())
                .order(ByteOrder.LITTLE_ENDIAN)

            RawV3SegmentationMasks(
                subjectMask     = readFloats(subjectFile),
                edgeMask        = readFloats(edgeFile),
                innerRectLeft   = meta.float,
                innerRectTop    = meta.float,
                innerRectRight  = meta.float,
                innerRectBottom = meta.float,
            ).also { Log.d(TAG, "Cache hit for ${sha256.take(8)}") }
        }.getOrElse { e ->
            Log.e(TAG, "load failed for ${sha256.take(8)}: ${e.message}")
            null
        }
    }

    fun save(context: Context, sha256: String, masks: RawV3SegmentationMasks) {
        if (sha256.isEmpty()) return
        runCatching {
            val dir = segDir(context, sha256)
            writeFloats(File(dir, "subject.bin"), masks.subjectMask)
            writeFloats(File(dir, "edge.bin"),    masks.edgeMask)
            // 16-byte meta: 4× float32 little-endian (u0, v0, u1, v1).
            val meta = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            meta.putFloat(masks.innerRectLeft)
            meta.putFloat(masks.innerRectTop)
            meta.putFloat(masks.innerRectRight)
            meta.putFloat(masks.innerRectBottom)
            File(dir, "meta.bin").writeBytes(meta.array())
            Log.d(TAG, "Saved segmentation for ${sha256.take(8)}")
        }.onFailure { e ->
            Log.e(TAG, "save failed for ${sha256.take(8)}: ${e.message}")
        }
    }

    private fun segDir(context: Context, sha256: String): File =
        File(RawV3Cache(context).sessionDir(sha256), "segmentation").also { it.mkdirs() }

    private fun readFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        val fb    = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(MASK_N) { fb.get() }
    }

    private fun writeFloats(file: File, data: FloatArray) {
        val buf = ByteBuffer.allocate(MASK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (v in data) buf.putFloat(v)
        file.writeBytes(buf.array())
    }
}
