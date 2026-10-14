package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.AppDao
import com.example.data.Category
import com.example.data.Medicine
import com.example.data.InventoryItem
import com.example.data.MedicineCategoryCrossRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UatSimulatorTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: AppDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.appDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun runUatTests() = runBlocking {
        println("===================================================")
        println("🚀 STARTING USER ACCEPTANCE TESTING (UAT) SIMULATION")
        println("===================================================")

        // 1. UAT: Initial Database Setup
        println("\n[UAT-1] Initializing Database Categories...")
        val categoryId = dao.insertCategory(Category(name = "Test Category", description = null, color = null, icon = null))
        val categories = dao.getAllCategories().first()
        println("✓ Categories created. Found: \${categories.size}")
        assertTrue(categories.isNotEmpty())

        // 2. UAT: Adding Dummy Medicines (Valid, Expiring Soon, Expired)
        println("\n[UAT-2] Adding Dummy Medicines...")
        val now = System.currentTimeMillis()
        val oneDay = 24L * 60 * 60 * 1000
        val thirtyDaysMs = 30L * oneDay

        // Medicine A: Valid (Expires in 60 days)
        val medIdA = dao.insertMedicine(Medicine(tradeName = "Paracetamol (Valid)", scientificName = "Paracetamol", barcode = "111", form = "Tablets", strength = "500mg", manufacturer = "Pharma1", imagePath = null, indications = "Pain", warnings = null, sourceId = null, createdAt = now))
        dao.insertInventoryItem(InventoryItem(medicineId = medIdA.toInt(), familyMemberId = null, quantity = 10.0, unit = "Pack", purchaseDate = now, expiryDate = now + (60 * oneDay), batchNumber = "B1", storageLocation = "Box A", needsRefrigeration = false, usageMode = "As needed", userNotes = null))
        
        // Medicine B: Expiring Soon (Expires in 15 days)
        val medIdB = dao.insertMedicine(Medicine(tradeName = "Ibuprofen (Expiring Soon)", scientificName = "Ibuprofen", barcode = "222", form = "Capsules", strength = "400mg", manufacturer = "Pharma2", imagePath = null, indications = "Fever", warnings = null, sourceId = null, createdAt = now))
        dao.insertInventoryItem(InventoryItem(medicineId = medIdB.toInt(), familyMemberId = null, quantity = 5.0, unit = "Pack", purchaseDate = now, expiryDate = now + (15 * oneDay), batchNumber = "B2", storageLocation = "Box B", needsRefrigeration = false, usageMode = "As needed", userNotes = null))
        
        // Medicine C: Expired (Expired 5 days ago)
        val medIdC = dao.insertMedicine(Medicine(tradeName = "Aspirin (Expired)", scientificName = "Aspirin", barcode = "333", form = "Tablets", strength = "100mg", manufacturer = "Pharma3", imagePath = null, indications = "Heart", warnings = null, sourceId = null, createdAt = now))
        dao.insertInventoryItem(InventoryItem(medicineId = medIdC.toInt(), familyMemberId = null, quantity = 2.0, unit = "Pack", purchaseDate = now, expiryDate = now - (5 * oneDay), batchNumber = "B3", storageLocation = "Box C", needsRefrigeration = false, usageMode = "Daily", userNotes = null))

        val allMeds = dao.getAllMedicines().first()
        println("✓ Added \${allMeds.size} dummy medicines.")
        assertEquals(3, allMeds.size)

        // 3. UAT: Testing Dashboard Logic (Available, Expiring Soon, Expired)
        println("\n[UAT-3] Validating Dashboard Filters & Logic...")
        val available = dao.getAvailableItems(now, null).first()
        val expired = dao.getExpiredItems(now).first()
        
        println("  -> Total Available Items: \${available.size} (Expected: 2)")
        assertEquals(2, available.size)
        
        val expiringSoonCount = available.count { (it.expiryDate - now) <= thirtyDaysMs }
        println("  -> Items Expiring Soon (<= 30 days): \$expiringSoonCount (Expected: 1)")
        assertEquals(1, expiringSoonCount)
        
        println("  -> Expired Items: \${expired.size} (Expected: 1)")
        assertEquals(1, expired.size)

        println("✓ Dashboard logic correctly identifies medicine statuses based on expiry dates.")

        // 4. UAT: Category Association
        println("\n[UAT-4] Testing Category Associations...")
        dao.insertMedicineCategory(MedicineCategoryCrossRef(medIdA.toInt(), categoryId.toInt()))
        val medsInCategory = dao.getMedicinesForCategory(categoryId.toInt()).first()
        println("  -> Medicines in '\${categories.first().name}': \${medsInCategory.size} (Expected: 1)")
        assertEquals(1, medsInCategory.size)
        assertEquals("Paracetamol (Valid)", medsInCategory.first().tradeName)
        println("✓ Category association successfully validated.")

        println("\n===================================================")
        println("✅ ALL UAT SCENARIOS PASSED SUCCESSFULLY")
        println("===================================================")
    }
}
