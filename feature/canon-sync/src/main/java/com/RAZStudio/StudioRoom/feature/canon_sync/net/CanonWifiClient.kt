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

import android.net.Network
import android.util.Log
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraDescriptor
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ConnectResult
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectTransferResult
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.PtpEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.blackholeSink
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the dual TCP-socket PTP/IP session with a Canon EOS body.
 *
 * Responsibilities (see claude advanced plan.md §3, §5.2):
 * - Sets up TCP_NODELAY + 1 MB SO_RCVBUF before connect (TCP window-scale is
 *   negotiated during the SYN handshake, so this must be pre-connect).
 * - Binds both sockets to the provided [Network] handle so PTP traffic rides
 *   the camera Wi-Fi AP exclusively and the rest of the app keeps using
 *   whatever the system default is (mobile data, normal Wi-Fi). Per-socket
 *   binding only — never [android.net.ConnectivityManager.bindProcessToNetwork].
 * - Runs the post-OpenSession EOS init sequence (§4.5):
 *     GetDeviceInfo (pre-session, txn=0) → OpenSession (txn=0 reset) →
 *     EOS_SetRemoteMode(5, 0x9114) → EOS_SetEventMode(1, 0x9115) → GetEvent drain → start event reader.
 *   Skipping the last two opcodes is the canonical reason a PTP/IP client
 *   "connects fine but never receives shutter events".
 * - Serialises all command-channel traffic behind a [Mutex] because Canon
 *   firmware actively rejects overlapping operations with PTP_RC_DeviceBusy
 *   (0x2019) and desyncs the transaction-ID stream.
 *
 * This v1 surface exposes only enough to validate the wire layer compiles.
 * Event-reader coroutine, GetObject streaming, and teardown helpers will be
 * added in subsequent slices.
 */
internal class CanonWifiClient {

    // PTP transactionId starts at 0. The pre-session GetDeviceInfo and the
    // immediately-following OpenSession both use txn=0 (per EOS Utility wire
    // capture); subsequent ops increment from 1. The 6D rejects an out-of-
    // session GetDeviceInfo carrying txn=1 (the previous default), which is
    // why our first connect attempt hangs forever after InitEvent ACK.
    private val transactionId = AtomicInteger(0)
    private val commandMutex = Mutex()

    // Set to true while a bulk thumbnail or file download is actively
    // running. The EOS event poller checks this flag before trying to
    // acquire commandMutex: if a download is in flight it skips the
    // GetEvent tick entirely so the mutex is never held for up to 2s
    // blocking the thumb pipeline. The poller runs every 200ms so
    // missing one or two ticks during a batch download is harmless —
    // shutter events are queued in the camera's firmware buffer and
    // drained on the next tick after the download finishes.
    internal val downloadActive = java.util.concurrent.atomic.AtomicBoolean(false)

    private var commandSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var commandSource: BufferedSource? = null
    private var commandSink: BufferedSink? = null
    private var eventSource: BufferedSource? = null
    private var eventSink: BufferedSink? = null
    private var connectionId: Int = 0

    /**
     * Channel.UNLIMITED — never drop a shutter event. The upper bound is the
     * camera's internal queue (~30-60 frames). See claude advanced plan.md §3.2
     * for the rationale: SharedFlow with DROP_OLDEST would silently lose
     * ObjectAdded events, and a lost event = a permanently-lost photo.
     */
    private val eventChannel: Channel<PtpEvent> = Channel(Channel.UNLIMITED)

    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventReaderJob: Job? = null
    private var eosPollerJob: Job? = null
    private var pingJob: Job? = null

    /**
     * How this particular camera body wants to deliver shutter events to us.
     * Set during the handshake; determines whether [startEventReaders]
     * launches an [EosEventPoller] alongside the [EventReader] or just the
     * latter on its own. Older bodies (6D-era) only support
     * [StandardPtpEvents].
     */
    internal enum class EventDeliveryMode {
        /** Camera supports EOS_SetEventMode + EOS_GetEvent. R-series + newer. */
        EosVendorPolling,

        /** Camera only fires standard PTP `ObjectAdded (0x4002)` on the event socket. */
        StandardPtpEvents,
    }

    private var eventDeliveryMode: EventDeliveryMode = EventDeliveryMode.StandardPtpEvents

    /**
     * Stream of decoded camera events. The single consumer (CaptureCoordinator
     * in the next slice) must serialise GetObject calls on the command channel
     * itself — Canon firmware does not tolerate overlapping operations.
     *
     * This is exposed as [ReceiveChannel] not [kotlinx.coroutines.flow.Flow]
     * because we want at-most-once delivery semantics; converting via
     * [kotlinx.coroutines.flow.consumeAsFlow] would still be safe but would
     * accidentally invite operators like `conflate()` that drop values.
     */
    val events: ReceiveChannel<PtpEvent> get() = eventChannel

