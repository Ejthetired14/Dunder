package com.agoria.app.core.connectors

import com.agoria.app.data.ConnectorEntity
import org.json.JSONObject

/**
 * Result of a connector action execution.
 */
data class HandlerResult(
    val ok: Boolean,
    val summary: String,
    val error: String? = null,
)

/**
 * A single connector's action handler.
 *
 * Each connector (wikipedia, github, files, stripe, etc.) gets its own handler that declares
 * the actions it supports and implements them. Handlers are registered in
 * [ConnectorHandlerRegistry] and dispatched from [ConnectorExecutor].
 *
 * Handlers are stateless and receive [ConnectorEntity], config and args on every call.
 */
interface ConnectorHandler {

    /** The connector ID this handler serves (e.g. "wikipedia", "github"). */
    val connectorId: String

    /** Human-readable description of what this connector does. */
    val description: String

    /** Returns a description of all available actions for the list_actions probe. */
    fun describeActions(): String

    /** Canonicalises an action name (synonyms -> canonical id). */
    fun canonicalAction(action: String): String

    /** Checks whether the connector's required config fields are present. Returns the missing field label, or null. */
    fun missingConfig(config: JSONObject?): String?

    /** Executes an action. Returns [HandlerResult]. */
    suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult

    /** Called after a successful execution to clean up any connector-level error state. */
    fun onSuccess(connector: ConnectorEntity): Unit

    /** The risk level of this connector's actions ("low", "medium", "high"). */
    val risk: String

    /** Default implementations shared by all handlers. */
    companion object {
        fun defaultOnSuccess(connector: ConnectorEntity): Unit = Unit
    }
}
