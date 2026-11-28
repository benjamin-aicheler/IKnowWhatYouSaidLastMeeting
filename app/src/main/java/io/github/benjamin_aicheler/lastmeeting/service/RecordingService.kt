package io.github.benjamin_aicheler.lastmeeting.service

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.benjamin_aicheler.lastmeeting.LastMeetingApp
import io.github.benjamin_aicheler.lastmeeting.MainActivity
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.repository.MeetingRepository
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal interface MeetingSessionStore {
    fun createRecordingFile(recordingsDir: File): File =
        RecordingService.createUniqueRecordingFile(recordingsDir).also(::registerLiveRecording)
    fun registerLiveRecording(audioFile: File) {}
    fun unregisterLiveRecording(audioFile: File) {}
    suspend fun startMeeting(audioFile: File, startTime: Long): Long
    suspend fun finishMeeting(id: Long, endTime: Long, isDamaged: Boolean = false)
    suspend fun createMeeting(audioFile: File, startTime: Long, endTime: Long, isDamaged: Boolean = false): Long
    suspend fun discardMeeting(id: Long)
}

internal fun MeetingRepository.asSessionStore(): MeetingSessionStore = object : MeetingSessionStore {
    override fun createRecordingFile(recordingsDir: File): File =
        this@asSessionStore.createRecordingFile(recordingsDir)

    override fun registerLiveRecording(audioFile: File) =
        this@asSessionStore.registerLiveRecording(audioFile)

    override fun unregisterLiveRecording(audioFile: File) =
        this@asSessionStore.unregisterLiveRecording(audioFile)

    override suspend fun startMeeting(audioFile: File, startTime: Long): Long =
        this@asSessionStore.startMeeting(audioFile, startTime)

    override suspend fun finishMeeting(id: Long, endTime: Long, isDamaged: Boolean) =
        this@asSessionStore.finishMeeting(id, endTime, isDamaged)

    override suspend fun createMeeting(audioFile: File, startTime: Long, endTime: Long, isDamaged: Boolean): Long =
        this@asSessionStore.createMeeting(audioFile, startTime, endTime, isDamaged)

    override suspend fun discardMeeting(id: Long) =
        this@asSessionStore.discardMeeting(id)
}

class RecordingService : Service() {

    private sealed interface RowInsertResult {
        data class Inserted(val rowId: Long) : RowInsertResult
        data object Failed : RowInsertResult
    }

    private class RecordingSession(
        val recorder: AudioRecorder,
        val outputFile: File,
        @Volatile var startElapsedTime: Long = 0L,
        @Volatile var startWallTime: Long = 0L,
        val storePair: Pair<CoroutineScope, MeetingSessionStore>? = null,
        @Volatile var pendingMeetingRow: Deferred<RowInsertResult>? = null
    )

    private val binder = LocalBinder()
    private val stateLock = Any()
    private var activeSession: RecordingSession? = null
    private var stoppingDeferred: CompletableDeferred<File?>? = null
    internal var recorderFactory: () -> AudioRecorder = { AndroidAudioRecorder(this) }
    internal var meetingStoreOverride: MeetingSessionStore? = null
    internal var persistenceScopeOverride: CoroutineScope? = null
    internal var destroyStopTimeoutMs: Long = DESTROY_STOP_TIMEOUT_MS

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isStopping = MutableStateFlow(false)
    val isStopping: StateFlow<Boolean> = _isStopping.asStateFlow()

    private val _elapsedTimeSeconds = MutableStateFlow(0L)
    val elapsedTimeSeconds: StateFlow<Long> = _elapsedTimeSeconds.asStateFlow()

    private val _startupError = MutableStateFlow<Int?>(null)
    val startupError: StateFlow<Int?> = _startupError.asStateFlow()

    private val _runtimeError = MutableStateFlow<Int?>(null)
    val runtimeError: StateFlow<Int?> = _runtimeError.asStateFlow()

    /** Clears the last startup failure once a client has reported it, so it is not replayed. */
    fun clearStartupError() {
        _startupError.value = null
    }

    /** Clears the last mid-recording runtime failure once a client has reported it. */
    fun clearRuntimeError() {
        _runtimeError.value = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val fallbackPersistenceScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
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
        _runtimeError.value = null

        var pendingFile: File? = null
        var pendingRecorder: AudioRecorder? = null
        val storePair = resolveMeetingStore()
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone permission is required")
            }
            // Satisfy the foreground deadline before any file or recorder operations.
            startForegroundServiceNotification()

            val recordingsDir = File(filesDir, "recordings")
            val outputFile = storePair?.second?.createRecordingFile(recordingsDir)
                ?: createUniqueRecordingFile(recordingsDir)
            pendingFile = outputFile

            val recorder = recorderFactory()
            // Retain ownership before configuration or prepare/start can throw.
            pendingRecorder = recorder
            val session = RecordingSession(
                recorder = recorder,
                outputFile = outputFile,
                storePair = storePair
            )
            recorder.setOnErrorListener { what, extra ->
                handleRecorderError(session, what, extra)
            }
            recorder.apply {
                configure(outputFile)
                prepare()
                start()
            }
            val startElapsedTime = SystemClock.elapsedRealtime()
            val startWallTime = System.currentTimeMillis()
            session.startElapsedTime = startElapsedTime
            session.startWallTime = startWallTime

            // Persist the meeting right away: if the process dies mid-recording, the audio stays
            // reachable instead of lying on disk with nothing pointing at it.
            val pendingMeetingRow = storePair?.let { (scope, store) ->
                scope.async {
                    try {
                        RowInsertResult.Inserted(store.startMeeting(outputFile, startWallTime))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to insert initial meeting row", e)
                        RowInsertResult.Failed
                    }
                }
            }
            session.pendingMeetingRow = pendingMeetingRow

