package com.agoria.app.core

import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.webkit.CookieManager
import android.provider.CalendarContract
import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.Repository
import com.agoria.app.util.Files
import com.agoria.app.util.Json
import com.agoria.app.util.Notifier
import com.agoria.app.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.TimeUnit

data class ConnectorResult(
    val ok: Boolean,
    val summary: String,
    val error: String? = null,
)

class ConnectorExecutor(
    private val repo: Repository,
    private val context: android.content.Context,
) {

    private val media = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

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
        val def = ConnectorCatalog.get(connectorId)
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
        val canonical = canonicalAction(connectorId, action)
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
        missingConfig(def, config)?.let { missing ->
            return finish(connectorId, agentId, action, false, "Missing configuration: $missing")
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                dispatch(connectorId, connector, config, canonical, args, agentId)
            }
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

    private fun canonicalAction(connectorId: String, action: String): String {
        val normalised = action.trim().lowercase().replace('-', '_').replace(' ', '_')
        val generic = when (normalised) {
            "list_files", "ls", "dir", "glob", "files" -> "list"
            "cat", "open", "read_file", "show", "view" -> "read"
            "save", "create", "write_file", "create_file", "append", "append_file" -> "write"
            "rm", "remove", "unlink", "delete_file" -> "delete"
            else -> normalised
        }
        if (connectorId == "accounts" && generic == "open") return "open_url"
        if (connectorId == "files" && generic == "help") return "list"
        return when (connectorId) {
            "github" -> when (generic) {
                "repo", "repository", "get_repository", "repo_info", "repository_info", "info" -> "get_repo"
                "issues", "get_issues", "list_issue", "open_issues", "list_open_issues" -> "list_issues"
                "issue", "new_issue", "open_issue", "file_issue", "create" -> "create_issue"
                else -> generic
            }
            "notion" -> when (generic) {
                "list", "list_pages", "query_database", "database_query", "search" -> "query"
                "create", "new_page", "add_page", "page", "insert", "add" -> "create_page"
                else -> generic
            }
            "agentmail" -> when (generic) {
                "send", "send_mail", "mail" -> "send_email"
                "inboxes", "list_inbox" -> "list_inboxes"
                "create_mailbox", "new_inbox" -> "create_inbox"
                else -> generic
            }
            else -> generic
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

    private fun missingConfig(def: ConnectorDef, config: JSONObject?): String? {
        def.fields.filter { it.required }.forEach { field ->
            if (config?.optString(field.key).isNullOrBlank()) return field.label
        }
        return null
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
                val def = ConnectorCatalog.get(connectorId)
                val clean = when {
                    def == null -> "needs_setup"
                    row.configEnc != null -> "configured"
                    else -> ConnectorCatalog.defaultStatus(def)
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

    private suspend fun dispatch(
        connectorId: String,
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): ConnectorResult = when (connectorId) {
        "wikipedia" -> wikipedia(config, action, args)
        "hn" -> hackerNews(config, action, args)
        "search" -> webSearch(config, action, args)
        "weather" -> weather(config, action, args)
        "currency" -> currency(config, action, args)
        "device" -> device(config, action, args)
        "files" -> files(config, action, args, agentId)
        "http" -> http(config, action, args, agentId)
        "accounts" -> accounts(agentId, connector, action, args)
        "github" -> github(config, action, args)
        "slack" -> slack(config, action, args)
        "discord" -> discord(config, action, args)
        "notion" -> notion(config, action, args)
        "telegram" -> telegram(config, action, args)
        "paypal" -> paypal(config, action, args)
        "polar" -> polar(config, action, args)
        "agentmail" -> agentMail(config, action, args)
        "stripe" -> stripe(config, action, args)
        "cloudflare_workers" -> cloudflareWorkers(config, action, args)
        "supabase" -> supabase(config, action, args)
        "resend" -> resend(config, action, args)
        "upstash" -> upstash(config, action, args)
        "huggingface" -> huggingFace(config, action, args)
        "vercel" -> vercel(config, action, args)
        "netlify" -> netlify(config, action, args)
        "linear" -> linear(config, action, args)
        "terminal" -> terminal(config, action, args, agentId)
        else -> ConnectorResult(false, "Connector '$connectorId' has no executor.", "no executor")
    }

    // ------------------------------------------------------------------ generic

    private data class HttpResult(val code: Int, val body: String, val ok: Boolean)

    private fun http(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): HttpResult {
        // Edge APIs answer 429/500/503 under load, and a dropped socket is not a real answer.
        // Three attempts with 1s, 2s and 4s backoff turn most of those into a normal reply.
        var attempt = 0
        var last = HttpResult(-1, "not attempted", false)
        while (attempt < 3) {
            last = httpOnce(method, url, headers, body)
            val transient = last.code == -1 || last.code == 429 || last.code >= 500
            if (!transient) return last
            attempt++
            if (attempt < 3) {
                val waitMs = 1000L shl (attempt - 1)
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return last
                }
            }
        }
        return last
    }

    private fun httpOnce(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResult {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Dunder/1.0 (Android)")
            headers.forEach { (key, value) -> builder.header(key, value) }
            val request = when (method.uppercase()) {
                "GET" -> builder.get().build()
                "POST" -> builder.post((body ?: "").toRequestBody(media)).build()
                "PUT" -> builder.put((body ?: "").toRequestBody(media)).build()
                "PATCH" -> builder.patch((body ?: "").toRequestBody(media)).build()
                "DELETE" -> builder.delete().build()
                else -> builder.method(method.uppercase(), (body ?: "").toRequestBody(media)).build()
            }
            val response = client.newCall(request).execute()
            val responseBody = try {
                response.body?.string() ?: ""
            } catch (_: Exception) {
                ""
            }
            HttpResult(response.code, responseBody, response.isSuccessful)
        } catch (e: Exception) {
            HttpResult(-1, e.message ?: "Network error", false)
        }
    }

    private fun cfg(config: JSONObject?, key: String): String? =
        config?.optString(key)?.trim()?.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u0000-\\u001f\\u007f]"), "")?.ifBlank { null }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun text(value: String?, max: Int = 700): String =
        (value ?: "")
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(max)

    private fun json(body: String): JSONObject? = Json.tryObject(body)

    private fun jsonArray(body: String): JSONArray? = try {
        JSONArray(body)
    } catch (_: Exception) {
        null
    }

    private fun ConnectorResult.asOk(): ConnectorResult = this

    // ---------------------------------------------------------------- wikipedia

    private fun wikipedia(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        return when (action) {
            "search" -> {
                val query = args.optString("query").ifBlank { return ConnectorResult(false, "Missing query") }
                val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
                    enc(query) + "&format=json&srlimit=5"
                val response = http("GET", url)
                if (!response.ok) return ConnectorResult(false, "Wikipedia error: ${text(response.body, 240)}")
                val root = json(response.body)
                    ?: return ConnectorResult(false, "Wikipedia returned invalid JSON")
                val search = root.optJSONObject("query")?.optJSONArray("search")
                    ?: return ConnectorResult(true, "No Wikipedia results for '$query'.")
                val sb = StringBuilder("Wikipedia results for '$query':\n")
                for (i in 0 until minOf(5, search.length())) {
                    val hit = search.getJSONObject(i)
                    sb.append("- ").append(hit.optString("title"))
                        .append(": ").append(text(hit.optString("snippet"), 180)).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "summary" -> {
                val title = args.optString("title").ifBlank { return ConnectorResult(false, "Missing title") }
                val url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + enc(title)
                val response = http("GET", url)
                if (!response.ok) return ConnectorResult(false, "Wikipedia error: ${text(response.body, 240)}")
                val root = json(response.body)
                    ?: return ConnectorResult(false, "Wikipedia returned invalid JSON")
                val extract = root.optString("extract")
                if (extract.isBlank()) return ConnectorResult(true, "No summary found for '$title'.")
                ConnectorResult(
                    true,
                    "Wikipedia: ${root.optString("title")}\n$extract\n${root.optString("content_urls").let {
                        if (it.isBlank()) "" else it
                    }}",
                )
            }
            else -> ConnectorResult(false, "Unknown Wikipedia action '$action'")
        }
    }

    // --------------------------------------------------------------- hacker news

    private fun hackerNews(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        return when (action) {
            "top" -> {
                val limit = args.optInt("limit", 10).coerceIn(1, 20)
                val idsResponse = http("GET", "https://hacker-news.firebaseio.com/v0/topstories.json")
                if (!idsResponse.ok) return ConnectorResult(false, "Hacker News error: ${text(idsResponse.body, 240)}")
                val ids = JSONArray(idsResponse.body)
                val sb = StringBuilder("Hacker News top stories:\n")
                for (i in 0 until minOf(limit, ids.length())) {
                    val id = ids.optInt(i)
                    val itemResponse = http("GET", "https://hacker-news.firebaseio.com/v0/item/$id.json")
                    if (!itemResponse.ok) continue
                    val item = json(itemResponse.body) ?: continue
                    sb.append("- ").append(item.optString("title"))
                        .append(" (").append(item.optString("score")).append(" points, ")
                        .append(item.optString("by")).append(")\n  ")
                        .append(item.optString("url").ifBlank { "https://news.ycombinator.com/item?id=$id" })
                        .append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "search" -> {
                val query = args.optString("query").ifBlank { return ConnectorResult(false, "Missing query") }
                val limit = args.optInt("limit", 10).coerceIn(1, 20)
                val url = "https://hn.algolia.com/api/v1/search?query=" + enc(query) +
                    "&tags=story&hitsPerPage=$limit"
                val response = http("GET", url)
                if (!response.ok) return ConnectorResult(false, "Hacker News search error: ${text(response.body, 240)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Hacker News returned invalid JSON")
                val hits = root.optJSONArray("hits") ?: return ConnectorResult(true, "No Hacker News results.")
                val sb = StringBuilder("Hacker News results for '$query':\n")
                for (i in 0 until minOf(limit, hits.length())) {
                    val hit = hits.getJSONObject(i)
                    sb.append("- ").append(hit.optString("title"))
                        .append(" (").append(hit.optString("points")).append(" points)\n  ")
                        .append(hit.optString("url").ifBlank { "https://news.ycombinator.com/item?id=" + hit.optString("objectID") })
                        .append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            else -> ConnectorResult(false, "Unknown Hacker News action '$action'")
        }
    }

    // ---------------------------------------------------------------- web search

    private fun webSearch(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        if (action != "search") return ConnectorResult(false, "Unknown search action '$action'")
        val query = args.optString("query").ifBlank { return ConnectorResult(false, "Missing query") }
        val url = "https://api.duckduckgo.com/?q=" + enc(query) + "&format=json&no_html=1&skip_disambig=1"
        val response = http("GET", url)
        if (!response.ok) return ConnectorResult(false, "Search error: ${text(response.body, 240)}")
        val root = json(response.body) ?: return ConnectorResult(false, "Search returned invalid JSON")
        val sb = StringBuilder("Instant answer for '$query':\n")
        val abstractText = root.optString("AbstractText")
        if (abstractText.isNotBlank()) {
            sb.append(abstractText).append('\n')
            val abstractUrl = root.optString("AbstractURL")
            if (abstractUrl.isNotBlank()) sb.append(abstractUrl).append('\n')
        }
        val related = root.optJSONArray("RelatedTopics")
        if (related != null) {
            var count = 0
            for (i in 0 until related.length()) {
                if (count >= 3) break
                val topic = related.optJSONObject(i) ?: continue
                val nested = topic.optJSONArray("Topics")
                if (nested != null && nested.length() > 0) {
                    val first = nested.getJSONObject(0)
                    sb.append("- ").append(text(first.optString("Text"), 200)).append('\n')
                    count++
                } else {
                    val t = topic.optString("Text")
                    if (t.isNotBlank()) {
                        sb.append("- ").append(text(t, 200)).append('\n')
                        count++
                    }
                }
            }
        }
        if (sb.length <= "Instant answer for '$query':\n".length) {
            // Most queries have no instant answer, which used to look like a broken connector.
            // Fall through to the HTML result page and summarise the top hits.
            val results = duckDuckGoResults(query)
            if (results.isNotBlank()) {
                sb.append("Web results:\n").append(results)
            } else {
                sb.append("No instant answer and no web results for '$query'.")
            }
        }
        return ConnectorResult(true, sb.toString().trim())
    }

    private fun duckDuckGoResults(query: String): String {
        val response = try {
            client.newCall(
                Request.Builder()
                    .url("https://html.duckduckgo.com/html/?q=" + enc(query))
                    .header("User-Agent", "Mozilla/5.0 (Android) Dunder/1.0")
                    .build(),
            ).execute()
        } catch (_: Exception) {
            return ""
        }
        val html = try {
            response.body?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (html.isBlank()) return ""
        val titleRegex = Regex("""result__a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""result__snippet[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippets = snippetRegex.findAll(html).map { text(it.groupValues[1], 160) }.toList()
        val sb = StringBuilder()
        titleRegex.findAll(html).take(5).forEachIndexed { index, match ->
            val raw = match.groupValues[1].replace("&amp;", "&")
            val link = if (raw.contains("uddg=")) {
                java.net.URLDecoder.decode(raw.substringAfter("uddg=").substringBefore("&"), "UTF-8")
            } else {
                raw
            }
            sb.append("- ").append(text(match.groupValues[2], 120))
            if (link.isNotBlank()) sb.append(" — ").append(link)
            sb.append('\n')
            val snippet = snippets.getOrNull(index)
            if (!snippet.isNullOrBlank()) sb.append("  ").append(snippet).append('\n')
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------- weather

    private fun weather(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val lat = args.optDouble("latitude", Double.NaN)
        val lon = args.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return ConnectorResult(false, "Missing latitude and longitude")
        return when (action) {
            "current" -> {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current_weather=true&timezone=auto"
                val response = http("GET", url)
                if (!response.ok) return ConnectorResult(false, "Weather error: ${text(response.body, 240)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Weather returned invalid JSON")
                val current = root.optJSONObject("current_weather")
                    ?: return ConnectorResult(false, "No current weather data")
                ConnectorResult(
                    true,
                    "Current weather at $lat, $lon: " +
                        "${current.optDouble("temperature")}°C, " +
                        "wind ${current.optDouble("windspeed")} km/h, " +
                        "weather code ${current.optInt("weathercode")} " +
                        "(${current.optString("time")}).",
                )
            }
            "forecast" -> {
                val days = args.optInt("days", 3).coerceIn(1, 7)
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&daily=temperature_2m_max,temperature_2m_min,weathercode&timezone=auto&days=$days"
                val response = http("GET", url)
                if (!response.ok) return ConnectorResult(false, "Weather error: ${text(response.body, 240)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Weather returned invalid JSON")
                val daily = root.optJSONObject("daily") ?: return ConnectorResult(false, "No forecast data")
                val dates = daily.optJSONArray("time")
                val max = daily.optJSONArray("temperature_2m_max")
                val min = daily.optJSONArray("temperature_2m_min")
                val codes = daily.optJSONArray("weathercode")
                if (dates == null) return ConnectorResult(false, "No forecast days")
                val sb = StringBuilder("Forecast for $lat, $lon:\n")
                for (i in 0 until dates.length()) {
                    sb.append("- ").append(dates.optString(i))
                        .append(": ").append(min?.optDouble(i)?.toString() ?: "?")
                        .append("°C to ").append(max?.optDouble(i)?.toString() ?: "?")
                        .append("°C, code ").append(codes?.optInt(i) ?: "?").append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            else -> ConnectorResult(false, "Unknown weather action '$action'")
        }
    }

    // ------------------------------------------------------------------ currency

    private fun currency(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        return when (action) {
            "convert" -> {
                val from = args.optString("from", "USD").uppercase()
                val to = args.optString("to", "EUR").uppercase()
                val amount = args.optDouble("amount", 1.0)
                val url = "https://api.frankfurter.app/latest?from=$from&to=$to"
                val response = http("GET", url)
                if (!response.ok) return ConnectorResult(false, "Currency error: ${text(response.body, 240)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Currency returned invalid JSON")
                val rate = root.optJSONObject("rates")?.optDouble(to)
                    ?: return ConnectorResult(false, "No rate for $from to $to")
                ConnectorResult(
                    true,
                    "$amount $from = %.2f $to (rate %s, %s)".format(amount * rate, rate, root.optString("date")),
                )
            }
            "latest" -> {
                val from = args.optString("from", "USD").uppercase()
                val url = "https://api.frankfurter.app/latest?from=$from"
                val response = http("GET", url)
                if (!response.ok) return ConnectorResult(false, "Currency error: ${text(response.body, 240)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Currency returned invalid JSON")
                val rates = root.optJSONObject("rates") ?: return ConnectorResult(true, "No rates found.")
                val sb = StringBuilder("1 $from = (ECB rates, ${root.optString("date")})\n")
                val keys = rates.keys()
                var count = 0
                while (keys.hasNext() && count < 8) {
                    val key = keys.next()
                    sb.append("- ").append(key).append(": ").append(rates.optDouble(key)).append('\n')
                    count++
                }
                ConnectorResult(true, sb.toString().trim())
            }
            else -> ConnectorResult(false, "Unknown currency action '$action'")
        }
    }

    // -------------------------------------------------------------------- device

    private fun device(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        return when (action) {
            "info", "get_info" -> {
                val batteryManager = context.getSystemService(BatteryManager::class.java)
                val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                ConnectorResult(
                    true,
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                        "battery $level%, time ${Time.dateTime(Time.now())}.",
                )
            }
            "notify" -> {
                val title = args.optString("title", "Dunder").ifBlank { "Dunder" }
                val body = args.optString("body").ifBlank { return ConnectorResult(false, "Missing body") }
                Notifier.notifyLocal(context, title, body)
                ConnectorResult(true, "Posted local notification: $title")
            }
            else -> ConnectorResult(false, "Unknown device action '$action'")
        }
    }

    // --------------------------------------------------------------------- files

    private fun files(
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): ConnectorResult {
        // Each agent used to get its own root, so a file `list` had just shown was "not found"
        // for any other agent and the answer flipped between "empty" and a listing. Everything
        // now resolves against the shared workspace, which is where builds are written too.
        val root = Files.workspace(context).canonicalFile
        // Models answer with "/notes.txt" or "./notes.txt" for the same file, so treat both as
        // relative to the workspace instead of rejecting them as an escape attempt.
        val relative = args.optString("path", "")
            .trim()
            .replace('\\', '/')
            .trimStart('/')
            .removePrefix("./")
        val target = if (relative.isBlank()) root else File(root, relative).canonicalFile
        if (target.canonicalPath != root.canonicalPath && !target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            val contents = Files.listFiles(root).take(12).joinToString(", ") {
                if (it.isDirectory) it.name + "/" else it.name
            }
            val workspaceContent = contents.ifBlank { "nothing yet" }
            return ConnectorResult(
                false,
                "That path points outside the agent workspace. Give a path relative to it, like \"notes.txt\". The workspace currently holds: $workspaceContent.",
            )
        }
        return when (action) {
            "list" -> {
                val dir = if (target.isDirectory) target else target.parentFile ?: root
                val files = Files.listFiles(dir)
                if (files.isEmpty()) return ConnectorResult(true, "Workspace is empty.")
                val sb = StringBuilder("Files in ${dir.name}:\n")
                files.take(40).forEach {
                    sb.append("- ").append(it.name)
                        .append(if (it.isDirectory) "/" else " (${Files.prettySize(it.length())})")
                        .append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "read" -> {
                if (!target.exists() || target.isDirectory) {
                    val name = relative.substringAfterLast('/')
                    val matches = findByName(root, name)
                    val hint = if (matches.isEmpty()) {
                        ""
                    } else {
                        " Did you mean: ${matches.joinToString(", ")}?"
                    }
                    return ConnectorResult(false, "File not found: $relative.$hint")
                }
                val content = Files.readText(target)
                    ?: return ConnectorResult(false, "Could not read $relative")
                ConnectorResult(true, content.take(20_000))
            }
            "write" -> {
                val content = args.optString("content")
                if (content.isBlank()) return ConnectorResult(false, "Missing content")
                Files.writeText(target, content.take(200_000))
                ConnectorResult(true, "Wrote ${content.length} characters to $relative.")
            }
            else -> ConnectorResult(false, "Unknown files action '$action'")
        }
    }

    private fun findByName(root: File, name: String): List<String> {
        if (name.isBlank()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.name.equals(name, ignoreCase = true) }
            .take(5)
            .map { root.toPath().relativize(it.toPath()).toString().replace('\\', '/') }
            .toList()
    }

    // ---------------------------------------------------------------------- http

    private suspend fun http(config: JSONObject?, action: String, args: JSONObject, agentId: String?): ConnectorResult {
        val baseUrl = cfg(config, "baseUrl") ?: return ConnectorResult(false, "Missing base URL")
        val agentToken = if (cfg(config, "authSource") == "agent") {
            agentId?.let { repo.agent(it)?.apiToken }?.ifBlank { null }
        } else {
            null
        }
        val apiKey = agentToken ?: cfg(config, "apiKey")
        val defaultHeaders = parseHeaders(cfg(config, "defaultHeaders"))
        val method = args.optString("method", "GET").uppercase()
        val path = args.optString("path", "")
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        }
        val headers = defaultHeaders.toMutableMap()
        if (!apiKey.isNullOrBlank()) headers["Authorization"] = "Bearer $apiKey"
        parseHeaders(args.optString("headers", "").ifBlank { null })?.let { headers.putAll(it) }
        val body = args.optString("body", "").ifBlank { null }
        val response = http(method, url, headers, if (method == "GET" || method == "HEAD") null else body)
        return if (response.ok) {
            ConnectorResult(true, "HTTP $method $url -> ${response.code}\n${text(response.body, 900)}")
        } else {
            ConnectorResult(false, "HTTP $method $url failed (${response.code}): ${text(response.body, 500)}")
        }
    }

    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = Json.tryObject(raw) ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optString(key)
        }
        return out
    }

    // ------------------------------------------------------------------ accounts
    //
    // Everything here happens inside the office browser, a WebView that keeps its own cookies.
    // The user signs in there once. The app never reads the accounts on the phone and never sees a
    // password, so the agents work as the provided account and not as the device owner.

    private suspend fun accounts(
        agentId: String?,
        connector: ConnectorEntity,
        action: String,
        args: JSONObject,
    ): ConnectorResult = when (action) {
        "list" -> browserSession(agentId)
        "signin" -> openInOfficeBrowser(
            agentId,
            connector,
            args.optString("url").trim().ifBlank { "https://accounts.google.com/ServiceLogin" },
            "Opened the office browser sign-in page for this agent. The session is kept for the office only.",
        )
        "email" -> draftEmail(agentId, connector, args)
        "event" -> addEvent(agentId, connector, args)
        "open_url", "open", "browse" -> openLink(agentId, connector, args)
        "read" -> readBrowser(agentId)
        "view" -> {
            val id = agentId ?: return ConnectorResult(true, "Open Browsers to watch an agent's page.")
            com.agoria.app.browser.BrowserHub.show(id)
            ConnectorResult(true, "Showing this agent's browser on screen.")
        }
        // New interaction actions
        "click" -> clickElement(agentId, connector, args)
        "fill" -> fillElement(agentId, connector, args)
        "submit" -> submitForm(agentId, connector, args)
        // Visual verification
        "screenshot" -> takeScreenshot(agentId, connector, args)
        // Structured extraction
        "extract" -> extractData(agentId, connector, args)
        // Tab/session management
        "tab" -> manageTab(agentId, connector, args)
        // Download support
        "download" -> downloadFile(agentId, connector, args)
        // JS-heavy page handling
        "wait" -> waitForCondition(agentId, connector, args)
        // Scroll support
        "scroll" -> scrollElement(agentId, connector, args)
        else -> ConnectorResult(
            false,
            "Unknown Office Browser action '$action'. Use list_actions to see them: " +
                "list, signin, email, event, open_url, read, view, click, fill, submit, screenshot, extract, tab, download, wait, scroll.",
        )
    }

    private fun readBrowser(agentId: String?): ConnectorResult {
        if (agentId == null) return ConnectorResult(false, "This action needs an agent context.")
        var text: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        com.agoria.app.browser.BrowserHub.readPage(agentId) { value ->
            text = value
            latch.countDown()
        }
        // evaluateJavascript answers on the main thread; wait a moment for it without blocking for
        // long, because a page with no loaded document simply has nothing to report.
        latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        val value = text?.trim()
        return if (value.isNullOrBlank()) {
            ConnectorResult(
                true,
                "No page text to read yet. Open a page first with open_url; it loads in the background.",
            )
        } else {
            ConnectorResult(true, value.take(3500))
        }
    }

    // ========== NEW BROWSER INTERACTION ACTIONS ==========

    private suspend fun clickElement(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        if (agentId == null) return ConnectorResult(false, "This action needs an agent context.")
        val selector = args.optString("selector").trim().ifBlank { return ConnectorResult(false, "Missing selector") }
        val textMatch = args.optString("text").trim()
        val waitForNav = args.optBoolean("waitForNavigation", true)

        val script = buildClickScript(selector, textMatch, waitForNav)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            val extra = textMatch.ifBlank { " ($selector)" } ?: ""
            val navMsg = if (waitForNav) " Waited for navigation." else ""
            ConnectorResult(true, "Clicked element$extra.$navMsg")
        } else {
            ConnectorResult(false, "Click failed: ${result.error}")
        }
    }

    private suspend fun fillElement(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        if (agentId == null) return ConnectorResult(false, "This action needs an agent context.")
        val selector = args.optString("selector").trim().ifBlank { return ConnectorResult(false, "Missing selector") }
        val value = args.optString("value").ifBlank { return ConnectorResult(false, "Missing value") }
        val isSelect = args.optBoolean("isSelect", false)

        val script = buildFillScript(selector, value, isSelect)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            val preview = value.take(100)
            ConnectorResult(true, "Filled $selector with: $preview")
        } else {
            ConnectorResult(false, "Fill failed: ${result.error}")
        }
    }

    private suspend fun submitForm(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        if (agentId == null) return ConnectorResult(false, "This action needs an agent context.")
        val selector = args.optString("selector").trim().ifBlank { return ConnectorResult(false, "Missing selector") }
        val waitForNav = args.optBoolean("waitForNavigation", true)

        val script = buildSubmitScript(selector, waitForNav)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            val navMsg = if (waitForNav) " Waited for navigation." else ""
            ConnectorResult(true, "Submitted form $selector.$navMsg")
        } else {
            ConnectorResult(false, "Submit failed: ${result.error}")
        }
    }

    private suspend fun takeScreenshot(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        if (agentId == null) return ConnectorResult(false, "This action needs an agent context.")
        val fullPage = args.optBoolean("fullPage", false)

        val result = executeBrowserScript(agentId, buildScreenshotScript(fullPage))
        return if (result.ok) {
            ConnectorResult(true, "Screenshot captured (base64 PNG).")
        } else {
            ConnectorResult(false, "Screenshot failed: ${result.error}")
        }
    }

    private suspend fun extractData(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        if (agentId == null) return ConnectorResult(false, "This action needs an agent context.")
        val selector = args.optString("selector").trim().ifBlank { return ConnectorResult(false, "Missing selector") }
        val type = args.optString("type", "auto").trim().lowercase() // table, links, elements, auto

        val script = buildExtractScript(selector, type)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            ConnectorResult(true, result.summary)
        } else {
            ConnectorResult(false, "Extract failed: ${result.error}")
        }
    }

    private suspend fun manageTab(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        if (agentId == null) return ConnectorResult(false, "This action needs an agent context.")
        val tabAction = args.optString("action", "new").trim().lowercase() // new, switch, close, list
        val url = args.optString("url").trim()
        val tabId = args.optString("tabId").trim()

        return when (tabAction) {
            "new" -> {
                if (url.isBlank()) return ConnectorResult(false, "Missing url for new tab")
                openInOfficeBrowser(agentId, connector, url, "Opened new tab: $url", show = false)
            }
            "switch" -> {
                if (tabId.isBlank()) return ConnectorResult(false, "Missing tabId for switch")
                // BrowserHub doesn't support multiple tabs yet; open URL instead
                if (url.isNotBlank()) {
                    openInOfficeBrowser(agentId, connector, url, "Switched to tab with URL: $url", show = false)
                } else {
                    ConnectorResult(false, "Tab switching not fully implemented; use open_url with new URL")
                }
            }
            "close" -> {
                ConnectorResult(false, "Close tab not implemented; navigate away instead")
            }
            "list" -> {
                // Return current session info
                browserSession(agentId)
            }
            else -> ConnectorResult(false, "Unknown tab action: $tabAction. Use new, switch, close, or list.")
        }
    }

    private suspend fun downloadFile(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        if (agentId == null) return ConnectorResult(false, "This action needs an agent context.")
        val url = args.optString("url").trim().ifBlank { return ConnectorResult(false, "Missing url") }
        val filename = args.optString("filename", "download").trim()

        // Use the existing download mechanism via the browser
        val script = buildDownloadScript(url, filename)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            ConnectorResult(true, "Download initiated for $filename from $url")
        } else {
            ConnectorResult(false, "Download failed: ${result.error}")
        }
    }

    private suspend fun waitForCondition(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        if (agentId == null) return ConnectorResult(false, "This action needs an agent context.")
        val selector = args.optString("selector").trim()
        val textMatch = args.optString("text").trim()
        val timeoutMs = args.optLong("timeoutMs", 10000).coerceIn(1000, 60000)
        val condition = args.optString("condition", "element").trim().lowercase() // element, text, networkIdle

        val script = buildWaitScript(selector, textMatch, condition, timeoutMs)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            val extra = selector.ifBlank { " (text: $textMatch)" } ?: ""
            ConnectorResult(true, "Wait condition met: $condition$extra")
        } else {
            ConnectorResult(false, "Wait failed: ${result.error}")
        }
    }

    private suspend fun scrollElement(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        if (agentId == null) return ConnectorResult(false, "This action needs an agent context.")
        val direction = args.optString("direction", "down").trim().lowercase() // up, down, top, bottom
        val pixels = args.optInt("pixels", 500).coerceIn(10, 10000)
        val selector = args.optString("selector").trim()
        val behavior = args.optString("behavior", "smooth").trim().lowercase() // smooth, instant

        val script = buildScrollScript(direction, pixels, selector, behavior)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            val target = selector.ifBlank { "by ${direction} $pixels px" } ?: ""
            ConnectorResult(true, "Scrolled $target")
        } else {
            ConnectorResult(false, "Scroll failed: ${result.error}")
        }
    }

    // ========== HELPER FUNCTIONS FOR BROWSER SCRIPTS ==========

    private data class ScriptResult(val ok: Boolean, val summary: String, val error: String? = null)

    private suspend fun executeBrowserScript(agentId: String, script: String): ScriptResult {
        var result: ScriptResult? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        com.agoria.app.browser.BrowserHub.executeScript(agentId, script) { value ->
            result = ScriptResult(true, value ?: "Script executed (no return value)")
            latch.countDown()
        }
        // Wait for script execution with timeout
        val completed = latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            return ScriptResult(false, "Script execution timed out")
        }
        return result ?: ScriptResult(false, "No result from script execution")
    }

    private fun buildClickScript(selector: String, textMatch: String, waitForNav: Boolean): String {
        val waitScript = if (waitForNav) """
                    return new Promise(resolve => {
                        var resolved = false;
                        var timeout = setTimeout(() => { resolved = true; resolve('Clicked (no navigation detected)'); }, 5000);
                        window.addEventListener('beforeunload', function() { clearTimeout(timeout); resolved = true; resolve('Clicked and navigation started'); }, {once: true});
                        // Also check for SPA navigation
                        var lastUrl = window.location.href;
                        var checkInterval = setInterval(() => {
                            if (window.location.href !== lastUrl) {
                                clearTimeout(timeout);
                                clearInterval(checkInterval);
                                if (!resolved) { resolved = true; resolve('Clicked and SPA navigation detected'); }
                            }
                        }, 100);
                    });""" else "return 'Clicked (no wait for navigation)';"
        return """
            (function() {
                try {
                    var element = null;
                    if ('$textMatch'.length > 0) {
                        // Find by text content
                        var elements = document.querySelectorAll('*');
                        for (var i = 0; i < elements.length; i++) {
                            var el = elements[i];
                            if (el.textContent && el.textContent.trim().includes('$textMatch')) {
                                element = el;
                                break;
                            }
                        }
                    } else {
                        element = document.querySelector('$selector');
                    }
                    if (!element) {
                        return 'Element not found: ' + ('$textMatch'.length > 0 ? 'text=\"$textMatch\"' : 'selector=\"$selector\"');
                    }
                    element.click();
                    $waitScript
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """.trimIndent()
    }

    private fun buildFillScript(selector: String, value: String, isSelect: Boolean): String {
        val escapedValue = value.replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        return if (isSelect) """
            (function() {
                try {
                    var select = document.querySelector('$selector');
                    if (!select) return 'Select element not found: $selector';
                    if (select.tagName !== 'SELECT') return 'Element is not a SELECT: $selector';
                    var found = false;
                    for (var i = 0; i < select.options.length; i++) {
                        if (select.options[i].value === '$escapedValue' || select.options[i].text === '$escapedValue') {
                            select.selectedIndex = i;
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        // Try to set value directly
                        select.value = '$escapedValue';
                    }
                    select.dispatchEvent(new Event('change', {bubbles: true}));
                    return 'Filled select: $escapedValue';
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """ else """
            (function() {
                try {
                    var input = document.querySelector('$selector');
                    if (!input) return 'Input element not found: $selector';
                    var tag = input.tagName.toLowerCase();
                    if (tag === 'input' || tag === 'textarea') {
                        input.value = '$escapedValue';
                        input.dispatchEvent(new Event('input', {bubbles: true}));
                        input.dispatchEvent(new Event('change', {bubbles: true}));
                        return 'Filled input: $escapedValue';
                    } else if (input.isContentEditable) {
                        input.textContent = '$escapedValue';
                        input.dispatchEvent(new Event('input', {bubbles: true}));
                        return 'Filled contenteditable: $escapedValue';
                    } else {
                        return 'Element is not fillable: $selector';
                    }
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """
    }

    private fun buildSubmitScript(selector: String, waitForNav: Boolean): String {
        val waitScript = if (waitForNav) """
                    return new Promise(resolve => {
                        var resolved = false;
                        var timeout = setTimeout(() => { resolved = true; resolve('Submitted (no navigation detected)'); }, 5000);
                        window.addEventListener('beforeunload', function() { clearTimeout(timeout); resolved = true; resolve('Submitted and navigation started'); }, {once: true});
                        var lastUrl = window.location.href;
                        var checkInterval = setInterval(() => {
                            if (window.location.href !== lastUrl) {
                                clearTimeout(timeout);
                                clearInterval(checkInterval);
                                if (!resolved) { resolved = true; resolve('Submitted and SPA navigation detected'); }
                            }
                        }, 100);
                    });""" else "return 'Submitted (no wait for navigation)';"
        return """
            (function() {
                try {
                    var form = document.querySelector('$selector');
                    if (!form) return 'Form not found: $selector';
                    if (form.tagName !== 'FORM') return 'Element is not a FORM: $selector';
                    form.submit();
                    $waitScript
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """.trimIndent()
    }

    private fun buildScreenshotScript(fullPage: Boolean): String {
        return if (fullPage) """
            (function() {
                try {
                    // For full page screenshot, we need to scroll and capture
                    return new Promise(async (resolve) => {
                        var originalScroll = window.scrollY;
                        var height = document.body.scrollHeight;
                        var width = document.body.scrollWidth;
                        window.scrollTo(0, 0);
                        await new Promise(r => setTimeout(r, 100));
                        var canvas = document.createElement('canvas');
                        canvas.width = width;
                        canvas.height = height;
                        var ctx = canvas.getContext('2d');
                        // Note: html2canvas would be needed for true full-page capture
                        // This is a simplified version that captures viewport
                        resolve('Full page screenshot requires html2canvas library. Use fullPage=false for viewport capture.');
                    });
                } catch (e) {
                    resolve('Error: ' + e.message);
                }
            })();
        """ else """
            (function() {
                try {
                    // Viewport screenshot using canvas
                    return new Promise((resolve) => {
                        html2canvas(document.body).then(function(canvas) {
                            var dataUrl = canvas.toDataURL('image/png');
                            resolve(dataUrl);
                        }).catch(function(err) {
                            // Fallback: return error if html2canvas not loaded
                            resolve('Screenshot requires html2canvas. Error: ' + err.message);
                        });
                    });
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """
    }

    private fun buildExtractScript(selector: String, type: String): String {
        return """
            (function() {
                try {
                    var elements = document.querySelectorAll('$selector');
                    if (elements.length === 0) return 'No elements found for selector: $selector';
                    var results = [];
                    var extractType = '$type';
                    elements.forEach(function(el) {
                        if (extractType === 'table' || (extractType === 'auto' && el.tagName === 'TABLE')) {
                            // Extract table as array of arrays
                            var rows = el.querySelectorAll('tr');
                            var tableData = [];
                            rows.forEach(function(row) {
                                var cells = row.querySelectorAll('th, td');
                                var rowData = [];
                                cells.forEach(function(cell) {
                                    rowData.push(cell.textContent.trim());
                                });
                                tableData.push(rowData);
                            });
                            results.push({type: 'table', selector: '$selector', data: tableData});
                        } else if (extractType === 'links' || (extractType === 'auto' && el.tagName === 'A')) {
                            // Extract links
                            var links = el.querySelectorAll('a[href]');
                            var linkData = [];
                            links.forEach(function(a) {
                                linkData.push({text: a.textContent.trim(), href: a.href});
                            });
                            results.push({type: 'links', selector: '$selector', data: linkData});
                        } else {
                            // Generic element extraction
                            results.push({
                                tag: el.tagName,
                                id: el.id,
                                class: el.className,
                                text: el.textContent.trim().substring(0, 500),
                                html: el.outerHTML.substring(0, 2000)
                            });
                        }
                    });
                    return JSON.stringify(results);
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """.trimIndent()
    }

    private fun buildDownloadScript(url: String, filename: String): String {
        return """
            (function() {
                try {
                    // Create a link and trigger download
                    var a = document.createElement('a');
                    a.href = '$url';
                    a.download = '$filename';
                    document.body.appendChild(a);
                    a.click();
                    document.body.removeChild(a);
                    return 'Download triggered for $filename';
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """.trimIndent()
    }

    private fun buildScrollScript(direction: String, pixels: Int, selector: String, behavior: String): String {
        return when {
            selector.isNotBlank() -> """
                (function() {
                    try {
                        var element = document.querySelector('$selector');
                        if (!element) return 'Element not found for scroll: $selector';
                        element.scrollIntoView({behavior: '$behavior', block: 'center'});
                        return 'Scrolled to element: $selector';
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
            direction == "top" -> """
                (function() {
                    try {
                        window.scrollTo({top: 0, behavior: '$behavior'});
                        return 'Scrolled to top';
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
            direction == "bottom" -> """
                (function() {
                    try {
                        window.scrollTo({top: document.body.scrollHeight, behavior: '$behavior'});
                        return 'Scrolled to bottom';
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
            direction == "up" -> """
                (function() {
                    try {
                        window.scrollBy({top: -$pixels, behavior: '$behavior'});
                        return 'Scrolled up $pixels px';
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
            else -> """
                (function() {
                    try {
                        window.scrollBy({top: $pixels, behavior: '$behavior'});
                        return 'Scrolled down $pixels px';
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
        }
    }

    private fun buildWaitScript(selector: String, textMatch: String, condition: String, timeoutMs: Long): String {
        return when (condition) {
            "networkidle" -> """
                (function() {
                    try {
                        return new Promise((resolve) => {
                            var timeout = setTimeout(() => resolve('Network idle timeout after \${timeoutMs}ms'), ${timeoutMs});
                            var pending = 0;
                            var origXHR = window.XMLHttpRequest;
                            window.XMLHttpRequest = function() {
                                var xhr = new origXHR();
                                var origOpen = xhr.open;
                                xhr.open = function() {
                                    pending++;
                                    return origOpen.apply(this, arguments);
                                };
                                xhr.addEventListener('loadend', function() {
                                    pending--;
                                    if (pending === 0) {
                                        clearTimeout(timeout);
                                        resolve('Network idle');
                                    }
                                });
                                return xhr;
                            };
                            // Also check fetch
                            var origFetch = window.fetch;
                            window.fetch = function() {
                                pending++;
                                return origFetch.apply(this, arguments).then(r => { pending--; if (pending === 0) { clearTimeout(timeout); resolve('Network idle'); } return r; });
                            };
                            // If already idle
                            if (pending === 0) { clearTimeout(timeout); resolve('Network already idle'); }
                        });
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
            "text" -> if (textMatch.isNotBlank()) """
                (function() {
                    try {
                        return new Promise((resolve) => {
                            var timeout = setTimeout(() => resolve('Text wait timeout after \${timeoutMs}ms'), ${timeoutMs});
                            var check = function() {
                                if (document.body.textContent.includes('$textMatch')) {
                                    clearTimeout(timeout);
                                    resolve('Text found: $textMatch');
                                }
                            };
                            check();
                            var observer = new MutationObserver(check);
                            observer.observe(document.body, {childList: true, subtree: true, characterData: true});
                        });
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent() else """
                (function() {
                    return 'Error: text condition requires text parameter';
                })();
            """.trimIndent()
            else -> if (selector.isNotBlank()) """
                (function() {
                    try {
                        return new Promise((resolve) => {
                            var timeout = setTimeout(() => resolve('Element wait timeout after \${timeoutMs}ms'), ${timeoutMs});
                            var check = function() {
                                var el = document.querySelector('$selector');
                                if (el) {
                                    clearTimeout(timeout);
                                    resolve('Element found: $selector');
                                }
                            };
                            check();
                            var observer = new MutationObserver(check);
                            observer.observe(document.body, {childList: true, subtree: true});
                        });
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent() else """
                (function() {
                    return 'Error: element condition requires selector parameter';
                })();
            """.trimIndent()
        }
    }

    private fun browserSession(agentId: String?): ConnectorResult {
        val cookies = try {
            CookieManager.getInstance()
        } catch (_: Exception) {
            null
        }
        val google = cookies?.getCookie("https://accounts.google.com").orEmpty()
        val gmail = cookies?.getCookie("https://mail.google.com").orEmpty()
        val calendar = cookies?.getCookie("https://calendar.google.com").orEmpty()
        val mine = agentId?.let { com.agoria.app.browser.BrowserHub.session(it) }
        val sb = StringBuilder("Office browser session:\n")
        sb.append("- Google sign-in: ").append(if (google.isNotBlank()) "signed in" else "not signed in yet").append('\n')
        sb.append("- Gmail: ").append(if (gmail.isNotBlank()) "reachable" else "will ask to sign in").append('\n')
        sb.append("- Google Calendar: ").append(if (calendar.isNotBlank()) "reachable" else "will ask to sign in").append('\n')
        if (mine != null) {
            sb.append("- This agent's page: ").append(mine.url.ifBlank { "none" })
                .append(if (mine.loading) " (loading)" else "").append('\n')
            if (mine.title.isNotBlank()) sb.append("- Page title: ").append(mine.title).append('\n')
            if (mine.error != null) sb.append("- Last error: ").append(mine.error).append('\n')
        }
        sb.append("\nPages load in this agent's own background browser. Use read to get the text, ")
        sb.append("view to put it on screen, or signin to sign the office in.")
        return ConnectorResult(true, sb.toString().trim())
    }

    /**
     * Opens a page in the agent's own background browser. The old behaviour started a full-screen
     * activity for every link, which stole the screen from whatever the user was doing.
     */
    private suspend fun openInOfficeBrowser(
        agentId: String?,
        connector: ConnectorEntity,
        url: String,
        okSummary: String,
        show: Boolean = false,
    ): ConnectorResult {
        val agent = agentId?.let { id -> repo.agent(id) }
        val id = agentId ?: "office"
        val name = agent?.name ?: "Office"
        val started = com.agoria.app.browser.BrowserHub.open(id, name, url, show)
        return if (started) {
            ConnectorResult(true, okSummary)
        } else {
            ConnectorResult(false, "Could not open $url in the office browser.")
        }
    }

    private suspend fun draftEmail(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        val to = args.optString("to").trim()
        val subject = args.optString("subject").trim()
        val body = args.optString("body")
        if (to.isBlank() && subject.isBlank() && body.isBlank()) {
            return ConnectorResult(false, "Give the draft a recipient, a subject or a body.")
        }
        val url = "https://mail.google.com/mail/?view=cm&fs=1" +
            "&to=" + enc(to) + "&su=" + enc(subject) + "&body=" + enc(body)
        val recipient = to.ifBlank { "a recipient of your choice" }
        return openInOfficeBrowser(
            agentId,
            connector,
            url,
            "Drafted a Gmail message for $recipient in this agent's " +
                "background browser. Nothing is sent until the user opens Browsers, reviews it and presses send.",
        )
    }

    private suspend fun addEvent(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        val title = args.optString("title").trim().ifBlank { return ConnectorResult(false, "Missing event title") }
        val start = parseInstant(args.optString("start"))
            ?: return ConnectorResult(false, "Missing start time. Use ISO-8601, for example 2026-11-20T10:00:00Z.")
        val end = parseInstant(args.optString("end")) ?: (start + 60 * 60 * 1000L)
        if (end <= start) return ConnectorResult(false, "The event end must be after its start.")
        val location = args.optString("location")
        val description = args.optString("description")
        val url = "https://calendar.google.com/calendar/render?action=TEMPLATE" +
            "&text=" + enc(title) +
            "&dates=" + calendarStamp(start) + "/" + calendarStamp(end) +
            (if (location.isBlank()) "" else "&location=" + enc(location)) +
            (if (description.isBlank()) "" else "&details=" + enc(description))
        return openInOfficeBrowser(
            agentId,
            connector,
            url,
            "Opened '$title' in Google Calendar inside this agent's background browser. " +
                "The user confirms it there and it lands in the office account.",
        )
    }

    private suspend fun openLink(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): ConnectorResult {
        val raw = args.optString("url").trim().ifBlank { return ConnectorResult(false, "Missing url") }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            return ConnectorResult(false, "Only http and https links can be opened.")
        }
        return openInOfficeBrowser(agentId, connector, raw, "Opened $raw in this agent's background browser.")
    }

    private fun calendarStamp(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.of("UTC"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))

    private fun parseInstant(raw: String): Long? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    value.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it }
                }
            }
        }
    }

    // Agents name actions in their own words ("create", "issues", "get_repository"), so map the
    // obvious synonyms onto the three calls this connector actually implements instead of failing
    // with "unknown action" for a request that was perfectly clear.
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

    private fun normalizeNotionAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "query", "list", "list_pages", "query_database", "database_query" -> "query"
            "search", "find", "lookup", "search_pages" -> "search"
            "create_page", "create", "new_page", "add_page", "page", "insert", "add" -> "create_page"
            else -> action.trim().lowercase()
        }

    // -------------------------------------------------------------------- github

    private fun github(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val token = cfg(config, "token") ?: return ConnectorResult(false, "Missing GitHub token")
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )

        fun repoOf(): String? {
            val explicit = args.optString("repo").trim().ifBlank { cfg(config, "repo") ?: "" }
            if (explicit.isNotBlank()) return explicit
            // A fine-grained token already knows its owner, so there is no owner/name to ask for.
            // If it reaches exactly one repository, use that one; otherwise ask for a name.
            val response = http("GET", "https://api.github.com/user/repos?per_page=100&sort=updated", headers)
            if (!response.ok) return null
            val array = runCatching { JSONArray(response.body) }.getOrNull() ?: return null
            return if (array.length() == 1) array.optJSONObject(0)?.optString("full_name") else null
        }

        return when (normalizeGithubAction(action)) {
            "whoami" -> {
                val response = http("GET", "https://api.github.com/user", headers)
                if (!response.ok) return ConnectorResult(false, "GitHub ${response.code}: ${text(response.body, 300)}")
                val root = json(response.body) ?: return ConnectorResult(false, "GitHub returned invalid JSON")
                ConnectorResult(
                    true,
                    "Signed in as ${root.optString("login")} (${root.optString("name")}). " +
                        "This token can reach the repositories it was granted.",
                )
            }
            "list_repos" -> {
                val response = http("GET", "https://api.github.com/user/repos?per_page=20&sort=updated", headers)
                if (!response.ok) return ConnectorResult(false, "GitHub ${response.code}: ${text(response.body, 300)}")
                val array = jsonArray(response.body) ?: return ConnectorResult(false, "GitHub returned invalid JSON")
                if (array.length() == 0) {
                    // /user/repos only lists repositories the token's own account owns or
                    // collaborates on. A token can still reach a repository outside that list
                    // ("Ejthetired14/Dunder" answered 200 while this call returned nothing), so
                    // probe the repository saved in the connector before reporting emptiness.
                    val saved = cfg(config, "repo")
                        ?: return ConnectorResult(
                            true,
                            "This token's account owns no repositories and is not listed as a " +
                                "collaborator on any. Save a default repository (owner/name) in this " +
                                "connector, then list again.",
                        )
                    val probe = http("GET", "https://api.github.com/repos/$saved", headers)
                    return if (probe.ok) {
                        ConnectorResult(true, "Repositories this token can reach:\n- $saved")
                    } else {
                        ConnectorResult(false, "The token cannot read $saved (GitHub ${probe.code}).")
                    }
                }
                val sb = StringBuilder("Repositories this token can reach:\n")
                for (i in 0 until array.length()) {
                    val repo = array.optJSONObject(i) ?: continue
                    sb.append("- ").append(repo.optString("full_name"))
                    if (repo.optBoolean("private")) sb.append(" (private)")
                    sb.append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "get_repo" -> {
                val repoName = repoOf()
                    ?: return ConnectorResult(
                        false,
                        "No repository selected. Save a default repository (owner/name) in this " +
                            "connector, or pass \"repo\": \"owner/name\" with the action.",
                    )
                val response = http("GET", "https://api.github.com/repos/$repoName", headers)
                if (!response.ok) return ConnectorResult(false, "GitHub ${response.code}: ${text(response.body, 300)}")
                val root = json(response.body) ?: return ConnectorResult(false, "GitHub returned invalid JSON")
                ConnectorResult(
                    true,
                    "Repository ${root.optString("full_name")}: ${root.optString("description")}\n" +
                        "stars ${root.optInt("stargazers_count")}, forks ${root.optInt("forks_count")}, " +
                        "open issues ${root.optInt("open_issues_count")}, " +
                        "default branch ${root.optString("default_branch")}.",
                )
            }
            "list_issues" -> {
                val repoName = repoOf()
                    ?: return ConnectorResult(
                        false,
                        "No repository selected. Save a default repository (owner/name) in this " +
                            "connector, or pass \"repo\": \"owner/name\" with the action.",
                    )
                val state = args.optString("state", "open")
                val response = http(
                    "GET",
                    "https://api.github.com/repos/$repoName/issues?state=$state&per_page=5",
                    headers,
                )
                if (!response.ok) return ConnectorResult(false, "GitHub ${response.code}: ${text(response.body, 300)}")
                val array = jsonArray(response.body) ?: return ConnectorResult(false, "GitHub returned invalid JSON")
                if (array.length() == 0) return ConnectorResult(true, "No $state issues in $repoName.")
                val sb = StringBuilder("GitHub issues ($state) in $repoName:\n")
                for (i in 0 until array.length()) {
                    val issue = array.optJSONObject(i) ?: continue
                    sb.append("#").append(issue.optInt("number"))
                        .append(" ").append(issue.optString("title"))
                        .append(" — ").append(issue.optJSONObject("user")?.optString("login") ?: "?")
                        .append('\n').append("  ").append(issue.optString("html_url")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "create_issue" -> {
                val repoName = repoOf()
                    ?: return ConnectorResult(
                        false,
                        "No repository selected. Save a default repository (owner/name) in this " +
                            "connector, or pass \"repo\": \"owner/name\" with the action.",
                    )
                val title = args.optString("title").ifBlank { return ConnectorResult(false, "Missing title") }
                val body = args.optString("body", "")
                val payload = JSONObject().put("title", title).put("body", body)
                val response = http(
                    "POST",
                    "https://api.github.com/repos/$repoName/issues",
                    headers,
                    payload.toString(),
                )
                if (!response.ok) {
                    return ConnectorResult(false, "GitHub ${response.code}: ${text(response.body, 400)}")
                }
                val root = json(response.body)
                ConnectorResult(
                    true,
                    "Created issue #${root?.optInt("number")} in $repoName: $title\n${root?.optString("html_url") ?: ""}",
                )
            }
            "get_file" -> {
                val repoName = repoOf()
                    ?: return ConnectorResult(
                        false,
                        "No repository selected. Save a default repository (owner/name) in this " +
                            "connector, or pass \"repo\": \"owner/name\" with the action.",
                    )
                val path = args.optString("path").ifBlank { return ConnectorResult(false, "Missing path") }
                val ref = args.optString("ref", "main")
                val response = http(
                    "GET",
                    "https://api.github.com/repos/$repoName/contents/$path?ref=${Uri.encode(ref)}",
                    headers,
                )
                if (!response.ok) {
                    return ConnectorResult(false, "GitHub ${response.code}: ${text(response.body, 400)}")
                }
                val root = json(response.body) ?: return ConnectorResult(false, "GitHub returned invalid JSON")
                // File content is base64 encoded
                val content = root.optString("content").replace("\n", "")
                val decoded = runCatching { String(Base64.getDecoder().decode(content)) }.getOrNull()
                ConnectorResult(
                    true,
                    "File: $path @ $ref\n---\n${decoded ?: "[binary or empty file]"}\n---\nSHA: ${root.optString("sha")}",
                )
            }
            "create_branch" -> {
                val repoName = repoOf()
                    ?: return ConnectorResult(
                        false,
                        "No repository selected. Save a default repository (owner/name) in this " +
                            "connector, or pass \"repo\": \"owner/name\" with the action.",
                    )
                val branch = args.optString("branch").ifBlank { return ConnectorResult(false, "Missing branch name") }
                val from = args.optString("from", "main")
                // Get the SHA of the base ref
                val refResponse = http(
                    "GET",
                    "https://api.github.com/repos/$repoName/git/ref/heads/$from",
                    headers,
                )
                if (!refResponse.ok) {
                    return ConnectorResult(false, "GitHub ${refResponse.code}: base ref '$from' not found")
                }
                val refJson = json(refResponse.body) ?: return ConnectorResult(false, "GitHub returned invalid JSON")
                val sha = refJson.optJSONObject("object")?.optString("sha")
                    ?: return ConnectorResult(false, "Could not resolve base ref SHA")
                // Create the new branch
                val payload = JSONObject().put("ref", "refs/heads/$branch").put("sha", sha)
                val response = http(
                    "POST",
                    "https://api.github.com/repos/$repoName/git/refs",
                    headers,
                    payload.toString(),
                )
                if (!response.ok) {
                    return ConnectorResult(false, "GitHub ${response.code}: ${text(response.body, 400)}")
                }
                ConnectorResult(true, "Created branch '$branch' from '$from' (sha: $sha) in $repoName")
            }
            "put_file" -> {
                val repoName = repoOf()
                    ?: return ConnectorResult(
                        false,
                        "No repository selected. Save a default repository (owner/name) in this " +
                            "connector, or pass \"repo\": \"owner/name\" with the action.",
                    )
                val branch = args.optString("branch").ifBlank { return ConnectorResult(false, "Missing branch") }
                if (branch.lowercase() == "main") {
                    return ConnectorResult(false, "Refusing to push directly to 'main'. Use a feature branch and open a PR.")
                }
                val path = args.optString("path").ifBlank { return ConnectorResult(false, "Missing path") }
                val content = args.optString("content").ifBlank { return ConnectorResult(false, "Missing content (base64)") }
                val message = args.optString("message").ifBlank { return ConnectorResult(false, "Missing commit message") }
                val sha = args.optString("sha") // Optional: required for updates
                val payload = JSONObject().put("message", message).put("content", content).put("branch", branch)
                if (sha.isNotBlank()) payload.put("sha", sha)
                val response = http(
                    "PUT",
                    "https://api.github.com/repos/$repoName/contents/$path",
                    headers,
                    payload.toString(),
                )
                if (!response.ok) {
                    return ConnectorResult(false, "GitHub ${response.code}: ${text(response.body, 400)}")
                }
                val root = json(response.body)
                ConnectorResult(
                    true,
                    "Committed ${if (sha.isBlank()) "new" else "updated"} file '$path' on branch '$branch' in $repoName\n${root?.optJSONObject("commit")?.optString("html_url") ?: ""}",
                )
            }
            "create_pr" -> {
                val repoName = repoOf()
                    ?: return ConnectorResult(
                        false,
                        "No repository selected. Save a default repository (owner/name) in this " +
                            "connector, or pass \"repo\": \"owner/name\" with the action.",
                    )
                val head = args.optString("head").ifBlank { return ConnectorResult(false, "Missing head branch") }
                val base = args.optString("base", "main")
                val title = args.optString("title").ifBlank { return ConnectorResult(false, "Missing title") }
                val body = args.optString("body", "")
                val payload = JSONObject().put("title", title).put("head", head).put("base", base).put("body", body)
                val response = http(
                    "POST",
                    "https://api.github.com/repos/$repoName/pulls",
                    headers,
                    payload.toString(),
                )
                if (!response.ok) {
                    return ConnectorResult(false, "GitHub ${response.code}: ${text(response.body, 400)}")
                }
                val root = json(response.body)
                ConnectorResult(
                    true,
                    "Opened PR #${root?.optInt("number")}: $title ($head -> $base)\n${root?.optString("html_url") ?: ""}",
                )
            }
            "list_prs" -> {
                val repoName = repoOf()
                    ?: return ConnectorResult(
                        false,
                        "No repository selected. Save a default repository (owner/name) in this " +
                            "connector, or pass \"repo\": \"owner/name\" with the action.",
                    )
                val state = args.optString("state", "open")
                val response = http(
                    "GET",
                    "https://api.github.com/repos/$repoName/pulls?state=$state&per_page=20",
                    headers,
                )
                if (!response.ok) {
                    return ConnectorResult(false, "GitHub ${response.code}: ${text(response.body, 400)}")
                }
                val array = jsonArray(response.body) ?: return ConnectorResult(false, "GitHub returned invalid JSON")
                if (array.length() == 0) return ConnectorResult(true, "No $state PRs in $repoName.")
                val sb = StringBuilder("Pull requests ($state) in $repoName:\n")
                for (i in 0 until array.length()) {
                    val pr = array.optJSONObject(i) ?: continue
                    sb.append("#").append(pr.optInt("number"))
                        .append(" ").append(pr.optString("title"))
                        .append(" (").append(pr.optString("head.label")).append(" -> ").append(pr.optString("base.label")).append(")")
                        .append(" — ").append(pr.optJSONObject("user")?.optString("login") ?: "?")
                        .append('\n').append("  ").append(pr.optString("html_url")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            else -> ConnectorResult(
                false,
                "Unknown GitHub action '$action'. This connector understands whoami, list_repos, " +
                    "get_repo, list_issues, create_issue, get_file, create_branch, put_file, create_pr, list_prs.",
            )
        }
    }

    // --------------------------------------------------------------------- slack

    private fun slack(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val webhook = cfg(config, "webhookUrl") ?: return ConnectorResult(false, "Missing Slack webhook")
        val textValue = args.optString("text").ifBlank { return ConnectorResult(false, "Missing text") }
        val payload = JSONObject().put("text", textValue)
        cfg(config, "channel")?.let { payload.put("channel", it) }
        val response = http("POST", webhook, body = payload.toString())
        return if (response.ok) {
            ConnectorResult(true, "Posted to Slack: ${textValue.take(160)}")
        } else {
            ConnectorResult(false, "Slack error: ${text(response.body, 300)}")
        }
    }

    // ------------------------------------------------------------------- discord

    private fun discord(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val webhook = cfg(config, "webhookUrl") ?: return ConnectorResult(false, "Missing Discord webhook")
        val content = args.optString("content").ifBlank { return ConnectorResult(false, "Missing content") }
        val payload = JSONObject().put("content", content)
        cfg(config, "username")?.let { payload.put("username", it) }
        val response = http("POST", webhook, body = payload.toString())
        return if (response.ok) {
            ConnectorResult(true, "Posted to Discord: ${content.take(160)}")
        } else {
            ConnectorResult(false, "Discord error: ${text(response.body, 300)}")
        }
    }

    // -------------------------------------------------------------------- notion

    private fun notion(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val token = cfg(config, "token")
            ?: return ConnectorResult(false, "Missing Notion token")
        val databaseId = args.optString("database_id").trim()
            .ifBlank { cfg(config, "databaseId") ?: "" }
        val parentPageId = args.optString("parent_page_id").trim()
            .ifBlank { cfg(config, "parentPageId") ?: "" }
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Notion-Version" to "2022-06-28",
            "Content-Type" to "application/json",
        )

        fun fail(response: HttpResult, what: String): ConnectorResult {
            val body = text(response.body, 300)
            val hint = when (response.code) {
                401 -> " The integration token is not valid."
                403, 404 -> " Share the page or database with the integration in Notion first."
                else -> ""
            }
            return ConnectorResult(false, "Notion $what failed (HTTP ${response.code}): $body$hint")
        }

        fun search(query: String): ConnectorResult {
            val payload = JSONObject()
                .put("query", query)
                .put("page_size", args.optInt("page_size", 10).coerceIn(1, 25))
            val response = http("POST", "https://api.notion.com/v1/search", headers, payload.toString())
            if (!response.ok) return fail(response, "search")
            val root = json(response.body) ?: return ConnectorResult(false, "Notion returned invalid JSON")
            val results = root.optJSONArray("results") ?: return ConnectorResult(true, "Nothing found in this workspace.")
            if (results.length() == 0) return ConnectorResult(true, "Nothing shared with this integration matched.")
            val sb = StringBuilder("Notion results:\n")
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                sb.append("- ").append(notionTitle(item))
                    .append(" (").append(item.optString("object")).append(") ")
                    .append(item.optString("id")).append('\n')
            }
            return ConnectorResult(true, sb.toString().trim())
        }

        return when (normalizeNotionAction(action)) {
            "search" -> search(args.optString("query", ""))

            "query" -> {
                if (databaseId.isBlank()) {
                    val fallback = search(args.optString("query", ""))
                    return if (fallback.ok) {
                        ConnectorResult(
                            true,
                            fallback.summary + "\n\nNo database id is configured, so this is a workspace " +
                                "search. Add a database id in the connector to query its rows.",
                        )
                    } else {
                        fallback
                    }
                }
                val payload = JSONObject().put("page_size", args.optInt("page_size", 5).coerceIn(1, 25))
                val response = http(
                    "POST",
                    "https://api.notion.com/v1/databases/" + enc(databaseId) + "/query",
                    headers,
                    payload.toString(),
                )
                if (!response.ok) return fail(response, "database query")
                val root = json(response.body) ?: return ConnectorResult(false, "Notion returned invalid JSON")
                val results = root.optJSONArray("results") ?: return ConnectorResult(true, "No pages found.")
                val sb = StringBuilder("Notion pages:\n")
                for (i in 0 until results.length()) {
                    val page = results.optJSONObject(i) ?: continue
                    sb.append("- ").append(notionTitle(page)).append(" ").append(page.optString("id")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }

            "create_page" -> {
                val title = args.optString("title").ifBlank { return ConnectorResult(false, "Missing title") }
                val content = args.optString("content", "")
                val parent = when {
                    databaseId.isNotBlank() -> JSONObject().put("database_id", databaseId)
                    parentPageId.isNotBlank() -> JSONObject().put("page_id", parentPageId)
                    else -> return ConnectorResult(
                        false,
                        "Notion needs a destination. Add a database id in the connector, or pass " +
                            "\"parent_page_id\" with the id of a page shared with the integration.",
                    )
                }
                val payload = JSONObject()
                    .put("parent", parent)
                    .put(
                        "properties",
                        if (parent.has("database_id")) {
                            JSONObject().put(
                                "title",
                                JSONObject().put(
                                    "title",
                                    JSONArray().put(JSONObject().put("text", JSONObject().put("content", title))),
                                ),
                            )
                        } else {
                            JSONObject().put(
                                "title",
                                JSONObject().put(
                                    "title",
                                    JSONArray().put(JSONObject().put("text", JSONObject().put("content", title))),
                                ),
                            )
                        },
                    )
                if (content.isNotBlank()) {
                    payload.put(
                        "children",
                        JSONArray().put(
                            JSONObject()
                                .put("object", "block")
                                .put("type", "paragraph")
                                .put(
                                    "paragraph",
                                    JSONObject().put(
                                        "rich_text",
                                        JSONArray().put(
                                            JSONObject().put(
                                                "text",
                                                JSONObject().put("content", content.take(1900)),
                                            ),
                                        ),
                                    ),
                                ),
                        ),
                    )
                }
                val response = http("POST", "https://api.notion.com/v1/pages", headers, payload.toString())
                if (!response.ok) return fail(response, "create page")
                ConnectorResult(true, "Created Notion page '$title'.")
            }

            else -> ConnectorResult(
                false,
                "Unknown Notion action '$action'. This connector understands search, query and create_page.",
            )
        }
    }

    private fun notionTitle(item: JSONObject): String {
        item.optJSONObject("properties")?.let { properties ->
            val keys = properties.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val property = properties.optJSONObject(key) ?: continue
                val array = property.optJSONArray("title") ?: continue
                val text = array.optJSONObject(0)?.optString("plain_text").orEmpty()
                if (text.isNotBlank()) return text
            }
        }
        item.optJSONArray("title")?.let { array ->
            val text = array.optJSONObject(0)?.optString("plain_text").orEmpty()
            if (text.isNotBlank()) return text
        }
        return item.optString("url").ifBlank { "untitled" }
    }

    // ------------------------------------------------------------------ telegram

    private fun telegram(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val token = cfg(config, "botToken") ?: return ConnectorResult(false, "Missing Telegram bot token")
        val chatId = cfg(config, "chatId") ?: return ConnectorResult(false, "Missing chat id")
        return when (action) {
            "send" -> {
                val textValue = args.optString("text").ifBlank { return ConnectorResult(false, "Missing text") }
                val payload = JSONObject().put("chat_id", chatId).put("text", textValue)
                val response = http(
                    "POST",
                    "https://api.telegram.org/bot$token/sendMessage",
                    body = payload.toString(),
                )
                if (!response.ok) return ConnectorResult(false, "Telegram error: ${text(response.body, 300)}")
                ConnectorResult(true, "Sent Telegram message to $chatId.")
            }
            "get_updates" -> {
                val limit = args.optInt("limit", 5).coerceIn(1, 20)
                val response = http(
                    "GET",
                    "https://api.telegram.org/bot$token/getUpdates?limit=$limit",
                )
                if (!response.ok) return ConnectorResult(false, "Telegram error: ${text(response.body, 300)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Telegram returned invalid JSON")
                val result = root.optBoolean("ok")
                if (!result) return ConnectorResult(false, "Telegram getUpdates failed: ${text(response.body, 300)}")
                val updates = root.optJSONArray("result") ?: return ConnectorResult(true, "No updates.")
                val sb = StringBuilder("Telegram updates:\n")
                for (i in 0 until updates.length()) {
                    val update = updates.getJSONObject(i)
                    sb.append("- update ").append(update.optInt("update_id")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            else -> ConnectorResult(false, "Unknown Telegram action '$action'")
        }
    }

    // ---------------------------------------------------------------- stripe

    private fun stripe(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val secretKey = cfg(config, "secretKey") ?: return ConnectorResult(false, "Missing Stripe secret key")
        val auth = mapOf("Authorization" to "Bearer $secretKey")
        return when (action) {
            "create_product" -> {
                val name = args.optString("name").ifBlank { return ConnectorResult(false, "Missing product name") }
                val description = args.optString("description", "")
                val payload = JSONObject().put("name", name).put("description", description)
                val response = http("POST", "https://api.stripe.com/v1/products", auth, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Stripe error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Stripe returned invalid JSON")
                ConnectorResult(
                    true,
                    "Created product: ${root.optString("id")} — ${root.optString("name")}\n${root.optString("description")}",
                )
            }
            "create_price" -> {
                val productId = args.optString("productId").ifBlank { return ConnectorResult(false, "Missing productId") }
                val amountCents = args.optLong("amountCents", 0L)
                if (amountCents <= 0) return ConnectorResult(false, "amountCents must be > 0")
                val currency = args.optString("currency", "usd").lowercase()
                val interval = args.optString("interval", "month").lowercase()
                if (!listOf("day", "week", "month", "year").contains(interval)) {
                    return ConnectorResult(false, "interval must be day, week, month, or year")
                }
                val payload = JSONObject()
                    .put("product", productId)
                    .put("unit_amount", amountCents)
                    .put("currency", currency)
                    .put("recurring", JSONObject().put("interval", interval))
                val response = http("POST", "https://api.stripe.com/v1/prices", auth, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Stripe error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Stripe returned invalid JSON")
                ConnectorResult(
                    true,
                    "Created price: ${root.optString("id")} — ${root.optLong("unit_amount")} ${root.optString("currency")}/${root.optJSONObject("recurring")?.optString("interval")}",
                )
            }
            "create_customer" -> {
                val email = args.optString("email").ifBlank { return ConnectorResult(false, "Missing email") }
                val name = args.optString("name", "")
                val payload = JSONObject().put("email", email).put("name", name)
                val response = http("POST", "https://api.stripe.com/v1/customers", auth, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Stripe error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Stripe returned invalid JSON")
                ConnectorResult(
                    true,
                    "Created customer: ${root.optString("id")} — ${root.optString("email")} (${root.optString("name")})",
                )
            }
            "create_subscription" -> {
                val customerId = args.optString("customerId").ifBlank { return ConnectorResult(false, "Missing customerId") }
                val priceId = args.optString("priceId").ifBlank { return ConnectorResult(false, "Missing priceId") }
                val payload = JSONObject().put("customer", customerId).put("items", JSONArray().put(JSONObject().put("price", priceId)))
                val response = http("POST", "https://api.stripe.com/v1/subscriptions", auth, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Stripe error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Stripe returned invalid JSON")
                ConnectorResult(
                    true,
                    "Created subscription: ${root.optString("id")} for customer ${root.optString("customer")}, status: ${root.optString("status")}",
                )
            }
            "verify_key" -> {
                val apiKey = args.optString("apiKey").ifBlank { return ConnectorResult(false, "Missing apiKey") }
                // Check if it's a valid Stripe secret key by trying to list products (limited)
                val testAuth = mapOf("Authorization" to "Bearer $apiKey")
                val response = http("GET", "https://api.stripe.com/v1/products?limit=1", testAuth)
                if (!response.ok) {
                    return ConnectorResult(false, "Invalid API key or key has no access: ${text(response.body, 300)}")
                }
                val root = json(response.body) ?: return ConnectorResult(false, "Stripe returned invalid JSON")
                ConnectorResult(
                    true,
                    "API key is valid. Account has access to products.",
                )
            }
            "usage_record" -> {
                val subscriptionItemId = args.optString("subscriptionItemId").ifBlank { return ConnectorResult(false, "Missing subscriptionItemId") }
                val quantity = args.optInt("quantity", 1).coerceAtLeast(1)
                val payload = JSONObject().put("quantity", quantity)
                val response = http("POST", "https://api.stripe.com/v1/subscription_items/$subscriptionItemId/usage_records", auth, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Stripe error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Stripe returned invalid JSON")
                ConnectorResult(
                    true,
                    "Recorded usage: ${root.optInt("quantity")} units for ${root.optString("subscription_item")}",
                )
            }
            "list_products" -> {
                val limit = args.optInt("limit", 10).coerceIn(1, 25)
                val response = http("GET", "https://api.stripe.com/v1/products?limit=$limit", auth)
                if (!response.ok) return ConnectorResult(false, "Stripe error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Stripe returned invalid JSON")
                val data = root.optJSONArray("data") ?: return ConnectorResult(true, "No products found.")
                val sb = StringBuilder("Stripe products:\n")
                for (i in 0 until data.length()) {
                    val p = data.getJSONObject(i)
                    sb.append("- ").append(p.optString("id")).append(": ").append(p.optString("name")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "get_product" -> {
                val productId = args.optString("productId").ifBlank { return ConnectorResult(false, "Missing productId") }
                val response = http("GET", "https://api.stripe.com/v1/products/$productId", auth)
                if (!response.ok) return ConnectorResult(false, "Stripe error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Stripe returned invalid JSON")
                ConnectorResult(
                    true,
                    "Product: ${root.optString("id")} — ${root.optString("name")}\n${root.optString("description")}\nActive: ${root.optBoolean("active")}, Created: ${root.optLong("created")}",
                )
            }
            "list_events" -> {
                val eventType = args.optString("type", "")
                val limit = args.optInt("limit", 10).coerceIn(1, 50)
                val typeParam = if (eventType.isNotBlank()) "&type=$eventType" else ""
                val url = "https://api.stripe.com/v1/events?limit=$limit$typeParam"
                val response = http("GET", url, auth)
                if (!response.ok) return ConnectorResult(false, "Stripe error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Stripe returned invalid JSON")
                val data = root.optJSONArray("data") ?: return ConnectorResult(true, "No events found.")
                val typeLabel = eventType.ifBlank { "all" }
                val sb = StringBuilder("Stripe events (type: $typeLabel):\n")
                for (i in 0 until data.length()) {
                    val e = data.getJSONObject(i)
                    sb.append("- ${e.optString("id")}: ${e.optString("type")} at ${e.optLong("created")}\n")
                    if (e.optString("type") == "invoice.payment_succeeded") {
                        val obj = e.optJSONObject("data")?.optJSONObject("object")
                        val amount = obj?.optLong("amount_paid", 0L)
                        val currency = obj?.optString("currency", "usd")
                        val customer = obj?.optString("customer", "")
                        val subscription = obj?.optString("subscription", "")
                        sb.append("  → Payment: $amount $currency for customer $customer, sub: $subscription\n")
                    }
                }
                ConnectorResult(true, sb.toString().trim())
            }
            else -> ConnectorResult(false, "Unknown Stripe action '$action'")
        }
    }

    // -------------------------------------------------------------------- paypal

    private fun paypalToken(base: String, clientId: String, secret: String): String? {
        val basic = android.util.Base64.encodeToString(
            "$clientId:$secret".toByteArray(),
            android.util.Base64.NO_WRAP,
        )
        val response = http(
            "POST",
            "$base/v1/oauth2/token",
            mapOf(
                "Authorization" to "Basic $basic",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
            "grant_type=client_credentials",
        )
        if (!response.ok) return null
        return json(response.body)?.optString("access_token")?.ifBlank { null }
    }

    private fun paypal(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val clientId = cfg(config, "clientId") ?: return ConnectorResult(false, "Missing PayPal client id")
        val secret = cfg(config, "clientSecret") ?: return ConnectorResult(false, "Missing PayPal client secret")
        val live = cfg(config, "environment")?.lowercase() == "live"
        val base = if (live) "https://api-m.paypal.com" else "https://api-m.sandbox.paypal.com"
        val token = paypalToken(base, clientId, secret)
            ?: return ConnectorResult(
                false,
                "PayPal refused the client id and secret. Check them, and check whether this app is sandbox or live.",
            )
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
        )
        return when (action) {
            "create_order" -> {
                val amount = args.optString("amount").ifBlank { return ConnectorResult(false, "Missing amount") }
                val currency = args.optString("currency", "USD").uppercase()
                val intent = args.optString("intent", "CAPTURE").uppercase()
                val payload = JSONObject()
                    .put("intent", intent)
                    .put(
                        "purchase_units",
                        JSONArray().put(
                            JSONObject().put(
                                "amount",
                                JSONObject().put("currency_code", currency).put("value", amount),
                            ),
                        ),
                    )
                val response = http("POST", "$base/v2/checkout/orders", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "PayPal error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "PayPal returned invalid JSON")
                val links = root.optJSONArray("links")
                val approve = (0 until (links?.length() ?: 0))
                    .mapNotNull { links?.optJSONObject(it) }
                    .firstOrNull { it.optString("rel") == "approve" }
                    ?.optString("href")
                    .orEmpty()
                val note = if (approve.isBlank()) "" else "\nApprove it here: $approve"
                ConnectorResult(true, "Created PayPal order ${root.optString("id")} for $amount $currency ($intent).$note")
            }
            "capture_order" -> {
                val orderId = args.optString("orderId").ifBlank { return ConnectorResult(false, "Missing orderId") }
                val response = http("POST", "$base/v2/checkout/orders/" + enc(orderId) + "/capture", headers, "{}")
                if (!response.ok) return ConnectorResult(false, "PayPal error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "PayPal returned invalid JSON")
                ConnectorResult(true, "Captured PayPal order $orderId, status ${root.optString("status")}.")
            }
            "get_order" -> {
                val orderId = args.optString("orderId").ifBlank { return ConnectorResult(false, "Missing orderId") }
                val response = http("GET", "$base/v2/checkout/orders/" + enc(orderId), headers)
                if (!response.ok) return ConnectorResult(false, "PayPal error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "PayPal returned invalid JSON")
                ConnectorResult(
                    true,
                    "Order ${root.optString("id")}: status ${root.optString("status")}, intent ${root.optString("intent")}, created ${root.optString("create_time")}.",
                )
            }
            "create_subscription" -> {
                val planId = args.optString("planId").ifBlank { return ConnectorResult(false, "Missing planId") }
                val payload = JSONObject().put("plan_id", planId)
                args.optString("subscriberEmail").ifBlank { null }?.let {
                    payload.put("subscriber", JSONObject().put("email_address", it))
                }
                val response = http("POST", "$base/v1/billing/subscriptions", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "PayPal error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "PayPal returned invalid JSON")
                ConnectorResult(true, "Created subscription ${root.optString("id")} on plan $planId, status ${root.optString("status")}.")
            }
            "list_transactions" -> {
                val start = args.optString("startDate").ifBlank { return ConnectorResult(false, "Missing startDate") }
                val end = args.optString("endDate").ifBlank { return ConnectorResult(false, "Missing endDate") }
                val url = "$base/v1/reporting/transactions?start_date=" + enc(start + "T00:00:00Z") +
                    "&end_date=" + enc(end + "T23:59:59Z") + "&fields=all"
                val response = http("GET", url, headers)
                if (!response.ok) return ConnectorResult(false, "PayPal error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "PayPal returned invalid JSON")
                val details = root.optJSONArray("transaction_details") ?: JSONArray()
                if (details.length() == 0) return ConnectorResult(true, "No transactions between $start and $end.")
                val sb = StringBuilder("PayPal transactions:\n")
                for (i in 0 until minOf(details.length(), 20)) {
                    val info = details.optJSONObject(i)?.optJSONObject("transaction_info") ?: continue
                    sb.append("- ")
                        .append(info.optString("transaction_event_code"))
                        .append(" ")
                        .append(info.optJSONObject("transaction_amount")?.optString("value").orEmpty())
                        .append(" ")
                        .append(info.optJSONObject("transaction_amount")?.optString("currency_code").orEmpty())
                        .append(" at ")
                        .append(info.optString("transaction_initiation_date"))
                        .append("\n")
                }
                ConnectorResult(true, sb.toString().trim())
            }
            else -> ConnectorResult(false, "Unknown PayPal action '$action'. Use create_order, capture_order, get_order, create_subscription or list_transactions.")
        }
    }

    // --------------------------------------------------------------------- polar

    private fun polarList(
        url: String,
        headers: Map<String, String>,
        label: String,
        format: (JSONObject) -> String,
    ): ConnectorResult {
        val response = http("GET", url, headers)
        if (!response.ok) return ConnectorResult(false, "Polar error: ${text(response.body, 400)}")
        // List endpoints answer with {"items": [...]}, never a bare array, so read the wrapper.
        val root = json(response.body) ?: return ConnectorResult(false, "Polar sent a non-JSON response (${response.code}): ${text(response.body, 200)}")
        val items = root.optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) return ConnectorResult(true, "Nothing found: no $label.")
        val sb = StringBuilder("Polar $label:\n")
        for (i in 0 until minOf(items.length(), 20)) {
            val item = items.optJSONObject(i) ?: continue
            sb.append("- ").append(format(item)).append("\n")
        }
        return ConnectorResult(true, sb.toString().trim())
    }

    private fun polar(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val token = cfg(config, "accessToken") ?: return ConnectorResult(false, "Missing Polar access token")
        val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        val base = "https://api.polar.sh/v1"
        val limit = args.optInt("limit", 10).coerceIn(1, 100)
        return when (action) {
            "list_products" -> polarList("$base/products?limit=$limit", headers, "products") {
                it.optString("name") + " (" + it.optString("id") + ")"
            }
            "list_orders" -> polarList("$base/orders?limit=$limit", headers, "orders") {
                val total = it.optInt("total_amount")
                val currency = it.optString("currency") // Polar returns lowercase; don't uppercase
                "order " + it.optString("id") + ", " + total + " " + currency + ", status " + it.optString("status")
            }
            "get_customer" -> {
                val email = args.optString("email").ifBlank { return ConnectorResult(false, "Missing email") }
                polarList("$base/customers/?limit=$limit&email=" + enc(email), headers, "customers") {
                    it.optString("email") + " (" + it.optString("id") + ")"
                }
            }
            "verify_license" -> {
                val key = args.optString("licenseKey").ifBlank { return ConnectorResult(false, "Missing licenseKey") }
                val response = http("POST", "$base/license-keys/validate", headers, JSONObject().put("key", key).toString())
                if (!response.ok) return ConnectorResult(false, "Polar error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Polar sent a non-JSON response (${response.code}): ${text(response.body, 200)}")
                val valid = root.optBoolean("valid", root.has("license_key"))
                ConnectorResult(true, if (valid) "License key $key is valid." else "License key $key was not accepted.")
            }
            "create_product" -> {
                val name = args.optString("name").ifBlank { return ConnectorResult(false, "Missing name") }
                val amount = args.optInt("priceAmount", 0)
                val currency = args.optString("priceCurrency", "usd").lowercase() // Polar expects lowercase (e.g., "usd")
                val payload = JSONObject().put("name", name)
                args.optString("description").ifBlank { null }?.let { payload.put("description", it) }
                payload.put(
                    "prices",
                    JSONArray().put(
                        JSONObject()
                            .put("amount_type", "fixed")
                            .put("price_amount", amount)
                            .put("price_currency", currency),
                    ),
                )
                val response = http("POST", "$base/products", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Polar error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Polar sent a non-JSON response (${response.code}): ${text(response.body, 200)}")
                ConnectorResult(true, "Created Polar product '" + root.optString("name") + "' (" + root.optString("id") + ").")
            }
            "create_checkout" -> {
                val productId = args.optString("productId").ifBlank { return ConnectorResult(false, "Missing productId") }
                val payload = JSONObject().put("products", JSONArray().put(productId))
                args.optString("successUrl").ifBlank { null }?.let { payload.put("success_url", it) }
                args.optString("customerEmail").ifBlank { null }?.let {
                    payload.put("customer_email", it)
                }
                val response = http("POST", "$base/checkouts", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Polar error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Polar sent a non-JSON response (${response.code}): ${text(response.body, 200)}")
                ConnectorResult(true, "Created checkout " + root.optString("id") + " for product $productId.\nURL: " + root.optString("url"))
            }
            else -> ConnectorResult(false, "Unknown Polar action '$action'. Use list_products, create_product, create_checkout, list_orders, get_customer or verify_license.")
        }
    }

    // ----------------------------------------------------------------- agentmail

    private fun agentMailList(
        url: String,
        headers: Map<String, String>,
        label: String,
        format: (JSONObject) -> String,
    ): ConnectorResult {
        val response = http("GET", url, headers)
        if (!response.ok) return ConnectorResult(false, "AgentMail error: ${text(response.body, 400)}")
        val root = json(response.body) ?: return ConnectorResult(false, "AgentMail sent a non-JSON response (${response.code}): ${text(response.body, 200)}")
        val items = root.optJSONArray("inboxes")
            ?: root.optJSONArray("messages")
            ?: root.optJSONArray("data")
            ?: JSONArray()
        if (items.length() == 0) return ConnectorResult(true, "Nothing found: no $label.")
        val sb = StringBuilder("AgentMail $label:\n")
        for (i in 0 until minOf(items.length(), 20)) {
            val item = items.optJSONObject(i) ?: continue
            sb.append("- ").append(format(item)).append("\n")
        }
        return ConnectorResult(true, sb.toString().trim())
    }

    private fun agentMail(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val apiKey = cfg(config, "apiKey") ?: return ConnectorResult(false, "Missing AgentMail API key")
        val headers = mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json")
        val base = "https://api.agentmail.to/v0"
        val inbox = args.optString("inboxId").ifBlank { cfg(config, "domain").orEmpty() }
        val limit = args.optInt("limit", 10).coerceIn(1, 100)
        return when (action) {
            "list_inboxes" -> agentMailList("$base/inboxes?limit=$limit", headers, "inboxes") {
                it.optString("inbox_id").ifBlank { it.optString("email") }
            }
            "create_inbox" -> {
                val username = args.optString("username").ifBlank { return ConnectorResult(false, "Missing username") }
                val payload = JSONObject().put("username", username)
                args.optString("domain").ifBlank { null }?.let { payload.put("domain", it) }
                val response = http("POST", "$base/inboxes", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "AgentMail error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "AgentMail sent a non-JSON response (${response.code}): ${text(response.body, 200)}")
                ConnectorResult(true, "Created inbox " + root.optString("inbox_id", root.optString("email")) + ".")
            }
            "send_email" -> {
                if (inbox.isBlank()) return ConnectorResult(false, "Missing inboxId")
                val to = args.optString("to").ifBlank { return ConnectorResult(false, "Missing to") }
                val subject = args.optString("subject").ifBlank { "(no subject)" }
                val text = args.optString("text")
                val html = args.optString("html")
                if (text.isBlank() && html.isBlank()) return ConnectorResult(false, "Missing text or html body")
                val payload = JSONObject().put("to", to).put("subject", subject)
                if (text.isNotBlank()) payload.put("text", text)
                if (html.isNotBlank()) payload.put("html", html)
                val response = http(
                    "POST",
                    "$base/inboxes/" + enc(inbox) + "/messages/send",
                    headers,
                    payload.toString(),
                )
                if (!response.ok) return ConnectorResult(false, "AgentMail error: ${text(response.body, 400)}")
                val root = json(response.body) ?: JSONObject()
                ConnectorResult(true, "Sent '$subject' to $to from $inbox (message " + root.optString("message_id", root.optString("id")) + ").")
            }
            "list_emails" -> {
                if (inbox.isBlank()) return ConnectorResult(false, "Missing inboxId")
                agentMailList("$base/inboxes/" + enc(inbox) + "/messages?limit=$limit", headers, "messages") {
                    it.optString("subject").ifBlank { "(no subject)" } + " from " + it.optString("from_") + " (" + it.optString("message_id", it.optString("id")) + ")"
                }
            }
            "get_email" -> {
                if (inbox.isBlank()) return ConnectorResult(false, "Missing inboxId")
                val emailId = args.optString("emailId").ifBlank { return ConnectorResult(false, "Missing emailId") }
                val response = http("GET", "$base/inboxes/" + enc(inbox) + "/messages/" + enc(emailId), headers)
                if (!response.ok) return ConnectorResult(false, "AgentMail error: ${text(response.body, 400)}")
                ConnectorResult(true, text(response.body, 4000))
            }
            "delete_inbox" -> {
                if (inbox.isBlank()) return ConnectorResult(false, "Missing inboxId")
                val response = http("DELETE", "$base/inboxes/" + enc(inbox), headers)
                if (!response.ok) return ConnectorResult(false, "AgentMail error: ${text(response.body, 400)}")
                ConnectorResult(true, "Deleted inbox $inbox.")
            }
            else -> ConnectorResult(false, "Unknown AgentMail action '$action'. Use create_inbox, list_inboxes, send_email, list_emails, get_email or delete_inbox.")
        }
    }

    // --------------------------------------------------------- cloudflare workers

    private fun cloudflareWorkers(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val apiToken = cfg(config, "apiToken") ?: return ConnectorResult(false, "Missing Cloudflare API token")
        val accountId = cfg(config, "accountId") ?: return ConnectorResult(false, "Missing Cloudflare account ID")
        val headers = mapOf(
            "Authorization" to "Bearer $apiToken",
            "Content-Type" to "application/json",
        )
        // The Workers upload endpoint (PUT /accounts/:account_id/workers/scripts/:script_name)
        // only accepts application/javascript or text/javascript. The connector config has a
        // contentType field defaulting to application/javascript, but to be safe we force the
        // correct type for all script upload actions regardless of config.
        val scriptType = cfg(config, "contentType")?.trim()?.ifBlank { null } ?: "application/javascript"
        val scriptHeaders = headers + ("Content-Type" to scriptType)
        val baseUrl = "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts"
        return when (action) {
            "deploy" -> {
                val name = args.optString("name").ifBlank { return ConnectorResult(false, "Missing worker name") }
                val code = args.optString("code").ifBlank { return ConnectorResult(false, "Missing worker code") }
                val url = "$baseUrl/$name"
                val response = http("PUT", url, scriptHeaders, code)
                if (!response.ok) return ConnectorResult(false, "Cloudflare error: ${cfErrors(response.body)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Cloudflare returned invalid JSON")
                if (!root.optBoolean("success")) {
                    return ConnectorResult(false, "Cloudflare deploy failed: ${cfErrors(root)}")
                }
                val result = root.optJSONObject("result")
                val deployedUrl = result?.optString("url") ?: "https://$name.$accountId.workers.dev"
                ConnectorResult(
                    true,
                    "Deployed worker '$name' successfully.\nURL: $deployedUrl",
                )
            }
            "get_script" -> {
                val name = args.optString("name").ifBlank { return ConnectorResult(false, "Missing worker name") }
                val url = "$baseUrl/$name"
                val response = http("GET", url, headers)
                if (!response.ok) return ConnectorResult(false, "Cloudflare error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Cloudflare returned invalid JSON")
                if (!root.optBoolean("success")) {
                    val errors = root.optJSONArray("errors")?.let { array -> (0 until array.length()).map { array.getString(it) }.joinToString(", ") } ?: "Unknown error"
                    return ConnectorResult(false, "Worker not found or error: $errors")
                }
                ConnectorResult(true, root.optString("result"))
            }
            "delete" -> {
                val name = args.optString("name").ifBlank { return ConnectorResult(false, "Missing worker name") }
                val url = "$baseUrl/$name"
                val response = http("DELETE", url, headers)
                if (!response.ok) return ConnectorResult(false, "Cloudflare error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Cloudflare returned invalid JSON")
                if (!root.optBoolean("success")) {
                    val errors = root.optJSONArray("errors")?.let { array -> (0 until array.length()).map { array.getString(it) }.joinToString(", ") } ?: "Unknown error"
                    return ConnectorResult(false, "Delete failed: $errors")
                }
                ConnectorResult(true, "Deleted worker '$name'.")
            }
            "list" -> {
                val url = baseUrl
                val response = http("GET", url, headers)
                if (!response.ok) return ConnectorResult(false, "Cloudflare error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Cloudflare returned invalid JSON")
                if (!root.optBoolean("success")) {
                    val errors = root.optJSONArray("errors")?.let { array -> (0 until array.length()).map { array.getString(it) }.joinToString(", ") } ?: "Unknown error"
                    return ConnectorResult(false, "List failed: $errors")
                }
                // The list endpoint answers with an array of scripts, not an object, so the old
                // object-only read reported "No workers found" for every account.
                val sb = StringBuilder()
                when (val result = root.opt("result")) {
                    is JSONArray -> {
                        for (i in 0 until result.length()) {
                            val script = result.optJSONObject(i) ?: continue
                            sb.append("- ").append(script.optString("id").ifBlank { script.optString("name") })
                            sb.append(" (modified ").append(script.optString("modified_on")).append(")\n")
                        }
                    }
                    is JSONObject -> result.keys().forEachRemaining { name -> sb.append("- $name\n") }
                    else -> Unit
                }
                if (sb.isEmpty()) ConnectorResult(true, "No workers found.")
                else ConnectorResult(true, "Cloudflare Workers:\n" + sb.toString().trim())
            }
            "tail_logs" -> {
                val name = args.optString("name").ifBlank { return ConnectorResult(false, "Missing worker name") }
                // Note: Log streaming requires WebSocket. This just returns the tail URL.
                val tailUrl = "wss://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$name/tail"
                ConnectorResult(
                    true,
                    "Log streaming URL (WebSocket): $tailUrl\nConnect with a WebSocket client to stream live logs.",
                )
            }
            "deploy_webhook_handler" -> {
                val name = args.optString("name").ifBlank { return ConnectorResult(false, "Missing worker name") }
                val webhookSecret = args.optString("stripeWebhookSecret").ifBlank { return ConnectorResult(false, "Missing stripeWebhookSecret") }
                val callbackUrl = args.optString("appCallbackUrl").ifBlank { return ConnectorResult(false, "Missing appCallbackUrl") }
                val code = stripeWebhookWorkerCode(name, webhookSecret, callbackUrl)
                val url = "$baseUrl/$name"
                val response = http("PUT", url, scriptHeaders, code)
                if (!response.ok) return ConnectorResult(false, "Cloudflare error: ${cfErrors(response.body)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Cloudflare returned invalid JSON")
                if (!root.optBoolean("success")) {
                    return ConnectorResult(false, "Cloudflare deploy failed: ${cfErrors(root)}")
                }
                val result = root.optJSONObject("result")
                val deployedUrl = result?.optString("url") ?: "https://$name.$accountId.workers.dev"
                ConnectorResult(
                    true,
                    "Deployed Stripe webhook handler '$name' successfully.\nURL: $deployedUrl\n\nConfigure this URL in Stripe Dashboard → Developers → Webhooks → Add endpoint.\nEvents to select: invoice.payment_succeeded, checkout.session.completed, customer.subscription.created, customer.subscription.updated",
                )
            }
            else -> ConnectorResult(false, "Unknown Cloudflare Workers action '$action'")
        }
    }

    // ------------------------------------------------------------------ supabase

    private fun supabase(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val base = cfg(config, "projectUrl")?.trimEnd('/')
            ?: return ConnectorResult(false, "Missing project URL")
        val key = cfg(config, "serviceKey") ?: return ConnectorResult(false, "Missing service key")
        val headers = mapOf(
            "apikey" to key,
            "Authorization" to "Bearer $key",
            "Content-Type" to "application/json",
        )
        val limit = args.optInt("limit", 20).coerceIn(1, 100)
        return when (action) {
            "list_rows" -> {
                val table = args.optString("table").ifBlank { return ConnectorResult(false, "Missing table") }
                val response = http("GET", "$base/rest/v1/" + enc(table) + "?select=*&limit=$limit", headers)
                if (!response.ok) return ConnectorResult(false, "Supabase error (${response.code}): ${text(response.body, 300)}")
                ConnectorResult(true, text(response.body, 6000))
            }
            "insert" -> {
                val table = args.optString("table").ifBlank { return ConnectorResult(false, "Missing table") }
                val row = args.optJSONObject("row") ?: return ConnectorResult(false, "Missing row")
                val response = http(
                    "POST",
                    "$base/rest/v1/" + enc(table),
                    headers + ("Prefer" to "return=representation"),
                    row.toString(),
                )
                if (!response.ok) return ConnectorResult(false, "Supabase error (${response.code}): ${text(response.body, 300)}")
                ConnectorResult(true, "Inserted into " + table + ": " + text(response.body, 2000))
            }
            "rpc" -> {
                val fn = args.optString("fn").ifBlank { return ConnectorResult(false, "Missing fn") }
                val body = args.optJSONObject("args") ?: JSONObject()
                val response = http("POST", "$base/rest/v1/rpc/" + enc(fn), headers, body.toString())
                if (!response.ok) return ConnectorResult(false, "Supabase error (${response.code}): ${text(response.body, 300)}")
                ConnectorResult(true, text(response.body, 4000))
            }
            else -> ConnectorResult(false, "Unknown Supabase action '$action'. Use list_actions to see them.")
        }
    }

    // -------------------------------------------------------------------- resend

    private fun resend(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val key = cfg(config, "apiKey") ?: return ConnectorResult(false, "Missing Resend API key")
        val from = cfg(config, "from") ?: return ConnectorResult(false, "Missing from address")
        val headers = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json")
        val limit = args.optInt("limit", 10).coerceIn(1, 100)
        return when (action) {
            "send_email" -> {
                val to = args.optString("to").ifBlank { return ConnectorResult(false, "Missing to") }
                val subject = args.optString("subject").ifBlank { "(no subject)" }
                val textBody = args.optString("text")
                val html = args.optString("html")
                if (textBody.isBlank() && html.isBlank()) return ConnectorResult(false, "Missing text or html body")
                val payload = JSONObject().put("from", from).put("to", to).put("subject", subject)
                if (textBody.isNotBlank()) payload.put("text", textBody)
                if (html.isNotBlank()) payload.put("html", html)
                val response = http("POST", "https://api.resend.com/emails", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Resend error (${response.code}): ${text(response.body, 300)}")
                val root = json(response.body) ?: JSONObject()
                ConnectorResult(true, "Sent '$subject' to $to (id " + root.optString("id") + ").")
            }
            "list_emails" -> {
                val response = http("GET", "https://api.resend.com/emails?limit=$limit", headers)
                if (!response.ok) return ConnectorResult(false, "Resend error (${response.code}): ${text(response.body, 300)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Resend sent an unexpected body: ${text(response.body, 200)}")
                val items = root.optJSONArray("data") ?: JSONArray()
                if (items.length() == 0) return ConnectorResult(true, "No emails yet.")
                val sb = StringBuilder("Recent emails:\n")
                for (i in 0 until minOf(items.length(), 20)) {
                    val item = items.optJSONObject(i) ?: continue
                    sb.append("- ").append(item.optString("subject")).append(" to ")
                        .append(item.optJSONArray("to")?.optString(0).orEmpty())
                        .append(" [").append(item.optString("last_event")).append("]\n")
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "get_email" -> {
                val id = args.optString("emailId").ifBlank { return ConnectorResult(false, "Missing emailId") }
                val response = http("GET", "https://api.resend.com/emails/" + enc(id), headers)
                if (!response.ok) return ConnectorResult(false, "Resend error (${response.code}): ${text(response.body, 300)}")
                ConnectorResult(true, text(response.body, 4000))
            }
            else -> ConnectorResult(false, "Unknown Resend action '$action'. Use list_actions to see them.")
        }
    }

    // ------------------------------------------------------------------- upstash

    private fun upstash(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val base = cfg(config, "redisUrl")?.trimEnd('/') ?: return ConnectorResult(false, "Missing Upstash REST URL")
        val token = cfg(config, "redisToken") ?: return ConnectorResult(false, "Missing Upstash REST token")
        val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        fun resultOf(response: HttpResult, note: String): ConnectorResult {
            if (!response.ok) return ConnectorResult(false, "Upstash error (${response.code}): ${text(response.body, 300)}")
            val root = json(response.body)
            val value = root?.opt("result")?.toString() ?: response.body
            return ConnectorResult(true, note + ": " + value.take(4000))
        }
        return when (action) {
            "command" -> {
                val command = args.optJSONArray("command")
                    ?: return ConnectorResult(false, "Missing command array")
                resultOf(http("POST", base, headers, JSONArray().put(command).toString()), "Result")
            }
            "get" -> {
                val key = args.optString("key").ifBlank { return ConnectorResult(false, "Missing key") }
                resultOf(http("GET", "$base/get/" + enc(key), headers), key)
            }
            "set" -> {
                val key = args.optString("key").ifBlank { return ConnectorResult(false, "Missing key") }
                val value = args.optString("value")
                val ttl = args.optInt("ttlSeconds", 0)
                val path = if (ttl > 0) "$base/set/" + enc(key) + "?EX=$ttl" else "$base/set/" + enc(key)
                resultOf(http("POST", path, headers, JSONArray().put(value).toString()), "Set $key")
            }
            "delete" -> {
                val key = args.optString("key").ifBlank { return ConnectorResult(false, "Missing key") }
                resultOf(http("POST", "$base/del/" + enc(key), headers), "Deleted $key")
            }
            else -> ConnectorResult(false, "Unknown Upstash action '$action'. Use list_actions to see them.")
        }
    }

    // --------------------------------------------------------------- huggingface

    private fun huggingFace(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val token = cfg(config, "hfToken") ?: return ConnectorResult(false, "Missing Hugging Face token")
        val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        return when (action) {
            "chat" -> {
                val model = args.optString("model").ifBlank { "meta-llama/Llama-3.1-8B-Instruct" }
                val prompt = args.optString("prompt").ifBlank { return ConnectorResult(false, "Missing prompt") }
                val payload = JSONObject()
                    .put("model", model)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", prompt)),
                    )
                    .put("max_tokens", args.optInt("maxTokens", 512))
                val response = http("POST", "https://router.huggingface.co/v1/chat/completions", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Hugging Face error (${response.code}): ${text(response.body, 300)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Unexpected body: ${text(response.body, 200)}")
                val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ConnectorResult(true, message?.optString("content").orEmpty().ifBlank { text(response.body, 2000) })
            }
            "infer" -> {
                val model = args.optString("model").ifBlank { return ConnectorResult(false, "Missing model") }
                val inputs = args.optString("inputs").ifBlank { return ConnectorResult(false, "Missing inputs") }
                val payload = JSONObject().put("inputs", inputs)
                args.optJSONObject("parameters")?.let { payload.put("parameters", it) }
                val response = http("POST", "https://api-inference.huggingface.co/models/" + enc(model), headers, payload.toString())
                if (response.code == 503) {
                    return ConnectorResult(false, "Model $model is warming up. Try again in about 20 seconds.")
                }
                if (!response.ok) return ConnectorResult(false, "Hugging Face error (${response.code}): ${text(response.body, 300)}")
                ConnectorResult(true, text(response.body, 4000))
            }
            "models" -> {
                val query = args.optString("query")
                val url = "https://huggingface.co/api/models?limit=10" + if (query.isBlank()) "" else "&search=" + enc(query)
                val response = http("GET", url, headers)
                if (!response.ok) return ConnectorResult(false, "Hugging Face error (${response.code}): ${text(response.body, 300)}")
                val root = Json.tryArray(response.body) ?: return ConnectorResult(false, "Unexpected body: ${text(response.body, 200)}")
                val sb = StringBuilder("Models:\n")
                for (i in 0 until minOf(10, root.length())) {
                    val item = root.optJSONObject(i) ?: continue
                    sb.append("- ").append(item.optString("id")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            else -> ConnectorResult(false, "Unknown Hugging Face action '$action'. Use list_actions to see them.")
        }
    }

    // -------------------------------------------------------------------- vercel

    private fun vercel(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val token = cfg(config, "vercelToken") ?: return ConnectorResult(false, "Missing Vercel token")
        val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        val limit = args.optInt("limit", 10).coerceIn(1, 100)
        return when (action) {
            "list_projects" -> {
                val response = http("GET", "https://api.vercel.com/v9/projects?limit=$limit", headers)
                if (!response.ok) return ConnectorResult(false, "Vercel error (${response.code}): ${text(response.body, 300)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Unexpected body: ${text(response.body, 200)}")
                val items = root.optJSONArray("projects") ?: JSONArray()
                if (items.length() == 0) return ConnectorResult(true, "No Vercel projects.")
                val sb = StringBuilder("Vercel projects:\n")
                for (i in 0 until minOf(items.length(), 20)) {
                    val item = items.optJSONObject(i) ?: continue
                    sb.append("- ").append(item.optString("name")).append(" (").append(item.optString("id")).append(")\n")
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "list_deployments" -> {
                val project = args.optString("projectId")
                val url = "https://api.vercel.com/v6/deployments?limit=$limit" +
                    if (project.isBlank()) "" else "&projectId=" + enc(project)
                val response = http("GET", url, headers)
                if (!response.ok) return ConnectorResult(false, "Vercel error (${response.code}): ${text(response.body, 300)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Unexpected body: ${text(response.body, 200)}")
                val items = root.optJSONArray("deployments") ?: JSONArray()
                if (items.length() == 0) return ConnectorResult(true, "No deployments.")
                val sb = StringBuilder("Deployments:\n")
                for (i in 0 until minOf(items.length(), 20)) {
                    val item = items.optJSONObject(i) ?: continue
                    sb.append("- ").append(item.optString("name")).append(" ").append(item.optString("url"))
                        .append(" [").append(item.optString("state")).append("]\n")
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "get_deployment" -> {
                val id = args.optString("deploymentId").ifBlank { return ConnectorResult(false, "Missing deploymentId") }
                val response = http("GET", "https://api.vercel.com/v13/deployments/" + enc(id), headers)
                if (!response.ok) return ConnectorResult(false, "Vercel error (${response.code}): ${text(response.body, 300)}")
                ConnectorResult(true, text(response.body, 4000))
            }
            else -> ConnectorResult(false, "Unknown Vercel action '$action'. Use list_actions to see them.")
        }
    }

    // ------------------------------------------------------------------- netlify

    private fun netlify(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val token = cfg(config, "netlifyToken") ?: return ConnectorResult(false, "Missing Netlify token")
        val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        val base = "https://api.netlify.com/api/v1"
        return when (action) {
            "list_sites" -> {
                val response = http("GET", "$base/sites", headers)
                if (!response.ok) return ConnectorResult(false, "Netlify error (${response.code}): ${text(response.body, 300)}")
                val root = Json.tryArray(response.body) ?: return ConnectorResult(false, "Unexpected body: ${text(response.body, 200)}")
                if (root.length() == 0) return ConnectorResult(true, "No Netlify sites.")
                val sb = StringBuilder("Netlify sites:\n")
                for (i in 0 until minOf(root.length(), 20)) {
                    val item = root.optJSONObject(i) ?: continue
                    sb.append("- ").append(item.optString("name")).append(" (").append(item.optString("id"))
                        .append(") ").append(item.optString("url")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "list_deploys" -> {
                val siteId = args.optString("siteId").ifBlank { return ConnectorResult(false, "Missing siteId") }
                val response = http("GET", "$base/sites/" + enc(siteId) + "/deploys", headers)
                if (!response.ok) return ConnectorResult(false, "Netlify error (${response.code}): ${text(response.body, 300)}")
                val root = Json.tryArray(response.body) ?: return ConnectorResult(false, "Unexpected body: ${text(response.body, 200)}")
                val sb = StringBuilder("Deploys for $siteId:\n")
                for (i in 0 until minOf(root.length(), 10)) {
                    val item = root.optJSONObject(i) ?: continue
                    sb.append("- ").append(item.optString("id")).append(" [").append(item.optString("state"))
                        .append("] ").append(item.optString("deploy_ssl_url")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim().ifBlank { "No deploys." })
            }
            "get_site" -> {
                val siteId = args.optString("siteId").ifBlank { return ConnectorResult(false, "Missing siteId") }
                val response = http("GET", "$base/sites/" + enc(siteId), headers)
                if (!response.ok) return ConnectorResult(false, "Netlify error (${response.code}): ${text(response.body, 300)}")
                ConnectorResult(true, text(response.body, 4000))
            }
            else -> ConnectorResult(false, "Unknown Netlify action '$action'. Use list_actions to see them.")
        }
    }

    // -------------------------------------------------------------------- linear

    private fun linear(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val key = cfg(config, "linearKey") ?: return ConnectorResult(false, "Missing Linear API key")
        val headers = mapOf("Authorization" to key, "Content-Type" to "application/json")
        fun graphql(query: String, variables: JSONObject = JSONObject()): JSONObject? {
            val payload = JSONObject().put("query", query).put("variables", variables)
            val response = http("POST", "https://api.linear.app/graphql", headers, payload.toString())
            if (!response.ok) return null
            return json(response.body)?.optJSONObject("data")
        }
        return when (action) {
            "list_issues" -> {
                val limit = args.optInt("limit", 10).coerceIn(1, 50)
                val data = graphql(
                    "query(\$first: Int!) { issues(first: \$first, orderBy: updatedAt) { nodes { identifier title state { name } url } } }",
                    JSONObject().put("first", limit),
                ) ?: return ConnectorResult(false, "Linear did not answer; check the API key.")
                val nodes = data.optJSONObject("issues")?.optJSONArray("nodes") ?: JSONArray()
                if (nodes.length() == 0) return ConnectorResult(true, "No Linear issues.")
                val sb = StringBuilder("Linear issues:\n")
                for (i in 0 until nodes.length()) {
                    val item = nodes.optJSONObject(i) ?: continue
                    sb.append("- ").append(item.optString("identifier")).append(" ").append(item.optString("title"))
                        .append(" [").append(item.optJSONObject("state")?.optString("name")).append("] ")
                        .append(item.optString("url")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "create_issue" -> {
                val title = args.optString("title").ifBlank { return ConnectorResult(false, "Missing title") }
                val teamId = args.optString("teamId").ifBlank { return ConnectorResult(false, "Missing teamId") }
                val data = graphql(
                    "mutation(\$title: String!, \$teamId: String!, \$description: String) { issueCreate(input: { title: \$title, teamId: \$teamId, description: \$description }) { success issue { identifier url } } }",
                    JSONObject().put("title", title).put("teamId", teamId).put("description", args.optString("description")),
                ) ?: return ConnectorResult(false, "Linear did not answer; check the API key and team id.")
                val issue = data.optJSONObject("issueCreate")?.optJSONObject("issue")
                ConnectorResult(true, "Created " + (issue?.optString("identifier") ?: "issue") + " " + (issue?.optString("url") ?: ""))
            }
            "list_teams" -> {
                val data = graphql("query { teams { nodes { id key name } } }")
                    ?: return ConnectorResult(false, "Linear did not answer; check the API key.")
                val nodes = data.optJSONObject("teams")?.optJSONArray("nodes") ?: JSONArray()
                val sb = StringBuilder("Linear teams:\n")
                for (i in 0 until nodes.length()) {
                    val item = nodes.optJSONObject(i) ?: continue
                    sb.append("- ").append(item.optString("key")).append(" ").append(item.optString("name"))
                        .append(" (").append(item.optString("id")).append(")\n")
                }
                ConnectorResult(true, sb.toString().trim().ifBlank { "No teams." })
            }
            else -> ConnectorResult(false, "Unknown Linear action '$action'. Use list_actions to see them.")
        }
    }

    // -------------------------------------------------------------------- terminal

    private fun terminal(config: JSONObject?, action: String, args: JSONObject, agentId: String?): ConnectorResult {
        return when (action) {
            "run" -> {
                val command = args.optString("command").ifBlank { return ConnectorResult(false, "Missing command") }
                val cwd = args.optString("cwd", "/workspace/app")
                val result = TerminalManager.runSync(command, cwd)
                if (result.ok) {
                    ConnectorResult(true, "Exit code: ${result.exitCode}\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}")
                } else {
                    ConnectorResult(false, "Exit code: ${result.exitCode}\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}")
                }
            }
            "run_background" -> {
                val command = args.optString("command").ifBlank { return ConnectorResult(false, "Missing command") }
                val cwd = args.optString("cwd", "/workspace/app")
                val pid = TerminalManager.startBackground(command, cwd)
                ConnectorResult(true, "Started background process with PID: $pid")
            }
            "list" -> {
                val processes = TerminalManager.listProcesses()
                if (processes.isEmpty()) {
                    ConnectorResult(true, "No background processes running.")
                } else {
                    val sb = StringBuilder("Background processes:\n")
                    processes.forEach { p ->
                        sb.append("PID: ${p.pid}, Command: ${p.command}, Status: ${p.status}, Started: ${p.startTime}\n")
                    }
                    ConnectorResult(true, sb.toString().trim())
                }
            }
            "stop" -> {
                val pid = args.optString("pid").ifBlank { return ConnectorResult(false, "Missing pid") }
                val ok = TerminalManager.stopProcess(pid)
                if (ok) {
                    ConnectorResult(true, "Process $pid stopped.")
                } else {
                    ConnectorResult(false, "Process $pid not found or already stopped.")
                }
            }
            "logs" -> {
                val pid = args.optString("pid").ifBlank { return ConnectorResult(false, "Missing pid") }
                val logs = TerminalManager.getLogs(pid)
                if (logs == null) {
                    ConnectorResult(false, "Process $pid not found.")
                } else {
                    ConnectorResult(true, "stdout:\n${logs.stdout}\nstderr:\n${logs.stderr}")
                }
            }
            else -> ConnectorResult(false, "Unknown Terminal action '$action'. Use list_actions to see them.")
        }
    }

    private fun cfErrors(body: String?): String {
        val root = json(body ?: "") ?: return text(body, 400).ifBlank { "no response from Cloudflare" }
        return cfErrors(root)
    }

    private fun cfErrors(root: JSONObject): String {
        val array = root.optJSONArray("errors")
        if (array == null || array.length() == 0) {
            return root.optString("message").ifBlank { "Unknown error" }
        }
        val messages = (0 until array.length()).mapNotNull { index ->
            val item = array.opt(index)
            val message = when (item) {
                null -> ""
                is JSONObject -> item.optString("message").ifBlank { item.optString("code") }
                else -> item.toString()
            }
            message.ifBlank { null }
        }
        return messages.joinToString(", ").ifBlank { "Unknown error" }
    }

    private fun stripeWebhookWorkerCode(name: String, webhookSecret: String, callbackUrl: String): String {
        val dollar = "\$"
        return """
            // Stripe Webhook Handler for $name
            // Receives Stripe events, verifies signature, forwards to app
            
            const WEBHOOK_SECRET = "$webhookSecret";
            const CALLBACK_URL = "$callbackUrl";
            
            // Minimal crypto for signature verification (Web Crypto API)
            async function verifySignature(payload, signature, secret) {
                const encoder = new TextEncoder();
                const key = await crypto.subtle.importKey(
                    "raw",
                    encoder.encode(secret),
                    { name: "HMAC", hash: "SHA-256" },
                    false,
                    ["sign", "verify"]
                );
                
                // Parse Stripe signature header: t=timestamp,v1=signature
                const elements = signature.split(",").map(s => s.split("="));
                const timestamp = elements.find(([k]) => k === "t")?.[1];
                const v1 = elements.find(([k]) => k === "v1")?.[1];
                
                if (!timestamp || !v1) return false;
                
                // Check timestamp tolerance (5 minutes)
                const now = Math.floor(Date.now() / 1000);
                if (Math.abs(now - parseInt(timestamp)) > 300) return false;
                
                const signedPayload = encoder.encode("${dollar}{timestamp}.${dollar}{payload}");
                const expectedSig = await crypto.subtle.sign("HMAC", key, signedPayload);
                const expectedHex = Array.from(new Uint8Array(expectedSig))
                    .map(b => b.toString(16).padStart(2, "0"))
                    .join("");
                
                return expectedHex === v1;
            }
            
            async function forwardToApp(event) {
                try {
                    await fetch(CALLBACK_URL, {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify(event),
                    });
                } catch (e) {
                    console.error("Failed to forward to app:", e);
                }
            }
            
            export default {
                async fetch(req) {
                    if (req.method !== "POST") {
                        return new Response("Method not allowed", { status: 405 });
                    }
                    
                    const signature = req.headers.get("stripe-signature");
                    if (!signature) {
                        return new Response("Missing stripe-signature header", { status: 400 });
                    }
                    
                    const payload = await req.text();
                    
                    const valid = await verifySignature(payload, signature, WEBHOOK_SECRET);
                    if (!valid) {
                        return new Response("Invalid signature", { status: 400 });
                    }
                    
                    let event;
                    try {
                        event = JSON.parse(payload);
                    } catch {
                        return new Response("Invalid JSON", { status: 400 });
                    }
                    
                    // Forward relevant events to app
                    const relevantTypes = [
                        "invoice.payment_succeeded",
                        "checkout.session.completed",
                        "customer.subscription.created",
                        "customer.subscription.updated",
                        "customer.subscription.deleted",
                    ];
                    
                    if (relevantTypes.includes(event.type)) {
                        await forwardToApp(event);
                    }
                    
                    return new Response("OK", { status: 200 });
                },
            };
        """.trimIndent()
    }
}