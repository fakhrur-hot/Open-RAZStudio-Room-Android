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

/**
 * PTP/IP wire-format constants for Canon EOS bodies.
 *
 * All fields on the wire are little-endian unless noted. Strings are UTF-16LE
 * with a mandatory U+0000 terminator. See claude advanced plan.md §4 for the
 * full wire-format reference.
 */
internal object PtpIpConstants {

    /** PTP/IP fixed TCP port on Canon EOS bodies. */
    const val PORT: Int = 15740

    /** Protocol version reported in INIT_COMMAND_REQUEST (1.0). */
    const val PROTOCOL_VERSION: Int = 0x00010000

    // ---------- Packet types (4-byte LE field after length) ----------

    const val INIT_COMMAND_REQUEST: Int = 0x00000001
    const val INIT_COMMAND_ACK: Int = 0x00000002
    const val INIT_EVENT_REQUEST: Int = 0x00000003
    const val INIT_EVENT_ACK: Int = 0x00000004
    const val INIT_FAIL: Int = 0x00000005
    const val OPERATION_REQUEST: Int = 0x00000006
    const val OPERATION_RESPONSE: Int = 0x00000007
    const val EVENT: Int = 0x00000008
    const val START_DATA_PACKET: Int = 0x00000009
    const val DATA_PACKET: Int = 0x0000000A
    const val CANCEL_TRANSACTION: Int = 0x0000000B
    const val END_DATA_PACKET: Int = 0x0000000C
    const val PING: Int = 0x0000000D
    const val PONG: Int = 0x0000000E

    // ---------- Standard PTP operation codes ----------

    const val OC_GET_DEVICE_INFO: Int = 0x1001
    const val OC_OPEN_SESSION: Int = 0x1002
    const val OC_CLOSE_SESSION: Int = 0x1003
    const val OC_GET_STORAGE_IDS: Int = 0x1004
    const val OC_GET_OBJECT_HANDLES: Int = 0x1007
    const val OC_GET_OBJECT_INFO: Int = 0x1008
    const val OC_GET_OBJECT: Int = 0x1009
    const val OC_DELETE_OBJECT: Int = 0x100B

    /**
     * GetDevicePropValue — cheap data-in PTP transaction used as a Wi-Fi
     * keep-alive heartbeat. Older Canon DSLR firmware (DIGIC 5+ era, 6D and
     * peers) aggressively powers down the Wi-Fi radio after a few seconds of
     * silence, dropping the active TCP connection. Polling a property at
     * 3–5 s intervals keeps the radio in the high-power transmission state.
     */
    const val OC_GET_DEVICE_PROP_VALUE: Int = 0x1015

    /**
     * Standard PTP `GetDevicePropDesc` (0x1014). Returns the full descriptor
     * for a property: current value, factory default, get/set permission,
     * form-flag (None / Range / Enum), and the corresponding range or
     * enumeration table. Used to populate the chip dropdowns in the live
     * shooting screen — every value the chip lets the user pick is one the
     * camera actually advertises right now (ISO list shrinks/grows with
     * the mode dial; Av list depends on the lens, etc.).
     *
     * EOS bodies populate this for vendor DPCs too — the same descriptor
     * format is reused.
     */
    const val OC_GET_DEVICE_PROP_DESC: Int = 0x1014

    /**
     * Standard PTP device property: battery level. Implemented by every PTP
     * device including all EOS bodies. Cheap to query (1-byte response) and
     * stable across firmware generations.
     */
    const val DPC_BATTERY_LEVEL: Int = 0x5001

    /**
     * Canon-vendor device property: EOS battery status. Preferred over the
     * standard [DPC_BATTERY_LEVEL] for DIGIC 5+/6-era bodies (6D, 5D Mk III,
     * 7D Mk II, 70D, etc.) because their firmware populates this reliably
     * while the standard PTP property is sometimes empty or rejected. Newer
     * R-series bodies accept both.
     *
     * Used as the keep-alive heartbeat target by [CanonWifiClient]
     * — cheap data-in operation that exercises the radio without disturbing
     * camera state.
     */
    const val DPC_EOS_BATTERY_STATUS: Int = 0xD013

    // ---------- Canon EOS vendor operation codes (mandatory for events) ----------

    /**
     * Switch the camera into PC-tether mode. Param=1 to enter, 0 to leave.
     * Without this the event firehose stays closed even after OpenSession.
     */
    const val OC_EOS_SET_REMOTE_MODE: Int = 0x9114

    /**
     * Enable the event channel firehose. Param=1 to enable, 0 to disable.
     * Without this the camera never pushes shutter events.
     */
    const val OC_EOS_SET_EVENT_MODE: Int = 0x9115

