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
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URI
import java.util.UUID

/**
 * Canon WFT (Wireless File Transmitter) UPnP/SSDP discoverer.
 *
 * "Remote control (EOS Utility)" connection mode on legacy EOS bodies
 * (6D, 5D-III, 7D, 60D…) works the opposite of what we initially
 * implemented: the **camera** is the SSDP advertiser and the **host**
 * (EOS Utility on PC, or this app) is the discoverer. The camera joins
 * an existing Wi-Fi infrastructure network and periodically broadcasts
 * `NOTIFY ssdp:alive` packets to `239.255.255.250:1900` advertising:
 *
 *   NOTIFY * HTTP/1.1
 *   NT: urn:schemas-canon-com:service:ICPO-WFTEOSSystemService:1
 *   NTS: ssdp:alive
 *   USN: uuid:00000000-0000-0000-0001-{MAC_HEX}::{NT}
 *   LOCATION: http://{cameraIp}:{port}/upnp/CameraDevDesc.xml
 *
 * The host fetches the LOCATION XML to learn:
 *   - friendlyName / modelName       — what to show the user
 *   - UDN (uuid:0000…) and X_targetId — Canon-namespaced UUID that the
 *     PTP/IP InitCommand handshake MUST echo back to the camera as its
 *     own host GUID. Without this exact value, the camera tears down
 *     the TCP socket after responding to InitCommandRequest.
 *
 * This class:
 *   1. Sends an `M-SEARCH * HTTP/1.1` with ST = WFT service type, then
 *      listens for HTTP/1.1 200 OK replies (synchronous discovery).
 *   2. Falls back to listening for unsolicited NOTIFY ssdp:alive packets
 *      (in case the camera sends alive announcements before M-SEARCH).
 *   3. Fetches the camera's UPnP description XML from LOCATION.
 *   4. Parses out the X_targetId, friendlyName, and modelName.
 *
 * Returns a [CanonCameraAdvertisement] or null if no camera was found
 * within the timeout.
 */
