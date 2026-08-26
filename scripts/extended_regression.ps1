param([string]$Serial = '')

$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'adb_context.ps1')
$adb = Resolve-OpenImeAdb
$Serial = Resolve-OpenImeSerial -RequestedSerial $Serial -AdbPath $adb
$pkg = 'llc.slacker.openime'
$receiver = "$pkg/.E2ETestReceiver"
$action = "$pkg.TEST_COMMAND"

function Adb { & $adb -s $Serial @args }

function FocusById([string]$id, [int]$waitSeconds = 3) {
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        Adb shell uiautomator dump /sdcard/focus.xml | Out-Null
        $local = Join-Path $env:TEMP 'ext-focus.xml'
        Adb pull /sdcard/focus.xml $local | Out-Null
        $h = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
        $xpath = '//node[@resource-id="' + $id + '"]'
        $node = $h.SelectNodes($xpath) | Select-Object -First 1
        if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
            $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
            Adb shell input tap ([int]$x) ([int]$y) | Out-Null
            Start-Sleep -Seconds $waitSeconds
            WaitForIme
            return
        }
        Start-Sleep -Seconds 1
    }
    throw ('element not found: ' + $id)
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

function StartActivity([string]$activity) {
    Adb logcat -c
    Adb shell am force-stop $pkg | Out-Null
    Adb shell am start -n "$pkg/.$activity" | Out-Null
    Start-Sleep -Seconds 3
}

function SendCommand([string]$command) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $command | Out-Null
    Start-Sleep -Milliseconds 300
}

function Tap([string]$target) {
    SendCommand ('tap:' + $target)
}

function TypeText([string]$text) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))
    SendCommand ('type64:' + $b64)
}

function GetText([string]$id) {
    Adb shell uiautomator dump /sdcard/ext.xml | Out-Null
    $local = Join-Path $env:TEMP 'ext.xml'
    Adb pull /sdcard/ext.xml $local | Out-Null
    $h = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
    $xpath = '//node[@resource-id="' + $id + '"]'
    ($h.SelectNodes($xpath) | Select-Object -First 1).text
}

function AssertText([string]$name, [string]$expected, [string]$actual) {
    if ($actual -ne $expected) {
        throw ("FAIL $name expected=[$expected] actual=[$actual]")
    }
    "EXT $name PASS -> [$actual]"
}

function AssertContains([string]$name, [string]$expected, [string]$actual) {
    if (-not $actual.Contains($expected)) {
        throw ("FAIL $name expected to contain [$expected] actual=[$actual]")
    }
    "EXT $name PASS -> contains [$expected]"
}

function WaitForMode([string]$mode) {
    for ($i = 0; $i -lt 8; $i++) {
        SendCommand 'state'
        $log = Adb logcat -d -t 300 | Select-String -Pattern 'MinisImeE2E' | Out-String
        if ($log -match ('MinisImeE2E: STATE mode=' + $mode)) { return }
        Tap 'key:mode'
        Start-Sleep -Milliseconds 350
    }
    throw ('mode not reached: ' + $mode)
}

function CommitPinyin([string]$pinyin) {
    foreach ($key in $pinyin.ToCharArray()) { Tap ([string]$key) }
    Tap 'key-space'
}

$ime = "$pkg/.LocalVoiceImeService"
Adb shell settings put --user 0 secure default_input_method $ime
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1

# Space first candidate
StartActivity 'MainActivity'
FocusById 'llc.slacker.openime:id/test_input'
WaitForMode 'PINYIN_26'
foreach ($key in @('n', 'i', 'h', 'a', 'o')) { Tap $key }
Tap 'key-space'
AssertText '021 space first candidate' '你好' (GetText 'llc.slacker.openime:id/test_input')

# Composition backspace
StartActivity 'MainActivity'
FocusById 'llc.slacker.openime:id/test_input'
WaitForMode 'PINYIN_26'
foreach ($key in @('n', 'i', 'h', 'a', 'o')) { Tap $key }
Tap 'key-backspace'
AssertText '023 composition backspace' 'niha' (GetText 'llc.slacker.openime:id/test_input')

# Committed backspace
StartActivity 'MainActivity'
FocusById 'llc.slacker.openime:id/test_input'
WaitForMode 'PINYIN_26'
TypeText '你好'
Tap 'key-backspace'
AssertText '080 committed backspace' '你' (GetText 'llc.slacker.openime:id/test_input')

# Emoji
StartActivity 'MainActivity'
FocusById 'llc.slacker.openime:id/test_input'
Tap 'Emoji'
Tap '😀'
AssertContains '060 emoji commit' '😀' (GetText 'llc.slacker.openime:id/test_input')

# Symbol
StartActivity 'MainActivity'
FocusById 'llc.slacker.openime:id/test_input'
Tap '符号'
Tap '，'
AssertContains '070 symbol commit' '，' (GetText 'llc.slacker.openime:id/test_input')

# Multi-line Enter
StartActivity 'SecurityTestActivity'
FocusById 'llc.slacker.openime:id/security_multiline'
TypeText 'a'
Tap 'key-enter'
AssertContains '090 multiline enter' "a`n" (GetText 'llc.slacker.openime:id/security_multiline')

# Panel back
StartActivity 'MainActivity'
FocusById 'llc.slacker.openime:id/test_input'
Tap '符号'
Tap 'key-panel-back'
SendCommand 'state'
$panelLog = Adb logcat -d -t 120 | Out-String
if ($panelLog -notmatch 'panel=NONE') { throw 'FAIL panel back: NONE not reached' }
'EXT 110 panel back PASS -> NONE'

# Mode switch cycle
StartActivity 'MainActivity'
FocusById 'llc.slacker.openime:id/test_input'
foreach ($mode in @('ENGLISH_26', 'PINYIN_9', 'DIGITS', 'PINYIN_26')) {
    WaitForMode $mode
}
'EXT 100 mode switch PASS'

# Long Chinese sentence: commit many independent Pinyin segments so the test
# covers repeated composing -> candidate -> commit cycles, not just one word.
StartActivity 'MainActivity'
FocusById 'llc.slacker.openime:id/test_input'
WaitForMode 'PINYIN_26'
$longSegments = @(
    @('nihao', '你好'), @('wo', '我'), @('xiang', '想'), @('chi', '吃'), @('fan', '饭'),
    @('jintian', '今天'), @('tianqi', '天气'), @('henhao', '很好'), @('mingtian', '明天'),
    @('jian', '见'), @('zai', '在'), @('gongzuo', '工作'), @('xiawu', '下午'), @('kaishi', '开始'),
    @('yi', '一'), @('ge', '个'), @('xin', '新'), @('de', '的'), @('xiangmu', '项目'),
    @('jiushi', '就是'), @('zheyang', '这样'), @('wode', '我的'), @('xuexi', '学习'),
    @('shenghuo', '生活'), @('yiding', '一定'), @('hui', '会'), @('yuelaiyue', '越来越'),
    @('hao', '好'), @('qing', '请'), @('jixu', '继续'), @('ba', '吧')
)
foreach ($segment in $longSegments) { CommitPinyin $segment[0] }
$longActual = GetText 'llc.slacker.openime:id/test_input'
$longExpected = ($longSegments | ForEach-Object { $_[1] }) -join ''
AssertText '120 long Chinese sentence' $longExpected $longActual
'EXT 120 long Chinese sentence PASS -> ' + $longActual

'EXTENDED REGRESSION SUITE PASS'
