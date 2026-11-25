package com.agoria.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agoria.app.util.Time
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = Repository(db, context)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun ensureSeedAgents_freshDatabase_insertsAllSeedAgents() = runBlocking {
        // Fresh DB, no seed data
        repo.ensureSeedAgents()

        val agents = db.agentDao().getAll()
        assertEquals(10, agents.size) // 10 seed agents

        val platformEngineer = db.agentDao().get("seed-platform-engineer")
        assertNotNull(platformEngineer)
        assertTrue(platformEngineer!!.isUndeletable)
        assertTrue(platformEngineer.allowedConnectors.contains("github"))

        val diary = db.diaryDao().recent(100)
        assertTrue(diary.any { it.agentId == "seed-platform-engineer" })

        val memories = db.memoryDao().recentForAgent("seed-platform-engineer", 10)
        assertTrue(memories.any { it.kind == "identity" })
    }

    @Test
    fun ensureSeedAgents_upgradedDatabase_addsMissingPlatformEngineer() = runBlocking {
        // Simulate upgraded DB with seedVersion=1 but missing Platform Engineer
        val now = Time.now()
        val settings = SettingsEntity(
            id = 1, autonomyEnabled = false, tickIntervalSeconds = 120,
            agentCooldownSeconds = 90, setupComplete = false, userName = "Boss",
            officeName = "Dunder", theme = "system", maxTokens = 1600,
            seedVersion = 1, autoApproveEnabled = false, autoApproveTypes = "",
            createdAt = now
        )
        db.settingsDao().upsert(settings)

        // Add some agents but NOT the Platform Engineer
        val existingAgents = SeedData.agents(now).filter { it.id != "seed-platform-engineer" }
        existingAgents.forEach { db.agentDao().insert(it) }

        // Now run ensureSeedAgents
        repo.ensureSeedAgents()

        val allAgents = db.agentDao().getAll()
        assertEquals(10, allAgents.size) // Should now have all 10

        val platformEngineer = db.agentDao().get("seed-platform-engineer")
        assertNotNull(platformEngineer)
        assertTrue(platformEngineer!!.isUndeletable)
        assertTrue(platformEngineer.allowedConnectors.contains("github"))
    }

    @Test
    fun ensureSeedAgents_existingPlatformEngineer_enforcesUndeletableAndGithub() = runBlocking {
        val now = Time.now()
        // Existing Platform Engineer without isUndeletable and without github
        val existing = AgentEntity(
            id = "seed-platform-engineer",
            name = "Platform Engineer",
            role = "Platform Engineer",
            persona = "test",
            goal = "test",
            avatarIndex = 8,
            status = "idle",
            autonomy = true,
            memory = "test",
            lastTickAt = 0L,
            failureCount = 0,
            lastError = null,
            createdAt = now,
            updatedAt = now,
            allowedConnectors = "",
            isUndeletable = false,
        )
        db.agentDao().insert(existing)

        repo.ensureSeedAgents()

        val updated = db.agentDao().get("seed-platform-engineer")
        assertNotNull(updated)
        assertTrue(updated!!.isUndeletable)
        assertTrue(updated.allowedConnectors.contains("github"))
    }

    @Test
    fun ensureSeedAgents_existingPlatformEngineerWithGithub_keepsGithub() = runBlocking {
        val now = Time.now()
        val existing = AgentEntity(
            id = "seed-platform-engineer",
            name = "Platform Engineer",
            role = "Platform Engineer",
            persona = "test",
            goal = "test",
            avatarIndex = 8,
            status = "idle",
            autonomy = true,
            memory = "test",
            lastTickAt = 0L,
            failureCount = 0,
            lastError = null,
            createdAt = now,
            updatedAt = now,
            allowedConnectors = "github,web",
            isUndeletable = false,
        )
        db.agentDao().insert(existing)

        repo.ensureSeedAgents()

        val updated = db.agentDao().get("seed-platform-engineer")
        assertNotNull(updated)
        assertTrue(updated!!.isUndeletable)
        assertTrue(updated.allowedConnectors.contains("github"))
        assertTrue(updated.allowedConnectors.contains("web"))
    }
}