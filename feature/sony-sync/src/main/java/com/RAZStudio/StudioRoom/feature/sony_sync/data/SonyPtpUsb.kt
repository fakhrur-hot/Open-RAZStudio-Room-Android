/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.data

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.RAZStudio.StudioRoom.feature.sony_sync.BuildConfig
import java.io.ByteArrayOutputStream

/**
 * Minimal Sony **PC Remote** PTP-over-USB client for the A7 II (and siblings).
 *
 * Reverse-engineered live from Imaging Edge Remote (see docs/SONY_PC_REMOTE.md).
 * The camera enumerates as PTP (USB class 06/01/01); we claim the still-image
 * interface and drive it over the bulk endpoints:
 *
 *   • live view   — GetObject(0x1009) on the fixed object handle 0xFFFFC002
 *   • all state   — GetAllDevicePropData (0x9209)
 *   • set value   — SetControlDeviceA (0x9205): CMD param1 = propCode, DATA = value
 *   • step / btn  — SetControlDeviceB (0x9207): CMD param1 = propCode, DATA = value
 *
 * All PTP containers are little-endian: len(u32) type(u16) code(u16) txid(u32) …
 *
 * NOT thread-safe: call every method from a single (IO) coroutine/thread. The
 * [SonyCameraRemoteController] owns one instance and serialises access.
 */
class SonyPtpUsb(private val log: (String) -> Unit = {}) {

    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var txId = 0

    val isOpen: Boolean get() = connection != null

    /**
     * Verbose PTP diagnostic log — per-op read traces, prop hex dumps, the full
     * parsed-prop list and the prop-change diff. Gated behind [BuildConfig.DEBUG]
     * so hardened/release builds stay quiet (these fire on every 250 ms poll).
     * Real faults (session/handshake/bulk failures) stay on the plain [log].
     */
    private fun dlog(msg: String) { if (BuildConfig.DEBUG) log(msg) }

