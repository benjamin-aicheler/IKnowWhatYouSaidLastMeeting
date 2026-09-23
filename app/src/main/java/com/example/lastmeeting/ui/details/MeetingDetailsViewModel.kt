package com.example.lastmeeting.ui.details

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.data.db.MeetingEntity
import com.example.lastmeeting.util.ShareUtil
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class DetailsUiEvent {
    data class ShowToast(val message: String) : DetailsUiEvent()
    object MeetingDeleted : DetailsUiEvent()
}

class MeetingDetailsViewModel(
    application: Application,
    val meetingId: Long
) : AndroidViewModel(application) {

    private val app = application as LastMeetingApp
    private val repository = app.meetingRepository

    val meeting: StateFlow<MeetingEntity?> = repository.getMeetingById(meetingId)
        .let { flow ->
            val stateFlow = MutableStateFlow<MeetingEntity?>(null)
            viewModelScope.launch {
                flow.collect { stateFlow.value = it }
            }
            stateFlow.asStateFlow()
        }

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _isProtocolizing = MutableStateFlow(false)
    val isProtocolizing: StateFlow<Boolean> = _isProtocolizing.asStateFlow()

    private val _isPlayingAudio = MutableStateFlow(false)
    val isPlayingAudio: StateFlow<Boolean> = _isPlayingAudio.asStateFlow()

    private val _eventFlow = MutableSharedFlow<DetailsUiEvent>()
    val eventFlow: SharedFlow<DetailsUiEvent> = _eventFlow.asSharedFlow()

    private var mediaPlayer: MediaPlayer? = null

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
                _eventFlow.emit(DetailsUiEvent.ShowToast("Audio file not found."))
            }
            return
        }

        if (_isPlayingAudio.value) {
            pauseAudio()
        } else {
            playAudio(audioFile)
        }
    }

    private fun playAudio(audioFile: File) {
        try {
            stopAudioPlayer()
            val player = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    _isPlayingAudio.value = false
                }
                start()
            }
            mediaPlayer = player
            _isPlayingAudio.value = true
        } catch (e: Exception) {
            e.printStackTrace()
            viewModelScope.launch {
                _eventFlow.emit(DetailsUiEvent.ShowToast("Failed to play audio: ${e.localizedMessage}"))
            }
        }
    }

    private fun pauseAudio() {
        try {
            mediaPlayer?.pause()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _isPlayingAudio.value = false
    }

    private fun stopAudioPlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null
        _isPlayingAudio.value = false
    }

    fun transcribe() {
        viewModelScope.launch {
            _isTranscribing.value = true
            val result = repository.transcribeMeeting(meetingId)
            _isTranscribing.value = false
            result.onSuccess {
                _eventFlow.emit(DetailsUiEvent.ShowToast("Transcript generated successfully."))
            }.onFailure { error ->
                _eventFlow.emit(DetailsUiEvent.ShowToast("Transcription failed: ${error.localizedMessage}"))
            }
        }
    }

    fun protocolize() {
        viewModelScope.launch {
            _isProtocolizing.value = true
            val result = repository.protocolizeMeeting(meetingId)
            _isProtocolizing.value = false
            result.onSuccess {
                _eventFlow.emit(DetailsUiEvent.ShowToast("Protocol generated successfully."))
            }.onFailure { error ->
                _eventFlow.emit(DetailsUiEvent.ShowToast("Protocol creation failed: ${error.localizedMessage}"))
            }
        }
    }

    fun exportPdf(typeLabel: String, textContent: String) {
        val currentMeeting = meeting.value ?: return
        val result = repository.exportPdf(currentMeeting.title, typeLabel, textContent)
        viewModelScope.launch {
            result.onSuccess { path ->
                _eventFlow.emit(DetailsUiEvent.ShowToast("PDF exported to $path"))
            }.onFailure { error ->
                _eventFlow.emit(DetailsUiEvent.ShowToast("PDF export failed: ${error.localizedMessage}"))
            }
        }
    }

    fun shareAudio() {
        val currentMeeting = meeting.value ?: return
        val file = File(currentMeeting.audioFilePath)
        if (file.exists()) {
            ShareUtil.shareFile(getApplication(), file, "audio/m4a", currentMeeting.title)
        }
    }

    fun shareText(text: String) {
        val currentMeeting = meeting.value ?: return
        ShareUtil.shareText(getApplication(), text, currentMeeting.title)
    }

    fun deleteTranscript() {
        viewModelScope.launch {
            repository.deleteTranscript(meetingId)
            _eventFlow.emit(DetailsUiEvent.ShowToast("Transcript deleted."))
        }
    }

    fun deleteProtocol() {
        viewModelScope.launch {
            repository.deleteProtocol(meetingId)
            _eventFlow.emit(DetailsUiEvent.ShowToast("Protocol deleted."))
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
