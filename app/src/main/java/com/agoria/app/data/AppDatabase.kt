package com.agoria.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AgentEntity::class,
        DiaryEntryEntity::class,
        MessageEntity::class,
        TaskEntity::class,
        ApprovalEntity::class,
        ConnectorEntity::class,
        ConnectorRequestEntity::class,
        BuildEntity::class,
        ProviderEntity::class,
        SettingsEntity::class,
        MemoryEntity::class,
        ConnectorLogEntity::class,
        RevenueEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun diaryDao(): DiaryDao
    abstract fun messageDao(): MessageDao
    abstract fun taskDao(): TaskDao
    abstract fun approvalDao(): ApprovalDao
    abstract fun connectorDao(): ConnectorDao
    abstract fun connectorRequestDao(): ConnectorRequestDao
    abstract fun buildDao(): BuildDao
    abstract fun providerDao(): ProviderDao
    abstract fun settingsDao(): SettingsDao
    abstract fun memoryDao(): MemoryDao
    abstract fun connectorLogDao(): ConnectorLogDao
    abstract fun revenueDao(): RevenueDao

    companion object {
        const val NAME = "agoria.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}