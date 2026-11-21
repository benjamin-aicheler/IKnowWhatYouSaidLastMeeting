package io.github.benjamin_aicheler.lastmeeting

import io.github.benjamin_aicheler.lastmeeting.data.api.ApiHttpException
import io.github.benjamin_aicheler.lastmeeting.data.api.AudioFileTooLargeException
import io.github.benjamin_aicheler.lastmeeting.data.api.BaseUrlValidationResult
import io.github.benjamin_aicheler.lastmeeting.data.api.IncompleteCompletionException
import io.github.benjamin_aicheler.lastmeeting.data.api.OpenAiApiClient
import io.github.benjamin_aicheler.lastmeeting.data.api.UnexpectedModelsResponseException
import io.github.benjamin_aicheler.lastmeeting.data.api.UnsupportedModelsEndpointException
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsRepository
import io.github.benjamin_aicheler.lastmeeting.data.preferences.TranscriptionMode
import io.github.benjamin_aicheler.lastmeeting.service.AndroidAudioRecorder
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
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun testAudioFileAbove25MbLimitFailsWithoutCallingNetwork() = runBlocking {
        val calls = AtomicInteger(0)
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor {
                calls.incrementAndGet()
                error("Network should not be called for oversized files")
            })
            .build()
        val client = OpenAiApiClient(httpClient = httpClient)
        val tempFile = File.createTempFile("meeting_oversized", ".m4a")
        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.setLength(OpenAiApiClient.MAX_AUDIO_UPLOAD_BYTES + 1)
        }

        try {
            val result = client.transcribeAudio(
                baseUrl = "https://api.openai.com/v1/",
                apiKey = "sk-test",
                model = SettingsRepository.DEFAULT_TRANSCRIPTION_MODEL,
                prompt = "Transcribe this",
                audioFile = tempFile
            )
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is AudioFileTooLargeException)
            assertEquals(0, calls.get())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testExplicitAudioTranscriptionsModeRoutesCustomHostToMultipartEndpoint() = runBlocking {
        var capturedRequest: Request? = null
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                capturedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"text":"Groq Whisper transcript"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val client = OpenAiApiClient(httpClient = httpClient)
        val tempFile = File.createTempFile("meeting_groq", ".m4a")
        tempFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        try {
            val result = client.transcribeAudio(
                baseUrl = "https://api.groq.com/openai/v1",
                apiKey = "gsk-test",
                model = "whisper-large-v3-turbo",
                prompt = "Meeting hints",
                audioFile = tempFile,
                transcriptionMode = TranscriptionMode.AUDIO_TRANSCRIPTIONS
            )

            val request = checkNotNull(capturedRequest)
            assertTrue(result.isSuccess)
            assertEquals("Groq Whisper transcript", result.getOrNull())
            assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", request.url.toString())
            assertTrue(request.body is MultipartBody)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testExplicitChatCompletionsModeRoutesOpenAiHostToChatAudioEndpoint() = runBlocking {
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
                        """{"choices":[{"message":{"content":"Chat audio transcript"}}]}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            })
            .build()

        val client = OpenAiApiClient(httpClient = httpClient)
        val tempFile = File.createTempFile("meeting_chat_override", ".m4a")
        tempFile.writeBytes(byteArrayOf(5, 6, 7, 8))

        try {
            val result = client.transcribeAudio(
                baseUrl = "https://api.openai.com/v1/",
                apiKey = "sk-test",
                model = "gpt-4o-audio-preview",
                prompt = "Transcribe",
                audioFile = tempFile,
                transcriptionMode = TranscriptionMode.CHAT_COMPLETIONS
            )

            val request = checkNotNull(capturedRequest)
            assertTrue(result.isSuccess)
            assertEquals("Chat audio transcript", result.getOrNull())
            assertEquals("https://api.openai.com/v1/chat/completions", request.url.toString())
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            assertTrue(buffer.readUtf8().contains("input_audio"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testTranscriptionModeFromStorageDefaultsToAuto() {
        assertEquals(TranscriptionMode.AUTO, TranscriptionMode.fromStorage(null))
        assertEquals(TranscriptionMode.AUTO, TranscriptionMode.fromStorage("   "))
        assertEquals(TranscriptionMode.AUTO, TranscriptionMode.fromStorage("unknown_mode"))
        assertEquals(TranscriptionMode.AUDIO_TRANSCRIPTIONS, TranscriptionMode.fromStorage("audio_transcriptions"))
        assertEquals(TranscriptionMode.CHAT_COMPLETIONS, TranscriptionMode.fromStorage("CHAT_COMPLETIONS"))
    }

    @Test
    fun testValidateBaseUrlHandlesValidHttpHttpsAndRejectsInvalidUrls() {
        val defaultValid = OpenAiApiClient.validateBaseUrl("   ")
        assertEquals(
            BaseUrlValidationResult.Valid(SettingsRepository.DEFAULT_BASE_URL, isPlainHttp = false),
            defaultValid
        )

        val customHttps = OpenAiApiClient.validateBaseUrl("https://api.groq.com/openai/v1")
        assertEquals(
            BaseUrlValidationResult.Valid("https://api.groq.com/openai/v1/", isPlainHttp = false),
            customHttps
        )

        val localHttp = OpenAiApiClient.validateBaseUrl("http://localhost:8080/v1")
        assertEquals(
            BaseUrlValidationResult.Valid("http://localhost:8080/v1/", isPlainHttp = true),
            localHttp
        )

        assertEquals(BaseUrlValidationResult.InvalidSchemeOrHost, OpenAiApiClient.validateBaseUrl("ftp://example.com/v1"))
        assertEquals(BaseUrlValidationResult.InvalidSchemeOrHost, OpenAiApiClient.validateBaseUrl("not-a-url"))
        assertEquals(
            BaseUrlValidationResult.ContainsCredentialsOrQuery,
            OpenAiApiClient.validateBaseUrl("https://user:pass@example.com/v1")
        )
        assertEquals(
            BaseUrlValidationResult.ContainsCredentialsOrQuery,
            OpenAiApiClient.validateBaseUrl("https://example.com/v1?key=123")
        )
        assertEquals(
            BaseUrlValidationResult.ContainsCredentialsOrQuery,
            OpenAiApiClient.validateBaseUrl("https://example.com/v1#frag")
        )
    }

    @Test
    fun testConnectionProbesModelsEndpointAndReportsAuthFailure() = runBlocking {
        var capturedRequest: Request? = null
        var responseCode = 200
        var responseBody = """{"data":[]}"""
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                capturedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message(if (responseCode == 200) "OK" else "Unauthorized")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val client = OpenAiApiClient(httpClient = httpClient)
        val okResult = client.testConnection("https://api.example.com/v1", "sk-valid")
        assertTrue(okResult.isSuccess)
        assertEquals("GET", capturedRequest?.method)
        assertEquals("https://api.example.com/v1/models", capturedRequest?.url.toString())
        assertEquals("Bearer sk-valid", capturedRequest?.header("Authorization"))

        responseCode = 401
        responseBody = """{"error":{"message":"Invalid API key"}}"""
        val failResult = client.testConnection("https://api.example.com/v1/", "sk-bad")
        assertTrue(failResult.isFailure)
        val exception = failResult.exceptionOrNull() as? ApiHttpException
        assertNotNull(exception)
        assertEquals(401, exception!!.code)
        assertEquals("Invalid API key", exception.message)
    }

    @Test
    fun testConnectionValidatesModelListAndDistinguishesFailuresWithoutFallbackRequests() = runBlocking {
        suspend fun runWithSyntheticResponse(
            code: Int,
            contentType: String,
            body: String
        ): Pair<Result<Unit>, Int> {
            val calls = AtomicInteger()
            val httpClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    calls.incrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code in 200..299) "OK" else "Error")
                        .body(body.toResponseBody(contentType.toMediaType()))
                        .build()
                })
                .build()
            val result = OpenAiApiClient(httpClient = httpClient)
                .testConnection("https://api.example.com/v1/", "sk-test")
            return result to calls.get()
        }

        for (validPayload in listOf(
            """{"object":"list","data":[{"id":"gpt-4o-mini"}]}""",
            """{"data":[{"id":"google/gemini-2.5-flash"}]}""",
            """{"data":[]}"""
        )) {
            val (result, callCount) = runWithSyntheticResponse(200, "application/json", validPayload)
            assertTrue("Expected success for $validPayload", result.isSuccess)
            assertEquals(1, callCount)
        }

        val (htmlResult, htmlCalls) = runWithSyntheticResponse(
            200,
            "text/html",
            "<!doctype html><html><body>Welcome</body></html>"
        )
        assertTrue(htmlResult.exceptionOrNull() is UnexpectedModelsResponseException)
        assertEquals(1, htmlCalls)

        for (unexpectedJson in listOf(
            """{"status":"ok"}""",
            """{"data":null}""",
            """{"data":[{}]}""",
            """{"data":[{"id":123}]}""",
            """{"data":[{"id":"   "}]}""",
            """[]""",
            ""
        )) {
            val (result, callCount) = runWithSyntheticResponse(200, "application/json", unexpectedJson)
            assertTrue(
                "Expected UnexpectedModelsResponseException for '$unexpectedJson'",
                result.exceptionOrNull() is UnexpectedModelsResponseException
            )
            assertEquals(1, callCount)
        }

        for (unsupportedCode in listOf(404, 405, 501)) {
            val (result, callCount) = runWithSyntheticResponse(
                unsupportedCode,
                "application/json",
                """{"error":{"message":"Not found"}}"""
            )
            val err = result.exceptionOrNull() as? UnsupportedModelsEndpointException
            assertNotNull("Expected UnsupportedModelsEndpointException for $unsupportedCode", err)
            assertEquals(unsupportedCode, err!!.code)
            assertEquals(1, callCount)
        }

        for (authCode in listOf(401, 403)) {
            val (result, callCount) = runWithSyntheticResponse(
                authCode,
                "application/json",
                """{"error":{"message":"Unauthorized"}}"""
            )
            val err = result.exceptionOrNull() as? ApiHttpException
            assertNotNull(err)
            assertFalse(err is UnsupportedModelsEndpointException)
            assertEquals(authCode, err!!.code)
            assertEquals(1, callCount)
        }

        val ioCalls = AtomicInteger()
        val ioClient = OpenAiApiClient(
            httpClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor {
                    ioCalls.incrementAndGet()
                    throw IOException("Connection refused")
                })
                .build()
        )
        val ioResult = ioClient.testConnection("https://api.example.com/v1/", "sk-test")
        val ioErr = ioResult.exceptionOrNull()
        assertTrue(ioErr is IOException)
        assertFalse(ioErr is ApiHttpException)
        assertFalse(ioErr is UnexpectedModelsResponseException)
        assertEquals(1, ioCalls.get())
    }

    @Test
    fun testChatCompletionAcceptsCompleteAndOmittedFinishReasons() = runBlocking {
        val tempFile = File.createTempFile("meeting_complete_reason", ".m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        try {
            for (jsonPayload in listOf(
                """{"choices":[{"message":{"content":"Complete output"},"finish_reason":"stop"}]}""",
                """{"choices":[{"message":{"content":"Complete output"},"finish_reason":"STOP"}]}""",
                """{"choices":[{"message":{"content":"Complete output"},"finish_reason":"end_turn"}]}""",
                """{"choices":[{"message":{"content":"Complete output"},"finish_reason":null}]}""",
                """{"choices":[{"message":{"content":"Complete output"}}]}"""
            )) {
                val client = OpenAiApiClient(
                    httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(jsonPayload.toResponseBody("application/json".toMediaType()))
                            .build()
                    }).build()
                )
                val protocolResult = client.generateFromText(
                    baseUrl = "https://api.example.com/v1/",
                    apiKey = "sk-test",
                    model = "gpt-4o-mini",
                    systemPrompt = "Summarize",
                    userText = "Meeting transcript"
                )
                assertEquals("Complete output", protocolResult.getOrNull())

                val audioResult = client.transcribeAudio(
                    baseUrl = "https://openrouter.ai/api/v1/",
                    apiKey = "sk-test",
                    model = "google/gemini-2.5-flash",
                    prompt = "Transcribe",
                    audioFile = tempFile,
                    transcriptionMode = TranscriptionMode.CHAT_COMPLETIONS
                )
                assertEquals("Complete output", audioResult.getOrNull())
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testChatCompletionRejectsIncompleteFinishReasons() = runBlocking {
        val tempFile = File.createTempFile("meeting_incomplete_reason", ".m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        try {
            for (reason in listOf("length", "content_filter", "max_tokens")) {
                val client = OpenAiApiClient(
                    httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                """{"choices":[{"message":{"content":"Partial truncated text"},"finish_reason":"$reason"}]}"""
                                    .toResponseBody("application/json".toMediaType())
                            )
                            .build()
                    }).build()
                )

                val protocolResult = client.generateFromText(
                    baseUrl = "https://api.example.com/v1/",
                    apiKey = "sk-test",
                    model = "gpt-4o-mini",
                    systemPrompt = "Summarize",
                    userText = "Meeting transcript"
                )
                val protocolError = protocolResult.exceptionOrNull() as? IncompleteCompletionException
                assertNotNull("Expected IncompleteCompletionException for $reason", protocolError)
                assertEquals(reason, protocolError!!.finishReason)

                val audioResult = client.transcribeAudio(
                    baseUrl = "https://openrouter.ai/api/v1/",
                    apiKey = "sk-test",
                    model = "google/gemini-2.5-flash",
                    prompt = "Transcribe",
                    audioFile = tempFile,
                    transcriptionMode = TranscriptionMode.CHAT_COMPLETIONS
                )
                val audioError = audioResult.exceptionOrNull() as? IncompleteCompletionException
                assertNotNull("Expected IncompleteCompletionException for chat audio $reason", audioError)
                assertEquals(reason, audioError!!.finishReason)
            }
        } finally {
            tempFile.delete()
        }
    }
}
