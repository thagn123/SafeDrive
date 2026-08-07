package vn.edu.haui.hvs.safedrive.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import vn.edu.haui.hvs.safedrive.voice.VoiceController

/**
 * Requesting the RECORD_AUDIO permission requires checking permission first.
 * If already granted, calls [VoiceController.startListening] directly without relying on permission launcher callback.
 */
@Composable
fun rememberVoiceTrigger(voiceController: VoiceController, screen: String): () -> Unit {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            voiceController.startListening(screen)
        }
    }
    return remember(voiceController, screen, context) {
        {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                voiceController.startListening(screen)
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
