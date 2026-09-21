--[[---------------------------------------------------------------------------
canon_sync_auto_pair.lua — PHASE 2 AUTO-CONFIRM

Watches the 6D's PROP_GUI_STATE. When the value matches PAIRING_STATE_ID
(the value canon_sync_probe captured for the "Connect this smartphone?
RAZStudio — Set/Cancel" prompt), the script presses SET automatically
through ML's key.press API.

This eliminates the "race against the 6D's 30-second confirmation timer"
problem that's killing every Connect attempt. The pairing prompt appears
→ the script auto-confirms within ~100 ms → the camera replies to
OpenSession → the phone-side handshake completes.

CONFIGURATION:
  Edit PAIRING_STATE_ID below to match the value canon_sync_probe.lua
  observed when the prompt appeared on your specific firmware. The default
  here is a guess for 6D 1.1.6; if it doesn't fire reliably, run probe
  first and update this value.

Install:
  1. Copy this file to <SD>/ML/SCRIPTS/CSYN_AP.LUA  (8.3 name)
  2. Boot the 6D with ML loaded.
  3. ML menu → Scripts → CSYN_AP.LUA → "Run script"
  4. Camera menu → Wi-Fi → Connect to smartphone → Register a device
  5. Tap Connect in RAZStudio Room on the phone.

The script will press SET for you when the pairing prompt appears.
--]]---------------------------------------------------------------------------

require('logger')

-- Set this to whatever canon_sync_probe.lua reported for the pairing prompt.
-- Common 6D candidates: 91, 92, 138 (these are guesses; probe to confirm).
local PAIRING_STATE_ID = 91

local LOG_PATH = "ML/LOGS/CSYNC_AP.LOG"
local POLL_INTERVAL_MS = 100         -- 10 Hz: fast enough that we press SET
                                     -- within 200ms of the prompt appearing
local AUTO_PAIR_TIMEOUT_MS = 120000   -- 2 min: gives the user time to navigate
                                     -- camera menus + tap Connect on phone
local MAX_PRESSES = 3                -- defensive: in case the prompt repeats

console.clear()
console.show()
print("canon_sync_auto_pair")
print(string.format("Watching GUI_STATE == %d", PAIRING_STATE_ID))
print("Now tap Connect in RAZStudio Room on the phone.")
print("---")

local log = logger(LOG_PATH)
log:writef("--- canon_sync_auto_pair START ---\n")
log:writef("PAIRING_STATE_ID=%d\n", PAIRING_STATE_ID)

local function stamp()
    local d = dryos.date
    return string.format("%02d:%02d:%02d", d.hour, d.min, d.sec)
end

local function read_state()
    local ok, val = pcall(function() return property.GUI_STATE end)
    if not ok then return -1 end
    return tonumber(val) or -1
end

local last_state = read_state()
log:writef("%s  start state=%d\n", stamp(), last_state)
print(string.format("Initial GUI_STATE=%d", last_state))

local presses = 0
local elapsed = 0

while elapsed < AUTO_PAIR_TIMEOUT_MS and presses < MAX_PRESSES do
    local cur = read_state()
    if cur ~= last_state then
        log:writef("%s  GUI_STATE %d -> %d\n", stamp(), last_state, cur)
        print(string.format("GUI_STATE -> %d", cur))
        last_state = cur

        if cur == PAIRING_STATE_ID then
            log:writef("%s  PAIRING PROMPT DETECTED — pressing SET\n", stamp())
            print("Pairing prompt — pressing SET!")
            -- Small debounce so the camera firmware has time to fully
            -- render the prompt before we confirm. Without this the
            -- press can occur during the prompt's transition animation
            -- and the camera ignores it.
            task.yield(150)
            key.press(KEY.SET)
            presses = presses + 1
            log:writef("%s  SET pressed (count=%d)\n", stamp(), presses)
            -- Give the camera a moment to process; otherwise our next
            -- poll catches the same state and we'd press twice.
            task.yield(500)
        end
    end
    task.yield(POLL_INTERVAL_MS)
    elapsed = elapsed + POLL_INTERVAL_MS
end

local exit_reason
if presses >= MAX_PRESSES then
    exit_reason = string.format("max presses (%d) reached", MAX_PRESSES)
elseif presses > 0 then
    exit_reason = string.format("paired ok (%d press)", presses)
else
    exit_reason = "timeout — pairing prompt never seen"
end
log:writef("--- canon_sync_auto_pair END: %s ---\n", exit_reason)
log:close()

print("---")
print(exit_reason)
print("Log: " .. LOG_PATH)
print("Press SET to exit.")
key.wait(KEY.SET)
console.hide()
