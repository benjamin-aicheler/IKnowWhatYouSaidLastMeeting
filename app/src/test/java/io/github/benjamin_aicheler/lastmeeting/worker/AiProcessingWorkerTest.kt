package io.github.benjamin_aicheler.lastmeeting.worker

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.benjamin_aicheler.lastmeeting.LastMeetingApp
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.api.IncompleteCompletionException
import io.github.benjamin_aicheler.lastmeeting.data.api.OpenAiApiClient
import io.github.benjamin_aicheler.lastmeeting.data.db.MeetingEntity
import io.github.benjamin_aicheler.lastmeeting.data.preferences.ApiSettings
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsProvider
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsRepository
import io.github.benjamin_aicheler.lastmeeting.data.preferences.TranscriptionMode
import io.github.benjamin_aicheler.lastmeeting.repository.MeetingRepository
import io.github.benjamin_aicheler.lastmeeting.util.AudioSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
            val input = workDataOf(
                AiProcessingWorker.KEY_MEETING_ID to id,
                AiProcessingWorker.KEY_TASK_TYPE to task,
                AiProcessingWorker.KEY_GENERATION to 0L,
                AiProcessingWorker.KEY_CONFIG_REVISION to 0L
            )
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

    @Test
    @Config(application = LastMeetingApp::class)
    fun terminalFailurePersistsTaskErrorAndPostsNotification() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as LastMeetingApp
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val dao = app.database.meetingDao()
        val id = dao.insertMeeting(
            MeetingEntity(
                title = "Broken audio meeting",
                startTime = 1L,
                endTime = 2L,
                audioFilePath = "/missing.m4a",
                transcript = null,
                protocol = null
            )
        )
        val configRevision = app.settingsRepository.settingsFlow.first().providerConfigRevision
        val input = workDataOf(
            AiProcessingWorker.KEY_MEETING_ID to id,
            AiProcessingWorker.KEY_TASK_TYPE to AiProcessingWorker.TASK_TRANSCRIBE,
            AiProcessingWorker.KEY_GENERATION to 0L,
            AiProcessingWorker.KEY_CONFIG_REVISION to configRevision
        )
        val worker = TestListenableWorkerBuilder<AiProcessingWorker>(app)
            .setInputData(input)
            .setRunAttemptCount(0)
            .build()

        val result = worker.doWork()
        val expectedError = app.getString(R.string.msg_audio_not_found)
        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(AiProcessingWorker.KEY_ERROR_CODE to AiTaskErrorCode.AUDIO_NOT_FOUND.code)
            ),
            result
        )
        assertEquals(expectedError, dao.getMeetingByIdDirect(id)!!.transcriptError)

        val notificationManager = app.getSystemService(NotificationManager::class.java)
        val postedTitles = Shadows.shadowOf(notificationManager).allNotifications
            .map { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() }
        assertTrue(postedTitles.contains(app.getString(R.string.ai_notification_transcript_failed)))

        assertEquals(1, dao.updateTranscript(id, "Recovered", 0L))
        assertNull(dao.getMeetingByIdDirect(id)!!.transcriptError)
    }

    @Test
    @Config(application = LastMeetingApp::class)
    fun workerWithMatchingConfigRevisionUploadsAndSucceeds() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as LastMeetingApp
        val dao = app.database.meetingDao()
        val previousRepository = app.meetingRepository
        val requestedUrls = mutableListOf<String>()
        val fakeSettings = FakeSettingsProvider(
            ApiSettings(
                baseUrl = "https://provider-a.example/v1/",
                apiKey = "sk-secret-a",
                transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
                transcriptionModel = "whisper-1",
                protocolModel = "gpt-4o-mini",
                transcriptionPrompt = "Transcribe",
                protocolPrompt = "Summarize",
                providerConfigRevision = 2L
            )
        )
        val audioFile = File.createTempFile("worker-match-", ".m4a", app.filesDir).apply { writeText("audio") }
        try {
            app.meetingRepository = MeetingRepository(
                context = app,
                meetingDao = dao,
                settingsRepository = fakeSettings,
                apiClient = OpenAiApiClient(
                    httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                        requestedUrls += chain.request().url.toString()
                        val body = if (chain.request().url.encodedPath.endsWith("/audio/transcriptions")) {
                            """{"text":"Worker transcript"}"""
                        } else {
                            """{"choices":[{"message":{"content":"Worker protocol"}}]}"""
                        }
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody("application/json".toMediaType()))
                            .build()
                    }).build()
                )
            )
            val id = dao.insertMeeting(
                MeetingEntity(
                    title = "Worker meeting",
                    startTime = 1L,
                    endTime = 2L,
                    audioFilePath = audioFile.absolutePath
                )
            )

            val transcribeInput = workDataOf(
                AiProcessingWorker.KEY_MEETING_ID to id,
                AiProcessingWorker.KEY_TASK_TYPE to AiProcessingWorker.TASK_TRANSCRIBE,
                AiProcessingWorker.KEY_GENERATION to 0L,
                AiProcessingWorker.KEY_CONFIG_REVISION to 2L
            )
            val transcribeResult = TestListenableWorkerBuilder<AiProcessingWorker>(app)
                .setInputData(transcribeInput)
                .build()
                .doWork()
            assertEquals(ListenableWorker.Result.success(workDataOf(AiProcessingWorker.KEY_DISCARDED to false)), transcribeResult)

            val protocolInput = workDataOf(
                AiProcessingWorker.KEY_MEETING_ID to id,
                AiProcessingWorker.KEY_TASK_TYPE to AiProcessingWorker.TASK_PROTOCOLIZE,
                AiProcessingWorker.KEY_GENERATION to 1L,
                AiProcessingWorker.KEY_CONFIG_REVISION to 2L
            )
            val protocolResult = TestListenableWorkerBuilder<AiProcessingWorker>(app)
                .setInputData(protocolInput)
                .build()
                .doWork()
            assertEquals(ListenableWorker.Result.success(workDataOf(AiProcessingWorker.KEY_DISCARDED to false)), protocolResult)

            val saved = dao.getMeetingByIdDirect(id)!!
            assertEquals("Worker transcript", saved.transcript)
            assertEquals("Worker protocol", saved.protocol)
            assertEquals(
                listOf(
                    "https://provider-a.example/v1/audio/transcriptions",
                    "https://provider-a.example/v1/chat/completions"
                ),
                requestedUrls
            )
        } finally {
            app.meetingRepository = previousRepository
            audioFile.delete()
        }
    }

    @Test
    @Config(application = LastMeetingApp::class)
    fun workerFailsWithoutUploadingWhenProviderConfigChangesAfterEnqueue() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as LastMeetingApp
        val dao = app.database.meetingDao()
        val previousRepository = app.meetingRepository
        val networkCalls = AtomicInteger()
        val fakeSettings = FakeSettingsProvider(
            ApiSettings(
                baseUrl = "https://provider-a.example/v1/",
                apiKey = "sk-secret-a",
                transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
                transcriptionModel = "whisper-1",
                protocolModel = "gpt-4o-mini",
                transcriptionPrompt = "Transcribe",
                protocolPrompt = "Summarize",
                providerConfigRevision = 1L
            )
        )
        val audioFile = File.createTempFile("worker-mismatch-", ".m4a", app.filesDir).apply { writeText("audio") }
        try {
            app.meetingRepository = MeetingRepository(
                context = app,
                meetingDao = dao,
                settingsRepository = fakeSettings,
                apiClient = OpenAiApiClient(
                    httpClient = OkHttpClient.Builder().addInterceptor(Interceptor {
                        networkCalls.incrementAndGet()
                        error("Network must not be called when provider config changed")
                    }).build()
                )
            )
            val id = dao.insertMeeting(
                MeetingEntity(
                    title = "Mismatch meeting",
                    startTime = 1L,
                    endTime = 2L,
                    audioFilePath = audioFile.absolutePath,
                    transcript = "Existing transcript",
                    protocol = "Existing protocol"
                )
            )

            // Provider changes between enqueue (rev 1) and execution (rev 2).
            fakeSettings.update {
                it.copy(
                    baseUrl = "https://provider-b.example/v1/",
                    apiKey = "sk-secret-b",
                    providerConfigRevision = 2L
                )
            }

            val expectedError = app.getString(R.string.msg_provider_config_changed)
            for (task in listOf(AiProcessingWorker.TASK_TRANSCRIBE, AiProcessingWorker.TASK_PROTOCOLIZE)) {
                val input = workDataOf(
                    AiProcessingWorker.KEY_MEETING_ID to id,
                    AiProcessingWorker.KEY_TASK_TYPE to task,
                    AiProcessingWorker.KEY_GENERATION to 0L,
                    AiProcessingWorker.KEY_CONFIG_REVISION to 1L
                )
                assertFalse(input.keyValueMap.values.any { it.toString().contains("sk-secret") || it.toString().contains("provider-") })

                val result = TestListenableWorkerBuilder<AiProcessingWorker>(app)
                    .setInputData(input)
                    .build()
                    .doWork()
                assertEquals(
                    ListenableWorker.Result.failure(
                        workDataOf(AiProcessingWorker.KEY_ERROR_CODE to AiTaskErrorCode.PROVIDER_CONFIG_CHANGED.code)
                    ),
                    result
                )
                val output = (result as ListenableWorker.Result.Failure).outputData
                assertNull(output.getString(AiProcessingWorker.KEY_ERROR_MESSAGE))
                assertFalse(output.keyValueMap.values.any { it.toString().contains("sk-secret") || it.toString().contains("provider-") })
            }

            assertEquals(0, networkCalls.get())
            val saved = dao.getMeetingByIdDirect(id)!!
            assertEquals("Existing transcript", saved.transcript)
            assertEquals("Existing protocol", saved.protocol)
            assertEquals(expectedError, saved.transcriptError)
            assertEquals(expectedError, saved.protocolError)
        } finally {
            app.meetingRepository = previousRepository
            audioFile.delete()
        }
    }

    @Test
    @Config(application = LastMeetingApp::class)
    fun legacyWorkerWithGenerationButMissingConfigRevisionFailsClosedUnlessDeleted() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as LastMeetingApp
        val dao = app.database.meetingDao()
        val previousRepository = app.meetingRepository
        val networkCalls = AtomicInteger()
        val fakeSettings = FakeSettingsProvider(
            ApiSettings(
                baseUrl = "https://provider-a.example/v1/",
                apiKey = "sk-secret-a",
                transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
                transcriptionModel = "whisper-1",
                protocolModel = "gpt-4o-mini",
                transcriptionPrompt = "Transcribe",
                protocolPrompt = "Summarize",
                providerConfigRevision = 0L
            )
        )
        val audioFile = File.createTempFile("worker-legacy-rev-", ".m4a", app.filesDir).apply { writeText("audio") }
        try {
            app.meetingRepository = MeetingRepository(
                context = app,
                meetingDao = dao,
                settingsRepository = fakeSettings,
                apiClient = OpenAiApiClient(
                    httpClient = OkHttpClient.Builder().addInterceptor(Interceptor {
                        networkCalls.incrementAndGet()
                        error("Network must not be called when config revision is missing")
                    }).build()
                )
            )
            val id = dao.insertMeeting(
                MeetingEntity(
                    title = "Legacy revision meeting",
                    startTime = 1L,
                    endTime = 2L,
                    audioFilePath = audioFile.absolutePath,
                    transcript = "Existing transcript",
                    protocol = "Existing protocol"
                )
            )

            val expectedError = app.getString(R.string.msg_provider_config_changed)
            val legacyInput = workDataOf(
                AiProcessingWorker.KEY_MEETING_ID to id,
                AiProcessingWorker.KEY_TASK_TYPE to AiProcessingWorker.TASK_TRANSCRIBE,
                AiProcessingWorker.KEY_GENERATION to 0L
            )
            val failureResult = TestListenableWorkerBuilder<AiProcessingWorker>(app)
                .setInputData(legacyInput)
                .build()
                .doWork()
            assertEquals(
                ListenableWorker.Result.failure(
                    workDataOf(AiProcessingWorker.KEY_ERROR_CODE to AiTaskErrorCode.PROVIDER_CONFIG_CHANGED.code)
                ),
                failureResult
            )
            assertEquals(expectedError, dao.getMeetingByIdDirect(id)!!.transcriptError)

            // Deleting the transcript bumps transcriptGeneration to 1; a stale legacy job with generation 0 discards quietly.
            dao.deleteTranscript(id)
            val staleResult = TestListenableWorkerBuilder<AiProcessingWorker>(app)
                .setInputData(legacyInput)
                .build()
                .doWork()
            assertEquals(discarded(), staleResult)
            assertEquals(0, networkCalls.get())
            assertNull(dao.getMeetingByIdDirect(id)!!.transcriptError)
        } finally {
            app.meetingRepository = previousRepository
            audioFile.delete()
        }
    }

    @Test
    @Config(application = LastMeetingApp::class)
    fun oversizedProviderErrorResponseProducesBoundedCodeAndPersistedMessageWithoutThrowing() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as LastMeetingApp
        val dao = app.database.meetingDao()
        val previousRepository = app.meetingRepository
        app.settingsRepository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "sk-test",
            transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "Transcribe",
            protocolPrompt = "Summarize"
        )
        val configRevision = app.settingsRepository.settingsFlow.first().providerConfigRevision
        val audioFile = File.createTempFile("worker-oversized-err-", ".m4a", app.filesDir).apply { writeText("audio") }
        val rawMarker = "RAW_PROVIDER_SECRET_PAYLOAD_"
        val oversizedRawBody = """{"detail":"$rawMarker${"X".repeat(25_000)}"}"""
        val oversizedStructuredMessage = """{"error":{"message":"$rawMarker${"Y".repeat(25_000)}"}}"""
        try {
            for (body in listOf(oversizedRawBody, oversizedStructuredMessage)) {
                app.meetingRepository = MeetingRepository(
                    context = app,
                    meetingDao = dao,
                    settingsRepository = app.settingsRepository,
                    apiClient = OpenAiApiClient(
                        httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(400)
                                .message("Bad Request")
                                .body(body.toResponseBody("application/json".toMediaType()))
                                .build()
                        }).build()
                    )
                )
                val id = dao.insertMeeting(
                    MeetingEntity(
                        title = "Oversized HTTP error",
                        startTime = 1L,
                        endTime = 2L,
                        audioFilePath = audioFile.absolutePath
                    )
                )
                val worker = TestListenableWorkerBuilder<AiProcessingWorker>(app)
                    .setInputData(
                        workDataOf(
                            AiProcessingWorker.KEY_MEETING_ID to id,
                            AiProcessingWorker.KEY_TASK_TYPE to AiProcessingWorker.TASK_TRANSCRIBE,
                            AiProcessingWorker.KEY_GENERATION to 0L,
                            AiProcessingWorker.KEY_CONFIG_REVISION to configRevision
                        )
                    )
                    .build()

                val result = worker.doWork()
                assertEquals(
                    ListenableWorker.Result.failure(
                        workDataOf(AiProcessingWorker.KEY_ERROR_CODE to AiTaskErrorCode.REQUEST_REJECTED.code)
                    ),
                    result
                )
                val output = (result as ListenableWorker.Result.Failure).outputData
                assertNull(output.getString(AiProcessingWorker.KEY_ERROR_MESSAGE))

                val persistedError = dao.getMeetingByIdDirect(id)!!.transcriptError!!
                assertEquals(app.getString(R.string.msg_ai_error_request_rejected_http, 400), persistedError)
                assertTrue(persistedError.length <= MeetingRepository.MAX_PERSISTED_ERROR_CHARS)
                assertFalse(persistedError.contains(rawMarker))
            }
        } finally {
            app.meetingRepository = previousRepository
            audioFile.delete()
        }
    }

    @Test
    @Config(application = LastMeetingApp::class)
    fun oversizedUnexpectedExceptionMessageProducesBoundedCodeAndPersistedMessageWithoutThrowing() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as LastMeetingApp
        val dao = app.database.meetingDao()
        val previousRepository = app.meetingRepository
        app.settingsRepository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "sk-test",
            transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "Transcribe",
            protocolPrompt = "Summarize"
        )
        val configRevision = app.settingsRepository.settingsFlow.first().providerConfigRevision
        val audioFile = File.createTempFile("worker-oversized-exc-", ".m4a", app.filesDir).apply { writeText("audio") }
        val oversizedMarker = "UNBOUNDED_STACK_TRACE_"
        try {
            app.meetingRepository = MeetingRepository(
                context = app,
                meetingDao = dao,
                settingsRepository = app.settingsRepository,
                audioSegmenter = AudioSegmenter { _, _ ->
                    throw IllegalStateException("$oversizedMarker${"Z".repeat(25_000)}")
                }
            )
            val id = dao.insertMeeting(
                MeetingEntity(
                    title = "Oversized unexpected error",
                    startTime = 1L,
                    endTime = 2L,
                    audioFilePath = audioFile.absolutePath
                )
            )
            val worker = TestListenableWorkerBuilder<AiProcessingWorker>(app)
                .setInputData(
                    workDataOf(
                        AiProcessingWorker.KEY_MEETING_ID to id,
                        AiProcessingWorker.KEY_TASK_TYPE to AiProcessingWorker.TASK_TRANSCRIBE,
                        AiProcessingWorker.KEY_GENERATION to 0L,
                        AiProcessingWorker.KEY_CONFIG_REVISION to configRevision
                    )
                )
                .build()

            val result = worker.doWork()
            assertEquals(
                ListenableWorker.Result.failure(
                    workDataOf(AiProcessingWorker.KEY_ERROR_CODE to AiTaskErrorCode.UNKNOWN.code)
                ),
                result
            )
            val persistedError = dao.getMeetingByIdDirect(id)!!.transcriptError!!
            assertEquals(app.getString(R.string.msg_ai_error_unknown), persistedError)
            assertFalse(persistedError.contains(oversizedMarker))
        } finally {
            app.meetingRepository = previousRepository
            audioFile.delete()
        }
    }

    @Test
    fun errorCodesDecodeRecognizedAndUnknownValues() {
        assertEquals(AiTaskErrorCode.AUTH_FAILED, AiTaskErrorCode.fromCode("auth_failed"))
        assertEquals(AiTaskErrorCode.PROVIDER_CONFIG_CHANGED, AiTaskErrorCode.fromCode("provider_config_changed"))
        assertEquals(AiTaskErrorCode.INCOMPLETE_RESPONSE, AiTaskErrorCode.fromCode("incomplete_response"))
        assertEquals(AiTaskErrorCode.UNKNOWN, AiTaskErrorCode.fromCode("unrecognized_future_code"))
        assertEquals(AiTaskErrorCode.UNKNOWN, AiTaskErrorCode.fromCode(null))
    }

    @Test
    fun incompleteResponseMapsToDedicatedCodeAndBoundsPersistedMessage() {
        val context = RuntimeEnvironment.getApplication()
        val normalError = IncompleteCompletionException("length", "truncated")
        assertEquals(AiTaskErrorCode.INCOMPLETE_RESPONSE, AiTaskErrorCode.fromThrowable(normalError))
        assertEquals(
            context.getString(R.string.msg_ai_error_incomplete_response),
            AiTaskErrorCode.INCOMPLETE_RESPONSE.localizedMessage(context)
        )
        assertEquals(
            context.getString(R.string.msg_ai_incomplete_response, "length"),
            AiTaskErrorCode.persistedMessage(context, normalError)
        )

        val oversizedReason = "length_${"X".repeat(500)}"
        val boundedMessage = AiTaskErrorCode.persistedMessage(
            context,
            IncompleteCompletionException(oversizedReason, "truncated")
        )
        assertTrue(boundedMessage.length <= MeetingRepository.MAX_PERSISTED_ERROR_CHARS)
        assertTrue(boundedMessage.contains("length_"))
    }

    @Test
    @Config(application = LastMeetingApp::class)
    fun cancelledWorkerRethrowsCancellationWithoutPersistingError() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as LastMeetingApp
        val dao = app.database.meetingDao()
        val previousRepository = app.meetingRepository
        app.settingsRepository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "sk-test",
            transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "Transcribe",
            protocolPrompt = "Summarize"
        )
        val configRevision = app.settingsRepository.settingsFlow.first().providerConfigRevision
        val audioFile = File.createTempFile("worker-cancel-", ".m4a", app.filesDir).apply { writeText("audio") }
        val segmentationStarted = CompletableDeferred<Unit>()
        try {
            app.meetingRepository = MeetingRepository(
                context = app,
                meetingDao = dao,
                settingsRepository = app.settingsRepository,
                audioSegmenter = AudioSegmenter { _, _ ->
                    segmentationStarted.complete(Unit)
                    throw CancellationException("Work cancelled")
                }
            )
            val id = dao.insertMeeting(
                MeetingEntity(
                    title = "Cancelled worker",
                    startTime = 1L,
                    endTime = 2L,
                    audioFilePath = audioFile.absolutePath
                )
            )
            val worker = TestListenableWorkerBuilder<AiProcessingWorker>(app)
                .setInputData(
                    workDataOf(
                        AiProcessingWorker.KEY_MEETING_ID to id,
                        AiProcessingWorker.KEY_TASK_TYPE to AiProcessingWorker.TASK_TRANSCRIBE,
                        AiProcessingWorker.KEY_GENERATION to 0L,
                        AiProcessingWorker.KEY_CONFIG_REVISION to configRevision
                    )
                )
                .build()

            val thrown = runCatching { worker.doWork() }.exceptionOrNull()
            assertTrue("Expected CancellationException, got $thrown", thrown is CancellationException)
            assertNull(dao.getMeetingByIdDirect(id)!!.transcriptError)
        } finally {
            app.meetingRepository = previousRepository
            audioFile.delete()
        }
    }

    private fun discarded() = ListenableWorker.Result.success(workDataOf(AiProcessingWorker.KEY_DISCARDED to true))

    private class FakeSettingsProvider(initial: ApiSettings) : SettingsProvider {
        private val state = MutableStateFlow(initial)
        override val settingsFlow: Flow<ApiSettings> = state

        fun update(transform: (ApiSettings) -> ApiSettings) {
            state.value = transform(state.value)
        }
    }
}
