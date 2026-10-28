package com.example.boxscan.models

import androidx.annotation.StringRes
import com.example.R

/**
 * Package side classification for multi-side medicine box scanning.
 */
enum class PackageSideType(
    val key: String,
    @get:StringRes val labelRes: Int,
    @get:StringRes val guidanceRes: Int,
    val arabicLabel: String,
    val englishLabel: String
) {
    FRONT(
        key = "front",
        labelRes = R.string.side_front_label,
        guidanceRes = R.string.side_front_guidance,
        arabicLabel = "الوجه الأمامي",
        englishLabel = "Front"
    ),
    BACK(
        key = "back",
        labelRes = R.string.side_back_label,
        guidanceRes = R.string.side_back_guidance,
        arabicLabel = "الوجه الخلفي",
        englishLabel = "Back"
    ),
    SIDE_1(
        key = "side_1",
        labelRes = R.string.side_side1_label,
        guidanceRes = R.string.side_side1_guidance,
        arabicLabel = "الجانب الأول",
        englishLabel = "Side 1"
    ),
    SIDE_2(
        key = "side_2",
        labelRes = R.string.side_side2_label,
        guidanceRes = R.string.side_side2_guidance,
        arabicLabel = "الجانب الثاني",
        englishLabel = "Side 2"
    ),
    FLAP(
        key = "flap",
        labelRes = R.string.side_flap_label,
        guidanceRes = R.string.side_flap_guidance,
        arabicLabel = "الغطاء / قاعدة العلبة",
        englishLabel = "Flap"
    ),
    UNKNOWN(
        key = "unknown",
        labelRes = R.string.side_unknown_label,
        guidanceRes = R.string.side_unknown_guidance,
        arabicLabel = "وجه إضافي",
        englishLabel = "Other Side"
    );

    companion object {
        fun fromKey(key: String): PackageSideType {
            return entries.find { it.key == key } ?: UNKNOWN
        }
    }
}

/**
 * Canonical dosage forms supported by the existing app model (AddMedicineScreen & Medicine).
 * Maps strictly to the existing forms without inventing unsupported enum members.
 */
enum class MedForm(
    val canonicalKey: String,
    @get:StringRes val labelRes: Int,
    val arabicLabel: String,
    val englishLabel: String
) {
    TABLET("tablets", R.string.form_tablets, "أقراص", "Tablets"),
    CAPSULE("capsules", R.string.form_capsules, "كبسولات", "Capsules"),
    SYRUP("syrup", R.string.form_syrup, "شراب", "Syrup"),
    CREAM("cream", R.string.form_cream, "كريم", "Cream"),
    OINTMENT("ointment", R.string.form_ointment, "مرهم", "Ointment"),
    SPRAY("spray", R.string.form_spray, "بخاخ", "Spray"),
    DROPS("drops", R.string.form_drops, "قطرات", "Drops"),
    INJECTION("injections", R.string.form_injections, "حقن", "Injections")
}

/**
 * Bounding box in normalized image coordinates (0.0f..1.0f relative to the original oriented image).
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

/**
 * A single captured photo of one side of a medicine box.
 */
data class CapturedSide(
    val id: String,
    val sideType: PackageSideType,
    val imagePath: String
)

/**
 * Recognized line from an OCR engine pass.
 */
data class RecognizedLine(
    val text: String,
    val confidence: Double? = null,
    val boundingBox: NormalizedRect? = null
)

/**
 * Result of running an OCR engine on a single preprocessed image variant.
 */
data class OcrSideOutput(
    val sideType: PackageSideType,
    val sideIndex: Int,
    val lines: List<RecognizedLine>,
    val fullText: String = lines.joinToString("\n") { it.text }
)

/**
 * Supported fields that OCR is permitted to propose.
 * Medical advice fields (dosage instructions, indications, warnings, contraindications) are strictly excluded.
 */
enum class BoxScanField(
    @get:StringRes val labelRes: Int,
    val arabicLabel: String,
    val englishLabel: String
) {
    TRADE_NAME(R.string.trade_name, "الاسم التجاري", "Trade Name"),
    SCIENTIFIC_NAME(R.string.scientific_name, "المادة الفعالة / الاسم العلمي", "Active Ingredient"),
    STRENGTH(R.string.strength, "التركيز / القوة", "Strength"),
    FORM(R.string.dosage_form, "الشكل الصيدلاني", "Dosage Form"),
    MANUFACTURER(R.string.manufacturer, "الشركة المصنعة", "Manufacturer"),
    BATCH_NUMBER(R.string.batch_number_label, "رقم التشغيلة (LOT)", "Batch / Lot Number"),
    EXPIRY_DATE(R.string.expiry_date, "تاريخ الانتهاء (EXP)", "Expiry Date"),
    MANUFACTURING_DATE(R.string.box_scan_mfg_date, "تاريخ الإنتاج (MFG)", "Manufacturing Date"),
    PACK_QUANTITY(R.string.box_scan_pack_quantity, "حجم / كمية العبوة", "Pack Quantity"),
    NEEDS_REFRIGERATION(R.string.needs_refrigeration, "يحفظ في الثلاجة (2–8°C)", "Requires Refrigeration")
}

/**
 * A candidate value extracted for a specific permitted field from a single package side.
 */
data class FieldCandidate(
    val field: BoxScanField,
    val value: String,
    val normalizedValue: String,
    val rawText: String,
    val confidence: Double,
    val isEstimatedConfidence: Boolean = true,
    val sideType: PackageSideType = PackageSideType.UNKNOWN,
    val sideIndex: Int = 0,
    val boundingBox: NormalizedRect? = null,
    val medForm: MedForm? = null,
    val corroboratingSideCount: Int = 1
)

/**
 * Draft data passed from BoxScanReviewScreen to the existing AddMedicineScreen(prefillDraft = draft).
 * Note:
 * - barcode MUST always remain null (no barcode/GTIN processing allowed in box scan).
 * - indications, warnings, usageMode MUST always remain null (never populated from OCR).
 */
data class MedicineDraft(
    val tradeName: String? = null,
    val scientificName: String? = null,
    val strength: String? = null,
    val form: MedForm? = null,
    val manufacturer: String? = null,
    val batchNumber: String? = null,
    val expiryDate: String? = null,
    val manufacturingDate: String? = null,
    val packQuantity: String? = null,
    val needsRefrigeration: Boolean? = null,
    val expiryExplicitlyConfirmed: Boolean = false,
    val refrigerationExplicitlyConfirmed: Boolean = false,
    val barcode: String? = null,
    val indications: String? = null,
    val warnings: String? = null,
    val usageMode: String? = null
) {
    init {
        require(barcode.isNullOrEmpty()) { "MedicineDraft from BoxScan must never contain a barcode." }
        require(indications.isNullOrEmpty()) { "MedicineDraft from BoxScan must never populate indications." }
        require(warnings.isNullOrEmpty()) { "MedicineDraft from BoxScan must never populate warnings." }
        require(usageMode.isNullOrEmpty()) { "MedicineDraft from BoxScan must never populate usage/dosage directions." }
    }
}

/**
 * Complete merged output of a multi-side box scan session.
 */
data class BoxScanResult(
    val capturedSides: List<CapturedSide>,
    val sideOutputs: List<OcrSideOutput>,
    val bestCandidates: Map<BoxScanField, FieldCandidate>,
    val alternatives: Map<BoxScanField, List<FieldCandidate>>,
    val ambiguousFields: Set<BoxScanField> = emptySet(),
    val hasDateOrderConflict: Boolean = false,
    val isExpiryPast: Boolean = false,
    val draft: MedicineDraft
)
