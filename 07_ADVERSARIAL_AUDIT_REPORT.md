# SafeDrive - Báo cáo kiểm thử đối kháng trước khi chạy thử thực tế

## Follow-up disposition - 2026-08-01

This section preserves the original audit below as an evidence record and
updates only the disposition of its open findings after a focused remediation
pass.

### Resolved after the audit

1. **Section 4.1 - HVAC confirmation vs. unrelated telemetry.** A typed HVAC
   action is now rebound to the latest state version only when its server-owned
   safety/comfort dependency fingerprint is unchanged. Speed and location
   updates no longer cancel the proposal; a change to cabin temperature,
   energy, HVAC target, driving duration, crash/passenger condition, reported
   fatigue, DTC code/severity, or state freshness invalidates it. The action ID,
   type and target remain server-validated and cannot be forged. Regression
   coverage proves both the permitted and rejected cases.
2. **Section 4.3 - natural Vietnamese wording.** The deterministic MVP router
   now recognizes `Toi khong khoe`, `Xe co van de gi khong?`, and the combined
   comfort command `Nong qua, bat dieu hoa len`. The latter now uses the
   context-grounded cabin route rather than the energy-only generic command.
3. **Section 4.5 - combined hot-cabin phrasing.** Addressed by the explicit
   compound-command test and route precedence described above.
4. **Section 4.6 - expired session retention.** Session start purges expired
   in-memory entries; expired lookup also removes the entry before returning
   the established fail-closed error. A clock-driven regression test covers
   the behavior.

### Still deliberately deferred before the trial run

- **Section 4.2:** true multi-turn conversation memory and a constrained LLM
  fallback need an explicit design and evaluation pass; they are not safe to
  add as a last-minute claim.
- **Section 4.4:** HVAC-off/fan controls require an additive command/action
  contract and Android control/UI implementation. They are outside the one
  real cockpit-control action in the frozen MVP.
- **Section 4.7:** media, volume, doors and infotainment remain future normal
  assistant capabilities, not live MVP controls.

### Updated verification

- Backend: `pytest -q` -> **159 passed**; `ruff check .` -> clean.
- Android: `testDebugUnitTest --rerun-tasks` -> **284 passed**, 0 failures,
  0 errors across 28 JUnit XML suites.
- The remaining integration gate is unchanged: run
  `android/REMOTE_MODE_SMOKE_TEST.md` on an actual emulator or device. No
  device is attached to this development machine at the time of this update.

**Ngày:** 2026-08-01
**Phạm vi:** Đối chiếu `graphify-out/GRAPH_REPORT.md` với bảng điểm 9.7/10 đã nhận được, sau đó kiểm thử đối kháng trực tiếp trên backend đang chạy (không chỉ đọc code hay tin vào test có sẵn), tìm bug, và sửa các bug an toàn/nghiêm trọng tìm được.

---

## 1. Về `graphify-out` và bảng điểm 9.7/10

Đọc trực tiếp `graphify-out/GRAPH_REPORT.md`: file này **không hề chứa điểm số, rubric hay bất kỳ đánh giá chất lượng nào**. Nội dung thật của nó chỉ là thống kê cấu trúc thuần túy:

- Số node/edge, số community (2389 node, 5380 edge, 141 community).
- Danh sách "God Nodes" — xếp hạng thuần túy theo *số lượng edge thô*, không phải chất lượng.
- Danh sách community kèm một con số cohesion (0.04–0.83), không có mô tả "làm tốt điều gì".
- Kiểm tra import cycle ("None detected").
- Danh sách node cô lập ("Knowledge Gaps").
- Danh sách câu hỏi gợi ý để khám phá thêm — tức là **câu hỏi**, không phải **câu trả lời**.

Các câu trong bảng điểm cũ như *"Community 18 và Community 4 DTO khớp 1-1"* hay *"SignalRegistry (109 edges) kiểm soát tốt độ tươi dữ liệu"* là diễn giải được ai đó gắn thêm vào sau, không phải kết luận mà công cụ graphify thực sự đưa ra. Công cụ chỉ đo được độ kết nối (connectivity), không đo được tính đúng đắn hay chất lượng.

