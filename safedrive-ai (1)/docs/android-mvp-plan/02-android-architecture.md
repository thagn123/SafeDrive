# 02 — Android architecture

## Luồng phụ thuộc

```text
Compose UI
  → feature ViewModel / MainViewModel
    → Use case
      → domain repository interface
        → MockSafeDriveGateway | RemoteSafeDriveGateway
          → Mock fixtures | Retrofit/OkHttp backend
```

UI chỉ render state và phát event. ViewModel không chứa HTTP, JSON parsing tùy ý, Gemini key, risk policy, DTC severity policy hoặc quyết định dispatch SOS.

## Cấu trúc source đề xuất

Một module `app` trong MVP:

```text
app/src/main/java/.../safedrive/
├── MainActivity.kt
├── SafeDriveApp.kt
├── navigation/
│   ├── AppRoute.kt
│   └── SafeDriveNavHost.kt
├── core/
│   ├── common/            # Result, Clock, DispatcherProvider, error mapping
│   ├── model/             # domain model, enum, sealed state
│   ├── network/           # Retrofit service, OkHttp, serializers, URL validation
│   ├── datastore/         # DataStore keys and settings persistence
│   ├── designsystem/      # colors, typography, spacing, components
│   └── testing/           # fake clock, fixture builders, test dispatchers
├── data/
│   ├── remote/            # DTO, RemoteSafeDriveGateway, API mapper
│   ├── local/             # PreferencesRepository, emergency snapshot
│   ├── mock/              # fixtures, MockSafeDriveGateway
│   └── repository/        # concrete repository composition
├── domain/
│   ├── repository/        # interfaces only
│   └── usecase/            # assistant, cockpit, diagnostics, emergency, settings
├── feature/
│   ├── cockpit/
│   ├── assistant/
│   ├── diagnostics/
│   ├── settings/
│   ├── simulator/
│   └── emergency/
├── vehicle/
│   ├── VehicleDataSource.kt
│   ├── MockVehicleDataSource.kt
│   └── VhalVehicleDataSource.kt # phase sau, compile-safe adapter/skeleton
└── voice/
    ├── VoiceController.kt
    ├── AndroidSpeechRecognizerController.kt
    └── AndroidTextToSpeechController.kt
```

## Interface boundaries

### `SafeDriveGateway`

Chịu session, health, state, assistant, events, action confirmation và emergency resource. Mọi implementation trả domain model hoặc typed `GatewayError`.

### `VehicleDataSource`

Phát `Flow<VehicleState>`; MVP dùng mock source. VHAL phase sau map property xe sang cùng model, không để feature biết VHAL.

### `VoiceController`

Expose state flow `Disabled/Idle/WakeDetected/Listening/Processing/Speaking/Error`, transcript event, start/stop listening, speak/stop speaking. Permission và lifecycle được controller quản lý.

### `PreferencesRepository`

Lưu backend URL, mode, TTS, wake word, developer mode, app flags. Không lưu secret và không log transcript mặc định.

### `EmergencyRepository`

Tạo/đọc/respond/mark-simulated-sent cho emergency. Lưu `emergencyId`, `state`, `deadlineMs`, evidence version và idempotency key để phục hồi process.

## Runtime composition

1. Đọc DataStore.
2. Chọn `Demo` hoặc `Remote` gateway qua một composition root duy nhất.
3. Khởi tạo vehicle source tương ứng.
4. Khởi tạo ViewModel bằng interface, không truyền Retrofit service vào UI.
5. Khi đổi BASE_URL/mode, validate URL, lưu cấu hình và recreate gateway an toàn; không restart app bắt buộc trong Demo.

## State ownership

| State | Owner | Persistence |
|---|---|---|
| Vehicle snapshot | cockpit/use case | cache memory; `updatedAtMs` để đánh dấu stale |
| Chat messages | assistant ViewModel | memory MVP; có thể cache session sau |
| Settings | PreferencesRepository/DataStore | có |
| Voice session | VoiceController + ViewModel | không lưu audio; transcript chỉ memory |
| Emergency | EmergencyRepository + EmergencyViewModel | phải lưu snapshot/deadline |
| Navigation | NavHost | không coi là safety state |

## Dependency rule

- `core.model` không import Android UI/network.
- `domain` không import Retrofit, Compose hoặc Android framework.
- `feature` không parse DTO.
- `data.remote` map DTO ↔ domain tại một chỗ.
- `MockSafeDriveGateway` và `RemoteSafeDriveGateway` phải pass cùng contract tests.
- Không thêm module Gradle riêng trước khi MVP ổn định; chỉ tách module nếu build time hoặc ownership thực sự cần.

