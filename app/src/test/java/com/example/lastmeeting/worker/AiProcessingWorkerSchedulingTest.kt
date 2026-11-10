package com.example.lastmeeting.worker

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AiProcessingWorkerSchedulingTest {
    private lateinit var app: Application
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(context: Context, workerClassName: String, parameters: WorkerParameters) =
                        object : Worker(context, parameters) {
                            override fun doWork() = Result.success(inputData)
                        }
                }).build())
        workManager = WorkManager.getInstance(app)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun newerRequestIsAcceptedAndSurvivesDelayedCancellationOfOlderWork() {
        for (task in listOf(AiProcessingWorker.TASK_TRANSCRIBE, AiProcessingWorker.TASK_PROTOCOLIZE)) {
            AiProcessingWorker.enqueue(app, 1L, task, 0L).result.get()
            val old = workManager.getWorkInfosForUniqueWork(AiProcessingWorker.uniqueWorkName(1L, task, 0L)).get().single()
            // Simulate deletion committing, a new explicit request, then delayed cancellation.
            AiProcessingWorker.enqueue(app, 1L, task, 1L).result.get()
            AiProcessingWorker.cancel(app, 1L, task, 0L).result.get()

            val current = workManager.getWorkInfosForUniqueWork(AiProcessingWorker.uniqueWorkName(1L, task, 1L)).get().single()
            assertNotEquals(old.id, current.id)
            assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(old.id).get()!!.state)
            assertEquals(WorkInfo.State.ENQUEUED, current.state)
            assertEquals(2, workManager.getWorkInfosByTag(AiProcessingWorker.workTag(1L, task)).get().size)
        }
    }

    @Test
    fun queuedWorkRetainsItsInputVersionWhenANewerVersionIsEnqueued() {
        val task = AiProcessingWorker.TASK_TRANSCRIBE
        AiProcessingWorker.enqueue(app, 1L, task, 0L).result.get()
        val old = workManager.getWorkInfosForUniqueWork(AiProcessingWorker.uniqueWorkName(1L, task, 0L)).get().single()
        AiProcessingWorker.enqueue(app, 1L, task, 1L).result.get()

        WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(old.id)

        val finished = workManager.getWorkInfoById(old.id).get()!!
        assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
        assertEquals(0L, finished.outputData.getLong(AiProcessingWorker.KEY_GENERATION, -1L))
        assertEquals(1L, finished.outputData.getLong(AiProcessingWorker.KEY_MEETING_ID, -1L))
        assertEquals(task, finished.outputData.getString(AiProcessingWorker.KEY_TASK_TYPE))
    }

    @Test
    fun duplicateRequestsWithinTheSameVersionStillKeepTheOriginalJob() {
        val task = AiProcessingWorker.TASK_TRANSCRIBE
        AiProcessingWorker.enqueue(app, 1L, task, 0L).result.get()
        val original = workManager.getWorkInfosForUniqueWork(AiProcessingWorker.uniqueWorkName(1L, task, 0L)).get().single()
        AiProcessingWorker.enqueue(app, 1L, task, 0L).result.get()
        assertEquals(original.id,
            workManager.getWorkInfosForUniqueWork(AiProcessingWorker.uniqueWorkName(1L, task, 0L)).get().single().id)
    }
}
