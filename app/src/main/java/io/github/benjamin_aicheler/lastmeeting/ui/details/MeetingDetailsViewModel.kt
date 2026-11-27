package io.github.benjamin_aicheler.lastmeeting.ui.details

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.benjamin_aicheler.lastmeeting.LastMeetingApp
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.db.MeetingEntity
import io.github.benjamin_aicheler.lastmeeting.repository.MeetingRepository
import io.github.benjamin_aicheler.lastmeeting.util.ShareUtil
import io.github.benjamin_aicheler.lastmeeting.worker.AiProcessingWorker
import io.github.benjamin_aicheler.lastmeeting.worker.AiTaskErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed class DetailsUiEvent {
    data class ShowToast(val message: String) : DetailsUiEvent()
    data object MeetingDeleted : DetailsUiEvent()
}

class MeetingDetailsViewModel(
    application: Application,
    val meetingId: Long,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : AndroidViewModel(application) {

    private val app = application as LastMeetingApp
    private val repository = app.meetingRepository
    private val workManager = WorkManager.getInstance(application)

    val meeting: StateFlow<MeetingEntity?> = repository.getMeetingById(meetingId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val transcribeWorkFlow = workManager.getWorkInfosByTagFlow(
        AiProcessingWorker.workTag(meetingId, AiProcessingWorker.TASK_TRANSCRIBE)
    )

    private val protocolizeWorkFlow = workManager.getWorkInfosByTagFlow(
        AiProcessingWorker.workTag(meetingId, AiProcessingWorker.TASK_PROTOCOLIZE)
    )

    val isTranscribing: StateFlow<Boolean> = combine(transcribeWorkFlow, meeting) { infos, current ->
        val name = current?.let {
            AiProcessingWorker.uniqueWorkName(meetingId, AiProcessingWorker.TASK_TRANSCRIBE, it.transcriptGeneration)
        }
        infos.any { !it.state.isFinished && name in it.tags }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val isProtocolizing: StateFlow<Boolean> = combine(protocolizeWorkFlow, meeting) { infos, current ->
        val name = current?.let {
            AiProcessingWorker.uniqueWorkName(meetingId, AiProcessingWorker.TASK_PROTOCOLIZE, it.protocolGeneration)
        }
        infos.any { !it.state.isFinished && name in it.tags }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val _eventFlow = MutableSharedFlow<DetailsUiEvent>()
    val eventFlow: SharedFlow<DetailsUiEvent> = _eventFlow.asSharedFlow()

    private val _pendingDeleteExit = MutableStateFlow(false)
    val pendingDeleteExit: StateFlow<Boolean> = _pendingDeleteExit.asStateFlow()

    fun consumePendingDeleteExit() {
        _pendingDeleteExit.value = false
    }

    private val playbackController = AudioPlaybackController.create(
        context = app,
        scope = viewModelScope,
        onPlaybackError = { message ->
            viewModelScope.launch {
                _eventFlow.emit(
                    DetailsUiEvent.ShowToast(
                        app.getString(R.string.msg_audio_play_error, message)
                    )
                )
            }
        },
        onAudioFocusDenied = {
            viewModelScope.launch {
                _eventFlow.emit(
                    DetailsUiEvent.ShowToast(app.getString(R.string.msg_audio_focus_denied))
                )
            }
        }
    )

    val isPlayingAudio: StateFlow<Boolean> = playbackController.isPlaying
    val isPreparingAudio: StateFlow<Boolean> = playbackController.isPreparing
    val audioPositionMs: StateFlow<Int> = playbackController.positionMs
    val audioDurationMs: StateFlow<Int> = playbackController.durationMs

    val showTranscriptionConfirmation: StateFlow<Boolean> =
        savedStateHandle.getStateFlow("confirm_transcript_replacement", false)

    private var requestingTranscription = false
    private var deletingMeeting = false

    init {
        observeWorkCompletion(
            workFlow = transcribeWorkFlow,
            taskType = AiProcessingWorker.TASK_TRANSCRIBE,
            successMessageRes = R.string.msg_transcript_success
        )
        observeWorkCompletion(
            workFlow = protocolizeWorkFlow,
            taskType = AiProcessingWorker.TASK_PROTOCOLIZE,
            successMessageRes = R.string.msg_protocol_success
        )
    }

    private fun observeWorkCompletion(
        workFlow: kotlinx.coroutines.flow.Flow<List<WorkInfo>>,
        taskType: String,
        successMessageRes: Int
    ) {
        val trackedRuns = WorkRunTracker()
        viewModelScope.launch {
            workFlow.collect { infos ->
                infos.forEach { info ->
                    if (trackedRuns.record(info.id, info.state.isFinished)) {
                        reportCompletion(info, taskType, successMessageRes)
                    }
                }
            }
        }
    }

    private suspend fun reportCompletion(info: WorkInfo, taskType: String, successMessageRes: Int) {
        val current = repository.getMeetingById(meetingId).first() ?: return
        val generation = if (taskType == AiProcessingWorker.TASK_TRANSCRIBE) {
            current.transcriptGeneration
        } else {
            current.protocolGeneration
        }
        if (AiProcessingWorker.uniqueWorkName(meetingId, taskType, generation) !in info.tags) return
        if (info.outputData.getBoolean(AiProcessingWorker.KEY_DISCARDED, false)) return
        when (info.state) {
            WorkInfo.State.SUCCEEDED -> {
                _eventFlow.emit(DetailsUiEvent.ShowToast(app.getString(successMessageRes)))
            }
            WorkInfo.State.FAILED -> {
                val errorMsg = info.outputData.getString(AiProcessingWorker.KEY_ERROR_CODE)
                    ?.let { AiTaskErrorCode.fromCode(it).localizedMessage(app) }
                    ?: info.outputData.getString(AiProcessingWorker.KEY_ERROR_MESSAGE)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.take(MeetingRepository.MAX_PERSISTED_ERROR_CHARS)
                    ?: app.getString(R.string.msg_ai_error_unknown)
                _eventFlow.emit(
                    DetailsUiEvent.ShowToast(app.getString(R.string.msg_ai_error, errorMsg))
                )
            }
            else -> Unit
        }
    }

    fun updateTitle(newTitle: String) {
        viewModelScope.launch {
            repository.updateTitle(meetingId, newTitle)
        }
    }

    fun toggleAudioPlayback() {
        val currentMeeting = meeting.value ?: return
        val audioFile = File(currentMeeting.audioFilePath)
        if (!audioFile.exists()) {
            viewModelScope.launch {
                _eventFlow.emit(DetailsUiEvent.ShowToast(app.getString(R.string.msg_audio_not_found)))
            }
            return
        }

        playbackController.togglePlayback(audioFile.absolutePath)
    }

    fun seekAudioTo(positionMs: Int) {
        playbackController.seekTo(positionMs)
    }

    private fun stopAudioPlayer() {
        playbackController.stop()
    }

    fun transcribe() {
        requestTranscription(replaceExisting = false)
    }

    fun confirmTranscription() {
        // Consume the decision before launching so a second tap cannot submit it again.
        if (!showTranscriptionConfirmation.value) return
        dismissTranscriptionConfirmation()
        requestTranscription(replaceExisting = true)
    }

    fun dismissTranscriptionConfirmation() {
        savedStateHandle["confirm_transcript_replacement"] = false
    }

    private fun requestTranscription(replaceExisting: Boolean) {
        if (deletingMeeting || requestingTranscription || isTranscribing.value || isProtocolizing.value) return
        requestingTranscription = true
        viewModelScope.launch {
            try {
                // Re-read storage: the screen may still display an older, empty transcript.
                val current = repository.getMeetingById(meetingId).first() ?: return@launch
                if (current.transcript != null && !replaceExisting) {
                    savedStateHandle["confirm_transcript_replacement"] = true
                    return@launch
                }
                val configRevision = app.settingsRepository.settingsFlow.first().providerConfigRevision
                if (deletingMeeting) return@launch
                AiProcessingWorker.enqueue(
                    app,
                    meetingId,
                    AiProcessingWorker.TASK_TRANSCRIBE,
                    current.transcriptGeneration,
                    configRevision
                )
            } finally {
                requestingTranscription = false
            }
        }
    }

    fun protocolize() {
        if (deletingMeeting) return
        viewModelScope.launch {
            val current = repository.getMeetingById(meetingId).first() ?: return@launch
            if (current.transcript.isNullOrBlank()) {
                _eventFlow.emit(DetailsUiEvent.ShowToast(app.getString(R.string.msg_transcribe_first)))
                return@launch
            }
            val configRevision = app.settingsRepository.settingsFlow.first().providerConfigRevision
            if (deletingMeeting) return@launch
            AiProcessingWorker.enqueue(
                app,
                meetingId,
                AiProcessingWorker.TASK_PROTOCOLIZE,
                current.protocolGeneration,
                configRevision
            )
        }
    }

    fun downloadAudio() {
        val currentMeeting = meeting.value ?: return
        val audioFile = File(currentMeeting.audioFilePath)
        viewModelScope.launch {
            val result = repository.exportAudio(currentMeeting.title, audioFile)
            result.onSuccess { path ->
                _eventFlow.emit(
                    DetailsUiEvent.ShowToast(app.getString(R.string.msg_exported_audio_path, path))
                )
            }.onFailure { error ->
                _eventFlow.emit(
                    DetailsUiEvent.ShowToast(
                        app.getString(R.string.msg_export_audio_failed, error.localizedMessage ?: "")
                    )
                )
            }
        }
    }

    fun exportPdf(typeLabel: String, textContent: String) {
        val currentMeeting = meeting.value ?: return
        viewModelScope.launch {
            val result = repository.exportPdf(currentMeeting.title, typeLabel, textContent)
            result.onSuccess { path ->
                _eventFlow.emit(
                    DetailsUiEvent.ShowToast(app.getString(R.string.msg_exported_pdf_path, path))
                )
            }.onFailure { error ->
                _eventFlow.emit(
                    DetailsUiEvent.ShowToast(
                        app.getString(R.string.msg_export_pdf_failed, error.localizedMessage ?: "")
                    )
                )
            }
        }
    }

    fun shareAudio() {
        val currentMeeting = meeting.value ?: return
        val file = File(currentMeeting.audioFilePath)
        if (file.exists()) {
            ShareUtil.shareFile(app, file, "audio/mp4", currentMeeting.title)
        } else {
            viewModelScope.launch {
                _eventFlow.emit(DetailsUiEvent.ShowToast(app.getString(R.string.msg_audio_not_found)))
            }
        }
    }

    fun shareText(text: String) {
        val currentMeeting = meeting.value ?: return
        ShareUtil.shareText(app, text, currentMeeting.title)
    }

    fun sharePdf(typeLabel: String, textContent: String) {
        val currentMeeting = meeting.value ?: return
        viewModelScope.launch {
            val result = repository.exportPdfToCache(currentMeeting.title, typeLabel, textContent)
            result.onSuccess { pdfFile ->
                ShareUtil.shareFile(app, pdfFile, "application/pdf", currentMeeting.title)
            }.onFailure { error ->
                _eventFlow.emit(
                    DetailsUiEvent.ShowToast(
                        app.getString(R.string.msg_export_pdf_failed, error.localizedMessage ?: "")
                    )
                )
            }
        }
    }

    fun deleteTranscript() {
        viewModelScope.launch {
            repository.deleteTranscript(meetingId)?.let { generation ->
                AiProcessingWorker.cancel(app, meetingId, AiProcessingWorker.TASK_TRANSCRIBE, generation)
            }
            _eventFlow.emit(DetailsUiEvent.ShowToast(app.getString(R.string.msg_transcript_deleted)))
        }
    }

    fun deleteProtocol() {
        viewModelScope.launch {
            repository.deleteProtocol(meetingId)?.let { generation ->
                AiProcessingWorker.cancel(app, meetingId, AiProcessingWorker.TASK_PROTOCOLIZE, generation)
            }
            _eventFlow.emit(DetailsUiEvent.ShowToast(app.getString(R.string.msg_protocol_deleted)))
        }
    }

    fun deleteMeeting() {
        if (deletingMeeting) return
        deletingMeeting = true
        viewModelScope.launch {
            var deleted = false
            try {
                stopAudioPlayer()
                val result = repository.deleteMeeting(meetingId)
                if (result.isSuccess) {
                    deleted = true
                    AiProcessingWorker.cancelAllForMeeting(app, meetingId)
                    _pendingDeleteExit.value = true
                    _eventFlow.emit(DetailsUiEvent.MeetingDeleted)
                } else {
                    _eventFlow.emit(DetailsUiEvent.ShowToast(app.getString(R.string.msg_delete_failed)))
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (!deleted) {
                    deletingMeeting = false
                }
            }
        }
    }

    override fun onCleared() {
        stopAudioPlayer()
        super.onCleared()
    }
}
