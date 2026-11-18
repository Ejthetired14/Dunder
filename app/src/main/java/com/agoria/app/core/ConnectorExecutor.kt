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
}