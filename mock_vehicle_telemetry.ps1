param (
    [float]$Speed = -1,
    [switch]$Crash,
    [int]$HeartRate = -1,
    [string]$DtcCode = "",
    [switch]$DtcClear
)

$Action = "vn.edu.haui.hvs.safedrive.action.MOCK_TELEMETRY"
$Command = "adb shell am broadcast -a $Action"

if ($Speed -ge 0) {
    $Command += " --ef speedKmh $Speed"
}

if ($Crash) {
    $Command += " --ez crashDetected true"
}

if ($HeartRate -ge 0) {
    $Command += " --ei heartRate $HeartRate"
}

if ($DtcCode -ne "") {
    $Command += " --es dtcCode `"$DtcCode`""
    if ($DtcClear) {
        $Command += " --ez dtcClear true"
    } else {
        $Command += " --ez dtcClear false"
    }
}

Write-Host "Executing: $Command"
Invoke-Expression $Command
