package com.example.lastmeeting.data.preferences

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConsentNoticeSettingsTest {

    @Test
    fun consentNoticeStartsUnacknowledgedAndPersistsAcknowledgement() = runBlocking {
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())

        assertFalse(repository.consentNoticeAcknowledgedFlow.first())

        repository.acknowledgeConsentNotice()

        assertTrue(repository.consentNoticeAcknowledgedFlow.first())
    }
}
