package com.example.lastmeeting

import com.example.lastmeeting.data.api.OpenAiApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

        val result = client.generateFromAudio(
            baseUrl = "https://api.openai.com/v1/",
            apiKey = "",
            model = "gpt-4o-audio-preview",
            prompt = "Transcribe this",
            audioFile = tempFile
        )

        tempFile.delete()
        assertTrue(result.isFailure)
    }

    @Test
    fun testEmptyFileFails() = runBlocking {
        val client = OpenAiApiClient()
        val emptyFile = File.createTempFile("test_empty", ".m4a")

        val result = client.generateFromAudio(
            baseUrl = "https://api.openai.com/v1/",
            apiKey = "sk-test",
            model = "gpt-4o-audio-preview",
            prompt = "Transcribe this",
            audioFile = emptyFile
        )

        emptyFile.delete()
        assertTrue(result.isFailure)
    }

    @Test
    fun testStreamingAudioRequestBodyValidJsonAndBase64() {
        val client = OpenAiApiClient()
        val tempFile = File.createTempFile("test_stream", ".m4a")
        // Create payload larger than 12 KB chunk size to test multi-chunk Base64 streaming
        val originalBytes = ByteArray(25_000) { (it * 31).toByte() }
        tempFile.writeBytes(originalBytes)

        try {
            val requestBody = client.createStreamingAudioRequestBody(
                model = "gpt-4o-audio-preview",
                prompt = "Line 1\n\"Quoted\" prompt",
                audioFile = tempFile
            )

            val buffer = Buffer()
            requestBody.writeTo(buffer)
            val jsonString = buffer.readUtf8()

            val root = Json.parseToJsonElement(jsonString).jsonObject
            assertEquals("gpt-4o-audio-preview", root["model"]?.jsonPrimitive?.content)

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
}
