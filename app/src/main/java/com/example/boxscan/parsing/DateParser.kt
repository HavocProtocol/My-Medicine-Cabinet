package com.example.boxscan.parsing

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

data class ParsedPackageDate(
    val date: LocalDate,
    val epochMillis: Long,
    val isoDate: String,
    val displayText: String,
    val isMonthOnly: Boolean,
    val rawMatchedToken: String
)

/**
 * Deterministic pharmaceutical package date parser supporting Western & Arabic-Indic digits,
 * numeric formats (MM/YYYY, MM-YY, MM.YYYY, DD/MM/YYYY, YYYY-MM, YYYY/MM/DD),
 * English month names (MMM YYYY, MMM-YY), and Arabic month names (Gregorian & Syriac/Levantine).
 */
class DateParser(
    private val referenceDate: LocalDate = LocalDate.now()
) {
    companion object {
        const val MAX_FUTURE_YEARS = 10L
        const val MIN_VALID_YEAR = 1995

        private val ENGLISH_MONTHS = mapOf(
            "jan" to 1, "january" to 1,
            "feb" to 2, "february" to 2,
            "mar" to 3, "march" to 3,
            "apr" to 4, "april" to 4,
            "may" to 5,
            "jun" to 6, "june" to 6,
            "jul" to 7, "july" to 7,
            "aug" to 8, "august" to 8,
            "sep" to 9, "sept" to 9, "september" to 9,
            "oct" to 10, "october" to 10,
            "nov" to 11, "november" to 11,
            "dec" to 12, "december" to 12
        )

        private val ARABIC_MONTHS: List<Pair<String, Int>> = listOf(
            "كانون الثاني" to 1,
            "كانون ثاني" to 1,
            "تشرين الأول" to 10,
            "تشرين الاول" to 10,
            "تشرين أول" to 10,
            "تشرين اول" to 10,
            "تشرين الثاني" to 11,
            "تشرين ثاني" to 11,
            "كانون الأول" to 12,
            "كانون الاول" to 12,
            "كانون أول" to 12,
            "كانون اول" to 12,
            "يناير" to 1,
            "فبراير" to 2,
            "شباط" to 2,
            "مارس" to 3,
            "آذار" to 3,
            "اذار" to 3,
            "أبريل" to 4,
            "ابريل" to 4,
            "إبريل" to 4,
            "نيسان" to 4,
            "مايو" to 5,
            "أيار" to 5,
            "ايار" to 5,
            "يونيو" to 6,
            "يونيه" to 6,
            "حزيران" to 6,
            "يوليو" to 7,
            "يوليه" to 7,
            "تموز" to 7,
            "أغسطس" to 8,
            "اغسطس" to 8,
            "آب" to 8,
            "أيلول" to 9,
            "ايلول" to 9,
            "سبتمبر" to 9,
            "أكتوبر" to 10,
            "اكتوبر" to 10,
            "نوفمبر" to 11,
            "ديسمبر" to 12
        )
    }

    fun resolveYear(rawYear: Int, isTwoDigit: Boolean): Int? {
        val fullYear = if (isTwoDigit) {
            if (rawYear in 0..69) 2000 + rawYear else return null
        } else {
            rawYear
        }
        val maxAllowedDate = referenceDate.plusYears(MAX_FUTURE_YEARS)
        if (fullYear < MIN_VALID_YEAR || fullYear > maxAllowedDate.year + 1) {
            return null
        }
        return fullYear
    }

    fun parseDate(
        input: String,
        isExpiry: Boolean = true
    ): ParsedPackageDate? {
        return extractAllDates(input, isExpiry).firstOrNull()
    }

    fun extractAllDates(
        line: String,
        isExpiry: Boolean = true
    ): List<ParsedPackageDate> {
        if (line.isBlank()) return emptyList()
        val results = mutableListOf<ParsedPackageDate>()

        val normalized = TextNormalizer.normalizeNumericDateToken(line)

        // 1. Arabic month name + year patterns
        for ((arMonthName, monthNum) in ARABIC_MONTHS) {
            val regex = Regex("""(?:(\d{1,2})\s+)?$arMonthName[\s/\-.]*(\d{2,4})""")
            for (match in regex.findAll(normalized)) {
                val dayStr = match.groups[1]?.value
                val yearStr = match.groups[2]?.value ?: continue
                if (yearStr.length != 2 && yearStr.length != 4) continue
                val year = resolveYear(yearStr.toIntOrNull() ?: continue, yearStr.length == 2) ?: continue
                val day = dayStr?.toIntOrNull()
                val parsed = buildValidDate(
                    year = year,
                    month = monthNum,
                    day = day,
                    isExpiry = isExpiry,
                    rawToken = match.value.trim()
                )
                if (parsed != null) results.add(parsed)
            }
        }

        // 2. English month name patterns: MMM YYYY, MMM-YY, DD MMM YYYY
        val engRegex = Regex("""\b(?:(\d{1,2})[\s/\-.]+)?([A-Za-z]{3,9})[\s/\-.]+(\d{2,4})\b""")
        for (match in engRegex.findAll(normalized)) {
            val dayStr = match.groups[1]?.value
            val monthWord = match.groups[2]?.value?.lowercase(Locale.ROOT) ?: continue
            val monthNum = ENGLISH_MONTHS[monthWord] ?: continue
            val yearStr = match.groups[3]?.value ?: continue
            if (yearStr.length != 2 && yearStr.length != 4) continue
            val year = resolveYear(yearStr.toIntOrNull() ?: continue, yearStr.length == 2) ?: continue
            val day = dayStr?.toIntOrNull()
            val parsed = buildValidDate(
                year = year,
                month = monthNum,
                day = day,
                isExpiry = isExpiry,
                rawToken = match.value.trim()
            )
            if (parsed != null) results.add(parsed)
        }

        // 3. 3-component numeric dates: DD/MM/YYYY, DD-MM-YYYY, DD.MM.YYYY, DD/MM/YY, or YYYY/MM/DD, YYYY-MM-DD
        val threePartRegex = Regex("""(?<!\d)(\d{1,4})([/\-.])(\d{1,2})\2(\d{1,4})(?!\d)""")
        for (match in threePartRegex.findAll(normalized)) {
            val p1 = match.groups[1]?.value ?: continue
            val p2 = match.groups[3]?.value ?: continue
            val p3 = match.groups[4]?.value ?: continue

            val parsed = if (p1.length == 4) {
                val year = resolveYear(p1.toIntOrNull() ?: continue, false) ?: continue
                val month = p2.toIntOrNull() ?: continue
                val day = p3.toIntOrNull() ?: continue
                buildValidDate(year, month, day, isExpiry, match.value)
            } else if (p3.length == 4 || p3.length == 2) {
                val day = p1.toIntOrNull() ?: continue
                val month = p2.toIntOrNull() ?: continue
                val year = resolveYear(p3.toIntOrNull() ?: continue, p3.length == 2) ?: continue
                buildValidDate(year, month, day, isExpiry, match.value)
            } else {
                null
            }
            if (parsed != null) results.add(parsed)
        }

        // 4. 2-component numeric dates: MM/YYYY, MM-YY, MM.YYYY, YYYY-MM, YYYY/MM
        val twoPartRegex = Regex("""(?<![\d/\-.])(\d{1,4})([/\-.])(\d{2,4})(?![\d/\-.])""")
        for (match in twoPartRegex.findAll(normalized)) {
            val p1 = match.groups[1]?.value ?: continue
            val p2 = match.groups[3]?.value ?: continue

            val parsed = if (p1.length == 4 && (p2.length == 1 || p2.length == 2)) {
                val year = resolveYear(p1.toIntOrNull() ?: continue, false) ?: continue
                val month = p2.toIntOrNull() ?: continue
                buildValidDate(year, month, null, isExpiry, match.value)
            } else if ((p1.length == 1 || p1.length == 2) && (p2.length == 4 || p2.length == 2)) {
                val month = p1.toIntOrNull() ?: continue
                val year = resolveYear(p2.toIntOrNull() ?: continue, p2.length == 2) ?: continue
                buildValidDate(year, month, null, isExpiry, match.value)
            } else {
                null
            }
            if (parsed != null) results.add(parsed)
        }

        return results.distinctBy { it.isoDate }
    }

    private fun buildValidDate(
        year: Int,
        month: Int,
        day: Int?,
        isExpiry: Boolean,
        rawToken: String
    ): ParsedPackageDate? {
        if (month !in 1..12) return null
        val yearMonth = try {
            YearMonth.of(year, month)
        } catch (_: Exception) {
            return null
        }

        val resolvedDate: LocalDate = if (day == null) {
            if (isExpiry) yearMonth.atEndOfMonth() else yearMonth.atDay(1)
        } else {
            if (day < 1 || day > yearMonth.lengthOfMonth()) return null
            LocalDate.of(year, month, day)
        }

        val maxFutureDate = referenceDate.plusYears(MAX_FUTURE_YEARS)
        if (resolvedDate.isAfter(maxFutureDate)) {
            return null
        }

        val epochMillis = resolvedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val iso = String.format(Locale.US, "%04d-%02d-%02d", resolvedDate.year, resolvedDate.monthValue, resolvedDate.dayOfMonth)
        val display = if (day == null) {
            String.format(Locale.US, "%02d/%04d (%s)", month, year, iso)
        } else {
            iso
        }

        return ParsedPackageDate(
            date = resolvedDate,
            epochMillis = epochMillis,
            isoDate = iso,
            displayText = display,
            isMonthOnly = (day == null),
            rawMatchedToken = rawToken
        )
    }
}
