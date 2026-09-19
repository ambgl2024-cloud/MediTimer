package com.example.meditimer

import android.Manifest
import android.app.AlarmManager
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
import androidx.compose.material3.lightColorScheme
import androidx.core.view.WindowCompat
import com.example.meditimer.notifications.NotificationHelper
import com.example.meditimer.notifications.Scheduler
import com.example.meditimer.ui.MediTimerApp

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
}
