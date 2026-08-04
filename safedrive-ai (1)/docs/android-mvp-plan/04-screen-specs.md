# 04 — Screen specifications

## Quy tắc UI chung

- Material 3, dark theme ưu tiên, màu semantic nhất quán: normal/teal, monitor/amber, high/orange, critical/red, offline/slate.
- Mọi màn hình có loading, error, empty và offline/stale state phù hợp; không dùng spinner vô hạn.
- Chạm tối thiểu 48dp, content description cho icon-only button, TalkBack order rõ ràng.
- `Developer Mode` mặc định tắt; raw JSON, latency, route, reason code, endpoint và simulator chỉ hiện khi bật.
- Emergency là full-screen modal state, ẩn bottom navigation, không dismiss bằng Back/swipe/tap outside.

## Cockpit

**Mục tiêu:** cho người lái nhìn nhanh trạng thái xe và nguồn tín hiệu gián tiếp trong một viewport.

**Input:** `VehicleState`, `RiskAssessment`, `RestRecommendation`, connection status, voice state, DTC count.

**Hiển thị:** header + connection chip; status hero; tốc độ; nhiệt độ động cơ/cabin; năng lượng; thời gian lái; signal summary; DTC summary; voice status; bottom navigation.

**Không hiển thị:** attention/drowsiness score, raw reason code, simulator, nút chat lớn, kết luận tài xế tỉnh/buồn ngủ.

**States:**

- Loading: skeleton/placeholder cố định, không làm vỡ chiều cao.
- Normal: data mới, risk LOW/NORMAL.
- Monitor/high: status hero và badge cảnh báo, có action mở cảnh báo/diagnostics.
- Stale: giữ giá trị cuối và gắn “Dữ liệu cũ”; không suy diễn nguồn thiếu.
- Offline/no vehicle data: thông báo ngắn, render phần dữ liệu còn tin cậy.

**Acceptance:** không scroll ở 390×844; không overlap ở 844×390; font scale 1.3 vẫn dùng được; active source count tính từ availability; bottom nav không xuất hiện trong emergency.

## Assistant

**Mục tiêu:** hỏi đáp text/voice an toàn, trả action có confirmation.

**Input:** chat history, composer, connection mode, vehicle context version, pending prompt từ Diagnostics/Cockpit.

**UI:** message list, composer, quick suggestion chips, loading row, retry, TTS toggle, action card, confirmation dialog, voice overlay.

**States:** idle, sending, success, timeout, offline fallback, error, empty input, duplicate-submit blocked.

**Actions:** `OPEN_DIAGNOSTICS` điều hướng; `SHOW_WARNING` mở alert; `SUGGEST_REST_STOP` chỉ đề xuất; `START_SOS_COUNTDOWN` luôn confirmation và vẫn chỉ mô phỏng.

**Acceptance:** keyboard không che composer; retry không tạo message user trùng; TTS dừng được; unknown action bị bỏ qua; latency/route chỉ hiện trong Developer Mode; response lỗi có retry.

## Diagnostics

**Mục tiêu:** hiển thị DTC do gateway cung cấp, không tự phân loại severity.

**Input:** `activeDtcs`, last updated, connection status, developer mode.

**Empty:** “Không có lỗi kỹ thuật đang hoạt động” + nút tới Simulator chỉ trong Developer Mode.

**Non-empty:** code, title, severity badge, technical description, recommendation, last updated, “Hỏi SafeDrive AI”.

**States:** loading scan, empty, populated, stale/offline, backend error với retry.

**Acceptance:** P0301 và ENGINE_OVERHEAT render đúng fixture; nút hỏi assistant prefill query; raw reason code chỉ ở dev; không đổi severity từ UI.

## Settings

**User section:** TTS, wake word, microphone permission status, wearable, location/privacy, service status, app version.

**Developer section:** toggle mặc định off; Demo/Remote mode; BASE_URL presets USB/emulator/LAN/cloud; health check thật; simulator; raw JSON; latency/route/reason code.

**States:** saving, invalid URL, permission denied, health success, health timeout, remote unavailable.

**Acceptance:** settings tồn tại sau app restart; release build không cho cleartext; `realEmergencyDispatchEnabled` luôn false; không hiển thị endpoint/debug data cho user thường.

## Vehicle Simulator

Chỉ route được từ Developer Mode.

**Preset bắt buộc:** hành trình mới; hơn 2 giờ; cân nhắc nghỉ; khuyến nghị nghỉ; thiếu dữ liệu; user báo mệt; P0301; quá nhiệt; va chạm có/không phản hồi; cloud offline.

**Manual fields:** speed, engine/cabin temperature, continuous driving minutes/null, steering availability, seat sensor/occupied, wearable connected/HR, user fatigue, DTC, crash, response.

**Actions:** apply state, reset nominal, preview JSON. Apply phải phát event và cập nhật Cockpit/Diagnostics/Assistant/Emergency qua cùng state flow.

