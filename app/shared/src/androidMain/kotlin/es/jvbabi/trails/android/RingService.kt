package es.jvbabi.trails.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import es.jvbabi.trails.data.repository.NotificationRepository
import es.jvbabi.trails.domain.repository.DeviceRepository
import es.jvbabi.trails.shared.compose.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RingService: Service(), KoinComponent {
    private val deviceRepository: DeviceRepository by inject()

    private var ringtone: Ringtone? = null
    private var causedByDeviceName: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRinging(intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "")
            // Triggered by the notification's "stop" action. Route through the
            // repository so the server (and therefore every other device / the
            // web UI) is told the ring stopped, and the RingingActivity closes.
            ACTION_USER_STOP -> deviceRepository.stopRinging()
            ACTION_STOP  -> stopRinging()
        }
        return START_STICKY
    }

    private fun startRinging(deviceName: String) {
        causedByDeviceName = deviceName
        setVolume(80)
        playRingtone()
        // The RingingActivity is launched via the notification's full-screen
        // intent, NOT a direct startActivity(): Android 14+ blocks background
        // activity launches from a foreground service (see the BAL restriction),
        // and the full-screen intent is the sanctioned exception to that.
        startForeground(30, buildCallNotification())
    }

    private fun stopRinging() {
        ringtone?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Sets the alarm stream volume. The ring deliberately plays on the alarm
     * stream rather than the ring stream: alarms are let through by Do Not
     * Disturb, and changing their volume never touches the DND state. Changing
     * the ring stream volume while DND is active may switch the ringer mode,
     * which Android rejects with a SecurityException ("Not allowed to change
     * Do Not Disturb state") unless the app holds notification policy access.
     */
    private fun setVolume(percent: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        try {
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                (max * percent.coerceIn(0, 100)) / 100,
                0
            )
        } catch (e: SecurityException) {
            // Keep ringing at the current volume rather than crashing the service.
            Log.w(TAG, "Could not set the alarm volume", e)
        }
    }

    private fun playRingtone() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(applicationContext, uri).also {
            it.audioAttributes = AudioAttributes.Builder()
                // USAGE_ALARM routes the sound to the alarm stream, which
                // Do Not Disturb lets through, see setVolume().
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.isLooping = true
            }
            it.play()
        }
    }

    /**
     * Explicit intent that opens the RingingActivity. An explicit component
     * target (rather than an implicit action) is required for a reliable
     * full-screen intent — implicit PendingIntents are discouraged and may be
     * silently dropped. The class is referenced by name because RingingActivity
     * lives in the app module, which this shared module does not depend on.
     */
    private fun showRingingIntent(): Intent =
        Intent().apply {
            setClassName(packageName, RINGING_ACTIVITY_CLASS)
            putExtra(EXTRA_DEVICE_NAME, causedByDeviceName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    private fun buildCallNotification(): Notification {
        val title = getString(R.string.notification_ring_title)
        val stopLabel = getString(R.string.notification_ring_stop)

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val fullScreenIntent = PendingIntent.getActivity(this, 0, showRingingIntent(), pendingFlags)

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RingService::class.java).apply { action = ACTION_USER_STOP },
            pendingFlags,
        )

        return NotificationCompat.Builder(this, NotificationRepository.RING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(causedByDeviceName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(R.drawable.ic_launcher_monochrome, stopLabel, stopIntent)
            .build()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_START = "ACTION_START_RING"
        const val ACTION_STOP  = "ACTION_STOP_RING"
        const val ACTION_USER_STOP = "ACTION_USER_STOP_RING"
        const val EXTRA_DEVICE_NAME = "caused_by_device_name"
        private const val TAG = "RingService"
        private const val RINGING_ACTIVITY_CLASS = "es.jvbabi.trails.RingingActivity"
    }
}
