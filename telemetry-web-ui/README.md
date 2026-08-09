# SafeDrive Telemetry Control Center

Dashboard cục bộ dùng trong demo để gửi tốc độ, nhịp tim, DTC và cờ va chạm tới APK debug qua ADB.

## Chạy

1. Kết nối đúng một thiết bị Android và kiểm tra bằng `adb devices`.
2. Trong SafeDrive, bật **Developer Mode**. Công tắc **Nhận dữ liệu từ PC (ADB)** mặc định bật trên APK debug.
3. Từ thư mục gốc repository, chạy:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start_dashboard.ps1
```

Dashboard mở tại `http://127.0.0.1:3000`. Server chỉ lắng nghe localhost; APK release không đăng ký receiver ADB.

Nếu kết nối nhiều thiết bị, có thể thử trực tiếp script với serial cụ thể:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\mock_vehicle_telemetry.ps1 `
  -DeviceSerial <SERIAL> -Speed 80 -HeartRate 120
```
