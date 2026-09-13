# Sony Sync — Sony Alpha (A7 II / ILCE-7M2) photo retrieval

Sibling of Canon Sync, but a **different transport**. Canon Sync speaks PTP/IP;
the A7 II copies photos over its own camera Wi-Fi SoftAP using SSDP/UPnP
discovery + HTTP, triggered by NFC one-touch. This doc records the on-device
capture that grounds the feature and the Android 14+ connectivity rules.

## What ships today (scaffold)

- **Navigation card** "Sony Sync" on the main page (`Screen.SonySync`, id 76),
  mirroring Canon Sync's data-driven card wiring.
- Module **`:feature:sony-sync`** (`com.RAZStudio.StudioRoom.feature.sony_sync`):
  `SonySyncComponent` (Decompose + assisted Hilt) + `SonySyncContent` screen that
  renders the connection state machine (`SonyConnectionStep`) and explains the
  NFC/Wi-Fi flow. The join + SSDP + HTTP transfer engine is the next slice.

## On-device capture (the source of truth)

Captured with `adb logcat` for 60 s on the Infinix X6873 test device
(**Android 16 / SDK 36**) while doing Send-to-Smartphone from the A7 II via
NFC. PlayMemories Mobile (`com.sony.playmemories.mobile` 7.8.5) is the app the
A7 II pairs with. Observed sequence:

1. **NFC one-touch** → `NfcService: onRemoteEndpointDiscovered` — the phone read
   the camera's N-Mark NDEF tag, which launched PlayMemories
   (`SplashActivity` → `devicelist.WiFiActivity`).
2. **Wi-Fi join** via the system `WifiNetworkFactory` (the `WifiNetworkSpecifier`
   + `ConnectivityManager.requestNetwork` local-only path):
   `wpa_supplicant: Trying to associate with SSID 'DIRECT-fPE0:ILCE-7M2'` →
   `CTRL-EVENT-CONNECTED`. Security **WPA_PSK**, 2.4 GHz ch. 11. The
   `DIRECT-<4char>:ILCE-7M2` SSID is Sony's SoftAP name; the PSK is supplied
   out-of-band by the NFC tap. (`WifiConfigManager: Cannot find network configKey
   ... WPA_PSK-0` is benign — a specifier/local-only network is not a saved config.)
3. **Addressing**: camera/gateway **192.168.122.1**; phone DHCP'd to
   192.168.0.x /16. No internet on the link (PlayMemories' analytics threw
   `UnknownHostException` — confirms the socket is bound to the camera network).
4. **Discovery**: PlayMemories (uid 10472) took
   `WifiService: acquireMulticastLock lockTag=upnp-device-finder` for ~3 s →
   **SSDP M-SEARCH** (multicast 239.255.255.250) to locate the camera's device
   description, then Camera Remote API / DLNA over HTTP (payload not logged in a
   release build).

**Net:** NFC → `WifiNetworkSpecifier` join `DIRECT-*:ILCE-7M2` (WPA2-PSK) →
SSDP discover camera @192.168.122.1 → HTTP/UPnP pull.

## What the "receive" step delivers (filesystem-verified)

Logcat does **not** log the transfer payload or the saved path — PlayMemories is
a release app and only the OEM `TransferHubManager: resetTransferHub` shows. The
receive requires a manual **OK** tap in the app. Verified instead via the
filesystem + MediaStore after a successful receive:

- **Save location:** `/sdcard/DCIM/Imaging Edge Mobile/` (Sony renamed
  *PlayMemories Mobile* → *Imaging Edge Mobile*; same `com.sony.playmemories.mobile`
  package). Received file e.g. `ACP02243.JPG`, `date_added` matched the transfer.
- **Size/format matters:** the default Send-to-Smartphone sends a **2M resized
  JPEG (1616×1080, ~0.7 MB)**, NOT full-res. Selecting "Original" on the camera
  sends a full-size JPEG (~4–6 MB, e.g. `ACP02260.JPG`/`DSC02270.JPG`).
- **JPEG only:** the A7 II Send-to-Smartphone path delivers **JPEG, never ARW/RAW**.
  Pulling RAW from an A7 II requires USB/PTP or a card reader — worth surfacing in
  the Sony Sync UI so RAW users aren't surprised. (Newer bodies with the full
  Camera Remote API `avContent` can transfer RAW.)

For our own transport we'll write into the app's Default Output (SAF) folder and
should request "Original" size; RAW is out of scope for the A7 II push path.

## Protocol notes

- Sony **Camera Remote API** = JSON-RPC over HTTP POST; services `camera` /
  `system` / `avContent`. `avContent.getContentList` / `getContentCount` /
  `startContentsTransfer` are the download calls. The A7 II (2014) exposes
  `avContent` only in limited form — its real image-pull path is the
  camera-initiated **Send to Smartphone** over the SoftAP (UPnP/DLNA + SSDP),
  which is exactly what the capture shows. Newer Alpha bodies support the full
  `avContent` API and can also be targeted this way.

## Android 14+ connectivity rules (device is on Android 16)

- Cannot silently join an arbitrary SSID since API 29. Use
  `WifiNetworkSpecifier.Builder(ssid, wpa2Passphrase)` +
  `ConnectivityManager.requestNetwork(...)`, then `bindProcessToNetwork(network)`
  in `onAvailable` so SSDP/HTTP route to the camera SoftAP rather than the phone's
  default network.
- **Not** Wi-Fi Direct / `WifiP2pManager`: the camera is an infrastructure SoftAP
  despite the `DIRECT-` SSID prefix.
- **NFC** = out-of-band credentials: read the N-Mark NDEF via
  `NfcAdapter.enableReaderMode` (Android Beam is gone since API 29), parse the
  Wi-Fi SSID/passphrase, feed into `WifiNetworkSpecifier`.
- Manifest needs `CHANGE_WIFI_MULTICAST_STATE` (SSDP), `NEARBY_WIFI_DEVICES`,
  `NFC`, fine location, and `FOREGROUND_SERVICE_CONNECTED_DEVICE` — declared in
  `feature/sony-sync/src/main/AndroidManifest.xml`.

## Files (card wiring)

- `core/ui/.../navigation/Screen.kt` — `Screen.SonySync` (id 76)
- `core/ui/.../navigation/ScreenUtils.kt` — import + `typedEntries` +
  `MAIN_PAGE_SCREEN_IDS` (76) + `simpleName`/`icon`/`twoToneIcon` branches
- `core/resources/.../values/strings.xml` — `sony_sync` / `sony_sync_sub`
- `feature/root/.../navigation/ChildProvider.kt` + `NavigationChild.kt` — DI +
  child wiring
- `settings.gradle.kts`, `feature/root/build.gradle.kts` — module include + dep
