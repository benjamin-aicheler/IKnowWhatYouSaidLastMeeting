package com.example.lastmeeting.service

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import com.example.lastmeeting.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 34])
class RecordingServiceTest {
    private lateinit var controller: ServiceController<RecordingService>
    private lateinit var service: RecordingService
    private lateinit var recorder: FakeRecorder
    private var factoryCalls = 0

    @Before
    fun setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        controller = Robolectric.buildService(RecordingService::class.java).create()
        service = controller.get()
        recorder = FakeRecorder()
        service.recorderFactory = {
            factoryCalls++
            val notification = checkNotNull(shadowOf(service).lastForegroundNotification) {
                "Foreground promotion must precede recorder creation"
            }
            assertEquals(service.getString(R.string.recording_preparing),
                notification.extras.getString(Notification.EXTRA_TITLE))
            recorder
        }
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun configurationFailureReleasesRecorderAndDiscardsPartialFile() = assertStartupFailure("configure")

    @Test
    fun prepareFailureReleasesRecorderAndDiscardsPartialFile() = assertStartupFailure("prepare")

    @Test
    fun startFailureReleasesRecorderAndDiscardsPartialFile() = assertStartupFailure("start")

    @Test
    fun releaseFailureDoesNotPreventStartupCleanup() {
        recorder.failRelease = true
        assertStartupFailure("prepare")
    }

