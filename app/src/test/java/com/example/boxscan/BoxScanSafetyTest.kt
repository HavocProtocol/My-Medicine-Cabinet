package com.example.boxscan

import com.example.boxscan.models.CapturedSide
import com.example.boxscan.models.OcrSideOutput
import com.example.boxscan.models.PackageSideType
import com.example.boxscan.models.RecognizedLine
import com.example.safety.SafetyStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BoxScanSafetyTest {

    private val referenceDate = LocalDate.of(2026, 3, 15)
    private val service = BoxScanService(referenceDate = referenceDate)

    @Test
    fun neverPopulatesMedicalAdviceOrBarcodeFromOcrText() {
        val sides = listOf(
            CapturedSide("1", PackageSideType.FRONT, "/tmp/front.jpg"),
            CapturedSide("2", PackageSideType.BACK, "/tmp/back.jpg")
        )
        val outputs = listOf(
            OcrSideOutput(
                sideType = PackageSideType.FRONT,
                sideIndex = 0,
                lines = listOf(
                    RecognizedLine("Cetirizine"),
                    RecognizedLine("10 mg"),
                    RecognizedLine("20 Tablets"),
                    RecognizedLine("6281000123456"),
                    RecognizedLine("(01)06281000123456(17)280131(10)ABC123")
                )
            ),
            OcrSideOutput(
                sideType = PackageSideType.BACK,
                sideIndex = 1,
                lines = listOf(
                    RecognizedLine("Uses: Relief of seasonal allergy and hay fever symptoms"),
                    RecognizedLine("Indications: Allergic rhinitis and urticaria"),
                    RecognizedLine("Dosage: Take one tablet daily in the evening"),
                    RecognizedLine("Warning: May cause drowsiness. Do not drive.")
                )
            )
        )

        val result = service.processOcrOutputs(sides, outputs)
        val draft = result.draft

        // Permitted fields are extracted
        assertEquals("Cetirizine", draft.tradeName)
        assertEquals("10 mg", draft.strength)

        // Prohibited fields MUST remain null
        assertNull(draft.barcode)
        assertNull(draft.indications)
        assertNull(draft.warnings)
        assertNull(draft.usageMode)
    }

    @Test
    fun detectsExpiredMedicationAndRequiresExplicitConfirmation() {
        val sides = listOf(CapturedSide("1", PackageSideType.FLAP, "/tmp/flap.jpg"))
        val outputs = listOf(
            OcrSideOutput(
                sideType = PackageSideType.FLAP,
                sideIndex = 0,
                lines = listOf(
                    RecognizedLine("LOT: EXP01"),
                    RecognizedLine("EXP: 01/2023")
                )
            )
        )

        val result = service.processOcrOutputs(sides, outputs)

        assertEquals("2023-01-31", result.draft.expiryDate)
        assertTrue(result.isExpiryPast)
        assertFalse(result.draft.expiryExplicitlyConfirmed)
        assertEquals(
            "لا تستخدم دواءً منتهي الصلاحية، واستشر الصيدلي لمعرفة طريقة التخلص الآمن منه.",
            SafetyStrings.EXPIRED_DISPOSAL
        )
    }
}
