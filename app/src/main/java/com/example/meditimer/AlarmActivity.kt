package com.example.meditimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.meditimer.data.Medication
import com.example.meditimer.data.MedicationRepository
import com.example.meditimer.notifications.ActionReceiver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlarmActivity : ComponentActivity() {
    private var medication by mutableStateOf<Medication?>(null)
    private var plannedEpochDay: Long = -1L
    private var plannedTime: String = ""
    private var autoCloseJob: Job? = null

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLOSE_ALARM_UI) finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreen()
        readIntent(getIntent())

        ContextCompat.registerReceiver(
            this,
            closeReceiver,
            IntentFilter(ACTION_CLOSE_ALARM_UI),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        scheduleAutoClose()

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val med = medication
                Column(
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Alarm, contentDescription = null)
                    Spacer(Modifier.height(18.dp))
                    Text("È ora del farmaco", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        med?.name ?: "Farmaco",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    if (!med?.doseNote.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(med?.doseNote.orEmpty(), textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Orario previsto: $plannedTime")
                    Spacer(Modifier.height(28.dp))

                    Button(
                        onClick = {
                            sendAction(ActionReceiver.ACTION_TAKEN)
                            finishAndRemoveTask()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Farmaco assunto")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            sendAction(ActionReceiver.ACTION_SNOOZE)
                            finishAndRemoveTask()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Snooze, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Snooze ${med?.snoozeMinutes ?: 10} min")
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        scheduleAutoClose()
    }

    override fun onDestroy() {
        autoCloseJob?.cancel()
        runCatching { unregisterReceiver(closeReceiver) }
        super.onDestroy()
    }

    private fun scheduleAutoClose() {
        autoCloseJob?.cancel()
        autoCloseJob = lifecycleScope.launch {
            delay(120_000L)
            if (!isFinishing) finishAndRemoveTask()
        }
    }

    private fun readIntent(intent: Intent) {
        val medId = intent.getLongExtra(EXTRA_MED_ID, -1L)
        plannedEpochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, -1L)
        plannedTime = intent.getStringExtra(EXTRA_TIME).orEmpty()
        medication = MedicationRepository(this).getMedication(medId)
    }

    private fun sendAction(action: String) {
        val med = medication ?: return
        sendBroadcast(
            Intent(this, ActionReceiver::class.java).apply {
                this.action = action
                putExtra(ActionReceiver.EXTRA_MED_ID, med.id)
                putExtra(ActionReceiver.EXTRA_EPOCH_DAY, plannedEpochDay)
                putExtra(ActionReceiver.EXTRA_TIME, plannedTime)
                putExtra(
                    ActionReceiver.EXTRA_NOTIFICATION_ID,
                    com.example.meditimer.notifications.NotificationHelper.alarmNotificationId(
                        med.id,
                        plannedEpochDay,
                        plannedTime
                    )
                )
            }
        )
    }

    private fun configureLockScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    companion object {
        const val ACTION_CLOSE_ALARM_UI = "com.example.meditimer.CLOSE_ALARM_UI"
        private const val EXTRA_MED_ID = "med_id"
        private const val EXTRA_EPOCH_DAY = "epoch_day"
        private const val EXTRA_TIME = "time"

        fun intent(context: Context, medId: Long, plannedEpochDay: Long, plannedTime: String): Intent =
            Intent(context, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_MED_ID, medId)
                putExtra(EXTRA_EPOCH_DAY, plannedEpochDay)
                putExtra(EXTRA_TIME, plannedTime)
            }

        fun requestClose(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_CLOSE_ALARM_UI).setPackage(context.packageName)
            )
        }
    }
}
