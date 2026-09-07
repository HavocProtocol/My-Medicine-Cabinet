package com.example.safety

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class ExpiryStatus {
    EXPIRED, WITHIN_30, WITHIN_60, WITHIN_90, OK
}

object ExpiryService {
    fun daysUntil(now: LocalDate, expiry: LocalDate): Long {
        return ChronoUnit.DAYS.between(now, expiry)
    }

    fun classify(now: LocalDate, expiry: LocalDate): ExpiryStatus {
        val d = daysUntil(now, expiry)
        return when {
            d <= 0 -> ExpiryStatus.EXPIRED
            d <= 30 -> ExpiryStatus.WITHIN_30
            d <= 60 -> ExpiryStatus.WITHIN_60
            d <= 90 -> ExpiryStatus.WITHIN_90
            else -> ExpiryStatus.OK
        }
    }

    fun alertsFor(now: LocalDate, expiry: LocalDate): List<String> {
        return when (classify(now, expiry)) {
            ExpiryStatus.EXPIRED -> listOf("expired")
            ExpiryStatus.WITHIN_30 -> listOf("expiry_30")
            ExpiryStatus.WITHIN_60 -> listOf("expiry_60")
            ExpiryStatus.WITHIN_90 -> listOf("expiry_90")
            ExpiryStatus.OK -> emptyList()
        }
    }

    fun isUsable(now: LocalDate, expiry: LocalDate): Boolean {
        return classify(now, expiry) != ExpiryStatus.EXPIRED
    }
}
