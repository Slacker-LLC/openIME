param(
    [string]$Serial = '',
    [string]$OutputDir = '',
    [string]$NamePrefix = '',
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
# PS5.1 decodes external stdout with the console codepage; adb emits UTF-8.
$OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$project = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $project 'artifacts\openIME-1.0-debug.apk'
. (Join-Path $PSScriptRoot 'adb_context.ps1')
$adb = Resolve-OpenImeAdb
$Serial = Resolve-OpenImeSerial -RequestedSerial $Serial -AdbPath $adb
$pkg = 'llc.slacker.openime'
$receiver = "$pkg/.E2ETestReceiver"
$action = "$pkg.TEST_COMMAND"
$outDir = if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    Join-Path $project 'docs\visual\check'
} else {
    $OutputDir
}
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Adb { & $adb -s $Serial @args }

function FocusEditor {
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        Adb shell uiautomator dump /sdcard/v.xml | Out-Null
        $local = Join-Path $env:TEMP 'visual-focus.xml'
        Adb pull /sdcard/v.xml $local | Out-Null
        $h = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
        $node = $h.SelectNodes('//node[@resource-id="llc.slacker.openime:id/lifecycle_a"]') |
            Select-Object -First 1
        if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
            $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
            Adb shell input tap ([int]$x) ([int]$y) | Out-Null
            WaitIme
            return
        }
        Start-Sleep -Seconds 1
    }
    throw 'test_input not found'
}

function WaitIme {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $dump = Adb shell dumpsys activity service llc.slacker.openime | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') { return }
        Start-Sleep -Seconds 1
    }
    throw 'IME did not start'
}

function Send([string]$cmd) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $cmd | Out-Null
    Start-Sleep -Milliseconds 400
}

function Mode([string]$mode) {
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        Send 'state'
        $log = Adb logcat -d -t 300 | Select-String -Pattern 'OpenImeE2E' | Out-String
        if ($log -match ('OpenImeE2E: STATE mode=' + $mode)) { return }
        Send 'tap:key:mode'
    }
    throw ('mode not reached: ' + $mode)
}

function Capture([string]$name) {
    $path = Join-Path $outDir ($NamePrefix + $name)
    # PS '>' re-encodes pipes and corrupts binary; cmd redirect is byte-exact.
    $quoted = $adb
    cmd /c "`"$quoted`" -s $Serial exec-out screencap -p > `"$path`""
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if ($bytes.Length -lt 8 -or $bytes[0] -ne 0x89) {
        throw "screencap produced invalid PNG ($($bytes.Length) bytes)"
    }
    return $path
}

function BoundsLog {
    Send 'bounds'
    Start-Sleep -Milliseconds 300
    (Adb logcat -d -t 500 | Out-String)
}

$ime = "$pkg/.LocalVoiceImeService"
if (-not (Test-Path -LiteralPath $apk)) { throw 'missing APK' }
if (-not $SkipInstall) { Adb install -r $apk | Out-Null }
Adb shell settings put --user 0 secure default_input_method $ime
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell am force-stop $pkg | Out-Null
Start-Sleep -Seconds 1
Adb shell am start -n "$pkg/.LifecycleTestActivity" | Out-Null
Start-Sleep -Seconds 3
FocusEditor

$checks = @()
$checks += @{ Mode = 'PINYIN_26'; Name = 'pinyin26.png'; Need = @('key:mode', 'key:q', 'key:a', 'key:n', 'key-space') }
$checks += @{ Mode = 'ENGLISH_26'; Name = 'english26.png'; Need = @('key:mode', 'key:q', 'key:a', 'key:n', 'key-space') }
$checks += @{ Mode = 'PINYIN_9'; Name = 'pinyin9.png'; Need = @('pinyin9-grid', 'pinyin9-actions', 'key-9:6', 'key-enter') }
$checks += @{ Mode = 'DIGITS'; Name = 'digits.png'; Need = @('digits-grid', 'digits-actions', 'key:1') }

$report = @()
foreach ($check in $checks) {
    Mode $check.Mode
    $path = Capture $check.Name
    $log = BoundsLog
    foreach ($need in $check.Need) {
        if (-not $log.Contains($need)) {
            throw ("$($check.Mode) missing [$need]")
        }
    }
    if ($log -notmatch 'BOUNDS') {
        throw "$($check.Mode) bounds report missing"
    }
    $report += "VIS $($check.Mode) PASS -> $path"
}

$report | ForEach-Object { $_ }
'VISUAL STRUCTURE CHECK PASS'
