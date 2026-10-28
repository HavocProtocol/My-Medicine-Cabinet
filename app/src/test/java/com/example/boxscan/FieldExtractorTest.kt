package com.example.boxscan

import com.example.boxscan.models.BoxScanField
import com.example.boxscan.models.MedForm
import com.example.boxscan.models.OcrSideOutput
import com.example.boxscan.models.PackageSideType
import com.example.boxscan.models.RecognizedLine
import com.example.boxscan.parsing.FieldExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FieldExtractorTest {

    private val referenceDate = LocalDate.of(2026, 3, 15)
    private val extractor = FieldExtractor(referenceDate = referenceDate)

    @Test
    fun extractsExpiryInEnglishAndArabicIncludingNextLine() {
        val sideEn = OcrSideOutput(
            sideType = PackageSideType.FLAP,
            sideIndex = 0,
            lines = listOf(RecognizedLine("EXP 08/2027"))
        )
        val candEn = extractor.extractFromSide(sideEn).firstOrNull { it.field == BoxScanField.EXPIRY_DATE }
        assertNotNull(candEn)
        assertEquals("2027-08-31", candEn!!.normalizedValue)

        val sideAr = OcrSideOutput(
            sideType = PackageSideType.FLAP,
            sideIndex = 1,
            lines = listOf(RecognizedLine("تاريخ الانتهاء: ٠٨/٢٠٢٧"))
        )
        val candAr = extractor.extractFromSide(sideAr).firstOrNull { it.field == BoxScanField.EXPIRY_DATE }
        assertNotNull(candAr)
        assertEquals("2027-08-31", candAr!!.normalizedValue)

        val sideNextLine = OcrSideOutput(
            sideType = PackageSideType.FLAP,
            sideIndex = 2,
            lines = listOf(
                RecognizedLine("EXP:"),
                RecognizedLine("11/2027")
            )
        )
        val candNext = extractor.extractFromSide(sideNextLine).firstOrNull { it.field == BoxScanField.EXPIRY_DATE }
        assertNotNull(candNext)
        assertEquals("2027-11-30", candNext!!.normalizedValue)
    }

    @Test
    fun extractsBatchLotAndRejectsRegNoPhonePriceAndBarcode() {
        val validSide = OcrSideOutput(
            sideType = PackageSideType.FLAP,
            sideIndex = 0,
            lines = listOf(
                RecognizedLine("LOT ABC1234"),
                RecognizedLine("B.No. 24K09"),
                RecognizedLine("رقم التشغيلة: A12-5"),
                RecognizedLine("Reg. No. 12345"),
                RecognizedLine("Tel: 0112345678"),
                RecognizedLine("Price: 25 SAR"),
                RecognizedLine("LOT 6281000123456") // 13-digit barcode must be rejected as batch
            )
        )
        val batchCandidates = extractor.extractFromSide(validSide)
            .filter { it.field == BoxScanField.BATCH_NUMBER }
            .map { it.normalizedValue }

        assertTrue(batchCandidates.contains("ABC1234"))
        assertTrue(batchCandidates.contains("24K09"))
        assertTrue(batchCandidates.contains("A12-5"))
        assertFalse(batchCandidates.contains("12345"))
        assertFalse(batchCandidates.contains("0112345678"))
        assertFalse(batchCandidates.contains("25"))
        assertFalse(batchCandidates.contains("6281000123456"))
    }

    @Test
    fun extractsStrengthFormAndPackQuantityInEnglishAndArabic() {
        val frontSide = OcrSideOutput(
            sideType = PackageSideType.FRONT,
            sideIndex = 0,
            lines = listOf(
                RecognizedLine("Panadol Advance"),
                RecognizedLine("٥٠٠ ملغ"),
                RecognizedLine("24 أقراص")
            )
        )
        val extracted = extractor.extractFromSide(frontSide)
        val strength = extracted.firstOrNull { it.field == BoxScanField.STRENGTH }
        val form = extracted.firstOrNull { it.field == BoxScanField.FORM }
        val pack = extracted.firstOrNull { it.field == BoxScanField.PACK_QUANTITY }

        assertNotNull(strength)
        assertEquals("500mg", strength!!.normalizedValue)
        assertNotNull(form)
        assertEquals(MedForm.TABLET, form!!.medForm)
        assertNotNull(pack)
        assertEquals("24", pack!!.normalizedValue)

        // Unknown form (e.g., suppositories) should keep medForm = null and low confidence (< 0.60)
        val unknownFormSide = OcrSideOutput(
            sideType = PackageSideType.FRONT,
            sideIndex = 1,
            lines = listOf(RecognizedLine("Glycerin Suppositories"))
        )
        val unknownForm = extractor.extractFromSide(unknownFormSide).firstOrNull { it.field == BoxScanField.FORM }
        assertNotNull(unknownForm)
        assertNull(unknownForm!!.medForm)
        assertTrue(unknownForm.confidence < 0.60)
    }

    @Test
    fun distinguishesRefrigerationFromRoomTempAndDoNotFreeze() {
        val refrigSide = OcrSideOutput(
            sideType = PackageSideType.BACK,
            sideIndex = 0,
            lines = listOf(RecognizedLine("يحفظ في الثلاجة (2 - 8°C)"))
        )
        val refrigCand = extractor.extractFromSide(refrigSide)
            .firstOrNull { it.field == BoxScanField.NEEDS_REFRIGERATION }
        assertNotNull(refrigCand)
        assertEquals("true", refrigCand!!.normalizedValue)

        val roomTempSide = OcrSideOutput(
            sideType = PackageSideType.BACK,
            sideIndex = 1,
            lines = listOf(
                RecognizedLine("Store below 25°C"),
                RecognizedLine("Do not freeze"),
                RecognizedLine("يحفظ في درجة حرارة أقل من 25")
            )
        )
        val roomTempCand = extractor.extractFromSide(roomTempSide)
            .firstOrNull { it.field == BoxScanField.NEEDS_REFRIGERATION }
        assertNull(roomTempCand)
    }

    @Test
    fun stampedBlockGroupProvidesConfidenceBonus() {
        val isolatedSide = OcrSideOutput(
            sideType = PackageSideType.FLAP,
            sideIndex = 0,
            lines = listOf(RecognizedLine("EXP: 08/2028"))
        )
        val groupedSide = OcrSideOutput(
            sideType = PackageSideType.FLAP,
            sideIndex = 1,
            lines = listOf(
                RecognizedLine("LOT: KX901"),
                RecognizedLine("MFG: 08/2025"),
                RecognizedLine("EXP: 08/2028")
            )
        )
        val isolatedConf = extractor.extractFromSide(isolatedSide)
            .first { it.field == BoxScanField.EXPIRY_DATE }.confidence
        val groupedConf = extractor.extractFromSide(groupedSide)
            .first { it.field == BoxScanField.EXPIRY_DATE }.confidence

        assertTrue(groupedConf >= isolatedConf)
    }
}
