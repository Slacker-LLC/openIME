param(
    [string]$Serial = '',
    [switch]$SkipInstall
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

function Adb { & $adb -s $Serial @args }

function Encode([string]$text) {
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))
}

function Send([string]$command, [int]$waitMs = 180) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $command | Out-Null
    Start-Sleep -Milliseconds $waitMs
}

function SendExpect([string]$command, [bool]$expected) {
    Adb logcat -c
    Send $command 240
    $line = Adb logcat -d -t 60 |
        Select-String -Pattern 'OpenImeE2E: cmd=' |
        Select-Object -Last 1
    $value = $expected.ToString().ToLowerInvariant()
    if (-not $line -or $line.Line -notmatch "ok=$value") {
        throw "command result expected ok=$value actual=[$($line.Line)]"
    }
    $separator = $command.IndexOf(':')
    $kind = if ($separator -gt 0) { $command.Substring(0, $separator) } else { $command }
    "PASS command $kind ok=$value"
}

function Tap([string]$target) { Send ('tap:' + $target) }

function SendText([string]$text) { Send ('type64:' + (Encode $text)) }

function FocusEditor {
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        $local = Join-Path $env:TEMP 'openime-panel-focus.xml'
        if (Test-Path -LiteralPath $local) { Remove-Item -LiteralPath $local -Force }
        Adb shell uiautomator dump /sdcard/panel-focus.xml | Out-Null
        if ($LASTEXITCODE -ne 0) { Start-Sleep -Milliseconds 500; continue }
        Adb pull /sdcard/panel-focus.xml $local | Out-Null
        if (-not (Test-Path -LiteralPath $local)) { Start-Sleep -Milliseconds 500; continue }
        $hierarchy = try {
            [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
        } catch {
            Start-Sleep -Milliseconds 500
            continue
        }
        $node = $hierarchy.SelectNodes('//node[@resource-id="llc.slacker.openime:id/test_input"]') |
            Select-Object -First 1
        if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
            $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
            Adb shell input tap ([int]$x) ([int]$y) | Out-Null
            return
        }
        Start-Sleep -Milliseconds 400
    }
    throw 'test_input EditText was not found'
}

function StartReal {
    Adb shell am force-stop $pkg | Out-Null
    Adb shell am start -n "$pkg/.MainActivity" | Out-Null
    Start-Sleep -Seconds 2
    FocusEditor
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $dump = Adb shell dumpsys activity service llc.slacker.openime | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') { return }
        Start-Sleep -Milliseconds 400
    }
    throw 'real IME input view was not started'
}

function OpenQuickPhrases {
    SendExpect 'tap:剪贴板' $true
    SendExpect 'tap:常用语' $true
}

function GetTestInput {
    $local = Join-Path $env:TEMP 'openime-panel-state.xml'
    if (Test-Path -LiteralPath $local) { Remove-Item -LiteralPath $local -Force }
    Adb shell uiautomator dump /sdcard/panel-state.xml | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'uiautomator failed while reading test input' }
    Adb pull /sdcard/panel-state.xml $local | Out-Null
    if (-not (Test-Path -LiteralPath $local)) { throw 'test input hierarchy was not pulled' }
    $hierarchy = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
    return ($hierarchy.SelectNodes('//node[@resource-id="llc.slacker.openime:id/test_input"]') |
        Select-Object -First 1).text
}

function AssertTestInput([string]$name, [string]$expected) {
    $actual = GetTestInput
    if ($actual -ne $expected) { throw "FAIL $name expected=[$expected] actual=[$actual]" }
    "PASS $name length=$($expected.Length)"
}

function TapUiText([string]$text) {
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        $local = Join-Path $env:TEMP 'openime-panel-activity.xml'
        if (Test-Path -LiteralPath $local) { Remove-Item -LiteralPath $local -Force }
        Adb shell uiautomator dump /sdcard/panel-activity.xml | Out-Null
        if ($LASTEXITCODE -ne 0) { Start-Sleep -Milliseconds 500; continue }
        Adb pull /sdcard/panel-activity.xml $local | Out-Null
        if (-not (Test-Path -LiteralPath $local)) { Start-Sleep -Milliseconds 500; continue }
        $hierarchy = try {
            [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
        } catch {
            Start-Sleep -Milliseconds 500
            continue
        }
        $node = $hierarchy.SelectNodes("//node[@text='$text']") | Select-Object -First 1
        if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
            $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
            Adb shell input tap ([int]$x) ([int]$y) | Out-Null
            Start-Sleep -Milliseconds 500
            return
        }
        Start-Sleep -Milliseconds 350
    }
    throw "UI text not found: $text"
}

if (-not $SkipInstall) {
    if (-not (Test-Path -LiteralPath $apk)) { throw "missing APK: $apk" }
    Adb install -r $apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "APK install failed on $Serial" }
}

Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell settings put --user 0 secure enabled_input_methods "$pkg/.LocalVoiceImeService"
Adb shell settings put --user 0 secure default_input_method "$pkg/.LocalVoiceImeService"
Adb shell ime enable --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb shell ime set --user 0 "$pkg/.LocalVoiceImeService" | Out-Null

$stamp = Get-Date -Format 'HHmmss'
$clipboardText = "剪贴板真实插入$stamp"
$addedPhrase = "面板新增测试$stamp"
$editedPhrase = "面板编辑测试$stamp"

StartReal
Send ('clipboard64:' + (Encode $clipboardText))
Tap '剪贴板'
Tap '使用'
AssertTestInput 'clipboard capture and use' $clipboardText

StartReal
OpenQuickPhrases
Tap 'quick-phrase-add'
Start-Sleep -Milliseconds 600
SendText $addedPhrase
TapUiText '保存'

StartReal
OpenQuickPhrases
SendExpect ('quick-phrase-exists64:' + (Encode $addedPhrase)) $true
SendExpect ('quick-phrase-use64:' + (Encode $addedPhrase)) $true
AssertTestInput 'quick phrase add and use' $addedPhrase

StartReal
OpenQuickPhrases
SendExpect ('quick-phrase-edit64:' + (Encode $addedPhrase)) $true
Start-Sleep -Milliseconds 600
SendExpect 'clear-swipe' $true
SendText $editedPhrase
TapUiText '保存'

StartReal
OpenQuickPhrases
SendExpect ('quick-phrase-exists64:' + (Encode $addedPhrase)) $false
SendExpect ('quick-phrase-exists64:' + (Encode $editedPhrase)) $true
SendExpect ('quick-phrase-use64:' + (Encode $editedPhrase)) $true
AssertTestInput 'quick phrase edit and use' $editedPhrase

StartReal
OpenQuickPhrases
SendExpect ('quick-phrase-delete64:' + (Encode $editedPhrase)) $true
SendExpect ('quick-phrase-exists64:' + (Encode $editedPhrase)) $false

'PANEL DATA REGRESSION PASS (clipboard + quick phrase add/edit/delete)'
