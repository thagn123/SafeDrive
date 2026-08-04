# SafeDrive AI Android MVP — bộ kế hoạch cho Claude

Bộ tài liệu này là kế hoạch triển khai Android Phone MVP native bằng Kotlin và Jetpack Compose, được lập từ hai prompt yêu cầu và source prototype hiện có trong thư mục `src/`.

## Cách sử dụng

Đọc theo thứ tự:

1. [00-executive-plan.md](00-executive-plan.md) — mục tiêu, phạm vi và các quyết định phải giữ nguyên.
2. [01-source-audit.md](01-source-audit.md) — prototype hiện có, phần giữ lại làm đặc tả và phần phải viết lại.
3. [02-android-architecture.md](02-android-architecture.md) — kiến trúc, package, dependency rule và mapping sang Android.
4. [03-data-api-contract.md](03-data-api-contract.md) — domain model, DTO và API contract.
5. [04-screen-specs.md](04-screen-specs.md) — đặc tả từng màn hình và trạng thái UI.
6. [05-voice-emergency.md](05-voice-emergency.md) — voice, TTS và Emergency State Machine.
7. [06-roadmap-20-days.md](06-roadmap-20-days.md) — thứ tự build, phân công và Definition of Done theo ngày.
8. [07-testing-security-acceptance.md](07-testing-security-acceptance.md) — test, bảo mật, nghiệm thu và deliverables.
9. [08-claude-prompts.md](08-claude-prompts.md) — các prompt giao tuần tự cho AI coding agent.
10. [09-checklists-and-decisions.md](09-checklists-and-decisions.md) — checklist bắt đầu, kết thúc phase và các quyết định cần khóa.
11. [10-plan-review-and-traceability.md](10-plan-review-and-traceability.md) — điểm chất lượng, traceability matrix và các gap còn mở.
12. [11-claude-master-build-prompt.md](11-claude-master-build-prompt.md) — prompt khởi động sẵn để dán vào Claude Code.
13. [12-mobile-completion-before-ai-backend.md](12-mobile-completion-before-ai-backend.md) — kế hoạch stabilization chi tiết để hoàn thiện text/voice/TTS, latency, Simulator, Remote Mode, device QA và khóa contract trước khi xây AI Backend.
14. [13-claude-mobile-stabilization-prompt.md](13-claude-mobile-stabilization-prompt.md) — master prompt để Claude triển khai liên tục W0–W8, kiểm chứng Gate A–E và dừng trước AI Backend.

## Source of truth

- UI, nội dung tiếng Việt, preset và luồng tương tác: prototype trong `src/`.
- Kiến trúc, ranh giới production và API contract: các file trong thư mục này.
- Android không được bê nguyên React Context, Tailwind, browser Speech API hoặc các timeout mock vào production.
- Với stabilization trước AI Backend, file `12` supersede quyết định cũ về Remote fallback, total
  assistant-turn timeout và lịch gate. Khi được freeze tại Gate E, `openapi/safedrive-v1.yaml` là
  contract máy đọc được ưu tiên hơn prose.

## Cách giao cho Claude

- Luôn bắt đầu bằng Prompt 0 trong `08-claude-prompts.md`.
- Chỉ giao một prompt phase tại một thời điểm.
- Không chuyển phase nếu phase gate chưa pass hoặc Claude chưa báo rõ test/build result.
- Khi source và tài liệu mâu thuẫn, các quyết định khóa trong `00`, `03`, `05` và `09` được ưu tiên,
  ngoại trừ ba quyết định stabilization đã được file `12` supersede rõ ràng.
- Mọi thay đổi contract phải cập nhật `03-data-api-contract.md` và traceability trong `10-plan-review-and-traceability.md` trước khi sửa UI.
- Sau baseline Android MVP, phải hoàn tất các Gate A–E trong `12-mobile-completion-before-ai-backend.md` trước khi bắt đầu AI Backend.

## Nguyên tắc khóa

- Android được viết lại native Kotlin + Compose; không đóng gói React bằng WebView.
- MVP chạy đầy đủ bằng `MockSafeDriveGateway`, không cần backend.
- `RemoteSafeDriveGateway` dùng cùng interface để thay mock mà không đổi UI.
- Risk, rest recommendation, DTC severity và emergency deadline là kết quả từ gateway/backend; Android chỉ điều phối và render.
- `realEmergencyDispatchEnabled` luôn là `false`; không gọi điện, SMS hay cứu hộ thật.
- Không có `attention_score`, `drowsiness_score`, camera DMS hoặc kết luận trạng thái tài xế.
- Không đưa Gemini API key vào APK.

Phiên bản tài liệu: **Revision 3 — 2026-07-27**.
