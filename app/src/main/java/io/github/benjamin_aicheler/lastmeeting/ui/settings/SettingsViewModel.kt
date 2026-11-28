package io.github.benjamin_aicheler.lastmeeting.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.benjamin_aicheler.lastmeeting.LastMeetingApp
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.api.ApiHttpException
import io.github.benjamin_aicheler.lastmeeting.data.api.BaseUrlValidationResult
import io.github.benjamin_aicheler.lastmeeting.data.api.InvalidApiKeyException
import io.github.benjamin_aicheler.lastmeeting.data.api.OpenAiApiClient
import io.github.benjamin_aicheler.lastmeeting.data.api.UnexpectedModelsResponseException
import io.github.benjamin_aicheler.lastmeeting.data.api.UnsupportedModelsEndpointException
import io.github.benjamin_aicheler.lastmeeting.data.preferences.ApiSettings
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsRepository
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsStore
import io.github.benjamin_aicheler.lastmeeting.data.preferences.TranscriptionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class SettingsDraft(
    val baseUrl: String = SettingsRepository.DEFAULT_BASE_URL,
    val apiKey: String = "",
    val transcriptionMode: TranscriptionMode = TranscriptionMode.AUTO,
    val transcriptionModel: String = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
    val protocolModel: String = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
    val transcriptionPrompt: String = "",
    val protocolPrompt: String = ""
)

data class SettingsUiState(
    val isLoaded: Boolean = false,
    val baseline: ApiSettings? = null,
    val draft: SettingsDraft = SettingsDraft(),
    val urlValidation: BaseUrlValidationResult = OpenAiApiClient.validateBaseUrl(SettingsRepository.DEFAULT_BASE_URL),
    val isTestingConnection: Boolean = false,
    val isSaving: Boolean = false,
    val showDiscardDialog: Boolean = false
) {
    val hasUnsavedChanges: Boolean
        get() = isLoaded && baseline?.let { saved ->
            draft.baseUrl.trim() != saved.baseUrl.trim() ||
                draft.apiKey.trim() != saved.apiKey.trim() ||
                draft.transcriptionMode != saved.transcriptionMode ||
                draft.transcriptionModel.trim() != saved.transcriptionModel.trim() ||
                draft.protocolModel.trim() != saved.protocolModel.trim() ||
                draft.transcriptionPrompt.trim() != saved.transcriptionPrompt.trim() ||
                draft.protocolPrompt.trim() != saved.protocolPrompt.trim()
        } ?: false

    val canTestConnection: Boolean
        get() = isLoaded && !isTestingConnection && urlValidation is BaseUrlValidationResult.Valid

    val canSave: Boolean
        get() = isLoaded && !isSaving && urlValidation is BaseUrlValidationResult.Valid
}

