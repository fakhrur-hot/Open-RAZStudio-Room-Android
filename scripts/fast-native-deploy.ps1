param(
    [string]$Variant = "FossDebug",
    [string]$Package = "com.RAZStudio.StudioRoom.debug",
    [string]$MainActivity = "com.RAZStudio.StudioRoom.app.presentation.AppActivity"
)

$ErrorActionPreference = "Stop"

Write-Host "[1/3] Building native module and APK ($Variant)..." -ForegroundColor Cyan
adb logcat -c
.\gradlew.bat ":lib:raw-native:assemble$Variant" --daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "[!] Native module build failed." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "[2/3] Installing updated APK..." -ForegroundColor Cyan
.\gradlew.bat ":app:install$Variant" --daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "[!] APK install failed." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "[3/3] Restarting $Package/$MainActivity..." -ForegroundColor Green
adb shell am force-stop $Package
adb shell am start -n "$Package/$MainActivity" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | Out-Null
.\scripts\adb-logcat.ps1 -Package $Package -NoClear
