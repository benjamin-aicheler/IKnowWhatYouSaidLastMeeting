package com.example.lastmeeting.ui.details

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lastmeeting.R
import com.example.lastmeeting.ui.components.DeleteConfirmationDialog
import com.example.lastmeeting.ui.theme.AppIcons
import com.example.lastmeeting.util.DateTimeFormatters

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MeetingDetailsScreen(
    viewModel: MeetingDetailsViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val meeting by viewModel.meeting.collectAsState()
    val isTranscribing by viewModel.isTranscribing.collectAsState()
    val isProtocolizing by viewModel.isProtocolizing.collectAsState()
    val isPlayingAudio by viewModel.isPlayingAudio.collectAsState()
    val audioPositionMs by viewModel.audioPositionMs.collectAsState()
    val audioDurationMs by viewModel.audioDurationMs.collectAsState()
    val showTranscriptionConfirmation by viewModel.showTranscriptionConfirmation.collectAsState()

    var showDeleteMeetingDialog by remember { mutableStateOf(false) }
    var showDeleteTranscriptDialog by remember { mutableStateOf(false) }
    var showDeleteProtocolDialog by remember { mutableStateOf(false) }

    var showShareTranscriptMenu by remember { mutableStateOf(false) }
    var showShareProtocolMenu by remember { mutableStateOf(false) }

    var isEditingTitle by remember { mutableStateOf(false) }
    var titleText by remember(meeting?.title) { mutableStateOf(meeting?.title ?: "") }

    // Publishing to the public Downloads folder needs WRITE_EXTERNAL_STORAGE on API 26-28.
    var pendingExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingExport
        pendingExport = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(context, R.string.msg_storage_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    fun exportToDownloads(action: () -> Unit) {
        val needsLegacyStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyStorage) {
            pendingExport = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, R.string.msg_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(resources) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is DetailsUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
                is DetailsUiEvent.MeetingDeleted -> {
                    Toast.makeText(context, resources.getString(R.string.msg_meeting_deleted), Toast.LENGTH_SHORT).show()
                    onNavigateBack()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.title_meeting_details)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = AppIcons.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteMeetingDialog = true }) {
                        Icon(
                            imageVector = AppIcons.Delete,
                            contentDescription = stringResource(R.string.action_delete_meeting),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        val currentMeeting = meeting
        if (currentMeeting == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val transcriptLabel = stringResource(R.string.label_transcript)
            val protocolLabel = stringResource(R.string.label_protocol)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Editable Title
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (isEditingTitle) {
                            OutlinedTextField(
                                value = titleText,
                                onValueChange = { titleText = it },
                                label = { Text(stringResource(R.string.label_title)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    viewModel.updateTitle(titleText)
                                    isEditingTitle = false
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(stringResource(R.string.action_save))
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentMeeting.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { isEditingTitle = true }) {
                                    Icon(
                                        imageVector = AppIcons.Edit,
                                        contentDescription = stringResource(R.string.action_edit_title)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        val locale = LocalLocale.current.platformLocale
                        val formattedDate = remember(currentMeeting.startTime, locale) {
                            DateTimeFormatters.formatLocalizedDateTime(currentMeeting.startTime, locale)
                        }
                        val durationSeconds = ((currentMeeting.endTime - currentMeeting.startTime) / 1000).coerceAtLeast(0L)
                        val durationFormatted = String.format(
                            locale,
                            "%02d:%02d",
                            durationSeconds / 60,
                            durationSeconds % 60
                        )
                        Text(
                            text = "${stringResource(R.string.label_date)}: $formattedDate • ${stringResource(R.string.label_duration)}: $durationFormatted",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Audio Playback & Actions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.label_audio),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(onClick = { viewModel.toggleAudioPlayback() }) {
                                Icon(
                                    imageVector = if (isPlayingAudio) AppIcons.Pause else AppIcons.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPlayingAudio) stringResource(R.string.action_pause) else stringResource(R.string.action_play))
                            }
                            OutlinedButton(onClick = { exportToDownloads { viewModel.downloadAudio() } }) {
                                Icon(imageVector = AppIcons.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_download_audio))
                            }
                            OutlinedButton(onClick = { viewModel.shareAudio() }) {
                                Icon(imageVector = AppIcons.Share, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_share_audio))
                            }
                        }
                        if (audioDurationMs > 0) {
                            var isDraggingSlider by remember(currentMeeting.id) { mutableStateOf(false) }
                            var dragPositionMs by remember(currentMeeting.id) { mutableStateOf(0f) }
                            val displayedPositionMs = if (isDraggingSlider) {
                                dragPositionMs.toInt()
                            } else {
                                audioPositionMs.coerceIn(0, audioDurationMs)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = displayedPositionMs.toFloat(),
                                onValueChange = { newValue ->
                                    isDraggingSlider = true
                                    dragPositionMs = newValue
                                },
                                onValueChangeFinished = {
                                    val target = dragPositionMs.toInt()
                                    isDraggingSlider = false
                                    viewModel.seekAudioTo(target)
                                },
                                valueRange = 0f..audioDurationMs.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            val locale = LocalLocale.current.platformLocale
                            val elapsedFormatted = formatMillis(displayedPositionMs, locale)
                            val totalFormatted = formatMillis(audioDurationMs, locale)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = "$elapsedFormatted / $totalFormatted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (currentMeeting.isDamaged) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.warning_recording_damaged_details),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // AI Action Trigger Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.transcribe() },
                        enabled = !isTranscribing && !isProtocolizing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isTranscribing) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp).width(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.action_transcribe))
                        }
                    }

                    Button(
                        onClick = { viewModel.protocolize() },
                        enabled = !isTranscribing && !isProtocolizing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isProtocolizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp).width(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.action_protocolize))
                        }
                    }
                }

                // Transcript Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = transcriptLabel,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (currentMeeting.transcript != null) {
                                Row {
                                    IconButton(onClick = {
                                        copyToClipboard(transcriptLabel, currentMeeting.transcript)
                                    }) {
                                        Icon(
                                            imageVector = AppIcons.ContentCopy,
                                            contentDescription = stringResource(R.string.action_copy_transcript)
                                        )
                                    }
                                    IconButton(onClick = {
                                        exportToDownloads {
                                            viewModel.exportPdf(transcriptLabel, currentMeeting.transcript)
                                        }
                                    }) {
                                        Icon(
                                            imageVector = AppIcons.Download,
                                            contentDescription = stringResource(R.string.action_download_transcript_pdf)
                                        )
                                    }
                                    Box {
                                        IconButton(onClick = { showShareTranscriptMenu = true }) {
                                            Icon(
                                                imageVector = AppIcons.Share,
                                                contentDescription = stringResource(R.string.action_share_transcript)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showShareTranscriptMenu,
                                            onDismissRequest = { showShareTranscriptMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.action_share_as_text)) },
                                                onClick = {
                                                    showShareTranscriptMenu = false
                                                    viewModel.shareText(currentMeeting.transcript)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.action_share_as_pdf)) },
                                                onClick = {
                                                    showShareTranscriptMenu = false
                                                    viewModel.sharePdf(transcriptLabel, currentMeeting.transcript)
                                                }
                                            )
                                        }
                                    }
                                    IconButton(onClick = { showDeleteTranscriptDialog = true }) {
                                        Icon(
                                            imageVector = AppIcons.Delete,
                                            contentDescription = stringResource(R.string.action_delete_transcript),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val transcriptText = currentMeeting.transcript
                        if (transcriptText != null) {
                            SelectionContainer {
                                Text(
                                    text = transcriptText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.no_transcript),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        currentMeeting.transcriptError?.takeIf { it.isNotBlank() }?.let { errorMsg ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.msg_ai_error, errorMsg),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // Protocol Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = protocolLabel,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (currentMeeting.protocol != null) {
                                Row {
                                    IconButton(onClick = {
                                        copyToClipboard(protocolLabel, currentMeeting.protocol)
                                    }) {
                                        Icon(
                                            imageVector = AppIcons.ContentCopy,
                                            contentDescription = stringResource(R.string.action_copy_protocol)
                                        )
                                    }
                                    IconButton(onClick = {
                                        exportToDownloads {
                                            viewModel.exportPdf(protocolLabel, currentMeeting.protocol)
                                        }
                                    }) {
                                        Icon(
                                            imageVector = AppIcons.Download,
                                            contentDescription = stringResource(R.string.action_download_protocol_pdf)
                                        )
                                    }
                                    Box {
                                        IconButton(onClick = { showShareProtocolMenu = true }) {
                                            Icon(
                                                imageVector = AppIcons.Share,
                                                contentDescription = stringResource(R.string.action_share_protocol)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showShareProtocolMenu,
                                            onDismissRequest = { showShareProtocolMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.action_share_as_text)) },
                                                onClick = {
                                                    showShareProtocolMenu = false
                                                    viewModel.shareText(currentMeeting.protocol)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.action_share_as_pdf)) },
                                                onClick = {
                                                    showShareProtocolMenu = false
                                                    viewModel.sharePdf(protocolLabel, currentMeeting.protocol)
                                                }
                                            )
                                        }
                                    }
                                    IconButton(onClick = { showDeleteProtocolDialog = true }) {
                                        Icon(
                                            imageVector = AppIcons.Delete,
                                            contentDescription = stringResource(R.string.action_delete_protocol),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val protocolText = currentMeeting.protocol
                        if (protocolText != null) {
                            SelectionContainer {
                                Text(
                                    text = protocolText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.no_protocol),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        currentMeeting.protocolError?.takeIf { it.isNotBlank() }?.let { errorMsg ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.msg_ai_error, errorMsg),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialogs
    if (showTranscriptionConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissTranscriptionConfirmation,
            title = { Text(stringResource(R.string.confirm_replace_transcript_title)) },
            text = { Text(stringResource(R.string.confirm_replace_transcript)) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmTranscription,
                    enabled = !isTranscribing && !isProtocolizing
                ) {
                    Text(stringResource(R.string.action_replace_transcript))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissTranscriptionConfirmation) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteMeetingDialog) {
        DeleteConfirmationDialog(
            message = stringResource(R.string.confirm_delete_meeting),
            onConfirm = {
                showDeleteMeetingDialog = false
                viewModel.deleteMeeting()
            },
            onDismiss = { showDeleteMeetingDialog = false }
        )
    }

    if (showDeleteTranscriptDialog) {
        DeleteConfirmationDialog(
            message = stringResource(R.string.confirm_delete_transcript),
            onConfirm = {
                showDeleteTranscriptDialog = false
                viewModel.deleteTranscript()
            },
            onDismiss = { showDeleteTranscriptDialog = false }
        )
    }

    if (showDeleteProtocolDialog) {
        DeleteConfirmationDialog(
            message = stringResource(R.string.confirm_delete_protocol),
            onConfirm = {
                showDeleteProtocolDialog = false
                viewModel.deleteProtocol()
            },
            onDismiss = { showDeleteProtocolDialog = false }
        )
    }
}

private fun formatMillis(millis: Int, locale: java.util.Locale): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return String.format(locale, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
