param([string]$Serial = '')

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'adb_context.ps1')
$adb = Resolve-OpenImeAdb
$Serial = Resolve-OpenImeSerial -RequestedSerial $Serial -AdbPath $adb
$pkg = 'llc.slacker.openime'
$receiver = "$pkg/.E2ETestReceiver"
$action = "$pkg.TEST_COMMAND"
$project = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $project 'artifacts\openIME-1.0-debug.apk'

function Adb { & $adb -s $Serial @args }

function FocusById([string]$id) {
    $local = Join-Path $env:TEMP 'lc-focus.xml'
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
        Adb shell rm -f /sdcard/lc.xml | Out-Null
        Adb shell uiautomator dump /sdcard/lc.xml | Out-Null
        Adb pull /sdcard/lc.xml $local 2>$null | Out-Null
        if (-not (Test-Path -LiteralPath $local)) {
            Start-Sleep -Milliseconds 500
            continue
        }
        try {
            $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $local
            if (-not [string]::IsNullOrWhiteSpace($raw)) {
                $h = [xml]$raw
                $xpath = '//node[@resource-id="' + $id + '"]'
                $node = $h.SelectNodes($xpath) | Select-Object -First 1
                if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                    $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
                    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
                    Adb shell input tap ([int]$x) ([int]$y) | Out-Null
                    WaitForIme
                    return
                }
            }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw ('element not found: ' + $id)
}

function WaitForIme {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $dump = Adb shell dumpsys activity service llc.slacker.openime | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') { return }
        Start-Sleep -Seconds 1
    }
    throw 'real IME input view was not started'
}

function StartActivity([string]$activity) {
    Adb logcat -c
    Adb shell am force-stop $pkg | Out-Null
    Adb shell am start -n "$pkg/.$activity" | Out-Null
    Start-Sleep -Seconds 3
}

function SendCommand([string]$command) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $command | Out-Null
    Start-Sleep -Milliseconds 350
}

function Tap([string]$target) { SendCommand ('tap:' + $target) }

function TypeText([string]$text) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))
    SendCommand ('type64:' + $b64)
}

function GetText([string]$id) {
    $local = Join-Path $env:TEMP 'lc-text.xml'
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
        Adb shell rm -f /sdcard/lc.xml | Out-Null
        Adb shell uiautomator dump /sdcard/lc.xml | Out-Null
        Adb pull /sdcard/lc.xml $local 2>$null | Out-Null
        if (Test-Path -LiteralPath $local) {
            try {
                $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $local
                if (-not [string]::IsNullOrWhiteSpace($raw)) {
                    $h = [xml]$raw
                    $xpath = '//node[@resource-id="' + $id + '"]'
                    $node = $h.SelectNodes($xpath) | Select-Object -First 1
                    if ($node) { return $node.text }
                }
            } catch { }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "GetText failed for $id"
}

function StateLog {
    SendCommand 'state'
    Start-Sleep -Milliseconds 200
    (
        Adb logcat -d -t 300 |
            Select-String -Pattern 'OpenImeE2E|OpenIme' |
            Out-String
    )
}

function AssertContains([string]$name, [string]$needle, [string]$haystack) {
    if ($null -eq $haystack -or -not $haystack.Contains($needle)) {
        throw ("FAIL $name missing [$needle] in [$haystack]")
    }
    "LC $name PASS"
}

$ime = "$pkg/.LocalVoiceImeService"
if (-not (Test-Path -LiteralPath $apk)) { throw ('missing APK: ' + $apk) }
Adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }
Adb shell settings put --user 0 secure default_input_method $ime
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1

StartActivity 'LifecycleTestActivity'
FocusById 'llc.slacker.openime:id/lifecycle_a'
foreach ($key in @('n', 'i', 'h', 'a', 'o')) { Tap $key }
AssertContains 'composition active on A' 'compositionLength=5' (StateLog)

FocusById 'llc.slacker.openime:id/lifecycle_b'
AssertContains 'composition cleared on B' 'compositionLength=0' (StateLog)

TypeText 'hi'
Adb shell input keyevent 4 | Out-Null
Start-Sleep -Seconds 1
Adb shell dumpsys input_method | Select-String -Pattern 'mInputShown=false' | Out-Null
FocusById 'llc.slacker.openime:id/lifecycle_b'
AssertContains 'composition cleared after hide/show' 'compositionLength=0' (StateLog)
AssertContains 'editor text retained after hide/show' 'hi' (GetText 'llc.slacker.openime:id/lifecycle_b')

# The only production voice entry is the combined space key. The debug command
# enters the same press/release callback used after the real 150ms threshold;
# it does not add a second UI entry.
Adb shell pm grant $pkg android.permission.RECORD_AUDIO | Out-Null
SendCommand 'voice-press'
AssertContains 'voice starts on held space path' 'voice=true' (StateLog)
SendCommand 'voice-release'
AssertContains 'voice stops on space release' 'voice=false' (StateLog)

StartActivity 'LifecycleTestActivity'
FocusById 'llc.slacker.openime:id/lifecycle_a'
AssertContains 'app restart clears composition' 'compositionLength=0' (StateLog)

'LIFECYCLE REGRESSION SUITE PASS'