class SettingsViewModel internal constructor(
    private val app: Application,
    private val repository: SettingsStore,
    private val apiClient: OpenAiApiClient
) : AndroidViewModel(app) {

    constructor(application: Application) : this(
        app = application,
        repository = (application as LastMeetingApp).settingsRepository,
        apiClient = application.apiClient
    )

    private var editVersion = 0L

    // Keep plaintext API key edits only in ViewModel memory (never SavedStateHandle/rememberSaveable)
    // so configuration changes preserve drafts without leaking secrets into saved instance state.
    val apiKeyState: TextFieldState = TextFieldState()
    private var isApplyingApiKeyProgrammatically = false

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _messageFlow = MutableSharedFlow<String>()
    val messageFlow: SharedFlow<String> = _messageFlow.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.settingsFlow.collect { persisted ->
                var shouldSyncApiKeyState = false
                _uiState.update { current ->
                    val shouldResetDraft = !current.isLoaded || (!current.hasUnsavedChanges && !current.isSaving)
                    shouldSyncApiKeyState = shouldResetDraft
                    if (shouldResetDraft) {
                        current.copy(
                            isLoaded = true,
                            baseline = persisted,
                            draft = persisted.toDraft(),
                            urlValidation = OpenAiApiClient.validateBaseUrl(persisted.baseUrl)
                        )
                    } else {
                        current.copy(isLoaded = true, baseline = persisted)
                    }
                }
                if (shouldSyncApiKeyState) {
                    applyApiKeyToState(persisted.apiKey)
                }
            }
        }
        viewModelScope.launch {
            snapshotFlow { apiKeyState.text.toString() }.collect { currentKey ->
                if (!isApplyingApiKeyProgrammatically &&
                    _uiState.value.isLoaded &&
                    currentKey != _uiState.value.draft.apiKey
                ) {
                    updateDraft { it.copy(apiKey = currentKey) }
                }
            }
        }
    }

    private fun applyApiKeyToState(value: String) {
        if (apiKeyState.text.toString() != value) {
            isApplyingApiKeyProgrammatically = true
            try {
                apiKeyState.setTextAndPlaceCursorAtEnd(value)
            } finally {
                isApplyingApiKeyProgrammatically = false
            }
        }
    }

    fun onBaseUrlChanged(value: String) = updateDraft { it.copy(baseUrl = value) }

    fun onApiKeyChanged(value: String) {
        if (!_uiState.value.isLoaded) return
        applyApiKeyToState(value)
        updateDraft { it.copy(apiKey = value) }
    }

    fun onTranscriptionModeChanged(mode: TranscriptionMode) = updateDraft { it.copy(transcriptionMode = mode) }

    fun onTranscriptionModelChanged(value: String) = updateDraft { it.copy(transcriptionModel = value) }

    fun onProtocolModelChanged(value: String) = updateDraft { it.copy(protocolModel = value) }

    fun onTranscriptionPromptChanged(value: String) = updateDraft { it.copy(transcriptionPrompt = value) }

    fun onProtocolPromptChanged(value: String) = updateDraft { it.copy(protocolPrompt = value) }

    fun resetTranscriptionPromptToDefault(defaultPrompt: String) =
        updateDraft { it.copy(transcriptionPrompt = defaultPrompt) }

    fun resetProtocolPromptToDefault(defaultPrompt: String) =
        updateDraft { it.copy(protocolPrompt = defaultPrompt) }

    fun requestDiscardDialog() {
        _uiState.update { current ->
            if (current.hasUnsavedChanges) current.copy(showDiscardDialog = true) else current
        }
    }

    fun dismissDiscardDialog() {
        _uiState.update { it.copy(showDiscardDialog = false) }
    }

    private fun updateDraft(transform: (SettingsDraft) -> SettingsDraft) {
        _uiState.update { current ->
            if (!current.isLoaded) return@update current
            editVersion++
            val nextDraft = transform(current.draft)
            current.copy(
                draft = nextDraft,
                urlValidation = OpenAiApiClient.validateBaseUrl(nextDraft.baseUrl)
            )
        }
    }

    fun saveSettings() {
        val currentState = _uiState.value
        if (!currentState.isLoaded || currentState.isSaving) return
        val draft = currentState.draft
        val currentApiKey = apiKeyState.text.toString()
        val editVersionAtStart = editVersion
        val validatedUrl = when (val result = OpenAiApiClient.validateBaseUrl(draft.baseUrl)) {
            is BaseUrlValidationResult.Valid -> result.normalizedUrl
            BaseUrlValidationResult.InvalidSchemeOrHost -> {
                viewModelScope.launch { _messageFlow.emit(app.getString(R.string.error_invalid_base_url)) }
                return
            }
            BaseUrlValidationResult.ContainsCredentialsOrQuery -> {
                viewModelScope.launch {
                    _messageFlow.emit(app.getString(R.string.error_base_url_credentials_or_query))
                }
                return
            }
        }

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                repository.saveSettings(
                    baseUrl = validatedUrl,
                    apiKey = currentApiKey,
                    transcriptionMode = draft.transcriptionMode,
                    transcriptionModel = draft.transcriptionModel,
                    protocolModel = draft.protocolModel,
                    transcriptionPrompt = draft.transcriptionPrompt,
                    protocolPrompt = draft.protocolPrompt
                )
                val persisted = repository.settingsFlow.first()
                var shouldSyncApiKeyState = false
                _uiState.update { state ->
                    val noConcurrentEdits =
                        editVersion == editVersionAtStart && apiKeyState.text.toString() == currentApiKey
                    shouldSyncApiKeyState = noConcurrentEdits
                    if (noConcurrentEdits) {
                        state.copy(
                            baseline = persisted,
                            draft = persisted.toDraft(),
                            urlValidation = OpenAiApiClient.validateBaseUrl(persisted.baseUrl),
                            isSaving = false
                        )
                    } else {
                        state.copy(
                            baseline = persisted,
                            isSaving = false
                        )
                    }
                }
                if (shouldSyncApiKeyState) {
                    applyApiKeyToState(persisted.apiKey)
                }
                _messageFlow.emit(app.getString(R.string.msg_settings_saved))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false) }
                throw e
            }
        }
    }

    fun testConnection() {
        val currentState = _uiState.value
        if (!currentState.isLoaded || currentState.isTestingConnection) return
        val draft = currentState.draft
        val currentApiKey = apiKeyState.text.toString()
        val validatedUrl = when (val result = OpenAiApiClient.validateBaseUrl(draft.baseUrl)) {
            is BaseUrlValidationResult.Valid -> result.normalizedUrl
            BaseUrlValidationResult.InvalidSchemeOrHost -> {
                viewModelScope.launch { _messageFlow.emit(app.getString(R.string.error_invalid_base_url)) }
                return
            }
            BaseUrlValidationResult.ContainsCredentialsOrQuery -> {
                viewModelScope.launch {
                    _messageFlow.emit(app.getString(R.string.error_base_url_credentials_or_query))
                }
                return
            }
        }
        if (currentApiKey.isBlank()) {
            viewModelScope.launch { _messageFlow.emit(app.getString(R.string.msg_missing_api_key)) }
            return
        }

        _uiState.update { it.copy(isTestingConnection = true) }
        viewModelScope.launch {
            try {
                apiClient.testConnection(baseUrl = validatedUrl, apiKey = currentApiKey).fold(
                    onSuccess = {
                        _messageFlow.emit(app.getString(R.string.msg_connection_test_success))
                    },
                    onFailure = { error ->
                        _messageFlow.emit(formatConnectionTestFailure(app, error))
                    }
                )
            } finally {
                _uiState.update { it.copy(isTestingConnection = false) }
            }
        }
    }
}

private fun ApiSettings.toDraft(): SettingsDraft = SettingsDraft(
    baseUrl = baseUrl,
    apiKey = apiKey,
    transcriptionMode = transcriptionMode,
    transcriptionModel = transcriptionModel,
    protocolModel = protocolModel,
    transcriptionPrompt = transcriptionPrompt,
    protocolPrompt = protocolPrompt
)

internal fun formatConnectionTestFailure(context: Context, error: Throwable): String = when (error) {
    is InvalidApiKeyException ->
        context.getString(R.string.msg_connection_test_invalid_api_key)
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
