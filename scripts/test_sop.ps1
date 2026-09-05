param(
    [ValidateSet('L0', 'L1', 'L2', 'L3')]
    [string]$Level = 'L0',
    [string]$Serial = '',
    [string]$EvidenceRoot = '',
    [switch]$SkipBuild,
    [switch]$FreshInstall,
    [switch]$NoScreenRecord,
    [switch]$NoShowTouches,
    [switch]$ListOnly
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$project = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'adb_context.ps1')

$levelRank = @{ L0 = 0; L1 = 1; L2 = 2; L3 = 3 }
$selectedRank = $levelRank[$Level]
$steps = [System.Collections.Generic.List[object]]::new()

function Add-SopStep {
    param(
        [string]$MinimumLevel,
        [string]$Name,
        [string]$Script,
        [bool]$UsesDevice = $true,
        [string[]]$Arguments = @()
    )
    if ($levelRank[$MinimumLevel] -le $selectedRank) {
        $steps.Add([pscustomobject]@{
            MinimumLevel = $MinimumLevel
            Name = $Name
            Script = (Join-Path $PSScriptRoot $Script)
            UsesDevice = $UsesDevice
            Arguments = $Arguments
        })
    }
}

if (-not $SkipBuild) {
    $buildArguments = if ($selectedRank -ge $levelRank.L2) { @('-IncludeLint') } else { @() }
    Add-SopStep 'L0' '构建与 JVM 门禁' 'build_ascii.ps1' $false $buildArguments
}
Add-SopStep 'L0' '核心输入冒烟' 'core_regression.ps1'
Add-SopStep 'L1' '拼音九键快速连续输入' 'nine_key_regression.ps1'
Add-SopStep 'L1' '清空删除与语音交叉状态' 'clear_delete_voice_regression.ps1'
Add-SopStep 'L1' '中文输入引擎' 'typing_engine_regression.ps1'
Add-SopStep 'L1' '输入框类型矩阵' 'field_matrix_regression.ps1'
Add-SopStep 'L1' '面板与编辑扩展回归' 'extended_regression.ps1'
Add-SopStep 'L1' '生命周期回归' 'lifecycle_regression.ps1'
Add-SopStep 'L1' '语音模型预热与热驻留' 'voice_lifecycle_regression.ps1'
Add-SopStep 'L1' '当前窗口视觉结构' 'visual_check.ps1'
Add-SopStep 'L2' '320～600dp 模拟器视觉矩阵' 'visual_matrix_regression.ps1'
Add-SopStep 'L2' '剪贴板与常用语数据链路' 'panel_data_regression.ps1'
Add-SopStep 'L2' '本地语音纠错学习' 'voice_correction_regression.ps1'
Add-SopStep 'L2' '升级与设置保留' 'upgrade_regression.ps1'
Add-SopStep 'L2' '性能基线' 'perf_baseline.ps1'
Add-SopStep 'L3' '隐私与权限边界' 'security_regression.ps1'
Add-SopStep 'L3' '压力基线' 'stress_baseline.ps1'

if ($ListOnly) {
    "openIME SOP $Level"
    $steps | ForEach-Object {
        "[$($_.MinimumLevel)] $($_.Name) -> $([IO.Path]::GetFileName($_.Script))"
    }
    if ($selectedRank -ge $levelRank.L2) {
        'MANUAL: 多宽度/字体/导航/横竖屏/分屏/外部应用/语音语料，见 docs/TEST_SOP_CHECKLIST.md'
    }
    exit 0
}

$adb = Resolve-OpenImeAdb
$Serial = Resolve-OpenImeSerial -RequestedSerial $Serial -AdbPath $adb
$pkg = 'llc.slacker.openime'
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'

function Get-Sha256Text([string]$value) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($value))) -replace '-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

