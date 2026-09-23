package com.example.lastmeeting.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.data.preferences.ApiSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LastMeetingApp
    private val repository = app.settingsRepository

    val settings: StateFlow<ApiSettings> = repository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ApiSettings(
            baseUrl = "https://api.openai.com/v1/",
            apiKey = "",
            model = "gpt-4o-audio-preview",
            transcriptionPrompt = "",
            protocolPrompt = ""
        )
    )

    private val _messageFlow = MutableSharedFlow<String>()
    val messageFlow: SharedFlow<String> = _messageFlow.asSharedFlow()

    fun saveSettings(
        baseUrl: String,
        apiKey: String,
        model: String,
        transcriptionPrompt: String,
        protocolPrompt: String
    ) {
        viewModelScope.launch {
            repository.saveSettings(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                transcriptionPrompt = transcriptionPrompt,
                protocolPrompt = protocolPrompt
            )
            _messageFlow.emit("Settings saved successfully.")
        }
    }
}
