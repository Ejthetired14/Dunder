package com.agoria.app.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

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
        ProjectEntity::class,
        ProviderEntity::class,
        SettingsEntity::class,
        MemoryEntity::class,
        ConnectorLogEntity::class,
        RevenueEntity::class,
        SkillEntity::class,
        AgentSkillEntity::class,
    ],
    version = 6,
    exportSchema = true,
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
    abstract fun projectDao(): ProjectDao
    abstract fun providerDao(): ProviderDao
    abstract fun settingsDao(): SettingsDao
    abstract fun memoryDao(): MemoryDao
    abstract fun connectorLogDao(): ConnectorLogDao
    abstract fun revenueDao(): RevenueDao
    abstract fun skillDao(): SkillDao
    abstract fun agentSkillDao(): AgentSkillDao

    companion object {
        const val NAME = "agoria.db"
        const val VERSION = 6

        fun create(context: Context): AppDatabase {
            val database = build(context)
            return try {
                // Open eagerly: a migration that cannot run surfaces here instead of crashing
                // whichever screen happens to touch the database first.
                database.openHelper.writableDatabase
                database
            } catch (t: Throwable) {
                Log.e(TAG, "Database upgrade failed, keeping the old file and starting fresh", t)
                runCatching { database.close() }
                val file = context.getDatabasePath(NAME)
                if (file.exists()) {
                    file.renameTo(File(file.parentFile, "$NAME.failed-${System.currentTimeMillis()}"))
                }
                build(context).also { it.openHelper.writableDatabase }
            }
        }

        private const val TAG = "AppDatabase"

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // An upgrade keeps every row. Only an actual downgrade (someone sideloading an
                // older APK over a newer one) falls back, because Room refuses to open a database
                // written by a newer schema.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}

private fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
    query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
        it.moveToFirst()
    }

private fun SupportSQLiteDatabase.columns(table: String): Set<String> {
    val names = mutableSetOf<String>()
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (nameIndex >= 0) names += cursor.getString(nameIndex)
        }
    }
    return names
}

private fun SupportSQLiteDatabase.addColumn(table: String, column: String, definition: String) {
    if (!hasTable(table)) return
    if (columns(table).contains(column)) return
    execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
}

/**
 * 1 -> 2 (the monetization release). Its schema changed in place: approvals lost two columns,
 * builds was rebuilt, and a few indices moved. Rebuilding the two tables keeps existing rows.
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.hasTable("approvals") && db.columns("approvals").contains("decisionNote")) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `approvals_new` (" +
                    "`id` TEXT NOT NULL, `agentId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `summary` TEXT NOT NULL, `details` TEXT NOT NULL, " +
                    "`risk` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`decidedAt` INTEGER, `result` TEXT, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`agentId`) REFERENCES `agents`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO `approvals_new` " +
                    "(`id`, `agentId`, `type`, `title`, `summary`, `details`, `risk`, `status`, " +
                    "`createdAt`, `decidedAt`, `result`) " +
                    "SELECT `id`, `agentId`, `type`, `title`, `summary`, `details`, `risk`, " +
                    "`status`, `createdAt`, `decidedAt`, `result` FROM `approvals`",
            )
            db.execSQL("DROP TABLE `approvals`")
            db.execSQL("ALTER TABLE `approvals_new` RENAME TO `approvals`")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_approvals_agentId` ON `approvals` (`agentId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_approvals_status` ON `approvals` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_approvals_createdAt` ON `approvals` (`createdAt`)")

        if (db.hasTable("builds") && db.columns("builds").contains("approvalId")) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `builds_new` (" +
                    "`id` TEXT NOT NULL, `agentId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, `fileCount` INTEGER NOT NULL, " +
                    "`zipPath` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`completedAt` INTEGER, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO `builds_new` " +
                    "(`id`, `agentId`, `name`, `description`, `fileCount`, `zipPath`, `status`, " +
                    "`createdAt`, `completedAt`) " +
                    "SELECT `id`, `agentId`, `name`, `description`, `fileCount`, `zipPath`, " +
                    "'ready', `createdAt`, `createdAt` FROM `builds`",
            )
            db.execSQL("DROP TABLE `builds`")
            db.execSQL("ALTER TABLE `builds_new` RENAME TO `builds`")
        }

        db.execSQL("DROP INDEX IF EXISTS `index_connectors_status`")
        db.execSQL("DROP INDEX IF EXISTS `index_providers_priority`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_createdAt` ON `tasks` (`createdAt`)")

        db.addColumn("settings", "agentProfitSharePercent", "INTEGER NOT NULL DEFAULT 50")
        db.addColumn("settings", "userProfitSharePercent", "INTEGER NOT NULL DEFAULT 50")
        db.addColumn("settings", "infrastructureFundCents", "INTEGER NOT NULL DEFAULT 0")
        db.addColumn("settings", "stripeAccountId", "TEXT")
        db.addColumn("settings", "stripePublishableKey", "TEXT")
        db.addColumn("settings", "autoApproveEnabled", "INTEGER NOT NULL DEFAULT 0")
        db.addColumn("settings", "autoApproveTypes", "TEXT NOT NULL DEFAULT ''")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `revenue` (" +
                "`id` TEXT NOT NULL, `agentId` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`amountCents` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
                "`metadata` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`agentId`) REFERENCES `agents`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_revenue_agentId` ON `revenue` (`agentId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_revenue_createdAt` ON `revenue` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_revenue_source` ON `revenue` (`source`)")
    }
}

/** 2 -> 3: per-agent connector grants and an optional per-agent API token. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumn("agents", "allowedConnectors", "TEXT NOT NULL DEFAULT ''")
        db.addColumn("agents", "apiToken", "TEXT NOT NULL DEFAULT ''")
    }
}

/** 3 -> 4: one shared project folder for the office. */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `projects` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                "`leadAgentId` TEXT NOT NULL, `status` TEXT NOT NULL, `folder` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.addColumn("tasks", "projectId", "TEXT NOT NULL DEFAULT ''")
        db.addColumn("builds", "projectId", "TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_projectId` ON `tasks` (`projectId`)")

        // Version 4 drops the builds->agents foreign key and the agentId/createdAt indices, and
        // drops both the foreign key and the indices on memories. A database written before that
        // still has them, and Room rejects the upgrade because the tables do not match the
        // entities, so both tables are rebuilt into their version 4 shape here.
        db.rebuildBuilds()
        db.rebuildMemories()
    }
}

