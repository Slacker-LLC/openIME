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

function FocusById([string]$id) {
    $local = Join-Path $env:TEMP 'openime-voice-lifecycle.xml'
    for ($attempt = 0; $attempt -lt 12; $attempt++) {
        Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
        Adb shell rm -f /sdcard/openime-voice-lifecycle.xml | Out-Null
        Adb shell uiautomator dump /sdcard/openime-voice-lifecycle.xml | Out-Null
        Adb pull /sdcard/openime-voice-lifecycle.xml $local 2>$null | Out-Null
        if (-not (Test-Path -LiteralPath $local)) {
            Start-Sleep -Milliseconds 500
            continue
        }
        try {
            $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $local
            if (-not [string]::IsNullOrWhiteSpace($raw)) {
                $hierarchy = [xml]$raw
                $node = $hierarchy.SelectNodes("//node[@resource-id='$id']") | Select-Object -First 1
                if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                    $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
                    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
                    Adb shell input tap ([int]$x) ([int]$y) | Out-Null
                    WaitForIme
                    return
                }
            }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "editor not found: $id"
}

function WaitForIme {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $dump = Adb shell dumpsys activity service $pkg | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') { return }
        Start-Sleep -Milliseconds 250
    }
    throw 'real IME input view did not start'
}

function StateLine {
    SendCommand 'state' 80
    $line = Adb logcat -d -t 200 | Select-String 'OpenImeE2E: STATE' | Select-Object -Last 1
    if (-not $line) { throw 'voice lifecycle STATE log missing' }
    return $line.Line
}

function WaitForVoiceState([string]$expected, [int]$attempts = 30) {
    for ($attempt = 0; $attempt -lt $attempts; $attempt++) {
        $line = StateLine
        if ($line -match "voiceModel=$expected") { return $line }
        Start-Sleep -Milliseconds 300
    }
    throw "voice model did not enter $expected"
}

function PreloadCount {
    return @(Adb logcat -d | Select-String 'OpenImeVoiceLifecycle: preloadComplete').Count
}

if (-not (Test-Path -LiteralPath $apk)) { throw "missing APK: $apk" }
Adb install -r $apk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }
Adb shell pm grant $pkg android.permission.RECORD_AUDIO | Out-Null
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell ime enable --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
Adb shell ime set --user 0 "$pkg/.LocalVoiceImeService" | Out-Null
$growthLimit = (Adb shell getprop dalvik.vm.heapgrowthlimit | Out-String).Trim()
if ($growthLimit -match '^(\d+)m' -and [int]$Matches[1] -lt 256) {
    Adb root | Out-Null
    Start-Sleep -Seconds 1
    Adb shell setprop dalvik.vm.heapgrowthlimit 256m | Out-Null
}
Adb shell am force-stop $pkg | Out-Null
Adb logcat -c
Adb shell am start -n "$pkg/.LifecycleTestActivity" | Out-Null
Start-Sleep -Milliseconds 600
FocusById "$pkg`:id/lifecycle_a"

# Typing must remain responsive while the model is still VERIFYING/PRELOADING.
SendCommand 'clear-swipe' 80
foreach ($key in @('n', 'i', 'h', 'a', 'o')) { SendCommand ("tap:$key") 40 }
$coldTyping = StateLine
if ($coldTyping -notmatch 'compositionLength=5') {
    throw "typing blocked during voice preload: $coldTyping"
}
'VOICE LIFECYCLE cold typing responsiveness PASS'

WaitForVoiceState 'HOT' | Out-Null
$firstPreloadCount = PreloadCount
if ($firstPreloadCount -lt 1) { throw 'initial voice preload was not logged' }
'VOICE LIFECYCLE initial preload PASS'

# Hide/reopen within 10 seconds: keep the same mapped recognizer.
Adb shell input keyevent 4 | Out-Null
Start-Sleep -Seconds 3
FocusById "$pkg`:id/lifecycle_b"
WaitForVoiceState 'HOT' | Out-Null
$warmReuseCount = PreloadCount
if ($warmReuseCount -ne $firstPreloadCount) {
    throw "unexpected reload inside cooldown first=$firstPreloadCount current=$warmReuseCount"
}
'VOICE LIFECYCLE 10-second warm reuse PASS'

# Stay hidden beyond cooldown: release, then preload again on next editor.
Adb shell input keyevent 4 | Out-Null
Start-Sleep -Seconds 12
$unloadCount = @(Adb logcat -d | Select-String 'OpenImeVoiceLifecycle: unloadMs=').Count
if ($unloadCount -lt 1) { throw 'recognizer did not unload after cooldown' }
FocusById "$pkg`:id/lifecycle_a"
WaitForVoiceState 'HOT' | Out-Null
$reloadedCount = PreloadCount
if ($reloadedCount -le $warmReuseCount) {
    throw "recognizer did not preload after cold reopen previous=$warmReuseCount current=$reloadedCount"
}
'VOICE LIFECYCLE delayed unload and cold reopen PASS'

$fatal = Adb logcat -d | Select-String 'FATAL EXCEPTION|ANR in llc.slacker.openime'
if ($fatal) { throw "voice lifecycle crash detected: $fatal" }
'VOICE LIFECYCLE REGRESSION PASS'
