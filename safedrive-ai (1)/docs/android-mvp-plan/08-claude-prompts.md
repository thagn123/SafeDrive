# 08 — Prompt giao tuần tự cho Claude

Các prompt dưới đây dùng sau khi đưa toàn bộ thư mục plan này cho Claude. Mỗi phase chỉ được làm trong phạm vi đã nêu; sau mỗi phase phải chạy kiểm tra và báo cáo file thay đổi.

## Execution protocol bắt buộc

Claude phải tuân thủ ở mọi phase:

1. Đọc file plan được dẫn và source liên quan trước khi sửa.
2. Nêu assumption; không tự đổi contract, enum, deadline, scope hoặc toolchain.
3. Giữ source prototype web làm reference; Android project nằm tách biệt theo quyết định của owner.
4. Chỉ sửa file thuộc phase; nếu phát hiện gap ngoài phase, ghi backlog.
5. Chạy build/test/lint phù hợp và dán kết quả tóm tắt.
6. Không tuyên bố hoàn thành khi test fail hoặc chưa chạy.
7. Không chuyển phase tự động; chờ review phase gate.

Output sau mỗi phase phải có: summary, files changed, architecture decisions, commands/results, acceptance checklist, remaining gaps và handoff note.

## Prompt 0 — Khởi động và đọc context

```text
Bạn là Technical Lead Android cho SafeDrive AI. Hãy đọc toàn bộ docs/android-mvp-plan/, đặc biệt 00→07, và audit lại source prototype trong src/. Đây là Android Phone MVP native Kotlin + Jetpack Compose, không dùng WebView và không chuyển TSX tự động.

Trước khi sửa file, hãy trả về: (1) các quyết định kiến trúc bạn đã hiểu, (2) vị trí Android project đề xuất, (3) phạm vi phase hiện tại, (4) câu hỏi/blocker thật sự. Prompt này chỉ dùng để audit và đề xuất thứ tự; chưa viết code cho đến khi nhận Prompt 1. Không thêm Gemini Live, Firebase, Maps, VHAL, camera DMS hoặc SOS dispatch thật.
```

## Prompt 1 — Foundation + domain + mock gateway

```text
Implement Phase 1 theo 02-android-architecture.md và 03-data-api-contract.md.

Tạo Android project một module app bằng Kotlin/Compose/Material 3 với AGP 9.3.0, Gradle 9.5.0, JDK 17, minSdk 26, compileSdk 37, targetSdk 37 và Compose BOM 2026.06.00. Dùng built-in Kotlin AGP 9.x, không áp dụng plugin org.jetbrains.kotlin.android. Nếu targetSdk 37 chưa thể dùng, dừng và báo blocker/ADR fallback; không tự hạ version âm thầm. Tạo domain models, enums/sealed errors, repository interfaces, DataStore settings, navigation shell, composition root và MockSafeDriveGateway/MockVehicleDataSource. Chuyển đủ fixtures/preset từ src/data/mock/mockRepository.ts nhưng không đưa risk policy vào UI.

Mục tiêu DoD: debug build pass; app mở được ở Demo Mode; đổi mode/BASE_URL có persistence; mock gateway compile qua interface; không có Gemini key. Viết unit test model/fixture và báo cáo lệnh kiểm tra đã chạy.
```

## Prompt 2 — Cockpit

```text
Implement Cockpit theo 04-screen-specs.md. Dùng StateFlow immutable UI state, không đọc DTO trực tiếp trong Compose. Tạo design system và adaptive layout cho 360×800, 390×844, 412×915, 844×390.

Render header/connection, status hero, speed, engine/cabin temperature, energy, continuous driving, indirect signal summary, DTC summary và voice status. Không render attention_score/drowsiness_score/raw reason code/simulator. Active source count phải tính từ availability. Thêm loading, stale, offline, high/critical và accessibility semantics. Viết Compose UI tests cho portrait/landscape và font scale lớn.
```

## Prompt 3 — Assistant + diagnostics + settings

```text
Implement Assistant, Diagnostics và Settings theo 04-screen-specs.md.

Assistant phải dùng một AssistantQueryUseCase cho text và voice; có chat list, composer, quick prompts, loading, timeout, retry, offline fallback, action card và confirmation. Chặn duplicate submit và map unknown action thành no-op.

Diagnostics phải render empty/P0301/overheat/stale/error, severity/recommendation từ gateway và nút prefill assistant. Settings dùng DataStore, tách user/developer sections; Demo/Remote, BASE_URL presets, health check thật, raw JSON/latency/route/reason code/simulator chỉ Developer Mode. Không dùng setTimeout để giả ping. Thêm ViewModel/repository/Compose tests.
```