    /**
     * Poll the EOS event queue. Returns a data-in blob containing zero or
     * more variable-length event records, terminated by `(size=8, code=0)`.
     *
     * EOS-specific shutter events (EOS_ObjectAddedEx, EOS_ObjectAddedEx64,
     * etc.) arrive via this opcode's data phase — **not** as PTP/IP EVENT
     * packets on the event socket. The event socket carries standard PTP
     * events (0x4002 ObjectAdded, 0x4009 StoreFull, …) only.
     */
    const val OC_EOS_GET_EVENT: Int = 0x9116

    /**
     * Report the connected PC's hard-disk capacity to the camera, completing
     * the legacy-body pairing handshake. Three params:
     *   p1 = inHDD (0)
     *   p2 = inLength — free space in MB; pass a generous fake value (e.g. 4 GB)
     *   p3 = inReset (0 = new connection)
     *
     * Pre-EOS-R bodies (6D, 5D-III, 7D, 60D…) sit in "pairing in progress" on
     * the camera LCD after OpenSession + SetRemoteMode + SetEventMode and
     * won't transition to a usable session until they receive this opcode.
     * EOS Utility's EDSDK exposes it as `DS_PCHDDCapacity`. Sending fails
     * gracefully on R-series bodies (returns OperationNotSupported but the
     * session stays alive).
     */
    const val OC_EOS_PC_HDD_CAPACITY: Int = 0x911A

    /**
     * EOS_KeepDeviceOn (0x911D). Canon-vendor keepalive opcode — explicit
     * "I'm still here, don't time out" ping. Unlike PCHDDCapacity which
     * legacy 6D firmware doesn't recognise (replies with OperationNotSupported
     * AND resets its idle-disconnect timer to "not real work"), this opcode
     * was specifically designed for EOS firmware as a session keepalive
     * and is in every Canon EDSDK from EOS 5D-III onwards.
     *
     * No params, no data phase, OK response. Sending it every <30 s resets
     * the camera's internal "client idle, disconnect" timer that otherwise
     * closes the PTP/IP socket with a TCP FIN at the 30-second mark.
     * camlib exposes it as `ptp_eos_ping()`.
     */
    const val OC_EOS_KEEP_DEVICE_ON: Int = 0x911D

    /**
     * EOS_GetDeviceInfoEx (0x9108). Canon-vendor "extended device info"
     * data-in operation. Real EOS Utility 3 sends this right after
     * pairing completes — the 6D uses the call as a "host is engaged"
     * marker and tears down the session with Err12 within ~5 s if it's
     * missing.
     *
     * Wire: dataPhase=DATA_IN, no params. The response payload is the
     * camera's extended device-info blob (we don't need to parse it).
     */
    const val OC_EOS_GET_DEVICE_INFO_EX: Int = 0x9108

    /**
     * EOS_SetDevicePropValueEx (0x9110). Canon-vendor property writer.
     * Used post-pairing to advertise an owner-name string to the camera
     * (real EOS Utility writes `DPC_CANON_EOS_Owner`). The 6D doesn't
     * validate the value but expects SOMETHING written within a few
     * seconds of pairing or it tears down the session.
     *
     * Wire: dataPhase=DATA_OUT, no operation params. The body is
     * `uint32 totalLength + uint32 dpc + utf16le value + 0x0000`.
     */
    const val OC_EOS_SET_DEV_PROP_VALUE_EX: Int = 0x9110

    /** Canon EOS device property code for the host owner name string. */
    const val DPC_CANON_EOS_OWNER: Int = 0xD115

    /**
     * EOS_SetUILock (0x911B). Locks the camera's physical UI so the host
     * can drive it remotely without the user accidentally interrupting.
     * Real EOS Utility sends this right after the post-pairing handshake
     * — and **on the 6D it's a prerequisite for `EOS_GetPartialObject`**:
     * the firmware silently ignores file-byte reads until the UI is
     * locked. No params, no data phase.
     *
     * Reversed by [OC_EOS_RESET_UI_LOCK] (0x911C) on disconnect.
     */
    const val OC_EOS_SET_UI_LOCK: Int = 0x911B
    const val OC_EOS_RESET_UI_LOCK: Int = 0x911C

    /**
     * Standard PTP `GetPartialObject` (0x101B). Three params:
     * (objectHandle, offset, maxBytes). Data-in returns up to
     * maxBytes from the camera-side file.
     *
     * **This is what libgphoto2 / Canon EDSDK actually use to
     * download files from EOS bodies over PTP/IP** — NOT the
     * Canon-vendor `EOS_GetPartialObject (0x9107)` we tried first.
     * The 0x9107 variant is silently ignored by 6D firmware in
     * Wi-Fi mode; 0x101B is honoured.
     *
     * Each chunk MUST be followed by an [OC_EOS_TRANSFER_COMPLETE]
     * after the final byte — without that terminator the camera
     * fires Err12 "Connection target not found" within seconds.
     */
    const val OC_GET_PARTIAL_OBJECT: Int = 0x101B

