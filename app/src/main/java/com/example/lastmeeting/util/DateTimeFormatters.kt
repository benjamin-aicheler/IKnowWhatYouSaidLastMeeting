package com.example.lastmeeting.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Locale

object DateTimeFormatters {
    fun formatLocalizedDateTime(
        epochMillis: Long,
        locale: Locale,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(formatter)
    }

    fun formatLocalizedDate(isoDate: String, locale: Locale): String =
        runCatching {
            val date = LocalDate.parse(isoDate, DateTimeFormatter.ISO_LOCAL_DATE)
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
        }.getOrDefault(isoDate)

    fun toIsoDateKey(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun orderedWeekDays(locale: Locale): List<DayOfWeek> {
        val firstDay = WeekFields.of(locale).firstDayOfWeek
        return (0L until 7L).map { offset -> firstDay.plus(offset) }
    }

    fun leadingEmptyCells(yearMonth: YearMonth, locale: Locale): Int {
        val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
        val firstOfMonth = yearMonth.atDay(1).dayOfWeek
        return (firstOfMonth.value - firstDayOfWeek.value + 7) % 7
    }
}
