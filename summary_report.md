# SafeDrive — Báo cáo hợp nhất bản demo/nộp bài

## Trạng thái phát hành

- Nhánh phát hành: `main`.
- Product release commit: `17fe087` (`feat(android): finalize crash signal demo and release evidence`).
- Voice, Agent Armor, Vehicle Action Executor, Crash Evidence Fusion, Context Memory,
  Signal Dashboard và Developer Crash Test Panel nằm trong cùng một phiên bản Android.
- Backend production: `https://safedrive-backend-165374511912.asia-southeast1.run.app`.
- Vertex AI đang hoạt động với `vertex_ai/gemini-2.5-flash`.
- APK release: `SafeDrive-demo-unified-17fe087.apk`, SHA-256
  `A1CE5999262ECA41F383C71B6632F9517C3B2A931E94D81D6816576786B44C46`.
- CarSky artifact `safedrive-apk` phiên bản `0.0.4` đang là `latest` và chứa đúng APK release.

## 1. Voice

- Đã hợp nhất Continuous Conversation, xử lý quyền microphone và TTS fallback.
- `forceDisableContinuousModeForTest` chỉ làm luồng unit test ổn định; app thực tế vẫn dùng
  hội thoại liên tục.
- Các thay đổi backend không liên quan từ nhánh Voice đã được loại khỏi lần merge.

## 2. Agent Armor và hành động xe

- LLM chỉ diễn đạt; Safety Risk Engine, Grounding, Action Authority và Emergency Engine quyết định.
- Hành động HVAC/cửa/media đi qua Action Authority, xác nhận và kiểm tra lại trạng thái trước khi
  thực thi.
- Chống replay, chống sửa payload và khóa đề xuất cũ khi context an toàn thay đổi.
- Có executor AAOS/VHAL thật và bài instrumentation kiểm tra HVAC, khóa/mở cửa, media cùng read-back.
- Có Context Memory lưu context, sự kiện, hành động, phản hồi và sở thích với nguồn/TTL/lưu bền.

## 3. Crash Evidence Fusion và SOS

Nguồn bằng chứng được hỗ trợ:

- `VHAL_IMPACT` và `VHAL_AIRBAG`: bằng chứng mạnh, có thể tự xác nhận va chạm.
- `DEVICE_IMU` + `VHAL_SPEED_DROP`: cặp bằng chứng hợp nhất để xác nhận va chạm.
- `HIGH_SPEED`: bối cảnh tốc độ cao từ ngưỡng khoảng 80 km/h.
- `CRITICAL_SENSOR_FAULT`: ít nhất hai cảm biến siêu âm VHAL báo lỗi trong cửa sổ hai giây.

Quy tắc an toàn: `HIGH_SPEED`, lỗi cảm biến hoặc phanh gấp không tự kích hoạt SOS khi đứng riêng,
nhằm tránh báo động giả. Chúng được giữ làm bằng chứng bối cảnh và hiển thị trên màn hình SOS khi
có quyết định va chạm hợp lệ.

Emergency UI hiển thị nguồn bằng chứng cụ thể, trạng thái sáu tín hiệu và đồ thị tốc độ thời gian
thực. Gửi cứu hộ vẫn là `SIMULATION_ONLY`; sản phẩm không tuyên bố đã gọi dịch vụ cứu hộ thật.

## 4. Simulator và khả năng trình diễn

- Signal Dashboard hiển thị telemetry/tín hiệu và lịch sử tốc độ thời gian thực.
- Developer Crash Test Panel có sáu nút bơm tín hiệu qua chính `CrashEvidenceAdapter.injectSignal`.
- Panel ghi rõ tín hiệu nào tự kích hoạt, tín hiệu nào cần kết hợp và tín hiệu nào chỉ làm bối cảnh.
- Vì dùng pipeline fusion thật, panel cho phép trình diễn chính xác trên điện thoại hoặc IVI không
  cung cấp property impact/airbag vật lý.

## 5. Bằng chứng kiểm thử của bản hợp nhất

- Android unit tests: **353/353 passed** (chạy lại không dùng cache).
- Backend tests: **355/355 passed**.
- Android app APK: build thành công.
- Android instrumentation APK: biên dịch thành công; đã sửa wiring của `EmergencyScreenTest` theo
  `VehicleDataSource` mới.
- Live production demo matrix: **13/13 passed**, gồm Vertex AI, grounding DTC, fatigue, HVAC
  confirm/replay protection, stale-action rejection, crash/SOS, cancel, rescue brief và WebSocket.
- APK release đã cài cập nhật thành công lên Xiaomi qua ADB.

## Trạng thái CarSky IVI

- Lần instrumentation gần nhất trên IVI đã đạt `OK (1 test)` cho HVAC/cửa/media thật.
- Artifact release `0.0.4` đã upload lên CarSky và APK cũng đã lưu trong bucket riêng
  `safedrive-carsky-apk-transfer`.
- Tại thời điểm chốt báo cáo, IVI vẫn đang chạy APK cũ có SHA-256 bắt đầu bằng `ccea43a9...`;
  chưa được phép tuyên bố node đang chạy `17fe087` cho đến khi cài APK và kiểm tra lại hash.
- Không thay đổi blueprint 20-node đang `Running (20/20)` chỉ để cập nhật APK; tránh làm gián đoạn
  môi trường demo đang ổn định.

## Phạm vi tuyên bố khi demo

- Có thể trình diễn HVAC/cửa/media thật khi AAOS/VHAL cung cấp property và quyền tương ứng.
- Có thể trình diễn toàn bộ fusion/SOS bằng Developer Crash Test Panel trên mọi thiết bị.
- Chỉ tuyên bố phát hiện va chạm vật lý từ nguồn mà thiết bị thực sự cung cấp; không giả định CarSky
  có impact/airbag/IMU nếu chưa kiểm tra property trên node đó.
