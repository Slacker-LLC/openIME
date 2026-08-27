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

function StartReal {
    Adb logcat -c
    Adb shell am force-stop $pkg | Out-Null
    Adb shell am start -n "$pkg/.MainActivity" | Out-Null
    Start-Sleep -Seconds 3
    # Focus the real EditText; the keyboard itself is driven through the
    # debug-only E2E receiver, so no absolute key coordinates are used.
    FocusEditor
    WaitForIme
}

function WaitForIme {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $dump = Adb shell dumpsys activity service llc.slacker.openime | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw 'real IME input view was not started'
}

function FocusEditor {
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        Adb shell uiautomator dump /sdcard/focus.xml | Out-Null
        $local = Join-Path $env:TEMP 'focus.xml'
        Adb pull /sdcard/focus.xml $local | Out-Null
        $h = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
        $node = $h.SelectNodes('//node[@resource-id="llc.slacker.openime:id/test_input"]') |
            Select-Object -First 1
        if ($node) {
            if ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
                $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
                Adb shell input tap ([int]$x) ([int]$y) | Out-Null
                return
            }
        }
        Start-Sleep -Seconds 1
    }
    throw 'test_input EditText was not found'
}

function SendCommand([string]$command) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $command | Out-Null
    Start-Sleep -Milliseconds 300
}

function Tap([string]$target) {
    SendCommand ('tap:' + $target)
}

function Mode([string]$mode) {
    for ($i = 0; $i -lt 8; $i++) {
        SendCommand 'state'
        $log = Adb logcat -d -t 300 | Select-String -Pattern 'OpenImeE2E' | Out-String
        if ($log -match ('OpenImeE2E: STATE mode=' + $mode)) { return }
        Tap 'key:mode'
        Start-Sleep -Milliseconds 350
    }
    throw ('mode not reached: ' + $mode)
}

function GetText([string]$name) {
    Adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    $local = Join-Path $env:TEMP ($name + '.xml')
    Adb pull /sdcard/ui.xml $local | Out-Null
    $h = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
    ($h.SelectNodes('//node[@resource-id="llc.slacker.openime:id/test_input"]') | Select-Object -First 1).text
}

function AssertText([string]$name, [string]$expected, [string]$actual) {
    if ($actual -ne $expected) {
        throw ("FAIL $name expected=[$expected] actual=[$actual]")
    }
    "CR $name PASS -> [$actual]"
}

if (-not (Test-Path -LiteralPath $apk)) {
    throw ('missing APK: ' + $apk)
}

Adb install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw ('APK install failed on ' + $Serial + ' with exit ' + $LASTEXITCODE)
}
Adb shell pm grant $pkg android.permission.RECORD_AUDIO | Out-Null
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell settings put --user 0 secure enabled_input_methods "$pkg/.LocalVoiceImeService"
Adb shell settings put --user 0 secure default_input_method "$pkg/.LocalVoiceImeService"
Adb shell ime enable --user 0 "$pkg/.LocalVoiceImeService"
Adb shell ime set --user 0 "$pkg/.LocalVoiceImeService"

StartReal
foreach ($key in @('n', 'i', 'h', 'a', 'o')) { Tap $key }
Tap 'candidate-first-row'
AssertText '020 nihao -> 你好' '你好' (GetText 'cr26')

StartReal
foreach ($key in 'woxiangchifan'.ToCharArray()) { Tap ([string]$key) }
Tap 'key-space'
AssertText '021 continuous Pinyin -> 我想吃饭' '我想吃饭' (GetText 'cr-sentence')

StartReal
Mode 'PINYIN_26'
foreach ($key in @('n', 'i', 'h', 'a', 'o')) { Tap $key }
Tap 'key-backspace'
AssertText '022 composition backspace' 'niha' (GetText 'cr-compose-delete')

StartReal
Mode 'PINYIN_26'
foreach ($key in @('n', 'i', 'h', 'a', 'o')) { Tap $key }
Tap 'candidate-first-row'
Tap 'key-backspace'
AssertText '023 committed backspace' '你' (GetText 'cr-commit-delete')

StartReal
Mode 'ENGLISH_26'
foreach ($key in 'openime'.ToCharArray()) { Tap ([string]$key) }
AssertText '025 English key path' 'openime' (GetText 'cr-en')

StartReal
Mode 'PINYIN_9'
# Chinese 9-key is a continuous T9 digit buffer. 你好 = 64426;
# the visible pre-edit stays as Pinyin and the user can tap a candidate
# without inserting a numeric string into the target editor.
foreach ($key in @('6', '4', '4', '2', '6')) { Tap $key }
Tap '确定'
AssertText '030 9-key continuous buffer -> 你好' '你好' (GetText 'crp9')

StartReal
Mode 'DIGITS'
foreach ($key in @('1', '2', '3')) { Tap $key }
AssertText '050 123 -> 123' '123' (GetText 'crdig')

StartReal
Mode 'PINYIN_26'
Tap '符号'
Tap '，'
AssertText '060 symbol panel commit' '，' (GetText 'cr-symbol')

'CORE REGRESSION SUITE PASS'
