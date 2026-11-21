package com.example.retailx.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateTimeUtils {
    const val DEFAULT_TIMEZONE = "Asia/Kolkata"

    fun zoneOrDefault(tz: String): ZoneId = try {
        ZoneId.of(tz.ifBlank { DEFAULT_TIMEZONE })
    } catch (_: Exception) {
        ZoneId.of(DEFAULT_TIMEZONE)
    }

    fun formatDateTime(epochMillis: Long, timezone: String = DEFAULT_TIMEZONE): String {
        val fmt = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH)
            .withZone(zoneOrDefault(timezone))
        return fmt.format(Instant.ofEpochMilli(epochMillis))
    }

    fun formatDate(epochMillis: Long, timezone: String = DEFAULT_TIMEZONE): String {
        val fmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
            .withZone(zoneOrDefault(timezone))
        return fmt.format(Instant.ofEpochMilli(epochMillis))
    }

    fun formatShortTime(epochMillis: Long, timezone: String = DEFAULT_TIMEZONE): String {
        val fmt = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)
            .withZone(zoneOrDefault(timezone))
        return fmt.format(Instant.ofEpochMilli(epochMillis))
    }

    fun dayStartMillis(date: LocalDate, timezone: String = DEFAULT_TIMEZONE): Long {
        return date.atStartOfDay(zoneOrDefault(timezone)).toInstant().toEpochMilli()
    }

    fun dayEndMillis(date: LocalDate, timezone: String = DEFAULT_TIMEZONE): Long {
        return date.plusDays(1).atStartOfDay(zoneOrDefault(timezone)).toInstant().toEpochMilli() - 1L
    }

    fun todayRange(timezone: String = DEFAULT_TIMEZONE): Pair<Long, Long> {
        val zone = zoneOrDefault(timezone)
        val today = LocalDate.now(zone)
        return dayStartMillis(today, timezone) to dayEndMillis(today, timezone)
    }

    fun lastNDaysRange(days: Long, timezone: String = DEFAULT_TIMEZONE): Pair<Long, Long> {
        val zone = zoneOrDefault(timezone)
        val today = LocalDate.now(zone)
        val start = today.minusDays(days - 1)
        return dayStartMillis(start, timezone) to dayEndMillis(today, timezone)
    }

    fun currentFinancialYear(epochMillis: Long = System.currentTimeMillis(), timezone: String = DEFAULT_TIMEZONE): String {
        val localDate = Instant.ofEpochMilli(epochMillis).atZone(zoneOrDefault(timezone)).toLocalDate()
        val startYear = if (localDate.monthValue >= 4) localDate.year else localDate.year - 1
        val endYearShort = (startYear + 1) % 100
        return "${startYear % 100}${endYearShort.toString().padStart(2, '0')}"
    }
}
