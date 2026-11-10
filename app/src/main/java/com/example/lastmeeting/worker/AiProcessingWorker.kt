package com.example.lastmeeting.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
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
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.R
import com.example.lastmeeting.repository.AiGenerationOutcome
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

        val apiResult = when (taskType) {
            TASK_TRANSCRIBE -> repository.transcribeMeeting(meetingId, generation)
            TASK_PROTOCOLIZE -> repository.protocolizeMeeting(meetingId, generation)
            else -> return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Unknown AI task type."))
        }

        return apiResult.fold(
            onSuccess = { outcome ->
                Result.success(workDataOf(KEY_DISCARDED to (outcome == AiGenerationOutcome.Discarded)))
            },
            onFailure = { error ->
                if (AiRetryPolicy.isRetryable(error, runAttemptCount)) {
                    Result.retry()
                } else {
                    Result.failure(
                        workDataOf(KEY_ERROR_MESSAGE to (error.localizedMessage ?: "AI processing failed."))
                    )
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
            e.printStackTrace()
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

    companion object {
        const val KEY_MEETING_ID = "meeting_id"
        const val KEY_TASK_TYPE = "task_type"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_GENERATION = "generation"
        const val KEY_DISCARDED = "discarded"

        const val TASK_TRANSCRIBE = "TRANSCRIBE"
        const val TASK_PROTOCOLIZE = "PROTOCOLIZE"

        private const val CHANNEL_ID = "ai_processing_channel"
        private const val NOTIFICATION_ID = 2001
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
