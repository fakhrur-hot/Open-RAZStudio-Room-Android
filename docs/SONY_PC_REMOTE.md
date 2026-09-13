# Sony A7 II (ILCE-7M2) — PC Remote (USB PTP) protocol

Reverse-engineered live from USBPcap captures of Sony **Imaging Edge Remote**
driving an A7 II over USB, decoded with `tshark` (USB-PTP dissector + raw
container parsing). This is the reference for the in-app **USB Camera Remote**
feature in `feature/sony-sync`.

## Enumeration / transport
- Camera in **PC Remote** USB mode enumerates as **PTP**: USB
  `Class 06 / SubClass 01 / Protocol 01` (Still Image Capture, PIMA 15740),
  `VID 0x054C` (Sony), `PID 0x0A6A` (A7 II in this mode), USB 2.0. Product
  string `ILCE-7M2`, serial e.g. `CA2240454541`.
- On Windows the `WUDFWpdMtp` driver claims it. On **Android** the app can
  claim the still-image interface via `UsbManager` and speak PTP over the
  bulk-in/bulk-out endpoints (interrupt-in = events).

## PTP container framing (all little-endian)
`len(u32) | type(u16) | code(u16) | transactionId(u32) | params/payload…`
- type: `1`=Command, `2`=Data, `3`=Response, `4`=Event
- Response `0x2001` = OK.

## Session start (from the connect phase)
Standard `OpenSession` (0x1002) then Sony's SDIO handshake
(`0x9201` SDIO_Connect ×3 phases, `0x9202` SDIO_GetExtDeviceInfo). After that
Imaging Edge just **polls** two things continuously (~every 100 ms):
- `GetAllDevicePropData` (**0x9209**) → full device-property table (state).
- Live view: `GetObjectInfo`(0x1008)+`GetObject`(0x1009) on the special object
  handle **`0xFFFFC002`** → a JPEG frame each poll.

## Reading state — GetAllDevicePropData (0x9209)
CMD 0x9209 (no params) → DATA 0x9209. Payload begins with a `u32` **count**
(observed 0x25 = 37 properties) then a Sony device-property-descriptor list:
per property `code(u16), dataType(u16), getSet(u8), sonyGetSet(u8),
factoryDefault(value), currentValue(value), formFlag(u8), form(...)`. Value
width follows `dataType` (0x0002 INT8, 0x0004 UINT16, 0x0006 UINT32, 0xFFFF
STR, etc.). Parse this to populate ISO/F/shutter/EV/DRO/WB/format/etc. live.

## Writing — two control opcodes
Both take the **DevicePropCode as CMD param1** and the value in the DATA phase.
Each set is sent twice by Imaging Edge (idempotent; a re-assert).

### SetControlDeviceA (0x9205) — ABSOLUTE set
CMD 0x9205, param1 = propCode; DATA = the exact new value (datatype-sized).
Confirmed:
- **DRO** `0xD201` = `0x10 | level` (0x11=Lv1 … 0x15=Lv5, 0x1F=Auto). Captured 0x11→0x12→0x13.
- **ImageSize** `0x5004` (captured 0x13, 0x03).
Use for absolute-choice settings: DRO, ImageSize/AspectRatio, WhiteBalance,
capture format (RAW/RAW+JPEG/JPEG), drive mode, focus mode, EV (absolute).

### SetControlDeviceB (0x9207) — RELATIVE step (the ▲▼ arrows)
CMD 0x9207, param1 = propCode; DATA = signed step **`+1` (up)** / **`-1` (down)**
sized to the property's datatype. Confirmed:
- **ISO** `0xD21E` — step value 4 bytes (`01000000`=+1, `ffff0000`=−1).
- **Aperture / FNumber** `0x5007` — step 2 bytes (`ffff`=−1).
- **ShutterSpeed** `0xD20D` — step 2 bytes (`ffff`=−1).
The camera jumps to the next VALID value (respecting mode), which is why
stepping is preferred over absolute for ISO/F/shutter.

## Buttons (AF / shutter / movie) — CAPTURED & CONFIRMED ✓
`SetControlDeviceB` (0x9207), CMD param1 = propcode, DATA = 2-byte value
`0x0002` = **press**, `0x0001` = **release**:
- **Autofocus** propcode **`0xD2C1`** ✓  (press then release)
- **Shutter / Capture** propcode **`0xD2C2`** ✓
- **Snap a photo** = the sequence, in order:
  `0xD2C1←0x0002` (AF press) → `0xD2C2←0x0002` (shutter press) →
  `0xD2C2←0x0001` (shutter release) → `0xD2C1←0x0001` (AF release).
- **Movie record** propcode **`0xD2C8`** ✓ — momentary press (`0x0002`) +
  release (`0x0001`) TOGGLES recording: one press-release starts, a second
  press-release stops (captured: start @49 s, stop @57 s). Works on the A7 II.

## Property-code map (confirmed ✓ / from libgphoto2 ⋯)
| Setting            | PropCode | Set via | Notes |
|--------------------|----------|---------|-------|
| ISO                | 0xD21E ✓ | 0x9207 step | INT32 step |
| Aperture (FNumber) | 0x5007 ✓ | 0x9207 step | value = F×100 (F4.0→400) |
| Shutter speed      | 0xD20D ✓ | 0x9207 step | |
| Exposure comp (EV) | 0x5010 ⋯ | 0x9205 abs | 1/1000 EV units |
| DRO                | 0xD201 ✓ | 0x9205 abs | 0x10\|level |
| Image size         | 0x5004 ✓ | 0x9205 abs | |
| Aspect ratio       | 0xD213 ⋯ | 0x9205 abs | |
| White balance      | 0x5005 ⋯ | 0x9205 abs | |
| Exposure program   | 0x500E ⋯ | read | Mode dial (A/S/M/P) |
| Focus mode         | 0x500A ⋯ | 0x9205 abs | AF-S/AF-C/MF |
| Capture format     | 0xD222 ⋯ | 0x9205 abs | RAW / RAW+JPEG / JPEG (confirm code) |

## Live view
Poll `GetObjectInfo`(0x1008)+`GetObject`(0x1009) on handle **`0xFFFFC002`**.
GetObject DATA phase = a full JPEG (decode straight to a bitmap). Poll at the
UI frame-rate; the A7 II liveview is ~VGA-ish and includes sensor noise.

## Notes for the Android implementation
- Claim the PTP interface, do OpenSession + SDIO_Connect handshake, then run a
  poll loop (state via 0x9209 + a frame via 0xFFFFC002) on an IO thread; push
  parsed state + the latest JPEG to the Compose UI via StateFlow.
- Controls: arrows → 0x9207 step; choice chips → 0x9205 absolute.
- Needs the camera connected to the PHONE via OTG in PC Remote mode.
- USBPcap folder has only the driver/tools — captures were made live; re-run
  `USBPcapCMD.exe -d \\.\USBPcapN -A --inject-descriptors -s 65535 -o x.pcap`
  then `tshark -r x.pcap --disable-protocol usb-ptp -Y "usb.transfer_type==0x03"
  -T fields -e usb.capdata` and parse containers as above.
