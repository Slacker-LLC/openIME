param(
    [switch]$SkipTests,
    [switch]$IncludeLint
)

# Gradle 在含中文的真实路径下，JDK17 worker @argfile 按 GBK
# 解码 UTF-8 classpath，导致 Unit 测试 ClassNotFoundException。junction 无效
#（Gradle 会 canonicalize 回真实路径）。方案：临时 ASCII 目录构建。
$ErrorActionPreference = 'Stop'
$src = Split-Path $PSScriptRoot -Parent
$dst = Join-Path ([System.IO.Path]::GetTempPath()) 'openime-build'

# Preserve the ASCII workspace's own Gradle/CMake outputs. Copying the source
# tree's .cxx cache over it invalidates both ABI builds after every Kotlin-only
# edit and turns a small verification build into several unnecessary minutes.
robocopy $src $dst /MIR /XD build .gradle .kotlin .cxx /NFL /NDL /NJH /NP /R:1 /W:1 | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed rc=$LASTEXITCODE" }

Push-Location $dst
try {
    $tasks = @(':app:assembleDebug')
    if (-not $SkipTests) { $tasks += ':app:testDebugUnitTest' }
    if ($IncludeLint) { $tasks += ':app:lintDebug' }
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "gradle failed rc=$LASTEXITCODE" }
} finally {
    Pop-Location
}

$artifactDir = Join-Path $src 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
Copy-Item (Join-Path $dst 'app\build\outputs\apk\debug\app-debug.apk') `
    -Destination (Join-Path $src 'artifacts\openIME-1.0-debug.apk') -Force
Write-Host "BUILD OK -> artifacts\openIME-1.0-debug.apk"
