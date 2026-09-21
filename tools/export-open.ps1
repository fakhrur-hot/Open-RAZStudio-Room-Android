# Overwrite Fixed16bit\_foss_export from this private tree, then strip Open-only denylist.
# Preserves dest .git and local.properties. Never copies keystore.
# Usage: powershell -File tools/export-open.ps1

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Dest = Join-Path $Root "_foss_export"
$Keep = Join-Path $env:TEMP "foss_export_keep"
$RoboLog = Join-Path $env:TEMP "foss_export_robocopy.log"

Write-Host "Export Open -> $Dest"

if (-not (Test-Path $Dest)) {
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
}

if (Test-Path $Keep) { Remove-Item -Recurse -Force $Keep }
New-Item -ItemType Directory -Force -Path $Keep | Out-Null

foreach ($name in @(".git", "local.properties", ".gitignore", "README.md")) {
    $p = Join-Path $Dest $name
    if (Test-Path $p) {
        Move-Item -Force $p (Join-Path $Keep $name)
    }
}

Get-ChildItem $Dest -Force | ForEach-Object {
    Remove-Item -Recurse -Force $_.FullName
}

$xd = @(
    ".git", ".gradle", ".gradle-foss", ".kotlin", ".cursor", ".kiro", ".idea",
    "build", "node_modules", "_foss_export", "feature\video-editor",
    "feature\photo-editor\src\main\assets\luts"
)
$xf = @(
    "keystore.properties", "local.properties", "*.jks", "*.apk"
)

$roboArgs = @(
    $Root, $Dest, "/E", "/NFL", "/NDL", "/NP", "/R:1", "/W:1", "/LOG:$RoboLog"
)
foreach ($d in $xd) { $roboArgs += "/XD"; $roboArgs += $d }
foreach ($f in $xf) { $roboArgs += "/XF"; $roboArgs += $f }

& robocopy @roboArgs
$rc = $LASTEXITCODE
if ($rc -ge 8) { throw "robocopy failed with $rc (see $RoboLog)" }

foreach ($name in @(".git", "local.properties", ".gitignore")) {
    $p = Join-Path $Keep $name
    if (Test-Path $p) {
        $target = Join-Path $Dest $name
        if (Test-Path $target) { Remove-Item -Recurse -Force $target }
        Move-Item -Force $p $target
    }
}

Get-ChildItem $Dest -Recurse -Filter "LutCreator*.kt" -ErrorAction SilentlyContinue |
    Remove-Item -Force

$debugRoot = Join-Path $Dest "feature\root\src\debug"
if (Test-Path $debugRoot) { Remove-Item -Recurse -Force $debugRoot }

$relImpl = Join-Path $Dest "feature\root\src\release\java\com\RAZStudio\StudioRoom\feature\root\presentation\components\navigation\UnstableFeatureScreensImpl.kt"
$mainNav = Join-Path $Dest "feature\root\src\main\java\com\RAZStudio\StudioRoom\feature\root\presentation\components\navigation"
if (Test-Path $relImpl) {
    New-Item -ItemType Directory -Force -Path $mainNav | Out-Null
    Copy-Item -Force $relImpl (Join-Path $mainNav "UnstableFeatureScreensImpl.kt")
    Remove-Item -Force $relImpl
}

$models = Join-Path $Dest "feature\photo-editor\src\main\assets\models"
$keepModels = @(
    "ae_tone_model.tflite", "ae_scaler_mean.npy", "ae_scaler_scale.npy",
    "raw_hdr_recovery.bin", "raw_shadow_recovery.bin",
    "README.md", "NOTICE_DEPTH_SEG.md"
)
if (Test-Path $models) {
    Get-ChildItem $models -File | Where-Object { $keepModels -notcontains $_.Name } | Remove-Item -Force
}

$lens = Join-Path $Dest "feature\photo-editor\src\main\assets\lensfun_db"
if (Test-Path $lens) {
    Get-ChildItem $lens -File | Where-Object { $_.Name -ne "README.md" } | Remove-Item -Force
}

$luts = Join-Path $Dest "feature\photo-editor\src\main\assets\luts"
if (Test-Path $luts) { Remove-Item -Recurse -Force $luts }

$video = Join-Path $Dest "feature\video-editor"
if (Test-Path $video) { Remove-Item -Recurse -Force $video }

foreach ($secret in @("keystore.properties", "local.properties")) {
    # local.properties restored for *this machine* build; still gitignored
}

$uiGradle = Join-Path $Dest "core\ui\build.gradle.kts"
if (Test-Path $uiGradle) {
    $g = Get-Content $uiGradle -Raw
    $g = $g -replace 'EDITION_PRIVATE_TRIAL", "true"', 'EDITION_PRIVATE_TRIAL", "false"'
    $g = $g -replace 'FEATURE_OPEN_ALLOWLIST_ONLY", "false"', 'FEATURE_OPEN_ALLOWLIST_ONLY", "true"'
    $g = $g -replace 'FEATURE_LUT_CREATOR", "true"', 'FEATURE_LUT_CREATOR", "false"'
    $g = $g -replace 'FEATURE_VIDEO_EDITOR", "true"', 'FEATURE_VIDEO_EDITOR", "false"'
    Set-Content -Path $uiGradle -Value $g -NoNewline
}

$settings = Join-Path $Dest "settings.gradle.kts"
if (Test-Path $settings) {
    (Get-Content $settings) | Where-Object { $_ -notmatch "video-editor" } | Set-Content $settings
}

$rootGradle = Join-Path $Dest "feature\root\build.gradle.kts"
if (Test-Path $rootGradle) {
    (Get-Content $rootGradle) | Where-Object { $_ -notmatch "videoEditor" } | Set-Content $rootGradle
}

$toml = Join-Path $Dest "gradle\libs.versions.toml"
if (Test-Path $toml) {
    $t = Get-Content $toml -Raw
    $t = $t -replace 'versionName = "[^"]+"', 'versionName = "1.0.1-alpha"'
    $t = $t -replace 'versionCode = "[^"]+"', 'versionCode = "101"'
    Set-Content -Path $toml -Value $t -NoNewline
}

$appGradle = Join-Path $Dest "app\build.gradle.kts"
if (Test-Path $appGradle) {
    $a = Get-Content $appGradle -Raw
    $a = $a -replace 'archivesName = "RAZStudio_Room-', 'archivesName = "Open_RAZStudio_Room-'
    Set-Content -Path $appGradle -Value $a -NoNewline
}

$readmeSrc = Join-Path $Keep "README.md"
$readmeDst = Join-Path $Dest "README.md"
if (Test-Path $readmeSrc) {
    $r = Get-Content $readmeSrc -Raw
    $r = $r -replace "1\.0\.0-alpha", "1.0.1-alpha"
    Set-Content -Path $readmeDst -Value $r -NoNewline
}

$denyHits = @()
Get-ChildItem $Dest -Recurse -Include *.kt, *.kts -File -ErrorAction SilentlyContinue | ForEach-Object {
    $p = $_.FullName
    if ($p -match "\\build\\") { return }
    $text = Get-Content $p -Raw -ErrorAction SilentlyContinue
    if ($null -eq $text) { return }
    if ($text -match "LutCreatorComponent" -or $text -match "feature\.video_editor" -or $text -match "class VideoEditorComponent") {
        $denyHits += $p
    }
}
if ($denyHits.Count -gt 0) {
    Write-Host "Deny-list grep failed:"
    $denyHits | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Export OK."
