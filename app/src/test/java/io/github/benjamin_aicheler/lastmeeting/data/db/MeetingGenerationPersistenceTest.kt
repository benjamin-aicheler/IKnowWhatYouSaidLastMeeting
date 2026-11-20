package io.github.benjamin_aicheler.lastmeeting.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

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
            // Deleting a transcript alone leaves the protocol version independent.
            assertEquals(1, dao.updateProtocol(id, "Current protocol", 0L))
            assertEquals(1, dao.updateTranscript(id, "New transcript", 1L))
            // Successfully storing changed source text has already advanced the protocol version.
            assertEquals(1L, dao.deleteProtocol(id))
            assertEquals(0, dao.updateProtocol(id, "Stale protocol", 1L))
            assertNull(dao.getMeetingByIdDirect(id)!!.protocol)
            assertEquals(1, dao.updateProtocol(id, "New protocol", 2L))
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
                assertEquals(4, database.openHelper.readableDatabase.version)
                assertFalse(dao.getMeetingByIdDirect(original.id)!!.isDamaged)
                assertEquals(0L, dao.deleteTranscript(original.id))
                assertEquals(0L, dao.deleteProtocol(original.id))
                assertEquals(0, dao.updateTranscript(original.id, "Old transcript", 0L))
                assertEquals(0, dao.updateProtocol(original.id, "Old protocol", 0L))
                assertEquals(1, dao.updateTranscript(original.id, "New transcript", 1L))
                assertEquals(1, dao.updateProtocol(original.id, "New protocol", 2L))
                assertEquals(original.audioFilePath, dao.getMeetingByIdDirect(original.id)!!.audioFilePath)
            }
        }
    }

    @Test
    fun changedTranscriptAtomicallyInvalidatesSavedAndPendingProtocolsAcrossReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "transcript-replacement.db"
        val id = open(context, name).useDatabase { database ->
            val dao = database.meetingDao()
            val id = dao.insertMeeting(meeting())
            assertEquals(1, dao.updateTranscript(id, "Changed transcript", 0L))
            val current = dao.getMeetingByIdDirect(id)!!
            assertEquals("Changed transcript", current.transcript)
            assertNull(current.protocol)
            assertEquals(1L, current.protocolGeneration)
            assertEquals(0L, current.transcriptGeneration)
            id
        }

        open(context, name).useDatabase { database ->
            val dao = database.meetingDao()
            assertEquals(0, dao.updateProtocol(id, "Delayed protocol", 0L))
            assertNull(dao.getMeetingByIdDirect(id)!!.protocol)
            assertEquals(1, dao.updateProtocol(id, "Current protocol", 1L))
        }
    }

    @Test
    fun identicalAndStaleTranscriptWritesPreserveProtocolAndVersions() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        open(context, "unchanged-transcript.db").useDatabase { database ->
            val dao = database.meetingDao()
            val original = meeting()
            val id = dao.insertMeeting(original)
            assertEquals(1, dao.updateTranscript(id, original.transcript!!, 0L))
            assertEquals(original.copy(id = id), dao.getMeetingByIdDirect(id))
            assertEquals(0, dao.updateTranscript(id, "Stale transcript", 1L))
            assertEquals(original.copy(id = id), dao.getMeetingByIdDirect(id))
            assertEquals(0, dao.updateTranscript(id + 1, "Missing meeting", 0L))
        }
    }

    @Test
    fun storingTranscriptAfterDeletionInvalidatesRemainingProtocol() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        open(context, "deleted-transcript.db").useDatabase { database ->
            val dao = database.meetingDao()
            val id = dao.insertMeeting(meeting())
            dao.deleteTranscript(id)
            assertEquals(0, dao.updateTranscript(id, "Stale transcript", 0L))
            assertEquals("Saved protocol", dao.getMeetingByIdDirect(id)!!.protocol)
            assertEquals(1, dao.updateTranscript(id, "New transcript", 1L))
            assertNull(dao.getMeetingByIdDirect(id)!!.protocol)
            assertEquals(0, dao.updateProtocol(id, "Stale protocol", 0L))
            assertEquals(1, dao.updateProtocol(id, "New protocol", 1L))
        }
    }

    @Test
    fun migration2To3AddsDamagedFlagDefaultingToFalse() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-2-3.db"
        val original = meeting().copy(id = 7L)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("""
                CREATE TABLE meetings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER NOT NULL,
                    audioFilePath TEXT NOT NULL, transcript TEXT, protocol TEXT, createdAt INTEGER NOT NULL,
                    transcriptGeneration INTEGER NOT NULL DEFAULT 0,
                    protocolGeneration INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            database.execSQL(
                "INSERT INTO meetings VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(original.id, original.title, original.startTime, original.endTime,
                    original.audioFilePath, original.transcript, original.protocol, original.createdAt, 0L, 0L)
            )
            database.version = 2
        }

        open(context, name).useDatabase { database ->
            val dao = database.meetingDao()
            assertEquals(4, database.openHelper.readableDatabase.version)
            val migrated = dao.getMeetingByIdDirect(original.id)!!
            assertEquals(original, migrated)
            assertFalse(migrated.isDamaged)
            assertNull(migrated.transcriptError)
            assertNull(migrated.protocolError)

            dao.finishMeeting(original.id, endTime = 5_000L, isDamaged = true)
            assertTrue(dao.getMeetingByIdDirect(original.id)!!.isDamaged)
        }
    }

    @Test
    fun migration3To4AddsNullableTaskErrorColumns() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-3-4.db"
        val original = meeting().copy(id = 9L)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("""
                CREATE TABLE meetings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER NOT NULL,
                    audioFilePath TEXT NOT NULL, transcript TEXT, protocol TEXT, createdAt INTEGER NOT NULL,
                    transcriptGeneration INTEGER NOT NULL DEFAULT 0,
                    protocolGeneration INTEGER NOT NULL DEFAULT 0,
                    isDamaged INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            database.execSQL(
                "INSERT INTO meetings VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(original.id, original.title, original.startTime, original.endTime,
                    original.audioFilePath, original.transcript, original.protocol, original.createdAt, 0L, 0L, 0)
            )
            database.version = 3
        }

        open(context, name).useDatabase { database ->
            val dao = database.meetingDao()
            assertEquals(4, database.openHelper.readableDatabase.version)
            val migrated = dao.getMeetingByIdDirect(original.id)!!
            assertEquals(original, migrated)
            assertNull(migrated.transcriptError)
            assertNull(migrated.protocolError)

            assertEquals(1, dao.updateTranscriptError(original.id, "Upload failed", 0L))
            assertEquals(1, dao.updateProtocolError(original.id, "Timeout", 0L))
            val errored = dao.getMeetingByIdDirect(original.id)!!
            assertEquals("Upload failed", errored.transcriptError)
            assertEquals("Timeout", errored.protocolError)

            assertEquals(1, dao.updateTranscript(original.id, "New transcript", 0L))
            val updatedTranscript = dao.getMeetingByIdDirect(original.id)!!
            assertNull(updatedTranscript.transcriptError)
            assertNull(updatedTranscript.protocolError)

            assertEquals(1, dao.updateProtocol(original.id, "New protocol", 1L))
            assertNull(dao.getMeetingByIdDirect(original.id)!!.protocolError)
        }
    }

    @Test
    fun exportedSchemasExistAndVersionOneSchemaMigratesToLatest() = runBlocking {
        val schemaDir = File("schemas/io.github.benjamin_aicheler.lastmeeting.data.db.MeetingDatabase")
            .takeIf { it.isDirectory }
            ?: File("app/schemas/io.github.benjamin_aicheler.lastmeeting.data.db.MeetingDatabase")
        assertTrue("Missing Room schema directory: ${schemaDir.absolutePath}", schemaDir.isDirectory)

        val expectedColumnCounts = mapOf(1 to 8, 2 to 10, 3 to 11, 4 to 13)
        val expectedHashes = mapOf(
            1 to "c30fb1d438ce5cf6490b34d5f80a1c7a",
            2 to "35c86cf2861ca546d22b18eb1eb7fb43",
            3 to "7a8b94d3b15e7c9f083287c07b15e8d1",
            4 to "538204fb3d5e2b3cb145912712c6f2de"
        )

        var versionOneCreateSql = ""
        for (version in 1..4) {
            val schemaFile = File(schemaDir, "$version.json")
            assertTrue("Missing exported schema $version.json", schemaFile.isFile)
            val database = Json.parseToJsonElement(schemaFile.readText())
                .jsonObject["database"]!!.jsonObject
            assertEquals(version, database["version"]!!.jsonPrimitive.int)
            assertEquals(expectedHashes[version], database["identityHash"]!!.jsonPrimitive.content)
            val entity = database["entities"]!!.jsonArray.single().jsonObject
            assertEquals("meetings", entity["tableName"]!!.jsonPrimitive.content)
            assertEquals(expectedColumnCounts[version], entity["fields"]!!.jsonArray.size)
            if (version == 1) {
                versionOneCreateSql = entity["createSql"]!!.jsonPrimitive.content
                    .replace("\${TABLE_NAME}", "meetings")
            }
        }

        val context = RuntimeEnvironment.getApplication()
        val name = "schema-exported-v1-to-v4.db"
        val original = meeting().copy(id = 17L)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL(versionOneCreateSql)
            database.execSQL(
                "INSERT INTO meetings VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    original.id,
                    original.title,
                    original.startTime,
                    original.endTime,
                    original.audioFilePath,
                    original.transcript,
                    original.protocol,
                    original.createdAt
                )
            )
            database.version = 1
        }

        open(context, name).useDatabase { database ->
            val dao = database.meetingDao()
            assertEquals(4, database.openHelper.readableDatabase.version)
            val migrated = dao.getMeetingByIdDirect(original.id)!!
            assertEquals(original, migrated)
            assertEquals(0L, migrated.transcriptGeneration)
            assertEquals(0L, migrated.protocolGeneration)
            assertFalse(migrated.isDamaged)
            assertNull(migrated.transcriptError)
            assertNull(migrated.protocolError)
        }
    }

    private fun open(context: Context, name: String): MeetingDatabase =
        Room.databaseBuilder(context, MeetingDatabase::class.java, name)
            .addMigrations(MeetingDatabase.MIGRATION_1_2, MeetingDatabase.MIGRATION_2_3, MeetingDatabase.MIGRATION_3_4).build()

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
