package com.agoria.app.core.connectors

import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.Repository
import com.agoria.app.util.Json
import org.json.JSONObject

/** Generic HTTP connector — validated base URL, stripped Authorization headers, bounded bodies. */

class HttpHandler(
    private val repo: Repository,
) : ConnectorHandler {
    override val connectorId = "http"
    override val description = "Make HTTP requests to any configured base URL. Credentials never leave the connector."
    override val risk = "medium"

    override fun describeActions(): String =
        "http actions:\n- request: Make an HTTP request. args {\"method\": \"GET\", \"path\": \"/api/endpoint\", \"headers\": {...}, \"body\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "request", "http", "call" -> "request"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val baseUrl = config?.optString("baseUrl")?.trim()
        if (baseUrl.isNullOrBlank()) return "base URL"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val baseUrl = ConnectorHttp.cfg(config, "baseUrl") ?: return HandlerResult(false, "Missing base URL")
        val agentToken = if (ConnectorHttp.cfg(config, "authSource") == "agent") {
            agentId?.let { repo.agent(it)?.apiToken }?.ifBlank { null }
        } else {
            null
        }
        val apiKey = agentToken ?: ConnectorHttp.cfg(config, "apiKey")
        val defaultHeaders = ConnectorHttp.parseHeaders(ConnectorHttp.cfg(config, "defaultHeaders"))
        val method = args.optString("method", "GET").uppercase()
        val path = args.optString("path", "")
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        }
        val headers = defaultHeaders.toMutableMap()
        if (!apiKey.isNullOrBlank()) headers["Authorization"] = "Bearer $apiKey"
        // Agent-supplied headers are merged, but they may not override Authorization: the connector's
        // own credentials must always win so an agent cannot exfiltrate or redirect them.
        ConnectorHttp.parseHeaders(args.optString("headers", "").ifBlank { null })?.let { agentHeaders ->
            agentHeaders.entries.filter { it.key.lowercase() != "authorization" }.forEach { (k, v) -> headers[k] = v }
        }
        val body = args.optString("body", "").ifBlank { null }
        val response = ConnectorHttp.http(method, url, headers, if (method == "GET" || method == "HEAD") null else body)
        return if (response.ok) {
            // Never echo the response body back to the agent: an arbitrary HTTP endpoint may return
            // secrets (keys, tokens, PII) that must not reach the LLM context or logs.
            HandlerResult(true, "HTTP $method $url -> ${response.code}")
        } else {
            HandlerResult(false, "HTTP $method $url failed (${response.code})")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}
