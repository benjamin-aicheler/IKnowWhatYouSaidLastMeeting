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
    val model: String,
    val transcriptionPrompt: String,
    val protocolPrompt: String
)

class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_BASE_URL = stringPreferencesKey("api_base_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL = stringPreferencesKey("api_model")
        val KEY_TRANSCRIPTION_PROMPT = stringPreferencesKey("transcription_prompt")
        val KEY_PROTOCOL_PROMPT = stringPreferencesKey("protocol_prompt")

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1/"
        const val DEFAULT_MODEL = "gpt-4o-audio-preview"
    }

    val settingsFlow: Flow<ApiSettings> = context.dataStore.data.map { preferences ->
        val defaultTranscriptionPrompt = context.getString(R.string.default_transcription_prompt)
        val defaultProtocolPrompt = context.getString(R.string.default_protocol_prompt)

        ApiSettings(
            baseUrl = preferences[KEY_BASE_URL] ?: DEFAULT_BASE_URL,
            apiKey = preferences[KEY_API_KEY] ?: "",
            model = preferences[KEY_MODEL] ?: DEFAULT_MODEL,
            transcriptionPrompt = preferences[KEY_TRANSCRIPTION_PROMPT] ?: defaultTranscriptionPrompt,
            protocolPrompt = preferences[KEY_PROTOCOL_PROMPT] ?: defaultProtocolPrompt
        )
    }

    suspend fun saveSettings(
        baseUrl: String,
        apiKey: String,
        model: String,
        transcriptionPrompt: String,
        protocolPrompt: String
    ) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BASE_URL] = baseUrl.trim()
            preferences[KEY_API_KEY] = apiKey.trim()
            preferences[KEY_MODEL] = model.trim()
            preferences[KEY_TRANSCRIPTION_PROMPT] = transcriptionPrompt.trim()
            preferences[KEY_PROTOCOL_PROMPT] = protocolPrompt.trim()
        }
    }
}
