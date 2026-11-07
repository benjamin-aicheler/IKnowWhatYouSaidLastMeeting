package com.example.lastmeeting.repository

import android.content.Context
import com.example.lastmeeting.R
import com.example.lastmeeting.data.api.OpenAiApiClient
import com.example.lastmeeting.data.db.MeetingDao
import com.example.lastmeeting.data.db.MeetingEntity
import com.example.lastmeeting.data.preferences.ApiSettings
import com.example.lastmeeting.data.preferences.SettingsRepository
import com.example.lastmeeting.util.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingRepository(
    private val context: Context,
    private val meetingDao: MeetingDao,
    private val settingsRepository: SettingsRepository,
    private val apiClient: OpenAiApiClient = OpenAiApiClient(),
    private val deleteAudioFile: (File) -> Boolean = File::delete
) {

    val allMeetings: Flow<List<MeetingEntity>> = meetingDao.getAllMeetings()

    fun getMeetingById(id: Long): Flow<MeetingEntity?> = meetingDao.getMeetingById(id)

    suspend fun createMeeting(audioFile: File, startTime: Long, endTime: Long): Long {
        val dateString = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(startTime))
        val defaultTitle = context.getString(R.string.default_meeting_title, dateString)

        val entity = MeetingEntity(
            title = defaultTitle,
            startTime = startTime,
            endTime = endTime,
            audioFilePath = audioFile.absolutePath,
            transcript = null,
            protocol = null,
            createdAt = System.currentTimeMillis()
        )
        return meetingDao.insertMeeting(entity)
    }

    suspend fun updateTitle(id: Long, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isNotEmpty()) {
            meetingDao.updateTitle(id, trimmed)
        }
    }

    suspend fun transcribeMeeting(id: Long): Result<String> =
        generateForMeeting(id, ApiSettings::transcriptionPrompt, meetingDao::updateTranscript)

    suspend fun protocolizeMeeting(id: Long): Result<String> =
        generateForMeeting(id, ApiSettings::protocolPrompt, meetingDao::updateProtocol)

    private suspend fun generateForMeeting(
        id: Long,
        promptOf: (ApiSettings) -> String,
        store: suspend (Long, String) -> Unit
    ): Result<String> {
        val meeting = meetingDao.getMeetingByIdDirect(id)
            ?: return Result.failure(Exception("Meeting not found."))

        val audioFile = File(meeting.audioFilePath)
        if (!audioFile.exists()) {
            return Result.failure(Exception(context.getString(R.string.msg_audio_not_found)))
        }

        val settings = settingsRepository.settingsFlow.first()
        if (settings.apiKey.isBlank()) {
            return Result.failure(Exception(context.getString(R.string.msg_missing_api_key)))
        }

        val result = apiClient.generateFromAudio(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.model,
            prompt = promptOf(settings),
            audioFile = audioFile
        )

        return result.onSuccess { text -> store(id, text) }
    }

    suspend fun deleteTranscript(id: Long) {
        meetingDao.updateTranscript(id, null)
    }

    suspend fun deleteProtocol(id: Long) {
        meetingDao.updateProtocol(id, null)
    }

    suspend fun deleteMeeting(id: Long): Result<Unit> {
        val meeting = meetingDao.getMeetingByIdDirect(id)
            ?: return Result.failure(Exception("Meeting not found."))

        val audioFile = File(meeting.audioFilePath)
        val audioGone = !audioFile.exists() ||
            runCatching { deleteAudioFile(audioFile) }.getOrDefault(false)
        if (!audioGone) {
            // Without the row the recording would be orphaned on disk and unreachable in the
            // UI, so the delete is reported as failed and the meeting stays.
            return Result.failure(Exception(context.getString(R.string.msg_delete_failed)))
        }

        meetingDao.deleteMeeting(id)
        return Result.success(Unit)
    }

    suspend fun exportPdf(title: String, typeLabel: String, textContent: String): Result<String> =
        withContext(Dispatchers.IO) {
            PdfExporter.exportToPdf(context, title, typeLabel, textContent)
        }

    suspend fun exportPdfToCache(title: String, typeLabel: String, textContent: String): Result<File> =
        withContext(Dispatchers.IO) {
            PdfExporter.exportToCachePdf(context, title, typeLabel, textContent)
        }

    suspend fun exportAudio(title: String, audioFile: File): Result<String> =
        withContext(Dispatchers.IO) {
            PdfExporter.exportAudioToDownloads(context, title, audioFile)
        }
}
