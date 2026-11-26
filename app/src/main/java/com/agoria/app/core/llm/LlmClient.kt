package com.agoria.app.core.llm

import com.agoria.app.core.ProviderCatalog
import com.agoria.app.core.ProviderSpec
import com.agoria.app.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LlmMessage(val role: String, val content: String)

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    val temperature: Double = 0.7,
    val maxTokens: Int = 1200,
    val json: Boolean = false,
)

data class ProviderAttempt(
    val providerId: String,
    val providerName: String,
    val model: String,
    val ok: Boolean,
    val error: String?,
    val latencyMs: Long,
)

data class LlmResult(
    val ok: Boolean,
    val text: String = "",
    val providerId: String = "",
    val providerName: String = "",
    val model: String = "",
    val latencyMs: Long = 0,
    val attempts: List<ProviderAttempt> = emptyList(),
    val error: String? = null,
)

class LlmClient(private val repo: Repository) {

    private val gate = Semaphore(2)

    // BUG-10: per-provider cooldown (milliseconds since last call), so a rate-limited or dead
    // provider is not hammered every cycle by every agent.
    private val lastCallMillis = mutableMapOf<String, Long>()
    private val minCooldownMillis = 5_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .writeTimeout(70, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun complete(request: LlmRequest): LlmResult = completeInternal(request)

    suspend fun completeWithProvider(
        providerId: String,
        request: LlmRequest,
    ): LlmResult = completeInternal(request, forceProviderId = providerId)

    private suspend fun completeInternal(
        request: LlmRequest,
        forceProviderId: String? = null,
    ): LlmResult {
        val providers = repo.dbProviderList()
            .sortedBy { it.priority }
            .filter { forceProviderId == null || it.id == forceProviderId }

        if (providers.isEmpty()) {
            return LlmResult(
                ok = false,
                error = "No providers are configured.",
            )
        }

        val attempts = mutableListOf<ProviderAttempt>()
        var lastError = "No provider succeeded."

        for (provider in providers) {
            if (forceProviderId == null && !provider.enabled) continue
            val spec = ProviderCatalog.byId[provider.id] ?: continue
            val key = repo.providerKey(provider.id)
            val extra = repo.providerExtra(provider.id)

            if (spec.keyRequired && key.isNullOrBlank()) {
                attempts += ProviderAttempt(
                    providerId = provider.id,
                    providerName = spec.name,
                    model = provider.model.ifBlank { spec.defaultModel },
                    ok = false,
                    error = "Missing API key",
                    latencyMs = 0,
                )
                continue
            }
            if (spec.needsExtra && extra.isNullOrBlank()) {
                attempts += ProviderAttempt(
                    providerId = provider.id,
                    providerName = spec.name,
                    model = provider.model.ifBlank { spec.defaultModel },
                    ok = false,
                    error = "Missing ${spec.extraLabel}",
                    latencyMs = 0,
                )
                continue
            }

            // BUG-10: per-provider cooldown — skip if called too recently.
            val now = System.currentTimeMillis()
            val last = lastCallMillis[provider.id] ?: 0L
            if (now - last < minCooldownMillis) {
                attempts += ProviderAttempt(
                    providerId = provider.id,
                    providerName = spec.name,
                    model = provider.model.ifBlank { spec.defaultModel },
                    ok = false,
                    error = "Provider on cooldown (${minCooldownMillis - (now - last)}ms remaining)",
                    latencyMs = 0,
                )
                continue
            }

            // Build model fallback list: user's configured model first, then all other models from the provider
            val configuredModel = provider.model.ifBlank { spec.defaultModel }
            val modelFallbacks = buildModelFallbacks(spec, configuredModel)

            // BUG-10: release the semaphore permit between providers instead of holding it across
            // the whole fallback chain.
            val (fallbackResult, fallbackAttempts) = gate.withPermit {
                lastCallMillis[provider.id] = System.currentTimeMillis()
                callProviderWithFallbacks(
                    spec = spec,
                    provider = provider,
                    models = modelFallbacks,
                    key = key,
                    extra = extra,
                    request = request,
                )
            }
            attempts.addAll(fallbackAttempts)

            if (fallbackResult.ok) {
                return fallbackResult.copy(attempts = attempts)
            }
            lastError = fallbackAttempts.lastOrNull()?.error ?: "Unknown provider error"
        }

        return LlmResult(
            ok = false,
            error = lastError,
            attempts = attempts,
        )
    }

    private data class ProviderCall(
        val result: LlmResult,
        val attempt: ProviderAttempt,
    )

    private suspend fun callProvider(
        spec: ProviderSpec,
        provider: com.agoria.app.data.ProviderEntity,
        model: String,
        key: String?,
        extra: String?,
        request: LlmRequest,
    ): ProviderCall {
        val started = System.nanoTime()
        val baseUrl = spec.baseUrl.replace("{account_id}", extra ?: "").trimEnd('/')
        val url = "$baseUrl/chat/completions"

        val body = JSONObject()
        body.put("model", model)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", request.system))
        request.messages.forEach {
            messages.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        body.put("messages", messages)
        body.put("temperature", request.temperature)
        body.put("max_tokens", request.maxTokens)

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Dunder/1.0 (Android)")
        if (!key.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $key")
        }
        spec.staticHeaders.forEach { (name, value) ->
            requestBuilder.addHeader(name, value)
        }
        if (request.json) {
            // BUG-10: tell the provider we want JSON back when the caller asked for it.
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        requestBuilder.post(body.toString().toRequestBody("application/json".toMediaType()))

        return try {
            var lastError = "Request failed"
            var lastCode = 0
            var retried = false
            repeat(2) { attempt ->
                val call = client.newCall(requestBuilder.build())
                // BUG-10: cancel the underlying OkHttp call when the coroutine is cancelled.
                val response = cancellableExecute(call)
                val responseBody = withContext(Dispatchers.IO) {
                    try {
                        response.body?.string() ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                }
                lastCode = response.code
                if (!response.isSuccessful) {
                    lastError = "HTTP ${response.code}: ${responseBody.take(400)}"
                    if (response.code >= 500 && !retried) {
                        retried = true
                        val retryAfter = retryAfterMillis(response)
                        delay(retryAfter ?: 600)
                    } else {
                        // 4xx errors (and a second 5xx) are not retried — break out of the loop.
                        break@repeat
                    }
                } else {
                    val parsed = parseResponse(responseBody)
                    if (parsed.isSuccess) {
                        val latency = (System.nanoTime() - started) / 1_000_000
                        val result = LlmResult(
                            ok = true,
                            text = parsed.text,
                            providerId = spec.id,
                            providerName = spec.name,
                            model = model,
                            latencyMs = latency,
                        )
                        repo.recordProviderResult(spec.id, "ok", latency, null)
                        return ProviderCall(
                            result = result,
                            attempt = ProviderAttempt(
                                providerId = spec.id,
                                providerName = spec.name,
                                model = model,
                                ok = true,
                                error = null,
                                latencyMs = latency,
                            ),
                        )
                    } else {
                        lastError = parsed.error ?: "Empty response"
                        break
                    }
                }
            }
            val latency = (System.nanoTime() - started) / 1_000_000
            val attempt = ProviderAttempt(
                providerId = spec.id,
                providerName = spec.name,
                model = model,
                ok = false,
                error = if (lastCode == 0) lastError else "HTTP $lastCode: $lastError",
                latencyMs = latency,
            )
            repo.recordProviderResult(spec.id, "error", latency, attempt.error)
            ProviderCall(
                result = LlmResult(
                    ok = false,
                    error = attempt.error,
                    providerId = spec.id,
                    providerName = spec.name,
                    model = model,
                    latencyMs = latency,
                ),
                attempt = attempt,
            )
        } catch (e: Exception) {
            val latency = (System.nanoTime() - started) / 1_000_000
            val message = e.message ?: e.javaClass.simpleName
            val attempt = ProviderAttempt(
                providerId = spec.id,
                providerName = spec.name,
                model = model,
                ok = false,
                error = message,
                latencyMs = latency,
            )
            repo.recordProviderResult(spec.id, "error", latency, message)
            ProviderCall(
                result = LlmResult(
                    ok = false,
                    error = message,
                    providerId = spec.id,
                    providerName = spec.name,
                    model = model,
                    latencyMs = latency,
                ),
                attempt = attempt,
            )
        }
    }

    /** Executes an OkHttp call cancellably: cancels the underlying call when the coroutine is cancelled. */
    private suspend fun cancellableExecute(call: okhttp3.Call): okhttp3.Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    cont.resume(response)
                }
            })
        }

