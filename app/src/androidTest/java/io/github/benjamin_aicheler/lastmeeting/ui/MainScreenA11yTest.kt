package io.github.benjamin_aicheler.lastmeeting.ui.main

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalProvidableLocaleList
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.db.MeetingEntity
import io.github.benjamin_aicheler.lastmeeting.util.DateTimeFormatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class MainScreenA11yTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun calendarDayCellsExposeLocalizedDateAvailabilitySelectionStateAndMinimumTouchTargets() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val dateWithMeetings = LocalDate.of(2026, 11, 15)
        val dateWithoutMeetings = LocalDate.of(2026, 11, 16)
        val usConfig = Configuration(targetContext.resources.configuration).apply {
            setLocale(Locale.US)
        }
        val usContext = targetContext.createConfigurationContext(usConfig)
        val expectedWithMeetingsUs = usContext.getString(
            R.string.calendar_day_with_meetings,
            DateTimeFormatters.formatAccessibleDate(dateWithMeetings, Locale.US)
        )
        val expectedWithoutMeetingsUs = usContext.getString(
            R.string.calendar_day_no_meetings,
            DateTimeFormatters.formatAccessibleDate(dateWithoutMeetings, Locale.US)
        )

        var selectedDate by mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalConfiguration provides usConfig,
                    LocalContext provides usContext,
                    LocalResources provides usContext.resources,
                    LocalProvidableLocaleList provides LocaleList("en-US")
                ) {
                    // Constrained 300.dp width verifies horizontal scroll fallback keeps >= 48.dp cells.
                    Box(modifier = Modifier.width(300.dp)) {
                        MonthCalendarCard(
                            currentMonth = YearMonth.of(2026, 11),
                            onPreviousMonth = {},
                            onNextMonth = {},
                            datesWithMeetings = setOf("2026-11-15"),
                            selectedDate = selectedDate,
                            onDateClick = { clicked ->
                                selectedDate = if (selectedDate == clicked) null else clicked
                            }
                        )
                    }
                }
            }
        }

        // Bare day numerals should not appear as independent semantic nodes.
        compose.onAllNodesWithText("15", useUnmergedTree = false).assertCountEquals(0)

        val dayWithMeetingsNode = compose.onNodeWithContentDescription(expectedWithMeetingsUs)
        dayWithMeetingsNode
            .assertIsSelectable()
            .assertIsNotSelected()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        val dayWithoutMeetingsNode = compose.onNodeWithContentDescription(expectedWithoutMeetingsUs)
        dayWithoutMeetingsNode
            .assertIsSelectable()
            .assertIsNotSelected()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        dayWithMeetingsNode.performClick()
        assertEquals("2026-11-15", selectedDate)
        dayWithMeetingsNode.assertIsSelected()

        dayWithMeetingsNode.performClick()
        assertEquals(null, selectedDate)
        dayWithMeetingsNode.assertIsNotSelected()
    }

    @Test
    fun germanLocaleFormatsGermanDayAvailabilitySemantics() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val deConfig = Configuration(targetContext.resources.configuration).apply {
            setLocale(Locale.GERMANY)
        }
        val deContext = targetContext.createConfigurationContext(deConfig)
        val date = LocalDate.of(2026, 11, 15)
        val expectedDe = deContext.getString(
            R.string.calendar_day_with_meetings,
            DateTimeFormatters.formatAccessibleDate(date, Locale.GERMANY)
        )

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalConfiguration provides deConfig,
                    LocalContext provides deContext,
                    LocalResources provides deContext.resources,
                    LocalProvidableLocaleList provides LocaleList("de-DE")
                ) {
                    MonthCalendarCard(
                        currentMonth = YearMonth.of(2026, 11),
                        onPreviousMonth = {},
                        onNextMonth = {},
                        datesWithMeetings = setOf("2026-11-15"),
                        selectedDate = null,
                        onDateClick = {}
                    )
                }
            }
        }

        compose.onNode(hasContentDescription(expectedDe)).assertIsSelectable()
    }

    @Test
    fun viewModeSegmentedBarDoesNotDuplicateSegmentLabels() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val listLabel = targetContext.getString(R.string.view_list)
        val calendarLabel = targetContext.getString(R.string.view_calendar)

        compose.setContent {
            MaterialTheme {
                ViewModeSegmentedBar(
                    viewMode = MainViewMode.LIST,
                    onViewModeSelected = {}
                )
            }
        }

        compose.onAllNodesWithText(listLabel).assertCountEquals(1)
        compose.onAllNodesWithText(calendarLabel).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(listLabel).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(calendarLabel).assertCountEquals(0)
    }

    @Test
    fun listAndCalendarScrollLastCardClearOfFabAndRemainClickable() {
        val baseTime = LocalDate.of(2026, 11, 1)
            .atTime(10, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val meetings = (1..18).map { index ->
            val start = baseTime + (index - 1) * 86_400_000L
            MeetingEntity(
                id = index.toLong(),
                title = "Meeting #$index",
                startTime = start,
                endTime = start + 60_000L,
                audioFilePath = "/tmp/m$index.m4a"
            )
        }
        val clickedIds = mutableListOf<Long>()
        var showCalendar by mutableStateOf(false)

        compose.setContent {
            MaterialTheme {
                Scaffold(
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = {},
                            modifier = Modifier.testTag("test_fab")
                        ) {
                            Text("Rec")
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp)
                    ) {
                        if (!showCalendar) {
                            MeetingListView(
                                meetings = meetings,
                                onMeetingClick = { clickedIds += it }
                            )
                        } else {
                            CalendarViewComponent(
                                meetings = meetings,
                                selectedDate = null,
                                onDateSelected = {},
                                onMeetingClick = { clickedIds += it }
                            )
                        }
                    }
                }
            }
        }

        val lastTitle = "Meeting #18"
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(lastTitle))
        compose.waitForIdle()
        val listFabTop = compose.onNodeWithTag("test_fab").fetchSemanticsNode().boundsInRoot.top
        val listCardBottom = compose.onNodeWithText(lastTitle).fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "Expected last list card ($listCardBottom) above FAB top ($listFabTop)",
            listCardBottom <= listFabTop
        )
        compose.onNodeWithText(lastTitle).performClick()
        assertEquals(listOf(18L), clickedIds)

        // Switch to CalendarViewComponent (sorted descending, so Meeting #1 is at the bottom).
        showCalendar = true
        compose.waitForIdle()
        val bottomCalendarTitle = "Meeting #1"
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(bottomCalendarTitle))
        compose.waitForIdle()
        val calendarFabTop = compose.onNodeWithTag("test_fab").fetchSemanticsNode().boundsInRoot.top
        val calendarCardBottom = compose.onNodeWithText(bottomCalendarTitle).fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "Expected last calendar card ($calendarCardBottom) above FAB top ($calendarFabTop)",
            calendarCardBottom <= calendarFabTop
        )
        compose.onNodeWithText(bottomCalendarTitle).performClick()
        assertEquals(listOf(18L, 1L), clickedIds)
    }
}
