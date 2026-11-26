package com.agoria.app.core.connectors

import com.agoria.app.util.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.util.concurrent.TimeUnit

/** Shared HTTP client and helpers for all connector handlers. */
object ConnectorHttp {

    private val media = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Max body bytes read per response. Larger bodies are truncated to avoid memory pressure. */
    val MAX_BODY = 1_048_576 // 1 MiB

    data class HttpResult(val code: Int, val body: String, val ok: Boolean)

    /** Reads at most `maxBytes` from a response body, always closing the stream. */
    fun readBoundedBody(body: ResponseBody?, maxBytes: Int): String {
        return body?.byteStream()?.use { input ->
            val buffer = ByteArray(maxBytes + 1)
            val read = input.read(buffer, 0, maxBytes + 1)
            String(buffer, 0, minOf(read, maxBytes))
        } ?: ""
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
            return response.use {
                val responseBody = readBoundedBody(it.body, MAX_BODY)
                HttpResult(response.code, responseBody, response.isSuccessful)
            }
        } catch (e: Exception) {
            HttpResult(-1, e.message ?: "Network error", false)
        }
    }

    /**
     * Performs an HTTP request with retries for idempotent methods only.
     *
     * Retry only idempotent methods (GET, HEAD, PUT, DELETE). POST/PATCH are not retried
     * because a transient failure after the request reached the server could duplicate
     * side effects (duplicate email, issue, payment, etc.).
     */
    fun http(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): HttpResult {
        val retryable = method == "GET" || method == "HEAD" || method == "PUT" || method == "DELETE"
        var attempt = 0
        var last = HttpResult(-1, "not attempted", false)
        while (attempt < 3) {
            last = httpOnce(method, url, headers, body)
            val transient = last.code == -1 || last.code == 429 || last.code >= 500
            if (!transient || !retryable) return last
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

    fun cfg(config: org.json.JSONObject?, key: String): String? =
        config?.optString(key)?.trim()?.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u0000-\\u001f\\u007f]"), "")?.ifBlank { null }

    fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun text(value: String?, max: Int = 700): String =
        (value ?: "")
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(max)

    fun json(body: String): org.json.JSONObject? = Json.tryObject(body)

    fun jsonArray(body: String): org.json.JSONArray? = try {
        org.json.JSONArray(body)
    } catch (_: Exception) {
        null
    }

    fun parseHeaders(raw: String?): Map<String, String> {
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
}
