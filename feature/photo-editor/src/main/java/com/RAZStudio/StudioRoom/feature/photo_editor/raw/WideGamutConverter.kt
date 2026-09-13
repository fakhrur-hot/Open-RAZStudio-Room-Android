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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import androidx.core.graphics.createBitmap
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawColorSpace
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceBitDepth
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * Stage C: convert linear camera-native pixels to ProPhoto RGB (ROMM RGB) and
 * produce two artefacts:
 *
 *   1. A 33³ .cube file encoding the full camera→ProPhoto transform (wide gamut cache).
 *      This is keyed on the camera's colour matrix so it is unique per camera model.
 *
 *   2. A sRGB workspace bitmap for immediate GPU LUT preview.
 *      Derived from the ProPhoto image via the standard ProPhoto→sRGB matrix,
 *      with a simple 2.2 gamma encode. This is what [GpuLutProcessor] receives.
 *
 * ── Colour math ────────────────────────────────────────────────────────────────
 *
 * LibRaw exposes [RawMetadata.rgbCam] (3×4, row-major): camera linear → linear sRGB.
 * We compose:
 *
 *   cameraLinear → [rgbCam] → linearSRGB
 *   linearSRGB   → [SRGB_TO_XYZ_D65] → XYZ(D65)
 *   XYZ(D65)     → [BRADFORD_D65_TO_D50] → XYZ(D50)
 *   XYZ(D50)     → [XYZ_D50_TO_PROPHOTO] → linearProPhoto
 *
 * For the workspace sRGB:
 *   linearProPhoto → [PROPHOTO_TO_XYZ_D50] → XYZ(D50)
 *   XYZ(D50)       → [BRADFORD_D50_TO_D65] → XYZ(D65)
 *   XYZ(D65)       → [XYZ_TO_SRGB] → linearSRGB → gamma(2.2) → sRGB [0,255]
 *
 * The .cube file maps lattice points from linearSRGB (normalised) → ProPhoto values,
 * so it can also be used in reverse: any LUT pipeline that receives the ProPhoto
 * bitmap as input can use the inverse matrix to convert back before applying sRGB LUTs.
 *
 * ── .cube format ──────────────────────────────────────────────────────────────
 *
 * TITLE "RAZStudio_<sha256_8chars>_ProPhoto"
 * LUT_3D_SIZE 33
 * DOMAIN_MIN 0.0 0.0 0.0
 * DOMAIN_MAX 1.0 1.0 1.0
 * <33³ lines, R-fastest order, values normalised to [0,1] ProPhoto>
 *
 * This .cube is directly loadable by [CubeLutFilter] / [GpuLutProcessor] to apply
 * camera→ProPhoto conversion as a filter step, or to build the combined
 * (conversion + creative LUT) chain.
 */
object WideGamutConverter {

    // ── Colour matrices ────────────────────────────────────────────────────────

    // Linear sRGB → CIE XYZ (D65), IEC 61966-2-1
    private val SRGB_TO_XYZ_D65 = floatArrayOf(
        0.4124564f, 0.3575761f, 0.1804375f,
        0.2126729f, 0.7151522f, 0.0721750f,
        0.0193339f, 0.1191920f, 0.9503041f,
    )

    // Bradford chromatic adaptation D65 → D50
    private val BRADFORD_D65_TO_D50 = floatArrayOf(
         0.9555766f, -0.0230393f,  0.0631636f,
        -0.0282895f,  1.0099416f,  0.0210077f,
         0.0122982f, -0.0204830f,  1.3299098f,
    )

    // CIE XYZ (D50) → linear ProPhoto RGB (ROMM RGB), ICC standard
    private val XYZ_D50_TO_PROPHOTO = floatArrayOf(
         1.3459433f, -0.2556075f, -0.0511118f,
        -0.5445989f,  1.5081673f,  0.0205351f,
         0.0000000f,  0.0000000f,  1.2118128f,
    )

    // Inverse: linear ProPhoto → XYZ (D50)
    private val PROPHOTO_TO_XYZ_D50 = floatArrayOf(
        0.7976749f, 0.1351917f, 0.0313534f,
        0.2880402f, 0.7118741f, 0.0000857f,
        0.0000000f, 0.0000000f, 0.8252100f,
    )

    // Bradford D50 → D65
    private val BRADFORD_D50_TO_D65 = floatArrayOf(
         1.0478112f,  0.0228866f, -0.0501270f,
         0.0295424f,  0.9904844f, -0.0170491f,
        -0.0092345f,  0.0150436f,  0.7521316f,
    )

