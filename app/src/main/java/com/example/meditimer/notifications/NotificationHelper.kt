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
import com.example.meditimer.data.PackageDurationMode

object NotificationHelper {
    const val CHANNEL_MED = "medication_alarm_v1"
    const val CHANNEL_COUNTDOWN = "countdown_finish_visual_v5"
    const val CHANNEL_PACKAGE = "package_reminder_v1"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()

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
                // The custom end-of-countdown bip is played by SoundHelper.
                // Keep this channel silent to avoid the Android alarm ringtone playing as well.
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PACKAGE, "Confezioni e scorte", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Promemoria per cambio confezione e riacquisto scorte"
                setSound(notificationUri, notificationAttrs)
                enableVibration(true)
            }
        )
        nm.deleteNotificationChannel("countdown_alarm_v1")
        nm.deleteNotificationChannel("countdown_alarm_v2")
        nm.deleteNotificationChannel("countdown_alarm_v3")
        nm.deleteNotificationChannel("countdown_alarm_v4")
        nm.deleteNotificationChannel("countdown_active_v1")
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
        val snoozeIntent = Intent(context, ActionReceiver::class.java).apply {
            action = ActionReceiver.ACTION_SNOOZE
            putExtra(ActionReceiver.EXTRA_MED_ID, medication.id)
            putExtra(ActionReceiver.EXTRA_EPOCH_DAY, plannedEpochDay)
            putExtra(ActionReceiver.EXTRA_TIME, plannedTime)
            putExtra(ActionReceiver.EXTRA_NOTIFICATION_ID, notificationId(medication.id, plannedTime))
        }
        val snoozePending = PendingIntent.getBroadcast(
            context,
            ("snooze-action:${medication.id}:$plannedEpochDay:$plannedTime:$occurrenceMillis").hashCode(),
            snoozeIntent,
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
            .addAction(0, "Rimanda ${medication.snoozeMinutes} min", snoozePending)
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


    fun showPackageChangeReminder(context: Context, medication: Medication, remaining: Long) {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            ("package-open:${medication.id}").hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (medication.packageDurationMode == PackageDurationMode.INTAKES) {
            when {
                remaining > 1 -> "Restano $remaining assunzioni prima di cambiare la confezione di ${medication.name}."
                remaining == 1L -> "Resta 1 assunzione prima di cambiare la confezione di ${medication.name}."
                remaining == 0L -> "Hai raggiunto il numero massimo di assunzioni per ${medication.name}: cambia la confezione."
                remaining == -1L -> "Hai superato di 1 assunzione la durata della confezione di ${medication.name}."
                else -> "Hai superato di ${-remaining} assunzioni la durata della confezione di ${medication.name}."
            }
        } else {
            when {
                remaining > 1 -> "Tra $remaining giorni dovrai cambiare la confezione di ${medication.name}."
                remaining == 1L -> "Domani dovrai cambiare la confezione di ${medication.name}."
                remaining == 0L -> "Oggi devi cambiare la confezione di ${medication.name}."
                remaining == -1L -> "Il cambio confezione di ${medication.name} è scaduto da 1 giorno."
                else -> "Il cambio confezione di ${medication.name} è scaduto da ${-remaining} giorni."
            }
        }
        val n = NotificationCompat.Builder(context, CHANNEL_PACKAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Promemoria cambio confezione")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(packageReminderNotificationId(medication.id), n) }
    }

    fun showLowStockWarning(context: Context, medication: Medication, stockCount: Int) {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            ("stock-open:${medication.id}").hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (stockCount <= 0) {
            "Non hai più confezioni di ${medication.name} in scorta. Effettua un nuovo acquisto."
        } else {
            "Ti rimane una sola confezione di ${medication.name} in scorta. Programma un nuovo acquisto."
        }
        val n = NotificationCompat.Builder(context, CHANNEL_PACKAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (stockCount <= 0) "Scorta esaurita" else "Scorta quasi esaurita")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(stockNotificationId(medication.id), n) }
    }

    fun cancelLowStockWarning(context: Context, medicationId: Long) {
        NotificationManagerCompat.from(context).cancel(stockNotificationId(medicationId))
    }

    private fun packageReminderNotificationId(medId: Long): Int = ("package-change:$medId").hashCode()
    private fun stockNotificationId(medId: Long): Int = ("package-stock:$medId").hashCode()

    fun notificationId(medId: Long, time: String): Int = ("med:$medId:$time").hashCode()
}
