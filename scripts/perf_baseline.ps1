param(
    [string]$Serial = '',
    [int]$TransportSamples = 50,
    [int]$FrameInteractions = 100
)

$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $project 'artifacts\openIME-1.0-debug.apk'
. (Join-Path $PSScriptRoot 'adb_context.ps1')
$adb = Resolve-OpenImeAdb
$Serial = Resolve-OpenImeSerial -RequestedSerial $Serial -AdbPath $adb
$pkg = 'llc.slacker.openime'
$receiver = "$pkg/.E2ETestReceiver"
$action = "$pkg.TEST_COMMAND"
$outDir = Join-Path $project 'docs\perf'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Adb { & $adb -s $Serial @args }

function WaitIme {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $dump = Adb shell dumpsys activity service llc.slacker.openime | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') { return }
        Start-Sleep -Seconds 1
    }
    throw 'IME did not start'
}

function Push([string]$cmd) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $cmd | Out-Null
}

function Percentile([double[]]$sorted, [double]$fraction) {
    if ($sorted.Count -eq 0) { return 0.0 }
    $index = [Math]::Ceiling($sorted.Count * $fraction) - 1
    $index = [Math]::Max(0, [Math]::Min($index, $sorted.Count - 1))
    return [Math]::Round($sorted[$index], 2)
}

function MeasureTransport([string]$cmd, [int]$count) {
    $times = @()
    for ($i = 0; $i -lt $count; $i++) {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        Push $cmd
        $sw.Stop()
        $times += $sw.Elapsed.TotalMilliseconds
    }
    [double[]]$sorted = $times | Sort-Object
    [PSCustomObject]@{
        Command = $cmd
        Samples = $count
        P50Ms = Percentile $sorted 0.50
        P95Ms = Percentile $sorted 0.95
        P99Ms = Percentile $sorted 0.99
        MaxMs = [Math]::Round(($sorted | Select-Object -Last 1), 2)
        MeanMs = [Math]::Round(($times | Measure-Object -Average).Average, 2)
    }
}

function ReadFrameJank {
    $gfx = Adb shell dumpsys gfxinfo $pkg | Out-String
    $totalMatch = [regex]::Match($gfx, 'Total frames rendered:\s*(\d+)')
    $jankMatch = [regex]::Match($gfx, 'Janky frames:\s*(\d+)\s*\(([\d\.,]+)%\)')
    $total = if ($totalMatch.Success) { [int]$totalMatch.Groups[1].Value } else { -1 }
    $janky = if ($jankMatch.Success) { [int]$jankMatch.Groups[1].Value } else { -1 }
    $percentText = if ($jankMatch.Success) { $jankMatch.Groups[2].Value.Replace(',', '.') } else { '-1' }
    $percent = [double]::Parse($percentText, [Globalization.CultureInfo]::InvariantCulture)
    [PSCustomObject]@{
        TotalFrames = $total
        JankyFrames = $janky
        JankyPercent = $percent
        Source = 'adb shell dumpsys gfxinfo; actual app frame statistics, not adb round-trip timing'
    }
}

if (-not (Test-Path -LiteralPath $apk)) { throw 'missing APK' }
Adb install -r $apk | Out-Null
Adb shell settings put --user 0 secure default_input_method "$pkg/.LocalVoiceImeService"
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell am force-stop $pkg | Out-Null
Start-Sleep -Seconds 1
Adb shell am start -n "$pkg/.LifecycleTestActivity" | Out-Null
Start-Sleep -Seconds 3
Adb shell uiautomator dump /sdcard/p.xml | Out-Null
$local = Join-Path $env:TEMP 'perf-focus.xml'
Adb pull /sdcard/p.xml $local | Out-Null
$h = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
$node = $h.SelectNodes('//node[@resource-id="llc.slacker.openime:id/lifecycle_a"]') | Select-Object -First 1
if ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    Adb shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2)) | Out-Null
}
WaitIme

# These are transport baselines only. Do not call them key latency: each sample
# includes host scheduling, adb, ActivityManager broadcast delivery and receiver work.
$modeTransport = MeasureTransport 'tap:key:mode' $TransportSamples
$stateTransport = MeasureTransport 'state' $TransportSamples

# Reset framework frame counters, exercise production UI, then read actual app
# frame statistics. This complements (but does not replace) an on-device
# key-down -> candidate timestamp trace for real-device P50/P95/P99.
Adb shell dumpsys gfxinfo $pkg reset | Out-Null
for ($i = 0; $i -lt $FrameInteractions; $i++) {
    Push 'tap:key:mode'
}
Start-Sleep -Milliseconds 500
$frameJank = ReadFrameJank

$result = [PSCustomObject]@{
    Device = $Serial
    MeasuredAt = (Get-Date -Format o)
    TransportNote = 'P50/P95/P99 below are host->adb broadcast round trips; they are deliberately not labeled key latency.'
    ModeSwitchTransport = $modeTransport
    StateQueryTransport = $stateTransport
    FrameInteractions = $FrameInteractions
    FrameJank = $frameJank
    RemainingRealDeviceAcceptance = 'Record on-device key-down -> visual/composition/first-candidate P50/P95/P99 on representative hardware.'
}
$json = Join-Path $outDir 'baseline.json'
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $json -Encoding utf8
Get-Content -LiteralPath $json -Raw