    /**
     * EOS_TransferComplete (0x9117). Single param: object handle.
     * Mandatory terminator after a chained [OC_GET_PARTIAL_OBJECT]
     * download loop completes — tells the 6D's firmware "host has
     * finished pulling this file" and prevents the Err12 timeout.
     */
    const val OC_EOS_TRANSFER_COMPLETE: Int = 0x9117

    /**
     * EOS_CancelTransfer (0x9118). Single param: object handle.
     * Host-initiated cancel for a partial-object download mid-flight.
     * Stops any further [OC_EOS_GET_PARTIAL_OBJECT] for that handle
     * and tells the camera to free its transfer state. Crucially:
     * after [OC_EOS_CANCEL_TRANSFER], do NOT send
     * [OC_EOS_TRANSFER_COMPLETE] for the same handle — that's the
     * "complete" terminator and the camera will Err12 if it sees
     * both.
     *
     * Distinct from 0xC18F EOS_RequestCancelTransfer, which is the
     * EVENT the camera sends to the host (e.g. user pressed shutter
     * mid-transfer).
     */
    const val OC_EOS_CANCEL_TRANSFER: Int = 0x9118

    /**
     * Canon EOS chunked-download primitive.
     *
     * Three params: handle, offset, length. Returns data-in chunk of up to
     * `length` bytes from the object identified by `handle` starting at
     * `offset`. EOS Utility uses this in preference to the standard
     * [OC_GET_OBJECT] (0x1009) because the legacy 6D / 5D-III firmware
     * times out mid-transfer for files >50 MB when streamed as a single
     * GetObject response. Looping 1 MB chunks reads the same image more
     * reliably and lets the host show byte-accurate progress.
     *
     * From PC_EOSUTILITY2.pcapng frame 84: chunk size = 0xF0000 (≈ 1 MB).
     */
    const val OC_EOS_GET_PARTIAL_OBJECT: Int = 0x9107

    /** Chunk size EOS Utility uses for 0x9107 (1 MB minus a few bytes). */
    const val EOS_PARTIAL_CHUNK_BYTES: Int = 0x000F_F000  // 1,044,480 bytes

    /**
     * EOS_GetStorageIDs (0x9101). Returns a uint32 array of available storage
     * IDs. The 6D in smartphone mode exposes one CF / SD slot per active card.
     * No params, data-in.
     */
    const val OC_EOS_GET_STORAGE_IDS: Int = 0x9101

    /**
     * EOS_GetStorageInfo (0x9102). Returns per-storage capacity and label.
     * One param: storageId. Data-in. We use this for the "free / total" hint
     * in the UI and to filter out empty storage slots.
     */
    const val OC_EOS_GET_STORAGE_INFO: Int = 0x9102

    /**
     * EOS_GetObjectInfoEx (0x9109). Canon-vendor bulk metadata enumeration.
     * Returns metadata for every image on a storage in a single data-in
     * response. EU calls it once per storage to populate the image browser.
     *
     * Note: Standard PTP GetObjectHandles (0x1007) and GetObjectInfo (0x1008)
     * already declared above in the "Standard PTP operation codes" section.
     */
    const val OC_EOS_GET_OBJECT_INFO_EX: Int = 0x9109

    /**
     * EOS_SetRequestOLCInfoGroup (0x913D). Tells the camera which event
     * groups to deliver via GetEvent. Real EOS Utility 2 sends this
     * with `0x0fff` before any storage-listing opcode. Without it the
     * 6D silently times out on 0x9109 — verified from
     * PC_EOSUTILITY.pcapng frame 1357.
     */
    const val OC_EOS_SET_REQUEST_OLC_INFO_GROUP: Int = 0x913D

    /**
     * EOS_GetCameraSupport (0x913F). Three uint32 params:
     *   (supportKind, modelId?, packedFeatureTag)
     * Documented in libgphoto2 ptp.h:1563 with no call site — gphoto
     * never issues it. But real EOS Utility 2 sends it TWICE between
     * the storage prep and the first 0x9109 listing call, and the
     * 6D's Wi-Fi firmware silently drops 0x9109 if it isn't called.
     * Param patterns verified from PC_EOSUTILITY.pcapng frames 1374
     * & 1411:
     *   1st: (0x01000000, 0x00000110, 0x31210000)
     *   2nd: (0x01000000, 0x00000210, 0x31210007)
     */
    const val OC_EOS_GET_CAMERA_SUPPORT: Int = 0x913F

