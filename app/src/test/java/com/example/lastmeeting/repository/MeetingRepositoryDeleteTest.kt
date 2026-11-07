package com.example.lastmeeting.repository

import androidx.room.Room
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.data.db.MeetingDao
import com.example.lastmeeting.data.db.MeetingDatabase
import com.example.lastmeeting.data.db.MeetingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LastMeetingApp::class, sdk = [34])
class MeetingRepositoryDeleteTest {

    private lateinit var app: LastMeetingApp
    private lateinit var database: MeetingDatabase
    private lateinit var dao: MeetingDao

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as LastMeetingApp
        database = Room.inMemoryDatabaseBuilder(app, MeetingDatabase::class.java).build()
        dao = database.meetingDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun keepsTheMeetingWhenTheAudioFileCannotBeDeleted() = runBlocking {
        val audioFile = File(app.filesDir, "kept.m4a").apply { writeText("audio") }
        val id = dao.insertMeeting(meeting(audioFile))

        val result = repository(deleteAudioFile = { false }).deleteMeeting(id)

        assertTrue(result.isFailure)
        assertNotNull(dao.getMeetingByIdDirect(id))
        assertTrue(audioFile.exists())
    }

    @Test
    fun removesTheMeetingAfterTheAudioFileIsGone() = runBlocking {
        val audioFile = File(app.filesDir, "deleted.m4a").apply { writeText("audio") }
        val id = dao.insertMeeting(meeting(audioFile))

        val result = repository(deleteAudioFile = File::delete).deleteMeeting(id)

        assertTrue(result.isSuccess)
        assertNull(dao.getMeetingByIdDirect(id))
        assertFalse(audioFile.exists())
    }

    @Test
    fun removesTheMeetingWhenTheAudioFileIsAlreadyGone() = runBlocking {
        val missingFile = File(app.filesDir, "missing.m4a")
        val id = dao.insertMeeting(meeting(missingFile))

        val result = repository(deleteAudioFile = File::delete).deleteMeeting(id)

        assertTrue(result.isSuccess)
        assertNull(dao.getMeetingByIdDirect(id))
    }

    private fun repository(deleteAudioFile: (File) -> Boolean) = MeetingRepository(
        context = app,
        meetingDao = dao,
        settingsRepository = app.settingsRepository,
        deleteAudioFile = deleteAudioFile
    )

    private fun meeting(audioFile: File) = MeetingEntity(
        title = "Meeting",
        startTime = 0L,
        endTime = 1L,
        audioFilePath = audioFile.absolutePath
    )
}
