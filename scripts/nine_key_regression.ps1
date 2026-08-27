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

function Adb { & $adb -s $Serial @args }

function SendCommand([string]$command, [int]$delayMs = 80) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $command | Out-Null
    if ($delayMs -gt 0) { Start-Sleep -Milliseconds $delayMs }
}

function Tap([string]$target, [int]$delayMs = 80) {
    SendCommand ('tap:' + $target) $delayMs
}

function WaitForIme {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $dump = Adb shell dumpsys activity service $pkg | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'real IME input view was not started'
}

function FocusEditor {
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        Adb shell uiautomator dump /sdcard/p9-focus.xml | Out-Null
        $local = Join-Path $env:TEMP 'p9-focus.xml'
        Adb pull /sdcard/p9-focus.xml $local | Out-Null
        $hierarchy = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
        $node = $hierarchy.SelectNodes('//node[@resource-id="llc.slacker.openime:id/test_input"]') |
            Select-Object -First 1
        if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
            $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
            Adb shell input tap ([int]$x) ([int]$y) | Out-Null
            WaitForIme
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'test_input EditText was not found'
}

function StateLog {
    SendCommand 'state' 100
    Adb logcat -d -t 200 |
        Select-String 'OpenImeE2E: STATE' |
        Select-Object -Last 1 |
        ForEach-Object { $_.Line }
}

function WaitForRime {
    for ($attempt = 0; $attempt -lt 240; $attempt++) {
        if ((StateLog) -match 'rimeReady=true') { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'librime did not become ready within 120 seconds'
}

function ModeP9 {
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        if ((StateLog) -match 'mode=PINYIN_9') { return }
        Tap 'key:mode' 120
    }
    throw 'PINYIN_9 mode not reached'
}

function StartReal {
    Adb shell am force-stop $pkg | Out-Null
    Adb shell am start -n "$pkg/.MainActivity" | Out-Null
    Start-Sleep -Seconds 1
    FocusEditor
    WaitForRime
    ModeP9
}

function GetText([string]$name) {
    Adb shell uiautomator dump /sdcard/p9.xml | Out-Null
    $local = Join-Path $env:TEMP ($name + '.xml')
    Adb pull /sdcard/p9.xml $local | Out-Null
    $hierarchy = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
    $text = ($hierarchy.SelectNodes('//node[@resource-id="llc.slacker.openime:id/test_input"]') |
        Select-Object -First 1).text
    if ($text -eq '在这里测试本地输入法') { return '' }
    $text
}

function AssertEqual([string]$name, [string]$expected, [string]$actual) {
    if ($actual -ne $expected) { throw "FAIL $name expected=[$expected] actual=[$actual]" }
    "P9 $name PASS -> length=$($actual.Length)"
}

function AssertRapidSequence([int]$clickCount) {
    StartReal
    $repeats = [int]($clickCount / 5)
    $digits = '64426' * $repeats
    $preview = 'nihao' * $repeats
    $committed = '你好' * $repeats
    SendCommand ('nine-sequence:' + $digits) 500
    $preedit = GetText ("p9-preedit-$clickCount")
    AssertEqual "$clickCount rapid clicks keep Pinyin" $preview $preedit
    if ($preedit -match '\d') { throw "FAIL $clickCount rapid clicks exposed digits" }
    $state = StateLog
    if ($state -notmatch "compositionLength=$clickCount") {
        throw "FAIL $clickCount rapid clicks lost/repeated input state=[$state]"
    }
    Tap '确定' 300
    AssertEqual "$clickCount rapid clicks commit" $committed (GetText ("p9-commit-$clickCount"))
    if ((StateLog) -notmatch 'compositionLength=0') {
        throw "FAIL $clickCount rapid clicks did not clear composition"
    }
}

if (-not (Test-Path -LiteralPath $apk)) { throw "missing APK: $apk" }
Adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "APK install failed on $Serial" }
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell ime enable --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb shell ime set --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb logcat -c

foreach ($clickCount in @(10, 20, 50)) { AssertRapidSequence $clickCount }

StartReal
SendCommand 'nine-sequence:64426' 300
Tap 'key-backspace' 200
AssertEqual 'backspace removes one raw digit path' 'niha' (GetText 'p9-delete-one')
foreach ($unused in 1..4) { Tap 'key-backspace' 40 }
AssertEqual 'backspace clears the Pinyin composition first' '' (GetText 'p9-delete-all')
if ((StateLog) -notmatch 'compositionLength=0') { throw 'FAIL P9 delete left stale composition' }

'NINE KEY REGRESSION SUITE PASS'
