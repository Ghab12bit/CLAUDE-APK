package com.focusblock.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.focusblock.app.database.dao.*
import com.focusblock.app.database.entity.*

@Database(
    entities = [
        BlockedApp::class,
        Schedule::class,
        QuickBlockSession::class,
        BlockLog::class,
        UsageStat::class,
        AppSettings::class,
        PomodoroSession::class,
        AppTimeLimit::class,
        DailyUsage::class,
        ExcludedApp::class,
        FocusCycle::class,
        FocusCycleOverride::class
    ],
    version = 3,
    exportSchema = false
)
abstract class FocusBlockDatabase : RoomDatabase() {

    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun quickBlockSessionDao(): QuickBlockSessionDao
    abstract fun blockLogDao(): BlockLogDao
    abstract fun usageStatDao(): UsageStatDao
    abstract fun settingsDao(): SettingsDao
    abstract fun pomodoroSessionDao(): PomodoroSessionDao
    abstract fun appTimeLimitDao(): AppTimeLimitDao
    abstract fun dailyUsageDao(): DailyUsageDao
    abstract fun excludedAppDao(): ExcludedAppDao
    abstract fun focusCycleDao(): FocusCycleDao
    abstract fun focusCycleOverrideDao(): FocusCycleOverrideDao

    companion object {
        @Volatile
        private var INSTANCE: FocusBlockDatabase? = null

        fun getDatabase(context: Context): FocusBlockDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FocusBlockDatabase::class.java,
                    "focusblock_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
