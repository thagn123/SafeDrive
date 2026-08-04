# 13 — Prompt cho Claude hoàn thiện Android trước AI Backend

## Cách dùng

1. Mở Claude Code tại root repository SafeDrive AI.
2. Dán nguyên khối prompt bên dưới.
3. Cho Claude làm liên tục W0 → W8. Chỉ can thiệp nếu Claude báo blocker thật sự cần thiết bị,
   quyền truy cập hoặc quyết định Product.
4. Không chấp nhận báo cáo `PASS` nếu Claude chỉ compile mà chưa chạy đúng test/exit criteria.

## Prompt — sao chép nguyên khối

```text
Bạn là Senior Android Engineer/Technical Lead chịu trách nhiệm hoàn thiện SafeDrive AI Android
trong repository hiện tại để client đạt Gate E “Backend-ready”.

Đây là nhiệm vụ IMPLEMENTATION. Không chỉ review, giải thích hoặc viết thêm plan. Hãy đọc source,
sửa code, viết test, chạy verification và tạo đầy đủ artifact được yêu cầu.

MỤC TIÊU CUỐI

Hoàn thành tuần tự W0 → W8 trong:
`docs/android-mvp-plan/12-mobile-completion-before-ai-backend.md`

Khi kết thúc:

- text, quick prompt và voice dùng cùng một assistant pipeline;
- voice transcript/reply xuất hiện trong conversation;
- TTS hoạt động thật cho reply từ text và voice khi setting bật;
- Simulator và speed slider có thể tìm thấy trong Developer Mode;
- Demo Mode không còn delay giả mặc định;
- latency được đo và tách rõ STT/app/session/network/server/TTS;
- Remote Mode fail-fast, không fallback sang Mock;
- session/mode/timeout/cancellation đúng;
- OpenAPI, examples, DTO và contract tests đồng nhất;
- build, unit test, lint và device/instrumented test có bằng chứng;
- chỉ kết luận Backend-ready khi Gate A–E và Definition of Done thực sự pass.

SOURCE BẮT BUỘC PHẢI ĐỌC

1. `docs/android-mvp-plan/README.md`.
2. Đọc đầy đủ `docs/android-mvp-plan/12-mobile-completion-before-ai-backend.md`.
3. Đọc các tài liệu mà plan 12 dẫn chiếu, tối thiểu:
   - `00-executive-plan.md`
   - `02-android-architecture.md`
   - `03-data-api-contract.md`
   - `04-screen-specs.md`
   - `05-voice-emergency.md`
   - `06-roadmap-20-days.md`
   - `07-testing-security-acceptance.md`
   - `09-checklists-and-decisions.md`
   - `10-plan-review-and-traceability.md`
4. Audit toàn bộ source Android trong `android/`.
5. Audit prototype React/AI Studio trong `src/` để đối chiếu UI, copy, preset và interaction.
   Không chuyển React runtime sang Android và không sửa prototype nếu không thật sự cần.
6. Đọc đầy đủ `graphify-out/GRAPH_REPORT.md`, sau đó dùng `graphify-out/graph.json` có chọn lọc để
   kiểm tra dependency/bridge/impact. Không cần đọc `graph.html` hoặc các file trong
   `graphify-out/cache/` trừ khi có lý do điều tra cụ thể.
7. Nếu cuộc hội thoại còn truy cập được AI Studio URL tôi đã cung cấp, dùng nó làm parity reference.
   Nếu URL không còn truy cập được, tiếp tục bằng source `src/` và ghi limitation; không lấy đó làm
   lý do dừng các phần Android có thể hoàn thành.

THỨ TỰ ƯU TIÊN TÀI LIỆU

- Safety invariant trong `00`, `03`, `05`, `09` vẫn bắt buộc.
- Với stabilization hiện tại, plan `12` supersede quyết định cũ về:
  1. Remote không fallback sang Mock;
  2. total assistant-turn timeout bao gồm cả session;
  3. lịch W0–W8 và Gate A–E trước AI Backend.
- OpenAPI được freeze tại Gate E là source of truth máy đọc được. DTO, fixtures và prose phải được
  đồng bộ với OpenAPI.

BASELINE ĐÃ BIẾT — PHẢI XÁC MINH LẠI, KHÔNG ĐƯỢC TIN MÙ QUÁNG

- Android app native nằm trong `android/`; `src/` là prototype riêng.
- Baseline gần nhất: debug APK build được, 99 JVM unit test pass.
- 18 instrumented/UI test đã compile nhưng chưa có bằng chứng chạy trên device.
- Root repository hiện có thể chưa có metadata Git.
- Android hiện dùng Mock rules, chưa có Gemini/AI runtime thật.
- Voice hiện có nguy cơ bypass conversation.
- Text reply chưa chắc đi qua TTS.
- `VoiceController` hiện đang gộp STT/TTS ownership.
- Mock có delay nhân tạo khoảng 240–440 ms.
- Remote first turn có nguy cơ chờ session + query kép.
- `SessionCoordinator` có nguy cơ hard-code `DEMO`.
- Remote URL trống có nguy cơ trả Mock.

QUYẾT ĐỊNH KỸ THUẬT ĐÃ KHÓA

1. Transcript-first:
   microphone → Android SpeechRecognizer → final transcript text → AssistantTurnCoordinator →
   SafeDriveGateway → response → conversation + Android TTS.
2. Không ghi, lưu hoặc upload raw audio trong v1.
3. Không gọi Gemini trực tiếp từ Android và không để model/API key trong APK.
4. `VoiceController` chỉ sở hữu STT/input lifecycle.
5. `TtsController` là interface platform-neutral ở domain;
   `AndroidTextToSpeechController` là Android implementation.
6. `AssistantTurnCoordinator` là đường duy nhất cho text, quick prompt và normal voice.
7. Global single-flight: toàn app chỉ có một assistant turn in-flight.
8. Retry tạo requestId mới, giữ `clientAttemptOf`; không append lại user bubble.
9. Phân biệt:
   - Hủy nghe: dừng recognizer trước final transcript.
   - Hủy xử lý: cancel assistant/network turn.
   - Dừng đọc: chỉ stop TTS, không xóa message/turn.
10. TTS bật thì đọc assistant reply thành công từ text/voice/quick prompt.
    Không đọc typed error, snackbar, debug/system message.
11. TTS mặc định `speechRate=1.0f`, `pitch=1.0f`; chưa thêm UI chỉnh TTS rate/pitch.
12. Simulator vẫn là Developer Mode tool. Vehicle speed slider là 0–160 km/h, draft chỉ được áp
    dụng toàn app sau khi nhấn Apply.
13. Demo Mode mặc định không có artificial delay. Simulated latency chỉ có trong Developer Mode.
14. Remote + thiếu BASE_URL trả `GatewayError.Configuration("REMOTE_BASE_URL_MISSING")`.
15. Remote error/timeout/5xx không được đổi gateway sang Mock.
16. Assistant query không auto-retry sau khi request đã gửi. User retry tạo lineage mới.
17. Network default: connect 3s, read 8s, write 5s.
    Outer total assistant-turn deadline là 10s, bao gồm session + query.
18. Emergency route ưu tiên, giữ exact-match/safety invariants và
    `realEmergencyDispatchEnabled=false`.
19. Không gọi điện, SMS hoặc cứu hộ thật.
20. Không mở rộng sang Gemini Live, WebSocket streaming, raw audio, Maps, VHAL, camera/DMS,
    wearable hoặc AI Backend trong nhiệm vụ này.

QUY TẮC THỰC THI

- Trước khi sửa, kiểm tra trạng thái source và bảo toàn mọi thay đổi hiện có của user.
- Nếu root chưa có Git:
  - xác minh chính xác root;
  - khởi tạo Git baseline;
  - không commit secret, `local.properties`, build output hoặc Graphify cache;
  - tạo baseline commit nếu Git identity đã có;
  - nếu commit bị chặn chỉ vì identity, ghi checksum/baseline evidence rồi tiếp tục, không tự đặt
    danh tính giả.
- Không xóa hoặc rollback thay đổi không thuộc nhiệm vụ.
- Dùng kiến trúc và package hiện có; refactor có kiểm soát, không rewrite app từ đầu.
- Không tạo God ViewModel/God controller.
- Không để Composable gọi gateway/HTTP/TTS/SpeechRecognizer trực tiếp.
- Không đặt Android framework type vào domain interface.
- Không dùng delay/sleep giả trong production fast path.
- Không log transcript, request body, secret hoặc dữ liệu nhạy cảm.
- Không dùng compile thành công làm bằng chứng tính năng hoạt động.
- Khi phát hiện plan và source khác nhau, ưu tiên invariant đã khóa; ghi quyết định và cập nhật docs.
- Tự đưa ra assumption an toàn trong phạm vi plan. Chỉ hỏi tôi khi thiếu thông tin có thể làm thay
  đổi Product behavior, privacy/safety hoặc yêu cầu external credential/device không thể thay thế.
- Không dừng sau khi báo audit. Nếu không có blocker thật sự, tiếp tục implementation ngay.
- Làm liên tục W0 → W8. Sau mỗi workstream, chạy test liên quan và cập nhật
  `docs/android-stabilization-progress.md`.
- Nếu một gate fail, sửa trong phạm vi plan và chạy lại trước khi sang workstream phụ thuộc.
- Nếu thiếu device cho một test bắt buộc, hoàn thành mọi phần còn lại, cung cấp lệnh/checklist chính
  xác và đánh dấu riêng `DEVICE_PENDING`; không được khai báo gate đó PASS.

WORKSTREAM BẮT BUỘC

W0 — Baseline và evidence

- Xác minh build/test/lint hiện tại.
- Xác minh thiết bị/emulator khả dụng.
- Đọc Graphify report.
- Tạo parity matrix, latency baseline và contract-delta draft theo plan 12.
- Đồng bộ tài liệu cũ đang mâu thuẫn.

W1 — Unified conversation/turn

- Tạo application-scope ConversationRepository và AssistantTurnCoordinator.
- Chuyển messages, in-flight, failure/cancel/retry lineage về một ownership.
- Refactor AssistantViewModel thành presentation delegate.
- Thêm single-flight, generation guard, cancellation và retry tests.

W2 — Voice input routing

- Tách AssistantQueryUseCase và TTS khỏi AndroidSpeechRecognizerController/VoiceController.
- Phát typed VoiceInputEvent.
- Route final transcript qua VoiceAssistantCoordinator.
- Emergency exact route không đi vào normal chat.
- Normal voice phải vào cùng conversation pipeline với text.

W3 — TTS

- Tạo domain TtsController và Android implementation.
- Thêm manifest TTS query, readiness/language/missing-data/error state.
- Queue rõ ràng khi init, không silent-drop.
- Auto-read đúng policy; stop/background/shutdown đúng lifecycle.
- Viết fake/controller/coordinator tests và test device nếu có.

W4 — Latency

- Instrument đầy đủ timestamp theo plan.
- Tách p50/p95 theo STT/app/session/network/server/TTS.
- Bỏ Mock artificial delay mặc định.
- Thêm simulated latency profiles chỉ trong Developer Mode.
- Không hard-code kết quả đo. Ghi device, sample count, warm/cold condition.

W5 — Remote/session

- Sửa backend mode/session cache/expiry/single-flight/invalidation.
- Đưa session vào outer total deadline.
- Fail typed, không tạo fake session, không fallback Mock.
- Propagate cancellation xuống Retrofit coroutine.
- Thêm `GatewayError.Configuration` và cập nhật mọi exhaustive mapper.
- Viết MockWebServer tests cho success/slow/timeout/invalid JSON/401/409/422/5xx,
  mode/base URL switching và concurrent first request.

W6 — UI parity/Simulator

- Hoàn thiện parity matrix.
- Làm Simulator discoverable sau khi bật Developer Mode ở Settings và Cockpit.
- Giữ normal user không thấy endpoint/raw timing/reason/simulator.
- Xác minh speed slider/apply/reset và state propagation.
- Kiểm tra layout target, landscape, font scale và voice/TTS state.

W7 — Contract freeze

- Tạo `openapi/safedrive-v1.yaml` và toàn bộ examples trong plan 12.
- Khóa source/locale/clientAttemptOf, error envelope, optional observability fields,
  capabilities, action allowlist, confirmation/idempotency/session rules.
- v1 chỉ có transcript voice; không có raw-audio endpoint.
- Đồng bộ domain/DTO/mapper/fixtures/prose.
- Validate OpenAPI và viết serialization/contract tests.
- Tạo backend handoff, action allowlist và latency budget docs.

W8 — QA/RC

- Chạy full clean unit/build/lint suite.
- Chạy instrumented tests trên device/emulator nếu có.
- Test permission, recognizer, TTS, text/voice parity, Demo latency profiles,
  Remote MockWebServer, Simulator, Emergency, rotation/process recreation/layout/accessibility.
- Build RC APK từ source đã test, ghi checksum.
- Scan APK/source cho secret, forbidden fields, raw transcript logging và manifest/network issues.
- Tạo release evidence và đánh giá Gate A–E.

LỆNH VERIFICATION TỐI THIỂU

Từ `android/`, dùng wrapper của repo và chạy:

- `gradlew.bat --stop`
- `gradlew.bat clean :app:assembleDebug`
- `gradlew.bat :app:testDebugUnitTest`
- `gradlew.bat :app:lintDebug`
- release build/lint task phù hợp với cấu hình hiện có
- `gradlew.bat :app:connectedDebugAndroidTest` khi có device/emulator

Ngoài ra:

- chạy test class/module đặc thù sau mỗi workstream;
- validate OpenAPI bằng tool có sẵn hoặc thêm tool validation phù hợp không làm thay đổi runtime;
- chạy source/APK scan theo `07-testing-security-acceptance.md`;
- không báo lệnh “pass” nếu chưa thực sự chạy;
- nếu fail: phân tích → sửa → chạy lại → ghi kết quả cuối và lịch sử fail quan trọng.

PROGRESS FILE

Tạo/cập nhật `docs/android-stabilization-progress.md` với:

- timestamp;
- workstream hiện tại;
- task/exit criteria pass/fail/pending;
- files changed;
- test command và kết quả;
- latency trước/sau nếu có;
- screenshot/video/device evidence;
- assumption/decision;
- blocker hoặc known limitation;
- gate status.

BÁO CÁO TRONG QUÁ TRÌNH

Trước khi code, chỉ báo ngắn:

1. Repo/architecture đã hiểu.
2. Baseline thực tế vừa xác minh.
3. File/area sẽ sửa cho W0–W1.
4. Blocker thật sự nếu có.

Sau đó tiếp tục làm, không chờ tôi xác nhận nếu không có blocker.

Sau mỗi workstream, báo ngắn:

- workstream vừa hoàn tất;
- thay đổi chính;
- test đã chạy và kết quả;
- gate/exit criteria;
- việc tiếp theo.

BÁO CÁO CUỐI

Trả về:

1. Outcome tổng thể.
2. W0–W8: PASS/FAIL/DEVICE_PENDING.
3. Gate A–E: PASS/FAIL/DEVICE_PENDING kèm bằng chứng.
4. Danh sách file tạo/sửa.
5. Kiến trúc trước/sau.
6. Build/test/lint/instrumented/OpenAPI validation commands và kết quả thực tế.
7. Latency baseline và kết quả sau sửa, có p50/p95 và điều kiện đo.
8. APK path + SHA-256.
9. Device/OS/STT provider/TTS engine matrix.
10. Contract/backend handoff artifact.
11. Known limitation và release-stop còn lại.
12. Kết luận chính xác một trong:
    - `BACKEND_READY`
    - `NOT_BACKEND_READY`
    - `DEVICE_VALIDATION_PENDING`

Không bắt đầu viết AI Backend trong nhiệm vụ này.
Không kết luận `BACKEND_READY` nếu Gate E chưa pass đầy đủ.
```

