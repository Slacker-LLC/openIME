param([string]$Serial = 'emulator-5554')

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'adb_context.ps1')
$adb = Resolve-OpenImeAdb
$Serial = Resolve-OpenImeSerial -RequestedSerial $Serial -AdbPath $adb
$project = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $project 'artifacts\openIME-1.0-debug.apk'
$pkg = 'llc.slacker.openime'
$receiver = "$pkg/.E2ETestReceiver"
$action = "$pkg.TEST_COMMAND"

function Adb { & $adb -s $Serial @args }

function SendCommand([string]$command, [int]$waitMs = 100) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $command | Out-Null
    if ($waitMs -gt 0) { Start-Sleep -Milliseconds $waitMs }
}

function SendBase64Command([string]$prefix, [string]$text) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))
    SendCommand ($prefix + $encoded) 180
}

function FocusEditor {
    for ($attempt = 0; $attempt -lt 12; $attempt++) {
        Adb shell uiautomator dump /sdcard/openime-correction.xml | Out-Null
        $local = Join-Path $env:TEMP 'openime-correction.xml'
        Adb pull /sdcard/openime-correction.xml $local | Out-Null
        $hierarchy = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
        $node = $hierarchy.SelectNodes("//node[@resource-id='$pkg`:id/test_input']") |
            Select-Object -First 1
        if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
            $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
            Adb shell input tap ([int]$x) ([int]$y) | Out-Null
            Start-Sleep -Milliseconds 500
            return
        }
        Start-Sleep -Milliseconds 400
    }
    throw 'test editor not found'
}

function EditorText {
    Adb shell uiautomator dump /sdcard/openime-correction.xml | Out-Null
    $local = Join-Path $env:TEMP 'openime-correction.xml'
    Adb pull /sdcard/openime-correction.xml $local | Out-Null
    $hierarchy = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
    return ($hierarchy.SelectNodes("//node[@resource-id='$pkg`:id/test_input']") |
        Select-Object -First 1).text
}

if (-not (Test-Path -LiteralPath $apk)) { throw "missing APK: $apk" }
Adb install -r $apk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }
Adb shell pm grant $pkg android.permission.RECORD_AUDIO | Out-Null
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell ime enable --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb shell ime set --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb shell am force-stop $pkg | Out-Null
Adb shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 1
FocusEditor
SendCommand 'clear-swipe'

$nonce = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$source = "开放爱慕专项测试$nonce"
$corrected = "openIME语音修正$nonce"
SendBase64Command 'voice-final-only64:' $source
if ((EditorText) -ne $source) { throw 'initial ASR simulation did not enter source text' }

for ($index = 0; $index -lt $source.Length; $index++) {
    SendCommand 'tap:key-backspace' 45
}
SendBase64Command 'type64:' $corrected
SendCommand 'tap:key-space' 150
SendCommand 'clear-swipe' 150

SendBase64Command 'voice-final-only64:' $source
$actual = EditorText
if ($actual -ne $corrected) {
    throw "voice correction was not learned expected=[$corrected] actual=[$actual]"
}

# Ordinary continuation must not be mislearned as a correction pair.
SendCommand 'clear-swipe' 120
$continuationSource = "普通续写$nonce"
SendBase64Command 'voice-final-only64:' $continuationSource
SendBase64Command 'type64:' '后续内容'
SendCommand 'tap:key-space' 120
SendCommand 'clear-swipe' 120
SendBase64Command 'voice-final-only64:' $continuationSource
$continuationActual = EditorText
if ($continuationActual -ne $continuationSource) {
    throw "ordinary continuation was incorrectly learned expected=[$continuationSource] actual=[$continuationActual]"
}

$fatal = Adb logcat -d -t 1000 | Select-String 'FATAL EXCEPTION|ANR in llc.slacker.openime'
if ($fatal) { throw "voice correction crash detected: $fatal" }
'VOICE CORRECTION LOCAL LEARNING PASS'
