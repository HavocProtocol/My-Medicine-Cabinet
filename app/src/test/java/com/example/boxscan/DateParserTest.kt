package com.example.boxscan

import com.example.boxscan.parsing.DateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DateParserTest {

    private val fixedNow = LocalDate.of(2026, 3, 15)
    private val parser = DateParser(referenceDate = fixedNow)

    @Test
    fun parsesMonthYearFormatsAndNormalizesExpiryToLastDayOfMonth() {
        // Non-leap February
        val feb2027 = parser.parseDate("02/2027", isExpiry = true)
        assertNotNull(feb2027)
        assertEquals("2027-02-28", feb2027!!.isoDate)
        assertTrue(feb2027.isMonthOnly)

        // Leap year February
        val feb2028 = parser.parseDate("02/2028", isExpiry = true)
        assertNotNull(feb2028)
        assertEquals("2028-02-29", feb2028!!.isoDate)

        // 2-digit year MM-YY: 01/26 -> 2026-01-31 (not 2026-01-01)
        val jan26 = parser.parseDate("01/26", isExpiry = true)
        assertNotNull(jan26)
        assertEquals("2026-01-31", jan26!!.isoDate)

        // Manufacturing date with month-only normalizes to first day of month
        val mfgJan26 = parser.parseDate("01/2025", isExpiry = false)
        assertNotNull(mfgJan26)
        assertEquals("2025-01-01", mfgJan26!!.isoDate)
    }

    @Test
    fun parsesFullDayMonthYearAndIsoFormats() {
        val dmy = parser.parseDate("15/08/2027", isExpiry = true)
        assertNotNull(dmy)
        assertEquals("2027-08-15", dmy!!.isoDate)

        val ymd = parser.parseDate("2028/11/30", isExpiry = true)
        assertNotNull(ymd)
        assertEquals("2028-11-30", ymd!!.isoDate)

        val ym = parser.parseDate("2027-09", isExpiry = true)
        assertNotNull(ym)
        assertEquals("2027-09-30", ym!!.isoDate)
    }

    @Test
    fun parsesEnglishAndArabicMonthNames() {
        val eng = parser.parseDate("AUG 2027", isExpiry = true)
        assertNotNull(eng)
        assertEquals("2027-08-31", eng!!.isoDate)

        val engShort = parser.parseDate("DEC-28", isExpiry = true)
        assertNotNull(engShort)
        assertEquals("2028-12-31", engShort!!.isoDate)

        val araGregorian = parser.parseDate("أغسطس ٢٠٢٧", isExpiry = true)
        assertNotNull(araGregorian)
        assertEquals("2027-08-31", araGregorian!!.isoDate)

        val araLevantine = parser.parseDate("كانون الأول 2027", isExpiry = true)
        assertNotNull(araLevantine)
        assertEquals("2027-12-31", araLevantine!!.isoDate)
    }

    @Test
    fun rejectsInvalidMonthsDaysAndFarFutureDates() {
        assertNull(parser.parseDate("13/2026", isExpiry = true))
        assertNull(parser.parseDate("00/2026", isExpiry = true))
        assertNull(parser.parseDate("32/01/2026", isExpiry = true))
        // More than 10 years after fixedNow (2026-03-15 -> max 2036-03-15)
        assertNull(parser.parseDate("05/2039", isExpiry = true))
    }
}
