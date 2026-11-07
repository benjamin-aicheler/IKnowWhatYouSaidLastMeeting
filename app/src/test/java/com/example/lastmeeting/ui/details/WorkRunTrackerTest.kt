package com.example.lastmeeting.ui.details

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WorkRunTrackerTest {

    private val tracker = WorkRunTracker()
    private val olderRun = UUID.randomUUID()
    private val newerRun = UUID.randomUUID()

    @Test
    fun reportsCompletionOfARunThatWasSeenRunning() {
        assertFalse(tracker.record(newerRun, isFinished = false))
        assertTrue(tracker.record(newerRun, isFinished = true))
    }

    @Test
    fun reportsEachRunOnlyOnce() {
        tracker.record(newerRun, isFinished = false)

        assertTrue(tracker.record(newerRun, isFinished = true))
        assertFalse(tracker.record(newerRun, isFinished = true))
    }

    @Test
    fun anOlderFinishedRunDoesNotMaskANewerOne() {
        // The kept run stays in the list, finished, before the new one is enqueued.
        assertFalse(tracker.record(olderRun, isFinished = true))
        assertFalse(tracker.record(newerRun, isFinished = false))

        assertTrue(tracker.record(newerRun, isFinished = true))
    }

    @Test
    fun ignoresRunsThatWereNeverSeenRunning() {
        // Opening the details screen of an already processed meeting must not toast.
        assertFalse(tracker.record(olderRun, isFinished = true))
    }
}
