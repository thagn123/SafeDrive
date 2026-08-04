package vn.edu.haui.hvs.safedrive

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.common.DefaultDispatcherProvider
import vn.edu.haui.hvs.safedrive.core.common.DispatcherProvider
import vn.edu.haui.hvs.safedrive.core.common.IdGenerator
import vn.edu.haui.hvs.safedrive.core.common.SystemAppClock
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.ChatMessage
import vn.edu.haui.hvs.safedrive.core.model.ChatSender
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem
import vn.edu.haui.hvs.safedrive.core.model.HealthStatus
import vn.edu.haui.hvs.safedrive.core.model.PassengerResponse
import vn.edu.haui.hvs.safedrive.core.model.Severity
import vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetricsRecorder
import vn.edu.haui.hvs.safedrive.data.local.DataStoreEmergencyRepository
import vn.edu.haui.hvs.safedrive.data.local.DataStorePreferencesRepository
import vn.edu.haui.hvs.safedrive.data.local.InMemoryConversationRepository
import vn.edu.haui.hvs.safedrive.core.network.NetworkModule
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.data.mock.MockPolicyEvaluator
import vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway
import vn.edu.haui.hvs.safedrive.data.remote.ConfigurationErrorGateway
import vn.edu.haui.hvs.safedrive.data.remote.ModeAwareEmergencyRepository
import vn.edu.haui.hvs.safedrive.data.remote.AssistantSocketClient
import vn.edu.haui.hvs.safedrive.data.remote.RemoteSafeDriveGateway
import vn.edu.haui.hvs.safedrive.data.remote.SafeDriveApi
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.EmergencyRepository
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.domain.repository.PreferencesRepository
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.TtsController
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleDataSource
import vn.edu.haui.hvs.safedrive.domain.usecase.AssistantQueryUseCase
import vn.edu.haui.hvs.safedrive.domain.usecase.AssistantTurnCoordinator
import vn.edu.haui.hvs.safedrive.domain.usecase.ConfirmActionUseCase
import vn.edu.haui.hvs.safedrive.domain.usecase.ObserveCockpitUseCase
import vn.edu.haui.hvs.safedrive.domain.usecase.PendingPromptCoordinator
import vn.edu.haui.hvs.safedrive.domain.usecase.SessionCoordinator
import vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator
import vn.edu.haui.hvs.safedrive.feature.emergency.EmergencyReducer
import vn.edu.haui.hvs.safedrive.vehicle.MockVehicleDataSource
import vn.edu.haui.hvs.safedrive.voice.AndroidSpeechRecognizerController
import vn.edu.haui.hvs.safedrive.voice.AndroidTextToSpeechController
import vn.edu.haui.hvs.safedrive.voice.VoiceController

private val Context.settingsDataStore by preferencesDataStore(name = "safedrive_settings")
private val Context.emergencyDataStore by preferencesDataStore(name = "safedrive_emergency")

private const val EMERGENCY_TICK_MS = 200L

/** Minimum gap between two Safety Guardian TTS announcements that are not a genuine severity escalation
 * — see the guardian block in [SafeDriveContainer.init] for why this exists: a live device was observed
 * with the guardian re-speaking roughly once a second, because its "same signature never repeats" dedup
 * resets the instant the level ever ticks back to LOW even momentarily, and re-fires on the next tick with
 * a technically-different (but not meaningfully different) title/message. Each repeat forced the ambient
 * wake-word listener to stop and restart (it never listens while TTS is speaking), making the assistant
 * effectively unable to hear "Mai ơi" for as long as the flicker continued. */
private const val GUARDIAN_TTS_COOLDOWN_MS = 30_000L

/**
 * Single composition root (see docs/android-mvp-plan/02-android-architecture.md, "Runtime composition").
 * Screens/ViewModels only ever see the interfaces exposed here — never a concrete Retrofit service or
 * DataStore instance — so Demo/Remote mode switches without any `if (demoMode)` in a composable.
 */
class SafeDriveContainer(context: Context, applicationScope: CoroutineScope) {

    val clock: AppClock = SystemAppClock()
    val idGenerator: IdGenerator = UuidIdGenerator()
    val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider()

    val fixtures = MockFixtures(clock)
    private val policyEvaluator = MockPolicyEvaluator(clock)

    val vehicleDataSource: VehicleDataSource = MockVehicleDataSource(clock, fixtures)

    private val mockGateway: SafeDriveGateway = MockSafeDriveGateway(
        clock = clock,
        idGenerator = idGenerator,
        fixtures = fixtures,
        policyEvaluator = policyEvaluator,
        latencyProfileProvider = { appPreferences.value.developerLatencyProfile },
    )

