package com.example.lastmeeting.ui.main

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.R
import com.example.lastmeeting.data.db.MeetingEntity
import com.example.lastmeeting.service.RecordingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private var isRecordingCollectorJob: Job? = null
    private var elapsedCollectorJob: Job? = null
    private var errorCollectorJob: Job? = null
    private var bindingRequested = false

    private val _recordingError = MutableStateFlow<Int?>(null)
    val recordingError: StateFlow<Int?> = _recordingError.asStateFlow()

    fun clearRecordingError() {
        _recordingError.value = null
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            val srv = binder.getService()
            recordingService = srv

            isRecordingCollectorJob?.cancel()
            isRecordingCollectorJob = viewModelScope.launch {
                srv.isRecording.collect { rec ->
                    _isRecording.value = rec
                }
            }

            elapsedCollectorJob?.cancel()
            elapsedCollectorJob = viewModelScope.launch {
                srv.elapsedTimeSeconds.collect { elapsed ->
                    _elapsedSeconds.value = elapsed
                }
            }

            errorCollectorJob?.cancel()
            errorCollectorJob = viewModelScope.launch {
                _recordingError.value = srv.startupError.filterNotNull().first()
                // stopSelf cannot destroy a service held by BIND_AUTO_CREATE.
                // Release the binding even when foreground promotion itself failed.
                unbindRecordingService()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isRecordingCollectorJob?.cancel()
            elapsedCollectorJob?.cancel()
            errorCollectorJob?.cancel()
            recordingService = null
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
        if (bindingRequested) return
        val intent = Intent(getApplication(), RecordingService::class.java)
        bindingRequested = getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindRecordingService() {
        if (bindingRequested) {
            getApplication<Application>().unbindService(serviceConnection)
            bindingRequested = false
        }
        isRecordingCollectorJob?.cancel()
        elapsedCollectorJob?.cancel()
        errorCollectorJob?.cancel()
        recordingService = null
        _isRecording.value = false
        _elapsedSeconds.value = 0L
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
        _recordingError.value = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _recordingError.value = R.string.msg_recording_permission_required
            return
        }
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
        }
        try {
            bindRecordingService()
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            e.printStackTrace()
            _recordingError.value = if (e is SecurityException) {
                R.string.msg_recording_permission_required
            } else {
                R.string.msg_recording_start_failed
            }
            unbindRecordingService()
        }
    }

    private fun stopRecording() {
        val service = recordingService
        if (service != null) {
            service.stopRecording()
        } else {
            val context = getApplication<Application>()
            val intent = Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }
    }

    override fun onCleared() {
        unbindRecordingService()
        super.onCleared()
    }
}
