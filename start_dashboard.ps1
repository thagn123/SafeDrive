# Khởi động Node.js backend và mở trình duyệt

$WebUIDir = "telemetry-web-ui"

# Kiểm tra thư mục
if (!(Test-Path $WebUIDir)) {
    Write-Host "Không tìm thấy thư mục $WebUIDir." -ForegroundColor Red
    exit 1
}

Write-Host "Đang khởi động SafeDrive Control Center..." -ForegroundColor Cyan

# Mở trình duyệt ẩn danh hoặc trình duyệt mặc định (chờ 2s để server khởi động)
Start-Job -ScriptBlock {
    Start-Sleep -Seconds 2
    Start-Process "http://localhost:3000"
} | Out-Null

# Khởi động server
cd $WebUIDir
node telemetry_server.js
