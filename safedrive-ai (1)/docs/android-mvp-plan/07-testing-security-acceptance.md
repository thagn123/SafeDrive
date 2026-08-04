# 07 — Testing, security và nghiệm thu

## Test strategy

### Unit test

- Model mapper DTO ↔ domain.
- Error mapper: timeout/offline/4xx/5xx/invalid JSON.
- Fixture/preset builder.
- Action allowlist và confirmation policy.
- Rest recommendation mapping chỉ dùng các level đã khóa; không tạo drowsiness/attention result.

### ViewModel/use case test

- Assistant gửi thành công, duplicate blocked, retry và timeout.
- Pending prompt từ Diagnostics/Cockpit.
- Settings đổi mode/BASE_URL và persist.
- Developer Mode ẩn/hiện simulator/raw metadata.
- Stale state và offline fallback.
- Voice state transitions, cancel, empty transcript, TTS stop.

### Repository/contract test

- Mock và Remote Gateway cùng interface.
- JSON serialization cho mọi request/response DTO.
- Health/session/state/query/action/emergency success và error.
- Idempotency cho event, action confirmation và emergency response.

### Emergency timeline test

Dùng `FakeClock`, không dùng `Thread.sleep`:

- 0→5 giây: `VERIFYING_EVIDENCE`.
- 5→20 giây: `AWAITING_USER_RESPONSE`.
- 20→30 giây: `FINAL_COUNTDOWN`.
- Tại 30 giây: đúng một `SOS_SIMULATED_SENT`.
- Cancel ở mọi state active → `CANCELLED`.
- Recreate ViewModel/Activity không reset deadline.
- Duplicate response không gửi duplicate payload.

### Compose UI/instrumentation

- Cockpit normal/high/critical/stale/offline.
- Assistant keyboard, retry, confirmation, TTS controls.
- Diagnostics empty/P0301/overheat/ask assistant.
- Settings user/developer toggle, endpoint validation.
- Simulator hidden/visible, preset/manual apply/reset/JSON.
- Emergency blocks Back/outside dismiss, action button/voice cancel, sent screen.
- TalkBack semantics, touch target, font scale, dark theme, rotation/process recreation.

### Manual device matrix

| Profile | Kích thước |
|---|---|
| Small portrait | 360×800 |
| Target portrait | 390×844 |
| Large portrait | 412×915 |
| Target landscape | 844×390 |
| Future Automotive | landscape reference only |

Kiểm thử cả permission microphone denied, network off, backend slow, backend invalid JSON, app background/foreground và process kill.

## Security rules

- Không lưu Gemini API key, refresh token dài hạn hoặc secret trong Android.
- Không commit secret vào repo, `BuildConfig`, DataStore hoặc logcat.
- Không log raw transcript, GPS chính xác, evidence nhạy cảm ngoài Developer Mode; production log phải redact.
- Permission microphone/location hỏi theo user action; denial là trạng thái hợp lệ.
- Cleartext chỉ cho debug với allowlist host/local; release chỉ HTTPS.
- Validate BASE_URL: scheme, host, trailing path và không cho endpoint tùy ý ngoài Developer Mode.
- Token/session ngắn hạn nếu phase sau dùng Gemini Live; Android chỉ nhận ephemeral token từ backend.
- Unknown backend action bị bỏ qua; control action phải allowlist; sensitive action cần confirmation.
- `realEmergencyDispatchEnabled` phải false ở code/config/test assertion; không có API cuộc gọi/SMS.
- Không hiển thị camera/DMS hoặc suy diễn tình trạng người lái.

## Acceptance checklist

### Functional

- [ ] Debug APK build/cài trên điện thoại thật.
- [ ] Demo Mode chạy không backend.
- [ ] Remote Mode đổi BASE_URL mà không sửa source/UI.
- [ ] Cockpit, Assistant, Diagnostics, Settings, Developer Simulator hoạt động.
- [ ] Voice input gửi cùng assistant path; TTS đọc/dừng được.
- [ ] Emergency tự chuyển 5/15/10 giây và cancel bằng button/voice.
- [ ] SOS simulated idempotent, không dispatch thật.

### Safety/data

- [ ] Không có attention/drowsiness score.
- [ ] Không kết luận tài xế tỉnh/buồn ngủ/mất tập trung.
- [ ] Thiếu nguồn hiển thị thiếu dữ liệu, không suy diễn.
- [ ] Risk/rest/DTC severity do gateway/backend trả.
- [ ] Raw reason code chỉ Developer Mode.

### Resilience/accessibility

- [ ] Không crash khi offline/timeout/invalid JSON.
- [ ] Không crash khi từ chối microphone/location.
- [ ] Rotation/process recreation giữ emergency deadline.
- [ ] Cockpit không scroll/overlap ở kích thước mục tiêu.
- [ ] Font scale lớn, TalkBack, touch target pass.

## Quality gate commands

Trước mỗi phase gate, Claude/developer phải chạy tối thiểu:

```text
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:lintDebug
```

Trước nghiệm thu APK, chạy thêm instrumentation/Compose tests trên emulator mục tiêu và build release candidate. Tên task cụ thể được ghi trong Android README nếu project dùng managed devices hoặc variant khác.

Evidence lưu trong `reports/` hoặc CI artifact:

- build/lint/test result;
- screenshot Cockpit portrait/landscape/font scale;
- emergency timeline test report;
- dependency/secret scan;
- APK checksum, version name/code và device install log.

## Release security audit

- Giải nén/scan APK và generated resources để tìm `GEMINI`, API key pattern, staging secret, transcript fixture nhạy cảm.
- Xác nhận network security config của release không trust cleartext/local host.
- Xác nhận Developer Mode/raw JSON/reason code không thể mở bằng route/deep link ngoài release policy.
- Xác nhận manifest không có permission ngoài phạm vi; microphone/location phải có lý do và đường denial.
- Xác nhận exported activity/service/receiver tối thiểu; không có background voice service MVP.
- Kiểm tra logging ở release bị tắt/redact và crash report không chứa transcript/location.
- Chạy `:app:analyzeReleaseR8Config` khi bật AGP 9.3 optimization/R8 để kiểm tra keep rules.

## Deliverables cuối

- Android Studio project + debug APK.
- Release APK unsigned hoặc internal-signed nếu có.
- Android README: build/install/run Demo/Remote.
- `adb reverse` và BASE_URL matrix: USB `127.0.0.1`, emulator `10.0.2.2`, LAN, cloud HTTPS.
- API contract/OpenAPI hoặc schema versioned.
- Demo scenarios và script.
- Test report + known limitations.
- Mapping AI Studio → Android.
- Mock vs Remote coverage list.

## Điều kiện dừng phát hành

Không bàn giao APK nếu còn một trong các lỗi:

- emergency reset, duplicate sent hoặc có đường dispatch thật;
- secret/cleartext/debug route lọt release;
- Cockpit che/cắt primary safety information;
- app crash khi permission denied, offline hoặc invalid response;
- test quan trọng không deterministic/flaky chưa có owner;
- contract version không khớp mà Remote Mode vẫn giả thành công.
