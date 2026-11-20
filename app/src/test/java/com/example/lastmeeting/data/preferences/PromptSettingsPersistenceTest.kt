package com.example.lastmeeting.data.preferences

import android.app.Application
import android.content.res.Configuration
import com.example.lastmeeting.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PromptSettingsPersistenceTest {

    @Test
    fun normalizePromptOverrideDropsBlankAndDefaultPrompts() {
        val defaultText = "Default prompt"
        assertNull(SettingsRepository.normalizePromptOverride("", defaultText))
        assertNull(SettingsRepository.normalizePromptOverride("   ", defaultText))
        assertNull(SettingsRepository.normalizePromptOverride("  Default prompt  ", defaultText))
        assertEquals(
            "Custom prompt",
            SettingsRepository.normalizePromptOverride("  Custom prompt  ", defaultText)
        )
    }

    @Test
    fun savingDefaultPromptsDoesNotPersistOverridesAndAllowsLocaleUpdates() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val repository = SettingsRepository(app)
        val enTranscriptionDefault = app.getString(R.string.default_transcription_prompt)
        val enProtocolDefault = app.getString(R.string.default_protocol_prompt)

        val deConfig = Configuration(app.resources.configuration).apply { setLocale(Locale.GERMAN) }
        val deContext = app.createConfigurationContext(deConfig)
        val deRepository = SettingsRepository(deContext)
        val deTranscriptionDefault = deContext.getString(R.string.default_transcription_prompt)
        val deProtocolDefault = deContext.getString(R.string.default_protocol_prompt)

        // Saving with the built-in English defaults must not pin them as custom overrides.
        repository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "sk-test",
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = enTranscriptionDefault,
            protocolPrompt = enProtocolDefault
        )

        val afterDefaultSaveDe = deRepository.settingsFlow.first()
        assertEquals(deTranscriptionDefault, afterDefaultSaveDe.transcriptionPrompt)
        assertEquals(deProtocolDefault, afterDefaultSaveDe.protocolPrompt)

        // Saving custom overrides persists them across locales.
        repository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "sk-test",
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "Custom transcription hints",
            protocolPrompt = "Custom protocol format"
        )

        val afterCustomSaveDe = deRepository.settingsFlow.first()
        assertEquals("Custom transcription hints", afterCustomSaveDe.transcriptionPrompt)
        assertEquals("Custom protocol format", afterCustomSaveDe.protocolPrompt)

        // Resetting back to the active default removes the override keys again.
        repository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "sk-test",
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = enTranscriptionDefault,
            protocolPrompt = enProtocolDefault
        )

        val afterResetDe = deRepository.settingsFlow.first()
        assertEquals(deTranscriptionDefault, afterResetDe.transcriptionPrompt)
        assertEquals(deProtocolDefault, afterResetDe.protocolPrompt)
    }

    @Test
    fun resolveTranscriptionPromptOmitsBuiltInInstructionOnMultipartEndpointOnly() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val repository = SettingsRepository(app)
        val enTranscriptionDefault = app.getString(R.string.default_transcription_prompt)
        val enProtocolDefault = app.getString(R.string.default_protocol_prompt)

        val uncustomized = repository.settingsFlow.first()
        assertNull(uncustomized.customTranscriptionPrompt)
        assertEquals("", uncustomized.resolveTranscriptionPrompt(useMultipart = true))
        assertEquals(enTranscriptionDefault, uncustomized.resolveTranscriptionPrompt(useMultipart = false))

        repository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "sk-test",
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "Project names: Phoenix, Apollo",
            protocolPrompt = enProtocolDefault
        )

        val customized = repository.settingsFlow.first()
        assertEquals("Project names: Phoenix, Apollo", customized.customTranscriptionPrompt)
        assertEquals("Project names: Phoenix, Apollo", customized.resolveTranscriptionPrompt(useMultipart = true))
        assertEquals("Project names: Phoenix, Apollo", customized.resolveTranscriptionPrompt(useMultipart = false))
    }
}
