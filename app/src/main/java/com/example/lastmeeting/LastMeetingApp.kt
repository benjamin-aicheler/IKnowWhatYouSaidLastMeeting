package com.example.lastmeeting

import android.app.Application
import com.example.lastmeeting.data.db.MeetingDatabase
import com.example.lastmeeting.data.preferences.SettingsRepository
import com.example.lastmeeting.repository.MeetingRepository
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
                meetingRepository.reconcileRecordings()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