    /**
     * EOS_GetThumbEx (0x910A). Canon's thumbnail/preview fetcher.
     * Two uint32 params: (objectHandle, maxBytes). Returns the
     * embedded JPEG thumbnail (typically 160×120 or 1620×1080
     * "QuickPreview") inline. ~200 KB per fetch on the 6D.
     *
     * This is what EOS Utility 2 uses to populate its image browser
     * thumbnails — verified from PC_EOSUTILITY_Select-and-Download.pcapng
     * frames 160/251/339/425/509/592 (handles iterate descending).
     * The full-resolution download uses EOS_GetPartialObject (0x9107).
     */
    const val OC_EOS_GET_THUMB_EX: Int = 0x910A

    /**
     * EOS_GetCTGInfo (0x9135). "Catalog/category info" — prepares the
     * camera-side thumbnail cache for a given folder. Real EOS Utility
     * 2 calls this with `(storageId, folderHandle, 3, 0x2000)` just
     * before iterating 0x910A thumbnails for each child. Without it
     * the 6D returns only the EXIF-only thumb (~16 KB) instead of the
     * full QuickPreview (~95 KB). Pcap-verified at
     * PC_EOSUTILITY_Select-and-Download.pcapng frame 151.
     */
    const val OC_EOS_GET_CTG_INFO: Int = 0x9135

    // ───────────────── Live Remote Shooting opcodes ─────────────────────
    // Verified against libgphoto2 master `camlibs/ptp2/ptp.h` + `library.c:6094`,
    // path branch `if (ptp_operation_issupported(0x9128) && !is_canon_eos_m)`,
    // documented as "verified with a 5Dm2, 5Ds and R8". The 5Dm2 is the
    // immediate contemporary of the 6D (same DIGIC-5 firmware family), so
    // this path is the canonical one for our target body.

    /**
     * EOS_RemoteReleaseOn (0x9128). Two uint32 params: `(state, afFlag)`.
     *   state=1, afFlag=0 → half-press, AF enabled (request focus lock)
     *   state=2, afFlag=0 → full-press, fire shutter
     * Paired with [OC_EOS_REMOTE_RELEASE_OFF] (0x9129) to release. No
     * data phase. The 5Dm2 takes up to ~8 s to ack the full-press with
     * a slow lens; budget the command-channel timeout accordingly.
     */
    const val OC_EOS_REMOTE_RELEASE_ON: Int = 0x9128

    /**
     * EOS_RemoteReleaseOff (0x9129). One uint32 param: `state`. Mirrors
     * [OC_EOS_REMOTE_RELEASE_ON]:
     *   state=2 → release the full-press first
     *   state=1 → then release the half-press
     * Must always be paired with the corresponding On call; leaving the
     * camera half-pressed prevents the next capture.
     */
    const val OC_EOS_REMOTE_RELEASE_OFF: Int = 0x9129

    /**
     * EOS_DoAf (0x9154). Standalone autofocus trigger for live view —
     * does NOT take a shot. No params, no data phase. Use for the
     * Live Remote Shooting AF-On button.
     */
    const val OC_EOS_DO_AF: Int = 0x9154

    /**
     * EOS_AfCancel (0x9160). Cancels an in-flight [OC_EOS_DO_AF].
     * Emitted on LV teardown and whenever the user releases AF-On.
     * No params, no data phase.
     */
    const val OC_EOS_AF_CANCEL: Int = 0x9160

    /**
     * EOS_DriveLens (0x9155). Single uint32 param encodes a manual-focus
     * step. The high bit `0x8000` is the direction (set = "far", clear
     * = "near"); the low 3 bits are step magnitude:
     *   0x0001 / 0x0002 / 0x0003 — near small / medium / large
     *   0x8001 / 0x8002 / 0x8003 — far  small / medium / large
     * No data phase. Matches Canon EDSDK's `EvfDriveLens_{Near,Far}{1,2,3}`
     * (verified against dukus/digiCamControl `CanonSDKBase.cs`).
     */
    const val OC_EOS_DRIVE_LENS: Int = 0x9155

    // Historical note: an earlier slice tried `0x9156 SET_LIVE_AF_FRAME`
    // as the FlexiZone-AF positioning opcode. The 6D firmware returns
    // `0x2005 OperationNotSupported` for it. The correct path is
    // [OC_EOS_SET_LV_AF_FRAME_PROP] (0x915A), confirmed against Magic
    // Lantern 6D source.

