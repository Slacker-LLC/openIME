function Resolve-OpenImeAdb {
    $sdkRoot = if ($env:ANDROID_HOME) {
        $env:ANDROID_HOME
    } elseif ($env:ANDROID_SDK_ROOT) {
        $env:ANDROID_SDK_ROOT
    } else {
        $null
    }

    if ($sdkRoot) {
        $adbPath = Join-Path $sdkRoot 'platform-tools\adb.exe'
    } else {
        $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
        if ($adbCommand) {
            $adbPath = $adbCommand.Source
        } elseif ($env:LOCALAPPDATA) {
            $adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
        } else {
            $adbPath = $null
        }
    }

    if (-not (Test-Path -LiteralPath $adbPath)) {
        throw 'adb not found; set ANDROID_HOME/ANDROID_SDK_ROOT or add adb to PATH'
    }
    return $adbPath
}

function Resolve-OpenImeSerial {
    param(
        [string]$RequestedSerial,
        [string]$AdbPath
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedSerial)) {
        return $RequestedSerial
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SERIAL)) {
        return $env:ANDROID_SERIAL
    }

    $devices = @(
        (& $AdbPath devices 2>$null) |
            Select-String -Pattern '^\S+\s+device$' |
            ForEach-Object { ($_ -split '\s+')[0] }
    )
    if ($devices.Count -eq 1) {
        return $devices[0]
    }
    if ($devices.Count -eq 0) {
        throw 'No adb device is available; connect a device or pass -Serial <serial>'
    }

    $emulators = @($devices | Where-Object { $_ -match '^emulator-\d+' })
    if ($emulators.Count -eq 1) {
        Write-Warning ("Multiple adb devices found ($($devices -join ', ')); defaulting to emulator " + $emulators[0])
        return $emulators[0]
    }

    throw ('Multiple adb devices found; pass -Serial: ' + ($devices -join ', '))
}
