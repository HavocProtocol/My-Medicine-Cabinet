package com.example.boxscan.parsing

import java.util.Locale

/**
 * Handles script, digit, punctuation, and whitespace normalization for medicine box OCR.
 * Preserves original recognized text for display while providing deterministic normalized
 * representations for parsing and cross-side comparison.
 */
object TextNormalizer {

    private val ARABIC_INDIC_DIGITS = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
    private val PERSIAN_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    private val MONTH_ABBREVIATIONS = setOf(
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
        "JUL", "AUG", "SEP", "SEPT", "OCT", "NOV", "DEC"
    )

    /**
     * Converts Arabic-Indic (٠-٩) and Persian (۰-۹) digits to Western ASCII digits (0-9).
     * Leaves all non-digit characters untouched.
     */
    fun convertToWesternDigits(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val arabicIdx = ARABIC_INDIC_DIGITS.indexOf(ch)
            if (arabicIdx >= 0) {
                sb.append(('0'.code + arabicIdx).toChar())
                continue
            }
            val persianIdx = PERSIAN_DIGITS.indexOf(ch)
            if (persianIdx >= 0) {
                sb.append(('0'.code + persianIdx).toChar())
                continue
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * Normalizes common Unicode dashes, slashes, Arabic punctuation, degree symbols, micro units,
     * and invisible spaces while converting Eastern digits to Western digits.
     */
    fun normalizeText(input: String): String {
        if (input.isEmpty()) return input
        val western = convertToWesternDigits(input)
        val sb = StringBuilder(western.length)
        for (ch in western) {
            when (ch) {
                '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212', '\uFE58', '\uFE63', '\uFF0D' -> sb.append('-')
                '\u2044', '\u2215', '\uFF0F' -> sb.append('/')
                '\u060C' -> sb.append(',')
                '\u061B' -> sb.append(';')
                '\u00BA', '\u02DA', '\u2218' -> sb.append('°')
                '\u03BC' -> sb.append('µ')
                '\u00A0', '\u2007', '\u202F', '\u2009', '\u200A', '\t' -> sb.append(' ')
                '\u200B', '\u200C', '\u200D', '\uFEFF' -> { /* strip zero-width chars */ }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
            .replace(Regex("[ ]+"), " ")
            .trim()
    }

    /**
     * Normalizes text for case-insensitive comparison across sides.
     */
    fun normalizeForComparison(input: String): String {
        return normalizeText(input)
            .lowercase(Locale.ROOT)
            .replace("μg", "mcg")
            .replace("µg", "mcg")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Normalizes a batch/lot code for comparison across sides:
     * trims surrounding whitespace, converts Eastern digits, normalizes dash/slash, and uppercases.
     * Preserves internal characters (-, /, .) and does not strip meaningful characters.
     */
    fun normalizeBatchCode(batch: String): String {
        return normalizeText(batch)
            .uppercase(Locale.ROOT)
            .trim()
    }

    /**
     * Applies OCR character-to-digit substitutions (O/o -> 0, I/l -> 1, S/s -> 5, B -> 8)
     * STRICTLY within a validated numeric or date token context.
     * Never alters month abbreviations (OCT, SEP, DEC, FEB) or ordinary words (SOLO).
     */
    fun normalizeNumericDateToken(token: String): String {
        val cleaned = normalizeText(token)
        if (cleaned.isEmpty()) return cleaned

        val parts = cleaned.split(Regex("(?<=[/\\-.\\s])|(?=[/\\-.\\s])"))
        val result = StringBuilder()

        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isEmpty() || trimmed == "/" || trimmed == "-" || trimmed == ".") {
                result.append(part)
                continue
            }
            val upper = trimmed.uppercase(Locale.ROOT)
            if (upper in MONTH_ABBREVIATIONS) {
                result.append(part)
                continue
            }

            val hasRealDigit = trimmed.any { it in '0'..'9' }
            val allNumericOrLookalike = trimmed.all {
                it in '0'..'9' || it == 'O' || it == 'o' || it == 'I' || it == 'l' || it == 'S' || it == 's' || it == 'B'
            }
            val hasSeparatorInOverallToken = cleaned.any { it == '/' || it == '-' || it == '.' }

            if (allNumericOrLookalike && (hasRealDigit || (hasSeparatorInOverallToken && trimmed.length == 2))) {
                for (ch in part) {
                    when (ch) {
                        'O', 'o' -> result.append('0')
                        'I', 'l' -> result.append('1')
                        'S', 's' -> result.append('5')
                        'B' -> result.append('8')
                        else -> result.append(ch)
                    }
                }
            } else {
                result.append(part)
            }
        }
        return result.toString()
    }
}
