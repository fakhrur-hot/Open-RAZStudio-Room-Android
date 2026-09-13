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

package com.RAZStudio.StudioRoom.feature.sony_sync.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Resolves the [Network] handle for the Sony camera's Wi-Fi SoftAP so all
 * sockets can be routed to it (Android 10+ keeps the phone's *default* network
 * elsewhere even while joined to a no-internet camera AP — see docs/SONY_SYNC.md,
 * confirmed by the PlayMemories capture using WifiNetworkSpecifier).
 *
 * Two paths:
 *   [findCurrentCameraNetwork] — the phone is ALREADY on the camera Wi-Fi (user
 *     joined via settings or NFC). We pick the active Wi-Fi network that has no
 *     internet and (best-effort) a `DIRECT-…` SSID, bind the process to it, and
 *     return it. This is the v1 path.
 *   [requestCameraNetwork] — full WifiNetworkSpecifier join for the NFC/QR flow
 *     once we can supply the SSID + passphrase (Android shows its own connect
 *     dialog). Wired for the follow-on NFC slice.
 */
class SonyWifiConnector(private val context: Context) {

    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    /** Current Wi-Fi SSID, or null (needs location permission on many OEMs). */
    fun currentSsid(): String? = runCatching {
        wifi.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }.getOrNull()

    /**
     * Find the Wi-Fi network the phone is currently joined to (the camera AP),
     * bind this process to it, and return it. Prefers a no-internet Wi-Fi
     * transport. Returns null if no such network is present.
     */
    fun findCurrentCameraNetwork(): Network? {
        val candidates = cm.allNetworks.filter { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@filter false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
        // Prefer a Wi-Fi network with NO internet (the camera AP); fall back to
        // the sole Wi-Fi network if capability flags are unreliable on this OEM.
        val net = candidates.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: candidates.singleOrNull() ?: candidates.firstOrNull()

        if (net != null) {
            @Suppress("DEPRECATION")
            cm.bindProcessToNetwork(net)
        }
        return net
    }

    /**
     * Join a specific Sony SoftAP by SSID + WPA2 passphrase via
     * WifiNetworkSpecifier and bind to it. Suspends until connected or [timeoutMs].
     * Use this once the NFC/QR handoff yields credentials. Returns the bound
     * Network or null on failure/timeout.
     */
    suspend fun requestCameraNetwork(ssid: String, passphrase: String, timeoutMs: Long = 20_000): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        @Suppress("DEPRECATION")
                        cm.bindProcessToNetwork(network)
                        if (cont.isActive) cont.resume(network)
                    }
                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                activeCallback = cb
                cm.requestNetwork(request, cb)
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        }
    }

    /** Release the process network binding + any active request callback. */
    fun release() {
        @Suppress("DEPRECATION")
        runCatching { cm.bindProcessToNetwork(null) }
        activeCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        activeCallback = null
    }

    /** Acquire a Wi-Fi multicast lock so SSDP (239.255.255.250) is not dropped. */
    fun acquireMulticastLock(): WifiManager.MulticastLock =
        wifi.createMulticastLock("sony-sync-ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
}
