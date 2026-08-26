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
$outDir = Join-Path $project 'docs\stress'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Adb { & $adb -s $Serial @args }

function WaitIme {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $dump = Adb shell dumpsys activity service llc.slacker.openime | Out-String
        if ($dump -match 'mInputViewStarted=true' -and $dump -match 'mShowInputRequested=true') { return }
        Start-Sleep -Seconds 1
    }
    throw 'IME did not start'
}

function Push([string]$cmd) {
    Adb shell am broadcast -n $receiver -a $action --es cmd $cmd | Out-Null
    Start-Sleep -Milliseconds 60
}

if (-not (Test-Path -LiteralPath $apk)) { throw 'missing APK' }
Adb install -r $apk | Out-Null
Adb shell settings put --user 0 secure default_input_method "$pkg/.LocalVoiceImeService"
Adb shell settings put --user 0 secure show_ime_with_hard_keyboard 1
Adb shell ime enable --user 0 "$pkg/.LocalVoiceImeService"
Adb shell ime set --user 0 "$pkg/.LocalVoiceImeService"
Adb shell am force-stop $pkg | Out-Null
Start-Sleep -Seconds 1
Adb shell am start -n "$pkg/.LifecycleTestActivity" | Out-Null
Start-Sleep -Seconds 3
Adb shell uiautomator dump /sdcard/stress.xml | Out-Null
$local = Join-Path $env:TEMP 'stress-focus.xml'
Adb pull /sdcard/stress.xml $local | Out-Null
$h = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $local)
$node = $h.SelectNodes('//node[@resource-id="llc.slacker.openime:id/lifecycle_a"]') | Select-Object -First 1
if ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    Adb shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2)) | Out-Null
}
WaitIme

$start = Get-Date
for ($i = 0; $i -lt 500; $i++) {
    Push 'tap:key:mode'
    Push 'tap:Emoji'
    Push 'tap:key-panel-back'
}
$elapsed = ((Get-Date) - $start).TotalSeconds
$pidValue = (Adb shell pidof $pkg | Out-String).Trim()
$memory = Adb shell dumpsys meminfo $pkg | Out-String
$fatal = Adb logcat -d -t 2000 | Out-String
$json = Join-Path $outDir 'stress-500.json'
[PSCustomObject]@{
    Device = $Serial
    Iterations = 500
    Commands = 1500
    ElapsedSeconds = [Math]::Round($elapsed, 2)
    Pid = $pidValue
    PssKb = ([regex]::Match($memory, 'TOTAL PSS:\s*(\d+)').Groups[1].Value)
    FatalAnr = ($fatal -match 'FATAL|ANR|AndroidRuntime')
} | ConvertTo-Json | Set-Content -LiteralPath $json -Encoding utf8
Get-Content -LiteralPath $json -Raw
