param([switch]$SkipTests)

# Gradle 在含中文的真实路径下，JDK17 worker @argfile 按 GBK
# 解码 UTF-8 classpath，导致 Unit 测试 ClassNotFoundException。junction 无效
#（Gradle 会 canonicalize 回真实路径）。方案：临时 ASCII 目录构建。
$ErrorActionPreference = 'Stop'
$src = Split-Path $PSScriptRoot -Parent
$dst = Join-Path ([System.IO.Path]::GetTempPath()) 'openime-build'

robocopy $src $dst /MIR /XD build .gradle .kotlin /NFL /NDL /NJH /NP /R:1 /W:1 | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed rc=$LASTEXITCODE" }

Push-Location $dst
try {
    if ($SkipTests) {
        & .\gradlew.bat :app:assembleDebug --console=plain
    } else {
        & .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
    }
    if ($LASTEXITCODE -ne 0) { throw "gradle failed rc=$LASTEXITCODE" }
} finally {
    Pop-Location
}

$artifactDir = Join-Path $src 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
Copy-Item (Join-Path $dst 'app\build\outputs\apk\debug\app-debug.apk') `
    -Destination (Join-Path $src 'artifacts\openIME-1.0-debug.apk') -Force
Write-Host "BUILD OK -> artifacts\openIME-1.0-debug.apk"
