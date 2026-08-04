# 06 — Roadmap 20 ngày và phân công

> **Trạng thái:** roadmap lịch sử cho lần build MVP ban đầu. Với repo Android hiện tại, không chạy
> lại lịch ngày 1–20 này; dùng W0–W8 và Gate A–E trong
> `12-mobile-completion-before-ai-backend.md`. Các safety invariant và deliverable chưa hoàn thành
> trong tài liệu này vẫn có hiệu lực.

## Giả định

- Hai Android developer, một backend developer hỗ trợ contract/fixtures.
- 20 ngày làm việc, Android Phone trước, SOS mô phỏng, vehicle simulator.
- Backend có thể chưa sẵn sàng; Mock Gateway là đường chạy chính từ ngày đầu.

## Kế hoạch theo ngày

| Ngày | Owner | Công việc | Dependency | Deliverable / DoD | Rủi ro cần theo dõi |
|---:|---|---|---|---|---|
| 1 | A+B | Khởi tạo project, SDK, Compose theme, package, lint, CI/build debug | Quyết định SDK | App build được, màn hình shell chạy | Version mismatch |
| 2 | A | Domain model, DataStore, navigation shell, DI/composition root | Ngày 1 | Model compile, mode/settings persist | Model đổi muộn |
| 2 | B | Design tokens, reusable cards/badges/buttons, accessibility baseline | Ngày 1 | Theme preview và component preview | Bê CSS 1:1 |
| 3 | A | `SafeDriveGateway`, `VehicleDataSource`, `MockSafeDriveGateway`, fixtures | Domain model | Demo state và 8 preset có contract | Mock lệch remote |
| 4 | A | Retrofit DTO, mapper, error model, health, BASE_URL runtime | Interface ngày 3 | Remote health có timeout/error mapping | Backend chưa có |
| 5 | A+B | Vertical slice assistant text: input → use case → gateway → chat | Mock gateway | Demo mode gửi/nhận không cần backend | Duplicate request |
| 6 | B | Cockpit header/status hero/connection chip | Design system | Normal/high/offline render đúng | Layout cao quá |
| 7 | B | Vehicle metrics, signal summary, DTC summary, voice status | Vehicle model | Cockpit portrait không scroll ở 390×844 | Hard-code source count |
| 8 | B | Adaptive portrait/landscape, font scale, semantics | Cockpit cơ bản | 844×390 không overlap | Landscape clipping |
| 9 | B | Simulator preset cards, manual telemetry, apply/reset | Mock vehicle source | Scenario cập nhật toàn app | State copy không đầy đủ |
| 10 | A+B | JSON preview, Developer Mode routing, scenario regression | Simulator | Simulator/raw JSON ẩn ngoài dev | Leak vào release |
| 11 | B | Assistant chat list/composer/chips/loading/error/retry | Vertical slice | UX đầy đủ, keyboard không che | Request lifecycle |
| 12 | A | Action card, confirmation, TTS controller | Assistant | Action allowlist/confirmation pass | TTS đọc lặp |
| 13 | A | SpeechRecognizer, permission, voice state controller | TTS/assistant | Voice text path hoạt động foreground | Device language/permission |
| 14 | B | Emergency renderer, reducer, fake clock, mock deadlines | Domain emergency | 5/15/10 timeline trong Demo | Timer reset |
| 15 | A+B | Persist emergency snapshot, rotation/process recreation, voice cancel | Emergency reducer | Không reset, cancel idempotent | Process death edge case |
| 16 | B | Diagnostics, DTC detail, ask assistant, stale/empty | Model/gateway | P0301/overheat/empty pass | Severity tự tính |
| 17 | A | Remote session/state/query/action/emergency, polling fallback | Backend contract | Remote mode chạy với staging/mock server | Contract drift |
| 18 | A+B | Unit, ViewModel, repository, DTO, emergency timeline tests | Tất cả feature | Test suite quan trọng pass | Test không deterministic |
| 19 | A+B | Compose UI, accessibility, rotation, offline, slow backend, manual device | Build candidate | Regression matrix pass | Chỉ test happy path |
| 20 | A+B + backend | Bug fix, APK, README, API contract, demo script, test report | QA ngày 19 | Debug APK cài được, demo end-to-end | Scope creep/release config |

## Phân công

### Android Developer A

Foundation, navigation, domain/network, Retrofit/OkHttp, repositories, DataStore, settings, voice, remote integration, test infrastructure.

### Android Developer B

Design system, Cockpit, Assistant UI, Diagnostics, Simulator, Emergency UI, Compose UI tests và accessibility.

### Backend developer

OpenAPI/schema, mock fixtures, health/session/state/query/action/emergency endpoints, idempotency, staging URL và contract change log.