    val preferencesRepository: PreferencesRepository = DataStorePreferencesRepository(
        dataStore = context.applicationContext.settingsDataStore,
        allowCleartext = BuildConfig.ALLOW_CLEARTEXT_DEBUG,
    )

    val appPreferences: StateFlow<AppPreferences> = preferencesRepository.preferences
        .stateIn(applicationScope, SharingStarted.Eagerly, AppPreferences())

    @Volatile
    private var cachedRemote: Pair<String, SafeDriveGateway>? = null
    private val remoteGatewayLock = Any()

    /**
     * Builds (and caches, keyed by BASE_URL) a [RemoteSafeDriveGateway] on first use in Remote mode.
     * `allowCleartext` comes from the same `BuildConfig.ALLOW_CLEARTEXT_DEBUG` flag
     * [preferencesRepository]'s URL validation already uses, so a release build can never end up
     * with a cleartext client even if a stale `http://` URL was persisted from a debug build.
     *
     * Double-checked locking around [cachedRemote]'s read-then-write (remediation item 2/3): the
     * fast, uncontended read outside the lock keeps the common case allocation-free, while the
     * `synchronized` re-check-then-write prevents two threads racing on a cold cache (e.g. Compose
     * Main resolving a session while the voice pipeline's application-scope coroutine does the same)
     * from each constructing and briefly "winning" a different [RemoteSafeDriveGateway]/Retrofit
     * instance for the same BASE_URL — which [SessionCoordinator] then relies on never happening, since
     * it pairs a cached session id with whichever gateway instance [forPreferences] hands back.
     */
    private fun remoteGatewayFor(baseUrl: String): SafeDriveGateway {
        cachedRemote?.let { (url, gateway) -> if (url == baseUrl) return gateway }
        synchronized(remoteGatewayLock) {
            cachedRemote?.let { (url, gateway) -> if (url == baseUrl) return gateway }
            val client = NetworkModule.createOkHttpClient(allowCleartext = BuildConfig.ALLOW_CLEARTEXT_DEBUG)
            val retrofit = NetworkModule.buildRetrofit(baseUrl, client)
            val socketClient = AssistantSocketClient(client, baseUrl)
            val gateway = RemoteSafeDriveGateway(retrofit.create(SafeDriveApi::class.java), socketClient)
            cachedRemote = baseUrl to gateway
            return gateway
        }
    }

    /**
     * Never falls back to Mock for Remote Mode (docs/android-mvp-plan/12 W5.6): Remote + blank
     * BASE_URL returns [ConfigurationErrorGateway], which fails every call with a typed
     * `GatewayError.Configuration` instead of silently behaving like Demo Mode.
     */
    val gatewayProvider: GatewayProvider = object : GatewayProvider {
        override fun current(): SafeDriveGateway = forPreferences(appPreferences.value)

        override fun forPreferences(prefs: AppPreferences): SafeDriveGateway = when (prefs.backendMode) {
            BackendMode.DEMO -> mockGateway
            BackendMode.REMOTE -> if (prefs.baseUrl.isBlank()) {
                ConfigurationErrorGateway("REMOTE_BASE_URL_MISSING")
            } else {
                remoteGatewayFor(prefs.baseUrl)
            }
        }
    }

    val sessionCoordinator = SessionCoordinator(
        gatewayProvider = gatewayProvider,
        appPreferences = appPreferences,
        idGenerator = idGenerator,
        clock = clock,
        appVersion = BuildConfig.VERSION_NAME,
    )

    private val localEmergencyRepository: EmergencyRepository = DataStoreEmergencyRepository(
        dataStore = context.applicationContext.emergencyDataStore,
        clock = clock,
        idGenerator = idGenerator,
        reducer = EmergencyReducer(),
    )

    /** Demo keeps the local reducer; Remote Mode renders backend-authoritative emergency snapshots. */
    val emergencyRepository = ModeAwareEmergencyRepository(
        localRepository = localEmergencyRepository,
        sessionCoordinator = sessionCoordinator,
        appPreferences = appPreferences,
        clock = clock,
        idGenerator = idGenerator,
        externalScope = applicationScope,
    )

    val observeCockpitUseCase = ObserveCockpitUseCase(
        vehicleDataSource = vehicleDataSource,
        sessionCoordinator = sessionCoordinator,
        appPreferences = appPreferences,
        idGenerator = idGenerator,
        clock = clock,
        remoteEmergencySnapshotSink = emergencyRepository,
    )

    /** Last known backend health/capabilities, updated only from Settings' explicit "Kiểm tra sức
     * khỏe backend" (the app never polls health automatically). Used to fail-fast a query when the
     * backend has explicitly reported `assistant=false` (docs/android-mvp-plan/12 W5.10). */
    private val _lastHealthStatus = MutableStateFlow<HealthStatus?>(null)
    val lastHealthStatus: StateFlow<HealthStatus?> = _lastHealthStatus.asStateFlow()

