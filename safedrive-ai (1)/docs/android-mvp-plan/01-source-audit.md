# 01 — Audit source prototype

## Tổng quan kỹ thuật

Prototype hiện tại là app Google AI Studio dạng web:

- React 19, TypeScript 5.8, Vite 6, Tailwind CSS 4, `lucide-react`, Motion.
- Có khai báo `@google/genai` nhưng không có call Gemini thật trong source đã audit.
- Chưa có Retrofit/OkHttp, REST client, WebSocket, persistence hay backend integration.
- State tập trung trong `src/context/SafeDriveContext.tsx`.
- Rule và dữ liệu demo ở `src/data/mock/mockRepository.ts`.
- README web còn yêu cầu `GEMINI_API_KEY`; hướng dẫn Android phải loại bỏ hoàn toàn cách làm này.

## Bản đồ source đã đọc

| Source | Phát hiện | Quyết định cho Android |
|---|---|---|
| `src/App.tsx` | Provider, switch screen, global overlay, confirmation và bottom nav | Giữ làm sơ đồ luồng; viết lại bằng `MainActivity`, `SafeDriveApp`, Navigation Compose và root state renderer |
| `src/context/SafeDriveContext.tsx` | Gom vehicle/risk/chat/settings/voice/SOS/navigation; dùng `setTimeout`/`setInterval` | Tách thành ViewModel, use case, repository và controller; không chuyển Context 1:1 |
| `src/types/safedrive.ts` | Model DTC, vehicle, risk, rest, chat, action, voice, emergency, settings | Dùng làm glossary; đổi sang Kotlin sealed class/data class/enum và thêm `updatedAtMs`, error model, request id |
| `src/data/mock/mockRepository.ts` | Default state, rule risk/rest, DTC P0301/overheat, 8 preset, initial chat | Chuyển thành fixtures của `MockSafeDriveGateway`; policy thật nằm backend/domain contract |
| `src/components/cockpit/*` | Header, status hero, vehicle metrics, signal summary, DTC, voice card, details modal | Giữ hierarchy, copy và visual intent; tách composable nhỏ, responsive bằng WindowSizeClass |
| `src/presentation/AssistantScreen.tsx` + `components/assistant/*` | Chat, quick prompts, TTS toggle, action card, voice overlay | Viết lại Compose; state loading/error/retry phải là state rõ ràng |
| `src/presentation/DiagnosticsScreen.tsx` | Empty state hoặc DTC card, nút hỏi assistant | Giữ flow; severity/recommendation đến từ model gateway |
| `src/presentation/SettingsScreen.tsx` | User settings, permission/signal summary, Developer Mode, endpoint presets | Dùng DataStore; không giả vờ ping thành công; simulator chỉ hiện trong dev |
| `src/presentation/SimulatorScreen.tsx` | Preset và manual telemetry, JSON preview | Giữ toàn bộ scenario làm test fixture; chỉ expose khi Developer Mode |
| `src/components/sos/EmergencyOverlay.tsx` | Overlay full-screen, friendly evidence, 3 bước countdown, simulated flag | Hợp nhất thành một emergency renderer; deadline phải persisted/backend-driven |
| `src/components/sos/SosCountdownCard.tsx` | Timer local 10 giây cũ | Không dùng làm flow thứ hai; loại khỏi production Android plan |
| `src/presentation/SosScreen.tsx` | Màn hình trung gian tự start emergency | Không cho tự kích hoạt do navigation; emergency bắt đầu từ event có evidence |
| `src/components/assistant/VoiceOverlay.tsx` | UI voice states và quick speech giả lập | Giữ UI state; thay browser API bằng Android controller |
| `src/navigation/BottomNavBar.tsx` | 4 tab chính, ẩn khi emergency | Giữ; simulator không phải tab user thường, SOS không phải tab điều hướng |

## Mapping chi tiết AI Studio → Android

