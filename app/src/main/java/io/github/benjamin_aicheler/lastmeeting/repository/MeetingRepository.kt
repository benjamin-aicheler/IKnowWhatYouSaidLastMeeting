package io.github.benjamin_aicheler.lastmeeting.repository

import android.content.Context
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.api.AudioFileTooLargeException
import io.github.benjamin_aicheler.lastmeeting.data.api.IncompleteCompletionException
import io.github.benjamin_aicheler.lastmeeting.data.api.OpenAiApiClient
import io.github.benjamin_aicheler.lastmeeting.data.db.MeetingDao
import io.github.benjamin_aicheler.lastmeeting.data.db.MeetingEntity
import io.github.benjamin_aicheler.lastmeeting.data.preferences.ApiSettings
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsProvider
import io.github.benjamin_aicheler.lastmeeting.util.AndroidAudioSegmenter
import io.github.benjamin_aicheler.lastmeeting.util.AudioSegmenter
import io.github.benjamin_aicheler.lastmeeting.util.DateTimeFormatters
import io.github.benjamin_aicheler.lastmeeting.util.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class ProviderConfigurationChangedException(message: String) : IllegalStateException(message)

class MeetingRepository(
    private val context: Context,
    private val meetingDao: MeetingDao,
    private val settingsRepository: SettingsProvider,
    private val apiClient: OpenAiApiClient = OpenAiApiClient(),
    private val deleteAudioFile: (File) -> Boolean = File::delete,
    private val recordingsDir: File = File(context.filesDir, "recordings"),
    private val audioSegmenter: AudioSegmenter = AndroidAudioSegmenter { File(context.cacheDir, "audio_segments") }
) {

    private val recordingRegistry = RecordingRegistry()

    val allMeetings: Flow<List<MeetingEntity>> = meetingDao.getAllMeetings()

    fun getMeetingById(id: Long): Flow<MeetingEntity?> = meetingDao.getMeetingById(id)

    /**
     * Creates a unique `.m4a` output file and marks its path as live atomically so a concurrent
     * [reconcileRecordings] scan never sees the file on disk as an unowned orphan.
     */
    fun createRecordingFile(recordingsDir: File = this.recordingsDir, now: Date = Date()): File =
        recordingRegistry.createAndRegisterLiveFile {
            check(recordingsDir.isDirectory || recordingsDir.mkdirs()) { "Cannot create recordings directory" }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now)
            // A failed retry or rapid stop/start must never overwrite another recording.
            val timestampFile = File(recordingsDir, "meeting_$timeStamp.m4a")
            if (timestampFile.createNewFile()) {
                timestampFile
            } else {
                File.createTempFile("meeting_${timeStamp}_", ".m4a", recordingsDir)
            }
        }

    /**
     * Marks [audioFile] as belonging to an active recording session in this process so
     * [reconcileRecordings] neither closes its unfinished row nor adopts the file as an orphan.
     */
    fun registerLiveRecording(audioFile: File) {
        recordingRegistry.registerLivePath(audioFile.absolutePath)
    }

    fun unregisterLiveRecording(audioFile: File) {
        recordingRegistry.unregisterLivePath(audioFile.absolutePath)
    }

    suspend fun createMeeting(
        audioFile: File,
        startTime: Long,
        endTime: Long,
        isDamaged: Boolean = false
    ): Long {
        val path = audioFile.absolutePath
        recordingRegistry.markTouchedDuringReconciliation(path)
        return try {
            meetingDao.insertMeeting(
                MeetingEntity(
                    title = defaultTitle(startTime),
                    startTime = startTime,
                    endTime = endTime,
                    audioFilePath = path,
                    transcript = null,
                    protocol = null,
                    createdAt = System.currentTimeMillis(),
                    isDamaged = isDamaged
                )
            )
        } finally {
            recordingRegistry.finishLivePath(path)
        }
    }

    /**
     * Inserts the row as soon as the recorder runs, so a recording interrupted by process death
     * keeps a meeting instead of leaving a file nothing points at. [MeetingEntity.endTime] stays
     * 0 until [finishMeeting] closes it, which keeps it out of the meeting list meanwhile.
     */
    suspend fun startMeeting(audioFile: File, startTime: Long): Long {
        val path = audioFile.absolutePath
        recordingRegistry.markTouchedDuringReconciliation(path)
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
        recordingRegistry.bindIdToLivePath(id, path)
        return id
    }

    suspend fun finishMeeting(id: Long, endTime: Long, isDamaged: Boolean = false) {
        try {
            meetingDao.finishMeeting(id, endTime, isDamaged)
        } finally {
            recordingRegistry.unregisterLiveId(id)
        }
    }

    /** Drops the row of a recording that never produced playable audio. */
    suspend fun discardMeeting(id: Long) {
        try {
            meetingDao.deleteMeeting(id)
        } finally {
            recordingRegistry.unregisterLiveId(id)
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
        val session = recordingRegistry.beginReconciliation()
        try {
            var changes = 0

            meetingDao.getUnfinishedMeetings().forEach { meeting ->
                if (recordingRegistry.isLiveOrTouched(meeting.audioFilePath, session)) {
                    return@forEach
                }
                val audioFile = File(meeting.audioFilePath)
                if (audioFile.exists() && audioFile.length() > 0L) {
                    meetingDao.finishMeeting(
                        meeting.id,
                        maxOf(audioFile.lastModified(), meeting.startTime),
                        isDamaged = true
                    )
                } else {
                    meetingDao.deleteMeeting(meeting.id)
                }
                changes++
            }

            val knownPaths = meetingDao.getAllAudioPaths().toSet()
            val orphans = recordingRegistry.withLock {
                recordingsDir.listFiles { file ->
                    file.isFile && file.extension == "m4a" && file.length() > 0L
                }.orEmpty().toList()
            }
            orphans
                .filterNot { orphan ->
                    orphan.absolutePath in knownPaths ||
                        recordingRegistry.isLiveOrTouched(orphan.absolutePath, session)
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
                            createdAt = System.currentTimeMillis(),
                            isDamaged = true
                        )
                    )
                    changes++
                }

            return changes
        } finally {
            recordingRegistry.endReconciliation(session)
        }
    }

    private fun defaultTitle(startTime: Long): String {
        val dateString = DateTimeFormatters.formatLocalizedDateTime(startTime, Locale.getDefault())
        return context.getString(R.string.default_meeting_title, dateString)
    }

    suspend fun updateTitle(id: Long, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isNotEmpty()) {
            meetingDao.updateTitle(id, trimmed)
        }
    }

    private suspend fun requireMatchingSettings(expectedConfigRevision: Long): ApiSettings? {
        if (expectedConfigRevision < 0L) return null
        val snapshot = settingsRepository.settingsFlow.first()
        return snapshot.takeIf { it.providerConfigRevision == expectedConfigRevision }
    }

    suspend fun transcribeMeeting(
        id: Long,
        generation: Long,
        expectedConfigRevision: Long
    ): Result<AiGenerationOutcome> {
        val meeting = meetingDao.getMeetingByIdDirect(id)
        if (meeting == null || meeting.transcriptGeneration != generation) {
            return Result.success(AiGenerationOutcome.Discarded)
        }

        val settings = requireMatchingSettings(expectedConfigRevision)
            ?: return Result.failure(
                ProviderConfigurationChangedException(context.getString(R.string.msg_provider_config_changed))
            )

        val audioFile = File(meeting.audioFilePath)
        if (!audioFile.exists()) {
            return Result.failure(Exception(context.getString(R.string.msg_audio_not_found)))
        }

        if (settings.apiKey.isBlank()) {
            return Result.failure(Exception(context.getString(R.string.msg_missing_api_key)))
        }

        val useMultipart = apiClient.shouldUseMultipartTranscription(
            baseUrl = settings.baseUrl,
            transcriptionMode = settings.transcriptionMode
        )
        val effectivePrompt = settings.resolveTranscriptionPrompt(useMultipart)

        val session = audioSegmenter.segmentIfNeeded(audioFile, OpenAiApiClient.MAX_AUDIO_UPLOAD_BYTES)
            .getOrElse { error ->
                return Result.failure(
                    IllegalArgumentException(context.getString(R.string.msg_audio_too_large_split_failed), error)
                )
            }

        return try {
            val transcriptParts = mutableListOf<String>()
            for (segment in session.segments) {
                val latest = meetingDao.getMeetingByIdDirect(id)
                if (latest == null || latest.transcriptGeneration != generation) {
                    return Result.success(AiGenerationOutcome.Discarded)
                }
                if (settingsRepository.settingsFlow.first().providerConfigRevision != expectedConfigRevision) {
                    return Result.failure(
                        ProviderConfigurationChangedException(context.getString(R.string.msg_provider_config_changed))
                    )
                }

                val segmentResult = apiClient.transcribeAudio(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    model = settings.transcriptionModel,
                    prompt = effectivePrompt,
                    audioFile = segment,
                    transcriptionMode = settings.transcriptionMode
                )
                val segmentText = segmentResult.getOrElse { error ->
                    if (error is AudioFileTooLargeException) {
                        return Result.failure(
                            AudioFileTooLargeException(
                                fileSizeBytes = error.fileSizeBytes,
                                maxBytes = error.maxBytes,
                                message = context.getString(R.string.msg_audio_too_large)
                            )
                        )
                    }
                    if (error is IncompleteCompletionException) {
                        return Result.failure(
                            IncompleteCompletionException(
                                finishReason = error.finishReason,
                                message = context.getString(R.string.msg_ai_incomplete_response, error.finishReason)
                            )
                        )
                    }
                    return Result.failure(error)
                }
                transcriptParts.add(segmentText)
            }

            val mergedText = transcriptParts
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")

            if (meetingDao.updateTranscript(id, mergedText, generation) > 0) {
                Result.success(AiGenerationOutcome.Stored(mergedText))
            } else {
                Result.success(AiGenerationOutcome.Discarded)
            }
        } finally {
            session.cleanup()
        }
    }

    suspend fun protocolizeMeeting(
        id: Long,
        generation: Long,
        expectedConfigRevision: Long
    ): Result<AiGenerationOutcome> {
        val meeting = meetingDao.getMeetingByIdDirect(id)
        if (meeting == null || meeting.protocolGeneration != generation) {
            return Result.success(AiGenerationOutcome.Discarded)
        }

        val settings = requireMatchingSettings(expectedConfigRevision)
            ?: return Result.failure(
                ProviderConfigurationChangedException(context.getString(R.string.msg_provider_config_changed))
            )

        val transcript = meeting.transcript?.trim()
        if (transcript.isNullOrEmpty()) {
            return Result.failure(Exception(context.getString(R.string.msg_transcribe_first)))
        }

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

        return result.fold(
            onSuccess = { text ->
                if (meetingDao.updateProtocol(id, text, generation) > 0) {
                    Result.success(AiGenerationOutcome.Stored(text))
                } else {
                    Result.success(AiGenerationOutcome.Discarded)
                }
            },
            onFailure = { error ->
                if (error is IncompleteCompletionException) {
                    Result.failure(
                        IncompleteCompletionException(
                            finishReason = error.finishReason,
                            message = context.getString(R.string.msg_ai_incomplete_response, error.finishReason)
                        )
                    )
                } else {
                    Result.failure(error)
                }
            }
        )
    }

    suspend fun clearTaskError(id: Long, isTranscriptTask: Boolean, generation: Long) {
        if (isTranscriptTask) {
            meetingDao.updateTranscriptError(id, null, generation)
        } else {
            meetingDao.updateProtocolError(id, null, generation)
        }
    }

    suspend fun recordTaskError(
        id: Long,
        isTranscriptTask: Boolean,
        generation: Long,
        errorMessage: String
    ): Boolean {
        val bounded = errorMessage.trim().ifEmpty { "Unknown error" }.take(500)
        val updated = if (isTranscriptTask) {
            meetingDao.updateTranscriptError(id, bounded, generation)
        } else {
            meetingDao.updateProtocolError(id, bounded, generation)
        }
        return updated > 0
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
