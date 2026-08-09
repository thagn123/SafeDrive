[CmdletBinding()]
param (
    [Nullable[float]]$Speed = $null,
    [switch]$Crash,
    [switch]$ClearCrash,
    [string]$CrashSignals = "",
    [int]$HeartRate = -2,
    [string]$DtcCode = "",
    [switch]$DtcClear,
    [string]$DeviceSerial = ""
)

$ErrorActionPreference = "Stop"
$Action = "vn.edu.haui.hvs.safedrive.action.MOCK_TELEMETRY"
$Adb = (Get-Command adb -ErrorAction Stop).Source

if ($Crash -and $ClearCrash) {
    throw "Choose either -Crash or -ClearCrash, not both."
}
if ($null -ne $Speed -and ($Speed -lt 0 -or $Speed -gt 300)) {
    throw "Speed must be between 0 and 300 km/h."
}
if ($HeartRate -ne -2 -and $HeartRate -ne -1 -and ($HeartRate -lt 20 -or $HeartRate -gt 250)) {
    throw "HeartRate must be -1 (clear) or between 20 and 250 bpm."
}
if ($DtcCode -ne "" -and $DtcCode -notmatch '^[A-Z0-9_]{1,64}$') {
    throw "DtcCode may contain only A-Z, 0-9 and underscore (maximum 64 characters)."
}

if ($DeviceSerial -eq "") {
    $ConnectedDevices = @(
        & $Adb devices |
            Select-Object -Skip 1 |
            Where-Object { $_ -match '^([^\s]+)\s+device$' } |
            ForEach-Object { $Matches[1] }
    )
    if ($ConnectedDevices.Count -ne 1) {
        throw "Expected exactly one connected ADB device; found $($ConnectedDevices.Count). Use -DeviceSerial when multiple devices are connected."
    }
    $DeviceSerial = $ConnectedDevices[0]
}

$AdbArgs = @("-s", $DeviceSerial, "shell", "am", "broadcast", "-a", $Action)
if ($null -ne $Speed) { $AdbArgs += @("--ef", "speedKmh", $Speed.ToString([Globalization.CultureInfo]::InvariantCulture)) }
if ($Crash) { $AdbArgs += @("--ez", "crashDetected", "true") }
if ($ClearCrash) { $AdbArgs += @("--ez", "crashDetected", "false") }
if ($CrashSignals -ne "") { $AdbArgs += @("--es", "crashSignals", $CrashSignals) }
if ($HeartRate -ne -2) { $AdbArgs += @("--ei", "heartRate", $HeartRate.ToString()) }
if ($DtcCode -ne "") {
    $AdbArgs += @("--es", "dtcCode", $DtcCode, "--ez", "dtcClear", $DtcClear.IsPresent.ToString().ToLowerInvariant())
}

$Output = & $Adb @AdbArgs 2>&1
if ($LASTEXITCODE -ne 0) { throw ($Output -join [Environment]::NewLine) }
$Output