    /**
     * EOS PTP handler `0x915A` — writes the `PROP_LV_AFFRAME`
     * (0x80050007) property which controls the FlexiZone-AF rectangle
     * in Live View. Source: Magic Lantern 6D platform code,
     * `src/property.h:95` —
     *
     *   `#define PROP_LV_AFFRAME 0x80050007 // called by ptp handler 915a`
     *
     * Wire shape (DATA_OUT phase): opcode params `[propertyId,
     * payloadLength]`, body = full `aff[]` int array as 4-byte LE
     * integers. The first six entries are documented:
     *   aff[0] sensor native width   (e.g. 5472 on the 6D)
     *   aff[1] sensor native height  (e.g. 3648)
     *   aff[2] AF box X (sensor coords)
     *   aff[3] AF box Y
     *   aff[4] AF box W
     *   aff[5] AF box H
     * Entries beyond [5] are reserved/cam-specific — Magic Lantern
     * round-trips them unmodified via `memcpy(aff, afframe, sizeof(aff))`.
     *
     * Only effective when [DPC_EOS_AF_METHOD] is FlexiZone (0x00).
     */
    const val OC_EOS_SET_LV_AF_FRAME_PROP: Int = 0x915A

    /**
     * Canon-internal property ID for the Live View AF frame, written
     * via [OC_EOS_SET_LV_AF_FRAME_PROP].
     */
    // 0x80050007 is > Int.MAX_VALUE, so write it as a negative Int
    // (sign-extended). The wire is `writeIntLe` which sends 4 bytes
    // regardless of sign — the camera reads them back as uint32.
    const val PROP_LV_AFFRAME: Int = 0x80050007.toInt()

    // ──────────── EOS 6D camera-physics constants ──────────────────
    //
    // Single source of truth — both [CanonWifiClient.setLvAfFrameProp]
    // and [CanonSyncRepository.setLiveAfPoint] reach here for sensor
    // dimensions and AF-box defaults. Previously duplicated in both
    // files which made adding 5D-III / 7D-II support a search-and-
    // replace minefield.

    /** EOS 6D sensor native width in pixels. */
    const val SENSOR_6D_WIDTH: Int = 5472
    /** EOS 6D sensor native height in pixels. */
    const val SENSOR_6D_HEIGHT: Int = 3648
    /**
     * Default FlexiZone AF-box dimensions in sensor pixels. Magic
     * Lantern's `move_lv_afframe` uses the same value on the 6D —
     * matches the on-LCD green rectangle the camera draws.
     */
    const val LV_AFFRAME_DEFAULT_BOX_PX: Int = 300
    /**
     * Magic Lantern's `COERCE` floor for AF-box coordinates: 500 px
     * from any sensor edge. The 6D firmware silently rejects writes
     * outside this band even though it advertises a wider valid range.
     */
    const val LV_AFFRAME_MIN_COORD_PX: Int = 500

    /**
     * EOS_GetViewFinderData (0x9153). Three uint32 params, the EU/6D
     * triplet is `(0x00200000, 0, 0)`. Data-in returns a single MJPEG
     * frame wrapped in a Canon-specific envelope:
     *   - 4-byte magic `FF FF FF FF`
     *   - 4-byte container header `0x40980010`
     *   - Geometry / AF-area / WB metadata block
     *   - JPEG payload starting at the SOI marker `FF D8`
     * Cadence is bounded only by the camera's internal MJPEG rate
     * (~10-15 fps on the 6D). Returning [RC_DEVICE_BUSY] (0x2019)
     * means "not ready yet — retry"; libgphoto2 backs off ~5 ms per
     * try up to 3 s.
     */
    const val OC_EOS_GET_VIEWFINDER_DATA: Int = 0x9153

    /**
     * EOS_TerminateViewfinder (0x9152). No params, no data phase.
     * Final teardown opcode after `0x9110 D1B0=0` (EVFOutputDevice off).
     */
    const val OC_EOS_TERMINATE_VIEWFINDER: Int = 0x9152

    /**
     * Canon-vendor "LV stream arm latch" (0x913E). Single uint32 param
     * acts as a boolean — 0 to arm the viewfinder stream, 1 to disarm.
     * Verified in PC_EOSUTILITY_Live-remote-shooting-and-download.pcapng
     * frame 94458: EU sends `0x913E(0)` immediately before the first
     * `0x9153` call and `0x913E(1)` immediately after the final
     * `0x9153`. libgphoto2 does NOT issue this latch (the 5Dm2/5Ds/R8
     * apparently don't need it), but the 6D's Wi-Fi firmware refuses
     * to buffer LV frames without it — returns 0xA102 indefinitely.
     */
    const val OC_EOS_LV_STREAM_LATCH: Int = 0x913E