internal class WftUpnpDiscoverer(
    private val context: Context,
    private val network: Network? = null,
) {

    companion object {
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        /** EOS Utility / Remote control mode — camera joined home Wi-Fi. */
        private const val WFT_SERVICE_TYPE =
            "urn:schemas-canon-com:service:ICPO-WFTEOSSystemService:1"
        /** Connect-to-smartphone mode — camera is the Wi-Fi AP. */
        private const val SMARTPHONE_SERVICE_TYPE =
            "urn:schemas-canon-com:service:ICPO-SmartPhoneEOSSystemService:1"
        private const val TAG = "WftUpnpDiscoverer"
        private const val M_SEARCH_MX_S = 3
    }

    /** Single discovered camera. */
    data class CanonCameraAdvertisement(
        /** Camera's IP on the local subnet — pulled from LOCATION URL. */
        val ip: String,
        /** Canon-namespaced UUID the camera expects as the host's InitCommand GUID. */
        val targetId: UUID,
        /** Friendly name (e.g. "Canon EOS 6D" or the user-set nickname). */
        val friendlyName: String,
        /** Model name (e.g. "Canon EOS 6D"). */
        val modelName: String,
        /**
         * Camera's Wi-Fi MAC as 12 uppercase hex digits, no separators
         * (e.g. "84BA3BF3C06E"). Extracted from the trailing 12 chars of
         * the `<UDN>uuid:00000000-0000-0000-0001-{MAC}</UDN>` field —
         * Canon firmware always embeds the camera's MAC there, and it's
         * stable across power-cycles + pairing-slot resets. Used as the
         * cache key for per-camera connection profiles
         * ([com.RAZStudio.StudioRoom.feature.canon_sync.data.CanonSyncPreferences.cameraProfile]).
         */
        val cameraMac: String?,
        /**
         * Camera-body serial number from `<serialNumber>` in the
         * description XML (e.g. "10981377" for an EOS 6D). Used as a
         * display fallback when [friendlyName] equals [modelName],
         * which means the user hasn't set a body nickname.
         */
        val serialNumber: String,
    )

    /**
     * Discover the first Canon camera on the LAN. Sends an M-SEARCH and
     * waits up to [timeoutMs] for a reply OR an unsolicited NOTIFY
     * ssdp:alive whose ST/NT matches the WFT service. Returns null on
     * timeout.
     */
    suspend fun discoverFirst(timeoutMs: Long = 60_000L): CanonCameraAdvertisement? =
        withContext(Dispatchers.IO) {
            val multicastLock = acquireMulticastLock()
            try {
                withTimeoutOrNull(timeoutMs) {
                    // Construct + configure inside `use` so any setup
                    // failure (Network.bindSocket throws on stale Network
                    // handles, joinGroup on /proc/net/igmp pressure)
                    // still closes the socket and releases its port.
                    MulticastSocket(SSDP_PORT).use { sock ->
                        sock.reuseAddress = true
                        // joinGroup BEFORE bindSocket: binding to a specific Network
                        // handle first causes the multicast join to silently fail or
                        // land on the wrong interface on some Android versions.
                        // Joining the group first (on the correct Wi-Fi NIF) ensures
                        // IGMP membership is established before the socket is pinned.
                        val group = InetSocketAddress(InetAddress.getByName(SSDP_HOST), SSDP_PORT)
                        val nif = pickWifiInterface()
                        if (nif != null) sock.joinGroup(group, nif)
                        network?.bindSocket(sock)
                        val s = sock
                        // Fire one M-SEARCH per service type, twice each, to
                        // ride over UDP loss. The 6D advertises a different
                        // service type depending on which connection mode it's
                        // in: WFT for "Remote control (EOS Utility)" and
                        // SmartPhone for "Connect to smartphone".
                        val searches = listOf(WFT_SERVICE_TYPE, SMARTPHONE_SERVICE_TYPE)
                        repeat(2) {
                            for (st in searches) {
                                sendMSearch(s, st)
                            }
                            try { kotlinx.coroutines.delay(500) } catch (_: Throwable) {}
                        }

                        val buf = ByteArray(4096)
                        while (true) {
                            val pkt = DatagramPacket(buf, buf.size)
                            s.receive(pkt)
                            val payload = String(pkt.data, 0, pkt.length, Charsets.ISO_8859_1)
                            val candidate = parseCandidate(payload) ?: continue
                            Log.i(TAG, "discoverFirst: candidate from ${pkt.address.hostAddress}: $candidate")
                            val ad = fetchDescription(candidate)
                            if (ad != null) return@withTimeoutOrNull ad
                        }
                        @Suppress("UNREACHABLE_CODE") null
                    }
                }
            } catch (t: TimeoutCancellationException) {
                Log.w(TAG, "discoverFirst: timed out", t)
                null
            } catch (t: Throwable) {
                Log.w(TAG, "discoverFirst: failed", t)
                null
            } finally {
                releaseMulticastLock(multicastLock)
            }
        }

    private fun sendMSearch(sock: MulticastSocket, serviceType: String) {
        val msg = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: $M_SEARCH_MX_S\r\n")
            append("ST: $serviceType\r\n")
            append("\r\n")
        }
        val data = msg.toByteArray(Charsets.ISO_8859_1)
        sock.send(DatagramPacket(data, data.size, InetAddress.getByName(SSDP_HOST), SSDP_PORT))
        Log.i(TAG, "sendMSearch: dispatched st=${serviceType.substringAfterLast(':')}")
    }

    /** Headers of interest from an SSDP response or NOTIFY alive. */
    private data class Candidate(val location: String, val nt: String)

    private fun parseCandidate(payload: String): Candidate? {
        val first = payload.substringBefore("\r\n").trim()
        val isResponse = first.startsWith("HTTP/1.1 200", ignoreCase = true)
        val isNotify = first.startsWith("NOTIFY", ignoreCase = true)
        if (!isResponse && !isNotify) return null
        val headers = parseHeaders(payload)
        if (isNotify) {
            val nts = headers["NTS"]?.trim()
            if (!nts.equals("ssdp:alive", ignoreCase = true)) return null
        }
        // Match either WFT or SmartPhone Canon service type, OR a wildcard
        // upnp:rootdevice whose LOCATION/USN points at a Canon camera. Real
        // Canon traffic always carries one of the explicit ST values so the
        // wildcard path is just a safety net for misbehaving stacks.
        val typeHeader = headers["ST"] ?: headers["NT"] ?: return null
        val matches = typeHeader.contains("ICPO-WFTEOSSystemService", ignoreCase = true) ||
            typeHeader.contains("ICPO-SmartPhoneEOSSystemService", ignoreCase = true) ||
            (typeHeader == "upnp:rootdevice" &&
                (headers["USN"]?.contains("canon", ignoreCase = true) == true ||
                    headers["LOCATION"]?.contains("/upnp/CameraDevDesc", ignoreCase = true) == true))
        if (!matches) return null
        val location = headers["LOCATION"]?.trim() ?: return null
        return Candidate(location = location, nt = typeHeader)
    }

    /**
     * GET the camera description XML and parse out targetId, model,
     * friendlyName, plus the camera's IP from the LOCATION URL.
     */
    private fun fetchDescription(candidate: Candidate): CanonCameraAdvertisement? {
        return runCatching {
            val uri = URI(candidate.location)
            val conn = (network?.openConnection(uri.toURL()) ?: uri.toURL().openConnection()) as HttpURLConnection
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            conn.useCaches = false
            conn.requestMethod = "GET"
            // Pose as Windows EOS Utility. The 6D firmware inspects the
            // User-Agent on this GET: when it sees the Microsoft-Windows
            // UPnP signature it transitions out of "Searching for EOS
            // Utility…" and arms its PTP/IP listener on port 15740.
            // Default Dalvik/Java User-Agent is silently ignored and the
            // camera stays in pairing-wait state forever. Verified
            // against every PC_EOSUTILITY*.pcapng capture in the project
            // root — all CameraDevDesc.xml GETs use these exact headers.
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("Connection", "Close")
            conn.setRequestProperty("Pragma", "no-cache")
            conn.setRequestProperty("Accept", "text/xml, application/xml")
            conn.setRequestProperty("User-Agent", "Microsoft-Windows/10.0 UPnP/1.0")
            val xml = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            Log.i(TAG, "fetchDescription: fetched ${xml.length} bytes from ${uri.host}")
            parseDescription(xml, cameraIp = uri.host)
        }.onFailure { Log.w(TAG, "fetchDescription: failed", it) }.getOrNull()
    }

    private fun parseDescription(xml: String, cameraIp: String): CanonCameraAdvertisement? {
        // X_targetId is only present once the camera has been paired
        // (in EOS Utility mode after the on-camera SET press, or in
        // Smartphone mode after the user accepts the connection on the
        // camera body). For first-time pairings the camera serves a
        // skeleton CameraDevDesc.xml WITHOUT X_targetId — we still
        // want to consider that a valid candidate so the user can
        // complete pairing. Fall back to a stable host-side UUID
        // derived from the camera's IP; the 6D accepts any GUID in
        // the unpaired state.
        val targetIdRaw = Regex(
            "<ns:X_targetId[^>]*>\\s*(?:uuid:)?([0-9A-Fa-f-]+)\\s*</ns:X_targetId>"
        ).find(xml)?.groupValues?.getOrNull(1)
        val targetId = targetIdRaw
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: run {
                Log.i(TAG, "parseDescription: no X_targetId in xml from $cameraIp — " +
                    "using sentinel GUID (camera is in pre-pairing state)")
                UUID.fromString("00000000-0000-0000-0000-000000000000")
            }
        val friendlyName = Regex("<friendlyName>([^<]+)</friendlyName>")
            .find(xml)?.groupValues?.getOrNull(1)?.trim() ?: "Canon Camera"
        val modelName = Regex("<modelName>([^<]+)</modelName>")
            .find(xml)?.groupValues?.getOrNull(1)?.trim() ?: friendlyName
        // Canon embeds the camera's Wi-Fi MAC in the UDN's trailing 12 hex
        // digits: `<UDN>uuid:00000000-0000-0000-0001-84BA3BF3C06E</UDN>`.
        // Stable per-device, survives power-cycles + pairing resets — used
        // as the cache key for connection profiles.
        val cameraMac = Regex(
            "<UDN>\\s*uuid:[0-9A-Fa-f-]+?-([0-9A-Fa-f]{12})\\s*</UDN>"
        ).find(xml)?.groupValues?.getOrNull(1)?.uppercase()
        // <serialNumber> always present on EOS bodies — used as the
        // display fallback when the user hasn't set a body-side
        // nickname (in which case friendlyName == modelName).
        val serialNumber = Regex("<serialNumber>([^<]+)</serialNumber>")
            .find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return CanonCameraAdvertisement(
            ip = cameraIp,
            targetId = targetId,
            friendlyName = friendlyName,
            modelName = modelName,
            cameraMac = cameraMac,
            serialNumber = serialNumber,
        )
    }

    private fun parseHeaders(payload: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in payload.split("\r\n").drop(1)) {
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                out[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
            }
        }
        return out
    }

    private fun pickWifiInterface(): NetworkInterface? = runCatching {
        val nifs = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
        // Priority 1: phone hotspot interface (ap0, wlan1, swlan0, …).
        // When the phone's Wi-Fi hotspot is enabled the camera connects TO
        // the phone; SSDP packets arrive on the hotspot NIF, not wlan0.
        // Android names hotspot interfaces: "ap0", "wlan1", "swlan0", "softap0".
        val hotspotNif = nifs.firstOrNull { nif ->
            nif.name.startsWith("ap") || nif.name == "wlan1" ||
                nif.name.startsWith("swlan") || nif.name.startsWith("softap")
        }
        if (hotspotNif != null) {
            Log.i(TAG, "pickWifiInterface: using hotspot NIF ${hotspotNif.name}")
            return@runCatching hotspotNif
        }
        // Priority 2: any wlan* interface with a private-range IPv4.
        // Covers infrastructure mode (camera joined home AP, or camera AP
        // and phone joined camera AP simultaneously).
        nifs.filter { nif ->
            nif.name.startsWith("wlan") &&
                nif.inetAddresses.toList().any { addr ->
                    addr is java.net.Inet4Address &&
                        (addr.hostAddress?.startsWith("192.168.") == true ||
                            addr.hostAddress?.startsWith("10.") == true ||
                            addr.hostAddress?.startsWith("172.") == true)
                }
        }.maxByOrNull { it.index } // highest index = most recently joined
    }.getOrNull()

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.createMulticastLock("RAZStudio-WFT-Discover").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
    }
}
