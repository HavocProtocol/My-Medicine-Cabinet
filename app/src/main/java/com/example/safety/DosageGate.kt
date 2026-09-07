package com.example.safety

object SafetyStrings {
    const val REFUSAL = "لا أملك بيانات موثوقة كافية للإجابة عن هذا السؤال. يرجى مراجعة الصيدلي أو الطبيب"
    const val NOT_A_PRESCRIPTION = "هذه معلومات إرشادية فقط وليست وصفة طبية. الطبيب أو الصيدلي هو المرجع النهائي."
    const val SPECIAL_POPULATIONS = "تختلف الجرعات حسب التشخيص والعمر والوزن والحالة الصحية والتركيز. توخَّ الحذر بشكل خاص مع الأطفال والحوامل وكبار السن ومرضى الكبد والكلى ومرضى الحساسية."
    const val EMERGENCY = "قد تكون هذه حالة طارئة. اتصل فورًا بخدمات الطوارئ أو مركز السموم المحلي."
    const val NO_DIAGNOSIS = "لا يمكن للتطبيق تشخيص الأمراض أو وصف الأدوية. يرجى مراجعة الطبيب أو الصيدلي."
    const val EXPIRED_DISPOSAL = "لا تستخدم دواءً منتهي الصلاحية، واستشر الصيدلي لمعرفة طريقة التخلص الآمن منه."
}

data class SourceRef(
    val sourceName: String,
    val url: String? = null,
    val lastUpdatedAt: Long? = null
) {
    val isVerified: Boolean
        get() = sourceName.isNotBlank() && lastUpdatedAt != null
}

enum class QueryIntent {
    GENERAL_INFO, DOSAGE, DIAGNOSIS, PRESCRIPTION
}

data class DosageRequest(
    val intent: QueryIntent,
    val doseText: String? = null,
    val source: SourceRef? = null,
    val isForChild: Boolean = false,
    val aiAttemptedToInfer: Boolean = false,
    val emergencySuspected: Boolean = false,
    val ageYears: Double? = null,
    val weightKg: Double? = null,
    val unit: String? = null,
    val concentration: String? = null
)

enum class GateDecision {
    SHOW, REFUSE, BLOCK, ASK_FOR_INPUTS, EMERGENCY
}

data class GateResult(
    val decision: GateDecision,
    val message: String,
    val doseText: String? = null,
    val source: SourceRef? = null
)

object DosageGate {
    fun evaluate(r: DosageRequest): GateResult {
        if (r.emergencySuspected) {
            return GateResult(GateDecision.EMERGENCY, SafetyStrings.EMERGENCY)
        }
        if (r.intent == QueryIntent.DIAGNOSIS || r.intent == QueryIntent.PRESCRIPTION) {
            return GateResult(GateDecision.BLOCK, SafetyStrings.NO_DIAGNOSIS)
        }
        if (r.intent == QueryIntent.GENERAL_INFO) {
            return GateResult(GateDecision.SHOW, SafetyStrings.NOT_A_PRESCRIPTION)
        }

        if (r.aiAttemptedToInfer) {
            return GateResult(GateDecision.REFUSE, SafetyStrings.REFUSAL)
        }

        val src = r.source
        if (src == null || !src.isVerified) {
            return GateResult(GateDecision.REFUSE, SafetyStrings.REFUSAL)
        }

        if (r.doseText.isNullOrBlank()) {
            return GateResult(GateDecision.REFUSE, SafetyStrings.REFUSAL)
        }

        if (r.isForChild) {
            val complete = r.ageYears != null && r.weightKg != null && !r.unit.isNullOrBlank() && !r.concentration.isNullOrBlank()
            if (!complete) {
                return GateResult(
                    GateDecision.ASK_FOR_INPUTS,
                    "لعرض جرعة الأطفال، يرجى إدخال العمر والوزن والوحدة والتركيز. ولن تُعرض أي نتيجة غير مؤكدة أو ناقصة."
                )
            }
        }

        val disclaimer = "${SafetyStrings.NOT_A_PRESCRIPTION}\n${SafetyStrings.SPECIAL_POPULATIONS}"
        return GateResult(
            GateDecision.SHOW,
            disclaimer,
            doseText = r.doseText,
            source = src
        )
    }
}