## Prompt 4 — Simulator và scenario regression

```text
Implement Developer Vehicle Simulator. Chỉ expose route khi developerMode=true. Chuyển đủ scenarios: new_trip, over_2h, consider_rest, rest_recommended, insufficient_data, user_reported_fatigue, P0301, overheat, crash có/không phản hồi, cloud offline.

Có preset selection, manual telemetry, DTC selector, crash/response controls, apply/reset và JSON preview không secret. Mọi thay đổi đi qua VehicleDataSource/repository để Cockpit, Diagnostics, Assistant và Emergency cùng nhận state. Viết regression test cho từng preset và đảm bảo Simulator không xuất hiện trong user/release path.
```

## Prompt 5 — Voice + TTS

```text
Implement VoiceController theo 05-voice-emergency.md. MVP chỉ foreground: SpeechRecognizer sau wake phrase “Hey SafeDrive”, nhận transcript, gửi AssistantQueryUseCase, TextToSpeech khi bật, stop/cancel đúng lifecycle.

State phải là DISABLED/IDLE/WAKE_WORD_DETECTED/LISTENING/PROCESSING/SPEAKING/ERROR. Hủy phải dừng recognizer/coroutine/TTS; transcript rỗng không gửi; permission denial không crash; app background/closed không giữ microphone. Không dùng continuous SpeechRecognizer 24/7 và không thêm foreground service ở phase này. Viết test bằng fake controller và UI test cho error/permission/TTS stop.
```

## Prompt 6 — Emergency

```text
Implement duy nhất một Emergency State Machine theo 05-voice-emergency.md. Loại bỏ/không dùng luồng SOS cũ kiểu local countdown. Dùng reducer/use case, FakeClock cho test, deadlineMs tuyệt đối, emergencyId, idempotency key và DataStore snapshot.

Flow: IDLE → CANDIDATE_DETECTED → VERIFYING_EVIDENCE 5s → AWAITING_USER_RESPONSE 15s → FINAL_COUNTDOWN 10s → SOS_SIMULATED_SENT; button/voice “Tôi ổn”, “Hủy SOS”, “Không cần hỗ trợ” → CANCELLED. Full-screen chặn Back, swipe, tap outside và ẩn bottom nav. Rotation/process recreation không reset. Remote mode lấy authoritative snapshot từ backend; Demo mode dùng mock reducer. realEmergencyDispatchEnabled luôn false. Viết timeline, idempotency, recreation và UI tests.
```

## Prompt 7 — Remote REST

```text
Implement RemoteSafeDriveGateway theo 03-data-api-contract.md, giữ nguyên UI và domain interfaces. Tạo Retrofit service, DTO, mapper, OkHttp timeout/log redaction, health/session/state/query/events/action/emergency endpoints. Validate BASE_URL và chỉ cho cleartext debug local; release HTTPS.

Remote mode phải có offline/stale/error mapping, bounded retry, polling fallback; WebSocket chỉ làm phase sau nếu REST đã pass. Mock và Remote phải pass cùng contract tests. Không đưa API key/Gemini vào app, không log transcript/GPS/secret. Viết integration tests bằng fake HTTP server hoặc test dispatcher.
```

## Prompt 8 — QA, hardening và handoff

```text
Chạy Phase QA theo 07-testing-security-acceptance.md. Hoàn thiện unit/ViewModel/repository/DTO/emergency/Compose/instrumentation/accessibility/rotation/process-death/network tests. Kiểm tra mọi scenario, mọi kích thước, font scale lớn, dark theme, permission denial, offline, timeout, invalid JSON và slow backend.

Audit release artifact để chắc chắn không có Gemini key, cleartext ngoài debug, simulator/raw metadata ngoài Developer Mode, attention/drowsiness field hoặc dispatch thật. Tạo README Android, API contract, demo script, test report, known limitations và mapping mock/remote. Chỉ kết luận hoàn thành khi checklist DoD pass; nếu còn lỗi, liệt kê blocker cụ thể thay vì tự mở rộng scope.
```

## Quy tắc phản hồi sau mỗi prompt

Claude phải báo:

1. Đã làm gì và file nào thay đổi.
2. Test/build command đã chạy và kết quả.
3. Requirement nào chưa hoàn thành.
4. Rủi ro hoặc quyết định cần owner xác nhận.
5. Phase gate: `PASS`, `FAIL` hoặc `BLOCKED`, kèm bằng chứng.

Claude không được tự gửi prompt kế tiếp hoặc mở rộng sang phase tiếp theo.
