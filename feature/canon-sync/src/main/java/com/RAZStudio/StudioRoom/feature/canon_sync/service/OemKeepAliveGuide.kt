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

import android.content.ComponentName
import android.content.Intent
import android.os.Build

/**
 * Per-vendor "allow Canon Sync to keep running in the background" guidance.
 *
 * Many OEM skins (Xiaomi MIUI, Huawei/Honor EMUI, Oppo/Vivo/Realme ColorOS,
 * Samsung OneUI's DeviceCare) run out-of-spec process reapers that ignore the
 * documented foreground-service contract. We layer the in-app defenses
 * ([CanonSyncCaptureService] + WifiLock + battery-opt exemption) but they're
 * not enough on these skins. The plan (§3.7 Layer 6) calls for an explicit
 * in-UI guide that links the user to the vendor's "autostart" / "no
 * restrictions" / "never sleep" setting.
 *
 * We can't guarantee the deep-link intents below resolve on every firmware
 * revision — vendors rename activities between OS updates with no notice. The
 * UI must always offer the human-readable instructions as a fallback even
 * when [intentFor] returns a non-resolving intent.
 */
internal object OemKeepAliveGuide {

    /**
     * Per-OEM guidance shown in the Connection Settings dialog. The [intent]
     * may not resolve on every firmware — caller should guard with
     * [Intent.resolveActivity] and fall back to plain instructions when null.
     */
    data class Guidance(
        val vendorName: String,
        val instructions: String,
        val intent: Intent? = null,
    )

    /** Detect the running device's vendor. */
    fun current(): Vendor = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> Vendor.Xiaomi
        "huawei", "honor" -> Vendor.Huawei
        "oppo" -> Vendor.Oppo
        "vivo" -> Vendor.Vivo
        "realme" -> Vendor.Realme
        "samsung" -> Vendor.Samsung
        "oneplus" -> Vendor.OnePlus
        else -> Vendor.Other
    }

    fun guidanceFor(vendor: Vendor = current()): Guidance = when (vendor) {
        Vendor.Xiaomi -> Guidance(
            vendorName = "MIUI",
            instructions = "Open Security → Permissions → Autostart and enable RAZStudio Room. " +
                "Also: long-press the app in Recents and lock it.",
            intent = miuiAutostartIntent(),
        )
        Vendor.Huawei -> Guidance(
            vendorName = "EMUI / Magic OS",
            instructions = "Open Settings → Apps → RAZStudio Room → Battery → Launch and " +
                "enable Manual launch (Auto-launch / Secondary launch / Run in background).",
            intent = huaweiProtectedAppsIntent(),
        )
        Vendor.Oppo, Vendor.Realme -> Guidance(
            vendorName = "ColorOS / Realme UI",
            instructions = "Open Settings → Battery → Power Saving Options → Background Power " +
                "Saving, and add RAZStudio Room to the allow list. Also enable Allow Auto-launch.",
            intent = oppoAutostartIntent(),
        )
        Vendor.Vivo -> Guidance(
            vendorName = "Funtouch OS / OriginOS",
            instructions = "Open iManager → App Manager → Autostart Manager and allow " +
                "RAZStudio Room. In Settings → Battery, set background app to High consumption / no limit.",
            intent = vivoAutostartIntent(),
        )
        Vendor.Samsung -> Guidance(
            vendorName = "One UI",
            instructions = "Open Settings → Battery → Background usage limits → Never sleeping " +
                "apps, and add RAZStudio Room. Also disable Adaptive Battery for this app.",
            intent = samsungBatteryIntent(),
        )
        Vendor.OnePlus -> Guidance(
            vendorName = "OxygenOS",
            instructions = "Open Settings → Battery → Battery optimization → All apps → " +
                "RAZStudio Room → Don't optimize.",
            intent = null,
        )
        Vendor.Other -> Guidance(
            vendorName = "Stock Android",
            instructions = "Open Settings → Apps → RAZStudio Room → Battery → Unrestricted.",
            intent = null,
        )
    }

    enum class Vendor { Xiaomi, Huawei, Oppo, Vivo, Realme, Samsung, OnePlus, Other }

    // ---------- Per-vendor deep-link intents (best-effort) ----------
    //
    // These activity names are documented by dontkillmyapp.com and have
    // remained stable for a few OS generations, but vendors do break them
    // without warning. Callers must guard with Intent.resolveActivity(...).

    private fun miuiAutostartIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
    }

    private fun huaweiProtectedAppsIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
    }

    private fun oppoAutostartIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        )
    }

    private fun vivoAutostartIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        )
    }

    private fun samsungBatteryIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity",
        )
    }
}
