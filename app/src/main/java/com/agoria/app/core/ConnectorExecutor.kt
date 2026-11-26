package com.agoria.app.core

import android.content.Context
import com.agoria.app.core.connectors.ConnectorHandlerRegistry
import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ConnectorResult(
    val ok: Boolean,
    val summary: String,
    val error: String? = null,
)

class ConnectorExecutor(
    private val repo: Repository,
    private val context: Context,
) {

    /** Initialises the handler registry. Call once from Application.onCreate. */
    fun initHandlers() {
        ConnectorHandlerRegistry.init(repo, context)
    }

    suspend fun execute(
        connectorId: String,
        agentId: String?,
        action: String,
        args: JSONObject,
    ): ConnectorResult {
        val connector = repo.connector(connectorId)
            ?: return ConnectorResult(false, "Connector '$connectorId' is not installed.", "not installed")
        if (connector.status == "disabled") {
            return ConnectorResult(false, "Connector '$connectorId' is disabled.", "disabled")
        }
        val def = com.agoria.app.core.ConnectorCatalog.get(connectorId)
        if (def == null) {
            return ConnectorResult(false, "Connector '$connectorId' has no definition.", "unknown connector")
        }
        // A token can be issued per agent, and that agent only reaches the connectors it was
        // granted. An empty grant list means every enabled connector.
        if (agentId != null) {
            val agent = repo.agent(agentId)
            val granted = agent?.allowedConnectors.orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (granted.isNotEmpty() && connectorId !in granted) {
                return finish(
                    connectorId,
                    agentId,
                    action,
                    false,
                    "'$connectorId' is not granted to ${agent?.name ?: "this agent"}. Granted: ${granted.joinToString(", ")}.",
                    marksError = false,
                )
            }
        }
        // Agents ask what a connector can do instead of guessing action names.
        val probe = action.trim().lowercase().replace('-', '_')
        if (probe in setOf("list_actions", "actions", "help", "describe")) {
            return finish(connectorId, agentId, probe, true, describeActions(def))
        }
        // Agents name actions in their own words ("ls", "cat", "save"), so resolve the obvious
        // synonyms before checking the catalog. A name that still does not resolve is the model's
        // mistake, not a connector failure, so it must not paint the connector red.
        val handler = ConnectorHandlerRegistry.get(connectorId)
            ?: return ConnectorResult(false, "Connector '$connectorId' has no executor.", "no executor")
        val canonical = handler.canonicalAction(action)
        if (def.actions.none { it.id == canonical }) {
            val available = def.actions.joinToString(", ") { it.id }
            return finish(
                connectorId,
                agentId,
                action,
                false,
                "Connector '$connectorId' has no action '$action'. Valid actions: $available.",
                marksError = false,
            )
        }

        val config = repo.connectorConfig(connectorId)
        handler.missingConfig(config)?.let { missing ->
            return finish(connectorId, agentId, action, false, "Missing configuration: $missing")
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                handler.execute(connector, config, canonical, args, agentId)
            }
            handler.onSuccess(connector)
            finish(connectorId, agentId, action, result.ok, result.summary)
        } catch (e: Exception) {
            finish(
                connectorId,
                agentId,
                action,
                false,
                "Connector error: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun describeActions(def: ConnectorDef): String {
        val sb = StringBuilder(def.name + " actions:")
        def.actions.forEach { action ->
            sb.append("\n- ").append(action.id).append(": ").append(action.description)
            if (action.argsHint.isNotBlank()) sb.append(" args ").append(action.argsHint)
        }
        val required = def.fields.filter { it.required }.map { it.label }
        if (required.isNotEmpty()) sb.append("\nNeeds: ").append(required.joinToString(", "))
        return sb.toString()
    }

    private suspend fun finish(
        connectorId: String,
        agentId: String?,
        action: String,
        ok: Boolean,
        summary: String,
        marksError: Boolean = true,
    ): ConnectorResult {
        val short = summary.take(1200)
        if (ok) {
            // One failed call used to leave the connector flagged "error" for good, so Local Files
            // kept showing an error even after it worked again.
            val row = repo.connector(connectorId)
            if (row != null && row.status == "error") {
                val def = com.agoria.app.core.ConnectorCatalog.get(connectorId)
                val clean = when {
                    def == null -> "needs_setup"
                    row.configEnc != null -> "configured"
                    else -> com.agoria.app.core.ConnectorCatalog.defaultStatus(def)
                }
                repo.updateConnector(row.copy(status = clean, lastError = null))
            }
        }
        if (ok || marksError) {
            repo.recordConnectorUse(connectorId, if (ok) null else short)
        }
        repo.addConnectorLog(connectorId, agentId, action, if (ok) "ok" else "error", short)
        return ConnectorResult(ok, short, if (ok) null else short)
    }
}
