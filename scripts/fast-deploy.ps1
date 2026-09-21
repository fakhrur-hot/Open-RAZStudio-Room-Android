param(
    [string]$Variant = "FossDebug",
    [string]$Package = "com.RAZStudio.StudioRoom.debug",
    [string]$MainActivity = "com.RAZStudio.StudioRoom.app.presentation.AppActivity"
)

$ErrorActionPreference = "Stop"

Write-Host "[1/3] Building and installing $Variant..." -ForegroundColor Cyan
adb logcat -c
.\gradlew.bat ":app:install$Variant" --daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "[!] Gradle install failed." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "[2/3] Launching $Package/$MainActivity..." -ForegroundColor Cyan
adb shell am force-stop $Package
adb shell am start -n "$Package/$MainActivity" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | Out-Null

Write-Host "[3/3] Streaming filtered logcat..." -ForegroundColor Green
.\scripts\adb-logcat.ps1 -Package $Package -NoClear
