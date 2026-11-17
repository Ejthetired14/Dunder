package com.agoria.app.core

import java.util.Locale

data class ModelSpec(
    val id: String,
    val label: String = id,
    val context: String = "",
    val modality: String = "Text",
    val note: String = "",
)

data class ProviderSpec(
    val id: String,
    val name: String,
    val baseUrl: String,
    val keyRequired: Boolean,
    val keyLabel: String,
    val keyHint: String,
    val docsUrl: String,
    val freeNote: String,
    val models: List<ModelSpec>,
    val defaultModel: String,
    val needsExtra: Boolean = false,
    val extraLabel: String = "",
    val extraHint: String = "",
    val extraPlaceholder: String = "",
    val staticHeaders: Map<String, String> = emptyMap(),
    val category: String = "Inference",
)

object ProviderCatalog {

    val all: List<ProviderSpec> = listOf(
        ProviderSpec(
            id = "kilo",
            name = "Kilo Code Gateway",
            baseUrl = "https://api.kilo.ai/api/gateway",
            keyRequired = false,
            keyLabel = "API key (optional)",
            keyHint = "Leave blank to use the free pool. Sign in at app.kilo.ai/profile for a higher limit.",
            docsUrl = "https://kilo.ai/docs/gateway/authentication",
            freeNote = "No key required. 200 requests/hour per IP. Free models may log prompts.",
            models = listOf(
                ModelSpec("kilo-auto/free", "kilo-auto/free", "256K", "Text", "Auto-router"),
                ModelSpec("nvidia/nemotron-3-ultra-550b-a55b:free", "Nemotron 3 Ultra (free)", "1M", "Text"),
                ModelSpec("openrouter/free", "OpenRouter Free Router", "Varies", "Text"),
                ModelSpec("cohere/north-mini-code:free", "North Mini Code (free)", "256K", "Text (code)"),
                ModelSpec("dots-studio/dots-3-note-preview:free", "Dots 3 Note (free)", "512K", "Text + Vision"),
            ),
            defaultModel = "kilo-auto/free",
            category = "Gateway",
        ),
        ProviderSpec(
            id = "ovh",
            name = "OVHcloud AI Endpoints",
            baseUrl = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1",
            keyRequired = false,
            keyLabel = "API key (optional)",
            keyHint = "Leave blank for the anonymous EU tier: 2 requests/minute per model, no signup.",
            docsUrl = "https://www.ovhcloud.com/en/public-cloud/ai-endpoints/catalog/",
            freeNote = "Anonymous free tier. 2 RPM per IP per model. EU-hosted open-weight models.",
            models = listOf(
                ModelSpec("gpt-oss-120b", "gpt-oss-120b", "131K", "Text"),
                ModelSpec("gpt-oss-20b", "gpt-oss-20b", "131K", "Text"),
                ModelSpec("Qwen3.5-397B-A17B", "Qwen3.5 397B", "262K", "Text + Vision"),
                ModelSpec("Meta-Llama-3_3-70B-Instruct", "Llama 3.3 70B", "131K", "Text"),
                ModelSpec("Mistral-Small-3.2-24B-Instruct", "Mistral Small 3.2", "128K", "Text + Vision"),
            ),
            defaultModel = "gpt-oss-120b",
            category = "Inference",
        ),
        ProviderSpec(
            id = "llm7",
            name = "LLM7.io",
            baseUrl = "https://api.llm7.io/v1",
            keyRequired = false,
            keyLabel = "API token (optional)",
            keyHint = "Anonymous access works without a token. A free token from token.llm7.io raises limits.",
            docsUrl = "https://docs.llm7.io/limits",
            freeNote = "Anonymous or free-token tier. 60 RPM, 250 requests/hour with a free token.",
            models = listOf(
                ModelSpec("gpt-oss:20b", "gpt-oss:20b", "128K", "Text"),
                ModelSpec("mistral-Nemo-Instruct-2407", "Mistral Nemo Instruct", "128K", "Text"),
                ModelSpec("codestral-latest", "Codestral", "32K", "Text (code)"),
            ),
            defaultModel = "gpt-oss:20b",
            category = "Gateway",
        ),
        ProviderSpec(
            id = "groq",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            keyRequired = true,
            keyLabel = "API key",
            keyHint = "Free key at console.groq.com/keys. 30 RPM, 1,000 requests/day on the free plan.",
            docsUrl = "https://console.groq.com/keys",
            freeNote = "Free tier, no credit card. Ultra-fast LPU inference. Rate limits vary by model.",
            models = listOf(
                ModelSpec("openai/gpt-oss-120b", "gpt-oss-120b", "131K", "Text"),
                ModelSpec("openai/gpt-oss-20b", "gpt-oss-20b", "131K", "Text"),
                ModelSpec("qwen/qwen3.8-27b", "Qwen3.8 27B", "131K", "Text + Image"),
            ),
            defaultModel = "openai/gpt-oss-120b",
            category = "Inference",
        ),
        ProviderSpec(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            keyRequired = true,
            keyLabel = "API key",
            keyHint = "Free key at openrouter.ai/keys. Free models: 20 RPM and 50 requests/day across all free models.",
            docsUrl = "https://openrouter.ai/keys",
            freeNote = "OpenAI-compatible. Free models use a :free suffix. Free providers may log prompts.",
            models = listOf(
                ModelSpec("openrouter/free", "Free router", "Varies", "Text"),
                ModelSpec("cohere/north-mini-code:free", "North Mini Code", "256K", "Text (code)"),
                ModelSpec("google/gemma-4-31b-it:free", "Gemma 4 31B", "262K", "Text + Image + Video"),
                ModelSpec("nvidia/nemotron-3-ultra-550b-a55b:free", "Nemotron 3 Ultra", "1M", "Text"),
            ),
            defaultModel = "openrouter/free",
            staticHeaders = mapOf(
                "HTTP-Referer" to "https://github.com/agoria-app/agoria",
                "X-Title" to "Agoria",
            ),
            category = "Gateway",
        ),
        ProviderSpec(
            id = "gemini",
            name = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            keyRequired = true,
            keyLabel = "API key",
            keyHint = "Free key at aistudio.google.com/app/apikey. Free-tier prompts may be used to improve Google products.",
            docsUrl = "https://aistudio.google.com/app/apikey",
            freeNote = "Free tier, no credit card. Context up to 1M tokens on supported models.",
            models = listOf(
                ModelSpec("gemini-2.5-flash", "Gemini 2.5 Flash", "1M", "Text + Image + Audio + Video"),
                ModelSpec("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", "1M", "Text + Image + Audio + Video"),
                ModelSpec("gemini-3.8-flash", "Gemini 3.8 Flash", "1M", "Text + Image + Audio + Video"),
                ModelSpec("gemma-4-31b", "Gemma 4 31B", "256K", "Text + Image"),
            ),
            defaultModel = "gemini-2.5-flash",
            category = "Inference",
        ),
        ProviderSpec(
            id = "cohere",
            name = "Cohere",
            baseUrl = "https://api.cohere.ai/compatibility/v1",
            keyRequired = true,
            keyLabel = "API key",
            keyHint = "Free trial key at dashboard.cohere.com/api-keys. 1,000 calls/month, non-commercial use only.",
            docsUrl = "https://dashboard.cohere.com/api-keys",
            freeNote = "Trial key. 20 RPM, 1,000 API calls/month. Non-commercial use only.",
            models = listOf(
                ModelSpec("command-r7b-12-2024", "Command R7B", "128K", "Text"),
                ModelSpec("command-r-08-2024", "Command R", "128K", "Text"),
                ModelSpec("command-a-03-2025", "Command A", "256K", "Text"),
            ),
            defaultModel = "command-r7b-12-2024",
            category = "Inference",
        ),
        ProviderSpec(
            id = "cloudflare",
            name = "Cloudflare Workers AI",
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1",
            keyRequired = true,
            keyLabel = "API token",
            keyHint = "Create a Workers AI token at dash.cloudflare.com/profile/api-tokens.",
            docsUrl = "https://dash.cloudflare.com/profile/api-tokens",
            freeNote = "10,000 Neurons/day free, shared across all models. Resets at 00:00 UTC.",
            models = listOf(
                ModelSpec("@cf/meta/llama-3.3-70b-instruct-fp8-fast", "Llama 3.3 70B", "24K", "Text"),
                ModelSpec("@cf/openai/gpt-oss-120b", "gpt-oss-120b", "128K", "Text"),
                ModelSpec("@cf/google/gemma-4-26b-a4b-it", "Gemma 4 26B", "256K", "Text + Vision"),
                ModelSpec("@cf/zai-org/glm-4.7-flash", "GLM 4.7 Flash", "131K", "Text"),
            ),
            defaultModel = "@cf/openai/gpt-oss-120b",
            needsExtra = true,
            extraLabel = "Account ID",
            extraHint = "Find it in the Cloudflare dashboard URL: dash.cloudflare.com/<account_id>/...",
            extraPlaceholder = "32-character account id",
            category = "Inference",
        ),
        ProviderSpec(
            id = "nvidia",
            name = "NVIDIA NIM",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            keyRequired = true,
            keyLabel = "API key",
            keyHint = "Free with an NVIDIA Developer Program membership at build.nvidia.com.",
            docsUrl = "https://build.nvidia.com/explore/discover",
            freeNote = "100+ models. Free for development and testing with rate limits per model.",
            models = listOf(
                ModelSpec("openai/gpt-oss-20b", "gpt-oss-20b", "131K", "Text"),
                ModelSpec("nvidia/nemotron-3-ultra-550b-a55b", "Nemotron 3 Ultra", "1M", "Text"),
                ModelSpec("nvidia/nemotron-3.5-super-vl", "Nemotron 3.5 Super VL", "1M", "Multimodal"),
            ),
            defaultModel = "openai/gpt-oss-20b",
            category = "Inference",
        ),
        ProviderSpec(
            id = "zai",
            name = "Z AI (Zhipu)",
            baseUrl = "https://api.z.ai/api/paas/v4",
            keyRequired = true,
            keyLabel = "API key",
            keyHint = "Get a key at open.bigmodel.cn/usercenter/apikeys or z.ai. GLM Flash models are free.",
            docsUrl = "https://docs.z.ai/guides/overview/pricing.md",
            freeNote = "Permanent free GLM Flash models. 1 concurrent request on the free tier.",
            models = listOf(
                ModelSpec("glm-4.7-flash", "GLM 4.7 Flash", "200K", "Text (reasoning)"),
                ModelSpec("glm-4.6v-flash", "GLM 4.6V Flash", "128K", "Multimodal"),
            ),
            defaultModel = "glm-4.7-flash",
            category = "Inference",
        ),
        ProviderSpec(
            id = "aionlabs",
            name = "Aion Labs",
            baseUrl = "https://api.aionlabs.ai/v1",
            keyRequired = true,
            keyLabel = "API key",
            keyHint = "Get a key at aionlabs.ai/app/api-keys. 15 RPM, 20K tokens/day free.",
            docsUrl = "https://www.aionlabs.ai/app/api-keys/",
            freeNote = "Permanent free tier, no credit card. 15 RPM, 20K tokens/day.",
            models = listOf(
                ModelSpec("aion-labs/aion-3.0", "Aion 3.0", "128K", "Text (reasoning)"),
                ModelSpec("aion-labs/aion-3.0-mini", "Aion 3.0 Mini", "128K", "Text (reasoning)"),
            ),
            defaultModel = "aion-labs/aion-3.0",
            category = "Inference",
        ),
        ProviderSpec(
            id = "gonka",
            name = "Gonka Broker",
            baseUrl = "https://proxy.gonkabroker.com/v1",
            keyRequired = true,
            keyLabel = "API key",
            keyHint = "Phone verification required at app.gonkabroker.com/signup. ~1M tokens/month free.",
            docsUrl = "https://app.gonkabroker.com/signup",
            freeNote = "Free monthly grant after SMS verification. Model lineup rotates; check /v1/models.",
            models = listOf(
                ModelSpec("MiniMaxAI/MiniMax-M2.7", "MiniMax M2.7", "200K", "Text (reasoning)"),
                ModelSpec("deepseek-ai/DeepSeek-V4-Flash-0731", "DeepSeek V4 Flash", "400K", "Text (reasoning)"),
                ModelSpec("zai-org/GLM-5.3-Flash", "GLM 5.3 Flash", "400K", "Text (reasoning)"),
            ),
            defaultModel = "MiniMaxAI/MiniMax-M2.7",
            category = "Gateway",
        ),
        ProviderSpec(
            id = "siliconflow",
            name = "SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            keyRequired = true,
            keyLabel = "API key",
            keyHint = "Requires real-name verification. Get a key at cloud.siliconflow.cn/account/ak.",
            docsUrl = "https://cloud.siliconflow.cn/account/ak",
            freeNote = "Permanently free models. Identity verification required; international support may be limited.",
            models = listOf(
                ModelSpec("tencent/Hunyuan-MT-7B", "Hunyuan MT 7B", "", "Text"),
                ModelSpec("XingChenAGI/Xing4.0-29B", "Xing 4.0 29B", "", "Text"),
            ),
            defaultModel = "tencent/Hunyuan-MT-7B",
            category = "Inference",
        ),
        ProviderSpec(
            id = "modelscope",
            name = "ModelScope",
            baseUrl = "https://api-inference.modelscope.cn/v1",
            keyRequired = true,
            keyLabel = "API key",
            keyHint = "Requires an Alibaba Cloud account binding and real-name verification.",
            docsUrl = "https://modelscope.cn/my/myaccesstoken",
            freeNote = "2,000 requests/day total, with dynamic per-model quotas. Mainland China verification required.",
            models = listOf(
                ModelSpec("Qwen/Qwen3.5-35B-A3B", "Qwen3.5 35B A3B", "256K", "Text"),
                ModelSpec("Qwen/Qwen3.5-27B", "Qwen3.5 27B", "256K", "Text"),
            ),
            defaultModel = "Qwen/Qwen3.5-35B-A3B",
            category = "Inference",
        ),
    )

    val byId: Map<String, ProviderSpec> = all.associateBy { it.id }

    val keylessDefaultIds: Set<String> = setOf("kilo", "ovh", "llm7")

    val defaultEnabledIds: Set<String> = setOf(
        "kilo", "ovh", "llm7", "groq", "openrouter", "gemini", "cohere", "cloudflare", "nvidia", "zai",
    )

    fun defaultPriority(id: String): Int {
        val order = listOf(
            "kilo" to 10,
            "groq" to 20,
            "openrouter" to 30,
            "gemini" to 40,
            "cloudflare" to 50,
            "ovh" to 60,
            "llm7" to 70,
            "nvidia" to 80,
            "zai" to 90,
            "cohere" to 100,
            "aionlabs" to 110,
            "gonka" to 120,
            "siliconflow" to 130,
            "modelscope" to 140,
        )
        return order.firstOrNull { it.first == id }?.second ?: 999
    }

    fun displayStatus(lastStatus: String?): String = when (lastStatus) {
        "ok" -> "Healthy"
        "error" -> "Failing"
        "untested" -> "Not tested"
        null -> "Not tested"
        else -> lastStatus.replaceFirstChar { it.uppercase(Locale.getDefault()) }
    }
}