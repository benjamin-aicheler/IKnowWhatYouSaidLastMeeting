package io.github.benjamin_aicheler.lastmeeting.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingRegistryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun createAndUnregisterByPathAndIdTracksLiveState() {
        val registry = RecordingRegistry()
        val liveFile = registry.createAndRegisterLiveFile {
            File(tempFolder.root, "meeting_1.m4a").apply { writeText("audio") }
        }
        val otherFile = File(tempFolder.root, "meeting_2.m4a").apply { writeText("other") }

        registry.bindIdToLivePath(10L, liveFile.absolutePath)
        registry.registerLivePath(otherFile.absolutePath)
        registry.bindIdToLivePath(20L, otherFile.absolutePath)

        val session = registry.beginReconciliation()
        try {
            assertTrue(registry.isLiveOrTouched(liveFile.absolutePath, session))
            assertTrue(registry.isLiveOrTouched(otherFile.absolutePath, session))
        } finally {
            registry.endReconciliation(session)
        }

        registry.unregisterLiveId(10L)
        val afterFirstFinish = registry.beginReconciliation()
        try {
            assertFalse(registry.isLiveOrTouched(liveFile.absolutePath, afterFirstFinish))
            assertTrue(registry.isLiveOrTouched(otherFile.absolutePath, afterFirstFinish))
        } finally {
            registry.endReconciliation(afterFirstFinish)
        }

        registry.unregisterLivePath(otherFile.absolutePath)
        val afterPathUnregister = registry.beginReconciliation()
        try {
            assertFalse(registry.isLiveOrTouched(otherFile.absolutePath, afterPathUnregister))
        } finally {
            registry.endReconciliation(afterPathUnregister)
        }
    }

    @Test
    fun openReconciliationSkipsPathsTouchedBetweenInsertBoundariesEvenAfterLiveUnregister() {
        val registry = RecordingRegistry()
        val path = File(tempFolder.root, "race.m4a").absolutePath

        val session = registry.beginReconciliation()
        registry.markTouchedDuringReconciliation(path)
        registry.registerLivePath(path)
        registry.bindIdToLivePath(42L, path)
        registry.unregisterLiveId(42L)

        // Still protected for this open scan even after live recording finishes, because it was
        // touched during the scan window.
        assertTrue(registry.isLiveOrTouched(path, session))
        registry.endReconciliation(session)

        // Subsequent scans no longer see it as live or touched.
        val nextSession = registry.beginReconciliation()
        try {
            assertFalse(registry.isLiveOrTouched(path, nextSession))
        } finally {
            registry.endReconciliation(nextSession)
        }
    }

    @Test
    fun overlappingReconciliationsStayIndependentAndCleanUp() {
        val registry = RecordingRegistry()
        val pathA = File(tempFolder.root, "a.m4a").absolutePath
        val pathB = File(tempFolder.root, "b.m4a").absolutePath

        registry.registerLivePath(pathA)
        val scan1 = registry.beginReconciliation()
        registry.finishLivePath(pathA)

        registry.registerLivePath(pathB)
        val scan2 = registry.beginReconciliation()
        registry.finishLivePath(pathB)

        assertTrue(registry.isLiveOrTouched(pathA, scan1))
        assertTrue(registry.isLiveOrTouched(pathB, scan1))
        assertFalse(registry.isLiveOrTouched(pathA, scan2))
        assertTrue(registry.isLiveOrTouched(pathB, scan2))

        registry.endReconciliation(scan1)
        registry.endReconciliation(scan2)

        val scan3 = registry.beginReconciliation()
        try {
            assertFalse(registry.isLiveOrTouched(pathA, scan3))
            assertFalse(registry.isLiveOrTouched(pathB, scan3))
        } finally {
            registry.endReconciliation(scan3)
        }
    }

    @Test
    fun multipleIdsMappedToSamePathAreAllClearedOnUnregisterPath() {
        val registry = RecordingRegistry()
        val path = File(tempFolder.root, "shared.m4a").absolutePath
        registry.registerLivePath(path)
        registry.bindIdToLivePath(1L, path)
        registry.bindIdToLivePath(2L, path)

        registry.unregisterLivePath(path)
        val session = registry.beginReconciliation()
        try {
            assertFalse(registry.isLiveOrTouched(path, session))
        } finally {
            registry.endReconciliation(session)
        }
    }
}
