package com.example.lastmeeting.repository

import android.app.Application
import com.example.lastmeeting.R
import com.example.lastmeeting.data.api.OpenAiApiClient
import com.example.lastmeeting.data.db.MeetingDao
import com.example.lastmeeting.data.db.MeetingEntity
import com.example.lastmeeting.data.preferences.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
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

        val result = repository.protocolizeMeeting(id, 0L)

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

        val result = repository.protocolizeMeeting(id, 0L)

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

    private suspend fun configuredRepository(interceptor: Interceptor): MeetingRepository {
        settingsRepository.saveSettings(
            baseUrl = SettingsRepository.DEFAULT_BASE_URL,
            apiKey = "test-key",
            transcriptionModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            protocolModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            transcriptionPrompt = "Transcribe", protocolPrompt = "Summarize"
        )
        return MeetingRepository(app, dao, settingsRepository,
            apiClient = OpenAiApiClient(httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()))
    }

    private suspend fun insertAiMeeting(): Long {
        val audio = File.createTempFile("ai-test-", ".m4a", app.filesDir).apply { writeText("Audio") }
        return dao.insertMeeting(MeetingEntity(
            title = "Meeting", startTime = 1L, endTime = 2L, audioFilePath = audio.absolutePath,
            transcript = "Existing transcript", protocol = "Existing protocol"
        ))
    }

    private suspend fun generate(repository: MeetingRepository, id: Long, transcriptTask: Boolean, generation: Long) =
        if (transcriptTask) repository.transcribeMeeting(id, generation) else repository.protocolizeMeeting(id, generation)

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
            items.value = items.value + (id to meeting.copy(transcript = transcript))
            return 1
        }

        override suspend fun updateProtocol(id: Long, protocol: String, generation: Long): Int {
            val meeting = items.value[id]?.takeIf { it.protocolGeneration == generation } ?: return 0
            items.value = items.value + (id to meeting.copy(protocol = protocol))
            return 1
        }

        override suspend fun clearTranscript(id: Long) {
            items.value[id]?.let {
                items.value = items.value + (id to it.copy(transcript = null, transcriptGeneration = it.transcriptGeneration + 1))
            }
        }

        override suspend fun clearProtocol(id: Long) {
            items.value[id]?.let {
                items.value = items.value + (id to it.copy(protocol = null, protocolGeneration = it.protocolGeneration + 1))
            }
        }

        override suspend fun finishMeeting(id: Long, endTime: Long) {
            items.value[id]?.let { items.value = items.value + (id to it.copy(endTime = endTime)) }
        }

        override suspend fun deleteMeeting(id: Long) {
            items.value = items.value - id
        }
    }
}
