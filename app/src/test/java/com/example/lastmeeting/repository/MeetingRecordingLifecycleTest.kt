package com.example.lastmeeting.repository

import androidx.room.Room
import com.example.lastmeeting.LastMeetingApp
import com.example.lastmeeting.data.db.MeetingDao
import com.example.lastmeeting.data.db.MeetingDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class MeetingRecordingLifecycleTest {

    private lateinit var app: LastMeetingApp
    private lateinit var database: MeetingDatabase
    private lateinit var dao: MeetingDao
    private lateinit var repository: MeetingRepository
    private lateinit var recordingsDir: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as LastMeetingApp
        database = Room.inMemoryDatabaseBuilder(app, MeetingDatabase::class.java).build()
        dao = database.meetingDao()
        recordingsDir = File(app.filesDir, "recordings-${System.nanoTime()}").apply { mkdirs() }
        repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = app.settingsRepository,
            recordingsDir = recordingsDir
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aRunningRecordingStaysOutOfTheMeetingList() = runBlocking {
        val id = repository.startMeeting(recording("running.m4a"), startTime = 1_000L)

        assertTrue(repository.allMeetings.first().isEmpty())
        assertEquals(0L, dao.getMeetingByIdDirect(id)?.endTime ?: -1L)
    }

    @Test
    fun finishingARecordingPublishesIt() = runBlocking {
        val id = repository.startMeeting(recording("finished.m4a"), startTime = 1_000L)

        repository.finishMeeting(id, endTime = 61_000L)

        val meeting = repository.allMeetings.first().single()
        assertEquals(id, meeting.id)
        assertEquals(1_000L, meeting.startTime)
        assertEquals(61_000L, meeting.endTime)
    }

    @Test
    fun reconciliationClosesAnInterruptedRecording() = runBlocking {
        val audioFile = recording("interrupted.m4a")
        audioFile.setLastModified(5_000_000L)
        val id = repository.startMeeting(audioFile, startTime = 1_000L)

        assertEquals(1, repository.reconcileRecordings())

        val meeting = dao.getMeetingByIdDirect(id)
        assertEquals(5_000_000L, meeting?.endTime ?: -1L)
        assertTrue(repository.allMeetings.first().any { it.id == id })
    }

    @Test
    fun reconciliationDropsARecordingWhoseAudioIsGone() = runBlocking {
        val missing = File(recordingsDir, "vanished.m4a")
        val id = repository.startMeeting(missing, startTime = 1_000L)

        assertEquals(1, repository.reconcileRecordings())

        assertNull(dao.getMeetingByIdDirect(id))
        assertTrue(repository.allMeetings.first().isEmpty())
    }

    @Test
    fun reconciliationAdoptsARecordingWithoutARow() = runBlocking {
        val orphan = recording("meeting_20260101_120000.m4a")
        orphan.setLastModified(7_000_000L)

        assertEquals(1, repository.reconcileRecordings())

        val meeting = repository.allMeetings.first().single()
        assertEquals(orphan.absolutePath, meeting.audioFilePath)
        assertEquals(7_000_000L, meeting.startTime)
        assertEquals(7_000_000L, meeting.endTime)
    }

    @Test
    fun reconciliationIsIdempotent() = runBlocking {
        recording("meeting_20260102_120000.m4a")
        repository.startMeeting(recording("interrupted.m4a"), startTime = 1_000L)

        assertEquals(2, repository.reconcileRecordings())
        assertEquals(0, repository.reconcileRecordings())
        assertEquals(2, repository.allMeetings.first().size)
    }

    private fun recording(name: String): File =
        File(recordingsDir, name).apply { writeText("audio") }
}