    // Note: 0x911C `OC_EOS_RESET_UI_LOCK` is also re-used by EU during
    // LV start (pcap frame 94291) — same opcode value, different
    // semantic from the doc-string above. Calling ResetUILock as part
    // of the LV-start sequence appears to clear residual UI lock from
    // a prior session that prevents EVF from buffering.

    /**
     * EOS_BulbStart (0x9125). No params, no data phase. The camera mode
     * dial MUST be on Bulb (or M with shutter=Bulb on bodies that allow
     * it); libgphoto2 does NOT switch modes for you. Pair with
     * [OC_EOS_BULB_END] after the exposure duration.
     */
    const val OC_EOS_BULB_START: Int = 0x9125

    /**
     * EOS_BulbEnd (0x9126). Closes the bulb exposure. No params, no
     * data phase. The shutter event lands in the usual
     * [EC_EOS_REQUEST_OBJECT_TRANSFER] flow after this returns.
     */
    const val OC_EOS_BULB_END: Int = 0x9126

    // Live View setup DPCs (written via OC_EOS_SET_DEV_PROP_VALUE_EX 0x9110).

    /**
     * CANON_EOS_EVFOutputDevice (0xD1B0). Despite libgphoto2 naming
     * D1B0 "EVFMode", the actual EU 6D wire trace at
     * PC_EOSUTILITY_Live-remote-shooting-and-download.pcapng frame
     * 94433 shows EU writes **0xD1B0 = 3** here — and 3 is the
     * documented "PC output target" value, not an "EVF on" toggle.
     * We trust the pcap; the libgphoto2 docstring labels are wrong
     * for the 6D.
     */
    const val DPC_CANON_EOS_EVF_OUTPUT_DEVICE: Int = 0xD1B0

    /**
     * CANON_EOS_EVFMode (0xD1B3). EU 6D pcap frame 94446 writes
     * **0xD1B3 = 0** — the "EVF mode" enable. Value 0 is what the
     * 6D needs; non-zero values are documented as TFT routing
     * variants we don't want.
     */
    const val DPC_CANON_EOS_EVF_MODE: Int = 0xD1B3

    /**
     * CANON_EOS_EVFColorTemp / pre-arm DPC (0xD1BC). EU 6D pcap
     * frame 94279 writes **0xD1BC = 3** as the FIRST step of the
     * LV-start sequence — before D1B0 / D1B3 / 0x913E. Without
     * this pre-arm, the 6D returns 0xA102 indefinitely to all
     * subsequent 0x9153 GetViewFinderData calls (verified
     * empirically across multiple attempts).
     */
    const val DPC_CANON_EOS_EVF_PRE_ARM: Int = 0xD1BC

    /**
     * CANON_EOS_CaptureDestination (0xD11C) — where the camera writes
     * the next shot. Values:
     *   1 = card only           (event: 0xC181 ObjectAddedEx)
     *   4 = host (RAM) only     (event: 0xC186 RequestObjectTransfer)
     *   3 = both card + host
     * Set on session start per user choice; the 6D fires the matching
     * event after each release.
     */
    const val DPC_CANON_EOS_CAPTURE_DESTINATION: Int = 0xD11C

    /** Card only — camera writes to the SD card and may not notify. */
    const val DPC_CAPTURE_DEST_CARD: Int = 1
    /** Host RAM only — camera streams the shot to us; nothing on the card. */
    const val DPC_CAPTURE_DEST_HOST: Int = 4
    /** Both — camera writes to the SD card AND notifies us via 0xC181. */
    const val DPC_CAPTURE_DEST_BOTH: Int = 3

    // ---------- Canon EOS shooting-parameter DPCs ----------
    //
    // Every code below is read+write via GetDevicePropDesc / SetDevicePropValueEx.
    // The advertised value list (enum) and current value come back from
    // 0x1014; we never hard-code per-body tables — different lenses /
    // mode-dial positions change the legal set on the fly.

    /** ISO speed (DPC_CANON_EOS_ISO). uint16 enum. */
    const val DPC_EOS_ISO: Int = 0xD002
    /** Tv / shutter speed (DPC_CANON_EOS_ShutterSpeed). uint16 enum. */
    const val DPC_EOS_TV: Int = 0xD003
    /** Av / aperture (DPC_CANON_EOS_Aperture). uint16 enum. */
    const val DPC_EOS_AV: Int = 0xD004
    /** Exposure compensation (DPC_CANON_EOS_ExpCompensation). uint8 enum. */
    const val DPC_EOS_EXPOSURE_COMP: Int = 0xD104

