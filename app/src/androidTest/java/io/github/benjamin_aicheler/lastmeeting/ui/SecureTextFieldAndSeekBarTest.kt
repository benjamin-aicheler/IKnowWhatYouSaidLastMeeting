package io.github.benjamin_aicheler.lastmeeting.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.api.OpenAiApiClient
import io.github.benjamin_aicheler.lastmeeting.data.preferences.ApiSettings
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsStore
import io.github.benjamin_aicheler.lastmeeting.data.preferences.TranscriptionMode
import io.github.benjamin_aicheler.lastmeeting.ui.details.AudioSeekBar
import io.github.benjamin_aicheler.lastmeeting.ui.settings.SettingsScreen
import io.github.benjamin_aicheler.lastmeeting.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureTextFieldAndSeekBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun outlinedSecureTextFieldSupportsDelayedLoadToggleSaveAndSkipsSavedStateRestoration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as Application
        val restorationTester = StateRestorationTester(compose)
        val settingsFlow = MutableSharedFlow<ApiSettings>(replay = 1)
        val savedKeys = mutableListOf<String>()
        val store = object : SettingsStore {
            override val settingsFlow: Flow<ApiSettings> = settingsFlow
            override suspend fun saveSettings(
                baseUrl: String,
                apiKey: String,
                transcriptionMode: TranscriptionMode,
                transcriptionModel: String,
                protocolModel: String,
                transcriptionPrompt: String,
                protocolPrompt: String
            ) {
                savedKeys += apiKey
                settingsFlow.emit(
                    ApiSettings(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        transcriptionMode = transcriptionMode,
                        transcriptionModel = transcriptionModel,
                        protocolModel = protocolModel,
                        transcriptionPrompt = transcriptionPrompt,
                        protocolPrompt = protocolPrompt
                    )
                )
            }
        }
        var currentViewModel by mutableStateOf(
            SettingsViewModel(app = app, repository = store, apiClient = OpenAiApiClient())
        )

        restorationTester.setContent {
            MaterialTheme {
                SettingsScreen(
                    viewModel = currentViewModel,
                    onNavigateBack = {}
                )
            }
        }

        // Disabled until initial baseline settings emit.
        compose.onNodeWithText(context.getString(R.string.label_api_key)).assertIsNotEnabled()

        settingsFlow.tryEmit(
            ApiSettings(
                baseUrl = "https://api.example.com/v1/",
                apiKey = "sk-saved-baseline",
                transcriptionMode = TranscriptionMode.AUTO,
                transcriptionModel = "gpt-4o-mini-transcribe",
                protocolModel = "gpt-4o-mini",
                transcriptionPrompt = "Transcribe",
                protocolPrompt = "Summarize"
            )
        )
        compose.waitForIdle()

        val hiddenMatcher = SemanticsMatcher.expectValue(SemanticsProperties.IsPasswordObfuscated, true)
        val visibleMatcher = SemanticsMatcher.expectValue(SemanticsProperties.IsPasswordObfuscated, false)

        val keyNode = compose.onNodeWithText(context.getString(R.string.label_api_key))
        keyNode.assertIsEnabled().assert(hiddenMatcher)

        // Toggle visible and verify obfuscation updates while field remains secure.
        keyNode.performScrollTo().performTextReplacement("sk-unsaved-secret")
        compose.onNodeWithContentDescription(context.getString(R.string.action_show_api_key)).performClick()
        keyNode.assert(visibleMatcher)
        compose.onNodeWithContentDescription(context.getString(R.string.action_hide_api_key)).assertIsDisplayed()

        // Clicking back with dirty changes opens discard confirmation without discarding yet.
        compose.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.confirm_discard_settings_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()

        // Saving persists the edited key through SettingsViewModel/SettingsStore.
        compose.onNodeWithText(context.getString(R.string.action_save_settings))
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals(listOf("sk-unsaved-secret"), savedKeys)

        // Edit again without saving, then simulate process death with a fresh empty ViewModel:
        // neither SettingsScreen nor SavedStateRegistry restores the unsaved plaintext API key,
        // and showApiKey ephemeral state resets to hidden.
        keyNode.performScrollTo().performTextReplacement("sk-never-restored-in-bundle")
        currentViewModel = SettingsViewModel(app = app, repository = store, apiClient = OpenAiApiClient())
        restorationTester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.label_api_key)).assert(hiddenMatcher)
        assertTrue(
            compose.onAllNodesWithText("sk-never-restored-in-bundle").fetchSemanticsNodes().isEmpty()
        )
        // Verify show/hide toggle re-masked after state restoration.
        compose.onNodeWithContentDescription(context.getString(R.string.action_show_api_key)).assertIsDisplayed()
    }

    @Test
    fun audioSeekBarSeparatesPlaybackTicksFromScrubPreviewAndCommitsOncePerAdjustment() {
        var meetingId by mutableLongStateOf(1L)
        var positionMs by mutableIntStateOf(10_000)
        var durationMs by mutableIntStateOf(60_000)
        val committedSeeks = mutableListOf<Int>()

        compose.setContent {
            MaterialTheme {
                AudioSeekBar(
                    meetingId = meetingId,
                    audioPositionMs = positionMs,
                    audioDurationMs = durationMs,
                    onSeekCommitted = { committedSeeks += it }
                )
            }
        }

        // Playback ticks advance thumb/label without triggering seeks.
        compose.onNodeWithText("00:10 / 01:00").assertIsDisplayed()
        positionMs = 25_000
        compose.waitForIdle()
        compose.onNodeWithText("00:25 / 01:00").assertIsDisplayed()
        assertTrue(committedSeeks.isEmpty())

        // Accessibility/keyboard SetProgress commits one clamped seek.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                action(35_000f)
            }
        compose.waitForIdle()
        assertEquals(listOf(35_000), committedSeeks)

        // During active drag, playback ticks do not overwrite the preview or commit extra seeks.
        val sliderNode = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        sliderNode.performTouchInput {
            down(center)
            moveTo(Offset(right - 1f, centerY))
        }
        compose.waitForIdle()
        compose.onNodeWithText("01:00 / 01:00").assertIsDisplayed()

        positionMs = 12_000
        compose.waitForIdle()
        compose.onNodeWithText("01:00 / 01:00").assertIsDisplayed()
        assertEquals(1, committedSeeks.size)

        sliderNode.performTouchInput {
            up()
        }
        compose.waitForIdle()
        assertEquals(2, committedSeeks.size)
        assertEquals(60_000, committedSeeks.last())

        // Changing meetingId / duration resets SliderState range cleanly.
        meetingId = 2L
        durationMs = 120_000
        positionMs = 5_000
        compose.waitForIdle()
        compose.onNodeWithText("00:05 / 02:00").assertIsDisplayed()
    }
}
