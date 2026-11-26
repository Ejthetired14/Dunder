package com.agoria.app.core.connectors

import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.Repository
import org.json.JSONObject

/**
 * Registry of connector handlers. Maps connector IDs to their [ConnectorHandler].
 *
 * Handlers are instantiated here and registered in one place. `ConnectorExecutor` delegates
 * to this registry instead of containing a giant `when` dispatch.
 */
object ConnectorHandlerRegistry {

    private val handlers = mutableMapOf<String, ConnectorHandler>()

    fun register(handler: ConnectorHandler) {
        handlers[handler.connectorId] = handler
    }

    fun get(connectorId: String): ConnectorHandler? = handlers[connectorId]

    val all: List<ConnectorHandler> = handlers.values.toList()

    /** Initialises all built-in handlers. Call once from Application.onCreate. */
    fun init(repo: Repository, context: android.content.Context) {
        register(GenericKnowledgeHandler())
        register(GenericNewsHandler())
        register(WebSearchHandler())
        register(WeatherHandler())
        register(CurrencyHandler())
        register(DeviceHandler(context))
        register(FileHandler(context))
        register(HttpHandler(repo))
        register(AccountsHandler(repo, context))
        register(GitHubHandler(repo))
        register(SlackHandler())
        register(DiscordHandler())
        register(NotionHandler())
        register(TelegramHandler())
        register(AgentMailHandler())
        register(PayPalHandler())
        register(PolarHandler())
        register(StripeHandler(repo))
        register(CloudflareWorkersHandler())
        register(SupabaseHandler())
        register(ResendHandler())
        register(UpstashHandler())
        register(HuggingFaceHandler())
        register(VercelHandler())
        register(NetlifyHandler())
        register(LinearHandler())
    }
}