    /** White balance (DPC_CANON_EOS_WhiteBalance). uint16 enum. */
    const val DPC_EOS_WHITE_BALANCE: Int = 0xD006
    /** Colour temperature (DPC_CANON_EOS_ColorTemperature). uint16 (Kelvin). */
    const val DPC_EOS_COLOR_TEMP: Int = 0xD01E
    /** WB shift A/B M/G axes (DPC_CANON_EOS_WhiteBalanceAdjustA / -G). packed. */
    const val DPC_EOS_WB_SHIFT_AB: Int = 0xD01F

    /** Metering mode (DPC_CANON_EOS_MeteringMode). uint8 enum. */
    const val DPC_EOS_METERING_MODE: Int = 0xD107
    /** Drive mode (DPC_CANON_EOS_DriveMode). uint16 enum. */
    const val DPC_EOS_DRIVE_MODE: Int = 0xD106
    /** AF mode (DPC_CANON_EOS_AFMode). uint8 enum. */
    const val DPC_EOS_AF_MODE: Int = 0xD108
    /** AF method in Live View (DPC_CANON_EOS_AfMethod). uint8 enum. */
    const val DPC_EOS_AF_METHOD: Int = 0xD11F

    /** Picture style selector (DPC_CANON_EOS_PictureStyle). uint8 enum. */
    const val DPC_EOS_PICTURE_STYLE: Int = 0xD110
    /** Picture style sharpness / contrast / saturation / colour tone. */
    const val DPC_EOS_PS_SHARPNESS: Int = 0xD111
    const val DPC_EOS_PS_CONTRAST: Int = 0xD112
    const val DPC_EOS_PS_SATURATION: Int = 0xD113
    const val DPC_EOS_PS_COLOR_TONE: Int = 0xD114

    /** Image quality / file format (DPC_CANON_EOS_ImageQuality). packed enum. */
    const val DPC_EOS_IMAGE_QUALITY: Int = 0xD120
    /** Aspect ratio (DPC_CANON_EOS_AspectRatio). uint8 enum. */
    const val DPC_EOS_ASPECT_RATIO: Int = 0xD06A

    /** Mode-dial position read-back (DPC_CANON_EOS_CameraMode). uint8 enum. */
    const val DPC_EOS_CAMERA_MODE: Int = 0xD01D
    /** Available shots remaining on the card (DPC_CANON_EOS_AvailableShots). uint32. */
    const val DPC_EOS_AVAILABLE_SHOTS: Int = 0xD11A

    /** LV zoom factor (DPC_CANON_EOS_EvfZoom). uint16 enum {1, 5, 10}. */
    const val DPC_EOS_EVF_ZOOM: Int = 0xD12C
    /** LV zoom rectangle position (DPC_CANON_EOS_EvfZoomPosition). packed x/y. */
    const val DPC_EOS_EVF_ZOOM_POSITION: Int = 0xD12E
    /** AF point bitmask / selection (DPC_CANON_EOS_AfPointSet). uint32 bitmask. */
    const val DPC_EOS_AF_POINT_SET: Int = 0xD12D

    // ---------- Event codes ----------

    /** Standard PTP fallback. Carries only the object handle as a parameter. */
    const val EC_OBJECT_ADDED: Int = 0x4002
    const val EC_DEVICE_INFO_CHANGED: Int = 0x4006
    const val EC_STORE_FULL: Int = 0x4009
    const val EC_CAPTURE_COMPLETE: Int = 0x400D

    /**
     * Canon EOS shutter-event record code emitted inside an [OC_EOS_GET_EVENT]
     * data blob. Carries handle + storage ID + OFC + size + parent + filename
     * inline, saving a follow-up GetObjectInfo round-trip on most bodies.
     *
     * Record layout (LE, offsets from start of record):
     *   0x00 uint32 recordSize  (incl. 8-byte header)
     *   0x04 uint32 eventCode = 0xC181
     *   0x08 uint32 objectHandle
     *   0x0C uint32 storageId
     *   0x10 uint16 ofc            (object format code — same as PTP FMT_*)
     *   0x12 10 bytes reserved
     *   0x1C uint32 sizeBytes      (32-bit on this variant; use [EC_EOS_OBJECT_ADDED_EX_64] for >4 GB)
     *   0x20 uint32 parentObject
     *   0x24 4 bytes reserved
     *   0x28 ASCII filename, NUL-terminated
     */
    const val EC_EOS_OBJECT_ADDED_EX: Int = 0xC181

