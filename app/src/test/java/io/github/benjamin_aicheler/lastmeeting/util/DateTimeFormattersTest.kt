package io.github.benjamin_aicheler.lastmeeting.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

class DateTimeFormattersTest {

    @Test
    fun orderedWeekDaysRespectsLocaleFirstDayOfWeek() {
        assertEquals(
            listOf(
                DayOfWeek.SUNDAY,
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
                DayOfWeek.SATURDAY
            ),
            DateTimeFormatters.orderedWeekDays(Locale.US)
        )

        assertEquals(
            listOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
                DayOfWeek.SATURDAY,
                DayOfWeek.SUNDAY
            ),
            DateTimeFormatters.orderedWeekDays(Locale.GERMANY)
        )
    }

    @Test
    fun leadingEmptyCellsMatchesLocaleFirstDayOfWeek() {
        // November 1, 2026 is a Sunday.
        val november2026 = YearMonth.of(2026, 11)
        assertEquals(0, DateTimeFormatters.leadingEmptyCells(november2026, Locale.US))
        assertEquals(6, DateTimeFormatters.leadingEmptyCells(november2026, Locale.GERMANY))

        // December 1, 2026 is a Tuesday.
        val december2026 = YearMonth.of(2026, 12)
        assertEquals(2, DateTimeFormatters.leadingEmptyCells(december2026, Locale.US))
        assertEquals(1, DateTimeFormatters.leadingEmptyCells(december2026, Locale.GERMANY))
    }

    @Test
    fun formatLocalizedDateTimeAndDateAdaptToLocaleAndIsoKeyStaysInvariant() {
        val instantMillis = ZonedDateTime.of(2026, 11, 11, 13, 30, 0, 0, ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        assertEquals("2026-11-11", DateTimeFormatters.toIsoDateKey(instantMillis, ZoneOffset.UTC))

        val usDateTime = DateTimeFormatters.formatLocalizedDateTime(instantMillis, Locale.US, ZoneOffset.UTC)
        val deDateTime = DateTimeFormatters.formatLocalizedDateTime(instantMillis, Locale.GERMANY, ZoneOffset.UTC)
        assertNotEquals(usDateTime, deDateTime)

        val usDate = DateTimeFormatters.formatLocalizedDate("2026-11-11", Locale.US)
        val deDate = DateTimeFormatters.formatLocalizedDate("2026-11-11", Locale.GERMANY)
        assertNotEquals(usDate, deDate)
        assertEquals("not-a-date", DateTimeFormatters.formatLocalizedDate("not-a-date", Locale.US))
    }
}
