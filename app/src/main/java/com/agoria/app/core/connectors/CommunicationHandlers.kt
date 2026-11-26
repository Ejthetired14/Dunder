package com.agoria.app.core.connectors

import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.Repository
import org.json.JSONArray
import org.json.JSONObject

/** GitHub connector — token-authenticated API calls with action synonym normalisation. */

class GitHubHandler(
    private val repo: Repository,
) : ConnectorHandler {
    override val connectorId = "github"
    override val description = "GitHub API — repositories, issues and pull requests. Needs a fine-grained token."
    override val risk = "medium"

    override fun describeActions(): String =
        "github actions:\n- whoami: Check the authenticated user.\n- list_repos: List reachable repositories.\n- get_repo: Get repository info.\n- list_issues: List open issues.\n- create_issue: Create a new issue."

    override fun canonicalAction(action: String): String = normalizeGithubAction(action)

    override fun missingConfig(config: JSONObject?): String? {
        val token = config?.optString("token")?.trim()
        if (token.isNullOrBlank()) return "token"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val token = config?.optString("token") ?: return HandlerResult(false, "Missing GitHub token")
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )

        fun repoOf(): String? {
            val explicit = args.optString("repo").trim().ifBlank { config?.optString("repo") ?: "" }
            if (explicit.isNotBlank()) return explicit
            val response = ConnectorHttp.http("GET", "https://api.github.com/user/repos?per_page=100&sort=updated", headers)
            if (!response.ok) return null
            val array = runCatching { JSONArray(response.body) }.getOrNull() ?: return null
            return if (array.length() == 1) array.optJSONObject(0)?.optString("full_name") else null
        }

        return when (normalizeGithubAction(action)) {
            "whoami" -> {
                val response = ConnectorHttp.http("GET", "https://api.github.com/user", headers)
                if (!response.ok) return HandlerResult(false, "GitHub ${response.code}: ${ConnectorHttp.text(response.body, 300)}")
                val root = ConnectorHttp.json(response.body) ?: return HandlerResult(false, "GitHub returned invalid JSON")
                HandlerResult(
                    true,
                    "Signed in as ${root.optString("login")} (${root.optString("name")}). " +
                        "This token can reach the repositories it was granted.",
                )
            }
            "list_repos" -> {
                val response = ConnectorHttp.http("GET", "https://api.github.com/user/repos?per_page=20&sort=updated", headers)
                if (!response.ok) return HandlerResult(false, "GitHub ${response.code}: ${ConnectorHttp.text(response.body, 300)}")
                val array = ConnectorHttp.jsonArray(response.body) ?: return HandlerResult(false, "GitHub returned invalid JSON")
                if (array.length() == 0) {
                    val saved = config?.optString("repo")
                        ?: return HandlerResult(
                            true,
                            "This token's account owns no repositories and is not listed as a " +
                                "collaborator on any. Save a default repository (owner/name) in this " +
                                "connector, then list again.",
                        )
                    if (!validRepoName(saved)) {
                        return HandlerResult(false, "Invalid saved repository '$saved': expected owner/name format.")
                    }
                    val probe = ConnectorHttp.http("GET", "https://api.github.com/repos/$saved", headers)
                    return if (probe.ok) {
                        HandlerResult(true, "Repositories this token can reach:\n- $saved")
                    } else {
                        HandlerResult(false, "The token cannot read $saved (GitHub ${probe.code}).")
                    }
                }
                val sb = StringBuilder("Repositories this token can reach:\n")
                for (i in 0 until array.length()) {
                    val repo = array.optJSONObject(i) ?: continue
                    sb.append("- ").append(repo.optString("full_name"))
                    if (repo.optBoolean("private")) sb.append(" (private)")
                    sb.append('\n')
                }
                HandlerResult(true, sb.toString().trim())
            }
            "get_repo" -> {
                val r = repoOf() ?: return HandlerResult(false, "No repository specified. Save a default repo or pass \"repo\": \"owner/name\".")
                if (!validRepoName(r)) return HandlerResult(false, "Invalid repository '$r': expected owner/name format.")
                val response = ConnectorHttp.http("GET", "https://api.github.com/repos/$r", headers)
                if (!response.ok) return HandlerResult(false, "GitHub ${response.code}: ${ConnectorHttp.text(response.body, 300)}")
                val root = ConnectorHttp.json(response.body) ?: return HandlerResult(false, "GitHub returned invalid JSON")
                HandlerResult(
                    true,
                    "Repository: ${root.optString("full_name")} — ${root.optString("description") ?: "no description"}\n" +
                        "Stars: ${root.optInt("stargazers_count")}, Forks: ${root.optInt("forks_count")}, " +
                        "Default branch: ${root.optString("default_branch")}",
                )
            }
            "list_issues" -> {
                val r = repoOf() ?: return HandlerResult(false, "No repository specified.")
                val state = args.optString("state", "open")
                if (!validIssueState(state)) return HandlerResult(false, "Invalid state '$state': use open, closed or all.")
                val url = "https://api.github.com/repos/$r/issues?state=$state&per_page=20"
                val response = ConnectorHttp.http("GET", url, headers)
                if (!response.ok) return HandlerResult(false, "GitHub ${response.code}: ${ConnectorHttp.text(response.body, 300)}")
                val array = ConnectorHttp.jsonArray(response.body) ?: return HandlerResult(false, "GitHub returned invalid JSON")
                if (array.length() == 0) return HandlerResult(true, "No $state issues in $r.")
                val sb = StringBuilder("$state issues in $r:\n")
                for (i in 0 until array.length()) {
                    val issue = array.optJSONObject(i) ?: continue
                    sb.append("- #${issue.optInt("number")} ${issue.optString("title")}\n")
                }
                HandlerResult(true, sb.toString().trim())
            }
            "create_issue" -> {
                val r = repoOf() ?: return HandlerResult(false, "No repository specified.")
                val title = args.optString("title").trim()
                val body = args.optString("body").trim()
                if (title.isBlank()) return HandlerResult(false, "Missing issue title.")
                val payload = JSONObject().put("title", title).put("body", body)
                val response = ConnectorHttp.http("POST", "https://api.github.com/repos/$r/issues", headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "GitHub ${response.code}: ${ConnectorHttp.text(response.body, 300)}")
                val root = ConnectorHttp.json(response.body) ?: return HandlerResult(false, "GitHub returned invalid JSON")
                HandlerResult(true, "Created issue #${root.optInt("number")} in $r: ${root.optString("html_url")}")
            }
            else -> HandlerResult(false, "Unknown GitHub action '$action'")
        }
    }

    private fun normalizeGithubAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "get_repo", "repo", "repository", "get_repository", "repo_info", "repository_info",
            "get_repository_info", "info" -> "get_repo"
            "whoami", "me", "who_am_i", "user", "account", "token" -> "whoami"
            "list_repos", "repos", "repositories", "list_repositories", "my_repos" -> "list_repos"
            "list_issues", "issues", "get_issues", "list_issue", "open_issues", "list_open_issues" -> "list_issues"
            "create_issue", "issue", "new_issue", "open_issue", "file_issue", "create" -> "create_issue"
            else -> action.trim().lowercase()
        }

    /** GitHub full_name: owner/name where each part is alphanumeric + . _ -. */
    private fun validRepoName(repoName: String): Boolean = repoName.matches(Regex("^[\\w.-]+/[\\w.-]+$"))

    /** GitHub issue state filter. */
    private fun validIssueState(state: String): Boolean = state in listOf("open", "closed", "all")

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Slack connector — posts messages to a channel via webhook. */

