param([string]$Serial = '')

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$project = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $project 'artifacts\openIME-1.0-debug.apk'
. (Join-Path $PSScriptRoot 'adb_context.ps1')
$adb = Resolve-OpenImeAdb
$Serial = Resolve-OpenImeSerial -RequestedSerial $Serial -AdbPath $adb
$pkg = 'llc.slacker.openime'
$receiver = "$pkg/.E2ETestReceiver"
$action = "$pkg.TEST_COMMAND"

function Adb { & $adb -s $Serial @args }

function SendCommand([string]$command, [int]$delayMs = 120) {
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
    throw 'IME did not start for test-lab editor'
}

function StartField([string]$id) {
    Adb logcat -c
    Adb shell am force-stop $pkg | Out-Null
    Adb shell am start -n "$pkg/.ImeTestLabActivity" --es focus_id $id | Out-Null
    WaitForIme
}

function LatestState {
    SendCommand 'state' 150
    Adb logcat -d -t 200 |
        Select-String 'OpenImeE2E: STATE' |
        Select-Object -Last 1 |
        ForEach-Object { $_.Line }
}

function AssertMode([string]$id, [string]$expected) {
    StartField $id
    $state = LatestState
    if ($state -notmatch ("mode=" + [regex]::Escape($expected))) {
        throw "FAIL editor mode $id expected=$expected state=[$state]"
    }
    "FIELD $id mode=$expected PASS"
}

function GetVisibleText([string]$id) {
    Adb shell uiautomator dump /sdcard/openime-field.xml | Out-Null
    $local = Join-Path $env:TEMP 'openime-field.xml'
    Adb pull /sdcard/openime-field.xml $local | Out-Null
    $hierarchy = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
    $resourceId = "$pkg`:id/$id"
    ($hierarchy.SelectNodes('//node[@resource-id="' + $resourceId + '"]') | Select-Object -First 1).text
}

$ime = "$pkg/.LocalVoiceImeService"
if (-not (Test-Path -LiteralPath $apk)) { throw "missing APK: $apk" }
Adb install -r $apk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }
Adb shell ime enable --user 0 $ime | Out-Null
Adb shell ime set --user 0 $ime | Out-Null
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1

$fields = @(
    @('lab_single', 'PINYIN_26'),
    @('lab_multiline', 'PINYIN_26'),
    @('lab_password', 'ENGLISH_26'),
    @('lab_number', 'DIGITS'),
    @('lab_phone', 'DIGITS'),
    @('lab_email', 'ENGLISH_26'),
    @('lab_url', 'ENGLISH_26'),
    @('lab_search', 'PINYIN_26'),
    @('lab_chat', 'PINYIN_26'),
    @('lab_form_next', 'PINYIN_26'),
    @('lab_form_done', 'PINYIN_26'),
    @('lab_long_text', 'PINYIN_26'),
    @('lab_replace', 'PINYIN_26')
)

foreach ($field in $fields) { AssertMode $field[0] $field[1] }

StartField 'lab_single'
foreach ($key in @('n', 'i', 'h', 'a', 'o')) { Tap $key }
Tap 'key-space'
$single = GetVisibleText 'lab_single'
if ($single -ne '你好') { throw "FAIL lab_single expected=[你好] actual=[$single]" }
'FIELD normal text commit PASS'

StartField 'lab_number'
foreach ($key in @('1', '2', '3')) { Tap $key }
$number = GetVisibleText 'lab_number'
if ($number -ne '123') { throw "FAIL lab_number expected=[123] actual=[$number]" }
'FIELD numeric commit PASS'

StartField 'lab_password'
foreach ($key in @('s', 'a', 'f', 'e')) { Tap $key }
$passwordState = LatestState
if ($passwordState -notmatch 'compositionLength=0') {
    throw "FAIL password field retained composition: [$passwordState]"
}
'FIELD password candidate/composition isolation PASS'

'FIELD MATRIX REGRESSION SUITE PASS'
