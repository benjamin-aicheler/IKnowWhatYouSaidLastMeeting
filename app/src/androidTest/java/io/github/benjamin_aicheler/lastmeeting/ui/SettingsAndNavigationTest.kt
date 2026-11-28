package io.github.benjamin_aicheler.lastmeeting.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.benjamin_aicheler.lastmeeting.LastMeetingApp
import io.github.benjamin_aicheler.lastmeeting.MainActivity
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.db.MeetingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsAndNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var app: LastMeetingApp
    private var seededMeetingId: Long = 0L
    private lateinit var audioFile: File

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LastMeetingApp
        audioFile = File.createTempFile("nav-test-", ".m4a", app.cacheDir).apply { writeText("audio") }
    }

    @After
    fun tearDown() {
        if (seededMeetingId > 0L) {
            runBlocking { app.database.meetingDao().deleteMeeting(seededMeetingId) }
        }
        audioFile.delete()
    }

    @Test
    fun settingsDraftAndDiscardDialogSurviveRecreationAndKeyRemasks() {
        dismissConsentNoticeIfVisible()
        compose.onNodeWithContentDescription(app.getString(R.string.title_settings)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(app.getString(R.string.label_api_base_url)).fetchSemanticsNodes().isNotEmpty()
        }

        val apiKeyFieldMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
        compose.onNodeWithText(app.getString(R.string.label_api_base_url))
            .performScrollTo()
            .performTextReplacement("https://draft.example/v1/")
        compose.onNodeWithText(app.getString(R.string.label_api_key))
            .performScrollTo()
            .performTextReplacement("sk-unsaved-secret")
        compose.onNodeWithContentDescription(app.getString(R.string.action_show_api_key)).performClick()
        compose.onNodeWithContentDescription(app.getString(R.string.action_back)).performClick()
        compose.onNodeWithText(app.getString(R.string.confirm_discard_settings_title)).assertIsDisplayed()

        compose.activityRule.scenario.recreate()

        // Both draft edits and open discard dialog survive; API key is re-masked.
        compose.onNodeWithText(app.getString(R.string.confirm_discard_settings_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithText("https://draft.example/v1/").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.label_api_key)).assert(apiKeyFieldMatcher)
    }

    @Test
    fun deleteNavigatesBackToMainOnceAndMainRemainsReachable() {
        dismissConsentNoticeIfVisible()
        val title = "Navigation race test"
        runBlocking {
            seededMeetingId = app.database.meetingDao().insertMeeting(
                MeetingEntity(
                    title = title,
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis(),
                    audioFilePath = audioFile.absolutePath
                )
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(app.getString(R.string.title_meeting_details)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription(app.getString(R.string.action_delete_meeting)).performClick()
        compose.onNodeWithText(app.getString(R.string.action_delete)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(app.getString(R.string.title_meetings)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.getString(R.string.title_meetings)).assertIsDisplayed()
        seededMeetingId = 0L
    }

    @Test
    fun rapidSettingsNavigationAndBackReturnsCleanlyToMain() {
        dismissConsentNoticeIfVisible()
        compose.onNodeWithContentDescription(app.getString(R.string.title_settings)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(app.getString(R.string.title_settings)).fetchSemanticsNodes().isNotEmpty()
        }
        pressBack()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(app.getString(R.string.title_meetings)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.getString(R.string.title_meetings)).assertIsDisplayed()
    }

    private fun dismissConsentNoticeIfVisible() {
        compose.waitForIdle()
        val buttonText = app.getString(R.string.consent_notice_acknowledge)
        if (compose.onAllNodesWithText(buttonText).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(buttonText).performClick()
            compose.waitForIdle()
        }
    }
}
