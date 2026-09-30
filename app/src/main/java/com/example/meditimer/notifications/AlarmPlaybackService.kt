package com.example.meditimer.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat

class AlarmPlaybackService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable {
        stopPlayback(keepNotification = true)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val medId = intent?.getLongExtra(EXTRA_MED_ID, -1L) ?: return START_NOT_STICKY
        val epochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, -1L)
        val time = intent.getStringExtra(EXTRA_TIME) ?: return START_NOT_STICKY
        val repo = com.example.meditimer.data.MedicationRepository(this)
        val med = repo.getMedication(medId) ?: return START_NOT_STICKY

        if (!med.enabled || repo.isTaken(medId, epochDay, time)) {
            stopSelf()
            return START_NOT_STICKY
        }

        NotificationHelper.ensureChannels(this)
        val notification = NotificationHelper.buildMedicationAlarmNotification(
            this,
            med,
            epochDay,
            time
        )
        startForeground(NotificationHelper.alarmNotificationId(medId, epochDay, time), notification)

        startPlayback()
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, MAX_RING_MILLIS)
        return START_NOT_STICKY
    }

    private fun startPlayback() {
        stopAudioOnly()

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediTimer:MedicationAlarm")
            .apply { acquire(MAX_RING_MILLIS + 5_000L) }

        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmPlaybackService, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()

        vibrator = getSystemService(Vibrator::class.java)
        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 900, 500), 0)
            )
        }
    }

    private fun stopAudioOnly() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        if (wakeLock?.isHeld == true) runCatching { wakeLock?.release() }
        wakeLock = null
    }

    private fun stopPlayback(keepNotification: Boolean) {
        handler.removeCallbacks(stopRunnable)
        stopAudioOnly()
        if (keepNotification) {
            @Suppress("DEPRECATION")
            stopForeground(false)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        stopAudioOnly()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val MAX_RING_MILLIS = 120_000L
        private const val EXTRA_MED_ID = "med_id"
        private const val EXTRA_EPOCH_DAY = "epoch_day"
        private const val EXTRA_TIME = "time"

        fun start(context: Context, medicationId: Long, plannedEpochDay: Long, plannedTime: String) {
            val intent = Intent(context, AlarmPlaybackService::class.java).apply {
                putExtra(EXTRA_MED_ID, medicationId)
                putExtra(EXTRA_EPOCH_DAY, plannedEpochDay)
                putExtra(EXTRA_TIME, plannedTime)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AlarmPlaybackService::class.java)) }
        }
    }
}
