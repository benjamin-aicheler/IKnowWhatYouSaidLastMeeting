package com.example.lastmeeting.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.R
import com.example.lastmeeting.data.api.BaseUrlValidationResult
import com.example.lastmeeting.data.api.OpenAiApiClient
import com.example.lastmeeting.data.preferences.ApiSettings
import com.example.lastmeeting.data.preferences.SettingsRepository
import com.example.lastmeeting.data.preferences.TranscriptionMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LastMeetingApp
    private val repository = app.settingsRepository
    private val apiClient = OpenAiApiClient()
    private var saveCounter = 0

    val settings: StateFlow<ApiSettings> = repository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ApiSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "",
            transcriptionMode = TranscriptionMode.AUTO,
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "",
            protocolPrompt = ""
        )
    )

    val isLoaded: StateFlow<Boolean> = repository.settingsFlow
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _lastSavedSettings = MutableStateFlow<Pair<Int, ApiSettings>?>(null)
    val lastSavedSettings: StateFlow<Pair<Int, ApiSettings>?> = _lastSavedSettings.asStateFlow()

    private val _messageFlow = MutableSharedFlow<String>()
    val messageFlow: SharedFlow<String> = _messageFlow.asSharedFlow()

    fun saveSettings(
        baseUrl: String,
        apiKey: String,
        transcriptionMode: TranscriptionMode = TranscriptionMode.AUTO,
        transcriptionModel: String,
        protocolModel: String,
        transcriptionPrompt: String,
        protocolPrompt: String
    ) {
        viewModelScope.launch {
            val validatedUrl = when (val result = OpenAiApiClient.validateBaseUrl(baseUrl)) {
                is BaseUrlValidationResult.Valid -> result.normalizedUrl
                BaseUrlValidationResult.InvalidSchemeOrHost -> {
                    _messageFlow.emit(app.getString(R.string.error_invalid_base_url))
                    return@launch
                }
                BaseUrlValidationResult.ContainsCredentialsOrQuery -> {
                    _messageFlow.emit(app.getString(R.string.error_base_url_credentials_or_query))
                    return@launch
                }
            }

            repository.saveSettings(
                baseUrl = validatedUrl,
                apiKey = apiKey,
                transcriptionMode = transcriptionMode,
                transcriptionModel = transcriptionModel,
                protocolModel = protocolModel,
                transcriptionPrompt = transcriptionPrompt,
                protocolPrompt = protocolPrompt
            )
            _lastSavedSettings.value = (++saveCounter) to repository.settingsFlow.first()
            _messageFlow.emit(app.getString(R.string.msg_settings_saved))
        }
    }

    fun testConnection(baseUrl: String, apiKey: String) {
        if (_isTestingConnection.value) return
        viewModelScope.launch {
            val validatedUrl = when (val result = OpenAiApiClient.validateBaseUrl(baseUrl)) {
                is BaseUrlValidationResult.Valid -> result.normalizedUrl
                BaseUrlValidationResult.InvalidSchemeOrHost -> {
                    _messageFlow.emit(app.getString(R.string.error_invalid_base_url))
                    return@launch
                }
                BaseUrlValidationResult.ContainsCredentialsOrQuery -> {
                    _messageFlow.emit(app.getString(R.string.error_base_url_credentials_or_query))
                    return@launch
                }
            }
            if (apiKey.isBlank()) {
                _messageFlow.emit(app.getString(R.string.msg_missing_api_key))
                return@launch
            }

            _isTestingConnection.value = true
            try {
                apiClient.testConnection(baseUrl = validatedUrl, apiKey = apiKey).fold(
                    onSuccess = {
                        _messageFlow.emit(app.getString(R.string.msg_connection_test_success))
                    },
                    onFailure = { error ->
                        _messageFlow.emit(
                            app.getString(
                                R.string.msg_connection_test_failed,
                                error.localizedMessage ?: ""
                            )
                        )
                    }
                )
            } finally {
                _isTestingConnection.value = false
            }
        }
    }
}
