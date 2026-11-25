package com.agoria.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    var helper: MigrationTestHelper = MigrationTestHelper(
        ApplicationProvider.getApplicationContext(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before
    fun setup() {
        // Ensure the helper is initialized
    }

    @Test
    @Throws(IOException::class)
    fun migration5to6_createsSkillsAndAgentSkillsTables() {
        // Create database at version 5
        val db = helper.createDatabase("test_db", AppDatabase.VERSION - 1) // version 5

        // Verify old schema exists (agents table with isUndeletable)
        var cursor = db.query("PRAGMA table_info(agents)")
        var hasIsUndeletable = false
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == "isUndeletable") {
                hasIsUndeletable = true
                break
            }
        }
        cursor.close()
        assertTrue("agents table should have isUndeletable column at version 5", hasIsUndeletable)

        // Skills and agent_skills tables should NOT exist at version 5
        cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='skills'")
        assertFalse("skills table should not exist at version 5", cursor.moveToNext())
        cursor.close()

        cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='agent_skills'")
        assertFalse("agent_skills table should not exist at version 5", cursor.moveToNext())
        cursor.close()

        db.close()

        // Migrate to version 6
        val migratedDb = helper.runMigrationsAndValidate("test_db", AppDatabase.VERSION, true, null)

        // Verify skills table exists with correct schema
        cursor = migratedDb.query("PRAGMA table_info(skills)")
        val skillsColumns = mutableMapOf<String, String>()
        while (cursor.moveToNext()) {
            skillsColumns[cursor.getString(1)] = cursor.getString(2) // name -> type
        }
        cursor.close()

        assertEquals("TEXT", skillsColumns["id"])
        assertEquals("TEXT", skillsColumns["name"])
        assertEquals("TEXT", skillsColumns["description"])
        assertEquals("TEXT", skillsColumns["category"])
        assertEquals("TEXT", skillsColumns["version"])
        assertEquals("TEXT", skillsColumns["author"])
        assertEquals("TEXT", skillsColumns["manifestJson"])
        assertEquals("INTEGER", skillsColumns["installedAt"])
        assertEquals("INTEGER", skillsColumns["updatedAt"])
        assertEquals("INTEGER", skillsColumns["enabled"])

        // Verify primary key on skills.id
        cursor = migratedDb.query("PRAGMA index_list(skills)")
        var hasPk = false
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == "1") { // unique
                val indexName = cursor.getString(2)
                val indexCursor = migratedDb.query("PRAGMA index_info($indexName)")
                if (indexCursor.moveToNext() && indexCursor.getString(2) == "id") {
                    hasPk = true
                }
                indexCursor.close()
                break
            }
        }
        cursor.close()
        assertTrue("skills table should have primary key on id", hasPk)

        // Verify indices on skills
        cursor = migratedDb.query("PRAGMA index_list(skills)")
        var hasCategoryIndex = false
        var hasIdIndex = false
        while (cursor.moveToNext()) {
            val indexName = cursor.getString(2)
            if (indexName.contains("category")) hasCategoryIndex = true
            if (indexName.contains("id")) hasIdIndex = true
        }
        cursor.close()
        assertTrue("skills should have index on category", hasCategoryIndex)
        assertTrue("skills should have index on id", hasIdIndex)

        // Verify agent_skills table exists with correct schema
        cursor = migratedDb.query("PRAGMA table_info(agent_skills)")
        val agentSkillsColumns = mutableMapOf<String, String>()
        while (cursor.moveToNext()) {
            agentSkillsColumns[cursor.getString(1)] = cursor.getString(2)
        }
        cursor.close()

        assertEquals("TEXT", agentSkillsColumns["agentId"])
        assertEquals("TEXT", agentSkillsColumns["skillId"])
        assertEquals("INTEGER", agentSkillsColumns["enabled"])
        assertEquals("TEXT", agentSkillsColumns["configJson"])
        assertEquals("INTEGER", agentSkillsColumns["installedAt"])

        // Verify composite primary key on agent_skills
        cursor = migratedDb.query("PRAGMA index_list(agent_skills)")
        var hasCompositePk = false
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == "1") { // unique
                val indexName = cursor.getString(2)
                val indexCursor = migratedDb.query("PRAGMA index_info($indexName)")
                var cols = mutableListOf<String>()
                while (indexCursor.moveToNext()) {
                    cols.add(indexCursor.getString(2))
                }
                indexCursor.close()
                if (cols.contains("agentId") && cols.contains("skillId")) {
                    hasCompositePk = true
                }
                break
            }
        }
        cursor.close()
        assertTrue("agent_skills should have composite primary key on (agentId, skillId)", hasCompositePk)

        // Verify foreign keys
        cursor = migratedDb.query("PRAGMA foreign_key_list(agent_skills)")
        var fkToAgents = false
        var fkToSkills = false
        while (cursor.moveToNext()) {
            val table = cursor.getString(2)
            if (table == "agents") fkToAgents = true
            if (table == "skills") fkToSkills = true
        }
        cursor.close()
        assertTrue("agent_skills should have FK to agents", fkToAgents)
        assertTrue("agent_skills should have FK to skills", fkToSkills)

        // Verify indices on agent_skills
        cursor = migratedDb.query("PRAGMA index_list(agent_skills)")
        var hasAgentIdIndex = false
        var hasSkillIdIndex = false
        while (cursor.moveToNext()) {
            val indexName = cursor.getString(2)
            if (indexName.contains("agentId")) hasAgentIdIndex = true
            if (indexName.contains("skillId")) hasSkillIdIndex = true
        }
        cursor.close()
        assertTrue("agent_skills should have index on agentId", hasAgentIdIndex)
        assertTrue("agent_skills should have index on skillId", hasSkillIdIndex)

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migration5to6_preservesExistingData() {
        // Create database at version 5 with some data
        val db = helper.createDatabase("test_db_data", AppDatabase.VERSION - 1)

        // Insert test agent
        db.execSQL("INSERT INTO agents (id, name, role, persona, goal, avatarIndex, status, autonomy, memory, lastTickAt, failureCount, createdAt, updatedAt, allowedConnectors, apiToken, isUndeletable) VALUES ('test-agent', 'Test', 'Role', 'Persona', 'Goal', 0, 'idle', 1, 'Memory', 0, 0, 1000, 1000, '', '', 0)")

        // Insert test diary entry
        db.execSQL("INSERT INTO diary_entries (id, agentId, kind, content, importance, pinned, createdAt) VALUES ('diary-1', 'test-agent', 'thought', 'Content', 5, 0, 1000)")

        db.close()

        // Migrate to version 6
        val migratedDb = helper.runMigrationsAndValidate("test_db_data", AppDatabase.VERSION, true, null)

        // Verify data preserved
        var cursor = migratedDb.query("SELECT * FROM agents WHERE id = 'test-agent'")
        assertTrue("Agent should be preserved", cursor.moveToNext())
        assertEquals("Test", cursor.getString(cursor.getColumnIndexOrThrow("name")))
        cursor.close()

        cursor = migratedDb.query("SELECT * FROM diary_entries WHERE id = 'diary-1'")
        assertTrue("Diary should be preserved", cursor.moveToNext())
        assertEquals("Content", cursor.getString(cursor.getColumnIndexOrThrow("content")))
        cursor.close()

        // New tables should be empty but exist
        cursor = migratedDb.query("SELECT COUNT(*) FROM skills")
        cursor.moveToNext()
        assertEquals(0, cursor.getInt(0))
        cursor.close()

        cursor = migratedDb.query("SELECT COUNT(*) FROM agent_skills")
        cursor.moveToNext()
        assertEquals(0, cursor.getInt(0))
        cursor.close()

        migratedDb.close()
    }
}