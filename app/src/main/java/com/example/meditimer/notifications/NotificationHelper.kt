package com.example.meditimer.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.meditimer.AlarmActivity
import com.example.meditimer.MainActivity
import com.example.meditimer.R
import com.example.meditimer.data.Medication
import com.example.meditimer.data.PackageDurationMode

object NotificationHelper {
    const val CHANNEL_MED_PRE = "medication_prealert_v1"
    const val CHANNEL_MED_ALARM = "medication_alarm_full_v2"
    const val CHANNEL_COUNTDOWN = "countdown_finish_visual_v5"
    const val CHANNEL_PACKAGE = "package_reminder_v1"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MED_PRE, "Promemoria farmaco anticipato", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Promemoria silenzioso un'ora prima dell'assunzione"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MED_ALARM, "Sveglie farmaci", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Sveglie per le assunzioni programmate"
                // The actual system alarm ringtone and vibration are played by AlarmPlaybackService.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COUNTDOWN, "Fine countdown", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avvisi al termine dell'attesa dopo l'assunzione"
                // The custom end-of-countdown bip is played by SoundHelper.
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

    fun showMedicationPreReminder(
        context: Context,
        medication: Medication,
        plannedEpochDay: Long,
        plannedTime: String
    ) {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            ("pre-open:${medication.id}:$plannedEpochDay:$plannedTime").hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val takePending = actionPendingIntent(
            context = context,
            medication = medication,
            plannedEpochDay = plannedEpochDay,
            plannedTime = plannedTime,
            action = ActionReceiver.ACTION_TAKEN,
            notificationId = preReminderNotificationId(medication.id, plannedEpochDay, plannedTime),
            salt = "pre-taken"
        )
        val body = buildString {
            append("Alle $plannedTime dovrai assumere ${medication.name}")
            if (medication.doseNote.isNotBlank()) append(" · ${medication.doseNote}")
        }
        val n = NotificationCompat.Builder(context, CHANNEL_MED_PRE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tra 1 ora · Farmaco da assumere")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(0, "Farmaco assunto", takePending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(
                preReminderNotificationId(medication.id, plannedEpochDay, plannedTime),
                n
            )
        }
    }

    fun buildMedicationAlarmNotification(
        context: Context,
        medication: Medication,
        plannedEpochDay: Long,
        plannedTime: String
    ): Notification {
        ensureChannels(context)
        val notificationId = alarmNotificationId(medication.id, plannedEpochDay, plannedTime)
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            ("alarm-screen:${medication.id}:$plannedEpochDay:$plannedTime").hashCode(),
            AlarmActivity.intent(context, medication.id, plannedEpochDay, plannedTime),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val takePending = actionPendingIntent(
            context, medication, plannedEpochDay, plannedTime,
            ActionReceiver.ACTION_TAKEN, notificationId, "alarm-taken"
        )
        val snoozePending = actionPendingIntent(
            context, medication, plannedEpochDay, plannedTime,
            ActionReceiver.ACTION_SNOOZE, notificationId, "alarm-snooze"
        )
        val body = buildString {
            append("È ora di assumere ${medication.name}")
            if (medication.doseNote.isNotBlank()) append(" · ${medication.doseNote}")
        }
        return NotificationCompat.Builder(context, CHANNEL_MED_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sveglia farmaco")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(0, "Farmaco assunto", takePending)
            .addAction(0, "Snooze ${medication.snoozeMinutes} min", snoozePending)
            .build()
    }

    private fun actionPendingIntent(
        context: Context,
        medication: Medication,
        plannedEpochDay: Long,
        plannedTime: String,
        action: String,
        notificationId: Int,
        salt: String
    ): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).apply {
            this.action = action
            putExtra(ActionReceiver.EXTRA_MED_ID, medication.id)
            putExtra(ActionReceiver.EXTRA_EPOCH_DAY, plannedEpochDay)
            putExtra(ActionReceiver.EXTRA_TIME, plannedTime)
            putExtra(ActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            context,
            ("$salt:${medication.id}:$plannedEpochDay:$plannedTime").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelDoseNotifications(context: Context, medId: Long, plannedEpochDay: Long, plannedTime: String) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(preReminderNotificationId(medId, plannedEpochDay, plannedTime))
        nm.cancel(alarmNotificationId(medId, plannedEpochDay, plannedTime))
        // Also clear the legacy notification id used by versions <= 0.7.2.
        nm.cancel(notificationId(medId, plannedTime))
    }

    fun alarmNotificationId(medId: Long, plannedEpochDay: Long, time: String): Int =
        ("med-alarm:$medId:$plannedEpochDay:$time").hashCode()

    fun preReminderNotificationId(medId: Long, plannedEpochDay: Long, time: String): Int =
        ("med-pre:$medId:$plannedEpochDay:$time").hashCode()

    fun notificationId(medId: Long, time: String): Int = ("med:$medId:$time").hashCode()

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

    fun showStockPurchaseReminder(
        context: Context,
        medication: Medication,
        body: String,
        weeklyReminder: Boolean
    ) {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            ("stock-open:${medication.id}").hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_PACKAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (weeklyReminder) "Promemoria riacquisto" else "Riacquisto consigliato")
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

}
