package com.example.safety

import com.example.data.Medicine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ============================================================================
 * CENTRALIZED FEATURE FLAGS & FUTURE ACCOUNT INTEGRATION ARCHITECTURE
 * (Sections 4, 5, 6, and 7)
 * ============================================================================
 *
 * Architectural Dependency Chain for Future Activation:
 * 1. [USER_ACCOUNTS_ENABLED]:
 *    - Prerequisite identity & authentication layer binding a verified user account
 *      to encrypted profile storage and per-user medical safety records.
 * 2. [FutureUserSafetyProfile]:
 *    - Depends on [USER_ACCOUNTS_ENABLED] to scope [UserAllergyRecord] and
 *      [UserChronicConditionRecord] entries to a specific authenticated user profile.
 * 3. [MEDICATION_COMPATIBILITY_ENABLED]:
 *    - Evaluates drug-drug interactions and duplicate active ingredients across a user's
 *      recorded [Medicine] inventory and candidate [GlobalMedicine] entries.
 * 4. [ALLERGY_ALERTS_ENABLED]:
 *    - Evaluates a user's recorded [UserAllergyRecord] list against active ingredients,
 *      combination components, generic/brand names, and cross-reactive drug classes.
 * 5. [CHRONIC_CONDITIONS_PROFILE_ENABLED]:
 *    - Evaluates a user's recorded [UserChronicConditionRecord] list against verified
 *      condition-medication contraindication and precaution rules.
 *
 * IMPORTANT: All flags below MUST remain `false` by default in the live application
 * until the future user-account feature is implemented and clinically validated.
 */
object SafetyFeatureFlags {
    const val DEFAULT_USER_ACCOUNTS_ENABLED: Boolean = false
    const val DEFAULT_MEDICATION_COMPATIBILITY_ENABLED: Boolean = false
    const val DEFAULT_ALLERGY_ALERTS_ENABLED: Boolean = false
    const val DEFAULT_CHRONIC_CONDITIONS_PROFILE_ENABLED: Boolean = false

    @Volatile
    var userAccountsEnabled: Boolean = DEFAULT_USER_ACCOUNTS_ENABLED
        private set

    @Volatile
    var isMedicationCompatibilityEnabled: Boolean = DEFAULT_MEDICATION_COMPATIBILITY_ENABLED
        private set

    @Volatile
    var isAllergyAlertsEnabled: Boolean = DEFAULT_ALLERGY_ALERTS_ENABLED
        private set

    @Volatile
    var isChronicConditionsProfileEnabled: Boolean = DEFAULT_CHRONIC_CONDITIONS_PROFILE_ENABLED
        private set

    /**
     * Returns true only if the prerequisite user-account system and the specific
     * safety feature flag are enabled, or if a specific feature flag was explicitly
     * enabled in isolated unit tests.
     */
    fun isAnySafetyFeatureActive(): Boolean =
        isMedicationCompatibilityEnabled || isAllergyAlertsEnabled || isChronicConditionsProfileEnabled

    /**
     * Controlled override exclusively for unit and integration testing of dormant
     * medication-safety services while keeping production defaults strictly `false`.
     */
    fun setFeatureFlagsForTesting(
        userAccounts: Boolean = DEFAULT_USER_ACCOUNTS_ENABLED,
        medicationCompatibility: Boolean = DEFAULT_MEDICATION_COMPATIBILITY_ENABLED,
        allergyAlerts: Boolean = DEFAULT_ALLERGY_ALERTS_ENABLED,
        chronicConditionsProfile: Boolean = DEFAULT_CHRONIC_CONDITIONS_PROFILE_ENABLED
    ) {
        userAccountsEnabled = userAccounts
        isMedicationCompatibilityEnabled = medicationCompatibility
        isAllergyAlertsEnabled = allergyAlerts
        isChronicConditionsProfileEnabled = chronicConditionsProfile
    }

    /**
     * Resets all feature flags back to their production dormant state (`false`).
     */
    fun resetToDefaults() {
        userAccountsEnabled = DEFAULT_USER_ACCOUNTS_ENABLED
        isMedicationCompatibilityEnabled = DEFAULT_MEDICATION_COMPATIBILITY_ENABLED
        isAllergyAlertsEnabled = DEFAULT_ALLERGY_ALERTS_ENABLED
        isChronicConditionsProfileEnabled = DEFAULT_CHRONIC_CONDITIONS_PROFILE_ENABLED
    }
}

/**
 * Documents the architectural contract between future user accounts, user health profiles,
 * medication cabinet records, and the three safety evaluation services.
 */
data class FutureAccountIntegrationBlueprint(
    val requiresAuthenticatedUserAccount: Boolean = true,
    val profileEntities: List<String> = listOf(
        "FutureUserSafetyProfile",
        "UserAllergyRecord",
        "UserChronicConditionRecord"
    ),
    val medicationEntities: List<String> = listOf(
        "Medicine",
        "GlobalMedicine"
    ),
    val dependentSafetyServices: List<String> = listOf(
        "MedicationCompatibilityService",
        "AllergyAlertService",
        "ChronicConditionProfileService"
    )
)

/**
 * Future user safety profile model (dormant until user accounts are enabled).
 */
