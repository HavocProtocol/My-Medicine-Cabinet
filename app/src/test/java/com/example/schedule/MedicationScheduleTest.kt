package com.example.schedule

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDao
import com.example.data.AppDatabase
import com.example.data.InventoryItem
import com.example.data.Medicine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MedicationScheduleTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: AppDao
    private lateinit var scheduler: ReminderNotificationScheduler
    private lateinit var repository: MedicationScheduleRepository

    private val oneDayMillis = 24L * 60L * 60L * 1000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.appDao()
        scheduler = ReminderNotificationScheduler(context)
        repository = MedicationScheduleRepository(dao, scheduler)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertCabinetMedicine(
        name: String,
        expiryDateMillis: Long,
        quantity: Double = 20.0,
        unit: String = "tablet"
    ): Pair<Int, Int> {
        val medId = dao.insertMedicine(
            Medicine(
                tradeName = name,
                scientificName = "Generic $name",
                barcode = null,
                form = "Tablets",
                strength = "500 mg",
                manufacturer = "PharmaLab",
                imagePath = null,
                indications = "Pain & Fever",
                warnings = null,
                sourceId = null,
                createdAt = System.currentTimeMillis()
            )
        ).toInt()

        val invId = dao.insertInventoryItem(
            InventoryItem(
                medicineId = medId,
                familyMemberId = null,
                quantity = quantity,
                unit = unit,
                purchaseDate = System.currentTimeMillis() - 10 * oneDayMillis,
                expiryDate = expiryDateMillis,
                batchNumber = "LOT-100",
                storageLocation = "Cabinet",
                needsRefrigeration = false,
                usageMode = "Oral",
                userNotes = null
            )
        ).toInt()

        return medId to invId
    }

    /**
     * Scenario 1: Adding a valid medicine with one daily reminder.
     */
    @Test
    fun scenario01_addValidMedicineWithOneDailyReminder() = runBlocking {
        val now = System.currentTimeMillis()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
        val validExpiry = now + 180 * oneDayMillis
        val (medId, invId) = insertCabinetMedicine("Amoxicillin", validExpiry)

        val draft = ScheduleDraftInput(
            userId = "user_1",
            medicineId = medId,
            inventoryItemId = invId,
            medicineName = "Amoxicillin",
            strength = "500 mg",
            formulation = "Tablets",
            defaultDosage = 1.0,
            dosageUnit = "tablet",
            routeOfAdministration = "Oral",
            instructions = "Take after breakfast",
            startDateIso = todayIso,
            endDateIso = null,
            frequency = ScheduleFrequency.DAILY,
            timeSlots = listOf(ScheduledTimeSlot("09:00", 1.0, "tablet")),
            recordedExpiryDateMillis = validExpiry
        )

        val res = repository.createSchedule(draft, nowMillis = now)
        assertTrue(res.isSuccess)
        val created = res.getOrThrow()
        assertEquals(ScheduleStatus.ACTIVE.key, created.status)

        val doses = dao.getDoseRecordsForDateSnapshot("user_1", todayIso)
        assertEquals(1, doses.size)
        assertEquals("09:00", doses[0].scheduledTimeHm)
        assertEquals(1.0, doses[0].configuredDosage, 0.001)
        assertEquals("tablet", doses[0].dosageUnit)
    }

    /**
     * Scenario 2: Scheduling multiple medicines at different times and detecting overlapping times.
     */
    @Test
    fun scenario02_multipleMedicinesAndOverlapDetection() = runBlocking {
        val now = System.currentTimeMillis()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
        val validExpiry = now + 180 * oneDayMillis
        val (med1, inv1) = insertCabinetMedicine("Vitamin D3", validExpiry)
        val (med2, inv2) = insertCabinetMedicine("Omega-3", validExpiry)
        val (med3, inv3) = insertCabinetMedicine("Magnesium", validExpiry)

        // Med 1 at 08:00, Med 2 at 08:00 (overlapping), Med 3 at 21:00 (different time)
        repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = med1,
                inventoryItemId = inv1,
                medicineName = "Vitamin D3",
                defaultDosage = 1.0,
                dosageUnit = "capsule",
                startDateIso = todayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("08:00", 1.0, "capsule")),
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now
        )
        repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = med2,
                inventoryItemId = inv2,
                medicineName = "Omega-3",
                defaultDosage = 2.0,
                dosageUnit = "capsule",
                startDateIso = todayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("08:00", 2.0, "capsule")),
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now
        )
        repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = med3,
                inventoryItemId = inv3,
                medicineName = "Magnesium",
                defaultDosage = 1.0,
                dosageUnit = "tablet",
                startDateIso = todayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("21:00", 1.0, "tablet")),
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now
        )

        val todayDoses = dao.getDoseRecordsForDateSnapshot("user_1", todayIso)
        assertEquals(3, todayDoses.size)

        val overlaps = MedicationScheduleEngine.detectOverlappingDoses(todayDoses)
        assertEquals(1, overlaps.size)
        assertEquals("08:00", overlaps[0].timeHm)
        assertEquals(2, overlaps[0].doses.size)
    }

    /**
     * Scenario 3: Configuring multiple doses per day with different prescribed amounts.
     */
    @Test
    fun scenario03_multipleDosesPerDayWithDifferentAmounts() = runBlocking {
        val now = System.currentTimeMillis()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
        val validExpiry = now + 180 * oneDayMillis
        val (medId, invId) = insertCabinetMedicine("Warfarin", validExpiry)

        val slots = listOf(
            ScheduledTimeSlot("08:00", 2.0, "tablet"),
            ScheduledTimeSlot("14:00", 1.0, "tablet"),
            ScheduledTimeSlot("20:00", 1.5, "tablet")
        )

        val res = repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = medId,
                inventoryItemId = invId,
                medicineName = "Warfarin",
                defaultDosage = 2.0,
                dosageUnit = "tablet",
                startDateIso = todayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = slots,
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now
        )
        assertTrue(res.isSuccess)

        val doses = dao.getDoseRecordsForDateSnapshot("user_1", todayIso)
        assertEquals(3, doses.size)
        assertEquals(2.0, doses.first { it.scheduledTimeHm == "08:00" }.configuredDosage, 0.001)
        assertEquals(1.0, doses.first { it.scheduledTimeHm == "14:00" }.configuredDosage, 0.001)
        assertEquals(1.5, doses.first { it.scheduledTimeHm == "20:00" }.configuredDosage, 0.001)
    }

    /**
     * Scenario 4: Creating a recurring schedule for selected weekdays and intervals.
     */
    @Test
    fun scenario04_recurringSelectedWeekdaysAndIntervalSchedule() = runBlocking {
        val validExpiry = System.currentTimeMillis() + 365 * oneDayMillis
        val (medId, invId) = insertCabinetMedicine("Methotrexate", validExpiry)

        // Find a Monday ISO date: 2026-10-26 is Monday (ISO day 1)
        val mondayIso = "2026-10-26"
        val tuesdayIso = "2026-10-27"
        val wednesdayIso = "2026-10-28"
        assertEquals(1, MedicationScheduleEngine.getIsoDayOfWeek(mondayIso))
        assertEquals(2, MedicationScheduleEngine.getIsoDayOfWeek(tuesdayIso))
        assertEquals(3, MedicationScheduleEngine.getIsoDayOfWeek(wednesdayIso))

        val res = repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = medId,
                inventoryItemId = invId,
                medicineName = "Methotrexate",
                defaultDosage = 1.0,
                dosageUnit = "tablet",
                startDateIso = mondayIso,
                frequency = ScheduleFrequency.SELECTED_DAYS,
                selectedDaysOfWeek = setOf(1, 3), // Mon & Wed only
                timeSlots = listOf(ScheduledTimeSlot("10:00", 1.0, "tablet")),
                recordedExpiryDateMillis = validExpiry
            )
        )
        assertTrue(res.isSuccess)
        repository.ensureOccurrencesGeneratedForRange("user_1", mondayIso, 3)

        assertEquals(1, dao.getDoseRecordsForDateSnapshot("user_1", mondayIso).size)
        assertEquals(0, dao.getDoseRecordsForDateSnapshot("user_1", tuesdayIso).size)
        assertEquals(1, dao.getDoseRecordsForDateSnapshot("user_1", wednesdayIso).size)
    }

    /**
     * Scenario 5: Editing a schedule and verifying that obsolete pending doses/notifications are removed while taken history is preserved.
     */
    @Test
    fun scenario05_editScheduleRemovesObsoleteNotificationsAndPreservesHistory() = runBlocking {
        val now = System.currentTimeMillis()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
        val validExpiry = now + 180 * oneDayMillis
        val (medId, invId) = insertCabinetMedicine("Metformin", validExpiry)

        val created = repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = medId,
                inventoryItemId = invId,
                medicineName = "Metformin",
                defaultDosage = 1.0,
                dosageUnit = "tablet",
                startDateIso = todayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(
                    ScheduledTimeSlot("08:00", 1.0, "tablet"),
                    ScheduledTimeSlot("20:00", 1.0, "tablet")
                ),
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now
        ).getOrThrow()

        val initialDoses = dao.getDoseRecordsForDateSnapshot("user_1", todayIso)
        assertEquals(2, initialDoses.size)

        // Mark 08:00 dose as TAKEN
        val morningDose = initialDoses.first { it.scheduledTimeHm == "08:00" }
        repository.markDoseTaken(morningDose.id, "user_1", nowMillis = now)

        // Edit schedule to change 20:00 -> 22:00 with 2 tablets
        val updated = repository.updateSchedule(
            ScheduleDraftInput(
                scheduleId = created.id,
                userId = "user_1",
                medicineId = medId,
                inventoryItemId = invId,
                medicineName = "Metformin",
                defaultDosage = 2.0,
                dosageUnit = "tablet",
                startDateIso = todayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("22:00", 2.0, "tablet")),
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now
        )
        assertTrue(updated.isSuccess)

        val dosesAfterEdit = dao.getDoseRecordsForDateSnapshot("user_1", todayIso)
        // Taken 08:00 record is preserved, obsolete pending 20:00 record is removed, and new 22:00 record is created
        assertTrue(dosesAfterEdit.any { it.scheduledTimeHm == "08:00" && it.status == DoseStatus.TAKEN.key })
        assertFalse(dosesAfterEdit.any { it.scheduledTimeHm == "20:00" })
        assertTrue(dosesAfterEdit.any { it.scheduledTimeHm == "22:00" && it.configuredDosage == 2.0 })
    }

    /**
     * Scenario 6: Pausing and resuming reminders.
     */
    @Test
    fun scenario06_pauseAndResumeSchedule() = runBlocking {
        val now = System.currentTimeMillis()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
        val validExpiry = now + 180 * oneDayMillis
        val (medId, invId) = insertCabinetMedicine("Atorvastatin", validExpiry)

        val sched = repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = medId,
                inventoryItemId = invId,
                medicineName = "Atorvastatin",
                defaultDosage = 1.0,
                dosageUnit = "tablet",
                startDateIso = todayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("21:00", 1.0, "tablet")),
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now
        ).getOrThrow()

        val paused = repository.pauseSchedule(sched.id, "user_1", nowMillis = now).getOrThrow()
        assertEquals(ScheduleStatus.PAUSED.key, paused.status)
        assertFalse(paused.remindersEnabled)

        val resumed = repository.resumeSchedule(sched.id, "user_1", nowMillis = now).getOrThrow()
        assertEquals(ScheduleStatus.ACTIVE.key, resumed.status)
        assertTrue(resumed.remindersEnabled)
    }

    /**
     * Scenario 7: Marking a dose as taken, skipped, or missed (never auto-taken).
     */
    @Test
    fun scenario07_markDoseTakenSkippedAndMissedDistinction() = runBlocking {
        val now = System.currentTimeMillis()
        val yesterdayIso = MedicationScheduleEngine.addDaysToIso(
            MedicationScheduleEngine.millisToLocalDateIso(now),
            -1
        )
        val validExpiry = now + 180 * oneDayMillis
        val (medId, invId) = insertCabinetMedicine("Cetirizine", validExpiry, quantity = 10.0)

        repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = medId,
                inventoryItemId = invId,
                medicineName = "Cetirizine",
                defaultDosage = 1.0,
                dosageUnit = "tablet",
                startDateIso = yesterdayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(
                    ScheduledTimeSlot("08:00", 1.0, "tablet"),
                    ScheduledTimeSlot("12:00", 1.0, "tablet"),
                    ScheduledTimeSlot("18:00", 1.0, "tablet")
                ),
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now - oneDayMillis // Created yesterday morning
        )

        val yesterdayDoses = dao.getDoseRecordsForDateSnapshot("user_1", yesterdayIso)
        assertEquals(3, yesterdayDoses.size)

        // 1. Mark 08:00 as TAKEN
        repository.markDoseTaken(yesterdayDoses[0].id, "user_1", nowMillis = now)
        // 2. Mark 12:00 as SKIPPED
        repository.markDoseSkipped(yesterdayDoses[1].id, "user_1", nowMillis = now)
        // 3. Leave 18:00 unanswered and evaluate missed doses at 'now' (24 hours later)
        repository.evaluateMissedDoses("user_1", nowMillis = now)

        val updatedDoses = dao.getDoseRecordsForDateSnapshot("user_1", yesterdayIso)
        assertEquals(DoseStatus.TAKEN.key, updatedDoses.first { it.scheduledTimeHm == "08:00" }.status)
        assertEquals(DoseStatus.SKIPPED.key, updatedDoses.first { it.scheduledTimeHm == "12:00" }.status)
        assertEquals(DoseStatus.MISSED.key, updatedDoses.first { it.scheduledTimeHm == "18:00" }.status)
    }

    /**
     * Scenario 8: Attempting to schedule an already-expired medicine and confirming it is blocked.
     */
    @Test
    fun scenario08_blockSchedulingAlreadyExpiredMedicine() = runBlocking {
        val now = System.currentTimeMillis()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
        val expiredMillis = now - 5 * oneDayMillis
        val (medId, invId) = insertCabinetMedicine("ExpiredIbuprofen", expiredMillis)

        val draft = ScheduleDraftInput(
            userId = "user_1",
            medicineId = medId,
            inventoryItemId = invId,
            medicineName = "ExpiredIbuprofen",
            defaultDosage = 1.0,
            dosageUnit = "tablet",
            startDateIso = todayIso,
            frequency = ScheduleFrequency.DAILY,
            timeSlots = listOf(ScheduledTimeSlot("09:00", 1.0, "tablet")),
            recordedExpiryDateMillis = expiredMillis
        )

        val validation = MedicationScheduleEngine.validateScheduleDraft(draft, referenceTimeMillis = now)
        assertFalse(validation.isValid)
        assertTrue(validation.errors.contains(ScheduleValidationError.MEDICINE_EXPIRED))
        assertEquals(ExpirySafetyStatus.EXPIRED, validation.expiryEvaluation.status)

        val createRes = repository.createSchedule(draft, nowMillis = now)
        assertTrue(createRes.isFailure)
        assertTrue(createRes.exceptionOrNull() is ScheduleValidationException)
        assertEquals(0, dao.getSchedulesForUserSnapshot("user_1").size)
    }

    /**
     * Scenario 9: Attempting to schedule a medicine with no recorded expiration date.
     */
    @Test
    fun scenario09_blockSchedulingMedicineWithMissingExpirationDate() = runBlocking {
        val now = System.currentTimeMillis()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
        val medId = dao.insertMedicine(
            Medicine(
                tradeName = "UnverifiedExpiryMed",
                scientificName = null,
                barcode = null,
                form = "Tablets",
                strength = "100 mg",
                manufacturer = null,
                imagePath = null,
                indications = null,
                warnings = null,
                sourceId = null,
                createdAt = now
            )
        ).toInt()

        val draft = ScheduleDraftInput(
            userId = "user_1",
            medicineId = medId,
            inventoryItemId = null,
            medicineName = "UnverifiedExpiryMed",
            defaultDosage = 1.0,
            dosageUnit = "tablet",
            startDateIso = todayIso,
            frequency = ScheduleFrequency.DAILY,
            timeSlots = listOf(ScheduledTimeSlot("09:00", 1.0, "tablet")),
            recordedExpiryDateMillis = null
        )

        val validation = MedicationScheduleEngine.validateScheduleDraft(draft, referenceTimeMillis = now)
        assertFalse(validation.isValid)
        assertTrue(validation.errors.contains(ScheduleValidationError.MEDICINE_EXPIRY_MISSING))

        val createRes = repository.createSchedule(draft, nowMillis = now)
        assertTrue(createRes.isFailure)
    }

    /**
     * Scenario 10: Allowing a previously valid medicine to reach its expiration date and confirming subsequent reminders are suspended.
     */
    @Test
    fun scenario10_activeMedicineExpiresLaterAutomaticallySuspendsScheduleAndBlocksDoses() = runBlocking {
        val day0 = System.currentTimeMillis()
        val day0Iso = MedicationScheduleEngine.millisToLocalDateIso(day0)
        // Medicine expires in 2 days
        val expiryMillis = day0 + 2 * oneDayMillis
        val (medId, invId) = insertCabinetMedicine("ShortShelfLifeDrops", expiryMillis)

        val created = repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = medId,
                inventoryItemId = invId,
                medicineName = "ShortShelfLifeDrops",
                defaultDosage = 2.0,
                dosageUnit = "drop",
                startDateIso = day0Iso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("10:00", 2.0, "drop")),
                recordedExpiryDateMillis = expiryMillis,
                expiringSoonAcknowledged = true
            ),
            nowMillis = day0
        ).getOrThrow()

        assertEquals(ScheduleStatus.ACTIVE.key, created.status)

        // Advance time to 4 days later (past the expiration date)
        val day4Millis = day0 + 4 * oneDayMillis
        val blockedList = repository.evaluateAndEnforceExpiryProtection("user_1", nowMillis = day4Millis)
        assertEquals(1, blockedList.size)

        val reloaded = dao.getScheduleById(created.id, "user_1")!!
        assertEquals(ScheduleStatus.BLOCKED_EXPIRED.key, reloaded.status)
        assertFalse(reloaded.remindersEnabled)

        // Pending doses should be marked BLOCKED
        val day0Doses = dao.getDoseRecordsForDateSnapshot("user_1", day0Iso)
        assertTrue(day0Doses.all { it.status == DoseStatus.BLOCKED.key })
    }

    /**
     * Scenario 11: Attempting to bypass expiry protection through editing or resume workflows.
     */
    @Test
    fun scenario11_preventBypassingExpiryProtectionViaEditOrResume() = runBlocking {
        val day0 = System.currentTimeMillis()
        val day0Iso = MedicationScheduleEngine.millisToLocalDateIso(day0)
        val expiryMillis = day0 + 2 * oneDayMillis
        val (medId, invId) = insertCabinetMedicine("EyeDrops", expiryMillis)

        val sched = repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = medId,
                inventoryItemId = invId,
                medicineName = "EyeDrops",
                defaultDosage = 1.0,
                dosageUnit = "drop",
                startDateIso = day0Iso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("09:00", 1.0, "drop")),
                recordedExpiryDateMillis = expiryMillis,
                expiringSoonAcknowledged = true
            ),
            nowMillis = day0
        ).getOrThrow()

        // Fast-forward past expiry
        val afterExpiryMillis = day0 + 5 * oneDayMillis
        repository.evaluateAndEnforceExpiryProtection("user_1", nowMillis = afterExpiryMillis)

        // Attempt 1: Try to resume the expired schedule
        val resumeRes = repository.resumeSchedule(sched.id, "user_1", nowMillis = afterExpiryMillis)
        assertTrue(resumeRes.isFailure)
        assertEquals(ScheduleStatus.BLOCKED_EXPIRED.key, dao.getScheduleById(sched.id, "user_1")!!.status)

        // Attempt 2: Try to edit reminder time without updating the expired medicine
        val editRes = repository.updateSchedule(
            ScheduleDraftInput(
                scheduleId = sched.id,
                userId = "user_1",
                medicineId = medId,
                inventoryItemId = invId,
                medicineName = "EyeDrops",
                defaultDosage = 1.0,
                dosageUnit = "drop",
                startDateIso = day0Iso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("11:30", 1.0, "drop")),
                recordedExpiryDateMillis = expiryMillis,
                expiringSoonAcknowledged = true
            ),
            nowMillis = afterExpiryMillis
        )
        assertTrue(editRes.isFailure)
        assertTrue(editRes.exceptionOrNull() is ScheduleValidationException)
    }

    /**
     * Scenario 12: Checking behavior around midnight, date boundaries, and time-zone changes.
     */
    @Test
    fun scenario12_midnightMonthBoundariesAndTimeZoneConsistency() = runBlocking {
        val utcTz = TimeZone.getTimeZone("UTC")
        val tokyoTz = TimeZone.getTimeZone("Asia/Tokyo")

        // Month boundary: 2026-01-31 + 1 day -> 2026-02-01
        val nextDay = MedicationScheduleEngine.addDaysToIso("2026-01-31", 1)
        assertEquals("2026-02-01", nextDay)

        // Midnight slot 00:00 and 23:59 on the same date produce ordered millis within that date
        val startMillis = MedicationScheduleEngine.combineDateAndTimeToMillis("2026-06-15", "00:00", tokyoTz)!!
        val endMillis = MedicationScheduleEngine.combineDateAndTimeToMillis("2026-06-15", "23:59", tokyoTz)!!
        assertTrue(endMillis > startMillis)
        assertEquals("2026-06-15", MedicationScheduleEngine.millisToLocalDateIso(startMillis, tokyoTz))
        assertEquals("2026-06-15", MedicationScheduleEngine.millisToLocalDateIso(endMillis, tokyoTz))
    }

    /**
     * Scenario 13: Confirming dose history and inventory deductions are not duplicated.
     */
    @Test
    fun scenario13_noDuplicateDoseHistoryOrInventoryDeductions() = runBlocking {
        val now = System.currentTimeMillis()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
        val validExpiry = now + 180 * oneDayMillis
        val (medId, invId) = insertCabinetMedicine("Losartan", validExpiry, quantity = 10.0)

        repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = medId,
                inventoryItemId = invId,
                medicineName = "Losartan",
                defaultDosage = 1.5,
                dosageUnit = "tablet",
                startDateIso = todayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("08:00", 1.5, "tablet")),
                autoDeductInventory = true,
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now
        )

        // Call ensureOccurrencesGeneratedForRange multiple times
        repository.ensureOccurrencesGeneratedForRange("user_1", todayIso, 1, nowMillis = now)
        repository.ensureOccurrencesGeneratedForRange("user_1", todayIso, 1, nowMillis = now)

        val doses = dao.getDoseRecordsForDateSnapshot("user_1", todayIso)
        assertEquals(1, doses.size)

        // Mark dose as taken twice — inventory should only be deducted ONCE (10.0 - 1.5 = 8.5)
        repository.markDoseTaken(doses[0].id, "user_1", nowMillis = now)
        repository.markDoseTaken(doses[0].id, "user_1", nowMillis = now)

        val invAfterTaken = dao.getInventoryItemById(invId)!!
        assertEquals(8.5, invAfterTaken.quantity, 0.001)

        // Revert to skipped — inventory should be restored to 10.0
        repository.markDoseSkipped(doses[0].id, "user_1", nowMillis = now)
        val invAfterSkipped = dao.getInventoryItemById(invId)!!
        assertEquals(10.0, invAfterSkipped.quantity, 0.001)
    }

    /**
     * Scenario 14: Testing notification triggers, duplicate prevention, snooze, and schedule removal preserving cabinet medicine.
     */
    @Test
    fun scenario14_notificationTriggersSnoozeAndRemoveSchedulePreservesCabinetMedicine() = runBlocking {
        val now = System.currentTimeMillis()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
        val validExpiry = now + 180 * oneDayMillis
        val (medId, invId) = insertCabinetMedicine("Levothyroxine", validExpiry)

        val sched = repository.createSchedule(
            ScheduleDraftInput(
                userId = "user_1",
                medicineId = medId,
                inventoryItemId = invId,
                medicineName = "Levothyroxine",
                defaultDosage = 1.0,
                dosageUnit = "tablet",
                startDateIso = todayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("07:00", 1.0, "tablet")),
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now
        ).getOrThrow()

        val dose = dao.getDoseRecordsForDateSnapshot("user_1", todayIso).first()

        // First alarm trigger delivers notification and stamps notificationDeliveredAt
        repository.handleAlarmTrigger(dose.occurrenceKey, nowMillis = now)
        val afterFirstTrigger = dao.getDoseRecordById(dose.id, "user_1")!!
        assertNotNull(afterFirstTrigger.notificationDeliveredAt)

        // Snooze dose by 15 minutes
        val snoozed = repository.snoozeDose(dose.id, "user_1", snoozeMinutes = 15, nowMillis = now).getOrThrow()
        assertNotNull(snoozed.snoozedUntilMillis)
        assertTrue(snoozed.snoozedUntilMillis!! > now)

        // Mark dose taken, then remove schedule: medicine in cabinet & dose history must remain intact
        repository.markDoseTaken(dose.id, "user_1", nowMillis = now)
        val removed = repository.removeSchedule(sched.id, "user_1")
        assertTrue(removed)

        // Cabinet medicine still exists!
        assertNotNull(dao.getMedicineById(medId))
        // Historical taken dose record still exists!
        val history = dao.getDoseRecordsForUser("user_1").first()
        assertEquals(1, history.size)
        assertEquals(DoseStatus.TAKEN.key, history[0].status)
    }

    /**
     * Scenario 15: Verifying data isolation between different users.
     */
    @Test
    fun scenario15_verifyDataIsolationBetweenUsers() = runBlocking {
        val now = System.currentTimeMillis()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
        val validExpiry = now + 180 * oneDayMillis
        val (medA, invA) = insertCabinetMedicine("UserA_Med", validExpiry)
        val (medB, invB) = insertCabinetMedicine("UserB_Med", validExpiry)

        val schedA = repository.createSchedule(
            ScheduleDraftInput(
                userId = "alice",
                medicineId = medA,
                inventoryItemId = invA,
                medicineName = "UserA_Med",
                defaultDosage = 1.0,
                dosageUnit = "tablet",
                startDateIso = todayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("08:00", 1.0, "tablet")),
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now
        ).getOrThrow()

        val schedB = repository.createSchedule(
            ScheduleDraftInput(
                userId = "bob",
                medicineId = medB,
                inventoryItemId = invB,
                medicineName = "UserB_Med",
                defaultDosage = 2.0,
                dosageUnit = "capsule",
                startDateIso = todayIso,
                frequency = ScheduleFrequency.DAILY,
                timeSlots = listOf(ScheduledTimeSlot("09:00", 2.0, "capsule")),
                recordedExpiryDateMillis = validExpiry
            ),
            nowMillis = now
        ).getOrThrow()

        // Alice only sees Alice's schedules and doses
        val aliceSchedules = dao.getSchedulesForUserSnapshot("alice")
        assertEquals(1, aliceSchedules.size)
        assertEquals("UserA_Med", aliceSchedules[0].medicineName)

        val bobSchedules = dao.getSchedulesForUserSnapshot("bob")
        assertEquals(1, bobSchedules.size)
        assertEquals("UserB_Med", bobSchedules[0].medicineName)

        // Bob cannot pause or remove Alice's schedule
        val bobPauseAlice = repository.pauseSchedule(schedA.id, "bob", nowMillis = now)
        assertTrue(bobPauseAlice.isFailure)
        val bobRemoveAlice = repository.removeSchedule(schedA.id, "bob")
        assertFalse(bobRemoveAlice)

        // Alice's schedule remains ACTIVE
        assertEquals(ScheduleStatus.ACTIVE.key, dao.getScheduleById(schedA.id, "alice")!!.status)
        assertEquals(ScheduleStatus.ACTIVE.key, dao.getScheduleById(schedB.id, "bob")!!.status)
    }

    /**
     * Scenario 16: Requiring a valid, existing cabinet medicine and retrieving its database details without duplicate records.
     */
    @Test
    fun scenario16_requireExistingCabinetMedicineAndPreventDuplicates() = runBlocking {
        val now = System.currentTimeMillis()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
        val validExpiry = now + 180 * oneDayMillis

        // 1. Attempt to create a schedule without selecting an existing medicineId -> rejected
        val missingMedDraft = ScheduleDraftInput(
            userId = "user_1",
            medicineId = null,
            inventoryItemId = null,
            medicineName = "ManualUnregisteredMedicine",
            defaultDosage = 1.0,
            dosageUnit = "tablet",
            startDateIso = todayIso,
            frequency = ScheduleFrequency.DAILY,
            timeSlots = listOf(ScheduledTimeSlot("08:00", 1.0, "tablet")),
            recordedExpiryDateMillis = validExpiry
        )
        val validationMissing = MedicationScheduleEngine.validateScheduleDraft(missingMedDraft, referenceTimeMillis = now)
        assertFalse(validationMissing.isValid)
        assertTrue(validationMissing.errors.contains(ScheduleValidationError.MISSING_MEDICINE))

        val resMissing = repository.createSchedule(missingMedDraft, nowMillis = now)
        assertTrue(resMissing.isFailure)

        // 2. Attempt to create a schedule with a non-existent medicineId -> rejected by repository
        val nonExistentMedDraft = missingMedDraft.copy(medicineId = 99999)
        val resNonExistent = repository.createSchedule(nonExistentMedDraft, nowMillis = now)
        assertTrue(resNonExistent.isFailure)

        // 3. Register one medicine in the cabinet and create a schedule referencing its medicineId
        val (medId, invId) = insertCabinetMedicine("Paracetamol Forte", validExpiry)
        val medicinesBefore = dao.getAllMedicines().first().size
        assertEquals(1, medicinesBefore)

        val validDraft = ScheduleDraftInput(
            userId = "user_1",
            medicineId = medId,
            inventoryItemId = invId,
            medicineName = "Paracetamol Forte",
            defaultDosage = 1.0,
            dosageUnit = "tablet",
            startDateIso = todayIso,
            frequency = ScheduleFrequency.DAILY,
            timeSlots = listOf(ScheduledTimeSlot("08:00", 1.0, "tablet")),
            recordedExpiryDateMillis = validExpiry
        )
        val created = repository.createSchedule(validDraft, nowMillis = now).getOrThrow()

        // Verify medicine details were retrieved directly from the registered Medicine record and no duplicate Medicine was created
        assertEquals(medId, created.medicineId)
        assertEquals("Paracetamol Forte", created.medicineName)
        assertEquals("500 mg", created.strength)
        assertEquals("Tablets", created.formulation)
        assertEquals(1, dao.getAllMedicines().first().size)
    }
}
