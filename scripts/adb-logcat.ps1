param(
    [string]$Package = "com.RAZStudio.StudioRoom.debug",
    [string]$TagFilter = "RawV3|Gles|shader|AndroidRuntime|FATAL|libc",
    [switch]$NoClear
)

$ErrorActionPreference = "Stop"

$device = adb devices | Select-String -Pattern "\tdevice$"
if (-not $device) {
    Write-Host "[!] No ADB device connected." -ForegroundColor Red
    exit 1
}

if (-not $NoClear) {
    Write-Host "[+] Clearing logcat buffer..." -ForegroundColor Cyan
    adb logcat -c
}

$pidValue = (adb shell pidof -s $Package 2>$null).Trim()
if ($pidValue) {
    Write-Host "[+] Streaming PID $pidValue ($Package)..." -ForegroundColor Green
    adb logcat -v time --pid=$pidValue | Select-String -Pattern $TagFilter
} else {
    Write-Host "[!] $Package is not running; listening for matching launch/crash logs..." -ForegroundColor Yellow
    adb logcat -v time | Select-String -Pattern $TagFilter
}