**Acceptance:** preset deterministic và reset được; không leak Simulator trong release/user mode; JSON preview không chứa secret; crash scenario khởi động đúng emergency evidence flow.

## Emergency/SOS

Full-screen renderer theo state trong `05-voice-emergency.md`.

**Input:** authoritative emergency snapshot, deadline, evidence summary, voice response.

**Allowed action:** “Tôi vẫn ổn — Hủy SOS”, “Hủy SOS” bằng voice; không có X, Back, swipe hoặc tap outside.

**Acceptance:** một tín hiệu đơn lẻ không đủ; countdown không reset khi recomposition/rotation/process recreation; transition tự động dùng deadline; payload chỉ mô phỏng và idempotent.

## Confirmation dialog và voice overlay

- Dialog chỉ dành cho action có `requiresConfirmation`; focus vào primary/secondary rõ ràng, Back có behavior do state owner quyết định.
- Voice overlay hiển thị transcript, state, TTS controls và permission/error message; không hiển thị “microphone đang mở” nếu controller chưa thực sự recording.
- Không giữ microphone khi app đã đóng; MVP wake phrase chỉ hoạt động foreground.

## Layout strategy

| Window | Strategy |
|---|---|
| 360×800 | single-column compact, ưu tiên status/metrics, không clip text |
| 390×844 | cockpit target layout, không scroll |
| 412×915 | tăng spacing nhưng giữ hierarchy |
| 844×390 | landscape two-column/grid, không overlay |
| Automotive future | landscape-first adaptive slot; không cam kết VHAL trong MVP |

## Ma trận state và điều hướng bắt buộc

| Màn hình | Loading | Empty | Error | Offline/stale | User action | Navigation/overlay |
|---|---|---|---|---|---|---|
| Cockpit | Skeleton giữ nguyên layout | No vehicle data | Retry state refresh | Giữ snapshot cuối + stale badge | mở details/diagnostics/voice | 4-tab nav; emergency che toàn bộ |
| Assistant | Pending message + progress | Welcome + suggestion chips | Inline retry theo message | Local allowlist hoặc thông báo unavailable | send/retry/action/TTS/voice | action có thể mở diagnostics/alert/confirm |
| Diagnostics | Scan/loading row | No active DTC | Retry fetch | DTC cuối + updated time | ask assistant, open detail | prefill + chuyển Assistant |
| Settings | Saving/health checking | Không áp dụng | Invalid URL/permission/health error | Service status offline | toggle, permission, test URL | Simulator chỉ khi dev |
| Simulator | Applying scenario | Preset list luôn có | Fixture/apply error | Cloud-offline là một scenario | select/apply/reset/preview | quay Cockpit/Diagnostics để quan sát |
| Emergency | State-specific verifying | Không áp dụng | Protocol/snapshot recovery state | Giữ snapshot/deadline; gắn connection state | cancel bằng button/voice | full-screen; chặn Back và nav |
| Confirmation | Pending action info | Không render nếu null | Action failure sau confirm | Cho hủy; không giả thành công | confirm/cancel | modal không làm mất screen state |
| Voice overlay | listening/processing/speaking | transcript rỗng vẫn ở listening | permission/no match/network/TTS error | Không gửi khi backend unavailable | cancel/stop/retry | overlay trên screen hiện tại |

## Acceptance chi tiết cho modal/overlay

### Confirmation dialog

- Chỉ mở khi `requiresConfirmation=true` và action nằm trong allowlist.
- Hiển thị action title, hậu quả dự kiến và primary/secondary action.
- Confirm chỉ gửi một lần; trong lúc gửi disable primary button.
- Cancel đóng dialog nhưng không xóa chat/history.
- Backend reject/timeout hiển thị lỗi và cho retry; không tự thực thi action.
- Rotation giữ pending action; process death chỉ restore nếu action vẫn còn hợp lệ theo context version.

### Voice overlay

- `LISTENING` chỉ hiển thị khi recognizer thực sự bắt đầu nghe.
- Transcript partial và final phân biệt; chỉ final non-empty mới gửi.
- Permission denied có CTA tới Settings khi cần, không loop permission dialog.
- App background dừng recognizer; quay foreground trở về `IDLE`, không tự mở mic.
- `SPEAKING` có stop; `onDone/onError` đều đưa state về `IDLE`.
- Emergency voice response được ưu tiên route tới emergency handler, không gửi vào assistant chat.

## Quy tắc adaptive Cockpit

- Portrait compact: status hero tối đa khoảng 30% chiều cao; metrics dùng 2×2; signal/DTC/voice dùng hàng compact.
- Landscape phone: hero và vehicle metrics ở hai cột; signal/DTC/voice ở cột hoặc hàng phụ, tránh text chạy dưới navigation.
- Không dùng chiều cao pixel cố định lấy từ prototype web; dùng constraints và text measurement của Compose.
- Khi font scale vượt 1.3, ưu tiên rút gọn secondary copy và cho details modal; không ẩn primary status/metrics.
- Automotive phase sau dùng template/UX guideline riêng; không tái sử dụng nguyên bottom navigation phone.
