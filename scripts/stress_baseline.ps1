param(
    [string]$Serial = '',
    [int]$Iterations = 2500,
    [int]$CommandDelayMs = 10
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
$outDir = Join-Path $project 'docs\stress'
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
    if ($CommandDelayMs -gt 0) { Start-Sleep -Milliseconds $CommandDelayMs }
}

function ReadPid {
    return (Adb shell pidof $pkg | Out-String).Trim()
}

function ReadPssKb {
    $memory = Adb shell dumpsys meminfo $pkg | Out-String
    $match = [regex]::Match($memory, 'TOTAL PSS:\s*(\d+)')
    if (-not $match.Success) { return -1 }
    return [int]$match.Groups[1].Value
}

if ($Iterations -lt 2500) {
    throw 'Iterations must be at least 2500 so the default 4-command loop executes >=10,000 commands'
}
if (-not (Test-Path -LiteralPath $apk)) { throw 'missing APK' }

Adb install -r $apk | Out-Null
Adb shell settings put --user 0 secure default_input_method "$pkg/.LocalVoiceImeService"
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell ime enable --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb shell ime set --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb shell am force-stop $pkg | Out-Null
Start-Sleep -Seconds 1
Adb shell am start -n "$pkg/.LifecycleTestActivity" | Out-Null
Start-Sleep -Seconds 3
Adb shell uiautomator dump /sdcard/stress.xml | Out-Null
$local = Join-Path $env:TEMP 'stress-focus.xml'
Adb pull /sdcard/stress.xml $local | Out-Null
$h = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
$node = $h.SelectNodes('//node[@resource-id="llc.slacker.openime:id/lifecycle_a"]') | Select-Object -First 1
if ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    Adb shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2)) | Out-Null
}
WaitIme

$initialPid = ReadPid
if ([string]::IsNullOrWhiteSpace($initialPid)) { throw 'IME process is not alive before stress run' }
$initialPssKb = ReadPssKb
Adb logcat -c | Out-Null

$start = Get-Date
$commands = 0
$processRestarted = $false
for ($i = 0; $i -lt $Iterations; $i++) {
    # Exercise candidate/composition work, deletion, and two mode transitions.
    Push 'tap:key:n'; $commands++
    Push 'tap:key-backspace'; $commands++
    Push 'tap:key:mode'; $commands++
    Push 'tap:key:mode'; $commands++

    if (($i + 1) % 250 -eq 0) {
        $pidNow = ReadPid
        if ([string]::IsNullOrWhiteSpace($pidNow)) {
            throw "IME process died after $commands commands"
        }
        if ($pidNow -ne $initialPid) { $processRestarted = $true }
    }
}
$elapsed = ((Get-Date) - $start).TotalSeconds

$finalPid = ReadPid
$finalPssKb = ReadPssKb
$logcat = Adb logcat -d -v threadtime | Out-String
$packageFatalPattern = "FATAL EXCEPTION|ANR in $([regex]::Escape($pkg))|Process: $([regex]::Escape($pkg))"
$fatalAnr = $logcat -match $packageFatalPattern

$result = [PSCustomObject]@{
    Device = $Serial
    MeasuredAt = (Get-Date -Format o)
    Iterations = $Iterations
    Commands = $commands
    CommandMix = 'key:n + backspace + mode + mode'
    CommandDelayMs = $CommandDelayMs
    ElapsedSeconds = [Math]::Round($elapsed, 2)
    InitialPid = $initialPid
    FinalPid = $finalPid
    ProcessRestarted = $processRestarted
    InitialPssKb = $initialPssKb
    FinalPssKb = $finalPssKb
    PssDeltaKb = if ($initialPssKb -ge 0 -and $finalPssKb -ge 0) { $finalPssKb - $initialPssKb } else { -1 }
    FatalOrAnr = $fatalAnr
    Acceptance = if (-not $fatalAnr -and -not [string]::IsNullOrWhiteSpace($finalPid) -and -not $processRestarted) { 'PASS' } else { 'FAIL' }
}

$json = Join-Path $outDir 'stress-10k.json'
$result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $json -Encoding utf8
Get-Content -LiteralPath $json -Raw

if ($commands -lt 10000) { throw "stress command count below acceptance floor: $commands" }
if ([string]::IsNullOrWhiteSpace($finalPid)) { throw 'IME process not alive after stress run' }
if ($processRestarted) { throw 'IME process restarted during stress run' }
if ($fatalAnr) { throw 'FATAL/ANR evidence detected for openIME during stress run' }
