package vn.edu.haui.hvs.safedrive.feature.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.designsystem.SafeDriveTheme
import vn.edu.haui.hvs.safedrive.core.model.VoiceState
import vn.edu.haui.hvs.safedrive.core.testing.FakeTtsController
import vn.edu.haui.hvs.safedrive.core.testing.FakeVoiceController
import vn.edu.haui.hvs.safedrive.domain.repository.TtsState
import vn.edu.haui.hvs.safedrive.domain.usecase.VoiceTurnOutcome
import vn.edu.haui.hvs.safedrive.voice.VoiceUiState

/**
 * Compose UI test using fake controllers per docs/android-mvp-plan/08-claude-prompts.md, Prompt 5,
 * updated for docs/android-mvp-plan/12 W2/W3: mic/STT ([FakeVoiceController]) and TTS
 * ([FakeTtsController]) are independently-owned fakes now, matching the real controllers.
 */
class VoiceOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleState_rendersNoOverlay() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.IDLE))
        val tts = FakeTtsController(TtsState.READY)
        composeRule.setContent {
            SafeDriveTheme { VoiceOverlay(voiceController = voice, ttsController = tts, onCancelProcessing = {}) }
        }
        assert(composeRule.onAllNodesWithText("SafeDrive đang nghe...").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun listeningState_showsTranscriptAndListeningCopy() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.LISTENING, partialTranscript = "kiểm tra tốc độ"))
        val tts = FakeTtsController(TtsState.READY)
        composeRule.setContent {
            SafeDriveTheme { VoiceOverlay(voiceController = voice, ttsController = tts, onCancelProcessing = {}) }
        }
        composeRule.onNodeWithText("SafeDrive đang nghe...").assertIsDisplayed()
        composeRule.onNodeWithText("kiểm tra tốc độ").assertIsDisplayed()
    }

    @Test
    fun listeningState_finishListeningButtonCallsFinishListeningNotCancel() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.LISTENING, partialTranscript = "kiểm tra tốc độ"))
        val tts = FakeTtsController(TtsState.READY)
        composeRule.setContent {
            SafeDriveTheme { VoiceOverlay(voiceController = voice, ttsController = tts, onCancelProcessing = {}) }
        }
        composeRule.onNodeWithText("Kết thúc câu nói").performClick()
        assert(voice.finishListeningCallCount == 1)
        assert(voice.cancelCallCount == 0) // "Kết thúc câu nói" finalizes; it must never discard/cancel (W4.7)
    }

    @Test
    fun errorState_showsErrorMessageNeverAsListening() {
        val voice = FakeVoiceController(
            VoiceUiState(state = VoiceState.ERROR, errorMessage = "Cần quyền microphone để dùng giọng nói"),
        )
        val tts = FakeTtsController(TtsState.READY)
        composeRule.setContent {
            SafeDriveTheme { VoiceOverlay(voiceController = voice, ttsController = tts, onCancelProcessing = {}) }
        }
        composeRule.onNodeWithText("Cần quyền microphone để dùng giọng nói").assertIsDisplayed()
        assert(composeRule.onAllNodesWithText("SafeDrive đang nghe...").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun processingState_cancelButtonCallsOnCancelProcessing() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.PROCESSING))
        val tts = FakeTtsController(TtsState.READY)
        var cancelCount = 0
        composeRule.setContent {
            SafeDriveTheme {
                VoiceOverlay(voiceController = voice, ttsController = tts, onCancelProcessing = { cancelCount++ })
            }
        }
        composeRule.onNodeWithContentDescription("Hủy xử lý").performClick()
        assert(cancelCount == 1)
    }

    @Test
    fun speakingState_isDrivenByTtsControllerNotVoiceController() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.IDLE))
        val tts = FakeTtsController(TtsState.SPEAKING)
        composeRule.setContent {
            SafeDriveTheme { VoiceOverlay(voiceController = voice, ttsController = tts, onCancelProcessing = {}) }
        }
        composeRule.onNodeWithText("SafeDrive đang trả lời").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dừng đọc").performClick()
        assert(tts.stopCallCount == 1)
        assert(voice.cancelCallCount == 0) // TTS ownership is separate from mic/STT ownership (W2.11)
    }

    @Test
    fun closeButton_callsCancelOnCancelProcessingAndStopSpeaking() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.LISTENING))
        val tts = FakeTtsController(TtsState.READY)
        var cancelProcessingCount = 0
        composeRule.setContent {
            SafeDriveTheme {
                VoiceOverlay(voiceController = voice, ttsController = tts, onCancelProcessing = { cancelProcessingCount++ })
            }
        }
        composeRule.onNodeWithContentDescription("Đóng trợ lý thoại").performClick()
        assert(voice.cancelCallCount == 1)
        assert(cancelProcessingCount == 1)
    }

    // --- Remediation item 6: the voice overlay must be able to show the completed voice turn's actual
    // reply/error, not just a generic "Đang xử lý..." spinner (W6.10). ---

    @Test
    fun successfulTurnOutcomeShowsReplyTextAndDismissButton() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.IDLE))
        val tts = FakeTtsController(TtsState.READY)
        val turnOutcome = MutableStateFlow<VoiceTurnOutcome?>(VoiceTurnOutcome.Success("Động cơ đang ở 90 độ C"))
        var dismissCount = 0
        composeRule.setContent {
            SafeDriveTheme {
                VoiceOverlay(
                    voiceController = voice,
                    ttsController = tts,
                    onCancelProcessing = {},
                    turnOutcome = turnOutcome,
                    onDismissTurnOutcome = { dismissCount++ },
                )
            }
        }
        composeRule.onNodeWithText("SafeDrive đã trả lời").assertIsDisplayed()
        composeRule.onNodeWithText("Động cơ đang ở 90 độ C").assertIsDisplayed()
        composeRule.onNodeWithText("Đóng").performClick()
        assert(dismissCount == 1)
    }

    @Test
    fun failureTurnOutcomeShowsErrorMessageAndDismissButton() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.IDLE))
        val tts = FakeTtsController(TtsState.READY)
        val turnOutcome = MutableStateFlow<VoiceTurnOutcome?>(VoiceTurnOutcome.Failure("Mất kết nối mạng. Vui lòng kiểm tra lại."))
        composeRule.setContent {
            SafeDriveTheme {
                VoiceOverlay(
                    voiceController = voice,
                    ttsController = tts,
                    onCancelProcessing = {},
                    turnOutcome = turnOutcome,
                )
            }
        }
        composeRule.onNodeWithText("Mất kết nối mạng. Vui lòng kiểm tra lại.").assertIsDisplayed()
        composeRule.onNodeWithText("Đóng").assertIsDisplayed()
    }

    @Test
    fun processingStateShowsFinalTranscriptWhenPresent() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.PROCESSING, finalTranscript = "kiểm tra tốc độ hiện tại"))
        val tts = FakeTtsController(TtsState.READY)
        composeRule.setContent {
            SafeDriveTheme { VoiceOverlay(voiceController = voice, ttsController = tts, onCancelProcessing = {}) }
        }
        composeRule.onNodeWithText("Đang xử lý yêu cầu...").assertIsDisplayed()
        composeRule.onNodeWithText("kiểm tra tốc độ hiện tại").assertIsDisplayed()
    }

    // --- Independent re-audit follow-up, item 6: the generic "speaking" branch used to take priority
    // over a Success/Failure turnOutcome, hiding the actual reply/error for the entire time TTS was
    // reading it aloud. ---

    @Test
    fun speakingSuccessShowsReplyAndStopReadingButton() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.IDLE))
        val tts = FakeTtsController(TtsState.SPEAKING)
        val turnOutcome = MutableStateFlow<VoiceTurnOutcome?>(VoiceTurnOutcome.Success("Động cơ đang ở 90 độ C"))
        composeRule.setContent {
            SafeDriveTheme {
                VoiceOverlay(
                    voiceController = voice,
                    ttsController = tts,
                    onCancelProcessing = {},
                    turnOutcome = turnOutcome,
                )
            }
        }
        composeRule.onNodeWithText("SafeDrive đang trả lời").assertIsDisplayed()
        composeRule.onNodeWithText("Động cơ đang ở 90 độ C").assertIsDisplayed() // the actual reply, not hidden
        composeRule.onNodeWithContentDescription("Dừng đọc").assertIsDisplayed()
        assert(composeRule.onAllNodesWithText("Đóng").fetchSemanticsNodes().isEmpty()) // no dismiss while speaking
    }

    @Test
    fun stopReadingDuringSpeakingKeepsReplyVisible() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.IDLE))
        val tts = FakeTtsController(TtsState.SPEAKING)
        val turnOutcome = MutableStateFlow<VoiceTurnOutcome?>(VoiceTurnOutcome.Success("Động cơ đang ở 90 độ C"))
        var dismissCount = 0
        composeRule.setContent {
            SafeDriveTheme {
                VoiceOverlay(
                    voiceController = voice,
                    ttsController = tts,
                    onCancelProcessing = {},
                    turnOutcome = turnOutcome,
                    onDismissTurnOutcome = { dismissCount++ },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Dừng đọc").performClick()
        assert(tts.stopCallCount == 1)
        assert(dismissCount == 0) // stopping TTS is not the same action as dismissing the outcome
        // The FakeTtsController is now READY (not SPEAKING) — the reply must still be shown, now with
        // the non-speaking copy and a real dismiss button, until the user explicitly closes it.
        composeRule.onNodeWithText("SafeDrive đã trả lời").assertIsDisplayed()
        composeRule.onNodeWithText("Động cơ đang ở 90 độ C").assertIsDisplayed()
    }

    @Test
    fun speakingFailureShowsRealErrorMessage() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.IDLE))
        val tts = FakeTtsController(TtsState.SPEAKING)
        val turnOutcome = MutableStateFlow<VoiceTurnOutcome?>(VoiceTurnOutcome.Failure("Mất kết nối mạng. Vui lòng kiểm tra lại."))
        composeRule.setContent {
            SafeDriveTheme {
                VoiceOverlay(
                    voiceController = voice,
                    ttsController = tts,
                    onCancelProcessing = {},
                    turnOutcome = turnOutcome,
                )
            }
        }
        composeRule.onNodeWithText("Mất kết nối mạng. Vui lòng kiểm tra lại.").assertIsDisplayed()
        assert(composeRule.onAllNodesWithText("SafeDrive đang trả lời").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun noTurnOutcomeAndIdleMicRendersNothing() {
        val voice = FakeVoiceController(VoiceUiState(state = VoiceState.IDLE))
        val tts = FakeTtsController(TtsState.READY)
        composeRule.setContent {
            SafeDriveTheme {
                VoiceOverlay(
                    voiceController = voice,
                    ttsController = tts,
                    onCancelProcessing = {},
                    turnOutcome = MutableStateFlow(null),
                )
            }
        }
        assert(composeRule.onAllNodesWithText("SafeDrive đã trả lời").fetchSemanticsNodes().isEmpty())
    }
}
