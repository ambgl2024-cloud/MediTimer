package com.example.meditimer.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.meditimer.data.ActiveCountdown
import com.example.meditimer.data.MedicationRepository
import kotlin.math.max

/**
 * Audio foreground service used only while at least one post-intake countdown is active.
 *
 * The previous implementation used a special-use foreground service driven by the main
 * looper. Some Android/OEM power managers still froze that work after the display turned
 * off. This version deliberately uses the mediaPlayback foreground-service type, a dedicated
 * HandlerThread, and a PARTIAL_WAKE_LOCK. The final exact AlarmManager alarm remains as an
 * independent fallback.
 */
class CountdownService : Service() {
    private lateinit var repo: MedicationRepository
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val lastTickMinute = mutableMapOf<Long, Long>()
    private var wakeLock: PowerManager.WakeLock? = null

    private val runner = object : Runnable {
        override fun run() = runCountdownLoop()
    }

    override fun onCreate() {
        super.onCreate()
        repo = MedicationRepository(this)
        NotificationHelper.ensureChannels(this)

        ServiceCompat.startForeground(
            this,
            FOREGROUND_NOTIFICATION_ID,
            NotificationHelper.buildCountdownActiveNotification(this, repo.getCountdowns()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )

        acquireWakeLock()
        workerThread = HandlerThread("MediTimerCountdownAudio").apply { start() }
        worker = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val now = System.currentTimeMillis()
        repo.getCountdowns().forEach { c ->
            // Do not replay ticks that occurred before a service restart.
            lastTickMinute.putIfAbsent(c.id, elapsedWholeMinutes(c, now))
        }
        worker.removeCallbacks(runner)
        worker.post(runner)
        return START_STICKY
    }

    override fun onDestroy() {
        if (::worker.isInitialized) worker.removeCallbacksAndMessages(null)
        if (::workerThread.isInitialized) workerThread.quitSafely()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runCountdownLoop() {
        val now = System.currentTimeMillis()
        val countdowns = repo.getCountdowns()

        if (countdowns.isEmpty()) {
            stopEngine()
            return
        }

        var nextWakeAt = Long.MAX_VALUE

        countdowns.forEach { countdown ->
            if (now >= countdown.endMillis) {
                finishCountdown(countdown)
                return@forEach
            }

            val elapsedMinute = elapsedWholeMinutes(countdown, now)
            val last = lastTickMinute[countdown.id] ?: elapsedMinute.also {
                lastTickMinute[countdown.id] = it
            }

            if (elapsedMinute > last && elapsedMinute >= 1L) {
                // One and only one short countdown beep at each completed minute.
                SoundHelper.playMinuteTick(this)
                lastTickMinute[countdown.id] = elapsedMinute
            }

            val nextMinuteBoundary = countdown.startMillis + (elapsedMinute + 1L) * 60_000L
            nextWakeAt = minOf(nextWakeAt, nextMinuteBoundary, countdown.endMillis)
        }

        val remaining = repo.getCountdowns()
        if (remaining.isEmpty()) {
            stopEngine()
            return
        }

        runCatching {
            NotificationManagerCompat.from(this).notify(
                FOREGROUND_NOTIFICATION_ID,
                NotificationHelper.buildCountdownActiveNotification(this, remaining)
            )
        }

        // Wake a fraction after the exact wall-clock boundary so integer minute division has
        // definitely advanced. The wake lock keeps the CPU available while the display is off.
        val delay = max(100L, nextWakeAt - System.currentTimeMillis() + 80L)
        worker.postDelayed(runner, delay.coerceAtMost(60_000L))
    }

    private fun finishCountdown(countdown: ActiveCountdown) {
        if (repo.getCountdown(countdown.id) == null) return
        repo.removeCountdown(countdown.id)
        lastTickMinute.remove(countdown.id)
        Scheduler.cancelCountdownFinishFallback(this, countdown.id)
        SoundHelper.playCountdownFinished(this)
        NotificationHelper.showCountdownFinished(
            this,
            countdown.medicationName,
            countdown.note,
            countdown.id
        )
    }

    private fun stopEngine() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun elapsedWholeMinutes(c: ActiveCountdown, now: Long): Long =
        ((now - c.startMillis).coerceAtLeast(0L) / 60_000L)

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MediTimer:CountdownAudio"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 8701

        fun start(context: Context) {
            val intent = Intent(context, CountdownService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
