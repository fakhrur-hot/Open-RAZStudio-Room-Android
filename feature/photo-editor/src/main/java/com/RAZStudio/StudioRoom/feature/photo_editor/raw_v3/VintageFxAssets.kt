package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bundled vintage overlay PNGs. Mist is [MIST_ASSET] (the user's mist_type3
 * film plate from Lut/Texture). Texture is [TEXTURE_ASSET]. When a file is
 * absent the FX tab hides the corresponding section.
 */
object VintageFxAssets {
    const val TEXTURE_ASSET = "fx/vintage/texture_plate_1_aged.png"
    const val TEXTURE_FALLBACK = "fx/vintage/texture_plate_0.png"
    const val MIST_ASSET = "fx/vintage/mist_type3.png"
    const val MIST_FALLBACK = "fx/vintage/mist_soft_cream.png"

    data class Overlay(val rgba: ByteArray, val width: Int, val height: Int)

    @Volatile var texture: Overlay? = null
        private set
    @Volatile var mist: Overlay? = null
        private set

    val hasTexture: Boolean get() = texture != null
    val hasMist: Boolean get() = mist != null

    private val loaded = AtomicBoolean(false)
    private val baked = AtomicBoolean(false)

    fun ensureLoaded(context: Context) {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            texture = decodeAsset(context, TEXTURE_ASSET)
                ?: decodeAsset(context, TEXTURE_FALLBACK)
            mist = decodeAsset(context, MIST_ASSET)
                ?: decodeAsset(context, MIST_FALLBACK)
            loaded.set(true)
            Log.i(TAG, "loaded texture=${texture?.let { "${it.width}×${it.height}" } ?: "none"} " +
                "mist=${mist?.let { "${it.width}×${it.height}" } ?: "none"}")
        }
    }

    /** Copy overlays into native so GPU JPEG + CPU Stage C can sample them. */
    fun bakeNative(context: Context) {
        ensureLoaded(context)
        if (baked.get()) return
        synchronized(this) {
            if (baked.get()) return
            texture?.let { RawV3Engine.bakeVintageOverlay(film = true, it.rgba, it.width, it.height) }
            mist?.let { RawV3Engine.bakeVintageOverlay(film = false, it.rgba, it.width, it.height) }
            baked.set(true)
        }
    }

    private fun decodeAsset(context: Context, path: String): Overlay? {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) } }
            .getOrNull()
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val longSide = maxOf(opts.outWidth, opts.outHeight)
        val sample = if (longSide > MAX_LONG) {
            Integer.highestOneBit((longSide - 1) / MAX_LONG)
        } else 1
        val decodeOpts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample.coerceAtLeast(1)
        }
        val bmp = runCatching {
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        }.getOrNull() ?: return null
        val argb = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
        val w = argb.width
        val h = argb.height
        val px = IntArray(w * h)
        argb.getPixels(px, 0, w, 0, 0, w, h)
        if (argb !== bmp) argb.recycle()
        bmp.recycle()
        val rgba = ByteArray(w * h * 4)
        var o = 0
        for (p in px) {
            rgba[o++] = (p shr 16).toByte()
            rgba[o++] = (p shr 8).toByte()
            rgba[o++] = p.toByte()
            rgba[o++] = (p ushr 24).toByte()
        }
        return Overlay(rgba, w, h)
    }

    private const val MAX_LONG = 1024
    private const val TAG = "RawV3.VintageFx"
}