    @Test
    fun recorderConstructionFailureStopsForegroundService() {
        service.recorderFactory = { throw IOException("recorder unavailable") }
        startCommand()
        assertFailedStart()
        assertTrue(File(service.filesDir, "recordings").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun directoryCreationFailureStopsForegroundService() {
        File(service.filesDir, "recordings").writeText("not a directory")
        startCommand()
        assertFailedStart()
        assertEquals(0, factoryCalls)
    }

    @Test
    fun missingPermissionStopsServiceWithoutCreatingRecorder() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        startCommand()
        assertFailedStart(R.string.msg_recording_permission_required)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun foregroundPromotionFailureStopsServiceWithoutCreatingRecorder() {
        shadowOf(service).setThrowInStartForeground(SecurityException("permission revoked"))
        startCommand()
        assertFailedStart(R.string.msg_recording_permission_required)
        assertEquals(0, factoryCalls)
    }

    @Test
    @Config(sdk = [34])
    fun backgroundStartRestrictionStopsServiceWithoutCreatingRecorder() {
        shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("background start"))
        startCommand()
        assertFailedStart()
        assertEquals(0, factoryCalls)
    }

    @Test
    fun failedStartCanBeRetriedOnTheSameBoundService() {
        assertStartupFailure("prepare")
        recorder = FakeRecorder()
        startCommand()
        assertTrue(service.isRecording.value)
        assertNull(service.startupError.value)
        assertTrue(recorder.outputFile!!.exists())
        val notification = service.getSystemService(NotificationManager::class.java)
            .activeNotifications.single().notification
        assertEquals(service.getString(R.string.recording_in_progress),
            notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun duplicateStartDoesNotCreateAnotherRecorderOrFile() {
        startCommand()
        val firstFile = recorder.outputFile
        startCommand()
        assertEquals(1, factoryCalls)
        assertEquals(1, recorder.startCalls)
        assertEquals(firstFile, runBlocking { service.stopRecording() })
        assertEquals(1, recorder.releaseCalls)
        assertFalse(service.isRecording.value)
    }

    @Test
    fun releasesTheRecorderWhenStopThrows() {
        startCommand()
        recorder.failAt = "stop"

        runBlocking { service.stopRecording() }

        // A failing stop() must not leave the native recorder and the microphone allocated.
        assertEquals(1, recorder.releaseCalls)
        assertFalse(service.isRecording.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun idleStopCommandStopsTheService() {
        startCommand(RecordingService.ACTION_STOP_RECORDING)
        // The stop request runs in the service scope, so the main looper has to drain first.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(service.isRecording.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test
    fun failedStartupPreservesExistingRecordings() {
        val directory = File(service.filesDir, "recordings").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val existing = File(directory, "meeting_$timestamp.m4a").apply { writeText("existing audio") }
        recorder.failAt = "start"
        startCommand()
        assertFailedStart()
        assertEquals("existing audio", existing.readText())
        assertEquals(listOf(existing), directory.listFiles()!!.toList())
    }

    @Test
    fun startDuringStopFinalizationIsRejectedAndNextSessionSucceedsAfterStopCompletes() = runBlocking {
        val store = FakeMeetingStore()
        val persistenceScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        service.meetingStoreOverride = store
        service.persistenceScopeOverride = persistenceScope

        val stopEntered = CountDownLatch(1)
        val allowStopFinish = CountDownLatch(1)
        val recorderA = FakeRecorder(
            onStop = {
                stopEntered.countDown()
                check(allowStopFinish.await(5, TimeUnit.SECONDS)) { "Timed out waiting to finish stop" }
            }
        )
        val recorderB = FakeRecorder()
        val recorders = ArrayDeque(listOf(recorderA, recorderB))
        service.recorderFactory = {
            factoryCalls++
            recorders.removeFirst()
        }

        // 1. Start session A
        val fileA = service.startRecording()
        assertNotNull(fileA)
        assertTrue(service.isRecording.value)
        assertFalse(service.isStopping.value)
        assertEquals(listOf(fileA!!.absolutePath), store.startedPaths)

        // 2. Begin stopping session A and hold inside recorderA.stop()
        val stopADeferred = async(Dispatchers.Default) { service.stopRecording() }
        assertTrue(stopEntered.await(5, TimeUnit.SECONDS))

        assertFalse(service.isRecording.value)
        assertTrue(service.isStopping.value)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)

        // 3. Attempt to start session B while A is still finalizing
        val rejectedStart = service.startRecording()
        assertNull(rejectedStart)
        startCommand(RecordingService.ACTION_START_RECORDING)
        assertEquals(1, factoryCalls)
        assertEquals(listOf(fileA.absolutePath), store.startedPaths)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertTrue(service.isStopping.value)

        // 4. Release session A's stop and verify A's file, row, and foreground teardown
        allowStopFinish.countDown()
        val stoppedFileA = stopADeferred.await()
        assertEquals(fileA, stoppedFileA)
        assertFalse(service.isStopping.value)
        assertEquals(1, recorderA.stopCalls)
        assertEquals(1, recorderA.releaseCalls)
        assertEquals(listOf(1L), store.finishedIds)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)

        // 5. Start session B after A has finalized; verify B gets its own file, row, and foreground state
        val fileB = service.startRecording()
        assertNotNull(fileB)
        assertNotEquals(fileA, fileB)
        assertTrue(service.isRecording.value)
        assertFalse(service.isStopping.value)
        assertEquals(2, factoryCalls)
        assertEquals(listOf(fileA.absolutePath, fileB!!.absolutePath), store.startedPaths)

        val stoppedFileB = service.stopRecording()
        assertEquals(fileB, stoppedFileB)
        assertEquals(listOf(1L, 2L), store.finishedIds)
    }

    @Test
    fun duplicateStopDuringFinalizationAwaitsInFlightStopWithoutTearingDownEarly() = runBlocking {
        val store = FakeMeetingStore()
        val persistenceScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        service.meetingStoreOverride = store
        service.persistenceScopeOverride = persistenceScope

        val stopEntered = CountDownLatch(1)
        val allowStopFinish = CountDownLatch(1)
        recorder = FakeRecorder(
            onStop = {
                stopEntered.countDown()
                check(allowStopFinish.await(5, TimeUnit.SECONDS)) { "Timed out waiting to finish stop" }
            }
        )

        val fileA = service.startRecording()
        assertNotNull(fileA)

        val firstStop = async(Dispatchers.Default) { service.stopRecording() }
        assertTrue(stopEntered.await(5, TimeUnit.SECONDS))

        // Issue a second stop while the first is still inside recorder.stop()
        val secondStop = async(Dispatchers.Default) { service.stopRecording() }
        // Give the second stop a moment to reach the in-flight deferred
        Thread.sleep(50)

        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(1, recorder.stopCalls)

        allowStopFinish.countDown()
        assertEquals(fileA, firstStop.await())
        assertEquals(fileA, secondStop.await())
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(listOf(1L), store.finishedIds)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    private fun assertStartupFailure(stage: String) {
        recorder.failAt = stage
        startCommand()
        assertFailedStart()
        assertEquals(1, recorder.releaseCalls)
        assertNotNull(recorder.outputFile)
        assertFalse(recorder.outputFile!!.exists())
        // Idle stop/onDestroy must not expose or save the discarded partial recording.
        assertNull(runBlocking { service.stopRecording() })
    }

    private fun assertFailedStart(error: Int = R.string.msg_recording_start_failed) {
        assertFalse(service.isRecording.value)
        assertEquals(0L, service.elapsedTimeSeconds.value)
        assertEquals(error, service.startupError.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    private fun startCommand(action: String = RecordingService.ACTION_START_RECORDING) {
        val intent = Intent(service, RecordingService::class.java)
            .setAction(action)
        service.onStartCommand(intent, 0, 1)
    }

    private class FakeRecorder(
        private val onStop: (() -> Unit)? = null
    ) : AudioRecorder {
        var failAt: String? = null
        var failRelease = false
        var outputFile: File? = null
        var releaseCalls = 0
        var startCalls = 0
        var stopCalls = 0

        override fun configure(outputFile: File) {
            this.outputFile = outputFile
            outputFile.writeText("partial audio")
            failIfRequested("configure")
        }

        override fun prepare() = failIfRequested("prepare")
        override fun start() {
            startCalls++
            failIfRequested("start")
        }
        override fun stop() {
            stopCalls++
            onStop?.invoke()
            failIfRequested("stop")
        }
        override fun release() {
            releaseCalls++
            if (failRelease) throw IllegalStateException("release failed")
        }

        private fun failIfRequested(stage: String) {
            if (failAt == stage) throw IOException("$stage failed")
        }
    }

    private class FakeMeetingStore : MeetingSessionStore {
        private var nextId = 1L
        val startedPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val finishedIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val discardedIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        override suspend fun startMeeting(audioFile: File, startTime: Long): Long {
            startedPaths.add(audioFile.absolutePath)
            return nextId++
        }

        override suspend fun finishMeeting(id: Long, endTime: Long) {
            finishedIds.add(id)
        }

        override suspend fun createMeeting(audioFile: File, startTime: Long, endTime: Long): Long {
            startedPaths.add(audioFile.absolutePath)
            val id = nextId++
            finishedIds.add(id)
            return id
        }

        override suspend fun discardMeeting(id: Long) {
            discardedIds.add(id)
        }
    }
}