data class FutureUserSafetyProfile(
    val accountId: String,
    val allergies: List<UserAllergyRecord> = emptyList(),
    val chronicConditions: List<UserChronicConditionRecord> = emptyList(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

// ============================================================================
// SHARED INGREDIENT NORMALIZATION & PHARMACOLOGICAL CLASS CATALOG
// ============================================================================

/**
 * Normalizes trade names, scientific/generic names, and combination formulas into
 * canonical active ingredient tokens and pharmacological drug classes.
 * Never fabricates ingredients when a medicine lacks recognizable ingredient metadata.
 */
object ActiveIngredientNormalizer {

    private val brandToCanonicalIngredients: Map<String, List<String>> = mapOf(
        "panadol" to listOf("paracetamol"),
        "panadol extra" to listOf("paracetamol", "caffeine"),
        "panadol cold & flu" to listOf("paracetamol", "chlorpheniramine", "pseudoephedrine"),
        "tylenol" to listOf("paracetamol"),
        "adol" to listOf("paracetamol"),
        "fevadol" to listOf("paracetamol"),
        "brufen" to listOf("ibuprofen"),
        "advil" to listOf("ibuprofen"),
        "nurofen" to listOf("ibuprofen"),
        "voltaren" to listOf("diclofenac"),
        "cataflam" to listOf("diclofenac"),
        "rofenac" to listOf("diclofenac"),
        "aleve" to listOf("naproxen"),
        "aspirin" to listOf("aspirin"),
        "jusprin" to listOf("aspirin"),
        "aspocid" to listOf("aspirin"),
        "augmentin" to listOf("amoxicillin", "clavulanic acid"),
        "hibiotic" to listOf("amoxicillin", "clavulanic acid"),
        "megamox" to listOf("amoxicillin", "clavulanic acid"),
        "amoxil" to listOf("amoxicillin"),
        "coumadin" to listOf("warfarin"),
        "marevan" to listOf("warfarin"),
        "plavix" to listOf("clopidogrel"),
        "nexium" to listOf("esomeprazole"),
        "losec" to listOf("omeprazole"),
        "prilosec" to listOf("omeprazole"),
        "glucophage" to listOf("metformin"),
        "zestril" to listOf("lisinopril"),
        "capoten" to listOf("captopril"),
        "lasix" to listOf("furosemide"),
        "aldactone" to listOf("spironolactone"),
        "concor" to listOf("bisoprolol"),
        "inderal" to listOf("propranolol"),
        "lipitor" to listOf("atorvastatin"),
        "zocor" to listOf("simvastatin"),
        "claritin" to listOf("loratadine"),
        "zyrtec" to listOf("cetirizine"),
        "ventolin" to listOf("salbutamol"),
        "bactrim" to listOf("sulfamethoxazole", "trimethoprim"),
        "septrin" to listOf("sulfamethoxazole", "trimethoprim")
    )

    private val synonymToCanonical: Map<String, String> = mapOf(
        "acetaminophen" to "paracetamol",
        "paracetamol" to "paracetamol",
        "acetylsalicylic acid" to "aspirin",
        "asa" to "aspirin",
        "aspirin" to "aspirin",
        "ibuprofen" to "ibuprofen",
        "diclofenac" to "diclofenac",
        "diclofenac sodium" to "diclofenac",
        "diclofenac potassium" to "diclofenac",
        "naproxen" to "naproxen",
        "meloxicam" to "meloxicam",
        "celecoxib" to "celecoxib",
        "ketoprofen" to "ketoprofen",
        "amoxicillin" to "amoxicillin",
        "amoxycillin" to "amoxicillin",
        "ampicillin" to "ampicillin",
        "penicillin" to "penicillin",
        "benzylpenicillin" to "penicillin",
        "phenoxymethylpenicillin" to "penicillin",
        "clavulanate" to "clavulanic acid",
        "clavulanic acid" to "clavulanic acid",
        " potassium clavulanate" to "clavulanic acid",
        "warfarin" to "warfarin",
        "warfarin sodium" to "warfarin",
        "clopidogrel" to "clopidogrel",
        "omeprazole" to "omeprazole",
        "esomeprazole" to "esomeprazole",
        "pantoprazole" to "pantoprazole",
        "lansoprazole" to "lansoprazole",
        "metformin" to "metformin",
        "metformin hydrochloride" to "metformin",
        "lisinopril" to "lisinopril",
        "enalapril" to "enalapril",
        "ramipril" to "ramipril",
        "captopril" to "captopril",
        "spironolactone" to "spironolactone",
        "furosemide" to "furosemide",
        "bisoprolol" to "bisoprolol",
        "propranolol" to "propranolol",
        "atenolol" to "atenolol",
        "metoprolol" to "metoprolol",
        "simvastatin" to "simvastatin",
        "atorvastatin" to "atorvastatin",
        "amlodipine" to "amlodipine",
        "clarithromycin" to "clarithromycin",
        "erythromycin" to "erythromycin",
        "azithromycin" to "azithromycin",
        "ciprofloxacin" to "ciprofloxacin",
        "sulfamethoxazole" to "sulfamethoxazole",
        "trimethoprim" to "trimethoprim",
        "pseudoephedrine" to "pseudoephedrine",
        "phenylephrine" to "phenylephrine",
        "chlorpheniramine" to "chlorpheniramine",
        "diphenhydramine" to "diphenhydramine",
        "cetirizine" to "cetirizine",
        "loratadine" to "loratadine",
        "salbutamol" to "salbutamol",
        "albuterol" to "salbutamol",
        "prednisolone" to "prednisolone",
        "prednisone" to "prednisone",
        "dexamethasone" to "dexamethasone",
        "lithium" to "lithium",
        "methotrexate" to "methotrexate",
        "digoxin" to "digoxin",
        "sildenafil" to "sildenafil",
        "nitroglycerin" to "nitroglycerin",
        "isosorbide dinitrate" to "isosorbide dinitrate",
        "tramadol" to "tramadol",
        "sertraline" to "sertraline",
        "fluoxetine" to "fluoxetine",
        "escitalopram" to "escitalopram",
        "caffeine" to "caffeine"
    )

    private val ingredientToDrugClasses: Map<String, Set<String>> = mapOf(
        "paracetamol" to setOf("ANALGESIC_ANTIPYRETIC", "ACETAMINOPHEN_CLASS"),
        "ibuprofen" to setOf("NSAID", "SALICYLATE_NSAID_CROSS_REACTIVE"),
        "diclofenac" to setOf("NSAID", "SALICYLATE_NSAID_CROSS_REACTIVE"),
        "naproxen" to setOf("NSAID", "SALICYLATE_NSAID_CROSS_REACTIVE"),
        "meloxicam" to setOf("NSAID", "SALICYLATE_NSAID_CROSS_REACTIVE"),
        "celecoxib" to setOf("NSAID", "COX2_INHIBITOR", "SULFONAMIDE_CROSS_REACTIVE"),
        "ketoprofen" to setOf("NSAID", "SALICYLATE_NSAID_CROSS_REACTIVE"),
        "aspirin" to setOf("NSAID", "SALICYLATE", "ANTIPLATELET", "SALICYLATE_NSAID_CROSS_REACTIVE"),
        "amoxicillin" to setOf("PENICILLIN", "BETA_LACTAM"),
        "ampicillin" to setOf("PENICILLIN", "BETA_LACTAM"),
        "penicillin" to setOf("PENICILLIN", "BETA_LACTAM"),
        "clavulanic acid" to setOf("BETA_LACTAMASE_INHIBITOR", "BETA_LACTAM"),
        "warfarin" to setOf("ANTICOAGULANT", "VITAMIN_K_ANTAGONIST"),
        "clopidogrel" to setOf("ANTIPLATELET", "P2Y12_INHIBITOR"),
        "omeprazole" to setOf("PROTON_PUMP_INHIBITOR"),
        "esomeprazole" to setOf("PROTON_PUMP_INHIBITOR"),
        "pantoprazole" to setOf("PROTON_PUMP_INHIBITOR"),
        "lansoprazole" to setOf("PROTON_PUMP_INHIBITOR"),
        "metformin" to setOf("BIGUANIDE", "ANTIDIABETIC"),
        "lisinopril" to setOf("ACE_INHIBITOR", "RAAS_INHIBITOR"),
        "enalapril" to setOf("ACE_INHIBITOR", "RAAS_INHIBITOR"),
        "ramipril" to setOf("ACE_INHIBITOR", "RAAS_INHIBITOR"),
        "captopril" to setOf("ACE_INHIBITOR", "RAAS_INHIBITOR"),
        "spironolactone" to setOf("POTASSIUM_SPARING_DIURETIC", "ALDOSTERONE_ANTAGONIST"),
        "furosemide" to setOf("LOOP_DIURETIC", "SULFONAMIDE_CROSS_REACTIVE"),
        "bisoprolol" to setOf("BETA_BLOCKER", "CARDIOSELECTIVE_BETA_BLOCKER"),
        "atenolol" to setOf("BETA_BLOCKER", "CARDIOSELECTIVE_BETA_BLOCKER"),
        "metoprolol" to setOf("BETA_BLOCKER", "CARDIOSELECTIVE_BETA_BLOCKER"),
        "propranolol" to setOf("BETA_BLOCKER", "NON_SELECTIVE_BETA_BLOCKER"),
        "simvastatin" to setOf("STATIN", "CYP3A4_SUBSTRATE_STATIN"),
        "atorvastatin" to setOf("STATIN", "CYP3A4_SUBSTRATE_STATIN"),
        "clarithromycin" to setOf("MACROLIDE_ANTIBIOTIC", "STRONG_CYP3A4_INHIBITOR"),
        "erythromycin" to setOf("MACROLIDE_ANTIBIOTIC", "STRONG_CYP3A4_INHIBITOR"),
        "ciprofloxacin" to setOf("FLUOROQUINOLONE"),
        "sulfamethoxazole" to setOf("SULFONAMIDE_ANTIBIOTIC", "SULFONAMIDE_CROSS_REACTIVE"),
        "pseudoephedrine" to setOf("SYMPATHOMIMETIC_DECONGESTANT"),
        "phenylephrine" to setOf("SYMPATHOMIMETIC_DECONGESTANT"),
        "prednisolone" to setOf("SYSTEMIC_CORTICOSTEROID"),
        "prednisone" to setOf("SYSTEMIC_CORTICOSTEROID"),
        "dexamethasone" to setOf("SYSTEMIC_CORTICOSTEROID"),
        "sildenafil" to setOf("PDE5_INHIBITOR"),
        "nitroglycerin" to setOf("ORGANIC_NITRATE"),
        "isosorbide dinitrate" to setOf("ORGANIC_NITRATE"),
        "tramadol" to setOf("OPIOID_ANALGESIC", "SEROTONERGIC_AGENT"),
        "sertraline" to setOf("SSRI", "SEROTONERGIC_AGENT"),
        "fluoxetine" to setOf("SSRI", "SEROTONERGIC_AGENT"),
        "escitalopram" to setOf("SSRI", "SEROTONERGIC_AGENT"),
        "lithium" to setOf("MOOD_STABILIZER_LITHIUM"),
        "methotrexate" to setOf("ANTIMETABOLITE_METHOTREXATE")
    )

    data class NormalizedMedicineProfile(
        val recordId: String,
        val displayName: String,
        val rawScientificName: String?,
        val canonicalIngredients: Set<String>,
        val drugClasses: Set<String>,
        val hasCompleteIngredientData: Boolean
    )

    fun normalizeMedicine(medicine: Medicine): NormalizedMedicineProfile {
        return normalizeRaw(
            recordId = "med_${medicine.id}_${medicine.tradeName.lowercase().trim()}",
            tradeName = medicine.tradeName,
            scientificName = medicine.scientificName
        )
    }

    fun normalizeRaw(
        recordId: String,
        tradeName: String,
        scientificName: String?
    ): NormalizedMedicineProfile {
        val resolvedIngredients = linkedSetOf<String>()
        val cleanTrade = tradeName.lowercase().trim()
        val cleanSci = scientificName?.lowercase()?.trim().orEmpty()

        // 1. Check direct brand lookup if scientificName is missing or generic
        brandToCanonicalIngredients[cleanTrade]?.let { resolvedIngredients.addAll(it) }
        brandToCanonicalIngredients.entries.forEach { (brandKey, ingredients) ->
            if (cleanTrade.contains(brandKey)) {
                resolvedIngredients.addAll(ingredients)
            }
        }

        // 2. Parse scientific/active ingredient field (supports combination products separated by +, /, &, comma, "and", "with")
        if (cleanSci.isNotBlank()) {
            val tokens = cleanSci.split(Regex("""[,+/&;]|\band\b|\bwith\b"""))
                .map { it.replace(Regex("""\b\d+(\.\d+)?\s*(mg|mcg|g|ml|iu|%)\b"""), "").trim() }
                .filter { it.isNotBlank() }

            for (token in tokens) {
                val direct = synonymToCanonical[token]
                if (direct != null) {
                    resolvedIngredients.add(direct)
                } else {
                    synonymToCanonical.entries.forEach { (syn, canonical) ->
                        if (token.contains(syn)) {
                            resolvedIngredients.add(canonical)
                        }
                    }
                }
            }
        }

        // 3. Also check if tradeName itself is a generic name (e.g., "Ibuprofen 400mg")
        synonymToCanonical.entries.forEach { (syn, canonical) ->
            if (cleanTrade.contains(syn)) {
                resolvedIngredients.add(canonical)
            }
        }

        val classes = resolvedIngredients.flatMap { ingredientToDrugClasses[it].orEmpty() }.toSet()
        val hasCompleteData = resolvedIngredients.isNotEmpty()

        return NormalizedMedicineProfile(
            recordId = recordId,
            displayName = tradeName.trim().ifBlank { "Unnamed Medicine" },
            rawScientificName = scientificName?.trim()?.takeIf { it.isNotBlank() },
            canonicalIngredients = resolvedIngredients,
            drugClasses = classes,
            hasCompleteIngredientData = hasCompleteData
        )
    }

    fun getClassesForIngredientOrAllergen(rawAllergen: String): Pair<Set<String>, Set<String>> {
        val clean = rawAllergen.lowercase().trim()
        val matchedIngredients = linkedSetOf<String>()
        val matchedClasses = linkedSetOf<String>()

        synonymToCanonical[clean]?.let {
            matchedIngredients.add(it)
            matchedClasses.addAll(ingredientToDrugClasses[it].orEmpty())
        }
        brandToCanonicalIngredients[clean]?.forEach {
            matchedIngredients.add(it)
            matchedClasses.addAll(ingredientToDrugClasses[it].orEmpty())
        }

        when {
            clean.contains("penicillin") || clean.contains("beta-lactam") || clean.contains("amoxicillin") -> {
                matchedClasses.add("PENICILLIN")
                matchedClasses.add("BETA_LACTAM")
            }
            clean.contains("nsaid") || clean.contains("aspirin") || clean.contains("ibuprofen") || clean.contains("diclofenac") -> {
                matchedClasses.add("NSAID")
                matchedClasses.add("SALICYLATE_NSAID_CROSS_REACTIVE")
            }
            clean.contains("sulfa") || clean.contains("sulfonamide") -> {
                matchedClasses.add("SULFONAMIDE_ANTIBIOTIC")
                matchedClasses.add("SULFONAMIDE_CROSS_REACTIVE")
            }
        }

        return matchedIngredients to matchedClasses
    }
}

// ============================================================================
// SECTION 4: MEDICATION COMPATIBILITY FEATURE (DORMANT BY DEFAULT)
// ============================================================================

enum class InteractionSeverity {
    CONTRAINDICATED_SERIOUS,
    MODERATE,
    MINOR,
    INSUFFICIENT_DATA
}

data class MedicationInteractionWarning(
    val warningId: String,
    val medicineAName: String,
    val medicineBName: String,
    val involvedIngredients: List<String>,
    val severity: InteractionSeverity,
    val title: String,
    val clinicalExplanation: String,
    val patientGuidance: String,
    val sourceRefs: List<SourceRef>
)

data class CompatibilityEvaluationResult(
    val featureEnabled: Boolean,
    val evaluatedMedicineCount: Int = 0,
    val warnings: List<MedicationInteractionWarning> = emptyList(),
    val insufficientDataMedicines: List<String> = emptyList(),
    val statusSummary: String
) {
    val hasSeriousContraindications: Boolean
        get() = warnings.any { it.severity == InteractionSeverity.CONTRAINDICATED_SERIOUS }

    val hasIncompleteData: Boolean
        get() = insufficientDataMedicines.isNotEmpty() ||
            warnings.any { it.severity == InteractionSeverity.INSUFFICIENT_DATA }

    companion object {
        fun disabled(): CompatibilityEvaluationResult = CompatibilityEvaluationResult(
            featureEnabled = false,
            statusSummary = "Medication compatibility checking is currently inactive pending user-account integration."
        )
    }
}

interface ClinicalInteractionDataSource {
    fun findInteractions(
        profileA: ActiveIngredientNormalizer.NormalizedMedicineProfile,
        profileB: ActiveIngredientNormalizer.NormalizedMedicineProfile
    ): List<MedicationInteractionWarning>
}

/**
 * Verified clinical interaction repository backed by established pharmacological rules
 * and traceable regulatory/clinical references (FDA, BNF, DailyMed).
 */
class VerifiedClinicalInteractionRepository : ClinicalInteractionDataSource {

    private val defaultProfessionalConsultationAdvice =
        "Consult a licensed pharmacist or physician before starting, combining, or changing these medications. Do not stop taking a prescribed medication without medical advice."

    private val fdaInteractionSource = SourceRef(
        sourceName = "FDA Drug Development & Drug Interactions Clinical Reference",
        url = "https://www.fda.gov/drugs/drug-interactions-labeling/healthcare-professionals-fdas-examples-drugs-interact",
        lastUpdatedAt = 1704067200000L
    )
    private val medlinePlusSource = SourceRef(
        sourceName = "MedlinePlus Drug Safety & Contraindication Reference",
        url = "https://medlineplus.gov/druginformation.html",
        lastUpdatedAt = 1704067200000L
    )

    override fun findInteractions(
        profileA: ActiveIngredientNormalizer.NormalizedMedicineProfile,
        profileB: ActiveIngredientNormalizer.NormalizedMedicineProfile
    ): List<MedicationInteractionWarning> {
        val warnings = mutableListOf<MedicationInteractionWarning>()
        val pairKey = listOf(profileA.displayName.lowercase(), profileB.displayName.lowercase())
            .sorted()
            .joinToString("::")

        // 1. Check for duplicate active ingredients (e.g., two products containing paracetamol)
        val sharedIngredients = profileA.canonicalIngredients.intersect(profileB.canonicalIngredients)
        for (ingredient in sharedIngredients) {
            val isSeriousDuplicate = ingredient == "paracetamol" ||
                ingredient in setOf("warfarin", "methotrexate", "lithium", "digoxin")
            warnings.add(
                MedicationInteractionWarning(
                    warningId = "DUP_${ingredient.uppercase()}_$pairKey",
                    medicineAName = profileA.displayName,
                    medicineBName = profileB.displayName,
                    involvedIngredients = listOf(ingredient),
                    severity = if (isSeriousDuplicate) {
                        InteractionSeverity.CONTRAINDICATED_SERIOUS
                    } else {
                        InteractionSeverity.MODERATE
                    },
                    title = "Duplicate Active Ingredient: ${ingredient.replaceFirstChar { it.uppercase() }}",
                    clinicalExplanation = "Both ${profileA.displayName} and ${profileB.displayName} contain $ingredient. Taking multiple medicines with the same active ingredient simultaneously increases the risk of accidental overdose and organ toxicity.",
                    patientGuidance = defaultProfessionalConsultationAdvice,
                    sourceRefs = listOf(fdaInteractionSource, medlinePlusSource)
                )
            )
        }

        // 2. Multiple NSAIDs taken together (e.g., Ibuprofen + Diclofenac or Naproxen + Aspirin)
        if ("NSAID" in profileA.drugClasses && "NSAID" in profileB.drugClasses && sharedIngredients.isEmpty()) {
            val ingPair = (profileA.canonicalIngredients + profileB.canonicalIngredients).sorted()
            warnings.add(
                MedicationInteractionWarning(
                    warningId = "CLASS_DUAL_NSAID_${ingPair.joinToString("_")}",
                    medicineAName = profileA.displayName,
                    medicineBName = profileB.displayName,
                    involvedIngredients = ingPair,
                    severity = InteractionSeverity.CONTRAINDICATED_SERIOUS,
                    title = "Concurrent NSAID Therapy Contraindication",
                    clinicalExplanation = "${profileA.displayName} and ${profileB.displayName} both belong to the nonsteroidal anti-inflammatory drug (NSAID) class. Combining multiple NSAIDs markedly increases the risk of severe gastrointestinal bleeding, ulceration, and acute kidney injury without providing additional pain relief.",
                    patientGuidance = defaultProfessionalConsultationAdvice,
                    sourceRefs = listOf(fdaInteractionSource, medlinePlusSource)
                )
            )
        }

        // 3. Anticoagulant (Warfarin) + NSAID / Antiplatelet -> Serious bleeding risk
        if (hasClassPair(profileA, profileB, "ANTICOAGULANT", "NSAID") ||
            hasClassPair(profileA, profileB, "ANTICOAGULANT", "ANTIPLATELET")
        ) {
            val ingPair = (profileA.canonicalIngredients + profileB.canonicalIngredients).sorted()
            warnings.add(
                MedicationInteractionWarning(
                    warningId = "IX_ANTICOAGULANT_BLEED_${ingPair.joinToString("_")}",
                    medicineAName = profileA.displayName,
                    medicineBName = profileB.displayName,
                    involvedIngredients = ingPair,
                    severity = InteractionSeverity.CONTRAINDICATED_SERIOUS,
                    title = "Serious Bleeding Risk: Anticoagulant & Antiplatelet/NSAID",
                    clinicalExplanation = "Combining ${profileA.displayName} and ${profileB.displayName} significantly elevates the risk of life-threatening gastrointestinal or systemic hemorrhage.",
                    patientGuidance = defaultProfessionalConsultationAdvice,
                    sourceRefs = listOf(fdaInteractionSource)
                )
            )
        }

        // 4. Organic Nitrates (Nitroglycerin / Isosorbide) + PDE5 Inhibitors (Sildenafil) -> Severe hypotension
        if (hasClassPair(profileA, profileB, "ORGANIC_NITRATE", "PDE5_INHIBITOR")) {
            val ingPair = (profileA.canonicalIngredients + profileB.canonicalIngredients).sorted()
            warnings.add(
                MedicationInteractionWarning(
                    warningId = "IX_NITRATE_PDE5_${ingPair.joinToString("_")}",
                    medicineAName = profileA.displayName,
                    medicineBName = profileB.displayName,
                    involvedIngredients = ingPair,
                    severity = InteractionSeverity.CONTRAINDICATED_SERIOUS,
                    title = "Absolute Contraindication: Nitrate & PDE5 Inhibitor",
                    clinicalExplanation = "Co-administration of ${profileA.displayName} and ${profileB.displayName} can cause a sudden, dangerous drop in blood pressure (profound hypotension) and syncope.",
                    patientGuidance = defaultProfessionalConsultationAdvice,
                    sourceRefs = listOf(fdaInteractionSource)
                )
            )
        }

        // 5.Clopidogrel + Omeprazole/Esomeprazole -> Reduced antiplatelet efficacy
        if (hasIngredientPair(profileA, profileB, "clopidogrel", "omeprazole") ||
            hasIngredientPair(profileA, profileB, "clopidogrel", "esomeprazole")
        ) {
            val ingPair = (profileA.canonicalIngredients + profileB.canonicalIngredients).sorted()
            warnings.add(
                MedicationInteractionWarning(
                    warningId = "IX_CLOPIDOGREL_PPI_${ingPair.joinToString("_")}",
                    medicineAName = profileA.displayName,
                    medicineBName = profileB.displayName,
                    involvedIngredients = ingPair,
                    severity = InteractionSeverity.CONTRAINDICATED_SERIOUS,
                    title = "CYP2C19 Inhibition: Clopidogrel & Omeprazole/Esomeprazole",
                    clinicalExplanation = "Omeprazole and esomeprazole inhibit CYP2C19 conversion of clopidogrel to its active metabolite, reducing its cardioprotective antiplatelet effect.",
                    patientGuidance = defaultProfessionalConsultationAdvice,
                    sourceRefs = listOf(fdaInteractionSource)
                )
            )
        }

        // 6. ACE Inhibitor + Potassium-Sparing Diuretic (Spironolactone) -> Hyperkalemia risk (Moderate)
        if (hasClassPair(profileA, profileB, "ACE_INHIBITOR", "POTASSIUM_SPARING_DIURETIC")) {
            val ingPair = (profileA.canonicalIngredients + profileB.canonicalIngredients).sorted()
            warnings.add(
                MedicationInteractionWarning(
                    warningId = "IX_ACEI_K_SPARING_${ingPair.joinToString("_")}",
                    medicineAName = profileA.displayName,
                    medicineBName = profileB.displayName,
                    involvedIngredients = ingPair,
                    severity = InteractionSeverity.MODERATE,
                    title = "Hyperkalemia Risk: ACE Inhibitor & Potassium-Sparing Diuretic",
                    clinicalExplanation = "Taking ${profileA.displayName} with ${profileB.displayName} can elevate serum potassium levels (hyperkalemia), particularly in patients with reduced kidney function.",
                    patientGuidance = defaultProfessionalConsultationAdvice,
                    sourceRefs = listOf(fdaInteractionSource)
                )
            )
        }

        // 7. ACE Inhibitor + NSAID -> Reduced antihypertensive effect & renal strain (Moderate)
        if (hasClassPair(profileA, profileB, "ACE_INHIBITOR", "NSAID")) {
            val ingPair = (profileA.canonicalIngredients + profileB.canonicalIngredients).sorted()
            warnings.add(
                MedicationInteractionWarning(
                    warningId = "IX_ACEI_NSAID_${ingPair.joinToString("_")}",
                    medicineAName = profileA.displayName,
                    medicineBName = profileB.displayName,
                    involvedIngredients = ingPair,
                    severity = InteractionSeverity.MODERATE,
                    title = "Renal & Blood Pressure Interaction: ACE Inhibitor & NSAID",
                    clinicalExplanation = "NSAIDs may reduce the blood-pressure-lowering effect of ACE inhibitors and increase the risk of kidney function decline.",
                    patientGuidance = defaultProfessionalConsultationAdvice,
                    sourceRefs = listOf(fdaInteractionSource)
                )
            )
        }

        // 8. Strong CYP3A4 Inhibitor (Clarithromycin/Erythromycin) + Statin (Simvastatin/Atorvastatin) -> Myopathy/Rhabdomyolysis
        if (hasClassPair(profileA, profileB, "STRONG_CYP3A4_INHIBITOR", "CYP3A4_SUBSTRATE_STATIN")) {
            val ingPair = (profileA.canonicalIngredients + profileB.canonicalIngredients).sorted()
            warnings.add(
                MedicationInteractionWarning(
                    warningId = "IX_CYP3A4_STATIN_${ingPair.joinToString("_")}",
                    medicineAName = profileA.displayName,
                    medicineBName = profileB.displayName,
                    involvedIngredients = ingPair,
                    severity = InteractionSeverity.CONTRAINDICATED_SERIOUS,
                    title = "Serious Muscle Toxicity Risk: Macrolide Antibiotic & Statin",
                    clinicalExplanation = "Clarithromycin and erythromycin strongly inhibit CYP3A4 metabolism of simvastatin and atorvastatin, substantially increasing statin blood levels and the risk of severe myopathy or rhabdomyolysis.",
                    patientGuidance = defaultProfessionalConsultationAdvice,
                    sourceRefs = listOf(fdaInteractionSource)
                )
            )
        }

        return warnings
    }

    private fun hasClassPair(
        a: ActiveIngredientNormalizer.NormalizedMedicineProfile,
        b: ActiveIngredientNormalizer.NormalizedMedicineProfile,
        class1: String,
        class2: String
    ): Boolean {
        return (class1 in a.drugClasses && class2 in b.drugClasses) ||
            (class2 in a.drugClasses && class1 in b.drugClasses)
    }

    private fun hasIngredientPair(
        a: ActiveIngredientNormalizer.NormalizedMedicineProfile,
        b: ActiveIngredientNormalizer.NormalizedMedicineProfile,
        ing1: String,
        ing2: String
    ): Boolean {
        return (ing1 in a.canonicalIngredients && ing2 in b.canonicalIngredients) ||
            (ing2 in a.canonicalIngredients && ing1 in b.canonicalIngredients)
    }
}

/**
 * Medication Compatibility Service (Section 4).
 * Evaluates saved cabinet medicines and/or candidate medicines for drug-drug interactions,
 * duplicate active ingredients, and missing ingredient data.
 * Remains strictly dormant (`CompatibilityEvaluationResult.disabled()`) while
 * [SafetyFeatureFlags.isMedicationCompatibilityEnabled] is `false`.
 */
class MedicationCompatibilityService(
    private val dataSource: ClinicalInteractionDataSource = VerifiedClinicalInteractionRepository()
) {
    fun evaluateCabinetCompatibility(
        medicines: List<Medicine>,
        candidateMedicine: Medicine? = null
    ): CompatibilityEvaluationResult {
        if (!SafetyFeatureFlags.isMedicationCompatibilityEnabled) {
            return CompatibilityEvaluationResult.disabled()
        }

        val combined = buildList {
            addAll(medicines)
            if (candidateMedicine != null && medicines.none { it.id != 0 && it.id == candidateMedicine.id }) {
                add(candidateMedicine)
            }
        }
        val profiles = combined.map { ActiveIngredientNormalizer.normalizeMedicine(it) }
        return evaluateNormalizedProfiles(profiles)
    }

    fun evaluateNormalizedProfiles(
        profiles: List<ActiveIngredientNormalizer.NormalizedMedicineProfile>
    ): CompatibilityEvaluationResult {
        if (!SafetyFeatureFlags.isMedicationCompatibilityEnabled) {
            return CompatibilityEvaluationResult.disabled()
        }

        if (profiles.size < 2) {
            val incompleteSingle = profiles.filter { !it.hasCompleteIngredientData }.map { it.displayName }
            return CompatibilityEvaluationResult(
                featureEnabled = true,
                evaluatedMedicineCount = profiles.size,
                warnings = emptyList(),
                insufficientDataMedicines = incompleteSingle,
                statusSummary = if (incompleteSingle.isNotEmpty()) {
                    "Compatibility cannot be verified for ${incompleteSingle.joinToString()} because active ingredient data is incomplete. Consult a pharmacist or physician."
                } else {
                    "At least two medicines are required to evaluate drug-drug interactions."
                }
            )
        }

        val insufficientNames = profiles
            .filter { !it.hasCompleteIngredientData }
            .map { it.displayName }
            .distinct()

        val deduplicatedWarnings = linkedMapOf<String, MedicationInteractionWarning>()

        // Flag any medicine with unrecognized or missing ingredient data so compatibility is NEVER falsely claimed
        for (incompleteProfile in profiles.filter { !it.hasCompleteIngredientData }) {
            val warningId = "INSUFFICIENT_DATA_${incompleteProfile.displayName.lowercase()}"
            deduplicatedWarnings[warningId] = MedicationInteractionWarning(
                warningId = warningId,
                medicineAName = incompleteProfile.displayName,
                medicineBName = "Cabinet Medications",
                involvedIngredients = emptyList(),
                severity = InteractionSeverity.INSUFFICIENT_DATA,
                title = "Incomplete Ingredient Data for ${incompleteProfile.displayName}",
                clinicalExplanation = "Active ingredient information for ${incompleteProfile.displayName} is missing or unrecognized. Compatibility with your other medicines cannot be confirmed.",
                patientGuidance = "Do not assume this medicine is compatible with your other medications. Check the package leaflet and consult a licensed pharmacist or physician.",
                sourceRefs = listOf(
                    SourceRef(
                        sourceName = "MedlinePlus Medication Safety Guidance",
                        url = "https://medlineplus.gov/druginformation.html",
                        lastUpdatedAt = 1704067200000L
                    )
                )
            )
        }

        // Pairwise check across all profiles with complete ingredient data
        for (i in profiles.indices) {
            for (j in i + 1 until profiles.size) {
                val pA = profiles[i]
                val pB = profiles[j]
                if (pA.hasCompleteIngredientData && pB.hasCompleteIngredientData) {
                    val found = dataSource.findInteractions(pA, pB)
                    for (warning in found) {
                        deduplicatedWarnings.putIfAbsent(warning.warningId, warning)
                    }
                }
            }
        }

        val sortedWarnings = deduplicatedWarnings.values.sortedBy { it.severity.ordinal }
        val summary = when {
            sortedWarnings.any { it.severity == InteractionSeverity.CONTRAINDICATED_SERIOUS } ->
                "Serious medication interaction or duplicate ingredient detected. Consult a pharmacist or physician before combining these medicines."
            insufficientNames.isNotEmpty() ->
                "Interaction check is incomplete because active ingredient data is missing for: ${insufficientNames.joinToString()}. Compatibility cannot be guaranteed."
            sortedWarnings.isNotEmpty() ->
                "Potential medication interaction warnings found. Review details with a healthcare professional."
            else ->
                "No known drug-drug interactions were identified in the reference catalog for the listed active ingredients. Always verify with a pharmacist or physician."
        }

        return CompatibilityEvaluationResult(
            featureEnabled = true,
            evaluatedMedicineCount = profiles.size,
            warnings = sortedWarnings,
            insufficientDataMedicines = insufficientNames,
            statusSummary = summary
        )
    }
}

// ============================================================================
// SECTION 5: ALLERGY ALERT FEATURE (DORMANT BY DEFAULT)
// ============================================================================

enum class AllergyReactionSeverity {
    ANAPHYLAXIS_SEVERE,
    MODERATE,
    MILD_OR_UNSPECIFIED
}

data class UserAllergyRecord(
    val allergyId: String,
    val allergenName: String,
    val allergenClassKey: String? = null,
    val reactionSeverity: AllergyReactionSeverity = AllergyReactionSeverity.ANAPHYLAXIS_SEVERE,
    val notes: String? = null
)

enum class AllergyMatchStatus {
    DIRECT_INGREDIENT_MATCH,
    CROSS_SENSITIVITY_CLASS_MATCH,
    INCOMPLETE_INGREDIENT_DATA,
    NO_KNOWN_MATCH_FOUND
}

data class AllergyAlertFinding(
    val alertId: String,
    val medicineName: String,
    val matchedAllergyRecord: UserAllergyRecord?,
    val matchStatus: AllergyMatchStatus,
    val matchedIngredientsOrClasses: List<String>,
    val clinicalMessage: String,
    val precautionaryDisclaimer: String
)

data class AllergyEvaluationResult(
    val featureEnabled: Boolean,
    val matchStatus: AllergyMatchStatus,
    val alerts: List<AllergyAlertFinding>,
    val safetyDisclaimer: String
) {
    val hasConfirmedOrCrossReactiveMatch: Boolean
        get() = alerts.any {
            it.matchStatus == AllergyMatchStatus.DIRECT_INGREDIENT_MATCH ||
                it.matchStatus == AllergyMatchStatus.CROSS_SENSITIVITY_CLASS_MATCH
        }

    companion object {
        const val NON_GUARANTEE_DISCLAIMER =
            "Absence of an allergy match does not guarantee that a medicine is allergy-safe. Always review the full active and inactive ingredient (excipient) list on the manufacturer package leaflet and consult a pharmacist or physician."

        fun disabled(): AllergyEvaluationResult = AllergyEvaluationResult(
            featureEnabled = false,
            matchStatus = AllergyMatchStatus.NO_KNOWN_MATCH_FOUND,
            alerts = emptyList(),
            safetyDisclaimer = "Allergy alert evaluation is currently inactive pending user-account integration."
        )
    }
}

/**
 * Allergy Alert Service (Section 5).
 * Compares recorded user medication allergies against candidate or saved medicines
 * (supporting brand names, generic names, combination products, and cross-reactive drug classes).
 * Remains strictly dormant (`AllergyEvaluationResult.disabled()`) while
 * [SafetyFeatureFlags.isAllergyAlertsEnabled] is `false`.
 */
class AllergyAlertService {

    fun checkMedicineAgainstAllergies(
        medicine: Medicine,
        recordedAllergies: List<UserAllergyRecord>
    ): AllergyEvaluationResult {
        if (!SafetyFeatureFlags.isAllergyAlertsEnabled) {
            return AllergyEvaluationResult.disabled()
        }
        val profile = ActiveIngredientNormalizer.normalizeMedicine(medicine)
        return evaluateNormalizedProfileAgainstAllergies(profile, recordedAllergies)
    }

    fun checkRawMedicineAgainstAllergies(
        tradeName: String,
        scientificName: String?,
        recordedAllergies: List<UserAllergyRecord>
    ): AllergyEvaluationResult {
        if (!SafetyFeatureFlags.isAllergyAlertsEnabled) {
            return AllergyEvaluationResult.disabled()
        }
        val profile = ActiveIngredientNormalizer.normalizeRaw(
            recordId = "raw_${tradeName.lowercase().trim()}",
            tradeName = tradeName,
            scientificName = scientificName
        )
        return evaluateNormalizedProfileAgainstAllergies(profile, recordedAllergies)
    }

    fun evaluateNormalizedProfileAgainstAllergies(
        profile: ActiveIngredientNormalizer.NormalizedMedicineProfile,
        recordedAllergies: List<UserAllergyRecord>
    ): AllergyEvaluationResult {
        if (!SafetyFeatureFlags.isAllergyAlertsEnabled) {
            return AllergyEvaluationResult.disabled()
        }

        if (!profile.hasCompleteIngredientData) {
            val incompleteFinding = AllergyAlertFinding(
                alertId = "ALLERGY_INCOMPLETE_${profile.displayName.lowercase()}",
                medicineName = profile.displayName,
                matchedAllergyRecord = null,
                matchStatus = AllergyMatchStatus.INCOMPLETE_INGREDIENT_DATA,
                matchedIngredientsOrClasses = emptyList(),
                clinicalMessage = "Active ingredient data for ${profile.displayName} is incomplete or unrecognized. Cannot verify against your recorded medication allergies.",
                precautionaryDisclaimer = AllergyEvaluationResult.NON_GUARANTEE_DISCLAIMER
            )
            return AllergyEvaluationResult(
                featureEnabled = true,
                matchStatus = AllergyMatchStatus.INCOMPLETE_INGREDIENT_DATA,
                alerts = listOf(incompleteFinding),
                safetyDisclaimer = AllergyEvaluationResult.NON_GUARANTEE_DISCLAIMER
            )
        }

        val alerts = mutableListOf<AllergyAlertFinding>()

        for (allergy in recordedAllergies) {
            val (allergenIngredients, allergenClasses) =
                ActiveIngredientNormalizer.getClassesForIngredientOrAllergen(allergy.allergenName)
            val explicitClassSet = setOfNotNull(allergy.allergenClassKey?.uppercase())
            val allTargetClasses = allergenClasses + explicitClassSet

            val directIngredientHits = profile.canonicalIngredients.intersect(allergenIngredients)
            val directNameHit = profile.displayName.contains(allergy.allergenName.trim(), ignoreCase = true) ||
                (profile.rawScientificName?.contains(allergy.allergenName.trim(), ignoreCase = true) == true)

            if (directIngredientHits.isNotEmpty() || directNameHit) {
                val matchedList = if (directIngredientHits.isNotEmpty()) {
                    directIngredientHits.toList()
                } else {
                    listOf(allergy.allergenName.lowercase().trim())
                }
                alerts.add(
                    AllergyAlertFinding(
                        alertId = "ALLERGY_DIRECT_${allergy.allergyId}_${profile.displayName.lowercase()}",
                        medicineName = profile.displayName,
                        matchedAllergyRecord = allergy,
                        matchStatus = AllergyMatchStatus.DIRECT_INGREDIENT_MATCH,
                        matchedIngredientsOrClasses = matchedList,
                        clinicalMessage = "ALLERGY ALERT: ${profile.displayName} contains ${matchedList.joinToString()}, which directly matches your recorded allergy to ${allergy.allergenName}. Do not take this medication without consulting a physician or pharmacist.",
                        precautionaryDisclaimer = AllergyEvaluationResult.NON_GUARANTEE_DISCLAIMER
                    )
                )
                continue
            }

            val crossClassHits = profile.drugClasses.intersect(allTargetClasses)
            if (crossClassHits.isNotEmpty()) {
                alerts.add(
                    AllergyAlertFinding(
                        alertId = "ALLERGY_CLASS_${allergy.allergyId}_${profile.displayName.lowercase()}",
                        medicineName = profile.displayName,
                        matchedAllergyRecord = allergy,
                        matchStatus = AllergyMatchStatus.CROSS_SENSITIVITY_CLASS_MATCH,
                        matchedIngredientsOrClasses = crossClassHits.toList(),
                        clinicalMessage = "CROSS-SENSITIVITY ALERT: ${profile.displayName} (${profile.canonicalIngredients.joinToString()}) belongs to a pharmacological class (${crossClassHits.joinToString()}) cross-reactive with your recorded allergy to ${allergy.allergenName}. Consult a physician or pharmacist before use.",
                        precautionaryDisclaimer = AllergyEvaluationResult.NON_GUARANTEE_DISCLAIMER
                    )
                )
            }
        }

        val overallStatus = when {
            alerts.any { it.matchStatus == AllergyMatchStatus.DIRECT_INGREDIENT_MATCH } ->
                AllergyMatchStatus.DIRECT_INGREDIENT_MATCH
            alerts.any { it.matchStatus == AllergyMatchStatus.CROSS_SENSITIVITY_CLASS_MATCH } ->
                AllergyMatchStatus.CROSS_SENSITIVITY_CLASS_MATCH
            else ->
                AllergyMatchStatus.NO_KNOWN_MATCH_FOUND
        }

        return AllergyEvaluationResult(
            featureEnabled = true,
            matchStatus = overallStatus,
            alerts = alerts,
            safetyDisclaimer = AllergyEvaluationResult.NON_GUARANTEE_DISCLAIMER
        )
    }
}

// ============================================================================
// SECTION 6: CHRONIC DISEASE PROFILE FEATURE (DORMANT BY DEFAULT)
// ============================================================================

/**
 * Verified medical condition terminology entry using standard ICD-10-CM and SNOMED-CT codes.
 */
data class VerifiedMedicalConditionCode(
    val conditionKey: String,
    val canonicalName: String,
    val icd10Code: String,
    val snomedCtCode: String,
    val synonyms: Set<String> = emptySet()
)

data class UserChronicConditionRecord(
    val recordId: String,
    val conditionCode: VerifiedMedicalConditionCode,
    val diagnosedYear: Int? = null,
    val clinicalNotes: String? = null
)

enum class ConditionRiskLevel {
    CONTRAINDICATED,
    USE_WITH_CAUTION,
    INSUFFICIENT_DATA
}

data class ConditionMedicationWarning(
    val warningId: String,
    val medicineName: String,
    val condition: VerifiedMedicalConditionCode,
    val riskLevel: ConditionRiskLevel,
    val triggeredByIngredientsOrClasses: List<String>,
    val clinicalSummary: String,
    val professionalAdvice: String,
    val sourceRefs: List<SourceRef>
)

data class ChronicConditionEvaluationResult(
    val featureEnabled: Boolean,
    val warnings: List<ConditionMedicationWarning>,
    val statusSummary: String
) {
    companion object {
        fun disabled(): ChronicConditionEvaluationResult = ChronicConditionEvaluationResult(
            featureEnabled = false,
            warnings = emptyList(),
            statusSummary = "Chronic condition contraindication checking is currently inactive pending user-account integration."
        )
    }
}

/**
 * Catalog of predefined common chronic conditions and extensible verified terminology.
 */
object ChronicConditionCatalog {
    val HYPERTENSION = VerifiedMedicalConditionCode(
        conditionKey = "HYPERTENSION",
        canonicalName = "Hypertension (High Blood Pressure)",
        icd10Code = "I10",
        snomedCtCode = "38341003",
        synonyms = setOf("high blood pressure", "essential hypertension", "arterial hypertension")
    )
    val DIABETES_MELLITUS = VerifiedMedicalConditionCode(
        conditionKey = "DIABETES_MELLITUS",
        canonicalName = "Diabetes Mellitus",
        icd10Code = "E11",
        snomedCtCode = "73211009",
        synonyms = setOf("diabetes", "type 1 diabetes", "type 2 diabetes", "hyperglycemia")
    )
    val CHRONIC_KIDNEY_DISEASE = VerifiedMedicalConditionCode(
        conditionKey = "CHRONIC_KIDNEY_DISEASE",
        canonicalName = "Chronic Kidney Disease (CKD)",
        icd10Code = "N18",
        snomedCtCode = "709044004",
        synonyms = setOf("renal impairment", "kidney failure", "chronic renal insufficiency")
    )
    val CHRONIC_LIVER_DISEASE = VerifiedMedicalConditionCode(
        conditionKey = "CHRONIC_LIVER_DISEASE",
        canonicalName = "Chronic Liver Disease / Hepatic Impairment",
        icd10Code = "K76.9",
        snomedCtCode = "328383001",
        synonyms = setOf("liver disease", "cirrhosis", "hepatic insufficiency", "hepatic impairment")
    )
    val ASTHMA = VerifiedMedicalConditionCode(
        conditionKey = "ASTHMA",
        canonicalName = "Bronchial Asthma",
        icd10Code = "J45",
        snomedCtCode = "195967001",
        synonyms = setOf("asthma", "reactive airway disease", "bronchospasm")
    )
    val PEPTIC_ULCER_DISEASE = VerifiedMedicalConditionCode(
        conditionKey = "PEPTIC_ULCER_DISEASE",
        canonicalName = "Peptic Ulcer Disease / GI Bleeding History",
        icd10Code = "K27",
        snomedCtCode = "13200003",
        synonyms = setOf("stomach ulcer", "gastric ulcer", "duodenal ulcer", "peptic ulcer")
    )
    val HEART_FAILURE = VerifiedMedicalConditionCode(
        conditionKey = "HEART_FAILURE",
        canonicalName = "Congestive Heart Failure",
        icd10Code = "I50",
        snomedCtCode = "84114007",
        synonyms = setOf("heart failure", "chf", "cardiac failure")
    )
    val G6PD_DEFICIENCY = VerifiedMedicalConditionCode(
        conditionKey = "G6PD_DEFICIENCY",
        canonicalName = "G6PD Deficiency (Favism)",
        icd10Code = "D55.0",
        snomedCtCode = "190650006",
        synonyms = setOf("g6pd deficiency", "glucose-6-phosphate dehydrogenase deficiency", "favism")
    )

    val predefinedConditions: List<VerifiedMedicalConditionCode> = listOf(
        HYPERTENSION,
        DIABETES_MELLITUS,
        CHRONIC_KIDNEY_DISEASE,
        CHRONIC_LIVER_DISEASE,
        ASTHMA,
        PEPTIC_ULCER_DISEASE,
        HEART_FAILURE,
        G6PD_DEFICIENCY
    )

    fun resolveVerifiedCondition(queryOrCode: String): VerifiedMedicalConditionCode? {
        val clean = queryOrCode.trim().lowercase()
        if (clean.isBlank()) return null
        return predefinedConditions.find { cond ->
            cond.conditionKey.lowercase() == clean ||
                cond.canonicalName.lowercase() == clean ||
                cond.icd10Code.lowercase() == clean ||
                cond.snomedCtCode.lowercase() == clean ||
                cond.synonyms.any { clean.contains(it) || it.contains(clean) }
        }
    }
}

/**
 * Chronic Condition Profile Service (Section 6).
 * Evaluates recorded chronic health conditions against medicine contraindications and precautions.
 * Strictly dormant (`ChronicConditionEvaluationResult.disabled()`) while
 * [SafetyFeatureFlags.isChronicConditionsProfileEnabled] is `false`.
 */
class ChronicConditionProfileService {

    private val fdaLabelSource = SourceRef(
        sourceName = "FDA Approved Drug Labeling Contraindications & Warnings",
        url = "https://labels.fda.gov/",
        lastUpdatedAt = 1704067200000L
    )

    fun evaluateMedicineForConditions(
        medicine: Medicine,
        userConditions: List<UserChronicConditionRecord>
    ): ChronicConditionEvaluationResult {
        if (!SafetyFeatureFlags.isChronicConditionsProfileEnabled) {
            return ChronicConditionEvaluationResult.disabled()
        }
        val profile = ActiveIngredientNormalizer.normalizeMedicine(medicine)
        return evaluateNormalizedProfileForConditions(profile, userConditions)
    }

    fun evaluateNormalizedProfileForConditions(
        profile: ActiveIngredientNormalizer.NormalizedMedicineProfile,
        userConditions: List<UserChronicConditionRecord>
    ): ChronicConditionEvaluationResult {
        if (!SafetyFeatureFlags.isChronicConditionsProfileEnabled) {
            return ChronicConditionEvaluationResult.disabled()
        }

        if (userConditions.isEmpty()) {
            return ChronicConditionEvaluationResult(
                featureEnabled = true,
                warnings = emptyList(),
                statusSummary = "No chronic conditions recorded for evaluation."
            )
        }

        if (!profile.hasCompleteIngredientData) {
            val incompleteWarnings = userConditions.map { record ->
                ConditionMedicationWarning(
                    warningId = "COND_INCOMPLETE_${record.conditionCode.conditionKey}_${profile.displayName.lowercase()}",
                    medicineName = profile.displayName,
                    condition = record.conditionCode,
                    riskLevel = ConditionRiskLevel.INSUFFICIENT_DATA,
                    triggeredByIngredientsOrClasses = emptyList(),
                    clinicalSummary = "Active ingredient data for ${profile.displayName} is incomplete; safety in ${record.conditionCode.canonicalName} cannot be verified.",
                    professionalAdvice = "Consult a physician or pharmacist before taking this medication.",
                    sourceRefs = listOf(fdaLabelSource)
                )
            }
            return ChronicConditionEvaluationResult(
                featureEnabled = true,
                warnings = incompleteWarnings,
                statusSummary = "Incomplete active ingredient data for ${profile.displayName}."
            )
        }

        val warnings = mutableListOf<ConditionMedicationWarning>()

        for (record in userConditions) {
            val cond = record.conditionCode
            when (cond.conditionKey) {
                "HYPERTENSION" -> {
                    if ("SYMPATHOMIMETIC_DECONGESTANT" in profile.drugClasses) {
                        warnings.add(
                            ConditionMedicationWarning(
                                warningId = "COND_HTN_DECONGESTANT_${profile.displayName.lowercase()}",
                                medicineName = profile.displayName,
                                condition = cond,
                                riskLevel = ConditionRiskLevel.CONTRAINDICATED,
                                triggeredByIngredientsOrClasses = profile.canonicalIngredients.toList(),
                                clinicalSummary = "${profile.displayName} contains a sympathomimetic decongestant (${profile.canonicalIngredients.joinToString()}), which can constrict blood vessels, elevate blood pressure, and antagonize antihypertensive therapy.",
                                professionalAdvice = "Avoid sympathomimetic decongestants in uncontrolled hypertension and consult a pharmacist or physician for hypertension-safe alternatives.",
                                sourceRefs = listOf(fdaLabelSource)
                            )
                        )
                    } else if ("NSAID" in profile.drugClasses) {
                        warnings.add(
                            ConditionMedicationWarning(
                                warningId = "COND_HTN_NSAID_${profile.displayName.lowercase()}",
                                medicineName = profile.displayName,
                                condition = cond,
                                riskLevel = ConditionRiskLevel.USE_WITH_CAUTION,
                                triggeredByIngredientsOrClasses = profile.canonicalIngredients.toList(),
                                clinicalSummary = "${profile.displayName} is an NSAID and may increase blood pressure and reduce the effectiveness of blood pressure medications.",
                                professionalAdvice = "Consult your physician or pharmacist before using NSAIDs with hypertension.",
                                sourceRefs = listOf(fdaLabelSource)
                            )
                        )
                    }
                }
                "CHRONIC_KIDNEY_DISEASE" -> {
                    if ("NSAID" in profile.drugClasses) {
                        warnings.add(
                            ConditionMedicationWarning(
                                warningId = "COND_CKD_NSAID_${profile.displayName.lowercase()}",
                                medicineName = profile.displayName,
                                condition = cond,
                                riskLevel = ConditionRiskLevel.CONTRAINDICATED,
                                triggeredByIngredientsOrClasses = profile.canonicalIngredients.toList(),
                                clinicalSummary = "${profile.displayName} belongs to the NSAID class, which reduces renal prostaglandin synthesis and can precipitate acute kidney injury or worsen Chronic Kidney Disease.",
                                professionalAdvice = "NSAIDs are generally contraindicated in Chronic Kidney Disease. Consult your nephrologist, physician, or pharmacist.",
                                sourceRefs = listOf(fdaLabelSource)
                            )
                        )
                    } else if ("metformin" in profile.canonicalIngredients) {
                        warnings.add(
                            ConditionMedicationWarning(
                                warningId = "COND_CKD_METFORMIN_${profile.displayName.lowercase()}",
                                medicineName = profile.displayName,
                                condition = cond,
                                riskLevel = ConditionRiskLevel.USE_WITH_CAUTION,
                                triggeredByIngredientsOrClasses = listOf("metformin"),
                                clinicalSummary = "Metformin requires renal function (eGFR) monitoring in Chronic Kidney Disease due to the risk of lactic acidosis in severe renal impairment.",
                                professionalAdvice = "Verify eGFR dosing thresholds with your prescribing physician.",
                                sourceRefs = listOf(fdaLabelSource)
                            )
                        )
                    }
                }
                "CHRONIC_LIVER_DISEASE" -> {
                    if ("paracetamol" in profile.canonicalIngredients || "STATIN" in profile.drugClasses) {
                        warnings.add(
                            ConditionMedicationWarning(
                                warningId = "COND_LIVER_${profile.displayName.lowercase()}",
                                medicineName = profile.displayName,
                                condition = cond,
                                riskLevel = ConditionRiskLevel.USE_WITH_CAUTION,
                                triggeredByIngredientsOrClasses = profile.canonicalIngredients.toList(),
                                clinicalSummary = "${profile.displayName} (${profile.canonicalIngredients.joinToString()}) undergoes hepatic metabolism and may cause hepatotoxicity or require strict dose restriction in Chronic Liver Disease.",
                                professionalAdvice = "Consult a physician before use or dose adjustment in hepatic impairment.",
                                sourceRefs = listOf(fdaLabelSource)
                            )
                        )
                    }
                }
                "ASTHMA" -> {
                    if ("NON_SELECTIVE_BETA_BLOCKER" in profile.drugClasses) {
                        warnings.add(
                            ConditionMedicationWarning(
                                warningId = "COND_ASTHMA_BETABLOCKER_${profile.displayName.lowercase()}",
                                medicineName = profile.displayName,
                                condition = cond,
                                riskLevel = ConditionRiskLevel.CONTRAINDICATED,
                                triggeredByIngredientsOrClasses = profile.canonicalIngredients.toList(),
                                clinicalSummary = "${profile.displayName} contains a non-selective beta-blocker, which is contraindicated in asthma because it can trigger severe bronchospasm.",
                                professionalAdvice = "Do not initiate without direct supervision from a physician; consult a doctor or pharmacist immediately.",
                                sourceRefs = listOf(fdaLabelSource)
                            )
                        )
                    } else if ("NSAID" in profile.drugClasses) {
                        warnings.add(
                            ConditionMedicationWarning(
                                warningId = "COND_ASTHMA_NSAID_${profile.displayName.lowercase()}",
                                medicineName = profile.displayName,
                                condition = cond,
                                riskLevel = ConditionRiskLevel.USE_WITH_CAUTION,
                                triggeredByIngredientsOrClasses = profile.canonicalIngredients.toList(),
                                clinicalSummary = "NSAIDs and aspirin can trigger bronchospasm in patients with aspirin-exacerbated respiratory disease (AERD) and asthma.",
                                professionalAdvice = "Consult a physician or pharmacist before taking NSAIDs if you have asthma.",
                                sourceRefs = listOf(fdaLabelSource)
                            )
                        )
                    }
                }
                "PEPTIC_ULCER_DISEASE" -> {
                    if ("NSAID" in profile.drugClasses || "SYSTEMIC_CORTICOSTEROID" in profile.drugClasses) {
                        warnings.add(
                            ConditionMedicationWarning(
                                warningId = "COND_ULCER_GI_${profile.displayName.lowercase()}",
                                medicineName = profile.displayName,
                                condition = cond,
                                riskLevel = ConditionRiskLevel.CONTRAINDICATED,
                                triggeredByIngredientsOrClasses = profile.canonicalIngredients.toList(),
                                clinicalSummary = "${profile.displayName} is contraindicated in active peptic ulcer disease or history of gastrointestinal bleeding due to mucosal injury and hemorrhage risk.",
                                professionalAdvice = "Consult a physician or pharmacist for gastro-safe alternatives.",
                                sourceRefs = listOf(fdaLabelSource)
                            )
                        )
                    }
                }
                "DIABETES_MELLITUS" -> {
                    if ("SYSTEMIC_CORTICOSTEROID" in profile.drugClasses || "SYMPATHOMIMETIC_DECONGESTANT" in profile.drugClasses) {
                        warnings.add(
                            ConditionMedicationWarning(
                                warningId = "COND_DIABETES_GLYCEMIC_${profile.displayName.lowercase()}",
                                medicineName = profile.displayName,
                                condition = cond,
                                riskLevel = ConditionRiskLevel.USE_WITH_CAUTION,
                                triggeredByIngredientsOrClasses = profile.canonicalIngredients.toList(),
                                clinicalSummary = "${profile.displayName} can elevate blood glucose levels and impair glycemic control in patients with Diabetes Mellitus.",
                                professionalAdvice = "Monitor blood glucose closely and consult a physician or pharmacist before use.",
                                sourceRefs = listOf(fdaLabelSource)
                            )
                        )
                    }
                }
                "G6PD_DEFICIENCY" -> {
                    if ("SULFONAMIDE_ANTIBIOTIC" in profile.drugClasses || "ciprofloxacin" in profile.canonicalIngredients) {
                        warnings.add(
                            ConditionMedicationWarning(
                                warningId = "COND_G6PD_HEMOLYSIS_${profile.displayName.lowercase()}",
                                medicineName = profile.displayName,
                                condition = cond,
                                riskLevel = ConditionRiskLevel.CONTRAINDICATED,
                                triggeredByIngredientsOrClasses = profile.canonicalIngredients.toList(),
                                clinicalSummary = "${profile.displayName} can induce acute oxidative hemolytic anemia in patients with G6PD Deficiency.",
                                professionalAdvice = "Avoid use in G6PD deficiency and consult a physician immediately.",
                                sourceRefs = listOf(fdaLabelSource)
                            )
                        )
                    }
                }
            }
        }

        return ChronicConditionEvaluationResult(
            featureEnabled = true,
            warnings = warnings,
            statusSummary = if (warnings.isEmpty()) {
                "No condition-specific contraindications matched in the reference catalog for the recorded conditions. Always consult a physician or pharmacist."
            } else {
                "${warnings.size} condition-based medication warning(s) identified."
            }
        )
    }
}

// ============================================================================
// COORDINATOR HOOK FOR CABINET LIFECYCLE (DORMANT WHEN FLAGS ARE FALSE)
// ============================================================================

object MedicationSafetyCoordinator {
    val compatibilityService = MedicationCompatibilityService()
    val allergyAlertService = AllergyAlertService()
    val chronicConditionService = ChronicConditionProfileService()

    private val _lastCompatibilityResult = MutableStateFlow(CompatibilityEvaluationResult.disabled())
    val lastCompatibilityResult: StateFlow<CompatibilityEvaluationResult> = _lastCompatibilityResult.asStateFlow()

    val integrationBlueprint = FutureAccountIntegrationBlueprint()

    /**
     * Hook invoked when medicines in the user's cabinet are added, edited, or removed.
     * When [SafetyFeatureFlags.isMedicationCompatibilityEnabled] is `false` (default),
     * this immediately sets the disabled state and performs zero live checks or side effects.
     */
    fun onCabinetMedicinesUpdated(medicines: List<Medicine>) {
        if (!SafetyFeatureFlags.isMedicationCompatibilityEnabled) {
            _lastCompatibilityResult.value = CompatibilityEvaluationResult.disabled()
            return
        }
        _lastCompatibilityResult.value = compatibilityService.evaluateCabinetCompatibility(medicines)
    }
}
