package com.example.lastmeeting.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meetings")
data class MeetingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val startTime: Long,
    /** Wall-clock end of the recording; 0 while the recording is still running. */
    val endTime: Long,
    val audioFilePath: String,
    val transcript: String? = null,
    val protocol: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val transcriptGeneration: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val protocolGeneration: Long = 0
)
