# Graph Report - .  (2026-08-01)

## Corpus Check
- 328 files · ~232,041 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2389 nodes · 5380 edges · 141 communities (118 shown, 23 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 746 edges (avg confidence: 0.66)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Community 0
- Community 1
- Community 2
- Community 3
- Community 4
- Community 5
- Community 6
- Community 7
- Community 8
- Community 9
- Community 10
- Community 11
- Community 12
- Community 13
- Community 14
- Community 15
- Community 16
- Community 17
- Community 18
- Community 19
- Community 20
- Community 21
- Community 22
- Community 23
- Community 24
- Community 25
- Community 26
- Community 27
- Community 28
- Community 29
- Community 30
- Community 31
- Community 32
- Community 33
- Community 34
- Community 35
- Community 36
- Community 37
- Community 38
- Community 39
- Community 40
- Community 41
- Community 42
- Community 43
- Community 44
- Community 45
- Community 46
- Community 47
- Community 48
- Community 49
- Community 50
- Community 51
- Community 52
- Community 53
- Community 54
- Community 55
- Community 56
- Community 57
- Community 58
- Community 59
- Community 60
- Community 61
- Community 62
- Community 63
- Community 64
- Community 65
- Community 66
- Community 67
- Community 68
- Community 69
- Community 70
- Community 71
- Community 72
- Community 73
- Community 74
- Community 75
- Community 76
- Community 77
- Community 78
- Community 79
- Community 80
- Community 81
- Community 82
- Community 83
- Community 84
- Community 85
- Community 86
- Community 87
- Community 88
- Community 89
- Community 90
- Community 91
- Community 92
- Community 93
- Community 94
- Community 95
- Community 96
- Community 97
- Community 98
- Community 99
- Community 100
- Community 101
- Community 102
- Community 103
- Community 104
- Community 105
- Community 106
- Community 107
- Community 108
- Community 109
- Community 110
- Community 111
- Community 112
- Community 113
- Community 114
- Community 115
- Community 116
- Community 117
- Community 118
- Community 119
- Community 120
- Community 121
- Community 122
- Community 123
- Community 124
- Community 125
- Community 126
- Community 127
- Community 128
- Community 136

## God Nodes (most connected - your core abstractions)
1. `SignalRegistry` - 109 edges
2. `CanonicalSignal` - 79 edges
3. `GatewayResult` - 64 edges
4. `AssistantTurnCoordinatorTest` - 59 edges
5. `create_app()` - 56 edges
6. `MobileSessionStore` - 55 edges
7. `LatestStateManager` - 50 edges
8. `VoiceAssistantCoordinatorTest` - 49 edges
9. `FakeTtsController` - 45 edges
10. `RollingWindowManager` - 44 edges

## Surprising Connections (you probably didn't know these)
- `SignalBatchLike` --inherits--> `Protocol`  [EXTRACTED]
  backend AI/safedrive-ai-backend/app/services/signal_ingestion.py → safedrive-ai (1)/android/app/src/main/java/vn/edu/haui/hvs/safedrive/core/common/GatewayError.kt
- `SafeDriveTheme()` --calls--> `statusColorsFor()`  [INFERRED]
  safedrive-ai (1)/android/app/src/main/java/vn/edu/haui/hvs/safedrive/core/designsystem/SafeDriveTheme.kt → safedrive-ai (1)/android/app/src/main/java/vn/edu/haui/hvs/safedrive/core/designsystem/SafeDriveColors.kt
- `custom_error_endpoint()` --calls--> `ApiError`  [EXTRACTED]
  backend AI/safedrive-ai-backend/tests/test_error_handlers.py → backend AI/safedrive-ai-backend/app/api/errors.py
- `DummyPayload` --uses--> `ApiError`  [INFERRED]
  backend AI/safedrive-ai-backend/tests/test_error_handlers.py → backend AI/safedrive-ai-backend/app/api/errors.py
- `IssuedAction` --uses--> `MobileApiError`  [INFERRED]
  backend AI/safedrive-ai-backend/app/mobile/session_store.py → backend AI/safedrive-ai-backend/app/api/errors.py

## Import Cycles
- None detected.

## Communities (141 total, 23 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.06
Nodes (19): FakeMainThreadExecutor, FakePlatformSpeechRecognizer, FakeSpeechRecognizerFactory, RecognitionListener, ActiveSession, AndroidMainThreadExecutor, AndroidSpeechRecognizerController, AndroidSpeechRecognizerFactory (+11 more)

### Community 1 - "Community 1"
Cohesion: 0.08
Nodes (46): CanonicalSignal, Server domain model with received_at, SignalRegistry, ComponentState, Freshness, LatestStateManager, OutOfOrderError, ProjectedComponentState (+38 more)

### Community 2 - "Community 2"
Cohesion: 0.06
Nodes (11): CompletableDeferred, kotlinx, EmergencySnapshot, HealthCapabilities, HealthStatus, SafetyInvariantsTest, ChatMessage, StateFlow (+3 more)

### Community 3 - "Community 3"
Cohesion: 0.05
Nodes (29): Bundle, ComponentActivity, NavHostController, SectionCard(), SettingsScreen(), SettingsToggleRow(), BaseUrlPreset, Checking (+21 more)

### Community 4 - "Community 4"
Cohesion: 0.06
Nodes (31): toDto(), AssistantContextDto, AssistantQueryRequestDto, AssistantQueryResponseDto, ChatMessageDto, EmergencyResponseRequestDto, EmergencySnapshotDto, EvidenceItemDto (+23 more)

### Community 5 - "Community 5"
Cohesion: 0.05
Nodes (32): EmergencyState, EmergencyScreenTest, EmergencySnapshot, AppClock, SystemAppClock, EvidenceItem, RescueBrief, RescueDispatchReceipt (+24 more)

### Community 6 - "Community 6"
Cohesion: 0.06
Nodes (22): EmergencyResponseType, CANCEL_SOS, NO_RESPONSE, USER_OK, FakeEmergencyRepository, EmergencySnapshot, EvidenceItem, StateFlow (+14 more)

### Community 7 - "Community 7"
Cohesion: 0.09
Nodes (25): datetime, Acquire the rolling-store transaction boundary before any live mutation., Build changed entries while the transaction lock is held., Append signal if feature rolling window policy is configured.          Return Tr, Legacy append wrapper for backwards compatibility., Remove signals older than ttl_seconds from the left. Return remaining count., Return signals that occurred within the last duration_seconds., RollingWindow (+17 more)

### Community 8 - "Community 8"
Cohesion: 0.11
Nodes (31): DriverSupportSignals, SafeDriveAction, VehicleState, AssistantPlan, ContextAwareAssistant, _fmt_temp(), AssistantQueryRequest, SafeDriveAction (+23 more)

### Community 9 - "Community 9"
Cohesion: 0.06
Nodes (27): ClosedFloatingPointRange, JsonPreviewDialog(), Modifier, ScenarioPreset, ScenarioPresetCard(), DtcOption(), DtcSelectionRow(), LabeledSlider() (+19 more)

### Community 10 - "Community 10"
Cohesion: 0.04
Nodes (47): autoprefixer, dotenv, esbuild, express, @google/genai, lucide-react, motion, react (+39 more)

### Community 11 - "Community 11"
Cohesion: 0.10
Nodes (4): AppPreferences, AssistantTurnCoordinatorTest, StateFlow, vn

### Community 12 - "Community 12"
Cohesion: 0.08
Nodes (36): _api_key_header, Depends, Request, Verify X-SafeDrive-Key while publishing a required OpenAPI security scheme., verify_api_key(), ApiError, ErrorDetail, ErrorEnvelope (+28 more)

### Community 13 - "Community 13"
Cohesion: 0.09
Nodes (33): AcceleratorPedalSignalInput, BaseCanonicalSignalInput, BrakePedalSignalInput, CrashSignalInput, DoorOpenSignalInput, DriverEyeClosureSignalInput, DriverGazeSignalInput, DriverHeadPoseSignalInput (+25 more)

### Community 14 - "Community 14"
Cohesion: 0.08
Nodes (32): ASGIApp, get_application_logger(), Return application request logger instance., extract_authoritative_request_id(), generate_request_id(), get_request_id(), Return the request ID for the current async execution context., Return the current request ID or raise RuntimeError if uninitialized. (+24 more)

### Community 15 - "Community 15"
Cohesion: 0.10
Nodes (33): build_lifespan(), create_app(), FastAPI, Settings, Create an isolated FastAPI application instance., _reset_application_state(), Exception, LogCaptureFixture (+25 more)

### Community 16 - "Community 16"
Cohesion: 0.10
Nodes (20): Job, AssistantTurnSource, QUICK_PROMPT, RETRY, TEXT, VOICE, AssistantTurnState, Cancelled (+12 more)

### Community 17 - "Community 17"
Cohesion: 0.12
Nodes (24): SignalBatchRequest, IngestionError, datetime, ValueError, Idempotent business outcome; transport metadata is added per response., Validate and stage a batch before one non-awaiting in-memory commit., Canonical JSON hash that recursively sorts objects and preserves arrays., SignalBatchBusinessResult (+16 more)

### Community 18 - "Community 18"
Cohesion: 0.18
Nodes (30): App-facing compatibility routes for the existing Android Remote gateway., ActionConfirmRequest, ActionConfirmResponse, AssistantContext, AssistantQueryRequest, AssistantQueryResponse, ChatMessage, Dtc (+22 more)

### Community 19 - "Community 19"
Cohesion: 0.10
Nodes (21): R, Failure, GatewayResult, T, map(), onFailure(), onSuccess(), Success (+13 more)

### Community 20 - "Community 20"
Cohesion: 0.13
Nodes (14): Canonicalizer, IngestionResult, LRUCache, LRUCacheNode, datetime, OrderedDict, Hash client-owned fields using deterministic canonical JSON., Classify signal without mutating dedup cache (side-effect free). (+6 more)

### Community 21 - "Community 21"
Cohesion: 0.17
Nodes (8): VoiceOverlayTest, SafeDriveTheme(), FakeVoiceController, Flow, StateFlow, StateFlow, VoiceOverlay(), VoiceUiState

### Community 22 - "Community 22"
Cohesion: 0.22
Nodes (25): BaseSignalValue, BooleanStatusValue, CrashValue, DMSProbabilityValue, DTCValue, GearValue, GPSValue, HVACFanValue (+17 more)

### Community 23 - "Community 23"
Cohesion: 0.09
Nodes (14): Application, CoroutineDispatcher, DefaultDispatcherProvider, DispatcherProvider, GatewayProvider, EmergencyResponseRequest, EmergencySnapshot, StateEnvelope (+6 more)

### Community 24 - "Community 24"
Cohesion: 0.17
Nodes (14): MobileApiError, Typed error for the app-facing mobile-compatibility routes.      ApiError's hand, MobileSessionStore, ActionConfirmRequest, AssistantQueryRequest, EmergencyResponseRequest, EmergencySnapshot, EventAccepted (+6 more)

### Community 25 - "Community 25"
Cohesion: 0.17
Nodes (9): InMemoryConversationRepository, StateFlow, AssistantQueryUseCase, CachedSession, ResolvedSession, SessionCoordinator, fakeProvider(), CoroutineScope (+1 more)

### Community 26 - "Community 26"
Cohesion: 0.12
Nodes (12): Domain, ErrorEnvelopeDto, ActionConfirmRequest, AssistantQueryRequest, EmergencyResponseRequest, EmergencySnapshot, EventAccepted, Response (+4 more)

### Community 27 - "Community 27"
Cohesion: 0.13
Nodes (18): CompactAppHeader(), CompactAppHeaderProps, DriverSignalSummary(), DriverSignalSummaryProps, DriverStatusCard(), DriverStatusCardProps, DriverSupportDetailsModal(), DriverSupportDetailsModalProps (+10 more)

### Community 28 - "Community 28"
Cohesion: 0.10
Nodes (19): AssistantUiAction, AssistantUiEffect, AssistantUiState, CancelPendingAction, CancelTurn, ComposerChanged, ConfirmPendingAction, ExecuteAction (+11 more)

### Community 29 - "Community 29"
Cohesion: 0.16
Nodes (10): EmergencyReducer, EmergencySnapshot, DataStoreEmergencyRepositoryTest, DataStore, FakeClock, Preferences, FakePreferencesDataStore, DataStore (+2 more)

### Community 30 - "Community 30"
Cohesion: 0.14
Nodes (13): EmergencyGateway, ActionConfirmRequest, AssistantQueryRequest, CoroutineScope, EmergencyResponseRequest, EmergencySnapshot, EventAccepted, FakeClock (+5 more)

### Community 31 - "Community 31"
Cohesion: 0.13
Nodes (6): Dispatcher, MockResponse, RecordedRequest, FakeSafeDriveBackendDispatcher, MockWebServer, RemoteSafeDriveGatewayErrorMappingTest

### Community 32 - "Community 32"
Cohesion: 0.12
Nodes (24): accept_event(), confirm_action(), get_emergency(), get_mobile_session_store(), get_mobile_state(), ActionConfirmRequest, alias, AssistantQueryRequest (+16 more)

### Community 33 - "Community 33"
Cohesion: 0.13
Nodes (17): Path, Settings, MonkeyPatch, test_default_registry_path_works_outside_repository_cwd(), test_environment_log_level_applied_during_lifespan(), test_environment_variable_loading(), test_explicit_settings_passed_to_create_app_published_during_lifespan(), test_health_200_and_ready_503_on_configuration_failure() (+9 more)

### Community 34 - "Community 34"
Cohesion: 0.11
Nodes (14): FakeTtsController, SharedFlow, StateFlow, SharedFlow, StateFlow, TtsController, TtsState, ERROR (+6 more)

### Community 35 - "Community 35"
Cohesion: 0.10
Nodes (20): RescueLocation, AssistantQueryResult, ChatMessage, DriverSupportSignals, Dtc, EmergencySnapshot, EventAccepted, EvidenceItem (+12 more)

### Community 36 - "Community 36"
Cohesion: 0.15
Nodes (11): CockpitContentTest, SystemConnectionStatus, vn, DriverSupportSignals, Dtc, RestRecommendation, RiskAssessment, ScenarioPreset (+3 more)

### Community 37 - "Community 37"
Cohesion: 0.11
Nodes (15): Configuration, Conflict, GatewayError, Offline, Protocol, Server, Timeout, Unauthorized (+7 more)

### Community 38 - "Community 38"
Cohesion: 0.33
Nodes (3): MutableStateFlow, RecordingGateway, SessionCoordinatorTest

### Community 39 - "Community 39"
Cohesion: 0.19
Nodes (13): App(), MainLayout(), VoiceOverlay(), ResponsiveCockpitLayout(), EmergencyOverlay(), useSafeDrive(), BottomNavBar(), AssistantScreen() (+5 more)

### Community 40 - "Community 40"
Cohesion: 0.19
Nodes (12): Compatibility imports for callers that used the former API-layer location., IdempotencyNode, IdempotencyStore, is_valid_idempotency_key(), OrderedDict, Validate a bounded ASCII idempotency key without normalizing it., Bounded in-memory LRU storing immutable business outcomes., Read a live result without mutating LRU state. (+4 more)

### Community 41 - "Community 41"
Cohesion: 0.20
Nodes (16): RestRecommendation, RiskAssessment, Deterministic Safety Core for the mobile compatibility path.  Rules are intentio, A small policy engine for the MVP's demonstrated safety scenarios., SafetyEvidence, SafetyRiskEngine, evaluate(), make_request() (+8 more)

### Community 42 - "Community 42"
Cohesion: 0.16
Nodes (4): AssistantTurnMetrics, AssistantTurnMetricsRecorder, StateFlow, AssistantTurnMetricsRecorderTest

### Community 43 - "Community 43"
Cohesion: 0.17
Nodes (15): get_settings(), Any, Return a Settings instance without triggering module import side-effects., initialize_application_services(), Build shared services from explicit settings or load default Settings., _rolling_window_prune_loop(), Any, Path (+7 more)

### Community 44 - "Community 44"
Cohesion: 0.22
Nodes (6): SafeDriveAction, CockpitSnapshot, AssistantViewModelTest, fakeGatewayProvider(), CoroutineScope, StateFlow

### Community 45 - "Community 45"
Cohesion: 0.17
Nodes (15): CompactStatusHeroProps, JsonPreviewModal(), JsonPreviewModalProps, ScenarioPresetCardProps, SafeDriveContextType, AppSettings, ChatSender, EmergencyState (+7 more)

### Community 46 - "Community 46"
Cohesion: 0.11
Nodes (18): DOM, DOM.Iterable, ES2022, compilerOptions, allowImportingTsExtensions, allowJs, experimentalDecorators, isolatedModules (+10 more)

### Community 47 - "Community 47"
Cohesion: 0.12
Nodes (16): SystemConnectionStatus, paletteForConnectionStatus(), paletteForSeverity(), SafeDriveStatusColors, statusColorsFor(), StatusPalette, Modifier, StatusBadge() (+8 more)

### Community 48 - "Community 48"
Cohesion: 0.16
Nodes (5): FakePreferencesRepository, StateFlow, StateFlow, PendingPromptCoordinator, DiagnosticsViewModelTest

### Community 49 - "Community 49"
Cohesion: 0.19
Nodes (15): configure_logging(), get_safedrive_owned_handlers(), JSONFormatter, Formats application log records as single-line JSON strings without raw log mess, Return all StreamHandlers attached to 'app' logger marked as owned by SafeDrive., Idempotently configure structured JSON logging on the dedicated 'app' logger nam, create_security_test_app(), FastAPI (+7 more)

### Community 50 - "Community 50"
Cohesion: 0.21
Nodes (15): ScenarioPresetCard(), SafeDriveContext, SafeDriveProvider(), DEFAULT_APP_SETTINGS, DEFAULT_DRIVER_SIGNALS, DEFAULT_VEHICLE_STATE, DTC_OVERHEAT, DTC_P0301 (+7 more)

### Community 51 - "Community 51"
Cohesion: 0.17
Nodes (6): ActionConfirmRequest, EmergencyResponseRequest, StartSessionRequest, SafeDriveActionDto, ConfigurationErrorGatewayTest, T

### Community 52 - "Community 52"
Cohesion: 0.20
Nodes (12): Compact, simulated-only handoff for the MVP rescue demonstration., Receipt from the in-memory mock rescue gateway, never a real dispatch., RescueBrief, RescueDispatchReceipt, RescueLocation, RescueBrief, RescueDispatchReceipt, Simulation-only rescue brief construction and gateway receipt handling. (+4 more)

### Community 53 - "Community 53"
Cohesion: 0.15
Nodes (8): PassengerResponse, driverSupportSignalsFixture(), DriverSupportSignals, VehicleState, vn, vehicleStateFixture(), MockWebServer, RemoteSafeDriveGatewayContractTest

### Community 54 - "Community 54"
Cohesion: 0.22
Nodes (4): IdGenerator, UuidIdGenerator, MockPolicyEvaluator, MockSafeDriveGatewayTest

### Community 55 - "Community 55"
Cohesion: 0.20
Nodes (12): CockpitContent(), LandscapeCockpitContent(), PortraitCockpitContent(), StaleBanner(), CockpitUiState, Content, Loading, DtcSummaryCard() (+4 more)

### Community 56 - "Community 56"
Cohesion: 0.15
Nodes (14): get_state(), alias, Depends, JSONResponse, max_length, min_length, pattern, Query (+6 more)

### Community 57 - "Community 57"
Cohesion: 0.27
Nodes (14): SignalQuality, SignalSource, canonicalizer(), create_signal(), Any, registry(), test_canonicalizer_accepts_valid(), test_canonicalizer_deduplication() (+6 more)

### Community 58 - "Community 58"
Cohesion: 0.23
Nodes (7): AssistantScreenTest, AssistantHeader(), AssistantScreen(), Composer(), QuickPromptsRow(), ConfirmActionDialog(), SafeDriveAction

### Community 59 - "Community 59"
Cohesion: 0.16
Nodes (8): CockpitScreen(), CockpitViewModel, StateFlow, ViewModel, DriverSupportDetailsDialog(), DriverSupportSignals, RestRecommendation, CockpitViewModelTest

### Community 60 - "Community 60"
Cohesion: 0.14
Nodes (11): Content, DiagnosticsUiEffect, DiagnosticsUiState, Loading, NavigateToAssistant, NavigateToSimulator, DiagnosticsViewModel, Dtc (+3 more)

### Community 62 - "Community 62"
Cohesion: 0.22
Nodes (10): ChatBubble(), ChatBubbleProps, ChatMetadata(), ChatMetadataProps, SafetyActionCard(), SafetyActionCardProps, ConfirmActionDialog(), ConfirmActionDialogProps (+2 more)

### Community 63 - "Community 63"
Cohesion: 0.23
Nodes (10): generate_fixtures(), main(), Path, Generate contract fixtures into an explicit, non-destructive destination., Any, Path, test_fixture_generator_is_non_destructive_and_validates(), test_fixtures() (+2 more)

### Community 64 - "Community 64"
Cohesion: 0.26
Nodes (11): health(), HealthResponse, BaseModel, datetime, Request, Response, Report process liveness with request correlation metadata., Report whether all shared application services initialized successfully. (+3 more)

### Community 65 - "Community 65"
Cohesion: 0.41
Nodes (8): SpeedSignalInput, MisnamedUtc, payload(), datetime, test_metadata_twenty_keys_pass_and_twenty_one_fail(), test_state_projection_uses_injected_clock_exactly(), test_utc_offset_is_enforced_and_normalized(), timedelta

### Community 66 - "Community 66"
Cohesion: 0.24
Nodes (10): create_test_app(), custom_error_endpoint(), dummy_endpoint(), DummyPayload, BaseModel, FastAPI, test_api_error_handler(), test_starlette_http_exception_handlers_404_and_405() (+2 more)

### Community 67 - "Community 67"
Cohesion: 0.30
Nodes (11): get_base_payload(), Any, test_all_25_signal_types_end_to_end_validation(), test_dms_and_passenger_signals_require_dms_demo_profile(), test_passenger_posture_and_head_position_use_string_schema(), test_pedal_values_enforce_range_0_100(), test_received_at_rejected_in_input(), test_simulated_dms_passenger_signals_require_simulated_true() (+3 more)

### Community 68 - "Community 68"
Cohesion: 0.17
Nodes (11): ChatSender, SAFEDRIVE, USER, ConfidenceLevel, HIGH, LOW, MEDIUM, PassengerResponse (+3 more)

### Community 69 - "Community 69"
Cohesion: 0.24
Nodes (5): AssistantContext, AssistantQueryRequest, EventAccepted, StateEnvelope, StateUpdateRequest

### Community 70 - "Community 70"
Cohesion: 0.29
Nodes (5): DriverSupportSignals, Dtc, ScenarioPreset, VehicleState, MockFixtures

### Community 71 - "Community 71"
Cohesion: 0.24
Nodes (6): EmergencyResponseRequest, EmergencySnapshot, StateEnvelope, StateUpdateRequest, vn, MockSafeDriveGateway

### Community 72 - "Community 72"
Cohesion: 0.27
Nodes (6): Failure, StateFlow, Success, VoiceAssistantCoordinator, VoiceTurnOutcome, VoiceTurnOwner

### Community 73 - "Community 73"
Cohesion: 0.20
Nodes (4): Flow, StateFlow, VoiceController, VoiceInputEvent

### Community 75 - "Community 75"
Cohesion: 0.25
Nodes (6): RiskHeroCardProps, EmptyState(), EmptyStateProps, RiskBadge(), RiskBadgeProps, RiskLevel

### Community 76 - "Community 76"
Cohesion: 0.44
Nodes (8): load_spec(), parameter_contract(), Any, Response, speed_signal(), test_live_http_bodies_validate_against_pinned_openapi(), test_runtime_openapi_matches_manual_implemented_route_surface(), validate_live_response()

### Community 77 - "Community 77"
Cohesion: 0.47
Nodes (8): get_test_settings(), make_speed_signal(), Any, Settings, test_authentication_scenarios(), test_payload_too_large_rejection(), test_post_signals_and_get_state_happy_path(), test_validation_and_partition_errors()

### Community 78 - "Community 78"
Cohesion: 0.25
Nodes (5): Interceptor, Retrofit, NetworkModule, Response, RedactingLoggingInterceptor

### Community 79 - "Community 79"
Cohesion: 0.22
Nodes (5): SessionInfo, StartSessionRequest, StartSessionRequest, StartSessionRequest, StartSessionRequest

### Community 80 - "Community 80"
Cohesion: 0.22
Nodes (3): DataStorePreferencesRepository, Keys, Flow

### Community 81 - "Community 81"
Cohesion: 0.28
Nodes (5): DriverSupportSignals, ScenarioPreset, StateFlow, VehicleState, VehicleDataSource

### Community 82 - "Community 82"
Cohesion: 0.28
Nodes (5): DriverSupportSignals, ScenarioPreset, StateFlow, VehicleState, MockVehicleDataSource

### Community 83 - "Community 83"
Cohesion: 0.28
Nodes (5): AndroidTextToSpeechController, SharedFlow, StateFlow, PendingUtterance, TextToSpeech

### Community 85 - "Community 85"
Cohesion: 0.39
Nodes (7): ImageVector, formatDrivingTime(), formatTemp(), Modifier, VehicleState, MetricTile(), VehicleMetricsPanel()

### Community 86 - "Community 86"
Cohesion: 0.25
Nodes (8): EmergencyState, AWAITING_USER_RESPONSE, CANCELLED, CANDIDATE_DETECTED, FINAL_COUNTDOWN, IDLE, SOS_SIMULATED_SENT, VERIFYING_EVIDENCE

### Community 87 - "Community 87"
Cohesion: 0.25
Nodes (6): SimulatedLatencyProfile, MS_100, MS_2000, MS_500, NONE, TIMEOUT

### Community 88 - "Community 88"
Cohesion: 0.25
Nodes (8): VoiceState, DISABLED, ERROR, IDLE, LISTENING, PROCESSING, SPEAKING, WAKE_WORD_DETECTED

### Community 90 - "Community 90"
Cohesion: 0.36
Nodes (4): Flow, SystemConnectionStatus, vn, ObserveCockpitUseCase

### Community 92 - "Community 92"
Cohesion: 0.29
Nodes (7): ActionType, NONE, OPEN_DIAGNOSTICS, SET_HVAC_TEMPERATURE, SHOW_WARNING, START_SOS_COUNTDOWN, SUGGEST_REST_STOP

### Community 93 - "Community 93"
Cohesion: 0.29
Nodes (3): BackendMode, DEMO, REMOTE

### Community 94 - "Community 94"
Cohesion: 0.29
Nodes (4): ActionConfirmResult, ActionConfirmRequest, ActionConfirmRequest, SafeDriveAction

### Community 96 - "Community 96"
Cohesion: 0.38
Nodes (4): CompactVoiceStatusProps, VoiceAssistantStatusCard(), VoiceAssistantStatusCardProps, VoiceAssistantState

### Community 97 - "Community 97"
Cohesion: 0.38
Nodes (4): DtcSummaryCard(), DtcSummaryCardProps, PriorityAlertProps, DtcItem

### Community 98 - "Community 98"
Cohesion: 0.33
Nodes (5): MonkeyPatch, Shared SignalRegistry loaded from the canonical config for unit tests., Ensure default test environment profile so tests don't require external producti, set_default_test_environment(), test_registry()

### Community 99 - "Community 99"
Cohesion: 0.53
Nodes (5): MatrixRow, parse_matrix_content(), MonkeyPatch, test_matrix_parser_rejects_malformed_rows(), test_task_status_matrix_self_contained_and_exact_match()

### Community 100 - "Community 100"
Cohesion: 0.40
Nodes (3): Description, MainDispatcherRule, TestWatcher

### Community 101 - "Community 101"
Cohesion: 0.33
Nodes (6): RestRecommendationLevel, CONSIDER_REST, INSUFFICIENT_DATA, MONITOR, NORMAL, REST_RECOMMENDED

### Community 102 - "Community 102"
Cohesion: 0.33
Nodes (6): SystemConnectionStatus, NO_AI_SERVICE, NO_VEHICLE_DATA, NORMAL, OFFLINE, STALE_DATA

### Community 103 - "Community 103"
Cohesion: 0.40
Nodes (4): DriverSupportSignals, RestRecommendation, RiskAssessment, VehicleState

### Community 104 - "Community 104"
Cohesion: 0.53
Nodes (5): CockpitHeader(), ConnectionChip(), DeveloperSimulatorChip(), Modifier, SystemConnectionStatus

### Community 105 - "Community 105"
Cohesion: 0.40
Nodes (5): DriverSignalSummaryCard(), DriverSupportSignals, Modifier, VehicleState, SignalItem

### Community 106 - "Community 106"
Cohesion: 0.33
Nodes (5): DriverSupportSignals, Modifier, RestRecommendation, RiskAssessment, StatusHeroCard()

### Community 108 - "Community 108"
Cohesion: 0.40
Nodes (5): SafeDriveEventType, CONNECTION_CHANGED, SCENARIO_APPLIED, USER_REPORTED_FATIGUE, VOICE_ERROR

### Community 109 - "Community 109"
Cohesion: 0.40
Nodes (5): ConnectionChanged, EventPayload, ScenarioApplied, UserReportedFatigue, VoiceError

### Community 110 - "Community 110"
Cohesion: 0.60
Nodes (4): DiagnosticsContent(), DiagnosticsScreen(), DtcCard(), Dtc

### Community 114 - "Community 114"
Cohesion: 0.50
Nodes (4): StateSource, MOCK, PHONE_SIMULATOR, VEHICLE_ADAPTER

### Community 115 - "Community 115"
Cohesion: 0.50
Nodes (3): AssistantActionCard(), Modifier, SafeDriveAction

### Community 116 - "Community 116"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **193 isolated node(s):** `safedrive-ai-backend`, `Timeout`, `Offline`, `Unauthorized`, `Unsupported` (+188 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **23 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `GatewayError` connect `Community 37` to `Community 3`, `Community 90`, `Community 16`, `Community 25`, `Community 26`?**
  _High betweenness centrality (0.362) - this node is a cross-community bridge._
- **Why does `Protocol` connect `Community 37` to `Community 17`?**
  _High betweenness centrality (0.348) - this node is a cross-community bridge._
- **Why does `SignalBatchLike` connect `Community 17` to `Community 1`, `Community 37`, `Community 7`, `Community 40`, `Community 13`, `Community 20`?**
  _High betweenness centrality (0.348) - this node is a cross-community bridge._
- **Are the 41 inferred relationships involving `SignalRegistry` (e.g. with `ApplicationServices` and `Canonicalizer`) actually correct?**
  _`SignalRegistry` has 41 INFERRED edges - model-reasoned connections that need verification._
- **Are the 27 inferred relationships involving `CanonicalSignal` (e.g. with `Canonicalizer` and `IngestionResult`) actually correct?**
  _`CanonicalSignal` has 27 INFERRED edges - model-reasoned connections that need verification._
- **What connects `safedrive-ai-backend`, `Timeout`, `Offline` to the rest of the system?**
  _193 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.06022282445046673 - nodes in this community are weakly interconnected._