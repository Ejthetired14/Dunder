package com.agoria.app.core

import android.os.BatteryManager
import android.os.Build
import android.util.Base64
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
        if (def.actions.none { it.id == action }) {
            return ConnectorResult(false, "Connector '$connectorId' has no action '$action'.", "unknown action")
        }

        val config = repo.connectorConfig(connectorId)
        missingConfig(def, config)?.let { missing ->
            return finish(connectorId, agentId, action, false, "Missing configuration: $missing")
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                dispatch(connectorId, connector, config, action, args, agentId)
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
    ): ConnectorResult {
        val short = summary.take(1200)
        repo.recordConnectorUse(connectorId, if (ok) null else short)
        repo.addConnectorLog(connectorId, agentId, action, if (ok) "ok" else "error", short)
        return ConnectorResult(ok, short, if (ok) null else short)
    }

    private fun dispatch(
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
        "http" -> http(config, action, args)
        "gmail" -> gmail(config, action, args)
        "calendar" -> calendar(config, action, args)
        "github" -> github(config, action, args)
        "slack" -> slack(config, action, args)
        "discord" -> discord(config, action, args)
        "notion" -> notion(config, action, args)
        "telegram" -> telegram(config, action, args)
        "stripe" -> stripe(config, action, args)
        "cloudflare_workers" -> cloudflareWorkers(config, action, args)
        "agentmail" -> agentmail(config, action, args)
        "polar" -> polar(config, action, args)
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
        config?.optString(key)?.trim()?.ifBlank { null }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun text(value: String?, max: Int = 700): String =
        (value ?: "")
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(max)

    private fun json(body: String): JSONObject? = Json.tryObject(body)

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
            sb.append("No instant answer. Try a more specific query or use Wikipedia.")
        }
        return ConnectorResult(true, sb.toString().trim())
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
            "info" -> {
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
        val root = Files.agentWorkspace(context, agentId ?: "shared").canonicalFile
        val relative = args.optString("path", "")
        val target = if (relative.isBlank()) root else File(root, relative).canonicalFile
        if (target.canonicalPath != root.canonicalPath && !target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            return ConnectorResult(false, "Path escapes the agent workspace.")
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
                if (!target.exists() || target.isDirectory) return ConnectorResult(false, "File not found: $relative")
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

    // ---------------------------------------------------------------------- http

    private fun http(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val baseUrl = cfg(config, "baseUrl") ?: return ConnectorResult(false, "Missing base URL")
        val apiKey = cfg(config, "apiKey")
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

    // --------------------------------------------------------------------- gmail

    private fun gmail(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val token = cfg(config, "accessToken") ?: return ConnectorResult(false, "Missing Gmail access token")
        val user = cfg(config, "userEmail") ?: "me"
        val auth = mapOf("Authorization" to "Bearer $token")
        return when (action) {
            "list" -> {
                val query = args.optString("query", "")
                val max = args.optInt("maxResults", 5).coerceIn(1, 25)
                val url = "https://gmail.googleapis.com/gmail/v1/users/$user/messages?maxResults=$max" +
                    if (query.isBlank()) "" else "&q=" + enc(query)
                val response = http("GET", url, auth)
                if (!response.ok) return ConnectorResult(false, "Gmail error: ${text(response.body, 300)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Gmail returned invalid JSON")
                val messages = root.optJSONArray("messages") ?: return ConnectorResult(true, "No messages found.")
                val sb = StringBuilder("Gmail messages:\n")
                for (i in 0 until messages.length()) {
                    val item = messages.getJSONObject(i)
                    val id = item.optString("id")
                    val detail = http("GET", "https://gmail.googleapis.com/gmail/v1/users/$user/messages/$id?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date", auth)
                    if (!detail.ok) continue
                    val detailRoot = json(detail.body) ?: continue
                    val payload = detailRoot.optJSONObject("payload")
                    val headers = payload?.optJSONArray("headers")
                    val subject = headerValue(headers, "Subject")
                    val from = headerValue(headers, "From")
                    val date = headerValue(headers, "Date")
                    sb.append("- ").append(subject.ifBlank { "(no subject)" })
                        .append(" — ").append(from).append(" (").append(date).append(")\n  id: ").append(id).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "get" -> {
                val id = args.optString("messageId").ifBlank { return ConnectorResult(false, "Missing messageId") }
                val url = "https://gmail.googleapis.com/gmail/v1/users/$user/messages/$id?format=full"
                val response = http("GET", url, auth)
                if (!response.ok) return ConnectorResult(false, "Gmail error: ${text(response.body, 300)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Gmail returned invalid JSON")
                val payload = root.optJSONObject("payload")
                val headers = payload?.optJSONArray("headers")
                val subject = headerValue(headers, "Subject")
                val from = headerValue(headers, "From")
                val body = gmailBody(payload)
                ConnectorResult(
                    true,
                    "Subject: $subject\nFrom: $from\n\n" + text(body, 2000),
                )
            }
            "send" -> {
                val to = args.optString("to").ifBlank { return ConnectorResult(false, "Missing recipient") }
                val subject = args.optString("subject").ifBlank { return ConnectorResult(false, "Missing subject") }
                val body = args.optString("body").ifBlank { return ConnectorResult(false, "Missing body") }
                val mime = "To: $to\r\n" +
                    "Subject: $subject\r\n" +
                    "Content-Type: text/plain; charset=\"UTF-8\"\r\n" +
                    "MIME-Version: 1.0\r\n\r\n" +
                    body
                val raw = Base64.encodeToString(mime.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
                val payload = JSONObject().put("raw", raw)
                val response = http(
                    "POST",
                    "https://gmail.googleapis.com/gmail/v1/users/$user/messages/send",
                    auth,
                    payload.toString(),
                )
                if (!response.ok) return ConnectorResult(false, "Gmail send failed: ${text(response.body, 400)}")
                ConnectorResult(true, "Sent email to $to with subject '$subject'.")
            }
            else -> ConnectorResult(false, "Unknown Gmail action '$action'")
        }
    }

    private fun headerValue(headers: JSONArray?, name: String): String {
        if (headers == null) return ""
        for (i in 0 until headers.length()) {
            val header = headers.optJSONObject(i) ?: continue
            if (header.optString("name").equals(name, ignoreCase = true)) return header.optString("value")
        }
        return ""
    }

    private fun gmailBody(payload: JSONObject?): String {
        if (payload == null) return ""
        val body = payload.optJSONObject("body")
        val data = body?.optString("data")
        if (!data.isNullOrBlank()) {
            return try {
                String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
        val parts = payload.optJSONArray("parts")
        if (parts != null) {
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(gmailBody(parts.optJSONObject(i)))
            }
            return sb.toString()
        }
        return ""
    }

    // ------------------------------------------------------------------ calendar

    private fun calendar(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val token = cfg(config, "accessToken") ?: return ConnectorResult(false, "Missing calendar access token")
        val calendarId = cfg(config, "calendarId") ?: "primary"
        val auth = mapOf("Authorization" to "Bearer $token")
        return when (action) {
            "list" -> {
                val max = args.optInt("maxResults", 5).coerceIn(1, 25)
                val timeMin = args.optString("timeMin", Time.iso(Time.now()))
                val url = "https://www.googleapis.com/calendar/v3/calendars/" + enc(calendarId) +
                    "/events?timeMin=" + enc(timeMin) + "&maxResults=$max&singleEvents=true&orderBy=startTime"
                val response = http("GET", url, auth)
                if (!response.ok) return ConnectorResult(false, "Calendar error: ${text(response.body, 300)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Calendar returned invalid JSON")
                val items = root.optJSONArray("items") ?: return ConnectorResult(true, "No events found.")
                val sb = StringBuilder("Upcoming events:\n")
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val start = item.optJSONObject("start")?.optString("dateTime")
                        ?: item.optJSONObject("start")?.optString("date") ?: "?"
                    sb.append("- ").append(item.optString("summary"))
                        .append(" at ").append(start)
                        .append('\n').append("  ").append(item.optString("htmlLink")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "create" -> {
                val summary = args.optString("summary").ifBlank { return ConnectorResult(false, "Missing summary") }
                val start = args.optString("start").ifBlank { return ConnectorResult(false, "Missing start") }
                val end = args.optString("end").ifBlank { return ConnectorResult(false, "Missing end") }
                val payload = JSONObject()
                    .put("summary", summary)
                    .put("description", args.optString("description", ""))
                    .put("start", JSONObject().put("dateTime", start))
                    .put("end", JSONObject().put("dateTime", end))
                val response = http(
                    "POST",
                    "https://www.googleapis.com/calendar/v3/calendars/" + enc(calendarId) + "/events",
                    auth,
                    payload.toString(),
                )
                if (!response.ok) return ConnectorResult(false, "Calendar create failed: ${text(response.body, 400)}")
                ConnectorResult(true, "Created calendar event '$summary'.")
            }
            else -> ConnectorResult(false, "Unknown calendar action '$action'")
        }
    }

    // -------------------------------------------------------------------- github

    private fun github(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val token = cfg(config, "token") ?: return ConnectorResult(false, "Missing GitHub token")
        val repoName = cfg(config, "repo") ?: return ConnectorResult(false, "Missing repository")
        if (!repoName.contains("/")) return ConnectorResult(false, "Repository must be owner/name")
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )
        return when (action) {
            "get_repo" -> {
                val response = http("GET", "https://api.github.com/repos/$repoName", headers)
                if (!response.ok) return ConnectorResult(false, "GitHub error: ${text(response.body, 300)}")
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
                val state = args.optString("state", "open")
                val response = http(
                    "GET",
                    "https://api.github.com/repos/$repoName/issues?state=$state&per_page=5",
                    headers,
                )
                if (!response.ok) return ConnectorResult(false, "GitHub error: ${text(response.body, 300)}")
                val array = JSONArray(response.body)
                if (array.length() == 0) return ConnectorResult(true, "No $state issues.")
                val sb = StringBuilder("GitHub issues ($state):\n")
                for (i in 0 until array.length()) {
                    val issue = array.getJSONObject(i)
                    sb.append("#").append(issue.optInt("number"))
                        .append(" ").append(issue.optString("title"))
                        .append(" — ").append(issue.optJSONObject("user")?.optString("login") ?: "?")
                        .append('\n').append("  ").append(issue.optString("html_url")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "create_issue" -> {
                val title = args.optString("title").ifBlank { return ConnectorResult(false, "Missing title") }
                val body = args.optString("body", "")
                val payload = JSONObject().put("title", title).put("body", body)
                val response = http(
                    "POST",
                    "https://api.github.com/repos/$repoName/issues",
                    headers,
                    payload.toString(),
                )
                if (!response.ok) return ConnectorResult(false, "GitHub create issue failed: ${text(response.body, 400)}")
                val root = json(response.body)
                ConnectorResult(
                    true,
                    "Created issue #${root?.optInt("number")} in $repoName: $title\n${root?.optString("html_url") ?: ""}",
                )
            }
            else -> ConnectorResult(false, "Unknown GitHub action '$action'")
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
        val token = cfg(config, "token") ?: return ConnectorResult(false, "Missing Notion token")
        val databaseId = cfg(config, "databaseId") ?: return ConnectorResult(false, "Missing database id")
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Notion-Version" to "2022-06-28",
        )
        return when (action) {
            "query" -> {
                val payload = JSONObject().put("page_size", args.optInt("page_size", 5).coerceIn(1, 25))
                val response = http(
                    "POST",
                    "https://api.notion.com/v1/databases/" + enc(databaseId) + "/query",
                    headers,
                    payload.toString(),
                )
                if (!response.ok) return ConnectorResult(false, "Notion error: ${text(response.body, 300)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Notion returned invalid JSON")
                val results = root.optJSONArray("results") ?: return ConnectorResult(true, "No pages found.")
                val sb = StringBuilder("Notion pages:\n")
                for (i in 0 until results.length()) {
                    val page = results.getJSONObject(i)
                    sb.append("- ").append(page.optString("id")).append('\n')
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "create_page" -> {
                val title = args.optString("title").ifBlank { return ConnectorResult(false, "Missing title") }
                val content = args.optString("content", "")
                val payload = JSONObject()
                    .put("parent", JSONObject().put("database_id", databaseId))
                    .put(
                        "properties",
                        JSONObject().put(
                            "title",
                            JSONObject().put(
                                "title",
                                JSONArray().put(JSONObject().put("text", JSONObject().put("content", title))),
                            ),
                        ),
                    )
                    .put(
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
                val response = http("POST", "https://api.notion.com/v1/pages", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Notion create failed: ${text(response.body, 400)}")
                ConnectorResult(true, "Created Notion page '$title'.")
            }
            else -> ConnectorResult(false, "Unknown Notion action '$action'")
        }
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
                val url = "https://api.stripe.com/v1/events?limit=$limit${if (eventType.isNotBlank()) "&type=$eventType" else ""}"
                val response = http("GET", url, auth)
                if (!response.ok) return ConnectorResult(false, "Stripe error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Stripe returned invalid JSON")
                val data = root.optJSONArray("data") ?: return ConnectorResult(true, "No events found.")
                val sb = StringBuilder("Stripe events (type: ${eventType.ifBlank { "all" }}):\n")
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

    // --------------------------------------------------------- cloudflare workers

    private fun cloudflareWorkers(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val apiToken = cfg(config, "apiToken") ?: return ConnectorResult(false, "Missing Cloudflare API token")
        val accountId = cfg(config, "accountId") ?: return ConnectorResult(false, "Missing Cloudflare account ID")
        val headers = mapOf(
            "Authorization" to "Bearer $apiToken",
            "Content-Type" to "application/json",
        )
        val baseUrl = "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts"
        return when (action) {
            "deploy" -> {
                val name = args.optString("name").ifBlank { return ConnectorResult(false, "Missing worker name") }
                val code = args.optString("code").ifBlank { return ConnectorResult(false, "Missing worker code") }
                val url = "$baseUrl/$name"
                val response = http("PUT", url, headers, code)
                if (!response.ok) return ConnectorResult(false, "Cloudflare error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Cloudflare returned invalid JSON")
                if (!root.optBoolean("success")) {
                    val errorsArray = root.optJSONArray("errors")
                    val errors = errorsArray?.let { array -> (0 until array.length()).map { array.getString(it) }.joinToString(", ") } ?: "Unknown error"
                    return ConnectorResult(false, "Cloudflare deploy failed: $errors")
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
                val result = root.optJSONObject("result") ?: return ConnectorResult(true, "No workers found.")
                val sb = StringBuilder("Cloudflare Workers:\n")
                result.keys().forEachRemaining { name ->
                    sb.append("- $name\n")
                }
                ConnectorResult(true, sb.toString().trim())
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
                val response = http("PUT", url, headers, code)
                if (!response.ok) return ConnectorResult(false, "Cloudflare error: ${text(response.body, 400)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Cloudflare returned invalid JSON")
                if (!root.optBoolean("success")) {
                    val errorsArray = root.optJSONArray("errors")
                    val errors = errorsArray?.let { array -> (0 until array.length()).map { array.getString(it) }.joinToString(", ") } ?: "Unknown error"
                    return ConnectorResult(false, "Cloudflare deploy failed: $errors")
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

    // --------------------------------------------------------------- agentmail
    private fun agentmail(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val apiKey = cfg(config, "apiKey") ?: return ConnectorResult(false, "Missing AgentMail API key")
        val baseUrl = "https://agentmail.to/api/v1"
        val headers = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json")

        return when (action) {
            "create_inbox" -> {
                val username = args.optString("username").ifBlank { return ConnectorResult(false, "Missing username") }
                val domain = args.optString("domain").ifBlank { return ConnectorResult(false, "Missing domain") }
                val payload = JSONObject().put("username", username).put("domain", domain)
                val response = http("POST", "$baseUrl/inboxes", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "AgentMail create inbox failed: ${text(response.body, 500)}")
                val root = json(response.body) ?: return ConnectorResult(false, "AgentMail returned invalid JSON")
                ConnectorResult(
                    true,
                    "Created inbox: ${root.optString("address")} (ID: ${root.optString("id")})\nDisplay name: ${root.optString("display_name")}",
                )
            }
            "list_inboxes" -> {
                val response = http("GET", "$baseUrl/inboxes", headers)
                if (!response.ok) return ConnectorResult(false, "AgentMail list failed: ${text(response.body, 500)}")
                val array = JSONArray(response.body)
                if (array.length() == 0) return ConnectorResult(true, "No inboxes found.")
                val sb = StringBuilder("AgentMail inboxes:\n")
                for (i in 0 until array.length()) {
                    val inbox = array.getJSONObject(i)
                    sb.append("- ${inbox.optString("address")} (${inbox.optString("display_name")})\n  ID: ${inbox.optString("id")}, Active: ${inbox.optBoolean("active")}\n")
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "get_inbox" -> {
                val inboxId = args.optString("inboxId").ifBlank { return ConnectorResult(false, "Missing inboxId") }
                val response = http("GET", "$baseUrl/inboxes/$inboxId", headers)
                if (!response.ok) return ConnectorResult(false, "AgentMail get failed: ${text(response.body, 500)}")
                val root = json(response.body) ?: return ConnectorResult(false, "AgentMail returned invalid JSON")
                ConnectorResult(
                    true,
                    "Inbox: ${root.optString("address")} (${root.optString("display_name")})\nID: ${root.optString("id")}\nActive: ${root.optBoolean("active")}\nCreated: ${root.optString("created_at")}",
                )
            }
            "list_messages" -> {
                val inboxId = args.optString("inboxId").ifBlank { return ConnectorResult(false, "Missing inboxId") }
                val limit = args.optInt("limit", 10).coerceIn(1, 50)
                val response = http("GET", "$baseUrl/inboxes/$inboxId/messages?limit=$limit", headers)
                if (!response.ok) return ConnectorResult(false, "AgentMail list messages failed: ${text(response.body, 500)}")
                val array = JSONArray(response.body)
                if (array.length() == 0) return ConnectorResult(true, "No messages found.")
                val sb = StringBuilder("Messages in $inboxId:\n")
                for (i in 0 until array.length()) {
                    val msg = array.getJSONObject(i)
                    sb.append("- ${msg.optString("subject")} — ${msg.optString("from")}\n  ID: ${msg.optString("id")}, Date: ${msg.optString("received_at")}\n  Preview: ${text(msg.optString("text_preview"), 200)}\n")
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "get_message" -> {
                val inboxId = args.optString("inboxId").ifBlank { return ConnectorResult(false, "Missing inboxId") }
                val messageId = args.optString("messageId").ifBlank { return ConnectorResult(false, "Missing messageId") }
                val response = http("GET", "$baseUrl/inboxes/$inboxId/messages/$messageId", headers)
                if (!response.ok) return ConnectorResult(false, "AgentMail get message failed: ${text(response.body, 500)}")
                val root = json(response.body) ?: return ConnectorResult(false, "AgentMail returned invalid JSON")
                ConnectorResult(
                    true,
                    "Subject: ${root.optString("subject")}\nFrom: ${root.optString("from")}\nTo: ${root.optString("to")}\nDate: ${root.optString("received_at")}\n\n${root.optString("text_body")}",
                )
            }
            "send" -> {
                val inboxId = args.optString("inboxId").ifBlank { return ConnectorResult(false, "Missing inboxId") }
                val to = args.optString("to").ifBlank { return ConnectorResult(false, "Missing recipient") }
                val subject = args.optString("subject").ifBlank { return ConnectorResult(false, "Missing subject") }
                val textBody = args.optString("text", "")
                val htmlBody = args.optString("html", "").ifBlank { null }
                val payload = JSONObject().put("to", to).put("subject", subject).put("text", textBody)
                htmlBody?.let { payload.put("html", it) }
                val response = http("POST", "$baseUrl/inboxes/$inboxId/messages", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "AgentMail send failed: ${text(response.body, 500)}")
                ConnectorResult(true, "Sent email to $to with subject '$subject' from $inboxId")
            }
            "delete_inbox" -> {
                val inboxId = args.optString("inboxId").ifBlank { return ConnectorResult(false, "Missing inboxId") }
                val response = http("DELETE", "$baseUrl/inboxes/$inboxId", headers)
                if (!response.ok) return ConnectorResult(false, "AgentMail delete failed: ${text(response.body, 500)}")
                ConnectorResult(true, "Deleted inbox $inboxId")
            }
            else -> ConnectorResult(false, "Unknown AgentMail action '$action'")
        }
    }

    // ---------------------------------------------------------------- polar
    private fun polar(config: JSONObject?, action: String, args: JSONObject): ConnectorResult {
        val accessToken = cfg(config, "accessToken") ?: return ConnectorResult(false, "Missing Polar access token")
        val baseUrl = "https://api.polar.sh/v1"
        val headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")

        return when (action) {
            "create_customer" -> {
                val email = args.optString("email").ifBlank { return ConnectorResult(false, "Missing email") }
                val name = args.optString("name", "")
                val payload = JSONObject().put("email", email).put("name", name)
                val response = http("POST", "$baseUrl/customers", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Polar create customer failed: ${text(response.body, 500)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Polar returned invalid JSON")
                ConnectorResult(
                    true,
                    "Created customer: ${root.optString("id")} — ${root.optString("email")} (${root.optString("name")})\nCreated: ${root.optString("created_at")}",
                )
            }
            "get_customer" -> {
                val customerId = args.optString("customerId").ifBlank { return ConnectorResult(false, "Missing customerId") }
                val response = http("GET", "$baseUrl/customers/$customerId", headers)
                if (!response.ok) return ConnectorResult(false, "Polar get customer failed: ${text(response.body, 500)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Polar returned invalid JSON")
                ConnectorResult(
                    true,
                    "Customer: ${root.optString("id")} — ${root.optString("email")} (${root.optString("name")})\nCreated: ${root.optString("created_at")}\nMetadata: ${root.optString("metadata")}",
                )
            }
            "list_products" -> {
                val limit = args.optInt("limit", 10).coerceIn(1, 50)
                val response = http("GET", "$baseUrl/products?limit=$limit", headers)
                if (!response.ok) return ConnectorResult(false, "Polar list products failed: ${text(response.body, 500)}")
                val array = JSONArray(response.body)
                if (array.length() == 0) return ConnectorResult(true, "No products found.")
                val sb = StringBuilder("Polar products:\n")
                for (i in 0 until array.length()) {
                    val p = array.getJSONObject(i)
                    sb.append("- ${p.optString("id")}: ${p.optString("name")}\n  Description: ${text(p.optString("description"), 100)}\n  Active: ${p.optBoolean("active")}\n")
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "create_product" -> {
                val name = args.optString("name").ifBlank { return ConnectorResult(false, "Missing name") }
                val description = args.optString("description", "")
                val payload = JSONObject().put("name", name).put("description", description)
                val response = http("POST", "$baseUrl/products", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Polar create product failed: ${text(response.body, 500)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Polar returned invalid JSON")
                ConnectorResult(
                    true,
                    "Created product: ${root.optString("id")} — ${root.optString("name")}\nDescription: ${root.optString("description")}\nActive: ${root.optBoolean("active")}",
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
                    .put("product_id", productId)
                    .put("amount", amountCents)
                    .put("currency", currency)
                    .put("recurring_interval", interval)
                val response = http("POST", "$baseUrl/prices", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Polar create price failed: ${text(response.body, 500)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Polar returned invalid JSON")
                ConnectorResult(
                    true,
                    "Created price: ${root.optString("id")} — ${root.optLong("amount")} ${root.optString("currency")}/${root.optString("recurring_interval")}\nProduct: ${root.optString("product_id")}\nActive: ${root.optBoolean("active")}",
                )
            }
            "create_checkout" -> {
                val priceId = args.optString("priceId").ifBlank { return ConnectorResult(false, "Missing priceId") }
                val customerId = args.optString("customerId", "").ifBlank { null }
                val successUrl = args.optString("successUrl", "").ifBlank { null }
                val cancelUrl = args.optString("cancelUrl", "").ifBlank { null }
                val payload = JSONObject().put("price_id", priceId)
                customerId?.let { payload.put("customer_id", it) }
                successUrl?.let { payload.put("success_url", it) }
                cancelUrl?.let { payload.put("cancel_url", it) }
                val response = http("POST", "$baseUrl/checkouts", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Polar create checkout failed: ${text(response.body, 500)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Polar returned invalid JSON")
                ConnectorResult(
                    true,
                    "Created checkout: ${root.optString("id")}\nURL: ${root.optString("url")}\nExpires: ${root.optString("expires_at")}",
                )
            }
            "list_subscriptions" -> {
                val customerId = args.optString("customerId", "").ifBlank { null }
                val status = args.optString("status", "active")
                val limit = args.optInt("limit", 10).coerceIn(1, 50)
                val url = StringBuilder("$baseUrl/subscriptions?limit=$limit&status=$status")
                customerId?.let { url.append("&customer_id=").append(it) }
                val response = http("GET", url.toString(), headers)
                if (!response.ok) return ConnectorResult(false, "Polar list subscriptions failed: ${text(response.body, 500)}")
                val array = JSONArray(response.body)
                if (array.length() == 0) return ConnectorResult(true, "No subscriptions found.")
                val sb = StringBuilder("Polar subscriptions (status: $status):\n")
                for (i in 0 until array.length()) {
                    val s = array.getJSONObject(i)
                    sb.append("- ${s.optString("id")}: ${s.optString("status")}\n  Customer: ${s.optString("customer_id")}, Price: ${s.optString("price_id")}\n  Current period: ${s.optString("current_period_start")} to ${s.optString("current_period_end")}\n")
                }
                ConnectorResult(true, sb.toString().trim())
            }
            "cancel_subscription" -> {
                val subscriptionId = args.optString("subscriptionId").ifBlank { return ConnectorResult(false, "Missing subscriptionId") }
                val payload = JSONObject().put("cancel_at_period_end", true)
                val response = http("POST", "$baseUrl/subscriptions/$subscriptionId/cancel", headers, payload.toString())
                if (!response.ok) return ConnectorResult(false, "Polar cancel subscription failed: ${text(response.body, 500)}")
                ConnectorResult(true, "Subscription $subscriptionId cancelled at period end")
            }
            "verify_key" -> {
                val response = http("GET", "$baseUrl/organization", headers)
                if (!response.ok) return ConnectorResult(false, "Invalid API key or no access: ${text(response.body, 500)}")
                val root = json(response.body) ?: return ConnectorResult(false, "Polar returned invalid JSON")
                ConnectorResult(
                    true,
                    "API key valid. Organization: ${root.optString("name")} (${root.optString("id")})",
                )
            }
            else -> ConnectorResult(false, "Unknown Polar action '$action'")
        }
    }
}