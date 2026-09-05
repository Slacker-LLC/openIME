param(
    [string]$Serial = '',
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $project 'artifacts\openIME-1.0-debug.apk'
$output = Join-Path $project 'artifacts\regression\clear-delete-voice'
. (Join-Path $PSScriptRoot 'adb_context.ps1')
$adb = Resolve-OpenImeAdb
$Serial = Resolve-OpenImeSerial -RequestedSerial $Serial -AdbPath $adb
$pkg = 'llc.slacker.openime'
$receiver = "$pkg/.E2ETestReceiver"
$action = "$pkg.TEST_COMMAND"

New-Item -ItemType Directory -Force -Path $output | Out-Null

function Adb { & $adb -s $Serial @args }

function SendCommand([string]$command, [int]$waitMs = 120) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $command | Out-Null
    if ($waitMs -gt 0) { Start-Sleep -Milliseconds $waitMs }
}

function SendText([string]$text) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))
    SendCommand ('type64:' + $encoded)
}

function Tap([string]$target, [int]$waitMs = 80) {
    SendCommand ('tap:' + $target) $waitMs
}

function FocusEditor {
    $local = Join-Path $env:TEMP 'openime-clear-delete-focus.xml'
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
        Adb shell rm -f /sdcard/openime-focus.xml | Out-Null
        Adb shell uiautomator dump /sdcard/openime-focus.xml | Out-Null
        Adb pull /sdcard/openime-focus.xml $local 2>$null | Out-Null
        if (Test-Path -LiteralPath $local) {
            try {
                $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $local
                if (-not [string]::IsNullOrWhiteSpace($raw)) {
                    $hierarchy = [xml]$raw
                    $node = $hierarchy.SelectNodes('//node[@resource-id="llc.slacker.openime:id/test_input"]') |
                        Select-Object -First 1
                    if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                        $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
                        $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
                        Adb shell input tap ([int]$x) ([int]$y) | Out-Null
                        return
                    }
                }
            } catch { }
        }
        Start-Sleep -Milliseconds 400
    }
    throw 'test_input EditText was not found'
}

function WaitForIme {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $dump = Adb shell dumpsys activity service llc.slacker.openime | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'real IME input view was not started'
}

function StartReal {
    Adb shell am force-stop $pkg | Out-Null
    Adb shell am start -n "$pkg/.MainActivity" | Out-Null
    Start-Sleep -Seconds 2
    FocusEditor
    WaitForIme
}

function StateLine {
    SendCommand 'state' 80
    $line = Adb logcat -d -t 80 |
        Select-String -Pattern 'OpenImeE2E: STATE' |
        Select-Object -Last 1
    if (-not $line) { throw 'STATE log was not emitted' }
    return $line.Line
}

function StateNumber([string]$line, [string]$field) {
    if ($line -notmatch ("(?:^|\s)" + [regex]::Escape($field) + '=(\d+)')) {
        throw "missing state field $field in: $line"
    }
    return [int]$Matches[1]
}

function AssertLength([string]$name, [int]$expected) {
    $line = StateLine
    $actual = StateNumber $line 'editorLength'
    if ($actual -ne $expected) {
        throw "FAIL $name editorLength expected=$expected actual=$actual state=[$line]"
    }
    "PASS $name editorLength=$actual"
}

function AssertClean([string]$name) {
    $line = StateLine
    $editor = StateNumber $line 'editorLength'
    $composition = StateNumber $line 'compositionLength'
    if ($editor -ne 0 -or $composition -ne 0 -or $line -notmatch 'voice=false' -or
        $line -notmatch 'voiceComposing=false') {
        throw "FAIL $name stale state: $line"
    }
    "PASS $name editor=0 composition=0 voice=false voiceComposing=false"
}

