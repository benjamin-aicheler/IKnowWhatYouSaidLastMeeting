package com.example.lastmeeting.ui.details

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.R
import com.example.lastmeeting.data.db.MeetingEntity
import com.example.lastmeeting.util.ShareUtil
import com.example.lastmeeting.worker.AiProcessingWorker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

sealed class DetailsUiEvent {
    data class ShowToast(val message: String) : DetailsUiEvent()
    data object MeetingDeleted : DetailsUiEvent()
}

class MeetingDetailsViewModel(
    application: Application,
    val meetingId: Long
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

    private val transcribeWorkFlow = workManager.getWorkInfosForUniqueWorkFlow(
        AiProcessingWorker.uniqueWorkName(meetingId, AiProcessingWorker.TASK_TRANSCRIBE)
    )

    private val protocolizeWorkFlow = workManager.getWorkInfosForUniqueWorkFlow(
        AiProcessingWorker.uniqueWorkName(meetingId, AiProcessingWorker.TASK_PROTOCOLIZE)
    )

    val isTranscribing: StateFlow<Boolean> = transcribeWorkFlow
        .map { infos -> infos.any { !it.state.isFinished } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val isProtocolizing: StateFlow<Boolean> = protocolizeWorkFlow
        .map { infos -> infos.any { !it.state.isFinished } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val _isPlayingAudio = MutableStateFlow(false)
    val isPlayingAudio: StateFlow<Boolean> = _isPlayingAudio.asStateFlow()

    private val _eventFlow = MutableSharedFlow<DetailsUiEvent>()
    val eventFlow: SharedFlow<DetailsUiEvent> = _eventFlow.asSharedFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var isAudioPaused: Boolean = false

    private val observedActiveWorkIds = mutableSetOf<UUID>()

    init {
        observeWorkCompletion(
            workFlow = transcribeWorkFlow,
            successMessageRes = R.string.msg_transcript_success
        )
        observeWorkCompletion(
            workFlow = protocolizeWorkFlow,
            successMessageRes = R.string.msg_protocol_success
        )
    }

    private fun observeWorkCompletion(
        workFlow: kotlinx.coroutines.flow.Flow<List<WorkInfo>>,
        successMessageRes: Int
    ) {
        viewModelScope.launch {
            workFlow.collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                if (!info.state.isFinished) {
                    observedActiveWorkIds.add(info.id)
                } else if (observedActiveWorkIds.remove(info.id)) {
                    when (info.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            _eventFlow.emit(DetailsUiEvent.ShowToast(app.getString(successMessageRes)))
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMsg = info.outputData.getString(AiProcessingWorker.KEY_ERROR_MESSAGE)
                                ?: "Unknown error"
                            _eventFlow.emit(
                                DetailsUiEvent.ShowToast(app.getString(R.string.msg_ai_error, errorMsg))
                            )
                        }
                        else -> Unit
                    }
                }
            }
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

        if (_isPlayingAudio.value) {
            pauseAudio()
        } else {
            playOrResumeAudio(audioFile)
        }
    }

    private fun playOrResumeAudio(audioFile: File) {
        try {
            val existingPlayer = mediaPlayer
            if (existingPlayer != null && isAudioPaused) {
                existingPlayer.start()
                isAudioPaused = false
                _isPlayingAudio.value = true
                return
            }

            stopAudioPlayer()
            val player = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopAudioPlayer()
                }
                start()
            }
            mediaPlayer = player
            isAudioPaused = false
            _isPlayingAudio.value = true
        } catch (e: Exception) {
            e.printStackTrace()
            stopAudioPlayer()
            viewModelScope.launch {
                _eventFlow.emit(
                    DetailsUiEvent.ShowToast(
                        app.getString(R.string.msg_audio_play_error, e.localizedMessage ?: "")
                    )
                )
            }
        }
    }

    private fun pauseAudio() {
        try {
            mediaPlayer?.pause()
            isAudioPaused = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _isPlayingAudio.value = false
    }

    private fun stopAudioPlayer() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        isAudioPaused = false
        _isPlayingAudio.value = false
    }

    fun transcribe() {
        AiProcessingWorker.enqueue(
            context = app,
            meetingId = meetingId,
            taskType = AiProcessingWorker.TASK_TRANSCRIBE
        )
    }

    fun protocolize() {
        AiProcessingWorker.enqueue(
            context = app,
            meetingId = meetingId,
            taskType = AiProcessingWorker.TASK_PROTOCOLIZE
        )
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
            repository.deleteTranscript(meetingId)
            _eventFlow.emit(DetailsUiEvent.ShowToast(app.getString(R.string.msg_transcript_deleted)))
        }
    }

    fun deleteProtocol() {
        viewModelScope.launch {
            repository.deleteProtocol(meetingId)
            _eventFlow.emit(DetailsUiEvent.ShowToast(app.getString(R.string.msg_protocol_deleted)))
        }
    }

    fun deleteMeeting() {
        viewModelScope.launch {
            stopAudioPlayer()
            repository.deleteMeeting(meetingId)
            _eventFlow.emit(DetailsUiEvent.MeetingDeleted)
        }
    }

    override fun onCleared() {
        stopAudioPlayer()
        super.onCleared()
    }
}