    /** Reads Retry-After (seconds) from a response; null when absent or unparsable. */
    private fun retryAfterMillis(response: okhttp3.Response): Long? {
        val header = response.header("Retry-After") ?: return null
        return try {
            header.toLong() * 1000
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun buildModelFallbacks(spec: ProviderSpec, configuredModel: String): List<String> {
        val fallbacks = mutableListOf<String>()
        if (configuredModel.isNotBlank()) {
            fallbacks.add(configuredModel)
        }
        for (model in spec.models) {
            if (model.id !in fallbacks) {
                fallbacks.add(model.id)
            }
        }
        if (spec.defaultModel.isNotBlank() && spec.defaultModel !in fallbacks) {
            fallbacks.add(spec.defaultModel)
        }
        return fallbacks
    }

    private suspend fun callProviderWithFallbacks(
        spec: ProviderSpec,
        provider: com.agoria.app.data.ProviderEntity,
        models: List<String>,
        key: String?,
        extra: String?,
        request: LlmRequest,
    ): Pair<LlmResult, List<ProviderAttempt>> {
        val allAttempts = mutableListOf<ProviderAttempt>()
        var lastError = "No model succeeded"

        for (model in models) {
            val call = callProvider(
                spec = spec,
                provider = provider,
                model = model,
                key = key,
                extra = extra,
                request = request,
            )
            allAttempts.add(call.attempt)

            if (call.result.ok) {
                return Pair(call.result.copy(attempts = allAttempts), allAttempts)
            }

            val errorCode = call.attempt.error?.takeWhile { it.isDigit() }?.toIntOrNull()
            val isRateLimit = errorCode == 429
            val isPaymentError = errorCode == 402

            if (isRateLimit || isPaymentError) {
                lastError = call.attempt.error ?: "HTTP $errorCode"
                continue
            } else {
                lastError = call.attempt.error ?: "Unknown error"
                break
            }
        }

        val finalResult = LlmResult(
            ok = false,
            error = lastError,
            attempts = allAttempts,
        )
        return Pair(finalResult, allAttempts)
    }

    private data class Parsed(val isSuccess: Boolean, val text: String = "", val error: String? = null)

    private fun parseResponse(body: String): Parsed {
        if (body.isBlank()) return Parsed(false, error = "Empty response body")
        return try {
            val root = JSONObject(body)
            val choices = root.optJSONArray("choices")
                ?: return Parsed(false, error = "No choices in response")
            if (choices.length() == 0) return Parsed(false, error = "No choices in response")
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: return Parsed(false, error = "No message in response")
            
            // Try multiple content fields (OpenAI, Anthropic, streaming, etc.)
            val content = message.opt("content") 
                ?: message.opt("text") 
                ?: message.opt("delta")
                ?: message.opt("content_block")
            val text = when (content) {
                is String -> content
                is JSONArray -> {
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i)
                        sb.append(part?.optString("text") ?: "")
                    }
                    sb.toString()
                }
                else -> message.optString("content") ?: message.optString("text") ?: ""
            }
            
            if (text.isBlank()) {
                android.util.Log.w("LlmClient", "Empty content in 200 response: $body")
                Parsed(false, error = "Model returned an empty message")
            } else Parsed(true, text = text)
        } catch (e: Exception) {
            android.util.Log.e("LlmClient", "Parse failed: ${e.message}, body: $body")
            Parsed(false, error = "Could not parse response: ${e.message}")
        }
    }
}