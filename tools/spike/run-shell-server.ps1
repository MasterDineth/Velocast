param(
    [Alias('s')]
    [string]$Serial = $env:ADB_SERIAL
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir '..\..')).Path
$ResultsDir = Join-Path $ScriptDir 'results'
$RemoteJar = '/data/local/tmp/velocast-server.jar'
$RemoteOut = '/data/local/tmp/velocast-probe.json'
$LocalJar = Join-Path $RepoRoot 'server-core\build\outputs\dex\velocast-server.jar'
$MainClass = 'com.mastersoft.velocast.server.CapabilityProbeMain'

function Invoke-Adb {
    param([string[]]$AdbArgs)
    if ($Serial) {
        & adb -s $Serial @AdbArgs
    } else {
        & adb @AdbArgs
    }
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed (exit $LASTEXITCODE): adb $($AdbArgs -join ' ')"
    }
}

if (-not $Serial) {
    $deviceLines = & adb devices | Select-String -Pattern '^(\S+)\s+device(\s|$)'
    $connected = @($deviceLines | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($connected.Count -eq 0) {
        throw "No authorised ADB device detected. Check 'adb devices -l'."
    }
    if ($connected.Count -gt 1) {
        & adb devices -l
        throw "Multiple ADB devices detected; pass -Serial <serial>."
    }
    $Serial = $connected[0]
}

Write-Host "==> Building :server-core dex jar..."
Push-Location $RepoRoot
try {
    & (Join-Path $RepoRoot 'gradlew.bat') ':server-core:assembleDexJar' '--console=plain'
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle :server-core:assembleDexJar failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

if (-not (Test-Path $LocalJar)) {
    throw "Expected dex jar not found at $LocalJar"
}

$ModelRaw = ((Invoke-Adb @('shell', 'getprop', 'ro.product.model')) -join '').Trim()
$ApiRaw = ((Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim()
$ReleaseRaw = ((Invoke-Adb @('shell', 'getprop', 'ro.build.version.release')) -join '').Trim()
$ManufacturerRaw = ((Invoke-Adb @('shell', 'getprop', 'ro.product.manufacturer')) -join '').Trim()

$ModelSafe = ($ModelRaw -replace '[^A-Za-z0-9._-]+', '-').Trim('-')
if (-not $ModelSafe) {
    $ModelSafe = 'unknown-device'
}

New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$OutFile = Join-Path $ResultsDir "$ModelSafe-android$ApiRaw.json"

Write-Host "==> Target: $ManufacturerRaw $ModelRaw (Android $ReleaseRaw, API $ApiRaw)"
Write-Host "==> Pushing $LocalJar -> $RemoteJar"
Invoke-Adb @('push', $LocalJar, $RemoteJar) | Out-Null

Write-Host "==> Running CapabilityProbe as shell user via app_process..."
Invoke-Adb @('shell', "rm -f '$RemoteOut' && CLASSPATH='$RemoteJar' app_process /system/bin '$MainClass' --out '$RemoteOut'") | Out-Null

Write-Host "==> Pulling results -> $OutFile"
Invoke-Adb @('pull', $RemoteOut, $OutFile) | Out-Null
& adb -s $Serial shell "rm -f '$RemoteOut'" *> $null

Write-Host ""
Write-Host "Summary ($OutFile):"
$rows = Get-Content -Path $OutFile -Encoding UTF8 |
    Where-Object { $_.Trim().Length -gt 0 } |
    ForEach-Object { $_ | ConvertFrom-Json }

$rows | ForEach-Object {
    $reasonOrPerm = if ($_.permission) { $_.permission } elseif ($_.reason) { $_.reason } else { '-' }
    $detail = if ($_.detail) { $_.detail } else { '-' }
    [PSCustomObject]@{
        Capability = $_.capability
        Status = $_.status
        'Reason / Permission' = $reasonOrPerm
        Detail = $detail
    }
} | Format-Table -AutoSize
