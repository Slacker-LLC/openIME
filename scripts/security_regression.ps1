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

function WaitForIme {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $dump = Adb shell dumpsys activity service $pkg | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'IME did not start for privacy test'
}

function FocusPassword {
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        Adb shell uiautomator dump /sdcard/openime-security.xml | Out-Null
        $local = Join-Path $env:TEMP 'openime-security.xml'
        Adb pull /sdcard/openime-security.xml $local | Out-Null
        $hierarchy = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
        $node = $hierarchy.SelectNodes('//node[@resource-id="llc.slacker.openime:id/security_password"]') |
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
    throw 'password editor was not found'
}

function SendCommand([string]$command) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $command | Out-Null
    Start-Sleep -Milliseconds 200
}

if (-not (Test-Path -LiteralPath $apk)) { throw "missing APK: $apk" }
Adb install -r $apk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }

$packageDump = Adb shell dumpsys package $pkg | Out-String
if ($packageDump -match 'android.permission.INTERNET') {
    throw 'FAIL unrelated INTERNET permission is present'
}
'SEC permission surface PASS -> no INTERNET permission'

$ime = "$pkg/.LocalVoiceImeService"
Adb shell ime enable --user 0 $ime | Out-Null
Adb shell ime set --user 0 $ime | Out-Null
Adb shell am force-stop $pkg | Out-Null
Adb shell am start -n "$pkg/.SecurityTestActivity" | Out-Null
FocusPassword
Adb logcat -c

$secret = 'OPENIME_PRIVACY_' + [Guid]::NewGuid().ToString('N')
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($secret))
SendCommand ('type64:' + $encoded)
SendCommand 'state'

$logs = Adb logcat -d -t 2000 | Out-String
if ($logs.Contains($secret)) { throw 'FAIL password content appeared in logcat' }
$state = $logs | Select-String 'OpenImeE2E: STATE' | Select-Object -Last 1 | Out-String
if ($state -notmatch 'compositionLength=0') {
    throw "FAIL password content entered composition state: [$state]"
}
'SEC password log/composition isolation PASS'

$fileHits = Adb shell run-as $pkg grep -R -l $secret . 2>$null | Out-String
if (-not [string]::IsNullOrWhiteSpace($fileHits)) {
    throw 'FAIL password content appeared in app-private files'
}
'SEC password private-file scan PASS'

$pidValue = (Adb shell pidof $pkg | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($pidValue)) { throw 'FAIL IME process died during privacy test' }
'SECURITY REGRESSION SUITE PASS'
