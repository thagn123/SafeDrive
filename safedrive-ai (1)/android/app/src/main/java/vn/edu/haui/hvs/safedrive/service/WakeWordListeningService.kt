package vn.edu.haui.hvs.safedrive.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import vn.edu.haui.hvs.safedrive.MainActivity
import vn.edu.haui.hvs.safedrive.R
import vn.edu.haui.hvs.safedrive.SafeDriveApplication
import vn.edu.haui.hvs.safedrive.core.model.VoiceState
import vn.edu.haui.hvs.safedrive.voice.AndroidSpeechRecognizerController
import vn.edu.haui.hvs.safedrive.voice.AndroidSpeechRecognizerFactory
import vn.edu.haui.hvs.safedrive.voice.LISTENABLE_VOICE_STATES
import vn.edu.haui.hvs.safedrive.voice.SpeechRecognizerWakeWordDetector
import vn.edu.haui.hvs.safedrive.voice.WakeWordSessionCoordinator

private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "wake_word_listening"
private const val ACTION_DISABLE = "vn.edu.haui.hvs.safedrive.action.DISABLE_WAKE_WORD"

/**
 * Foreground service owning the "Mai ơi" ambient listener
 * ([vn.edu.haui.hvs.safedrive.voice.SpeechRecognizerWakeWordDetector] via
 * [WakeWordSessionCoordinator]) — independent of [MainActivity]'s lifecycle, so ambient listening
 * survives backgrounding/swiping the app away (docs: real background wake-word listening plan). Uses
 * Android's own `SpeechRecognizer` rather than a dedicated low-power engine, since no mature,
 * no-account Android SDK exists as a drop-in replacement today — heavier on battery, but needs no
 * third-party account/API key.
 *
 * Started from `SafeDriveApp`'s `LaunchedEffect(preferences.wakeWordEnabled)` — never from
 * [SafeDriveApplication.onCreate] (Android 12+ forbids starting a foreground service outside a
 * foreground-causing context). Self-stops by observing [SafeDriveApplication.container]'s
 * `appPreferences` directly, so any path that flips `wakeWordEnabled` off (Settings toggle, this
 * service's own notification action) reliably stops it without a separate stop-Intent wired from every
 * call site.
 */
class WakeWordListeningService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var coordinator: WakeWordSessionCoordinator? = null
    private var detector: SpeechRecognizerWakeWordDetector? = null

    private val container get() = (applicationContext as SafeDriveApplication).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(listeningForCommand = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        val newDetector = SpeechRecognizerWakeWordDetector(
            recognizerFactory = AndroidSpeechRecognizerFactory(applicationContext),
            // Last-moment guard against this detector's own self-triggered restarts racing a command
            // session that just started (see SpeechRecognizerWakeWordDetector's own KDoc) -- reads the
            // exact same state set WakeWordSessionCoordinator's reactive stop already uses.
            isCommandCaptureActive = { container.voiceController.state.value.state !in LISTENABLE_VOICE_STATES },
        )
        detector = newDetector
        // Closes the ambient/command mic-ownership race (see AndroidSpeechRecognizerController
        // .onBeforeListen's KDoc): a same-thread synchronous caller (a Compose tap, or this service's own
        // onWakeWordDetected callback) would otherwise create+start a command-capture recognizer before
        // WakeWordSessionCoordinator's purely reactive stop ever gets scheduled to run.
        (container.voiceController as? AndroidSpeechRecognizerController)?.onBeforeListen = { newDetector.stop() }
        val newCoordinator = WakeWordSessionCoordinator(
            voiceController = container.voiceController,
            ttsController = container.ttsController,
            wakeWordDetector = newDetector,
            externalScope = scope,
            // Not fatal to the service: WakeWordSessionCoordinator itself retries after a real error
            // (network blip, transient audio glitch) on a timer -- see its onDetectorFailure KDoc for why
            // that self-heal exists (a real device was observed going silently, permanently deaf without
            // it). This callback is diagnostics-only; logging isn't wired up yet, but a future pass could
            // surface it in the notification text instead of a silent no-op.
            onDetectorError = { },
        )
        coordinator = newCoordinator
        newCoordinator.start()

        container.voiceController.state
            .onEach { updateNotification(listeningForCommand = it.state !in setOf(VoiceState.IDLE, VoiceState.ERROR)) }
            .launchIn(scope)

        scope.launch {
            container.appPreferences.collect { prefs ->
                if (!prefs.wakeWordEnabled) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            scope.launch { container.preferencesRepository.setWakeWordEnabled(false) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (container.voiceController as? AndroidSpeechRecognizerController)?.onBeforeListen = {}
        coordinator?.stop()
        detector?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Lắng nghe \"Mai ơi\"", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(listeningForCommand: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(listeningForCommand))
    }

    private fun buildNotification(listeningForCommand: Boolean): Notification {
        val contentText = if (listeningForCommand) "Đang nghe lệnh của bạn..." else "Nói \"Mai ơi\" để bắt đầu"
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disableIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, WakeWordListeningService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle("SafeDrive đang lắng nghe")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Tắt", disableIntent)
            .build()
    }
}
