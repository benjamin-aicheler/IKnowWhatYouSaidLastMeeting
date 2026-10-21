package com.example.lastmeeting.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.MainActivity
import com.example.lastmeeting.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    private val binder = LocalBinder()
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var recordingStartElapsedTime: Long = 0
    private var recordingStartWallTime: Long = 0

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedTimeSeconds = MutableStateFlow(0L)
    val elapsedTimeSeconds: StateFlow<Long> = _elapsedTimeSeconds.asStateFlow()

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
            ACTION_STOP_RECORDING -> stopRecording()
        }
        return START_NOT_STICKY
    }

    fun startRecording(): File? {
        if (_isRecording.value) return currentOutputFile

        val recordingsDir = File(filesDir, "recordings")
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputFile = File(recordingsDir, "meeting_$timeStamp.m4a")
        currentOutputFile = outputFile

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(24000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            recordingStartElapsedTime = SystemClock.elapsedRealtime()
            recordingStartWallTime = System.currentTimeMillis()
            _isRecording.value = true

            startForegroundServiceNotification()
            startTimer()

            return outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            cleanupRecorder()
            _isRecording.value = false
            return null
        }
    }

    fun stopRecording(): File? {
        if (!_isRecording.value) return currentOutputFile

        val recordedFile = currentOutputFile
        val startTime = recordingStartWallTime
        val endTime = System.currentTimeMillis()
        currentOutputFile = null

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
            _isRecording.value = false
            stopTimer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0L) {
            val app = application as? LastMeetingApp
            app?.applicationScope?.launch {
                app.meetingRepository.createMeeting(
                    audioFile = recordedFile,
                    startTime = startTime,
                    endTime = endTime
                )
            }
        }

        return recordedFile
    }

    private fun cleanupRecorder() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaRecorder = null
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive && _isRecording.value) {
                val elapsed = (SystemClock.elapsedRealtime() - recordingStartElapsedTime) / 1000
                _elapsedTimeSeconds.value = elapsed
                updateNotification(elapsed)
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

    private fun buildNotification(elapsedSeconds: Long): Notification {
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

        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_in_progress))
            .setContentText(timeString)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.action_stop_recording),
                stopPendingIntent
            )
            .build()
    }

    private fun startForegroundServiceNotification() {
        val notification = buildNotification(0L)
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

    private fun updateNotification(elapsedSeconds: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(elapsedSeconds))
    }

    override fun onDestroy() {
        stopRecording()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "recording_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_RECORDING = "com.example.lastmeeting.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.lastmeeting.action.STOP_RECORDING"
    }
}
