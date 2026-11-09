package com.example.lastmeeting.repository

import android.app.Application
import com.example.lastmeeting.R
import com.example.lastmeeting.data.api.OpenAiApiClient
import com.example.lastmeeting.data.db.MeetingDao
import com.example.lastmeeting.data.db.MeetingEntity
import com.example.lastmeeting.data.preferences.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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

        val result = repository.protocolizeMeeting(id)

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

        val result = repository.protocolizeMeeting(id)

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

        override suspend fun updateTranscript(id: Long, transcript: String?) {
            items.value[id]?.let { items.value = items.value + (id to it.copy(transcript = transcript)) }
        }

        override suspend fun updateProtocol(id: Long, protocol: String?) {
            items.value[id]?.let { items.value = items.value + (id to it.copy(protocol = protocol)) }
        }

        override suspend fun finishMeeting(id: Long, endTime: Long) {
            items.value[id]?.let { items.value = items.value + (id to it.copy(endTime = endTime)) }
        }

        override suspend fun deleteMeeting(id: Long) {
            items.value = items.value - id
        }
    }
}
