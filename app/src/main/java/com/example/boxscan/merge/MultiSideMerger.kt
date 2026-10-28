package com.example.boxscan.merge

import com.example.boxscan.models.BoxScanField
import com.example.boxscan.models.BoxScanResult
import com.example.boxscan.models.CapturedSide
import com.example.boxscan.models.FieldCandidate
import com.example.boxscan.models.MedicineDraft
import com.example.boxscan.models.OcrSideOutput
import com.example.boxscan.models.PackageSideType
import com.example.safety.ExpiryService
import com.example.safety.ExpiryStatus
import java.time.LocalDate

/**
 * Merges extracted field candidates across multiple captured package sides,
 * resolves conflicts, enforces date-order and safety rules, and builds a [BoxScanResult].
 */
class MultiSideMerger(
    private val referenceDate: LocalDate = LocalDate.now()
) {
    companion object {
        const val DRAFT_THRESHOLD = 0.60
        const val HIGH_CONFIDENCE_THRESHOLD = 0.85
        const val REPEAT_SIDE_BONUS = 0.15
        const val MAX_CONFIDENCE_CAP = 0.95
        const val TIE_MARGIN = 0.10
    }

    fun merge(
        capturedSides: List<CapturedSide>,
        sideOutputs: List<OcrSideOutput>,
        rawCandidates: List<FieldCandidate>
    ): BoxScanResult {
        val groupedByField = rawCandidates.groupBy { it.field }

        val bestCandidates = mutableMapOf<BoxScanField, FieldCandidate>()
        val alternatives = mutableMapOf<BoxScanField, List<FieldCandidate>>()
        val ambiguousFields = mutableSetOf<BoxScanField>()

        for ((field, fieldCandidates) in groupedByField) {
            if (fieldCandidates.isEmpty()) continue

            // Group identical normalized values across sides
            val mergedByNormalizedValue = fieldCandidates
                .groupBy { it.normalizedValue }
                .map { (_, sameValueCandidates) ->
                    consolidateSameValueCandidates(field, sameValueCandidates)
                }
                .sortedWith(
                    compareByDescending<FieldCandidate> { it.confidence }
                        .thenByDescending { sidePriorityForField(it.field, it.sideType) }
                )

            alternatives[field] = mergedByNormalizedValue

            val top = mergedByNormalizedValue.first()
            val second = mergedByNormalizedValue.getOrNull(1)

            // Check tie margin rule: if two distinct candidates are within 0.10 confidence,
            // do not guess silently; mark as ambiguous and leave draft field empty for manual selection.
            if (second != null && (top.confidence - second.confidence) < TIE_MARGIN && top.confidence >= DRAFT_THRESHOLD) {
                ambiguousFields.add(field)
                // Still record top in bestCandidates so UI can show the conflicting candidates,
                // or keep it accessible in alternatives while leaving draft empty.
            } else {
                bestCandidates[field] = top
            }
        }

        // Cross-check MFG vs EXP date order (MFG must be strictly earlier than EXP)
        var hasDateOrderConflict = false
        val expCandidate = bestCandidates[BoxScanField.EXPIRY_DATE]
            ?: alternatives[BoxScanField.EXPIRY_DATE]?.firstOrNull()
        val mfgCandidate = bestCandidates[BoxScanField.MANUFACTURING_DATE]
            ?: alternatives[BoxScanField.MANUFACTURING_DATE]?.firstOrNull()

        if (expCandidate != null && mfgCandidate != null) {
            val expDate = runCatching { LocalDate.parse(expCandidate.normalizedValue) }.getOrNull()
            val mfgDate = runCatching { LocalDate.parse(mfgCandidate.normalizedValue) }.getOrNull()
            if (expDate != null && mfgDate != null && !mfgDate.isBefore(expDate)) {
                hasDateOrderConflict = true
                // Penalize both conflicting candidates and remove them from auto-selected bestCandidates
                val penalizedExp = expCandidate.copy(confidence = (expCandidate.confidence - 0.30).coerceAtLeast(0.30))
                val penalizedMfg = mfgCandidate.copy(confidence = (mfgCandidate.confidence - 0.30).coerceAtLeast(0.30))
                alternatives[BoxScanField.EXPIRY_DATE] = listOf(penalizedExp) +
                    (alternatives[BoxScanField.EXPIRY_DATE]?.drop(1) ?: emptyList())
                alternatives[BoxScanField.MANUFACTURING_DATE] = listOf(penalizedMfg) +
                    (alternatives[BoxScanField.MANUFACTURING_DATE]?.drop(1) ?: emptyList())
                bestCandidates.remove(BoxScanField.EXPIRY_DATE)
                bestCandidates.remove(BoxScanField.MANUFACTURING_DATE)
                ambiguousFields.add(BoxScanField.EXPIRY_DATE)
                ambiguousFields.add(BoxScanField.MANUFACTURING_DATE)
            }
        }

        // Build MedicineDraft using only non-ambiguous candidates with confidence >= DRAFT_THRESHOLD (0.60)
        fun selectedValue(field: BoxScanField): FieldCandidate? {
            if (field in ambiguousFields) return null
            val candidate = bestCandidates[field] ?: return null
            return if (candidate.confidence >= DRAFT_THRESHOLD) candidate else null
        }

        val selectedExpiry = selectedValue(BoxScanField.EXPIRY_DATE)
        val expiryLocalDate = selectedExpiry?.normalizedValue?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        val isExpiryPast = if (expiryLocalDate != null) {
            ExpiryService.classify(referenceDate, expiryLocalDate) == ExpiryStatus.EXPIRED
        } else {
            // Also check if top alternative expiry is in the past so the user sees the warning immediately
            val topAltExpiry = alternatives[BoxScanField.EXPIRY_DATE]?.firstOrNull()?.normalizedValue?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            }
            topAltExpiry != null && ExpiryService.classify(referenceDate, topAltExpiry) == ExpiryStatus.EXPIRED
        }

        val selectedFormCandidate = selectedValue(BoxScanField.FORM)
        val selectedRefrigCandidate = selectedValue(BoxScanField.NEEDS_REFRIGERATION)

        val draft = MedicineDraft(
            tradeName = selectedValue(BoxScanField.TRADE_NAME)?.value,
            scientificName = selectedValue(BoxScanField.SCIENTIFIC_NAME)?.value,
            strength = selectedValue(BoxScanField.STRENGTH)?.value,
            form = selectedFormCandidate?.medForm,
            manufacturer = selectedValue(BoxScanField.MANUFACTURER)?.value,
            batchNumber = selectedValue(BoxScanField.BATCH_NUMBER)?.value,
            expiryDate = selectedExpiry?.normalizedValue,
            manufacturingDate = selectedValue(BoxScanField.MANUFACTURING_DATE)?.normalizedValue,
            packQuantity = selectedValue(BoxScanField.PACK_QUANTITY)?.value,
            needsRefrigeration = if (selectedRefrigCandidate?.normalizedValue == "true") true else null,
            expiryExplicitlyConfirmed = false,
            refrigerationExplicitlyConfirmed = false,
            barcode = null,
            indications = null,
            warnings = null,
            usageMode = null
        )

        return BoxScanResult(
            capturedSides = capturedSides,
            sideOutputs = sideOutputs,
            bestCandidates = bestCandidates,
            alternatives = alternatives,
            ambiguousFields = ambiguousFields,
            hasDateOrderConflict = hasDateOrderConflict,
            isExpiryPast = isExpiryPast,
            draft = draft
        )
    }

    private fun consolidateSameValueCandidates(
        field: BoxScanField,
        sameValueCandidates: List<FieldCandidate>
    ): FieldCandidate {
        // Sort so we pick the best primary representative (prefer preferred sideType, then highest confidence)
        val sorted = sameValueCandidates.sortedWith(
            compareByDescending<FieldCandidate> { sidePriorityForField(field, it.sideType) }
                .thenByDescending { it.confidence }
        )
        val primary = sorted.first()
        val distinctSidesCount = sameValueCandidates.map { it.sideIndex }.distinct().size
        val extraSideCount = (distinctSidesCount - 1).coerceAtLeast(0)
        val bonus = extraSideCount * REPEAT_SIDE_BONUS

        val maxBaseConfidence = sameValueCandidates.maxOf { it.confidence }
        var boostedConfidence = (maxBaseConfidence + bonus).coerceAtMost(MAX_CONFIDENCE_CAP)

        // If confidence is rule-estimated (no OCR engine confidence) and only seen on a single side,
        // keep it strictly below HIGH_CONFIDENCE_THRESHOLD (0.85).
        val allEstimated = sameValueCandidates.all { it.isEstimatedConfidence }
        if (allEstimated && distinctSidesCount == 1 && boostedConfidence >= HIGH_CONFIDENCE_THRESHOLD) {
            boostedConfidence = 0.84
        }

        return primary.copy(
            confidence = boostedConfidence,
            corroboratingSideCount = distinctSidesCount
        )
    }

    private fun sidePriorityForField(field: BoxScanField, sideType: PackageSideType): Int {
        return when (field) {
            BoxScanField.TRADE_NAME,
            BoxScanField.STRENGTH,
            BoxScanField.FORM,
            BoxScanField.PACK_QUANTITY -> when (sideType) {
                PackageSideType.FRONT -> 3
                PackageSideType.BACK -> 2
                else -> 1
            }

            BoxScanField.EXPIRY_DATE,
            BoxScanField.MANUFACTURING_DATE,
            BoxScanField.BATCH_NUMBER -> when (sideType) {
                PackageSideType.FLAP -> 4
                PackageSideType.SIDE_1, PackageSideType.SIDE_2 -> 3
                PackageSideType.BACK -> 2
                else -> 1
            }

            BoxScanField.SCIENTIFIC_NAME,
            BoxScanField.MANUFACTURER,
            BoxScanField.NEEDS_REFRIGERATION -> when (sideType) {
                PackageSideType.BACK -> 3
                PackageSideType.SIDE_1, PackageSideType.SIDE_2 -> 3
                PackageSideType.FRONT -> 2
                else -> 1
            }
        }
    }
}
