# SafeDrive AI Android — Demo Script

Everything below runs in **Demo Mode** (`MockSafeDriveGateway`) with no backend required. Total time
~12 minutes. See `README.md` for build instructions and `KNOWN_LIMITATIONS.md` for what has not been
verified on real hardware yet. Updated after the mobile stabilization pass
(`docs/android-mvp-plan/12`) — voice/TTS now actually work end-to-end and Demo Mode has no artificial
delay by default, both different from the original MVP build.

## 0. Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Launch **SafeDrive AI**. It should open directly to Cockpit with no crash and no network required,
and replies should feel instant (no more built-in "thinking" delay — see step 2's latency profiles
for how to deliberately slow it down for a demo).

## 1. Cockpit (first impression)

- Confirm the header shows a green "Đã kết nối" connection chip, a status hero card, 2×2 vehicle
  metrics, the driver-support signal summary (3/4 sources), a DTC summary, and a voice status row.
- Rotate the device/emulator to landscape: the same information should reflow into a two-column
  layout with no overlap and no scrolling in portrait.

## 2. Guided Demo Scenarios

1. From Cockpit, tap the purple **Demo** shortcut. It is always visible and opens the guided scenario
   runner directly, without requiring Developer Mode or a trip through Settings.
2. The first screen explains the three-step flow, shows whether the chosen backend has acknowledged
   the signal, and presents the scenario cards. Select one scenario, then return to Cockpit or Assistant
   to show the resulting risk assessment and explanation.
3. Developer Mode is optional: use it only when the team needs to change BASE_URL, test a latency profile,
   inspect demo JSON, or open **Tùy chỉnh tín hiệu** for manual telemetry.
4. Tap through all 11 presets one at a time, and after each one switch to Cockpit to confirm the
   status hero/metrics changed accordingly:
   - `1. Hành trình mới` → normal/teal.
   - `2. Đã lái hơn 2 giờ` → monitor/amber.
   - `3. Nên cân nhắc nghỉ` → consider-rest tier.
   - `4. Đã lái hơn 4 giờ` → rest-recommended, high/orange.
   - `5. Chưa đủ dữ liệu` → insufficient-data messaging.
   - `6. Người dùng báo đang mệt` → rest-recommended regardless of drive time.
   - `7. Động cơ quá nhiệt` → Diagnostics should now show the `ENGINE_OVERHEAT` DTC (HIGH), risk card
     driven by the engine-temperature branch (≥108°C).
   - `8. Va chạm giả lập` → **this immediately triggers the Emergency screen** — see step 5 before
     continuing, or tap "Hủy SOS" to stop it and come back to this checklist later.
   - `9. Lỗi chẩn đoán mức trung bình` (new) → `P0301` DTC only, engine temp stays under the overheat
     threshold. Diagnostics/Cockpit should show **MEDIUM** severity — contrast this against preset 7's
     HIGH to demonstrate the severity gradient.
   - `10. Nhiều mã lỗi cùng lúc` (new) → both `P0301` and `ENGINE_OVERHEAT` active at once (engine temp
     kept below 108°C so the DTC-aggregation branch runs instead of the single engine-overheat branch).
     Diagnostics should list both DTC cards; Cockpit risk message should name both codes and show HIGH
     (driven by the more severe of the two).
   - `11. Cảm biến va chạm đơn lẻ` (new) → `crashDetected=true` but passenger marked responsive and the
     seat sensor reports empty — only one piece of evidence. Confirm this **does NOT** open the
     Emergency screen (evidence rule: a lone crash signal is insufficient — see `05-voice-emergency.md`),
     while the Cockpit still shows a softer "vui lòng xác nhận tình trạng an toàn" prompt instead of the
     no-response CRITICAL wording from preset 8. This is a good scripted moment to explain that SafeDrive
     does not launch SOS on a single noisy sensor.
5. With Developer Mode enabled, open **Tùy chỉnh tín hiệu** and try **manual telemetry**: drag the speed/temperature sliders, toggle
   "Có dữ liệu thời gian lái" off, tap **Áp dụng trạng thái** — a **snackbar** should now confirm
   "Đã áp dụng: `<speed>` km/h" (new). Tap **Khôi phục bình thường** — a snackbar should confirm the
   reset too. Tap **Xem JSON demo** and confirm the preview never shows an API key or secret field.
6. **Latency profiles (new)**: back in Settings' "Độ trễ giả lập" section, try `500 ms` and send an
   Assistant question — the reply should now visibly take about half a second (Demo Mode's own
   default is 0ms — this section exists specifically so a demo can *choose* to show a slower reply).
   Try `Hết thời gian chờ (timeout)` and confirm the Assistant shows a timeout error rather than
   hanging silently. Reset back to "Không giả lập (mặc định)" afterward.

## 3. Diagnostics

1. With the `overheat` preset still active (or re-apply it), open **Chẩn đoán**.
2. Confirm `ENGINE_OVERHEAT` renders with its HIGH severity badge, description and recommendation.
3. Tap **Hỏi SafeDrive AI** — this should navigate to Assistant with the composer already prefilled
   with a question about that DTC.

## 4. Assistant (text + voice) — now a single shared pipeline

