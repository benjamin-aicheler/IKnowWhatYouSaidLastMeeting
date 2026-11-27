package io.github.benjamin_aicheler.lastmeeting.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioSegmenterCleanupTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pruneAbandonedSegmentDirsDeletesOnlyInactiveSegmentDirectories() {
        val rootDir = File(temporaryFolder.root, AndroidAudioSegmenter.SEGMENT_CACHE_DIR_NAME).apply {
            mkdirs()
        }
        val abandonedOne = File(rootDir, "seg_old_1").apply {
            mkdirs()
            File(this, "segment_0001.m4a").writeText("old1")
        }
        val abandonedTwo = File(rootDir, "seg_old_2").apply {
            mkdirs()
            File(this, "segment_0001.m4a").writeText("old2")
        }
        val activeSession = File(rootDir, "seg_live_1").apply {
            mkdirs()
            File(this, "segment_0001.m4a").writeText("live")
        }
        val unrelatedDir = File(rootDir, "custom_keep").apply {
            mkdirs()
            File(this, "keep.txt").writeText("keep")
        }
        val strayFile = File(rootDir, "seg_not_a_dir.tmp").apply {
            writeText("file")
        }

        val deleted = AndroidAudioSegmenter.pruneAbandonedSegmentDirs(
            rootDir = rootDir,
            activeSessionNames = setOf("seg_live_1")
        )

        assertEquals(2, deleted)
        assertFalse(abandonedOne.exists())
        assertFalse(abandonedTwo.exists())
        assertTrue(activeSession.exists())
        assertTrue(unrelatedDir.exists())
        assertTrue(strayFile.exists())
    }

    @Test
    fun startupSweepRunsOnceAndPreservesLiveAndPostSweepSessions() {
        val cacheDir = temporaryFolder.newFolder("app_cache")
        val rootDir = File(cacheDir, AndroidAudioSegmenter.SEGMENT_CACHE_DIR_NAME).apply { mkdirs() }
        val staleSession = File(rootDir, "seg_crashed").apply {
            mkdirs()
            File(this, "segment_0001.m4a").writeText("crashed")
        }
        val liveSession = File(rootDir, "seg_active").apply {
            mkdirs()
            File(this, "segment_0001.m4a").writeText("live")
        }
        AndroidAudioSegmenter.registerActiveSessionDir(liveSession)
        try {
            assertEquals(1, AndroidAudioSegmenter.pruneAbandonedSessionsOnStartup(cacheDir))
            assertFalse(staleSession.exists())
            assertTrue(liveSession.exists())

            val laterSession = File(rootDir, "seg_later_without_registration").apply {
                mkdirs()
                File(this, "segment_0001.m4a").writeText("later")
            }
            // Subsequent startup sweep calls for the same root must be no-ops.
            assertEquals(0, AndroidAudioSegmenter.pruneAbandonedSessionsOnStartup(cacheDir))
            assertTrue(laterSession.exists())
        } finally {
            AndroidAudioSegmenter.releaseSessionDir(liveSession)
        }
    }
}
