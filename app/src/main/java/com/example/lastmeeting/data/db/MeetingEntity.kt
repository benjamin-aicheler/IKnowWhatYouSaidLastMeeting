package com.example.lastmeeting.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meetings")
data class MeetingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val audioFilePath: String,
    val transcript: String? = null,
    val protocol: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