    fun recordHealthStatus(status: HealthStatus) {
        _lastHealthStatus.value = status
    }

    /**
     * Single application-scoped subscription to [observeCockpitUseCase]. Cockpit, Assistant and
     * Diagnostics all read from this same hot [StateFlow] instead of each independently
     * re-subscribing (which would otherwise call `updateVehicleState` once per subscriber).
     */
    val cockpitSnapshot: StateFlow<CockpitSnapshot?> = observeCockpitUseCase()
        .stateIn(applicationScope, SharingStarted.Eagerly, null)

    val assistantQueryUseCase = AssistantQueryUseCase(
        sessionCoordinator = sessionCoordinator,
        clock = clock,
    )

    val confirmActionUseCase = ConfirmActionUseCase(
        sessionCoordinator = sessionCoordinator,
        idGenerator = idGenerator,
    )

    val pendingPromptCoordinator = PendingPromptCoordinator()

    /**
     * Platform `android.speech.tts.TextToSpeech` engine, application-scoped (docs/android-mvp-plan/12
     * W3). Owns only TTS lifecycle — never mic/STT (see [voiceController]). A sherpa-onnx + Piper
     * on-device neural replacement was attempted here previously but crashed the app natively on the
     * test device and was removed; see `09_MVP_HANDOFF.md` §2 for the full investigation before
     * attempting this swap again.
     */
    val ttsController: TtsController = AndroidTextToSpeechController(context.applicationContext, clock)

    /**
     * Application-scoped conversation store + single turn coordinator (docs/android-mvp-plan/12 §4).
     * Shared by text/quick-prompt (Assistant tab) and voice — so history and single-flight state
     * survive Assistant's ViewModel being recreated and are identical regardless of input source.
     */
    val conversationRepository = InMemoryConversationRepository(initialMessages = fixtures.initialChatMessages())

    /** Redacted (numbers only, never transcript/body — W4.10) latency recorder, logged via
     * [android.util.Log.d] and surfaced in Developer Mode (docs/android-mvp-plan/12 W4.1/W4.2). */
    val assistantTurnMetricsRecorder = AssistantTurnMetricsRecorder(
        logger = { message -> android.util.Log.d("SafeDriveLatency", message) },
    )

    val assistantTurnCoordinator = AssistantTurnCoordinator(
        conversationRepository = conversationRepository,
        assistantQueryUseCase = assistantQueryUseCase,
        cockpitSnapshot = cockpitSnapshot,
        appPreferences = appPreferences,
        ttsController = ttsController,
        metricsRecorder = assistantTurnMetricsRecorder,
        lastHealthStatus = lastHealthStatus,
        idGenerator = idGenerator,
        clock = clock,
        externalScope = applicationScope,
        // Redacted (requestId/generation/source/exception type only — never transcript, reply text or
        // exception.message, which could itself echo request/response content — independent re-audit
        // follow-up, second pass) diagnostic trail for exceptions this class deliberately swallows
        // rather than propagating (a buggy onStarted callback, a metrics/TTS side-effect failure).
        logger = { message -> android.util.Log.w("SafeDriveTurn", message) },
    )

    /**
     * Owns ONLY mic/STT lifecycle (docs/android-mvp-plan/12 W2) — no assistant/TTS calls. Final
     * transcripts are routed by [voiceAssistantCoordinator].
     */
    val voiceController: VoiceController = AndroidSpeechRecognizerController(
        context = context.applicationContext,
        preferencesRepository = preferencesRepository,
        clock = clock,
        externalScope = applicationScope,
    )

    val voiceAssistantCoordinator = VoiceAssistantCoordinator(
        voiceController = voiceController,
        assistantTurnCoordinator = assistantTurnCoordinator,
        emergencyRepository = emergencyRepository,
        externalScope = applicationScope,
    )

