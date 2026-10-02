package com.example.meditimer

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.lightColorScheme
import androidx.core.view.WindowCompat
import com.example.meditimer.notifications.NotificationHelper
import com.example.meditimer.notifications.DoseActionHandler
import com.example.meditimer.notifications.Scheduler
import com.example.meditimer.ui.MediTimerApp

class MainActivity : ComponentActivity() {
    private var openTodayRequest by mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        NotificationHelper.ensureChannels(this)
        Scheduler.scheduleAll(this)
        Scheduler.scheduleAllPackageReminders(this)
        Scheduler.restoreCountdowns(this)
        Scheduler.restoreSnoozes(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)

        // Keep Android system navigation controls visible on all devices.
        window.navigationBarColor = Color.rgb(15, 118, 110)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        NotificationHelper.ensureChannels(this)
        Scheduler.scheduleAll(this)
        Scheduler.scheduleAllPackageReminders(this)
        Scheduler.restoreCountdowns(this)
        Scheduler.restoreSnoozes(this)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                MediTimerApp(
                    openTodayRequest = openTodayRequest,
                    requestExactAlarmPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:$packageName")
                            })
                        }
                    }
                )
            }
        }
    }

    private fun handleLaunchIntent(intent: Intent) {
        if (intent.action == ACTION_MARK_TAKEN) {
            val medId = intent.getLongExtra(EXTRA_MED_ID, -1L)
            val epochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, -1L)
            val time = intent.getStringExtra(EXTRA_TIME).orEmpty()
            if (medId >= 0L && epochDay >= 0L && time.isNotBlank()) {
                DoseActionHandler.markTaken(this, medId, epochDay, time)
            }
            openTodayRequest++
            // Prevent the same action from being processed again if Android later reuses
            // this activity intent.
            intent.action = null
            return
        }

        if (intent.getBooleanExtra(EXTRA_OPEN_TODAY, false)) {
            openTodayRequest++
        }
    }

    companion object {
        const val EXTRA_OPEN_TODAY = "open_today"
        const val ACTION_MARK_TAKEN = "com.example.meditimer.MARK_TAKEN_OPEN_TODAY"
        private const val EXTRA_MED_ID = "med_id"
        private const val EXTRA_EPOCH_DAY = "epoch_day"
        private const val EXTRA_TIME = "time"

        fun takenIntent(
            context: Context,
            medicationId: Long,
            plannedEpochDay: Long,
            plannedTime: String
        ): Intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_MARK_TAKEN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MED_ID, medicationId)
            putExtra(EXTRA_EPOCH_DAY, plannedEpochDay)
            putExtra(EXTRA_TIME, plannedTime)
        }
    }

}
