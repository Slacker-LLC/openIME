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

function SendCommand([string]$command, [int]$delayMs = 45) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $command | Out-Null
    if ($delayMs -gt 0) { Start-Sleep -Milliseconds $delayMs }
}

function Tap([string]$target, [int]$delayMs = 45) {
    SendCommand ('tap:' + $target) $delayMs
}

function WaitForIme {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $dump = Adb shell dumpsys activity service llc.slacker.openime | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'real IME input view was not started'
}

function FocusEditor {
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        Adb shell uiautomator dump /sdcard/typing-focus.xml | Out-Null
        $local = Join-Path $env:TEMP 'typing-focus.xml'
        Adb pull /sdcard/typing-focus.xml $local | Out-Null
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

function WaitForRime {
    for ($attempt = 0; $attempt -lt 240; $attempt++) {
        SendCommand 'state' 20
        $log = Adb logcat -d -t 120 | Select-String 'OpenImeE2E: STATE' | Out-String
        if ($log -match 'rimeReady=true') { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'librime did not become ready within 120 seconds'
}

function StartReal {
    Adb shell am force-stop $pkg | Out-Null
    Adb shell am start -n "$pkg/.MainActivity" | Out-Null
    Start-Sleep -Seconds 1
    FocusEditor
    WaitForRime
}

function TypePinyin([string]$pinyin) {
    foreach ($key in $pinyin.ToCharArray()) { Tap ([string]$key) 35 }
}

function GetText([string]$name) {
    Adb shell uiautomator dump /sdcard/typing.xml | Out-Null
    $local = Join-Path $env:TEMP ($name + '.xml')
    Adb pull /sdcard/typing.xml $local | Out-Null
    $hierarchy = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
    ($hierarchy.SelectNodes('//node[@resource-id="llc.slacker.openime:id/test_input"]') |
        Select-Object -First 1).text
}

function AssertText([string]$name, [string]$expected, [string]$actual) {
    if ($actual -ne $expected) { throw "FAIL $name expected=[$expected] actual=[$actual]" }
    "TYPE $name PASS -> [$actual]"
}

function AssertStateCleared([string]$name) {
    SendCommand 'state' 100
    $log = Adb logcat -d -t 120 | Select-String 'OpenImeE2E: STATE' | Select-Object -Last 1 | Out-String
    if ($log -notmatch 'compositionLength=0') { throw "FAIL $name state=[$log]" }
    "TYPE $name PASS -> compositionLength=0"
}

function AssertContinuousSentence([string]$name, [string]$pinyin, [string]$expected) {
    StartReal
    TypePinyin $pinyin
    Tap 'key-space' 0
    AssertText $name $expected (GetText ('typing-' + $name.Replace(' ', '-')))
    AssertStateCleared ($name + ' clears pre-edit')
}

if (-not (Test-Path -LiteralPath $apk)) { throw "missing APK: $apk" }
Adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "APK install failed on $Serial" }
Adb shell pm grant $pkg android.permission.RECORD_AUDIO | Out-Null
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell ime enable --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb shell ime set --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb logcat -c

# Full Pinyin keeps the ordinary interpretation: xian -> 先.
StartReal
TypePinyin 'xian'
Tap 'key-space'
AssertText '010 full pinyin xian' '先' (GetText 'typing-xian')

# Explicit boundary is editable and reaches Rime as xi'an.
StartReal
TypePinyin 'xi'
Tap 'key-segment'
TypePinyin 'an'
Tap 'key-space'
AssertText '020 explicit segmentation xi an' '西安' (GetText 'typing-xian-split')

# Space must use Rime's current authoritative first candidate even when pressed
# immediately after the last key, before the async candidate row has repainted.
StartReal
TypePinyin 'jintiantianqi'
Tap 'key-space' 0
AssertText '030 immediate first choice' '今天天气' (GetText 'typing-weather')

# Continuous sentence segmentation, not manual one-word-at-a-time commits.
StartReal
TypePinyin 'woxiangchifan'
Tap 'key-space' 0
AssertText '040 continuous sentence' '我想吃饭' (GetText 'typing-sentence')

# A low-frequency technical term lives in the extended lexicon and must be
# reachable beyond the old five-item native menu.
StartReal
TypePinyin 'shurufayinqing'
Start-Sleep -Milliseconds 500
Tap '候选:输入法引擎'
AssertText '050 extended candidate selection' '输入法引擎' (GetText 'typing-engine-word')
AssertStateCleared '051 selected candidate clears pre-edit'
Tap 'key-backspace'
AssertText '052 backspace edits committed text' '输入法引' (GetText 'typing-engine-delete')

# Exact 10/20/50-character uninterrupted compositions. These cross the old
# 48-letter native cap without hiding a failure behind repeated word commits.
$tenPinyin = 'jintiantianqihenhaowomenxuexi'
$tenText = '今天天气很好我们学习'
AssertContinuousSentence '060 10 Chinese characters' $tenPinyin $tenText
AssertContinuousSentence '070 20 Chinese characters' ($tenPinyin * 2) ($tenText * 2)
AssertContinuousSentence '080 50 Chinese characters' ($tenPinyin * 5) ($tenText * 5)

'TYPING ENGINE REGRESSION SUITE PASS'
