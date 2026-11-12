package com.example.lastmeeting.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {

    // endTime = 0 marks a recording that is still running, so it never shows up as a meeting.
    @Query("SELECT * FROM meetings WHERE endTime > 0 ORDER BY startTime DESC")
    fun getAllMeetings(): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE endTime = 0")
    suspend fun getUnfinishedMeetings(): List<MeetingEntity>

    @Query("SELECT audioFilePath FROM meetings")
    suspend fun getAllAudioPaths(): List<String>

    @Query("SELECT * FROM meetings WHERE id = :id")
    fun getMeetingById(id: Long): Flow<MeetingEntity?>

    @Query("SELECT * FROM meetings WHERE id = :id")
    suspend fun getMeetingByIdDirect(id: Long): MeetingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeeting(meeting: MeetingEntity): Long

    @Query("UPDATE meetings SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    // Invalidate dependent results in the same guarded write, including in-flight protocols.
    // IS NOT compares NULL safely when storing the first transcript after an explicit deletion.
    @Query("""
        UPDATE meetings SET
            protocol = CASE WHEN transcript IS NOT :transcript THEN NULL ELSE protocol END,
            protocolGeneration = protocolGeneration + CASE WHEN transcript IS NOT :transcript THEN 1 ELSE 0 END,
            transcript = :transcript
        WHERE id = :id AND transcriptGeneration = :generation
    """)
    suspend fun updateTranscript(id: Long, transcript: String, generation: Long): Int

    @Query("UPDATE meetings SET protocol = :protocol WHERE id = :id AND protocolGeneration = :generation")
    suspend fun updateProtocol(id: Long, protocol: String, generation: Long): Int

    @Query("UPDATE meetings SET transcript = NULL, transcriptGeneration = transcriptGeneration + 1 WHERE id = :id")
    suspend fun clearTranscript(id: Long)

    @Query("UPDATE meetings SET protocol = NULL, protocolGeneration = protocolGeneration + 1 WHERE id = :id")
    suspend fun clearProtocol(id: Long)

    @Transaction
    suspend fun deleteTranscript(id: Long): Long? {
        val generation = getMeetingByIdDirect(id)?.transcriptGeneration ?: return null
        clearTranscript(id)
        return generation
    }

    @Transaction
    suspend fun deleteProtocol(id: Long): Long? {
        val generation = getMeetingByIdDirect(id)?.protocolGeneration ?: return null
        clearProtocol(id)
        return generation
    }

    @Query("UPDATE meetings SET endTime = :endTime WHERE id = :id")
    suspend fun finishMeeting(id: Long, endTime: Long)

    @Query("DELETE FROM meetings WHERE id = :id")
    suspend fun deleteMeeting(id: Long)
}