class SlackHandler : ConnectorHandler {
    override val connectorId = "slack"
    override val description = "Slack — post messages to a channel via incoming webhook."
    override val risk = "medium"

    override fun describeActions(): String =
        "slack actions:\n- send: Post a message to the configured channel. args {\"text\": \"...\", \"channel\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "send", "post", "message" -> "send"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val webhook = config?.optString("webhookUrl")?.trim()
        if (webhook.isNullOrBlank()) return "webhook URL"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val webhookUrl = config?.optString("webhookUrl") ?: return HandlerResult(false, "Missing Slack webhook URL")
        return when (action) {
            "send" -> {
                val text = args.optString("text").trim()
                val channel = args.optString("channel").trim()
                if (text.isBlank()) return HandlerResult(false, "Missing message text.")
                val payload = JSONObject().put("text", text)
                if (channel.isNotBlank()) payload.put("channel", channel)
                val response = ConnectorHttp.http("POST", webhookUrl, mapOf("Content-Type" to "application/json"), payload.toString())
                if (!response.ok) return HandlerResult(false, "Slack ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Message posted to Slack.")
            }
            else -> HandlerResult(false, "Unknown Slack action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Discord connector — posts messages via webhook. */

class DiscordHandler : ConnectorHandler {
    override val connectorId = "discord"
    override val description = "Discord — post messages to a channel via webhook."
    override val risk = "medium"

    override fun describeActions(): String =
        "discord actions:\n- send: Post a message. args {\"content\": \"...\", \"username\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "send", "post", "message" -> "send"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val webhook = config?.optString("webhookUrl")?.trim()
        if (webhook.isNullOrBlank()) return "webhook URL"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val webhookUrl = config?.optString("webhookUrl") ?: return HandlerResult(false, "Missing Discord webhook URL")
        return when (action) {
            "send" -> {
                val content = args.optString("content").trim()
                val username = args.optString("username").trim()
                if (content.isBlank()) return HandlerResult(false, "Missing message content.")
                val payload = JSONObject().put("content", content)
                if (username.isNotBlank()) payload.put("username", username)
                val response = ConnectorHttp.http("POST", webhookUrl, mapOf("Content-Type" to "application/json"), payload.toString())
                if (!response.ok) return HandlerResult(false, "Discord ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Message posted to Discord.")
            }
            else -> HandlerResult(false, "Unknown Discord action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Telegram connector — sends messages via bot token. */

class TelegramHandler : ConnectorHandler {
    override val connectorId = "telegram"
    override val description = "Telegram — send messages via a bot token."
    override val risk = "medium"

    override fun describeActions(): String =
        "telegram actions:\n- send: Send a message to a chat. args {\"chat_id\": \"...\", \"text\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "send", "message" -> "send"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val token = config?.optString("botToken")?.trim()
        if (token.isNullOrBlank()) return "bot token"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val token = config?.optString("botToken") ?: return HandlerResult(false, "Missing Telegram bot token")
        return when (action) {
            "send" -> {
                val chatId = args.optString("chat_id").trim()
                val text = args.optString("text").trim()
                if (chatId.isBlank() || text.isBlank()) return HandlerResult(false, "Missing chat_id or text.")
                val url = "https://api.telegram.org/bot$token/sendMessage"
                val payload = JSONObject().put("chat_id", chatId).put("text", text)
                val response = ConnectorHttp.http("POST", url, mapOf("Content-Type" to "application/json"), payload.toString())
                if (!response.ok) return HandlerResult(false, "Telegram ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Message sent to Telegram chat $chatId.")
            }
            else -> HandlerResult(false, "Unknown Telegram action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** AgentMail connector — creates inboxes and sends email. */

class AgentMailHandler : ConnectorHandler {
    override val connectorId = "agentmail"
    override val description = "AgentMail — create inboxes and send email."
    override val risk = "medium"

    override fun describeActions(): String =
        "agentmail actions:\n- send_email: Send an email. args {\"to\": \"...\", \"subject\": \"...\", \"body\": \"...\"}\n- list_inboxes: List mailboxes.\n- create_inbox: Create a new inbox."

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "send", "send_mail", "mail" -> "send_email"
            "inboxes", "list_inbox" -> "list_inboxes"
            "create_mailbox", "new_inbox" -> "create_inbox"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val key = config?.optString("apiKey")?.trim()
        if (key.isNullOrBlank()) return "API key"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val apiKey = config?.optString("apiKey") ?: return HandlerResult(false, "Missing AgentMail API key")
        val headers = mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json")
        return when (action) {
            "send_email" -> {
                val to = args.optString("to").trim()
                val subject = args.optString("subject").trim()
                val body = args.optString("body").trim()
                if (to.isBlank() || subject.isBlank() || body.isBlank()) return HandlerResult(false, "Missing to, subject or body.")
                val payload = JSONObject().put("to", to).put("subject", subject).put("body", body)
                val response = ConnectorHttp.http("POST", "https://api.agentmail.to/v0/messages", headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "AgentMail ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Email sent to $to.")
            }
            "list_inboxes" -> {
                val response = ConnectorHttp.http("GET", "https://api.agentmail.to/v0/inboxes", headers)
                if (!response.ok) return HandlerResult(false, "AgentMail ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Inboxes listed: ${ConnectorHttp.text(response.body, 500)}")
            }
            "create_inbox" -> {
                val response = ConnectorHttp.http("POST", "https://api.agentmail.to/v0/inboxes", headers, "{}")
                if (!response.ok) return HandlerResult(false, "AgentMail ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Inbox created: ${ConnectorHttp.text(response.body, 500)}")
            }
            else -> HandlerResult(false, "Unknown AgentMail action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Notion connector — query databases and create pages. */

class NotionHandler : ConnectorHandler {
    override val connectorId = "notion"
    override val description = "Notion — query databases and create pages."
    override val risk = "medium"

    override fun describeActions(): String =
        "notion actions:\n- query: Query a database. args {\"database_id\": \"...\"}\n- create_page: Create a page. args {\"parent\": {...}, \"properties\": {...}}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "list", "list_pages", "query_database", "database_query", "search" -> "query"
            "create", "new_page", "add_page", "page", "insert", "add" -> "create_page"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val token = config?.optString("token")?.trim()
        if (token.isNullOrBlank()) return "token"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val token = config?.optString("token") ?: return HandlerResult(false, "Missing Notion token")
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Notion-Version" to "2022-06-28",
            "Content-Type" to "application/json",
        )
        return when (action) {
            "query" -> {
                val databaseId = args.optString("database_id").trim()
                if (databaseId.isBlank()) return HandlerResult(false, "Missing database_id.")
                val url = "https://api.notion.com/v1/databases/$databaseId/query"
                val payload = JSONObject()
                val response = ConnectorHttp.http("POST", url, headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "Notion ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Database queried: ${ConnectorHttp.text(response.body, 500)}")
            }
            "create_page" -> {
                val parent = Json.obj(args, "parent") ?: JSONObject().put("type", "workspace")
                val properties = Json.obj(args, "properties") ?: JSONObject()
                val payload = JSONObject().put("parent", parent).put("properties", properties)
                val response = ConnectorHttp.http("POST", "https://api.notion.com/v1/pages", headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "Notion ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Page created: ${ConnectorHttp.text(response.body, 500)}")
            }
            else -> HandlerResult(false, "Unknown Notion action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** PayPal connector — creates orders and captures payments. */

class PayPalHandler : ConnectorHandler {
    override val connectorId = "paypal"
    override val description = "PayPal — create orders and capture payments."
    override val risk = "high"

    override fun describeActions(): String =
        "paypal actions:\n- create_order: Create a payment order. args {\"amount\": \"...\", \"currency\": \"USD\"}\n- capture_order: Capture an authorised order. args {\"order_id\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "create_order", "create" -> "create_order"
            "capture_order", "capture" -> "capture_order"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val clientId = config?.optString("clientId")?.trim()
        val secret = config?.optString("secret")?.trim()
        if (clientId.isNullOrBlank()) return "client ID"
        if (secret.isNullOrBlank()) return "secret"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val clientId = config?.optString("clientId") ?: return HandlerResult(false, "Missing PayPal client ID")
        val secret = config?.optString("secret") ?: return HandlerResult(false, "Missing PayPal secret")
        val base = if (config?.optBoolean("sandbox") == true) "https://api-m.sandbox.paypal.com" else "https://api-m.paypal.com"
        return when (action) {
            "create_order" -> {
                val amount = args.optString("amount").trim()
                val currency = args.optString("currency", "USD").uppercase()
                if (amount.isBlank()) return HandlerResult(false, "Missing amount.")
                val payload = JSONObject().put("intent", "CAPTURE")
                    .put("purchase_units", JSONArray().put(
                        JSONObject().put("amount", JSONObject().put("currency_code", currency).put("value", amount))
                    ))
                val response = ConnectorHttp.http("POST", "$base/v2/checkout/orders", mapOf("Authorization" to "Basic $clientId:$secret", "Content-Type" to "application/json"), payload.toString())
                if (!response.ok) return HandlerResult(false, "PayPal ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Order created: ${ConnectorHttp.text(response.body, 500)}")
            }
            "capture_order" -> {
                val orderId = args.optString("order_id").trim()
                if (orderId.isBlank()) return HandlerResult(false, "Missing order_id.")
                val response = ConnectorHttp.http("POST", "$base/v2/checkout/orders/$orderId/capture", mapOf("Authorization" to "Basic $clientId:$secret", "Content-Type" to "application/json"), "{}")
                if (!response.ok) return HandlerResult(false, "PayPal ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Order captured: ${ConnectorHttp.text(response.body, 500)}")
            }
            else -> HandlerResult(false, "Unknown PayPal action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Polar connector — creates products and checkout links. */

class PolarHandler : ConnectorHandler {
    override val connectorId = "polar"
    override val description = "Polar — software licensing and checkout."
    override val risk = "high"

    override fun describeActions(): String =
        "polar actions:\n- create_product: Create a product. args {\"name\": \"...\", \"price\": \"...\"}\n- create_checkout: Create a checkout link. args {\"product_id\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "create_product", "product" -> "create_product"
            "create_checkout", "checkout" -> "create_checkout"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val token = config?.optString("accessToken")?.trim()
        if (token.isNullOrBlank()) return "access token"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val token = config?.optString("accessToken") ?: return HandlerResult(false, "Missing Polar access token")
        val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        return when (action) {
            "create_product" -> {
                val name = args.optString("name").trim()
                val price = args.optString("price").trim()
                if (name.isBlank() || price.isBlank()) return HandlerResult(false, "Missing name or price.")
                val payload = JSONObject().put("name", name).put("price", price)
                val response = ConnectorHttp.http("POST", "https://api.polar.sh/v1/products", headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "Polar ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Product created: ${ConnectorHttp.text(response.body, 500)}")
            }
            "create_checkout" -> {
                val productId = args.optString("product_id").trim()
                if (productId.isBlank()) return HandlerResult(false, "Missing product_id.")
                val payload = JSONObject().put("product_id", productId)
                val response = ConnectorHttp.http("POST", "https://api.polar.sh/v1/checkouts", headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "Polar ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Checkout created: ${ConnectorHttp.text(response.body, 500)}")
            }
            else -> HandlerResult(false, "Unknown Polar action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}
