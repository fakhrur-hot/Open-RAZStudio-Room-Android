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

package com.RAZStudio.StudioRoom.feature.canon_sync.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.NetworkBindResult

/**
 * Resolves the per-app [android.net.Network] handle for PTP/IP sockets,
 * matching Canon Camera Connect's behaviour (verified via `dumpsys
 * connectivity` against `jp.co.canon.ic.cameraconnect` on a live device).
 *
 * **What Canon does, that we now do too:**
 *
 *   - Never calls `ConnectivityManager.requestNetwork()`.
 *   - Never uses a `WifiNetworkSpecifier` / SSID matcher.
 *   - Never shows the system Wi-Fi picker.
 *   - Just relies on the user having joined the camera AP manually via
 *     Android Settings → Wi-Fi, then operates on the resulting default
 *     network.
 *
 * **Why we used to do it differently, and why that was wrong for legacy
 * Canon bodies (6D-era):**
 *
 *   - `requestNetwork(WifiNetworkSpecifier)` tells Android to re-associate
 *     to the picked SSID as an app-scoped restricted network. The OS
 *     disconnects from the current Wi-Fi association and re-associates a
 *     few seconds later. The 6D's firmware treats that disconnect-reconnect
 *     as a session failure and kills its AP entirely.
 *   - The "scope traffic to camera AP only" benefit that `requestNetwork`
 *     gave us is moot: the camera AP has no internet, so non-PTP traffic
 *     naturally fails over to mobile data through the regular default
 *     route. We get the same isolation for free.
 */
internal class NetworkBinder(private val context: Context) {

