param([string]$Serial = '')

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

function MeasureCommands([string]$cmd, [int]$count) {
    $times = @()
    for ($i = 0; $i -lt $count; $i++) {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        Push $cmd
        $sw.Stop()
        $times += $sw.Elapsed.TotalMilliseconds
    }
    $sorted = $times | Sort-Object
    $idx = [Math]::Ceiling($count * 0.95) - 1
    [PSCustomObject]@{
        Command = $cmd
        Samples = $count
        P50 = [Math]::Round(($sorted[[Math]::Ceiling($count * 0.5) - 1]), 2)
        P95 = [Math]::Round($sorted[[Math]::Min($idx, $count - 1)], 2)
        Max = [Math]::Round(($sorted | Select-Object -Last 1), 2)
        Mean = [Math]::Round(($times | Measure-Object -Average).Average, 2)
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

$mode = MeasureCommands 'tap:key:mode' 20
$state = MeasureCommands 'state' 20
$result = [PSCustomObject]@{
    Device = $Serial
    MeasuredAt = (Get-Date -Format o)
    Note = 'Host->adb broadcast round trip; not frame/Jank measurement'
    ModeSwitch = $mode
    StateQuery = $state
}
$json = Join-Path $outDir 'baseline.json'
$result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $json -Encoding utf8
Get-Content -LiteralPath $json -Raw
