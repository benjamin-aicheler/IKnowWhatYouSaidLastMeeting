package io.github.benjamin_aicheler.lastmeeting.ui.details

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.benjamin_aicheler.lastmeeting.LastMeetingApp
import io.github.benjamin_aicheler.lastmeeting.data.db.MeetingDatabase
import io.github.benjamin_aicheler.lastmeeting.data.db.MeetingEntity
import io.github.benjamin_aicheler.lastmeeting.repository.MeetingRepository
import io.github.benjamin_aicheler.lastmeeting.worker.AiProcessingWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LastMeetingApp::class, sdk = [34])
class MeetingDetailsViewModelDeletionTest {

    private lateinit var app: LastMeetingApp
    private lateinit var database: MeetingDatabase
    private lateinit var previousRepository: MeetingRepository
    private lateinit var workManager: WorkManager
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as LastMeetingApp
        previousRepository = app.meetingRepository
        database = Room.inMemoryDatabaseBuilder(app, MeetingDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters
                    ) = object : Worker(appContext, workerParameters) {
                        override fun doWork() = Result.success()
                    }
                })
                .build()
        )
        workManager = WorkManager.getInstance(app)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        app.meetingRepository = previousRepository
        database.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun deleteMeetingCancelsBothAiTasksForTargetMeetingOnly() = runBlocking {
        val dao = database.meetingDao()
        val fileA = File.createTempFile("del-a-", ".m4a", app.filesDir).apply { writeText("audioA") }
        val fileB = File.createTempFile("del-b-", ".m4a", app.filesDir).apply { writeText("audioB") }
        try {
            app.meetingRepository = MeetingRepository(
                context = app,
                meetingDao = dao,
                settingsRepository = app.settingsRepository,
                deleteAudioFile = File::delete
            )
            val idA = dao.insertMeeting(
                MeetingEntity(
                    title = "Meeting A",
                    startTime = 1L,
                    endTime = 2L,
                    audioFilePath = fileA.absolutePath,
                    transcript = "Transcript A"
                )
            )
            val idB = dao.insertMeeting(
                MeetingEntity(
                    title = "Meeting B",
                    startTime = 3L,
                    endTime = 4L,
                    audioFilePath = fileB.absolutePath,
                    transcript = "Transcript B"
                )
            )

            AiProcessingWorker.enqueue(app, idA, AiProcessingWorker.TASK_TRANSCRIBE, 0L, 0L).result.get()
            AiProcessingWorker.enqueue(app, idA, AiProcessingWorker.TASK_PROTOCOLIZE, 0L, 0L).result.get()
            AiProcessingWorker.enqueue(app, idB, AiProcessingWorker.TASK_TRANSCRIBE, 0L, 0L).result.get()
            AiProcessingWorker.enqueue(app, idB, AiProcessingWorker.TASK_PROTOCOLIZE, 0L, 0L).result.get()

            val viewModel = MeetingDetailsViewModel(app, idA).also { viewModelStore.put("a", it) }
            val eventDeferred = CompletableDeferred<DetailsUiEvent>()
            val collector = launch(Dispatchers.Main.immediate) {
                viewModel.eventFlow.collect { eventDeferred.complete(it) }
            }
            shadowOf(Looper.getMainLooper()).idle()

            viewModel.deleteMeeting()
            withTimeout(5_000) {
                while (!eventDeferred.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            val event = eventDeferred.await()
            collector.cancel()

            assertNull(dao.getMeetingByIdDirect(idA))
            assertNotNull(dao.getMeetingByIdDirect(idB))
            assertEquals(DetailsUiEvent.MeetingDeleted, event)

            assertEquals(
                WorkInfo.State.CANCELLED,
                workManager.getWorkInfosByTag(AiProcessingWorker.workTag(idA, AiProcessingWorker.TASK_TRANSCRIBE))
                    .get().single().state
            )
            assertEquals(
                WorkInfo.State.CANCELLED,
                workManager.getWorkInfosByTag(AiProcessingWorker.workTag(idA, AiProcessingWorker.TASK_PROTOCOLIZE))
                    .get().single().state
            )
            assertEquals(
                WorkInfo.State.ENQUEUED,
                workManager.getWorkInfosByTag(AiProcessingWorker.workTag(idB, AiProcessingWorker.TASK_TRANSCRIBE))
                    .get().single().state
            )
            assertEquals(
                WorkInfo.State.ENQUEUED,
                workManager.getWorkInfosByTag(AiProcessingWorker.workTag(idB, AiProcessingWorker.TASK_PROTOCOLIZE))
                    .get().single().state
            )
        } finally {
            fileA.delete()
            fileB.delete()
        }
    }

    @Test
    fun failedAudioDeleteKeepsMeetingAndExistingAiWork() = runBlocking {
        val dao = database.meetingDao()
        val fileA = File.createTempFile("del-fail-", ".m4a", app.filesDir).apply { writeText("audioA") }
        try {
            app.meetingRepository = MeetingRepository(
                context = app,
                meetingDao = dao,
                settingsRepository = app.settingsRepository,
                deleteAudioFile = { false }
            )
            val id = dao.insertMeeting(
                MeetingEntity(
                    title = "Protected meeting",
                    startTime = 1L,
                    endTime = 2L,
                    audioFilePath = fileA.absolutePath,
                    transcript = "Transcript"
                )
            )
            AiProcessingWorker.enqueue(app, id, AiProcessingWorker.TASK_TRANSCRIBE, 0L, 0L).result.get()

            val viewModel = MeetingDetailsViewModel(app, id).also { viewModelStore.put("fail", it) }
            val eventDeferred = CompletableDeferred<DetailsUiEvent>()
            val collector = launch(Dispatchers.Main.immediate) {
                viewModel.eventFlow.collect { eventDeferred.complete(it) }
            }
            shadowOf(Looper.getMainLooper()).idle()

            viewModel.deleteMeeting()
            withTimeout(5_000) {
                while (!eventDeferred.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            val event = eventDeferred.await()
            collector.cancel()

            assertNotNull(dao.getMeetingByIdDirect(id))
            assertEquals(
                WorkInfo.State.ENQUEUED,
                workManager.getWorkInfosByTag(AiProcessingWorker.workTag(id, AiProcessingWorker.TASK_TRANSCRIBE))
                    .get().single().state
            )
            assertEquals(DetailsUiEvent.ShowToast::class, event::class)
        } finally {
            fileA.delete()
        }
    }
}