    /** Claim the PTP interface of [device] (must already have USB permission). */
    fun open(usbManager: UsbManager, device: UsbDevice): Boolean {
        // Find the Still-Image (PTP) interface: class 6, subclass 1, protocol 1.
        var chosen: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == USB_CLASS_STILL_IMAGE) { chosen = intf; break }
        }
        if (chosen == null) { log("No PTP (still-image) interface on the camera."); return false }
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (e in 0 until chosen.endpointCount) {
            val ep = chosen.getEndpoint(e)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        if (epIn == null || epOut == null) { log("PTP interface has no bulk endpoints."); return false }
        val conn = usbManager.openDevice(device) ?: run { log("openDevice failed."); return false }
        if (!conn.claimInterface(chosen, true)) { log("claimInterface failed."); conn.close(); return false }
        connection = conn; iface = chosen; bulkIn = epIn; bulkOut = epOut; txId = 0
        log("PTP interface claimed (bulkIn=${epIn.address}, bulkOut=${epOut.address}).")
        return true
    }

    fun close() {
        runCatching { iface?.let { connection?.releaseInterface(it) } }
        runCatching { connection?.close() }
        connection = null; iface = null; bulkIn = null; bulkOut = null
    }

    // ── PTP session + Sony handshake ─────────────────────────────────────────

    /** OpenSession + Sony SDIO_Connect handshake. Returns true on success. */
    fun connectSession(): Boolean {
        if (!isOpen) return false
        // Clear stalls + drain any stale bytes so the handshake starts on a clean
        // pipe (Android's MTP service often probes the camera first, leaving data).
        resetPipes()
        Thread.sleep(300)   // let the camera's PTP state machine settle
        // OpenSession(sessionId=1)
        if (!command(OP_OPEN_SESSION, intArrayOf(1)).ok) { log("OpenSession failed."); return false }
        // Sony SDIO handshake (mirrors libgphoto2's Sony driver). CRITICAL:
        // SDIO_Connect (0x9201) is a device→host DATA-IN op — the camera returns
        // a data blob before its response. It MUST be read with readData(), not
        // command(): calling it as command+response leaves the unconsumed DATA
        // container in the pipe, desyncing every later read (responses come back
        // one container behind), so live-view GetObject times out and the bulk
        // endpoint wedges (blank canvas). GetExtDeviceInfo (0x9202) is data-in too.
        // Order matters — libgphoto2's Sony init is connect(1) → connect(2) →
        // GetExtDeviceInfo → connect(3). Putting GetExtDeviceInfo between phases
        // 1 and 2 (as before) leaves the camera in a half-initialised state: the
        // handshake "succeeds" but every later data-in op (0x9209 props, live
        // view GetObject) gets no data phase and times out. connect(3) MUST be
        // the last step, after GetExtDeviceInfo.
        readData(OP_SDIO_CONNECT, intArrayOf(1, 0, 0))            // connect phase 1
        readData(OP_SDIO_CONNECT, intArrayOf(2, 0, 0))            // connect phase 2
        readData(OP_SDIO_GET_EXT_DEVICE_INFO, intArrayOf(0x00C8)) // ext device info (version)
        readData(OP_SDIO_CONNECT, intArrayOf(3, 0, 0))            // connect phase 3
        // The A7 II needs a beat after connect(3) before device props populate;
        // prime GetAllDevicePropData (retry a few times) so the first poll isn't
        // empty and to confirm control ops now return their data phase.
        var primed = false
        for (i in 0 until 5) {
            if (readData(OP_GET_ALL_DEVICE_PROP_DATA, IntArray(0)) != null) { primed = true; break }
            Thread.sleep(150)
        }
        log("Sony PC-Remote session established (props ${if (primed) "ready" else "not yet"}).")
        return true
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * Live view → the JPEG frame bytes (or null). Confirmed against an Imaging
     * Edge USB capture: each frame is GetObjectInfo(0x1008) IMMEDIATELY followed
     * by GetObject(0x1009) on handle 0xFFFFC002 — a bare GetObject returns
     * nothing, the GetObjectInfo primes the frame. GetObject returns a ~100 KB
     * EXIF/JPEG. (The whole cycle IEM runs is 0x9209 → 0x1008 → 0x1009.)
     */
    fun getLiveViewJpeg(): ByteArray? {
        readData(OP_GET_OBJECT_INFO, intArrayOf(LIVEVIEW_HANDLE))  // primes the frame; result unused
        return fetchJpeg(LIVEVIEW_HANDLE)
    }

    /**
     * GetObject on Sony's "last captured image" handle (0xFFFFC001) → the
     * full-resolution EXIF/JPEG just shot (delivered when the camera's PC-Remote
     * "Save to PC" is on). Null if nothing captured / not delivered.
     *
     * Verified against an Imaging Edge USB capture: ObjectFormat 0x3801 (EXIF
     * JPEG), ~10 MB for the A7 II's 24 MP frame. IEM always issues GetObjectInfo
     * first, so we mirror that (some bodies won't return the object otherwise).
     */
    fun getCapturedJpeg(): ByteArray? {
        readData(OP_GET_OBJECT_INFO, intArrayOf(CAPTURED_HANDLE))   // mirror IEM; result unused
        return fetchJpeg(CAPTURED_HANDLE)
    }

    /**
     * Poll GetObjectInfo(0xFFFFC001) until the just-captured image is ready
     * (a big RAW/JPEG isn't exposed the instant the shutter fires), then pull it
     * with GetObject. IEM waits for an ObjectAdded event; polling ObjectInfo is
     * the simpler equivalent. Returns null if nothing appears within [maxWaitMs]
     * (e.g. the camera's Save Destination is card-only, so no object is exposed).
     */
    fun getCapturedJpegWhenReady(maxWaitMs: Long = 5000, stepMs: Long = 200): ByteArray? {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (readData(OP_GET_OBJECT_INFO, intArrayOf(CAPTURED_HANDLE)) != null) {
                val jpeg = fetchJpeg(CAPTURED_HANDLE)
                if (jpeg != null) return jpeg
            }
            Thread.sleep(stepMs)
        }
        return null
    }

    private fun fetchJpeg(handle: Int): ByteArray? {
        val payload = readData(OP_GET_OBJECT, intArrayOf(handle)) ?: return null
        // Sony may wrap the JPEG in a small header — locate the SOI (FFD8).
        val soi = indexOfMarker(payload, 0xFF.toByte(), 0xD8.toByte())
        if (soi < 0) return null
        return payload.copyOfRange(soi, payload.size)
    }

    /** GetAllDevicePropData → best-effort parse to propCode → current value (Long). */
    private var dumpedProps = false
    fun getAllDeviceProps(): Map<Int, Long>? {
        val data = readData(OP_GET_ALL_DEVICE_PROP_DATA, IntArray(0)) ?: return null
        if (!dumpedProps) {
            dumpedProps = true
            val n = minOf(data.size, 192)
            val sb = StringBuilder()
            for (i in 0 until n) { sb.append("%02x".format(data[i].toInt() and 0xFF)); if ((i and 1) == 1) sb.append(' ') }
            dlog("props dump ${data.size}B: $sb")
        }
        val map = runCatching { parseAllDeviceProps(data) }
            .onFailure { log("props parse threw: ${it.message}") }
            .getOrNull()
        if (map != null && !loggedMap) {
            loggedMap = true
            dlog("parsed ${map.size} props: " +
                map.entries.joinToString(" ") { "0x${it.key.toString(16)}=${it.value}" })
        }
        // Log any property that CHANGED since the last poll — this surfaces which
        // code reflects a live event (e.g. movie recording start/stop) so it can
        // be wired to real state instead of an optimistic UI toggle.
        if (map != null) {
            val prev = prevProps
            if (prev != null) {
                val diffs = map.filter { (k, v) -> prev[k] != v }
                if (diffs.isNotEmpty())
                    dlog("props changed: " + diffs.entries.joinToString(" ") {
                        "0x${it.key.toString(16)}:${prev[it.key]}->${it.value}"
                    })
            }
            prevProps = map
        }
        return map
    }
    private var loggedMap = false
    private var prevProps: Map<Int, Long>? = null

    // ── Writes (control) ─────────────────────────────────────────────────────

    /** Absolute set (DRO, ImageSize, WB, …) — SetControlDeviceA (0x9205). */
    fun setControlA(propCode: Int, value: ByteArray): Boolean =
        commandWithData(OP_SET_CONTROL_A, intArrayOf(propCode), value).ok

    /** Step / button (ISO/aperture/shutter step, AF/shutter/movie) — 0x9207. */
    fun setControlB(propCode: Int, value: ByteArray): Boolean =
        commandWithData(OP_SET_CONTROL_B, intArrayOf(propCode), value).ok

    // High-level helpers (see docs/SONY_PC_REMOTE.md for codes).
    fun stepIso(up: Boolean)      = setControlB(DPC_ISO,      le32(if (up) 1 else -1))
    fun stepAperture(up: Boolean) = setControlB(DPC_FNUMBER,  le16(if (up) 1 else -1))
    fun stepShutter(up: Boolean)  = setControlB(DPC_SHUTTER,  le16(if (up) 1 else -1))
    fun stepExposure(up: Boolean) = setControlB(DPC_EXPOSURE_BIAS, le16(if (up) 1 else -1))
    fun stepWhiteBalance(up: Boolean) = setControlB(DPC_WHITE_BALANCE, le16(if (up) 1 else -1))
    /** Absolute WB preset (0x9205). Used by the WB preset chips / custom hold. */
    fun setWhiteBalance(value: Int) = setControlA(DPC_WHITE_BALANCE, le16(value))
    /** Custom colour temperature in Kelvin (0xD20F), for the WB long-press. */
    fun setColorTemp(kelvin: Int)  = setControlA(DPC_COLOR_TEMP, le16(kelvin))
    /** WB fine-tune A↔B (amber/blue) and G↔M (green/magenta). Best-effort codes. */
    fun setWbAB(v: Int)            = setControlA(DPC_WB_AB, le16(v))
    fun setWbGM(v: Int)            = setControlA(DPC_WB_GM, le16(v))
    fun setDro(level: Int)        = setControlA(DPC_DRO, byteArrayOf((0x10 or level.coerceIn(0, 0x0F)).toByte()))

    private fun button(propCode: Int, press: Boolean) = setControlB(propCode, le16(if (press) 2 else 1))
    fun autofocus(press: Boolean) = button(DPC_AF, press)
    fun movieToggle() { button(DPC_MOVIE, true); Thread.sleep(120); button(DPC_MOVIE, false) }
    /** Full snap: AF↓ → shutter↓ → shutter↑ → AF↑ (the captured Imaging Edge order). */
    fun snap() {
        button(DPC_AF, true)
        button(DPC_CAPTURE, true)
        Thread.sleep(120)
        button(DPC_CAPTURE, false)
        button(DPC_AF, false)
    }

    // ── PTP container plumbing ───────────────────────────────────────────────

    private data class Resp(val code: Int, val ok: Boolean)

    /** Command + response, no data phase. */
    private fun command(op: Int, params: IntArray): Resp {
        val tid = nextTid()
        writeContainer(TYPE_COMMAND, op, tid, params, null)
        return readResponse(tid)
    }

    /** Command + host→device data phase + response. */
    private fun commandWithData(op: Int, params: IntArray, data: ByteArray): Resp {
        val tid = nextTid()
        writeContainer(TYPE_COMMAND, op, tid, params, null)
        writeContainer(TYPE_DATA, op, tid, IntArray(0), data)
        return readResponse(tid)
    }

    /** Command + device→host data phase + response → the data payload (or null). */
    private fun readData(op: Int, params: IntArray): ByteArray? {
        val tid = nextTid()
        val wrote = writeContainer(TYPE_COMMAND, op, tid, params, null)
        if (!wrote) { dlog("readData 0x${op.toString(16)} tid=$tid: write failed"); return null }
        val c = readContainer()
        if (c == null) {
            // Data-in read timed out — the camera is still holding the transaction
            // open (e.g. live-view GetObject on a body not streaming live view).
            // Abort it with a PTP Cancel so the pending/late data phase can't
            // desync the NEXT op (that cascade wedged the whole poll before).
            dlog("readData 0x${op.toString(16)} tid=$tid: no container (read fail)")
            cancelTransaction(tid)
            return null
        }
        val (type, code, payload) = c
        if (type != TYPE_DATA) {
            dlog("readData 0x${op.toString(16)} tid=$tid: got type=$type code=0x${code.toString(16)} (no data)")
            return null
        }
        readResponse(tid)  // consume the trailing response
        dlog("readData 0x${op.toString(16)} tid=$tid: ${payload.size}B ok")
        return payload
    }

    private fun writeContainer(type: Int, code: Int, tid: Int, params: IntArray, data: ByteArray?): Boolean {
        val bodyLen = (data?.size ?: (params.size * 4))
        val total = 12 + bodyLen
        val buf = ByteArray(total)
        putLe32(buf, 0, total)
        putLe16(buf, 4, type)
        putLe16(buf, 6, code)
        putLe32(buf, 8, tid)
        if (data != null) System.arraycopy(data, 0, buf, 12, data.size)
        else for (i in params.indices) putLe32(buf, 12 + i * 4, params[i])
        return writeBulk(buf)
    }

    /** Reads one PTP container from bulk-IN → (type, code, payload-after-header). */
    // Big reusable read buffer — a whole live-view frame (~96 KB) or a big data
    // chunk comes back in ONE bulkTransfer. The old 512-byte first read + 16 KB
    // chunks meant ~190 round-trips per frame / ~1500 for a 25 MB photo, which
    // stalled/desynced on a shaky bus. (Mirrors argallo/sony-camera-android's
    // 256 KB liveview / 512 KB transfer buffers.)
    private val readBuf = ByteArray(READ_BUF_SIZE)
    private fun readContainer(): Triple<Int, Int, ByteArray>? {
        val ep = bulkIn ?: return null
        val conn = connection ?: return null
        var n = conn.bulkTransfer(ep, readBuf, readBuf.size, IO_TIMEOUT_MS)
        if (n < 12) {
            if (n < 0) { log("bulk read timeout/err ($n)"); clearHalt(ep) }
            return null
        }
        val total = readLe32(readBuf, 0).toInt().coerceIn(12, MAX_CONTAINER)
        val type = readLe16(readBuf, 4)
        val code = readLe16(readBuf, 6)
        val out = ByteArrayOutputStream(total)
        out.write(readBuf, 12, n - 12)               // payload after the 12-byte header
        var got = n
        while (got < total) {
            n = conn.bulkTransfer(ep, readBuf, readBuf.size, IO_TIMEOUT_MS)
            if (n <= 0) break
            out.write(readBuf, 0, n)
            got += n
        }
        return Triple(type, code, out.toByteArray())
    }

    private fun readResponse(expectTid: Int): Resp {
        val c = readContainer() ?: return Resp(0, false)
        // c.first=type(should be 3), c.second=response code.
        val ok = c.second == RESP_OK
        if (!ok) dlog("PTP response 0x${c.second.toString(16)} (tid=$expectTid)")
        return Resp(c.second, ok)
    }

    private fun writeBulk(buf: ByteArray): Boolean {
        val ep = bulkOut ?: return false
        val conn = connection ?: return false
        var off = 0
        while (off < buf.size) {
            val len = (buf.size - off).coerceAtMost(16384)
            val slice = if (off == 0 && len == buf.size) buf else buf.copyOfRange(off, off + len)
            val n = conn.bulkTransfer(ep, slice, len, IO_TIMEOUT_MS)
            if (n <= 0) { log("bulk write failed ($n)"); clearHalt(ep); return false }
            off += n
        }
        return true
    }

    /**
     * Clear a STALL/HALT on a bulk endpoint via the standard control transfer
     * CLEAR_FEATURE(ENDPOINT_HALT) — bmRequestType=0x02 (host→device, standard,
     * endpoint), bRequest=1, wValue=0 (HALT selector), wIndex=endpoint address.
     * Without this, one stalled transfer (e.g. the camera rejecting a live-view
     * GetObject when PC-Remote live view is off) leaves every later transfer
     * returning -1 for the whole session — a wedged pipe. Best-effort.
     */
    private fun clearHalt(ep: UsbEndpoint) {
        val conn = connection ?: return
        runCatching { conn.controlTransfer(0x02, 1, 0, ep.address, null, 0, 200) }
    }

    /**
     * PTP "Cancel Transaction" class control request (bRequest=0x64) — tells the
     * camera to abort an in-flight transaction whose data phase we gave up
     * reading (a live-view GetObject on a body that isn't streaming). Without it
     * the camera's late/pending data desyncs the next op and wedges the pipe.
     * bmRequestType=0x21 (host→device, class, interface); data = cancel code
     * 0x4001 (LE u16) + transaction id (LE u32). Best-effort; also clear both
     * bulk endpoints so the next transfer starts clean.
     */
    private fun cancelTransaction(tid: Int) {
        val conn = connection ?: return
        val data = ByteArray(6)
        putLe16(data, 0, 0x4001)
        putLe32(data, 2, tid)
        runCatching { conn.controlTransfer(0x21, 0x64, 0, iface?.id ?: 0, data, 6, 500) }
        resetPipes()
    }

    /**
     * Clear-HALT both bulk endpoints and DRAIN any stale bytes left in bulk-IN
     * (leftovers from the Android MTP service probing the camera, or a
     * previously-abandoned/timed-out data phase). Without this a single stray
     * container desyncs every later read → wedged pipe. Mirrors the drain step in
     * argallo/sony-camera-android. Call before the handshake and after a cancel.
     */
    private fun resetPipes() {
        bulkOut?.let { clearHalt(it) }
        bulkIn?.let { clearHalt(it) }
        val ep = bulkIn ?: return
        val conn = connection ?: return
        val buf = ByteArray(4096)
        var guard = 0
        while (guard++ < 64) {
            val r = runCatching { conn.bulkTransfer(ep, buf, buf.size, 100) }.getOrDefault(-1)
            if (r <= 0) break
        }
    }

    private fun nextTid(): Int = (++txId)

    // ── GetAllDevicePropData parser (best-effort; refine on-device) ───────────
    // Sony layout: u32 count, u32 (reserved), then per property:
    //   code(u16) dataType(u16) getSet(u8) [factoryDefault DPV] [current DPV]
    //   formFlag(u8) [form...]. We only keep the current value per code.
    private fun parseAllDeviceProps(d: ByteArray): Map<Int, Long> {
        val out = LinkedHashMap<Int, Long>()
        if (d.size < 8) return out
        val count = readLe32(d, 0).toInt()
        var o = 8
        var i = 0
        while (i < count && o + 5 <= d.size) {
            val code = readLe16(d, o); o += 2
            val dtype = readLe16(d, o); o += 2
            o += 1                                   // getSet
            o += 1                                   // Sony-specific isEnabled byte
            val sz = dpvSize(dtype)
            if (sz < 0) break                        // variable/unknown → stop (avoid mis-align)
            if (o + sz * 2 + 1 > d.size) break
            o += sz                                  // factory default
            val current = readValue(d, o, dtype); o += sz
            val formFlag = d[o].toInt() and 0xFF; o += 1
            o += formSize(d, o, dtype, formFlag)     // skip form
            out[code] = current
            i++
        }
        return out
    }

    private fun dpvSize(dtype: Int): Int = when (dtype) {
        0x0001, 0x0002 -> 1     // INT8 / UINT8
        0x0003, 0x0004 -> 2     // INT16 / UINT16
        0x0005, 0x0006 -> 4     // INT32 / UINT32
        0x0007, 0x0008 -> 8     // INT64 / UINT64
        else -> -1              // strings / arrays → bail
    }

    private fun readValue(d: ByteArray, o: Int, dtype: Int): Long = when (dtype) {
        0x0001 -> d[o].toLong()                            // INT8  (signed)
        0x0002 -> d[o].toLong() and 0xFF                   // UINT8
        0x0003 -> readLe16(d, o).toShort().toLong()        // INT16 (signed, e.g. EV)
        0x0004 -> readLe16(d, o).toLong()                  // UINT16
        0x0005 -> readLe32(d, o).toInt().toLong()          // INT32 (signed)
        0x0006 -> readLe32(d, o)                           // UINT32
        0x0007, 0x0008 -> readLe32(d, o) or (readLe32(d, o + 4) shl 32)
        else -> 0L
    }

    private fun formSize(d: ByteArray, o: Int, dtype: Int, formFlag: Int): Int {
        val sz = dpvSize(dtype); if (sz < 0) return 0
        return when (formFlag) {
            0x01 -> sz * 3                                   // range: min,max,step
            0x02 -> {                                        // enum: u16 count + values
                if (o + 2 > d.size) 0 else 2 + readLe16(d, o) * sz
            }
            else -> 0
        }
    }

    private fun indexOfMarker(a: ByteArray, b0: Byte, b1: Byte): Int {
        var i = 0
        while (i < a.size - 1) { if (a[i] == b0 && a[i + 1] == b1) return i; i++ }
        return -1
    }

    // ── LE helpers ───────────────────────────────────────────────────────────
    private fun putLe16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }
    private fun readLe16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun readLe32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    companion object {
        private const val USB_CLASS_STILL_IMAGE = 6
        // With the big read buffer, normal reads return in ms regardless of this
        // cap, so a generous timeout only affects a genuinely hung/dead read (a
        // 25 MB photo pull needs headroom). Matches the proven impl's ~5s.
        private const val IO_TIMEOUT_MS = 4000
        // 256 KB read buffer: one bulkTransfer grabs a whole ~96 KB live-view
        // frame (and large data chunks) — far fewer round-trips than 512B/16 KB.
        private const val READ_BUF_SIZE = 256 * 1024
        // Sanity cap on a container's declared length (guards a corrupt header).
        private const val MAX_CONTAINER = 64 * 1024 * 1024

        // Container types
        private const val TYPE_COMMAND = 1
        private const val TYPE_DATA = 2
        private const val RESP_OK = 0x2001

        // Operations
        private const val OP_OPEN_SESSION = 0x1002
        private const val OP_GET_OBJECT_INFO = 0x1008
        private const val OP_GET_OBJECT = 0x1009
        private const val OP_SDIO_CONNECT = 0x9201
        private const val OP_SDIO_GET_EXT_DEVICE_INFO = 0x9202
        private const val OP_SET_CONTROL_A = 0x9205
        private const val OP_SET_CONTROL_B = 0x9207
        private const val OP_GET_ALL_DEVICE_PROP_DATA = 0x9209

        const val LIVEVIEW_HANDLE = -0x3FFE  // 0xFFFFC002 as a signed Int
        const val CAPTURED_HANDLE = -0x3FFF  // 0xFFFFC001 — last captured image

        // Device property codes (confirmed from capture — see docs/SONY_PC_REMOTE.md)
        const val DPC_IMAGE_SIZE = 0x5004
        const val DPC_WHITE_BALANCE = 0x5005
        // Colour temperature (Kelvin) for custom WB — 0xD20F on the A7 II family
        // (from the GetAllDevicePropData dump); verify the step granularity on-device.
        const val DPC_COLOR_TEMP = 0xD20F
        // WB fine-tune amber/blue + green/magenta (best-effort codes from the dump).
        const val DPC_WB_AB = 0xD210
        const val DPC_WB_GM = 0xD211
        const val DPC_FNUMBER = 0x5007
        const val DPC_EXPOSURE_PROGRAM = 0x500E
        const val DPC_EXPOSURE_BIAS = 0x5010
        const val DPC_DRO = 0xD201
        const val DPC_SHUTTER = 0xD20D
        const val DPC_ISO = 0xD21E
        const val DPC_AF = 0xD2C1
        const val DPC_CAPTURE = 0xD2C2
        const val DPC_MOVIE = 0xD2C8
        // Focus mode — STANDARD PTP DevicePropCode (0x500A), safe to read. If the
        // A7 II exposes it in the poll it drives the read-only focus-mode readout.
        const val DPC_FOCUS_MODE = 0x500A

        // ── UNVERIFIED — needs a live capture before use ─────────────────────
        // Movie recording status: the code that flips when recording starts/stops.
        // NULL until a `props changed` diff on movie start/stop identifies it — kept
        // null so recordingStatus is never populated and the UI shows no fake REC.
        // TODO(capture): set to the confirmed Int (e.g. 0xD2C7) then verify the
        // recording sentinel value in SonyCameraState.isRecording.
        val DPC_MOVIE_STATUS: Int? = null
        // Aspect ratio + still capture format (RAW/JPEG). The 0xD213 / 0xD222 codes
        // circulating for these are guesses that CONFLICT with this app's existing
        // D2xx map (0xD211 is WB_GM here, not aspect), so wiring write-controls to
        // them could change the WRONG setting on the body. Left unwired until a
        // capture confirms them; fold into the same session as WB + movie status.
        // TODO(capture): confirm codes, then add read + guarded stepper controls.
    }
}