    /**
     * Detect "phone is the Wi-Fi AP" topology. `WifiManager.isWifiApEnabled`
     * was public until API 26 then hidden; modern AOSP still exposes the
     * method on the system service, just not in the SDK. Reflection is
     * the standard workaround used by every Wi-Fi-AP-aware Android app
     * I've seen (Termux's `wifictl`, KDE Connect, Localsend, …).
     *
     * Returns false on any failure (e.g. OEM removed the method, missing
     * permission, reflection security exception) — we'd rather assume
     * "no AP" and fall through to the normal Wi-Fi join check than crash.
     */
    private fun isWifiApEnabled(): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? WifiManager ?: return false
        return runCatching {
            val m = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
            m.isAccessible = true
            m.invoke(wm) as? Boolean ?: false
        }.getOrElse {
            Log.d(TAG, "isWifiApEnabled: reflection failed (${it.javaClass.simpleName}) — assuming false")
            false
        }
    }


    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Resolves the [android.net.Network] handle we'll bind PTP/IP sockets to.
     *
     * We match Canon Camera Connect's approach exactly (verified via
     * `dumpsys connectivity` on a live device): use whatever Wi-Fi network
     * the user has already manually joined via Android Settings → Wi-Fi.
     * **No `requestNetwork()` call, no `WifiNetworkSpecifier`, no system
     * Wi-Fi picker.**
     *
     * Why this matches Canon: their NetworkRequest pattern is purely
     * passive — TRANSPORT_WIFI listen-only callbacks, registered and
     * released in milliseconds to observe state changes. They never
     * force-associate a network. The result is that legacy Canon bodies
     * (6D / DIGIC 5+ era) never see the disconnect-reconnect cycle that
     * `requestNetwork` triggers, and never kill their AP mid-handshake.
     *
     * The trade-off is that if the user's default network ISN'T the
     * camera AP (e.g. they're still on home Wi-Fi), we return
     * [NetworkBindResult.Failure.WrongNetwork] and the UI surfaces a
     * "please join the camera Wi-Fi in Android Settings" message —
     * same UX Canon ships.
     */
    suspend fun acquireCameraWifi(): NetworkBindResult {
        Log.d(TAG, "acquireCameraWifi: start sdk=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "acquireCameraWifi: API < Q, unsupported")
            return NetworkBindResult.Failure.Unsupported
        }

        // Phone-as-AP topology: the user runs Android's "Wi-Fi hotspot"
        // and the camera joins THAT. In this case the phone has no
        // active Wi-Fi network of its own — the AP interface (typically
        // `ap0` / `wlan1`) isn't surfaced as a default network. Detect
        // tether state and short-circuit with a "no Network handle
        // needed" success — the OS routing table delivers traffic to
        // 192.168.x.x via the AP interface automatically, so PTP/IP
        // sockets just work without bindProcessToNetwork.
        if (isWifiApEnabled()) {
            Log.i(TAG, "acquireCameraWifi: phone hotspot active — skipping Network bind, " +
                "letting kernel route to camera over the AP interface")
            return NetworkBindResult.Acquired(network = null, release = {})
        }
        val active = connectivityManager.activeNetwork
        if (active == null) {
            Log.w(TAG, "acquireCameraWifi: no active network — phone not connected to any Wi-Fi")
            return NetworkBindResult.Failure.WrongNetwork
        }
        val caps = connectivityManager.getNetworkCapabilities(active)
        if (caps == null) {
            Log.w(TAG, "acquireCameraWifi: getNetworkCapabilities returned null for $active")
            return NetworkBindResult.Failure.WrongNetwork
        }
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        Log.d(TAG, "acquireCameraWifi: active=$active wifi=$isWifi validated=$isValidated")

        // Don't fail just because the OS DEFAULT network isn't Wi-Fi. When the
        // phone is on the camera's/home Wi-Fi but ALSO has cellular up (or the
        // Wi-Fi is momentarily unvalidated), Android keeps cellular as the
        // default `activeNetwork`, so the old code returned WrongNetwork even
        // though the phone was on the right Wi-Fi. Bind the WIFI-transport
        // network explicitly — this is what makes "both phone and camera on
        // home Wi-Fi" (and camera-AP with data on) work.
        val cameraWifi: Network = if (isWifi) active else findWifiNetwork() ?: run {
            Log.w(TAG, "acquireCameraWifi: default network isn't Wi-Fi and no other " +
                "connected Wi-Fi network found — phone not on any Wi-Fi")
            return NetworkBindResult.Failure.WrongNetwork
        }
        // NOTE: we previously rejected `isValidated=true` (internet present)
        // on the assumption that any Wi-Fi with internet couldn't be a camera
        // AP. That heuristic only fits "Connect to smartphone" (camera = AP,
        // no upstream). The "Remote control (EOS Utility)" path puts the
        // camera on a regular home Wi-Fi WITH internet, so this rejection
        // blocked the discovery path entirely. We now accept any Wi-Fi and
        // let the SSDP discoverer decide whether a Canon camera is reachable.
        Log.i(TAG, "acquireCameraWifi: binding Wi-Fi=$cameraWifi (default active=$active, activeIsWifi=$isWifi, activeValidated=$isValidated)")
        // Hold the camera-AP Wi-Fi against Android's auto-drop heuristics.
        // Android 11+ aggressively reassigns the default network to a
        // route that has validated internet (cellular, home Wi-Fi) when
        // the current Wi-Fi has none — fine for normal apps, fatal for
        // PTP/IP because:
        //   1) the visible "Phone" pip in the UI follows the default
        //      network (PhoneWifiObserver uses registerDefaultNetworkCallback)
        //      so it flips red whenever the OS moves default away,
        //   2) any traffic the app sends WITHOUT explicit Network binding
        //      (DNS lookups, etc.) falls through to cellular and never
        //      reaches the camera.
        //
        // Fix: register the camera Wi-Fi as a desired NO_INTERNET WIFI
        // network and bind the PROCESS default to it. The kernel then
        // routes every socket in our process — bound or unbound — to the
        // camera AP, and the OS treats "no-internet Wi-Fi is preferred"
        // as a deliberate developer choice, not a state to fix. Release
        // restores the previous default network on session teardown.
        val keepAlive: Pair<Network, () -> Unit> =
            registerKeepAliveCallback() ?: (cameraWifi to { /* no-op */ })
        // Bind the Wi-Fi we chose above — NOT keepAlive.first, which falls back
        // to the (possibly cellular) default network when the WIFI keep-alive
        // request hasn't resolved synchronously.
        val held = cameraWifi
        val previousProcessNetwork = runCatching { connectivityManager.boundNetworkForProcess }.getOrNull()
        runCatching { connectivityManager.bindProcessToNetwork(held) }
            .onFailure { Log.w(TAG, "bindProcessToNetwork failed", it) }
        val release: () -> Unit = {
            runCatching { connectivityManager.bindProcessToNetwork(previousProcessNetwork) }
            keepAlive.second()
        }
        return NetworkBindResult.Acquired(
            network = held,
            release = release,
        )
    }

    /**
     * Find a connected WIFI-transport [Network] — the network the camera is on
     * when "both phone and camera are on home Wi-Fi" (or on a camera AP) — even
     * when it is NOT the OS default `activeNetwork`. Android keeps cellular as
     * the default when the phone has mobile data on, or when the Wi-Fi has no
     * validated upstream (camera AP), so `activeNetwork` alone misses the
     * phone's actual Wi-Fi. Prefers the active network if it is Wi-Fi; otherwise
     * scans all connected networks for the first WIFI-transport one. Returns
     * null only when the phone is on no Wi-Fi at all.
     */
    private fun findWifiNetwork(): Network? {
        val cm = connectivityManager
        cm.activeNetwork?.let { n ->
            if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return n
            }
        }
        @Suppress("DEPRECATION")
        val all = runCatching { cm.allNetworks }.getOrNull().orEmpty()
        return all.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * Register a passive [ConnectivityManager.NetworkCallback] on a
     * TRANSPORT_WIFI [NetworkRequest]. The callback is intentionally a
     * no-op — its mere existence prevents Android from auto-dropping a
     * "no internet" Wi-Fi network (camera AP) which is the failure mode
     * users see as the phone Wi-Fi status pip flipping red and the
     * camera AP randomly disconnecting after ~30-90s.
     *
     * Returns the (network, releaseFn) pair, or null if registration
     * failed. The release closure unregisters the callback when the
     * session tears down, restoring normal auto-drop behaviour.
     */
    private fun registerKeepAliveCallback(): Pair<Network, () -> Unit>? {
        return try {
            val cm = connectivityManager
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            val resolved = java.util.concurrent.atomic.AtomicReference<Network?>(null)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    resolved.compareAndSet(null, network)
                    Log.i(TAG, "keepAlive.onAvailable: net=$network")
                }
                override fun onLosing(network: Network, maxMsToLive: Int) {
                    Log.w(TAG, "keepAlive.onLosing: net=$network maxMs=$maxMsToLive " +
                        "(camera AP will drop in $maxMsToLive ms unless reaffirmed)")
                }
                override fun onLost(network: Network) {
                    Log.e(TAG, "keepAlive.onLost: net=$network — held Wi-Fi GONE. " +
                        "boundProcess=${cm.boundNetworkForProcess} active=${cm.activeNetwork}")
                }
                override fun onUnavailable() {
                    Log.e(TAG, "keepAlive.onUnavailable: no matching WIFI network exists")
                }
            }
            cm.registerNetworkCallback(req, cb)
            val held: Network = resolved.get() ?: cm.activeNetwork ?: return null
            Log.i(TAG, "registerKeepAliveCallback: holding $held to prevent OS auto-drop")
            val release: () -> Unit = { runCatching { cm.unregisterNetworkCallback(cb) } }
            held to release
        } catch (t: Throwable) {
            Log.w(TAG, "registerKeepAliveCallback failed", t)
            null
        }
    }

    /**
     * Pin the Wi-Fi network identified by SSID for the lifetime of the
     * returned release closure. Used POST-handshake: once the PTP/IP
     * session is established, we ask the OS to keep this specific SSID
     * connected and not auto-drop it for "no internet". This is the only
     * documented mechanism that makes Android 11+ keep a no-internet
     * Wi-Fi network alive indefinitely — `removeCapability(INTERNET)` on
     * a generic NetworkRequest is not enough, the OS still tears it down
     * after ~30-60s in our testing (see PhoneWifiObserver diagnostic
     * `=== GREEN → RED DIAGNOSTIC ===`).
     *
     * Why we do this AFTER PTP handshake (not before): `requestNetwork`
     * with a `WifiNetworkSpecifier` can trigger a brief Wi-Fi re-
     * association on some bodies, which kills the 6D's PTP/IP socket
     * mid-handshake. Doing it post-handshake the OS sees we're already
     * on the matching SSID and just "claims" the existing association
     * — no re-association, no socket drop.
     *
     * Returns null if pinning fails (older SDK, permission denied, SSID
     * detection failed). Caller stays on the previous keep-alive setup.
     */
    fun pinCameraWifiBySsid(): (() -> Unit)? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cm = connectivityManager
        // Android's Captive Portal Detection (CPD) probes Google validation
        // servers when joining a new AP. If the probe fails (camera AP has
        // no upstream internet), the OS will mark the network unvalidated
        // and may quietly disconnect it to preserve the user's data plan.
        // The documented escape hatch is to register a NetworkRequest that:
        //   • addTransport(WIFI)                  — restricts to Wi-Fi
        //   • addCapability(INTERNET)             — we DO send TCP/IP packets
        //                                           (PTP/IP needs IP routing)
        //   • removeCapability(VALIDATED)         — but we don't need the
        //                                           Google probe to succeed
        // Without removeCapability(VALIDATED) the OS still kills the AP
        // when the probe fails. Without addCapability(INTERNET) some
        // OEM builds skip IP routing setup. Combined, this matches the
        // exact pattern used by Camera Connect and chdkptp Android ports.
        //
        // Why NO WifiNetworkSpecifier with SSID: that path needs
        // ACCESS_FINE_LOCATION at runtime even on Android 13+, which
        // users routinely reject. Capability-only is permission-free.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val pinned = java.util.concurrent.atomic.AtomicReference<Network?>(null)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                pinned.set(network)
                // Re-bind the process to the pin's Network instance. The
                // earlier acquireCameraWifi() bound a Network handle that
                // may now be stale (Android frees Network handles on
                // disassociation even when the radio link is technically
                // still up). Binding here is the authoritative wire-up.
                runCatching { cm.bindProcessToNetwork(network) }
                    .onFailure { Log.w(TAG, "pinCameraWifi: bindProcessToNetwork($network) failed", it) }
                Log.i(TAG, "pinCameraWifi.onAvailable: net=$network pinned + process bound")
            }
            override fun onLosing(network: Network, maxMsToLive: Int) {
                Log.w(TAG, "pinCameraWifi.onLosing: net=$network maxMs=$maxMsToLive")
            }
            override fun onLost(network: Network) {
                if (pinned.get() == network) {
                    pinned.set(null)
                    // Restore default routing so the rest of the system
                    // recovers (cellular for everything else). Without
                    // this the user's phone stays locked out of mobile
                    // data until our process exits.
                    runCatching { cm.bindProcessToNetwork(null) }
                }
                Log.w(TAG, "pinCameraWifi.onLost: net=$network — pinned AP gone " +
                    "(signal loss or forget). active=${cm.activeNetwork}")
            }
            override fun onUnavailable() {
                Log.w(TAG, "pinCameraWifi.onUnavailable: no matching no-internet WIFI exists")
            }
        }
        return try {
            cm.requestNetwork(request, cb)
            Log.i(TAG, "pinCameraWifi: requestNetwork dispatched (WIFI + INTERNET - VALIDATED)")
            // Release closure: cleanly unbind process AND unregister
            // callback. Skipping bindProcessToNetwork(null) here would
            // leave the user's phone routing through a freed Network
            // until process death — see "Engineering Traps to Avoid".
            ({
                runCatching { cm.bindProcessToNetwork(null) }
                runCatching { cm.unregisterNetworkCallback(cb) }
                Log.i(TAG, "pinCameraWifi.release: process unbound + callback unregistered")
            })
        } catch (t: Throwable) {
            Log.e(TAG, "pinCameraWifi failed", t)
            null
        }
    }

    companion object {
        /** Kept in API for stability; unused since we no longer call requestNetwork. */
        private const val DEFAULT_TIMEOUT_MS: Long = 60_000

        /** logcat filter: `adb logcat -s CanonSync:*` */
        private const val TAG = "CanonSync"
    }
}
