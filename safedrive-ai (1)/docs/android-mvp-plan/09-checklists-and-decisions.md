# 09 — Checklists và quyết định cần khóa

> **Trạng thái:** Checklist khởi động dự án ban đầu, phần lớn đã chốt khi build Android MVP (8 phase).
> Với stabilization hiện tại dùng `12-mobile-completion-before-ai-backend.md` cho W0–W8/Gate A–E;
> `12` supersede tài liệu này nếu có xung đột về Remote fallback, total timeout hoặc lịch gate.

## Checklist bắt đầu dự án

- [ ] Chốt package name/applicationId và versioning.
- [ ] Xác nhận baseline: minSdk 26, compileSdk 37, targetSdk 37, JDK 17, Gradle 9.5.0, AGP 9.3.0, Compose BOM 2026.06.00.
- [ ] Xác nhận targetSdk 37 behavior-change regression gate hoặc ghi ADR fallback target 36 tạm thời.
- [ ] Chốt locale MVP `vi-VN`, timezone/timestamp UTC và dark theme baseline.
- [ ] Chốt Demo Mode là default và `realEmergencyDispatchEnabled=false`.
- [ ] Chốt domain contract ở `03-data-api-contract.md`; mọi thay đổi phải versioned.
- [ ] Chốt BASE_URL matrix: USB, emulator, LAN, cloud HTTPS.
- [ ] Chốt test devices/emulators và kích thước layout.
- [ ] Chốt owner review cho emergency/security/DTO.
- [ ] Tạo branch/build pipeline và rule không commit secret.

## Checklist kết thúc mỗi phase

- [ ] Build/lint/test pass.
- [ ] Demo Mode vẫn chạy không backend.
- [ ] Không có scope creep ngoài phase.
- [ ] UI không nhận DTO/HTTP trực tiếp.
- [ ] Loading/error/offline state đã được xem xét.
- [ ] Accessibility semantics và touch target không bị bỏ qua.
- [ ] README/changelog của phase đã cập nhật.
- [ ] Reviewer chéo đã xem các thay đổi nhạy cảm.

## Checklist nghiệm thu APK

- [ ] Cài APK trên điện thoại thật.
- [ ] Mở lần đầu không cần backend và không crash.
- [ ] Bật Developer Mode, chọn từng preset, kiểm tra Cockpit/Diagnostics/Assistant.
- [ ] Nhập text query, retry timeout, tắt TTS, bật voice và từ chối microphone.
- [ ] Chạy crash + no response, đợi đủ 5/15/10 giây, xác nhận chỉ SOS mô phỏng.
- [ ] Xoay màn hình và kill/reopen app trong emergency; deadline không reset.
- [ ] Test offline, invalid URL, emulator `10.0.2.2`, LAN và HTTPS staging.
- [ ] Kiểm tra release artifact không có simulator/raw metadata/API key/cleartext.
- [ ] Đính kèm test report, demo script và known limitations.

## Quyết định cần khóa trước khi code

| Quyết định | Giá trị mặc định đề xuất | Người xác nhận |
|---|---|---|
| Application ID/package | `com.<team>.safedrive` | Product/Android lead |
| Android minSdk | 26 mặc định; chỉ đổi khi có device requirement | Android lead |
| compileSdk/targetSdk | 37/37; target 36 chỉ là bootstrap fallback có ADR | Android lead |
| Build toolchain | AGP 9.3.0, Gradle 9.5.0, JDK 17, Compose BOM 2026.06.00 | Android lead |
| Kotlin plugin | AGP built-in Kotlin; không dùng lại `org.jetbrains.kotlin.android` | Android lead |
| Backend API base path | `/api/v1` | Backend lead |
| Session identity | generated device/session id, không dùng secret cố định | Backend + security |
| Rest enum | map `NO_IMMEDIATE_INDICATION` của prototype → `NORMAL` | Product + backend |
| Emergency authority | Remote backend; Mock reducer chỉ cho Demo | Backend + safety owner |
| Demo location | simulated + label rõ ràng | Product |
| WebSocket | Sau REST/polling, không blocking MVP gate | Android/backend |
| Voice hotword | foreground demo, không 24/7 | Product + privacy |
| Wearable/VHAL/Maps | roadmap phase sau | Product |

## Prompt order checklist

- [ ] Prompt 0: read plan/context.
- [ ] Prompt 1: foundation/domain/mock.
- [ ] Prompt 2: cockpit.
- [ ] Prompt 3: assistant/diagnostics/settings.
- [ ] Prompt 4: simulator.
- [ ] Prompt 5: voice/TTS.
- [ ] Prompt 6: emergency.
- [ ] Prompt 7: remote REST.
- [ ] Prompt 8: QA/handoff.

## Phase sau MVP

1. Backend production deterministic safety policy + observability.
2. Gemini server-side orchestration; Gemini Live bằng ephemeral token nếu thật sự cần.
3. Maps/location consent và route-aware rest stop.
4. Wearable integration với freshness/permission rõ ràng.
5. Android Automotive adapter, VHAL và landscape HMI.
6. Dedicated on-device wake-word engine + foreground service sau khi có battery/privacy test.
7. Chỉ xem xét real emergency dispatch sau threat model, legal/safety review, explicit feature flag và end-to-end approval.
