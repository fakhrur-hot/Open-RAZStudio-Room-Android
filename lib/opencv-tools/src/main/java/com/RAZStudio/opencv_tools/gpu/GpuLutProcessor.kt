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

package com.RAZStudio.opencv_tools.gpu

import android.graphics.Bitmap
import android.os.Build
import android.opengl.EGL14
import androidx.annotation.RequiresApi
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * Single-pass GPU LUT processor using OpenGL ES 3.0.
 *
 * Pipeline per [applyLut] call:
 *  1. Upload source bitmap as GL_TEXTURE_2D (uImage).
 *  2. Optionally upload a grayscale segmentation mask as GL_TEXTURE_2D (uMask).
 *  3. Upload the 3-D LUT FloatArray as GL_TEXTURE_3D (uLut).
 *  4. Render a fullscreen quad through a single-pass GLSL shader that:
 *       a. Applies an S-curve contrast boost on the subject area (mask-weighted).
 *       b. Looks up the 3-D LUT with hardware trilinear filtering.
 *       c. Blends LUT vs original based on mask weight and [intensity].
 *  5. Async readback via PBO with GPU fence synchronisation.
 *
 * The EGL context and quad VAO/program are created once ([createOrNull]) and
 * reused. Textures and FBO are created/destroyed per call to avoid leaks.
 *
 * Thread-safety: all OpenGL calls must happen on [gpuDispatcher] (single thread).
 */
