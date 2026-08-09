$ErrorActionPreference = "Stop"
$WebUIDir = Join-Path $PSScriptRoot "telemetry-web-ui"
$DashboardUrl = "http://127.0.0.1:3000"

if (!(Test-Path -LiteralPath $WebUIDir)) {
    throw "Không tìm thấy thư mục $WebUIDir."
}

Write-Host "Đang khởi động SafeDrive Control Center..." -ForegroundColor Cyan
Push-Location $WebUIDir
try {
    if (!(Test-Path -LiteralPath "node_modules")) {
        npm.cmd install
        if ($LASTEXITCODE -ne 0) { throw "npm install thất bại." }
    }
    Start-Job -ArgumentList $DashboardUrl -ScriptBlock {
        param($Url)
        Start-Sleep -Seconds 2
        Start-Process $Url
    } | Out-Null
    node telemetry_server.js
} finally {
    Pop-Location
}
