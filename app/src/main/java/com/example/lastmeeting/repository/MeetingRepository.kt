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
    private val deleteAudioFile: (File) -> Boolean = File::delete,
    private val recordingsDir: File = File(context.filesDir, "recordings")
) {

    val allMeetings: Flow<List<MeetingEntity>> = meetingDao.getAllMeetings()

    fun getMeetingById(id: Long): Flow<MeetingEntity?> = meetingDao.getMeetingById(id)

    suspend fun createMeeting(audioFile: File, startTime: Long, endTime: Long): Long =
        meetingDao.insertMeeting(
            MeetingEntity(
                title = defaultTitle(startTime),
                startTime = startTime,
                endTime = endTime,
                audioFilePath = audioFile.absolutePath,
                transcript = null,
                protocol = null,
                createdAt = System.currentTimeMillis()
            )
        )

    /**
     * Inserts the row as soon as the recorder runs, so a recording interrupted by process death
     * keeps a meeting instead of leaving a file nothing points at. [MeetingEntity.endTime] stays
     * 0 until [finishMeeting] closes it, which keeps it out of the meeting list meanwhile.
     */
    suspend fun startMeeting(audioFile: File, startTime: Long): Long =
        meetingDao.insertMeeting(
            MeetingEntity(
                title = defaultTitle(startTime),
                startTime = startTime,
                endTime = 0L,
                audioFilePath = audioFile.absolutePath,
                transcript = null,
                protocol = null,
                createdAt = System.currentTimeMillis()
            )
        )

    suspend fun finishMeeting(id: Long, endTime: Long) {
        meetingDao.finishMeeting(id, endTime)
    }

    /** Drops the row of a recording that never produced playable audio. */
    suspend fun discardMeeting(id: Long) {
        meetingDao.deleteMeeting(id)
    }

    /**
     * Repairs what a killed process leaves behind: unfinished rows are closed with the audio
     * file's last modification time, or dropped when the audio is gone; recordings that never got
     * a row are adopted so they are visible and deletable instead of sitting on disk unseen.
     */
    suspend fun reconcileRecordings(): Int {
        var changes = 0

        meetingDao.getUnfinishedMeetings().forEach { meeting ->
            val audioFile = File(meeting.audioFilePath)
            if (audioFile.exists()) {
                meetingDao.finishMeeting(meeting.id, maxOf(audioFile.lastModified(), meeting.startTime))
            } else {
                meetingDao.deleteMeeting(meeting.id)
            }
            changes++
        }

        val knownPaths = meetingDao.getAllAudioPaths().toSet()
        val orphans = recordingsDir.listFiles { file -> file.isFile && file.extension == "m4a" }.orEmpty()
        orphans.filterNot { it.absolutePath in knownPaths }.forEach { orphan ->
            val timestamp = maxOf(orphan.lastModified(), 0L)
            meetingDao.insertMeeting(
                MeetingEntity(
                    title = defaultTitle(timestamp),
                    startTime = timestamp,
                    endTime = timestamp,
                    audioFilePath = orphan.absolutePath,
                    transcript = null,
                    protocol = null,
                    createdAt = System.currentTimeMillis()
                )
            )
            changes++
        }

        return changes
    }

    private fun defaultTitle(startTime: Long): String {
        val dateString = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(startTime))
        return context.getString(R.string.default_meeting_title, dateString)
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