| Source prototype | Target Android | Xử lý | Ghi chú triển khai |
|---|---|---|---|
| `src/main.tsx` | `MainActivity.kt` | Viết lại | Activity chỉ host Compose và edge-to-edge |
| `src/App.tsx` | `SafeDriveApp.kt`, `SafeDriveNavHost.kt`, root overlays | Viết lại | Không dùng switch thủ công theo string tab |
| `SafeDriveProvider` | `MainViewModel` + feature ViewModel + repositories | Tách | Không tạo một “God ViewModel” tương đương Context |
| `vehicleState` trong Context | `ObserveVehicleStateUseCase`, `CockpitViewModel` | Tách | StateFlow immutable, có freshness |
| `sendChatMessage()` | `AssistantQueryUseCase`, `SafeDriveGateway.queryAssistant()` | Viết lại | Mock và Remote dùng cùng request/result |
| `performActionType()` | `ActionDispatcher` + action allowlist | Viết lại | Unknown action là no-op; sensitive action cần confirm |
| `speakText()` | `AndroidTextToSpeechController` | Viết lại | Lifecycle-aware, stop/shutdown rõ ràng |
| `triggerWakeWord()`/`submitVoiceQuery()` | `VoiceController`, `VoiceViewModel` | Viết lại | SpeechRecognizer foreground; không timeout giả |
| Emergency timer trong Context | `EmergencyReducer`, `EmergencyRepository`, `EmergencyViewModel` | Viết lại | Absolute deadline + persistence + idempotency |
| `src/types/safedrive.ts` | `core/model/*.kt` | Chuyển đặc tả | Chuẩn hóa nullability, timestamp và enum |
| `mockRepository.ts` fixtures | `data/mock/MockFixtures.kt` | Chuyển dữ liệu | Giữ copy/scenario; policy production không ở client |
| `evaluateRisk()`/`evaluateRestRecommendation()` | Mock fixture evaluator hoặc backend response | Giới hạn | Chỉ dùng Demo; không coi là authority production |
| `ResponsiveCockpitLayout.tsx` | `CockpitScreen.kt`, `CockpitContent.kt` | Viết lại | WindowSizeClass/adaptive slots |
| `StatusHeroCard.tsx` | `StatusHeroCard.kt` | Tham chiếu UI | Render risk/rest từ domain |
| `VehicleOverviewPanel.tsx` | `VehicleMetricsPanel.kt` | Tham chiếu UI | Không hard-code energy/source count |
| `DriverSignalSummary.tsx` | `DriverSignalSummary.kt` | Tham chiếu UI | Thiếu nguồn phải hiển thị unknown |
| `DtcSummaryCard.tsx` | `DtcSummaryCard.kt` | Tham chiếu UI | Điều hướng Diagnostics |
| `AssistantScreen.tsx`/`ChatBubble.tsx` | `AssistantScreen.kt`, `ChatMessageItem.kt` | Viết lại | LazyColumn, keyboard insets, retry |
| `SafetyActionCard.tsx` | `AssistantActionCard.kt` | Viết lại | Allowlist + requiresConfirmation |
| `ConfirmActionDialog.tsx` | `ConfirmActionDialog.kt` | Viết lại | Dialog state do ViewModel sở hữu |
| `DiagnosticsScreen.tsx` | `DiagnosticsScreen.kt` | Viết lại | Severity do gateway cung cấp |
| `SettingsScreen.tsx` | `SettingsScreen.kt`, `PreferencesRepository` | Viết lại | Health thật, DataStore, dev-only controls |
| `SimulatorScreen.tsx` | `SimulatorScreen.kt`, `SimulatorViewModel` | Viết lại | Route chỉ Developer Mode |
| `EmergencyOverlay.tsx` | `EmergencyScreen.kt` | Viết lại | Một renderer duy nhất, chặn Back |
| `SosCountdownCard.tsx` | Không có target | Loại bỏ | Luồng timer cũ trùng lặp |
| `SosScreen.tsx` | Không là nav route user | Loại bỏ/ghép | Emergency chỉ bắt đầu từ event/evidence |
| `VoiceOverlay.tsx` | `VoiceOverlay.kt` | Viết lại | Trạng thái phải phản ánh recorder thật |
| Tailwind/CSS | `core/designsystem` | Viết lại | Semantic color, spacing, typography |
| `lucide-react` icons | Material Symbols hoặc vector drawable | Thay thế | Chốt icon set, content description |
| `DriverStatusCard`, `RiskHeroCard`, `CompactStatusHero` | Không port mặc định | Loại duplicate | Chỉ giữ component đang được layout chính sử dụng |

## Kết luận audit theo nhóm

