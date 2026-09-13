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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut

import ai.onnxruntime.OrtSession
import android.os.Build

/**
 * Builds the best available [OrtSession.SessionOptions] for the current device.
 *
 * Acceleration priority:
 *
 * **Qualcomm (Snapdragon) devices:**
 * 1. QNN HTP  — Hexagon NPU, 5–15× faster than CPU
 * 2. QNN GPU  — Adreno GPU, 3–8× faster
 * 3. CPU      — multi-threaded fallback
 *
 * **All other devices (MediaTek, Exynos, etc.):**
 * 1. CPU      — multi-threaded (NNAPI deliberately omitted — hangs on MediaTek)
 *
 * Every provider addition is wrapped in runCatching — a missing or
 * incompatible provider never crashes the session.
 */
internal fun buildAiSessionOptions(): OrtSession.SessionOptions {
    val cores = Runtime.getRuntime().availableProcessors()
    val isQualcomm = isQualcommDevice()

    return OrtSession.SessionOptions().apply {
        runCatching { setIntraOpNumThreads(cores.coerceAtLeast(2)) }
        runCatching { setInterOpNumThreads(2) }
        runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }

        if (isQualcomm) {
            // ── Qualcomm path: try QNN HTP → QNN GPU → CPU ───────────────────
            val htpAdded = runCatching {
                addQnn(
                    mapOf(
                        "backend_path"              to "libQnnHtp.so",
                        "htp_performance_mode"      to "burst",
                        "enable_htp_fp16_precision" to "1",
                    )
                )
            }.isSuccess

            if (!htpAdded) {
                runCatching {
                    addQnn(mapOf("backend_path" to "libQnnGpu.so"))
                }
            }
        }
        // Non-Qualcomm: CPU-only with multi-threading.
        // NNAPI is intentionally omitted — it hangs indefinitely on MediaTek NPUs
        // for certain transformer ops used in U2Net and Zero-DCE.
    }
}

/**
 * Returns true if the device uses a Qualcomm SoC.
 * Checks ro.hardware and ro.soc.manufacturer at runtime.
 */
private fun isQualcommDevice(): Boolean = try {
    val hardware = Build.HARDWARE.lowercase()
    val manufacturer = runCatching {
        @Suppress("UNCHECKED_CAST")
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, "ro.soc.manufacturer", "") as String
    }.getOrDefault("")
    hardware.contains("qcom") || hardware.contains("qualcomm") ||
        manufacturer.lowercase().let { it.contains("qualcomm") || it.contains("qti") }
} catch (_: Throwable) {
    false
}