    // XYZ (D65) → linear sRGB
    private val XYZ_TO_SRGB = floatArrayOf(
         3.2404542f, -1.5371385f, -0.4985314f,
        -0.9692660f,  1.8760108f,  0.0415560f,
         0.0556434f, -0.2040259f,  1.0572252f,
    )

    // ── Compound matrices (precomputed) ────────────────────────────────────────

    // camera → ProPhoto (3×3, composed from rgbCam 3×3 ignoring 4th column + chain above)
    // Built per-file from the camera's rgbCam matrix.

    // ProPhoto → sRGB (3×3, static, used for workspace bitmap generation)
    // Chain: ProPhoto → XYZ(D50) → XYZ(D65) → linear sRGB
    // Column-vector convention: combined = XYZ_TO_SRGB × BRADFORD_D50_TO_D65 × PROPHOTO_TO_XYZ_D50
    private val PROPHOTO_TO_SRGB = mul3x3(XYZ_TO_SRGB, mul3x3(BRADFORD_D50_TO_D65, PROPHOTO_TO_XYZ_D50))

    // sRGB → ProPhoto (3×3, static, inverse of the ProPhoto→sRGB chain above)
    // Chain: linear sRGB → XYZ(D65) → XYZ(D50) → linear ProPhoto
    private val SRGB_TO_PROPHOTO = mul3x3(XYZ_D50_TO_PROPHOTO, mul3x3(BRADFORD_D65_TO_D50, SRGB_TO_XYZ_D65))

