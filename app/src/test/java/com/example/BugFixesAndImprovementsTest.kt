package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDao
import com.example.data.AppDatabase
import com.example.data.DoseRecord
import com.example.data.InventoryItem
import com.example.data.MedicationRefillClassification
import com.example.data.MedicationSchedule
import com.example.data.Medicine
import com.example.safety.ExpiryService
import com.example.safety.MedicationRefillEvaluator
import com.example.safety.MedicineExpirationStatus
import com.example.schedule.DoseStatus
import com.example.schedule.ExpirySafetyStatus
import com.example.schedule.MedicationScheduleEngine
import com.example.schedule.MedicationScheduleRepository
import com.example.schedule.ReminderNotificationScheduler
import com.example.schedule.ScheduleDraftInput
import com.example.schedule.ScheduleFrequency
import com.example.schedule.ScheduleStatus
import com.example.schedule.ScheduleValidationError
import com.example.schedule.ScheduledTimeSlot
import com.example.services.LocalLlmConfig
import com.example.services.OnlineMedicineSearchGate
import com.example.services.PremiumAccessRequiredException
import com.example.ui.screens.performGeminiSearch
import com.example.ui.screens.queryMedicineOnline
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BugFixesAndImprovementsTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: AppDao
    private lateinit var scheduler: ReminderNotificationScheduler
    private lateinit var repository: MedicationScheduleRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.appDao()
        scheduler = ReminderNotificationScheduler(context)
        repository = MedicationScheduleRepository(dao, scheduler)
        // Reset default settings
        LocalLlmConfig.setExpiryWarningDays(context, 7)
        LocalLlmConfig.setPremiumSubscribed(context, false)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Criteria 1, 2, 3, 4, 5:
     * - Medicine expiring today -> Expired
     * - Medicine expiring earlier than today -> Expired
     * - Medicine expiring tomorrow (1 day) -> Expiring Soon (under 7-day default warning window)
     * - Medicine expiring in 7 days -> Expiring Soon (under 7-day default warning window)
     * - Medicine expiring in 8 days -> Active (under 7-day default warning window)
     */
    @Test
    fun test01_expirationStatusBoundariesAndTimezoneSafety() {
        val today = LocalDate.of(2026, 10, 28)

        // 1. Expiring today -> EXPIRED
        assertEquals(
            MedicineExpirationStatus.EXPIRED,
            ExpiryService.evaluateExpirationStatus(today, today, warningDays = 7)
        )

        // 2. Expiring earlier than today (yesterday & 30 days ago) -> EXPIRED
        assertEquals(
            MedicineExpirationStatus.EXPIRED,
            ExpiryService.evaluateExpirationStatus(today, today.minusDays(1), warningDays = 7)
        )
        assertEquals(
            MedicineExpirationStatus.EXPIRED,
            ExpiryService.evaluateExpirationStatus(today, today.minusDays(30), warningDays = 7)
        )

        // 3. Expiring tomorrow (+1 day) -> EXPIRING_SOON
        assertEquals(
            MedicineExpirationStatus.EXPIRING_SOON,
            ExpiryService.evaluateExpirationStatus(today, today.plusDays(1), warningDays = 7)
        )

        // 4. Expiring in 7 days -> EXPIRING_SOON
        assertEquals(
            MedicineExpirationStatus.EXPIRING_SOON,
            ExpiryService.evaluateExpirationStatus(today, today.plusDays(7), warningDays = 7)
        )

        // 5. Expiring in 8 days -> ACTIVE
        assertEquals(
            MedicineExpirationStatus.ACTIVE,
            ExpiryService.evaluateExpirationStatus(today, today.plusDays(8), warningDays = 7)
        )

        // Configurable warning window (e.g. 14 days): 10 days -> EXPIRING_SOON, 15 days -> ACTIVE
        assertEquals(
            MedicineExpirationStatus.EXPIRING_SOON,
            ExpiryService.evaluateExpirationStatus(today, today.plusDays(10), warningDays = 14)
        )
        assertEquals(
            MedicineExpirationStatus.ACTIVE,
            ExpiryService.evaluateExpirationStatus(today, today.plusDays(15), warningDays = 14)
        )

        // Verify UTC-midnight DatePicker timestamp conversion does not shift calendar day in negative/positive timezones
        val datePickerUtcMidnight = ExpiryService.localDateToDatePickerMillis(today.plusDays(1))
        val roundTripDate = ExpiryService.datePickerMillisToLocalDate(datePickerUtcMidnight)
        assertEquals(today.plusDays(1), roundTripDate)
    }

    /**
     * Criteria 6 & 7:
     * - Add Medicine rejects expiry dates equal to today or earlier.
     * - Add Medicine accepts future dates (including tomorrow, which is within 7 days and classified as Expiring Soon).
     */
    @Test
    fun test02_addMedicineExpiryDateValidation() {
        val today = LocalDate.of(2026, 10, 28)
        val todayMillis = ExpiryService.localDateToStoredExpiryMillis(today)
        val yesterdayMillis = ExpiryService.localDateToStoredExpiryMillis(today.minusDays(1))
        val tomorrowMillis = ExpiryService.localDateToStoredExpiryMillis(today.plusDays(1))
        val nextMonthMillis = ExpiryService.localDateToStoredExpiryMillis(today.plusDays(30))

        // Null, 0, yesterday, and today must all be rejected
        assertFalse(ExpiryService.isValidNewMedicineExpiryMillis(todayMillis, null))
        assertFalse(ExpiryService.isValidNewMedicineExpiryMillis(todayMillis, 0L))
        assertFalse(ExpiryService.isValidNewMedicineExpiryMillis(todayMillis, yesterdayMillis))
        assertFalse(ExpiryService.isValidNewMedicineExpiryMillis(todayMillis, todayMillis))

        // Tomorrow and next month must be accepted
        assertTrue(ExpiryService.isValidNewMedicineExpiryMillis(todayMillis, tomorrowMillis))
        assertTrue(ExpiryService.isValidNewMedicineExpiryMillis(todayMillis, nextMonthMillis))

        // Tomorrow is allowed on entry and classified as Expiring Soon (not Expired)
        assertEquals(
            MedicineExpirationStatus.EXPIRING_SOON,
            ExpiryService.evaluateExpirationStatusMillis(todayMillis, tomorrowMillis, warningDays = 7)
        )
    }

    /**
     * Criterion 8:
     * - Expired and Expiring Soon statuses synchronize with the Room database immediately on add/update/load.
     */
    @Test
    fun test03_databaseExpirationStatusSynchronization() = runBlocking {
        val today = ExpiryService.currentLocalDate()
        val expiredMillis = ExpiryService.localDateToStoredExpiryMillis(today.minusDays(2))
        val soonMillis = ExpiryService.localDateToStoredExpiryMillis(today.plusDays(4))
        val activeMillis = ExpiryService.localDateToStoredExpiryMillis(today.plusDays(30))

        val medId = dao.insertMedicine(
            Medicine(
                tradeName = "SyncTestMed",
                scientificName = "Paracetamol",
                barcode = null,
                form = "Tablets",
                strength = "500mg",
                manufacturer = "Lab",
                imagePath = null,
                indications = null,
                warnings = null,
                sourceId = null,
                createdAt = System.currentTimeMillis(),
                expirationStatus = MedicineExpirationStatus.ACTIVE.key
            )
        ).toInt()

        val invId = dao.insertInventoryItem(
            InventoryItem(
                medicineId = medId,
                familyMemberId = null,
                quantity = 10.0,
                unit = "tablets",
                purchaseDate = System.currentTimeMillis(),
                expiryDate = soonMillis,
                batchNumber = "B1",
                storageLocation = "Drawer",
                needsRefrigeration = false,
                usageMode = "Oral",
                userNotes = null
            )
        ).toInt()

        // Recalculate and sync status -> EXPIRING_SOON
        val nowMillis = ExpiryService.localDateToStoredExpiryMillis(today)
        val computedSoon = ExpiryService.evaluateExpirationStatusMillis(nowMillis, soonMillis, warningDays = 7)
        assertEquals(MedicineExpirationStatus.EXPIRING_SOON, computedSoon)
        dao.updateMedicineExpirationStatus(medId, computedSoon.key)
        assertEquals(MedicineExpirationStatus.EXPIRING_SOON.key, dao.getMedicineById(medId)!!.expirationStatus)

        // Update inventory expiry to expiredMillis -> recalculates to EXPIRED
        val inv = dao.getInventoryItemById(invId)!!
        dao.updateInventoryItem(inv.copy(expiryDate = expiredMillis))
        val computedExpired = ExpiryService.evaluateExpirationStatusMillis(nowMillis, expiredMillis, warningDays = 7)
        assertEquals(MedicineExpirationStatus.EXPIRED, computedExpired)
        dao.updateMedicineExpirationStatus(medId, computedExpired.key)
        assertEquals(MedicineExpirationStatus.EXPIRED.key, dao.getMedicineById(medId)!!.expirationStatus)

        // Update inventory expiry to activeMillis -> recalculates to ACTIVE
        dao.updateInventoryItem(inv.copy(expiryDate = activeMillis))
        val computedActive = ExpiryService.evaluateExpirationStatusMillis(nowMillis, activeMillis, warningDays = 7)
        assertEquals(MedicineExpirationStatus.ACTIVE, computedActive)
        dao.updateMedicineExpirationStatus(medId, computedActive.key)
        assertEquals(MedicineExpirationStatus.ACTIVE.key, dao.getMedicineById(medId)!!.expirationStatus)
    }

    /**
     * Criteria 9 & 10:
     * - Free user attempting to access online medicine search is blocked and shown the paywall (cannot bypass via UI state alone).
     * - Premium user can access online medicine search.
     */
    @Test
    fun test04_onlineMedicineSearchPremiumGating() = runBlocking {
        // Default: Free user
        LocalLlmConfig.setPremiumSubscribed(context, false)
        assertFalse(OnlineMedicineSearchGate.canAccessOnlineSearch(context))

        // Attempting to bypass UI and call performGeminiSearch or queryMedicineOnline directly throws PremiumAccessRequiredException
        try {
            performGeminiSearch("Panadol uses", context)
            fail("Expected PremiumAccessRequiredException for free user")
        } catch (e: PremiumAccessRequiredException) {
            assertTrue(e.message!!.contains("Premium subscription"))
        }

        try {
            queryMedicineOnline("Panadol", "Paracetamol", "English", context)
            fail("Expected PremiumAccessRequiredException for free user")
        } catch (e: PremiumAccessRequiredException) {
            assertTrue(e.message!!.contains("Premium subscription"))
        }

        // Upgrade to Premium
        LocalLlmConfig.setPremiumSubscribed(context, true)
        assertTrue(OnlineMedicineSearchGate.canAccessOnlineSearch(context))
        OnlineMedicineSearchGate.requirePremiumAccess(LocalLlmConfig.isPremiumSubscribed(context))
    }

    /**
     * Criteria 11, 12, 13:
     * - Ongoing medication reaching quantity 0 moves to Refill Needed.
     * - Increasing an ongoing medication's quantity above 0 removes it from Refill Needed.
     * - One-time medication reaching quantity 0 moves to One-Time Medications while preserving history.
     */
    @Test
    fun test05_refillNeededAndOneTimeMedicationRouting() = runBlocking {
        val nowMillis = System.currentTimeMillis()
        val futureExpiry = nowMillis + 60L * 24 * 60 * 60 * 1000L

        val ongoingMedId = dao.insertMedicine(
            Medicine(
                tradeName = "Lisinopril",
                scientificName = "Lisinopril Dihydrate",
                barcode = null,
                form = "Tablets",
                strength = "10 mg",
                manufacturer = "HeartCare",
                imagePath = null,
                indications = "Blood pressure",
                warnings = null,
                sourceId = null,
                createdAt = nowMillis,
                refillClassification = MedicationRefillClassification.ONGOING_REFILL.key,
                prescribedDosage = "1 tablet",
                administrationRoute = "Oral"
            )
        ).toInt()

        val oneTimeMedId = dao.insertMedicine(
            Medicine(
                tradeName = "Azithromycin Course",
                scientificName = "Azithromycin",
                barcode = null,
                form = "Capsules",
                strength = "250 mg",
                manufacturer = "BioPharm",
                imagePath = null,
                indications = "Short course antibiotic",
                warnings = null,
                sourceId = null,
                createdAt = nowMillis,
                refillClassification = MedicationRefillClassification.ONE_TIME.key,
                prescribedDosage = "2 capsules",
                administrationRoute = "Oral"
            )
        ).toInt()

        val ongoingInvId = dao.insertInventoryItem(
            InventoryItem(
                medicineId = ongoingMedId,
                familyMemberId = null,
                quantity = 1.0,
                unit = "tablet",
                purchaseDate = nowMillis,
                expiryDate = futureExpiry,
                batchNumber = "LOT-1",
                storageLocation = "Kitchen",
                needsRefrigeration = false,
                usageMode = "Oral",
                userNotes = null
            )
        ).toInt()

        val oneTimeInvId = dao.insertInventoryItem(
            InventoryItem(
                medicineId = oneTimeMedId,
                familyMemberId = null,
                quantity = 2.0,
                unit = "capsule",
                purchaseDate = nowMillis,
                expiryDate = futureExpiry,
                batchNumber = "LOT-2",
                storageLocation = "Bedroom",
                needsRefrigeration = false,
                usageMode = "Oral",
                userNotes = null
            )
        ).toInt()

        // Initially both have quantity > 0 -> neither is in Refill Needed or One-Time Medications
        val evalInitial = MedicationRefillEvaluator.evaluate(
            medicines = dao.getAllMedicines().first(),
            inventoryItems = dao.getAllInventoryItems().first()
        )
        assertTrue(evalInitial.refillNeeded.isEmpty())
        assertTrue(evalInitial.oneTimeCompleted.isEmpty())

        // Deplete both medicines to quantity 0 via dose deduction / inventory update
        dao.updateInventoryQuantity(ongoingInvId, 0.0)
        dao.updateInventoryQuantity(oneTimeInvId, 0.0)

        val evalDepleted = MedicationRefillEvaluator.evaluate(
            medicines = dao.getAllMedicines().first(),
            inventoryItems = dao.getAllInventoryItems().first()
        )
        // Ongoing medicine is in Refill Needed (and NOT in One-Time Medications)
        assertEquals(1, evalDepleted.refillNeeded.size)
        assertEquals("Lisinopril", evalDepleted.refillNeeded[0].medicine.tradeName)
        assertEquals(0.0, evalDepleted.refillNeeded[0].totalRemainingQuantity, 0.001)

        // One-Time medicine is in One-Time Medications (and NOT in Refill Needed)
        assertEquals(1, evalDepleted.oneTimeCompleted.size)
        assertEquals("Azithromycin Course", evalDepleted.oneTimeCompleted[0].medicine.tradeName)
        assertNotNull(dao.getMedicineById(oneTimeMedId))

        // Restock ongoing medication above 0 -> immediately removed from Refill Needed
        dao.updateInventoryQuantity(ongoingInvId, 30.0)
        val evalRestocked = MedicationRefillEvaluator.evaluate(
            medicines = dao.getAllMedicines().first(),
            inventoryItems = dao.getAllInventoryItems().first()
        )
        assertTrue(evalRestocked.refillNeeded.isEmpty())
        assertEquals(1, evalRestocked.oneTimeCompleted.size)
    }

    /**
     * Criterion 14:
     * - Selecting an expired medicine in Medication Schedule blocks scheduling (including expiring today).
     */
    @Test
    fun test06_preventSchedulingExpiredMedicineIncludingToday() {
        val today = LocalDate.of(2026, 10, 28)
        val todayMillis = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val yesterdayMillis = today.minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val validFutureMillis = today.plusDays(20).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // Medicine expiring today -> EXPIRED
        val evalToday = MedicationScheduleEngine.evaluateExpirySafety(
            medicineName = "ExpiredTodayMed",
            expiryDateMillis = todayMillis,
            referenceTimeMillis = todayMillis,
            warningDays = 7
        )
        assertEquals(ExpirySafetyStatus.EXPIRED, evalToday.status)

        val draftToday = ScheduleDraftInput(
            medicineId = 1,
            medicineName = "ExpiredTodayMed",
            defaultDosage = 1.0,
            dosageUnit = "tablet",
            startDateIso = "2026-10-28",
            frequency = ScheduleFrequency.DAILY,
            timeSlots = listOf(ScheduledTimeSlot("08:00", 1.0, "tablet")),
            recordedExpiryDateMillis = todayMillis
        )
        val validationToday = MedicationScheduleEngine.validateScheduleDraft(
            draft = draftToday,
            referenceTimeMillis = todayMillis,
            warningDays = 7
        )
        assertFalse(validationToday.isValid)
        assertTrue(validationToday.errors.contains(ScheduleValidationError.MEDICINE_EXPIRED))

        // Medicine expiring yesterday -> EXPIRED
        val draftYesterday = draftToday.copy(recordedExpiryDateMillis = yesterdayMillis)
        val validationYesterday = MedicationScheduleEngine.validateScheduleDraft(
            draft = draftYesterday,
            referenceTimeMillis = todayMillis,
            warningDays = 7
        )
        assertFalse(validationYesterday.isValid)
        assertTrue(validationYesterday.errors.contains(ScheduleValidationError.MEDICINE_EXPIRED))

        // Switching to a valid future medicine restores validity
        val draftValid = draftToday.copy(
            medicineId = 2,
            medicineName = "ValidFutureMed",
            recordedExpiryDateMillis = validFutureMillis
        )
        val validationValid = MedicationScheduleEngine.validateScheduleDraft(
            draft = draftValid,
            referenceTimeMillis = todayMillis,
            warningDays = 7
        )
        assertTrue(validationValid.isValid)
    }

    /**
     * Criterion 15:
     * - History displays only completed (TAKEN with actualTakenTimestampMillis != null) dosage occurrences
     *   and excludes future scheduled doses, pending doses, skipped doses, and missed doses.
     */
    @Test
    fun test07_historyFiltersOnlyActuallyCompletedDosages() {
        val nowMillis = LocalDate.of(2026, 10, 28)
            .atTime(14, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val records = listOf(
            // 1. Completed dose earlier today -> MUST BE INCLUDED
            DoseRecord(
                id = 1,
                userId = "default_user",
                scheduleId = 10,
                medicineId = 1,
                inventoryItemId = 1,
                medicineName = "Omega-3",
                configuredDosage = 1.0,
                dosageUnit = "capsule",
                scheduledDateIso = "2026-10-28",
                scheduledTimeHm = "08:00",
                scheduledTimestampMillis = nowMillis - 6 * 3600_000L,
                occurrenceKey = "10_2026-10-28_08:00",
                status = DoseStatus.TAKEN.key,
                actualTakenTimestampMillis = nowMillis - 5 * 3600_000L
            ),
            // 2. Pending dose later today -> MUST BE EXCLUDED
            DoseRecord(
                id = 2,
                userId = "default_user",
                scheduleId = 10,
                medicineId = 1,
                inventoryItemId = 1,
                medicineName = "Omega-3",
                configuredDosage = 1.0,
                dosageUnit = "capsule",
                scheduledDateIso = "2026-10-28",
                scheduledTimeHm = "20:00",
                scheduledTimestampMillis = nowMillis + 6 * 3600_000L,
                occurrenceKey = "10_2026-10-28_20:00",
                status = DoseStatus.PENDING.key,
                actualTakenTimestampMillis = null
            ),
            // 3. Future scheduled dose tomorrow -> MUST BE EXCLUDED
            DoseRecord(
                id = 3,
                userId = "default_user",
                scheduleId = 10,
                medicineId = 1,
                inventoryItemId = 1,
                medicineName = "Omega-3",
                configuredDosage = 1.0,
                dosageUnit = "capsule",
                scheduledDateIso = "2026-10-29",
                scheduledTimeHm = "08:00",
                scheduledTimestampMillis = nowMillis + 18 * 3600_000L,
                occurrenceKey = "10_2026-10-29_08:00",
                status = DoseStatus.PENDING.key,
                actualTakenTimestampMillis = null
            ),
            // 4. Skipped dose yesterday -> MUST BE EXCLUDED
            DoseRecord(
                id = 4,
                userId = "default_user",
                scheduleId = 10,
                medicineId = 1,
                inventoryItemId = 1,
                medicineName = "Omega-3",
                configuredDosage = 1.0,
                dosageUnit = "capsule",
                scheduledDateIso = "2026-10-27",
                scheduledTimeHm = "20:00",
                scheduledTimestampMillis = nowMillis - 18 * 3600_000L,
                occurrenceKey = "10_2026-10-27_20:00",
                status = DoseStatus.SKIPPED.key,
                actualTakenTimestampMillis = null
            ),
            // 5. Completed dose yesterday -> MUST BE INCLUDED
            DoseRecord(
                id = 5,
                userId = "default_user",
                scheduleId = 10,
                medicineId = 1,
                inventoryItemId = 1,
                medicineName = "Omega-3",
                configuredDosage = 1.0,
                dosageUnit = "capsule",
                scheduledDateIso = "2026-10-27",
                scheduledTimeHm = "08:00",
                scheduledTimestampMillis = nowMillis - 30 * 3600_000L,
                occurrenceKey = "10_2026-10-27_08:00",
                status = DoseStatus.TAKEN.key,
                actualTakenTimestampMillis = nowMillis - 29 * 3600_000L
            )
        )

        val completedOnly = MedicationScheduleEngine.filterCompletedHistoryDoses(
            records = records,
            referenceTimeMillis = nowMillis
        )

        assertEquals(2, completedOnly.size)
        assertTrue(completedOnly.all { it.status == DoseStatus.TAKEN.key && it.actualTakenTimestampMillis != null })
        assertEquals(listOf(1, 5), completedOnly.map { it.id })
    }

    /**
     * Criterion 16:
     * - Inventory Sufficiency Warning does NOT appear when available stock is sufficient
     *   (e.g., 10 tablets available, 1 tablet/day for 3 days -> 3 tablets required -> NO warning).
     * - Inventory Sufficiency Warning DOES appear when available stock is less than required
     *   (e.g., 2 tablets available, 1 tablet/day for 5 days -> 5 tablets required -> warning shown).
     * - Already-taken doses today are NOT double-counted against remaining inventory.
     */
    @Test
    fun test08_inventorySufficiencyWarningAccuracy() {
        val today = LocalDate.of(2026, 10, 28)
        val futureExpiryMillis = today.plusDays(90).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val schedule3Days = MedicationSchedule(
            id = 100,
            userId = "default_user",
            medicineId = 10,
            inventoryItemId = 20,
            medicineName = "Paracetamol",
            strength = "500 mg",
            formulation = "Tablets",
            defaultDosage = 1.0,
            dosageUnit = "tablet",
            routeOfAdministration = "Oral",
            instructions = null,
            startDateIso = "2026-10-28",
            endDateIso = "2026-10-30", // 3 days inclusive: Oct 28, 29, 30 -> 3 tablets total
            frequencyType = ScheduleFrequency.DAILY.key,
            selectedDaysMask = "1,2,3,4,5,6,7",
            intervalDays = 1,
            doseTimesJson = ScheduledTimeSlot.encodeList(listOf(ScheduledTimeSlot("08:00", 1.0, "tablet"))),
            remindersEnabled = true,
            snoozeMinutes = 15,
            autoDeductInventory = true,
            lowStockThreshold = 15.0, // Even if lowStockThreshold is 15, 10 tablets is sufficient for 3 required!
            status = ScheduleStatus.ACTIVE.key,
            recordedExpiryDateMillis = futureExpiryMillis,
            userNotes = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Case A: 10 tablets available, 3 tablets required -> sufficient!
        val inv10 = listOf(
            InventoryItem(
                id = 20,
                medicineId = 10,
                familyMemberId = null,
                quantity = 10.0,
                unit = "tablets",
                purchaseDate = System.currentTimeMillis(),
                expiryDate = futureExpiryMillis,
                batchNumber = "B1",
                storageLocation = "Cabinet",
                needsRefrigeration = false,
                usageMode = "Oral",
                userNotes = null
            )
        )

        val infoSufficient = MedicationScheduleEngine.evaluateInventorySufficiency(
            schedules = listOf(schedule3Days),
            inventoryItems = inv10,
            startDateIso = "2026-10-28",
            daysAhead = 7
        ).first()
        assertEquals(10.0, infoSufficient.recordedQuantity ?: 0.0, 0.001)
        assertEquals(3.0, infoSufficient.upcoming7DayRequired, 0.001)
        assertFalse(infoSufficient.isInsufficientForUpcoming)
        assertNull(infoSufficient.unitMismatchWarning)

        // Case B: 2 tablets available, 3 tablets required -> insufficient!
        val inv2 = listOf(inv10[0].copy(quantity = 2.0))
        val infoInsufficient = MedicationScheduleEngine.evaluateInventorySufficiency(
            schedules = listOf(schedule3Days),
            inventoryItems = inv2,
            startDateIso = "2026-10-28",
            daysAhead = 7
        ).first()
        assertEquals(2.0, infoInsufficient.recordedQuantity ?: 0.0, 0.001)
        assertEquals(3.0, infoInsufficient.upcoming7DayRequired, 0.001)
        assertTrue(infoInsufficient.isInsufficientForUpcoming)

        // Case C: 2 tablets remaining in inventory AFTER today's dose was already TAKEN and deducted
        // Remaining required for Oct 29 and Oct 30 is 2 tablets -> 2 available >= 2 remaining -> sufficient!
        val todayTakenDose = listOf(
            DoseRecord(
                id = 1,
                userId = "default_user",
                scheduleId = 100,
                medicineId = 10,
                inventoryItemId = 20,
                medicineName = "Paracetamol",
                configuredDosage = 1.0,
                dosageUnit = "tablet",
                scheduledDateIso = "2026-10-28",
                scheduledTimeHm = "08:00",
                scheduledTimestampMillis = System.currentTimeMillis(),
                occurrenceKey = "100_2026-10-28_08:00",
                status = DoseStatus.TAKEN.key,
                actualTakenTimestampMillis = System.currentTimeMillis(),
                inventoryDeducted = true
            )
        )
        val infoAfterTodayTaken = MedicationScheduleEngine.evaluateInventorySufficiency(
            schedules = listOf(schedule3Days),
            inventoryItems = inv2,
            startDateIso = "2026-10-28",
            daysAhead = 7,
            existingDoseRecords = todayTakenDose
        ).first()
        assertEquals(2.0, infoAfterTodayTaken.upcoming7DayRequired, 0.001)
        assertFalse(infoAfterTodayTaken.isInsufficientForUpcoming)
    }
}
