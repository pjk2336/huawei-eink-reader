[CmdletBinding()]
param(
    [switch]$Wireless,
    [string]$Device,
    [switch]$SkipInstall,
    [switch]$SkipHashCheck
)

$ErrorActionPreference = "Stop"
$PackageName = "moe.shizuku.privileged.api"
$ExpectedSha256 = "6E273AB0E991C4E79BC8B1BBB9B9DD739CCAC1A8712A541A214078886B7B790F"

function Write-Step([string]$Text) {
    Write-Host ""
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Write-Ok([string]$Text) {
    Write-Host "[OK] $Text" -ForegroundColor Green
}

function Write-Warn([string]$Text) {
    Write-Host "[!] $Text" -ForegroundColor Yellow
}

function Fail([string]$Text) {
    Write-Host "[ERROR] $Text" -ForegroundColor Red
    exit 1
}

function Find-Adb {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    $candidates += (Join-Path $PSScriptRoot "platform-tools\adb.exe")
    $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    $candidates += "C:\Android\Sdk\platform-tools\adb.exe"
    $candidates += "D:\Android\Sdk\platform-tools\adb.exe"
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return $null
}

function Invoke-Adb {
    param([string[]]$AdbArgs, [switch]$AllowFailure)
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & $script:AdbPath @AdbArgs 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($code -ne 0 -and -not $AllowFailure) {
        $text = ($output | Out-String).Trim()
        Fail "ADB command failed (exit $code): adb $($AdbArgs -join ' ')`n$text"
    }
    return @{
        Code = $code
        Output = @($output | ForEach-Object { "$_" })
    }
}

function Get-OnlineDevices {
    $result = Invoke-Adb -AdbArgs @("devices")
    return @($result.Output | Where-Object { $_ -match "^(\S+)\s+device$" } | ForEach-Object {
        ($_ -split "\s+")[0]
    })
}

function Select-Device {
    $devices = @(Get-OnlineDevices)
    if ($Device) {
        if ($devices -notcontains $Device) {
            Fail "Device '$Device' is not connected/authorized. Run: adb devices"
        }
        return $Device
    }
    if ($devices.Count -eq 1) { return $devices[0] }
    if ($devices.Count -gt 1) {
        Write-Host "Connected devices:"
        for ($i = 0; $i -lt $devices.Count; $i++) {
            Write-Host "  $($i + 1). $($devices[$i])"
        }
        $choice = Read-Host "Select device number"
        $index = 0
        if (-not [int]::TryParse($choice, [ref]$index) -or $index -lt 1 -or $index -gt $devices.Count) {
            Fail "Invalid device selection."
        }
        return $devices[$index - 1]
    }
    return $null
}

function Connect-Wireless {
    Write-Step "Wireless debugging"
    Write-Host "On the phone: Developer options -> Wireless debugging -> ON."
    Write-Host "For first pairing, open 'Pair device with pairing code'."
    $pairAddress = Read-Host "Pairing IP:port (leave blank if already paired)"
    if ($pairAddress.Trim()) {
        $pairCode = Read-Host "Six-digit pairing code"
        $pair = Invoke-Adb -AdbArgs @("pair", $pairAddress.Trim(), $pairCode.Trim()) -AllowFailure
        $pair.Output | ForEach-Object { Write-Host $_ }
        if ($pair.Code -ne 0) { Write-Warn "Pairing failed. Check the pairing port and code, then retry." }
    }
    $connectAddress = Read-Host "Wireless debugging IP:port shown on the main page"
    if (-not $connectAddress.Trim()) { Fail "A connection address is required." }
    $connect = Invoke-Adb -AdbArgs @("connect", $connectAddress.Trim()) -AllowFailure
    $connect.Output | ForEach-Object { Write-Host $_ }
    if ($connect.Code -ne 0) { Fail "Wireless connection failed." }
}

function Get-ShizukuStarter([string]$Serial) {
    $abiResult = Invoke-Adb -AdbArgs @("-s", $Serial, "shell", "getprop", "ro.product.cpu.abi")
    $abi = ($abiResult.Output -join "").Trim()
    if (-not $abi) { $abi = "arm64-v8a" }

    $pathResult = Invoke-Adb -AdbArgs @("-s", $Serial, "shell", "pm", "path", $PackageName)
    $apkPath = $pathResult.Output |
        Where-Object { $_ -match "^package:" } |
        Select-Object -First 1
    if (-not $apkPath) { Fail "Shizuku package is not installed correctly." }
    $apkPath = ($apkPath -replace "^package:", "").Trim()
    $appDir = $apkPath -replace "/base\.apk$", ""
    $starter = "$appDir/lib/$abi/libshizuku.so"

    $check = Invoke-Adb -AdbArgs @("-s", $Serial, "shell",
        "if [ -f '$starter' ]; then echo FOUND; else echo MISSING; fi") -AllowFailure
    if (($check.Output -join "") -notmatch "FOUND") {
        # Some vendor installs put the native library under a split APK directory.
        $find = Invoke-Adb -AdbArgs @("-s", $Serial, "shell",
            "ls /data/app/*/$PackageName-*/lib/$abi/libshizuku.so 2>/dev/null | head -n 1") -AllowFailure
        $starter = ($find.Output -join "").Trim()
    }
    if (-not $starter) { return $null }
    return $starter
}

function Test-ShizukuProcess([string]$Serial) {
    $result = Invoke-Adb -AdbArgs @("-s", $Serial, "shell", "ps", "-A") -AllowFailure
    return (($result.Output -join "`n") -match "shizuku")
}

$script:AdbPath = Find-Adb
if (-not $script:AdbPath) {
    Fail "adb.exe was not found. Put platform-tools next to this script or add adb to PATH."
}
$script:AdbPath = (Resolve-Path -LiteralPath $script:AdbPath).Path
Write-Host "ADB: $script:AdbPath"

Invoke-Adb -AdbArgs @("start-server") | Out-Null
$serial = Select-Device

if (-not $serial) {
    Write-Warn "No authorized Android device is connected."
    $mode = if ($Wireless) { "2" } else {
        Write-Host "  1. USB cable - enable USB debugging and authorize this computer"
        Write-Host "  2. Wireless debugging (Android 11+, pairing may be required)"
        Read-Host "Choose 1 or 2"
    }
    if ($mode -ne "2") {
        Write-Host "Connect the phone by USB, unlock it, and accept the debugging authorization dialog."
        Fail "No device is ready."
    }
    Connect-Wireless
    $serial = Select-Device
    if (-not $serial) { Fail "ADB connected but no online device was found." }
}

Write-Ok "Using device: $serial"
$apk = Get-ChildItem -LiteralPath $PSScriptRoot -Filter "shizuku-*-release.apk" |
    Sort-Object Name -Descending |
    Select-Object -First 1

if ($apk -and -not $SkipHashCheck) {
    $hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($hash -ne $ExpectedSha256) {
        Fail "Shizuku APK SHA-256 mismatch.`nExpected: $ExpectedSha256`nActual:   $hash"
    }
    Write-Ok "Shizuku APK SHA-256 verified."
}

if (-not $SkipInstall -and $apk) {
    Write-Step "Installing Shizuku $($apk.Name)"
    $installFile = Join-Path $env:TEMP "shizuku-adb-install.apk"
    Copy-Item -LiteralPath $apk.FullName -Destination $installFile -Force
    $install = Invoke-Adb -AdbArgs @("-s", $serial, "install", "-r", $installFile) -AllowFailure
    $install.Output | ForEach-Object { Write-Host $_ }
    Remove-Item -LiteralPath $installFile -Force -ErrorAction SilentlyContinue
    if ($install.Code -ne 0) {
        Fail "Install failed. Enable 'Install via USB' if your vendor requires it, then retry."
    }
} else {
    $installed = Invoke-Adb -AdbArgs @("-s", $serial, "shell", "pm", "path", $PackageName) -AllowFailure
    if (($installed.Output -join "") -notmatch "^package:") {
        Fail "Shizuku is not installed. Install it from https://shizuku.rikka.app/ or place its release APK next to this script."
    }
}

Write-Step "Opening Shizuku"
Invoke-Adb -AdbArgs @("-s", $serial, "shell", "monkey", "-p", $PackageName,
    "-c", "android.intent.category.LAUNCHER", "1") -AllowFailure | Out-Null
Start-Sleep -Seconds 2

Write-Step "Locating Shizuku v13.6+ ADB starter"
$starter = Get-ShizukuStarter $serial
if (-not $starter) {
    Write-Warn "Could not locate libshizuku.so. Trying the legacy start.sh command..."
    $legacy = Invoke-Adb -AdbArgs @("-s", $serial, "shell",
        "sh /sdcard/Android/data/$PackageName/start.sh") -AllowFailure
    $legacy.Output | ForEach-Object { Write-Host $_ }
} else {
    $command = "adb -s $serial shell $starter"
    Write-Host "Starter: $starter"
    Write-Host "Command: $command"
    $start = Invoke-Adb -AdbArgs @("-s", $serial, "shell", $starter) -AllowFailure
    $start.Output | ForEach-Object { Write-Host $_ }
}

Start-Sleep -Seconds 2
if (Test-ShizukuProcess $serial) {
    Write-Ok "Shizuku is running."
} else {
    Write-Warn "The starter returned, but the Shizuku process was not detected."
    Write-Host "Open Shizuku on the phone and tap 'View command' to compare the command shown there."
}

Write-Step "Final steps on the phone"
Write-Host "1. Open Shizuku and confirm the service status is running."
Write-Host "2. Open Ink Reader (Mo Du / package com.inkreader.reader)."
Write-Host "3. Grant Shizuku permission to Ink Reader."
Write-Host "4. In Ink Reader settings, select panel A0 or A1 before connecting the screen."
Write-Host ""
Write-Host "Android requires activation again after each reboot." -ForegroundColor Yellow
exit 0
