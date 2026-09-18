package com.example.meditimer.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.meditimer.data.ActiveCountdown
import com.example.meditimer.data.MedicationRepository
import kotlin.math.max

/**
 * Foreground service used only while one or more post-intake countdowns are active.
 *
 * A foreground service + partial wake lock is intentional here: Android Doze can throttle
 * one-minute AlarmManager events when the screen is off. Keeping this short-lived service
 * active makes the minute cue reliable even with the display locked, while the final exact
 * alarm remains scheduled as a fallback in case the process is killed.
 */
class CountdownService : Service() {
    private lateinit var repo: MedicationRepository
    private val handler = Handler(Looper.getMainLooper())
    private val lastTickMinute = mutableMapOf<Long, Long>()
    private var wakeLock: PowerManager.WakeLock? = null

    private val runner = object : Runnable {
        override fun run() {
            runCountdownLoop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = MedicationRepository(this)
        NotificationHelper.ensureChannels(this)

        val all = repo.getCountdowns()
        startForeground(
            FOREGROUND_NOTIFICATION_ID,
            NotificationHelper.buildCountdownActiveNotification(this, all)
        )
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Initialise newly added countdowns without replaying minute cues that occurred before
        // the service was started/restarted.
        val now = System.currentTimeMillis()
        repo.getCountdowns().forEach { c ->
            lastTickMinute.putIfAbsent(c.id, elapsedWholeMinutes(c, now))
        }
        handler.removeCallbacks(runner)
        handler.post(runner)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(runner)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runCountdownLoop() {
        val now = System.currentTimeMillis()
        val countdowns = repo.getCountdowns()

        if (countdowns.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
                // Exactly one simple tick even if Android delayed execution for more than a minute.
                SoundHelper.playMinuteTick(this)
                lastTickMinute[countdown.id] = elapsedMinute
            }

            val nextMinuteBoundary = countdown.startMillis + (elapsedMinute + 1L) * 60_000L
            nextWakeAt = minOf(nextWakeAt, nextMinuteBoundary, countdown.endMillis)
        }

        val remaining = repo.getCountdowns()
        if (remaining.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        runCatching {
            NotificationManagerCompat.from(this).notify(
                FOREGROUND_NOTIFICATION_ID,
                NotificationHelper.buildCountdownActiveNotification(this, remaining)
            )
        }

        val delay = max(100L, nextWakeAt - System.currentTimeMillis() + 40L)
        handler.postDelayed(runner, delay.coerceAtMost(60_000L))
    }

    private fun finishCountdown(countdown: ActiveCountdown) {
        // Remove first so the exact-alarm fallback becomes a no-op if it races with this service.
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
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
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
