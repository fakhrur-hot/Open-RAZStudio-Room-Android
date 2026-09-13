# Canon Sync Pipeline

End-to-end documentation of the `feature:canon-sync` module — how the
RAZStudio Room app talks to a Canon EOS body over Wi-Fi (PTP/IP) to
browse the SD card, fetch thumbnails, and download full-resolution
JPEG/RAW files.

Target hardware: **Canon EOS 6D** (DIGIC 5+, "Remote control (EOS
Utility)" mode), but the layer is built to also work with newer EOS R
mirrorless bodies that share the PTP/IP wire format.

The phone runs an **Android Wi-Fi hotspot**; the camera joins it as a
station. No router, no infrastructure Wi-Fi required.

---

## 1. Architecture at a glance

```
┌──────────────────────────────────────────────────────────────────┐
│  Compose UI                                                      │
│  CanonSyncScreen.kt           ← grid, preview canvas, buttons    │
└────────────────────────┬─────────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────────┐
│  Decompose Component                                             │
│  CanonSyncComponent.kt        ← thread hops, nav, in-flight gate │
└────────────────────────┬─────────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────────┐
│  Repository (Singleton)                                          │
│  CanonSyncRepository.kt       ← session owner, StateFlows,       │
│                                  Stage A→B→C orchestration       │
└──────┬──────────┬──────────────┬────────────────┬────────────────┘
       │          │              │                │
       ▼          ▼              ▼                ▼
┌────────────┐ ┌────────────┐ ┌─────────────┐ ┌────────────────┐
│ Network    │ │ UPnP/SSDP  │ │ PTP/IP      │ │ SAF capture    │
│ Binder     │ │ Discoverer │ │ Client      │ │ target          │
│ .kt        │ │ .kt        │ │ (Canon-     │ │ SafCapture-     │
│            │ │            │ │  WifiClient)│ │ Target.kt       │
└────────────┘ └────────────┘ └─────────────┘ └────────────────┘
                                     │
                                     ├── HeartbeatLoop (0x911D)
                                     ├── EventReader (cmd-stream)
                                     ├── EventReader (event-socket)
                                     └── ObjectInfoDecoder
```

Foreground service: `CanonSyncCaptureService` keeps the radio hot
while connected (WIFI_MODE_FULL_HIGH_PERF Wi-Fi lock, FGS oom_adj).

---

## 2. Connection lifecycle

### 2.1 Phase order

```
Idle
  │
  ▼  user taps Connect
Acquiring-Network        ← NetworkBinder.acquireCameraWifi()
  │
  ▼  hotspot AP is up
Discovery (RACE)         ← SSDP + subnet scan in parallel, first hit wins
  │     ├─ SSDP path:    WftUpnpDiscoverer.discoverFirst()
  │     │                M-SEARCH → camera replies → fetch CameraDevDesc.xml
  │     │                **with EOS-Utility User-Agent** (arms cam pairing)
  │     └─ Scan path:    TCP probe :15740 across hotspot /24
  │                      (250 ms/host, ~1–2 s typical;
  │                       isActive check between hosts → ≤ 250 ms cancel)
  ▼
InitCommand RETRY LOOP   ← NO pre-probe of :15740 (consumes the camera's
  │                        one-shot listener slot, see §2.5).
  │                        Loop up to 45 s:
  │                          1. CanonWifiClient().connect(...)
  │                          2. On ECONNREFUSED → re-issue CameraDevDesc.xml
  │                             GET with EOS-Utility User-Agent (nudge cam
  │                             pairing state machine), wait 2 s, retry.
  │                          3. Non-refused failure → abort.
  │
  ▼
[ON FIRST EVER PAIRING]  Camera body shows "EOS Utility found — pair?"
                         after a few GET nudges. User presses SET to confirm.
  │
  ▼
PTP/IP-InitCommand       ← CanonWifiClient.connect()
  │   (Canon-namespaced host GUID `0001-{stable_hex}`,
  │    NOT the camera's `0001-FFFFFFFFFFFF` sentinel)
  ▼
PTP/IP-InitEvent         ← second socket on :15740
  │
  ▼
OpenSession (txn=0)
  │
GetDeviceInfo (warmup)
  │
EOS_SetRemoteMode(5)     ← param=5, NOT 1 — confirmed against EOS
  │                        Utility wire trace; 1 leaves event firehose
  │                        closed.
EOS_SetEventMode(1)
  │
EOS_GetEvent (drain)
  │
EOS_SetRequestOLCInfoGroup(0xfff)
  │
EOS_GetStorageIDs
  │
EOS_GetStorageInfo (per storage)
  │
EOS_GetCameraSupport (x2, EU pattern)
  │
HeartbeatLoop starts     ← 0x911D KeepDeviceOn every 8 s
  │
Connected
```