private fun SupportSQLiteDatabase.rebuildBuilds() {
    if (!hasTable("builds")) return
    val project = if (columns("builds").contains("projectId")) "`projectId`" else "''"
    execSQL(
        "CREATE TABLE IF NOT EXISTS `builds_v4` (`id` TEXT NOT NULL, `agentId` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, `description` TEXT NOT NULL, `fileCount` INTEGER NOT NULL, " +
            "`zipPath` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`completedAt` INTEGER, `projectId` TEXT NOT NULL, PRIMARY KEY(`id`))",
    )
    execSQL(
        "INSERT OR REPLACE INTO `builds_v4` (`id`, `agentId`, `name`, `description`, " +
            "`fileCount`, `zipPath`, `status`, `createdAt`, `completedAt`, `projectId`) " +
            "SELECT `id`, `agentId`, `name`, `description`, `fileCount`, `zipPath`, `status`, " +
            "`createdAt`, `completedAt`, $project FROM `builds`",
    )
    execSQL("DROP TABLE `builds`")
    execSQL("ALTER TABLE `builds_v4` RENAME TO `builds`")
    execSQL("CREATE INDEX IF NOT EXISTS `index_builds_projectId` ON `builds` (`projectId`)")
}

private fun SupportSQLiteDatabase.rebuildMemories() {
    if (!hasTable("memories")) return
    execSQL(
        "CREATE TABLE IF NOT EXISTS `memories_v4` (`id` TEXT NOT NULL, `agentId` TEXT NOT NULL, " +
            "`kind` TEXT NOT NULL, `content` TEXT NOT NULL, `importance` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )
    execSQL(
        "INSERT OR REPLACE INTO `memories_v4` (`id`, `agentId`, `kind`, `content`, " +
            "`importance`, `createdAt`) SELECT `id`, `agentId`, `kind`, `content`, `importance`, " +
            "`createdAt` FROM `memories`",
    )
    execSQL("DROP TABLE `memories`")
    execSQL("ALTER TABLE `memories_v4` RENAME TO `memories`")
}

/** 4 -> 5: add isUndeletable column to agents for the Platform Engineer. */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumn("agents", "isUndeletable", "INTEGER NOT NULL DEFAULT 0")
    }
}

/** 5 -> 6: add skills and agent_skills tables for the Skills system. */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `skills` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`version` TEXT NOT NULL, " +
                "`author` TEXT NOT NULL, " +
                "`manifestJson` TEXT NOT NULL, " +
                "`installedAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_skills_category` ON `skills` (`category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_skills_id` ON `skills` (`id`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `agent_skills` (" +
                "`agentId` TEXT NOT NULL, " +
                "`skillId` TEXT NOT NULL, " +
                "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                "`configJson` TEXT NOT NULL DEFAULT '{}', " +
                "`installedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`agentId`, `skillId`), " +
                "FOREIGN KEY(`agentId`) REFERENCES `agents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`skillId`) REFERENCES `skills`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_skills_agentId` ON `agent_skills` (`agentId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_skills_skillId` ON `agent_skills` (`skillId`)")
    }
}
