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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.capability

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService

/**
 * Vulkan version components captured at app start. Comparable so the GPU-pipeline gate
 * can be expressed as `vulkanVersion >= VulkanVersion(1, 1, 0)`.
 */
data class VulkanVersion(val major: Int, val minor: Int, val patch: Int) :
    Comparable<VulkanVersion> {
    override fun compareTo(other: VulkanVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

/**
 * v2-integration §6.4 / design.md §Vulkan detection — device-side capability snapshot used
 * to decide whether Preview and Idle pipelines can use the GPU compute path.
 *
 * Phase A ships with [vulkanVersion] hard-coded to null (the real JNI probe lands in v2
 * Phase D after the GPU compute shader chain exists). Heap fields are real so the Settings
 * diagnostics card (v2-integration §5.1) has something to show today.
 *
 * The shape is also future-proof: when Phase D arrives, only [initialize] needs swapping.
 */
data class DeviceCapabilities(
    val vulkanVersion: VulkanVersion?,
    val hasComputeQueue: Boolean,
    val hasFloat16Support: Boolean,
    val maxComputeWorkgroupSize: IntArray,
    val nativeHeapMaxMb: Int,
    val javaHeapMaxMb: Int,
) {
    /**
     * True only when every requirement of [com.RAZStudio.StudioRoom.feature.photo_editor.raw.pipeline]
     * Phase D is met: Vulkan ≥ 1.1, a compute queue family, and shader-fp16 for the macro
     * processor's working precision.
     *
     * Phase A returns `false` unconditionally — GPU pipeline isn't wired yet. The flag is
     * read by the coordinator to select CPU vs GPU MacroProcessor (v2 §7.7).
     */
    val canUseGpuPipeline: Boolean
        get() = vulkanVersion != null &&
            vulkanVersion >= VulkanVersion(1, 1, 0) &&
            hasComputeQueue &&
            hasFloat16Support

    // data class equals/hashCode auto-generated; IntArray needs manual handling.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceCapabilities) return false
        return vulkanVersion == other.vulkanVersion &&
            hasComputeQueue == other.hasComputeQueue &&
            hasFloat16Support == other.hasFloat16Support &&
            maxComputeWorkgroupSize.contentEquals(other.maxComputeWorkgroupSize) &&
            nativeHeapMaxMb == other.nativeHeapMaxMb &&
            javaHeapMaxMb == other.javaHeapMaxMb
    }

    override fun hashCode(): Int {
        var r = vulkanVersion?.hashCode() ?: 0
        r = 31 * r + hasComputeQueue.hashCode()
        r = 31 * r + hasFloat16Support.hashCode()
        r = 31 * r + maxComputeWorkgroupSize.contentHashCode()
        r = 31 * r + nativeHeapMaxMb
        r = 31 * r + javaHeapMaxMb
        return r
    }

    companion object {
        @Volatile
        private var snapshot: DeviceCapabilities? = null

        /**
         * Capture once at app start. Subsequent calls are no-ops; the result is process-stable.
         * The Vulkan probe is intentionally stubbed in Phase A (`null` version).
         */
        fun initialize(context: Context): DeviceCapabilities {
            snapshot?.let { return it }
            synchronized(Companion) {
                snapshot?.let { return it }
                val javaHeapMaxMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt()
                val activityManager = context.getSystemService<ActivityManager>()
                val memoryInfo = activityManager?.let {
                    ActivityManager.MemoryInfo().also(it::getMemoryInfo)
                }
                val nativeHeapMaxMb = memoryInfo?.let {
                    ((it.totalMem - it.threshold).coerceAtLeast(0L) / (1024L * 1024L)).toInt()
                } ?: 0
                val result = DeviceCapabilities(
                    vulkanVersion = null,
                    hasComputeQueue = false,
                    hasFloat16Support = false,
                    maxComputeWorkgroupSize = IntArray(3),
                    nativeHeapMaxMb = nativeHeapMaxMb,
                    javaHeapMaxMb = javaHeapMaxMb,
                )
                snapshot = result
                return result
            }
        }

        /** Read the cached snapshot or capture it on demand. */
        fun get(context: Context): DeviceCapabilities = snapshot ?: initialize(context)
    }
}
