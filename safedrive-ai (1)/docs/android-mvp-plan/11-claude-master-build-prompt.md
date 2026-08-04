# 11 — Master prompt khởi động Claude Code

## Cách dùng

1. Mở Claude Code tại thư mục gốc của repository SafeDrive AI.
2. Thay `<APPLICATION_ID>` bằng package chính thức, ví dụ tạm thời `vn.edu.haui.hvs.safedrive`.
3. Dán nguyên prompt bên dưới.
4. Cho Claude hoàn thành Bootstrap + Phase 1 rồi kiểm tra báo cáo.
5. Sau khi phase gate `PASS`, dùng prompt tiếp tục ở cuối tài liệu hoặc từng prompt trong `08-claude-prompts.md`.

Không nên yêu cầu Claude build toàn bộ MVP trong một lần không checkpoint. Mỗi phase phải build/test và được review trước khi chuyển tiếp.

## Prompt khởi động — sao chép nguyên khối

```text
Bạn là Senior Android Engineer và Technical Lead chịu trách nhiệm xây dựng Android Phone MVP SafeDrive AI trong repository hiện tại.

MỤC TIÊU

Đọc toàn bộ source prototype và bộ kế hoạch đã chuẩn bị, sau đó xây dựng ứng dụng Android native bằng Kotlin + Jetpack Compose theo đúng kiến trúc và thứ tự phase. Prototype React chỉ là tài liệu tham khảo về UI, nội dung, mock data và interaction flow.

VỊ TRÍ LÀM VIỆC

- Repository root: thư mục hiện tại.
- Prototype web cần audit nhưng không được sửa: `src/`, `package.json`, `README.md` hiện tại.
- Toàn bộ kế hoạch bắt buộc phải đọc: `docs/android-mvp-plan/README.md` và các file `00` đến `11` trong cùng thư mục.
- Tạo Android project mới tại: `android/`.
- Namespace/applicationId: `<APPLICATION_ID>`. Nếu placeholder chưa được thay, phải hỏi tôi trước khi tạo project.
- Không ghi đè hoặc chuyển đổi tự động source React/TSX.

QUYẾT ĐỊNH KHÓA

- Native Kotlin, Jetpack Compose, Material 3; không WebView.
- Một module Gradle `app` trong MVP.
- MVVM + immutable UiState + StateFlow + Coroutines.
- UI → ViewModel → Use Case → Repository → Mock/Remote Gateway.
- Demo Mode dùng `MockSafeDriveGateway` và `MockVehicleDataSource`, phải chạy không cần backend.
- Remote Mode dùng cùng domain/repository interface; không đổi UI.
- AGP 9.3.0, Gradle 9.5.0, JDK 17.
- minSdk 26, compileSdk 37, targetSdk 37, Compose BOM 2026.06.00.
- Dùng built-in Kotlin của AGP 9.x; không áp dụng lại plugin `org.jetbrains.kotlin.android`.
- Không thêm Gemini Live, Firebase, Maps, VHAL thật, camera DMS, RAG, multi-agent hoặc cloud deployment trong MVP.
- Không lưu Gemini/API key trong Android.
- Không tạo `attention_score`, `drowsiness_score` hoặc kết luận tài xế tỉnh táo/buồn ngủ/mất tập trung.
- `realEmergencyDispatchEnabled` luôn là `false`.
- Không gọi điện, gửi SMS hoặc dispatch SOS thật.

QUY TRÌNH BẮT BUỘC

PHẦN A — AUDIT TRƯỚC KHI CODE

1. Đọc đầy đủ tất cả file trong `docs/android-mvp-plan/`.
2. Audit các file hiện có trong `src/`, đặc biệt:
   - `App.tsx`
   - `context/SafeDriveContext.tsx`
   - `types/safedrive.ts`
   - `data/mock/mockRepository.ts`
   - toàn bộ `presentation/`, `components/` và `navigation/`.
3. Xác nhận:
   - phần nào chỉ giữ làm UI/data/flow reference;
   - phần nào phải viết lại;
   - phần nào phải loại bỏ;
   - phần nào thuộc backend hoặc phase sau.
4. Kiểm tra môi trường cục bộ: JDK, Android SDK, Gradle/Android Studio tooling có sẵn.
5. Báo blocker thật sự. Không coi việc backend chưa tồn tại là blocker cho Demo Mode.

Trước khi sửa file, trả về một báo cáo ngắn gồm:

- Kiến trúc đã hiểu.
- Danh sách tài liệu/source đã đọc.
- Vị trí Android project sẽ tạo.
- Toolchain phát hiện được.
- Assumption và blocker.
- Kế hoạch file cho Phase 1.

Nếu không có blocker nghiêm trọng, tiếp tục thực hiện Phần B trong cùng tác vụ.

PHẦN B — IMPLEMENT BOOTSTRAP + PHASE 1

Thực hiện Prompt 1 trong `docs/android-mvp-plan/08-claude-prompts.md`.

Tối thiểu phải tạo:

- Android project tại `android/`.
- Gradle wrapper/version catalog/build configuration tương thích.
- `MainActivity`, `SafeDriveApp`, Compose theme và navigation shell.
- Domain models/enums/sealed errors theo `03-data-api-contract.md`.
- Repository interfaces:
  - `SafeDriveGateway`
  - `VehicleDataSource`
  - `VoiceController`
  - `PreferencesRepository`
  - `EmergencyRepository`
- DataStore settings.
- Composition root chọn Demo/Remote mode mà không có `if (demoMode)` trong composable.
- `MockSafeDriveGateway`.
- `MockVehicleDataSource`.
- Fixtures/presets chuyển từ prototype.
- Một vertical slice tối thiểu chạy được bằng Demo Mode.
- Unit tests cho model, fixture và mock contract.
- Android README hướng dẫn build Phase 1.

RÀNG BUỘC IMPLEMENTATION

- Không sửa prototype web.
- Không tạo một God ViewModel tương đương `SafeDriveContext`.
- Không đặt HTTP, DTO parsing hoặc safety policy trong ViewModel/composable.
- Không dùng `Map<String, Any>` cho domain/API payload.
- Không dùng `Thread.sleep` trong test.
- Không hard-code secret hoặc production endpoint.
- Không tự hạ AGP/SDK/targetSdk. Nếu toolchain không đáp ứng, dừng và báo phương án/ADR trước khi đổi.
- Giữ thay đổi trong phạm vi Phase 1; phát hiện ngoài phạm vi thì ghi backlog.

KIỂM TRA BẮT BUỘC

Từ thư mục `android/`, chạy tối thiểu:

- `gradlew.bat :app:assembleDebug`
- `gradlew.bat :app:testDebugUnitTest`
- `gradlew.bat :app:lintDebug`

Nếu một lệnh fail:

1. Phân tích nguyên nhân.
2. Sửa lỗi trong phạm vi phase.
3. Chạy lại.
4. Không báo `PASS` nếu lệnh vẫn fail hoặc chưa chạy.

BÁO CÁO CUỐI PHASE

Trả về:

1. Tóm tắt kết quả.
2. Cây thư mục Android đã tạo.
3. Danh sách file thay đổi.
4. Quyết định kiến trúc/assumption.
5. Các lệnh build/test/lint và kết quả.
6. Checklist acceptance của Phase 1.
7. Gap hoặc rủi ro còn lại.
8. Phase gate: `PASS`, `FAIL` hoặc `BLOCKED`.

Dừng sau Phase 1. Không tự thực hiện Prompt 2 cho tới khi tôi review và yêu cầu tiếp tục.
```

