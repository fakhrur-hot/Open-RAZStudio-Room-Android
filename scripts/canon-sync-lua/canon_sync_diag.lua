--[[---------------------------------------------------------------------------
canon_sync_diag.lua — DIAGNOSTIC DUMP

Captures a snapshot of the 6D's relevant state to ML/LOGS/CSYNC_DG.LOG:
battery, lens, camera model, every PROP_GUI_STATE transition over a 5-minute
window, and any ML console messages emitted during that window. Useful for
post-mortem analysis when a Connect attempt fails in a new way — pair this
log with the phone's `adb logcat -s CanonSync:V` output to reconstruct what
both sides of the link saw.

Install:
  1. Copy this file to <SD>/ML/SCRIPTS/CSYN_DG.LUA  (8.3 name)
  2. Boot the 6D with ML loaded.
  3. ML menu → Scripts → CSYN_DG.LUA → "Run script"
  4. Within the next 5 minutes, do whatever sequence triggered the failure
     (e.g. tap Connect on the phone, navigate camera menus). The log
     captures everything in the window.
--]]---------------------------------------------------------------------------

require('logger')

local LOG_PATH = "ML/LOGS/CSYNC_DG.LOG"
local DURATION_MS = 5 * 60 * 1000
local POLL_INTERVAL_MS = 250

console.clear()
console.show()
print("canon_sync_diag — 5 minute capture")
print(string.format("Writing %s", LOG_PATH))
print("Trigger the failure now.")
print("Press SET to exit early.")
print("---")

local log = logger(LOG_PATH)
log:writef("=== Canon Sync diagnostic dump ===\n")

-- Static snapshot — values that don't change during the capture window.
log:writef("\n[STATIC]\n")
log:writef("camera_model=%s\n", tostring(camera.model or "?"))
log:writef("firmware=%s\n", tostring(camera.firmware or "?"))
log:writef("lens=%s\n", tostring(lens.name or "no_lens"))
log:writef("battery_level=%s\n", tostring(battery.level or "?"))
log:writef("battery_id=%s\n", tostring(battery.id or "?"))

-- Properties to track over time. Each entry = one row in the time-series log.
local watched = {
    "GUI_STATE",
    "BATTERY",
    "LCD_POSITION",
    "HDMI_CHANGE",
    "USBRCA_MONITOR",
}

log:writef("\n[TIMESERIES] (epoch_ms  PROP=value)\n")

local function stamp_ms()
    return (dryos.ms_clock and dryos.ms_clock()) or 0
end

local function date_stamp()
    local d = dryos.date
    return string.format("%02d:%02d:%02d", d.hour, d.min, d.sec)
end

local function read_prop(name)
    local ok, val = pcall(function() return property[name] end)
    if ok then return tostring(val) else return "<error>" end
end

local last = {}
for _, name in ipairs(watched) do
    last[name] = read_prop(name)
    log:writef("%d  %s  INIT %s=%s\n", stamp_ms(), date_stamp(), name, last[name])
end

print(string.format("Capture window: %d s", DURATION_MS / 1000))
local elapsed = 0
while elapsed < DURATION_MS do
    for _, name in ipairs(watched) do
        local cur = read_prop(name)
        if cur ~= last[name] then
            local line = string.format("%d  %s  %s=%s  (was %s)\n",
                stamp_ms(), date_stamp(), name, cur, last[name])
            log:write(line)
            -- Console echoes only major changes (GUI_STATE) so the screen
            -- doesn't drown in battery-bar updates.
            if name == "GUI_STATE" then
                print(string.format("%s  GUI_STATE=%s", date_stamp(), cur))
            end
            last[name] = cur
        end
    end
    task.yield(POLL_INTERVAL_MS)
    elapsed = elapsed + POLL_INTERVAL_MS
end

log:writef("\n=== capture window ended after %d ms ===\n", elapsed)
log:close()

print("---")
print("Diagnostic capture complete.")
print("File: " .. LOG_PATH)
print("Pull it with: adb pull /sdcard/ML/LOGS/CSYNC_DG.LOG")
print("(or via card reader)")
print("Press SET to exit.")
key.wait(KEY.SET)
console.hide()
