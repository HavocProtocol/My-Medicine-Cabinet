package com.example.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.DoseRecord
import com.example.data.MedicationSchedule
import java.util.concurrent.ConcurrentHashMap

class ReminderNotificationScheduler(private val context: Context) {

    companion object {
        const val CHANNEL_REMINDERS_ID = "medication_reminders_channel"
        const val CHANNEL_SAFETY_ID = "medication_safety_channel"

        const val ACTION_TRIGGER_REMINDER = "com.example.schedule.ACTION_TRIGGER_REMINDER"
        const val ACTION_MARK_TAKEN = "com.example.schedule.ACTION_MARK_TAKEN"
        const val ACTION_MARK_SKIPPED = "com.example.schedule.ACTION_MARK_SKIPPED"
        const val ACTION_SNOOZE_REMINDER = "com.example.schedule.ACTION_SNOOZE_REMINDER"

        const val EXTRA_DOSE_RECORD_ID = "extra_dose_record_id"
        const val EXTRA_OCCURRENCE_KEY = "extra_occurrence_key"
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"

        // Observable registry of active scheduled alarms (occurrenceKey -> triggerAtMillis)
        val activeScheduledAlarms = ConcurrentHashMap<String, Long>()
        // Observable registry of delivered notification IDs (occurrenceKey -> deliveredTimestamp)
        val deliveredNotificationKeys = ConcurrentHashMap<String, Long>()
    }

    init {
        ensureChannelsCreated()
    }

