package io.github.benjamin_aicheler.lastmeeting.ui.settings

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
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
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
import androidx.compose.ui.unit.dp
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.api.BaseUrlValidationResult
import io.github.benjamin_aicheler.lastmeeting.data.preferences.TranscriptionMode
import io.github.benjamin_aicheler.lastmeeting.ui.theme.AppIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val draft = uiState.draft

    // Ephemeral UI-only state: re-masks the API key after configuration changes.
    var showApiKey by remember { mutableStateOf(false) }
    var showModeMenu by remember { mutableStateOf(false) }
    val defaultTranscriptionPrompt = stringResource(R.string.default_transcription_prompt)
    val defaultProtocolPrompt = stringResource(R.string.default_protocol_prompt)

    LaunchedEffect(uiState.isLoaded) {
        if (!uiState.isLoaded) {
            showModeMenu = false
        }
    }

    fun handleBackPress() {
        if (uiState.hasUnsavedChanges) {
            viewModel.requestDiscardDialog()
        } else {
            onNavigateBack()
        }
    }

    BackHandler(enabled = uiState.hasUnsavedChanges) {
        viewModel.requestDiscardDialog()
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
                value = draft.baseUrl,
                onValueChange = viewModel::onBaseUrlChanged,
                enabled = uiState.isLoaded,
                label = { Text(stringResource(R.string.label_api_base_url)) },
                isError = uiState.isLoaded && uiState.urlValidation !is BaseUrlValidationResult.Valid,
                supportingText = when (val validation = uiState.urlValidation) {
                    is BaseUrlValidationResult.Valid -> if (uiState.isLoaded && validation.isPlainHttp) {
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

            OutlinedSecureTextField(
                state = viewModel.apiKeyState,
                enabled = uiState.isLoaded,
                label = { Text(stringResource(R.string.label_api_key)) },
                textObfuscationMode = if (showApiKey) {
                    TextObfuscationMode.Visible
                } else {
                    TextObfuscationMode.Hidden
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false
                ),
                trailingIcon = {
                    IconButton(
                        onClick = { showApiKey = !showApiKey },
                        enabled = uiState.isLoaded
                    ) {
                        Icon(
                            imageVector = if (showApiKey) AppIcons.VisibilityOff else AppIcons.Visibility,
                            contentDescription = stringResource(
                                if (showApiKey) R.string.action_hide_api_key else R.string.action_show_api_key
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenuBox(
                expanded = uiState.isLoaded && showModeMenu,
                onExpandedChange = {
                    if (uiState.isLoaded) {
                        showModeMenu = !showModeMenu
                    }
                }
            ) {
                OutlinedTextField(
                    value = stringResource(transcriptionModeLabelRes(draft.transcriptionMode)),
                    onValueChange = {},
                    readOnly = true,
                    enabled = uiState.isLoaded,
                    label = { Text(stringResource(R.string.label_transcription_mode)) },
                    supportingText = { Text(stringResource(R.string.helper_transcription_mode)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = uiState.isLoaded && showModeMenu)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = uiState.isLoaded && showModeMenu,
                    onDismissRequest = { showModeMenu = false }
                ) {
                    TranscriptionMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(stringResource(transcriptionModeLabelRes(mode))) },
                            onClick = {
                                viewModel.onTranscriptionModeChanged(mode)
                                showModeMenu = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = draft.transcriptionModel,
                onValueChange = viewModel::onTranscriptionModelChanged,
                enabled = uiState.isLoaded,
                label = { Text(stringResource(R.string.label_transcription_model)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = draft.protocolModel,
                onValueChange = viewModel::onProtocolModelChanged,
                enabled = uiState.isLoaded,
                label = { Text(stringResource(R.string.label_protocol_model)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = draft.transcriptionPrompt,
                    onValueChange = viewModel::onTranscriptionPromptChanged,
                    enabled = uiState.isLoaded,
                    label = { Text(stringResource(R.string.label_transcription_prompt)) },
                    supportingText = { Text(stringResource(R.string.helper_transcription_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                TextButton(
                    onClick = { viewModel.resetTranscriptionPromptToDefault(defaultTranscriptionPrompt) },
                    enabled = uiState.isLoaded && draft.transcriptionPrompt.trim() != defaultTranscriptionPrompt.trim(),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.action_reset_prompt_default))
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = draft.protocolPrompt,
                    onValueChange = viewModel::onProtocolPromptChanged,
                    enabled = uiState.isLoaded,
                    label = { Text(stringResource(R.string.label_protocol_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                TextButton(
                    onClick = { viewModel.resetProtocolPromptToDefault(defaultProtocolPrompt) },
                    enabled = uiState.isLoaded && draft.protocolPrompt.trim() != defaultProtocolPrompt.trim(),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.action_reset_prompt_default))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = uiState.canTestConnection,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(
                        if (uiState.isTestingConnection) {
                            R.string.msg_testing_connection
                        } else {
                            R.string.action_test_connection
                        }
                    )
                )
            }

            Button(
                onClick = viewModel::saveSettings,
                enabled = uiState.canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.action_save_settings))
            }
        }
    }

    if (uiState.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDiscardDialog,
            title = { Text(stringResource(R.string.confirm_discard_settings_title)) },
            text = { Text(stringResource(R.string.confirm_discard_settings_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissDiscardDialog()
                        onNavigateBack()
                    }
                ) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDiscardDialog) {
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