            synchronized(stateLock) {
                activeSession = session
                _isRecording.value = true
            }

            updateNotification(startWallTime)
            startTimer(startElapsedTime)

            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseRecorder(pendingRecorder)
            pendingFile?.delete()
            pendingFile?.let { file -> storePair?.second?.unregisterLiveRecording(file) }
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

    /** Stops the recording without blocking the caller; finalization runs in the persistence scope. */
    fun requestStop() {
        beginStopSession(expectedSession = null, forceDamaged = false)
    }

    suspend fun stopRecording(): File? {
        return beginStopSession(expectedSession = null, forceDamaged = false)?.await()
    }

    private fun handleRecorderError(session: RecordingSession, what: Int, extra: Int) {
        Log.e(TAG, "MediaRecorder runtime error: what=$what extra=$extra")
        beginStopSession(expectedSession = session, forceDamaged = true)
    }

    private fun beginStopSession(
        expectedSession: RecordingSession?,
        forceDamaged: Boolean
    ): CompletableDeferred<File?>? {
        val session: RecordingSession?
        val deferred: CompletableDeferred<File?>?
        val existingStop: CompletableDeferred<File?>?

        synchronized(stateLock) {
            if (expectedSession != null && activeSession !== expectedSession) {
                return null
            }
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
            return existingStop
        }
        if (session == null || deferred == null) {
            return null
        }

        val endTime = System.currentTimeMillis()
        val finalizationScope = session.storePair?.first ?: resolvePersistenceScope()

        // Hand off stop/release and row persistence to the application IO scope before any
        // suspension point so an onDestroy() timeout cannot abandon finalization or unpinning.
        finalizationScope.launch {
            try {
                var stopFailed = forceDamaged
                try {
                    session.recorder.stop()
                } catch (e: Exception) {
                    stopFailed = true
                    Log.e(TAG, "Failed to stop MediaRecorder cleanly", e)
                } finally {
                    releaseRecorder(session.recorder)
                }

                val store = session.storePair?.second
                val audioFile = session.outputFile
                if (store != null) {
                    try {
                        val insertResult = session.pendingMeetingRow?.await()
                        if (audioFile.exists() && audioFile.length() > 0L) {
                            when (insertResult) {
                                is RowInsertResult.Inserted ->
                                    store.finishMeeting(insertResult.rowId, endTime, isDamaged = stopFailed)
                                RowInsertResult.Failed, null ->
                                    store.createMeeting(
                                        audioFile,
                                        session.startWallTime,
                                        endTime,
                                        isDamaged = stopFailed
                                    )
                            }
                        } else {
                            if (insertResult is RowInsertResult.Inserted) {
                                store.discardMeeting(insertResult.rowId)
                            }
                            if (audioFile.exists() && audioFile.length() == 0L) {
                                audioFile.delete()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to finalize meeting row", e)
                    } finally {
                        store.unregisterLiveRecording(audioFile)
                    }
                }

                if (forceDamaged) {
                    _runtimeError.value = R.string.msg_recording_interrupted
                    postInterruptedNotification()
                }
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

        return deferred
    }

    private fun resolvePersistenceScope(): CoroutineScope {
        persistenceScopeOverride?.let { return it }
        val app = application as? LastMeetingApp
        return app?.applicationScope ?: fallbackPersistenceScope
    }

    private fun resolveMeetingStore(): Pair<CoroutineScope, MeetingSessionStore>? {
        val storeOverride = meetingStoreOverride
        if (storeOverride != null) {
            return resolvePersistenceScope() to storeOverride
        }
        val app = application as? LastMeetingApp ?: return null
        return resolvePersistenceScope() to app.meetingRepository.asSessionStore()
    }

    private fun releaseRecorder(recorder: AudioRecorder?) {
        try {
            recorder?.setOnErrorListener(null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear MediaRecorder error listener", e)
        }
        try {
            recorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MediaRecorder", e)
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

    private fun postInterruptedNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.notification_channel_recording_alert_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_recording_alert_desc)
            }
            manager.createNotificationChannel(channel)

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                ALERT_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_recording_interrupted_title))
                .setContentText(getString(R.string.msg_recording_interrupted))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        }.onFailure {
            Log.w(TAG, "Failed to post interrupted recording notification", it)
        }
    }

    override fun onDestroy() {
        // Last chance to finalize the container. Normally the recording is already stopped and
        // this hits the cheap idle path; the bounded wait only matters when the service is torn
        // down mid-recording. Any in-flight persistence continues on the application IO scope.
        runBlocking { withTimeoutOrNull(destroyStopTimeoutMs) { stopRecording() } }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val DESTROY_STOP_TIMEOUT_MS = 2_000L
        const val CHANNEL_ID = "recording_service_channel"
        const val ALERT_CHANNEL_ID = "recording_alert_channel"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002
        const val ACTION_START_RECORDING = "io.github.benjamin_aicheler.lastmeeting.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "io.github.benjamin_aicheler.lastmeeting.action.STOP_RECORDING"

        internal fun createUniqueRecordingFile(recordingsDir: File): File {
            check(recordingsDir.isDirectory || recordingsDir.mkdirs()) { "Cannot create recordings directory" }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            // A failed retry or rapid stop/start must never overwrite another recording.
            val timestampFile = File(recordingsDir, "meeting_$timeStamp.m4a")
            return if (timestampFile.createNewFile()) {
                timestampFile
            } else {
                File.createTempFile("meeting_${timeStamp}_", ".m4a", recordingsDir)
            }
        }
    }
}
