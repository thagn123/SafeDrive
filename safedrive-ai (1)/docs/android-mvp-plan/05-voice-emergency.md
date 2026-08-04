# 05 — Voice và Emergency State Machine

## Voice MVP

### Phạm vi

- Chỉ chạy khi app foreground.
- Wake phrase “Hey SafeDrive” là demo foreground; `SpeechRecognizer` không phải hotword engine 24/7.
- Sau wake phrase, chuyển sang nhận lệnh tiếp theo; transcript được gửi qua cùng `AssistantQueryUseCase` như text.
- `TextToSpeech` đọc response khi bật TTS; có stop/cancel và trở về `IDLE`.
- Microphone permission hỏi đúng lúc user bật voice; denial không crash và không tự hỏi lại liên tục.

### State contract

```text
DISABLED
  ↔ IDLE
IDLE → WAKE_WORD_DETECTED → LISTENING → PROCESSING → SPEAKING → IDLE
LISTENING → ERROR → IDLE
PROCESSING → ERROR → IDLE
mọi state active → cancel → IDLE hoặc DISABLED
```

Events phải có `sessionId`/generation để response cũ không ghi đè phiên voice mới. Cancel phải hủy recognizer, coroutine và TTS.

### Lifecycle và resource ownership

| Sự kiện | Recognizer | TTS | UI state |
|---|---|---|---|
| App foreground + user bật voice | sẵn sàng, chưa nghe | init khi cần | `IDLE` |
| Wake/manual mic | bắt đầu một recognition session | stop response cũ | `LISTENING` |
| Final transcript | stop listening | chưa speak | `PROCESSING` |
| Response thành công | release/cancel session | speak nếu enabled | `SPEAKING` rồi `IDLE` |
| User cancel | cancel + destroy session hiện tại | stop | `IDLE`/`DISABLED` |
| App background | stop/cancel | có thể stop theo policy MVP | `IDLE` |
| Activity/ViewModel destroy | destroy | shutdown khi app scope kết thúc | không giữ mic |

Không tạo nhiều `SpeechRecognizer` song song. Callback cũ phải bị bỏ qua bằng generation id. Partial transcript chỉ để preview; final transcript mới được gửi.

### Voice acceptance

- Câu voice “Xe có mã lỗi gì?” đi cùng request path với text.
- Transcript rỗng không gửi request.
- Mất mạng hiển thị lỗi rõ ràng; không đọc response cũ lặp lại.
- TTS tắt thì không gọi `speak`; stop TTS không làm mất chat message.
- App background/closed thì dừng microphone; không chạy foreground service trong MVP.

## Emergency policy

### Evidence rule

Một tín hiệu đơn lẻ không được tự gửi SOS. Mock scenario `crash` phải có ít nhất:

- evidence chính: `crashDetected`;
- evidence hỗ trợ: `passengerResponse = NO_RESPONSE` hoặc driver/seat signal phù hợp;
- emergency snapshot có `realEmergencyDispatchEnabled = false`.

Backend thật sẽ quyết định evidence threshold và deadline; Android không tái tạo policy production.

### State machine

```text
IDLE
  → CANDIDATE_DETECTED
  → VERIFYING_EVIDENCE (5s deadline)
  → AWAITING_USER_RESPONSE (15s deadline)
  → FINAL_COUNTDOWN (10s deadline)
  → SOS_SIMULATED_SENT

VERIFYING_EVIDENCE / AWAITING_USER_RESPONSE / FINAL_COUNTDOWN
  → CANCELLED khi user xác nhận an toàn hoặc hủy SOS
```

`CANDIDATE_DETECTED` có thể là transient state nhưng phải được ghi event; renderer không được tự chuyển state chỉ vì recomposition.

### Bảng transition authoritative

