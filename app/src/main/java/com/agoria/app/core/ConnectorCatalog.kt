package com.agoria.app.core

import java.util.Locale

enum class ConnectorAuth {
    NONE,
    API_KEY,
    OAUTH_TOKEN,
    WEBHOOK,
    BOT_TOKEN,
}

data class ConnectorField(
    val key: String,
    val label: String,
    val hint: String,
    val secret: Boolean = false,
    val required: Boolean = false,
    val placeholder: String = "",
)

data class ConnectorActionDef(
    val id: String,
    val label: String,
    val description: String,
    val mutating: Boolean,
    val argsHint: String,
)

data class ConnectorDef(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val auth: ConnectorAuth,
    val fields: List<ConnectorField>,
    val actions: List<ConnectorActionDef>,
    val setupHelp: String,
    val docsUrl: String,
    val risk: String,
    val iconKey: String,
)

object ConnectorCatalog {

    val all: List<ConnectorDef> = listOf(
        ConnectorDef(
            id = "wikipedia",
            name = "Wikipedia",
            category = "Knowledge",
            description = "Search and summarize public Wikipedia articles. No key needed.",
            auth = ConnectorAuth.NONE,
            fields = emptyList(),
            actions = listOf(
                ConnectorActionDef("search", "Search", "Search article titles for a query.", false, "{\"query\": \"...\"}"),
                ConnectorActionDef("summary", "Summary", "Get the plain-text summary of an article.", false, "{\"title\": \"...\"}"),
            ),
            setupHelp = "No setup needed. This connector is ready to use.",
            docsUrl = "https://www.mediawiki.org/wiki/API:Main_page",
            risk = "low",
            iconKey = "wikipedia",
        ),
        ConnectorDef(
            id = "hn",
            name = "Hacker News",
            category = "News",
            description = "Read the current top stories and search Hacker News.",
            auth = ConnectorAuth.NONE,
            fields = emptyList(),
            actions = listOf(
                ConnectorActionDef("top", "Top stories", "Fetch the current top stories.", false, "{\"limit\": 10}"),
                ConnectorActionDef("search", "Search", "Search Hacker News through Algolia.", false, "{\"query\": \"...\", \"limit\": 10}"),
            ),
            setupHelp = "No setup needed. This connector is ready to use.",
            docsUrl = "https://github.com/HackerNews/API",
            risk = "low",
            iconKey = "hn",
        ),
        ConnectorDef(
            id = "search",
            name = "Web Search",
            category = "Knowledge",
            description = "Quick answers from DuckDuckGo's public instant-answer API.",
            auth = ConnectorAuth.NONE,
            fields = emptyList(),
            actions = listOf(
                ConnectorActionDef("search", "Search", "Return the instant answer for a query.", false, "{\"query\": \"...\"}"),
            ),
            setupHelp = "No setup needed. This connector is ready to use.",
            docsUrl = "https://duckduckgo.com/api",
            risk = "low",
            iconKey = "search",
        ),
        ConnectorDef(
            id = "weather",
            name = "Weather (Open-Meteo)",
            category = "Data",
            description = "Current weather and a short forecast for any coordinates.",
            auth = ConnectorAuth.NONE,
            fields = emptyList(),
            actions = listOf(
                ConnectorActionDef("current", "Current weather", "Get temperature, wind, and weather code.", false, "{\"latitude\": 51.5, \"longitude\": -0.1}"),
                ConnectorActionDef("forecast", "Forecast", "Get a short daily forecast.", false, "{\"latitude\": 51.5, \"longitude\": -0.1, \"days\": 3}"),
            ),
            setupHelp = "No setup needed. This connector is ready to use.",
            docsUrl = "https://open-meteo.com/en/docs",
            risk = "low",
            iconKey = "weather",
        ),
        ConnectorDef(
            id = "currency",
            name = "Currency (Frankfurter)",
            category = "Data",
            description = "Convert currencies using European Central Bank reference rates.",
            auth = ConnectorAuth.NONE,
            fields = emptyList(),
            actions = listOf(
                ConnectorActionDef("convert", "Convert", "Convert an amount between currencies.", false, "{\"from\": \"USD\", \"to\": \"EUR\", \"amount\": 10}"),
                ConnectorActionDef("latest", "Latest rates", "Get the latest rates for a base currency.", false, "{\"from\": \"USD\"}"),
            ),
            setupHelp = "No setup needed. This connector is ready to use.",
            docsUrl = "https://frankfurter.app/",
            risk = "low",
            iconKey = "currency",
        ),
        ConnectorDef(
            id = "device",
            name = "This Device",
            category = "Local",
            description = "Read local device facts and post a local notification.",
            auth = ConnectorAuth.NONE,
            fields = emptyList(),
            actions = listOf(
                ConnectorActionDef("info", "Device info", "Return model, Android version, battery, and time.", false, "{}"),
                ConnectorActionDef("notify", "Local notification", "Post a local notification on this device.", true, "{\"title\": \"...\", \"body\": \"...\"}"),
            ),
            setupHelp = "No setup needed. This connector only touches this device.",
            docsUrl = "",
            risk = "low",
            iconKey = "device",
        ),
        ConnectorDef(
            id = "files",
            name = "Local Files",
            category = "Local",
            description = "List and read files inside the agent workspace. Writes need approval.",
            auth = ConnectorAuth.NONE,
            fields = emptyList(),
            actions = listOf(
                ConnectorActionDef("list", "List", "List files in the workspace.", false, "{\"path\": \"\"}"),
                ConnectorActionDef("read", "Read", "Read a text file from the workspace.", false, "{\"path\": \"...\"}"),
                ConnectorActionDef("write", "Write", "Write a text file inside the workspace. Requires approval.", true, "{\"path\": \"...\", \"content\": \"...\"}"),
            ),
            setupHelp = "No setup needed. Files are sandboxed under the app's private storage.",
            docsUrl = "",
            risk = "medium",
            iconKey = "files",
        ),
        ConnectorDef(
            id = "http",
            name = "HTTP Request",
            category = "Web",
            description = "Make a generic HTTP request to a base URL you trust. Every request needs approval.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("baseUrl", "Base URL", "For example https://api.example.com. The path is appended.", false, true, "https://api.example.com"),
                ConnectorField("apiKey", "API key", "Optional. Sent as Authorization: Bearer.", true, false, "sk-..."),
                ConnectorField("defaultHeaders", "Default headers", "Optional JSON object, for example {\"X-Test\": \"1\"}.", false, false, "{}"),
            ),
            actions = listOf(
                ConnectorActionDef("request", "HTTP request", "Make a GET/POST/PUT/PATCH/DELETE request.", true, "{\"method\": \"GET\", \"path\": \"/v1/thing\", \"body\": \"\"}"),
            ),
            setupHelp = "Set a base URL you trust. The app will show the exact method, path, headers, and body before it sends anything.",
            docsUrl = "https://developer.mozilla.org/en-US/docs/Web/HTTP",
            risk = "high",
            iconKey = "http",
        ),
        ConnectorDef(
            id = "gmail",
            name = "Gmail",
            category = "Email",
            description = "Read and send mail through the Gmail API.",
            auth = ConnectorAuth.OAUTH_TOKEN,
            fields = listOf(
                ConnectorField("accessToken", "OAuth access token", "Paste a token with gmail.readonly and gmail.send scopes.", true, true, "ya29...."),
                ConnectorField("userEmail", "Account email", "Optional. Used for labels and display.", false, false, "you@example.com"),
            ),
            actions = listOf(
                ConnectorActionDef("list", "List messages", "Search recent messages.", false, "{\"query\": \"is:unread\", \"maxResults\": 5}"),
                ConnectorActionDef("get", "Read message", "Read one message by id.", false, "{\"messageId\": \"...\"}"),
                ConnectorActionDef("send", "Send email", "Send a plain-text email. Requires approval.", true, "{\"to\": \"...\", \"subject\": \"...\", \"body\": \"...\"}"),
            ),
            setupHelp = "Create an OAuth 2.0 access token with the Google OAuth Playground (developers.google.com/oauthplayground) using the Gmail API v1 and the scopes gmail.readonly and gmail.send. Paste the access token below. Tokens expire after about an hour; paste a fresh one when it expires.",
            docsUrl = "https://developers.google.com/gmail/api",
            risk = "high",
            iconKey = "gmail",
        ),
        ConnectorDef(
            id = "calendar",
            name = "Google Calendar",
            category = "Calendar",
            description = "Read and create events in a Google calendar.",
            auth = ConnectorAuth.OAUTH_TOKEN,
            fields = listOf(
                ConnectorField("accessToken", "OAuth access token", "Paste a token with calendar.readonly and calendar.events scopes.", true, true, "ya29...."),
                ConnectorField("calendarId", "Calendar id", "Defaults to primary.", false, false, "primary"),
            ),
            actions = listOf(
                ConnectorActionDef("list", "List events", "List upcoming events.", false, "{\"maxResults\": 5}"),
                ConnectorActionDef("create", "Create event", "Create an event. Requires approval.", true, "{\"summary\": \"...\", \"start\": \"2026-11-17T10:00:00Z\", \"end\": \"2026-11-17T11:00:00Z\", \"description\": \"...\"}"),
            ),
            setupHelp = "Create an OAuth token with the Google Calendar API v3 and the scopes calendar.readonly and calendar.events in the OAuth Playground. Paste the access token below.",
            docsUrl = "https://developers.google.com/calendar/api",
            risk = "high",
            iconKey = "calendar",
        ),
        ConnectorDef(
            id = "github",
            name = "GitHub",
            category = "Code",
            description = "Read repositories and create issues with a personal access token.",
            auth = ConnectorAuth.BOT_TOKEN,
            fields = listOf(
                ConnectorField("token", "Personal access token", "A fine-grained token with issues:read/write and contents:read.", true, true, "github_pat_..."),
                ConnectorField("repo", "Repository", "Format owner/name.", false, true, "owner/repo"),
            ),
            actions = listOf(
                ConnectorActionDef("get_repo", "Get repository", "Read repository metadata.", false, "{}"),
                ConnectorActionDef("list_issues", "List issues", "List open or closed issues.", false, "{\"state\": \"open\"}"),
                ConnectorActionDef("create_issue", "Create issue", "Open a new issue. Requires approval.", true, "{\"title\": \"...\", \"body\": \"...\"}"),
            ),
            setupHelp = "Create a fine-grained personal access token at github.com/settings/tokens with access to the repository and issues:read/write. Paste it below.",
            docsUrl = "https://docs.github.com/en/rest",
            risk = "high",
            iconKey = "github",
        ),
        ConnectorDef(
            id = "slack",
            name = "Slack",
            category = "Chat",
            description = "Post messages to a Slack channel through an incoming webhook.",
            auth = ConnectorAuth.WEBHOOK,
            fields = listOf(
                ConnectorField("webhookUrl", "Webhook URL", "A Slack incoming webhook URL.", true, true, "https://hooks.slack.com/services/..."),
                ConnectorField("channel", "Channel override", "Optional. Overrides the webhook's default channel.", false, false, "#general"),
            ),
            actions = listOf(
                ConnectorActionDef("post", "Post message", "Post a message. Requires approval.", true, "{\"text\": \"...\"}"),
            ),
            setupHelp = "Create a Slack app with an incoming webhook at api.slack.com/messaging/webhooks, choose a channel, and paste the webhook URL.",
            docsUrl = "https://api.slack.com/messaging/webhooks",
            risk = "high",
            iconKey = "slack",
        ),
        ConnectorDef(
            id = "discord",
            name = "Discord",
            category = "Chat",
            description = "Post messages to a Discord channel through a webhook.",
            auth = ConnectorAuth.WEBHOOK,
            fields = listOf(
                ConnectorField("webhookUrl", "Webhook URL", "A Discord channel webhook URL.", true, true, "https://discord.com/api/webhooks/..."),
                ConnectorField("username", "Display name", "Optional. Overrides the webhook name.", false, false, "Dunder"),
            ),
            actions = listOf(
                ConnectorActionDef("post", "Post message", "Post a message. Requires approval.", true, "{\"content\": \"...\"}"),
            ),
            setupHelp = "In Discord, open channel settings, Integrations, Webhooks, create a webhook, copy its URL, and paste it below.",
            docsUrl = "https://discord.com/developers/docs/resources/webhook",
            risk = "high",
            iconKey = "discord",
        ),
        ConnectorDef(
            id = "notion",
            name = "Notion",
            category = "Docs",
            description = "Create pages and query a Notion database.",
            auth = ConnectorAuth.BOT_TOKEN,
            fields = listOf(
                ConnectorField("token", "Integration token", "An internal integration secret.", true, true, "secret_..."),
                ConnectorField("databaseId", "Database id", "The destination database id.", false, true, "32-character id"),
            ),
            actions = listOf(
                ConnectorActionDef("query", "Query database", "Read rows from the database.", false, "{\"page_size\": 5}"),
                ConnectorActionDef("create_page", "Create page", "Create a page. Requires approval.", true, "{\"title\": \"...\", \"content\": \"...\"}"),
            ),
            setupHelp = "Create an internal integration at notion.so/my-integrations, share the destination database with it, and paste the integration token plus database id.",
            docsUrl = "https://developers.notion.com/",
            risk = "high",
            iconKey = "notion",
        ),
        ConnectorDef(
            id = "telegram",
            name = "Telegram",
            category = "Chat",
            description = "Send messages through a Telegram bot.",
            auth = ConnectorAuth.BOT_TOKEN,
            fields = listOf(
                ConnectorField("botToken", "Bot token", "From @BotFather.", true, true, "123456:ABC..."),
                ConnectorField("chatId", "Chat id", "The chat or channel id.", false, true, "-1001234567890"),
            ),
            actions = listOf(
                ConnectorActionDef("send", "Send message", "Send a message. Requires approval.", true, "{\"text\": \"...\"}"),
                ConnectorActionDef("get_updates", "Get updates", "Read recent updates.", false, "{\"limit\": 5}"),
            ),
            setupHelp = "Create a bot with @BotFather, add it to the chat, send a message, and use the chat id from getUpdates. Paste the bot token and chat id below.",
            docsUrl = "https://core.telegram.org/bots/api",
            risk = "high",
            iconKey = "telegram",
        ),
        ConnectorDef(
            id = "stripe",
            name = "Stripe",
            category = "Payments",
            description = "Create products, prices, subscriptions, and verify API keys for monetization.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("secretKey", "Secret Key", "Stripe secret key (sk_live_... or sk_test_...)", true, true, "sk_test_..."),
                ConnectorField("webhookSecret", "Webhook Secret", "Optional. For verifying webhook events (whsec_...)", false, false, "whsec_..."),
            ),
            actions = listOf(
                ConnectorActionDef("create_product", "Create Product", "Create a Stripe product for your API.", true, "{\"name\": \"My API\", \"description\": \"Access to...\"}"),
                ConnectorActionDef("create_price", "Create Price", "Create a recurring price for a product.", true, "{\"productId\": \"prod_...\", \"amountCents\": 999, \"currency\": \"usd\", \"interval\": \"month\"}"),
                ConnectorActionDef("create_customer", "Create Customer", "Create a Stripe customer.", false, "{\"email\": \"user@example.com\", \"name\": \"User\"}"),
                ConnectorActionDef("create_subscription", "Create Subscription", "Subscribe a customer to a price.", true, "{\"customerId\": \"cus_...\", \"priceId\": \"price_...\"}"),
                ConnectorActionDef("verify_key", "Verify API Key", "Check if a customer's API key is valid and get their subscription status.", false, "{\"apiKey\": \"sk_...\"}"),
                ConnectorActionDef("usage_record", "Record Usage", "Report usage for metered billing.", true, "{\"subscriptionItemId\": \"si_...\", \"quantity\": 1}"),
                ConnectorActionDef("list_products", "List Products", "List your Stripe products.", false, "{\"limit\": 10}"),
                ConnectorActionDef("get_product", "Get Product", "Retrieve a product by ID.", false, "{\"productId\": \"prod_...\"}"),
                ConnectorActionDef("list_events", "List Events", "List recent Stripe events (for polling revenue).", false, "{\"type\": \"invoice.payment_succeeded\", \"limit\": 10}"),
            ),
            setupHelp = "Get your secret key from dashboard.stripe.com/apikeys. For webhooks, add an endpoint in Stripe dashboard and copy the signing secret. The app will only use the key for API calls you approve.",
            docsUrl = "https://stripe.com/docs/api",
            risk = "high",
            iconKey = "stripe",
        ),
        ConnectorDef(
            id = "cloudflare_workers",
            name = "Cloudflare Workers",
            category = "Deploy",
            description = "Deploy JavaScript/TypeScript code as serverless functions at the edge. Free tier: 100k requests/day.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("apiToken", "API Token", "Cloudflare API token with Workers Scripts:Edit and Account:Read permissions.", true, true, "..."),
                ConnectorField("accountId", "Account ID", "Your Cloudflare account ID (from dashboard URL).", true, true, "..."),
                ConnectorField("zoneId", "Zone ID (optional)", "Zone ID for custom domain. Leave empty for workers.dev subdomain.", false, false, "..."),
            ),
            actions = listOf(
                ConnectorActionDef("deploy", "Deploy Worker", "Deploy a Workers script. Requires approval.", true, "{\"name\": \"my-api\", \"code\": \"export default { async fetch(req) { return new Response('Hello') } }\", \"routes\": []}"),
                ConnectorActionDef("get_script", "Get Script", "Retrieve deployed script content.", false, "{\"name\": \"my-api\"}"),
                ConnectorActionDef("delete", "Delete Worker", "Delete a deployed worker. Requires approval.", true, "{\"name\": \"my-api\"}"),
                ConnectorActionDef("list", "List Workers", "List all deployed workers.", false, "{}"),
                ConnectorActionDef("tail_logs", "Tail Logs", "Stream live logs from a worker.", false, "{\"name\": \"my-api\"}"),
                ConnectorActionDef("deploy_webhook_handler", "Deploy Stripe Webhook Handler", "Deploy a pre-built worker to receive Stripe webhooks and forward to app. Requires approval.", true, "{\"name\": \"stripe-webhook\", \"stripeWebhookSecret\": \"whsec_...\", \"appCallbackUrl\": \"http://192.168.1.x:8080/webhook\"}"),
            ),
            setupHelp = "Create an API token at dash.cloudflare.com/profile/api-tokens with Workers Scripts:Edit and Account:Read. Copy your Account ID from the dashboard overview page. The app will only deploy code you approve.",
            docsUrl = "https://developers.cloudflare.com/workers/",
            risk = "high",
            iconKey = "cloudflare",
        ),
    )

    val byId: Map<String, ConnectorDef> = all.associateBy { it.id }

    fun get(id: String): ConnectorDef? = byId[id]

    fun defaultStatus(def: ConnectorDef): String =
        if (def.fields.any { it.required } || def.auth != ConnectorAuth.NONE) "needs_setup" else "ready"

    fun defaultScopes(def: ConnectorDef): String = when (def.id) {
        "gmail" -> "gmail.readonly, gmail.send"
        "calendar" -> "calendar.readonly, calendar.events"
        "github" -> "repo, issues"
        "notion" -> "pages, databases"
        else -> def.fields.filter { it.required }.joinToString(", ") { it.key }
    }

    fun isReadOnly(connectorId: String, action: String): Boolean {
        val def = get(connectorId) ?: return false
        val actionDef = def.actions.firstOrNull { it.id == action } ?: return false
        if (actionDef.mutating) return false
        return when (connectorId) {
            "wikipedia", "hn", "search", "weather", "currency" -> true
            "device" -> true
            "files" -> action == "list" || action == "read"
            else -> false
        }
    }

    fun risk(def: ConnectorDef): String = def.risk

    fun displayName(id: String): String = get(id)?.name ?: id.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }

    fun category(id: String): String = get(id)?.category ?: "Custom"

    fun description(id: String): String = get(id)?.description ?: "Custom connector."

    fun requiresSetup(id: String): Boolean {
        val def = get(id) ?: return true
        return def.fields.any { it.required } || def.auth != ConnectorAuth.NONE
    }
}