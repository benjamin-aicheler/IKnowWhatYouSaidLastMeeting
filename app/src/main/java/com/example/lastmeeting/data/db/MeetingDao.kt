package com.example.lastmeeting.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {

    @Query("SELECT * FROM meetings ORDER BY startTime DESC")
    fun getAllMeetings(): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE id = :id")
    fun getMeetingById(id: Long): Flow<MeetingEntity?>

    @Query("SELECT * FROM meetings WHERE id = :id")
    suspend fun getMeetingByIdDirect(id: Long): MeetingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeeting(meeting: MeetingEntity): Long

    @Update
    suspend fun updateMeeting(meeting: MeetingEntity)

    @Query("UPDATE meetings SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE meetings SET transcript = :transcript WHERE id = :id")
    suspend fun updateTranscript(id: Long, transcript: String?)

    @Query("UPDATE meetings SET protocol = :protocol WHERE id = :id")
    suspend fun updateProtocol(id: Long, protocol: String?)

    @Query("DELETE FROM meetings WHERE id = :id")
    suspend fun deleteMeeting(id: Long)
}
