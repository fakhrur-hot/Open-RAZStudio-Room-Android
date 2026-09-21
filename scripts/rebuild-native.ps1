param(
    [string]$Target = "raw_decoder",
    [string]$Abi = "arm64-v8a",
    [string]$BuildType = "Debug"
)

$ErrorActionPreference = "Stop"

$cxxRoot = Join-Path $PSScriptRoot "..\lib\raw-native\.cxx"
$cxxDir = Get-ChildItem -Path $cxxRoot -Recurse -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "\\$BuildType\\[^\\]+\\$Abi$" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $cxxDir) {
    Write-Host "[!] No CMake build directory found for $BuildType/$Abi." -ForegroundColor Red
    Write-Host "[!] Run .\gradlew.bat :lib:raw-native:assembleFossDebug first." -ForegroundColor Yellow
    exit 1
}

$ninja = (Get-Command ninja -ErrorAction SilentlyContinue).Source
if (-not $ninja) {
    $sdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\Sdk") |
        Where-Object { $_ -and (Test-Path $_) }
    $ninja = $sdkRoots |
        ForEach-Object { Get-ChildItem $_ -Filter ninja.exe -Recurse -File -ErrorAction SilentlyContinue } |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $ninja) {
    Write-Host "[!] ninja.exe was not found on PATH or under the Android SDK." -ForegroundColor Red
    exit 1
}

Write-Host "[+] CMake directory: $($cxxDir.FullName)" -ForegroundColor Cyan
Write-Host "[+] Rebuilding native target '$Target'..." -ForegroundColor Green
& $ninja -C $cxxDir.FullName $Target
if ($LASTEXITCODE -ne 0) {
    Write-Host "[!] Native compilation failed." -ForegroundColor Red
    exit $LASTEXITCODE
}
Write-Host "[+] Native target '$Target' compiled successfully." -ForegroundColor Green
