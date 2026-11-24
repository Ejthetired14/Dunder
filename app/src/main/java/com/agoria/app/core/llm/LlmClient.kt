package com.agoria.app.core.llm

import com.agoria.app.core.ProviderCatalog
import com.agoria.app.core.ProviderSpec
import com.agoria.app.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .writeTimeout(70, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun complete(request: LlmRequest): LlmResult = gate.withPermit {
        completeInternal(request)
    }

    suspend fun completeWithProvider(
        providerId: String,
        request: LlmRequest,
    ): LlmResult = gate.withPermit {
        completeInternal(request, forceProviderId = providerId)
    }

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

            // Build model fallback list: user's configured model first, then all other models from the provider
            val configuredModel = provider.model.ifBlank { spec.defaultModel }
            val modelFallbacks = buildModelFallbacks(spec, configuredModel)
            
            val (fallbackResult, fallbackAttempts) = callProviderWithFallbacks(
                spec = spec,
                provider = provider,
                models = modelFallbacks,
                key = key,
                extra = extra,
                request = request,
            )
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
        requestBuilder.post(body.toString().toRequestBody("application/json".toMediaType()))

        return try {
            var lastError = "Request failed"
            var lastCode = 0
            repeat(2) { attempt ->
                val call = client.newCall(requestBuilder.build())
                val response = withContext(Dispatchers.IO) { call.execute() }
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
                    if (response.code >= 500) {
                        delay(600)
                    } else {
                        return@repeat
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
                is JSONArray -> content.joinToString("") { it.optString("text") }
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