**Kết luận:** bảng điểm 9.7/10 trước đó không có căn cứ thật từ graphify-out.

---

## 2. Phương pháp kiểm thử lần này

Thay vì đọc code hoặc chạy lại bộ test có sẵn (dễ bị thiên vị vì cùng một quá trình viết ra cả code lẫn test), 4 agent độc lập đã:

- Khởi động backend thật trong tiến trình (`httpx.AsyncClient` + `ASGITransport` chạy `create_app()` thật).
- Tự viết kịch bản hội thoại mới, có cả câu tự nhiên và câu "khó" (không giống ví dụ có sẵn trong test).
- Đóng vai người dùng thật: gửi lệnh, đọc JSON trả về, đánh giá xem có tự nhiên/đúng ngữ cảnh hay không.
- Với luồng khẩn cấp: dùng `asyncio.sleep()` thật để chờ hết deadline, không patch nội bộ.
- Thử tấn công vào cơ chế bảo vệ xác nhận action (giả mạo actionId, đổi nhiệt độ, replay sau khi state đổi).

4 hướng kiểm thử song song: (a) lệnh HVAC/điều hòa, (b) grounding theo ngữ cảnh + tính liên tục hội thoại, (c) toàn bộ state machine khẩn cấp/cứu hộ, (d) rà soát code tĩnh cho bug + đối chiếu với ý định sản phẩm trong các file .md gốc.

---

## 3. Bug tìm thấy và đã sửa

| # | Bug | Mức độ | Vị trí | Đã sửa |
|---|---|---|---|---|
| 1 | Emergency đã `CANCELLED` hoặc đã gửi SOS mô phỏng (`SOS_SIMULATED_SENT`) vẫn có thể bị **mở lại** bởi một tín hiệu `NO_RESPONSE` trùng lặp/muộn đến sau | An toàn — nghiêm trọng | `app/mobile/session_store.py`, hàm `respond_emergency` | ✅ Thêm `_TERMINAL_EMERGENCY_STATES` + kiểm tra trạng thái kết thúc ngay đầu hàm |
| 2 | Trong lúc va chạm thật đang diễn ra (risk = CRITICAL), nếu người dùng hỏi kiểu buồn ngủ, hệ thống trả lời "bối cảnh hiện tại cho thấy nguy cơ **mệt mỏi** (crash_detected, occupant_no_response)" — gán nhãn sai bằng chứng va chạm thành mệt mỏi, không hề nhắc đến vụ khẩn cấp đang chạy. Vì đây là hệ thống ưu tiên giọng nói, câu trả lời sai trong lúc va chạm thật là lỗi giao tiếp an toàn thật sự | An toàn — trung bình (dữ liệu `risk`/`actions` vẫn đúng, chỉ sai lời thoại) | `app/mobile/assistant.py`, `_message_and_actions` | ✅ Thêm kiểm tra `safety.emergency_candidate` ngay từ đầu, ưu tiên hơn mọi route theo từ khoá |
| 3 | "Đặt điều hòa **âm** 20 độ C" (-20°C) bị parse thành **+20°C** — nằm trong dải hợp lệ 16-30°C nên sẽ bị **chấp nhận ngầm**, ngược hoàn toàn ý người dùng | Trung bình | `app/mobile/intent.py`, regex `_TEMPERATURE_PATTERN` | ✅ Regex nhận diện dấu âm/"âm", áp dụng đúng dấu trước khi kiểm tra dải hợp lệ |
| 4 | Badge HVAC/Cabin trên Android hiển thị `"23,5"` (dấu phẩy) thay vì `"23.5"` trên máy có locale dùng dấu phẩy thập phân (ví dụ **tiếng Việt — đúng thị trường mục tiêu của app**) — lệch với chữ trong tin nhắn trợ lý từ backend (luôn dùng dấu chấm). Phát hiện được *nhờ* việc viết unit test còn thiếu cho hàm này | Trung bình, dễ bị bỏ sót vì máy CI/dev tiếng Anh không lộ ra | `VehicleMetricsPanel.kt`, hàm `formatTemp` | ✅ Ép dùng `Locale.US` khi format; thêm test file `VehicleMetricsPanelFormattingTest.kt` |

