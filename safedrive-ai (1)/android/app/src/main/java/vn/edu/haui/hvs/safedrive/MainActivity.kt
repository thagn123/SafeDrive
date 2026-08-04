package vn.edu.haui.hvs.safedrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import vn.edu.haui.hvs.safedrive.core.designsystem.SafeDriveTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as SafeDriveApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SafeDriveTheme {
                SafeDriveApp(container = container)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Cancels only an in-progress command capture/reply — never the ambient "Mai ơi" wake-word
        // listener, which lives in WakeWordListeningService and is intentionally independent of this
        // Activity's lifecycle (docs/android-mvp-plan/05-voice-emergency.md, "Voice acceptance"; 12 W3.9).
        container.voiceController.cancel()
        container.ttsController.stop()
    }
}
