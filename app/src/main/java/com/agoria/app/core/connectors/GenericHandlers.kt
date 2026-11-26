package com.agoria.app.core.connectors

import android.content.Context
import com.agoria.app.data.ConnectorEntity
import org.json.JSONArray
import org.json.JSONObject

/** Wikipedia, Hacker News and Web Search handlers — no auth, read-only. */

class GenericKnowledgeHandler : ConnectorHandler {
    override val connectorId = "wikipedia"
    override val description = "Search and summarize public Wikipedia articles. No key needed."
    override val risk = "low"

    override fun describeActions(): String =
        "wikipedia actions:\n- search: Search article titles for a query. args {\"query\": \"...\"}\n- summary: Get the plain-text summary of an article. args {\"title\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "search" -> "search"
            "summary" -> "summary"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? = null

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        return when (action) {
            "search" -> {
                val query = args.optString("query").ifBlank {
                    return HandlerResult(false, "Missing query")
                }
                val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
                    ConnectorHttp.enc(query) + "&format=json&srlimit=5"
                val response = ConnectorHttp.http("GET", url)
                if (!response.ok) return HandlerResult(false, "Wikipedia error: ${ConnectorHttp.text(response.body, 240)}")
                val root = ConnectorHttp.json(response.body)
                    ?: return HandlerResult(false, "Wikipedia returned invalid JSON")
                val search = root.optJSONObject("query")?.optJSONArray("search")
                    ?: return HandlerResult(true, "No Wikipedia results for '$query'.")
                val sb = StringBuilder("Wikipedia results for '$query':\n")
                for (i in 0 until minOf(5, search.length())) {
                    val hit = search.getJSONObject(i)
                    sb.append("- ").append(hit.optString("title"))
                        .append(": ").append(ConnectorHttp.text(hit.optString("snippet"), 180)).append('\n')
                }
                HandlerResult(true, sb.toString().trim())
            }
            "summary" -> {
                val title = args.optString("title").ifBlank { return HandlerResult(false, "Missing title") }
                val url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + ConnectorHttp.enc(title)
                val response = ConnectorHttp.http("GET", url)
                if (!response.ok) return HandlerResult(false, "Wikipedia error: ${ConnectorHttp.text(response.body, 240)}")
                val root = ConnectorHttp.json(response.body)
                    ?: return HandlerResult(false, "Wikipedia returned invalid JSON")
                val extract = root.optString("extract")
                if (extract.isBlank()) return HandlerResult(true, "No summary found for '$title'.")
                HandlerResult(
                    true,
                    "Wikipedia: ${root.optString("title")}\n$extract\n${root.optString("content_urls").let {
                        if (it.isBlank()) "" else it
                    }}",
                )
            }
            else -> HandlerResult(false, "Unknown Wikipedia action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

class GenericNewsHandler : ConnectorHandler {
    override val connectorId = "hn"
    override val description = "Read the current top stories and search Hacker News."
    override val risk = "low"

    override fun describeActions(): String =
        "hn actions:\n- top: Fetch the current top stories. args {\"limit\": 10}\n- search: Search Hacker News through Algolia. args {\"query\": \"...\", \"limit\": 10}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "top" -> "top"
            "search" -> "search"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? = null

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        return when (action) {
            "top" -> {
                val limit = args.optInt("limit", 10).coerceIn(1, 20)
                val idsResponse = ConnectorHttp.http("GET", "https://hacker-news.firebaseio.com/v0/topstories.json")
                if (!idsResponse.ok) return HandlerResult(false, "Hacker News error: ${ConnectorHttp.text(idsResponse.body, 240)}")
                val ids = JSONArray(idsResponse.body)
                val sb = StringBuilder("Hacker News top stories:\n")
                for (i in 0 until minOf(limit, ids.length())) {
                    val id = ids.optInt(i)
                    val itemResponse = ConnectorHttp.http("GET", "https://hacker-news.firebaseio.com/v0/item/$id.json")
                    if (!itemResponse.ok) continue
                    val item = ConnectorHttp.json(itemResponse.body) ?: continue
                    sb.append("- ").append(item.optString("title"))
                        .append(" (").append(item.optString("score")).append(" points, ")
                        .append(item.optString("by")).append(")\n  ")
                        .append(item.optString("url").ifBlank { "https://news.ycombinator.com/item?id=$id" })
                        .append('\n')
                }
                HandlerResult(true, sb.toString().trim())
            }
            "search" -> {
                val query = args.optString("query").ifBlank { return HandlerResult(false, "Missing query") }
                val limit = args.optInt("limit", 10).coerceIn(1, 20)
                val url = "https://hn.algolia.com/api/v1/search?query=" + ConnectorHttp.enc(query) +
                    "&tags=story&hitsPerPage=$limit"
                val response = ConnectorHttp.http("GET", url)
                if (!response.ok) return HandlerResult(false, "Hacker News search error: ${ConnectorHttp.text(response.body, 240)}")
                val root = ConnectorHttp.json(response.body) ?: return HandlerResult(false, "Hacker News returned invalid JSON")
                val hits = root.optJSONArray("hits") ?: return HandlerResult(true, "No Hacker News results.")
                val sb = StringBuilder("Hacker News results for '$query':\n")
                for (i in 0 until minOf(limit, hits.length())) {
                    val hit = hits.getJSONObject(i)
                    sb.append("- ").append(hit.optString("title"))
                        .append(" (").append(hit.optString("points")).append(" points)\n  ")
                        .append(hit.optString("url").ifBlank { "https://news.ycombinator.com/item?id=" + hit.optString("objectID") })
                        .append('\n')
                }
                HandlerResult(true, sb.toString().trim())
            }
            else -> HandlerResult(false, "Unknown Hacker News action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

class WebSearchHandler : ConnectorHandler {
    override val connectorId = "search"
    override val description = "Quick answers from DuckDuckGo's public instant-answer API."
    override val risk = "low"

    override fun describeActions(): String =
        "search actions:\n- search: Return the instant answer for a query. args {\"query\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "search" -> "search"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? = null

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        if (action != "search") return HandlerResult(false, "Unknown search action '$action'")
        val query = args.optString("query").ifBlank { return HandlerResult(false, "Missing query") }
        val apiKey = ConnectorHttp.cfg(config, "searchApiKey")
        if (apiKey?.isNotBlank() == true) {
            return braveSearch(query, apiKey!!)
        }
        // No API key configured: fall back to DuckDuckGo's public instant-answer API, then the
        // HTML scraper as last resort.
        val url = "https://api.duckduckgo.com/?q=" + ConnectorHttp.enc(query) + "&format=json&no_html=1&skip_disambig=1"
        val response = ConnectorHttp.http("GET", url)
        if (!response.ok) return HandlerResult(false, "Search error: ${ConnectorHttp.text(response.body, 240)}")
        val root = ConnectorHttp.json(response.body) ?: return HandlerResult(false, "Search returned invalid JSON")
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
                    sb.append("- ").append(ConnectorHttp.text(first.optString("Text"), 200)).append('\n')
                    count++
                } else {
                    val t = topic.optString("Text")
                    if (t.isNotBlank()) {
                        sb.append("- ").append(ConnectorHttp.text(t, 200)).append('\n')
                        count++
                    }
                }
            }
        }
        if (sb.length <= "Instant answer for '$query':\n".length) {
            val results = duckDuckGoResults(query)
            if (results.isNotBlank()) {
                sb.append("Web results:\n").append(results)
            } else {
                sb.append("No instant answer and no web results for '$query'.")
            }
        }
        return HandlerResult(true, sb.toString().trim())
    }

    private fun braveSearch(query: String, apiKey: String): HandlerResult {
        val url = "https://api.search.brave.com/res/v1/web/search?q=" + ConnectorHttp.enc(query) + "&count=10"
        val headers = mapOf("X-Subscription-Token" to apiKey, "Accept" to "application/json")
        val response = ConnectorHttp.http("GET", url, headers)
        if (!response.ok) return HandlerResult(false, "Search error: ${ConnectorHttp.text(response.body, 240)}")
        val root = ConnectorHttp.json(response.body) ?: return HandlerResult(false, "Search returned invalid JSON")
        val web = root.optJSONObject("web") ?: return HandlerResult(true, "No search results for '$query'.")
        val results = web.optJSONArray("results")
        if (results == null || results.length() == 0) return HandlerResult(true, "No search results for '$query'.")
        val sb = StringBuilder("Web results for '$query':\n")
        var count = 0
        for (i in 0 until results.length()) {
            if (count >= 5) break
            val item = results.optJSONObject(i) ?: continue
            val title = item.optString("title")
            val link = item.optString("url")
            val desc = item.optString("description")
            if (title.isNotBlank()) {
                sb.append("- ").append(ConnectorHttp.text(title, 120)).append('\n')
                if (link.isNotBlank()) sb.append("  ").append(link).append('\n')
                if (desc.isNotBlank()) sb.append("  ").append(ConnectorHttp.text(desc, 200)).append('\n')
                count++
            }
        }
        return HandlerResult(true, sb.toString().trim())
    }

    private fun duckDuckGoResults(query: String): String {
        val response = try {
            okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder()
                    .url("https://html.duckduckgo.com/html/?q=" + ConnectorHttp.enc(query))
                    .header("User-Agent", "Mozilla/5.0 (Android) Dunder/1.0")
                    .build(),
            ).execute()
        } catch (_: Exception) {
            return ""
        }
        return response.use {
            val html = ConnectorHttp.readBoundedBody(it.body, ConnectorHttp.MAX_BODY)
            if (html.isBlank()) return@use ""
            val titleRegex = Regex("""result__a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val snippetRegex = Regex("""result__snippet[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val snippets = snippetRegex.findAll(html).map { ConnectorHttp.text(it.groupValues[1], 160) }.toList()
            val sb = StringBuilder()
            titleRegex.findAll(html).take(5).forEachIndexed { index, match ->
                val raw = match.groupValues[1].replace("&amp;", "&")
                val link = if (raw.contains("uddg=")) {
                    java.net.URLDecoder.decode(raw.substringAfter("uddg=").substringBefore("&"), "UTF-8")
                } else {
                    raw
                }
                sb.append("- ").append(ConnectorHttp.text(match.groupValues[2], 120))
                if (link.isNotBlank()) sb.append(" — ").append(link)
                sb.append('\n')
                val snippet = snippets.getOrNull(index)
                if (!snippet.isNullOrBlank()) sb.append("  ").append(snippet).append('\n')
            }
            sb.toString().trim()
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

class WeatherHandler : ConnectorHandler {
    override val connectorId = "weather"
    override val description = "Current weather and a short forecast for any coordinates."
    override val risk = "low"

    override fun describeActions(): String =
        "weather actions:\n- current: Get temperature, wind, and weather code. args {\"latitude\": 51.5, \"longitude\": -0.1}\n- forecast: Get a short daily forecast. args {\"latitude\": 51.5, \"longitude\": -0.1, \"days\": 3}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "current" -> "current"
            "forecast" -> "forecast"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? = null

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val lat = args.optDouble("latitude", Double.NaN)
        val lon = args.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return HandlerResult(false, "Missing latitude and longitude")
        return when (action) {
            "current" -> {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current_weather=true&timezone=auto"
                val response = ConnectorHttp.http("GET", url)
                if (!response.ok) return HandlerResult(false, "Weather error: ${ConnectorHttp.text(response.body, 240)}")
                val root = ConnectorHttp.json(response.body) ?: return HandlerResult(false, "Weather returned invalid JSON")
                val current = root.optJSONObject("current_weather")
                    ?: return HandlerResult(false, "No current weather data")
                HandlerResult(
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
                val response = ConnectorHttp.http("GET", url)
                if (!response.ok) return HandlerResult(false, "Weather error: ${ConnectorHttp.text(response.body, 240)}")
                val root = ConnectorHttp.json(response.body) ?: return HandlerResult(false, "Weather returned invalid JSON")
                val daily = root.optJSONObject("daily") ?: return HandlerResult(false, "No forecast data")
                val dates = daily.optJSONArray("time")
                val max = daily.optJSONArray("temperature_2m_max")
                val min = daily.optJSONArray("temperature_2m_min")
                val codes = daily.optJSONArray("weathercode")
                if (dates == null) return HandlerResult(false, "No forecast days")
                val sb = StringBuilder("Forecast for $lat, $lon:\n")
                for (i in 0 until dates.length()) {
                    sb.append("- ").append(dates.optString(i))
                        .append(": ").append(min?.optDouble(i)?.toString() ?: "?")
                        .append("°C to ").append(max?.optDouble(i)?.toString() ?: "?")
                        .append("°C, code ").append(codes?.optInt(i) ?: "?").append('\n')
                }
                HandlerResult(true, sb.toString().trim())
            }
            else -> HandlerResult(false, "Unknown weather action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

class CurrencyHandler : ConnectorHandler {
    override val connectorId = "currency"
    override val description = "Convert currencies using European Central Bank reference rates."
    override val risk = "low"

    override fun describeActions(): String =
        "currency actions:\n- convert: Convert between currencies. args {\"from\": \"USD\", \"to\": \"EUR\", \"amount\": 100}\n- latest: Latest rates for a base currency. args {\"from\": \"USD\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "convert" -> "convert"
            "latest" -> "latest"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? = null

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        return when (action) {
            "convert" -> {
                val from = args.optString("from", "USD").uppercase()
                val to = args.optString("to", "EUR").uppercase()
                if (!validCurrencyCode(from)) return HandlerResult(false, "Invalid currency '$from': use ISO 4217 3-letter codes (e.g. USD, EUR).")
                if (!validCurrencyCode(to)) return HandlerResult(false, "Invalid currency '$to': use ISO 4217 3-letter codes (e.g. USD, EUR).")
                val amount = args.optDouble("amount", 1.0)
                val url = "https://api.frankfurter.app/latest?from=$from&to=$to"
                val response = ConnectorHttp.http("GET", url)
                if (!response.ok) return HandlerResult(false, "Currency error: ${ConnectorHttp.text(response.body, 240)}")
                val root = ConnectorHttp.json(response.body) ?: return HandlerResult(false, "Currency returned invalid JSON")
                val rate = root.optJSONObject("rates")?.optDouble(to)
                    ?: return HandlerResult(false, "No rate for $from to $to")
                HandlerResult(
                    true,
                    "$amount $from = %.2f $to (rate %s, %s)".format(amount * rate, rate, root.optString("date")),
                )
            }
            "latest" -> {
                val from = args.optString("from", "USD").uppercase()
                if (!validCurrencyCode(from)) return HandlerResult(false, "Invalid currency '$from': use ISO 4217 3-letter codes (e.g. USD, EUR).")
                val url = "https://api.frankfurter.app/latest?from=$from"
                val response = ConnectorHttp.http("GET", url)
                if (!response.ok) return HandlerResult(false, "Currency error: ${ConnectorHttp.text(response.body, 240)}")
                val root = ConnectorHttp.json(response.body) ?: return HandlerResult(false, "Currency returned invalid JSON")
                val rates = root.optJSONObject("rates") ?: return HandlerResult(true, "No rates found.")
                val sb = StringBuilder("1 $from = (ECB rates, ${root.optString("date")})\n")
                val keys = rates.keys()
                var count = 0
                while (keys.hasNext() && count < 8) {
                    val key = keys.next()
                    sb.append("- ").append(key).append(": ").append(rates.optDouble(key)).append('\n')
                    count++
                }
                HandlerResult(true, sb.toString().trim())
            }
            else -> HandlerResult(false, "Unknown currency action '$action'")
        }
    }

    private fun validCurrencyCode(code: String): Boolean = code.matches(Regex("^[A-Z]{3}$"))

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

class DeviceHandler(
    private val context: Context,
) : ConnectorHandler {
    override val connectorId = "device"
    override val description = "Device information and local notifications."
    override val risk = "low"

    override fun describeActions(): String =
        "device actions:\n- info: Get device manufacturer, model, Android version and battery.\n- notify: Post a local notification. args {\"title\": \"...\", \"body\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "info", "get_info" -> "info"
            "notify" -> "notify"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? = null

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        return when (action) {
            "info", "get_info" -> {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                HandlerResult(
                    true,
                    "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                        "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), " +
                        "battery $level%, time ${com.agoria.app.util.Time.dateTime(com.agoria.app.util.Time.now())}.",
                )
            }
            "notify" -> {
                val title = args.optString("title", "Dunder").ifBlank { "Dunder" }
                val body = args.optString("body").ifBlank { return HandlerResult(false, "Missing body") }
                com.agoria.app.util.Notifier.notifyLocal(context, title, body)
                HandlerResult(true, "Posted local notification: $title")
            }
            else -> HandlerResult(false, "Unknown device action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}
