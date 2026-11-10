package com.example.lastmeeting.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MeetingGenerationPersistenceTest {

    @Test
    fun deletionVersionsSurviveClosingAndReopeningTheDatabase() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "generation-persistence.db"
        val original = meeting()
        val id = open(context, name).useDatabase { database ->
            val dao = database.meetingDao()
            val id = dao.insertMeeting(original)
            assertEquals(0L, dao.deleteTranscript(id))
            id
        }

        open(context, name).useDatabase { database ->
            val dao = database.meetingDao()
            assertEquals(0, dao.updateTranscript(id, "Stale transcript", 0L))
            assertNull(dao.getMeetingByIdDirect(id)!!.transcript)
            // The other field's version is independent.
            assertEquals(1, dao.updateProtocol(id, "Current protocol", 0L))
            assertEquals(1, dao.updateTranscript(id, "New transcript", 1L))
            assertEquals(0L, dao.deleteProtocol(id))
            assertEquals(0, dao.updateProtocol(id, "Stale protocol", 0L))
            assertNull(dao.getMeetingByIdDirect(id)!!.protocol)
            assertEquals(1, dao.updateProtocol(id, "New protocol", 1L))
            assertEquals("New transcript", dao.getMeetingByIdDirect(id)!!.transcript)
            assertEquals("New protocol", dao.getMeetingByIdDirect(id)!!.protocol)
        }
    }

    @Test
    fun migrationPreservesVersionOneMeetingsAndSupportsConditionalWrites() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "last_meeting_db"
        val original = meeting().copy(id = 42L)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            // Version 1's exact table definition, before generation columns existed.
            database.execSQL("""
                CREATE TABLE meetings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER NOT NULL,
                    audioFilePath TEXT NOT NULL, transcript TEXT, protocol TEXT, createdAt INTEGER NOT NULL
                )
            """.trimIndent())
            database.execSQL(
                "INSERT INTO meetings VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(original.id, original.title, original.startTime, original.endTime,
                    original.audioFilePath, original.transcript, original.protocol, original.createdAt)
            )
            database.version = 1
        }

        withIsolatedDatabaseSingleton {
            MeetingDatabase.getDatabase(context).useDatabase { database ->
                val dao = database.meetingDao()
                assertEquals(original, dao.getMeetingByIdDirect(original.id))
                assertEquals(2, database.openHelper.readableDatabase.version)
                assertEquals(0L, dao.deleteTranscript(original.id))
                assertEquals(0L, dao.deleteProtocol(original.id))
                assertEquals(0, dao.updateTranscript(original.id, "Old transcript", 0L))
                assertEquals(0, dao.updateProtocol(original.id, "Old protocol", 0L))
                assertEquals(1, dao.updateTranscript(original.id, "New transcript", 1L))
                assertEquals(1, dao.updateProtocol(original.id, "New protocol", 1L))
                assertEquals(original.audioFilePath, dao.getMeetingByIdDirect(original.id)!!.audioFilePath)
            }
        }
    }

    private fun open(context: Context, name: String): MeetingDatabase =
        Room.databaseBuilder(context, MeetingDatabase::class.java, name)
            .addMigrations(MeetingDatabase.MIGRATION_1_2).build()

    private inline fun <T> MeetingDatabase.useDatabase(block: (MeetingDatabase) -> T): T =
        try { block(this) } finally { close() }

    private inline fun <T> withIsolatedDatabaseSingleton(block: () -> T): T {
        val previous = ReflectionHelpers.getStaticField<MeetingDatabase?>(MeetingDatabase::class.java, "INSTANCE")
        ReflectionHelpers.setStaticField(MeetingDatabase::class.java, "INSTANCE", null)
        return try { block() } finally {
            // Robolectric can reuse application classes between tests. Never leave the
            // production factory pointing at the migrated database that this test closed.
            ReflectionHelpers.setStaticField(MeetingDatabase::class.java, "INSTANCE", previous)
        }
    }

    private fun meeting() = MeetingEntity(
        title = "Besprechung", startTime = 1_000L, endTime = 2_000L,
        audioFilePath = "/private/recordings/meeting.m4a", transcript = "Saved transcript",
        protocol = "Saved protocol", createdAt = 3_000L
    )
}
