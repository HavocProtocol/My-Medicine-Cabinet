package com.example.boxscan

import com.example.boxscan.merge.MultiSideMerger
import com.example.boxscan.models.BoxScanField
import com.example.boxscan.models.CapturedSide
import com.example.boxscan.models.MedForm
import com.example.boxscan.models.OcrSideOutput
import com.example.boxscan.models.PackageSideType
import com.example.boxscan.models.RecognizedLine
import com.example.boxscan.parsing.FieldExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MultiSideMergerTest {

    private val referenceDate = LocalDate.of(2026, 3, 15)
    private val extractor = FieldExtractor(referenceDate = referenceDate)
    private val merger = MultiSideMerger(referenceDate = referenceDate)

    @Test
    fun mergesFrontBackAndFlapSidesAndBoostsCorroboratedValues() {
        val sides = listOf(
            CapturedSide("1", PackageSideType.FRONT, "/tmp/front.jpg"),
            CapturedSide("2", PackageSideType.BACK, "/tmp/back.jpg"),
            CapturedSide("3", PackageSideType.FLAP, "/tmp/flap.jpg")
        )
        val outputs = listOf(
            OcrSideOutput(
                sideType = PackageSideType.FRONT,
                sideIndex = 0,
                lines = listOf(
                    RecognizedLine("Brufen Rapid"),
                    RecognizedLine("400 mg"),
                    RecognizedLine("30 Film-Coated Tablets")
                )
            ),
            OcrSideOutput(
                sideType = PackageSideType.BACK,
                sideIndex = 1,
                lines = listOf(
                    RecognizedLine("Each tablet contains: Ibuprofen 400 mg"),
                    RecognizedLine("Manufactured by: Abbott Laboratories")
                )
            ),
            OcrSideOutput(
                sideType = PackageSideType.FLAP,
                sideIndex = 2,
                lines = listOf(
                    RecognizedLine("LOT: BR409"),
                    RecognizedLine("MFG: 01/2025"),
                    RecognizedLine("EXP: 01/2028")
                )
            )
        )

        val candidates = outputs.flatMap { extractor.extractFromSide(it) }
        val result = merger.merge(sides, outputs, candidates)

        assertEquals("Brufen Rapid", result.draft.tradeName)
        assertEquals("Ibuprofen", result.draft.scientificName)
        assertEquals("400 mg", result.draft.strength)
        assertEquals(MedForm.TABLET, result.draft.form)
        assertEquals("Abbott Laboratories", result.draft.manufacturer)
        assertEquals("BR409", result.draft.batchNumber)
        assertEquals("2028-01-31", result.draft.expiryDate)
        assertEquals("2025-01-01", result.draft.manufacturingDate)
        assertFalse(result.hasDateOrderConflict)

        // Strength "400 mg" appeared on both FRONT (index 0) and BACK (index 1), so corroboratingSideCount == 2
        // and confidence receives +0.15 repeat-side bonus (>= 0.85 high confidence threshold)
        val bestStrength = result.bestCandidates[BoxScanField.STRENGTH]!!
        assertEquals(2, bestStrength.corroboratingSideCount)
        assertTrue(bestStrength.confidence >= MultiSideMerger.HIGH_CONFIDENCE_THRESHOLD)
    }

    @Test
    fun closeTieBetweenDistinctCandidatesIsMarkedAmbiguousAndNotAutoSelected() {
        val sides = listOf(
            CapturedSide("1", PackageSideType.SIDE_1, "/tmp/s1.jpg"),
            CapturedSide("2", PackageSideType.SIDE_2, "/tmp/s2.jpg")
        )
        val outputs = listOf(
            OcrSideOutput(
                sideType = PackageSideType.SIDE_1,
                sideIndex = 0,
                lines = listOf(RecognizedLine("LOT: AB100"))
            ),
            OcrSideOutput(
                sideType = PackageSideType.SIDE_2,
                sideIndex = 1,
                lines = listOf(RecognizedLine("LOT: XY999"))
            )
        )
        val candidates = outputs.flatMap { extractor.extractFromSide(it) }
        val result = merger.merge(sides, outputs, candidates)

        assertTrue(BoxScanField.BATCH_NUMBER in result.ambiguousFields)
        assertNull(result.draft.batchNumber)
        assertEquals(2, result.alternatives[BoxScanField.BATCH_NUMBER]?.size)
    }

    @Test
    fun flagsDateOrderConflictWhenMfgIsLaterThanOrEqualToExp() {
        val sides = listOf(CapturedSide("1", PackageSideType.FLAP, "/tmp/flap.jpg"))
        val outputs = listOf(
            OcrSideOutput(
                sideType = PackageSideType.FLAP,
                sideIndex = 0,
                lines = listOf(
                    RecognizedLine("MFG: 05/2028"),
                    RecognizedLine("EXP: 05/2026")
                )
            )
        )
        val candidates = outputs.flatMap { extractor.extractFromSide(it) }
        val result = merger.merge(sides, outputs, candidates)

        assertTrue(result.hasDateOrderConflict)
        assertNull(result.draft.expiryDate)
        assertNull(result.draft.manufacturingDate)
    }
}
