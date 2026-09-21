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

# Open shows LUT / LUT Adj as disabled chrome, but does not publish their RAW
# implementation. Keep tiny ABI stubs so shared editor code still compiles.
$rawLutTab = Join-Path $Dest "feature\photo-editor\src\main\java\com\RAZStudio\StudioRoom\feature\photo_editor\presentation\raw\components\RawLutTab.kt"
@'
package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

/** Open edition ABI stub. The LUT and LUT Adj tabs are permanently disabled. */
@Composable
internal fun RawLutTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    modifier: Modifier = Modifier,
    subjectMaskReady: Boolean = false,
    showPicker: Boolean = true,
    showFinishing: Boolean = true,
    onSaveEditAsLut: (suspend (String) -> String?)? = null,
) = Unit
'@ | Set-Content -Path $rawLutTab -NoNewline

$lutChain = Join-Path $Dest "feature\photo-editor\src\main\java\com\RAZStudio\StudioRoom\feature\photo_editor\raw_v3\RawV3LutChainResolver.kt"
@'
package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import java.io.File

/** Open edition ABI stub: no LUT path is resolved or applied. */
object RawV3LutChainResolver {
    data class Result(val file: File?, val intensity: Float, val isChained: Boolean)
    fun resolveTopmost(
        context: Context,
        actions: List<RawAction>,
        cacheDir: File = File(context.cacheDir, "raw_v3_editor_luts"),
    ) = Result(null, 1f, false)
    fun resolveChain(
        context: Context,
        actions: List<RawAction>,
        cacheDir: File = File(context.cacheDir, "raw_v3_editor_luts"),
    ) = Result(null, 1f, false)
    fun resolveLutPath(context: Context, uri: String, cacheDir: File): String? = null
}
'@ | Set-Content -Path $lutChain -NoNewline

$lutStore = Join-Path $Dest "feature\photo-editor\src\main\java\com\RAZStudio\StudioRoom\feature\photo_editor\raw_v3\RawV3LutStore.kt"
@'
package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import java.io.File

/** Open edition ABI stub. Dedicated LUT parsing and chaining are private. */
object RawV3LutStore {
    data class LutEntry(val name: String, val file: File)
    data class ParsedCube(
        val size: Int,
        val data: FloatArray,
        val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
        val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    )
    fun discoveryDir(context: Context) = File(context.cacheDir, "open_lut_disabled")
    fun list(context: Context): List<LutEntry> = emptyList()
    fun parseCubeFile(file: File): ParsedCube? = null
    fun chainLuts(
        lut1: ParsedCube, intensity1: Float, lut2: ParsedCube, intensity2: Float,
        bw1: Boolean = false, bw2: Boolean = false,
    ): ParsedCube = ParsedCube(0, FloatArray(0))
    fun chainLuts(layers: List<Pair<ParsedCube, Float>>) = ParsedCube(0, FloatArray(0))
    fun chainLutsBw(layers: List<Triple<ParsedCube, Float, Boolean>>) = ParsedCube(0, FloatArray(0))
    fun trilinearSample(lut: ParsedCube, r: Float, g: Float, b: Float, ch: Int) = 0f
    fun writeCubeFile(cube: ParsedCube, file: File) = Unit
}
'@ | Set-Content -Path $lutStore -NoNewline

$lutBake = Join-Path $Dest "feature\photo-editor\src\main\java\com\RAZStudio\StudioRoom\feature\photo_editor\presentation\lut_creator\RawV3LutBake.kt"
@'
package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.lut_creator

import android.content.Context
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ShaderParams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Open edition ABI stub. Export-edit-as-LUT is private. */
object RawV3LutBake {
    suspend fun bakeEditToLut(
        context: Context,
        stageATifPath: String,
        fullW: Int,
        fullH: Int,
        currentParams: ShaderParams,
        lutCubeFile: File?,
        name: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): String? = null
}
'@ | Set-Content -Path $lutBake -NoNewline

foreach ($privateLutMath in @("PairLutFitter.kt", "ReferenceColorTransfer.kt")) {
    $p = Join-Path $Dest "feature\photo-editor\src\main\java\com\RAZStudio\StudioRoom\feature\photo_editor\presentation\lut_creator\$privateLutMath"
    if (Test-Path $p) { Remove-Item -Force $p }
}

$nativeLut = Join-Path $Dest "lib\raw-native\src\main\cpp\v3\lut3d.cpp"
@'
#include "lut3d.h"
namespace raw_v3 {
CubeLut parseCubeFile(const std::string&) { return {}; }
#ifndef RAZ_NO_EGL
GLuint uploadCubeLutAsTexture3D(const CubeLut&) { return 0; }
#endif
}  // namespace raw_v3
'@ | Set-Content -Path $nativeLut -NoNewline

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
New-Item -ItemType Directory -Force -Path $luts | Out-Null
Set-Content -Path (Join-Path $luts ".gitkeep") -Value ""

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

# No bundled LUTs exist in Open, so remove the private cube-to-smcube task too.
$photoGradle = Join-Path $Dest "feature\photo-editor\build.gradle.kts"
if (Test-Path $photoGradle) {
    $pg = Get-Content $photoGradle -Raw
    $pg = $pg -replace '(?s)// [─-]+\r?\n// Build-time LUT compaction.*?\r?\n\r?\ndependencies \{', 'dependencies {'
    Set-Content -Path $photoGradle -Value $pg -NoNewline
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
    $r = $r -replace "All application source code \(every module\)\.", "All Open-edition application source code. Private LUT implementation and Short Video are excluded."
    $r = $r -replace '(?m)^- \*\*RAW LUT and LUT Adj implementation\*\*.*\r?\n', ''
    $lutNotice = "**RAW LUT and LUT Adj implementation** - tabs remain visible but disabled; dedicated browsing, parsing, chaining, baking, and native-loader code is private.`r`n- "
    $r = $r -replace '(\*\*The short video editor\*\*.+?edition\.)', ($lutNotice + '$1')
    Set-Content -Path $readmeDst -Value $r -NoNewline
}

$denyHits = @()
Get-ChildItem $Dest -Recurse -Include *.kt, *.kts -File -ErrorAction SilentlyContinue | ForEach-Object {
    $p = $_.FullName
    if ($p -match "\\build\\") { return }
    $text = Get-Content $p -Raw -ErrorAction SilentlyContinue
    if ($null -eq $text) { return }
    if ($text -match "LutCreatorComponent" -or $text -match "feature\.video_editor" -or
        $text -match "class VideoEditorComponent" -or $text -match "parseSmcube" -or
        $text -match "fun withPickDefaults") {
        $denyHits += $p
    }
}
if ($denyHits.Count -gt 0) {
    Write-Host "Deny-list grep failed:"
    $denyHits | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Export OK."
