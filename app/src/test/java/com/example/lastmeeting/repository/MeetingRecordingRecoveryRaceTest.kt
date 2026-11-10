package com.example.lastmeeting.repository

import android.Manifest
import android.app.Application
import com.example.lastmeeting.data.db.MeetingDao
import com.example.lastmeeting.data.db.MeetingEntity
import com.example.lastmeeting.data.preferences.SettingsRepository
import com.example.lastmeeting.service.AudioRecorder
import com.example.lastmeeting.service.RecordingService
import com.example.lastmeeting.service.asSessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MeetingRecordingRecoveryRaceTest {

    private lateinit var app: Application
    private lateinit var dao: ControllableFakeMeetingDao
    private lateinit var repository: MeetingRepository
    private lateinit var controller: ServiceController<RecordingService>
    private lateinit var service: RecordingService
    private lateinit var recordingsDir: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        dao = ControllableFakeMeetingDao()
        recordingsDir = File(app.filesDir, "recordings").apply {
            deleteRecursively()
            mkdirs()
        }
        repository = MeetingRepository(
            context = app,
            meetingDao = dao,
            settingsRepository = SettingsRepository(app),
            recordingsDir = recordingsDir
        )
        controller = Robolectric.buildService(RecordingService::class.java).create()
        service = controller.get()
        service.recorderFactory = { FakeAudioRecorder() }
        service.meetingStoreOverride = repository.asSessionStore()
        service.persistenceScopeOverride = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun delayedUnfinishedRowScanKeepsActiveRecordingHiddenWhileRecoveringEarlierSession() = runBlocking {
        // Seed an unfinished row left behind by a previous process.
        val earlierFile = File(recordingsDir, "meeting_20260101_090000.m4a").apply {
            writeText("interrupted session audio")
            setLastModified(5_000L)
        }
        val earlierId = repository.startMeeting(earlierFile, startTime = 1_000L)

        val enteredUnfinishedScan = CompletableDeferred<Unit>()
        val releaseUnfinishedScan = CompletableDeferred<Unit>()
        dao.onBeforeGetUnfinishedMeetings = {
            enteredUnfinishedScan.complete(Unit)
            releaseUnfinishedScan.await()
        }

        // Launch startup recovery and hold it at the unfinished-row scan.
        val reconcileDeferred = async(Dispatchers.Default) {
            repository.reconcileRecordings()
        }
        enteredUnfinishedScan.await()

        // Start a new recording while startup recovery is delayed.
        val activeFile = service.startRecording()
        assertNotNull(activeFile)
        activeFile!!.setLastModified(9_000L)
        val activeRowBeforeReconcile = dao.allRows().single { it.audioFilePath == activeFile.absolutePath }
        assertEquals(0L, activeRowBeforeReconcile.endTime)

        // Release recovery while the new recording is still active.
        releaseUnfinishedScan.complete(Unit)
        val changes = reconcileDeferred.await()

        // Only the earlier process's interrupted row may be closed; the active recording stays hidden.
        assertEquals(1, changes)
        assertEquals(5_000L, dao.getMeetingByIdDirect(earlierId)?.endTime)
        val activeRowAfterReconcile = dao.getMeetingByIdDirect(activeRowBeforeReconcile.id)
        assertNotNull(activeRowAfterReconcile)
        assertEquals(0L, activeRowAfterReconcile!!.endTime)

        val visibleWhileRecording = repository.allMeetings.first()
        assertEquals(listOf(earlierId), visibleWhileRecording.map { it.id })

        // Finalize the active recording and verify it appears once with its real end time.
        service.stopRecording()

        val rowsForActiveFile = dao.allRows().filter { it.audioFilePath == activeFile.absolutePath }
        assertEquals(1, rowsForActiveFile.size)
        assertTrue(rowsForActiveFile.single().endTime > 0L)
        val visibleAfterStop = repository.allMeetings.first()
        assertEquals(2, visibleAfterStop.size)
        assertEquals(1, visibleAfterStop.count { it.audioFilePath == activeFile.absolutePath })
    }

    @Test
    fun delayedOrphanScanWithStalePathSnapshotDoesNotAdoptActiveRecordingFile() = runBlocking {
        // Seed an orphan file left behind by an earlier process.
        val earlierOrphan = File(recordingsDir, "meeting_20260101_100000.m4a").apply {
            writeText("orphan audio")
            setLastModified(6_000L)
        }

        val snapshotTaken = CompletableDeferred<Unit>()
        val releaseOrphanScan = CompletableDeferred<Unit>()
        dao.onAfterGetAllAudioPathsSnapshot = {
            snapshotTaken.complete(Unit)
            releaseOrphanScan.await()
        }

        // Hold startMeeting's DB insert so the live file exists on disk without a DB row during the orphan scan.
        val allowStartInsert = CompletableDeferred<Unit>()
        dao.onBeforeInsertMeeting = { entity ->
            if (entity.endTime == 0L) {
                allowStartInsert.await()
            }
        }

        val reconcileDeferred = async(Dispatchers.Default) {
            repository.reconcileRecordings()
        }
        snapshotTaken.await()

        // Start a recording while recovery holds a stale knownPaths snapshot and before the row lands.
        service.persistenceScopeOverride = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val activeFile = service.startRecording()
        assertNotNull(activeFile)
        activeFile!!.setLastModified(10_000L)

        // Release the orphan scan while the recording is active and its row insert is still in flight.
        releaseOrphanScan.complete(Unit)
        val changes = reconcileDeferred.await()

        // Only the earlier orphan is adopted; the live file is skipped.
        assertEquals(1, changes)
        val visibleWhileRecording = repository.allMeetings.first()
        assertEquals(1, visibleWhileRecording.size)
        assertEquals(earlierOrphan.absolutePath, visibleWhileRecording.single().audioFilePath)
        assertTrue(dao.allRows().none { it.audioFilePath == activeFile.absolutePath })

        // Let the start row land and then stop the recording.
        allowStartInsert.complete(Unit)
        service.stopRecording()

        val rowsForActiveFile = dao.allRows().filter { it.audioFilePath == activeFile.absolutePath }
        assertEquals(1, rowsForActiveFile.size)
        assertTrue(rowsForActiveFile.single().endTime > 0L)

        val visibleAfterStop = repository.allMeetings.first()
        assertEquals(2, visibleAfterStop.size)
        assertEquals(1, visibleAfterStop.count { it.audioFilePath == activeFile.absolutePath })
    }

    private class FakeAudioRecorder : AudioRecorder {
        override fun configure(outputFile: File) {
            outputFile.writeText("recorded audio")
        }

        override fun prepare() = Unit
        override fun start() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    private class ControllableFakeMeetingDao : MeetingDao {
        private val lock = Any()
        private val items = MutableStateFlow<Map<Long, MeetingEntity>>(emptyMap())
        private var nextId = 1L

        var onBeforeGetUnfinishedMeetings: (suspend () -> Unit)? = null
        var onAfterGetAllAudioPathsSnapshot: (suspend () -> Unit)? = null
        var onBeforeInsertMeeting: (suspend (MeetingEntity) -> Unit)? = null

        fun allRows(): List<MeetingEntity> = synchronized(lock) { items.value.values.toList() }

        override fun getAllMeetings(): Flow<List<MeetingEntity>> =
            items.map { map -> map.values.filter { it.endTime > 0L }.sortedByDescending { it.startTime } }

        override suspend fun getUnfinishedMeetings(): List<MeetingEntity> {
            onBeforeGetUnfinishedMeetings?.invoke()
            return synchronized(lock) {
                items.value.values.filter { it.endTime == 0L }
            }
        }

        override suspend fun getAllAudioPaths(): List<String> {
            val snapshot = synchronized(lock) {
                items.value.values.map { it.audioFilePath }
            }
            onAfterGetAllAudioPathsSnapshot?.invoke()
            return snapshot
        }

        override fun getMeetingById(id: Long): Flow<MeetingEntity?> =
            items.map { it[id] }

        override suspend fun getMeetingByIdDirect(id: Long): MeetingEntity? =
            synchronized(lock) { items.value[id] }

        override suspend fun insertMeeting(meeting: MeetingEntity): Long {
            onBeforeInsertMeeting?.invoke(meeting)
            return synchronized(lock) {
                val id = if (meeting.id == 0L) nextId++ else meeting.id
                items.value = items.value + (id to meeting.copy(id = id))
                id
            }
        }

        override suspend fun updateTitle(id: Long, title: String) {
            synchronized(lock) {
                items.value[id]?.let { items.value = items.value + (id to it.copy(title = title)) }
            }
        }

        override suspend fun updateTranscript(id: Long, transcript: String, generation: Long): Int =
            synchronized(lock) {
                val meeting = items.value[id]?.takeIf { it.transcriptGeneration == generation } ?: return@synchronized 0
                items.value = items.value + (id to meeting.copy(transcript = transcript))
                1
            }

        override suspend fun updateProtocol(id: Long, protocol: String, generation: Long): Int =
            synchronized(lock) {
                val meeting = items.value[id]?.takeIf { it.protocolGeneration == generation } ?: return@synchronized 0
                items.value = items.value + (id to meeting.copy(protocol = protocol))
                1
            }

        override suspend fun clearTranscript(id: Long) {
            synchronized(lock) {
                items.value[id]?.let {
                    items.value = items.value + (id to it.copy(transcript = null, transcriptGeneration = it.transcriptGeneration + 1))
                }
            }
        }

        override suspend fun clearProtocol(id: Long) {
            synchronized(lock) {
                items.value[id]?.let {
                    items.value = items.value + (id to it.copy(protocol = null, protocolGeneration = it.protocolGeneration + 1))
                }
            }
        }

        override suspend fun finishMeeting(id: Long, endTime: Long) {
            synchronized(lock) {
                items.value[id]?.let { items.value = items.value + (id to it.copy(endTime = endTime)) }
            }
        }

        override suspend fun deleteMeeting(id: Long) {
            synchronized(lock) {
                items.value = items.value - id
            }
        }
    }
}
