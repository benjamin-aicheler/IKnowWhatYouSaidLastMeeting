package io.github.benjamin_aicheler.lastmeeting

import android.app.Application
import android.util.Log
import io.github.benjamin_aicheler.lastmeeting.data.db.MeetingDatabase
import io.github.benjamin_aicheler.lastmeeting.data.preferences.SettingsRepository
import io.github.benjamin_aicheler.lastmeeting.repository.MeetingRepository
import io.github.benjamin_aicheler.lastmeeting.util.PdfExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LastMeetingApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: MeetingDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var meetingRepository: MeetingRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = MeetingDatabase.getDatabase(this)
        settingsRepository = SettingsRepository(this)
        meetingRepository = MeetingRepository(
            context = this,
            meetingDao = database.meetingDao(),
            settingsRepository = settingsRepository
        )

        // A killed process can leave an unfinished row or a recording without one; repair that
        // before the meeting list is shown.
        applicationScope.launch {
            try {
                PdfExporter.pruneExpiredSharedPdfs(cacheDir)
            } catch (e: Exception) {
                Log.w("LastMeetingApp", "Failed to prune expired shared PDFs", e)
            }
            try {
                meetingRepository.reconcileRecordings()
            } catch (e: Exception) {
                Log.e("LastMeetingApp", "Failed to reconcile recordings", e)
            }
        }
    }
}
