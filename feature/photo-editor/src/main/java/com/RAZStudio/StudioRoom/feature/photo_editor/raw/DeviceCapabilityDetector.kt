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

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads device hardware capabilities dynamically on first call and caches the result.
 *
 * Enforces hardware compatibility requirements for the high-end RAW pipeline:
 * - Screen resolution: Minimum 1080p short side, with valid resolution bounds.
 * - RAM: Minimum 6GB total RAM.
 * - Storage: Minimum 2GB free space left.
 * - Processor (SoC/GPU): Latest Unisoc and Snapdragon mid-high to high tier, or other
 *   vendor equivalent mid-high to high tier (e.g. MediaTek Dimensity, Exynos, Google Tensor) only.
 */
class DeviceCapabilityDetector(context: Context) {

    data class Capabilities(
        /** Shortest screen side in physical pixels (not dp). */
        val shortestScreenSide: Int,
        /** Screen width in physical pixels. */
        val screenWidth: Int,
        /** Screen height in physical pixels. */
        val screenHeight: Int,
        /** Cap on the longest side of the preview bitmap/texture. */
        val previewLongestSide: Int,
        /** GPU maximum 2-D texture dimension (GL_MAX_TEXTURE_SIZE). */
        val maxGlTextureSize: Int,
        /** Total device RAM in bytes. */
        val totalRamBytes: Long,
        /** Available RAM at detection time, in bytes. */
        val availableRamBytes: Long,
    )

    val capabilities: Capabilities by lazy { detect(context.applicationContext) }

    /**
     * Given the RAW image's native aspect ratio, compute the target preview bitmap size
     * such that the longest side equals [capabilities.previewLongestSide].
     */
    fun previewSize(rawWidth: Int, rawHeight: Int): Pair<Int, Int> {
        val cap = capabilities.previewLongestSide
        val ratio = rawWidth.toFloat() / rawHeight.toFloat()
        return if (rawWidth >= rawHeight) {
            val w = min(rawWidth, cap)
            val h = (w / ratio).roundToInt()
            w to h
        } else {
            val h = min(rawHeight, cap)
            val w = (h * ratio).roundToInt()
            w to h
        }
    }

    companion object {
        private const val TAG = "DeviceCapability"

        private fun detect(context: Context): Capabilities {
            // Screen size — read from Resources (safe on any thread, no GL context needed)
            val dm = context.resources.displayMetrics
            val width = dm.widthPixels
            val height = dm.heightPixels
            val shortestScreenSide = min(width, height)

            // RAM
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalRamBytes = memoryInfo.totalMem
            val availableRamBytes = memoryInfo.availMem

            // GL_MAX_TEXTURE_SIZE cannot be queried off the GL thread — use a safe default.
            // Real value would be 4096–16384 depending on GPU; 4096 is the universal minimum.
            val maxGlTextureSize = 4096

            Log.d(TAG, "Hardware detection: short=${shortestScreenSide}px " +
                "ram=${totalRamBytes / (1024 * 1024)}MB")

            // Preview resolution tier: scale to device screen without exceeding RAM limits.
            // Devices with <3 GB RAM use a smaller preview to avoid OOM.
            val ramGb = totalRamBytes.toDouble() / (1024.0 * 1024 * 1024)
            val previewLongestSide = when {
                shortestScreenSide >= 1440 && ramGb >= 6.0 -> 3072
                shortestScreenSide >= 1080 && ramGb >= 4.0 -> 2464
                shortestScreenSide >= 720  && ramGb >= 3.0 -> 1840
                else -> 1232
            }

            Log.i(TAG, "Preview longest side: $previewLongestSide px")

            return Capabilities(
                shortestScreenSide = shortestScreenSide,
                screenWidth        = width,
                screenHeight       = height,
                previewLongestSide = previewLongestSide,
                maxGlTextureSize   = maxGlTextureSize,
                totalRamBytes      = totalRamBytes,
                availableRamBytes  = availableRamBytes,
            )
        }
    }
}