    init {
        voiceAssistantCoordinator.start()

        // A session belongs to exactly one (mode, baseUrl) pair; switching either invalidates the
        // cached session id and cancels any in-flight assistant turn, so nothing keeps running
        // against the gateway the user just switched away from (docs/android-mvp-plan/12 W5.7). Also
        // clears the cached health/capability status: it describes the *previous* gateway, and
        // leaving it in place would let a stale `assistant=false` from the old backend wrongly block
        // (or a stale `assistant=true` wrongly allow) queries against the newly-selected one.
        applicationScope.launch {
            var previous = appPreferences.value
            appPreferences.collect { current ->
                if (current.backendMode != previous.backendMode || current.baseUrl != previous.baseUrl) {
                    sessionCoordinator.invalidate()
                    assistantTurnCoordinator.cancelCurrent()
                    _lastHealthStatus.value = null
                }
                previous = current
            }
        }

        // Evidence rule: a single signal never starts an emergency on its own — crashDetected is the
        // primary signal and needs at least one supporting signal (05-voice-emergency.md,
        // "Evidence rule").
        applicationScope.launch {
            vehicleDataSource.vehicleState.collect { state ->
                if (appPreferences.value.backendMode != BackendMode.DEMO) return@collect
                if (!state.crashDetected) return@collect
                val active = emergencyRepository.activeSnapshot.value
                if (active != null && active.state != EmergencyState.IDLE && active.state != EmergencyState.CANCELLED) {
                    return@collect
                }
                val evidence = buildList {
                    add(EvidenceItem("crash_detected", "Phát hiện va chạm gia tốc lớn", clock.nowMs()))
                    if (state.passengerResponse == PassengerResponse.NO_RESPONSE) {
                        add(EvidenceItem("passenger_no_response", "Không nhận được phản hồi", clock.nowMs()))
                    }
                    if (state.driverSeatOccupied == true) {
                        add(EvidenceItem("seat_occupied", "Cảm biến ghế lái ghi nhận có người", clock.nowMs()))
                    }
                }
                if (evidence.size < 2) return@collect // single signal is insufficient to auto-trigger
                emergencyRepository.startCandidate(evidence)
            }
        }

        // Safety Guardian proactive warning: whenever the risk assessment escalates to MEDIUM or
        // worse, the assistant announces the harmful factors instead of waiting to be asked — both as
        // a chat notice (visible whenever Assistant is opened) and spoken immediately regardless of
        // which screen is showing. Suppressed while the full-screen Emergency flow is actively
        // engaging the user (05-voice-emergency.md) since that screen already delivers the strongest
        // possible warning and demands a response; re-arms once the level drops back to LOW so a later
        // re-escalation (even to the same title/message) warns again.
        applicationScope.launch {
            var lastWarned: Triple<Severity, String, String>? = null
            var lastSpokenTtsAtMs: Long? = null
            var lastSpokenTtsLevel: Severity? = null
            cockpitSnapshot.collect { snapshot ->
                val risk = snapshot?.riskAssessment ?: return@collect
                if (risk.level == Severity.LOW) {
                    lastWarned = null
                    return@collect
                }
                val signature = Triple(risk.level, risk.title, risk.message)
                if (signature == lastWarned) return@collect
                val active = emergencyRepository.activeSnapshot.value
                if (active != null && active.state != EmergencyState.IDLE && active.state != EmergencyState.CANCELLED) {
                    return@collect
                }
                lastWarned = signature
                val prefix = when (risk.level) {
                    Severity.MEDIUM -> "Cảnh báo, cần theo dõi:"
                    Severity.HIGH -> "Cảnh báo mức cao:"
                    Severity.CRITICAL -> "Cảnh báo khẩn cấp:"
                    Severity.LOW -> return@collect
                }
                val text = "$prefix ${risk.title}. ${risk.message}"
                conversationRepository.appendSystemNotice(
                    ChatMessage(
                        id = idGenerator.next("msg_guardian"),
                        sender = ChatSender.SAFEDRIVE,
                        text = text,
                        timestampMs = clock.nowMs(),
                        risk = risk,
                        route = "proactive_safety_guardian",
                    ),
                )
                // TTS gets its own, coarser gate on top of the chat notice's signature dedup above (a live
                // device was observed re-announcing roughly once a second: the signature dedup resets the
                // instant the level so much as touches LOW for one tick, and the very next MEDIUM+ tick
                // counts as "new" again even though nothing meaningfully changed -- each repeat forced the
                // ambient "Mai ơi" listener to stop and restart, since it never listens while TTS speaks,
                // making wake-word detection effectively unusable for as long as the flicker continued).
                // A genuine escalation (severity strictly higher than whatever was last actually spoken)
                // always speaks immediately regardless of cooldown -- only same-or-lower-severity repeats
                // are throttled.
                val now = clock.nowMs()
                val escalated = lastSpokenTtsLevel == null || risk.level.ordinal > lastSpokenTtsLevel!!.ordinal
                val cooldownElapsed = lastSpokenTtsAtMs == null || now - lastSpokenTtsAtMs!! >= GUARDIAN_TTS_COOLDOWN_MS
                if (appPreferences.value.ttsEnabled && (escalated || cooldownElapsed)) {
                    ttsController.speak(text, utteranceId = idGenerator.next("tts_guardian"))
                    lastSpokenTtsAtMs = now
                    lastSpokenTtsLevel = risk.level
                }
            }
        }

        // Advances the reducer (including "catch up" through multiple expired deadlines after
        // process recreation); a no-op while there is no active emergency.
        applicationScope.launch {
            while (true) {
                emergencyRepository.tick()
                delay(EMERGENCY_TICK_MS)
            }
        }
    }
}
