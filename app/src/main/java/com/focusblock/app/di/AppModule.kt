package com.focusblock.app.di

import android.content.Context
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FocusBlockDatabase {
        return FocusBlockDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideBlockedAppDao(database: FocusBlockDatabase): BlockedAppDao {
        return database.blockedAppDao()
    }

    @Provides
    @Singleton
    fun provideScheduleDao(database: FocusBlockDatabase): ScheduleDao {
        return database.scheduleDao()
    }

    @Provides
    @Singleton
    fun provideQuickBlockSessionDao(database: FocusBlockDatabase): QuickBlockSessionDao {
        return database.quickBlockSessionDao()
    }

    @Provides
    @Singleton
    fun provideBlockLogDao(database: FocusBlockDatabase): BlockLogDao {
        return database.blockLogDao()
    }

    @Provides
    @Singleton
    fun provideUsageStatDao(database: FocusBlockDatabase): UsageStatDao {
        return database.usageStatDao()
    }

    @Provides
    @Singleton
    fun provideSettingsDao(database: FocusBlockDatabase): SettingsDao {
        return database.settingsDao()
    }

    @Provides
    @Singleton
    fun providePomodoroSessionDao(database: FocusBlockDatabase): PomodoroSessionDao {
        return database.pomodoroSessionDao()
    }

    @Provides
    @Singleton
    fun provideAppTimeLimitDao(database: FocusBlockDatabase): AppTimeLimitDao {
        return database.appTimeLimitDao()
    }

    @Provides
    @Singleton
    fun provideDailyUsageDao(database: FocusBlockDatabase): DailyUsageDao {
        return database.dailyUsageDao()
    }

    @Provides
    @Singleton
    fun provideExcludedAppDao(database: FocusBlockDatabase): ExcludedAppDao {
        return database.excludedAppDao()
    }

    @Provides
    @Singleton
    fun provideFocusCycleDao(database: FocusBlockDatabase): FocusCycleDao {
        return database.focusCycleDao()
    }

    @Provides
    @Singleton
    fun provideFocusCycleOverrideDao(database: FocusBlockDatabase): FocusCycleOverrideDao {
        return database.focusCycleOverrideDao()
    }

    @Provides
    @Singleton
    fun provideAppTimerSettingsDao(database: FocusBlockDatabase): AppTimerSettingsDao {
        return database.appTimerSettingsDao()
    }

    @Provides
    @Singleton
    fun provideAppTimerDailyUsageDao(database: FocusBlockDatabase): AppTimerDailyUsageDao {
        return database.appTimerDailyUsageDao()
    }

    @Provides
    @Singleton
    fun provideGlobalDailyLimitSettingsDao(database: FocusBlockDatabase): GlobalDailyLimitSettingsDao {
        return database.globalDailyLimitSettingsDao()
    }

    @Provides
    @Singleton
    fun provideGlobalDailyUsageDao(database: FocusBlockDatabase): GlobalDailyUsageDao {
        return database.globalDailyUsageDao()
    }

    @Provides
    @Singleton
    fun provideDailyUsageSummaryDao(database: FocusBlockDatabase): DailyUsageSummaryDao {
        return database.dailyUsageSummaryDao()
    }

    // Smart Suggestions DAOs
    @Provides
    @Singleton
    fun provideSmartSuggestionsSettingsDao(database: FocusBlockDatabase): SmartSuggestionsSettingsDao {
        return database.smartSuggestionsSettingsDao()
    }

    @Provides
    @Singleton
    fun provideEssentialAppWhitelistDao(database: FocusBlockDatabase): EssentialAppWhitelistDao {
        return database.essentialAppWhitelistDao()
    }

    @Provides
    @Singleton
    fun provideSuggestedBlockingAppDao(database: FocusBlockDatabase): SuggestedBlockingAppDao {
        return database.suggestedBlockingAppDao()
    }

    // Bedtime Mode
    @Provides
    @Singleton
    fun provideBedtimeModeSettingsDao(database: FocusBlockDatabase): BedtimeModeSettingsDao {
        return database.bedtimeModeSettingsDao()
    }

    // App Groups
    @Provides
    @Singleton
    fun provideAppGroupDao(database: FocusBlockDatabase): AppGroupDao {
        return database.appGroupDao()
    }

    @Provides
    @Singleton
    fun provideAppGroupMembershipDao(database: FocusBlockDatabase): AppGroupMembershipDao {
        return database.appGroupMembershipDao()
    }

    // Onboarding
    @Provides
    @Singleton
    fun provideOnboardingDao(database: FocusBlockDatabase): OnboardingDao {
        return database.onboardingDao()
    }
}
