package com.example.lastmeeting.service

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import com.example.lastmeeting.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.Date
import java.util.Locale

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
        assertEquals(firstFile, service.stopRecording())
        assertEquals(1, recorder.releaseCalls)
        assertFalse(service.isRecording.value)
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

    private fun assertStartupFailure(stage: String) {
        recorder.failAt = stage
        startCommand()
        assertFailedStart()
        assertEquals(1, recorder.releaseCalls)
        assertNotNull(recorder.outputFile)
        assertFalse(recorder.outputFile!!.exists())
        // Idle stop/onDestroy must not expose or save the discarded partial recording.
        assertNull(service.stopRecording())
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

    private fun startCommand() {
        val intent = Intent(service, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START_RECORDING)
        service.onStartCommand(intent, 0, 1)
    }

    private class FakeRecorder : AudioRecorder {
        var failAt: String? = null
        var failRelease = false
        var outputFile: File? = null
        var releaseCalls = 0
        var startCalls = 0

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
        override fun stop() = Unit
        override fun release() {
            releaseCalls++
            if (failRelease) throw IllegalStateException("release failed")
        }

        private fun failIfRequested(stage: String) {
            if (failAt == stage) throw IOException("$stage failed")
        }
    }
}