## Trách nhiệm và file chính theo package

| Package | Trách nhiệm | File/class tối thiểu |
|---|---|---|
| `navigation` | Route và điều hướng user/dev | `AppRoute`, `SafeDriveNavHost`, `NavigationEvent` |
| `core/common` | Clock, dispatcher, result/error, ID generator | `AppClock`, `DispatcherProvider`, `GatewayResult`, `GatewayError` |
| `core/model` | Domain model dùng chung | `VehicleState`, `RestRecommendation`, `Dtc`, `ChatMessage`, `EmergencySnapshot` |
| `core/network` | Retrofit/OkHttp creation, URL validation, redaction | `SafeDriveApi`, `NetworkModule`, `BaseUrlValidator`, `RedactingLogger` |
| `core/datastore` | Settings và emergency snapshot | `SettingsDataStore`, `EmergencySnapshotStore` |
| `core/designsystem` | Theme/tokens/reusable UI | `SafeDriveTheme`, `SafeDriveColors`, `Dimensions`, `StatusBadge` |
| `core/testing` | Fakes/builders/test dispatcher | `FakeClock`, `TestDispatcherProvider`, `VehicleStateBuilder` |
| `data/mock` | Gateway/data source Demo | `MockSafeDriveGateway`, `MockVehicleDataSource`, `MockFixtures` |
| `data/remote` | DTO, service, mapper, remote gateway | `SafeDriveApi`, `RemoteSafeDriveGateway`, `ApiMappers`, `dto/*` |
| `data/local` | Preferences/emergency persistence | `DataStorePreferencesRepository`, `DataStoreEmergencyRepository` |
| `domain/repository` | Interface ổn định | `SafeDriveGateway`, `VehicleDataSource`, `PreferencesRepository`, `EmergencyRepository` |
| `domain/usecase` | Điều phối hành vi | `ObserveCockpit`, `QueryAssistant`, `ConfirmAction`, `ApplyScenario`, `AdvanceEmergency` |
| `feature/cockpit` | Cockpit state và Compose | `CockpitUiState`, `CockpitAction`, `CockpitViewModel`, `CockpitScreen` |
| `feature/assistant` | Chat/action/confirmation | `AssistantUiState`, `AssistantAction`, `AssistantViewModel`, `AssistantScreen` |
| `feature/diagnostics` | DTC list/detail/prefill | `DiagnosticsUiState`, `DiagnosticsViewModel`, `DiagnosticsScreen` |
| `feature/settings` | User/dev settings và health | `SettingsUiState`, `SettingsViewModel`, `SettingsScreen` |
| `feature/simulator` | Preset/manual telemetry | `SimulatorUiState`, `SimulatorViewModel`, `SimulatorScreen` |
| `feature/emergency` | Reducer/deadline/full-screen renderer | `EmergencyReducer`, `EmergencyViewModel`, `EmergencyScreen` |
| `vehicle` | Adapter boundary | `VehicleDataSource`, `MockVehicleDataSource`; VHAL phase sau |
| `voice` | Recognition/TTS lifecycle | `VoiceController`, `AndroidSpeechRecognizerController`, `AndroidTextToSpeechController` |

## Contract UI state/event

Mỗi feature phải có:

```text
UiState: immutable data class, đủ loading/content/empty/error/offline/stale
UiAction: sealed interface cho user/system input
UiEffect: one-shot navigation/dialog/snackbar nếu thật sự cần
ViewModel: reduce action → gọi use case → cập nhật StateFlow
Screen: collectAsStateWithLifecycle → render → phát UiAction
```

Không lưu navigation event lâu dài trong `UiState`; không phát snackbar/dialog bằng boolean không được consume. Request bất đồng bộ cần `requestId` hoặc generation để response cũ không ghi đè state mới.

## Build variants và cấu hình

| Variant | Gateway mặc định | Network | Developer tools | Logging |
|---|---|---|---|---|
| `debug` | Demo | HTTP local được allowlist; HTTPS staging | Có, nhưng mặc định tắt | Redacted debug |
| `release` | Demo hoặc HTTPS staging theo quyết định phát hành | Chỉ HTTPS | Không route trực tiếp; Developer Mode bị khóa theo release policy | Không transcript/GPS/raw payload |

BASE_URL là runtime setting chỉ trong debug/internal. Nếu release cần Remote Mode, URL phải đến từ build config đã ký hoặc remote config an toàn, không cho user nhập host tùy ý.
