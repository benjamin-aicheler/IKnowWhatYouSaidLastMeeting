package com.example.lastmeeting.repository

import android.content.Context
import com.example.lastmeeting.R
import com.example.lastmeeting.data.api.OpenAiApiClient
import com.example.lastmeeting.data.db.MeetingDao
import com.example.lastmeeting.data.db.MeetingEntity
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

    private val recordingLock = Any()
    private val activeRecordingPaths = mutableSetOf<String>()
    private val activeRecordingIdsToPaths = mutableMapOf<Long, String>()
    private val activeReconciliations = mutableListOf<MutableSet<String>>()

    val allMeetings: Flow<List<MeetingEntity>> = meetingDao.getAllMeetings()

    fun getMeetingById(id: Long): Flow<MeetingEntity?> = meetingDao.getMeetingById(id)

    /**
     * Creates a unique `.m4a` output file and marks its path as live atomically so a concurrent
     * [reconcileRecordings] scan never sees the file on disk as an unowned orphan.
     */
    fun createRecordingFile(recordingsDir: File = this.recordingsDir, now: Date = Date()): File =
        synchronized(recordingLock) {
            check(recordingsDir.isDirectory || recordingsDir.mkdirs()) { "Cannot create recordings directory" }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now)
            // A failed retry or rapid stop/start must never overwrite another recording.
            val timestampFile = File(recordingsDir, "meeting_$timeStamp.m4a")
            val outputFile = if (timestampFile.createNewFile()) {
                timestampFile
            } else {
                File.createTempFile("meeting_${timeStamp}_", ".m4a", recordingsDir)
            }
            markRecordingLiveLocked(outputFile.absolutePath)
            outputFile
        }

    /**
     * Marks [audioFile] as belonging to an active recording session in this process so
     * [reconcileRecordings] neither closes its unfinished row nor adopts the file as an orphan.
     */
    fun registerLiveRecording(audioFile: File) {
        synchronized(recordingLock) {
            markRecordingLiveLocked(audioFile.absolutePath)
        }
    }

    fun unregisterLiveRecording(audioFile: File) {
        synchronized(recordingLock) {
            val path = audioFile.absolutePath
            activeRecordingPaths.remove(path)
            val iterator = activeRecordingIdsToPaths.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value == path) {
                    iterator.remove()
                }
            }
        }
    }

    private fun markRecordingLiveLocked(path: String) {
        activeRecordingPaths.add(path)
        activeReconciliations.forEach { it.add(path) }
    }

    suspend fun createMeeting(audioFile: File, startTime: Long, endTime: Long): Long {
        val path = audioFile.absolutePath
        synchronized(recordingLock) {
            activeReconciliations.forEach { it.add(path) }
        }
        return try {
            meetingDao.insertMeeting(
                MeetingEntity(
                    title = defaultTitle(startTime),
                    startTime = startTime,
                    endTime = endTime,
                    audioFilePath = path,
                    transcript = null,
                    protocol = null,
                    createdAt = System.currentTimeMillis()
                )
            )
        } finally {
            synchronized(recordingLock) {
                activeReconciliations.forEach { it.add(path) }
                activeRecordingPaths.remove(path)
                val iterator = activeRecordingIdsToPaths.entries.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().value == path) {
                        iterator.remove()
                    }
                }
            }
        }
    }

    /**
     * Inserts the row as soon as the recorder runs, so a recording interrupted by process death
     * keeps a meeting instead of leaving a file nothing points at. [MeetingEntity.endTime] stays
     * 0 until [finishMeeting] closes it, which keeps it out of the meeting list meanwhile.
     */
    suspend fun startMeeting(audioFile: File, startTime: Long): Long {
        val path = audioFile.absolutePath
        synchronized(recordingLock) {
            activeReconciliations.forEach { it.add(path) }
        }
        val id = meetingDao.insertMeeting(
            MeetingEntity(
                title = defaultTitle(startTime),
                startTime = startTime,
                endTime = 0L,
                audioFilePath = path,
                transcript = null,
                protocol = null,
                createdAt = System.currentTimeMillis()
            )
        )
        synchronized(recordingLock) {
            activeReconciliations.forEach { it.add(path) }
            if (path in activeRecordingPaths) {
                activeRecordingIdsToPaths[id] = path
            }
        }
        return id
    }

    suspend fun finishMeeting(id: Long, endTime: Long) {
        try {
            meetingDao.finishMeeting(id, endTime)
        } finally {
            synchronized(recordingLock) {
                activeRecordingIdsToPaths.remove(id)?.let { path ->
                    activeRecordingPaths.remove(path)
                }
            }
        }
    }

    /** Drops the row of a recording that never produced playable audio. */
    suspend fun discardMeeting(id: Long) {
        try {
            meetingDao.deleteMeeting(id)
        } finally {
            synchronized(recordingLock) {
                activeRecordingIdsToPaths.remove(id)?.let { path ->
                    activeRecordingPaths.remove(path)
                }
            }
        }
    }

    /**
     * Repairs what a killed process leaves behind: unfinished rows are closed with the audio
     * file's last modification time, or dropped when the audio is gone; recordings that never got
     * a row are adopted so they are visible and deletable instead of sitting on disk unseen.
     *
     * Recordings belonging to an active session in this process are skipped so a concurrent
     * startup scan never publishes a running recording or adopts its file a second time.
     */
    suspend fun reconcileRecordings(): Int {
        val skippedPaths = synchronized(recordingLock) {
            activeRecordingPaths.toMutableSet().also { activeReconciliations.add(it) }
        }
        try {
            var changes = 0

            meetingDao.getUnfinishedMeetings().forEach { meeting ->
                if (isLiveOrTouchedDuringReconcile(meeting.audioFilePath, skippedPaths)) {
                    return@forEach
                }
                val audioFile = File(meeting.audioFilePath)
                if (audioFile.exists()) {
                    meetingDao.finishMeeting(meeting.id, maxOf(audioFile.lastModified(), meeting.startTime))
                } else {
                    meetingDao.deleteMeeting(meeting.id)
                }
                changes++
            }

            val knownPaths = meetingDao.getAllAudioPaths().toSet()
            val orphans = synchronized(recordingLock) {
                recordingsDir.listFiles { file -> file.isFile && file.extension == "m4a" }.orEmpty().toList()
            }
            orphans
                .filterNot { orphan ->
                    orphan.absolutePath in knownPaths ||
                        isLiveOrTouchedDuringReconcile(orphan.absolutePath, skippedPaths)
                }
                .forEach { orphan ->
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
        } finally {
            synchronized(recordingLock) {
                activeReconciliations.remove(skippedPaths)
            }
        }
    }

    private fun isLiveOrTouchedDuringReconcile(path: String, skippedPaths: Set<String>): Boolean =
        synchronized(recordingLock) {
            path in skippedPaths || path in activeRecordingPaths
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

    suspend fun transcribeMeeting(id: Long, generation: Long): Result<AiGenerationOutcome> {
        val meeting = meetingDao.getMeetingByIdDirect(id)
        if (meeting == null || meeting.transcriptGeneration != generation) {
            return Result.success(AiGenerationOutcome.Discarded)
        }

        val audioFile = File(meeting.audioFilePath)
        if (!audioFile.exists()) {
            return Result.failure(Exception(context.getString(R.string.msg_audio_not_found)))
        }

        val settings = settingsRepository.settingsFlow.first()
        if (settings.apiKey.isBlank()) {
            return Result.failure(Exception(context.getString(R.string.msg_missing_api_key)))
        }

        val result = apiClient.transcribeAudio(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.transcriptionModel,
            prompt = settings.transcriptionPrompt,
            audioFile = audioFile
        )

        return result.map { text ->
            if (meetingDao.updateTranscript(id, text, generation) > 0) {
                AiGenerationOutcome.Stored(text)
            } else {
                AiGenerationOutcome.Discarded
            }
        }
    }

    suspend fun protocolizeMeeting(id: Long, generation: Long): Result<AiGenerationOutcome> {
        val meeting = meetingDao.getMeetingByIdDirect(id)
        if (meeting == null || meeting.protocolGeneration != generation) {
            return Result.success(AiGenerationOutcome.Discarded)
        }

        val transcript = meeting.transcript?.trim()
        if (transcript.isNullOrEmpty()) {
            return Result.failure(Exception(context.getString(R.string.msg_transcribe_first)))
        }

        val settings = settingsRepository.settingsFlow.first()
        if (settings.apiKey.isBlank()) {
            return Result.failure(Exception(context.getString(R.string.msg_missing_api_key)))
        }

        val result = apiClient.generateFromText(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.protocolModel,
            systemPrompt = settings.protocolPrompt,
            userText = transcript
        )

        return result.map { text ->
            if (meetingDao.updateProtocol(id, text, generation) > 0) {
                AiGenerationOutcome.Stored(text)
            } else {
                AiGenerationOutcome.Discarded
            }
        }
    }

    suspend fun deleteTranscript(id: Long): Long? = meetingDao.deleteTranscript(id)

    suspend fun deleteProtocol(id: Long): Long? = meetingDao.deleteProtocol(id)

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
