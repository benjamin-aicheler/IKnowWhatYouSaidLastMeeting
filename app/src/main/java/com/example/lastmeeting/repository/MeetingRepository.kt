package com.example.lastmeeting.repository

import android.content.Context
import com.example.lastmeeting.R
import com.example.lastmeeting.data.api.OpenAiApiClient
import com.example.lastmeeting.data.db.MeetingDao
import com.example.lastmeeting.data.db.MeetingEntity
import com.example.lastmeeting.data.preferences.SettingsRepository
import com.example.lastmeeting.util.PdfExporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingRepository(
    private val context: Context,
    private val meetingDao: MeetingDao,
    private val settingsRepository: SettingsRepository,
    private val apiClient: OpenAiApiClient = OpenAiApiClient()
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
        meetingDao.updateTitle(id, newTitle.trim())
    }

    suspend fun transcribeMeeting(id: Long): Result<String> {
        val meeting = meetingDao.getMeetingByIdDirect(id)
            ?: return Result.failure(Exception("Meeting not found."))

        val audioFile = File(meeting.audioFilePath)
        if (!audioFile.exists()) {
            return Result.failure(Exception("Audio file not found on device."))
        }

        val settings = settingsRepository.settingsFlow.first()
        if (settings.apiKey.isBlank()) {
            return Result.failure(Exception(context.getString(R.string.msg_missing_api_key)))
        }

        val result = apiClient.generateFromAudio(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.model,
            prompt = settings.transcriptionPrompt,
            audioFile = audioFile
        )

        return result.onSuccess { transcriptText ->
            meetingDao.updateTranscript(id, transcriptText)
        }
    }

    suspend fun protocolizeMeeting(id: Long): Result<String> {
        val meeting = meetingDao.getMeetingByIdDirect(id)
            ?: return Result.failure(Exception("Meeting not found."))

        val audioFile = File(meeting.audioFilePath)
        if (!audioFile.exists()) {
            return Result.failure(Exception("Audio file not found on device."))
        }

        val settings = settingsRepository.settingsFlow.first()
        if (settings.apiKey.isBlank()) {
            return Result.failure(Exception(context.getString(R.string.msg_missing_api_key)))
        }

        val result = apiClient.generateFromAudio(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.model,
            prompt = settings.protocolPrompt,
            audioFile = audioFile
        )

        return result.onSuccess { protocolText ->
            meetingDao.updateProtocol(id, protocolText)
        }
    }

    suspend fun deleteTranscript(id: Long) {
        meetingDao.updateTranscript(id, null)
    }

    suspend fun deleteProtocol(id: Long) {
        meetingDao.updateProtocol(id, null)
    }

    suspend fun deleteMeeting(id: Long) {
        val meeting = meetingDao.getMeetingByIdDirect(id)
        if (meeting != null) {
            val audioFile = File(meeting.audioFilePath)
            if (audioFile.exists()) {
                try {
                    audioFile.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            meetingDao.deleteMeeting(id)
        }
    }

    fun exportPdf(title: String, typeLabel: String, textContent: String): Result<String> {
        return PdfExporter.exportToPdf(context, title, typeLabel, textContent)
    }
}