### 2.2 Discovery — SSDP and subnet scan, in parallel

The 6D's UPnP advertiser is **one-shot**: it fires `NOTIFY ssdp:alive`
only at the moment the camera joins the AP, then goes silent. A
serial SSDP-then-scan flow makes the user wait the full SSDP timeout
(60 s) before falling back; by then the camera has often timed itself
out and shows Err11 on the body.

`CanonSyncRepository.connect()` races both methods with a
`CompletableDeferred<Pair<…>>`-based primitive (we tried
`kotlinx.coroutines.selects.select` first but the `Deferred.onAwait`
clauses didn't resume promptly when one branch completed — adb showed
a 36 s delay between SSDP success and the select returning). The
primitive: first probe to find the camera fills the deferred,
`winner.await()` resumes the caller immediately, the loser is
cancelled.

- **SSDP** — `WftUpnpDiscoverer.discoverFirst()` joins multicast
  `239.255.255.250:1900` and waits for the camera's `NOTIFY ssdp:alive`
  advertising `ICPO-WFTEOSSystemService:1` (legacy 6D / 5D-III / 7D)
  or `ICPO-SmartPhoneEOSSystemService:1` (mirrorless).
- **Subnet scan** — `scanForPtpIpOnHotspotSubnet()` reads the phone's
  Wi-Fi interface addresses (`192.168.43.x` / `192.168.49.x` /
  `192.168.238.x` …), derives the /24 prefix, and TCP-probes
  `:15740` on every other host with a 250 ms connect timeout. Finds
  a silent-but-listening camera in 1–2 s.

Race policy:
1. If SSDP wins with a non-null `CanonCameraAdvertisement`, use it
   (better, because we get the camera-issued `X_targetId`).
2. If subnet scan wins, give SSDP a 750 ms grace period to also
   produce a fresh `X_targetId`; otherwise commit to the scan
   result with `preferences.hostGuid()` as the InitCommand GUID.
3. If neither lands, surface "Camera not found. Power-cycle …".

**Cooperative cancellation inside the scan loop** is mandatory:
`Socket.connect(timeout=250)` is a blocking syscall the coroutine
runtime can't preempt, so we check `isActive` between each host
probe and bail-out as `return@withContext null` when the race
loser is cancelled. Without that check, a `coroutineScope`-scoped
race waits for the entire scan to finish (38 s worst case on a /24)
before returning — by which time the camera-side pairing window has
closed and InitCommand hits `ECONNREFUSED`. Verified in adb against
both broken (36 s gap) and fixed (sub-second gap) variants.

### 2.3 The CameraDevDesc.xml GET — User-Agent matters

Once SSDP yields a `LOCATION:` URL we issue:

```
GET /upnp/CameraDevDesc.xml HTTP/1.1
Cache-Control: no-cache
Connection: Close
Pragma: no-cache
Accept: text/xml, application/xml
User-Agent: Microsoft-Windows/10.0 UPnP/1.0
Host: {cameraIp}:{port}
```

The User-Agent string is **load-bearing**, not cosmetic. The 6D
firmware inspects the GET's User-Agent and only transitions out of
"Searching for EOS Utility…" / arms its PTP/IP listener on port
15740 when it sees the `Microsoft-Windows/…UPnP/1.0` signature.
Default Dalvik/Java User-Agent is silently ignored — the camera
stays in pairing-wait state forever and any TCP connect to port
15740 fails with `ECONNREFUSED`. Verified byte-for-byte against
frame 738 of `PC_EOSUTILITY_Select-and-Download.pcapng`.

From the response XML we parse:
- `<friendlyName>` → the model label shown in the UI.
- `<X_targetId>` → see §2.4 — the "host slot" identifier. **Not**
  the value we send back as our InitCommand GUID.

### 2.4 Host GUID format and the unpaired-sentinel trap

`INIT_COMMAND_REQUEST` carries a 16-byte host GUID that the camera
uses to identify which host is pairing. The Canon format is:

```
00000000-0000-0000-0001-{12_hex_digits}
       Canon namespace          host's "MAC"
```

PC EOS Utility builds this from the host's Wi-Fi MAC. Android 6+
randomises the MAC reported to apps (`02:00:00:00:00:00`), so
`CanonSyncPreferences.hostGuid()` instead derives 12 stable hex
digits from a one-shot `UUID.randomUUID()` and persists them
across sessions. The 6D doesn't validate the trailing 12 hex digits
against anything; it just needs:

1. The fixed `00000000-0000-0000-0001-` prefix (Canon namespace).
2. A 12-hex tail that is **NOT** `FFFFFFFFFFFF`.

The trap that bit us originally: the 6D's `CameraDevDesc.xml`
advertises `X_targetId = 00000000-0000-0000-0001-FFFFFFFFFFFF` in
**two** cases:
- No host has paired yet (camera in pre-pairing state), AND
- As a default placeholder between sessions.

Echoing that sentinel back in InitCommand tells the camera "I am the
un-paired ghost host" — the firmware doesn't fire its on-body "EOS
Utility found — pair?" confirmation and never arms port 15740.

`CanonSyncRepository.connect()` detects the sentinel and substitutes
the persisted host GUID instead:

```kotlin
val sentinel = UUID.fromString("00000000-0000-0000-0001-ffffffffffff")
initGuid = if (ssdpAd.targetId == sentinel)
    preferences.hostGuid()       // 0001-{stable_per_install_hex}
else
    ssdpAd.targetId              // already-paired session, echo back
```

`hostGuid()` self-upgrades any legacy random `UUID.randomUUID()`
value persisted by earlier builds — the `isCanonNamespacedHostGuid`
check on read regenerates a Canon-format GUID if the stored value
isn't already in the right shape.

The Microsoft-GUID byte ordering (first three groups swapped to LE,
last two groups BE) still applies on the wire — handled by
`PtpIpPacketBuilders.buildInitCommandRequest`.

### 2.5 InitCommand retry loop — never pre-probe :15740

The 6D's PTP/IP listener on port 15740 is a **one-shot accept**: the
firmware opens it briefly after a successful `CameraDevDesc.xml` GET,
accepts the *first* TCP handshake, and **closes the listener again**
until the user takes another action on the body. Any code that
probes the port before issuing InitCommand burns that single slot —
adb captured this exact pattern:

```
T+0 ms   probe Socket.connect(:15740) → OPEN
T+9 ms   real CanonWifiClient.connect(:15740) → ECONNREFUSED
```

Our previous `waitForPtpListener` helper that polled port 15740 was
self-defeating for the same reason. Removed.

The current connect path:

