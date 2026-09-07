package com.example.services

import com.example.safety.DosageGate
import com.example.safety.DosageRequest
import com.example.safety.GateDecision
import com.example.safety.GateResult
import com.example.safety.QueryIntent
import com.example.safety.SourceRef
import kotlinx.coroutines.delay

data class BarcodeProduct(
    val barcode: String,
    val tradeName: String? = null,
    val scientificName: String? = null,
    val manufacturer: String? = null,
    val form: String? = null,
    val isMock: Boolean = false
)

sealed class BarcodeLookupResult {
    data class Recognized(val product: BarcodeProduct) : BarcodeLookupResult()
    object NotFound : BarcodeLookupResult()
}

interface BarcodeScanService {
    suspend fun lookup(code: String): BarcodeLookupResult
}

class MockBarcodeLookup : BarcodeScanService {
    private val demoCodes = setOf("0000000000001", "0000000000002")

    override suspend fun lookup(code: String): BarcodeLookupResult {
        delay(300)
        if (code in demoCodes) {
            return BarcodeLookupResult.Recognized(
                BarcodeProduct(
                    barcode = code,
                    tradeName = "منتج تجريبي (بيانات نموذجية)",
                    scientificName = "مادة فعالة تجريبية",
                    manufacturer = "شركة تجريبية",
                    form = "أقراص",
                    isMock = true
                )
            )
        }
        return BarcodeLookupResult.NotFound
    }
}

data class MedInfoQuery(
    val medicineName: String,
    val question: String,
    val intent: QueryIntent,
    val isForChild: Boolean = false,
    val ageYears: Double? = null,
    val weightKg: Double? = null,
    val unit: String? = null,
    val concentration: String? = null
)

data class MedInfoAnswer(
    val decision: GateDecision,
    val message: String,
    val doseText: String? = null,
    val source: SourceRef? = null
) {
    companion object {
        fun fromGate(r: GateResult) = MedInfoAnswer(
            decision = r.decision,
            message = r.message,
            doseText = r.doseText,
            source = r.source
        )
    }
}

interface MedInfoService {
    suspend fun ask(q: MedInfoQuery): MedInfoAnswer
}

class MockMedInfoProvider : MedInfoService {
    private val verified = mapOf<String, Pair<String, SourceRef>>()

    private fun looksLikeEmergency(text: String): Boolean {
        val flags = listOf(
            "جرعة زائدة", "تسمم", "اختناق", "صعوبة تنفس", "حساسية شديدة",
            "overdose", "poison", "can't breathe", "anaphyla"
        )
        val t = text.lowercase()
        return flags.any { t.contains(it) }
    }

    override suspend fun ask(q: MedInfoQuery): MedInfoAnswer {
        if (looksLikeEmergency("${q.question} ${q.medicineName}")) {
            return MedInfoAnswer.fromGate(
                DosageGate.evaluate(DosageRequest(intent = q.intent, emergencySuspected = true))
            )
        }

        if (q.intent == QueryIntent.GENERAL_INFO) {
            return MedInfoAnswer.fromGate(
                DosageGate.evaluate(DosageRequest(intent = QueryIntent.GENERAL_INFO))
            )
        }

        val key = q.medicineName.trim().lowercase()
        val entry = verified[key]

        val result = DosageGate.evaluate(
            DosageRequest(
                intent = q.intent,
                doseText = entry?.first,
                source = entry?.second,
                isForChild = q.isForChild,
                aiAttemptedToInfer = false,
                ageYears = q.ageYears,
                weightKg = q.weightKg,
                unit = q.unit,
                concentration = q.concentration
            )
        )
        return MedInfoAnswer.fromGate(result)
    }
}
