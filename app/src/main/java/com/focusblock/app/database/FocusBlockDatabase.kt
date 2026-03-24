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
        FocusCycleOverride::class,
        AppTimerSettings::class,
        AppTimerDailyUsage::class,
        // Entities for Global Daily Limit and Usage Comparison
        GlobalDailyLimitSettings::class,
        GlobalDailyUsage::class,
        DailyUsageSummary::class,
        // Smart Suggestions entities
        SmartSuggestionsSettings::class,
        EssentialAppWhitelist::class,
        SuggestedBlockingApp::class,
        // Bedtime Mode
        BedtimeModeSettings::class,
        // App Groups
        AppGroup::class,
        AppGroupMembership::class,
        // Onboarding
        OnboardingState::class
    ],
    version = 12,
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
    abstract fun appTimerSettingsDao(): AppTimerSettingsDao
    abstract fun appTimerDailyUsageDao(): AppTimerDailyUsageDao
    // DAOs for Global Daily Limit and Usage Comparison
    abstract fun globalDailyLimitSettingsDao(): GlobalDailyLimitSettingsDao
    abstract fun globalDailyUsageDao(): GlobalDailyUsageDao
    abstract fun dailyUsageSummaryDao(): DailyUsageSummaryDao
    // Smart Suggestions DAOs
    abstract fun smartSuggestionsSettingsDao(): SmartSuggestionsSettingsDao
    abstract fun essentialAppWhitelistDao(): EssentialAppWhitelistDao
    abstract fun suggestedBlockingAppDao(): SuggestedBlockingAppDao
    // Bedtime Mode
    abstract fun bedtimeModeSettingsDao(): BedtimeModeSettingsDao
    // App Groups
    abstract fun appGroupDao(): AppGroupDao
    abstract fun appGroupMembershipDao(): AppGroupMembershipDao
    // Onboarding
    abstract fun onboardingDao(): OnboardingDao

    // Combined DAO access for widget and services
    abstract fun focusBlockDao(): FocusBlockDao

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

        // Alias for getDatabase
        fun getInstance(context: Context): FocusBlockDatabase = getDatabase(context)
    }
}
