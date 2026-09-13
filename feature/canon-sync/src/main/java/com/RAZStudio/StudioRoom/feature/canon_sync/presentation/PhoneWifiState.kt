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

package com.RAZStudio.StudioRoom.feature.canon_sync.presentation

/**
 * Observed state of the phone's default network, narrowed to what the Canon
 * Sync status pill cares about.
 *
 * We don't have SSID visibility without extra permissions, so we infer the
 * "looks like a camera AP" case from the combination
 *   (default network is Wi-Fi) + (no internet capability).
 *
 * That's not a perfect match — the user could be on any captive-portal Wi-Fi
 * with no upstream — but for the pill's "phone-side connectivity looks ready
 * for Canon Sync" signal it's close enough. Once [com.RAZStudio.StudioRoom.
 * feature.canon_sync.net.NetworkBinder] actually acquires a camera-AP
 * specifier-matched Network, the Camera pill (driven by the repository) is
 * the authoritative signal.
 */
enum class PhoneWifiState {
    /** Initial state before any network callback has fired. */
    Unknown,

    /** No Wi-Fi default network, or Wi-Fi is off entirely. */
    Off,

    /** Wi-Fi default network but with internet (regular home Wi-Fi). */
    ConnectedToInternet,

    /** Wi-Fi default network without internet — likely a camera AP. */
    ConnectedNoInternet,

    /**
     * Phone is running its own Wi-Fi hotspot (the camera joins the
     * phone as a client). The phone has no station Wi-Fi but the AP
     * interface is up and routable to camera-side `192.168.x.x`. UI
     * should treat this as a green pip.
     */
    HotspotActive,
}
