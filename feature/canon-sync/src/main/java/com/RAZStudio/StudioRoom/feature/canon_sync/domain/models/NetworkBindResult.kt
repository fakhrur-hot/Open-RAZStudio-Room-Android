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

package com.RAZStudio.StudioRoom.feature.canon_sync.domain.models

import android.net.Network

/**
 * Typed outcome of
 * [com.RAZStudio.StudioRoom.feature.canon_sync.net.NetworkBinder.acquireCameraWifi].
 *
 * On [Acquired] the caller **must** call [Acquired.release] when finished with
 * the network. The per-app outstanding-NetworkRequest cap is 100 (across all
 * NetworkCallback registrations), so leaking the callback eventually throws
 * `TooManyRequestsException` and the app can no longer bind any network. The
 * release() lambda invokes `ConnectivityManager.unregisterNetworkCallback`.
 */
sealed interface NetworkBindResult {

    data class Acquired(
        /**
         * Per-app [Network] handle to bind sockets / SSDP M-SEARCH to.
         *
         * Null when the phone runs the Wi-Fi hotspot AP itself (camera
         * connects to the phone). In that topology the phone has no
         * "default" Wi-Fi network — the AP interface (`ap0`/`wlan1`)
         * isn't surfaced — but the kernel's routing table delivers
         * `192.168.x.x` traffic over the AP interface automatically.
         * Callers should treat null as "no Network scoping needed; use
         * normal socket I/O" rather than as an error.
         */
        val network: Network?,
        val release: () -> Unit,
    ) : NetworkBindResult

    sealed interface Failure : NetworkBindResult {

        /** Device is below API 29 — WifiNetworkSpecifier isn't available. */
        data object Unsupported : Failure

        /**
         * NEARBY_WIFI_DEVICES (API 33+) or ACCESS_FINE_LOCATION (≤32) has not
         * been granted. Caller should re-prompt and retry.
         */
        data object PermissionMissing : Failure

        /**
         * `onUnavailable()` from the NetworkCallback — the user dismissed the
         * system network-picker dialog, or no SSID matched the spec.
         */
        data object UserDeclined : Failure

        /** Caller's deadline elapsed before `onAvailable` arrived. */
        data object Timeout : Failure

        /** ConnectivityManager threw (TooManyRequestsException et al.). */
        data class Error(val message: String) : Failure

        /**
         * Phone is not currently joined to a camera-AP-style Wi-Fi network.
         * Either no active network, active network isn't Wi-Fi, or active
         * Wi-Fi has been validated as having internet (so it's a regular
         * home/office Wi-Fi, not a camera AP).
         *
         * UI should prompt the user to join the camera's Wi-Fi via Android
         * Settings, then tap Connect again. This is the same UX Canon
         * Camera Connect ships.
         */
        data object WrongNetwork : Failure
    }
}
