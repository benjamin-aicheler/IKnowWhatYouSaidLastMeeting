package com.example.lastmeeting.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.MainActivity
import com.example.lastmeeting.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    private val binder = LocalBinder()
    private var mediaRecorder: AudioRecorder? = null
    internal var recorderFactory: () -> AudioRecorder = { AndroidAudioRecorder(this) }
    private var currentOutputFile: File? = null
    private var recordingStartElapsedTime: Long = 0
    private var recordingStartWallTime: Long = 0
    /** Row inserted when the recording starts, so an interrupted recording keeps its meeting. */
    private var pendingMeetingRow: Deferred<Long?>? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedTimeSeconds = MutableStateFlow(0L)
    val elapsedTimeSeconds: StateFlow<Long> = _elapsedTimeSeconds.asStateFlow()

    private val _startupError = MutableStateFlow<Int?>(null)
    val startupError: StateFlow<Int?> = _startupError.asStateFlow()

    /** Clears the last startup failure once a client has reported it, so it is not replayed. */
    fun clearStartupError() {
        _startupError.value = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> requestStop()
        }
        return START_NOT_STICKY
    }

    fun startRecording(): File? {
        if (_isRecording.value) return currentOutputFile
        _startupError.value = null

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone permission is required")
            }
            // Satisfy the foreground deadline before any file or recorder operations.
            startForegroundServiceNotification()

            val recordingsDir = File(filesDir, "recordings")
            check(recordingsDir.isDirectory || recordingsDir.mkdirs()) { "Cannot create recordings directory" }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            // A failed retry or rapid stop/start must never overwrite another recording.
            val timestampFile = File(recordingsDir, "meeting_$timeStamp.m4a")
            val outputFile = if (timestampFile.createNewFile()) timestampFile else {
                File.createTempFile("meeting_${timeStamp}_", ".m4a", recordingsDir)
            }
            currentOutputFile = outputFile

            val recorder = recorderFactory()
            // Retain ownership before configuration or prepare/start can throw.
            mediaRecorder = recorder
            recorder.apply {
                configure(outputFile)
                prepare()
                start()
            }
            recordingStartElapsedTime = SystemClock.elapsedRealtime()
            recordingStartWallTime = System.currentTimeMillis()
            _isRecording.value = true

            // Persist the meeting right away: if the process dies mid-recording, the audio stays
            // reachable instead of lying on disk with nothing pointing at it.
            val startWallTime = recordingStartWallTime
            val app = application as? LastMeetingApp
            if (app != null) {
                pendingMeetingRow = app.applicationScope.async {
                    runCatching { app.meetingRepository.startMeeting(outputFile, startWallTime) }
                        .getOrNull()
                }
            }

            updateNotification()
            startTimer()

            return outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            cleanupRecorder()
            currentOutputFile?.delete()
            currentOutputFile = null
            _isRecording.value = false
            recordingStartElapsedTime = 0L
            recordingStartWallTime = 0L
            stopTimer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Retained state lets a late binding observe the failure, too.
            _startupError.value = if (e is SecurityException) {
                R.string.msg_recording_permission_required
            } else {
                R.string.msg_recording_start_failed
            }
            stopSelf()
            return null
        }
    }

    /** Stops the recording without blocking the caller; finalization runs in the service scope. */
    fun requestStop() {
        serviceScope.launch { stopRecording() }
    }

    suspend fun stopRecording(): File? {
        if (!_isRecording.value) {
            // An idle stop command (double tap, stale notification action) must not leave a
            // started service behind. onDestroy reaches this path as well, where both calls
            // are no-ops.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return currentOutputFile
        }

        val recordedFile = currentOutputFile
        val startTime = recordingStartWallTime
        val endTime = System.currentTimeMillis()
        currentOutputFile = null
        // Detach the recorder before leaving the main thread: a second stop while the file is
        // being finalized must not touch the same instance.
        val recorder = mediaRecorder
        mediaRecorder = null
        _isRecording.value = false
        stopTimer()

        // MediaRecorder.stop() finalizes the container and can block on long recordings.
        withContext(Dispatchers.IO) {
            try {
                recorder?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                releaseRecorder(recorder)
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        val app = application as? LastMeetingApp
        if (app != null) {
            val rowId = pendingMeetingRow?.let { deferred ->
                withTimeoutOrNull(MEETING_ROW_TIMEOUT_MS) { deferred.await() }
            }
            pendingMeetingRow = null

            val audioFile = recordedFile
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
                app.applicationScope.launch {
                    if (rowId != null) {
                        app.meetingRepository.finishMeeting(rowId, endTime)
                    } else {
                        // The insert at start failed or never ran; fall back to the old path.
                        app.meetingRepository.createMeeting(audioFile, startTime, endTime)
                    }
                }
            } else if (rowId != null) {
                app.applicationScope.launch { app.meetingRepository.discardMeeting(rowId) }
            }
        }

        return recordedFile
    }

    private fun cleanupRecorder() {
        releaseRecorder(mediaRecorder)
        mediaRecorder = null
    }

    private fun releaseRecorder(recorder: AudioRecorder?) {
        try {
            recorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive && _isRecording.value) {
                // The notification ticks itself via a chronometer; only the UI value is
                // refreshed here, so recording does not re-post a notification every second.
                _elapsedTimeSeconds.value = (SystemClock.elapsedRealtime() - recordingStartElapsedTime) / 1000
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _elapsedTimeSeconds.value = 0L
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_recording_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_recording_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(preparing: Boolean = false): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(if (preparing) R.string.recording_preparing else R.string.recording_in_progress))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (preparing) {
                    setShowWhen(false)
                } else {
                    // The system ticks the elapsed time itself, so the notification is posted once.
                    setUsesChronometer(true)
                    setWhen(recordingStartWallTime)
                }
            }
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.action_stop_recording),
                stopPendingIntent
            )
            .build()
    }

    private fun startForegroundServiceNotification() {
        val notification = buildNotification(preparing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        // Last chance to finalize the container. Normally the recording is already stopped and
        // this hits the cheap idle path; the bounded wait only matters when the service is torn
        // down mid-recording.
        runBlocking { withTimeoutOrNull(DESTROY_STOP_TIMEOUT_MS) { stopRecording() } }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val DESTROY_STOP_TIMEOUT_MS = 2_000L
        private const val MEETING_ROW_TIMEOUT_MS = 5_000L
        const val CHANNEL_ID = "recording_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_RECORDING = "com.example.lastmeeting.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.lastmeeting.action.STOP_RECORDING"
    }
}
