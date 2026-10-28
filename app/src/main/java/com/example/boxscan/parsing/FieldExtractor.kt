package com.example.boxscan.parsing

import com.example.boxscan.models.BoxScanField
import com.example.boxscan.models.FieldCandidate
import com.example.boxscan.models.MedForm
import com.example.boxscan.models.NormalizedRect
import com.example.boxscan.models.OcrSideOutput
import com.example.boxscan.models.PackageSideType
import java.time.LocalDate
import java.util.Locale

/**
 * Multilingual (Arabic + English) medication package field extractor.
 *
 * Strictly extracts ONLY the permitted fields from OCR side outputs:
 * - TRADE_NAME
 * - SCIENTIFIC_NAME
 * - STRENGTH
 * - FORM
 * - MANUFACTURER
 * - BATCH_NUMBER
 * - EXPIRY_DATE
 * - MANUFACTURING_DATE
 * - PACK_QUANTITY
 * - NEEDS_REFRIGERATION
 *
 * Never extracts barcode, indications, warnings, or usageMode/dosage instructions.
 */
class FieldExtractor(
    private val referenceDate: LocalDate = LocalDate.now()
) {
    private val dateParser = DateParser(referenceDate)

    companion object {
        const val STAMPED_GROUP_BONUS = 0.08
        const val MAX_ESTIMATED_SINGLE_SIDE = 0.84

        private val EXPIRY_KEYWORDS = listOf(
            "expiry date", "exp. date", "exp date", "use before", "use by",
            "expiry", "exp.", "exp:", "exp",
            "تاريخ الانتهاء", "تاريخ انتهاء الصلاحية", "انتهاء الصلاحية", "صالح حتى", "صالح لغاية", "انتهاء", "ينتهي"
        )

        private val MFG_KEYWORDS = listOf(
            "manufacturing date", "mfg. date", "mfg date", "mfd. date", "mfd date",
            "manufactured", "mfg.", "mfg:", "mfg", "mfd.", "mfd:", "mfd", "prod. date", "prod.", "prod:",
            "تاريخ الإنتاج", "تاريخ الانتاج", "تاريخ التصنيع", "تاريخ الصنع", "إنتاج", "انتاج"
        )

        private val BATCH_KEYWORDS = listOf(
            "batch no.", "batch no", "batch number", "lot no.", "lot no", "lot number",
            "b.no.", "b.no", "batch:", "batch", "lot:", "lot.", "lot",
            "رقم التشغيلة", "رقم التشغيله", "التشغيلة", "تشغيلة", "رقم الدفعة"
        )

        private val REJECT_BATCH_KEYWORDS = listOf(
            "reg. no", "reg no", "registration", "رقم التسجيل", "تسجيل",
            "tel", "phone", "fax", "هاتف", "تلفون",
            "price", "sar", "riyal", "usd", "eur", "egp", "aed", "سعر", "ريال", "درهم", "جنيه",
            "gtin", "ean", "upc", "barcode"
        )

        private val ACTIVE_INGREDIENT_KEYWORDS = listOf(
            "each tablet contains", "each film-coated tablet contains", "each film coated tablet contains",
            "each capsule contains", "each 5 ml contains", "each 5ml contains", "each ml contains",
            "active ingredient:", "active ingredients:", "active ingredient", "active ingredients",
            "composition:", "composition", "contains:",
            "يحتوي كل قرص على", "تحتوي كل كبسولة على", "يحتوي كل 5 مل على", "يحتوي كل ٥ مل على",
            "المادة الفعالة:", "المادة الفعالة", "المواد الفعالة", "التركيب:", "التركيب"
        )

        private val MANUFACTURER_KEYWORDS = listOf(
            "manufactured by:", "manufactured by", "mfd. by:", "mfd. by", "mfd by",
            "marketed by:", "marketed by", "produced by:", "produced by",
            "marketing authorization holder:", "marketing authorization holder",
            "made in", "الشركة المصنعة:", "الشركة المصنعة", "تصنيع شركة", "تصنيع:", "تصنيع",
            "إنتاج شركة", "إنتاج:", "انتاج شركة", "صنع بواسطة", "صنع في"
        )

        private val COMPANY_SUFFIX_REGEX = Regex(
            """\b(?:pharma|pharmaceuticals|pharmaceutical|laboratories|labs|ltd\.?|co\.?|inc\.?|gmbh|s\.a\.?|llc|corp\.?|شركة|فارما|للأدوية|للادوية|للصناعات الدوائية)\b""",
            RegexOption.IGNORE_CASE
        )

        private val REFRIGERATION_POSITIVE_PATTERNS = listOf(
            Regex("""(?:store|keep)\s+(?:in\s+a\s+refrigerator|refrigerated)""", RegexOption.IGNORE_CASE),
            Regex("""(?:store|keep)?\s*(?:at|between)?\s*2\s*(?:°?\s*c)?\s*(?:[-–—]|to)\s*8\s*°?\s*c""", RegexOption.IGNORE_CASE),
            Regex("""يحفظ\s+في\s+الثلاجة"""),
            Regex("""يحفظ\s+في\s+درجة\s+حرارة\s*2\s*(?:[-–—]|إلى|الى)\s*8"""),
            Regex("""في\s+الثلاجة\s*\(?\s*2\s*[-–—]\s*8""")
        )

        private val REFRIGERATION_NEGATIVE_PATTERNS = listOf(
            Regex("""store\s+below\s+(?:25|30)\s*°?\s*c""", RegexOption.IGNORE_CASE),
            Regex("""do\s+not\s+freeze""", RegexOption.IGNORE_CASE),
            Regex("""يحفظ\s+في\s+درجة\s+حرارة\s+أقل\s+من\s+(?:25|30)"""),
            Regex("""لا\s+يجمد""")
        )

        // Strength regex: e.g. 500 mg, 250mg/5ml, 1%, 100000 IU, 50 mcg, 500 ملغ, 250 مجم / 5 مل
        private val STRENGTH_REGEX = Regex(
            """(?<!\d)(\d+(?:[.,]\d+)?\s*(?:mg|mcg|µg|ug|g|iu|مجم|ملغ|مغ|جم|غرام|وحدة\s*دولية|وحدة)(?:\s*/\s*\d+(?:[.,]\d+)?\s*(?:ml|مل))?|\d+(?:[.,]\d+)?\s*%)(?![\p{L}\d])""",
            RegexOption.IGNORE_CASE
        )

        // Pack quantity regex: e.g. 20 tablets, 30 film-coated tablets, 30 caps, 100 ml, 20 قرص, 30 كبسولة, عبوة 30 قرص
        private val PACK_QUANTITY_REGEX = Regex(
            """(?<!\d)(?:عبوة\s*(?:تحتوي\s*على\s*)?)?(\d{1,4})\s*(?:film[- ]coated\s+tablets|coated\s+tablets|effervescent\s+tablets|chewable\s+tablets|tablets|tabs|hard\s+capsules|soft\s+capsules|capsules|caps|sachets|ampoules|vials|ml|g|قرص(?:اً|ا)?|أقراص|اقراص|كبسولة|كبسولات|مل|أمبولة|امبولة|فيال)(?![\p{L}\d])""",
            RegexOption.IGNORE_CASE
        )

        private val GENERIC_NON_TRADE_PHRASES = listOf(
            "for oral use", "for external use", "keep out of reach of children",
            "prescription only", "rx only", "store below", "do not freeze",
            "batch no", "lot no", "mfg date", "exp date", "expiry date",
            "each tablet contains", "each capsule contains", "active ingredient",
            "manufactured by", "marketed by", " للاستعمال الخارجي", "عن متناول الأطفال",
            "يحفظ في", "تاريخ الانتهاء", "تاريخ الإنتاج", "رقم التشغيلة", "المادة الفعالة"
        )

        private val BARCODE_DIGITS_REGEX = Regex("""^\d{8,14}$""")
        private val GS1_AI_REGEX = Regex("""(?:\(01\)|\(17\)|\(10\)|\(21\))\d+""")
    }

    /**
     * Extracts all candidate fields from a single [OcrSideOutput].
     */
    fun extractFromSide(sideOutput: OcrSideOutput): List<FieldCandidate> {
        val candidates = mutableListOf<FieldCandidate>()
        val rawLines = sideOutput.lines.ifEmpty {
            sideOutput.fullText.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { com.example.boxscan.models.RecognizedLine(text = it) }
        }
        if (rawLines.isEmpty()) return emptyList()

        val normalizedLines = rawLines.map { TextNormalizer.normalizeText(it.text) }

        // Detect stamped block group (LOT + MFG + EXP in close proximity within a 4-line window)
        val stampedGroupLineIndices = detectStampedBlockGroup(normalizedLines)

        for (i in rawLines.indices) {
            val rawLine = rawLines[i]
            val lineText = normalizedLines[i]
            val nextLineText = normalizedLines.getOrNull(i + 1)
            val nextRawLine = rawLines.getOrNull(i + 1)
            val inStampedGroup = i in stampedGroupLineIndices || (i + 1) in stampedGroupLineIndices
            val groupBonus = if (inStampedGroup) STAMPED_GROUP_BONUS else 0.0

            // Skip pure GS1 / barcode lines
            if (isBarcodeNoise(lineText)) continue

            // 1. EXPIRY_DATE & MANUFACTURING_DATE
            extractDatesFromLine(
                sideOutput = sideOutput,
                lineText = lineText,
                nextLineText = nextLineText,
                boundingBox = rawLine.boundingBox ?: nextRawLine?.boundingBox,
                ocrConfidence = rawLine.confidence,
                groupBonus = groupBonus,
                outCandidates = candidates
            )

            // 2. BATCH_NUMBER
            extractBatchFromLine(
                sideOutput = sideOutput,
                lineText = lineText,
                nextLineText = nextLineText,
                boundingBox = rawLine.boundingBox ?: nextRawLine?.boundingBox,
                ocrConfidence = rawLine.confidence,
                groupBonus = groupBonus,
                outCandidates = candidates
            )

            // 3. STRENGTH
            extractStrengthFromLine(
                sideOutput = sideOutput,
                lineText = lineText,
                boundingBox = rawLine.boundingBox,
                ocrConfidence = rawLine.confidence,
                outCandidates = candidates
            )

            // 4. FORM
            extractFormFromLine(
                sideOutput = sideOutput,
                lineText = lineText,
                boundingBox = rawLine.boundingBox,
                ocrConfidence = rawLine.confidence,
                outCandidates = candidates
            )

            // 5. PACK_QUANTITY
            extractPackQuantityFromLine(
                sideOutput = sideOutput,
                lineText = lineText,
                boundingBox = rawLine.boundingBox,
                ocrConfidence = rawLine.confidence,
                outCandidates = candidates
            )

            // 6. SCIENTIFIC_NAME (Active Ingredient)
            extractScientificNameFromLine(
                sideOutput = sideOutput,
                lineText = lineText,
                nextLineText = nextLineText,
                boundingBox = rawLine.boundingBox,
                ocrConfidence = rawLine.confidence,
                outCandidates = candidates
            )

            // 7. MANUFACTURER
            extractManufacturerFromLine(
                sideOutput = sideOutput,
                lineText = lineText,
                nextLineText = nextLineText,
                boundingBox = rawLine.boundingBox,
                ocrConfidence = rawLine.confidence,
                outCandidates = candidates
            )

            // 8. NEEDS_REFRIGERATION
            extractRefrigerationFromLine(
                sideOutput = sideOutput,
                lineText = lineText,
                boundingBox = rawLine.boundingBox,
                ocrConfidence = rawLine.confidence,
                outCandidates = candidates
            )
        }

        // 9. TRADE_NAME (Evaluated across the side's lines)
        extractTradeNameFromSide(
            sideOutput = sideOutput,
            rawLines = rawLines,
            normalizedLines = normalizedLines,
            existingCandidates = candidates,
            outCandidates = candidates
        )

        return candidates
    }

    private fun detectStampedBlockGroup(lines: List<String>): Set<Int> {
        val expLines = mutableSetOf<Int>()
        val mfgLines = mutableSetOf<Int>()
        val lotLines = mutableSetOf<Int>()

        for (i in lines.indices) {
            val lower = lines[i].lowercase(Locale.ROOT)
            if (EXPIRY_KEYWORDS.any { containsKeyword(lower, it) }) expLines.add(i)
            if (MFG_KEYWORDS.any { containsKeyword(lower, it) }) mfgLines.add(i)
            if (BATCH_KEYWORDS.any { containsKeyword(lower, it) }) lotLines.add(i)
        }

        val groupIndices = mutableSetOf<Int>()
        for (i in lines.indices) {
            val window = (maxOf(0, i - 2)..minOf(lines.lastIndex, i + 2)).toSet()
            val typesInWindow = listOf(
                window.any { it in expLines },
                window.any { it in mfgLines },
                window.any { it in lotLines }
            ).count { it }
            if (typesInWindow >= 2) {
                groupIndices.addAll(window)
            }
        }
        return groupIndices
    }

    private fun extractDatesFromLine(
        sideOutput: OcrSideOutput,
        lineText: String,
        nextLineText: String?,
        boundingBox: NormalizedRect?,
        ocrConfidence: Double?,
        groupBonus: Double,
        outCandidates: MutableList<FieldCandidate>
    ) {
        val lower = lineText.lowercase(Locale.ROOT)
        val hasExpKeyword = EXPIRY_KEYWORDS.any { containsKeyword(lower, it) }
        val hasMfgKeyword = MFG_KEYWORDS.any { containsKeyword(lower, it) }

        if (hasExpKeyword && hasMfgKeyword) {
            // Line contains both MFG and EXP (e.g., "MFG 01/2025 EXP 01/2028")
            val expIdx = EXPIRY_KEYWORDS.mapNotNull { kw ->
                val idx = lower.indexOf(kw)
                if (idx >= 0) idx else null
            }.minOrNull() ?: -1
            val mfgIdx = MFG_KEYWORDS.mapNotNull { kw ->
                val idx = lower.indexOf(kw)
                if (idx >= 0) idx else null
            }.minOrNull() ?: -1

            if (expIdx >= 0 && mfgIdx >= 0) {
                if (mfgIdx < expIdx) {
                    val mfgPart = lineText.substring(mfgIdx, expIdx)
                    val expPart = lineText.substring(expIdx)
                    parseAndAddDateCandidate(
                        field = BoxScanField.MANUFACTURING_DATE,
                        textSegment = mfgPart,
                        rawContext = lineText,
                        isExpiry = false,
                        baseConfidence = 0.86 + groupBonus,
                        sideOutput = sideOutput,
                        boundingBox = boundingBox,
                        ocrConfidence = ocrConfidence,
                        outCandidates = outCandidates
                    )
                    parseAndAddDateCandidate(
                        field = BoxScanField.EXPIRY_DATE,
                        textSegment = expPart,
                        rawContext = lineText,
                        isExpiry = true,
                        baseConfidence = 0.86 + groupBonus,
                        sideOutput = sideOutput,
                        boundingBox = boundingBox,
                        ocrConfidence = ocrConfidence,
                        outCandidates = outCandidates
                    )
                } else {
                    val expPart = lineText.substring(expIdx, mfgIdx)
                    val mfgPart = lineText.substring(mfgIdx)
                    parseAndAddDateCandidate(
                        field = BoxScanField.EXPIRY_DATE,
                        textSegment = expPart,
                        rawContext = lineText,
                        isExpiry = true,
                        baseConfidence = 0.86 + groupBonus,
                        sideOutput = sideOutput,
                        boundingBox = boundingBox,
                        ocrConfidence = ocrConfidence,
                        outCandidates = outCandidates
                    )
                    parseAndAddDateCandidate(
                        field = BoxScanField.MANUFACTURING_DATE,
                        textSegment = mfgPart,
                        rawContext = lineText,
                        isExpiry = false,
                        baseConfidence = 0.86 + groupBonus,
                        sideOutput = sideOutput,
                        boundingBox = boundingBox,
                        ocrConfidence = ocrConfidence,
                        outCandidates = outCandidates
                    )
                }
                return
            }
        }

        if (hasExpKeyword) {
            val addedSameLine = parseAndAddDateCandidate(
                field = BoxScanField.EXPIRY_DATE,
                textSegment = lineText,
                rawContext = lineText,
                isExpiry = true,
                baseConfidence = 0.86 + groupBonus,
                sideOutput = sideOutput,
                boundingBox = boundingBox,
                ocrConfidence = ocrConfidence,
                outCandidates = outCandidates
            )
            if (!addedSameLine && nextLineText != null) {
                parseAndAddDateCandidate(
                    field = BoxScanField.EXPIRY_DATE,
                    textSegment = nextLineText,
                    rawContext = "$lineText $nextLineText",
                    isExpiry = true,
                    baseConfidence = 0.75 + groupBonus,
                    sideOutput = sideOutput,
                    boundingBox = boundingBox,
                    ocrConfidence = ocrConfidence,
                    outCandidates = outCandidates
                )
            }
            return
        }

        if (hasMfgKeyword) {
            val addedSameLine = parseAndAddDateCandidate(
                field = BoxScanField.MANUFACTURING_DATE,
                textSegment = lineText,
                rawContext = lineText,
                isExpiry = false,
                baseConfidence = 0.86 + groupBonus,
                sideOutput = sideOutput,
                boundingBox = boundingBox,
                ocrConfidence = ocrConfidence,
                outCandidates = outCandidates
            )
            if (!addedSameLine && nextLineText != null) {
                parseAndAddDateCandidate(
                    field = BoxScanField.MANUFACTURING_DATE,
                    textSegment = nextLineText,
                    rawContext = "$lineText $nextLineText",
                    isExpiry = false,
                    baseConfidence = 0.75 + groupBonus,
                    sideOutput = sideOutput,
                    boundingBox = boundingBox,
                    ocrConfidence = ocrConfidence,
                    outCandidates = outCandidates
                )
            }
            return
        }

        // Unlabeled bare date check: kept strictly below 0.60 draft threshold so it never auto-populates
        val bareDates = dateParser.extractAllDates(lineText, isExpiry = true)
        for (bare in bareDates) {
            val conf = computeConfidence(0.45, ocrConfidence, capBelowHigh = true).coerceAtMost(0.52)
            outCandidates.add(
                FieldCandidate(
                    field = BoxScanField.EXPIRY_DATE,
                    value = bare.isoDate,
                    normalizedValue = bare.isoDate,
                    rawText = lineText,
                    confidence = conf,
                    isEstimatedConfidence = ocrConfidence == null,
                    sideType = sideOutput.sideType,
                    sideIndex = sideOutput.sideIndex,
                    boundingBox = boundingBox
                )
            )
        }
    }

    private fun parseAndAddDateCandidate(
        field: BoxScanField,
        textSegment: String,
        rawContext: String,
        isExpiry: Boolean,
        baseConfidence: Double,
        sideOutput: OcrSideOutput,
        boundingBox: NormalizedRect?,
        ocrConfidence: Double?,
        outCandidates: MutableList<FieldCandidate>
    ): Boolean {
        val parsed = dateParser.parseDate(textSegment, isExpiry = isExpiry) ?: return false
        val conf = computeConfidence(baseConfidence, ocrConfidence, capBelowHigh = ocrConfidence == null)
        outCandidates.add(
            FieldCandidate(
                field = field,
                value = parsed.isoDate,
                normalizedValue = parsed.isoDate,
                rawText = rawContext,
                confidence = conf,
                isEstimatedConfidence = ocrConfidence == null,
                sideType = sideOutput.sideType,
                sideIndex = sideOutput.sideIndex,
                boundingBox = boundingBox
            )
        )
        return true
    }

    private fun extractBatchFromLine(
        sideOutput: OcrSideOutput,
        lineText: String,
        nextLineText: String?,
        boundingBox: NormalizedRect?,
        ocrConfidence: Double?,
        groupBonus: Double,
        outCandidates: MutableList<FieldCandidate>
    ) {
        val lower = lineText.lowercase(Locale.ROOT)
        // Reject lines with registration numbers, phone numbers, prices, or barcodes
        if (REJECT_BATCH_KEYWORDS.any { containsKeyword(lower, it) }) return

        val matchedKeyword = BATCH_KEYWORDS.firstOrNull { containsKeyword(lower, it) } ?: return
        val kwIdx = lower.indexOf(matchedKeyword)
        val afterKeyword = if (kwIdx >= 0) {
            lineText.substring(kwIdx + matchedKeyword.length).trim()
        } else {
            ""
        }

        // Remove any trailing EXP/MFG segments on the same line
        val cleanedAfter = cutBeforeKeywords(afterKeyword, EXPIRY_KEYWORDS + MFG_KEYWORDS)
        val batchToken = extractValidBatchToken(cleanedAfter)
        if (batchToken != null) {
            val normalizedBatch = TextNormalizer.normalizeBatchCode(batchToken)
            val conf = computeConfidence(0.84 + groupBonus, ocrConfidence, capBelowHigh = ocrConfidence == null)
            outCandidates.add(
                FieldCandidate(
                    field = BoxScanField.BATCH_NUMBER,
                    value = normalizedBatch,
                    normalizedValue = normalizedBatch,
                    rawText = lineText,
                    confidence = conf,
                    isEstimatedConfidence = ocrConfidence == null,
                    sideType = sideOutput.sideType,
                    sideIndex = sideOutput.sideIndex,
                    boundingBox = boundingBox
                )
            )
            return
        }

        // Check next line if current line only had the keyword
        if (nextLineText != null) {
            val nextLower = nextLineText.lowercase(Locale.ROOT)
            if (REJECT_BATCH_KEYWORDS.none { containsKeyword(nextLower, it) } &&
                EXPIRY_KEYWORDS.none { containsKeyword(nextLower, it) } &&
                MFG_KEYWORDS.none { containsKeyword(nextLower, it) }
            ) {
                val nextToken = extractValidBatchToken(nextLineText)
                if (nextToken != null) {
                    val normalizedBatch = TextNormalizer.normalizeBatchCode(nextToken)
                    val conf = computeConfidence(0.74 + groupBonus, ocrConfidence, capBelowHigh = ocrConfidence == null)
                    outCandidates.add(
                        FieldCandidate(
                            field = BoxScanField.BATCH_NUMBER,
                            value = normalizedBatch,
                            normalizedValue = normalizedBatch,
                            rawText = "$lineText $nextLineText",
                            confidence = conf,
                            isEstimatedConfidence = ocrConfidence == null,
                            sideType = sideOutput.sideType,
                            sideIndex = sideOutput.sideIndex,
                            boundingBox = boundingBox
                        )
                    )
                }
            }
        }
    }

    private fun extractValidBatchToken(text: String): String? {
        val cleaned = text.replace(Regex("""^[:.#\-–—\s]+"""), "").trim()
        if (cleaned.isEmpty()) return null

        // Tokenize by whitespace and check first plausible code
        val tokens = cleaned.split(Regex("""\s+"""))
        for (rawToken in tokens) {
            val token = rawToken.trim(',', ';', ':', '(', ')')
            if (token.length !in 3..20) continue
            // Reject if it parses as a date
            if (dateParser.parseDate(token, isExpiry = true) != null) continue
            // Reject 12-14 digit barcode strings or 8-14 pure digits that look like EAN/GTIN
            val digitsOnly = token.filter { it.isDigit() }
            if (token.all { it.isDigit() } && token.length in 12..14) continue
            // Must contain only ASCII letters/digits and '-', '/', '.'
            if (!token.all { it.isLetterOrDigit() || it == '-' || it == '/' || it == '.' }) continue
            // Must contain at least one digit (almost all pharmaceutical batch codes contain digits)
            if (digitsOnly.isEmpty()) continue
            return token
        }
        return null
    }

    private fun extractStrengthFromLine(
        sideOutput: OcrSideOutput,
        lineText: String,
        boundingBox: NormalizedRect?,
        ocrConfidence: Double?,
        outCandidates: MutableList<FieldCandidate>
    ) {
        val matches = STRENGTH_REGEX.findAll(lineText)
        for (match in matches) {
            val rawStrength = match.value.trim()
            val normalizedStrength = normalizeStrengthValue(rawStrength)
            val baseConf = if (sideOutput.sideType == PackageSideType.FRONT) 0.84 else 0.78
            val conf = computeConfidence(baseConf, ocrConfidence, capBelowHigh = ocrConfidence == null)
            outCandidates.add(
                FieldCandidate(
                    field = BoxScanField.STRENGTH,
                    value = rawStrength,
                    normalizedValue = normalizedStrength,
                    rawText = lineText,
                    confidence = conf,
                    isEstimatedConfidence = ocrConfidence == null,
                    sideType = sideOutput.sideType,
                    sideIndex = sideOutput.sideIndex,
                    boundingBox = boundingBox
                )
            )
        }
    }

    private fun normalizeStrengthValue(raw: String): String {
        var s = TextNormalizer.normalizeText(raw).lowercase(Locale.ROOT)
        s = s.replace("مجم", "mg")
            .replace("ملغ", "mg")
            .replace("مغ", "mg")
            .replace("غرام", "g")
            .replace("جم", "g")
            .replace("مل", "ml")
            .replace("µg", "mcg")
            .replace("ug", "mcg")
            .replace("وحدة دولية", "iu")
            .replace("وحدة", "iu")
        s = s.replace(Regex("""\s+"""), "")
        return s
    }

    private fun extractFormFromLine(
        sideOutput: OcrSideOutput,
        lineText: String,
        boundingBox: NormalizedRect?,
        ocrConfidence: Double?,
        outCandidates: MutableList<FieldCandidate>
    ) {
        val lower = lineText.lowercase(Locale.ROOT)

        // Check supported forms first
        val formMappings = listOf(
            MedForm.TABLET to listOf("film-coated tablets", "film coated tablets", "coated tablets", "effervescent tablets", "chewable tablets", "tablets", "tablet", "tabs", "أقراص", "اقراص", "قرص"),
            MedForm.CAPSULE to listOf("hard capsules", "soft capsules", "capsules", "capsule", "caps", "كبسولات", "كبسولة"),
            MedForm.SYRUP to listOf("oral syrup", "syrup", "شراب"),
            MedForm.CREAM to listOf("topical cream", "cream", "كريم"),
            MedForm.OINTMENT to listOf("ointment", "مرهم"),
            MedForm.SPRAY to listOf("nasal spray", "oral spray", "spray", "بخاخ"),
            MedForm.DROPS to listOf("eye drops", "ear drops", "oral drops", "nasal drops", "drops", "قطرات", "قطرة", "نقط"),
            MedForm.INJECTION to listOf("solution for injection", "powder for injection", "injectable", "injection", "ampoules", "ampoule", "vials", "vial", "حقن", "حقنة", "أمبولات", "أمبولة", "امبولة", "فيال")
        )

        for ((medForm, keywords) in formMappings) {
            val matchedKw = keywords.firstOrNull { containsWordOrArabic(lower, it) }
            if (matchedKw != null) {
                val baseConf = if (sideOutput.sideType == PackageSideType.FRONT) 0.84 else 0.78
                val conf = computeConfidence(baseConf, ocrConfidence, capBelowHigh = ocrConfidence == null)
                outCandidates.add(
                    FieldCandidate(
                        field = BoxScanField.FORM,
                        value = medForm.arabicLabel,
                        normalizedValue = medForm.name.lowercase(Locale.ROOT),
                        rawText = lineText,
                        confidence = conf,
                        isEstimatedConfidence = ocrConfidence == null,
                        sideType = sideOutput.sideType,
                        sideIndex = sideOutput.sideIndex,
                        boundingBox = boundingBox,
                        medForm = medForm
                    )
                )
                return
            }
        }

        // Unknown / unsupported form terms (e.g., suppository, patch, lozenge, powder, gel, suspension)
        // Must NOT be guessed into a supported MedForm; keep medForm = null and low confidence for review only
        val unsupportedFormTerms = listOf(
            "suppositories", "suppository", "patch", "patches", "lozenges", "lozenge",
            "تحاميل", "لبوس", "لصقات", "لصقة", "استحلاب"
        )
        val unsupportedMatch = unsupportedFormTerms.firstOrNull { containsWordOrArabic(lower, it) }
        if (unsupportedMatch != null) {
            outCandidates.add(
                FieldCandidate(
                    field = BoxScanField.FORM,
                    value = unsupportedMatch,
                    normalizedValue = unsupportedMatch.lowercase(Locale.ROOT),
                    rawText = lineText,
                    confidence = 0.50, // Below 0.60 draft threshold so it never auto-populates a supported MedForm
                    isEstimatedConfidence = true,
                    sideType = sideOutput.sideType,
                    sideIndex = sideOutput.sideIndex,
                    boundingBox = boundingBox,
                    medForm = null
                )
            )
        }
    }

    private fun extractPackQuantityFromLine(
        sideOutput: OcrSideOutput,
        lineText: String,
        boundingBox: NormalizedRect?,
        ocrConfidence: Double?,
        outCandidates: MutableList<FieldCandidate>
    ) {
        val lower = lineText.lowercase(Locale.ROOT)
        // Avoid matching "Each 5 ml contains" as pack quantity
        if (ACTIVE_INGREDIENT_KEYWORDS.any { lower.contains(it) }) return
        // Avoid matching concentration like "250mg/5ml"
        if (Regex("""\d+\s*(?:mg|mcg|g|مجم|ملغ)\s*/\s*\d+\s*(?:ml|مل)""", RegexOption.IGNORE_CASE).containsMatchIn(lineText)) {
            return
        }

        val match = PACK_QUANTITY_REGEX.find(lineText) ?: return
        val fullMatch = match.value.trim()
        val countNum = match.groupValues[1]
        val baseConf = if (sideOutput.sideType == PackageSideType.FRONT) 0.82 else 0.76
        val conf = computeConfidence(baseConf, ocrConfidence, capBelowHigh = ocrConfidence == null)

        outCandidates.add(
            FieldCandidate(
                field = BoxScanField.PACK_QUANTITY,
                value = fullMatch,
                normalizedValue = countNum,
                rawText = lineText,
                confidence = conf,
                isEstimatedConfidence = ocrConfidence == null,
                sideType = sideOutput.sideType,
                sideIndex = sideOutput.sideIndex,
                boundingBox = boundingBox
            )
        )
    }

    private fun extractScientificNameFromLine(
        sideOutput: OcrSideOutput,
        lineText: String,
        nextLineText: String?,
        boundingBox: NormalizedRect?,
        ocrConfidence: Double?,
        outCandidates: MutableList<FieldCandidate>
    ) {
        val lower = lineText.lowercase(Locale.ROOT)
        val matchedKw = ACTIVE_INGREDIENT_KEYWORDS.firstOrNull { lower.contains(it) }
        if (matchedKw != null) {
            val kwIdx = lower.indexOf(matchedKw)
            val afterKw = lineText.substring(kwIdx + matchedKw.length)
                .replace(Regex("""^[:.\-–—\s]+"""), "")
                .trim()

            val cleanedSameLine = cleanScientificNameCandidate(afterKw)
            if (cleanedSameLine != null) {
                val conf = computeConfidence(0.83, ocrConfidence, capBelowHigh = ocrConfidence == null)
                outCandidates.add(
                    FieldCandidate(
                        field = BoxScanField.SCIENTIFIC_NAME,
                        value = cleanedSameLine,
                        normalizedValue = TextNormalizer.normalizeForComparison(cleanedSameLine),
                        rawText = lineText,
                        confidence = conf,
                        isEstimatedConfidence = ocrConfidence == null,
                        sideType = sideOutput.sideType,
                        sideIndex = sideOutput.sideIndex,
                        boundingBox = boundingBox
                    )
                )
                return
            }

            if (nextLineText != null) {
                val cleanedNext = cleanScientificNameCandidate(nextLineText)
                if (cleanedNext != null) {
                    val conf = computeConfidence(0.76, ocrConfidence, capBelowHigh = ocrConfidence == null)
                    outCandidates.add(
                        FieldCandidate(
                            field = BoxScanField.SCIENTIFIC_NAME,
                            value = cleanedNext,
                            normalizedValue = TextNormalizer.normalizeForComparison(cleanedNext),
                            rawText = "$lineText $nextLineText",
                            confidence = conf,
                            isEstimatedConfidence = ocrConfidence == null,
                            sideType = sideOutput.sideType,
                            sideIndex = sideOutput.sideIndex,
                            boundingBox = boundingBox
                        )
                    )
                }
            }
        }
    }

    private fun cleanScientificNameCandidate(raw: String): String? {
        // Strip strength quantities from scientific name (e.g. "Paracetamol 500 mg" -> "Paracetamol")
        var cleaned = STRENGTH_REGEX.replace(raw, "").trim()
        cleaned = cleaned.replace(Regex("""^[:.\-–—(\s]+|[:.\-–—)\s]+$"""), "").trim()
        if (cleaned.length < 3 || cleaned.length > 60) return null
        if (cleaned.all { it.isDigit() }) return null
        return cleaned
    }

    private fun extractManufacturerFromLine(
        sideOutput: OcrSideOutput,
        lineText: String,
        nextLineText: String?,
        boundingBox: NormalizedRect?,
        ocrConfidence: Double?,
        outCandidates: MutableList<FieldCandidate>
    ) {
        val lower = lineText.lowercase(Locale.ROOT)
        val matchedKw = MANUFACTURER_KEYWORDS.firstOrNull { lower.contains(it) }
        if (matchedKw != null) {
            val kwIdx = lower.indexOf(matchedKw)
            val afterKw = lineText.substring(kwIdx + matchedKw.length)
                .replace(Regex("""^[:.\-–—\s]+"""), "")
                .trim()

            if (afterKw.length in 3..70) {
                val conf = computeConfidence(0.82, ocrConfidence, capBelowHigh = ocrConfidence == null)
                outCandidates.add(
                    FieldCandidate(
                        field = BoxScanField.MANUFACTURER,
                        value = afterKw,
                        normalizedValue = TextNormalizer.normalizeForComparison(afterKw),
                        rawText = lineText,
                        confidence = conf,
                        isEstimatedConfidence = ocrConfidence == null,
                        sideType = sideOutput.sideType,
                        sideIndex = sideOutput.sideIndex,
                        boundingBox = boundingBox
                    )
                )
                return
            } else if (nextLineText != null && nextLineText.length in 3..70) {
                val conf = computeConfidence(0.75, ocrConfidence, capBelowHigh = ocrConfidence == null)
                outCandidates.add(
                    FieldCandidate(
                        field = BoxScanField.MANUFACTURER,
                        value = nextLineText.trim(),
                        normalizedValue = TextNormalizer.normalizeForComparison(nextLineText),
                        rawText = "$lineText $nextLineText",
                        confidence = conf,
                        isEstimatedConfidence = ocrConfidence == null,
                        sideType = sideOutput.sideType,
                        sideIndex = sideOutput.sideIndex,
                        boundingBox = boundingBox
                    )
                )
                return
            }
        }

        // Also check company suffixes if not already matched
        if (COMPANY_SUFFIX_REGEX.containsMatchIn(lineText) &&
            lineText.length in 4..70 &&
            EXPIRY_KEYWORDS.none { containsKeyword(lower, it) } &&
            BATCH_KEYWORDS.none { containsKeyword(lower, it) }
        ) {
            val conf = computeConfidence(0.72, ocrConfidence, capBelowHigh = ocrConfidence == null)
            outCandidates.add(
                FieldCandidate(
                    field = BoxScanField.MANUFACTURER,
                    value = lineText.trim(),
                    normalizedValue = TextNormalizer.normalizeForComparison(lineText),
                    rawText = lineText,
                    confidence = conf,
                    isEstimatedConfidence = ocrConfidence == null,
                    sideType = sideOutput.sideType,
                    sideIndex = sideOutput.sideIndex,
                    boundingBox = boundingBox
                )
            )
        }
    }

    private fun extractRefrigerationFromLine(
        sideOutput: OcrSideOutput,
        lineText: String,
        boundingBox: NormalizedRect?,
        ocrConfidence: Double?,
        outCandidates: MutableList<FieldCandidate>
    ) {
        // Check if line only contains room temperature / do not freeze instructions
        val hasPositive = REFRIGERATION_POSITIVE_PATTERNS.any { it.containsMatchIn(lineText) }
        if (!hasPositive) {
            // Note: REFRIGERATION_NEGATIVE_PATTERNS like "Store below 25°C" or "Do not freeze"
            // intentionally do NOT emit a refrigeration candidate.
            return
        }

        val conf = computeConfidence(0.84, ocrConfidence, capBelowHigh = ocrConfidence == null)
        outCandidates.add(
            FieldCandidate(
                field = BoxScanField.NEEDS_REFRIGERATION,
                value = "true",
                normalizedValue = "true",
                rawText = lineText,
                confidence = conf,
                isEstimatedConfidence = ocrConfidence == null,
                sideType = sideOutput.sideType,
                sideIndex = sideOutput.sideIndex,
                boundingBox = boundingBox
            )
        )
    }

    private fun extractTradeNameFromSide(
        sideOutput: OcrSideOutput,
        rawLines: List<com.example.boxscan.models.RecognizedLine>,
        normalizedLines: List<String>,
        existingCandidates: List<FieldCandidate>,
        outCandidates: MutableList<FieldCandidate>
    ) {
        // Prefer FRONT side for trade name; allow OTHER sides with lower confidence
        val scientificValues = existingCandidates
            .filter { it.field == BoxScanField.SCIENTIFIC_NAME }
            .map { it.normalizedValue }
            .toSet()
        val manufacturerValues = existingCandidates
            .filter { it.field == BoxScanField.MANUFACTURER }
            .map { it.normalizedValue }
            .toSet()

        for (i in normalizedLines.indices) {
            val line = normalizedLines[i]
            val rawLine = rawLines[i]
            val lower = line.lowercase(Locale.ROOT)

            if (line.length !in 3..45) continue
            if (isBarcodeNoise(line)) continue
            if (GENERIC_NON_TRADE_PHRASES.any { lower.contains(it) }) continue
            if (EXPIRY_KEYWORDS.any { containsKeyword(lower, it) }) continue
            if (MFG_KEYWORDS.any { containsKeyword(lower, it) }) continue
            if (BATCH_KEYWORDS.any { containsKeyword(lower, it) }) continue
            if (ACTIVE_INGREDIENT_KEYWORDS.any { lower.contains(it) }) continue
            if (MANUFACTURER_KEYWORDS.any { lower.contains(it) }) continue
            if (COMPANY_SUFFIX_REGEX.containsMatchIn(line)) continue
            if (REFRIGERATION_POSITIVE_PATTERNS.any { it.containsMatchIn(line) }) continue
            if (REFRIGERATION_NEGATIVE_PATTERNS.any { it.containsMatchIn(line) }) continue

            // Strip any trailing strength or pack quantity from a prominent brand line (e.g. "Panadol Extra 500mg" -> "Panadol Extra")
            var cleanedBrand = PACK_QUANTITY_REGEX.replace(line, "").trim()
            cleanedBrand = STRENGTH_REGEX.replace(cleanedBrand, "").trim()
            // Also strip standalone form words if the brand line had them attached
            cleanedBrand = cleanedBrand.replace(
                Regex("""\b(?:film[- ]coated\s+tablets|tablets|capsules|syrup|cream|ointment|spray|drops|injection|أقراص|اقراص|كبسولات|شراب|كريم|مرهم|بخاخ|قطرات|حقن)\b""", RegexOption.IGNORE_CASE),
                ""
            ).trim()

            if (cleanedBrand.length !in 3..35) continue
            // Reject lines that are mostly digits
            val letterCount = cleanedBrand.count { it.isLetter() }
            if (letterCount < 3) continue

            val normBrand = TextNormalizer.normalizeForComparison(cleanedBrand)
            if (normBrand in scientificValues || normBrand in manufacturerValues) continue

            // Calculate prominence bonus from boundingBox height or early line index on FRONT
            val boxHeight = rawLine.boundingBox?.height ?: 0f
            val baseConf = when {
                sideOutput.sideType == PackageSideType.FRONT && i == 0 -> 0.84
                sideOutput.sideType == PackageSideType.FRONT && i <= 2 -> 0.78
                sideOutput.sideType == PackageSideType.FRONT -> 0.70
                i == 0 && boxHeight >= 0.08f -> 0.72
                else -> 0.62
            }

            val conf = computeConfidence(baseConf, rawLine.confidence, capBelowHigh = rawLine.confidence == null)
            outCandidates.add(
                FieldCandidate(
                    field = BoxScanField.TRADE_NAME,
                    value = cleanedBrand,
                    normalizedValue = normBrand,
                    rawText = line,
                    confidence = conf,
                    isEstimatedConfidence = rawLine.confidence == null,
                    sideType = sideOutput.sideType,
                    sideIndex = sideOutput.sideIndex,
                    boundingBox = rawLine.boundingBox
                )
            )
            // Take top 2 plausible brand candidates per side max
            if (outCandidates.count { it.field == BoxScanField.TRADE_NAME && it.sideIndex == sideOutput.sideIndex } >= 2) {
                break
            }
        }
    }

    private fun isBarcodeNoise(line: String): Boolean {
        val compact = line.replace(" ", "")
        if (BARCODE_DIGITS_REGEX.matches(compact)) return true
        if (GS1_AI_REGEX.containsMatchIn(compact)) return true
        return false
    }

    private fun containsKeyword(lowerText: String, keyword: String): Boolean {
        val kw = keyword.lowercase(Locale.ROOT)
        val idx = lowerText.indexOf(kw)
        if (idx < 0) return false
        // If keyword is short ASCII (like "exp", "lot", "mfg"), require word boundary before it
        if (kw.all { it.code < 128 } && kw.length <= 4) {
            val beforeOk = idx == 0 || !lowerText[idx - 1].isLetterOrDigit()
            val afterIdx = idx + kw.length
            val afterOk = afterIdx >= lowerText.length || !lowerText[afterIdx].isLetter()
            return beforeOk && afterOk
        }
        return true
    }

    private fun containsWordOrArabic(lowerText: String, keyword: String): Boolean {
        val kw = keyword.lowercase(Locale.ROOT)
        if (kw.any { it.code >= 128 }) {
            return lowerText.contains(kw)
        }
        return Regex("""\b${Regex.escape(kw)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerText)
    }

    private fun cutBeforeKeywords(text: String, keywords: List<String>): String {
        val lower = text.lowercase(Locale.ROOT)
        var earliest = text.length
        for (kw in keywords) {
            val idx = lower.indexOf(kw.lowercase(Locale.ROOT))
            if (idx > 0 && idx < earliest) {
                earliest = idx
            }
        }
        return text.substring(0, earliest).trim()
    }

    private fun computeConfidence(
        ruleConfidence: Double,
        ocrConfidence: Double?,
        capBelowHigh: Boolean
    ): Double {
        val combined = if (ocrConfidence != null) {
            (ruleConfidence * 0.65) + (ocrConfidence.coerceIn(0.0, 1.0) * 0.35)
        } else {
            ruleConfidence
        }
        return if (capBelowHigh) {
            combined.coerceIn(0.0, MAX_ESTIMATED_SINGLE_SIDE)
        } else {
            combined.coerceIn(0.0, 0.95)
        }
    }
}
