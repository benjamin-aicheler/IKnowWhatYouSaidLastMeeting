package com.example.lastmeeting

import com.example.lastmeeting.data.api.OpenAiApiClient
import com.example.lastmeeting.data.preferences.SettingsRepository
import com.example.lastmeeting.service.AndroidAudioRecorder
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class OpenAiApiClientTest {

    @Test
    fun testMissingApiKeyFails() = runBlocking {
        val client = OpenAiApiClient()
        val tempFile = File.createTempFile("test", ".m4a")
        tempFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        try {
            val audioResult = client.transcribeAudio(
                baseUrl = "https://api.openai.com/v1/",
                apiKey = "",
                model = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
                prompt = "Transcribe this",
                audioFile = tempFile
            )
            assertTrue(audioResult.isFailure)

            val textResult = client.generateFromText(
                baseUrl = "https://api.openai.com/v1/",
                apiKey = "   ",
                model = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
                systemPrompt = "Create protocol",
                userText = "Meeting transcript"
            )
            assertTrue(textResult.isFailure)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testEmptyFileAndBlankTranscriptFail() = runBlocking {
        val client = OpenAiApiClient()
        val emptyFile = File.createTempFile("test_empty", ".m4a")

        try {
            val audioResult = client.transcribeAudio(
                baseUrl = "https://api.openai.com/v1/",
                apiKey = "sk-test",
                model = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
                prompt = "Transcribe this",
                audioFile = emptyFile
            )
            assertTrue(audioResult.isFailure)

            val textResult = client.generateFromText(
                baseUrl = "https://api.openai.com/v1/",
                apiKey = "sk-test",
                model = SettingsRepository.DEFAULT_PROTOCOL_MODEL,
                systemPrompt = "Create protocol",
                userText = "   "
            )
            assertTrue(textResult.isFailure)
        } finally {
            emptyFile.delete()
        }
    }

    @Test
    fun testModelResolutionDefaultsAndLegacyMigration() {
        assertEquals(
            SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            SettingsRepository.resolveModel(
                stored = null,
                legacyStored = null,
                defaultModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL
            )
        )
        assertEquals(
            SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            SettingsRepository.resolveModel(
                stored = "   ",
                legacyStored = null,
                defaultModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL
            )
        )
        assertEquals(
            SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
            SettingsRepository.resolveModel(
                stored = null,
                legacyStored = "gpt-4o-audio-preview",
                defaultModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL
            )
        )
        assertEquals(
            SettingsRepository.DEFAULT_PROTOCOL_MODEL,
            SettingsRepository.resolveModel(
                stored = "gpt-4o-audio-preview",
                legacyStored = null,
                defaultModel = SettingsRepository.DEFAULT_PROTOCOL_MODEL
            )
        )
        assertEquals(
            "google/gemini-2.5-flash",
            SettingsRepository.resolveModel(
                stored = null,
                legacyStored = "google/gemini-2.5-flash",
                defaultModel = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL
            )
        )
    }

    @Test
    fun testOneHourRecordingBitrateStaysBelow25MbLimit() {
        val oneHourBytes = (AndroidAudioRecorder.AUDIO_BIT_RATE.toLong() * 3600L) / 8L
        val openAiUploadLimitBytes = 25L * 1024L * 1024L
        assertTrue(
            "Expected 1h audio ($oneHourBytes bytes) to stay well below 25 MB ($openAiUploadLimitBytes bytes)",
            oneHourBytes < openAiUploadLimitBytes
        )
    }

    @Test
    fun testOpenAiTranscriptionUsesMultipartAudioTranscriptionsEndpoint() = runBlocking {
        var capturedRequest: Request? = null
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                capturedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"text":"  Verbatim meeting transcript  "}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val client = OpenAiApiClient(httpClient = httpClient)
        val tempFile = File.createTempFile("meeting_openai", ".m4a")
        val audioBytes = byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70)
        tempFile.writeBytes(audioBytes)

        try {
            val result = client.transcribeAudio(
                baseUrl = "https://api.openai.com/v1/",
                apiKey = "sk-test",
                model = "", // blank falls back to DEFAULT_TRANSCRIPTION_MODEL
                prompt = "Transcribe accurately",
                audioFile = tempFile
            )

            assertTrue(result.isSuccess)
            assertEquals("Verbatim meeting transcript", result.getOrNull())

            val request = capturedRequest
            assertNotNull(request)
            assertEquals("https://api.openai.com/v1/audio/transcriptions", request!!.url.toString())

            val multipartBody = request.body as MultipartBody
            assertEquals(3, multipartBody.size)

            val filePartBuffer = Buffer()
            multipartBody.part(0).body.writeTo(filePartBuffer)
            assertArrayEquals(audioBytes, filePartBuffer.readByteArray())
            assertEquals("audio/mp4", multipartBody.part(0).body.contentType().toString())

            val modelPartBuffer = Buffer()
            multipartBody.part(1).body.writeTo(modelPartBuffer)
            assertEquals(SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL, modelPartBuffer.readUtf8())

            val promptPartBuffer = Buffer()
            multipartBody.part(2).body.writeTo(promptPartBuffer)
            assertEquals("Transcribe accurately", promptPartBuffer.readUtf8())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testCustomEndpointTranscriptionPreservesChatCompletionsWithM4a() = runBlocking {
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
                        """{"choices":[{"message":{"content":"Custom endpoint transcript"}}]}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            })
            .build()

        val client = OpenAiApiClient(httpClient = httpClient)
        val tempFile = File.createTempFile("meeting_custom", ".m4a")
        val originalBytes = ByteArray(25_000) { (it * 31).toByte() }
        tempFile.writeBytes(originalBytes)

        try {
            val result = client.transcribeAudio(
                baseUrl = "https://openrouter.ai/api/v1",
                apiKey = "sk-or-test",
                model = "google/gemini-2.5-flash",
                prompt = "Line 1\n\"Quoted\" prompt",
                audioFile = tempFile
            )

            assertTrue(result.isSuccess)
            assertEquals("Custom endpoint transcript", result.getOrNull())

            val request = capturedRequest
            assertNotNull(request)
            assertEquals("https://openrouter.ai/api/v1/chat/completions", request!!.url.toString())

            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val root = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals("google/gemini-2.5-flash", root["model"]?.jsonPrimitive?.content)

            val messages = root["messages"]!!.jsonArray
            val content = messages[0].jsonObject["content"]!!.jsonArray
            assertEquals("Line 1\n\"Quoted\" prompt", content[0].jsonObject["text"]?.jsonPrimitive?.content)

            val inputAudio = content[1].jsonObject["input_audio"]!!.jsonObject
            assertEquals("m4a", inputAudio["format"]?.jsonPrimitive?.content)

            val encodedData = inputAudio["data"]!!.jsonPrimitive.content
            val decodedBytes = Base64.getDecoder().decode(encodedData)
            assertArrayEquals(originalBytes, decodedBytes)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testProtocolGenerationSendsTextOnlyToChatCompletions() = runBlocking {
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
                        """{"choices":[{"message":{"content":"## Executive Summary\nDecisions made."}}]}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            })
            .build()

        val client = OpenAiApiClient(httpClient = httpClient)
        val result = client.generateFromText(
            baseUrl = "https://api.openai.com/v1/",
            apiKey = "sk-test",
            model = "", // blank falls back to DEFAULT_PROTOCOL_MODEL
            systemPrompt = "You are an executive assistant.",
            userText = "Alice: Let's ship v1.0 on Friday.\nBob: Agreed."
        )

        assertTrue(result.isSuccess)
        assertEquals("## Executive Summary\nDecisions made.", result.getOrNull())

        val request = capturedRequest
        assertNotNull(request)
        assertEquals("https://api.openai.com/v1/chat/completions", request!!.url.toString())

        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val jsonBody = buffer.readUtf8()
        assertFalse(jsonBody.contains("input_audio"))

        val root = Json.parseToJsonElement(jsonBody).jsonObject
        assertEquals(SettingsRepository.DEFAULT_PROTOCOL_MODEL, root["model"]?.jsonPrimitive?.content)

        val messages = root["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("You are an executive assistant.", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("user", messages[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(
            "Alice: Let's ship v1.0 on Friday.\nBob: Agreed.",
            messages[1].jsonObject["content"]?.jsonPrimitive?.content
        )
    }
}
