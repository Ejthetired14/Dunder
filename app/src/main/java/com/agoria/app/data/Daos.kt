package com.agoria.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents ORDER BY createdAt ASC")
    suspend fun getAll(): List<AgentEntity>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun get(id: String): AgentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(agent: AgentEntity)

    @Update
    suspend fun update(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM agents")
    suspend fun deleteAll()
}

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE agentId = :agentId ORDER BY createdAt DESC")
    fun observeForAgent(agentId: String): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE agentId = :agentId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentForAgent(agentId: String, limit: Int): List<DiaryEntryEntity>
","oldString">
    @Query("SELECT * FROM diary_entries WHERE agentId = :agentId ORDER BY createdAt DESC")
    fun observeForAgent(agentId: String): Flow<List<DiaryEntryEntity>>
","path":"/workspace/app/app/src/main/java/com/agoria/app/data/Daos.kt
    @Query("SELECT * FROM diary_entries WHERE agentId = :agentId ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun pagedForAgent(agentId: String, limit: Int, offset: Int): List<DiaryEntryEntity>

    @Query("SELECT COUNT(*) FROM diary_entries WHERE agentId = :agentId")
    suspend fun countForAgent(agentId: String): Int

    @Query("SELECT * FROM diary_entries ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DiaryEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DiaryEntryEntity)

    @Query("DELETE FROM diary_entries")
    suspend fun deleteAll()

    @Query("SELECT * FROM diary_entries WHERE kind = 'say' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentSayEntries(limit: Int): List<DiaryEntryEntity>
","oldString">
    @Query("SELECT * FROM diary_entries ORDER BY createdAt ASC")
    suspend fun getAll(): List<DiaryEntryEntity>
","path":"/workspace/app/app/src/main/java/com/agoria/app/data/Daos.kt}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE agentId = :agentId ORDER BY createdAt ASC")
    fun observeForAgent(agentId: String): Flow<List<MessageEntity>>

    // Newest first, so page 0 is the live tail of the conversation and later pages walk backwards
    // into history. The chat renders this order directly in a reversed LazyColumn.
    @Query("SELECT * FROM messages WHERE agentId = :agentId ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun pagedForAgent(agentId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE agentId = :agentId")
    suspend fun countForAgent(agentId: String): Int

    @Query("SELECT * FROM messages WHERE agentId = :agentId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentForAgent(agentId: String, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT * FROM messages ORDER BY createdAt ASC")
    suspend fun getAll(): List<MessageEntity>
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE agentId = :agentId ORDER BY updatedAt DESC")
    fun observeForAgent(agentId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeForProject(projectId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()

    @Query("SELECT * FROM tasks ORDER BY createdAt ASC")
    suspend fun getAll(): List<TaskEntity>
}

@Dao
interface ApprovalDao {
    @Query("SELECT * FROM approvals ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ApprovalEntity>>

    @Query("SELECT * FROM approvals WHERE status IN ('pending', 'auto_pending') ORDER BY createdAt ASC")
    fun observePending(): Flow<List<ApprovalEntity>>

    @Query("SELECT * FROM approvals WHERE agentId = :agentId ORDER BY createdAt DESC")
    fun observeForAgent(agentId: String): Flow<List<ApprovalEntity>>

    @Query("SELECT * FROM approvals WHERE id = :id")
    suspend fun get(id: String): ApprovalEntity?

    @Query("SELECT * FROM approvals WHERE agentId = :agentId AND status IN ('pending', 'auto_pending')")
    suspend fun pendingForAgent(agentId: String): List<ApprovalEntity>

    @Query("SELECT * FROM approvals WHERE status IN ('pending', 'auto_pending') ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<ApprovalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(approval: ApprovalEntity)

    @Update
    suspend fun update(approval: ApprovalEntity)

    @Query("DELETE FROM approvals")
    suspend fun deleteAll()

    @Query("SELECT * FROM approvals ORDER BY createdAt ASC")
    suspend fun getAll(): List<ApprovalEntity>
}

@Dao
interface ConnectorDao {
    @Query("SELECT * FROM connectors ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ConnectorEntity>>

    @Query("SELECT * FROM connectors WHERE id = :id")
    suspend fun get(id: String): ConnectorEntity?

    @Query("SELECT * FROM connectors")
    suspend fun getAll(): List<ConnectorEntity>

    @Query("DELETE FROM connectors WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connector: ConnectorEntity)

    @Update
    suspend fun update(connector: ConnectorEntity)

    @Query("DELETE FROM connectors")
    suspend fun deleteAll()
}

@Dao
interface ConnectorRequestDao {
    @Query("SELECT * FROM connector_requests ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ConnectorRequestEntity>>

    @Query("SELECT * FROM connector_requests WHERE status = 'pending' ORDER BY createdAt ASC")
    fun observePending(): Flow<List<ConnectorRequestEntity>>

    @Query("SELECT * FROM connector_requests WHERE agentId = :agentId AND status = 'pending'")
    suspend fun pendingForAgent(agentId: String): List<ConnectorRequestEntity>

    @Query("SELECT * FROM connector_requests WHERE status = 'pending' ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<ConnectorRequestEntity>

    @Query("SELECT * FROM connector_requests WHERE connectorId = :connectorId AND status = 'pending' ORDER BY createdAt DESC LIMIT 1")
    suspend fun pendingForConnector(connectorId: String): ConnectorRequestEntity?

    @Query("SELECT * FROM connector_requests WHERE id = :id")
    suspend fun get(id: String): ConnectorRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(request: ConnectorRequestEntity)

    @Update
    suspend fun update(request: ConnectorRequestEntity)

    @Query("DELETE FROM connector_requests")
    suspend fun deleteAll()
}

@Dao
interface BuildDao {
    @Query("SELECT * FROM builds ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BuildEntity>>

    @Query("SELECT * FROM builds WHERE agentId = :agentId ORDER BY createdAt DESC")
    fun observeForAgent(agentId: String): Flow<List<BuildEntity>>

    @Query("SELECT * FROM builds WHERE agentId = :agentId ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun pagedForAgent(agentId: String, limit: Int, offset: Int): List<BuildEntity>

    @Query("SELECT * FROM builds WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeForProject(projectId: String): Flow<List<BuildEntity>>

    @Query("SELECT * FROM builds ORDER BY createdAt ASC")
    suspend fun getAll(): List<BuildEntity>

    @Query("SELECT COUNT(*) FROM builds WHERE agentId = :agentId")
    suspend fun countForAgent(agentId: String): Int

    @Query("SELECT * FROM builds WHERE id = :id")
    suspend fun get(id: String): BuildEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(build: BuildEntity)

    @Query("DELETE FROM builds")
    suspend fun deleteAll()
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY priority ASC, id ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY priority ASC, id ASC")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun get(id: String): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: ProviderEntity)

    @Update
    suspend fun update(provider: ProviderEntity)

    @Query("DELETE FROM providers")
    suspend fun deleteAll()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SettingsEntity)

    @Query("DELETE FROM settings")
    suspend fun deleteAll()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE agentId = :agentId ORDER BY createdAt DESC")
    fun observeForAgent(agentId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE agentId = :agentId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentForAgent(agentId: String, limit: Int): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()

    @Query("SELECT * FROM memories ORDER BY createdAt ASC")
    suspend fun getAll(): List<MemoryEntity>
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY createdAt ASC")
    suspend fun getAll(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE leadAgentId = :agentId LIMIT 1")
    suspend fun forLead(agentId: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM projects")
    suspend fun deleteAll()
}

@Dao
interface ConnectorLogDao {
    @Query("SELECT * FROM connector_logs ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ConnectorLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ConnectorLogEntity)

    @Query("DELETE FROM connector_logs")
    suspend fun deleteAll()
}

@Dao
interface RevenueDao {
    @Query("SELECT * FROM revenue ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RevenueEntity>>

    @Query("SELECT * FROM revenue WHERE agentId = :agentId ORDER BY createdAt DESC")
    fun observeForAgent(agentId: String): Flow<List<RevenueEntity>>

    @Query("SELECT * FROM revenue WHERE agentId = :agentId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentForAgent(agentId: String, limit: Int): List<RevenueEntity>

    @Query("SELECT SUM(amountCents) FROM revenue WHERE agentId = :agentId")
    suspend fun totalForAgent(agentId: String): Long?

    @Query("SELECT SUM(amountCents) FROM revenue")
    suspend fun totalAll(): Long?

    @Query("SELECT * FROM revenue WHERE source = :source ORDER BY createdAt DESC LIMIT :limit")
    suspend fun bySource(source: String, limit: Int): List<RevenueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(revenue: RevenueEntity)

    @Query("DELETE FROM revenue")
    suspend fun deleteAll()
}