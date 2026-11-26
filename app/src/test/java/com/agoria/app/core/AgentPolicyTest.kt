package com.agoria.app.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPolicyTest {

    private val policy = AgentPolicy

    // ------------------------------------------------- extractFirstJsonObject

    @Test fun extractFirstJsonObject_returnsPlainJson() {
        val text = "{\"action\": \"think\", \"diary\": \"hello\"}"
        assertEquals("{\"action\": \"think\", \"diary\": \"hello\"}", policy.extractFirstJsonObject(text))
    }

    @Test fun extractFirstJsonObject_extractsFromMarkdownFence() {
        val text = "Here is the result:\n```json\n{\"action\": \"say\", \"message\": \"hi\"}\n```\nDone."
        val extracted = policy.extractFirstJsonObject(text)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("\"action\""))
    }

    @Test fun extractFirstJsonObject_extractsFromProse() {
        val text = "I think we should {\"action\": \"think\", \"diary\": \"note\"} and continue."
        val extracted = policy.extractFirstJsonObject(text)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("\"action\""))
    }

    @Test fun extractFirstJsonObject_handlesNestedBraces() {
        val text = "{\"action\": \"use_connector\", \"input\": {\"connector_id\": \"github\"}}"
        val extracted = policy.extractFirstJsonObject(text)
        assertNotNull(extracted)
        assertTrue(extracted!!.endsWith("}"))
    }

    @Test fun extractFirstJsonObject_handlesEscapedQuotes() {
        val text = "{\"message\": \"He said \\\"hello\\\"\"}"
        val extracted = policy.extractFirstJsonObject(text)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("\\\""))
    }

    @Test fun extractFirstJsonObject_returnsNullWhenNoJson() {
        assertNull(policy.extractFirstJsonObject("just plain text"))
        assertNull(policy.extractFirstJsonObject(""))
    }

    // -------------------------------------------------------- parseDecision

    @Test fun parseDecision_parsesValidDecision() {
        val text = "{\"action\": \"think\", \"diary\": \"planning\", \"diary_kind\": \"plan\", \"message\": \"hi\", \"importance\": 3}"
        val d = policy.parseDecision(text)
        assertNotNull(d)
        assertEquals("think", d!!.action)
        assertEquals("planning", d.diary)
        assertEquals("plan", d.diaryKind)
        assertEquals("hi", d.message)
        assertEquals(3, d.importance)
    }

    @Test fun parseDecision_defaultsActionToNone() {
        val text = "{\"diary\": \"just a note\"}"
        val d = policy.parseDecision(text)
        assertNotNull(d)
        assertEquals("none", d!!.action)
    }

    @Test fun parseDecision_clampsImportance() {
        val text = "{\"action\": \"think\", \"importance\": 99}"
        val d = policy.parseDecision(text)
        assertNotNull(d)
        assertEquals(5, d!!.importance)
    }

    @Test fun parseDecision_extractsFromMarkdown() {
        val text = "```json\n{\"action\": \"say\", \"message\": \"hello world\"}\n```"
        val d = policy.parseDecision(text)
        assertNotNull(d)
        assertEquals("say", d!!.action)
        assertEquals("hello world", d.message)
    }

    @Test fun parseDecision_returnsNullOnInvalidJson() {
        assertNull(policy.parseDecision("{not valid json"))
        assertNull(policy.parseDecision("no json here"))
    }

    // ---------------------------------------------------------- isAutoAllowed

    @Test fun isAutoAllowed_trueForAutoActions() {
        for (action in setOf("none", "create_task", "update_goal", "think", "say")) {
            val d = AgentDecision("", "thought", "", action, JSONObject(), 2)
            assertTrue("$action should be auto-allowed", policy.isAutoAllowed(d))
        }
    }

    @Test fun isAutoAllowed_falseForMutatingConnector() {
        // github is mutating -> not auto-allowed
        val input = JSONObject().put("connector_id", "github").put("action_name", "create_issue")
        val d = AgentDecision("", "thought", "", "use_connector", input, 2)
        assertFalse(policy.isAutoAllowed(d))
    }

    @Test fun isAutoAllowed_trueForReadOnlyConnector() {
        // wikipedia is read-only -> auto-allowed
        val input = JSONObject().put("connector_id", "wikipedia").put("action_name", "search")
        val d = AgentDecision("", "thought", "", "use_connector", input, 2)
        assertTrue(policy.isAutoAllowed(d))
    }

    // ------------------------------------------------------- isApprovalRequest

    @Test fun isApprovalRequest_trueForRequestApproval() {
        val d = AgentDecision("", "thought", "", "request_approval", JSONObject(), 2)
        assertTrue(policy.isApprovalRequest(d))
    }

    @Test fun isApprovalRequest_trueForMutatingConnectorUse() {
        val input = JSONObject().put("connector_id", "github").put("action_name", "create_issue")
        val d = AgentDecision("", "thought", "", "use_connector", input, 2)
        assertTrue(policy.isApprovalRequest(d))
    }

    @Test fun isApprovalRequest_falseForReadOnlyConnectorUse() {
        val input = JSONObject().put("connector_id", "wikipedia").put("action_name", "search")
        val d = AgentDecision("", "thought", "", "use_connector", input, 2)
        assertFalse(policy.isApprovalRequest(d))
    }

    // ----------------------------------------------------- normalizeImportance

    @Test fun normalizeImportance_clampsToRange() {
        assertEquals(1, policy.normalizeImportance(0))
        assertEquals(1, policy.normalizeImportance(1))
        assertEquals(3, policy.normalizeImportance(3))
        assertEquals(5, policy.normalizeImportance(5))
        assertEquals(5, policy.normalizeImportance(99))
    }

    // ------------------------------------------------------------ diaryKind

    @Test fun diaryKind_acceptsValidKinds() {
        for (kind in setOf("thought", "observation", "plan", "decision", "lesson", "error", "build", "connector", "review")) {
            assertEquals(kind, policy.diaryKind(kind))
        }
    }

    @Test fun diaryKind_defaultsToThought() {
        assertEquals("thought", policy.diaryKind("nonsense"))
        assertEquals("thought", policy.diaryKind(""))
    }

    // -------------------------------------------------------------- sayLine

    @Test fun sayLine_formatsCorrectly() {
        assertEquals("To Michael: hello there", policy.sayLine("Michael", "hello there"))
    }

    // -------------------------------------------- ConnectorCatalog.isReadOnly

    @Test fun isReadOnly_trueForKnowledgeConnectors() {
        assertTrue(ConnectorCatalog.isReadOnly("wikipedia", "search"))
        assertTrue(ConnectorCatalog.isReadOnly("hn", "search"))
        assertTrue(ConnectorCatalog.isReadOnly("weather", "current"))
        assertTrue(ConnectorCatalog.isReadOnly("currency", "convert"))
    }

    @Test fun isReadOnly_falseForUnknownConnector() {
        assertFalse(ConnectorCatalog.isReadOnly("nonexistent", "search"))
    }
}
