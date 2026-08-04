# 00 — Executive plan

## Mục tiêu

Xây Android Phone MVP SafeDrive AI có thể cài trên điện thoại thật, chạy độc lập bằng dữ liệu mock, hiển thị đầy đủ cockpit/assistant/diagnostics/settings/developer simulator, hỗ trợ text assistant, voice input, TTS và mô phỏng emergency flow. Khi backend sẵn sàng, chỉ thay gateway và cấu hình endpoint.

## Quyết định triển khai

| Quyết định | Lựa chọn |
|---|---|
| UI | Kotlin, Jetpack Compose, Material 3 |
| Kiến trúc | Một module `app`, MVVM + Use Case + Repository |
| State | Coroutines + `StateFlow`, state chính ở ViewModel/use case |
| Network | Retrofit + OkHttp; WebSocket sau khi REST ổn định |
| Local | DataStore cho settings, session và emergency snapshot |
| Voice MVP | `SpeechRecognizer` foreground + `TextToSpeech` |
| Vehicle MVP | `MockVehicleDataSource`; VHAL là adapter phase sau |
| Backend mode | Demo/Mock mặc định; Remote cấu hình runtime trong Developer Mode |
| Build toolchain | AGP 9.3.0, Gradle 9.5.0, JDK 17 |
| Android SDK | minSdk 26 mặc định, compileSdk 37, targetSdk 37 |
| Compose | Stable Compose BOM 2026.06.00 |
| Kotlin build | Dùng built-in Kotlin của AGP 9.x; không áp dụng lại `org.jetbrains.kotlin.android` |
| SOS | Chỉ payload mô phỏng, idempotent, không dispatch thật |

## Toolchain đã xác minh

Baseline trên được kiểm tra ngày 2026-07-27:

- [AGP 9.3.0](https://developer.android.com/build/releases/agp-9-3-0-release-notes) yêu cầu tối thiểu Gradle 9.5.0, JDK 17 và hỗ trợ tối đa API 37.
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom) stable hiện dùng `2026.06.00`.
- [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk) xác nhận `compileSdk = 37` và hướng dẫn opt-in `targetSdk = 37`.
- AGP 9.x có [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin); không khai báo thêm plugin Kotlin Android.

`targetSdk 37` chỉ được khóa sau khi regression behavior-change pass trên emulator/device API 37. Nếu SDK 37 chưa có trong máy build hoặc một dependency chặn target 37, đội được phép tạm dùng `targetSdk 36` trong nhánh bootstrap nhưng phải giữ `compileSdk 37`, ghi ADR và hoàn tất migration trước APK nghiệm thu. `minSdk 26` là mặc định để giảm biến thể lifecycle/permission; Product Owner có thể hạ nếu có yêu cầu thiết bị cụ thể.

## Phạm vi MVP

- Cockpit một viewport, không cuộn ở portrait phone; có layout landscape riêng.
- Status hero, tốc độ, nhiệt độ động cơ/cabin, năng lượng, thời gian lái, signal summary, DTC summary, voice status.
- Assistant text: chat list, composer, quick suggestions, loading, timeout, retry, action card và confirmation.
- Diagnostics: empty state, danh sách DTC, severity từ backend/gateway, recommendation, hỏi SafeDrive.
- Settings người dùng và Developer Mode; không hiển thị simulator/raw JSON ngoài Developer Mode.
- Simulator với toàn bộ preset hiện có và manual telemetry.
- Voice states: `DISABLED`, `IDLE`, `WAKE_WORD_DETECTED`, `LISTENING`, `PROCESSING`, `SPEAKING`, `ERROR`.
- Emergency: `VERIFYING_EVIDENCE` 5 giây, `AWAITING_USER_RESPONSE` 15 giây, `FINAL_COUNTDOWN` 10 giây, `SOS_SIMULATED_SENT` hoặc `CANCELLED`.
- REST contract và health check; WebSocket có thể để sau khi polling/REST ổn định.

## Ngoài phạm vi

Gemini Live, Gemini key trên thiết bị, Firebase Auth/Firestore, Google Maps, cloud deployment, Jetson, RAG, multi-agent, camera/DMS, VHAL thật, Android Automotive production, cuộc gọi/SMS/cứu hộ thật, wearable integration thật và background hotword 24/7.

## Thứ tự build bắt buộc

```text
Project foundation
→ Domain model + Mock Gateway
→ Cockpit
→ Assistant text
→ Simulator
→ Diagnostics + Settings
→ Voice + TTS
→ Emergency
→ Remote REST
→ Tests + APK
```

## Definition of Done cấp sản phẩm

- Debug APK build được và cài được trên điện thoại thật.
- Demo Mode chạy hoàn chỉnh không cần backend.
- Remote Mode đổi được bằng `BASE_URL`, không sửa UI.
- Cockpit không scroll/overlap ở portrait và landscape đã định.
- Assistant, diagnostics, simulator, voice/TTS và emergency đều có đường happy path và failure path.
- Rotation, process recreation, network loss, permission denial và backend timeout không làm app crash.
- Không có Gemini key, dữ liệu tài xế suy diễn quá mức hoặc dispatch thật trong APK.
- Unit test, ViewModel test, repository test và Compose UI test quan trọng đều pass.
- Có README Android, API contract, demo script, test report và bảng mock/remote coverage.

## Chỉ số thành công đo được

| Chỉ số | Ngưỡng nghiệm thu |
|---|---|
| Build | `assembleDebug`, unit test và lint đều pass |
| Startup | Cold start không crash ở Demo Mode, không phụ thuộc network |
| Cockpit | Không scroll/overlap ở 390×844 và 844×390; font scale 1.3 dùng được |
| Assistant | Không duplicate request; timeout có retry; unknown action là no-op |
| Emergency | Timeline 5/15/10 giây, rotation/process recreation không reset, sent đúng một lần |
| Security | Không secret trong APK/log; release không cleartext; dispatch thật bị khóa |
| Resilience | Permission denied, offline, invalid JSON và 5xx không làm app crash |

## Quy tắc chống scope creep

Một hạng mục chỉ được đưa vào MVP nếu đồng thời:

1. Có requirement trong bộ plan này.
2. Có owner và ngày trong roadmap.
3. Có acceptance test.
4. Không phụ thuộc Gemini Live, Maps, Firebase, VHAL hoặc dịch vụ production chưa sẵn sàng.

Nếu thiếu một điều kiện, đưa vào backlog phase sau thay vì chèn vào sprint hiện tại.
