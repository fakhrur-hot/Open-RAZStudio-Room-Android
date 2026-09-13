/*
 * StudioRoom — TFLite-backed Auto Exposure for the Tone tab.
 *
 * Loads ae_tone_model.tflite from assets and runs inference on a 65-dim
 * feature vector extracted from the editor's neutral Bitmap (same source
 * RawAutoExposure.analyse() uses).  Returns a UserMacro delta for the 8
 * tone-tab sliders:
 *
 *   exposure / highlights / shadows / whites / blacks
 *   tonemapExposure / tonemapHighlights / ambiance
 *
 * The model was trained on the Nafifi Exposure Correction CVPR 2021
 * dataset (~24 k image pairs at EV ±1.5/±1/0) and fine-tuned on the
 * user's personal 200-sample dataset when available.
 *
 * Usage:
 *   val result = RawAutoExposureModel.analyse(context, bitmap, base, evHint)
 *   if (result != null) applyMacro(result)
 *   else fallbackToHistogramAE()
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.OrtSessionGate
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

object RawAutoExposureModel {

    private const val TAG = "AE_Model"

    // Asset paths
    private const val MODEL_ASSET   = "models/ae_tone_model.tflite"
    private const val MEAN_ASSET    = "models/ae_scaler_mean.npy"
    private const val SCALE_ASSET   = "models/ae_scaler_scale.npy"

    // Feature / output dims matching the training script
    private const val FEATURE_DIM = 65
    private const val OUTPUT_DIM  = 8

    // Model output index labels (matches train_local.py OUTPUT_COLS order):
    // 0=exp  1=shadows  2=highlights  3=whites  4=blacks  5=contrast  6=vibrance  7=saturation
    // Model predicts raw float deltas in training-label units — convert to slider units below.

    // Lazy-loaded inference state — initialised once, reused across calls
    @Volatile private var interpreter: Interpreter? = null
    private val gate = OrtSessionGate(TAG)
    @Volatile private var scalerMean:  FloatArray?  = null
    @Volatile private var scalerScale: FloatArray?  = null

    // ── Initialise (idempotent, call from any thread) ──────────────────
    private fun ensureLoaded(context: Context): Boolean {
        if (interpreter != null && scalerMean != null) return true
        return try {
            val model = loadModelBuffer(context, MODEL_ASSET)
            Log.i(TAG, "Model buffer loaded: ${model.capacity()} bytes")
            val opts  = Interpreter.Options().apply { numThreads = 2 }
            interpreter = Interpreter(model, opts)

            scalerMean  = loadNpyFloats(context, MEAN_ASSET,  FEATURE_DIM)
            scalerScale = loadNpyFloats(context, SCALE_ASSET, FEATURE_DIM)
            Log.i(TAG, "Model loaded: $MODEL_ASSET  mean[0]=${scalerMean!![0]}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load AE model: ${e.message}")
            false
        }
    }

    /**
     * Run TFLite AE model on [bitmap] and return a UserMacro with tone sliders set.
     * Returns null on failure — caller should fall back to histogram AE.
     *
     * @param bitmap      Editor neutral/preview Bitmap (sRGB, any size).
     * @param base        Current UserMacro — unchanged fields are preserved.
     * @param evHint      Known EV offset, or null.
     * @param workspaceAe Result from RawAutoExposure.analyse() if already run — used
     *                    to clamp highlight recovery so AI Expose never undoes it.
     */
    fun analyse(
        context: Context,
        bitmap: Bitmap,
        base: UserMacro,
        evHint: Float? = null,
        workspaceAe: UserMacro? = null,
    ): UserMacro? {
        if (gate.isReleased) return null
        if (!ensureLoaded(context)) return null
        val interp = interpreter ?: return null
        val mean   = scalerMean  ?: return null
        val scale  = scalerScale ?: return null

        return gate.run {
        try {
            val features = extractFeatures(bitmap, evHint)

            // Measure highlight pixel fraction directly from the bitmap for clamping below.
            val hlStats = measureHighlights(bitmap)
            Log.i(TAG, "features: p50L=${features[5].fmt()} p99L=${features[8].fmt()} " +
                "ev=${features[64].fmt()} hlFrac=${hlStats.first.fmt()} blownFrac=${hlStats.second.fmt()}")

            // Standardise: (x - mean) / scale
            for (i in 0 until FEATURE_DIM) {
                val s = scale[i].let { if (it < 1e-8f) 1f else it }
                features[i] = (features[i] - mean[i]) / s
            }

            // Run inference
            val inputBuf  = ByteBuffer.allocateDirect(FEATURE_DIM * 4).order(ByteOrder.nativeOrder())
            val outputBuf = ByteBuffer.allocateDirect(OUTPUT_DIM  * 4).order(ByteOrder.nativeOrder())
            for (v in features) inputBuf.putFloat(v)
            inputBuf.rewind()
            interp.run(inputBuf, outputBuf)
            outputBuf.rewind()

            // Model outputs raw float deltas in training-label units (tanh * 1.5 range).
            // Convert each to the corresponding UserMacro slider unit:
            //   exp       [-0.10..+1.16] -> exposure EV    (1:1 mapping, clamp -2..+2)
            //   shadows   [-0.30..+0.34] -> shadows slider  (* 100, clamp -30..+60)
            //   highlights[-0.08..+0.08] -> highlights slider(* 500, clamp -60..0)
            //   whites    [-0.24..+0.30] -> whites slider   (* 100, clamp -30..+40)
            //   blacks    [-0.20..+0.20] -> blacks slider   (* 100, clamp -30..0)
            //   contrast  (unused -> 0)
            //   vibrance  (unused -> 0)
            //   saturation(unused -> 0)
            val raw = FloatArray(OUTPUT_DIM) { outputBuf.getFloat() }

            var exp        = raw[0].coerceIn(-2f, 2f)
            var shadows    = (raw[1] * 100f).coerceIn(-30f, 60f)
            var highlights = (raw[2] * 500f).coerceIn(-60f, 0f)
            var whites     = (raw[3] * 100f).coerceIn(-30f, 40f)
            var blacks     = (raw[4] * 100f).coerceIn(-30f, 0f)

            // ── Highlight protection ──────────────────────────────────────────
            // hlFrac  = fraction of pixels with luma > 0.80 (bright, watch)
            // blownFrac = fraction of pixels with luma > 0.96 (clipped, dangerous)
            val hlFrac    = hlStats.first
            val blownFrac = hlStats.second

            // If significant blown pixels exist, force highlights recovery and cap exposure.
            if (blownFrac > 0.01f) {
                // Scale highlight pull proportionally to how blown the image is.
                val pullStrength = (blownFrac * 4f).coerceIn(0.3f, 1.0f)
                val minHl = (-60f * pullStrength).coerceAtMost(-15f)
                if (highlights > minHl) highlights = minHl
                // Cap exposure so we don't push more light into already blown zones.
                val maxExp = (1f - blownFrac * 5f).coerceIn(-0.5f, 0.5f)
                if (exp > maxExp) exp = maxExp
                Log.i(TAG, "blown highlight clamp: blownFrac=${blownFrac.fmt()} " +
                    "exp capped=${ maxExp.fmt()} hl forced=${minHl.fmt()}")
            } else if (hlFrac > 0.05f) {
                // Moderate bright area — gentle highlight protection only.
                val maxExp = (0.8f - hlFrac).coerceIn(0f, 0.8f)
                if (exp > maxExp) exp = maxExp
                if (highlights > -8f) highlights = -8f
            }

            // Never undo stronger highlight recovery that the workspace AE already set.
            workspaceAe?.let { ws ->
                if (ws.highlights < highlights) highlights = ws.highlights
                if (exp > ws.exposure + 0.3f) exp = ws.exposure + 0.3f
            }

            // Re-measure highlights as they will appear AFTER the exposure boost is applied.
            // The neutral Stage A bitmap is flat/dark — blown pixels only emerge after the
            // model's exposure lift. Simulate exp2(exp) gain on luma before thresholding.
            val expGain = Math.pow(2.0, exp.toDouble()).toFloat()
            val postBoostBlown = measureHighlightsWithGain(bitmap, expGain)

            Log.i(TAG, "model -> exp=${exp.fmt()} hl=${highlights.fmt()} " +
                "sh=${shadows.fmt()} wh=${whites.fmt()} bl=${blacks.fmt()} " +
                "postBoostBlown=${postBoostBlown.fmt()}")

            base.copy(
                exposure        = exp,
                highlights      = highlights,
                shadows         = shadows,
                whites          = whites,
                blacks          = blacks,
                filmicHlProtect = (postBoostBlown * 4f).coerceIn(0f, 1f),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            null
        }
        }
    }

    /**
     * Measure highlight pixel fractions from [bitmap].
     * The bitmap is ARGB_8888 with gamma-encoded sRGB values — getPixels()
     * returns channels already in perceptual [0,255] space. No gamma correction
     * needed; dividing by 255 gives the correct [0,1] gamma luma directly.
     * Returns Pair(hlFrac, blownFrac):
     *   hlFrac    = fraction of pixels with luma > 0.80 (bright zone)
     *   blownFrac = fraction of pixels with luma > 0.96 (clipped / blown)
     */
    fun measureHighlights(bitmap: Bitmap): Pair<Float, Float> {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val n = pixels.size.toFloat()
        var hlCount = 0; var blownCount = 0
        for (px in pixels) {
            val ri = ((px shr 16) and 0xFF) / 255f
            val gi = ((px shr  8) and 0xFF) / 255f
            val bi = ( px         and 0xFF) / 255f
            val li = 0.299f * ri + 0.587f * gi + 0.114f * bi
            if (li > 0.80f) hlCount++
            if (li > 0.96f) blownCount++
        }
        return Pair(hlCount / n, blownCount / n)
    }

    /**
     * Like measureHighlights but applies a linear [gain] to each pixel's luma
     * before thresholding. The gain is an EV multiplier (exp2(ev)) applied in
     * gamma-encoded space — approximates what the exposure slider will do to the
     * perceptual brightness, which is sufficient for blown-pixel prediction.
     * Returns the fraction of pixels that will exceed 0.96 luma after the gain.
     */
    fun measureHighlightsWithGain(bitmap: Bitmap, gain: Float): Float {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val n = pixels.size.toFloat()
        var blownCount = 0
        for (px in pixels) {
            val ri = ((px shr 16) and 0xFF) / 255f
            val gi = ((px shr  8) and 0xFF) / 255f
            val bi = ( px         and 0xFF) / 255f
            val li = (0.299f * ri + 0.587f * gi + 0.114f * bi) * gain
            if (li > 0.96f) blownCount++
        }
        return blownCount / n
    }

    /** Release TFLite resources — safe while analyse is mid-infer. */
    fun release() {
        gate.release {
            interpreter?.close()
            interpreter = null
            scalerMean  = null
            scalerScale = null
        }
    }

    // ── Feature extraction (mirrors Colab Cell 5 extract_features) ────────
    private fun extractFeatures(bitmap: Bitmap, ev: Float?): FloatArray {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val n = pixels.size.toFloat()
        // Per-pixel luma + channel arrays
        val luma = FloatArray(pixels.size)
        val r    = FloatArray(pixels.size)
        val g    = FloatArray(pixels.size)
        val b    = FloatArray(pixels.size)
        var sumL = 0.0; var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        var anyClip = 0
        for (i in pixels.indices) {
            val px = pixels[i]
            // Stage A bitmap is gamma-encoded sRGB (LibRaw gamm applied).
            // Pixels are already in perceptual [0,1] space — no gamma needed.
            // NOTE: model result is currently discarded (safeExp = histoAe.exposure);
            // this path only affects feature statistics logged for diagnostics.
            val ri = (((px shr 16) and 0xFF) / 255.0).toFloat()
            val gi = (((px shr  8) and 0xFF) / 255.0).toFloat()
            val bi = (( px         and 0xFF) / 255.0).toFloat()
            val li = 0.299f * ri + 0.587f * gi + 0.114f * bi
            luma[i] = li; r[i] = ri; g[i] = gi; b[i] = bi
            sumL += li; sumR += ri; sumG += gi; sumB += bi
            if (ri >= 254/255f || gi >= 254/255f || bi >= 254/255f) anyClip++
        }
        val meanL = (sumL / n).toFloat()
        val meanR = (sumR / n).toFloat()
        val meanG = (sumG / n).toFloat()
        val meanB = (sumB / n).toFloat()

        val sortedL = luma.copyOf().also { it.sort() }
        val sortedR = r.copyOf().also { it.sort() }
        val sortedG = g.copyOf().also { it.sort() }
        val sortedB = b.copyOf().also { it.sort() }

        fun pct(arr: FloatArray, p: Float): Float {
            val idx = (p / 100f * (arr.size - 1)).toInt().coerceIn(0, arr.size - 1)
            return arr[idx]
        }

        val f = FloatArray(FEATURE_DIM)
        var fi = 0

        // Luma percentiles: p0.5,5,10,25,50,75,90,95,99,99.5  (10)
        for (p in floatArrayOf(0.5f,5f,10f,25f,50f,75f,90f,95f,99f,99.5f))
            f[fi++] = pct(sortedL, p)

        // Per-channel: mean, p99.5, clip_fraction  (9)
        for (arr in arrayOf(sortedR, sortedG, sortedB)) {
            val m = when (arr) {
                sortedR -> meanR; sortedG -> meanG; else -> meanB
            }
            f[fi++] = m
            f[fi++] = pct(arr, 99.5f)
            f[fi++] = arr.count { it >= 254/255f } / n
        }

        // Channel imbalance  (4)
        val gm = meanG + 1e-6f
        f[fi++] = meanR - gm; f[fi++] = meanB - gm
        f[fi++] = meanR / gm; f[fi++] = meanB / gm

        // Highlight/shadow fractions  (4)
        f[fi++] = luma.count { it > 0.9f } / n
        f[fi++] = luma.count { it > 0.8f } / n
        f[fi++] = luma.count { it < 0.1f } / n
        f[fi++] = luma.count { it < 0.2f } / n

        // Dynamic range / IQR  (2)
        f[fi++] = pct(sortedL, 99.5f) - pct(sortedL, 0.5f)
        f[fi++] = pct(sortedL, 75f)   - pct(sortedL, 25f)

        // 32-bucket luma histogram  (32)
        val hist = IntArray(32)
        for (v in luma) hist[(v * 31.99f).toInt().coerceIn(0, 31)]++
        for (c in hist) f[fi++] = c / n

        // Log mean, skewness proxy, any_clipped  (3)
        f[fi++] = kotlin.math.ln(meanL + 1e-4f)
        f[fi++] = meanL - pct(sortedL, 50f)
        f[fi++] = if (anyClip > (n * 0.005f)) 1f else 0f

        // EV feature at index 64
        f[fi] = ev ?: 0f

        return f
    }

    // ── Asset loading helpers ─────────────────────────────────────────────

    private fun loadModelBuffer(context: Context, assetPath: String): ByteBuffer {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).also {
            it.put(bytes)
            it.rewind()
        }
    }

    /**
     * Load a 1-D float32 array from a NumPy .npy file.
     * Handles only the simple case written by np.save(): 1-D float32,
     * little-endian, no object arrays, no Fortran order.
     * The 128-byte header is parsed just enough to skip it safely.
     */
    private fun loadNpyFloats(context: Context, assetPath: String, expectedLen: Int): FloatArray {
        context.assets.open(assetPath).use { stream ->
            val header = ByteArray(128)
            var read = 0
            while (read < 128) {
                val n = stream.read(header, read, 128 - read)
                if (n < 0) break
                read += n
            }
            // NumPy magic: \x93NUMPY then 2 bytes version, 2 bytes header_len (little-endian)
            // Total header size = 10 + header_len (for v1.0)
            val headerLen = (header[8].toInt() and 0xFF) or ((header[9].toInt() and 0xFF) shl 8)
            val totalHeader = 10 + headerLen
            // Skip any remaining header bytes beyond the 128 we already read
            val alreadyRead = 128
            val toSkip = (totalHeader - alreadyRead).coerceAtLeast(0).toLong()
            if (toSkip > 0) stream.skip(toSkip)

            val buf = ByteArray(expectedLen * 4)
            var pos = 0
            while (pos < buf.size) {
                val n = stream.read(buf, pos, buf.size - pos)
                if (n < 0) break
                pos += n
            }
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(expectedLen) { bb.getFloat() }
        }
    }

    private fun Float.fmt() = "%.3f".format(this)
}
