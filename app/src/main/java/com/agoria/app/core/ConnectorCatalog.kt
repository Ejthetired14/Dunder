package com.agoria.app.core

import java.util.Locale

enum class ConnectorAuth {
    NONE,
    API_KEY,
    WEBHOOK,
    BOT_TOKEN,

    /**
     * Uses the office's own browser session, never the accounts signed in to this phone. The app never stores a token or a
     * password for these connectors: it hands the action to the signed-in mail, calendar or
     * browser app, and the user confirms it there.
     */
    ACCOUNT,
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
            id = "accounts",
            name = "Office Browser",
            category = "Personal",
            description = "Work inside a browser window that belongs to the office. Sign in there once with the account the office should use, then it can draft Gmail, add Google Calendar events and open any page in that same session. Your phone's own accounts are never touched.",
            auth = ConnectorAuth.ACCOUNT,
            fields = emptyList(),
            actions = listOf(
                ConnectorActionDef("list", "Browser session", "Report whether the office browser is signed in to Google.", false, "{}"),
                ConnectorActionDef("signin", "Sign in", "Open the office browser on a sign-in page so you can sign in once.", true, "{\"url\": \"https://accounts.google.com/ServiceLogin\"}"),
                ConnectorActionDef("email", "Draft an email", "Open a Gmail draft in the office browser. You review it and press send.", true, "{\"to\": \"...\", \"subject\": \"...\", \"body\": \"...\"}"),
                ConnectorActionDef("event", "Add calendar event", "Open a Google Calendar event form in the office browser. You confirm it.", true, "{\"title\": \"...\", \"start\": \"2026-11-20T10:00:00Z\", \"end\": \"2026-11-20T11:00:00Z\", \"location\": \"\"}"),
                ConnectorActionDef("open", "Open a link", "Open a web link in the office browser.", true, "{\"url\": \"https://mail.google.com\"}"),
            ),
            setupHelp = "Open the office browser and sign in to the account you want the office to use. That session is stored for the app only, so the office works as that account without reading the accounts on your phone.",
            docsUrl = "",
            risk = "medium",
            iconKey = "accounts",
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
            id = "paypal",
            name = "PayPal",
            category = "Payments",
            description = "Create orders, capture payments, and manage subscriptions through PayPal REST API.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("clientId", "Client ID", "PayPal REST API client ID from developer dashboard.", true, true, "Ad...xyz"),
                ConnectorField("clientSecret", "Client Secret", "PayPal REST API client secret.", true, true, "EF...xyz"),
                ConnectorField("environment", "Environment", "sandbox or live.", false, false, "sandbox"),
            ),
            actions = listOf(
                ConnectorActionDef("create_order", "Create Order", "Create a PayPal order for a purchase.", true, "{\"amount\": \"10.00\", \"currency\": \"USD\", \"intent\": \"CAPTURE\"}"),
                ConnectorActionDef("capture_order", "Capture Order", "Capture payment for an approved order.", true, "{\"orderId\": \"...\"}"),
                ConnectorActionDef("get_order", "Get Order Details", "Retrieve order details by ID.", false, "{\"orderId\": \"...\"}"),
                ConnectorActionDef("create_subscription", "Create Subscription", "Create a subscription plan.", true, "{\"planId\": \"P-...\", \"subscriberEmail\": \"user@example.com\"}"),
                ConnectorActionDef("list_transactions", "List Transactions", "List recent transactions.", false, "{\"startDate\": \"2026-01-01\", \"endDate\": \"2026-12-31\"}"),
            ),
            setupHelp = "Create a PayPal app at developer.paypal.com, get Client ID and Secret. Use sandbox for testing, live for production. The app will only make API calls you approve.",
            docsUrl = "https://developer.paypal.com/docs/api/overview/",
            risk = "high",
            iconKey = "paypal",
        ),
        ConnectorDef(
            id = "polar",
            name = "Polar",
            category = "Payments",
            description = "Sell digital products and subscriptions through Polar.sh - the merchant of record for developers.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("accessToken", "Access Token", "Polar access token from your organization settings.", true, true, "polar_oat_..."),
                ConnectorField("organizationId", "Organization ID", "Your Polar organization ID.", false, false, "..."),
            ),
            actions = listOf(
                ConnectorActionDef("list_products", "List Products", "List all products.", false, "{\"limit\": 10}"),
                ConnectorActionDef("create_product", "Create Product", "Create a digital product.", true, "{\"name\": \"My Course\", \"description\": \"Learn...\", \"priceAmount\": 2900, \"priceCurrency\": \"USD\"}"),
                ConnectorActionDef("create_checkout", "Create Checkout", "Create a checkout session for a product.", true, "{\"productId\": \"...\", \"customerEmail\": \"user@example.com\", \"successUrl\": \"https://yourapp.com/success\"}"),
                ConnectorActionDef("list_orders", "List Orders", "List recent orders.", false, "{\"limit\": 10}"),
                ConnectorActionDef("get_customer", "Get Customer", "Look up customers by email.", false, "{\"email\": \"user@example.com\"}"),
                ConnectorActionDef("verify_license", "Verify License Key", "Check whether a license key is valid.", false, "{\"licenseKey\": \"...\"}"),
            ),
            setupHelp = "Get an access token from Polar (settings, then access tokens). Paste it below; the organization id is optional but lets the app filter by organization.",
            docsUrl = "https://polar.sh/docs/api",
            risk = "high",
            iconKey = "polar",
        ),
        ConnectorDef(
            id = "agentmail",
            name = "AgentMail",
            category = "Email",
            description = "Programmatic email inboxes for AI agents. Create addresses, send and receive mail via API.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("apiKey", "API Key", "AgentMail API key from your dashboard.", true, true, "am_sk_..."),
                ConnectorField("domain", "Domain", "Your verified sending domain (optional, uses the shared domain when empty).", false, false, "mail.yourapp.com"),
            ),
            actions = listOf(
                ConnectorActionDef("create_inbox", "Create Inbox", "Create a new email inbox.", true, "{\"username\": \"agent-1\", \"domain\": \"mail.yourapp.com\"}"),
                ConnectorActionDef("list_inboxes", "List Inboxes", "List all inboxes.", false, "{\"limit\": 10}"),
                ConnectorActionDef("send_email", "Send Email", "Send an email from an inbox. Requires approval.", true, "{\"inboxId\": \"agent-1@mail.yourapp.com\", \"to\": \"user@example.com\", \"subject\": \"Hello\", \"text\": \"Body\"}"),
                ConnectorActionDef("get_email", "Get Email", "Retrieve a specific email by id.", false, "{\"inboxId\": \"...\", \"emailId\": \"...\"}"),
                ConnectorActionDef("list_emails", "List Emails", "List emails in an inbox.", false, "{\"inboxId\": \"...\", \"limit\": 10}"),
                ConnectorActionDef("delete_inbox", "Delete Inbox", "Delete an inbox. Requires approval.", true, "{\"inboxId\": \"...\"}"),
            ),
            setupHelp = "Sign up at agentmail.to and copy an API key from the dashboard. Optionally verify your own domain for sending.",
            docsUrl = "https://agentmail.to/docs",
            risk = "high",
            iconKey = "agentmail",
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

    fun defaultStatus(def: ConnectorDef): String = when {
        def.auth == ConnectorAuth.ACCOUNT -> "ready"
        def.fields.any { it.required } || def.auth != ConnectorAuth.NONE -> "needs_setup"
        else -> "ready"
    }

    fun defaultScopes(def: ConnectorDef): String = when (def.id) {
        "accounts" -> "in-app browser session only"
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
            "accounts" -> action == "list"
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