    fun ensureChannelsCreated() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val reminderChannel = NotificationChannel(
                CHANNEL_REMINDERS_ID,
                context.getString(R.string.sched_channel_reminders_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.sched_channel_reminders_desc)
                enableVibration(true)
            }
            val safetyChannel = NotificationChannel(
                CHANNEL_SAFETY_ID,
                context.getString(R.string.sched_channel_safety_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.sched_channel_safety_desc)
                enableVibration(true)
            }
            nm.createNotificationChannel(reminderChannel)
            nm.createNotificationChannel(safetyChannel)
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun canScheduleExactAlarms(): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(true)
        } else {
            true
        }
    }

    /**
     * Schedules an alarm for a future PENDING [DoseRecord] if its schedule is ACTIVE and remindersEnabled == true.
     */
    fun scheduleDoseAlarm(
        schedule: MedicationSchedule,
        dose: DoseRecord,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (schedule.status != ScheduleStatus.ACTIVE.key || !schedule.remindersEnabled) {
            cancelDoseAlarm(dose.occurrenceKey, dose.notificationId)
            return false
        }
        if (dose.status != DoseStatus.PENDING.key) {
            cancelDoseAlarm(dose.occurrenceKey, dose.notificationId)
            return false
        }

        val targetMillis = dose.snoozedUntilMillis ?: dose.scheduledTimestampMillis
        if (targetMillis < nowMillis - 60_000L) {
            // Do not schedule alarms in the past
            return false
        }

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pendingIntent = buildTriggerPendingIntent(schedule, dose, PendingIntent.FLAG_UPDATE_CURRENT)

        if (am != null && pendingIntent != null) {
            try {
                if (canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        targetMillis,
                        pendingIntent
                    )
                } else {
                    am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        targetMillis,
                        pendingIntent
                    )
                }
            } catch (_: SecurityException) {
                runCatching {
                    am.set(AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
                }
            }
        }

        activeScheduledAlarms[dose.occurrenceKey] = targetMillis
        return true
    }

    /**
     * Cancels a scheduled alarm and dismisses any active notification for the given dose.
     */
    fun cancelDoseAlarm(occurrenceKey: String, notificationId: Int) {
        activeScheduledAlarms.remove(occurrenceKey)
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ACTION_TRIGGER_REMINDER
            putExtra(EXTRA_OCCURRENCE_KEY, occurrenceKey)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null && am != null) {
            runCatching { am.cancel(pi) }
            runCatching { pi.cancel() }
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        runCatching { nm?.cancel(notificationId) }
    }

    /**
     * Cancels all alarms belonging to a specific [scheduleId].
     */
    fun cancelAllAlarmsForSchedule(scheduleId: Int, doses: List<DoseRecord>) {
        doses.forEach { dose ->
            cancelDoseAlarm(dose.occurrenceKey, dose.notificationId)
        }
        val prefixPattern = "_${scheduleId}_"
        val matchingKeys = activeScheduledAlarms.keys().toList().filter { it.contains(prefixPattern) }
        matchingKeys.forEach { key ->
            val notifId = MedicationScheduleEngine.computeNotificationId(key)
            cancelDoseAlarm(key, notifId)
        }
    }

    /**
     * Delivers the actual notification for a due [DoseRecord], preventing duplicates unless snoozed.
     */
    fun showDoseReminderNotification(
        schedule: MedicationSchedule,
        dose: DoseRecord,
        isSnoozeTrigger: Boolean = false
    ): Boolean {
        if (schedule.status != ScheduleStatus.ACTIVE.key || !schedule.remindersEnabled) {
            return false
        }
        if (dose.status != DoseStatus.PENDING.key) {
            return false
        }
        if (!isSnoozeTrigger && dose.notificationDeliveredAt != null && dose.snoozedUntilMillis == null) {
            // Prevent duplicate notification for the same scheduled dose
            return false
        }
        if (!isSnoozeTrigger && deliveredNotificationKeys.containsKey(dose.occurrenceKey) && dose.snoozedUntilMillis == null) {
            return false
        }

        deliveredNotificationKeys[dose.occurrenceKey] = System.currentTimeMillis()
        activeScheduledAlarms.remove(dose.occurrenceKey)

        if (!hasNotificationPermission()) {
            return false
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        ensureChannelsCreated()

        val formattedDosage = if (dose.configuredDosage % 1.0 == 0.0) {
            dose.configuredDosage.toInt().toString()
        } else {
            dose.configuredDosage.toString()
        }

        val title = context.getString(
            R.string.sched_notif_title,
            dose.medicineName,
            dose.scheduledTimeHm
        )
        val body = buildString {
            append(
                context.getString(
                    R.string.sched_notif_body_dosage,
                    formattedDosage,
                    dose.dosageUnit
                )
            )
            if (!dose.instructions.isNullOrBlank()) {
                append(" • ")
                append(dose.instructions)
            }
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context,
            dose.notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val takenIntent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ACTION_MARK_TAKEN
            putExtra(EXTRA_DOSE_RECORD_ID, dose.id)
            putExtra(EXTRA_OCCURRENCE_KEY, dose.occurrenceKey)
            putExtra(EXTRA_NOTIFICATION_ID, dose.notificationId)
        }
        val takenPi = PendingIntent.getBroadcast(
            context,
            dose.notificationId + 1,
            takenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val skippedIntent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ACTION_MARK_SKIPPED
            putExtra(EXTRA_DOSE_RECORD_ID, dose.id)
            putExtra(EXTRA_OCCURRENCE_KEY, dose.occurrenceKey)
            putExtra(EXTRA_NOTIFICATION_ID, dose.notificationId)
        }
        val skippedPi = PendingIntent.getBroadcast(
            context,
            dose.notificationId + 2,
            skippedIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ACTION_SNOOZE_REMINDER
            putExtra(EXTRA_DOSE_RECORD_ID, dose.id)
            putExtra(EXTRA_OCCURRENCE_KEY, dose.occurrenceKey)
            putExtra(EXTRA_NOTIFICATION_ID, dose.notificationId)
            putExtra(EXTRA_SNOOZE_MINUTES, schedule.snoozeMinutes)
        }
        val snoozePi = PendingIntent.getBroadcast(
            context,
            dose.notificationId + 3,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentPi)
            .setAutoCancel(false)
            .addAction(
                android.R.drawable.checkbox_on_background,
                context.getString(R.string.sched_action_taken),
                takenPi
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.sched_action_skipped),
                skippedPi
            )
            .addAction(
                android.R.drawable.ic_lock_idle_alarm,
                context.getString(R.string.sched_action_remind_later),
                snoozePi
            )
            .build()

        return try {
            nm.notify(dose.notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Notifies the user that a scheduled medicine has reached its expiration date and reminders were suspended.
     */
    fun showExpirySuspendedNotification(medicineName: String, expiryDateIso: String, scheduleId: Int) {
        if (!hasNotificationPermission()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannelsCreated()

        val title = context.getString(R.string.sched_expired_suspended_title, medicineName)
        val body = context.getString(R.string.sched_expired_suspended_body, medicineName, expiryDateIso)

        val notification = NotificationCompat.Builder(context, CHANNEL_SAFETY_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        runCatching {
            nm.notify(800_000 + scheduleId, notification)
        }
    }

    /**
     * Notifies the user when a scheduled medicine is running low in cabinet inventory.
     */
    fun showLowStockNotification(medicineName: String, remainingQty: Double, unit: String, medicineId: Int) {
        if (!hasNotificationPermission()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannelsCreated()

        val qtyStr = if (remainingQty % 1.0 == 0.0) remainingQty.toInt().toString() else remainingQty.toString()
        val title = context.getString(R.string.sched_low_stock_title, medicineName)
        val body = context.getString(R.string.sched_low_stock_body, medicineName, qtyStr, unit)

        val notification = NotificationCompat.Builder(context, CHANNEL_SAFETY_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            nm.notify(900_000 + medicineId, notification)
        }
    }

    private fun buildTriggerPendingIntent(
        schedule: MedicationSchedule,
        dose: DoseRecord,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ACTION_TRIGGER_REMINDER
            putExtra(EXTRA_DOSE_RECORD_ID, dose.id)
            putExtra(EXTRA_OCCURRENCE_KEY, dose.occurrenceKey)
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
            putExtra(EXTRA_NOTIFICATION_ID, dose.notificationId)
        }
        return runCatching {
            PendingIntent.getBroadcast(
                context,
                dose.notificationId,
                intent,
                flags or PendingIntent.FLAG_IMMUTABLE
            )
        }.getOrNull()
    }
}
