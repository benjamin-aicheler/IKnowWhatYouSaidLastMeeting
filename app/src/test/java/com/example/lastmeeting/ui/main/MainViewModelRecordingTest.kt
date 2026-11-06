package com.example.lastmeeting.ui.main

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.R
import com.example.lastmeeting.service.RecordingService
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertEquals(RecordingService.ACTION_START_RECORDING,
            checkNotNull(shadowOf(app).nextStartedService).action)
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
}