**Kết quả kiểm chứng sau khi sửa:**
- Backend: `pytest -q` → **153/153 pass** (thêm 4 test hồi quy), `ruff check` → sạch.
- Android: `gradlew testDebugUnitTest` → **284/284 pass**, 28 class (thêm 1 file test mới, 3 test case).
- Toàn bộ thay đổi **chưa commit**, chờ quyết định của bạn.

---

## 4. Khoảng trống còn lại — chưa sửa, cần bạn quyết định

### 4.1. ⚠️ Rủi ro cao nhất cho lần chạy thử thực tế của bạn

Bất kỳ `POST /api/v1/state/update` nào — kể cả chỉ đổi GPS/tốc độ, không liên quan gì đến HVAC — cũng **huỷ toàn bộ action đang chờ xác nhận** (kể cả HVAC hợp lệ). Trong khi đó, phía Android (`ObserveCockpitUseCase.kt`) gửi `state/update` **mỗi lần** có tín hiệu telemetry mới trong Remote Mode. Hệ quả: cửa sổ để bấm "xác nhận" một đề xuất HVAC có thể rất ngắn — bị giới hạn bởi tần suất telemetry, không phải bởi tốc độ phản ứng thật của bạn.

Đây là lựa chọn phòng thủ có chủ đích (đã kiểm chứng: chống được mọi kiểu giả mạo/replay tôi thử), nhưng có chi phí sử dụng thật, không giả định. **Rất có thể bạn sẽ gặp thông báo "The vehicle context changed. Please review the latest recommendation." ngay giữa demo HVAC.** Chưa sửa vì nới lỏng cơ chế này là đánh đổi bảo mật/tiện dụng cần quyết định rõ ràng, không nên tự ý thay đổi logic bảo vệ an toàn.

### 4.2. Không có bộ nhớ hội thoại

Xác nhận chắc chắn qua code: `AssistantQueryRequest` và `MobileSession` không có field nào lưu lượt hỏi trước. Mỗi câu hỏi được xử lý độc lập, chỉ dựa trên (văn bản hiện tại, trạng thái xe hiện tại). Điều này trái với tuyên bố rõ ràng trong `00_SAFEDRIVE_MASTER_CONTEXT.md` ("Keep a short conversation history so the next question is interpreted in context"). Ví dụ mẫu chính tài liệu đưa ra ("Tôi không khỏe") **không** kích hoạt được logic hỏi làm rõ mà tài liệu dùng nó để minh hoạ.

### 4.3. Router chỉ khớp từ khoá, dễ vỡ

- "Xe có vấn đề gì không?" (câu tự nhiên) không khớp từ khoá DTC nào — chỉ câu cứng "xe co gi la" (dùng trong unit test có sẵn) mới hoạt động.
- "Tôi không khỏe" — ví dụ mẫu của chính tài liệu gốc — rơi vào câu trả lời chung chung thay vì logic làm rõ.

### 4.4. Không có lệnh "tắt điều hòa"

"Tắt điều hòa" / "Tắt máy lạnh đi" không khớp route nào, rơi vào câu hỏi lại gây hiểu lầm ("Bạn đang thấy mệt, khó chịu trong cabin, hay lo về tình trạng xe?") — nghe như hệ thống hiểu nhầm thành báo cáo sự cố an toàn. Tương tự với yêu cầu tốc độ quạt và các câu diễn đạt gián tiếp ("Cho tôi xin chút gió mát").

### 4.5. Câu ghép bỏ qua ngữ cảnh nhiệt độ thật

"Nóng quá, bật điều hòa lên" đi thẳng vào nhánh lệnh trực tiếp (`climate.enable_default`, chỉ dựa vào % năng lượng) thay vì nhánh có grounding theo nhiệt độ cabin thật (`comfort.too_hot`). Có thể chấp nhận được (người dùng đã ra lệnh rõ ràng) nhưng là đánh đổi chưa được cân nhắc kỹ.

### 4.6. Session không bao giờ bị dọn khỏi bộ nhớ

