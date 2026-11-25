package com.agoria.app.util

import com.agoria.app.core.ConnectorCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotSecretsTest {

    private fun sampleSnapshot(): JSONObject = JSONObject().apply {
        put("app", "Dunder")
        put("version", 2)
        put("settings", JSONObject().put("officeName", "The Office"))
        put(
            "agents",
            JSONArray().put(
                JSONObject().apply {
                    put("id", "agent-1")
                    put("name", "Ada")
                    put("memory", "prefers small diffs")
                    put("apiToken", "sk-live-agent-secret")
                },
            ),
        )
        put(
            "connectors",
            JSONArray().put(
                JSONObject().apply {
                    put("id", "github")
                    put(
                        "config",
                        JSONObject().apply {
                            put("token", "ghp_live_token")
                            put("repo", "Ejthetired14/Dunder")
                        },
                    )
                },
            ),
        )
        put(
            "agent_skills",
            JSONArray().put(
                JSONObject().apply {
                    put("agentId", "agent-1")
                    put("skillId", "research")
                    put("config", "{\"apiKey\":\"sk-skill-secret\",\"model\":\"fast\"}")
                },
            ),
        )
    }

    @Test
    fun `snapshot keeps its data but loses every credential`() {
        val snapshot = sampleSnapshot()

        val removed = SnapshotSecrets.redactSnapshot(snapshot)
        val text = snapshot.toString()

        assertTrue("expected at least three removals, got $removed", removed >= 3)
        assertFalse("agent token survived", text.contains("sk-live-agent-secret"))
        assertFalse("connector token survived", text.contains("ghp_live_token"))
        assertFalse("skill credential survived", text.contains("sk-skill-secret"))

        assertEquals("The Office", snapshot.getJSONObject("settings").getString("officeName"))
        val agent = snapshot.getJSONArray("agents").getJSONObject(0)
        assertEquals("Ada", agent.getString("name"))
        assertEquals("prefers small diffs", agent.getString("memory"))
        assertFalse(agent.has("apiToken"))
        val connector = snapshot.getJSONArray("connectors").getJSONObject(0)
        assertEquals("Ejthetired14/Dunder", connector.getJSONObject("config").getString("repo"))
        assertFalse(connector.getJSONObject("config").has("token"))
        val skillConfig = JSONObject(snapshot.getJSONArray("agent_skills").getJSONObject(0).getString("config"))
        assertEquals("fast", skillConfig.getString("model"))
        assertFalse(skillConfig.has("apiKey"))
    }

    @Test
    fun `redaction is idempotent`() {
        val snapshot = sampleSnapshot()
        SnapshotSecrets.redactSnapshot(snapshot)
        assertEquals(0, SnapshotSecrets.redactSnapshot(snapshot))
    }

    @Test
    fun `every catalog secret field is removed from its connector config`() {
        val failures = mutableListOf<String>()
        for (def in ConnectorCatalog.all) {
            val config = JSONObject()
            def.fields.forEach { config.put(it.key, "value-of-${it.key}") }
            SnapshotSecrets.redactConnectorConfig(def.id, config)
            def.fields.filter { it.secret }.forEach { field ->
                if (config.has(field.key)) failures += "${def.id}.${field.key} survived"
            }
            def.fields.filterNot { it.secret }.forEach { field ->
                if (!config.has(field.key)) failures += "${def.id}.${field.key} was removed"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `unknown connectors still lose pattern-named credentials`() {
        val config = JSONObject()
            .put("baseUrl", "https://api.example.com")
            .put("apiKey", "sk-unknown")
            .put("defaultHeaders", "{\"X-Test\":\"1\"}")

        val removed = SnapshotSecrets.redactConnectorConfig("not-in-catalog", config)

        assertEquals(1, removed)
        assertFalse(config.has("apiKey"))
        assertEquals("https://api.example.com", config.getString("baseUrl"))
        assertEquals("{\"X-Test\":\"1\"}", config.getString("defaultHeaders"))
    }

    @Test
    fun `budget keys are not mistaken for credentials`() {
        assertFalse(SnapshotSecrets.isSecretKey("maxTokens"))
        assertFalse(SnapshotSecrets.isSecretKey("tokensUsed"))
        assertTrue(SnapshotSecrets.isSecretKey("apiToken"))
        assertTrue(SnapshotSecrets.isSecretKey("clientSecret"))
        assertTrue(SnapshotSecrets.isSecretKey("webhookUrl"))
    }
}