    /**
     * Side-channel fan-out for `EOS_DevicePropChanged (0xC189)` and
     * `EOS_PropertyValueChanged (0xC18B)` records — emits the affected
     * DPC code. The primary [events] channel keeps its single-consumer
     * contract (the CaptureCoordinator), so we duplicate property-change
     * notifications here for the [EosPropertyController] to subscribe
     * without contending for the channel.
     *
     * `replay = 0` because property-change events are transient — late
     * subscribers should call `getDevicePropDesc()` directly rather than
     * replay an arbitrary prior change. Buffered to soak up bursts
     * (mode-dial rotation fires several in rapid succession).
     */
    private val _propertyChanges = kotlinx.coroutines.flow.MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 32,
    )
    val propertyChanges: kotlinx.coroutines.flow.SharedFlow<Int> = _propertyChanges

    /**
     * Latest decoded `EOS_OLCInfoChanged (0xC18A)` record. Replay-1 so
     * a freshly subscribed observer instantly sees the camera's current
     * AE-lock / flash-ready / AF-lock state without waiting for the
     * next push.
     */
    private val _olcInfo = kotlinx.coroutines.flow.MutableSharedFlow<PtpEvent.EosOlcInfo>(
        replay = 1,
        extraBufferCapacity = 8,
    )
    val olcInfo: kotlinx.coroutines.flow.SharedFlow<PtpEvent.EosOlcInfo> = _olcInfo

    /**
     * Establish the full PTP/IP session including the EOS-specific event
     * enablement. Returns a typed [ConnectResult]; callers should never
     * inspect exceptions thrown from below.
     *
     * @param network Wi-Fi network handle obtained from
     *   [android.net.ConnectivityManager.requestNetwork] with the INTERNET
     *   capability removed. Used for per-socket binding only.
     * @param cameraIp Camera AP address — typically 192.168.1.1 or 192.168.0.1
     *   when the body is in its own AP mode.
     * @param hostGuid Stable per-install UUID. Reused across sessions so the
     *   camera's "remembered hosts" list doesn't fill up with random entries.
     * @param hostName Friendly name shown on the camera's pairing screen.
     */
    @Suppress("ReturnCount")
    suspend fun connect(
        // Null means "no Network scoping" — the phone is running the AP
        // (hotspot mode) and the kernel will route 192.168.x.x via the
        // AP interface automatically. See NetworkBinder.acquireCameraWifi.
        network: Network?,
        cameraIp: String,
        hostGuid: UUID,
        hostName: String,
    ): ConnectResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "connect: start ip=$cameraIp host=$hostName guid=$hostGuid")
        try {
            // ---------- 1. Command socket ----------
            Log.d(TAG, "connect: [1/8] create cmd socket")
            val cmdSocket = createTunedSocket()
            network?.bindSocket(cmdSocket)
            Log.d(TAG, "connect: [1/8] cmd socket bound, connecting to $cameraIp:${PtpIpConstants.PORT}")
            cmdSocket.connect(InetSocketAddress(cameraIp, PtpIpConstants.PORT), CONNECT_TIMEOUT_MS)
            Log.i(TAG, "connect: [1/8] cmd TCP connected local=${cmdSocket.localSocketAddress} remote=${cmdSocket.remoteSocketAddress}")
            commandSocket = cmdSocket
            val cmdSource = cmdSocket.source().buffer().also { commandSource = it }
            val cmdSink = cmdSocket.sink().buffer().also { commandSink = it }

            // ---------- 2. Init command handshake ----------
            Log.d(TAG, "connect: [2/8] sending INIT_COMMAND_REQUEST guid=$hostGuid host='$hostName'")
            val initCmdBuf = PtpIpPacketBuilders.buildInitCommandRequest(hostGuid, hostName)
            Log.i(TAG, "connect: [2/8] INIT_COMMAND bytes=${initCmdBuf.copy().readByteString().hex()}")
            cmdSink.writeAll(initCmdBuf)
            cmdSink.flush()
            Log.d(TAG, "connect: [2/8] awaiting INIT_COMMAND_ACK")

            val ack = when (val packet = PtpIpPacketReader.readPacket(cmdSource)) {
                is PtpIpPacket.InitCommandAck -> {
                    Log.i(TAG, "connect: [2/8] INIT_COMMAND_ACK connId=${packet.connectionId} model='${packet.cameraName}'")
                    packet
                }
                is PtpIpPacket.InitFail -> {
                    Log.w(TAG, "connect: [2/8] INIT_FAIL reasonCode=0x${"%08X".format(packet.reasonCode)} -> closing")
                    closeQuietly()
                    return@withContext ConnectResult.Failure.InitRejected(
                        reasonCode = packet.reasonCode,
                        reasonText = describeInitFailReason(packet.reasonCode),
                    )
                }
                else -> {
                    Log.w(TAG, "connect: [2/8] unexpected packet type=0x${"%08X".format(packet.type)} -> closing")
                    closeQuietly()
                    return@withContext ConnectResult.Failure.ProtocolError(
                        "Unexpected packet type 0x%08X in place of INIT_COMMAND_ACK".format(packet.type)
                    )
                }
            }
            connectionId = ack.connectionId

            // ---------- 3. Event socket + handshake ----------
            Log.d(TAG, "connect: [3/8] create event socket")
            val evtSocket = createTunedSocket()
            network?.bindSocket(evtSocket)
            Log.d(TAG, "connect: [3/8] event socket bound, connecting")
            evtSocket.connect(InetSocketAddress(cameraIp, PtpIpConstants.PORT), CONNECT_TIMEOUT_MS)
            Log.i(TAG, "connect: [3/8] event TCP connected")
            eventSocket = evtSocket
            val evtSource = evtSocket.source().buffer().also { eventSource = it }
            val evtSink = evtSocket.sink().buffer().also { eventSink = it }

            Log.d(TAG, "connect: [3/8] sending INIT_EVENT_REQUEST connId=$connectionId")
            evtSink.writeAll(PtpIpPacketBuilders.buildInitEventRequest(connectionId))
            evtSink.flush()

            when (val evtAck = PtpIpPacketReader.readPacket(evtSource)) {
                is PtpIpPacket.InitEventAck -> {
                    Log.i(TAG, "connect: [3/8] INIT_EVENT_ACK ok")
                }
                is PtpIpPacket.InitFail -> {
                    Log.w(TAG, "connect: [3/8] event-INIT_FAIL reasonCode=0x${"%08X".format(evtAck.reasonCode)}")
                    closeQuietly()
                    return@withContext ConnectResult.Failure.InitRejected(
                        reasonCode = evtAck.reasonCode,
                        reasonText = describeInitFailReason(evtAck.reasonCode),
                    )
                }
                else -> {
                    Log.w(TAG, "connect: [3/8] event-unexpected packet type=0x${"%08X".format(evtAck.type)}")
                    closeQuietly()
                    return@withContext ConnectResult.Failure.ProtocolError(
                        "Unexpected packet type 0x%08X in place of INIT_EVENT_ACK".format(evtAck.type)
                    )
                }
            }

            // ---------- 4. GetDeviceInfo (NO session yet) ----------
            //
            // EOS Utility's wire order on the 6D (from PC_EOSUTILITY.pcapng):
            //   InitCommand → InitEvent → GetDeviceInfo → OpenSession → ...
            //
            // PTP normally requires OpenSession before any other op, but
            // GetDeviceInfo is the documented exception (transactionId=0
            // outside a session). The 6D specifically expects this probe
            // before OpenSession — sending OpenSession first works on
            // some bodies but trips the legacy 6D's pairing checker and
            // it tears down the TCP socket without responding.
            Log.i(TAG, "connect: [4/8] GetDeviceInfo (pre-session probe) — sending now")
            // PAIRING_TIMEOUT_MS passed directly to runOperation so it survives
            // inside the commandMutex. A bare `commandSocket?.soTimeout = ...`
            // poke here is overwritten by runOperation's own soTimeout pin, which
            // defaults to READ_TIMEOUT_MS (30 s) — not enough for the user to walk
            // to the camera and press SET (needs the full 90 s pairing window).
            // EOS Utility sends dataPhase=NONE for GetDeviceInfo (pcap frame
            // 1233 confirms `dataPhase=1`). The camera replies with the
            // descriptor data regardless of this hint, but using DATA_IN
            // here makes the 6D drop the request silently.
            val initialDeviceInfo = runOperation(
                operationCode = PtpIpConstants.OC_GET_DEVICE_INFO,
                dataPhase = PtpIpConstants.DATA_PHASE_NONE,
                readTimeoutMs = PAIRING_TIMEOUT_MS,
            )
            Log.i(TAG, "connect: [4/8] GetDeviceInfo rc=0x${"%04X".format(initialDeviceInfo.response.responseCode)}")
            if (initialDeviceInfo.response.responseCode != PtpIpConstants.RC_OK) {
                Log.w(TAG, "connect: [4/8] GetDeviceInfo pre-session failed -> closing")
                closeQuietly()
                return@withContext ConnectResult.Failure.PtpResponseError(
                    operationCode = PtpIpConstants.OC_GET_DEVICE_INFO,
                    responseCode = initialDeviceInfo.response.responseCode,
                )
            }

            // ---------- 5. OpenSession ----------
            // Per PTP spec, OpenSession itself uses transactionId=0 (it's
            // the "start of session" marker). The pcap confirms EOS Utility
            // sends GetDeviceInfo with txn=0, then OpenSession also with
            // txn=0 — re-using the counter — and subsequent ops increment
            // from 1. Reset the counter so OpenSession lands at 0.
            transactionId.set(0)
            Log.d(TAG, "connect: [5/8] OpenSession")
            val openSessionResult = runOperation(
                operationCode = PtpIpConstants.OC_OPEN_SESSION,
                params = listOf(SESSION_ID),
            )
            commandSocket?.soTimeout = READ_TIMEOUT_MS
            Log.i(TAG, "connect: [5/8] OpenSession rc=0x${"%04X".format(openSessionResult.response.responseCode)}")
            if (openSessionResult.response.responseCode != PtpIpConstants.RC_OK) {
                Log.w(TAG, "connect: [5/8] OpenSession failed -> closing")
                closeQuietly()
                return@withContext ConnectResult.Failure.PtpResponseError(
                    operationCode = PtpIpConstants.OC_OPEN_SESSION,
                    responseCode = openSessionResult.response.responseCode,
                )
            }

            // ---------- 6 & 7. EOS vendor mode opcodes ----------
            //
            // The pcap shows EOS Utility on the 6D running SetRemoteMode
            // (0x9114) → SetEventMode (0x9115) → an initial GetEvent
            // (0x9116) drain immediately after OpenSession. We do the
            // same. Both opcodes return OK on the 6D in EOS Utility
            // mode; the older bodies that reject these stay usable via
            // standard PTP events fallback.
            Log.d(TAG, "connect: [6-7/8] SetRemoteMode + SetEventMode")
            val supportsEosEvents = trySetEosRemoteMode() && trySetEosEventMode()
            eventDeliveryMode = if (supportsEosEvents) {
                EventDeliveryMode.EosVendorPolling
            } else {
                EventDeliveryMode.StandardPtpEvents
            }
            Log.i(TAG, "connect: [6-7/8] eventDeliveryMode=$eventDeliveryMode")

            // Initial GetEvent drain — clears any events the camera
            // accumulated before the session opened. EOS Utility does
            // this immediately and it's what flushes the pairing-progress
            // state on legacy bodies. We don't care about the contents;
            // we just need the camera to see a poll happen.
            //
            // Hard-cap this drain to 3 s. The default 30 s READ_TIMEOUT
            // burned the 6D's NIC sleep budget BEFORE the post-handshake
            // heartbeat could fire — by the time SUCCESS was logged the
            // radio had already gone to sleep, dropping the AP within a
            // few seconds. 3 s is enough for the camera to emit any
            // queued events; if it's silent we move on and let the
            // periodic heartbeat keep the link alive instead.
            //
            // Timeout is passed via [runOperation]'s `readTimeoutMs`
            // parameter — Phase 3 moved socket-timeout management
            // inside the command mutex, so out-of-mutex pokes at
            // `commandSocket.soTimeout` would be overwritten by the
            // next runOperation call.
            runCatching {
                runOperation(
                    operationCode = PtpIpConstants.OC_EOS_GET_EVENT,
                    dataPhase = PtpIpConstants.DATA_PHASE_DATA_IN,
                    readTimeoutMs = 3_000,
                )
            }.onFailure {
                // Most common path here is SocketTimeoutException — the
                // event queue is empty so GetEvent times out at 3 s. That's
                // expected and benign; log at DEBUG without the full stack
                // so it doesn't pollute production logcat. A real failure
                // (e.g. premature TCP close, malformed response) shows up
                // immediately at the *next* opcode anyway.
                if (it is java.net.SocketTimeoutException) {
                    Log.d(TAG, "connect: initial GetEvent drain timed out (queue empty, expected)")
                } else {
                    Log.w(TAG, "connect: initial GetEvent drain failed (continuing)", it)
                }
            }

            // ---------- 7b. PC pairing handshake (EOS Utility mode only) ----
            // After the event-drain, send PCHDDCapacity(0x0fffffff, 0x1000, 1).
            // On a 6D sitting at "Start the pairing software on your computer",
            // this opcode dismisses that screen and advances the LCD to
            // "Connect to this PC? — Press SET". libgphoto2 only sends this
            // opcode when switching capture destination, but on the 6D in
            // EOS Utility Wi-Fi mode it's also the pairing-completion
            // signal — verified via pcap of EOS Utility 3 talking to a
            // freshly-Wi-Fi-paired 6D.
            //
            // The "Press SET" prompt then appears on the camera body. The
            // user has ~30 s to press SET; once they do, file-tree opcodes
            // (GetPartialObject, GetObjectHandles batched, etc.) start
            // returning real data. Without this opcode, the camera silently
            // ignores ALL file ops — the symptom you've been seeing.
            //
            // If the camera is in Smartphone mode (not EOS Utility), this
            // call may tear down the socket; we tolerate that and continue
            // — the Smartphone-mode users will see the standard file
            // enumeration succeed without pairing.
            // NOTE: PCHDDCapacity / GetDeviceInfoEx / SetOwnerName /
            // SetUILock are NOT part of the real EOS Utility 2 wire
            // trace (verified from PC_EOSUTILITY.pcapng — see frames
            // 1233-1411). They were guesses from libgphoto2's USB code
            // paths. On a 6D in EOS-Utility Wi-Fi mode the real
            // sequence is just: OpenSession(0x41) → SetRemoteMode(5)
            // → SetEventMode(1) → GetEvent → SetRequestOLCInfoGroup
            // → EOS_GetStorageIDs → EOS_GetStorageInfo →
            // EOS_GetObjectInfoEx. Pairing happens at the InitCommand
            // layer when the user clicks the camera's connect-confirm
            // button; no app-level pairing opcodes are needed.

            // ---------- 8. Start event readers ----------
            Log.d(TAG, "connect: [8/8] starting event readers + (if legacy) keepalive heartbeat")
            startEventReaders(evtSource = evtSource)

            Log.i(TAG, "connect: SUCCESS model='${ack.cameraName}' mode=$eventDeliveryMode")
            ConnectResult.Success(
                CameraDescriptor(
                    cameraGuid = ack.cameraGuid.hex(),
                    model = ack.cameraName,
                    ipAddress = cameraIp,
                    connectionId = connectionId,
                )
            )
        } catch (io: IOException) {
            Log.e(TAG, "connect: IOException -> SocketUnreachable", io)
            closeQuietly()
            ConnectResult.Failure.SocketUnreachable(io.message ?: io.javaClass.simpleName)
        } catch (t: Throwable) {
            Log.e(TAG, "connect: Throwable -> ProtocolError", t)
            closeQuietly()
            ConnectResult.Failure.ProtocolError(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Outcome of a [runOperation] invocation. For data-in operations the
     * camera streams Start/Data/End packets before the final OperationResponse;
     * [dataPhasePayload] collects those bytes (concatenated, headers stripped)
     * for the caller to decode. Non-data operations have [dataPhasePayload] == null.
     *
     * v1 note: small data-in operations like [PtpIpConstants.OC_EOS_GET_EVENT]
     * (a few hundred bytes per poll) collect into memory here. The
     * multi-megabyte GetObject path will land in the next slice and use a
     * zero-copy `bufferedSource.read(fileSink, payloadLength)` directly into
     * a [okio.Sink] — it will not go through this helper.
     */
    internal data class OperationOutcome(
        val response: PtpIpPacket.OperationResponse,
        val dataPhasePayload: Buffer?,
    )

    /**
     * Send an OPERATION_REQUEST and read back the matching OPERATION_RESPONSE,
     * collecting any data-phase payload into an in-memory [Buffer].
     *
     * Suitable for operations whose data-phase payload is small (GetEvent,
     * GetDeviceInfo, GetObjectInfo). For multi-megabyte transfers like
     * GetObject use [streamObjectTo] instead — it pipes straight to the
     * caller's sink without ever buffering the full payload in memory.
     *
     * Guarded by [commandMutex] so concurrent callers cannot interleave
     * requests on the command channel — Canon firmware would reject overlap
     * with PTP_RC_DeviceBusy (0x2019) and desync the transaction-ID stream.
     */
    private suspend fun runOperation(
        operationCode: Int,
        params: List<Int> = emptyList(),
        dataPhase: Int = PtpIpConstants.DATA_PHASE_NONE,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): OperationOutcome = commandMutex.withLock {
        val source = commandSource ?: error("Command source not established")
        // Pin socket timeout INSIDE the mutex so concurrent callers can
        // never race-leak their own timeout to a different op. Each
        // call gets exactly the [readTimeoutMs] it asked for.
        val savedTimeout = commandSocket?.soTimeout ?: READ_TIMEOUT_MS
        commandSocket?.soTimeout = readTimeoutMs
        try {
            val txnId = sendOperationRequest(operationCode, params, dataPhase)
            val collectionSink = Buffer()
            val readResult = readDataPhase(
                source = source,
                sink = collectionSink,
                onProgress = null,
                operationCode = operationCode,
                txnId = txnId,
            )
            val payload = if (collectionSink.size > 0) collectionSink else null
            OperationOutcome(response = readResult.response, dataPhasePayload = payload)
        } finally {
            commandSocket?.soTimeout = savedTimeout
        }
    }

    /**
     * Wire-call helper for "fire a simple opcode, return whether the
     * camera ack'd with `RC_OK`". Folds the recurring three-line
     * pattern (`runCatching { runOperation(...) }.getOrNull()` + rc
     * check + optional warn) into one call site. Replaces seven
     * near-identical implementations across shutter / lens / bulb /
     * cancel methods.
     *
     * @param logTag short string used in failure / non-OK log lines.
     *               When null, errors are swallowed silently.
     */
    private suspend fun runOpReturningOk(
        opcode: Int,
        params: List<Int> = emptyList(),
        logTag: String? = null,
    ): Boolean {
        val outcome = runCatching {
            runOperation(
                operationCode = opcode,
                params = params,
                dataPhase = PtpIpConstants.DATA_PHASE_NONE,
            )
        }.onFailure {
            if (logTag != null) {
                Log.w(TAG, "$logTag: threw ${it.javaClass.simpleName}: ${it.message}")
            }
        }.getOrNull() ?: return false
        val rc = outcome.response.responseCode
        if (rc != PtpIpConstants.RC_OK && logTag != null) {
            Log.w(TAG, "$logTag: rc=0x${"%04X".format(rc)}")
        }
        return rc == PtpIpConstants.RC_OK
    }

    /**
     * Fetch the standard PTP `ObjectInfo` dataset for [handle] via GetObjectInfo
     * (0x1008). Used as a fallback when the camera fires the standard
     * `ObjectAdded (0x4002)` event instead of `EOS_ObjectAddedEx (0xC181)` —
     * the latter already carries filename + format + size inline.
     *
     * Runs through [runOperation], so it's serialised against other
     * command-channel traffic.
     */
    suspend fun getObjectInfo(handle: Int): ObjectInfo {
        val outcome = runOperation(
            operationCode = PtpIpConstants.OC_GET_OBJECT_INFO,
            params = listOf(handle),
            dataPhase = PtpIpConstants.DATA_PHASE_DATA_IN,
        )
        val payload = outcome.dataPhasePayload
            ?: error("GetObjectInfo returned no data-phase payload for handle=$handle")
        return ObjectInfoDecoder.decode(handle = handle, source = payload)
    }

    /**
     * Stream a single object (RAW frame, JPEG, thumbnail) from the camera into
     * the caller's [destination] sink, using a zero-copy data-phase pipe.
     *
     * Implementation notes:
     * - Each DATA_PACKET / END_DATA_PACKET body is piped into [destination]
     *   via Okio's `BufferedSource.read(BufferedSink, byteCount)` extension,
     *   which moves segments directly out of the TCP receive buffer and into
     *   the sink. No intermediate `ByteArray` is allocated; the GC stays quiet
     *   even during a 50 MB CR3 transfer. (claude advanced plan.md §3.8.1.)
     * - The [destination] sink is NOT closed here. The caller owns its
     *   lifecycle — important for SAF `ContentResolver.openOutputStream()`
     *   sinks where premature close interferes with the ContentResolver session.
     * - [onProgress] fires once per DATA/END packet (~64 KB segments after the
     *   socket buffers settle), with `(bytesWritten, advertisedTotal)`. Cheap
     *   enough for UI progress without firing per byte.
     * - Guarded by [commandMutex] for the whole transaction, including the
     *   trailing OperationResponse — releasing the mutex before the response
     *   would let another caller's request interleave and desync the
     *   transaction-ID stream.
     *
     * @param handle PTP object handle to fetch.
     * @param destination Sink that receives the file bytes. Will be flushed
     *   on success; not closed.
     * @param onProgress Optional progress callback. Fires from a coroutine
     *   on [Dispatchers.IO]; must not block.
     */
    /**
     * Chunked image download — the actual Canon EDSDK / libgphoto2 path.
     *
     * Uses **standard PTP `GetPartialObject (0x101B)`** (not the
     * Canon-vendor `EOS_GetPartialObject (0x9107)` which is silently
     * ignored by 6D firmware in Wi-Fi mode), then sends the mandatory
     * **`EOS_TransferComplete (0x9117)`** terminator after the last
     * chunk. Without that terminator the 6D fires Err12 "Connection
     * target not found" within seconds. Confirmed in libgphoto2
     * `camlibs/ptp2/library.c:4595-4619` and Canon's own EDSDK.dll
     * symbol table.
     *
     * Loop ends when a chunk returns fewer bytes than asked OR the
     * advertised total is reached.
     *
     * Each chunk is its own short DATA-phase transaction; partial-write
     * failure mid-transfer doesn't abort previous chunks already on disk.
     */
    /**
     * Fetch the embedded JPEG thumbnail / preview for [handle] via
     * `EOS_GetThumbEx (0x910A)`. Single round-trip, ~200 KB max.
     *
     * On the 6D this returns a QuickPreview JPEG suitable for an
     * in-app preview canvas — much faster than the full-resolution
     * file (which can be 6 MB JPG / 25 MB CR2 and take 30+ s over
     * Wi-Fi). EOS Utility 2 uses this exact opcode to populate its
     * image browser grid — pcap-verified, see
     * PC_EOSUTILITY_Select-and-Download.pcapng frames 160/251/339.
     *
     * Wire: dataPhase=NONE, params=(handle, maxBytes). The 6D
     * silently drops the request if dataPhase=DATA_IN (3).
     */
    /**
     * Prime the 6D's thumbnail cache for [parentFolder] on [storageId].
     * Real EOS Utility 2 calls this ONCE per folder browse (pcap frame
     * 151), not per thumbnail. Calling it per-thumb is redundant and
     * stalls the batch.
     */
    suspend fun primeThumbCache(storageId: Int, parentFolder: Int) {
        runCatching {
            runOperation(
                operationCode = PtpIpConstants.OC_EOS_GET_CTG_INFO,
                params = listOf(storageId, parentFolder, 3, 0x2000),
                dataPhase = PtpIpConstants.DATA_PHASE_NONE,
            )
        }.onFailure {
            Log.w(TAG, "primeThumbCache: GetCTGInfo rejected (continuing): ${it.message}")
        }
    }

    suspend fun fetchThumb(
        handle: Int,
        maxBytes: Int = 0x32000, // 200 KB — what EOS Utility uses
    ): okio.Buffer {
        downloadActive.set(true)
        return try { fetchThumbLocked(handle, maxBytes) } finally { downloadActive.set(false) }
    }

    private suspend fun fetchThumbLocked(
        handle: Int,
        maxBytes: Int,
    ): okio.Buffer = commandMutex.withLock {
        val source = commandSource ?: error("Command source not established")
        val saved = commandSocket?.soTimeout ?: READ_TIMEOUT_MS
        commandSocket?.soTimeout = READ_TIMEOUT_MS
        try {
            // Single 0x910A call — EOS Utility issues exactly ONE per
            // photo and the 6D returns the entire QuickPreview JPEG
            // (~95 KB) in one PTP/IP transaction. The thumbnail cache
            // must be primed once before this loop via primeThumbCache.
            val txnId = sendOperationRequest(
                operationCode = PtpIpConstants.OC_EOS_GET_THUMB_EX,
                params = listOf(handle, maxBytes),
                dataPhase = PtpIpConstants.DATA_PHASE_NONE,
            )
            val sink = okio.Buffer()
            val outcome = readDataPhase(
                source = source, sink = sink, onProgress = null,
                operationCode = PtpIpConstants.OC_EOS_GET_THUMB_EX, txnId = txnId,
            )
            if (outcome.response.responseCode != PtpIpConstants.RC_OK) {
                Log.w(TAG, "fetchThumb: rc=0x${"%04X".format(outcome.response.responseCode)} " +
                    "after ${sink.size} bytes")
            } else {
                Log.i(TAG, "fetchThumb: got ${sink.size} bytes for handle=0x${"%08X".format(handle)}")
            }
            sink
        } finally {
            commandSocket?.soTimeout = saved
        }
    }

    suspend fun streamObjectViaPartial(
        handle: Int,
        destination: BufferedSink,
        advertisedTotalBytes: Long = -1L,
        onProgress: ((bytesWritten: Long, advertisedTotal: Long) -> Unit)? = null,
    ): ObjectTransferResult {
        downloadActive.set(true)
        return try { streamObjectViaPartialLocked(handle, destination, advertisedTotalBytes, onProgress) }
        finally { downloadActive.set(false) }
    }

    private suspend fun streamObjectViaPartialLocked(
        handle: Int,
        destination: BufferedSink,
        advertisedTotalBytes: Long = -1L,
        onProgress: ((bytesWritten: Long, advertisedTotal: Long) -> Unit)? = null,
    ): ObjectTransferResult = commandMutex.withLock {
        val source = commandSource ?: error("Command source not established")
        val chunkSize = PtpIpConstants.EOS_PARTIAL_CHUNK_BYTES
        var offset = 0L
        var written = 0L
        var lastResponseCode = PtpIpConstants.RC_OK
        // Bump socket timeout for the whole transfer. The event poller
        // / heartbeat live on commandMutex with a 2s timeout — fine
        // for tiny opcodes but way too short for a 1 MB chunk to land
        // over Canon Wi-Fi (~150-300 KB/s sustained, so a chunk can
        // take 3-7s). Restored in finally so the event poller's snug
        // 2s timeout is back in place after.
        val savedTimeout = commandSocket?.soTimeout ?: READ_TIMEOUT_MS
        commandSocket?.soTimeout = READ_TIMEOUT_MS
        try {
            while (true) {
                val txnId = sendOperationRequest(
                    // EOS Utility 2 uses Canon-vendor 0x9107 (NOT standard
                    // 0x101B) with dataPhase=NONE — verified in
                    // PC_EOSUTILITY2.pcapng frames 84/2918/5576... Same
                    // dataPhase=NONE quirk as 0x9109. The 6D's Wi-Fi
                    // firmware silently drops 0x9107 when sent with
                    // dataPhase=DATA_IN (3).
                    operationCode = PtpIpConstants.OC_EOS_GET_PARTIAL_OBJECT,
                    params = listOf(handle, offset.toInt(), chunkSize),
                    dataPhase = PtpIpConstants.DATA_PHASE_NONE,
                )
                val outcome = readDataPhase(
                    source = source,
                    sink = destination,
                    onProgress = { localWritten, _ ->
                        onProgress?.invoke(written + localWritten, advertisedTotalBytes)
                    },
                    operationCode = PtpIpConstants.OC_EOS_GET_PARTIAL_OBJECT,
                    txnId = txnId,
                )
                lastResponseCode = outcome.response.responseCode
                if (lastResponseCode != PtpIpConstants.RC_OK) break
                val gotThisChunk = outcome.bytesWritten
                written += gotThisChunk
                offset += gotThisChunk
                onProgress?.invoke(written, advertisedTotalBytes)
                if (gotThisChunk < chunkSize) break
                if (advertisedTotalBytes > 0 && written >= advertisedTotalBytes) break
            }
            destination.flush()
            // Note: pcap analysis (PC_EOSUTILITY2.pcapng) shows real EOS
            // Utility 2 does NOT issue EOS_TransferComplete (0x9117)
            // after partial-object downloads — it just stops calling
            // 0x9107 when the chunks run short. That was a libgphoto2
            // USB-mode convention; sending it here was unnecessary.
        } catch (io: IOException) {
            commandSocket?.soTimeout = savedTimeout
            return@withLock ObjectTransferResult.Failure.TransportError(
                advertisedSizeBytes = advertisedTotalBytes,
                bytesWritten = written,
                message = io.message ?: io.javaClass.simpleName,
            )
        }
        commandSocket?.soTimeout = savedTimeout
        when {
            lastResponseCode != PtpIpConstants.RC_OK ->
                ObjectTransferResult.Failure.PtpResponseError(
                    advertisedSizeBytes = advertisedTotalBytes,
                    bytesWritten = written,
                    responseCode = lastResponseCode,
                )
            advertisedTotalBytes > 0 && written < advertisedTotalBytes ->
                ObjectTransferResult.Failure.PartialPayload(
                    advertisedSizeBytes = advertisedTotalBytes,
                    bytesWritten = written,
                )
            // 0-byte "OK" — the camera ack'd the GetPartialObject but
            // sent an empty data phase. Observed in adb when the
            // session is mid-degradation (camera Wi-Fi flaky, prior
            // PTP socket hiccup). Treat as a failure so callers
            // (preview-cache) don't store a 0-byte placeholder and
            // serve a black canvas forever.
            written == 0L ->
                ObjectTransferResult.Failure.PartialPayload(
                    advertisedSizeBytes = advertisedTotalBytes,
                    bytesWritten = 0L,
                )
            else -> ObjectTransferResult.Success(
                advertisedSizeBytes = advertisedTotalBytes,
                bytesWritten = written,
            )
        }
    }

    /**
     * Same as [runOperation] but the caller already holds [commandMutex].
     * Used by mid-transaction helpers like the TransferComplete tail of
     * [streamObjectViaPartial].
     */
    private fun runOperationLocked(
        operationCode: Int,
        params: List<Int> = emptyList(),
        dataPhase: Int = PtpIpConstants.DATA_PHASE_NONE,
    ): OperationOutcome {
        val source = commandSource ?: error("Command source not established")
        val txnId = sendOperationRequest(operationCode, params, dataPhase)
        val sink = Buffer()
        val read = readDataPhase(
            source = source, sink = sink, onProgress = null,
            operationCode = operationCode, txnId = txnId,
        )
        return OperationOutcome(
            response = read.response,
            dataPhasePayload = if (sink.size > 0) sink else null,
        )
    }

    suspend fun streamObjectTo(
        handle: Int,
        destination: BufferedSink,
        onProgress: ((bytesWritten: Long, advertisedTotal: Long) -> Unit)? = null,
    ): ObjectTransferResult = commandMutex.withLock {
        val source = commandSource ?: error("Command source not established")
        val txnId = sendOperationRequest(
            operationCode = PtpIpConstants.OC_GET_OBJECT,
            params = listOf(handle),
            dataPhase = PtpIpConstants.DATA_PHASE_DATA_IN,
        )
        val outcome = try {
            readDataPhase(
                source = source,
                sink = destination,
                onProgress = onProgress,
                operationCode = PtpIpConstants.OC_GET_OBJECT,
                txnId = txnId,
            )
        } catch (io: IOException) {
            return@withLock ObjectTransferResult.Failure.TransportError(
                advertisedSizeBytes = -1,
                bytesWritten = -1,
                message = io.message ?: io.javaClass.simpleName,
            )
        }
        destination.flush()

        val responseCode = outcome.response.responseCode
        val advertised = outcome.advertisedSizeBytes
        val written = outcome.bytesWritten

        when {
            responseCode != PtpIpConstants.RC_OK -> ObjectTransferResult.Failure.PtpResponseError(
                advertisedSizeBytes = advertised,
                bytesWritten = written,
                responseCode = responseCode,
            )
            advertised >= 0 && written != advertised -> ObjectTransferResult.Failure.PartialPayload(
                advertisedSizeBytes = advertised,
                bytesWritten = written,
            )
            else -> ObjectTransferResult.Success(
                advertisedSizeBytes = advertised,
                bytesWritten = written,
            )
        }
    }

    /**
     * Enumerate every storage ID exposed by the camera (CF / SD slots
     * with media inserted). Returns an empty array on PTP-level failure.
     *
     * Wire: standard PTP [PtpIpConstants.OC_GET_STORAGE_IDS] (0x1004),
     * data-phase response = `uint32 count` + `uint32[count]` storage IDs.
     */
    suspend fun getStorageIds(): IntArray = commandMutex.withLock {
        val source = commandSource ?: error("Command source not established")
        val txnId = sendOperationRequest(
            operationCode = PtpIpConstants.OC_GET_STORAGE_IDS,
            params = emptyList(),
            dataPhase = PtpIpConstants.DATA_PHASE_NONE,
        )
        val sink = okio.Buffer()
        val outcome = readDataPhase(
            source = source, sink = sink, onProgress = null,
            operationCode = PtpIpConstants.OC_GET_STORAGE_IDS, txnId = txnId,
        )
        if (outcome.response.responseCode != PtpIpConstants.RC_OK) return@withLock IntArray(0)
        PtpUint32ArrayDecoder.decode(sink)
    }

    /**
     * Canon-vendor [PtpIpConstants.OC_EOS_GET_STORAGE_IDS] (0x9101).
     * Wire-equivalent of standard PTP `GetStorageIDs` but on the 6D
     * in EOS Utility mode this is what registers the host as a
     * storage observer — calling 0x1004 alone leaves the camera in
     * a state where 0x9109 silently drops file-tree requests.
     * Verified from PC_EOSUTILITY.pcapng frames 1360/1377.
     */
    suspend fun getEosStorageIds(): IntArray = commandMutex.withLock {
        val source = commandSource ?: error("Command source not established")
        val txnId = sendOperationRequest(
            operationCode = PtpIpConstants.OC_EOS_GET_STORAGE_IDS,
            params = emptyList(),
            dataPhase = PtpIpConstants.DATA_PHASE_NONE,
        )
        val sink = okio.Buffer()
        val outcome = readDataPhase(
            source = source, sink = sink, onProgress = null,
            operationCode = PtpIpConstants.OC_EOS_GET_STORAGE_IDS, txnId = txnId,
        )
        if (outcome.response.responseCode != PtpIpConstants.RC_OK) return@withLock IntArray(0)
        PtpUint32ArrayDecoder.decode(sink)
    }

    /**
     * Enumerate every object handle on a storage matching the
     * (format, parent) filter. Pass `format=0` for "all formats" and
     * `parent=0xFFFFFFFF` for "all subfolders flat" (the documented
     * CIPA shortcut that avoids walking the directory tree manually).
     *
     * Returns an empty array on PTP-level failure.
     *
     * Wire: standard PTP [PtpIpConstants.OC_GET_OBJECT_HANDLES] (0x1007),
     * three params: storageId, format, parent. Data-phase = uint32 array.
     */
    suspend fun getObjectHandles(
        storageId: Int,
        format: Int = 0,
        parent: Int = 0xFFFF_FFFF.toInt(),
    ): IntArray = commandMutex.withLock {
        val source = commandSource ?: error("Command source not established")
        val txnId = sendOperationRequest(
            operationCode = PtpIpConstants.OC_GET_OBJECT_HANDLES,
            params = listOf(storageId, format, parent),
            dataPhase = PtpIpConstants.DATA_PHASE_NONE,
        )
        val sink = okio.Buffer()
        val outcome = readDataPhase(
            source = source, sink = sink, onProgress = null,
            operationCode = PtpIpConstants.OC_GET_OBJECT_HANDLES, txnId = txnId,
        )
        if (outcome.response.responseCode != PtpIpConstants.RC_OK) return@withLock IntArray(0)
        PtpUint32ArrayDecoder.decode(sink)
    }

    /**
     * Convenience: enumerate every object across every storage, returned
     * as a list of [com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo].
     *
     * Walks: GetStorageIDs → GetObjectHandles(storage, 0, 0xFFFFFFFF) →
     * getObjectInfo(handle) for each. The pre-existing single-object
     * [getObjectInfo] is reused, so this routine costs one round-trip
     * per file on the camera SD card. Camera Connect runs this the same
     * way and tolerates 1-2 seconds for a few hundred files.
     *
     * Skips non-image associations (folders) by filtering on PTP format
     * code 0x3001 (Association). Real image formats (0x3801 EXIF/JPEG,
     * 0xB103 CR2, 0xB104 CR3, …) all pass through.
     */
    /**
     * Decode the EOS_GetObjectInfoEx (0x9109) batched response body.
     *
     * Wire layout per libgphoto2 `camlibs/ptp2/ptp-pack.c:1444-1465`:
     * ```
     *   uint32 entryCount
     *   repeat entryCount times:
     *     uint32 entrySize        (size INCLUDING this u32)
     *     uint32 objectHandle
     *     uint32 storageId
     *     uint16 formatCode       (0x3001=Association, 0x3801=JPEG, 0xB103=CR2, …)
     *     [6 bytes padding/unused fields]
     *     uint8  flags            (bit0 = read-only)
     *     [3 bytes padding]
     *     uint32 sizeBytes
     *     [8 bytes — additional unused fields]
     *     char[13] filename       (ASCII, null-padded, 8.3 slot)
     *     uint32 unixTime
     * ```
     *
     * Note that absolute offsets are within the per-entry BODY, i.e.
     * starting from BYTE 4 of the entry (after the entrySize u32 we
     * already consumed). Comment block uses libgphoto2's `PTP_cefe_*`
     * convention where offset 0 == ObjectHandle.
     */
    private fun decodeEosObjectInfoEx(
        buf: okio.Buffer,
    ): List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo> {
        val out = mutableListOf<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo>()
        val initialSize = buf.size
        if (buf.size < 4) {
            Log.w(TAG, "decodeEosObjectInfoEx: buffer too small (${buf.size} bytes), expected ≥4 for entryCount")
            return out
        }
        val entryCount = buf.readIntLe()
        Log.i(TAG, "decodeEosObjectInfoEx: bufBytes=$initialSize entryCount=$entryCount")
        if (entryCount <= 0 || entryCount > 100_000) {
            Log.w(TAG, "decodeEosObjectInfoEx: entryCount=$entryCount out of range — aborting decode")
            return out
        }
        repeat(entryCount) { idx ->
            if (buf.size < 4) {
                Log.w(TAG, "decodeEosObjectInfoEx: truncated at idx=$idx (got ${buf.size} bytes left)")
                return out
            }
            val entrySize = buf.readIntLe()
            if (entrySize < 56 || entrySize - 4 > buf.size) {
                Log.w(TAG, "decodeEosObjectInfoEx: bad entrySize=$entrySize at idx=$idx (buf left=${buf.size}) — aborting")
                return out
            }
            val body = buf.readByteArray((entrySize - 4).toLong())
            // Per-entry body decoding — offsets are libgphoto2's PTP_cefe_*.
            val handle = body.readU32Le(0)
            val storageId = body.readU32Le(4)
            val format = body.readU16Le(8)
            val sizeBytes = body.readU32Le(20).toLong() and 0xFFFFFFFFL
            // Filename slot: 13 ASCII bytes starting at body offset 32,
            // null-padded if shorter than 13. Real EOS files are 8.3
            // (IMG_1234.JPG = 12 chars) so 13 is enough.
            val nameEnd = body.indexOfNull(32, 13).coerceAtMost(32 + 13)
            val filename = String(body, 32, nameEnd - 32, Charsets.US_ASCII)
            if (idx < 8) {
                // Log the first few entries to expose what the camera is
                // actually sending. Truncate after 8 to keep logcat usable
                // on big folders.
                Log.i(TAG,
                    "  entry[$idx] handle=0x${"%08X".format(handle)} " +
                    "storage=0x${"%08X".format(storageId)} format=0x${"%04X".format(format)} " +
                    "size=$sizeBytes name='$filename' bodyLen=${body.size}",
                )
            }
            out += com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo(
                handle = handle,
                storageId = storageId,
                format = format,
                filename = filename,
                sizeBytes = sizeBytes,
            )
        }
        Log.i(TAG, "decodeEosObjectInfoEx: decoded ${out.size} entries, leftover=${buf.size}")
        return out
    }

    private fun ByteArray.readU32Le(off: Int): Int =
        ((this[off].toInt() and 0xFF)) or
        ((this[off + 1].toInt() and 0xFF) shl 8) or
        ((this[off + 2].toInt() and 0xFF) shl 16) or
        ((this[off + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.readU16Le(off: Int): Int =
        (this[off].toInt() and 0xFF) or
        ((this[off + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.indexOfNull(from: Int, max: Int): Int {
        val end = (from + max).coerceAtMost(size)
        for (i in from until end) if (this[i] == 0.toByte()) return i
        return end
    }

    /**
     * Fetch every immediate child of [parentHandle] on [storageId] using
     * Canon's vendor opcode [PtpIpConstants.OC_EOS_GET_OBJECT_INFO_EX]
     * (0x9109). One round-trip per directory — vastly faster than
     * standard PTP GetObjectHandles + per-handle GetObjectInfo, AND
     * works on the 6D in EOS Utility mode where standard 0x1008
     * silently hangs on real image handles.
     *
     * Pass `parentHandle = 0xFFFFFFFF` (PTP_HANDLER_SPECIAL) to get
     * the storage's root entries. The returned list includes Format
     * codes so the caller can tell folders (0x3001) from images
     * (0x3801 JPEG, 0xB103 CR2, etc.).
     */
    suspend fun eosListFolder(
        storageId: Int,
        parentHandle: Int = 0xFFFF_FFFF.toInt(),
    ): List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo> = commandMutex.withLock {
        val source = commandSource ?: error("Command source not established")
        val txnId = sendOperationRequest(
            operationCode = PtpIpConstants.OC_EOS_GET_OBJECT_INFO_EX,
            // Verified from PC_EOSUTILITY.pcapng frames 1389/1399/1405 —
            // real EOS Utility 2 passes 3rd param = 0x2000 (not 0x100000
            // as in libgphoto2) AND uses dataPhase=NONE (1) even though
            // the response IS a data-in transaction. The 6D firmware
            // treats dataPhase as a state-machine signal; sending
            // DATA_IN (3) here causes the camera to silently drop
            // the request.
            params = listOf(storageId, parentHandle, 0x2000),
            dataPhase = PtpIpConstants.DATA_PHASE_NONE,
        )
        val sink = okio.Buffer()
        val outcome = readDataPhase(
            source = source, sink = sink, onProgress = null,
            operationCode = PtpIpConstants.OC_EOS_GET_OBJECT_INFO_EX, txnId = txnId,
        )
        if (outcome.response.responseCode != PtpIpConstants.RC_OK) return@withLock emptyList()
        decodeEosObjectInfoEx(sink)
    }

    /**
     * Drain any queued EOS events before browsing.
     *
     * Why NOT call PCHDDCapacity here: libgphoto2's `config.c:348` only
     * fires it from `camera_canon_eos_update_capture_target` when the
     * user is switching capture destination to HD/PC. It's NOT a generic
     * "unlock file-tree opcodes" handshake — and on the 6D, sending it
     * unsolicited tears down the TCP socket within 4 ms (verified
     * on-device 2026-05-30). Skip it entirely.
     */
    private suspend fun prepareForFileAccess() {
        // 1.5s read timeout via runOperation's parameter — out-of-mutex
        // soTimeout pokes are now overwritten by runOperation itself
        // (Phase 3 moved timeout management inside the mutex).
        runCatching {
            runOperation(
                operationCode = PtpIpConstants.OC_EOS_GET_EVENT,
                dataPhase = PtpIpConstants.DATA_PHASE_DATA_IN,
                readTimeoutMs = 1_500,
            )
        }
    }

    suspend fun listCameraObjects():
        List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo> {
        // EOS Utility 2 prep sequence (verified BYTE-FOR-BYTE from
        // PC_EOSUTILITY.pcapng frames 1357-1411). The full ritual:
        //   1. SetRequestOLCInfoGroup(0x0fff)
        //   2. EOS_GetStorageIDs (0x9101)
        //   3. EOS_GetStorageInfo (0x9102, storage)
        //   4. EOS_GetCameraSupport (0x913F) — first call
        //   5. EOS_GetStorageIDs again
        //   6. EOS_GetStorageInfo again
        //   7. EOS_GetCameraSupport (0x913F) — second call (after 0x9109s)
        // Steps 4 + 7 are the missing unlock — without 0x913F the 6D's
        // Wi-Fi firmware silently drops 0x9109. libgphoto2 never sends
        // 0x913F (USB code path) which is why it doesn't work for
        // legacy Wi-Fi bodies. (gphoto issue #1133.)
        runCatching {
            runOperation(
                operationCode = PtpIpConstants.OC_EOS_SET_REQUEST_OLC_INFO_GROUP,
                params = listOf(0x0fff),
            )
        }.onFailure {
            Log.w(TAG, "listCameraObjects: SetRequestOLCInfoGroup rejected (continuing): ${it.message}")
        }
        // First iteration
        val storages = getEosStorageIds()
        if (storages.isEmpty()) return emptyList()
        for (storage in storages) {
            runCatching {
                runOperation(
                    operationCode = PtpIpConstants.OC_EOS_GET_STORAGE_INFO,
                    params = listOf(storage),
                    dataPhase = PtpIpConstants.DATA_PHASE_NONE,
                )
            }
        }
        // EOS_GetCameraSupport call 1 — supportKind=1, modelId=0x110,
        // packed=0x31210000. Pcap-derived; the 6D needs this exact
        // triple to advance into "listing-ready" state.
        runCatching {
            runOperation(
                operationCode = PtpIpConstants.OC_EOS_GET_CAMERA_SUPPORT,
                params = listOf(0x01000000, 0x00000110, 0x31210000),
            )
        }.onFailure {
            Log.w(TAG, "listCameraObjects: GetCameraSupport(1) rejected (continuing): ${it.message}")
        }
        // Second iteration (EOS Utility repeats the storage queries
        // after GetCameraSupport)
        getEosStorageIds()
        for (storage in storages) {
            runCatching {
                runOperation(
                    operationCode = PtpIpConstants.OC_EOS_GET_STORAGE_INFO,
                    params = listOf(storage),
                    dataPhase = PtpIpConstants.DATA_PHASE_NONE,
                )
            }
        }
        val out = mutableListOf<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo>()
        for (storage in storages) {
            val queue = ArrayDeque<Int>()
            queue.addLast(0xFFFF_FFFF.toInt()) // storage root sentinel
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_OBJECTS_PER_STORAGE) {
                val parent = queue.removeFirst()
                val rootSentinel = 0xFFFF_FFFF.toInt()
                var entries = runCatching {
                    eosListFolder(storageId = storage, parentHandle = parent)
                }.getOrNull() ?: continue
                // Fallback for the 6D's "Smartphone (Camera Connect)" mode:
                // 0x9109 successfully walks the folder tree but returns 0
                // entries for the leaf xxxCANON folder that holds the actual
                // photos. Standard PTP GetObjectHandles(0x1007) DOES return
                // the handle list correctly, so we synthesise ObjectInfo
                // entries from those handles alone.
                //
                // Per-handle GetObjectInfo(0x1008) is intentionally NOT
                // called: the 6D's Smartphone-mode firmware silently HANGS
                // on 0x1008 for non-root handles (verified — each call
                // burned the full 30 s socket timeout, so 164 photos would
                // take 80 minutes). Downstream pairCameraObjects() only
                // uses handle + storageId, so the missing format/filename/
                // size fields don't break the browser. Per-photo metadata
                // (filename, dimensions) is fetched on demand by the
                // preview path when the user taps a tile.
                if (entries.isEmpty() && parent != rootSentinel) {
                    val handles = runCatching {
                        getObjectHandles(storageId = storage, format = 0, parent = parent)
                    }.getOrNull() ?: IntArray(0)
                    if (handles.isNotEmpty()) {
                        Log.i(TAG,
                            "listCameraObjects: 0x9109 empty for parent=0x${"%08X".format(parent)} — " +
                            "synthesising entries from standard PTP GetObjectHandles (${handles.size} handles)",
                        )
                        entries = handles.map { h ->
                            // Synthesize an IMG_NNNN.{CR2|JPG} filename
                            // so the file lands with a human-readable
                            // name in the working folder. Previously
                            // used "0x%08X" which produced opaque blobs
                            // like "0x91910131" with no extension. The
                            // low-nibble parity tells RAW from JPEG.
                            val isRaw = (h and 1) == 1
                            val ext = if (isRaw) "CR2" else "JPG"
                            val seq = (h ushr 4) and 0xFFFF
                            com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo(
                                handle = h,
                                storageId = storage,
                                // 0x3801 = generic EXIF/JPEG, treated as
                                // image by the recurse-or-include branch
                                // below.
                                format = if (isRaw) 0xB103 else 0x3801,
                                filename = "IMG_%04d.%s".format(seq, ext),
                                sizeBytes = 0L,
                            )
                        }
                    }
                }
                val folders = entries.count { it.format == 0x3001 }
                val images = entries.size - folders
                Log.i(TAG,
                    "listCameraObjects: storage=0x${"%08X".format(storage)} " +
                    "parent=0x${"%08X".format(parent)} → ${entries.size} entries " +
                    "($folders folders, $images images)",
                )
                for (entry in entries) {
                    visited++
                    if (entry.format == 0x3001) {
                        queue.addLast(entry.handle)
                    } else {
                        out += entry
                    }
                }
            }
            if (visited >= MAX_OBJECTS_PER_STORAGE) {
                Log.w(TAG, "listCameraObjects: hit MAX_OBJECTS_PER_STORAGE cap " +
                    "($MAX_OBJECTS_PER_STORAGE) for storage 0x${"%08X".format(storage)} — " +
                    "use a smaller SD or split into multiple folders")
            }
        }
        Log.i(TAG, "listCameraObjects: returning ${out.size} images")
        return out
    }

    /**
     * Send an [PtpIpConstants.OPERATION_REQUEST] frame and return its
     * transaction ID. Caller must hold [commandMutex].
     */
    private fun sendOperationRequest(
        operationCode: Int,
        params: List<Int>,
        dataPhase: Int,
    ): Int {
        val sink = commandSink ?: error("Command sink not established")
        val txnId = transactionId.getAndIncrement()
        val buf = PtpIpPacketBuilders.buildOperationRequest(
            operationCode = operationCode,
            transactionId = txnId,
            dataPhase = dataPhase,
            params = params,
        )
        val hex = buf.copy().readByteString().hex()
        Log.i(TAG, "sendOperationRequest: op=0x${"%04X".format(operationCode)} " +
            "txn=$txnId dataPhase=$dataPhase params=$params bytes=$hex")
        sink.writeAll(buf)
        sink.flush()
        return txnId
    }

    /**
     * Internal outcome of [readDataPhase]: the final OperationResponse plus
     * how many bytes were piped to the sink and what the camera advertised.
     */
    private data class DataPhaseOutcome(
        val response: PtpIpPacket.OperationResponse,
        val advertisedSizeBytes: Long,
        val bytesWritten: Long,
    )

    /**
     * Walk the data phase of a PTP transaction: read frames off [source] until
     * the OperationResponse arrives, piping every DATA_PACKET / END_DATA_PACKET
     * body into [sink] via Okio's zero-copy `read(BufferedSink, byteCount)`.
     *
     * Returns the [DataPhaseOutcome] tuple. Throws if an unexpected packet type
     * appears or if the camera drops the connection mid-transaction.
     *
     * Caller must hold [commandMutex].
     */
    @Suppress("LongParameterList")
    private fun readDataPhase(
        source: BufferedSource,
        sink: BufferedSink,
        onProgress: ((bytesWritten: Long, advertisedTotal: Long) -> Unit)?,
        operationCode: Int,
        txnId: Int,
    ): DataPhaseOutcome {
        var advertisedTotal: Long = -1
        var bytesWritten: Long = 0
        // Reusable transfer Buffer. Okio's Segment pool recycles the underlying
        // 8 KB chunks across iterations, so this stays allocation-free in
        // steady state. We don't pipe `source.read(Buffer, n)` directly into
        // `sink` because BufferedSource.read accepts the concrete Buffer type,
        // not the BufferedSink interface — going via this temporary Buffer is
        // the canonical zero-copy pattern (segments are donated, not copied).
        val transferBuffer = Buffer()
        while (true) {
            val framing = PtpIpPacketReader.readFraming(source)
            when (framing.type) {
                PtpIpConstants.START_DATA_PACKET -> {
                    // Body: txnId (4) + totalDataLength (8 LE uint64) [+ optional padding]
                    source.require(12)
                    source.skip(4) // txnId — we already know it
                    advertisedTotal = source.readLongLe()
                    val remaining = framing.bodySize - 12
                    if (remaining > 0) source.skip(remaining)
                }
                PtpIpConstants.DATA_PACKET,
                PtpIpConstants.END_DATA_PACKET -> {
                    // CIPA DC-X005 §3.4 — confirmed by libgphoto2
                    // camlibs/ptp2/ptpip.c and MerrickZ/camlib src/packet.c:
                    // every DATA / END_DATA frame body starts with a 4-byte
                    // TransactionId BEFORE the payload bytes. Strip it
                    // before sinking — decoders downstream want only the
                    // raw PTP array payload.
                    if (framing.bodySize >= 4) {
                        source.skip(4)
                    }
                    var remaining = framing.bodySize - 4
                    // Debug dump for small responses to help diagnose
                    // "0 entries" mysteries — useful when the camera's
                    // response shape doesn't match our decoder. Skipped
                    // for big transfers to keep logcat readable.
                    if (remaining in 1..64) {
                        val peek = source.peek().readByteArray(remaining)
                        Log.i(TAG, "dataPhase payload (${remaining}B): " +
                            peek.joinToString("") { "%02x".format(it) })
                    }
                    while (remaining > 0) {
                        // Okio's read(Buffer, n) may return fewer bytes than
                        // asked (it returns whatever's available in one
                        // segment). Loop until we've drained exactly n bytes,
                        // otherwise we leave tail bytes in the source and
                        // desync the next framing read — the bug that caused
                        // GetDeviceInfo's OPERATION_RESPONSE frame to be
                        // misread as `length=393316` garbage.
                        val got = source.read(transferBuffer, remaining)
                        if (got <= 0) break
                        sink.writeAll(transferBuffer)
                        bytesWritten += got
                        remaining -= got
                        onProgress?.invoke(bytesWritten, advertisedTotal)
                    }
                }
                PtpIpConstants.OPERATION_RESPONSE -> {
                    val response = PtpIpPacketReader.readBody(source, framing)
                            as PtpIpPacket.OperationResponse
                    return DataPhaseOutcome(
                        response = response,
                        advertisedSizeBytes = advertisedTotal,
                        bytesWritten = bytesWritten,
                    )
                }
                else -> {
                    if (framing.bodySize > 0) source.skip(framing.bodySize)
                    error(
                        "Unexpected packet type 0x%08X during op 0x%04X txn=$txnId"
                            .format(framing.type, operationCode)
                    )
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * Try to set EOS PC-tether mode. Returns true if the camera accepted the
     * opcode, false if it returned a non-OK response code. **Never throws on
     * a PTP-layer rejection** — both outcomes leave the session usable.
     * Socket/IO errors still propagate; only response-code failures soft-fail.
     */
    private suspend fun trySetEosRemoteMode(): Boolean {
        // Param = 5 (verified from PC_EOSUTILITY.pcapng frame 1302 — real
        // EOS Utility 2 talking to a 6D). Mode 1 puts the camera into
        // "live view / capture-event-driven" mode where SD-card file
        // opcodes (GetObjectInfo, EOS_GetObjectInfoEx, GetPartialObject)
        // are silently ignored. Mode 5 is the "EOS Utility 2 / image
        // transfer" mode where the full standard PTP + EOS-vendor file
        // tree is honored.
        return runCatching {
            runOperation(
                operationCode = PtpIpConstants.OC_EOS_SET_REMOTE_MODE,
                params = listOf(5),
            ).response.responseCode == PtpIpConstants.RC_OK
        }.getOrElse { false }
    }

    /**
     * Send `EOS_SetDevicePropValueEx(DPC_CANON_EOS_Owner, name)` as the
     * post-pairing engagement marker. Real EOS Utility writes its own
     * computer name here within a few seconds of the user pressing SET
     * on the camera — without it, the 6D fires Err12 "Connection
     * target not found" and tears down the session.
     *
     * Wire (DATA_OUT phase):
     *   1. OPERATION_REQUEST  op=0x9110, no params, dataPhase=DATA_OUT
     *   2. START_DATA_PACKET  txnId + totalDataLength (8 bytes)
     *   3. END_DATA_PACKET    txnId + payload
     *       payload = uint32 totalLength
     *               + uint32 dpc (=0xD115)
     *               + utf16le name + 0x0000 terminator
     *   4. OPERATION_RESPONSE — read and discard
     */
    /**
     * Single helper for every "send an op-request with a DATA_OUT body
     * then drain the OperationResponse" wire pattern. Replaces three
     * near-identical copies (sendOwnerName, writeUint32Dpc,
     * setLvAfFrameProp) of ~50 lines each.
     *
     * The helper:
     *  1. Acquires [commandMutex].
     *  2. Validates [commandSource] / [commandSink] (returns null
     *     responseCode on missing — same semantics as before).
     *  3. Sends the operation request frame.
     *  4. Sends START_DATA + END_DATA with the caller-supplied [payload].
     *  5. Reads back the OperationResponse and returns the
     *     `responseCode`. Caller decides what counts as success.
     *
     * Callers log their own context (DPC code, value, etc.) — this
     * helper just owns the framing.
     *
     * @param logTag short identifier used in diagnostics ("writeUint32Dpc",
     *               "setLvAfFrameProp", etc.)
     * @return responseCode or null on transport failure
     */
    private suspend fun sendDataOutOperation(
        opcode: Int,
        params: List<Int>,
        payload: okio.Buffer,
        logTag: String,
    ): Int? = commandMutex.withLock {
        val source = commandSource
        if (source == null) {
            Log.w(TAG, "$logTag: ignored — command source is null (session not open)")
            return@withLock null
        }
        val sink = commandSink
        if (sink == null) {
            Log.w(TAG, "$logTag: ignored — command sink is null (session not open)")
            return@withLock null
        }
        val payloadLen = payload.size.toInt()
        val txnId = sendOperationRequest(
            operationCode = opcode,
            params = params,
            dataPhase = PtpIpConstants.DATA_PHASE_DATA_OUT,
        )
        // START_DATA_PACKET: header(8) + txnId(4) + totalDataLength(8) = 20 bytes
        val startData = okio.Buffer().apply {
            writeIntLe(20)
            writeIntLe(PtpIpConstants.START_DATA_PACKET)
            writeIntLe(txnId)
            writeLongLe(payloadLen.toLong())
        }
        sink.writeAll(startData)
        // END_DATA_PACKET: header(8) + txnId(4) + payload
        val endData = okio.Buffer().apply {
            writeIntLe(8 + 4 + payloadLen)
            writeIntLe(PtpIpConstants.END_DATA_PACKET)
            writeIntLe(txnId)
            writeAll(payload)
        }
        sink.writeAll(endData)
        sink.flush()
        val framing = PtpIpPacketReader.readFraming(source)
        if (framing.type == PtpIpConstants.OPERATION_RESPONSE) {
            val response = PtpIpPacketReader.readBody(source, framing)
                as PtpIpPacket.OperationResponse
            return@withLock response.responseCode
        }
        if (framing.bodySize > 0) source.skip(framing.bodySize)
        null
    }

    private suspend fun sendOwnerName(name: String): Boolean {
        val nameBytes = (name + " ").toByteArray(Charsets.UTF_16LE) // null-terminate
        val payloadLength = 4 + 4 + nameBytes.size // totalLen + dpc + name
        val payload = okio.Buffer().apply {
            writeIntLe(payloadLength)
            writeIntLe(PtpIpConstants.DPC_CANON_EOS_OWNER)
            write(nameBytes)
        }
        val rc = sendDataOutOperation(
            opcode = PtpIpConstants.OC_EOS_SET_DEV_PROP_VALUE_EX,
            params = emptyList(),
            payload = payload,
            logTag = "sendOwnerName",
        ) ?: return false
        Log.i(TAG, "sendOwnerName: rc=0x${"%04X".format(rc)}")
        return rc == PtpIpConstants.RC_OK
    }

    /**
     * Try to enable the EOS event firehose. Same contract as
     * [trySetEosRemoteMode] — a non-OK response code just means the body
     * doesn't speak this opcode (typical for pre-R-series bodies), not that
     * the session is broken.
     */
    private suspend fun trySetEosEventMode(): Boolean {
        return runCatching {
            runOperation(
                operationCode = PtpIpConstants.OC_EOS_SET_EVENT_MODE,
                params = listOf(1),
            ).response.responseCode == PtpIpConstants.RC_OK
        }.getOrElse { false }
    }

    /**
     * Report the connected PC's HDD capacity (`DS_PCHDDCapacity`, opcode
     * 0x911A). On the 6D this is **not** a pairing-completion handshake —
     * the pcap traffic shows EOS Utility issuing it periodically during a
     * live session as a keepalive/disk-space update, not at connect. Kept
     * here for future periodic-heartbeat wiring; soft-fails on bodies
     * that reject it.
     *
     * Params (matching EDSDK's `DS_PCHDDCapacity (inHDD, inLength, inReset)`):
     *   p1 = inHDD     — disk index, 0 for primary
     *   p2 = inLength  — free space in MB. 4096 (4 GB) is well over any
     *                    per-shot file size the camera cares about.
     *   p3 = inReset   — 0 for routine update, 1 to reset accounting.
     */
    suspend fun reportPcHddCapacity(): Boolean {
        // Returns true if the camera responds AT ALL (even with
        // OperationNotSupported / DeviceBusy). On legacy 6D / 5D-III
        // firmware the body doesn't recognise 0x911A and replies with
        // ResponseCode 0x2005 (OperationNotSupported) — but that round-
        // trip is exactly what we want for keepalive purposes. The active
        // TX+RX wakes the camera's Wi-Fi NIC sleep timer regardless of
        // whether the camera's PTP layer recognised the opcode.
        //
        // Only IOException / SocketTimeout (network actually dead) count
        // as failure. Per-call backoff in the heartbeat loop is driven by
        // the throw, not by the boolean result.
        return runCatching {
            runOperation(
                operationCode = PtpIpConstants.OC_EOS_PC_HDD_CAPACITY,
                params = listOf(0, 4096, 0),
            )
            true  // any response (even !OK) = NIC awake
        }.getOrElse { false }
    }

    /**
     * Send `EOS_KeepDeviceOn` (0x911D) — Canon's explicit session
     * keepalive ping. Unlike [reportPcHddCapacity] which legacy firmware
     * treats as "client error" and IGNORES for idle-timer purposes, this
     * opcode is the documented keepalive that resets the 6D's internal
     * "client idle → close PTP socket" timer. Tested in camlib as
     * `ptp_eos_ping()` and used as the periodic heartbeat in gphoto2's
     * EOS driver for the same legacy bodies we're targeting.
     *
     * No params, returns OK on success. Sending it every <25 s prevents
     * the 6D's firmware-level disconnect at the 30 s mark (observed as
     * `EOFException: null` on our event TCP).
     *
     * Treats any camera response (even !OK) as "alive" for the same
     * reason as [reportPcHddCapacity] — the SEND keeps the NIC awake.
     */
    suspend fun keepDeviceOn(): Boolean {
        // Short socket timeout for the heartbeat specifically — the 6D
        // doesn't always send a response to KeepDeviceOn (just acks it
        // internally and resets its idle timer). With the default 30 s
        // READ_TIMEOUT_MS each ping blocks 30 s waiting for a response
        // that never arrives, which means we ACTUALLY ping every 30 s
        // instead of every 3 s — barely enough margin against the
        // camera's own 30 s disconnect timer.
        //
        // Pass 2 s via runOperation's readTimeoutMs so the timeout
        // sticks under the command mutex (Phase 3 made out-of-mutex
        // soTimeout pokes a no-op when runOperation re-pins it).
        return runCatching {
            runOperation(
                operationCode = PtpIpConstants.OC_EOS_KEEP_DEVICE_ON,
                readTimeoutMs = 2_000,
            )
            Log.i(TAG, "keepDeviceOn: ok (camera replied)")
            true
        }.getOrElse { ex ->
            // Distinguish "camera ate the request but didn't reply"
            // (SocketTimeoutException — the send went through, NIC
            // got woken) from a genuinely dead socket. The first is
            // a successful keepalive; the second is the camera gone.
            val ok = ex is java.net.SocketTimeoutException
            Log.i(TAG, "keepDeviceOn: ${ex.javaClass.simpleName}: ${ex.message} → ok=$ok")
            ok
        }
    }

    // ───────────────── Live Remote Shooting wire layer ─────────────────
    // Wire formats verified against libgphoto2 master `camlibs/ptp2`
    // (the 5Dm2 code path libgphoto2 explicitly marks "verified" — same
    // DIGIC-5 firmware family as the 6D).

    /**
     * Arm the live-view stream. Byte-for-byte mirror of the EU 6D
     * startup sequence in PC_EOSUTILITY_Live-remote-shooting.pcapng
     * (frames 94279-94528):
     *
     *   1. 0x9110 D1BC=3   ← pre-arm DPC (only fires once; libgphoto2
     *                        doesn't doc this but the 6D refuses
     *                        without it)
     *   2. 0x911C          ← ResetUILock used as LV side-effect
     *   3. 0x9110 D1B0=3   ← EVFOutputDevice = PC
     *   4. 0x9110 D1B3=0   ← EVFMode = 0
     *   5. 0x913E(0)       ← LV stream arm latch
     *
     * After this returns true, the next [fetchViewFinderFrame] call
     * begins returning frames (with a typical 30-50 0xA102 warm-up
     * poll burst first). Returns false on any wire failure; the
     * caller surfaces it as LiveViewState.Error.
     */
    suspend fun startViewFinder(): Boolean {
        // Step 1: pre-arm DPC. Critical for 6D — without this every
        // 0x9153 returns 0xA102 forever.
        val preArmOk = writeUint32Dpc(
            PtpIpConstants.DPC_CANON_EOS_EVF_PRE_ARM, value = 3,
        )
        if (!preArmOk) {
            Log.w(TAG, "startViewFinder: D1BC=3 (pre-arm) refused")
            return false
        }
        // Step 2: ResetUILock side-effect. Non-fatal on failure.
        runCatching {
            runOperation(
                operationCode = PtpIpConstants.OC_EOS_RESET_UI_LOCK,
                dataPhase = PtpIpConstants.DATA_PHASE_NONE,
            )
        }.onFailure { Log.d(TAG, "startViewFinder: 0x911C non-fatal: ${it.message}") }
        // Step 3: EVFOutputDevice = PC.
        val outputOk = writeUint32Dpc(
            PtpIpConstants.DPC_CANON_EOS_EVF_OUTPUT_DEVICE, value = 3,
        )
        if (!outputOk) {
            Log.w(TAG, "startViewFinder: D1B0=3 (output device) refused")
            return false
        }
        // Step 4: EVFMode = 0.
        val modeOk = writeUint32Dpc(
            PtpIpConstants.DPC_CANON_EOS_EVF_MODE, value = 0,
        )
        if (!modeOk) {
            Log.w(TAG, "startViewFinder: D1B3=0 (EVFMode) refused")
            return false
        }
        // Step 5: LV stream arm latch.
        val latchOk = runOpReturningOk(
            opcode = PtpIpConstants.OC_EOS_LV_STREAM_LATCH,
            params = listOf(0),
            logTag = "startViewFinder.latch",
        )
        if (!latchOk) {
            Log.w(TAG, "startViewFinder: 0x913E(0) latch refused")
            return false
        }
        return true
    }

    /**
     * Tear down the live-view stream. Mirror of [startViewFinder] in
     * reverse order. Best-effort — every step is wrapped in
     * runCatching so partial state still gets cleaned up.
     */
    suspend fun stopViewFinder() {
        runOpReturningOk(
            opcode = PtpIpConstants.OC_EOS_LV_STREAM_LATCH,
            params = listOf(1),
            logTag = "stopViewFinder.unlatch",
        )
        runOpReturningOk(
            opcode = PtpIpConstants.OC_EOS_TERMINATE_VIEWFINDER,
            logTag = "stopViewFinder.terminate",
        )
        runCatching {
            writeUint32Dpc(PtpIpConstants.DPC_CANON_EOS_EVF_OUTPUT_DEVICE, value = 0)
        }.onFailure { Log.w(TAG, "stopViewFinder: D1B0=0 failed", it) }
        runCatching {
            writeUint32Dpc(PtpIpConstants.DPC_CANON_EOS_EVF_MODE, value = 0)
        }.onFailure { Log.w(TAG, "stopViewFinder: D1B3=0 failed", it) }
    }

    /**
     * Pull one viewfinder frame. Returns null when the camera replies
     * `RC_DEVICE_BUSY` (0x2019) — caller should back off ~5 ms and
     * retry, matching libgphoto2's polling discipline. Returns an
     * empty buffer if the camera replies OK but the payload was
     * stripped (e.g. EVFOutputDevice rolled back to 0 mid-loop).
     *
     * Frame envelope: Canon's magic `FF FF FF FF` container header
     * + AF/WB metadata block + JPEG starting at `FF D8`. The decoder
     * lives in the UI layer and scans for the SOI marker.
     */
    suspend fun fetchViewFinderFrame(): okio.Buffer? = commandMutex.withLock {
        val source = commandSource ?: error("Command source not established")
        val saved = commandSocket?.soTimeout ?: READ_TIMEOUT_MS
        // Tight timeout — at 10-15 fps any frame slower than 1 s
        // already means the stream is stalled and a re-arm is in order.
        commandSocket?.soTimeout = 2_000
        try {
            val txnId = sendOperationRequest(
                operationCode = PtpIpConstants.OC_EOS_GET_VIEWFINDER_DATA,
                params = listOf(0x00200000, 0, 0),
                dataPhase = PtpIpConstants.DATA_PHASE_NONE,
            )
            val sink = okio.Buffer()
            val outcome = readDataPhase(
                source = source, sink = sink, onProgress = null,
                operationCode = PtpIpConstants.OC_EOS_GET_VIEWFINDER_DATA, txnId = txnId,
            )
            return@withLock when (outcome.response.responseCode) {
                PtpIpConstants.RC_OK -> sink
                // Both response codes mean "EVF armed but no frame buffered
                // yet — retry shortly". libgphoto2 polls in a tight loop
                // with ~5 ms backoff for up to 3 s after first LV arm.
                PtpIpConstants.RC_DEVICE_BUSY,
                PtpIpConstants.RC_CANON_EOS_DEVICE_BUSY -> null
                else -> {
                    Log.w(TAG, "fetchViewFinderFrame: rc=0x${"%04X".format(outcome.response.responseCode)}")
                    null
                }
            }
        } finally {
            commandSocket?.soTimeout = saved
        }
    }

    /**
     * Standalone autofocus pulse — does NOT take a shot. Wired to the
     * "AF-On" button on the live-shoot screen. libgphoto2's
     * `ptp_canon_eos_afdrive`; matches Canon EDSDK `DoEvfAf(1)`.
     * Returns true on the camera ack'ing with RC_OK.
     */
    suspend fun doAf(): Boolean =
        runOpReturningOk(PtpIpConstants.OC_EOS_DO_AF)

    /** Cancel any in-flight AF — `0x9160 EOS_AfCancel`. */
    suspend fun afCancel() {
        runOpReturningOk(PtpIpConstants.OC_EOS_AF_CANCEL)
    }

    /**
     * Manual-focus nudge. `step` encodes both direction and magnitude
     * via the high-bit convention:
     *   0x0001 / 0x0002 / 0x0003 — near (toward camera) small/med/large
     *   0x8001 / 0x8002 / 0x8003 — far (away from camera) small/med/large
     * Use [LensFocusStep] for type-safety.
     */
    suspend fun driveLens(step: Int): Boolean =
        runOpReturningOk(PtpIpConstants.OC_EOS_DRIVE_LENS, params = listOf(step))

    /**
     * Move the FlexiZone-AF rectangle via Magic Lantern's confirmed
     * 6D-compatible path: opcode `0x915A` writing property
     * `PROP_LV_AFFRAME (0x80050007)`. The DATA_OUT body is the
     * full `aff[]` int array — minimum 6 entries:
     *   `[sensorW, sensorH, x, y, boxW, boxH]`.
     *
     * For the 6D the sensor is 5472×3648. The minimum AF box position
     * is (500, 500) per ML's COERCE bounds; we pass through the
     * caller's coords (clamped above this call).
     *
     * Returns true on `RC_OK`. Non-OK rc means either:
     *   - AF method is not FlexiZone (switch via `DPC_EOS_AF_METHOD`)
     *   - LV is not running (call after `startViewFinder` succeeds)
     *   - The 6 entries aren't enough for the 6D's payload (would
     *     need a real `0xC189` capture of the camera's current
     *     `aff[]` to round-trip the reserved fields).
     */
    suspend fun setLvAfFrameProp(
        boxX: Int,
        boxY: Int,
        boxWidth: Int = PtpIpConstants.LV_AFFRAME_DEFAULT_BOX_PX,
        boxHeight: Int = PtpIpConstants.LV_AFFRAME_DEFAULT_BOX_PX,
        sensorWidth: Int = PtpIpConstants.SENSOR_6D_WIDTH,
        sensorHeight: Int = PtpIpConstants.SENSOR_6D_HEIGHT,
    ): Boolean {
        // Clamp to ML's documented bounds: MIN_COORD..(sensor - box).
        val minCoord = PtpIpConstants.LV_AFFRAME_MIN_COORD_PX
        val clampedX = boxX.coerceIn(minCoord, sensorWidth - boxWidth)
        val clampedY = boxY.coerceIn(minCoord, sensorHeight - boxHeight)
        // Build the aff[] int array. 6 entries × 4 bytes = 24-byte body.
        // Magic Lantern preserves a longer trailing payload from the
        // camera's own announcement — we don't have that yet, so send
        // the minimum the protocol documents. If the camera rejects,
        // we'll add the full round-trip via 0xC189 parsing.
        val payload = okio.Buffer().apply {
            writeIntLe(sensorWidth)
            writeIntLe(sensorHeight)
            writeIntLe(clampedX)
            writeIntLe(clampedY)
            writeIntLe(boxWidth)
            writeIntLe(boxHeight)
        }
        val payloadLen = payload.size.toInt()
        val rc = sendDataOutOperation(
            opcode = PtpIpConstants.OC_EOS_SET_LV_AF_FRAME_PROP,
            params = listOf(PtpIpConstants.PROP_LV_AFFRAME, payloadLen),
            payload = payload,
            logTag = "setLvAfFrameProp",
        ) ?: return false
        Log.i(TAG, "setLvAfFrameProp: x=$clampedX y=$clampedY rc=0x${"%04X".format(rc)}")
        return rc == PtpIpConstants.RC_OK
    }

    /**
     * Half-press the shutter: send `0x9128(1, 0)` then poll OLCInfo
     * events for ~3 s waiting for the in-focus byte pattern. Returns
     * true once the camera signals focus lock OR the poll times out
     * (libgphoto2's safety net — the 5Dm2 will full-press even without
     * confirmed lock, and the camera itself decides whether to actually
     * release the shutter).
     */
    /**
     * Half-press shutter, NO autofocus. Use for "shoot now, focus is
     * already where I want it" (manual focus on the camera, or after a
     * prior tap-to-focus on LV).
     *
     * Wire shape from EOS Utility pcap:
     *   0x9128 params=(1, 0) — half-press, NO AF
     *   0x9128 params=(1, 1) — half-press WITH AF (see [halfPressShutterWithAf])
     */
    suspend fun halfPressShutter(): Boolean = runOpReturningOk(
        opcode = PtpIpConstants.OC_EOS_REMOTE_RELEASE_ON,
        params = listOf(1, 0),
        logTag = "halfPressShutter",
    )

    /**
     * Half-press shutter WITH autofocus — `0x9128 (1, 1)`. Used by the
     * AF-On button: hold AF-On to lock focus, then shutter button
     * fires the shot via the no-AF [halfPressShutter] / [fullPressShutter]
     * path.
     */
    suspend fun halfPressShutterWithAf(): Boolean = runOpReturningOk(
        opcode = PtpIpConstants.OC_EOS_REMOTE_RELEASE_ON,
        params = listOf(1, 1),
        logTag = "halfPressShutterWithAf",
    )

    /**
     * Full-press the shutter: send `0x9128(2, 0)`. Returns true on the
     * camera ack'ing — the actual image-ready event arrives later via
     * the event poller as either 0xC181 (card destination) or 0xC186
     * (host destination).
     *
     * Lens-dependent latency: the 5Dm2 can take up to 8 s to ack with
     * a slow lens. The command channel already uses [READ_TIMEOUT_MS]
     * (30 s) which absorbs that.
     */
    /**
     * Full-press shutter, NO AF. The shot fires immediately regardless
     * of focus state. Use for after-AF-locked shooting and explicit
     * manual-focus capture. Wire `0x9128 (2, 0)` per EOS Utility pcap.
     */
    suspend fun fullPressShutter(): Boolean = runOpReturningOk(
        opcode = PtpIpConstants.OC_EOS_REMOTE_RELEASE_ON,
        params = listOf(2, 0),
        logTag = "fullPressShutter",
    )

    /**
     * Release the full-press half of the shutter. Always called after
     * [fullPressShutter] so we don't leave the camera latched.
     */
    suspend fun releaseFullPress() {
        runOpReturningOk(
            opcode = PtpIpConstants.OC_EOS_REMOTE_RELEASE_OFF,
            params = listOf(2),
        )
    }

    /**
     * Release the half-press. Mirror-image of [halfPressShutter] —
     * always called as the last step of a capture sequence even on
     * the failure path so the camera isn't left half-pressed.
     */
    suspend fun releaseHalfPress() {
        runOpReturningOk(
            opcode = PtpIpConstants.OC_EOS_REMOTE_RELEASE_OFF,
            params = listOf(1),
        )
    }

    /**
     * Open the bulb shutter. Mode dial must already be on Bulb (or M
     * with shutter speed set to Bulb on bodies that allow it); the
     * camera will return non-OK otherwise. Call [bulbEnd] to close.
     */
    suspend fun bulbStart(): Boolean =
        runOpReturningOk(PtpIpConstants.OC_EOS_BULB_START)

    /**
     * EOS_CancelTransfer (0x9118) — host-initiated abort of an in-flight
     * partial-object download. Per the 6D research, the correct cancel
     * sequence is: stop new 0x9107 chunks, send `0x9118 <handle>`,
     * and **do not** send 0x9117 TransferComplete for that handle.
     * The camera then frees its transfer state and we can move on.
     */
    suspend fun cancelTransfer(handle: Int): Boolean = runOpReturningOk(
        opcode = PtpIpConstants.OC_EOS_CANCEL_TRANSFER,
        params = listOf(handle),
    )

    /** Close a bulb exposure started with [bulbStart]. */
    suspend fun bulbEnd(): Boolean =
        runOpReturningOk(PtpIpConstants.OC_EOS_BULB_END)

    /**
     * Route post-capture images. `1` = card-only (camera fires 0xC181),
     * `4` = host-only (camera fires 0xC186 and waits for download),
     * `3` = both. Returns true on the write being acknowledged.
     */
    suspend fun setCaptureDestination(value: Int): Boolean =
        writeUint32Dpc(PtpIpConstants.DPC_CANON_EOS_CAPTURE_DESTINATION, value)

    /**
     * `GetDevicePropDesc (0x1014)` for [dpc]. Returns the decoded
     * descriptor or null on transport / decode failure. Single-shot per
     * call; the controller layer caches results across calls.
     *
     * Standard-PTP opcode — works on every body that implements PTP/IP,
     * including 6D-era firmware. Param[0] is the DPC code; data phase
     * is the descriptor blob.
     */
    suspend fun getDevicePropDesc(
        dpc: Int,
    ): com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.EosPropertyDescriptor? {
        val outcome = runCatching {
            runOperation(
                operationCode = PtpIpConstants.OC_GET_DEVICE_PROP_DESC,
                params = listOf(dpc),
                dataPhase = PtpIpConstants.DATA_PHASE_DATA_IN,
            )
        }.onFailure {
            Log.w(TAG, "getDevicePropDesc: dpc=0x${"%04X".format(dpc)} threw " +
                "${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull() ?: return null
        if (outcome.response.responseCode != PtpIpConstants.RC_OK) {
            Log.w(TAG, "getDevicePropDesc: dpc=0x${"%04X".format(dpc)} " +
                "rc=0x${"%04X".format(outcome.response.responseCode)} (rejected)")
            return null
        }
        val payload = outcome.dataPhasePayload
        if (payload == null) {
            Log.w(TAG, "getDevicePropDesc: dpc=0x${"%04X".format(dpc)} returned OK with no payload")
            return null
        }
        val payloadSize = payload.size
        val decoded = DevicePropDescDecoder.decode(payload)
        if (decoded == null) {
            Log.w(TAG, "getDevicePropDesc: dpc=0x${"%04X".format(dpc)} decode failed " +
                "(payload=$payloadSize bytes)")
        } else {
            Log.i(TAG, "getDevicePropDesc: dpc=0x${"%04X".format(dpc)} ok " +
                "dt=0x${"%04X".format(decoded.dataType)} " +
                "cur=${decoded.currentValue} form=${decoded.form::class.simpleName}")
        }
        return decoded
    }

    /**
     * Write the camera-side value for [dpc] via `SetDevicePropValueEx
     * (0x9110)`. Sized by the live descriptor: every Canon EOS scalar
     * DPC we care about fits in 4 bytes once we sign-extend / zero-extend
     * the smaller wire types, so the existing [writeUint32Dpc] is the
     * primitive. Returns true on a `RC_OK` ack.
     */
    suspend fun setProperty(dpc: Int, value: Long): Boolean =
        writeUint32Dpc(dpc, value.toInt())

    /**
     * Generic SetDevicePropValueEx (0x9110) for any UINT32-valued DPC.
     * Used by the live-shoot setup (EVF mode / output device / capture
     * destination) and reusable by chip pickers for ISO/SS/Av/WB writes
     * in a later slice.
     *
     * Wire body for 0x9110: `uint32 totalLength` + `uint32 dpc` +
     * `uint32 value`. Wrapped in START_DATA + END_DATA PTP/IP frames
     * — same pattern as [sendOwnerName] above.
     */
    private suspend fun writeUint32Dpc(dpc: Int, value: Int): Boolean {
        val payloadLength = 4 + 4 + 4 // totalLen + dpc + value
        val payload = okio.Buffer().apply {
            writeIntLe(payloadLength)
            writeIntLe(dpc)
            writeIntLe(value)
        }
        val rc = sendDataOutOperation(
            opcode = PtpIpConstants.OC_EOS_SET_DEV_PROP_VALUE_EX,
            params = emptyList(),
            payload = payload,
            logTag = "writeUint32Dpc dpc=0x${"%04X".format(dpc)}",
        ) ?: return false
        Log.i(TAG, "writeUint32Dpc: dpc=0x${"%04X".format(dpc)} val=$value " +
            "rc=0x${"%04X".format(rc)}")
        return rc == PtpIpConstants.RC_OK
    }

    /**
     * Step magnitudes for [driveLens]. Match Canon EDSDK's
     * `EvfDriveLens_{Near,Far}{1,2,3}` enum.
     */
    enum class LensFocusStep(val wireValue: Int) {
        NEAR_SMALL(0x0001),
        NEAR_MEDIUM(0x0002),
        NEAR_LARGE(0x0003),
        FAR_SMALL(0x8001),
        FAR_MEDIUM(0x8002),
        FAR_LARGE(0x8003),
    }

    /**
     * Best-effort teardown. Per claude advanced plan.md §4.5 we attempt the
     * EOS event/remote-mode deactivation and the standard CloseSession before
     * closing the TCP sockets, because some EOS bodies lock the PTP session
     * otherwise and require a power-cycle to release.
     */
    suspend fun close() = withContext(Dispatchers.IO) {
        // Stop the readers first so they don't fight us for the command channel.
        runCatching { eosPollerJob?.cancelAndJoin() }
        runCatching { eventReaderJob?.cancelAndJoin() }
        runCatching { pingJob?.cancelAndJoin() }
        eosPollerJob = null
        eventReaderJob = null
        pingJob = null

        runCatching {
            if (commandSink != null && commandSource != null) {
                // Only attempt the EOS-mode-off opcodes on bodies that
                // actually accepted them — sending them to legacy bodies just
                // gets two more rejected transactions for no gain.
                if (eventDeliveryMode == EventDeliveryMode.EosVendorPolling) {
                    runCatching {
                        runOperation(PtpIpConstants.OC_EOS_SET_EVENT_MODE, params = listOf(0))
                    }
                    runCatching {
                        runOperation(PtpIpConstants.OC_EOS_SET_REMOTE_MODE, params = listOf(0))
                    }
                }
                runCatching {
                    runOperation(PtpIpConstants.OC_CLOSE_SESSION, params = listOf(SESSION_ID))
                }
            }
        }
        closeQuietly()
        // Don't close the event channel — the consumer may still want to drain
        // any in-flight events. The session is dead for capture either way.
    }

    /**
     * Launch the event-ingest coroutines on [readerScope]. Composition depends
     * on the body's [EventDeliveryMode]:
     *
     *   - **All bodies** start an [EventReader] on the event socket so we
     *     receive standard PTP `ObjectAdded (0x4002)` and the keepalive
     *     PING/PONG packets.
     *   - **R-series and newer** ([EosVendorPolling]) additionally start the
     *     [EosEventPoller] which calls `EOS_GetEvent (0x9116)` to drain the
     *     richer EOS event stream (filename, format + size inline via the
     *     `EOS_ObjectAddedEx 0xC181` record).
     *   - **Older bodies** ([StandardPtpEvents], 6D-era) also start a
     *     [startKeepAliveHeartbeat] coroutine. Their Wi-Fi firmware powers
     *     the radio down within seconds of idle, dropping the TCP
     *     connection; a low-rate PTP property poll keeps the link warm.
     *     R-series bodies don't need this — the EOS poller's own 200 ms
     *     cadence is already heartbeat enough.
     */
    private fun startEventReaders(evtSource: BufferedSource) {
        eventReaderJob = EventReader(
            source = evtSource,
            out = eventChannel,
        ).start(readerScope)

        when (eventDeliveryMode) {
            EventDeliveryMode.EosVendorPolling -> {
                eosPollerJob = EosEventPoller(
                    runGetEvent = ::pollEosEventsOnce,
                    out = eventChannel,
                ).start(readerScope)
            }
            EventDeliveryMode.StandardPtpEvents -> {
                pingJob = startKeepAliveHeartbeat()
            }
        }
    }

    /**
     * Periodic `GetDevicePropValue (0x1015)` against `BatteryLevel (0x5001)`
     * — a cheap, single-byte data-in PTP transaction that every body since
     * the PTP 1.0 era implements. Acts as a Wi-Fi keep-alive on legacy bodies
     * (DIGIC 5+ era, e.g. 6D) whose firmware aggressively powers down the
     * radio after a few seconds of silence.
     *
     * Cadence is 4 s — between user feedback's 3-5 s recommendation. Faster
     * burns battery for no extra reliability; slower lets the 6D's radio
     * sleep between polls and drop the link.
     *
     * Errors are swallowed: a transient failure here shouldn't tear down the
     * session, and a *persistent* failure means the socket is already dead —
     * the next real operation will surface the actual transport error.
     */
    private fun startKeepAliveHeartbeat(): Job = readerScope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(KEEPALIVE_INTERVAL_MS)
            // Try the Canon vendor property first (more reliably populated on
            // DIGIC 5+/6 bodies like the 6D). Falls back to the standard PTP
            // property if the vendor code is rejected — the keepalive doesn't
            // care about the value, only that the socket round-trips.
            val canonOk = runCatching {
                runOperation(
                    operationCode = PtpIpConstants.OC_GET_DEVICE_PROP_VALUE,
                    params = listOf(PtpIpConstants.DPC_EOS_BATTERY_STATUS),
                    dataPhase = PtpIpConstants.DATA_PHASE_DATA_IN,
                ).response.responseCode == PtpIpConstants.RC_OK
            }.getOrElse { false }

            if (!canonOk) {
                runCatching {
                    runOperation(
                        operationCode = PtpIpConstants.OC_GET_DEVICE_PROP_VALUE,
                        params = listOf(PtpIpConstants.DPC_BATTERY_LEVEL),
                        dataPhase = PtpIpConstants.DATA_PHASE_DATA_IN,
                    )
                }
            }
        }
    }

    /**
     * One tick of the EOS event poller: run [PtpIpConstants.OC_EOS_GET_EVENT]
     * and decode the returned data blob into [PtpEvent]s. The poller calls
     * this on a fixed cadence; it serialises with all other command-channel
     * traffic via [commandMutex].
     */
    private suspend fun pollEosEventsOnce(): EosEventPoller.EosPollResult {
        // Skip the GetEvent poll entirely while a bulk thumb/file download
        // is active. The poller runs every 200ms; skipping one or two ticks
        // is harmless — Canon firmware buffers shutter events internally and
        // they are drained on the next tick. Without this guard the 2s
        // GetEvent read-timeout blocks commandMutex and stalls fetchThumb,
        // causing 800ms UI jank spikes during batch thumbnail downloads.
        if (downloadActive.get()) {
            return EosEventPoller.EosPollResult(transactionId = 0, events = emptyList())
        }
        // Short read timeout — same rationale as keepDeviceOn. When the
        // 6D has nothing to report, GetEvent (0x9116) returns quickly
        // with an empty list, but during quiet periods the camera
        // sometimes withholds a response for the full 30s READ_TIMEOUT.
        // That blocks the commandMutex and starves the 3s-cadence
        // KeepDeviceOn heartbeat. Cap THIS CALL to 2s via the
        // per-operation override — [runOperation] manages soTimeout
        // inside the mutex so concurrent shutter / property reads
        // can't race-leak this short value into their own timeouts.
        val outcome = runOperation(
            operationCode = PtpIpConstants.OC_EOS_GET_EVENT,
            dataPhase = PtpIpConstants.DATA_PHASE_DATA_IN,
            readTimeoutMs = 2_000,
        )
        val payload = outcome.dataPhasePayload
        val events = if (payload != null && payload.size > 0) {
            EosEventStreamDecoder.decode(payload, outcome.response.transactionId)
        } else {
            emptyList()
        }
        // Side-channel fan-out of property-change events so the
        // EosPropertyController can invalidate cached descriptors
        // without contending for the single-consumer events channel.
        for (event in events) {
            when (event) {
                is PtpEvent.EosPropertyChanged -> _propertyChanges.tryEmit(event.dpcCode)
                is PtpEvent.EosOlcInfo -> _olcInfo.tryEmit(event)
                else -> Unit
            }
        }
        return EosEventPoller.EosPollResult(
            transactionId = outcome.response.transactionId,
            events = events,
        )
    }

    private fun closeQuietly() {
        runCatching { commandSink?.close() }
        runCatching { commandSource?.close() }
        runCatching { eventSink?.close() }
        runCatching { eventSource?.close() }
        runCatching { commandSocket?.close() }
        runCatching { eventSocket?.close() }
        commandSocket = null
        eventSocket = null
        commandSource = null
        commandSink = null
        eventSource = null
        eventSink = null
    }

    private fun createTunedSocket(): Socket {
        // All knobs must be set BEFORE connect():
        //   - TCP_NODELAY disables Nagle so the 8-byte PTP header doesn't
        //     wait to be coalesced with the next write.
        //   - SO_RCVBUF sets the window-scale advertised in the SYN. Android's
        //     ~128 KB default starves the camera AP's ~200 Mbps link on bursts.
        return Socket().apply {
            tcpNoDelay = true
            receiveBufferSize = RCV_BUFFER_BYTES
            sendBufferSize = SND_BUFFER_BYTES
            soTimeout = READ_TIMEOUT_MS
            keepAlive = true
        }
    }

    private fun describeInitFailReason(code: Int): String = when (code) {
        // 0x00000001: legacy bodies (6D, 5D-III, 7D-II, 70D) usually mean
        // "this host's previous session wasn't torn down cleanly". The 6D's
        // pairing slot is still occupied and the camera rejects the new
        // attempt. Power-cycle the camera (off / on, then re-enable Wi-Fi
        // function) — the AP slot resets on every fresh boot. Also possible:
        // another host (EOS Utility, Camera Connect) is actively connected.
        0x00000001 -> "Camera rejected pairing (busy / stale session). " +
            "Power-cycle the camera and re-enable Wi-Fi function, then try again."
        0x00000002 -> "Camera rejected protocol version (PTP/IP 1.0 not supported)"
        else -> "INIT_FAIL reason 0x%08X".format(code)
    }

    companion object {
        /** logcat filter: `adb logcat -s CanonSync:*` */
        private const val TAG = "CanonSync"

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * Extended timeout used only for the OpenSession step on legacy EOS
         * bodies (6D-era) that block the response until the user presses
         * SET on the camera body to confirm pairing. 90 s gives enough room
         * for the user to walk to the camera, see the prompt, and press it.
         */
        private const val PAIRING_TIMEOUT_MS = 90_000
        private const val RCV_BUFFER_BYTES = 1 shl 20   // 1 MB — pre-connect SO_RCVBUF
        private const val SND_BUFFER_BYTES = 64 * 1024  // 64 KB — commands are tiny
        private const val SESSION_ID = 1
        private const val POST_OPEN_SESSION_WARMUP_MS = 150L

        // EOS 6D sensor + AF-box constants live in [PtpIpConstants] —
        // single source of truth shared with [CanonSyncRepository].

        /**
         * Safety cap on the BFS in [listCameraObjects]. A typical 6D session
         * folder has tens to a few hundred images. 10000 covers a full SD
         * card of bursts without letting a pathological camera-side response
         * loop the walk forever (each handle costs one GetObjectInfo
         * round-trip, so the cap is also a soft latency limit).
         */
        private const val MAX_OBJECTS_PER_STORAGE = 10_000

        /**
         * Safety cap on the 0x910A chunk loop. Per-call payload is
         * ~32 KB from the 6D; a 1620×1080 QuickPreview takes 3-4
         * calls. 16 is a generous upper bound while still bounded
         * if the camera misbehaves and never reaches EOI.
         */
        private const val MAX_THUMB_CHUNKS = 16

        /**
         * Folder-tier depth cap for [listCameraObjects].
         *  - depth 0: storage root entries (DCIM, MISC, …)
         *  - depth 1: per-storage subfolders (DCIM/100EOS6D, …)
         *  - depth 2: image leaves (DCIM/100EOS6D/IMG_*.JPG)
         *
         * Anything we encounter at depth ≥ 2 is treated as a leaf and
         * sent through GetObjectInfo. This avoids the infinite recursion
         * that happened with a "is the top byte ≥ 0x80" folder check —
         * Canon EOS reuses the high-bit handle pattern for both
         * directories AND image objects, so the only safe semantic
         * primitive is plain depth.
         */

        /**
         * Wi-Fi keep-alive cadence for legacy bodies (StandardPtpEvents mode).
         * 4 s sits in the middle of the documented 3-5 s window — fast enough
         * to keep the DIGIC 5+/6 radio out of power-save, slow enough not to
         * spam the camera with property reads.
         */
        private const val KEEPALIVE_INTERVAL_MS = 4_000L
    }
}
