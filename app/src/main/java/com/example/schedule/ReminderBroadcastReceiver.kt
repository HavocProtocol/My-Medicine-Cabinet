package com.example.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.TimeZone

class ReminderBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context.applicationContext).appDao()
                val scheduler = ReminderNotificationScheduler(context.applicationContext)
                val repository = MedicationScheduleRepository(dao, scheduler)
                val now = System.currentTimeMillis()
                val tz = TimeZone.getDefault()

                when (action) {
                    ReminderNotificationScheduler.ACTION_TRIGGER_REMINDER -> {
                        val occKey = intent.getStringExtra(ReminderNotificationScheduler.EXTRA_OCCURRENCE_KEY)
                        if (!occKey.isNullOrBlank()) {
                            repository.handleAlarmTrigger(occKey, now, tz)
                        }
                    }

                    ReminderNotificationScheduler.ACTION_MARK_TAKEN -> {
                        val doseId = intent.getIntExtra(ReminderNotificationScheduler.EXTRA_DOSE_RECORD_ID, -1)
                        val occKey = intent.getStringExtra(ReminderNotificationScheduler.EXTRA_OCCURRENCE_KEY)
                        val record = if (doseId > 0) {
                            dao.getDoseRecordByIdAnyUser(doseId)
                        } else if (!occKey.isNullOrBlank()) {
                            dao.getDoseRecordByOccurrenceKey(occKey)
                        } else null

                        if (record != null) {
                            repository.markDoseTaken(
                                doseRecordId = record.id,
                                userId = record.userId,
                                nowMillis = now,
                                timeZone = tz
                            )
                        }
                    }

                    ReminderNotificationScheduler.ACTION_MARK_SKIPPED -> {
                        val doseId = intent.getIntExtra(ReminderNotificationScheduler.EXTRA_DOSE_RECORD_ID, -1)
                        val occKey = intent.getStringExtra(ReminderNotificationScheduler.EXTRA_OCCURRENCE_KEY)
                        val record = if (doseId > 0) {
                            dao.getDoseRecordByIdAnyUser(doseId)
                        } else if (!occKey.isNullOrBlank()) {
                            dao.getDoseRecordByOccurrenceKey(occKey)
                        } else null

                        if (record != null) {
                            repository.markDoseSkipped(
                                doseRecordId = record.id,
                                userId = record.userId,
                                nowMillis = now
                            )
                        }
                    }

                    ReminderNotificationScheduler.ACTION_SNOOZE_REMINDER -> {
                        val doseId = intent.getIntExtra(ReminderNotificationScheduler.EXTRA_DOSE_RECORD_ID, -1)
                        val occKey = intent.getStringExtra(ReminderNotificationScheduler.EXTRA_OCCURRENCE_KEY)
                        val snoozeMins = intent.getIntExtra(ReminderNotificationScheduler.EXTRA_SNOOZE_MINUTES, 15)
                        val record = if (doseId > 0) {
                            dao.getDoseRecordByIdAnyUser(doseId)
                        } else if (!occKey.isNullOrBlank()) {
                            dao.getDoseRecordByOccurrenceKey(occKey)
                        } else null

                        if (record != null) {
                            repository.snoozeDose(
                                doseRecordId = record.id,
                                userId = record.userId,
                                snoozeMinutes = snoozeMins,
                                nowMillis = now
                            )
                        }
                    }

                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        val activeSchedules = dao.getAllActiveSchedulesSnapshot()
                        val distinctUsers = activeSchedules.map { it.userId }.distinct()
                        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now, tz)
                        for (userId in distinctUsers) {
                            repository.ensureOccurrencesGeneratedForRange(
                                userId = userId,
                                fromDateIso = todayIso,
                                daysCount = 7,
                                nowMillis = now,
                                timeZone = tz
                            )
                        }
                    }
                }
            } finally {
                runCatching { pendingResult?.finish() }
            }
        }
    }
}
