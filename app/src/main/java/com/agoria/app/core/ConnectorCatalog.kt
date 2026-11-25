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
            id = "terminal",
            name = "Terminal",
            category = "System",
            description = "Run shell commands on the device. Background processes supported. Every command needs approval.",
            auth = ConnectorAuth.NONE,
            fields = emptyList(),
            actions = listOf(
                ConnectorActionDef("run", "Run command", "Run a shell command and wait for completion (max 60s). Requires approval.", true, "{\"command\": \"ls -la\", \"cwd\": \"/workspace/app\"}"),
                ConnectorActionDef("run_background", "Run background", "Start a long-running command. Returns a process ID. Requires approval.", true, "{\"command\": \"./gradlew assembleDebug\", \"cwd\": \"/workspace/app\"}"),
                ConnectorActionDef("list", "List processes", "List all background processes.", false, "{}"),
                ConnectorActionDef("stop", "Stop process", "Stop a background process by PID. Requires approval.", true, "{\"pid\": \"123\"}"),
                ConnectorActionDef("logs", "Get logs", "Get stdout/stderr of a background process.", false, "{\"pid\": \"123\"}"),
            ),
            setupHelp = "No setup needed. This connector is ready to use. Commands run in the app's working directory with the app's user permissions.",
            docsUrl = "",
            risk = "high",
            iconKey = "terminal",
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
                ConnectorActionDef("get_info", "Device info (alias)", "Alias for 'info' action.", false, "{}"),
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
            description = "Work inside a browser window that belongs to the office. Sign in there once with the account the office should use, then it can draft Gmail, add Google Calendar events, click buttons, fill forms, submit, take screenshots, extract structured data, manage tabs, download files, and wait for JS-heavy pages. Your phone's own accounts are never touched.",
            auth = ConnectorAuth.ACCOUNT,
            fields = emptyList(),
            actions = listOf(
                ConnectorActionDef("list", "Browser session", "Report whether the office browser is signed in to Google.", false, "{}"),
                ConnectorActionDef("signin", "Sign in", "Load the sign-in page in the office browser so you can sign in once.", true, "{\"url\": \"https://accounts.google.com/ServiceLogin\"}"),
                ConnectorActionDef("email", "Draft an email", "Load a Gmail draft in this agent's browser. You review it and press send.", true, "{\"to\": \"...\", \"subject\": \"...\", \"body\": \"...\"}"),
                ConnectorActionDef("event", "Add calendar event", "Load a Google Calendar event form in this agent's browser. You confirm it.", true, "{\"title\": \"...\", \"start\": \"2026-11-20T10:00:00Z\", \"end\": \"2026-11-20T11:00:00Z\", \"location\": \"\"}"),
                ConnectorActionDef("open_url", "Open a link", "Open a web link in this agent's own browser. It loads in the background.", true, "{\"url\": \"https://mail.google.com\"}"),
                ConnectorActionDef("read", "Read the page", "Return the visible text of the page in this agent's browser.", false, "{}"),
                ConnectorActionDef("view", "Show on screen", "Put this agent's browser on screen when the user wants to watch.", false, "{}"),
                // New interaction actions
                ConnectorActionDef("click", "Click element", "Click a button, link, or element by CSS selector or text. Requires approval.", true, "{\"selector\": \"button[type=submit]\", \"text\": \"Send\", \"waitForNavigation\": true}"),
                ConnectorActionDef("fill", "Fill form field", "Fill an input, textarea, or select by CSS selector. Requires approval.", true, "{\"selector\": \"input[name=q]\", \"value\": \"search query\"}"),
                ConnectorActionDef("submit", "Submit form", "Submit a form by CSS selector. Requires approval.", true, "{\"selector\": \"form#search-form\"}"),
                // Visual verification
                ConnectorActionDef("screenshot", "Take screenshot", "Capture a PNG screenshot of the current page. Returns base64. Requires approval.", true, "{\"fullPage\": false}"),
                // Structured extraction
                ConnectorActionDef("extract", "Extract structured data", "Extract tables, links, or specific elements by CSS selector. Returns JSON.", false, "{\"selector\": \"table\", \"type\": \"table\"}"),
                // Tab/session management
                ConnectorActionDef("tab", "Tab management", "Create, switch, or close tabs in the office browser.", true, "{\"action\": \"new\", \"url\": \"https://mail.google.com\"}"),
                // Download support
                ConnectorActionDef("download", "Download file", "Download a file or PDF from the current page. Requires approval.", true, "{\"url\": \"https://example.com/file.pdf\", \"filename\": \"file.pdf\"}"),
                // JS-heavy page handling
                ConnectorActionDef("wait", "Wait for condition", "Wait for element, text, or network idle before reading. Use for SPA pages.", false, "{\"selector\": \".results\", \"timeoutMs\": 10000}"),
                // Scroll support
                ConnectorActionDef("scroll", "Scroll page", "Scroll the page by pixels, to an element, or to top/bottom.", true, "{\"direction\": \"down\", \"pixels\": 500, \"selector\": \".results\"}"),
            ),
            setupHelp = "Sign in once inside the office browser with the account the office should use. Every agent then browses in its own background window: it can open pages, click, fill forms, submit, take screenshots, extract data, manage tabs, download files, and wait for JS rendering — all without covering your screen. Browsers shows any agent's page when you want to look.",
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
                ConnectorField("authSource", "Authentication", "Use this connector's key, or the token of the agent making the call (agent).", false, false, "own"),
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
            description = "Work with GitHub through one fine-grained personal access token.",
            auth = ConnectorAuth.BOT_TOKEN,
            fields = listOf(
                ConnectorField("token", "Fine-grained token", "The token decides which repositories are reachable, so there is no owner/name to fill in.", true, true, "github_pat_..."),
                ConnectorField("repo", "Default repository", "Optional. owner/name used by actions that do not pass a repo. Needed when the token reaches a repository the token's own account does not own.", false, false, "Ejthetired14/Dunder"),
            ),
            actions = listOf(
                ConnectorActionDef("whoami", "Who am I", "Check the token and the account it belongs to.", false, "{}"),
                ConnectorActionDef("list_repos", "List repositories", "List the repositories this token can reach.", false, "{}"),
                ConnectorActionDef("get_repo", "Get repository", "Read repository metadata. Pass repo as owner/name.", false, "{\"repo\": \"owner/name\"}"),
                ConnectorActionDef("list_issues", "List issues", "List open or closed issues. repo is optional when the token reaches one repository.", false, "{\"repo\": \"owner/name\", \"state\": \"open\"}"),
                ConnectorActionDef("create_issue", "Create issue", "Open a new issue. Requires approval.", true, "{\"repo\": \"owner/name\", \"title\": \"...\", \"body\": \"...\"}"),
                ConnectorActionDef("get_file", "Get file", "Read a file at a path and ref (branch/tag/sha).", false, "{\"repo\": \"owner/name\", \"path\": \"...\", \"ref\": \"main\"}"),
                ConnectorActionDef("create_branch", "Create branch", "Create a new branch from a base ref (default: main). Requires approval.", true, "{\"repo\": \"owner/name\", \"branch\": \"feature/xyz\", \"from\": \"main\"}"),
                ConnectorActionDef("put_file", "Put file", "Create or update a file on a branch. Requires approval. Refuses branch \"main\". Needs sha for updates.", true, "{\"repo\": \"owner/name\", \"branch\": \"feature/xyz\", \"path\": \"...\", \"content\": \"base64...\", \"message\": \"...\", \"sha\": \"...\"}"),
                ConnectorActionDef("create_pr", "Create PR", "Open a pull request from a branch into base (default: main). Requires approval.", true, "{\"repo\": \"owner/name\", \"head\": \"feature/xyz\", \"base\": \"main\", \"title\": \"...\", \"body\": \"...\"}"),
                ConnectorActionDef("list_prs", "List PRs", "List pull requests for a repository.", false, "{\"repo\": \"owner/name\", \"state\": \"open\"}"),
            ),
            setupHelp = "Create a fine-grained personal access token (recommended) at github.com/settings/tokens → Fine-grained tokens, give it Repository access (read/write to code, issues, metadata) — do NOT grant administration unless you need repo settings changes. Paste it here. For full repo create/manage access across all repos, a Classic PAT with 'repo' scope also works but is less secure. Paste the token (github_pat_... or ghp_...) and nothing else is needed. Invisible/control characters are stripped automatically.",
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
            description = "Search, query and create pages in the Notion workspace.",
            auth = ConnectorAuth.BOT_TOKEN,
            fields = listOf(
                ConnectorField("token", "Integration token", "An internal integration secret.", true, true, "secret_..."),
                ConnectorField("databaseId", "Database id (optional)", "Leave blank to work with pages the integration can already see.", false, false, "32-character id"),
            ),
            actions = listOf(
                ConnectorActionDef("search", "Search workspace", "Find pages and databases shared with the integration.", false, "{}"),
                ConnectorActionDef("query", "Query database", "Read rows from the configured database, or fall back to search.", false, "{\"page_size\": 5}"),
                ConnectorActionDef("create_page", "Create page", "Create a page in the database or under a parent page. Requires approval.", true, "{\"title\": \"...\", \"content\": \"...\"}"),
            ),
            setupHelp = "Create an internal integration at notion.so/my-integrations and share the pages or database you want it to touch. The token is enough: a database id is optional and can be set later.",
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
            id = "supabase",
            name = "Supabase",
            category = "Data",
            description = "Postgres tables and rows through the Supabase REST API.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("projectUrl", "Project URL", "https://<project-ref>.supabase.co", false, true, "https://xyz.supabase.co"),
                ConnectorField("serviceKey", "Service key", "Service role key, or the anon key for read-only use.", true, true, "eyJ..."),
            ),
            actions = listOf(
                ConnectorActionDef("list_rows", "List rows", "Read rows from a table.", false, "{\"table\": \"notes\", \"limit\": 20}"),
                ConnectorActionDef("insert", "Insert row", "Insert a row into a table. Requires approval.", true, "{\"table\": \"notes\", \"row\": {\"title\": \"Hi\"}}"),
                ConnectorActionDef("rpc", "Call function", "Call a Postgres function. Requires approval.", true, "{\"fn\": \"my_function\", \"args\": {}}"),
            ),
            setupHelp = "Project settings, API: copy the project URL and the service role key (or the anon key for reads).",
            docsUrl = "https://supabase.com/docs/guides/api",
            risk = "high",
            iconKey = "supabase",
        ),
        ConnectorDef(
            id = "resend",
            name = "Resend",
            category = "Email",
            description = "Send transactional email from your own verified domain.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("apiKey", "API key", "A Resend API key with sending permission.", true, true, "re_..."),
                ConnectorField("from", "From address", "A verified sender, for example Dunder <hi@yourdomain.com>.", false, true, "Dunder <hi@yourdomain.com>"),
            ),
            actions = listOf(
                ConnectorActionDef("send_email", "Send email", "Send an email. Requires approval.", true, "{\"to\": \"user@example.com\", \"subject\": \"Hello\", \"text\": \"Body\"}"),
                ConnectorActionDef("list_emails", "List emails", "List recently sent emails.", false, "{\"limit\": 10}"),
                ConnectorActionDef("get_email", "Get email", "Read one email by id.", false, "{\"emailId\": \"...\"}"),
            ),
            setupHelp = "Create a key at resend.com/api-keys and verify the sending domain first, otherwise sends are rejected.",
            docsUrl = "https://resend.com/docs/api-reference",
            risk = "high",
            iconKey = "resend",
        ),
        ConnectorDef(
            id = "upstash",
            name = "Upstash Redis",
            category = "Data",
            description = "Serverless Redis for caching, rate limits and short-lived sessions.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("redisUrl", "REST URL", "The https URL from the Upstash Redis REST API section.", false, true, "https://xyz.upstash.io"),
                ConnectorField("redisToken", "REST token", "The matching REST token.", true, true, "..."),
            ),
            actions = listOf(
                ConnectorActionDef("command", "Run command", "Run any Redis command as an array. Requires approval.", true, "{\"command\": [\"INCR\", \"page:views\"]}"),
                ConnectorActionDef("get", "Get key", "Read a key.", false, "{\"key\": \"page:views\"}"),
                ConnectorActionDef("set", "Set key", "Write a key with an optional TTL. Requires approval.", true, "{\"key\": \"page:views\", \"value\": \"1\", \"ttlSeconds\": 60}"),
                ConnectorActionDef("delete", "Delete key", "Delete a key. Requires approval.", true, "{\"key\": \"page:views\"}"),
            ),
            setupHelp = "Open the Upstash console for your database, REST API section, and copy the URL and token.",
            docsUrl = "https://upstash.com/docs/redis/features/restapi",
            risk = "high",
            iconKey = "upstash",
        ),
        ConnectorDef(
            id = "huggingface",
            name = "Hugging Face",
            category = "AI",
            description = "Run open-source models through the Hugging Face inference API.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("hfToken", "Access token", "A Hugging Face token with inference permission.", true, true, "hf_..."),
            ),
            actions = listOf(
                ConnectorActionDef("chat", "Chat", "Chat completion through the router.", false, "{\"model\": \"meta-llama/Llama-3.1-8B-Instruct\", \"prompt\": \"Hi\"}"),
                ConnectorActionDef("infer", "Run model", "Run a model on a text input.", false, "{\"model\": \"distilbert-base-uncased-finetuned-sst-2-english\", \"inputs\": \"I love this\"}"),
                ConnectorActionDef("models", "Search models", "Search the public model catalog.", false, "{\"query\": \"summarization\"}"),
            ),
            setupHelp = "Create a read token at huggingface.co/settings/tokens. Some models take a while to warm up; a 503 means retry shortly.",
            docsUrl = "https://huggingface.co/docs/api-inference",
            risk = "low",
            iconKey = "huggingface",
        ),
        ConnectorDef(
            id = "vercel",
            name = "Vercel",
            category = "Deploy",
            description = "Read projects and deployments from your Vercel account.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("vercelToken", "Access token", "A Vercel access token from account settings.", true, true, "..."),
            ),
            actions = listOf(
                ConnectorActionDef("list_projects", "List projects", "List projects in the account.", false, "{\"limit\": 10}"),
                ConnectorActionDef("list_deployments", "List deployments", "List recent deployments.", false, "{\"limit\": 10}"),
                ConnectorActionDef("get_deployment", "Get deployment", "Read one deployment by id.", false, "{\"deploymentId\": \"dpl_...\"}"),
            ),
            setupHelp = "Create a token at vercel.com/account/tokens. Reads only in this build.",
            docsUrl = "https://vercel.com/docs/rest-api",
            risk = "medium",
            iconKey = "vercel",
        ),
        ConnectorDef(
            id = "netlify",
            name = "Netlify",
            category = "Deploy",
            description = "Read sites and deploys from your Netlify account.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("netlifyToken", "Personal access token", "A Netlify personal access token.", true, true, "..."),
            ),
            actions = listOf(
                ConnectorActionDef("list_sites", "List sites", "List sites on the account.", false, "{}"),
                ConnectorActionDef("list_deploys", "List deploys", "List recent deploys for a site.", false, "{\"siteId\": \"...\"}"),
                ConnectorActionDef("get_site", "Get site", "Read one site by id or name.", false, "{\"siteId\": \"...\"}"),
            ),
            setupHelp = "Create a token at app.netlify.com/user/applications, personal access tokens.",
            docsUrl = "https://docs.netlify.com/api/get-started/",
            risk = "medium",
            iconKey = "netlify",
        ),
        ConnectorDef(
            id = "linear",
            name = "Linear",
            category = "Tasks",
            description = "Track issues and projects through the Linear GraphQL API.",
            auth = ConnectorAuth.API_KEY,
            fields = listOf(
                ConnectorField("linearKey", "API key", "A Linear personal API key.", true, true, "lin_api_..."),
            ),
            actions = listOf(
                ConnectorActionDef("list_issues", "List issues", "List issues, newest first.", false, "{\"limit\": 10}"),
                ConnectorActionDef("create_issue", "Create issue", "Create an issue. Requires approval.", true, "{\"title\": \"...\", \"teamId\": \"...\", \"description\": \"...\"}"),
                ConnectorActionDef("list_teams", "List teams", "List teams.", false, "{}"),
            ),
            setupHelp = "Create a personal API key in Linear settings, API, personal API keys.",
            docsUrl = "https://developers.linear.app/docs/graphql/working-with-the-graphql-api",
            risk = "high",
            iconKey = "linear",
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
                ConnectorField("contentType", "Script content type", "application/javascript (default) or text/javascript.", false, false, "application/javascript"),
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
        ConnectorDef(
            id = "terminal",
            name = "Terminal",
            category = "System",
            description = "Run shell commands in the app's sandbox. Use for build, test, git, and platform debugging. Every command needs approval.",
            auth = ConnectorAuth.NONE,
            fields = emptyList(),
            actions = listOf(
                ConnectorActionDef("run", "Run command", "Execute a shell command and return stdout/stderr. Requires approval.", true, "{\"command\": \"ls -la\", \"cwd\": \"/workspace/app\"}"),
                ConnectorActionDef("run_background", "Run background", "Start a long-running command (build, dev server) and return immediately. Requires approval.", true, "{\"command\": \"./gradlew assembleDebug\", \"cwd\": \"/workspace/app\"}"),
                ConnectorActionDef("list", "List processes", "List running background processes.", false, "{}"),
                ConnectorActionDef("stop", "Stop process", "Stop a background process by PID. Requires approval.", true, "{\"pid\": 12345}"),
                ConnectorActionDef("logs", "Get logs", "Get stdout/stderr of a background process.", false, "{\"pid\": 12345}"),
            ),
            setupHelp = "No setup needed. The terminal runs in the app's sandbox with access to the repository workspace. Commands run as the app user. Every command requires your approval.",
            docsUrl = "https://github.com/Ejthetired14/Dunder",
            risk = "high",
            iconKey = "terminal",
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

    fun canonicalizeAction(connectorId: String, action: String): String {
        val normalised = action.trim().lowercase().replace('-', '_').replace(' ', '_')
        val generic = when (normalised) {
            "list_files", "ls", "dir", "glob", "files" -> "list"
            "cat", "open", "read_file", "show", "view" -> "read"
            "save", "create", "write_file", "create_file", "append", "append_file" -> "write"
            "rm", "remove", "unlink", "delete_file" -> "delete"
            else -> normalised
        }
        return generic
    }

    fun isReadOnly(connectorId: String, action: String): Boolean {
        val def = get(connectorId) ?: return false
        // CONN-1: canonicalize action aliases before lookup
        val canonical = canonicalizeAction(connectorId, action)
        val actionDef = def.actions.firstOrNull { it.id == action || it.id == canonical } ?: return false
        if (actionDef.mutating) return false
        return when (connectorId) {
            "wikipedia", "hn", "search", "weather", "currency" -> true
            "device" -> true
            "accounts" -> action == "list" || canonical == "list"
            "files" -> action == "list" || action == "read" || canonical == "list" || canonical == "read"
            "terminal" -> action == "list" || action == "logs" || canonical == "list" || canonical == "logs"
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