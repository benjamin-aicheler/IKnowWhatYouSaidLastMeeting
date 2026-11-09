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
import kotlinx.coroutines.CompletableDeferred
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

internal interface MeetingSessionStore {
    suspend fun startMeeting(audioFile: File, startTime: Long): Long
    suspend fun finishMeeting(id: Long, endTime: Long)
    suspend fun createMeeting(audioFile: File, startTime: Long, endTime: Long): Long
    suspend fun discardMeeting(id: Long)
}

class RecordingService : Service() {

    private class RecordingSession(
        val recorder: AudioRecorder,
        val outputFile: File,
        val startElapsedTime: Long,
        val startWallTime: Long,
        val pendingMeetingRow: Deferred<Long?>?
    )

    private val binder = LocalBinder()
    private val stateLock = Any()
    private var activeSession: RecordingSession? = null
    private var stoppingDeferred: CompletableDeferred<File?>? = null
    internal var recorderFactory: () -> AudioRecorder = { AndroidAudioRecorder(this) }
    internal var meetingStoreOverride: MeetingSessionStore? = null
    internal var persistenceScopeOverride: CoroutineScope? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isStopping = MutableStateFlow(false)
    val isStopping: StateFlow<Boolean> = _isStopping.asStateFlow()

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
        synchronized(stateLock) {
            if (_isStopping.value || stoppingDeferred != null) {
                return null
            }
            activeSession?.let { return it.outputFile }
        }
        _startupError.value = null

        var pendingFile: File? = null
        var pendingRecorder: AudioRecorder? = null
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
            pendingFile = outputFile

            val recorder = recorderFactory()
            // Retain ownership before configuration or prepare/start can throw.
            pendingRecorder = recorder
            recorder.apply {
                configure(outputFile)
                prepare()
                start()
            }
            val startElapsedTime = SystemClock.elapsedRealtime()
            val startWallTime = System.currentTimeMillis()

            // Persist the meeting right away: if the process dies mid-recording, the audio stays
            // reachable instead of lying on disk with nothing pointing at it.
            val pendingMeetingRow = resolveMeetingStore()?.let { (scope, store) ->
                scope.async {
                    runCatching { store.startMeeting(outputFile, startWallTime) }
                        .getOrNull()
                }
            }

            synchronized(stateLock) {
                activeSession = RecordingSession(
                    recorder = recorder,
                    outputFile = outputFile,
                    startElapsedTime = startElapsedTime,
                    startWallTime = startWallTime,
                    pendingMeetingRow = pendingMeetingRow
                )
                _isRecording.value = true
            }

            updateNotification(startWallTime)
            startTimer(startElapsedTime)

            return outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            releaseRecorder(pendingRecorder)
            pendingFile?.delete()
            synchronized(stateLock) {
                activeSession = null
                _isRecording.value = false
            }
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
        val session: RecordingSession?
        val deferred: CompletableDeferred<File?>?
        val existingStop: CompletableDeferred<File?>?

        synchronized(stateLock) {
            if (stoppingDeferred != null) {
                existingStop = stoppingDeferred
                session = null
                deferred = null
            } else {
                existingStop = null
                val current = activeSession
                if (current == null) {
                    // An idle stop command (double tap, stale notification action) must not leave a
                    // started service behind. onDestroy reaches this path as well, where both calls
                    // are no-ops.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    session = null
                    deferred = null
                } else {
                    session = current
                    activeSession = null
                    _isRecording.value = false
                    _isStopping.value = true
                    stopTimer()
                    deferred = CompletableDeferred()
                    stoppingDeferred = deferred
                }
            }
        }

        if (existingStop != null) {
            return existingStop.await()
        }
        if (session == null || deferred == null) {
            return null
        }

        val endTime = System.currentTimeMillis()

        // MediaRecorder.stop() finalizes the container and can block on long recordings.
        // Completing finalization inside Dispatchers.IO also ensures onDestroy()'s runBlocking
        // can await the in-flight stop without deadlocking on the main thread.
        return withContext(Dispatchers.IO) {
            try {
                try {
                    session.recorder.stop()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    releaseRecorder(session.recorder)
                }

                val storePair = resolveMeetingStore()
                if (storePair != null) {
                    val (scope, store) = storePair
                    val rowId = session.pendingMeetingRow?.let { pending ->
                        withTimeoutOrNull(MEETING_ROW_TIMEOUT_MS) { pending.await() }
                    }

                    val audioFile = session.outputFile
                    if (audioFile.exists() && audioFile.length() > 0L) {
                        scope.launch {
                            if (rowId != null) {
                                store.finishMeeting(rowId, endTime)
                            } else {
                                // The insert at start failed or never ran; fall back to the old path.
                                store.createMeeting(audioFile, session.startWallTime, endTime)
                            }
                        }
                    } else if (rowId != null) {
                        scope.launch { store.discardMeeting(rowId) }
                    }
                }

                session.outputFile
            } finally {
                synchronized(stateLock) {
                    if (stoppingDeferred === deferred) {
                        stoppingDeferred = null
                        _isStopping.value = false
                    }
                    if (activeSession == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                deferred.complete(session.outputFile)
            }
        }
    }

    private fun resolveMeetingStore(): Pair<CoroutineScope, MeetingSessionStore>? {
        val storeOverride = meetingStoreOverride
        if (storeOverride != null) {
            return (persistenceScopeOverride ?: serviceScope) to storeOverride
        }
        val app = application as? LastMeetingApp ?: return null
        val repo = app.meetingRepository
        return app.applicationScope to object : MeetingSessionStore {
            override suspend fun startMeeting(audioFile: File, startTime: Long): Long =
                repo.startMeeting(audioFile, startTime)

            override suspend fun finishMeeting(id: Long, endTime: Long) =
                repo.finishMeeting(id, endTime)

            override suspend fun createMeeting(audioFile: File, startTime: Long, endTime: Long): Long =
                repo.createMeeting(audioFile, startTime, endTime)

            override suspend fun discardMeeting(id: Long) =
                repo.discardMeeting(id)
        }
    }

    private fun releaseRecorder(recorder: AudioRecorder?) {
        try {
            recorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startTimer(startElapsedTime: Long) {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive && _isRecording.value) {
                // The notification ticks itself via a chronometer; only the UI value is
                // refreshed here, so recording does not re-post a notification every second.
                _elapsedTimeSeconds.value = (SystemClock.elapsedRealtime() - startElapsedTime) / 1000
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

    private fun buildNotification(preparing: Boolean = false, startWallTime: Long = 0L): Notification {
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
                    setWhen(startWallTime)
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

    private fun updateNotification(startWallTime: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(startWallTime = startWallTime))
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