`self._sessions` không có cơ chế xoá/hết hạn thật sự — mỗi lần start session mới sẽ tăng bộ nhớ server vĩnh viễn trong vòng đời tiến trình. Không gấp cho demo ngắn hạn, nhưng là rò rỉ thật.

### 4.7. Fan/media/volume/door: 0% triển khai

Đúng như phạm vi MVP đã chốt (mục 9, `00_SAFEDRIVE_MASTER_CONTEXT.md` — chỉ định rõ 5 kịch bản, HVAC là hành động cockpit duy nhất), dù mục 3 cùng file mô tả tham vọng rộng hơn (fan/media/volume/doors/infotainment). Đây là mâu thuẫn nội tại giữa mục 3 và mục 9 của chính tài liệu, không phải lỗi code.

---

## 5. Điểm đánh giá trung thực (thay cho 9.7/10)

Dùng cùng khung 5 hạng mục như bảng cũ, nhưng có bằng chứng chạy thật thay vì suy diễn từ đồ thị cấu trúc:

| Hạng mục | Trọng số | Điểm | Vì sao |
|---|---|---|---|
| Kiến trúc & Hợp nhất Contract | 25% | 8.5/10 | 9 endpoint hoạt động đúng, cơ chế chống giả mạo action vững chắc — nhưng rủi ro cửa sổ xác nhận HVAC (mục 4.1) chưa xử lý |
| Quản lý Tín hiệu & Context Xe | 20% | 9/10 | Grounding thật (số liệu, mã DTC, thời lượng lái đều lấy từ state thật, không hallucinate) |
| Voice Assistant & NLU | 20% | 6.5/10 | Điểm yếu nhất: không có bộ nhớ hội thoại, router chỉ khớp từ khoá kể cả với ví dụ mẫu của chính tài liệu, thiếu lệnh tắt/quạt |
| Safety Guardian | 20% | 9/10 | Luật xác định, fail-closed đúng với bằng chứng đơn lẻ và dữ liệu cũ — đã sửa 2 bug state machine/text tìm thấy trong đợt này |
| Rescue/SOS Simulation | 15% | 9/10 | Đi hết toàn bộ state machine bằng thời gian thực, location/fail-closed đúng, luôn `SIMULATION_ONLY` |

**Tổng điểm có trọng số: ~8.4/10.** Nền tảng contract, context-grounding, Safety Guardian và rescue simulation đều vững chắc và đã được kiểm chứng bằng cách chạy thật. Điểm yếu thật sự nằm ở lớp voice assistant/NLU — đúng khoảng cách mà chính tài liệu sản phẩm cảnh báo cần tránh ("avoid keyword-only behavior").

---

## 6. Checklist trước khi chạy thử thực tế

- [ ] **Chưa có smoke test trên thiết bị/emulator Android thật** — đây vẫn là hạng mục lớn nhất chưa được kiểm chứng trực quan trong toàn bộ dự án.
- [ ] Khi test HVAC qua Remote Mode: nếu gặp "The vehicle context changed...", đó là do mục 4.1, không phải lỗi crash.
- [ ] Không thử các câu ngoài kịch bản đã kiểm chứng (tắt điều hòa, quạt, câu nối tiếp không nêu lại chủ đề) trong buổi demo chính thức — sẽ ra câu trả lời gây hiểu lầm.
- [ ] Các câu đã kiểm chứng hoạt động tốt: "Đặt điều hòa X độ C" (16-30, kể cả số thập phân), "Tôi muốn bật điều hòa"/"Bật máy lạnh" (mặc định theo năng lượng), câu hỏi tình trạng xe/DTC theo đúng từ khoá đã test, "tôi hơi buồn ngủ" sau lái lâu, kịch bản va chạm + không phản hồi.
- [ ] Quyết định: có commit đợt sửa lỗi này trước khi bắt đầu chạy thử không.

---

## 7. Trạng thái commit

- Baseline đã commit: backend `2866880`/`12de3fe`; Android/app `9153619`/`961ff9f`.
- Đợt "action-confirmation hardening và lệnh HVAC tự nhiên" (149 test backend) và đợt kiểm thử đối kháng này (153 backend / 284 Android) đều **chưa commit**.