1. Discovery picks `effectiveCameraIp` + `initGuid` (§2.2, §2.4).
2. **Go straight to `CanonWifiClient.connect(...)`** — no probe.
3. On `ECONNREFUSED`, the camera-side pairing state machine isn't
   armed yet → re-issue the `CameraDevDesc.xml` GET with the
   EOS-Utility User-Agent to nudge the firmware, `delay(2_000)`,
   spawn a fresh `CanonWifiClient` (the prior one's socket is dead),
   retry InitCommand.
4. Loop for up to 45 s. Each retry cycle:
   - Sends one GET (sustains the EOS-Utility-shaped HTTP presence
     the firmware looks for).
   - Attempts one InitCommand.
   - Sleeps 2 s.
5. Any non-`ECONNREFUSED` failure aborts immediately
   (`INIT_FAIL`, `PtpResponseError`, `SocketTimeout`, …) — those mean
   retries can't help.
6. On 45 s timeout, surface
   *"Camera shows 'Searching for EOS Utility'. Press SET on the camera
   body to confirm pairing, then tap Connect again."*

This pattern matches what PC EOS Utility does on the wire — repeated
`GET /upnp/CameraDevDesc.xml` every few seconds across the SSDP
cycle, with InitCommand bashing through whenever the camera opens
its window. The on-body "EOS Utility found — pair?" prompt fires
after a few of these GET nudges, the user presses SET, and the
*next* retry's InitCommand goes through cleanly.

### 2.6 NetworkBinder

`acquireCameraWifi()` is intentionally **minimal**:
- No `ConnectivityManager.requestNetwork()` call.
- No `WifiNetworkSpecifier` / SSID matcher.
- No system Wi-Fi picker dialog.

Reason: when the phone is the hotspot AP, the camera AP interface is
**not** Android's default network — it's an "AP-mode tether". Calling
`requestNetwork` against a `WifiNetworkSpecifier` would tell Android
to *associate* to a Wi-Fi infrastructure SSID, which forces a Wi-Fi
disassociate/reassociate cycle that the 6D's firmware interprets as a
session drop. So we return `NetworkBindResult.Acquired(network=null)`
and let the PTP sockets ride the system default (or implicit tether
route).

This is the same thing Canon's own Camera Connect app does —
verified via `dumpsys connectivity` against
`jp.co.canon.ic.cameraconnect`.

### 2.7 PTP/IP framing

`PtpIpPacket` / `PtpIpPacketBuilders` implement the CIPA DC-X005
wire format. Every packet is `[len:u32][type:u32][payload...]` LE.

Packet types we use:
- `0x01 INIT_COMMAND_REQUEST`
- `0x02 INIT_COMMAND_ACK`
- `0x03 INIT_EVENT_REQUEST`
- `0x04 INIT_EVENT_ACK`
- `0x06 OPERATION_REQUEST`
- `0x07 OPERATION_RESPONSE`
- `0x09 START_DATA_PACKET`
- `0x0A DATA_PACKET`
- `0x0C END_DATA_PACKET`
- `0x08 EVENT` (event socket only)

All data containers (start/middle/end) carry a **4-byte txnId prefix**
inside the payload — libgphoto2-confirmed. The data-phase decoder
strips this prefix before handing bytes to the consumer.

The host GUID inside `INIT_COMMAND_REQUEST` is encoded in
**Microsoft-GUID byte order** (first three groups swapped to LE, last
two groups BE) — see §2.4 for the value selection.

---

## 3. Keeping the connection alive

The 6D's Wi-Fi chip has an aggressive hardware sleep timer that drops
the TCP socket when:
- The PTP command channel is idle for >10–30 s, **or**
- No real TX+RX is happening (passive `EOS_GetEvent` polls don't count).

Three layers of defense:

### 3.1 `HeartbeatLoop` — 0x911D KeepDeviceOn every 8 s

Active opcode, no data phase, replies OK. Forces the camera firmware
to process a request and emit a reply, so the radio sees genuine
TX+RX. State-neutral — doesn't disturb metering, doesn't move
focus.

Chosen over `EOS_PCHDDCapacity (0x911A)` because legacy 6D firmware
replies `OperationNotSupported` to PCHDDCapacity AND resets its idle
timer to "not real work". `0x911D` is honoured on every EOS body from
the 5D-III onward.

When the heartbeat reply itself fails (timeout / RC error /
ECONNRESET), `HeartbeatLoop.onSessionDeadDetected` fires and the
repository's auto-reconnect supervisor kicks in.

### 3.2 Foreground service + WifiLock

`CanonSyncCaptureService` (started by `CanonSyncRepository` the moment
state transitions to `Connected`):
- Acquires `WifiManager.createWifiLock(WIFI_MODE_FULL_HIGH_PERF, ...)`
  for the lifetime of the session. Defeats Android's Wi-Fi power-save
  mode that otherwise produces ~200ms latency spikes mid-transfer and
  occasional AP drops.
- Bumps oom_adj to FOREGROUND so Doze / LMK can't pause coroutines.
- Survives screen-off and app backgrounding.

### 3.3 Auto-reconnect supervisor

`CanonSyncRepository.scheduleAutoReconnect()` walks an exponential
backoff (2 s → 4 s → 8 s → 16 s → 30 s, capped) when the connection
drops while `lastConnectArgs` is still set (user did NOT tap
Disconnect). Cleared when the user explicitly disconnects.

Important guard: reconnect requests come from **exactly one** place
(`HeartbeatLoop.onSessionDeadDetected`). The original implementation
also spawned reconnects from `connect()` failure transitions, which
chained parallel reconnect loops on top of itself.

---

## 4. Browsing the SD card

User taps the Browse icon → component calls
`repository.refreshCameraObjects()`.

### 4.1 Enumeration sequence

```
EOS_GetStorageIDs (0x9101)                ← uint32[] of storage slots
  │
  ▼  for each storage:
EOS_GetStorageInfo (0x9102, storageId)
  │
EOS_GetObjectInfoEx (0x9109,
                      storageId, parent=0, format=0x2000)
                                          ← dataPhase=NONE — 6D quirk;
                                            DATA_IN times out.
                                            param3=0x2000 = "all
                                            objects on this storage"
  │
  ▼
ObjectInfoDecoder parses the data-in blob into
  List<ObjectInfo>(handle, storageId, format, filename, sizeBytes)
```

The 6D in EOS Utility mode silently **ignores** `GetObjectInfo (0x1008)`
and `EOS_GetObjectInfoEx (0x9109)` calls that target an individual
image handle. The only call that works is the bulk enumeration with
parent=0 / format=0x2000.

### 4.2 RAW + JPEG pair detection

Canon EOS bodies in RAW+JPEG mode write each shot as two handles
differing by exactly 1 in the low nibble:

```
0x9190fb61   CR2 (RAW)   ← odd low-nibble
0x9190fb62   JPG          ← even low-nibble
```

`pairCameraObjects()` sorts the raw list by unsigned handle, walks it
in pairs, and emits a single `CameraPhoto(rawHandle, jpegHandle,
displayName, storageId)` per shot. Stragglers (camera was in
single-format mode) get a tile with only one side filled.

The list is reactive on `(cameraObjects, formatMode)` — toggling the
RAW / JPEG / RAW+JPG filter recomputes synchronously, no network
round-trip.

### 4.3 Tile naming

The 6D in EOS Utility mode does NOT return filenames in `0x9109`'s
response for image handles (only metadata and storage info). So we
synthesise the tile label from the handle:

```kotlin
"IMG_${"%04d".format((handle ushr 4) and 0xFFFF)}"
```

This roughly tracks the camera's own image counter, so the labels stay
consistent with what the user sees on the camera body.

---

## 5. Thumbnails

Two distinct paths — small grid thumbs and big canvas preview.

### 5.1 Small grid thumbs (`startGridThumbsFetch`)

For each `CameraPhoto` in the grid, the repository fetches a
~16 KB EXIF-embedded JPEG via `EOS_GetThumbEx (0x910A)`:

```
For each unique (storageId, parentFolder) pair:
  EOS_GetCTGInfo (0x9135, storage, parent, 3, 0x2000)
      ← primes the camera-side thumb cache. Without this the 6D
        returns ONLY the EXIF-only thumb (~1 KB) instead of the
        QuickPreview (~95 KB). Verified at
        PC_EOSUTILITY_Select-and-Download.pcapng frame 151.

For each photo:
  EOS_GetThumbEx (0x910A, handle, maxBytes=0xFFFFFFFF)
      ← dataPhase=NONE
      ← writes to cacheDir/canon-thumb/{handle:08x}.jpg
      ← APPEND synthetic FF D9 EOI marker (the 6D's response can
        end mid-XMP without a JPEG EOI; BitmapFactory is strict)
```

Idempotency guard: `lastGridThumbsHandles: Set<Int>` is compared
against the new handle set before scheduling. Without this guard
Compose's `LaunchedEffect(cameraPhotos)` re-fires on every recompose
(new `List<>` instance, even when content-equal) and chains
"scheduling 64 thumbs" loops forever, each cancelling the previous.

UI side: `gridThumbs: StateFlow<Map<Int, File>>` is observed by the
grid; tiles render their bitmap as each handle's file appears.

### 5.2 Big canvas preview (`previewCameraObject`)

User taps a tile → repository streams the full file into
`cacheDir/canon-preview/`:

- For RAW+JPEG pairs: prefer the JPEG sibling (5–8 MB; instantly
  decodable by BitmapFactory).
- For RAW-only shots: stream the CR2 (~25 MB). The UI's
  `decodePhotoFile()` helper scans for the `FF D8 FF` SOI marker
  inside the TIFF wrapper and decodes the embedded preview JPEG.

`PreviewState` machine:
```
Idle ──tap──> Loading(handle, filename) ──success──> Ready(file)
                       │                                  │
                       └─error/cancel──> Idle  <──dismiss─┘
```

Why no fast preview opcode? On the 6D in EOS Utility mode both
`GetThumb (0x100A)` and `EOS_GetThumbEx (0x910A)` on image handles
return only the EXIF metadata header for the JPG sibling (no scan
data). The CR2 sibling's 0x910A returns ~95 KB of full QuickPreview
JPEG — that's what we use for the SMALL grid thumb. But the **big**
canvas preview needs decoded scan data at full resolution, which
forces a full download.

---

## 6. Downloading photos

Two entry points, same wire layer:

- **Edit button** → `editCameraPhoto()` → downloads RAW-preferred,
  navigates to RAWEditor on success.
- **Download button** → `downloadCameraPhoto()` → downloads the user's
  picked variant (RAW or JPG), stays on the canvas.

Both ultimately call `streamObjectViaPartial()` on the wire client.

### 6.1 Chunked transfer (`streamObjectViaPartial`)

```
For offset = 0 step CHUNK (≈ 1 MB = 0xFF000 bytes):
  EOS_GetPartialObject (0x9107, handle, offset, CHUNK)
      ← dataPhase=NONE
      ← data-in container streams up to CHUNK bytes
      ← write into the SAF sink as bytes arrive
  Until camera returns short chunk OR cumulative bytes == ObjectInfo.sizeBytes
EOS_TransferComplete (0x9117, handle)
      ← mandatory terminator. Without this the 6D fires Err12
        "Connection target not found" within seconds.
```

Why chunked, not `GetObject (0x1009)`? Legacy 6D firmware times out
mid-transfer for files >50 MB when streamed as a single response.
Chunked 0x9107 reads the same image more reliably and lets us show
byte-accurate progress.

### 6.2 SAF staging + atomic rename (`SafCaptureTarget`)

Every download writes to a `.part` staging file first, then renames
to the final name on success:

```
open(IMG_4027.CR2):
  createFile("IMG_4027.CR2.part", "application/octet-stream")
  openOutputStream(stagingUri, "w")
  wrap in BufferedOutputStream(128 KB)
                 ← 128 KB picked per perf plan; default 8 KB is
                   catastrophic on SAF (every flush is a Binder IPC).

finalize():
  sink.flush(); sink.close(); bufferedStream.close()
                 ← order matters: SAF writes are not durable until
                   the bufferedStream is closed.
  stagingFile.renameTo("IMG_4027.CR2")
  return refreshed Uri from directory.findFile(final name)
                 ← critical: on the external-storage SAF provider
                   the document ID is path-derived, so the
                   pre-rename stagingFile.uri becomes invalid the
                   moment renameTo() completes. Returning the stale
                   stagingUri caused RAWEditor's copy-in step to
                   fail with "Missing file … IMG_4027.CR2.part".

discard():
  sink.close(); bufferedStream.close(); stagingFile.delete()
                 ← called on any failure path.
```

Collision handling: `uniqueFinalFilename` picks `IMG_4027_1.CR2`,
`IMG_4027_2.CR2`, … if the requested name already exists. Matches the
gallery viewer's expectations.

### 6.3 Destination folder resolution

Priority order in `CanonSyncComponent.downloadCameraPhoto()`:

1. `explicitFolder` param — set when the caller just got a folder Uri
   back from the SAF picker, before the `workingDirUri` StateFlow has
   propagated. Avoids a race.
2. `workingDirUri.value` — the app-wide Working Directory.
3. Per-session armed folder fallback (`_armedFolderUri`).
4. If still null → log + Toast, abort.

The `+New Folder` fallback flow:
- User taps Edit/Download with no Working Directory configured.
- Canvas closes, SAF folder picker opens.
- On pick: persist as Working Directory AND immediately resume the
  chosen action (Edit or Download) with the picked Uri as
  `explicitFolder`.

### 6.4 Edit flow detail

`editCameraPhoto()` is `downloadCameraPhoto()` with an extra step:

```kotlin
onComplete = { uri ->
    componentScope.launch(Dispatchers.Main) {
        if (uri != null) onNavigate(Screen.RawEditor(uri = uri))
    }
}
```

The `Dispatchers.Main` hop is **mandatory** — Decompose's root
navigation crashes if called from a background thread, and the repo
invokes `onComplete` from its IO coroutine.

---

## 7. Phone-AP UI state

`PhoneWifiObserver` polls `WifiManager.isWifiApEnabled()` reflectively
every 2 s and emits a `PhoneWifiState` StateFlow. The UI shows:
- 🟢 Green pip when `HotspotActive`.
- 🔴 Red pip when hotspot is off.

We use reflection because `isWifiApEnabled` is hidden API on most
Android versions but present on every device since API 26. The
fallback (no hotspot detected) just shows red.

---

## 8. Concurrency model

| Concern                       | Mechanism                                           |
|-------------------------------|-----------------------------------------------------|
| Command-channel serialisation | `Mutex` inside `CanonWifiClient` — Canon firmware   |
|                               | rejects overlapping ops with `RC_DEVICE_BUSY`       |
|                               | and desyncs txnIds.                                 |
| Session scope                 | `SupervisorJob() + Dispatchers.IO` on the repo.     |
| UI thread hops                | Component wraps Toast / nav / state in              |
|                               | `Dispatchers.Main` blocks before invoking UI.       |
| Connect button storm guard    | `connectInFlight: MutableStateFlow<Boolean>`        |
|                               | gates `repository.connect()` re-entry — without it  |
|                               | a mash-tapping user produced 50+ Failed transitions |
|                               | in 8 s.                                             |
| Grid-thumb storm guard        | `lastGridThumbsHandles: Set<Int>` cancels self-     |
|                               | restart from Compose `LaunchedEffect` re-fires.     |

---

## 9. Error surfaces

| Code path                          | Surface                              |
|------------------------------------|--------------------------------------|
| Discovery (SSDP + scan) both null  | "Camera not found. Power-cycle the   |
|                                    | 6D and re-enter 'Remote control      |
|                                    | (EOS Utility)' mode, then tap        |
|                                    | Connect." → Toast                    |
| InitCommand `ECONNREFUSED` × 45 s  | "Camera shows 'Searching for EOS     |
|                                    | Utility'. Press SET on the camera    |
|                                    | body to confirm pairing, then tap    |
|                                    | Connect again." → Toast              |
| InitCommandRequest rejected        | `ConnectResult.InitRejected` → Toast |
| OpenSession non-OK                 | `ConnectResult.PtpResponseError`     |
| HeartbeatLoop session death        | `connectionState = Disconnected` +   |
|                                    | auto-reconnect supervisor fires.     |
| Download chunk transfer failure    | `ObjectTransferResult.Failed` →      |
|                                    | discard staging file, Toast.         |
| RAW copy into RAWEditor's          | RAWEditor's pink overlay shows the   |
| Working Directory                  | `FileNotFoundException`. Fixed by    |
|                                    | the SafCaptureTarget Uri-refresh.    |

---

## 10. PTP/IP opcode quick-reference

| Opcode  | Name                              | DataPhase | Used for                          |
|---------|-----------------------------------|-----------|-----------------------------------|
| 0x1001  | GetDeviceInfo                     | DATA_IN   | Warmup after OpenSession          |
| 0x1002  | OpenSession                       | NONE      | Session establishment             |
| 0x1003  | CloseSession                      | NONE      | Disconnect                        |
| 0x1015  | GetDevicePropValue                | DATA_IN   | (Unused; superseded by 0x911D)    |
| 0x101B  | GetPartialObject (std)            | DATA_IN   | (Unused; we use 0x9107)           |
| 0x9101  | EOS_GetStorageIDs                 | DATA_IN   | Enumerate SD card slots           |
| 0x9102  | EOS_GetStorageInfo                | DATA_IN   | Per-storage capacity / label      |
| 0x9107  | EOS_GetPartialObject              | NONE      | **Chunked download (1 MB chunks)**|
| 0x9108  | EOS_GetDeviceInfoEx               | DATA_IN   | Post-pair "host engaged" marker   |
| 0x9109  | EOS_GetObjectInfoEx               | NONE      | **Bulk image enumeration**        |
| 0x910A  | EOS_GetThumbEx                    | NONE      | **Small grid thumbnails**         |
| 0x9110  | EOS_SetDevicePropValueEx          | DATA_OUT  | Owner-name write post-pair        |
| 0x9114  | EOS_SetRemoteMode                 | NONE      | **param=5** to open event firehose|
| 0x9115  | EOS_SetEventMode                  | NONE      | Enable event channel              |
| 0x9116  | EOS_GetEvent                      | DATA_IN   | Drain event queue                 |
| 0x9117  | EOS_TransferComplete              | NONE      | **Mandatory after chunked dl**    |
| 0x911A  | EOS_PCHDDCapacity                 | NONE      | (Legacy heartbeat — replaced)     |
| 0x911B  | EOS_SetUILock                     | NONE      | Lock body UI for remote control   |
| 0x911C  | EOS_ResetUILock                   | NONE      | Reverse on disconnect             |
| 0x911D  | EOS_KeepDeviceOn                  | NONE      | **Active heartbeat (8 s cadence)**|
| 0x9135  | EOS_GetCTGInfo                    | NONE      | **Prime thumb cache per folder**  |
| 0x913D  | EOS_SetRequestOLCInfoGroup        | DATA_OUT  | Event-group selector (0xfff)      |
| 0x913F  | EOS_GetCameraSupport              | NONE      | EU pattern — x2 before 0x9109     |

---

## 11. File map

```
feature/canon-sync/src/main/java/.../canon_sync/
├── data/
│   ├── CanonSyncPreferences.kt      ← DataStore: working dir, format mode
│   └── SafCaptureTarget.kt          ← .part-staged SAF writes + atomic rename
├── domain/
│   ├── CanonSyncRepository.kt       ← Singleton session owner, StateFlows
│   ├── CaptureCoordinator.kt        ← Live-shoot capture pipeline (separate)
│   ├── CaptureTarget.kt             ← Allocation/sink interface
│   ├── FormatFilter.kt              ← RAW/JPG/RAW+JPG filter logic
│   └── models/
│       ├── CameraDescriptor.kt      ← From CameraDevDesc.xml
│       ├── CameraPhoto.kt           ← RAW+JPG paired tile model
│       ├── ConnectResult.kt
│       ├── ConnectionState.kt
│       ├── FormatMode.kt
│       ├── NetworkBindResult.kt
│       ├── ObjectInfo.kt            ← One row in the SD-card manifest
│       ├── ObjectTransferResult.kt
│       └── PtpEvent.kt
├── net/
│   ├── CanonWifiClient.kt           ← PTP/IP session + opcodes (the wire)
│   ├── EosEventPoller.kt            ← 0x9116 background drain
│   ├── EosEventStreamDecoder.kt     ← Parse 0xC181 / 0xC1A7 records
│   ├── EventReader.kt               ← Event-socket reader
│   ├── HeartbeatLoop.kt             ← 0x911D every 8 s
│   ├── NetworkBinder.kt             ← acquireCameraWifi() — no requestNetwork
│   ├── ObjectInfoDecoder.kt         ← Parse 0x9109 data-in blob
│   ├── PtpIpConstants.kt            ← All opcodes/format codes/RCs
│   ├── PtpIpPacket.kt               ← Wire-format reader
│   ├── PtpIpPacketBuilders.kt       ← Wire-format writer
│   ├── PtpUint32ArrayDecoder.kt
│   ├── StandardEventDecoder.kt
│   └── WftUpnpDiscoverer.kt         ← SSDP listener + CameraDevDesc fetch
├── presentation/
│   ├── CanonSyncScreen.kt           ← Compose UI (grid, canvas, buttons)
│   ├── PhoneWifiObserver.kt         ← Hotspot polling (reflection)
│   ├── PhoneWifiState.kt
│   └── screenLogic/
│       └── CanonSyncComponent.kt    ← Decompose component, thread hops
└── service/
    ├── BatteryOptimizationHelper.kt ← Whitelist nag flow
    ├── CanonSyncCaptureService.kt   ← FGS + WIFI_MODE_FULL_HIGH_PERF lock
    ├── OemKeepAliveGuide.kt         ← MIUI/Oppo/Honor-specific guidance
    └── SessionDurationTracker.kt
```

---

## 12. Tuning knobs

| Knob                                | Value           | Source                              |
|-------------------------------------|-----------------|-------------------------------------|
| TCP `SO_RCVBUF`                     | 1 MB            | `CanonWifiClient` pre-connect       |
| `TCP_NODELAY`                       | true            | `CanonWifiClient` pre-connect       |
| SAF write buffer                    | 128 KB          | `SafCaptureTarget.BUFFER_BYTES`     |
| Chunked download chunk size         | ≈ 1 MB (0xFF000)| `PtpIpConstants.EOS_PARTIAL_CHUNK_BYTES` |
| Heartbeat cadence                   | 8 s             | `HeartbeatLoop`                     |
| SSDP timeout                        | 60 s            | `WftUpnpDiscoverer`                 |
| Auto-reconnect backoff              | 2 / 4 / 8 / 16 / 30 s | `CanonSyncRepository`         |
| Hotspot polling cadence             | 2 s             | `PhoneWifiObserver`                 |
| Subnet-scan probe timeout           | 250 ms / host   | `scanForPtpIpOnHotspotSubnet`       |
| InitCommand retry window            | 45 s            | `CanonSyncRepository.connect`       |
| InitCommand retry interval          | 2 s             | `CanonSyncRepository.connect`       |
| SSDP→scan grace period              | 750 ms          | `CanonSyncRepository.connect`       |

---

## 13. Wire-trace references

PCAP captures used to validate every nontrivial wire detail above:

- `PC_EOSUTILITY.pcapng` — full EOS Utility 2 session (idle, connect,
  capture). Frame 1357 confirms the `0x913D` value (0xfff). Frames
  1374 + 1411 confirm the two `0x913F` calls before the first 0x9109.
- `PC_EOSUTILITY_Select-and-Download.pcapng` — image browser flow.
  Frame 151 confirms `0x9135` cache-priming before 0x910A iteration.
  Frames 160/251/339/425/509/592 confirm the descending-handle
  thumbnail iteration pattern. Frame 738 carries the
  `GET /upnp/CameraDevDesc.xml` request whose User-Agent header
  (`Microsoft-Windows/10.0 UPnP/1.0`) is what arms the 6D's PTP/IP
  listener and fires its on-body "EOS Utility found — pair?" prompt.
  Frame 742's response body shows the camera advertising
  `X_targetId = 00000000-0000-0000-0001-FFFFFFFFFFFF` even after a
  successful pairing — proving this is a default-placeholder value,
  not a "currently-paired-host" marker, and must NOT be echoed back
  in InitCommand.
- `PC_EOSUTILITY_Download-Image-To-PC_All-images-raw-jpeg.pcapng` —
  bulk download. Frame 84 confirms the 0x9107 chunk size (0xF0000).

These files live in the project root for byte-for-byte protocol
comparison against new wire issues.
