package io.github.benjamin_aicheler.lastmeeting.repository

import android.app.Application
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.api.IncompleteCompletionException
import io.github.benjamin_aicheler.lastmeeting.data.api.OpenAiApiClient
import io.github.benjamin_aicheler.lastmeeting.data.db.MeetingDao
import io.github.benjamin_aicheler.lastmeeting.data.db.MeetingEntity
import io.github.benjamin_aicheler.lastmeeting.data.preferences.ApiSettings
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsProvider
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsRepository
import io.github.benjamin_aicheler.lastmeeting.data.preferences.TranscriptionMode
import io.github.benjamin_aicheler.lastmeeting.util.AndroidAudioSegmenter
import io.github.benjamin_aicheler.lastmeeting.util.AudioSegmentationSession
import io.github.benjamin_aicheler.lastmeeting.util.AudioSegmenter
import io.github.benjamin_aicheler.lastmeeting.worker.AiProcessingWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MeetingRepositoryAiTest {

    private lateinit var app: Application
    private lateinit var dao: FakeMeetingDao
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        dao = FakeMeetingDao()
        settingsRepository = SettingsRepository(app)
    }

    @Test
    fun protocolizeFailsWhenTranscriptIsMissingWithoutCallingNetwork() = runBlocking {
        var networkCalls = 0
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor {
                networkCalls++
                error("Network should not be called when transcript is missing")
            })
            .build()

        settingsRepository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "sk-test",
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "Transcribe",
            protocolPrompt = "Protocolize"
        )

        val repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = settingsRepository,
            apiClient = OpenAiApiClient(httpClient = httpClient)
        )

        val id = dao.insertMeeting(
            MeetingEntity(
                title = "Meeting 1",
                startTime = 1_000L,
                endTime = 2_000L,
                audioFilePath = "/nonexistent/audio.m4a",
                transcript = null,
                protocol = null
            )
        )

        val result = repository.protocolizeMeeting(id, 0L, currentRevision())

        assertTrue(result.isFailure)
        assertEquals(app.getString(R.string.msg_transcribe_first), result.exceptionOrNull()?.message)
        assertEquals(0, networkCalls)
        assertNull(dao.getMeetingByIdDirect(id)?.protocol)
    }

    @Test
    fun protocolizeUsesTranscriptTextAndConfiguredProtocolModel() = runBlocking {
        var capturedRequest: Request? = null
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                capturedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"choices":[{"message":{"content":"Generated Protocol Summary"}}]}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            })
            .build()

        settingsRepository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "sk-test",
            transcriptionModel = "gpt-4o-transcribe",
            protocolModel = "gpt-4o",
            transcriptionPrompt = "Transcribe prompt",
            protocolPrompt = "Protocol system prompt"
        )

        val repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = settingsRepository,
            apiClient = OpenAiApiClient(httpClient = httpClient)
        )

        // Note: audioFilePath does not even need to exist because protocolize uses the text transcript only.
        val id = dao.insertMeeting(
            MeetingEntity(
                title = "Meeting 2",
                startTime = 1_000L,
                endTime = 2_000L,
                audioFilePath = File(app.filesDir, "missing_audio.m4a").absolutePath,
                transcript = "Speaker A: Welcome everyone.\nSpeaker B: Let's review the roadmap.",
                protocol = null
            )
        )

        val result = repository.protocolizeMeeting(id, 0L, currentRevision())

        assertTrue(result.isSuccess)
        assertEquals("Generated Protocol Summary", dao.getMeetingByIdDirect(id)?.protocol)

        val requestBuffer = Buffer()
        capturedRequest!!.body!!.writeTo(requestBuffer)
        val root = Json.parseToJsonElement(requestBuffer.readUtf8()).jsonObject
        assertEquals("gpt-4o", root["model"]?.jsonPrimitive?.content)
        val messages = root["messages"]!!.jsonArray
        assertEquals("Protocol system prompt", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals(
            "Speaker A: Welcome everyone.\nSpeaker B: Let's review the roadmap.",
            messages[1].jsonObject["content"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun replacementKeepsExistingResultsUntilSuccessThenInvalidatesProtocol() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CountDownLatch(1)
        val repository = configuredRepository(Interceptor { chain ->
            requestStarted.complete(Unit)
            check(releaseResponse.await(15, TimeUnit.SECONDS))
            response(chain.request(), "Changed transcript")
        })
        val id = insertAiMeeting()
        val original = dao.getMeetingByIdDirect(id)!!
        val revision = currentRevision()
        val pending = async(Dispatchers.Default) { repository.transcribeMeeting(id, 0L, revision) }
        try {
            withTimeout(10_000) { requestStarted.await() }
            assertEquals(original, dao.getMeetingByIdDirect(id))
        } finally {
            releaseResponse.countDown()
        }

        assertEquals(AiGenerationOutcome.Stored("Changed transcript"), pending.await().getOrThrow())
        val current = dao.getMeetingByIdDirect(id)!!
        assertEquals("Changed transcript", current.transcript)
        assertNull(current.protocol)
        assertEquals(1L, current.protocolGeneration)
        assertEquals(original.transcriptGeneration, current.transcriptGeneration)
    }

    @Test
    fun failedReplacementKeepsExistingTranscriptAndProtocol() = runBlocking {
        val repository = configuredRepository(Interceptor { chain ->
            response(chain.request(), "Unavailable").newBuilder().code(503).message("Unavailable").build()
        })
        val id = insertAiMeeting()
        val original = dao.getMeetingByIdDirect(id)

        assertTrue(repository.transcribeMeeting(id, 0L, currentRevision()).isFailure)
        assertEquals(original, dao.getMeetingByIdDirect(id))
    }

    @Test
    fun identicalReplacementPreservesProtocolAndItsGeneration() = runBlocking {
        val repository = configuredRepository(Interceptor { chain -> response(chain.request(), "Existing transcript") })
        val id = insertAiMeeting()
        val original = dao.getMeetingByIdDirect(id)

        assertEquals(AiGenerationOutcome.Stored("Existing transcript"), repository.transcribeMeeting(id, 0L, currentRevision()).getOrThrow())
        assertEquals(original, dao.getMeetingByIdDirect(id))
    }

    @Test
    fun staleTranscriptionResponseDoesNotInvalidateProtocol() = runBlocking {
        val id = insertAiMeeting()
        val repository = configuredRepository(Interceptor { chain ->
            runBlocking { dao.deleteTranscript(id) }
            response(chain.request(), "Obsolete transcript")
        })

        assertEquals(AiGenerationOutcome.Discarded, repository.transcribeMeeting(id, 0L, currentRevision()).getOrThrow())
        val current = dao.getMeetingByIdDirect(id)!!
        assertNull(current.transcript)
        assertEquals("Existing protocol", current.protocol)
        assertEquals(0L, current.protocolGeneration)
    }

    @Test
    fun delayedProtocolCannotRepopulateAfterTranscriptChanges() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CountDownLatch(1)
        val protocolCalls = AtomicInteger()
        val repository = configuredRepository(Interceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/audio/transcriptions")) {
                response(chain.request(), "Changed transcript")
            } else {
                if (protocolCalls.incrementAndGet() == 1) {
                    requestStarted.complete(Unit)
                    check(releaseResponse.await(15, TimeUnit.SECONDS))
                }
                response(chain.request(), "Generated protocol")
            }
        })
        val id = insertAiMeeting()
        val revision = currentRevision()
        val pending = async(Dispatchers.Default) { repository.protocolizeMeeting(id, 0L, revision) }
        try {
            withTimeout(10_000) { requestStarted.await() }
            repository.transcribeMeeting(id, 0L, revision).getOrThrow()
        } finally {
            releaseResponse.countDown()
        }

        assertEquals(AiGenerationOutcome.Discarded, pending.await().getOrThrow())
        assertNull(dao.getMeetingByIdDirect(id)!!.protocol)
        // A retry keeps the old generation and must not make another request.
        assertEquals(AiGenerationOutcome.Discarded, repository.protocolizeMeeting(id, 0L, revision).getOrThrow())
        assertEquals(1, protocolCalls.get())
        assertEquals(AiGenerationOutcome.Stored("Generated protocol"), repository.protocolizeMeeting(id, 1L, revision).getOrThrow())
        assertEquals("Generated protocol", dao.getMeetingByIdDirect(id)!!.protocol)
    }

    @Test
    fun deletedTranscriptStaysEmptyWhenOldResponseArrives() = runBlocking {
        delayedResponse(transcriptTask = true)
    }

    @Test
    fun deletedProtocolStaysEmptyWhenOldResponseArrives() = runBlocking {
        delayedResponse(transcriptTask = false)
    }

    @Test
    fun oldResponsesCannotOverwriteAnExplicitGenerationAfterDeletion() = runBlocking {
        delayedResponse(transcriptTask = true, replaceBeforeResponse = true)
        delayedResponse(transcriptTask = false, replaceBeforeResponse = true)
    }

    @Test
    fun deletingTheOtherFieldDoesNotInvalidateTheResponse() = runBlocking {
        delayedResponse(transcriptTask = true, deleteOtherField = true)
        delayedResponse(transcriptTask = false, deleteOtherField = true)
    }

    private suspend fun delayedResponse(
        transcriptTask: Boolean,
        replaceBeforeResponse: Boolean = false,
        deleteOtherField: Boolean = false
    ) = coroutineScope {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CountDownLatch(1)
        val calls = AtomicInteger()
        val repository = configuredRepository(Interceptor { chain ->
            val first = calls.incrementAndGet() == 1
            if (first) {
                requestStarted.complete(Unit)
                check(releaseResponse.await(15, TimeUnit.SECONDS)) { "Response was not released" }
            }
            response(chain.request(), if (first) "Old response" else "New response")
        })
        val id = insertAiMeeting()
        val pending = async(Dispatchers.Default) { generate(repository, id, transcriptTask, 0L) }
        try {
            withTimeout(10_000) { requestStarted.await() }
            val deletedGeneration = if (transcriptTask != deleteOtherField) {
                repository.deleteTranscript(id)
            } else {
                repository.deleteProtocol(id)
            }
            assertEquals(0L, deletedGeneration)
            if (replaceBeforeResponse) {
                assertEquals(AiGenerationOutcome.Stored("New response"), generate(repository, id, transcriptTask, 1L).getOrThrow())
            }
        } finally {
            releaseResponse.countDown()
        }
        val outcome = pending.await().getOrThrow()
        val meeting = dao.getMeetingByIdDirect(id)!!
        val content = if (transcriptTask) meeting.transcript else meeting.protocol
        if (deleteOtherField) {
            assertEquals(AiGenerationOutcome.Stored("Old response"), outcome)
            assertEquals("Old response", content)
        } else {
            assertEquals(AiGenerationOutcome.Discarded, outcome)
            assertEquals(if (replaceBeforeResponse) "New response" else null, content)
            if (!replaceBeforeResponse) {
                assertEquals(AiGenerationOutcome.Stored("New response"), generate(repository, id, transcriptTask, 1L).getOrThrow())
            }
        }
    }

    @Test
    fun queuedJobsKeepTheirOriginalVersionAcrossRepositoryRecreation() = runBlocking {
        val calls = AtomicInteger()
        val interceptor = Interceptor { calls.incrementAndGet(); error("Stale job called the network") }
        val repository = configuredRepository(interceptor)
        for (transcriptTask in listOf(true, false)) {
            val id = insertAiMeeting()
            if (transcriptTask) repository.deleteTranscript(id) else repository.deleteProtocol(id)
            val restarted = configuredRepository(interceptor)
            assertEquals(AiGenerationOutcome.Discarded, generate(restarted, id, transcriptTask, 0L).getOrThrow())
            val meeting = dao.getMeetingByIdDirect(id)!!
            assertNull(if (transcriptTask) meeting.transcript else meeting.protocol)
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun retryAfterDeletionCannotAdoptTheNewVersion() = runBlocking {
        for (transcriptTask in listOf(true, false)) {
            val calls = AtomicInteger()
            val interceptor = Interceptor { chain ->
                if (calls.incrementAndGet() == 1) {
                    response(chain.request(), "Unavailable").newBuilder().code(503).message("Unavailable").build()
                } else {
                    response(chain.request(), "New response")
                }
            }
            val repository = configuredRepository(interceptor)
            val id = insertAiMeeting()
            assertTrue(generate(repository, id, transcriptTask, 0L).isFailure)
            if (transcriptTask) repository.deleteTranscript(id) else repository.deleteProtocol(id)
            val restarted = configuredRepository(interceptor)
            assertEquals(AiGenerationOutcome.Discarded, generate(restarted, id, transcriptTask, 0L).getOrThrow())
            assertEquals(1, calls.get())
            assertEquals(AiGenerationOutcome.Stored("New response"), generate(restarted, id, transcriptTask, 1L).getOrThrow())
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun multiSegmentRecordingUploadsSequentiallyMergesAndCleansUp() = runBlocking {
        val seg1 = File.createTempFile("seg-1-", ".m4a", app.cacheDir).apply { writeText("part1") }
        val seg2 = File.createTempFile("seg-2-", ".m4a", app.cacheDir).apply { writeText("part2") }
        var cleanedUp = false
        val segmenter = AudioSegmenter { _, _ ->
            Result.success(AudioSegmentationSession(listOf(seg1, seg2)) { cleanedUp = true })
        }
        val calls = AtomicInteger()
        val repository = configuredRepository(
            interceptor = Interceptor { chain ->
                val n = calls.incrementAndGet()
                response(chain.request(), "Segment $n text")
            },
            audioSegmenter = segmenter
        )
        val id = insertAiMeeting()

        val revision = currentRevision()
        val outcome = repository.transcribeMeeting(id, 0L, revision).getOrThrow()

        assertEquals(AiGenerationOutcome.Stored("Segment 1 text\n\nSegment 2 text"), outcome)
        assertEquals(2, calls.get())
        assertTrue(cleanedUp)
        val updated = dao.getMeetingByIdDirect(id)!!
        assertEquals("Segment 1 text\n\nSegment 2 text", updated.transcript)
        assertNull(updated.protocol)
    }

    @Test
    fun failedSecondSegmentPreservesExistingResultsAndCleansUp() = runBlocking {
        val seg1 = File.createTempFile("seg-fail-1-", ".m4a", app.cacheDir).apply { writeText("part1") }
        val seg2 = File.createTempFile("seg-fail-2-", ".m4a", app.cacheDir).apply { writeText("part2") }
        var cleanedUp = false
        val segmenter = AudioSegmenter { _, _ ->
            Result.success(AudioSegmentationSession(listOf(seg1, seg2)) { cleanedUp = true })
        }
        val calls = AtomicInteger()
        val repository = configuredRepository(
            interceptor = Interceptor { chain ->
                if (calls.incrementAndGet() == 1) {
                    response(chain.request(), "Segment 1 text")
                } else {
                    response(chain.request(), "Unavailable").newBuilder().code(503).message("Unavailable").build()
                }
            },
            audioSegmenter = segmenter
        )
        val id = insertAiMeeting()
        val original = dao.getMeetingByIdDirect(id)

        val result = repository.transcribeMeeting(id, 0L, currentRevision())

        assertTrue(result.isFailure)
        assertTrue(cleanedUp)
        assertEquals(original, dao.getMeetingByIdDirect(id))
    }

    @Test
    fun deletingTranscriptMidSegmentationSkipsRemainingSegmentsAndDiscards() = runBlocking {
        val seg1 = File.createTempFile("seg-del-1-", ".m4a", app.cacheDir).apply { writeText("part1") }
        val seg2 = File.createTempFile("seg-del-2-", ".m4a", app.cacheDir).apply { writeText("part2") }
        var cleanedUp = false
        val segmenter = AudioSegmenter { _, _ ->
            Result.success(AudioSegmentationSession(listOf(seg1, seg2)) { cleanedUp = true })
        }
        val id = insertAiMeeting()
        val calls = AtomicInteger()
        val repository = configuredRepository(
            interceptor = Interceptor { chain ->
                calls.incrementAndGet()
                runBlocking { dao.deleteTranscript(id) }
                response(chain.request(), "Segment 1 text")
            },
            audioSegmenter = segmenter
        )

        val outcome = repository.transcribeMeeting(id, 0L, currentRevision()).getOrThrow()

        assertEquals(AiGenerationOutcome.Discarded, outcome)
        assertEquals(1, calls.get())
        assertTrue(cleanedUp)
        assertNull(dao.getMeetingByIdDirect(id)!!.transcript)
    }

    @Test
    fun defaultInstructionPromptIsOmittedOnMultipartButSentOnChatAndCustomSentOnBoth() = runBlocking {
        var capturedRequest: Request? = null
        val interceptor = Interceptor { chain ->
            capturedRequest = chain.request()
            response(chain.request(), "Transcribed text")
        }
        val repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = settingsRepository,
            apiClient = OpenAiApiClient(httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build())
        )
        val id = insertAiMeeting()
        val defaultInstruction = app.getString(R.string.default_transcription_prompt)

        // Default prompt on multipart (/audio/transcriptions) -> omitted (2 parts: file + model).
        settingsRepository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "test-key",
            transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = defaultInstruction,
            protocolPrompt = app.getString(R.string.default_protocol_prompt)
        )
        repository.transcribeMeeting(id, 0L, currentRevision()).getOrThrow()
        val defaultMultipart = capturedRequest!!.body as MultipartBody
        assertEquals(2, defaultMultipart.size)

        // Default prompt on chat audio -> sends the localized instruction.
        settingsRepository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "test-key",
            transcriptionMode = TranscriptionMode.CHAT_COMPLETIONS,
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = defaultInstruction,
            protocolPrompt = app.getString(R.string.default_protocol_prompt)
        )
        repository.transcribeMeeting(id, 0L, currentRevision()).getOrThrow()
        val chatBuffer = Buffer()
        capturedRequest.body!!.writeTo(chatBuffer)
        val chatRoot = Json.parseToJsonElement(chatBuffer.readUtf8()).jsonObject
        val chatPrompt = chatRoot["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(defaultInstruction, chatPrompt)

        // Custom prompt on multipart -> included as the 3rd multipart part.
        settingsRepository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "test-key",
            transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "Vocabulary: Kubernetes, gRPC",
            protocolPrompt = app.getString(R.string.default_protocol_prompt)
        )
        repository.transcribeMeeting(id, 0L, currentRevision()).getOrThrow()
        val customMultipart = capturedRequest.body as MultipartBody
        assertEquals(3, customMultipart.size)
        val promptBuffer = Buffer()
        customMultipart.part(2).body.writeTo(promptBuffer)
        assertEquals("Vocabulary: Kubernetes, gRPC", promptBuffer.readUtf8())
    }

    @Test
    fun unchangedConfigRevisionUploadsToCapturedSnapshotAndStoresResult() = runBlocking {
        val requests = mutableListOf<Request>()
        val fakeSettings = FakeSettingsProvider(
            ApiSettings(
                baseUrl = "https://provider-a.example/v1/",
                apiKey = "sk-provider-a",
                transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
                transcriptionModel = "whisper-1",
                protocolModel = "gpt-4o-mini",
                transcriptionPrompt = "Transcribe",
                protocolPrompt = "Summarize",
                providerConfigRevision = 4L
            )
        )
        val repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = fakeSettings,
            apiClient = OpenAiApiClient(
                httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                    requests += chain.request()
                    response(chain.request(), "Updated from provider A")
                }).build()
            )
        )
        val id = insertAiMeeting()

        val transcribeOutcome = repository.transcribeMeeting(id, 0L, expectedConfigRevision = 4L).getOrThrow()
        assertEquals(AiGenerationOutcome.Stored("Updated from provider A"), transcribeOutcome)
        val protocolOutcome = repository.protocolizeMeeting(id, 1L, expectedConfigRevision = 4L).getOrThrow()
        assertEquals(AiGenerationOutcome.Stored("Updated from provider A"), protocolOutcome)

        assertEquals(2, requests.size)
        assertEquals("https://provider-a.example/v1/audio/transcriptions", requests[0].url.toString())
        assertEquals("Bearer sk-provider-a", requests[0].header("Authorization"))
        assertEquals("https://provider-a.example/v1/chat/completions", requests[1].url.toString())
        assertEquals("Bearer sk-provider-a", requests[1].header("Authorization"))
    }

    @Test
    fun changedConfigRevisionBeforeAttemptFailsWithoutNetworkAndPreservesResults() = runBlocking {
        val calls = AtomicInteger()
        val fakeSettings = FakeSettingsProvider(
            ApiSettings(
                baseUrl = "https://provider-a.example/v1/",
                apiKey = "sk-provider-a",
                transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
                transcriptionModel = "whisper-1",
                protocolModel = "gpt-4o-mini",
                transcriptionPrompt = "Transcribe",
                protocolPrompt = "Summarize",
                providerConfigRevision = 1L
            )
        )
        val repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = fakeSettings,
            apiClient = OpenAiApiClient(
                httpClient = OkHttpClient.Builder().addInterceptor(Interceptor {
                    calls.incrementAndGet()
                    error("Network must not be called after provider configuration changes")
                }).build()
            )
        )
        val id = insertAiMeeting()
        val original = dao.getMeetingByIdDirect(id)

        fakeSettings.update {
            it.copy(
                baseUrl = "https://provider-b.example/v1/",
                apiKey = "sk-provider-b",
                providerConfigRevision = 2L
            )
        }

        val transcribeResult = repository.transcribeMeeting(id, 0L, expectedConfigRevision = 1L)
        val protocolResult = repository.protocolizeMeeting(id, 0L, expectedConfigRevision = 1L)

        assertTrue(transcribeResult.exceptionOrNull() is ProviderConfigurationChangedException)
        assertTrue(protocolResult.exceptionOrNull() is ProviderConfigurationChangedException)
        assertEquals(0, calls.get())
        assertEquals(original, dao.getMeetingByIdDirect(id))
    }

    @Test
    fun changedConfigRevisionBetweenRetryAttemptsFailsWithoutUploadingToNewProvider() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val fakeSettings = FakeSettingsProvider(
            ApiSettings(
                baseUrl = "https://provider-a.example/v1/",
                apiKey = "sk-provider-a",
                transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
                transcriptionModel = "whisper-1",
                protocolModel = "gpt-4o-mini",
                transcriptionPrompt = "Transcribe",
                protocolPrompt = "Summarize",
                providerConfigRevision = 1L
            )
        )
        val repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = fakeSettings,
            apiClient = OpenAiApiClient(
                httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                    requestedUrls += chain.request().url.toString()
                    response(chain.request(), "Unavailable").newBuilder().code(503).message("Unavailable").build()
                }).build()
            )
        )
        val id = insertAiMeeting()

        // Initial attempt reaches provider A and hits a retryable 503.
        assertTrue(repository.transcribeMeeting(id, 0L, expectedConfigRevision = 1L).isFailure)
        assertEquals(listOf("https://provider-a.example/v1/audio/transcriptions"), requestedUrls)

        // User switches provider before the scheduled retry runs.
        fakeSettings.update {
            it.copy(
                baseUrl = "https://provider-b.example/v1/",
                apiKey = "sk-provider-b",
                providerConfigRevision = 2L
            )
        }

        // Retry still carries expectedConfigRevision = 1L and must abort before any upload.
        val retryResult = repository.transcribeMeeting(id, 0L, expectedConfigRevision = 1L)
        assertTrue(retryResult.exceptionOrNull() is ProviderConfigurationChangedException)
        assertEquals(listOf("https://provider-a.example/v1/audio/transcriptions"), requestedUrls)
    }

    @Test
    fun changedConfigRevisionMidSegmentationStopsWithoutUploadingRemainingSegmentsAndCleansUp() = runBlocking {
        val seg1 = File.createTempFile("seg-rev-1-", ".m4a", app.cacheDir).apply { writeText("part1") }
        val seg2 = File.createTempFile("seg-rev-2-", ".m4a", app.cacheDir).apply { writeText("part2") }
        var cleanedUp = false
        val segmenter = AudioSegmenter { _, _ ->
            Result.success(AudioSegmentationSession(listOf(seg1, seg2)) { cleanedUp = true })
        }
        val fakeSettings = FakeSettingsProvider(
            ApiSettings(
                baseUrl = "https://provider-a.example/v1/",
                apiKey = "sk-provider-a",
                transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
                transcriptionModel = "whisper-1",
                protocolModel = "gpt-4o-mini",
                transcriptionPrompt = "Transcribe",
                protocolPrompt = "Summarize",
                providerConfigRevision = 3L
            )
        )
        val calls = AtomicInteger()
        val repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = fakeSettings,
            apiClient = OpenAiApiClient(
                httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                    calls.incrementAndGet()
                    fakeSettings.update {
                        it.copy(
                            baseUrl = "https://provider-b.example/v1/",
                            apiKey = "sk-provider-b",
                            providerConfigRevision = 4L
                        )
                    }
                    response(chain.request(), "Segment 1 text")
                }).build()
            ),
            audioSegmenter = segmenter
        )
        val id = insertAiMeeting()
        val original = dao.getMeetingByIdDirect(id)

        val result = repository.transcribeMeeting(id, 0L, expectedConfigRevision = 3L)

        assertTrue(result.exceptionOrNull() is ProviderConfigurationChangedException)
        assertEquals(1, calls.get())
        assertTrue(cleanedUp)
        assertEquals(original, dao.getMeetingByIdDirect(id))
    }

    @Test
    fun missingConfigRevisionFailsClosedWithoutNetwork() = runBlocking {
        val calls = AtomicInteger()
        val fakeSettings = FakeSettingsProvider(
            ApiSettings(
                baseUrl = "https://provider-a.example/v1/",
                apiKey = "sk-provider-a",
                transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS,
                transcriptionModel = "whisper-1",
                protocolModel = "gpt-4o-mini",
                transcriptionPrompt = "Transcribe",
                protocolPrompt = "Summarize",
                providerConfigRevision = 0L
            )
        )
        val repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = fakeSettings,
            apiClient = OpenAiApiClient(
                httpClient = OkHttpClient.Builder().addInterceptor(Interceptor {
                    calls.incrementAndGet()
                    error("Network must not be called when revision is missing")
                }).build()
            )
        )
        val id = insertAiMeeting()
        val original = dao.getMeetingByIdDirect(id)

        val transcribeResult = repository.transcribeMeeting(
            id,
            0L,
            expectedConfigRevision = AiProcessingWorker.MISSING_CONFIG_REVISION
        )
        val protocolResult = repository.protocolizeMeeting(
            id,
            0L,
            expectedConfigRevision = AiProcessingWorker.MISSING_CONFIG_REVISION
        )

        assertTrue(transcribeResult.exceptionOrNull() is ProviderConfigurationChangedException)
        assertTrue(protocolResult.exceptionOrNull() is ProviderConfigurationChangedException)
        assertEquals(0, calls.get())
        assertEquals(original, dao.getMeetingByIdDirect(id))
    }

    @Test
    fun incompleteProtocolGenerationPreservesExistingProtocolAndGeneration() = runBlocking {
        val repository = configuredRepository(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"choices":[{"message":{"content":"Truncated protocol"},"finish_reason":"length"}]}"""
                        .toResponseBody("application/json".toMediaType())
                )
                .build()
        })
        val id = insertAiMeeting()
        val original = dao.getMeetingByIdDirect(id)!!

        val result = repository.protocolizeMeeting(id, 0L, currentRevision())

        assertTrue(result.exceptionOrNull() is IncompleteCompletionException)
        assertEquals(
            app.getString(R.string.msg_ai_incomplete_response, "length"),
            result.exceptionOrNull()?.message
        )
        assertEquals(original, dao.getMeetingByIdDirect(id))
    }

    @Test
    fun incompleteChatAudioTranscriptionPreservesExistingTranscriptAndProtocol() = runBlocking {
        settingsRepository.saveSettings(
            baseUrl = "https://openrouter.ai/api/v1/",
            apiKey = "test-key",
            transcriptionMode = TranscriptionMode.CHAT_COMPLETIONS,
            transcriptionModel = "google/gemini-2.5-flash",
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "Transcribe",
            protocolPrompt = "Summarize"
        )
        val repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = settingsRepository,
            apiClient = OpenAiApiClient(
                httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"choices":[{"message":{"content":"Different partial transcript"},"finish_reason":"length"}]}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }).build()
            )
        )
        val id = insertAiMeeting()
        val original = dao.getMeetingByIdDirect(id)!!

        val result = repository.transcribeMeeting(id, 0L, currentRevision())

        assertTrue(result.exceptionOrNull() is IncompleteCompletionException)
        assertEquals(
            app.getString(R.string.msg_ai_incomplete_response, "length"),
            result.exceptionOrNull()?.message
        )
        assertEquals(original, dao.getMeetingByIdDirect(id))
    }

    @Test
    fun incompleteSegmentedChatAudioCleansUpAndPreservesExistingResults() = runBlocking {
        val seg1 = File.createTempFile("seg-inc-1-", ".m4a", app.cacheDir).apply { writeText("part1") }
        val seg2 = File.createTempFile("seg-inc-2-", ".m4a", app.cacheDir).apply { writeText("part2") }
        var cleanedUp = false
        val segmenter = AudioSegmenter { _, _ ->
            Result.success(AudioSegmentationSession(listOf(seg1, seg2)) { cleanedUp = true })
        }
        settingsRepository.saveSettings(
            baseUrl = "https://openrouter.ai/api/v1/",
            apiKey = "test-key",
            transcriptionMode = TranscriptionMode.CHAT_COMPLETIONS,
            transcriptionModel = "google/gemini-2.5-flash",
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "Transcribe",
            protocolPrompt = "Summarize"
        )
        val calls = AtomicInteger()
        val repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = settingsRepository,
            apiClient = OpenAiApiClient(
                httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                    val body = if (calls.incrementAndGet() == 1) {
                        """{"choices":[{"message":{"content":"Segment 1 text"},"finish_reason":"stop"}]}"""
                    } else {
                        """{"choices":[{"message":{"content":"Segment 2 truncated"},"finish_reason":"length"}]}"""
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }).build()
            ),
            audioSegmenter = segmenter
        )
        val id = insertAiMeeting()
        val original = dao.getMeetingByIdDirect(id)!!

        val result = repository.transcribeMeeting(id, 0L, currentRevision())

        assertTrue(result.exceptionOrNull() is IncompleteCompletionException)
        assertTrue(cleanedUp)
        assertEquals(original, dao.getMeetingByIdDirect(id))
    }

    private suspend fun currentRevision(): Long =
        settingsRepository.settingsFlow.first().providerConfigRevision

    private class FakeSettingsProvider(initial: ApiSettings) : SettingsProvider {
        private val state = MutableStateFlow(initial)
        override val settingsFlow: Flow<ApiSettings> = state

        fun update(transform: (ApiSettings) -> ApiSettings) {
            state.value = transform(state.value)
        }
    }
    private suspend fun configuredRepository(
        interceptor: Interceptor,
        audioSegmenter: AudioSegmenter = AndroidAudioSegmenter { File(app.cacheDir, "audio_segments") }
    ): MeetingRepository {
        settingsRepository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "test-key",
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "Transcribe", protocolPrompt = "Summarize"
        )
        return MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = settingsRepository,
            apiClient = OpenAiApiClient(httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()),
            audioSegmenter = audioSegmenter
        )
    }

    private suspend fun insertAiMeeting(): Long {
        val audio = File.createTempFile("ai-test-", ".m4a", app.filesDir).apply { writeText("Audio") }
        return dao.insertMeeting(MeetingEntity(
            title = "Meeting", startTime = 1L, endTime = 2L, audioFilePath = audio.absolutePath,
            transcript = "Existing transcript", protocol = "Existing protocol"
        ))
    }

    private suspend fun generate(
        repository: MeetingRepository,
        id: Long,
        transcriptTask: Boolean,
        generation: Long,
        configRevision: Long? = null
    ): Result<AiGenerationOutcome> {
        val revision = configRevision ?: currentRevision()
        return if (transcriptTask) {
            repository.transcribeMeeting(id, generation, revision)
        } else {
            repository.protocolizeMeeting(id, generation, revision)
        }
    }

    private fun response(request: Request, text: String): Response {
        val body = if (request.url.encodedPath.endsWith("/audio/transcriptions")) {
            """{"text":"$text"}"""
        } else {
            """{"choices":[{"message":{"content":"$text"}}]}"""
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    private class FakeMeetingDao : MeetingDao {
        private val items = MutableStateFlow<Map<Long, MeetingEntity>>(emptyMap())
        private var nextId = 1L

        override fun getAllMeetings(): Flow<List<MeetingEntity>> =
            items.map { map -> map.values.filter { it.endTime > 0 }.sortedByDescending { it.startTime } }

        override suspend fun getUnfinishedMeetings(): List<MeetingEntity> =
            items.value.values.filter { it.endTime == 0L }

        override suspend fun getAllAudioPaths(): List<String> =
            items.value.values.map { it.audioFilePath }

        override fun getMeetingById(id: Long): Flow<MeetingEntity?> =
            items.map { it[id] }

        override suspend fun getMeetingByIdDirect(id: Long): MeetingEntity? =
            items.value[id]

        override suspend fun insertMeeting(meeting: MeetingEntity): Long {
            val id = if (meeting.id == 0L) nextId++ else meeting.id
            items.value = items.value + (id to meeting.copy(id = id))
            return id
        }

        override suspend fun updateTitle(id: Long, title: String) {
            items.value[id]?.let { items.value = items.value + (id to it.copy(title = title)) }
        }

        override suspend fun updateTranscript(id: Long, transcript: String, generation: Long): Int {
            val meeting = items.value[id]?.takeIf { it.transcriptGeneration == generation } ?: return 0
            val changed = meeting.transcript != transcript
            items.value = items.value + (id to meeting.copy(
                transcript = transcript,
                transcriptError = null,
                protocol = if (changed) null else meeting.protocol,
                protocolError = if (changed) null else meeting.protocolError,
                protocolGeneration = meeting.protocolGeneration + if (changed) 1 else 0
            ))
            return 1
        }

        override suspend fun updateProtocol(id: Long, protocol: String, generation: Long): Int {
            val meeting = items.value[id]?.takeIf { it.protocolGeneration == generation } ?: return 0
            items.value = items.value + (id to meeting.copy(
                protocol = protocol,
                protocolError = null
            ))
            return 1
        }

        override suspend fun updateTranscriptError(id: Long, error: String?, generation: Long): Int {
            val meeting = items.value[id]?.takeIf { it.transcriptGeneration == generation } ?: return 0
            items.value = items.value + (id to meeting.copy(transcriptError = error))
            return 1
        }

        override suspend fun updateProtocolError(id: Long, error: String?, generation: Long): Int {
            val meeting = items.value[id]?.takeIf { it.protocolGeneration == generation } ?: return 0
            items.value = items.value + (id to meeting.copy(protocolError = error))
            return 1
        }

        override suspend fun clearTranscript(id: Long) {
            items.value[id]?.let {
                items.value = items.value + (id to it.copy(
                    transcript = null,
                    transcriptError = null,
                    transcriptGeneration = it.transcriptGeneration + 1
                ))
            }
        }

        override suspend fun clearProtocol(id: Long) {
            items.value[id]?.let {
                items.value = items.value + (id to it.copy(
                    protocol = null,
                    protocolError = null,
                    protocolGeneration = it.protocolGeneration + 1
                ))
            }
        }

        override suspend fun finishMeeting(id: Long, endTime: Long, isDamaged: Boolean) {
            items.value[id]?.let { items.value = items.value + (id to it.copy(endTime = endTime, isDamaged = isDamaged)) }
        }

        override suspend fun deleteMeeting(id: Long) {
            items.value = items.value - id
        }
    }
}