$serialHash = (Get-Sha256Text $Serial).Substring(0, 12)
if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
    $EvidenceRoot = Join-Path $project '.local\test-runs'
}
$runDir = Join-Path $EvidenceRoot "$runId-$Level-$serialHash"
$stepsDir = Join-Path $runDir 'steps'
$screensDir = Join-Path $runDir 'screenshots'
$uiDir = Join-Path $runDir 'ui'
$videoDir = Join-Path $runDir 'video'
$systemDir = Join-Path $runDir 'system'
foreach ($path in @($runDir, $stepsDir, $screensDir, $uiDir, $videoDir, $systemDir)) {
    New-Item -ItemType Directory -Force -Path $path | Out-Null
}
Copy-Item -LiteralPath (Join-Path $project 'docs\TEST_SOP_CHECKLIST.md') `
    -Destination (Join-Path $runDir 'manual-checklist.md') -Force

function Adb { & $adb -s $Serial @args }

function DeviceText {
    # Keep adb switches such as -d/-v in the ordinary argument list. An advanced
    # PowerShell parameter block would consume them as Debug/Verbose parameters.
    $commandArguments = @($args)
    try { (Adb @commandArguments 2>&1 | Out-String).Trim() } catch { "UNAVAILABLE: $($_.Exception.Message)" }
}

$apk = Join-Path $project 'artifacts\openIME-1.0-debug.apk'
$apkInfo = if (Test-Path -LiteralPath $apk) { Get-Item -LiteralPath $apk } else { $null }
$metadata = [ordered]@{
    schema = 'openime-test-run-v1'
    runId = $runId
    level = $Level
    startedAt = (Get-Date -Format o)
    gitCommit = ((& git -C $project rev-parse HEAD 2>$null) | Out-String).Trim()
    dirtyPaths = @(& git -C $project status --porcelain).Count
    serialHash = $serialHash
    manufacturer = DeviceText shell getprop ro.product.manufacturer
    model = DeviceText shell getprop ro.product.model
    androidRelease = DeviceText shell getprop ro.build.version.release
    sdk = DeviceText shell getprop ro.build.version.sdk
    buildFingerprint = DeviceText shell getprop ro.build.fingerprint
    displaySize = DeviceText shell wm size
    displayDensity = DeviceText shell wm density
    fontScale = DeviceText shell settings get system font_scale
    navigationMode = DeviceText shell settings get secure navigation_mode
    nightMode = DeviceText shell cmd uimode night
    currentIme = DeviceText shell settings get secure default_input_method
    freshInstall = [bool]$FreshInstall
    screenRecording = -not [bool]$NoScreenRecord
    apk = if ($apkInfo) {
        [ordered]@{
            name = $apkInfo.Name
            bytes = $apkInfo.Length
            modifiedAt = $apkInfo.LastWriteTime.ToString('o')
            sha256 = (Get-FileHash -LiteralPath $apkInfo.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    } else { $null }
}
$metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir 'metadata.json') -Encoding utf8

function Capture-Screenshot([string]$name) {
    $path = Join-Path $screensDir "$name.png"
    $command = "`"$adb`" -s `"$Serial`" exec-out screencap -p > `"$path`""
    & cmd.exe /d /c $command
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $path)) { throw "screenshot failed: $name" }
    $bytes = [IO.File]::ReadAllBytes($path)
    if ($bytes.Length -lt 8 -or $bytes[0] -ne 0x89) { throw "invalid PNG: $name" }
}

function Capture-UiTree([string]$name) {
    $remote = "/sdcard/openime-sop-$runId.xml"
    Adb shell uiautomator dump $remote | Out-Null
    Adb pull $remote (Join-Path $uiDir "$name.xml") | Out-Null
    Adb shell rm -f $remote | Out-Null
}

function Start-ScreenRecord([string]$name) {
    if ($NoScreenRecord) { return $null }
    $remote = "/sdcard/openime-sop-$runId-$name.mp4"
    Adb shell rm -f $remote | Out-Null
    $arguments = @(
        '-s', $Serial, 'shell', 'screenrecord', '--bit-rate', '6000000',
        '--time-limit', '180', $remote
    )
    $process = Start-Process -FilePath $adb -ArgumentList $arguments -PassThru -WindowStyle Hidden
    [pscustomobject]@{ Process = $process; Remote = $remote; Name = $name }
}

function Stop-ScreenRecord($record) {
    if ($null -eq $record) { return }
    $remotePid = (Adb shell pidof screenrecord 2>$null | Out-String).Trim()
    if (-not [string]::IsNullOrWhiteSpace($remotePid)) {
        Adb shell kill -2 (($remotePid -split '\s+')[0]) 2>$null | Out-Null
        Start-Sleep -Seconds 2
    }
    if (-not $record.Process.HasExited) {
        Stop-Process -Id $record.Process.Id -Force -ErrorAction SilentlyContinue
    }
    $local = Join-Path $videoDir "$($record.Name).mp4"
    Adb pull $record.Remote $local 2>$null | Out-Null
    Adb shell rm -f $record.Remote 2>$null | Out-Null
}

function Capture-SystemEvidence([string]$name) {
    (DeviceText logcat -d -v threadtime) |
        Set-Content -LiteralPath (Join-Path $systemDir "$name-logcat.txt") -Encoding utf8
    (DeviceText shell dumpsys meminfo $pkg) |
        Set-Content -LiteralPath (Join-Path $systemDir "$name-meminfo.txt") -Encoding utf8
    (DeviceText shell dumpsys gfxinfo $pkg framestats) |
        Set-Content -LiteralPath (Join-Path $systemDir "$name-framestats.txt") -Encoding utf8
    (DeviceText shell dumpsys input_method) |
        Set-Content -LiteralPath (Join-Path $systemDir "$name-input-method.txt") -Encoding utf8
    (DeviceText logcat -d -v brief '*:E') |
        Set-Content -LiteralPath (Join-Path $systemDir "$name-errors.txt") -Encoding utf8
}

$shellCommand = Get-Command pwsh -ErrorAction SilentlyContinue
if (-not $shellCommand) { $shellCommand = Get-Command powershell -ErrorAction Stop }
$hostShell = $shellCommand.Source
$results = [System.Collections.Generic.List[object]]::new()
$originalShowTouches = DeviceText shell settings get system show_touches
$failed = $false
$devicePrepared = $false
$freshInstallApplied = $false

try {
    $index = 0
    foreach ($step in $steps) {
        $index++
        $leaf = [IO.Path]::GetFileNameWithoutExtension($step.Script)
        $slug = '{0:d2}-{1}' -f $index, ([regex]::Replace($leaf, '[^a-zA-Z0-9_-]', '-'))
        $stepLog = Join-Path $stepsDir "$slug.log"
        $started = Get-Date
        $record = $null
        $status = 'PASS'
        $message = ''

        try {
            "=== $($step.Name) ===" | Tee-Object -FilePath $stepLog
            if ($step.UsesDevice) {
                if (-not $devicePrepared) {
                    if (-not $NoShowTouches) { Adb shell settings put system show_touches 1 | Out-Null }
                    if ($FreshInstall) {
                        $installed = DeviceText shell pm list packages $pkg
                        if ($installed -match [regex]::Escape($pkg)) {
                            Adb uninstall $pkg | Out-Null
                            if ($LASTEXITCODE -ne 0) { throw 'fresh-install uninstall failed' }
                        }
                        $freshInstallApplied = $true
                    }
                    $growthLimit = (DeviceText shell getprop dalvik.vm.heapgrowthlimit).Trim()
                    if ($growthLimit -match '^(\d+)m' -and [int]$Matches[1] -lt 256) {
                        Adb root | Out-Null
                        Start-Sleep -Seconds 1
                        Adb shell setprop dalvik.vm.heapgrowthlimit 256m | Out-Null
                        Adb shell am force-stop $pkg | Out-Null
                    }
                    $devicePrepared = $true
                }
                Adb logcat -c
                try { Capture-Screenshot "$slug-before" } catch { "BEFORE SCREENSHOT: $($_.Exception.Message)" | Add-Content $stepLog }
                try { Capture-UiTree "$slug-before" } catch { "BEFORE UI: $($_.Exception.Message)" | Add-Content $stepLog }
                $record = Start-ScreenRecord $slug
            }

            $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $step.Script)
            if ($step.UsesDevice) { $arguments += @('-Serial', $Serial) }
            if ([IO.Path]::GetFileName($step.Script) -eq 'visual_matrix_regression.ps1') {
                $arguments += @('-OutputDir', (Join-Path $runDir 'visual-matrix'))
            }
            $arguments += $step.Arguments
            & $hostShell @arguments 2>&1 | Tee-Object -FilePath $stepLog -Append
            if ($LASTEXITCODE -ne 0) { throw "step exited with code $LASTEXITCODE" }
        } catch {
            $status = 'FAIL'
            $message = $_.Exception.Message
            "FAILED: $message" | Tee-Object -FilePath $stepLog -Append
            $failed = $true
        } finally {
            if ($step.UsesDevice) {
                Stop-ScreenRecord $record
                try { Capture-Screenshot "$slug-after" } catch { "AFTER SCREENSHOT: $($_.Exception.Message)" | Add-Content $stepLog }
                try { Capture-UiTree "$slug-after" } catch { "AFTER UI: $($_.Exception.Message)" | Add-Content $stepLog }
                try { Capture-SystemEvidence $slug } catch { "SYSTEM EVIDENCE: $($_.Exception.Message)" | Add-Content $stepLog }
            }
        }

        $results.Add([pscustomobject]@{
            index = $index
            name = $step.Name
            script = [IO.Path]::GetFileName($step.Script)
            status = $status
            message = $message
            durationSeconds = [Math]::Round(((Get-Date) - $started).TotalSeconds, 2)
        })
        if ($failed) { break }
    }
} finally {
    if ($devicePrepared -and -not $NoShowTouches -and $originalShowTouches -match '^[01]$') {
        Adb shell settings put system show_touches $originalShowTouches | Out-Null
    }
}

$finalApkInfo = if (Test-Path -LiteralPath $apk) { Get-Item -LiteralPath $apk } else { $null }
$metadata['completedAt'] = (Get-Date -Format o)
$metadata['freshInstallApplied'] = $freshInstallApplied
$metadata['apk'] = if ($finalApkInfo) {
    [ordered]@{
        name = $finalApkInfo.Name
        bytes = $finalApkInfo.Length
        modifiedAt = $finalApkInfo.LastWriteTime.ToString('o')
        sha256 = (Get-FileHash -LiteralPath $finalApkInfo.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
} else { $null }
$metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir 'metadata.json') -Encoding utf8

$automatedStatus = if ($failed) { 'FAIL' } else { 'PASS' }
$manualPending = $selectedRank -ge $levelRank.L2
$summary = [ordered]@{
    schema = 'openime-test-summary-v1'
    runId = $runId
    level = $Level
    automatedStatus = $automatedStatus
    manualChecklistPending = $manualPending
    strictFreshInstall = [bool]$FreshInstall
    freshInstallApplied = $freshInstallApplied
    completedAt = (Get-Date -Format o)
    evidenceDirectory = $runDir
    steps = $results
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir 'summary.json') -Encoding utf8

$markdown = [System.Collections.Generic.List[string]]::new()
$markdown.Add("# openIME $Level 测试结果")
$markdown.Add('')
$markdown.Add("- 自动化结果：$automatedStatus")
$markdown.Add("- 全新安装：$([bool]$FreshInstall)")
$markdown.Add("- 人工清单待完成：$manualPending")
$markdown.Add('')
$markdown.Add('| 步骤 | 状态 | 秒 |')
$markdown.Add('|---|---:|---:|')
foreach ($result in $results) {
    $markdown.Add("| $($result.name) | $($result.status) | $($result.durationSeconds) |")
}
$markdown | Set-Content -LiteralPath (Join-Path $runDir 'summary.md') -Encoding utf8

"SOP EVIDENCE -> $runDir"
if ($failed) { exit 1 }
if ($manualPending) { 'AUTOMATED PASS; COMPLETE manual-checklist.md BEFORE RELEASE' }
else { 'SOP AUTOMATED PASS' }
