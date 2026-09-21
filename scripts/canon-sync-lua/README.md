# Canon Sync — Magic Lantern Lua helpers

Three on-camera scripts to assist debugging and operating Canon Sync (Type 1,
phone-as-client) against a 6D running Magic Lantern. They run on the camera
body itself, not on the phone. They cannot sniff PTP/IP traffic (no socket
API in ML Lua) but they CAN automate the camera-side actions that have been
the friction point in every Connect attempt.

## What each script does

| Script | Purpose | When to run |
|---|---|---|
| `canon_sync_probe.lua` | **Phase 1 discovery.** Passively watches `PROP_GUI_STATE` (and a few other properties) for 60 seconds, logs every change to `ML/LOGS/CSYNC_P.LOG`. Used to find the specific `GUI_STATE` value that the 6D shows when the "Connect this smartphone? RAZStudio" pairing prompt is on screen. | **Once**, before using `canon_sync_auto_pair`. The value is firmware-version-specific. |
| `canon_sync_auto_pair.lua` | **Phase 2 auto-confirm.** Polls `PROP_GUI_STATE` every 100ms; when it matches the configured `PAIRING_STATE_ID`, the script presses SET via `key.press(KEY.SET)`. Eliminates the 30s "user-must-press-SET-fast-enough" race that's been killing every Connect attempt. | Every time the user wants to pair the phone via the app's Connect button. |
| `canon_sync_diag.lua` | **Diagnostic dump.** 5-minute capture of property transitions + camera state to `ML/LOGS/CSYNC_DG.LOG`. Pair with `adb logcat -s CanonSync:V` on the phone for full picture. | When a Connect attempt fails in a new way and we need camera-side telemetry. |

## Why these scripts exist

The Connect button in RAZStudio Room → Canon Sync gets through these wire-level steps reliably on the 6D:

```
[1/8] cmd TCP connected      local:NNNNN → 192.168.1.2:15740
[2/8] INIT_COMMAND_ACK       connId=1 model='amil'
[3/8] event TCP connected
[3/8] INIT_EVENT_ACK ok
[4/8] OpenSession            << waits here >>
```

Step `[4/8] OpenSession` then **hangs**. The 6D firmware is waiting for the
user to physically press SET on the camera body to confirm a "Connect this
smartphone? RAZStudio" prompt that appears on the rear LCD. If the user
doesn't press SET within ~30 seconds, the 6D's internal timer fires, the
firmware cancels the pairing, and the camera sends TCP RST. The phone sees
`SocketException: Software caused connection abort`.

The phone-side code can't fix this — the 6D is waiting for human input.
`canon_sync_auto_pair.lua` solves it by running ON the camera and pressing
SET for us.

## Setup

### 0. Prerequisites

You already have Magic Lantern installed on the 6D (the `magiclantern_6D_116_2025.zip`
in `C:\Users\Public\Kiro\ML_6D`). If not:

1. Extract the ML zip onto the SD card root.
2. Insert the SD card, boot the camera in Lv mode, run the firmware-update prompt.
3. The card will be made bootable. From then on the camera boots with ML at
   the top of its menu system.

### 1. Copy the Lua scripts to the SD card

Magic Lantern uses 8.3 short filenames for scripts. Copy these files into
`<SD>/ML/SCRIPTS/`:

| Source (this folder) | Destination on SD card |
|---|---|
| `canon_sync_probe.lua` | `<SD>/ML/SCRIPTS/CSYN_PRO.LUA` |
| `canon_sync_auto_pair.lua` | `<SD>/ML/SCRIPTS/CSYN_AP.LUA` |
| `canon_sync_diag.lua` | `<SD>/ML/SCRIPTS/CSYN_DG.LUA` |

ML's Lua engine needs `<SD>/ML/MODULES/lua.mo` enabled (it is by default in
the bundled ML zip).

### 2. Enable Lua module + show the scripts menu

On the camera:

1. `MENU` → tab to Modules → enable **Lua scripting**.
2. Restart the camera.
3. `MENU` → Scripts. The three scripts should now be listed.

## Workflow

### Step A — Run probe ONCE to discover the pairing GUI state ID

You only need to do this the first time, or after a firmware update.

1. On the camera: Menu → Wi-Fi → Connect to smartphone → **Register a device
   for connection** → Camera access point mode → Easy connection. LCD shows
   "Standby for connection".
2. Phone: Settings → Wi-Fi → join the camera's SSID (e.g. `RAZStudio`).
3. On the camera (still on the standby screen): half-press shutter to wake
   if needed, then `MENU` → Scripts → `CSYN_PRO.LUA` → Run script. The ML
   console appears showing "Trigger the pairing prompt now."
