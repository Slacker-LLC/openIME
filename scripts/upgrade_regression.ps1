param([string]$Serial = 'f0e2ff6f')

$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $project 'artifacts\openIME-1.0-debug.apk'
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $null }
$adb = if ($sdkRoot) { Join-Path $sdkRoot 'platform-tools\adb.exe' } else { (Get-Command adb -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found; set ANDROID_HOME/ANDROID_SDK_ROOT or add adb to PATH" }
$pkg = 'llc.slacker.openime'
$receiver = "$pkg/.E2ETestReceiver"
$action = "$pkg.TEST_COMMAND"
$outDir = Join-Path $project 'docs\upgrade'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Adb { & $adb -s $Serial @args }

function Push([string]$cmd) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $cmd | Out-Null
    Start-Sleep -Milliseconds 400
}

function FocusEditor {
    Adb shell uiautomator dump /sdcard/u.xml | Out-Null
    $local = Join-Path $env:TEMP 'upgrade-focus.xml'
    Adb pull /sdcard/u.xml $local | Out-Null
    $h = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
    $node = $h.SelectNodes('//node[@resource-id="llc.slacker.openime:id/lifecycle_a"]') | Select-Object -First 1
    if ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        Adb shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2)) | Out-Null
    }
}

function WaitIme {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $dump = Adb shell dumpsys activity service llc.slacker.openime | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') { return }
        Start-Sleep -Seconds 1
    }
    throw 'IME did not start'
}

function Prefs {
    (Adb shell run-as $pkg cat shared_prefs/ime_settings.xml | Out-String)
}

if (-not (Test-Path -LiteralPath $apk)) { throw 'missing APK' }
Adb install -r $apk | Out-Null
Adb shell settings put --user 0 secure default_input_method "$pkg/.LocalVoiceImeService"
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell am force-stop $pkg | Out-Null
Start-Sleep -Seconds 1
Adb shell am start -n "$pkg/.LifecycleTestActivity" | Out-Null
Start-Sleep -Seconds 3
FocusEditor
WaitIme

Push 'tap:设置'
Push 'tap:DARK'
Start-Sleep -Seconds 1
$before = Prefs
if ($before -notmatch 'DARK') { throw 'theme DARK not saved before upgrade' }

Adb install -r $apk | Out-Null
Start-Sleep -Seconds 1
$after = Prefs
if ($after -notmatch 'DARK') { throw 'theme DARK lost after reinstall' }

$json = Join-Path $outDir 'settings-retention.json'
[PSCustomObject]@{
    Device = $Serial
    Apk = (Get-Item $apk).Name
    Reinstall = 'PASS'
    ThemeBefore = 'DARK'
    ThemeAfter = 'DARK'
    PrefsPath = 'shared_prefs/ime_settings.xml'
} | ConvertTo-Json | Set-Content -LiteralPath $json -Encoding utf8
'UPGRADE SETTINGS RETENTION PASS'
