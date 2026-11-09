package com.example.lastmeeting.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.lastmeeting.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "last_meeting_settings")

data class ApiSettings(
    val baseUrl: String,
    val apiKey: String,
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
    }

    val settingsFlow: Flow<ApiSettings> = context.dataStore.data.map { preferences ->
        val defaultTranscriptionPrompt = context.getString(R.string.default_transcription_prompt)
        val defaultProtocolPrompt = context.getString(R.string.default_protocol_prompt)
        val legacyModel = preferences[KEY_MODEL]

        ApiSettings(
            baseUrl = preferences[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
            apiKey = preferences[KEY_API_KEY] ?: "",
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
        transcriptionModel: String,
        protocolModel: String,
        transcriptionPrompt: String,
        protocolPrompt: String
    ) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BASE_URL] = baseUrl.trim()
            preferences[KEY_API_KEY] = apiKey.trim()
            preferences[KEY_TRANSCRIPTION_MODEL] = transcriptionModel.trim()
            preferences[KEY_PROTOCOL_MODEL] = protocolModel.trim()
            preferences.remove(KEY_MODEL)
            preferences[KEY_TRANSCRIPTION_PROMPT] = transcriptionPrompt.trim()
            preferences[KEY_PROTOCOL_PROMPT] = protocolPrompt.trim()
        }
    }
}
