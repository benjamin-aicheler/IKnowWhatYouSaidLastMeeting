package io.github.benjamin_aicheler.lastmeeting.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.benjamin_aicheler.lastmeeting.LastMeetingApp
import io.github.benjamin_aicheler.lastmeeting.MainActivity
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.repository.AiGenerationOutcome
import java.util.concurrent.TimeUnit

class AiProcessingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val meetingId = inputData.getLong(KEY_MEETING_ID, -1L)
        val taskType = inputData.getString(KEY_TASK_TYPE) ?: return Result.failure()
        if (meetingId <= 0L) return Result.failure()
        // Work persisted by an older app version has no deletion version; never rebase it.
        val generation = inputData.getLong(KEY_GENERATION, -1L)
        if (generation < 0L) return Result.success(workDataOf(KEY_DISCARDED to true))

        val app = applicationContext as? LastMeetingApp
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Application context unavailable."))

        val repository = app.meetingRepository

        // Uploading a full recording and generating the response can outlive the ~10 minutes a
        // regular worker gets before WorkManager cancels it.
        promoteToForeground(taskType)

        if (taskType != TASK_TRANSCRIBE && taskType != TASK_PROTOCOLIZE) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Unknown AI task type."))
        }
        val isTranscriptTask = taskType == TASK_TRANSCRIBE
        if (runAttemptCount == 0) {
            repository.clearTaskError(meetingId, isTranscriptTask, generation)
        }

        val apiResult = if (isTranscriptTask) {
            repository.transcribeMeeting(meetingId, generation)
        } else {
            repository.protocolizeMeeting(meetingId, generation)
        }

        return apiResult.fold(
            onSuccess = { outcome ->
                if (outcome is AiGenerationOutcome.Stored) {
                    postCompletionNotification(meetingId, taskType, success = true)
                }
                Result.success(workDataOf(KEY_DISCARDED to (outcome == AiGenerationOutcome.Discarded)))
            },
            onFailure = { error ->
                if (AiRetryPolicy.isRetryable(error, runAttemptCount)) {
                    Result.retry()
                } else {
                    val errorMessage = error.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "AI processing failed."
                    val recorded = repository.recordTaskError(
                        id = meetingId,
                        isTranscriptTask = isTranscriptTask,
                        generation = generation,
                        errorMessage = errorMessage
                    )
                    if (recorded) {
                        postCompletionNotification(meetingId, taskType, success = false)
                    }
                    Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMessage))
                }
            }
        )
    }

    /**
     * Promotion is refused while the app is in the background (Android 12+) or for the dataSync
     * type (Android 15). A refusal only downgrades the job to regular work: running without the
     * exemption is better than failing a job that might still finish in time.
     */
    private suspend fun promoteToForeground(taskType: String) {
        try {
            setForeground(createForegroundInfo(taskType))
        } catch (e: Exception) {
            Log.w("AiProcessingWorker", "Foreground promotion refused", e)
        }
    }

    private fun createForegroundInfo(taskType: String): ForegroundInfo {
        val context = applicationContext
        ensureNotificationChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.ai_processing_notification_title))
            .setContentText(
                context.getString(
                    if (taskType == TASK_TRANSCRIBE) {
                        R.string.ai_processing_transcribing
                    } else {
                        R.string.ai_processing_protocolizing
                    }
                )
            )
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_ai_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_ai_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun postCompletionNotification(meetingId: Long, taskType: String, success: Boolean) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                context.getString(R.string.notification_channel_ai_completion_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_ai_completion_desc)
            }
            manager.createNotificationChannel(channel)

            val titleRes = when {
                success && taskType == TASK_TRANSCRIBE -> R.string.ai_notification_transcript_ready
                success -> R.string.ai_notification_protocol_ready
                taskType == TASK_TRANSCRIBE -> R.string.ai_notification_transcript_failed
                else -> R.string.ai_notification_protocol_failed
            }
            val requestCode = COMPLETION_NOTIFICATION_BASE_ID +
                ((meetingId * 2L + if (taskType == TASK_TRANSCRIBE) 0L else 1L).toInt() and 0x7FFF)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
                .setContentTitle(context.getString(titleRes))
                .setSmallIcon(
                    if (success) android.R.drawable.ic_dialog_info else android.R.drawable.stat_notify_error
                )
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(requestCode, notification)
        }.onFailure {
            Log.w("AiProcessingWorker", "Failed to post completion notification", it)
        }
    }

    companion object {
        const val KEY_MEETING_ID = "meeting_id"
        const val KEY_TASK_TYPE = "task_type"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_GENERATION = "generation"
        const val KEY_DISCARDED = "discarded"

        const val TASK_TRANSCRIBE = "TRANSCRIBE"
        const val TASK_PROTOCOLIZE = "PROTOCOLIZE"

        private const val CHANNEL_ID = "ai_processing_channel"
        private const val COMPLETION_CHANNEL_ID = "ai_completion_channel"
        private const val NOTIFICATION_ID = 2001
        private const val COMPLETION_NOTIFICATION_BASE_ID = 3000
        private const val BACKOFF_SECONDS = 30L

        fun workTag(meetingId: Long, taskType: String): String =
            "ai_processing_${meetingId}_$taskType"

        fun uniqueWorkName(meetingId: Long, taskType: String, generation: Long): String =
            "${workTag(meetingId, taskType)}_$generation"

        fun cancel(context: Context, meetingId: Long, taskType: String, generation: Long): Operation {
            val workManager = WorkManager.getInstance(context)
            // Also stop legacy work without a generation, without touching newer requests.
            workManager.cancelUniqueWork(workTag(meetingId, taskType))
            return workManager.cancelUniqueWork(uniqueWorkName(meetingId, taskType, generation))
        }

        fun enqueue(context: Context, meetingId: Long, taskType: String, generation: Long): Operation {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AiProcessingWorker>()
                .addTag(workTag(meetingId, taskType))
                .addTag(uniqueWorkName(meetingId, taskType, generation))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_MEETING_ID to meetingId,
                        KEY_TASK_TYPE to taskType,
                        KEY_GENERATION to generation
                    )
                )
                .build()

            return WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(meetingId, taskType, generation),
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
