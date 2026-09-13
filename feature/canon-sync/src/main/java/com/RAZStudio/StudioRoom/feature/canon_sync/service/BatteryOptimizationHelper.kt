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

package com.RAZStudio.StudioRoom.feature.canon_sync.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService

/**
 * Helper for the optional "ignore battery optimizations" exemption (claude
 * advanced plan.md §3.7 Layer 3).
 *
 * Important policy note: Google Play restricts apps that request the direct
 * [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] dialog. Apps need to
 * declare a "core function" that breaks without it. A camera-tether session
 * arguably qualifies but expect manual review.
 *
 * We default to the gentler [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS]
 * which opens the list and lets the user toggle. The direct dialog is exposed
 * as [requestExemptionDirect] in case we ever justify it on Play. Both are
 * non-mandatory — the rest of the layered defenses (FGS + WifiLock + OEM
 * guide) work without this exemption.
 */
internal object BatteryOptimizationHelper {

    /** True if RAZStudio Room is currently exempt from battery optimization. */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService<PowerManager>() ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Open the system "Battery optimization" list so the user can toggle the
     * exemption manually. Recommended over the direct request — no Play
     * policy implications.
     */
    fun openBatteryOptimizationSettings(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Direct "Please exempt me" dialog. Requires
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission and Play-policy
     * justification. Caller must guard with [isExempt] first — calling this
     * when already exempt opens an empty dialog.
     */
    fun requestExemptionDirect(packageName: String): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
