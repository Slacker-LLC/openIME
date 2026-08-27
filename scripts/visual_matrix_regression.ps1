param(
    [string]$Serial = '',
    [int[]]$WidthsDp = @(320, 360, 390, 412, 432, 600),
    [string]$OutputDir = ''
)

$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $project 'artifacts\openIME-1.0-debug.apk'
. (Join-Path $PSScriptRoot 'adb_context.ps1')
$adb = Resolve-OpenImeAdb
$Serial = Resolve-OpenImeSerial -RequestedSerial $Serial -AdbPath $adb

function Adb { & $adb -s $Serial @args }

$isEmulator = (Adb shell getprop ro.kernel.qemu | Out-String).Trim() -eq '1'
if (-not $isEmulator) {
    'VISUAL MATRIX SKIP -> display override is restricted to emulators; complete the L2 manual checklist'
    exit 0
}
if (-not (Test-Path -LiteralPath $apk)) { throw "missing APK: $apk" }
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $project ('.local\visual-matrix\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$sizeInfo = Adb shell wm size | Out-String
$densityInfo = Adb shell wm density | Out-String
$sizeOverride = [regex]::Match($sizeInfo, 'Override size:\s*(\d+x\d+)').Groups[1].Value
$densityOverride = [regex]::Match($densityInfo, 'Override density:\s*(\d+)').Groups[1].Value

Adb install -r $apk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }

try {
    # 320 dpi gives an exact 2 px = 1 dp conversion for deterministic widths.
    Adb shell wm density 320 | Out-Null
    foreach ($widthDp in $WidthsDp) {
        if ($widthDp -lt 280 -or $widthDp -gt 800) { throw "unsafe widthDp: $widthDp" }
        $widthPx = $widthDp * 2
        $heightPx = [Math]::Max(1280, [int]($widthPx * 1.8))
        Adb shell wm size "${widthPx}x${heightPx}" | Out-Null
        Start-Sleep -Seconds 2
        & (Join-Path $PSScriptRoot 'visual_check.ps1') `
            -Serial $Serial `
            -OutputDir $OutputDir `
            -NamePrefix "${widthDp}dp-" `
            -SkipInstall
        "VISUAL WIDTH ${widthDp}dp PASS"
    }
} finally {
    if ([string]::IsNullOrWhiteSpace($sizeOverride)) {
        Adb shell wm size reset | Out-Null
    } else {
        Adb shell wm size $sizeOverride | Out-Null
    }
    if ([string]::IsNullOrWhiteSpace($densityOverride)) {
        Adb shell wm density reset | Out-Null
    } else {
        Adb shell wm density $densityOverride | Out-Null
    }
}

"VISUAL MATRIX PASS -> $OutputDir"
