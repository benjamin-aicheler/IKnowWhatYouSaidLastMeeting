package io.github.benjamin_aicheler.lastmeeting.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.benjamin_aicheler.lastmeeting.LastMeetingApp
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.api.ApiHttpException
import io.github.benjamin_aicheler.lastmeeting.data.api.BaseUrlValidationResult
import io.github.benjamin_aicheler.lastmeeting.data.api.OpenAiApiClient
import io.github.benjamin_aicheler.lastmeeting.data.api.UnexpectedModelsResponseException
import io.github.benjamin_aicheler.lastmeeting.data.api.UnsupportedModelsEndpointException
import io.github.benjamin_aicheler.lastmeeting.data.preferences.ApiSettings
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsRepository
import io.github.benjamin_aicheler.lastmeeting.data.preferences.TranscriptionMode
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
import java.io.IOException

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
                        _messageFlow.emit(formatConnectionTestFailure(app, error))
                    }
                )
            } finally {
                _isTestingConnection.value = false
            }
        }
    }
}

internal fun formatConnectionTestFailure(context: Context, error: Throwable): String = when (error) {
    is UnsupportedModelsEndpointException ->
        context.getString(R.string.msg_connection_test_models_unsupported, error.code)
    is UnexpectedModelsResponseException ->
        context.getString(R.string.msg_connection_test_unexpected_response)
    is ApiHttpException -> if (error.code == 401 || error.code == 403) {
        context.getString(R.string.msg_connection_test_auth_failed, error.code)
    } else {
        context.getString(R.string.msg_connection_test_failed, error.localizedMessage ?: "HTTP ${error.code}")
    }
    is IOException ->
        context.getString(R.string.msg_connection_test_network_failed, error.localizedMessage ?: "")
    else ->
        context.getString(R.string.msg_connection_test_failed, error.localizedMessage ?: "")
}
