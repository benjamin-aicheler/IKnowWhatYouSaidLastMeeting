package io.github.benjamin_aicheler.lastmeeting.repository

import java.io.File

/**
 * Coordinates live recording paths/IDs with concurrent [MeetingRepository.reconcileRecordings]
 * scans under a single monitor so startup recovery never closes or adopts an in-flight recording.
 */
internal class RecordingRegistry {

    class ReconciliationSession internal constructor(
        internal val skippedPaths: MutableSet<String>
    )

    private val lock = Any()
    private val activeRecordingPaths = mutableSetOf<String>()
    private val activeRecordingIdsToPaths = mutableMapOf<Long, String>()
    private val activeReconciliations = mutableListOf<MutableSet<String>>()

    fun createAndRegisterLiveFile(createFile: () -> File): File =
        synchronized(lock) {
            createFile().also { outputFile ->
                markRecordingLiveLocked(outputFile.absolutePath)
            }
        }

    fun registerLivePath(path: String) {
        synchronized(lock) {
            markRecordingLiveLocked(path)
        }
    }

    fun unregisterLivePath(path: String) {
        synchronized(lock) {
            removeLivePathLocked(path)
        }
    }

    fun markTouchedDuringReconciliation(path: String) {
        synchronized(lock) {
            activeReconciliations.forEach { it.add(path) }
        }
    }

    fun bindIdToLivePath(id: Long, path: String) {
        synchronized(lock) {
            activeReconciliations.forEach { it.add(path) }
            if (path in activeRecordingPaths) {
                activeRecordingIdsToPaths[id] = path
            }
        }
    }

    fun finishLivePath(path: String) {
        synchronized(lock) {
            activeReconciliations.forEach { it.add(path) }
            removeLivePathLocked(path)
        }
    }

    fun unregisterLiveId(id: Long) {
        synchronized(lock) {
            activeRecordingIdsToPaths.remove(id)?.let { path ->
                if (path !in activeRecordingIdsToPaths.values) {
                    activeRecordingPaths.remove(path)
                }
            }
        }
    }

    fun beginReconciliation(): ReconciliationSession =
        synchronized(lock) {
            val skipped = activeRecordingPaths.toMutableSet()
            activeReconciliations.add(skipped)
            ReconciliationSession(skipped)
        }

    fun isLiveOrTouched(path: String, session: ReconciliationSession): Boolean =
        synchronized(lock) {
            path in session.skippedPaths || path in activeRecordingPaths
        }

    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    fun endReconciliation(session: ReconciliationSession) {
        synchronized(lock) {
            activeReconciliations.remove(session.skippedPaths)
        }
    }

    private fun markRecordingLiveLocked(path: String) {
        activeRecordingPaths.add(path)
        activeReconciliations.forEach { it.add(path) }
    }

    private fun removeLivePathLocked(path: String) {
        activeRecordingPaths.remove(path)
        val iterator = activeRecordingIdsToPaths.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value == path) {
                iterator.remove()
            }
        }
    }
}
