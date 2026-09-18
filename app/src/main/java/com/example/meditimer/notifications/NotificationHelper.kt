package com.example.meditimer.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.meditimer.MainActivity
import com.example.meditimer.R
import com.example.meditimer.data.Medication

object NotificationHelper {
    const val CHANNEL_MED = "medication_alarm_v1"
    const val CHANNEL_COUNTDOWN = "countdown_alarm_v2"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MED, "Promemoria farmaci", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avvisi per le assunzioni programmate"
                setSound(alarmUri, attrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COUNTDOWN, "Fine countdown", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avvisi al termine dell'attesa dopo l'assunzione"
                setSound(null, null)
                enableVibration(true)
            }
        )
    }

    fun showMedicationAlarm(
        context: Context,
        medication: Medication,
        plannedEpochDay: Long,
        plannedTime: String,
        occurrenceMillis: Long
    ) {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            medication.id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val takeIntent = Intent(context, ActionReceiver::class.java).apply {
            action = ActionReceiver.ACTION_TAKEN
            putExtra(ActionReceiver.EXTRA_MED_ID, medication.id)
            putExtra(ActionReceiver.EXTRA_EPOCH_DAY, plannedEpochDay)
            putExtra(ActionReceiver.EXTRA_TIME, plannedTime)
            putExtra(ActionReceiver.EXTRA_NOTIFICATION_ID, notificationId(medication.id, plannedTime))
        }
        val takePending = PendingIntent.getBroadcast(
            context,
            (medication.id.toString() + plannedTime + occurrenceMillis).hashCode(),
            takeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = buildString {
            append("È ora di assumere ${medication.name}")
            if (medication.doseNote.isNotBlank()) append(" · ${medication.doseNote}")
        }
        val n = NotificationCompat.Builder(context, CHANNEL_MED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Farmaco da assumere")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(0, "Farmaco assunto", takePending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(medication.id, plannedTime), n) }
    }

    fun showCountdownFinished(context: Context, medicationName: String, note: String, countdownId: Long) {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            countdownId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (note.isBlank()) "L'attesa dopo $medicationName è terminata." else note
        val n = NotificationCompat.Builder(context, CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Attesa terminata · $medicationName")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(countdownId.hashCode(), n) }
    }

    fun notificationId(medId: Long, time: String): Int = ("med:$medId:$time").hashCode()
}