1. Send the prefilled question (or type "Kiểm tra nhiệt độ động cơ") — a user bubble appears, then a
   SAFEDRIVE reply referencing the actual current engine temperature. If TTS is on (see the header's
   volume icon), **the reply should now be read aloud** — this previously did not happen for typed
   questions at all; confirming it now works is the single most important check in this script.
2. Try the quick-suggestion chips (now 5, matching the AI Studio prototype — a "Gợi ý điểm dừng nghỉ
   gần đây" chip was added) — confirm they scroll horizontally without clipping if you narrow the
   window/use a small device (new safety fix; previously a very narrow screen could clip them).
3. Tap the mic icon in the composer. Grant the microphone permission if prompted. Say a short
   phrase — the overlay should show "SafeDrive đang nghe..." only once the recognizer is actually
   ready (not immediately on tap), process it through the same reply path as typed text (**the reply
   must appear in the chat history, not just be spoken and disappear** — this was broken before the
   stabilization pass), and read the reply aloud if TTS is on. While listening, confirm a **"Kết thúc
   câu nói"** button is visible (new) — tapping it should finalize whatever was captured so far rather
   than discarding it (distinct from the close button's "Hủy nghe"). Once the turn finishes, confirm the
   overlay now shows **"SafeDrive đã trả lời"** with the actual reply text and a **"Đóng"** button (new,
   second re-audit) instead of instantly vanishing — tapping "Đóng" dismisses it; if the turn instead
   failed, confirm the overlay shows the real error message with the same "Đóng" button, not a generic
   spinner that disappears on its own.
4. While the assistant is "thinking" (either via a slowed-down latency profile or naturally), confirm
   there's now a **"Hủy"** button next to the thinking indicator — tapping it should cancel the turn,
   keep your typed message in the chat, and let you retry.
5. Toggle the speaker icon in the Assistant header — confirm its icon/tint changes meaningfully (not
   just on/off): while SafeDrive is actually speaking it should look visually distinct from idle-ready.
6. Ask something that triggers a confirmable action, e.g. "Tôi đang cảm thấy mệt" — a confirmation
   dialog should appear before anything happens; cancelling must not change the chat history. See
   `docs/assistant-action-allowlist.md` for the full list of actions and their effects.

## 5. Emergency (5s / 15s / 10s timeline)

1. In Simulator, apply preset **8. Va chạm giả lập** (or toggle "Phát hiện va chạm xe" manually with
   "Hành khách KHÔNG PHẢN HỒI" also on — a single toggle alone must **not** trigger anything).
2. A full-screen red/amber emergency screen should appear within ~1 second, showing "Đang xác minh
   tình huống khẩn cấp..." with a 5-second countdown. The banner reads
   "SOS MÔ PHỎNG (KHÔNG PHẢI CỨU HỘ THẬT)" / `real_emergency_dispatch_enabled: false`.
3. Press the system Back button — nothing should happen (the screen must not dismiss).
4. Wait out the 5s: the screen moves to "Bạn có ổn không?" with a 15s countdown and a
   "TÔI VẪN ỔN — HỦY SOS" button.
5. **Test cancellation**: either tap that button, or tap "Nói 'Tôi ổn' / 'Hủy SOS'" and say exactly
   "Tôi ổn" or "Hủy SOS" — the screen should close immediately back to whatever screen was behind it.
   Saying "Tôi không ổn" must **not** be treated as a cancel.
6. **Test rotation/process death mid-countdown**: re-trigger the crash preset, then rotate the device
   during the 15s or 10s countdown (and/or `adb shell am kill <package>` + relaunch) — reopening the
   app must show the **same** remaining time, never silently reset to IDLE.
7. **Let it run to completion** at least once: after 5s + 15s + 10s = 30s total with no response,
   the screen shows "Đã gửi tín hiệu SOS mô phỏng khẩn cấp" (still simulated only). Tap
   "Quay lại Cockpit" to dismiss.

## 6. Remote Mode (optional, needs a backend or a local mock server)

1. In Settings' Developer section, switch **Backend mode** to Remote **without** setting a BASE_URL
   first — tap **Kiểm tra sức khỏe backend**. Confirm it fails with a clear "chưa cấu hình BASE_URL"
   message, **not** a silent success and **not** Demo Mode data (new — this used to silently fall back
   to Mock).
2. Now set a BASE_URL preset (Emulator `http://10.0.2.2:8002/` if running an emulator with a local
   server, or Cloud Staging for an HTTPS server) and tap **Lưu BASE_URL**.
3. Tap **Kiểm tra sức khỏe backend** again — if reachable, the message should now show the host and
   an `assistant=true/false` capability flag (new). If no server is reachable, it should show a clear
   offline/timeout error within about 5 seconds, never a crash and never a false "connected" state.
4. Switch back to Demo mode — the app must keep working with no backend, exactly as in step 1, and
   any in-flight request against the old mode should have been cancelled automatically.

For the full five-scenario Remote Mode acceptance run against the SafeDrive
backend, follow [REMOTE_MODE_SMOKE_TEST.md](REMOTE_MODE_SMOKE_TEST.md).
