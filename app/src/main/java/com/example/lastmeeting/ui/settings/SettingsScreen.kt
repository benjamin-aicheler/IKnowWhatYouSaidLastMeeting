package com.example.lastmeeting.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.lastmeeting.R
import com.example.lastmeeting.data.api.BaseUrlValidationResult
import com.example.lastmeeting.data.api.OpenAiApiClient
import com.example.lastmeeting.data.preferences.ApiSettings
import com.example.lastmeeting.data.preferences.TranscriptionMode
import com.example.lastmeeting.ui.theme.AppIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val isLoaded by viewModel.isLoaded.collectAsState()
    val isTestingConnection by viewModel.isTestingConnection.collectAsState()
    val lastSavedSettings by viewModel.lastSavedSettings.collectAsState()

    var baseline by remember { mutableStateOf<ApiSettings?>(null) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var transcriptionMode by remember { mutableStateOf(settings.transcriptionMode) }
    var showModeMenu by remember { mutableStateOf(false) }
    var transcriptionModel by remember { mutableStateOf(settings.transcriptionModel) }
    var protocolModel by remember { mutableStateOf(settings.protocolModel) }
    var transcriptionPrompt by remember { mutableStateOf(settings.transcriptionPrompt) }
    var protocolPrompt by remember { mutableStateOf(settings.protocolPrompt) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val defaultTranscriptionPrompt = stringResource(R.string.default_transcription_prompt)
    val defaultProtocolPrompt = stringResource(R.string.default_protocol_prompt)

    val hasUnsavedChanges = baseline?.let { saved ->
        baseUrl.trim() != saved.baseUrl.trim() ||
            apiKey.trim() != saved.apiKey.trim() ||
            transcriptionMode != saved.transcriptionMode ||
            transcriptionModel.trim() != saved.transcriptionModel.trim() ||
            protocolModel.trim() != saved.protocolModel.trim() ||
            transcriptionPrompt.trim() != saved.transcriptionPrompt.trim() ||
            protocolPrompt.trim() != saved.protocolPrompt.trim()
    } ?: false

    fun applyBaseline(target: ApiSettings) {
        baseline = target
        baseUrl = target.baseUrl
        apiKey = target.apiKey
        transcriptionMode = target.transcriptionMode
        transcriptionModel = target.transcriptionModel
        protocolModel = target.protocolModel
        transcriptionPrompt = target.transcriptionPrompt
        protocolPrompt = target.protocolPrompt
    }

    LaunchedEffect(isLoaded, settings) {
        if (isLoaded && (baseline == null || !hasUnsavedChanges)) {
            applyBaseline(settings)
        }
    }

    LaunchedEffect(lastSavedSettings) {
        lastSavedSettings?.second?.let { saved ->
            applyBaseline(saved)
        }
    }

    val urlValidation = remember(baseUrl) { OpenAiApiClient.validateBaseUrl(baseUrl) }

    fun handleBackPress() {
        if (hasUnsavedChanges) {
            showDiscardDialog = true
        } else {
            onNavigateBack()
        }
    }

    BackHandler(enabled = hasUnsavedChanges) {
        showDiscardDialog = true
    }

    LaunchedEffect(Unit) {
        viewModel.messageFlow.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = ::handleBackPress) {
                        Icon(
                            imageVector = AppIcons.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.label_api_base_url)) },
                isError = urlValidation !is BaseUrlValidationResult.Valid,
                supportingText = when (urlValidation) {
                    is BaseUrlValidationResult.Valid -> if (urlValidation.isPlainHttp) {
                        {
                            Text(
                                text = stringResource(R.string.warning_plain_http_base_url),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        null
                    }
                    BaseUrlValidationResult.InvalidSchemeOrHost -> {
                        { Text(stringResource(R.string.error_invalid_base_url)) }
                    }
                    BaseUrlValidationResult.ContainsCredentialsOrQuery -> {
                        { Text(stringResource(R.string.error_base_url_credentials_or_query)) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.label_api_key)) },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false
                ),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) AppIcons.VisibilityOff else AppIcons.Visibility,
                            contentDescription = stringResource(
                                if (showApiKey) R.string.action_hide_api_key else R.string.action_show_api_key
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            ExposedDropdownMenuBox(
                expanded = showModeMenu,
                onExpandedChange = { showModeMenu = !showModeMenu }
            ) {
                OutlinedTextField(
                    value = stringResource(transcriptionModeLabelRes(transcriptionMode)),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_transcription_mode)) },
                    supportingText = { Text(stringResource(R.string.helper_transcription_mode)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = showModeMenu)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = showModeMenu,
                    onDismissRequest = { showModeMenu = false }
                ) {
                    TranscriptionMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(stringResource(transcriptionModeLabelRes(mode))) },
                            onClick = {
                                transcriptionMode = mode
                                showModeMenu = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = transcriptionModel,
                onValueChange = { transcriptionModel = it },
                label = { Text(stringResource(R.string.label_transcription_model)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = protocolModel,
                onValueChange = { protocolModel = it },
                label = { Text(stringResource(R.string.label_protocol_model)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = transcriptionPrompt,
                    onValueChange = { transcriptionPrompt = it },
                    label = { Text(stringResource(R.string.label_transcription_prompt)) },
                    supportingText = { Text(stringResource(R.string.helper_transcription_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                TextButton(
                    onClick = { transcriptionPrompt = defaultTranscriptionPrompt },
                    enabled = transcriptionPrompt.trim() != defaultTranscriptionPrompt.trim(),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.action_reset_prompt_default))
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = protocolPrompt,
                    onValueChange = { protocolPrompt = it },
                    label = { Text(stringResource(R.string.label_protocol_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                TextButton(
                    onClick = { protocolPrompt = defaultProtocolPrompt },
                    enabled = protocolPrompt.trim() != defaultProtocolPrompt.trim(),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.action_reset_prompt_default))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { viewModel.testConnection(baseUrl = baseUrl, apiKey = apiKey) },
                enabled = !isTestingConnection,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(
                        if (isTestingConnection) {
                            R.string.msg_testing_connection
                        } else {
                            R.string.action_test_connection
                        }
                    )
                )
            }

            Button(
                onClick = {
                    viewModel.saveSettings(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        transcriptionMode = transcriptionMode,
                        transcriptionModel = transcriptionModel,
                        protocolModel = protocolModel,
                        transcriptionPrompt = transcriptionPrompt,
                        protocolPrompt = protocolPrompt
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.action_save_settings))
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.confirm_discard_settings_title)) },
            text = { Text(stringResource(R.string.confirm_discard_settings_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

private fun transcriptionModeLabelRes(mode: TranscriptionMode): Int = when (mode) {
    TranscriptionMode.AUTO -> R.string.transcription_mode_auto
    TranscriptionMode.AUDIO_TRANSCRIPTIONS -> R.string.transcription_mode_audio_transcriptions
    TranscriptionMode.CHAT_COMPLETIONS -> R.string.transcription_mode_chat_completions
}
