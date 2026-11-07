package com.example.lastmeeting.ui.details

import java.util.UUID

/**
 * Tracks the runs of one unique work name so that each run's completion is reported once.
 *
 * WorkManager returns every run of a unique work name, oldest first, while
 * [androidx.work.ExistingWorkPolicy.KEEP] keeps finished runs in that list. A finished older
 * run must therefore not mask a newer one.
 */
internal class WorkRunTracker {

    private val activeRuns = mutableSetOf<UUID>()

    /**
     * Records the observed state of [runId] and returns true when this observation completes a
     * run that had previously been seen unfinished.
     */
    fun record(runId: UUID, isFinished: Boolean): Boolean {
        if (!isFinished) {
            activeRuns.add(runId)
            return false
        }
        return activeRuns.remove(runId)
    }
}
