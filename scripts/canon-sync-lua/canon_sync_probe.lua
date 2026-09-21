--[[---------------------------------------------------------------------------
canon_sync_probe.lua — PHASE 1 DISCOVERY SCRIPT

Passive observer that watches the 6D's PROP_GUI_STATE property and logs every
state change to ML/LOGS/CSYNC_P.LOG on the SD card. Run this once with the
camera in Wi-Fi → Connect to smartphone → "Register a device" mode, then
trigger the pairing prompt by tapping Connect in the RAZStudio Room app on
the phone. The log will capture the exact GUI_STATE value that corresponds
to the "Connect this smartphone? — RAZStudio — Set / Cancel" prompt.

We need that value before canon_sync_auto_pair.lua can safely auto-press SET
without confirming the wrong prompts (delete photo, format card, etc.).

Install:
  1. Copy this file to <SD>/ML/SCRIPTS/CSYN_PRO.LUA  (8.3 name)
  2. Boot the 6D with ML loaded.
  3. ML menu → Scripts → CSYN_PRO.LUA → "Run script"

Usage:
  The script prints a single line on the ML console every time GUI_STATE
  changes, and appends the same line to CSYNC_P.LOG. After ~60s it stops
  automatically. Press SET to exit early.

Output format:
  HH:MM:SS  gui_state=N  delta_ms=DDD
  HH:MM:SS  PROP_BATTERY=N
  HH:MM:SS  PROP_LCD_POSITION=N
  ...

Look for the line whose timestamp matches the moment the LCD prompt
appears. The `gui_state=N` value on that line is what canon_sync_auto_pair
needs.
--]]---------------------------------------------------------------------------

require('logger')

local LOG_PATH = "ML/LOGS/CSYNC_P.LOG"
local DURATION_MS = 60 * 1000
local POLL_INTERVAL_MS = 100  -- 10 Hz — fast enough to catch transient prompts

console.clear()
console.show()
print("canon_sync_probe — watching GUI state")
print("Trigger the pairing prompt now.")
print(string.format("Log: %s", LOG_PATH))
print("SET to exit early.")
print("---")

local log = logger(LOG_PATH)
log:writef("--- canon_sync_probe START ---\n")
log:writef("camera_model=%s firmware=%s\n",
    tostring(camera.model or "?"), tostring(camera.firmware or "?"))

-- Properties we care about. Adding more here is cheap — the loop iterates
-- this table on every tick. The pairing prompt almost certainly lives on
-- PROP_GUI_STATE but we also watch a few peripherals because the 6D may
-- surface the pairing dialog through a different prop entirely.
local watched = {
    "GUI_STATE",
    "BATTERY",
    "LCD_POSITION",
    "HDMI_CHANGE",
}

local last = {}
for _, name in ipairs(watched) do last[name] = "<unset>" end

local function read_prop(name)
    local ok, val = pcall(function() return property[name] end)
    if ok then return tostring(val) else return "<error>" end
end

local function stamp()
    local d = dryos.date
    return string.format("%02d:%02d:%02d.%03d",
        d.hour, d.min, d.sec, (dryos.ms_clock or function() return 0 end)() % 1000)
end

local start_ms = dryos.ms_clock and dryos.ms_clock() or 0
local last_change_ms = start_ms

-- Snapshot once at start so we have a baseline.
for _, name in ipairs(watched) do
    last[name] = read_prop(name)
    local line = string.format("%s  INIT %s=%s\n", stamp(), name, last[name])
    log:write(line)
    print(line:sub(1, -2))
end

-- Poll loop with early-exit on SET keypress.
local exit_requested = false
key.press = key.press or function() end  -- defensive: older builds

local function poll_once()
    for _, name in ipairs(watched) do
        local cur = read_prop(name)
        if cur ~= last[name] then
            local now_ms = (dryos.ms_clock and dryos.ms_clock()) or 0
            local delta = now_ms - last_change_ms
            last_change_ms = now_ms
            local line = string.format("%s  %s=%s  (was %s, dt=%dms)\n",
                stamp(), name, cur, last[name], delta)
            log:write(line)
            print(line:sub(1, -2))
            last[name] = cur
        end
    end
end

-- Main loop. task.yield(ms) is the canonical way to sleep in ML Lua.
local elapsed = 0
while elapsed < DURATION_MS do
    poll_once()
    task.yield(POLL_INTERVAL_MS)
    elapsed = elapsed + POLL_INTERVAL_MS
end

log:writef("\n--- canon_sync_probe END (timeout) ---\n")
log:close()

print("---")
print("Probe finished. Check the SD card:")
print("  " .. LOG_PATH)
print("Look for the GUI_STATE line at the timestamp of the pairing prompt.")
print("Press SET to exit.")
key.wait(KEY.SET)
console.hide()