4. **Within the next 60 seconds**, on the phone, tap Connect in RAZStudio
   Room → Canon Sync. The 6D's LCD should show the "Connect this
   smartphone?" prompt.
5. **Don't press SET yet** — let the camera firmware time out (after ~30s)
   so the probe captures the entire prompt-shown → prompt-cancelled window.
6. The script finishes automatically after 60 seconds. Press SET on the
   camera to close the ML console.
7. Eject the SD card and read `ML/LOGS/CSYNC_P.LOG` on a computer (or use
   ML's File Manager to view it on the camera). Look for the
   `GUI_STATE=N (was M, dt=NNNms)` line whose timestamp matches when the
   prompt appeared.
8. Open `canon_sync_auto_pair.lua` and update this line:

   ```lua
   local PAIRING_STATE_ID = 91
   ```

   Set it to the N value you found.

### Step B — Use auto-pair for normal Connect attempts

Every time you want to use Canon Sync from the phone:

1. Camera: Menu → Wi-Fi → Connect to smartphone → Register a device. LCD =
   "Standby for connection".
2. Phone: confirm it's joined the camera's Wi-Fi SSID.
3. Camera: `MENU` → Scripts → `CSYN_AP.LUA` → Run script. ML console
   shows "Watching GUI_STATE == N. Now tap Connect on phone."
4. Phone: open RAZStudio Room → Canon Sync → tap **Connect**.
5. The camera LCD shows the pairing prompt for a fraction of a second; the
   script presses SET within 200ms. You'll see "Pairing prompt — pressing
   SET!" on the ML console.
6. Phone-side logcat (filtered with `adb logcat -s CanonSync:V`) should now
   show:

   ```
   [4/8] OpenSession rc=0x2001
   [5/8] GetDeviceInfo rc=0x2001
   [6-7/8] eventDeliveryMode=StandardPtpEvents
   [8/8] starting event readers + (if legacy) keepalive heartbeat
   connect: SUCCESS model='amil' mode=StandardPtpEvents
   ```

7. The app's Camera pill flips green. You're paired.

### Step C — Diagnostic capture (only if something new breaks)

When a Connect attempt fails in a new way and the phone's adb log doesn't
explain it:

1. On the camera: `MENU` → Scripts → `CSYN_DG.LUA` → Run script. Capture
   window is 5 minutes.
2. Trigger the failing scenario on the phone.
3. After it fails, wait for the script to finish (or press SET to exit early).
4. Read `ML/LOGS/CSYNC_DG.LOG`. Send it alongside the phone-side
   `adb logcat -s CanonSync:V` output for diagnosis.

## Known limitations

- **No PTP/IP socket inspection.** ML Lua doesn't expose the network stack.
  These scripts can't see PTP packets or interfere with the camera's Wi-Fi
  firmware directly. They can only observe and automate via PROPAD and
  simulated keypresses.
- **Pairing-state ID is firmware-specific.** The value that
  `canon_sync_probe` captures is valid for whatever firmware you're running
  (likely 6D 1.1.6 since that's the dir name). A different firmware build
  may use a different value — re-run probe after any ML or Canon firmware
  update.
- **The 6D's pairing slot is finite.** The camera remembers ~3 paired hosts.
  After the first successful pairing, subsequent connects from the same
  phone should skip the prompt entirely (no SET press needed). If you ever
  see "Camera busy" `INIT_FAIL(0x01)`, clear the registered-device list on
  the camera (Menu → Wi-Fi → "Edit/delete device" or "Clear settings").
- **`key.press(KEY.SET)` only works while ML is active.** If the camera is
  in pure Canon firmware mode (e.g., during a deep menu navigation that
  suspends ML scripts), the auto-press won't fire. In practice ML runs
  alongside the Wi-Fi pairing UI fine, but be aware.

## File map

```
scripts/canon-sync-lua/
├── README.md                  ← you are here
├── canon_sync_probe.lua       → SD:/ML/SCRIPTS/CSYN_PRO.LUA
├── canon_sync_auto_pair.lua   → SD:/ML/SCRIPTS/CSYN_AP.LUA
└── canon_sync_diag.lua        → SD:/ML/SCRIPTS/CSYN_DG.LUA
```

Output logs land in `<SD>/ML/LOGS/`:

| File | Written by | Contents |
|---|---|---|
| `CSYNC_P.LOG` | probe | Time-series of property changes during the 60s probe window. |
| `CSYNC_AP.LOG` | auto-pair | Each auto-press SET event with timestamps. |
| `CSYNC_DG.LOG` | diag | 5-minute property dump including static camera metadata. |

Pull these off the SD card with `adb pull /sdcard/ML/LOGS/CSYNC_*.LOG` (if
the card is mounted on a phone), or read directly via a card reader.
