package com.example.boxscan

import com.example.boxscan.parsing.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun convertsArabicIndicAndEasternArabicDigitsToWestern() {
        assertEquals("0123456789", TextNormalizer.convertToWesternDigits("٠١٢٣٤٥٦٧٨٩"))
        assertEquals("0123456789", TextNormalizer.convertToWesternDigits("۰۱۲۳۴۵۶۷۸۹"))
        assertEquals("EXP: 08/2027 - 500 mg", TextNormalizer.normalizeText("EXP: ٠٨/٢٠٢٧ - ٥٠٠ mg"))
    }

    @Test
    fun normalizesPunctuationAndWhitespace() {
        val raw = "  LOT\u061B  AB—12\u060C   EXP:  05 / 2028  "
        val normalized = TextNormalizer.normalizeText(raw)
        assertEquals("LOT; AB-12, EXP: 05 / 2028", normalized)
    }

    @Test
    fun contextRestrictedOcrConfusionInNumericDateContextOnly() {
        // Numeric date tokens should fix O->0, I/l->1, S->5, B->8
        assertEquals("05/2026", TextNormalizer.normalizeNumericDateToken("05/2O26"))
        assertEquals("12/2027", TextNormalizer.normalizeNumericDateToken("l2/2027"))
        assertEquals("15/08/2028", TextNormalizer.normalizeNumericDateToken("I5/0B/2028"))

        // Alphabetic month names or words must NOT be corrupted by digit substitutions
        assertEquals("OCT 2026", TextNormalizer.normalizeNumericDateToken("OCT 2026"))
        assertEquals("SOLO", TextNormalizer.normalizeNumericDateToken("SOLO"))
        assertEquals("DEC 2027", TextNormalizer.normalizeNumericDateToken("DEC 2O27"))
    }
}
