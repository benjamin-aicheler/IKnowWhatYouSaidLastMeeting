package com.example.lastmeeting.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.lastmeeting.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "last_meeting_settings")

enum class TranscriptionMode(val storageValue: String) {
    AUTO("auto"),
    AUDIO_TRANSCRIPTIONS("audio_transcriptions"),
    CHAT_COMPLETIONS("chat_completions");

    companion object {
        fun fromStorage(value: String?): TranscriptionMode =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() } ?: AUTO
    }
}

data class ApiSettings(
    val baseUrl: String,
    val apiKey: String,
    val transcriptionMode: TranscriptionMode = TranscriptionMode.AUTO,
    val transcriptionModel: String,
    val protocolModel: String,
    val transcriptionPrompt: String,
    val protocolPrompt: String
)

class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_BASE_URL = stringPreferencesKey("api_base_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL = stringPreferencesKey("api_model")
        val KEY_TRANSCRIPTION_MODEL = stringPreferencesKey("api_transcription_model")
        val KEY_PROTOCOL_MODEL = stringPreferencesKey("api_protocol_model")
        val KEY_TRANSCRIPTION_PROMPT = stringPreferencesKey("transcription_prompt")
        val KEY_PROTOCOL_PROMPT = stringPreferencesKey("protocol_prompt")
        val KEY_TRANSCRIPTION_MODE = stringPreferencesKey("api_transcription_mode")
        val KEY_CONSENT_NOTICE_ACKNOWLEDGED = booleanPreferencesKey("consent_notice_acknowledged")

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1/"
        const val DEFAULT_TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe"
        const val DEFAULT_PROTOCOL_MODEL = "gpt-4o-mini"
        internal const val LEGACY_RETIRED_MODEL = "gpt-4o-audio-preview"

        internal fun resolveModel(
            stored: String?,
            legacyStored: String? = null,
            defaultModel: String
        ): String {
            val candidate = stored?.trim()?.takeIf { it.isNotEmpty() }
                ?: legacyStored?.trim()?.takeIf { it.isNotEmpty() }
            return if (candidate == null || candidate == LEGACY_RETIRED_MODEL) {
                defaultModel
            } else {
                candidate
            }
        }

        internal fun normalizePromptOverride(input: String, defaultPrompt: String): String? {
            val trimmedInput = input.trim()
            val trimmedDefault = defaultPrompt.trim()
            return if (trimmedInput.isEmpty() || trimmedInput == trimmedDefault) {
                null
            } else {
                trimmedInput
            }
        }
    }

    val settingsFlow: Flow<ApiSettings> = context.dataStore.data.map { preferences ->
        val defaultTranscriptionPrompt = context.getString(R.string.default_transcription_prompt)
        val defaultProtocolPrompt = context.getString(R.string.default_protocol_prompt)
        val legacyModel = preferences[KEY_MODEL]

        ApiSettings(
            baseUrl = preferences[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
            apiKey = preferences[KEY_API_KEY] ?: "",
            transcriptionMode = TranscriptionMode.fromStorage(preferences[KEY_TRANSCRIPTION_MODE]),
            transcriptionModel = resolveModel(
                stored = preferences[KEY_TRANSCRIPTION_MODEL],
                legacyStored = legacyModel,
                defaultModel = DEFAULT_TRANSCRIPTION_MODEL
            ),
            protocolModel = resolveModel(
                stored = preferences[KEY_PROTOCOL_MODEL],
                legacyStored = legacyModel,
                defaultModel = DEFAULT_PROTOCOL_MODEL
            ),
            transcriptionPrompt = preferences[KEY_TRANSCRIPTION_PROMPT]?.takeIf { it.isNotBlank() }
                ?: defaultTranscriptionPrompt,
            protocolPrompt = preferences[KEY_PROTOCOL_PROMPT]?.takeIf { it.isNotBlank() }
                ?: defaultProtocolPrompt
        )
    }

    suspend fun saveSettings(
        baseUrl: String,
        apiKey: String,
        transcriptionMode: TranscriptionMode = TranscriptionMode.AUTO,
        transcriptionModel: String,
        protocolModel: String,
        transcriptionPrompt: String,
        protocolPrompt: String
    ) {
        val defaultTranscriptionPrompt = context.getString(R.string.default_transcription_prompt)
        val defaultProtocolPrompt = context.getString(R.string.default_protocol_prompt)
        val normalizedTranscriptionPrompt = normalizePromptOverride(transcriptionPrompt, defaultTranscriptionPrompt)
        val normalizedProtocolPrompt = normalizePromptOverride(protocolPrompt, defaultProtocolPrompt)

        context.dataStore.edit { preferences ->
            preferences[KEY_BASE_URL] = baseUrl.trim()
            preferences[KEY_API_KEY] = apiKey.trim()
            preferences[KEY_TRANSCRIPTION_MODE] = transcriptionMode.storageValue
            preferences[KEY_TRANSCRIPTION_MODEL] = transcriptionModel.trim()
            preferences[KEY_PROTOCOL_MODEL] = protocolModel.trim()
            preferences.remove(KEY_MODEL)
            if (normalizedTranscriptionPrompt != null) {
                preferences[KEY_TRANSCRIPTION_PROMPT] = normalizedTranscriptionPrompt
            } else {
                preferences.remove(KEY_TRANSCRIPTION_PROMPT)
            }
            if (normalizedProtocolPrompt != null) {
                preferences[KEY_PROTOCOL_PROMPT] = normalizedProtocolPrompt
            } else {
                preferences.remove(KEY_PROTOCOL_PROMPT)
            }
        }
    }

    val consentNoticeAcknowledgedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_CONSENT_NOTICE_ACKNOWLEDGED] ?: false
    }

    suspend fun acknowledgeConsentNotice() {
        context.dataStore.edit { preferences ->
            preferences[KEY_CONSENT_NOTICE_ACKNOWLEDGED] = true
        }
    }
}
