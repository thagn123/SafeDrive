# 10 — Chấm điểm và traceability

> **Trạng thái:** Revision 2 bên dưới ghi lại đánh giá trước khi Android MVP được build. Revision 3
> dùng `12-mobile-completion-before-ai-backend.md` cho gap, owner, deadline và gate còn lại; không
> dùng lại deadline “ngày 3/12/17” như lịch hiện tại.

## Kết quả chấm điểm

Thang điểm 10, đánh giá khả năng dùng bộ tài liệu để đội Android hoặc Claude triển khai mà không tự đổi kiến trúc.

| Tiêu chí | Trọng số | Revision 1 | Revision 2 | Nhận xét sau cải thiện |
|---|---:|---:|---:|---|
| Bám yêu cầu/phạm vi | 15% | 8.8 | 9.8 | Scope, out-of-scope và chống scope creep rõ |
| Audit source prototype | 15% | 8.3 | 9.7 | Có mapping file/component/class và disposition |
| Kiến trúc/ownership | 15% | 8.6 | 9.7 | Package/class, state/event, variants rõ |
| Data/API contract | 15% | 7.8 | 9.5 | Có DTO/repository/phase/error/freshness/change control |
| Screen/UX acceptance | 10% | 8.0 | 9.5 | Có loading/empty/error/offline/nav cho mọi surface |
| Voice/emergency safety | 10% | 9.0 | 9.8 | Có transition/recovery/idempotency/lifecycle |
| Roadmap/owner/risk | 10% | 8.1 | 9.6 | Có exit gate, artifact, risk register và cadence |
| Test/security/release | 5% | 8.5 | 9.7 | Có command/evidence/release-stop criteria |
| AI coding-agent handoff | 5% | 7.5 | 9.6 | Prompt protocol, phase gate và output contract rõ |
| **Tổng điểm** | **100%** | **8.3/10** | **9.7/10** | Sẵn sàng giao build sau khi khóa các quyết định mở |

Không chấm 10/10 vì package/application ID, thiết bị mục tiêu, OpenAPI thật, staging URL, asset/logo và signing policy vẫn cần owner/backend cung cấp.

## Những cải thiện trong Revision 2

- Sửa số phiên bản AGP sai ở Revision 1 thành AGP 9.3.0 và khóa Gradle/JDK/SDK/Compose BOM tương thích.
- Thêm mapping chi tiết source React/TSX → class/package Android và quyết định giữ/viết lại/loại bỏ/backend.
- Thêm trách nhiệm từng package, class tối thiểu, UI state/action/effect contract và build variants.
- Thêm endpoint/DTO/repository/phase matrix, freshness/authority và contract change control.
- Bổ sung state/navigation matrix cho mọi màn hình, confirmation và voice overlay.
- Bổ sung emergency transition table, lifecycle/resource ownership và recovery matrix.
- Bổ sung phase artifact/exit criteria, risk register, cadence review và release-stop criteria.
- Siết prompt cho Claude bằng execution protocol, phase gate và output contract.
- Thêm traceability từ yêu cầu → tài liệu → prompt → kiểm chứng.

## Traceability matrix

| ID | Yêu cầu gốc | Đặc tả | Prompt build | Kiểm chứng |
|---|---|---|---|---|
| REQ-01 | Native Kotlin/Compose, không WebView | `00`, `01`, `02` | Prompt 1 | build/source audit |
| REQ-02 | Audit toàn bộ AI Studio source | `01` | Prompt 0 | mapping/disposition review |
| REQ-03 | Một module app, MVVM/StateFlow | `02` | Prompt 1 | dependency rule/unit test |
| REQ-04 | Mock và Remote Gateway | `02`, `03` | Prompt 1, 7 | shared contract tests |
| REQ-05 | VehicleDataSource mock/VHAL boundary | `02` | Prompt 1 | interface/fake tests |
| REQ-06 | Cockpit một viewport | `04` | Prompt 2 | 390×844/844×390 screenshots/tests |
| REQ-07 | Không attention/drowsiness/DMS | `00`, `01`, `03`, `07` | Mọi prompt | source/APK scan |
| REQ-08 | Assistant text/action/confirmation | `03`, `04` | Prompt 3 | ViewModel/Compose tests |
| REQ-09 | Diagnostics | `04` | Prompt 3 | empty/P0301/overheat tests |
| REQ-10 | Settings/Developer Mode/BASE_URL | `02`, `04`, `07` | Prompt 3 | persistence/route/release tests |
| REQ-11 | Vehicle Simulator/scenarios | `01`, `04` | Prompt 4 | scenario regression |
| REQ-12 | Voice/TTS foreground | `05` | Prompt 5 | permission/lifecycle/fake controller tests |
| REQ-13 | Emergency 5/15/10, simulated only | `03`, `05` | Prompt 6 | FakeClock/recreation/idempotency |
| REQ-14 | REST endpoints và optional WS | `03` | Prompt 7 | DTO/mock HTTP/contract tests |
| REQ-15 | USB/emulator/LAN/staging | `04`, `07`, `09` | Prompt 3, 7 | device/network matrix |
| REQ-16 | Security/no key/HTTPS/allowlist | `00`, `07` | Prompt 7, 8 | release audit |
| REQ-17 | Test strategy/device sizes | `04`, `07` | Prompt 8 | CI/device report |
| REQ-18 | Roadmap 20 ngày/2 Android + backend | `06` | phase sequence | gate review |
| REQ-19 | Phương án một Android developer | `06` | planning only | schedule approval |
| REQ-20 | Deliverables/DoD/checklists | `00`, `07`, `09` | Prompt 8 | APK handoff checklist |
| REQ-21 | Roadmap Gemini/Maps/Automotive/VHAL | `09` | không thuộc MVP | backlog review |
| REQ-22 | Prompt tuần tự cho coding agent | `08` | Prompt 0–8 | phase gate outputs |
| REQ-23 | Master prompt khởi động Claude Code | `11` | Prompt khởi động | Bootstrap/Phase 1 report |

Tên file rút gọn trong bảng là số thứ tự tương ứng trong thư mục này.

## Gap còn mở trước Prompt 1

| Gap | Có chặn code? | Owner | Deadline đề xuất |
|---|---|---|---|
| Application ID/package chính thức | Có, trước tạo project | Product/Android lead | Ngày 0 |
| Thư mục Android project trong repo | Có | Android lead | Ngày 0 |
| Device/API inventory và minSdk approval | Không nếu dùng mặc định 26 | Android lead | Ngày 1 |
| OpenAPI v1 + example fixtures | Không chặn Demo; chặn AI Backend | Android + backend | Gate E của plan `12` |
| Staging HTTPS URL/auth scheme | Không chặn Demo; chặn ngày 17 | Backend | Ngày 12 |
| Logo/font/license chính thức | Không chặn foundation; chặn release | Product/design | Ngày 10 |
| APK signing/internal distribution | Không chặn debug; chặn handoff | Release owner | Ngày 18 |
| Privacy copy/permission rationale | Không chặn mock; chặn device QA | Product/security | Ngày 13 |

## Quyết định chất lượng cuối

Bộ plan Revision 2 được xem là **Ready for implementation with open decisions**. Có thể giao Prompt 0 ngay. Chỉ giao Prompt 1 sau khi khóa application ID và vị trí Android project; các gap backend không được dùng làm lý do trì hoãn Demo Mode.