## Prompt tiếp tục từng phase

Sau khi phase trước đã `PASS`, dùng mẫu sau:

```text
Tiếp tục dự án SafeDrive AI Android trong repository hiện tại.

1. Đọc lại `docs/android-mvp-plan/README.md`, `08-claude-prompts.md` và các tài liệu được prompt phase dẫn tới.
2. Kiểm tra source Android hiện có trong `android/` và báo phase gate trước vẫn còn hợp lệ.
3. Thực hiện chính xác Prompt <N> — <TÊN PHASE> trong `docs/android-mvp-plan/08-claude-prompts.md`.
4. Không sửa contract/kiến trúc/scope nếu chưa nêu rõ và được chấp thuận.
5. Chạy build, unit test, lint và test đặc thù của phase.
6. Sửa lỗi trong phạm vi phase rồi chạy lại.
7. Báo files changed, commands/results, acceptance checklist, gap và `PASS/FAIL/BLOCKED`.
8. Dừng sau phase này; không tự chuyển prompt kế tiếp.
```

Thay `<N>` bằng:

| N | Phase |
|---:|---|
| 2 | Cockpit |
| 3 | Assistant + Diagnostics + Settings |
| 4 | Vehicle Simulator |
| 5 | Voice + TTS |
| 6 | Emergency |
| 7 | Remote REST |
| 8 | QA, hardening và handoff |

## Prompt tiếp tục khi Claude bị ngắt giữa phase

```text
Tiếp tục phase hiện tại của SafeDrive AI Android, không bắt đầu lại.

Hãy:

1. Đọc `docs/android-mvp-plan/` và trạng thái source trong `android/`.
2. Tóm tắt phần đã hoàn thành dựa trên file/test thực tế, không dựa vào trí nhớ hội thoại.
3. Xác định acceptance item chưa hoàn thành của phase hiện tại.
4. Tiếp tục implementation và verification từ checkpoint gần nhất.
5. Không mở rộng scope hoặc chuyển phase.
6. Kết thúc bằng files changed, build/test result và `PASS/FAIL/BLOCKED`.
```
