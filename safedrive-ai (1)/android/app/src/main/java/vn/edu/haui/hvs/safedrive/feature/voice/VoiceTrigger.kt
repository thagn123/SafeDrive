package vn.edu.haui.hvs.safedrive.feature.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import vn.edu.haui.hvs.safedrive.voice.VoiceController

/**
 * Requesting the RECORD_AUDIO permission requires an Activity result launcher, which
 * [vn.edu.haui.hvs.safedrive.voice.AndroidSpeechRecognizerController] intentionally does not own
 * (see its class doc). This composable owns the launcher and always calls [VoiceController.startListening]
 * afterward — a manual mic tap is itself the wake gesture, so it goes straight to command capture, no
 * "say Mai ơi first" required. If permission is still denied, the controller itself publishes the
 * `ERROR` state with a clear message, so there is exactly one source of truth for "why voice didn't
 * start." [screen] identifies which surface triggered listening (docs/android-mvp-plan/12 W2.8) for
 * observability.
 */
@Composable
fun rememberVoiceTrigger(voiceController: VoiceController, screen: String): () -> Unit {
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        voiceController.startListening(screen)
    }
    return remember(voiceController, screen) {
        {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
