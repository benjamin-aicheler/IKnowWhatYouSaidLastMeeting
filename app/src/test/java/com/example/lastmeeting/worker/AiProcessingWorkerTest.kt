package com.example.lastmeeting.worker

import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.data.db.MeetingEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AiProcessingWorkerTest {

    @Test
    fun legacyQueuedJobWithoutVersionIsDiscarded() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val worker = TestListenableWorkerBuilder<AiProcessingWorker>(app)
            .setInputData(workDataOf(AiProcessingWorker.KEY_MEETING_ID to 1L,
                AiProcessingWorker.KEY_TASK_TYPE to AiProcessingWorker.TASK_TRANSCRIBE))
            .build()
        assertEquals(discarded(), worker.doWork())
    }

    @Test
    @Config(application = LastMeetingApp::class)
    fun queuedAndRetriedWorkersCannotReadoptTheVersionAfterDeletion() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as LastMeetingApp
        val dao = app.database.meetingDao()
        for (task in listOf(AiProcessingWorker.TASK_TRANSCRIBE, AiProcessingWorker.TASK_PROTOCOLIZE)) {
            val id = dao.insertMeeting(MeetingEntity(title = "Queued meeting", startTime = 1L, endTime = 2L,
                audioFilePath = "/missing.m4a", transcript = "Existing transcript", protocol = "Existing protocol"))
            val input = workDataOf(AiProcessingWorker.KEY_MEETING_ID to id,
                AiProcessingWorker.KEY_TASK_TYPE to task, AiProcessingWorker.KEY_GENERATION to 0L)
            if (task == AiProcessingWorker.TASK_TRANSCRIBE) dao.deleteTranscript(id) else dao.deleteProtocol(id)
            for (attempt in 0..1) {
                val worker = TestListenableWorkerBuilder<AiProcessingWorker>(app)
                    .setInputData(input).setRunAttemptCount(attempt).build()
                assertEquals(discarded(), worker.doWork())
            }
            val meeting = dao.getMeetingByIdDirect(id)!!
            assertNull(if (task == AiProcessingWorker.TASK_TRANSCRIBE) meeting.transcript else meeting.protocol)
        }
    }

    private fun discarded() = ListenableWorker.Result.success(workDataOf(AiProcessingWorker.KEY_DISCARDED to true))
}