| Nhóm | Giữ làm đặc tả | Viết lại Android | Loại bỏ | Chuyển backend |
|---|---|---|---|---|
| Visual/copy | hierarchy, màu semantic, nội dung tiếng Việt | toàn bộ Compose UI | duplicate/unused UI | — |
| State | enum và luồng khái niệm | ViewModel/use case/repository | React Context monolith | risk/rest/emergency authority |
| Data | fixture, DTC, scenario | Kotlin model/mapper | attention/drowsiness fields | policy và live vehicle state |
| Voice | state/copy/interaction | SpeechRecognizer + TTS | browser mock/timeouts | Gemini Live phase sau |
| Emergency | 5/15/10 và cancel UX | reducer/deadline/persistence | timer cũ + SOS nav tab | evidence/deadline authority |
| Network | endpoint names/BASE_URL presets | Retrofit/OkHttp | fake ping | production services/WebSocket |

## Những gì được giữ làm đặc tả

- Tên màn hình, hierarchy, copy tiếng Việt, màu sắc/ý định cảnh báo và các preset demo.
- Model khái niệm: vehicle telemetry, indirect driver-support signals, DTC, chat action, voice state, emergency state.
- Scenario IDs: `new_trip`, `over_2h`, `consider_rest`, `rest_recommended`, `insufficient_data`, `user_reported_fatigue`, `overheat`, `crash`.
- Emergency timeline 5/15/10 giây và nút “Tôi vẫn ổn — Hủy SOS”.

## Những gì phải viết lại

- React Context → `MainViewModel`/feature ViewModel + immutable `UiState`.
- Tailwind/CSS → Compose theme, dimensions, typography, semantic colors.
- Browser speech synthesis → `TextToSpeech`; browser/mock voice → `SpeechRecognizer` foreground.
- `setTimeout` assistant → cancellable coroutine/use case với request id và timeout.
- Local timer trong composable/context → deadline absolute, reducer/state machine và persistence.
- `settings.isConnected` giả lập → `ConnectionRepository` gọi `/health` và phân loại offline/stale/no AI/no vehicle.
- Mock rule được hard-code trong Context → gateway trả kết quả theo contract; không để ViewModel tự quyết định safety policy.
- Desktop/web responsive CSS → Compose adaptive layout cho 360×800, 390×844, 412×915 và 844×390.

## Những gì phải loại bỏ

- API key/Gemini setup trong README web và mọi secret client-side.
- `attention_score`, `drowsiness_score`, phần trăm tập trung/buồn ngủ và mọi kết luận “tài xế tỉnh táo/buồn ngủ”.
- SOS UI cũ `SosCountdownCard` và navigation tới SOS như một tab độc lập.
- Hard-coded GPS thật/giả trong UI nếu không có `locationSource` rõ ràng; MVP dùng location simulated và gắn nhãn.
- Logic “test connection thành công” sau `setTimeout`.

## Những gì chuyển sang backend phase sau

- Deterministic risk/rest policy production và DTC severity authority.
- Gemini orchestration, action allowlist, safety fast path và audit event.
- Emergency evidence aggregation, deadline authority, idempotent dispatch adapter.
- WebSocket cockpit stream, vehicle adapters thật, wearable, location và Android Automotive/VHAL.

## Rủi ro/điểm cần chú ý từ prototype

1. Có hai emergency implementation và hai kiểu timer; Android phải có một state machine duy nhất.
2. `startEmergencyFlow()` được gọi khi `crashDetected` đổi và cũng từ `SosScreen`; dễ kích hoạt lặp.
3. `EmergencyOverlay` cho phép `overflow-y-auto`, trái với yêu cầu emergency full-screen không dismiss bằng swipe/back; Android phải khóa back và navigation.
4. `ResponsiveCockpitLayout` có `overflow-hidden` và fixed viewport; cần kiểm thử font scale lớn để không cắt nội dung.
5. `activeSourceCount={3}` đang hard-code trong cockpit; Android phải lấy từ signal availability.
6. `lastSteeringInteractionMs` là timestamp tuyệt đối nhưng model kế hoạch phải chuẩn hóa tên `steeringLastInteractionSeconds` hoặc quy ước rõ milliseconds.
7. Chat mock dựa trên text matching; Android có thể dùng fixture tương đương nhưng response phải cùng schema với remote.
