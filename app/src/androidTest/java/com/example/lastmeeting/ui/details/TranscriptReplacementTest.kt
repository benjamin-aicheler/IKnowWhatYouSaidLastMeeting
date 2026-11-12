package com.example.lastmeeting.ui.details

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.MainActivity
import com.example.lastmeeting.R
import com.example.lastmeeting.data.db.MeetingEntity
import com.example.lastmeeting.worker.AiProcessingWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TranscriptReplacementTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var app: LastMeetingApp
    private lateinit var workManager: WorkManager
    private lateinit var audio: File
    private var meetingId = 0L
    private val store = ViewModelStore()
    private val meetingTitle = "Transcript replacement test"

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LastMeetingApp
        // Keep network constraints unmet and replace workers as a second safeguard against uploads.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    object : Worker(appContext, workerParameters) {
                        override fun doWork() = Result.success()
                    }
            }).build())
        workManager = WorkManager.getInstance(app)
        audio = File.createTempFile("transcript-replacement-", ".m4a", app.cacheDir)
    }

    @After
    fun tearDown() {
        compose.activityRule.scenario.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        workManager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        runBlocking { app.database.meetingDao().deleteMeeting(meetingId) }
        audio.delete()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun firstTranscriptionEnqueuesWithoutConfirmation() {
        seedMeeting(transcript = null)
        openMeeting()
        tapTranscribe()

        compose.waitUntil(5_000) { jobs().size == 1 }
        compose.onNodeWithText(app.getString(R.string.confirm_replace_transcript_title)).assertDoesNotExist()
        assertEquals(0L, jobs().single().inputGeneration())
    }

    @Test
    fun cancelAndDismissLeaveResultsUntouchedAndEnqueueNothing() {
        seedMeeting()
        openMeeting()
        val original = currentMeeting()

        tapTranscribe()
        assertConfirmation()
        compose.onNodeWithText(app.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithText(app.getString(R.string.confirm_replace_transcript_title)).assertDoesNotExist()
        assertTrue(jobs().isEmpty())

        tapTranscribe()
        assertConfirmation()
        pressBack()
        compose.onNodeWithText(app.getString(R.string.confirm_replace_transcript_title)).assertDoesNotExist()
        assertTrue(jobs().isEmpty())
        assertEquals(original, currentMeeting())
    }

    @Test
    fun confirmationSurvivesActivityRecreationAndEnqueuesOneRequest() {
        seedMeeting()
        openMeeting()
        tapTranscribe()
        assertConfirmation()
        assertTrue(jobs().isEmpty())

        compose.activityRule.scenario.recreate()
        assertConfirmation()
        assertTrue(jobs().isEmpty())
        compose.onNodeWithText(app.getString(R.string.action_replace_transcript)).performClick()

        compose.waitUntil(5_000) { jobs().size == 1 }
        compose.onNodeWithText(app.getString(R.string.confirm_replace_transcript_title)).assertDoesNotExist()
        assertEquals("Existing transcript", currentMeeting().transcript)
        assertEquals("Existing protocol", currentMeeting().protocol)
    }

    @Test
    fun viewModelChecksStorageEvenBeforeScreenStateHasLoaded() {
        seedMeeting()
        lateinit var viewModel: MeetingDetailsViewModel
        compose.runOnIdle {
            viewModel = MeetingDetailsViewModel(app, meetingId).also { store.put("details", it) }
            assertNull(viewModel.meeting.value)
            viewModel.transcribe()
        }

        compose.waitUntil(5_000) { viewModel.showTranscriptionConfirmation.value }
        assertTrue(jobs().isEmpty())

        compose.runOnIdle {
            viewModel.confirmTranscription()
            viewModel.confirmTranscription()
        }
        compose.waitUntil(5_000) { jobs().size == 1 }
        assertFalse(viewModel.showTranscriptionConfirmation.value)
    }

    private fun seedMeeting(transcript: String? = "Existing transcript") = runBlocking {
        meetingId = app.database.meetingDao().insertMeeting(MeetingEntity(
            title = meetingTitle,
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis(),
            audioFilePath = audio.absolutePath,
            transcript = transcript,
            protocol = if (transcript == null) null else "Existing protocol"
        ))
    }

    private fun openMeeting() {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(meetingTitle).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(meetingTitle).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(app.getString(R.string.action_transcribe)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tapTranscribe() {
        compose.onNodeWithText(app.getString(R.string.action_transcribe)).performScrollTo().performClick()
    }

    private fun assertConfirmation() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(app.getString(R.string.confirm_replace_transcript_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.getString(R.string.confirm_replace_transcript)).assertIsDisplayed()
    }

    private fun jobs(): List<WorkInfo> = workManager.getWorkInfosByTag(
        AiProcessingWorker.workTag(meetingId, AiProcessingWorker.TASK_TRANSCRIBE)
    ).get(5, TimeUnit.SECONDS)

    private fun WorkInfo.inputGeneration(): Long {
        // Generation is encoded in the unique-work tag, which survives configuration changes.
        return tags.single { it.startsWith(AiProcessingWorker.workTag(meetingId, AiProcessingWorker.TASK_TRANSCRIBE) + "_") }
            .substringAfterLast('_').toLong()
    }

    private fun currentMeeting() = runBlocking {
        requireNotNull(app.database.meetingDao().getMeetingByIdDirect(meetingId))
    }
}