### Code review chéo bắt buộc

- A review domain/data của B và B review UI của A.
- Mọi thay đổi DTO phải có backend + Android cùng review.
- Emergency, permission, security và cleartext config cần ít nhất hai reviewer.
- PR không merge nếu thiếu test hoặc làm lộ Developer Mode trong user path.

## Nếu chỉ có một Android developer

Kéo dài thành 6–8 tuần:

1. Tuần 1: foundation, model, mock gateway, design system.
2. Tuần 2: Cockpit + Assistant text.
3. Tuần 3: Simulator + Diagnostics + Settings.
4. Tuần 4: Voice/TTS + emergency reducer.
5. Tuần 5: persistence/lifecycle + Remote REST.
6. Tuần 6: test matrix + APK.
7. Tuần 7–8: buffer cho device/accessibility/backend integration.

Giữ nguyên thứ tự dependency; không song song hóa bằng cách đặt backend/Gemini trước vertical slice mock.

## Phase gate

- Gate 1 (ngày 5): project build + assistant vertical slice.
- Gate 2 (ngày 10): cockpit/simulator responsive, demo fixtures.
- Gate 3 (ngày 15): voice + emergency timeline/persistence.
- Gate 4 (ngày 20): remote, test, APK và handoff.

## Deliverable và exit criteria theo phase

| Phase | Ngày | Artifact bắt buộc | Exit criteria |
|---|---:|---|---|
| P0 Foundation | 1–5 | Android project, version catalog, domain model, mock gateway, vertical slice | build/lint/unit pass; Demo không backend |
| P1 Cockpit/Simulator | 6–10 | design system, adaptive cockpit, simulator fixtures | portrait/landscape/font scale + scenario regression pass |
| P2 Assistant/Voice/Emergency | 11–15 | chat/action, voice controller, emergency reducer/store | duplicate/permission/TTS/timeline/recreation tests pass |
| P3 Diagnostics/Remote/QA | 16–20 | diagnostics, remote gateway, APK, docs/report | contract/security/device matrix và acceptance pass |

Mỗi phase tạo một checkpoint commit/tag nội bộ. Nếu exit criteria fail, sửa trong phase hiện tại; không giao prompt phase tiếp theo cho AI agent.

## Risk register

| ID | Rủi ro | Xác suất | Tác động | Giảm thiểu | Owner |
|---|---|---|---|---|---|
| R1 | React UI không map 1:1 sang Compose | Cao | Trung bình | Dùng hierarchy/copy làm spec; preview + device test | B |
| R2 | Cockpit landscape/font lớn overlap | Cao | Cao | Adaptive slots, test 844×390 và font 1.3 từ ngày 8 | B |
| R3 | SpeechRecognizer không ổn định theo OEM/ngôn ngữ | Cao | Trung bình | Foreground-only, manual mic fallback, typed input luôn có | A |
| R4 | Backend chưa sẵn sàng | Cao | Trung bình | Mock-first, OpenAPI/fixtures, Remote không chặn Demo | A + backend |
| R5 | API contract drift | Trung bình | Cao | Contract version, change control, mapper/serialization test | A + backend |
| R6 | Emergency countdown reset/duplicate sent | Trung bình | Rất cao | absolute deadline, persisted snapshot, FakeClock, idempotency | A+B |
| R7 | Process death/lifecycle race | Trung bình | Cao | Saved state/snapshot, generation id, recreate tests | A |
| R8 | Mất mạng/dữ liệu stale bị hiểu là normal | Cao | Cao | typed status, freshness threshold, giữ snapshot + stale badge | A+B |
| R9 | Lộ secret/transcript/location | Thấp | Rất cao | không client key, log redaction, release audit | A |
| R10 | Cleartext lọt release | Trung bình | Cao | debug-only network security config + release test | A |
| R11 | Scope creep sang Gemini/Maps/Firebase/VHAL | Cao | Cao | phase gate + requirement/owner/test rule | Lead/Product |
| R12 | Simulator/raw metadata lộ cho user | Trung bình | Trung bình | dev route guard, release policy, UI test | B |
| R13 | API 37 behavior change gây regression | Trung bình | Trung bình | targetSdk gate + API 37 device suite; temporary ADR fallback | A |
| R14 | Hai developer merge xung đột model/UI | Trung bình | Trung bình | freeze interfaces ngày 3, PR nhỏ, cross-review | A+B |

## Cadence làm việc

- Daily 15 phút: blocker, contract change, phase gate.
- Ngày 3/5/10/15/20: review artifact và demo.
- Backend contract sync tối thiểu ngày 2, 4, 12 và 17.
- Không để AI agent sửa cùng lúc file contract và nhiều feature mà không có checkpoint/review.
