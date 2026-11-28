package io.github.benjamin_aicheler.lastmeeting.ui.main

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.media.MediaRecorder
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.benjamin_aicheler.lastmeeting.LastMeetingApp
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.service.AudioRecorder
import io.github.benjamin_aicheler.lastmeeting.service.MeetingSessionStore
import io.github.benjamin_aicheler.lastmeeting.service.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(application = LastMeetingApp::class, sdk = [34])
class MainViewModelRecordingTest {
    private lateinit var app: LastMeetingApp
    private lateinit var controller: ServiceController<RecordingService>
    private lateinit var service: RecordingService
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as LastMeetingApp
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowApp.setUnbindServiceCallsOnServiceDisconnected(false)
        controller = Robolectric.buildService(RecordingService::class.java).create()
        service = controller.get()
        shadowApp.setComponentNameAndServiceForBindService(
            ComponentName(app, RecordingService::class.java), service.onBind(null)
        )
    }

    private class NoopRecorder : AudioRecorder {
        var errorListener: ((Int, Int) -> Unit)? = null

        override fun setOnErrorListener(listener: ((what: Int, extra: Int) -> Unit)?) {
            errorListener = listener
        }

        override fun configure(outputFile: File) {
            outputFile.writeText("audio")
        }

        override fun prepare() = Unit
        override fun start() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    @After
    fun tearDown() {
        store.clear()
        controller.destroy()
    }

    @Test
    fun promotionFailureReleasesBindingAndRetryBindsAgain() {
        val viewModel = MainViewModel(app).also { store.put("main", it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shadowOf(app).boundServiceConnections.size)

        shadowOf(service).setThrowInStartForeground(SecurityException("permission revoked"))
        service.onStartCommand(Intent().setAction(RecordingService.ACTION_START_RECORDING), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(R.string.msg_recording_permission_required, viewModel.recordingError.value)
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)

        // Android creates a fresh service after the failed instance is unbound and stopped.
        controller.destroy()
        controller = Robolectric.buildService(RecordingService::class.java).create()
        service = controller.get()
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, RecordingService::class.java), service.onBind(null)
        )
        viewModel.toggleRecording()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shadowOf(app).boundServiceConnections.size)
        // Robolectric records bind intents in the started-service queue as well.
        val recordingStarts = generateSequence { shadowOf(app).nextStartedService }
            .filter { it.action == RecordingService.ACTION_START_RECORDING }
            .toList()
        assertEquals(1, recordingStarts.size)
        assertEquals(ComponentName(app, RecordingService::class.java), recordingStarts.single().component)
    }

    @Test
    fun reportedStartupFailureIsNotReplayedToALaterBinding() {
        // The failure happens while no client is bound, so the service instance survives.
        shadowOf(service).setThrowInStartForeground(SecurityException("permission revoked"))
        service.onStartCommand(Intent().setAction(RecordingService.ACTION_START_RECORDING), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        val first = MainViewModel(app).also { store.put("first", it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(R.string.msg_recording_permission_required, first.recordingError.value)
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        assertNull(service.startupError.value)

        // A second observer (new screen, configuration change) must not see the old failure.
        val second = MainViewModel(app).also { store.put("second", it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(second.recordingError.value)
        assertEquals(1, shadowOf(app).boundServiceConnections.size)
    }

    @Test
    fun retryOnSurvivingInstanceKeepsBindingAndShowsRecording() {
        service.recorderFactory = { NoopRecorder() }
        shadowOf(service).setThrowInStartForeground(SecurityException("permission revoked"))
        service.onStartCommand(Intent().setAction(RecordingService.ACTION_START_RECORDING), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        val first = MainViewModel(app).also { store.put("first", it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(R.string.msg_recording_permission_required, first.recordingError.value)

        // Retry against the same service instance: no stale failure may unbind the retry.
        val retry = MainViewModel(app).also { store.put("retry", it) }
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(service).setThrowInStartForeground(null)
        retry.toggleRecording()
        service.onStartCommand(Intent().setAction(RecordingService.ACTION_START_RECORDING), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(service.isRecording.value)
        assertTrue(retry.isRecording.value)
        assertNull(retry.recordingError.value)
        assertEquals(1, shadowOf(app).boundServiceConnections.size)
    }

    @Test
    fun failureBeforeBindingCallbackIsStillObserved() {
        val viewModel = MainViewModel(app).also { store.put("main", it) }
        shadowOf(service).setThrowInStartForeground(SecurityException("permission revoked"))
        service.onStartCommand(Intent().setAction(RecordingService.ACTION_START_RECORDING), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(R.string.msg_recording_permission_required, viewModel.recordingError.value)
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
    }

    @Test
    fun runtimeRecorderErrorSurfacesOnceKeepsServiceBoundAndAllowsNewRecording() {
        val recorders = ArrayDeque(listOf(NoopRecorder(), NoopRecorder()))
        val firstRecorder = recorders.first()
        service.recorderFactory = { recorders.removeFirst() }
        service.persistenceScopeOverride = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        service.meetingStoreOverride = object : MeetingSessionStore {
            private var nextId = 1L
            override suspend fun startMeeting(audioFile: File, startTime: Long): Long = nextId++
            override suspend fun finishMeeting(id: Long, endTime: Long, isDamaged: Boolean) = Unit
            override suspend fun createMeeting(audioFile: File, startTime: Long, endTime: Long, isDamaged: Boolean): Long = nextId++
            override suspend fun discardMeeting(id: Long) = Unit
        }

        val viewModel = MainViewModel(app).also { store.put("main", it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shadowOf(app).boundServiceConnections.size)

        service.onStartCommand(Intent().setAction(RecordingService.ACTION_START_RECORDING), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.isRecording.value)

        firstRecorder.errorListener?.invoke(MediaRecorder.MEDIA_ERROR_SERVER_DIED, 0)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(R.string.msg_recording_interrupted, viewModel.recordingError.value)
        assertFalse(viewModel.isRecording.value)
        assertNull(service.runtimeError.value)
        assertEquals(1, shadowOf(app).boundServiceConnections.size)

        // A second observer must not see the already-consumed runtime error.
        val secondObserver = MainViewModel(app).also { store.put("second", it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(secondObserver.recordingError.value)

        // Starting another recording on the still-bound service works cleanly.
        viewModel.clearRecordingError()
        viewModel.toggleRecording()
        service.onStartCommand(Intent().setAction(RecordingService.ACTION_START_RECORDING), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.isRecording.value)
        assertTrue(viewModel.isRecording.value)
        assertNull(viewModel.recordingError.value)
    }
}
