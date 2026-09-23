package com.example.lastmeeting.ui.main

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.data.db.MeetingEntity
import com.example.lastmeeting.service.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class MainViewMode {
    LIST,
    CALENDAR
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LastMeetingApp
    private val repository = app.meetingRepository

    val meetings: StateFlow<List<MeetingEntity>> = repository.allMeetings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _viewMode = MutableStateFlow(MainViewMode.LIST)
    val viewMode: StateFlow<MainViewMode> = _viewMode.asStateFlow()

    private var recordingService: RecordingService? = null
    private val _isRecordingServiceBound = MutableStateFlow(false)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private var recordingStartTime: Long = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            val srv = binder.getService()
            recordingService = srv
            _isRecordingServiceBound.value = true

            viewModelScope.launch {
                srv.isRecording.collect { rec ->
                    _isRecording.value = rec
                }
            }
            viewModelScope.launch {
                srv.elapsedTimeSeconds.collect { elapsed ->
                    _elapsedSeconds.value = elapsed
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            _isRecordingServiceBound.value = false
            _isRecording.value = false
        }
    }

    init {
        bindRecordingService()
    }

    fun setViewMode(mode: MainViewMode) {
        _viewMode.value = mode
    }

    private fun bindRecordingService() {
        val intent = Intent(getApplication(), RecordingService::class.java)
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
        }
        recordingStartTime = System.currentTimeMillis()
        context.startService(intent)
        recordingService?.startRecording()
    }

    private fun stopRecording() {
        val context = getApplication<Application>()
        val endTime = System.currentTimeMillis()
        val recordedFile = recordingService?.stopRecording()

        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
        context.stopService(intent)

        if (recordedFile != null && recordedFile.exists()) {
            viewModelScope.launch {
                repository.createMeeting(recordedFile, recordingStartTime, endTime)
            }
        }
    }

    override fun onCleared() {
        if (_isRecordingServiceBound.value) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        super.onCleared()
    }
}
