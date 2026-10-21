package com.example.lastmeeting.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.lastmeeting.LastMeetingApp

class AiProcessingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val meetingId = inputData.getLong(KEY_MEETING_ID, -1L)
        val taskType = inputData.getString(KEY_TASK_TYPE) ?: return Result.failure()
        if (meetingId <= 0L) return Result.failure()

        val app = applicationContext as? LastMeetingApp
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Application context unavailable."))

        val repository = app.meetingRepository

        val apiResult = when (taskType) {
            TASK_TRANSCRIBE -> repository.transcribeMeeting(meetingId)
            TASK_PROTOCOLIZE -> repository.protocolizeMeeting(meetingId)
            else -> return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Unknown AI task type."))
        }

        return apiResult.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                Result.failure(
                    workDataOf(KEY_ERROR_MESSAGE to (error.localizedMessage ?: "AI processing failed."))
                )
            }
        )
    }

    companion object {
        const val KEY_MEETING_ID = "meeting_id"
        const val KEY_TASK_TYPE = "task_type"
        const val KEY_ERROR_MESSAGE = "error_message"

        const val TASK_TRANSCRIBE = "TRANSCRIBE"
        const val TASK_PROTOCOLIZE = "PROTOCOLIZE"

        fun uniqueWorkName(meetingId: Long, taskType: String): String =
            "ai_processing_${meetingId}_$taskType"

        fun enqueue(context: Context, meetingId: Long, taskType: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AiProcessingWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_MEETING_ID to meetingId,
                        KEY_TASK_TYPE to taskType
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(meetingId, taskType),
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