function AssertEditorText([string]$name, [string]$expected) {
    $local = Join-Path $env:TEMP 'openime-clear-delete-state.xml'
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
        Adb shell rm -f /sdcard/openime-state.xml | Out-Null
        Adb shell uiautomator dump /sdcard/openime-state.xml | Out-Null
        Adb pull /sdcard/openime-state.xml $local 2>$null | Out-Null
        if (Test-Path -LiteralPath $local) {
            try {
                $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $local
                if (-not [string]::IsNullOrWhiteSpace($raw)) {
                    $hierarchy = [xml]$raw
                    $actual = ($hierarchy.SelectNodes('//node[@resource-id="llc.slacker.openime:id/test_input"]') |
                        Select-Object -First 1).text
                    if ($actual -ne $expected) {
                        throw "FAIL $name expected=[$expected] actual=[$actual]"
                    }
                    "PASS $name text verified length=$($expected.Length)"
                    return
                }
            } catch {
                if ($_.Exception.Message -match '^FAIL ') { throw }
            }
        }
        Start-Sleep -Milliseconds 400
    }
    throw "AssertEditorText failed for $name"
}

function ClearBySwipe([string]$name) {
    SendCommand 'clear-swipe' 180
    AssertClean $name
}

function DeleteOneByOne([string]$name, [string]$text) {
    SendText $text
    AssertEditorText "$name inserted" $text
    for ($remaining = $text.Length - 1; $remaining -ge 0; $remaining--) {
        Tap 'key-backspace'
        AssertLength "$name delete-to-$remaining" $remaining
    }
    AssertClean "$name final"
}

function CaptureScreen([string]$name) {
    $remote = "/sdcard/$name.png"
    Adb shell screencap -p $remote | Out-Null
    Adb pull $remote (Join-Path $output "$name.png") | Out-Null
}

if (-not $SkipInstall) {
    if (-not (Test-Path -LiteralPath $apk)) { throw "missing APK: $apk" }
    Adb install -r $apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "APK install failed on $Serial" }
}

Adb shell pm grant $pkg android.permission.RECORD_AUDIO | Out-Null
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell settings put --user 0 secure enabled_input_methods "$pkg/.LocalVoiceImeService"
Adb shell settings put --user 0 secure default_input_method "$pkg/.LocalVoiceImeService"
Adb shell ime enable --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb shell ime set --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb logcat -c
StartReal

$roundNames = @('一', '二', '三')
for ($round = 1; $round -le 3; $round++) {
    $label = $roundNames[$round - 1]
    "===== ROUND $round ====="

    $first = "第${label}轮第一段文字"
    SendText $first
    AssertEditorText "round-$round first input" $first
    ClearBySwipe "round-$round first clear"

    $second = "连续输入后立即清空${label}"
    SendText $second
    AssertEditorText "round-$round second input" $second
    ClearBySwipe "round-$round second clear"

    foreach ($letter in 'woxiangchifan'.ToCharArray()) { Tap ([string]$letter) 45 }
    $compositionState = StateLine
    $compositionLength = StateNumber $compositionState 'compositionLength'
    if ($compositionLength -ne 13) {
        throw "FAIL round-$round Pinyin composition expected=13 actual=$compositionLength"
    }
    "PASS round-$round Pinyin composition length=13"
    ClearBySwipe "round-$round composition clear"

    DeleteOneByOne "round-$round manual-delete" "逐字删除${label}测试"

    SendCommand 'voice-press' 140
    SendCommand 'bounds' 60
    CaptureScreen "round-$round-voice-hold"
    SendCommand 'voice-release' 260
    $voiceText = "这是第${label}轮语音输入测试"
    $voiceEncoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($voiceText))
    SendCommand ('voice-simulate64:' + $voiceEncoded) 240
    AssertEditorText "round-$round voice final" $voiceText
    $voiceState = StateLine
    if ($voiceState -notmatch 'voiceComposing=false') {
        throw "FAIL round-$round voice final remained composing: $voiceState"
    }
    ClearBySwipe "round-$round voice clear"

    $finalOnlyText = "只有终态也能上屏${label}"
    $finalOnlyEncoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($finalOnlyText))
    SendCommand ('voice-final-only64:' + $finalOnlyEncoded) 240
    AssertEditorText "round-$round final-only voice" $finalOnlyText
    ClearBySwipe "round-$round final-only clear"

    DeleteOneByOne "round-$round post-voice-delete" "语音后逐字删除${label}"
    CaptureScreen "round-$round-complete"
}

Adb logcat -d -v threadtime | Set-Content -Encoding UTF8 (Join-Path $output 'logcat.txt')
AssertClean 'all three rounds complete'
'CLEAR / DELETE / VOICE CROSS-STATE REGRESSION PASS (3 ROUNDS)'
