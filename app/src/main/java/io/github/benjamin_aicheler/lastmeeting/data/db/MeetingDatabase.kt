package io.github.benjamin_aicheler.lastmeeting.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MeetingEntity::class],
    version = 4,
    exportSchema = true
)
abstract class MeetingDatabase : RoomDatabase() {

    abstract fun meetingDao(): MeetingDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meetings ADD COLUMN transcriptGeneration INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meetings ADD COLUMN protocolGeneration INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meetings ADD COLUMN isDamaged INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meetings ADD COLUMN transcriptError TEXT")
                db.execSQL("ALTER TABLE meetings ADD COLUMN protocolError TEXT")
            }
        }

        @Volatile
        private var INSTANCE: MeetingDatabase? = null

        fun getDatabase(context: Context): MeetingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MeetingDatabase::class.java,
                    "last_meeting_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
