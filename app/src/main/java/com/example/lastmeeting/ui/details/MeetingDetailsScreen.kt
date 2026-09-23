package com.example.lastmeeting.ui.details

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lastmeeting.R
import com.example.lastmeeting.ui.components.DeleteConfirmationDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingDetailsScreen(
    viewModel: MeetingDetailsViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val meeting by viewModel.meeting.collectAsState()
    val isTranscribing by viewModel.isTranscribing.collectAsState()
    val isProtocolizing by viewModel.isProtocolizing.collectAsState()
    val isPlayingAudio by viewModel.isPlayingAudio.collectAsState()

    var showDeleteMeetingDialog by remember { mutableStateOf(false) }
    var showDeleteTranscriptDialog by remember { mutableStateOf(false) }
    var showDeleteProtocolDialog by remember { mutableStateOf(false) }

    var isEditingTitle by remember { mutableStateOf(false) }
    var titleText by remember(meeting) { mutableStateOf(meeting?.title ?: "") }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is DetailsUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
                is DetailsUiEvent.MeetingDeleted -> {
                    Toast.makeText(context, context.getString(R.string.action_delete_meeting), Toast.LENGTH_SHORT).show()
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteMeetingDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
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
                                Text("Save")
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
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit Title"
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                        Text(
                            text = "${stringResource(R.string.label_date)}: ${dateFormat.format(Date(currentMeeting.startTime))}",
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(onClick = { viewModel.toggleAudioPlayback() }) {
                                Icon(
                                    imageVector = if (isPlayingAudio) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isPlayingAudio) stringResource(R.string.action_pause) else stringResource(R.string.action_play))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            OutlinedButton(onClick = { viewModel.shareAudio() }) {
                                Icon(imageVector = Icons.Default.Share, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_share_audio))
                            }
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
                                text = stringResource(R.string.label_transcript),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (currentMeeting.transcript != null) {
                                Row {
                                    IconButton(onClick = {
                                        viewModel.exportPdf("Transcript", currentMeeting.transcript)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = stringResource(R.string.action_download_transcript_pdf)
                                        )
                                    }
                                    IconButton(onClick = {
                                        viewModel.shareText(currentMeeting.transcript)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = stringResource(R.string.action_share_transcript)
                                        )
                                    }
                                    IconButton(onClick = { showDeleteTranscriptDialog = true }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.action_delete_transcript),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currentMeeting.transcript ?: stringResource(R.string.no_transcript),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (currentMeeting.transcript != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                text = stringResource(R.string.label_protocol),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (currentMeeting.protocol != null) {
                                Row {
                                    IconButton(onClick = {
                                        viewModel.exportPdf("Protocol", currentMeeting.protocol)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = stringResource(R.string.action_download_protocol_pdf)
                                        )
                                    }
                                    IconButton(onClick = {
                                        viewModel.shareText(currentMeeting.protocol)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = stringResource(R.string.action_share_protocol)
                                        )
                                    }
                                    IconButton(onClick = { showDeleteProtocolDialog = true }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.action_delete_protocol),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currentMeeting.protocol ?: stringResource(R.string.no_protocol),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (currentMeeting.protocol != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Confirmation Dialogs
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