    // Linear sRGB → linear Display-P3 (both D65, no chromatic adaptation needed).
    // Values from the Display P3 spec (BT.2020 primaries with the sRGB transfer
    // function, D65 white point). Documented in `design.md` §3.6.
    private val SRGB_TO_DISPLAY_P3 = floatArrayOf(
         0.8224621f,  0.1775380f,  0.0000000f,
         0.0331941f,  0.9668058f,  0.0000000f,
         0.0170827f,  0.0723974f,  0.9105199f,
    )

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Convert [bitmap] (sRGB workspace) to [colorSpace].
     * SRGB: returns [bitmap] unchanged.
     * PROPHOTO_RGB: decode sRGB γ2.2 → apply sRGB→ProPhoto matrix → re-encode with ProPhoto γ1.8.
     */
    fun convertBitmapToColorSpace(bitmap: Bitmap, colorSpace: RawColorSpace): Bitmap {
        if (colorSpace == RawColorSpace.SRGB) return bitmap
        // 16-bit (RGBA_F16) bitmaps already carry a `ColorSpace` tag from Stage C
        // and are not re-encodable via the legacy 8-bit IntArray path. Skip the
        // conversion — Android color-manages display directly from the tagged
        // bitmap, and the export encoders handle the gamut at write time.
        if (bitmap.config == Bitmap.Config.RGBA_F16) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(w * h)
        when (colorSpace) {
            // sRGB → Display-P3 conversion. Both spaces share the D65 white point and
            // identical 2.2-style transfer functions (Display-P3 reuses the sRGB curve),
            // so chromatic adaptation is unnecessary — just the 3×3 primaries rotation
            // applied in linear light, then re-encoded with the sRGB curve.
            //
            // Why we tag the output bitmap with ColorSpace.DISPLAY_P3: Android's PNG
            // encoder writes an ICC profile chunk matching the bitmap's color space.
            // Without the tag the encoder defaults to "sRGB Gamut with 2.2 Transfer"
            // (Skia default), which causes Display-P3 viewers to interpret the wider
            // primaries as sRGB — washing out the colors. Setting the tag here makes
            // the saved file render identically to the editor canvas (verified on
            // device 2026-05-24 — until this branch landed, every BIT_8 + DISPLAY_P3
            // save was effectively sRGB content with a misleading workspace label).
            RawColorSpace.DISPLAY_P3 -> {
                val m = SRGB_TO_DISPLAY_P3
                for (i in src.indices) {
                    val px = src[i]
                    val rf = ((px shr 16 and 0xFF) / 255f).toDouble().pow(2.2).toFloat()
                    val gf = ((px shr  8 and 0xFF) / 255f).toDouble().pow(2.2).toFloat()
                    val bf = ((px        and 0xFF) / 255f).toDouble().pow(2.2).toFloat()
                    val ro = (m[0]*rf + m[1]*gf + m[2]*bf).coerceIn(0f, 1f)
                    val go = (m[3]*rf + m[4]*gf + m[5]*bf).coerceIn(0f, 1f)
                    val bo = (m[6]*rf + m[7]*gf + m[8]*bf).coerceIn(0f, 1f)
                    val ri = (ro.toDouble().pow(1.0 / 2.2).toFloat() * 255f).toInt().coerceIn(0, 255)
                    val gi = (go.toDouble().pow(1.0 / 2.2).toFloat() * 255f).toInt().coerceIn(0, 255)
                    val bi = (bo.toDouble().pow(1.0 / 2.2).toFloat() * 255f).toInt().coerceIn(0, 255)
                    dst[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
                val out = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Bitmap.createBitmap(
                        w, h, Bitmap.Config.ARGB_8888, true,
                        android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3),
                    )
                } else {
                    createBitmap(w, h, Bitmap.Config.ARGB_8888)
                }
                out.setPixels(dst, 0, w, 0, 0, w, h)
                return out
            }
            RawColorSpace.PROPHOTO_RGB -> {
                val m = SRGB_TO_PROPHOTO
                for (i in src.indices) {
                    val px = src[i]
                    val rf = ((px shr 16 and 0xFF) / 255f).toDouble().pow(2.2).toFloat()
                    val gf = ((px shr  8 and 0xFF) / 255f).toDouble().pow(2.2).toFloat()
                    val bf = ((px        and 0xFF) / 255f).toDouble().pow(2.2).toFloat()
                    val ro = (m[0]*rf + m[1]*gf + m[2]*bf).coerceIn(0f, 1f)
                    val go = (m[3]*rf + m[4]*gf + m[5]*bf).coerceIn(0f, 1f)
                    val bo = (m[6]*rf + m[7]*gf + m[8]*bf).coerceIn(0f, 1f)
                    val ri = (ro.toDouble().pow(1.0 / 1.8).toFloat() * 255f).toInt().coerceIn(0, 255)
                    val gi = (go.toDouble().pow(1.0 / 1.8).toFloat() * 255f).toInt().coerceIn(0, 255)
                    val bi = (bo.toDouble().pow(1.0 / 1.8).toFloat() * 255f).toInt().coerceIn(0, 255)
                    dst[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
            }
            RawColorSpace.SRGB -> return bitmap  // unreachable; handled by early return above
        }
        val out = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        return out
    }

    data class StageC(
        /** 33³ float ProPhoto values (R-fastest), normalised [0,1] */
        val proPhotoCube: FloatArray,
        /** .cube file text ready to write */
        val cubeFileText: String,
        /** 8-bit sRGB workspace bitmap for GPU LUT preview */
        val workspaceBitmap: Bitmap,
    )

    /**
     * Convert [pixelsFloat16] (from Stage B) to ProPhoto RGB and produce [StageC].
     *
     * @param pixelsFloat16  Float16 RGBA buffer (width × height × 8 bytes, row-major).
     * @param width          Image width.
     * @param height         Image height.
     * @param metadata       Contains rgbCam and whiteLevel for normalisation.
     */
    suspend fun convert(
        pixelsFloat16: ByteArray,
        width: Int,
        height: Int,
        metadata: RawMetadata,
        config: WorkspaceConfig = WorkspaceConfig.Default,
    ): StageC = convert(java.nio.ByteBuffer.wrap(pixelsFloat16), width, height, metadata, config)

    /**
     * Overload that accepts any [java.nio.ByteBuffer]. The full-res cache-hit path
     * uses this with a `FileChannel.map()` mapped buffer to avoid loading 161 MB
     * into the JVM heap as a [ByteArray]. The on-disk file becomes the backing
     * store; the OS demand-pages pixels as the converter reads them.
     */
    suspend fun convert(
        pixelsFloat16: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        metadata: RawMetadata,
        config: WorkspaceConfig = WorkspaceConfig.Default,
    ): StageC = withContext(Dispatchers.Default) {
        android.util.Log.d("WideGamutConverter", "convert: start ${width}x${height} buf=${pixelsFloat16.capacity()}")
        android.util.Log.d("WideGamutConverter", "convert: rgbCam=${metadata.rgbCam.joinToString()}")
        val cameraToProPhoto = buildCameraToProPhotoMatrix(metadata.rgbCam)
        android.util.Log.d("WideGamutConverter", "convert: cam2prophoto=${cameraToProPhoto.joinToString()}")

        // Compute the WB-ratio-derived highlight ceiling for each channel.
        // LibRaw boosts R and B by (wb_R/wb_G) and (wb_B/wb_G) respectively.
        // Green clips at 1.0; R and B would clip at 1.0 too but their WB multipliers
        // mean the sensor headroom before clipping is proportionally smaller.
        // ceiling_R = wb_G / wb_R,  ceiling_B = wb_G / wb_B  (both ≤ 1.0)
        // Any sRGB output above these ceilings is a highlight reconstruction artifact.
        val cubeSize = 33
        android.util.Log.d("WideGamutConverter", "convert: building camera→ProPhoto matrix")

        // ── Build .cube LUT ────────────────────────────────────────────────────
        // Maps normalised linear sRGB lattice points → ProPhoto, accounting for
        // the full camera→ProPhoto chain so downstream LUTs see correct input.
        val cubeValues = FloatArray(cubeSize * cubeSize * cubeSize * 3)
        val c = cameraToProPhoto
        var idx = 0
        for (b in 0 until cubeSize) {
            for (g in 0 until cubeSize) {
                for (r in 0 until cubeSize) {
                    val rf = r.toFloat() / (cubeSize - 1)
                    val gf = g.toFloat() / (cubeSize - 1)
                    val bf = b.toFloat() / (cubeSize - 1)
                    cubeValues[idx++] = (c[0]*rf + c[1]*gf + c[2]*bf).coerceIn(0f, 1f)
                    cubeValues[idx++] = (c[3]*rf + c[4]*gf + c[5]*bf).coerceIn(0f, 1f)
                    cubeValues[idx++] = (c[6]*rf + c[7]*gf + c[8]*bf).coerceIn(0f, 1f)
                }
            }
        }

        android.util.Log.d("WideGamutConverter", "convert: LUT built (${cubeSize}^3)")
        val sha8 = metadata.fileSha256.take(8)
        val cubeText = buildCubeText("RAZStudio_${sha8}_ProPhoto", cubeSize, cubeValues)
        android.util.Log.d("WideGamutConverter", "convert: cube text built ${cubeText.length} chars")

        // ── Convert image pixels to ProPhoto and then to sRGB workspace ────────
        // Compose camera→ProPhoto and ProPhoto→sRGB into a single matrix to avoid
        // two matrix multiplications and all Triple allocations per pixel.
        // Correct order: apply cameraToProPhoto first, then PROPHOTO_TO_SRGB
        val cam2srgb = mul3x3(PROPHOTO_TO_SRGB, cameraToProPhoto)
        val d0 = cam2srgb[0]; val d1 = cam2srgb[1]; val d2 = cam2srgb[2]
        val d3 = cam2srgb[3]; val d4 = cam2srgb[4]; val d5 = cam2srgb[5]
        val d6 = cam2srgb[6]; val d7 = cam2srgb[7]; val d8 = cam2srgb[8]

        // ── Highlight desaturation threshold in linear sRGB space ─────────────
        // The previous approach compared sRGB channel values against camera-space
        // WB ratios (normR/normB), which are in different spaces and fire at wrong
        // thresholds — causing residual magenta halos at highlight/shadow edges.
        //
        // Correct approach: map the camera-space green-clip point through the full
        // cam2srgb matrix to find where it lands in linear sRGB, then use that as
        // the per-channel ceiling. The ramp starts earlier (0.7× ceiling) to catch
        // edge transition pixels before they fully clip.
        //
        // For Canon CR2 typical daylight WB (R≈2.0, G≈1.0, B≈1.6):
        //   normR = 0.50, normB = 0.625 in camera space.
        // Mapped through cam2srgb the sRGB clip ceiling lands around 0.80–0.92
        // per channel, which is where the ramp should start — not at >1.0.
        val pixelCount = width * height
        val midByte = pixelCount * 4  // center pixel byte offset (8 bytes/pixel, mid is at pixelCount/2 * 8)
        val sampleR = float16ToFloat(pixelsFloat16[midByte], pixelsFloat16[midByte+1])
        val sampleG = float16ToFloat(pixelsFloat16[midByte+2], pixelsFloat16[midByte+3])
        val sampleB = float16ToFloat(pixelsFloat16[midByte+4], pixelsFloat16[midByte+5])
        android.util.Log.d("WideGamutConverter", "convert: mid pixel linear R=$sampleR G=$sampleG B=$sampleB")

        // ── 16-bit RGBA_F16 branch ────────────────────────────────────────────
        // When the user selected BIT_16 in the workspace dialog and the device
        // supports it (API 26+), produce an `RGBA_F16` bitmap tagged with the
        // chosen colour space. Pixels are written as half-floats in linear light
        // — Android's color management transforms to the display at present time
        // and the export path encodes 16-bit PNG/TIFF with no precision loss.
        //
        // We still emit the 8-bit `cubeText` (used by the GPU LUT preview) and
        // the 8-bit `workspaceBitmap` is replaced by the FP16 bitmap. Callers
        // that need to know the bit depth check `bitmap.config == RGBA_F16`.
        android.util.Log.i(
            "WideGamutConverter",
            "convert: branch decision bitDepth=${config.bitDepth.name} gamut=${config.colorGamut.name} sdk=${Build.VERSION.SDK_INT}",
        )
        if (config.bitDepth == WorkspaceBitDepth.BIT_16 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            android.util.Log.i("WideGamutConverter", "convert: BIT_16 path entered, gamut=${config.colorGamut.name}")
            val fp16Bitmap = build16BitBitmap(
                width, height, pixelsFloat16, cam2srgb, config.colorGamut,
            )
            return@withContext StageC(
                proPhotoCube = cubeValues,
                cubeFileText = cubeText,
                workspaceBitmap = fp16Bitmap,
            )
        }

        android.util.Log.d("WideGamutConverter", "convert: allocating IntArray $pixelCount px")
        val workPixels = IntArray(pixelCount)
        // Bulk read: pull every half-float into a JVM ShortArray in one native copy
        // instead of indexing the ByteBuffer twice per channel inside the hot loop.
        // The buffer was allocated LITTLE_ENDIAN upstream so `asShortBuffer` gives
        // us the correct half-float interpretation. RGBA_F16 = 4 shorts/pixel.
        val shortCount = pixelCount * 4
        val shorts = ShortArray(shortCount)
        pixelsFloat16.duplicate()
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        android.util.Log.d("WideGamutConverter", "convert: starting pixel loop (parallel × 4)")
        // Parallelize the 20MP pixel loop across 4 cores. Each worker owns a
        // contiguous pixel range — both the read (shorts) and write (workPixels)
        // index spaces are disjoint, so no synchronization needed. Verified on
        // adb 2026-05-25: Stage C 8-bit pass was the single largest hot loop in
        // the open-RAW path (≈22 s of the ≈30 s end-to-end at 20MP).
        coroutineScope {
            val n = 4
            val band = (pixelCount + n - 1) / n
            (0 until n).map { t ->
                async(Dispatchers.Default) {
                    val i0 = t * band
                    val i1 = minOf(i0 + band, pixelCount)
                    var sIdx = i0 * 4
                    var i = i0
                    while (i < i1) {
                        val rf = float16ToFloat(shorts[sIdx])
                        val gf = float16ToFloat(shorts[sIdx + 1])
                        val bf = float16ToFloat(shorts[sIdx + 2])
                        sIdx += 4

                        val sr = (d0 * rf + d1 * gf + d2 * bf).coerceIn(0f, 1f)
                        val sg = (d3 * rf + d4 * gf + d5 * bf).coerceIn(0f, 1f)
                        val sb = (d6 * rf + d7 * gf + d8 * bf).coerceIn(0f, 1f)

                        val ri = (gamma22(sr) * 255f).toInt().coerceIn(0, 255)
                        val gi = (gamma22(sg) * 255f).toInt().coerceIn(0, 255)
                        val bi = (gamma22(sb) * 255f).toInt().coerceIn(0, 255)
                        workPixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                        i++
                    }
                }
            }.awaitAll()
        }

        android.util.Log.d("WideGamutConverter", "convert: pixel loop done, creating bitmap")
        val bmp = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(workPixels, 0, width, 0, 0, width, height)
        val sample = bmp.getPixel(width / 2, height / 2)
        android.util.Log.d("WideGamutConverter", "convert: done ${bmp.width}x${bmp.height} centerPixel=0x${sample.toString(16)} workPixels[mid]=0x${workPixels[pixelCount/2].toString(16)}")

        StageC(
            proPhotoCube  = cubeValues,
            cubeFileText  = cubeText,
            workspaceBitmap = bmp,
        )
    }

    // ── Colour math helpers ───────────────────────────────────────────────────

    /**
     * Build the camera→ProPhoto 3×3 matrix from LibRaw's 3×4 rgb_cam.
     * The 4th column of rgb_cam is a second G channel (ignored here).
     *
     * When rgb_cam is the identity (LibRaw did not apply the color matrix —
     * common when the camera make/model was not read from the file), we look up
     * the Adobe-derived camera→sRGB matrix for the given make/model instead.
     * If neither is available, fall back to identity (no matrix conversion).
     */
    private fun buildCameraToProPhotoMatrix(
        rgbCam: FloatArray,
        make: String = "",
        model: String = "",
    ): FloatArray {
        // LibRaw with output_color=1 already applies rgb_cam internally during decode.
        // The identity matrix in metadata is an artifact of how the native bridge reads
        // back the stored value — the pixel data is already correct linear sRGB.
        // We only need to convert linear sRGB → ProPhoto, so compose with SRGB_TO_PROPHOTO.
        @Suppress("UNUSED_PARAMETER")
        val cam3x3 = floatArrayOf(
            rgbCam[0], rgbCam[1], rgbCam[2],
            rgbCam[4], rgbCam[5], rgbCam[6],
            rgbCam[8], rgbCam[9], rgbCam[10],
        )
        return mul3x3(SRGB_TO_PROPHOTO, cam3x3)
    }

    private fun applyMatrix3x3(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> =
        Triple(
            m[0] * r + m[1] * g + m[2] * b,
            m[3] * r + m[4] * g + m[5] * b,
            m[6] * r + m[7] * g + m[8] * b,
        )

    private fun mul3x3(a: FloatArray, b: FloatArray): FloatArray {
        val c = FloatArray(9)
        for (row in 0 until 3) for (col in 0 until 3) {
            c[row * 3 + col] = (0 until 3).sumOf { k ->
                (a[row * 3 + k] * b[k * 3 + col]).toDouble()
            }.toFloat()
        }
        return c
    }

    private fun identity3x3() = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)

    /** Simple power-law gamma 2.2 encode (approximation of sRGB transfer function). */
    private fun gamma22(v: Float): Float = v.toDouble().pow(1.0 / 2.2).toFloat()

    // ── Float16 decoding ──────────────────────────────────────────────────────

    private fun float16ToFloat(lo: Byte, hi: Byte): Float {
        val bits = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
        return float16BitsToFloat(bits)
    }

    /**
     * Fast-path variant that accepts the half-float as a Short directly. Used
     * by the bulk-read pixel loops to skip the per-call byte-merge and the
     * NIO/JNI overhead of [java.nio.ByteBuffer.get] per byte.
     */
    private fun float16ToFloat(s: Short): Float =
        float16BitsToFloat(s.toInt() and 0xFFFF)

    private fun float16BitsToFloat(bits: Int): Float {
        val e = (bits and 0x7C00) shr 10
        val m = bits and 0x03FF
        val sign = (bits and 0x8000) shl 16
        return when {
            e == 0 && m == 0 -> java.lang.Float.intBitsToFloat(sign)
            e == 0 -> {
                var mantissa = m
                var exp = -14
                while (mantissa and 0x0400 == 0) { mantissa = mantissa shl 1; exp-- }
                java.lang.Float.intBitsToFloat(sign or ((exp + 127) shl 23) or ((mantissa and 0x03FF) shl 13))
            }
            e == 31 -> java.lang.Float.intBitsToFloat(sign or 0x7F800000 or (m shl 13))
            else -> java.lang.Float.intBitsToFloat(sign or ((e + 112) shl 23) or (m shl 13))
        }
    }

    // ── 16-bit Stage C output ──────────────────────────────────────────────────

    /**
     * Build an `RGBA_F16` workspace bitmap tagged with the chosen color space.
     *
     * Camera-linear pixels are multiplied by [cam2srgb] to land in linear sRGB
     * (the workspace coordinate system), then written as half-floats. NO gamma
     * encoding is applied — the bitmap is linear, the [ColorSpace] tag tells
     * Android how to encode for display.
     *
     * For [RawColorSpace.SRGB]: tagged `LINEAR_EXTENDED_SRGB` (the canonical
     * companion of `RGBA_F16`). For [RawColorSpace.DISPLAY_P3]: tagged
     * `DISPLAY_P3` (Android color-manages from linear sRGB at present time
     * when both bitmap and display advertise gamuts). For
     * [RawColorSpace.PROPHOTO_RGB] the bitmap stays sRGB-tagged for the
     * preview canvas (the ProPhoto matrix lives in [buildCameraToProPhotoMatrix]
     * for the export `.cube` path, not the FP16 preview).
     *
     * Must only be called on API 26+ (caller's responsibility).
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun build16BitBitmap(
        width: Int,
        height: Int,
        pixelsFloat16: ByteBuffer,
        cam2srgb: FloatArray,
        gamut: RawColorSpace,
    ): Bitmap {
        // Compose `cam → linear-sRGB → target-gamut` into a single 3×3 outside the
        // hot loop. The bitmap is then tagged with the matching linear ColorSpace
        // so Android color-manages from the linear-light samples to the display.
        val cam2target: FloatArray = when (gamut) {
            RawColorSpace.SRGB         -> cam2srgb
            RawColorSpace.DISPLAY_P3   -> mul3x3(SRGB_TO_DISPLAY_P3, cam2srgb)
            RawColorSpace.PROPHOTO_RGB -> mul3x3(SRGB_TO_PROPHOTO, cam2srgb)
        }
        val d0 = cam2target[0]; val d1 = cam2target[1]; val d2 = cam2target[2]
        val d3 = cam2target[3]; val d4 = cam2target[4]; val d5 = cam2target[5]
        val d6 = cam2target[6]; val d7 = cam2target[7]; val d8 = cam2target[8]
        val pixelCount = width * height

        // Each RGBA_F16 pixel = 8 bytes (R,G,B,A half-floats, little-endian).
        val out = ByteBuffer.allocateDirect(pixelCount * 8).order(ByteOrder.LITTLE_ENDIAN)

        // Bulk read input + bulk write output. Two native copies replace
        // pixelCount * 6 individual ByteBuffer.get / putShort calls — on a 24 MP
        // image that's >144 million NIO boundary crossings collapsed into 2.
        val shortCount = pixelCount * 4
        val srcShorts = ShortArray(shortCount)
        pixelsFloat16.duplicate()
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(srcShorts)
        val dstShorts = ShortArray(shortCount)
        val alphaOne = floatToHalfBits(1f)

        var sIdx = 0
        for (i in 0 until pixelCount) {
            val rf = float16ToFloat(srcShorts[sIdx])
            val gf = float16ToFloat(srcShorts[sIdx + 1])
            val bf = float16ToFloat(srcShorts[sIdx + 2])

            // Project camera-linear into the chosen workspace gamut. All targets
            // are linear-light; the gamma encode happens implicitly via the
            // bitmap's ColorSpace tag when Android renders to the display.
            val tr = (d0 * rf + d1 * gf + d2 * bf).coerceAtLeast(0f)
            val tg = (d3 * rf + d4 * gf + d5 * bf).coerceAtLeast(0f)
            val tb = (d6 * rf + d7 * gf + d8 * bf).coerceAtLeast(0f)

            dstShorts[sIdx]     = floatToHalfBits(tr)
            dstShorts[sIdx + 1] = floatToHalfBits(tg)
            dstShorts[sIdx + 2] = floatToHalfBits(tb)
            dstShorts[sIdx + 3] = alphaOne
            sIdx += 4
        }
        out.asShortBuffer().put(dstShorts)
        out.rewind()

        val colorSpace = when (gamut) {
            // LINEAR_EXTENDED_SRGB: sRGB primaries, linear transfer, extended range
            // (allows values < 0 and > 1 for HDR/wide-gamut roundtrips).
            RawColorSpace.SRGB         -> ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
            // DISPLAY_P3 named space uses sRGB piecewise transfer + P3 primaries.
            // Our samples are *linear* P3, so we'd ideally tag with a linear-transfer
            // P3 space; Android exposes that via BT2020_LINEAR's gamut but not P3's.
            // Build a custom linear P3 ColorSpace.Rgb on the fly using the named
            // DISPLAY_P3's primaries with the LINEAR transfer function.
            RawColorSpace.DISPLAY_P3   -> linearDisplayP3()
            // No named ProPhoto in Android; construct from ROMM primaries.
            RawColorSpace.PROPHOTO_RGB -> linearProPhoto()
        }

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16, true, colorSpace)
        bmp.copyPixelsFromBuffer(out)
        return bmp
    }

    /**
     * Build a linear-transfer Display-P3 [ColorSpace] for tagging RGBA_F16 bitmaps
     * whose samples are in linear P3 (not P3-with-sRGB-transfer). Android's named
     * `DISPLAY_P3` constant uses the piecewise sRGB transfer; our samples are
     * already linearly encoded, so we need a custom RGB ColorSpace with the same
     * primaries + white point but a linear transfer function.
     *
     * Display P3 primaries (xy chromaticities from the Display P3 / SMPTE RP 431-2
     * spec); D65 white point.
     */
    // @RequiresApi guard lives on the only callers (build16BitBitmap and the
    // wrapper fun below) — Kotlin won't accept the annotation on a delegated property.
    private val linearP3Cached: ColorSpace by lazy {
        val primaries = floatArrayOf(
            0.680f, 0.320f,   // Red
            0.265f, 0.690f,   // Green
            0.150f, 0.060f,   // Blue
        )
        val whitePointD65 = floatArrayOf(0.3127f, 0.3290f)
        // CRITICAL: the 4-arg `ColorSpace.Rgb(name, primaries, whitePoint, gamma)`
        // constructor produces a ColorSpace whose component range is [0, 1].
        // RGBA_F16 bitmaps require an *extended-range* ColorSpace (min < 0 or
        // max > 1) — Android **silently rejects** a [0,1] custom space when
        // attaching it to an FP16 bitmap and falls back to a sentinel
        // "Unknown" ColorSpace (verified adb 2026-05-25: `requested
        // cs=Linear Display P3 got cs=Unknown (eq=false)`).
        //
        // Fix: use the 7-arg overload that accepts explicit oetf/eotf functions
        // plus a wide [min, max] range matching what Android uses for the
        // built-in scRGB and BT2020_LINEAR ColorSpaces. The OETF/EOTF are
        // identity since our samples are already linear-light.
        ColorSpace.Rgb(
            "Linear Display P3",
            primaries,
            whitePointD65,
            { x -> x },                       // OETF: linear in → linear out
            { x -> x },                       // EOTF: linear in → linear out
            -0.5f,                            // component min (matches scRGB)
            7.5f,                             // component max (matches scRGB)
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun linearDisplayP3(): ColorSpace = linearP3Cached

    /**
     * Public-to-the-package accessor for the linear-transfer Display-P3 color space.
     * Used by [RawPipelineCoordinator] when it reloads a persisted FP16 bitmap from
     * the on-disk `workspace_full_base.fp16` cache so the tag matches what
     * [build16BitBitmap] originally wrote.
     *
     * Reading the FP16 bytes back with the gamma-encoded `ColorSpace.Named.DISPLAY_P3`
     * tag would force Android to inverse-sRGB-decode our already-linear values,
     * crushing midtones and shadows toward black — exactly the symptom that the
     * idle-full-res canvas swap exhibited prior to this fix.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    internal fun linearDisplayP3Public(): ColorSpace = linearP3Cached

    /**
     * Build a linear-transfer ProPhoto / ROMM-RGB [ColorSpace] for tagging
     * RGBA_F16 bitmaps. Android has no `Named.PROPHOTO_RGB`; this constructs the
     * gamut from ROMM primaries + D50 white point + linear transfer.
     */
    // @RequiresApi guard lives on the only callers — Kotlin rejects the
    // annotation on a delegated property.
    private val linearProPhotoCached: ColorSpace by lazy {
        // ROMM RGB / ProPhoto primaries (xy chromaticities from the ROMM RGB spec).
        val primaries = floatArrayOf(
            0.7347f, 0.2653f,   // Red
            0.1596f, 0.8404f,   // Green
            0.0366f, 0.0001f,   // Blue
        )
        // D50 white point (x, y).
        val whitePoint = floatArrayOf(0.3457f, 0.3585f)
        // Extended-range constructor — see linearP3Cached for the rationale.
        ColorSpace.Rgb(
            "Linear ProPhoto RGB",
            primaries,
            whitePoint,
            { x -> x },                       // OETF: identity
            { x -> x },                       // EOTF: identity
            -0.5f,                            // component min
            7.5f,                             // component max
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun linearProPhoto(): ColorSpace = linearProPhotoCached

    /** Package-internal accessor for the linear ProPhoto color space — see
     *  [linearDisplayP3Public] for rationale. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    internal fun linearProPhotoPublic(): ColorSpace = linearProPhotoCached

    /**
     * IEEE 754 binary32 → binary16 conversion, returning the raw 16-bit pattern
     * as a Short. Implements round-to-nearest-even with proper subnormal and
     * overflow handling. We avoid `android.util.Half` to keep this callable
     * outside the API-26 branch in tests.
     */
    private fun floatToHalfBits(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        val mant = bits and 0x7FFFFF

        if (exp == 0xFF) {
            // Inf or NaN — preserve NaN payload best-effort.
            return (sign or 0x7C00 or if (mant != 0) 0x200 else 0).toShort()
        }
        val newExp = exp - 127 + 15
        if (newExp >= 0x1F) {
            // Overflow → Inf
            return (sign or 0x7C00).toShort()
        }
        if (newExp <= 0) {
            // Subnormal or underflow
            if (newExp < -10) return sign.toShort()
            val m = (mant or 0x800000) shr (1 - newExp + 13)
            return (sign or m).toShort()
        }
        // Round-to-nearest-even mantissa
        val rounded = mant + 0x1000
        return if (rounded and 0x800000 != 0) {
            // Mantissa overflow on round → bump exponent
            if (newExp + 1 >= 0x1F) (sign or 0x7C00).toShort()
            else (sign or ((newExp + 1) shl 10)).toShort()
        } else {
            (sign or (newExp shl 10) or (rounded shr 13)).toShort()
        }
    }

    // ── .cube file generation ─────────────────────────────────────────────────

    private fun buildCubeText(title: String, size: Int, values: FloatArray): String {
        val sb = StringBuilder()
        sb.append("TITLE \"$title\"\n")
        sb.append("LUT_3D_SIZE $size\n")
        sb.append("DOMAIN_MIN 0.0 0.0 0.0\n")
        sb.append("DOMAIN_MAX 1.0 1.0 1.0\n\n")
        var i = 0
        repeat(size * size * size) {
            sb.append("%.6f %.6f %.6f\n".format(values[i], values[i + 1], values[i + 2]))
            i += 3
        }
        return sb.toString()
    }
}