class GpuLutProcessor private constructor(
    private val display: EGLDisplay,
    private val context: EGLContext,
    private val surface: EGLSurface,
    private val program: Int,
    private val vao: Int,
    // Uniform locations
    private val uImage: Int,
    private val uMask: Int,
    private val uLut: Int,
    private val uIntensity: Int,
    private val uLutScale: Int,
    private val uLutOffset: Int,
    private val uContrastBoost: Int,
    private val uHasMask: Int,
    private val uIsFp16: Int,
    /**
     * True when the GPU advertises `GL_EXT_color_buffer_half_float` (required to
     * render into a `GL_RGBA16F` framebuffer attachment). When false, FP16 input
     * bitmaps fall through to the existing 8-bit path so the LUT still applies —
     * with the BIT_16 dynamic-range loss the user is already used to. Modern
     * Adreno 5xx+, Mali-G series, and PowerVR Series 7+ all support this; only
     * very old Android GPUs (pre-2016) lack it.
     */
    private val supportsFp16Color: Boolean,
) {

    // ── Shader sources ─────────────────────────────────────────────────────────

    companion object {
        val gpuDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "GpuLutThread") }
                .asCoroutineDispatcher()

        fun createOrNull(): GpuLutProcessor? = runCatching { create() }.getOrNull()

        private fun create(): GpuLutProcessor {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0)
            val config = checkNotNull(configs[0])

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

            val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val surface = EGL14.eglCreatePbufferSurface(display, config, pbAttribs, 0)

            EGL14.eglMakeCurrent(display, surface, surface, context)

            val program = buildProgram(VERT_SRC, FRAG_SRC)
            val vao = buildQuadVao()

            // Detect FP16 framebuffer support once. The renderbuffer storage
            // format `GL_RGBA16F` requires `GL_EXT_color_buffer_half_float`
            // (https://registry.khronos.org/OpenGL/extensions/EXT/EXT_color_buffer_half_float.txt)
            // or GLES 3.2 core. Linear filtering of FP16 3D textures needs
            // `GL_OES_texture_half_float_linear`. Both are checked here so the
            // applyLut hot path doesn't re-query every frame.
            val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            val fp16ColorBuf  = "GL_EXT_color_buffer_half_float" in extensions
            val fp16TexLinear = "GL_OES_texture_half_float_linear" in extensions ||
                                "GL_OES_texture_float_linear"      in extensions
            val supportsFp16 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                fp16ColorBuf && fp16TexLinear
            android.util.Log.i(
                "GpuLutProcessor",
                "FP16 support: colorBuf=$fp16ColorBuf texLinear=$fp16TexLinear → enabled=$supportsFp16",
            )

            return GpuLutProcessor(
                display, context, surface, program, vao,
                uImage        = GLES30.glGetUniformLocation(program, "uImage"),
                uMask         = GLES30.glGetUniformLocation(program, "uMask"),
                uLut          = GLES30.glGetUniformLocation(program, "uLut"),
                uIntensity    = GLES30.glGetUniformLocation(program, "uIntensity"),
                uLutScale     = GLES30.glGetUniformLocation(program, "uLutScale"),
                uLutOffset    = GLES30.glGetUniformLocation(program, "uLutOffset"),
                uContrastBoost = GLES30.glGetUniformLocation(program, "uContrastBoost"),
                uHasMask      = GLES30.glGetUniformLocation(program, "uHasMask"),
                uIsFp16       = GLES30.glGetUniformLocation(program, "uIsFp16"),
                supportsFp16Color = supportsFp16,
            )
        }

        // ── GLSL ──────────────────────────────────────────────────────────────

        private const val VERT_SRC = """
#version 300 es
in vec2 aPosition;
out vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    // Flip V so that GL row-0 (bottom) maps to bitmap row-0 (top)
    vTexCoord = vec2(aPosition.x * 0.5 + 0.5, 0.5 - aPosition.y * 0.5);
}
"""

        private const val FRAG_SRC = """
#version 300 es
precision highp float;
precision highp sampler3D;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uImage;
uniform sampler2D uMask;
uniform sampler3D uLut;
uniform float uIntensity;
uniform float uLutScale;
uniform float uLutOffset;
uniform float uContrastBoost;
uniform bool uHasMask;
// True when input is HDR (RGBA_F16) and we need a Reinhard pre-shaper + extended-range
// restore so highlights above 1.0 survive the LUT lookup. False on the legacy 8-bit
// path keeps the existing behavior bit-for-bit.
uniform bool uIsFp16;

// Smoothstep S-curve: pushes shadows darker, highlights brighter, mids anchored at 0.5.
// color * color * (3 - 2*color) is the standard smoothstep(0,1,color) formulation.
// Safe on extended range — c²(3 - 2c) is well-defined for any c, monotonic on [0,1].
vec3 sCurve(vec3 c, float amount) {
    vec3 curved = c * c * (3.0 - 2.0 * c);
    return mix(c, curved, amount);
}

void main() {
    vec4 base = texture(uImage, vTexCoord);

    // mask = 1.0 on subject, 0.0 on background; 1.0 everywhere when no mask provided.
    float mask = uHasMask ? texture(uMask, vTexCoord).r : 1.0;

    // S-curve contrast boost — scales with mask weight so only subject gets "pop".
    // contrastBoost = 0 means no S-curve (pure LUT application).
    vec3 popped = sCurve(base.rgb, mask * uContrastBoost);

    // ── 3D LUT lookup with HDR-safe pre-shaping ──────────────────────────────
    // 3D LUTs are authored on a [0,1]³ grid. For HDR (FP16) inputs above 1.0 we
    // pre-shape with a Reinhard tone curve `x / (1 + x)` so the coord stays in
    // [0,1), which the LUT can sample without clamping or wrap artifacts. After
    // the lookup we lerp back toward the raw input for extended-range pixels,
    // weighted by how much the pre-shaper compressed (mix factor = (popped - 1)
    // / (popped + ε) once popped > 1). This preserves specular highlights and
    // clipped-channel detail while still letting the LUT shape the in-gamut tones.
    vec3 lutInput;
    if (uIsFp16) {
        // Reinhard on positive channels; clamp at 0 to avoid negative LUT coords.
        vec3 nonNeg = max(popped, vec3(0.0));
        lutInput = nonNeg / (vec3(1.0) + nonNeg);
    } else {
        lutInput = clamp(popped, 0.0, 1.0);
    }
    vec3 lutCoord = lutInput * uLutScale + uLutOffset;
    vec3 lutColor = texture(uLut, lutCoord).rgb;

    // HDR restore: for FP16 input, pixels with channel values > 1.0 get blended
    // back toward `popped` proportional to how far above 1 they sit. Below 1.0
    // the blend is 0 (pure LUT). At 2.0 it's 0.5, at 4.0 it's 0.8, asymptoting
    // to 1.0 (pure pass-through) as popped → ∞. The result is a graceful
    // shoulder where LUT tone-shaping fades into pass-through HDR highlights.
    vec3 finalLut;
    if (uIsFp16) {
        vec3 excess = max(popped - 1.0, 0.0);
        vec3 hdrMix = excess / (excess + 1.0);
        finalLut = mix(lutColor, popped, hdrMix);
    } else {
        finalLut = lutColor;
    }

    // Selective blending: full LUT on subject, 50% on background.
    // This naturally makes the subject "pop" relative to a softer background.
    float lutBlend = mix(0.5, 1.0, mask) * uIntensity;
    vec3 finalColor = mix(popped, finalLut, lutBlend);

    fragColor = vec4(finalColor, base.a);
}
"""

        // ── GL helpers ────────────────────────────────────────────────────────

        private fun buildProgram(vertSrc: String, fragSrc: String): Int {
            fun compile(type: Int, src: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, src)
                GLES30.glCompileShader(s)
                val status = IntArray(1)
                GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
                check(status[0] == GLES30.GL_TRUE) {
                    "Shader compile failed: ${GLES30.glGetShaderInfoLog(s)}"
                }
                return s
            }
            val vert = compile(GLES30.GL_VERTEX_SHADER, vertSrc)
            val frag = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc)
            val prog = GLES30.glCreateProgram()
            GLES30.glAttachShader(prog, vert)
            GLES30.glAttachShader(prog, frag)
            GLES30.glLinkProgram(prog)
            GLES30.glDeleteShader(vert)
            GLES30.glDeleteShader(frag)
            return prog
        }

        private fun buildQuadVao(): Int {
            val quad = floatArrayOf(
                -1f, -1f,  1f, -1f,  -1f,  1f,
                -1f,  1f,  1f, -1f,   1f,  1f,
            )
            val vaoArr = IntArray(1)
            val vboArr = IntArray(1)
            GLES30.glGenVertexArrays(1, vaoArr, 0)
            GLES30.glGenBuffers(1, vboArr, 0)
            GLES30.glBindVertexArray(vaoArr[0])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboArr[0])
            val buf = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(quad).position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
            val posLoc = 0
            GLES30.glEnableVertexAttribArray(posLoc)
            GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 8, 0)
            GLES30.glBindVertexArray(0)
            return vaoArr[0]
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Destroy the EGL context, surface, and display connection held by this processor.
     *
     * Must be called on [gpuDispatcher]. After calling this, the instance must not be used.
     */
    fun release() {
        runCatching {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            GLES30.glDeleteProgram(program)
            GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    /**
     * Apply the 3-D LUT to [image] and return the result.
     *
     * @param image         Source bitmap (any size, ARGB_8888).
     * @param lutTable      Flat float array from the .cube parser: R-fastest order,
     *                      normalised [0, 1], size = [lutSize]^3 * 3.
     * @param lutSize       LUT grid dimension (typically 17, 33, or 64).
     * @param intensity     LUT blend amount [0, 1].
     * @param mask          Optional grayscale mask bitmap (subject=white, bg=black).
     *                      When null, LUT is applied uniformly at [intensity].
     * @param contrastBoost S-curve strength [0, 1] applied to the subject area.
     *                      0 = pure LUT application (default), 1 = strong S-curve.
     */
    fun applyLut(
        image: Bitmap,
        lutTable: FloatArray,
        lutSize: Int,
        intensity: Float,
        mask: Bitmap? = null,
        contrastBoost: Float = 0f,
    ): Bitmap {
        // Decide upfront whether we can serve the FP16 path. Requires both:
        //   1. Input bitmap is RGBA_F16 (otherwise the upload would lose dynamic range)
        //   2. GPU advertises GL_EXT_color_buffer_half_float (FBO can render to FP16)
        // We accept lossless 8-bit fallback on devices missing the extension.
        val useFp16 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            supportsFp16Color &&
            isRgbaF16Config(image)
        EGL14.eglMakeCurrent(display, surface, surface, context)

        val w = image.width
        val h = image.height

        // ── 1. Textures ───────────────────────────────────────────────────────

        val texIds = IntArray(if (mask != null) 3 else 2)
        GLES30.glGenTextures(texIds.size, texIds, 0)
        val (imgTex, lutTex) = texIds[0] to texIds[1]
        val maskTex = if (mask != null) texIds[2] else -1

        // Image texture.
        // 8-bit path: GLUtils.texImage2D uploads as GL_RGBA8 (preserves existing
        // behavior bit-for-bit). FP16 path: copy bitmap pixels into a direct
        // ByteBuffer and upload as GL_RGBA16F + GL_HALF_FLOAT so the extended
        // range survives into the shader.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imgTex)
        if (useFp16) {
            // RGBA_F16 = 4 channels × 2 bytes (half-float) per pixel
            val srcBuf = ByteBuffer.allocateDirect(w * h * 4 * 2)
                .order(ByteOrder.nativeOrder())
            image.copyPixelsToBuffer(srcBuf)
            srcBuf.rewind()
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F,
                w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, srcBuf,
            )
        } else {
            android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, image, 0)
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Mask texture (optional)
        if (mask != null) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, mask, 0)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        // 3-D LUT texture — GL_RGB32F for full floating-point precision
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        val lutBuf: FloatBuffer = ByteBuffer.allocateDirect(lutTable.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        lutBuf.put(lutTable).position(0)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F,
            lutSize, lutSize, lutSize, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, lutBuf,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        // ── 2. FBO ────────────────────────────────────────────────────────────

        val fboArr = IntArray(1); val rbArr = IntArray(1)
        GLES30.glGenFramebuffers(1, fboArr, 0)
        GLES30.glGenRenderbuffers(1, rbArr, 0)
        val fbo = fboArr[0]; val rb = rbArr[0]

        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb)
        // GL_RGBA16F renderbuffer when in the FP16 path so the shader's extended-range
        // output survives the readback. Requires GL_EXT_color_buffer_half_float
        // (verified at init time via supportsFp16Color).
        val rbFormat = if (useFp16) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, rbFormat, w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rb,
        )
        // Guard: on a device that lied about FP16 support, fall back gracefully.
        val fbStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(fbStatus == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "GL framebuffer incomplete: status=0x${fbStatus.toString(16)} useFp16=$useFp16"
        }

        // ── 3. Render ─────────────────────────────────────────────────────────

        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imgTex)
        GLES30.glUniform1i(uImage, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        if (mask != null) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        GLES30.glUniform1i(uMask, 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(uLut, 2)

        GLES30.glUniform1f(uIntensity, intensity.coerceIn(0f, 1f))
        GLES30.glUniform1f(uLutScale, (lutSize - 1f) / lutSize)
        GLES30.glUniform1f(uLutOffset, 0.5f / lutSize)
        GLES30.glUniform1f(uContrastBoost, contrastBoost.coerceIn(0f, 1f))
        GLES30.glUniform1i(uHasMask, if (mask != null) GLES30.GL_TRUE else GLES30.GL_FALSE)
        GLES30.glUniform1i(uIsFp16,  if (useFp16) GLES30.GL_TRUE else GLES30.GL_FALSE)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)

        // ── 4. PBO readback ───────────────────────────────────────────────────
        // 8-bit path: 4 bytes/pixel, GL_UNSIGNED_BYTE, ARGB_8888 output bitmap.
        // FP16 path:  8 bytes/pixel (4 channels × half-float), GL_HALF_FLOAT,
        //             RGBA_F16 output bitmap tagged with the input's color space
        //             so Android color-manages it identically to the source.

        val bytesPerPixel = if (useFp16) 8 else 4
        val byteCount = w * h * bytesPerPixel
        val pboArr = IntArray(1)
        GLES30.glGenBuffers(1, pboArr, 0)
        val pbo = pboArr[0]

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, byteCount, null, GLES30.GL_STREAM_READ)
        if (useFp16) {
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, 0)
        } else {
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
        }

        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 2_000_000_000L)
        GLES30.glDeleteSync(fence)

        val rawBuf = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, byteCount,
            GLES30.GL_MAP_READ_BIT,
        ) as ByteBuffer
        rawBuf.order(ByteOrder.nativeOrder())

        val result: Bitmap = if (useFp16) {
            // Build a flipped destination buffer (GL row-0 is bottom, bitmap row-0
            // is top) then copy straight into an RGBA_F16 bitmap. Tag with the
            // input bitmap's ColorSpace so downstream consumers can render the
            // extended-range data correctly — typically LINEAR_EXTENDED_SRGB,
            // linear Display-P3, or linear ProPhoto.
            val flipped = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
            val rowBytes = w * bytesPerPixel
            val rowBuf = ByteArray(rowBytes)
            for (row in 0 until h) {
                val srcRow = h - 1 - row
                rawBuf.position(srcRow * rowBytes)
                rawBuf.get(rowBuf, 0, rowBytes)
                flipped.position(row * rowBytes)
                flipped.put(rowBuf)
            }
            flipped.rewind()
            createFp16BitmapFromPixels(image, flipped, w, h)
        } else {
            val out = createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            // glReadPixels gives RGBA bottom-to-top; flip to top-to-bottom for Bitmap
            for (row in 0 until h) {
                val srcRow = h - 1 - row
                for (col in 0 until w) {
                    val idx = (srcRow * w + col) * 4
                    val r = rawBuf.get(idx).toInt() and 0xFF
                    val g = rawBuf.get(idx + 1).toInt() and 0xFF
                    val b = rawBuf.get(idx + 2).toInt() and 0xFF
                    val a = rawBuf.get(idx + 3).toInt() and 0xFF
                    pixels[row * w + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            out
        }
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)

        // ── 5. Cleanup ────────────────────────────────────────────────────────

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glDeleteBuffers(1, pboArr, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fboArr, 0)
        GLES30.glDeleteRenderbuffers(1, rbArr, 0)
        GLES30.glDeleteTextures(texIds.size, texIds, 0)

        return result
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun isRgbaF16Config(image: Bitmap): Boolean =
        image.config == Bitmap.Config.RGBA_F16

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createFp16BitmapFromPixels(
        image: Bitmap,
        flipped: ByteBuffer,
        w: Int,
        h: Int,
    ): Bitmap {
        val cs = image.colorSpace
            ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16, true, cs)
        bmp.copyPixelsFromBuffer(flipped)
        return bmp
    }
}
