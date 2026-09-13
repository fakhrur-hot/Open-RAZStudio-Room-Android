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

import android.net.Network
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

/**
 * A minimal client for Sony's **Camera Remote API** (ScalarWebAPI) — the
 * documented JSON-RPC-over-HTTP interface Alpha bodies expose over their camera
 * Wi-Fi. Discovery is UPnP/SSDP; the retrieval calls live in the `avContent`
 * service. Verified transport facts (from the on-device PlayMemories capture,
 * see docs/SONY_SYNC.md): camera SoftAP `DIRECT-*:ILCE-7M2`, gateway
 * 192.168.122.1, SSDP `upnp-device-finder`.
 *
 * Everything is routed through the supplied [Network] (the camera link) so
 * traffic never leaks onto mobile data — critical on Android 10+, where an app
 * that joined a no-internet Wi-Fi still has its default network elsewhere.
 *
 * No third-party deps: java.net + org.json + XmlPullParser (all Android SDK).
 * Call these off the main thread; every method blocks on I/O.
 */
class SonyCameraRemoteApi(
    private val network: Network?,
    private val log: (String) -> Unit = {},
) {
    /** SSDP result: the LOCATION device-description URL for a Sony ScalarWebAPI device. */
    data class Discovery(val locationUrl: String, val server: String?)

    /** Parsed service endpoints keyed by service type (camera/avContent/system/guide). */
    data class Services(
        val friendlyName: String?,
        val endpoints: Map<String, String>, // e.g. "avContent" -> "http://192.168.122.1:64321/sony/avContent"
    )

    /** One retrievable item from the camera storage. */
    data class RemoteContent(
        val name: String,       // e.g. DSC02243.JPG
        val uri: String,        // content uri (for reference)
        val originalUrl: String, // best full-size URL to download
        val isStill: Boolean,
    )

    // ── SSDP discovery ───────────────────────────────────────────────────────
    /**
     * M-SEARCH for a Sony ScalarWebAPI device on the camera link. Retries a few
     * times (the camera can be slow to answer right after association). Returns
     * null if nothing responds within the budget.
     */
    fun discover(timeoutMs: Int = 3000, attempts: Int = 3): Discovery? {
        val st = "urn:schemas-sony-com:service:ScalarWebAPI:1"
        val msearch = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 1\r\n")
            append("ST: $st\r\n")
            append("\r\n")
        }.toByteArray()

        repeat(attempts) { attempt ->
            try {
                DatagramSocket().use { sock ->
                    network?.bindSocket(sock)
                    sock.soTimeout = timeoutMs
                    sock.broadcast = true
                    val group = InetAddress.getByName("239.255.255.250")
                    sock.send(DatagramPacket(msearch, msearch.size, InetSocketAddress(group, 1900)))
                    log("SSDP M-SEARCH sent (attempt ${attempt + 1}/$attempts)")

                    val buf = ByteArray(2048)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val resp = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(resp)
                        } catch (_: Exception) {
                            break // timeout for this attempt
                        }
                        val text = String(resp.data, 0, resp.length)
                        val location = headerValue(text, "LOCATION")
                        if (location != null &&
                            (text.contains("ScalarWebAPI", true) || location.isNotBlank())
                        ) {
                            // Only accept Sony ScalarWebAPI devices.
                            if (text.contains("ScalarWebAPI", true) || text.contains("sony", true)) {
                                log("SSDP reply: $location")
                                return Discovery(location, headerValue(text, "SERVER"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("SSDP error: ${e.message}")
            }
        }
        return null
    }

    private fun headerValue(response: String, key: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()

    // ── Device description ────────────────────────────────────────────────────
    /**
     * GET + parse the device-description XML at [locationUrl]. Extracts the
     * ScalarWebAPI service endpoints (base ActionList URL + service type →
     * full endpoint), plus the friendlyName for display.
     */
    fun fetchServices(locationUrl: String): Services {
        val xml = httpGetText(locationUrl)
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            .newPullParser()
        parser.setInput(xml.reader())

        var friendlyName: String? = null
        var actionListUrl: String? = null
        val endpoints = LinkedHashMap<String, String>()
        var pendingServiceType: String? = null
        var text = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.TEXT -> text = parser.text ?: ""
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    when {
                        tag.endsWith("friendlyName", true) && friendlyName == null ->
                            friendlyName = text.trim()
                        tag.endsWith("X_ScalarWebAPI_ActionList_URL", true) ->
                            actionListUrl = text.trim()
                        tag.endsWith("X_ScalarWebAPI_ServiceType", true) ->
                            pendingServiceType = text.trim()
                        tag.endsWith("X_ScalarWebAPI_Service", true) -> {
                            val svc = pendingServiceType
                            val base = actionListUrl
                            if (svc != null && base != null) {
                                endpoints[svc] = base.trimEnd('/') + "/" + svc
                            }
                            pendingServiceType = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        log("Services: ${endpoints.keys.joinToString()} (name=$friendlyName)")
        return Services(friendlyName, endpoints)
    }

    // ── JSON-RPC ──────────────────────────────────────────────────────────────
    /** POST a ScalarWebAPI JSON-RPC call and return the raw response object. */
    fun call(endpoint: String, method: String, params: JSONArray = JSONArray(), version: String = "1.0"): JSONObject {
        val body = JSONObject()
            .put("method", method)
            .put("params", params)
            .put("id", 1)
            .put("version", version)
            .toString()
        val resp = httpPostJson(endpoint, body)
        return JSONObject(resp)
    }

    private fun resultArray(resp: JSONObject): JSONArray? =
        resp.optJSONArray("result") ?: resp.optJSONArray("results")

    // ── High-level retrieval ─────────────────────────────────────────────────
    /**
     * Put the camera into Contents Transfer mode (if supported), then browse
     * still images. Returns an empty list (and logs why) if the body doesn't
     * expose avContent browsing — many older bodies only allow remote shooting.
     */
    fun listStills(services: Services, max: Int = 200): List<RemoteContent> {
        val avContent = services.endpoints["avContent"]
        if (avContent == null) {
            log("avContent service not offered by this camera — browsing unsupported (remote-shoot-only body).")
            return emptyList()
        }
        val camera = services.endpoints["camera"]

        // Switch to Contents Transfer so avContent can enumerate the card.
        if (camera != null) {
            runCatching {
                val r = call(camera, "setCameraFunction", JSONArray().put("Contents Transfer"), "1.0")
                log("setCameraFunction(Contents Transfer): ${r.opt("result") ?: r.opt("error")}")
            }.onFailure { log("setCameraFunction failed: ${it.message}") }
            // The camera drops and re-advertises the API after a mode switch; give it a moment.
            Thread.sleep(1500)
        }

        // scheme -> source (usually storage -> storage:memoryCard1)
        val source = runCatching {
            val schemes = call(avContent, "getSchemeList", JSONArray(), "1.0")
            log("getSchemeList: ${resultArray(schemes)}")
            val sources = call(avContent, "getSourceList",
                JSONArray().put(JSONObject().put("scheme", "storage")), "1.0")
            resultArray(sources)?.optJSONArray(0)?.optJSONObject(0)?.optString("source")
        }.getOrNull() ?: "storage:memoryCard1"
        log("source = $source")

        val out = ArrayList<RemoteContent>()
        var stIdx = 0
        val page = 50
        while (out.size < max) {
            val params = JSONArray().put(
                JSONObject()
                    .put("uri", source)
                    .put("stIdx", stIdx)
                    .put("cnt", page)
                    .put("view", "flat")
                    .put("sort", "descending")
            )
            val resp = runCatching { call(avContent, "getContentList", params, "1.3") }
                .getOrElse { log("getContentList failed: ${it.message}"); break }
            val arr = resultArray(resp)?.optJSONArray(0) ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val content = item.optJSONObject("content") ?: continue
                val kind = item.optString("contentKind")
                val still = kind.equals("still", true)
                val name = item.optString("title").ifBlank {
                    content.optJSONArray("original")?.optJSONObject(0)?.optString("fileName")
                        ?: "IMG_$stIdx$i"
                }
                val originalUrl = content.optJSONArray("original")?.optJSONObject(0)?.optString("url")
                    ?: content.optString("largeUrl").ifBlank { content.optString("thumbnailUrl") }
                if (originalUrl.isNotBlank()) {
                    out.add(RemoteContent(name, item.optString("uri"), originalUrl, still))
                }
            }
            stIdx += page
        }
        log("Found ${out.size} content item(s).")
        return out.take(max)
    }

    // ── DLNA / UPnP MediaServer path (the A7 II "Send to Smartphone" mode) ─────
    // The A7 II does NOT expose ScalarWebAPI in the plain photo-push mode — it
    // advertises a UPnP MediaServer whose ContentDirectory lists the JPEGs, and
    // PlayMemories pulls them over plain HTTP. This mirrors that: SSDP-find the
    // MediaServer → resolve its ContentDirectory controlURL → recursive SOAP
    // Browse (BrowseDirectChildren) → collect image/jpeg <res> URLs.

    /** ContentDirectory control endpoint + its exact serviceType (for SOAPACTION).
     *  [xPushControlUrl]/[xPushType] are the Sony XPushList service (present in
     *  "Send to Smartphone" push mode) — the completion handshake that stops the
     *  camera's spinner (X_TransferStart/Progress/End). Null if not advertised. */
    data class ContentDirectory(
        val controlUrl: String,
        val serviceType: String,
        val friendlyName: String?,
        val xPushControlUrl: String? = null,
        val xPushType: String? = null,
    )

    /**
     * SSDP M-SEARCH with `ssdp:all` so we see everything the camera advertises
     * (logged for diagnostics), returning the LOCATION of a UPnP MediaServer
     * (or any device exposing a ContentDirectory) if present.
     */
    fun discoverMediaServer(timeoutMs: Int = 3000, attempts: Int = 3): Discovery? {
        val msearch = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: ssdp:all\r\n")
            append("\r\n")
        }.toByteArray()

        val seenLocations = HashSet<String>()
        var mediaServerLoc: String? = null
        var mediaServerServer: String? = null
        repeat(attempts) { attempt ->
            try {
                DatagramSocket().use { sock ->
                    network?.bindSocket(sock)
                    sock.soTimeout = timeoutMs
                    sock.broadcast = true
                    val group = InetAddress.getByName("239.255.255.250")
                    sock.send(DatagramPacket(msearch, msearch.size, InetSocketAddress(group, 1900)))
                    log("SSDP ssdp:all sent (attempt ${attempt + 1}/$attempts)")

                    val buf = ByteArray(2048)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val resp = DatagramPacket(buf, buf.size)
                        try { sock.receive(resp) } catch (_: Exception) { break }
                        val text = String(resp.data, 0, resp.length)
                        val location = headerValue(text, "LOCATION") ?: continue
                        val stOrNt = headerValue(text, "ST") ?: headerValue(text, "NT") ?: ""
                        if (seenLocations.add("$location|$stOrNt")) {
                            log("SSDP: ${stOrNt.ifBlank { "(no ST)" }} @ $location")
                        }
                        // Prefer an explicit MediaServer; fall back to any device
                        // description (we confirm ContentDirectory by parsing it).
                        if (mediaServerLoc == null &&
                            (stOrNt.contains("MediaServer", true) ||
                                stOrNt.contains("ContentDirectory", true))
                        ) {
                            mediaServerLoc = location
                            mediaServerServer = headerValue(text, "SERVER")
                        }
                    }
                }
            } catch (e: Exception) {
                log("SSDP(all) error: ${e.message}")
            }
            if (mediaServerLoc != null) return Discovery(mediaServerLoc!!, mediaServerServer)
        }
        return mediaServerLoc?.let { Discovery(it, mediaServerServer) }
    }

    /** What a single SSDP sweep learned about the camera's advertised services. */
    data class TransportProbe(
        val hasScalarWeb: Boolean,   // urn:schemas-sony-com:…:ScalarWebAPI (newer bodies)
        val hasMediaServer: Boolean, // UPnP MediaServer / ContentDirectory (older bodies)
        val services: List<String>,  // every distinct ST/NT seen, for the log/UI
    ) {
        /** Recommended transport, or null if nothing Sony-ish answered. */
        val recommended: RecommendedTransport?
            get() = when {
                hasScalarWeb -> RecommendedTransport.WIFI_NEWER
                hasMediaServer -> RecommendedTransport.WIFI_OLDER
                else -> null
            }
    }

    enum class RecommendedTransport { WIFI_NEWER, WIFI_OLDER }

    /**
     * ONE `ssdp:all` sweep that classifies the camera's Wi-Fi transport instead of
     * making the user guess "older vs newer". A newer body (A7 III/R III, α9…)
     * advertises `ScalarWebAPI`; an older one (A7 II, α6000, RX…) advertises only
     * a UPnP MediaServer/ContentDirectory in Send-to-Smartphone. Every ST/NT is
     * logged for diagnostics. Cheap enough (one multicast round-trip) to run right
     * before the download so the actual pull uses the matching path. Falls back to
     * the caller's manual choice when nothing answers (returns null `recommended`).
     */
    fun autoDetectTransport(timeoutMs: Int = 3000, attempts: Int = 2): TransportProbe {
        val msearch = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: ssdp:all\r\n")
            append("\r\n")
        }.toByteArray()

        val services = LinkedHashSet<String>()
        var hasScalarWeb = false
        var hasMediaServer = false
        repeat(attempts) { attempt ->
            try {
                DatagramSocket().use { sock ->
                    network?.bindSocket(sock)
                    sock.soTimeout = timeoutMs
                    sock.broadcast = true
                    val group = InetAddress.getByName("239.255.255.250")
                    sock.send(DatagramPacket(msearch, msearch.size, InetSocketAddress(group, 1900)))
                    log("SSDP auto-detect sweep (attempt ${attempt + 1}/$attempts)")

                    val buf = ByteArray(2048)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val resp = DatagramPacket(buf, buf.size)
                        try { sock.receive(resp) } catch (_: Exception) { break }
                        val text = String(resp.data, 0, resp.length)
                        val stOrNt = headerValue(text, "ST") ?: headerValue(text, "NT") ?: ""
                        if (stOrNt.isNotBlank() && services.add(stOrNt)) {
                            log("SSDP: $stOrNt @ ${headerValue(text, "LOCATION") ?: "(no location)"}")
                        }
                        // Some bodies only name the API in the device-description
                        // body, not the ST — scan the whole datagram for the token.
                        if (stOrNt.contains("ScalarWebAPI", true) || text.contains("ScalarWebAPI", true)) {
                            hasScalarWeb = true
                        }
                        if (stOrNt.contains("MediaServer", true) || stOrNt.contains("ContentDirectory", true)) {
                            hasMediaServer = true
                        }
                    }
                }
            } catch (e: Exception) {
                log("SSDP auto-detect error: ${e.message}")
            }
            // Stop early once we've positively identified the newer path (nothing
            // more precise to learn); otherwise a second sweep catches slow bodies.
            if (hasScalarWeb) return TransportProbe(true, hasMediaServer, services.toList())
        }
        return TransportProbe(hasScalarWeb, hasMediaServer, services.toList())
    }

    /**
     * GET + parse the device description at [locationUrl]; find the
     * ContentDirectory service and resolve its controlURL against URLBase (or the
     * location host). Returns null if the device has no ContentDirectory.
     */
    fun resolveContentDirectory(locationUrl: String): ContentDirectory? {
        val xml = httpGetText(locationUrl)
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            .newPullParser()
        parser.setInput(xml.reader())

        var friendlyName: String? = null
        var urlBase: String? = null
        // per-<service> scratch
        var svcType: String? = null
        var controlUrl: String? = null
        var cdType: String? = null
        var cdControl: String? = null
        var xpType: String? = null
        var xpControl: String? = null
        var text = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name.equals("service", true)) { svcType = null; controlUrl = null }
                    text = ""
                }
                XmlPullParser.TEXT -> text += parser.text ?: ""
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    when {
                        tag.endsWith("friendlyName", true) && friendlyName == null -> friendlyName = text.trim()
                        tag.equals("URLBase", true) -> urlBase = text.trim()
                        tag.endsWith("serviceType", true) -> svcType = text.trim()
                        tag.endsWith("controlURL", true) -> controlUrl = text.trim()
                        tag.equals("service", true) -> {
                            if (svcType?.contains("ContentDirectory", true) == true && controlUrl != null) {
                                cdType = svcType; cdControl = controlUrl
                            }
                            if (svcType?.contains("XPushList", true) == true && controlUrl != null) {
                                xpType = svcType; xpControl = controlUrl
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        val ctrl = cdControl ?: return null
        val absolute = resolveUrl(locationUrl, urlBase, ctrl)
        val xpAbs = xpControl?.let { resolveUrl(locationUrl, urlBase, it) }
        log("ContentDirectory: $absolute (name=$friendlyName)" +
            (if (xpAbs != null) " [XPushList present]" else ""))
        return ContentDirectory(
            absolute,
            cdType ?: "urn:schemas-upnp-org:service:ContentDirectory:1",
            friendlyName,
            xPushControlUrl = xpAbs,
            xPushType = xpType,
        )
    }

    /** Which resolution to pull, mirroring PlayMemories' "Image Size for Importing". */
    enum class ImportSize { ORIGINAL, TWO_M }

    /** Recursive BrowseDirectChildren from [startId] (the push root when known,
     *  else "0"), collecting JPEG items at the requested [size]. */
    fun browseStillsDlna(
        cd: ContentDirectory,
        startId: String = "0",
        size: ImportSize = ImportSize.ORIGINAL,
        max: Int = 300,
    ): List<RemoteContent> {
        val out = ArrayList<RemoteContent>()
        val queue = ArrayDeque<String>().apply { add(startId) }
        var guard = 0
        while (queue.isNotEmpty() && out.size < max && guard < 500) {
            guard++
            val objectId = queue.removeFirst()
            val didl = browseRaw(cd, objectId) ?: continue
            parseDidl(didl, containers = queue, items = out, size = size, max = max)
        }
        log("DLNA: found ${out.size} still(s) at ${if (size == ImportSize.ORIGINAL) "Original" else "2M"}.")
        return out.take(max)
    }

    // ── XPushList lifecycle (the A7 II "Send to Smartphone" completion handshake) ──
    // Without these the camera keeps its "connecting/transferring" spinner forever,
    // because it never learns a push client started and finished. Sequence:
    //   X_GetPushRoot → (browse that ObjectID) → X_TransferStart → per-file
    //   X_TransferProgress → X_TransferEnd(0). All plain SOAP; no GENA eventing.

    /** X_GetPushRoot → the ObjectID of the queued push container, or null. */
    fun xGetPushRoot(cd: ContentDirectory): String? {
        val url = cd.xPushControlUrl ?: return null
        val type = cd.xPushType ?: "urn:schemas-sony-com:service:XPushList:1"
        val resp = runCatching { xpCall(url, type, "X_GetPushRoot", "") }
            .getOrElse { log("X_GetPushRoot error: ${it.message}"); return null }
        val id = extractTag(resp, "ObjectID")
        log("X_GetPushRoot → ObjectID=${id ?: "(none)"}")
        return id?.takeIf { it.isNotBlank() }
    }

    fun xTransferStart(cd: ContentDirectory) {
        val url = cd.xPushControlUrl ?: return
        val type = cd.xPushType ?: "urn:schemas-sony-com:service:XPushList:1"
        runCatching { xpCall(url, type, "X_TransferStart", "") }
            .onSuccess { log("X_TransferStart ok") }
            .onFailure { log("X_TransferStart error: ${it.message}") }
    }

    fun xTransferProgress(cd: ContentDirectory, numTotal: Int, numTransferred: Int) {
        val url = cd.xPushControlUrl ?: return
        val type = cd.xPushType ?: "urn:schemas-sony-com:service:XPushList:1"
        val args = "<NumTotal>$numTotal</NumTotal><NumTransferd>$numTransferred</NumTransferd>"
        runCatching { xpCall(url, type, "X_TransferProgress", args) }
    }

    /** X_TransferEnd(ErrCode) — 0 = success. This is what dismisses the spinner. */
    fun xTransferEnd(cd: ContentDirectory, errCode: Int = 0) {
        val url = cd.xPushControlUrl ?: return
        val type = cd.xPushType ?: "urn:schemas-sony-com:service:XPushList:1"
        runCatching { xpCall(url, type, "X_TransferEnd", "<ErrCode>$errCode</ErrCode>") }
            .onSuccess { log("X_TransferEnd($errCode) ok — camera session closed") }
            .onFailure { log("X_TransferEnd error: ${it.message}") }
    }

    /** Build + POST an XPushList SOAP action; returns the raw response body. */
    private fun xpCall(controlUrl: String, serviceType: String, action: String, innerArgs: String): String {
        val soap = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:$action xmlns:u="$serviceType">$innerArgs</u:$action>""" +
            """</s:Body></s:Envelope>"""
        return httpPostSoap(controlUrl, "\"$serviceType#$action\"", soap)
    }

    /** First text of <tag>…</tag> (namespace-insensitive) in [xml], or null. */
    private fun extractTag(xml: String, tag: String): String? {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        p.setInput(xml.reader())
        var ev = p.eventType
        var buf = ""
        var capturing = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> if (p.name.endsWith(tag, true)) { capturing = true; buf = "" }
                XmlPullParser.TEXT -> if (capturing) buf += p.text ?: ""
                XmlPullParser.END_TAG -> if (p.name.endsWith(tag, true) && capturing) return buf.trim()
            }
            ev = p.next()
        }
        return null
    }

    /** One Browse SOAP call → the (unescaped) DIDL-Lite Result string, or null. */
    private fun browseRaw(cd: ContentDirectory, objectId: String): String? {
        val soap = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:Browse xmlns:u="${cd.serviceType}">""" +
            """<ObjectID>$objectId</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>""" +
            """<Filter>*</Filter><StartingIndex>0</StartingIndex>""" +
            """<RequestedCount>200</RequestedCount><SortCriteria></SortCriteria>""" +
            """</u:Browse></s:Body></s:Envelope>"""
        val resp = runCatching {
            httpPostSoap(cd.controlUrl, "\"${cd.serviceType}#Browse\"", soap)
        }.getOrElse { log("Browse($objectId) error: ${it.message}"); return null }
        // Extract the <Result>…</Result> text — XmlPullParser decodes the inner
        // entities so we get real DIDL-Lite XML back.
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        p.setInput(resp.reader())
        var ev = p.eventType
        var buf = ""
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.TEXT) buf = p.text ?: buf
            if (ev == XmlPullParser.END_TAG && p.name.endsWith("Result", true)) return buf
            if (ev == XmlPullParser.START_TAG && p.name.endsWith("Result", true)) buf = ""
            ev = p.next()
        }
        return null
    }

    /** One JPEG <res> candidate for an item. */
    private data class ResCand(val url: String, val pixels: Long, val bytes: Long, val profile: Int, val desc: String)

    /** Parse DIDL-Lite: enqueue child container ids, collect JPEG items, choosing
     *  the [size] the caller requested (Original = largest; 2M = the res nearest
     *  ~2 MP, which is what the A7 II labels "2M"). */
    private fun parseDidl(
        didl: String,
        containers: ArrayDeque<String>,
        items: MutableList<RemoteContent>,
        size: ImportSize,
        max: Int,
    ) {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        p.setInput(didl.reader())
        var ev = p.eventType
        var inItem = false
        var title = ""
        var cands = ArrayList<ResCand>()
        var text = ""
        var resProtocol = ""
        var resResolution = ""
        var resSize = 0L
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    when (p.name.lowercase()) {
                        "container" -> p.getAttributeValue(null, "id")?.let { containers.add(it) }
                        "item" -> { inItem = true; title = ""; cands = ArrayList() }
                        "res" -> {
                            resProtocol = p.getAttributeValue(null, "protocolInfo") ?: ""
                            resResolution = p.getAttributeValue(null, "resolution") ?: ""
                            resSize = p.getAttributeValue(null, "size")?.toLongOrNull() ?: 0L
                        }
                    }
                    text = ""
                }
                XmlPullParser.TEXT -> text += p.text ?: ""
                XmlPullParser.END_TAG -> {
                    val tag = p.name.lowercase()
                    when {
                        tag.endsWith("title") && inItem -> title = text.trim()
                        tag == "res" && inItem -> {
                            val url = text.trim()
                            val isJpeg = resProtocol.contains("image/jpeg", true) ||
                                url.endsWith(".jpg", true) || url.endsWith(".jpeg", true)
                            if (isJpeg && url.isNotBlank()) {
                                val px = resResolution.split("x", "X").let {
                                    if (it.size == 2) (it[0].trim().toLongOrNull() ?: 0L) *
                                        (it[1].trim().toLongOrNull() ?: 0L) else 0L
                                }
                                val pl = resProtocol.uppercase()
                                val profile = when {
                                    pl.contains("ORG") || pl.contains("LRG") -> 3
                                    pl.contains("_MED") || pl.contains("_SM") -> 2
                                    pl.contains("_TN") -> 0
                                    else -> 1
                                }
                                cands += ResCand(url, px, resSize,
                                    profile, resResolution.ifBlank { pl })
                            }
                        }
                        tag == "item" -> {
                            val chosen = chooseRes(cands, size)
                            if (chosen != null && items.size < max) {
                                val name = title.ifBlank { chosen.url.substringAfterLast('/') }
                                if (cands.size > 1)
                                    log("$name: offers [${cands.joinToString(", ") { it.desc }}] → ${chosen.desc}")
                                items.add(RemoteContent(name, chosen.url, chosen.url, isStill = true))
                            }
                            inItem = false
                        }
                    }
                }
            }
            ev = p.next()
        }
    }

    /** Pick the res matching the requested [size]. ORIGINAL = most pixels/bytes;
     *  TWO_M = the candidate whose pixel count is closest to ~2 MP but never a
     *  thumbnail. Falls back gracefully when the camera offers only one res. */
    private fun chooseRes(cands: List<ResCand>, size: ImportSize): ResCand? {
        if (cands.isEmpty()) return null
        val ranked = cands.sortedWith(
            compareByDescending<ResCand> { it.pixels }.thenByDescending { it.bytes }.thenByDescending { it.profile }
        )
        return when (size) {
            ImportSize.ORIGINAL -> ranked.first()
            ImportSize.TWO_M -> {
                val target = 2_000_000L
                // Prefer non-thumbnail candidates; pick the one nearest 2 MP.
                (cands.filter { it.profile > 0 }.ifEmpty { cands })
                    .minByOrNull { kotlin.math.abs((it.pixels.takeIf { p -> p > 0 } ?: target) - target) }
                    ?: ranked.first()
            }
        }
    }

    /** Resolve a (possibly relative) controlURL against URLBase or the location host. */
    private fun resolveUrl(locationUrl: String, urlBase: String?, path: String): String {
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) return path
        val base = urlBase?.takeIf { it.isNotBlank() } ?: run {
            val u = URL(locationUrl); "${u.protocol}://${u.host}:${if (u.port > 0) u.port else 80}"
        }
        return base.trimEnd('/') + "/" + path.trimStart('/')
    }

    /** Stream-download [url] into [sink]. Returns bytes written. */
    fun download(url: String, sink: OutputStream): Long {
        val conn = openConn(url)
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code for $url")
        }
        var total = 0L
        conn.inputStream.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                total += n
            }
        }
        conn.disconnect()
        return total
    }

    // ── HTTP plumbing (routed through the camera Network) ─────────────────────
    private fun openConn(urlStr: String): HttpURLConnection {
        val url = URL(urlStr)
        val conn = if (network != null) network.openConnection(url) else url.openConnection()
        return (conn as HttpURLConnection)
    }

    private fun httpGetText(urlStr: String): String {
        val conn = openConn(urlStr)
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.connect()
        return conn.inputStream.bufferedReader().use { it.readText() }
            .also { conn.disconnect() }
    }

    /** POST a UPnP SOAP action (ContentDirectory Browse). */
    private fun httpPostSoap(urlStr: String, soapAction: String, body: String): String {
        val conn = openConn(urlStr)
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", soapAction)
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            .also { conn.disconnect() }
    }

    private fun httpPostJson(urlStr: String, json: String): String {
        val conn = openConn(urlStr)
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(json.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            .also { conn.disconnect() }
    }
}
