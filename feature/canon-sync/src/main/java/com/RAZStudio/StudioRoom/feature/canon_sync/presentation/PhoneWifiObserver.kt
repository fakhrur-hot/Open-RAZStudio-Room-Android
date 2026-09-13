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

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide observer for the phone's Wi-Fi state. Singleton so
 * there's exactly one [ConnectivityManager.NetworkCallback] registered for
 * the app's lifetime — leaking callbacks burns one of the per-UID 100
 * registration slots permanently.
 *
 * **Instrumentation:** every state transition is logged with the full
 * NetworkCapabilities snapshot + the system's allNetworks list. The moment
 * the UI pip flips green → red we want to be able to read the logcat and
 * see exactly which Wi-Fi network the OS thought was available, what
 * capabilities it had, and what the process-bound network was.
 */
@Singleton
internal class PhoneWifiObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _state = MutableStateFlow(PhoneWifiState.Unknown)
    val state: StateFlow<PhoneWifiState> = _state.asStateFlow()

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService<ConnectivityManager>()

    private val wifiManager: WifiManager? =
        context.getSystemService<WifiManager>()

    init {
        connectivityManager?.let { cm ->
            runCatching {
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(
                    req,
                    object : ConnectivityManager.NetworkCallback() {

                        override fun onAvailable(network: Network) {
                            val caps = cm.getNetworkCapabilities(network)
                            val newState = caps?.let { inferState(it) } ?: PhoneWifiState.Off
                            transitionTo(newState, "onAvailable", network, caps)
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities,
                        ) {
                            transitionTo(inferState(capabilities), "onCapabilitiesChanged",
                                network, capabilities)
                        }

                        override fun onLinkPropertiesChanged(
                            network: Network,
                            linkProperties: LinkProperties,
                        ) {
                            // Log only — don't change state on link-prop alone.
                            val caps = cm.getNetworkCapabilities(network)
                            Log.i(TAG, "onLinkPropertiesChanged net=$network " +
                                "iface=${linkProperties.interfaceName} " +
                                "routes=${linkProperties.routes.size} " +
                                "dns=${linkProperties.dnsServers.size} " +
                                "caps=${caps?.let { capsSummary(it) }}")
                        }

                        override fun onLosing(network: Network, maxMsToLive: Int) {
                            Log.w(TAG, "onLosing net=$network maxMsToLive=$maxMsToLive " +
                                "(OS warning we will lose this network soon)")
                        }

                        override fun onLost(network: Network) {
                            // Don't immediately flip to Off — another Wi-Fi
                            // may still be available. Re-check after the
                            // network state settles.
                            val survivors = cm.allNetworks.toList()
                                .map { net ->
                                    val c = cm.getNetworkCapabilities(net)
                                    Triple(net, c, c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false)
                                }
                            val activeWifi = survivors.firstOrNull { it.third }?.second
                            val newState = activeWifi?.let { inferState(it) }
                                ?: PhoneWifiState.Off
                            Log.w(TAG, "onLost net=$network survivors=${survivors.size} " +
                                "wifiSurvivor=${activeWifi != null} → $newState")
                            transitionTo(newState, "onLost", network, activeWifi)
                        }
                    }
                )
                Log.i(TAG, "init: WIFI NetworkCallback registered (process-default network = " +
                    "${cm.boundNetworkForProcess})")
            }.onFailure {
                Log.e(TAG, "init: registerNetworkCallback failed", it)
            }
        }
    }

    private fun transitionTo(
        newState: PhoneWifiState,
        reason: String,
        network: Network,
        caps: NetworkCapabilities?,
    ) {
        val prev = _state.value
        if (prev == newState) {
            Log.i(TAG, "[$reason] state unchanged ($prev) net=$network " +
                "caps=${caps?.let { capsSummary(it) }}")
            return
        }
        val wasGreen = prev == PhoneWifiState.ConnectedNoInternet ||
            prev == PhoneWifiState.ConnectedToInternet
        val isRed = newState == PhoneWifiState.Off ||
            newState == PhoneWifiState.Unknown
        _state.value = newState

        // Always log every transition.
        Log.i(TAG, "[$reason] $prev → $newState  net=$network " +
            "caps=${caps?.let { capsSummary(it) }}")

        // The critical moment: green → red. Dump everything we know.
        if (wasGreen && isRed) {
            dumpDiagnostics(reason, network, caps)
        }
    }

    /**
     * Comprehensive snapshot logged at the green→red transition. Helps
     * diagnose whether the OS Wi-Fi truly dropped, whether the process
     * binding pinned us to a stale network, what the active default
     * route is, and what the WifiManager driver-level view says.
     */
    private fun dumpDiagnostics(
        reason: String,
        triggerNetwork: Network,
        triggerCaps: NetworkCapabilities?,
    ) {
        val cm = connectivityManager ?: return
        val ts = System.currentTimeMillis()
        Log.e(TAG, "=== GREEN → RED DIAGNOSTIC (reason=$reason ts=$ts) ===")
        Log.e(TAG, "trigger: net=$triggerNetwork caps=${triggerCaps?.let { capsSummary(it) }}")
        // Process-bound network (what bindProcessToNetwork set).
        Log.e(TAG, "process: boundNetworkForProcess=${cm.boundNetworkForProcess}")
        // Default network (what unbound traffic would use).
        Log.e(TAG, "default: activeNetwork=${cm.activeNetwork}")
        Log.e(TAG, "default: activeNetworkInfo=${cm.activeNetworkInfo}")
        // Walk every known network and summarize each.
        val all = cm.allNetworks
        Log.e(TAG, "system: allNetworks.size=${all.size}")
        all.forEachIndexed { idx, net ->
            val caps = cm.getNetworkCapabilities(net)
            val link = cm.getLinkProperties(net)
            Log.e(TAG, "system[$idx]: net=$net iface=${link?.interfaceName} " +
                "caps=${caps?.let { capsSummary(it) }} " +
                "routes=${link?.routes?.size} dns=${link?.dnsServers?.size}")
        }
        // WifiManager driver-level snapshot — what the radio actually reports.
        try {
            val wi = wifiManager?.connectionInfo
            Log.e(TAG, "wifi: ssid='${wi?.ssid}' bssid=${wi?.bssid} " +
                "rssi=${wi?.rssi} linkSpeed=${wi?.linkSpeed} " +
                "networkId=${wi?.networkId} ip=${wi?.ipAddress}")
            Log.e(TAG, "wifi: isWifiEnabled=${wifiManager?.isWifiEnabled} " +
                "wifiState=${wifiManager?.wifiState}")
        } catch (t: Throwable) {
            Log.e(TAG, "wifi: snapshot failed", t)
        }
        // Process-network-binding integrity check.
        try {
            val bound = cm.boundNetworkForProcess
            val boundCaps = bound?.let { cm.getNetworkCapabilities(it) }
            val boundLink = bound?.let { cm.getLinkProperties(it) }
            Log.e(TAG, "bound: net=$bound caps=${boundCaps?.let { capsSummary(it) }} " +
                "iface=${boundLink?.interfaceName} routes=${boundLink?.routes?.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "bound: snapshot failed", t)
        }
        Log.e(TAG, "build: sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}")
        Log.e(TAG, "=== END DIAGNOSTIC ===")
    }

    private fun capsSummary(c: NetworkCapabilities): String {
        val transports = buildList {
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELL")
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETH")
        }.joinToString("+")
        val caps = buildList {
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("INTERNET")
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("VALIDATED")
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("NOT_METERED")
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) add("NOT_RESTRICTED")
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)) add("TRUSTED")
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) add("NOT_SUSPENDED")
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) add("NOT_VPN")
        }.joinToString("|")
        return "[$transports|$caps|linkDownKbps=${c.linkDownstreamBandwidthKbps}]"
    }

    private fun inferState(caps: NetworkCapabilities): PhoneWifiState {
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        if (!isWifi) {
            // No Wi-Fi station network — but check if our own hotspot
            // is up. The Network/Capabilities callbacks DON'T fire for
            // AP-side state changes (the AP interface isn't exposed as
            // a Network), so we have to peek WifiManager directly.
            return if (isWifiApEnabled()) PhoneWifiState.HotspotActive
            else PhoneWifiState.Off
        }
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (hasInternet) PhoneWifiState.ConnectedToInternet
        else PhoneWifiState.ConnectedNoInternet
    }

    /**
     * Reflective check on the hidden `WifiManager.isWifiApEnabled()`
     * method. Same trick we use in NetworkBinder — the method's been
     * public-then-hidden since API 26 but every Wi-Fi-aware Android
     * app still uses reflection to read it. Returns false on any
     * failure so we conservatively fall through to [PhoneWifiState.Off]
     * rather than misreport.
     */
    private fun isWifiApEnabled(): Boolean {
        val wm = wifiManager ?: return false
        return runCatching {
            val m = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
            m.isAccessible = true
            m.invoke(wm) as? Boolean ?: false
        }.getOrElse {
            Log.d(TAG, "isWifiApEnabled: reflection failed (${it.javaClass.simpleName})")
            false
        }
    }

    /**
     * Periodic hotspot-state poller. NetworkCallback doesn't fire for
     * AP changes, so the pip would stay stuck on whatever station-Wi-Fi
     * state was last reported. Every 2 s we re-evaluate the AP state
     * and re-publish if it flips Off ↔ HotspotActive.
     *
     * Cheap — reflection costs <0.1 ms on warm JVM caches. Battery hit
     * is negligible at 2 s cadence vs a real poll loop with networking.
     */
    fun startHotspotPolling(scope: kotlinx.coroutines.CoroutineScope) {
        if (hotspotPollJob?.isActive == true) return
        hotspotPollJob = scope.launch {
            while (isActive) {
                val apOn = isWifiApEnabled()
                val current = _state.value
                when {
                    apOn && (current == PhoneWifiState.Off || current == PhoneWifiState.Unknown) -> {
                        Log.i(TAG, "hotspotPoll: $current → HotspotActive (AP came up)")
                        _state.value = PhoneWifiState.HotspotActive
                    }
                    !apOn && current == PhoneWifiState.HotspotActive -> {
                        Log.i(TAG, "hotspotPoll: HotspotActive → Off (AP went down)")
                        _state.value = PhoneWifiState.Off
                    }
                }
                kotlinx.coroutines.delay(HOTSPOT_POLL_MS)
            }
        }
    }

    fun stopHotspotPolling() {
        hotspotPollJob?.cancel()
        hotspotPollJob = null
    }

    private var hotspotPollJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val TAG = "PhoneWifiObserver"
        private const val HOTSPOT_POLL_MS = 2_000L
    }
}