    /**
     * 64-bit-size variant used by some current EOS R-series bodies for CR3
     * frames larger than 4 GB. Record layout differs from [EC_EOS_OBJECT_ADDED_EX]:
     *   0x00 uint32 recordSize
     *   0x04 uint32 eventCode = 0xC1A7
     *   0x08 uint32 objectHandle
     *   0x0C uint32 storageId
     *   0x10 uint16 ofc
     *   0x12 10 bytes reserved
     *   0x1C uint64 sizeBytes
     *   0x24 uint32 parentObject
     *   0x28 uint32 secondaryOid
     *   0x2C ASCII filename, NUL-terminated
     *
     * Note: libgphoto2 has historically read this field as 32-bit with a FIXME
     * comment. We read it as 64-bit per the field naming convention.
     */
    const val EC_EOS_OBJECT_ADDED_EX_64: Int = 0xC1A7

    /** EOS shutter-event record: object removed (handle only). */
    const val EC_EOS_OBJECT_REMOVED: Int = 0xC182

    /** EOS shutter-event record: object metadata changed (same layout as 0xC181). */
    const val EC_EOS_OBJECT_INFO_CHANGED_EX: Int = 0xC187

    /**
     * EOS_RequestObjectTransfer (0xC186). Emitted when the camera holds
     * a freshly-captured image in RAM only (CaptureDestination=4 host).
     * The host MUST download via [OC_GET_PARTIAL_OBJECT] chunks +
     * [OC_EOS_TRANSFER_COMPLETE]; the camera does not free RAM until
     * TransferComplete acks. Record layout matches [EC_EOS_OBJECT_ADDED_EX]
     * — same handle/storage/ofc/size/filename fields.
     */
    const val EC_EOS_REQUEST_OBJECT_TRANSFER: Int = 0xC186

    /**
     * EOS_OLCInfoChanged (0xC18A). Stream of "On-Lens Camera" status
     * updates — focus lock, AE values, mode dial position, white-balance.
     * Used during the half-press → full-press handshake to detect "in
     * focus" before firing the shutter. libgphoto2 reads byte pattern
     * `00 00 00 00 01 01` from the OLC payload as "in focus".
     */
    const val EC_EOS_OLC_INFO_CHANGED: Int = 0xC18A

    /**
     * EOS_DevicePropChanged (0xC189). Pushed when a property's current
     * value AND descriptor (allowed-values list, range) change — i.e.
     * the user spun the mode dial, mounted a new lens, etc. Payload
     * starts with the affected DPC code (uint32 LE).
     *
     * Distinct from [EC_EOS_PROPERTY_VALUE_CHANGED] which only signals
     * a value change while the descriptor stays the same. We treat
     * both as "invalidate cached prop and re-fetch" for simplicity.
     */
    const val EC_EOS_DEVICE_PROP_CHANGED: Int = 0xC189

    /** EOS_PropertyValueChanged (0xC18B). Value-only update for a DPC. */
    const val EC_EOS_PROPERTY_VALUE_CHANGED: Int = 0xC18B

    /**
     * EOS event-stream terminator: a record with size=8 and code=0 marks the
     * end of an [OC_EOS_GET_EVENT] data blob. The decoder stops there.
     */
    const val EC_EOS_STREAM_TERMINATOR: Int = 0x00000000

    // ---------- Response codes ----------

    const val RC_OK: Int = 0x2001
    const val RC_DEVICE_BUSY: Int = 0x2019

    /**
     * Canon EOS vendor "not ready yet" response. Specifically observed as
     * the reply to [OC_EOS_GET_VIEWFINDER_DATA] when the EVF stream has
     * been armed via D1B0/D1B3 writes but the camera hasn't buffered a
     * frame yet — libgphoto2 treats this identically to [RC_DEVICE_BUSY]
     * (sleep ~5 ms, retry) and the 6D in LV mode produces it for the
     * first ~30-50 fetches after the arm sequence completes.
     */
    const val RC_CANON_EOS_DEVICE_BUSY: Int = 0xA102

    // ---------- Standard PTP object format codes (subset we care about) ----------

    /** PTP standard JPEG. */
    const val FMT_JPEG: Int = 0x3801

    /** Canon CR2 (legacy RAW). */
    const val FMT_CANON_CR2: Int = 0xB103

    /** Canon CR3 (HEIF-container RAW, current EOS R / mirrorless). */
    const val FMT_CANON_CR3: Int = 0xB10B

    /** Set of format codes we treat as "RAW" for FormatFilter. */
    val RAW_FORMATS: Set<Int> = setOf(FMT_CANON_CR2, FMT_CANON_CR3)

    // ---------- DataPhaseInfo values for OPERATION_REQUEST ----------

    /** No data phase follows; this is an immediate command. */
    const val DATA_PHASE_NONE: Int = 1

    /** A data-out phase follows (host sends data to camera). */
    const val DATA_PHASE_DATA_OUT: Int = 2

    /** A data-in phase follows (camera streams data to host). */
    const val DATA_PHASE_DATA_IN: Int = 3
}