| Current state | Trigger/guard | Next state | Side effect | Persist |
|---|---|---|---|---|
| `IDLE` | candidate có primary + supporting evidence | `CANDIDATE_DETECTED` | tạo emergencyId/eventId | Có |
| `CANDIDATE_DETECTED` | candidate accepted | `VERIFYING_EVIDENCE` | đặt deadline T+5s | Có |
| `VERIFYING_EVIDENCE` | evidence invalid/user responsive | `CANCELLED` | gửi response idempotent | Có |
| `VERIFYING_EVIDENCE` | deadline qua, evidence đủ | `AWAITING_USER_RESPONSE` | đặt deadline T+15s | Có |
| `AWAITING_USER_RESPONSE` | button/voice `USER_OK` | `CANCELLED` | stop TTS/mic, respond | Có |
| `AWAITING_USER_RESPONSE` | deadline qua | `FINAL_COUNTDOWN` | đặt deadline T+10s | Có |
| `FINAL_COUNTDOWN` | button/voice cancel | `CANCELLED` | respond idempotent | Có |
| `FINAL_COUNTDOWN` | deadline qua | `SOS_SIMULATED_SENT` | ghi simulated payload đúng một lần | Có |
| `SOS_SIMULATED_SENT` | acknowledge | `IDLE` hoặc history | không dispatch thật | Theo session |
| `CANCELLED` | acknowledge/new independent event | `IDLE`/new candidate | clear active overlay | Theo session |

### Deadline implementation rules

1. State machine nằm trong `EmergencyViewModel`/repository, không nằm trong composable.
2. Lưu `deadlineMs` tuyệt đối, `emergencyId`, current state và idempotency key vào DataStore/local snapshot.
3. UI chỉ tính `max(0, deadlineMs - clock.nowMs())` để render; không khởi tạo countdown mới.
4. Khi rotation/recomposition, cùng state/deadline tiếp tục.
5. Khi process recreation, load snapshot rồi `GET /emergency/{id}` ở Remote Mode; Mock Mode chạy reducer với fake clock.
6. Khi deadline hết, gửi transition/response một lần; guard bằng state + idempotency key.
7. Sau `SOS_SIMULATED_SENT`, không tự retry vô hạn và không gọi phone/SMS.

### Input protection

- Emergency overlay dùng full-screen, `BackHandler` chặn Back, không dismiss outside/swipe.
- Chỉ hiển thị friendly evidence; raw reason code chỉ Developer Mode.
- Voice cancel nhận “Tôi ổn”, “Hủy SOS”, “Không cần hỗ trợ” qua allowlist tiếng Việt; không dùng substring nguy hiểm quá rộng.
- `realEmergencyDispatchEnabled` compile/config assertion phải false trong MVP.

Chuẩn hóa voice phrase bằng lowercase, trim, bỏ dấu câu và so khớp exact intent/grammar giới hạn. Không dùng điều kiện kiểu `contains("không")`, vì có thể hủy nhầm câu “tôi không ổn”.

### Emergency test timeline

| Mốc | Expected |
|---|---|
| T0 | Candidate/evidence được ghi, hiển thị verifying 5s |
| T0+5s | Awaiting response 15s |
| T0+20s | Final countdown 10s |
| T0+30s | SOS simulated sent đúng một lần |
| Bất kỳ trước T0+30s | User button/voice cancel → CANCELLED |
| Rotation/process death | Cùng deadline, không reset |
| Network loss | Không crash; trạng thái/snapshot rõ ràng |

## Recovery matrix

| Tình huống | Demo Mode | Remote Mode |
|---|---|---|
| Rotation | ViewModel giữ state/deadline | ViewModel giữ state/deadline |
| Process recreation | đọc snapshot, reducer chạy theo clock | đọc snapshot rồi refetch emergency |
| App mở sau deadline | advance qua các state đã hết, sent tối đa một lần | server trả authoritative state |
| Mất mạng trước countdown | tiếp tục mock flow | giữ snapshot; UI báo offline; không tự dispatch |
| Response gửi timeout | idempotent local reducer | retry bounded cùng `responseId`, sau đó refetch |
| Snapshot hỏng/không parse | reset an toàn về IDLE + developer error | không tự gửi; yêu cầu refetch/recovery |

Fail-safe của MVP là **không gửi thật**. Protocol hoặc persistence error không được phép biến thành `SOS_SIMULATED_SENT` bằng phỏng đoán.
