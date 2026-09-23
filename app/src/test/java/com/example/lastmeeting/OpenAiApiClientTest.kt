package com.example.lastmeeting

import com.example.lastmeeting.data.api.OpenAiApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
}
