package com.agoria.app.core.connectors

import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.Repository
import com.agoria.app.util.Json
import org.json.JSONArray
import org.json.JSONObject

/** Stripe connector — products, prices and event listing. */

class StripeHandler(
    private val repo: Repository,
) : ConnectorHandler {
    override val connectorId = "stripe"
    override val description = "Stripe — create products, prices and list events."
    override val risk = "high"

    override fun describeActions(): String =
        "stripe actions:\n- create_product: Create a product. args {\"name\": \"...\", \"description\": \"...\"}\n- create_price: Create a price. args {\"productId\": \"...\", \"amount_cents\": 1000, \"interval\": \"month\"}\n- list_events: List recent events. args {\"type\": \"invoice.payment_succeeded\", \"limit\": 20}\n- verify_key: Verify the secret key."

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "create_product", "product" -> "create_product"
            "create_price", "price" -> "create_price"
            "list_events", "events" -> "list_events"
            "verify_key", "verify" -> "verify_key"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val key = config?.optString("secretKey")?.trim()
        if (key.isNullOrBlank()) return "secret key"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val secretKey = config?.optString("secretKey") ?: return HandlerResult(false, "Missing Stripe secret key")
        val headers = mapOf("Authorization" to "Bearer $secretKey", "Content-Type" to "application/x-www-form-urlencoded")
        return when (action) {
            "create_product" -> {
                val name = args.optString("name").trim()
                val description = args.optString("description", "").trim()
                if (name.isBlank()) return HandlerResult(false, "Missing product name.")
                val body = "name=" + ConnectorHttp.enc(name) + "&description=" + ConnectorHttp.enc(description)
                val response = ConnectorHttp.http("POST", "https://api.stripe.com/v1/products", headers, body)
                if (!response.ok) return HandlerResult(false, "Stripe ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Created product: ${ConnectorHttp.text(response.body, 500)}")
            }
            "create_price" -> {
                val productId = args.optString("productId").trim()
                val amountCents = args.optLong("amount_cents", 0L)
                val interval = args.optString("interval", "month").trim()
                if (productId.isBlank() || amountCents <= 0) return HandlerResult(false, "Missing productId or amount_cents.")
                val body = "product=" + ConnectorHttp.enc(productId) +
                    "&unit_amount=$amountCents&currency=usd&recurring[interval]=$interval"
                val response = ConnectorHttp.http("POST", "https://api.stripe.com/v1/prices", headers, body)
                if (!response.ok) return HandlerResult(false, "Stripe ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Created price: ${ConnectorHttp.text(response.body, 500)}")
            }
            "list_events" -> {
                val eventType = args.optString("type", "invoice.payment_succeeded")
                val limit = args.optInt("limit", 20)
                val url = "https://api.stripe.com/v1/events?type=$eventType&limit=$limit"
                val response = ConnectorHttp.http("GET", url, headers)
                if (!response.ok) return HandlerResult(false, "Stripe ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Events: ${ConnectorHttp.text(response.body, 1000)}")
            }
            "verify_key" -> {
                val response = ConnectorHttp.http("GET", "https://api.stripe.com/v1/account", headers)
                if (!response.ok) return HandlerResult(false, "Stripe key invalid: ${response.code} ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Stripe key verified.")
            }
            else -> HandlerResult(false, "Unknown Stripe action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Cloudflare Workers connector — deploy workers and webhook handlers. */

class CloudflareWorkersHandler : ConnectorHandler {
    override val connectorId = "cloudflare_workers"
    override val description = "Cloudflare Workers — deploy serverless functions."
    override val risk = "high"

    override fun describeActions(): String =
        "cloudflare_workers actions:\n- deploy: Deploy a worker. args {\"name\": \"...\", \"code\": \"...\", \"routes\": [...]}\n- deploy_webhook_handler: Deploy a Stripe webhook handler."

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "deploy" -> "deploy"
            "deploy_webhook_handler" -> "deploy_webhook_handler"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val token = config?.optString("apiToken")?.trim()
        val accountId = config?.optString("accountId")?.trim()
        if (token.isNullOrBlank()) return "API token"
        if (accountId.isNullOrBlank()) return "account ID"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val token = config?.optString("apiToken") ?: return HandlerResult(false, "Missing Cloudflare API token")
        val accountId = config?.optString("accountId") ?: return HandlerResult(false, "Missing Cloudflare account ID")
        val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        return when (action) {
            "deploy" -> {
                val name = args.optString("name").trim()
                val code = args.optString("code").trim()
                val routes = args.optJSONArray("routes") ?: org.json.JSONArray()
                if (name.isBlank() || code.isBlank()) return HandlerResult(false, "Missing name or code.")
                if (!validWorkerName(name)) return HandlerResult(false, "Invalid worker name: alphanumeric + hyphen only.")
                val payload = JSONObject().put("name", name).put("code", code).put("routes", routes)
                val response = ConnectorHttp.http("PUT", "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$name", headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "Cloudflare ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Worker '$name' deployed.")
            }
            "deploy_webhook_handler" -> {
                val name = args.optString("name").trim()
                val secret = args.optString("stripeWebhookSecret").trim()
                val callbackUrl = args.optString("appCallbackUrl").trim()
                if (name.isBlank() || secret.isBlank() || callbackUrl.isBlank()) return HandlerResult(false, "Missing name, stripeWebhookSecret or appCallbackUrl.")
                if (!validWorkerName(name)) return HandlerResult(false, "Invalid worker name: alphanumeric + hyphen only.")
                val code = """
                    addEventListener('fetch', event => {
                      event.respondWith(handleRequest(event.request))
                    })
                    async function handleRequest(request) {
                      if (request.method !== 'POST') return new Response('Method not allowed', {status: 405})
                      const signature = request.headers.get('Stripe-Signature') || ''
                      const body = await request.text()
                      const payload = JSON.stringify({event: body, signature: signature})
                      const resp = await fetch('$callbackUrl', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: payload
                      })
                      return new Response(resp.body, {status: resp.status})
                    }
                """.trimIndent()
                val routes = JSONArray().put("https://stripe-webhook-${name.lowercase().replace(" ", "-")}.workers.dev/*")
                val payload = JSONObject().put("name", name).put("code", code).put("routes", routes)
                val response = ConnectorHttp.http("PUT", "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$name", headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "Cloudflare ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Webhook handler '$name' deployed.")
            }
            else -> HandlerResult(false, "Unknown Cloudflare Workers action '$action'")
        }
    }

    /** Cloudflare worker names: alphanumeric + hyphen only. */
    private fun validWorkerName(name: String): Boolean = name.matches(Regex("^[a-zA-Z0-9-]+$"))

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Supabase connector — database queries. */

class SupabaseHandler : ConnectorHandler {
    override val connectorId = "supabase"
    override val description = "Supabase — query tables via REST API."
    override val risk = "medium"

    override fun describeActions(): String =
        "supabase actions:\n- select: Query a table. args {\"table\": \"...\", \"filter\": \"...\"}\n- insert: Insert a row. args {\"table\": \"...\", \"data\": {...}}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "select", "query", "read" -> "select"
            "insert", "create", "write" -> "insert"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val url = config?.optString("url")?.trim()
        val key = config?.optString("anonKey")?.trim()
        if (url.isNullOrBlank()) return "URL"
        if (key.isNullOrBlank()) return "anon key"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val url = config?.optString("url") ?: return HandlerResult(false, "Missing Supabase URL")
        val key = config?.optString("anonKey") ?: return HandlerResult(false, "Missing Supabase anon key")
        val headers = mapOf("apikey" to key, "Authorization" to "Bearer $key", "Content-Type" to "application/json")
        return when (action) {
            "select" -> {
                val table = args.optString("table").trim()
                if (table.isBlank()) return HandlerResult(false, "Missing table.")
                val filter = args.optString("filter", "").trim()
                val queryUrl = "$url/rest/v1/$table${if (filter.isNotBlank()) "?$filter" else ""}"
                val response = ConnectorHttp.http("GET", queryUrl, headers)
                if (!response.ok) return HandlerResult(false, "Supabase ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Selected from $table: ${ConnectorHttp.text(response.body, 500)}")
            }
            "insert" -> {
                val table = args.optString("table").trim()
                val data = Json.obj(args, "data") ?: JSONObject()
                if (table.isBlank() || data.length() == 0) return HandlerResult(false, "Missing table or data.")
                val response = ConnectorHttp.http("POST", "$url/rest/v1/$table", headers, data.toString())
                if (!response.ok) return HandlerResult(false, "Supabase ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Inserted into $table: ${ConnectorHttp.text(response.body, 500)}")
            }
            else -> HandlerResult(false, "Unknown Supabase action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Resend connector — send email. */

class ResendHandler : ConnectorHandler {
    override val connectorId = "resend"
    override val description = "Resend — send transactional email."
    override val risk = "medium"

    override fun describeActions(): String =
        "resend actions:\n- send_email: Send an email. args {\"to\": \"...\", \"subject\": \"...\", \"body\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "send_email", "send", "email" -> "send_email"
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
        val apiKey = config?.optString("apiKey") ?: return HandlerResult(false, "Missing Resend API key")
        val headers = mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json")
        return when (action) {
            "send_email" -> {
                val to = args.optString("to").trim()
                val subject = args.optString("subject").trim()
                val body = args.optString("body").trim()
                if (to.isBlank() || subject.isBlank() || body.isBlank()) return HandlerResult(false, "Missing to, subject or body.")
                val payload = JSONObject().put("from", "onboarding@resend.dev").put("to", to).put("subject", subject).put("html", body)
                val response = ConnectorHttp.http("POST", "https://api.resend.com/emails", headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "Resend ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Email sent to $to.")
            }
            else -> HandlerResult(false, "Unknown Resend action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Upstash connector — Redis and vector operations. */

class UpstashHandler : ConnectorHandler {
    override val connectorId = "upstash"
    override val description = "Upstash — serverless Redis and vector database."
    override val risk = "medium"

    override fun describeActions(): String =
        "upstash actions:\n- get: Get a key. args {\"key\": \"...\"}\n- set: Set a key. args {\"key\": \"...\", \"value\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "get", "read" -> "get"
            "set", "write" -> "set"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val url = config?.optString("url")?.trim()
        val token = config?.optString("token")?.trim()
        if (url.isNullOrBlank()) return "URL"
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
        val url = config?.optString("url") ?: return HandlerResult(false, "Missing Upstash URL")
        val token = config?.optString("token") ?: return HandlerResult(false, "Missing Upstash token")
        val headers = mapOf("Authorization" to "Bearer $token")
        return when (action) {
            "get" -> {
                val key = args.optString("key").trim()
                if (key.isBlank()) return HandlerResult(false, "Missing key.")
                val response = ConnectorHttp.http("GET", "$url/get/$key", headers)
                if (!response.ok) return HandlerResult(false, "Upstash ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Got $key: ${ConnectorHttp.text(response.body, 500)}")
            }
            "set" -> {
                val key = args.optString("key").trim()
                val value = args.optString("value").trim()
                if (key.isBlank() || value.isBlank()) return HandlerResult(false, "Missing key or value.")
                val payload = JSONObject().put("key", key).put("value", value)
                val response = ConnectorHttp.http("POST", "$url/set", headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "Upstash ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Set $key = $value.")
            }
            else -> HandlerResult(false, "Unknown Upstash action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Hugging Face connector — model inference. */

class HuggingFaceHandler : ConnectorHandler {
    override val connectorId = "huggingface"
    override val description = "Hugging Face — run inference on public models."
    override val risk = "low"

    override fun describeActions(): String =
        "huggingface actions:\n- infer: Run inference on a model. args {\"model\": \"...\", \"inputs\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "infer", "predict", "run" -> "infer"
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
        val model = args.optString("model").trim()
        val inputs = args.optString("inputs").trim()
        if (model.isBlank() || inputs.isBlank()) return HandlerResult(false, "Missing model or inputs.")
        val token = config?.optString("apiToken")?.trim()
        val headers: Map<String, String> = if (token != null && token.isNotBlank()) {
            mapOf("Authorization" to "Bearer $token")
        } else {
            emptyMap()
        }
        val response = ConnectorHttp.http("POST", "https://api-inference.huggingface.co/models/$model", headers, inputs)
        if (!response.ok) return HandlerResult(false, "Hugging Face ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
        HandlerResult(true, "Inference result: ${ConnectorHttp.text(response.body, 1000)}")
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Vercel connector — deployments. */

class VercelHandler : ConnectorHandler {
    override val connectorId = "vercel"
    override val description = "Vercel — create deployments."
    override val risk = "medium"

    override fun describeActions(): String =
        "vercel actions:\n- deploy: Create a deployment. args {\"name\": \"...\", \"files\": [...]}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "deploy" -> "deploy"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val token = config?.optString("apiToken")?.trim()
        if (token.isNullOrBlank()) return "API token"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val token = config?.optString("apiToken") ?: return HandlerResult(false, "Missing Vercel API token")
        val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        return when (action) {
            "deploy" -> {
                val name = args.optString("name").trim()
                if (name.isBlank()) return HandlerResult(false, "Missing name.")
                val payload = JSONObject().put("name", name)
                val response = ConnectorHttp.http("POST", "https://api.vercel.com/v13/deployments", headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "Vercel ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Deployment created: ${ConnectorHttp.text(response.body, 500)}")
            }
            else -> HandlerResult(false, "Unknown Vercel action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Netlify connector — site deploys. */

class NetlifyHandler : ConnectorHandler {
    override val connectorId = "netlify"
    override val description = "Netlify — trigger site builds and deploys."
    override val risk = "medium"

    override fun describeActions(): String =
        "netlify actions:\n- deploy: Trigger a deploy. args {\"site_id\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "deploy" -> "deploy"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? {
        val token = config?.optString("apiToken")?.trim()
        if (token.isNullOrBlank()) return "API token"
        return null
    }

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult {
        val token = config?.optString("apiToken") ?: return HandlerResult(false, "Missing Netlify API token")
        val headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        return when (action) {
            "deploy" -> {
                val siteId = args.optString("site_id").trim()
                if (siteId.isBlank()) return HandlerResult(false, "Missing site_id.")
                val response = ConnectorHttp.http("POST", "https://api.netlify.com/api/v1/sites/$siteId/builds", headers, "{}")
                if (!response.ok) return HandlerResult(false, "Netlify ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Deploy triggered for $siteId.")
            }
            else -> HandlerResult(false, "Unknown Netlify action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}

/** Linear connector — create issues. */

class LinearHandler : ConnectorHandler {
    override val connectorId = "linear"
    override val description = "Linear — create issues and manage projects."
    override val risk = "medium"

    override fun describeActions(): String =
        "linear actions:\n- create_issue: Create an issue. args {\"title\": \"...\", \"description\": \"...\", \"team_id\": \"...\"}"

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "create_issue", "create", "issue" -> "create_issue"
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
        val apiKey = config?.optString("apiKey") ?: return HandlerResult(false, "Missing Linear API key")
        val headers = mapOf("Authorization" to apiKey, "Content-Type" to "application/json")
        return when (action) {
            "create_issue" -> {
                val title = args.optString("title").trim()
                val description = args.optString("description").trim()
                val teamId = args.optString("team_id").trim()
                if (title.isBlank() || teamId.isBlank()) return HandlerResult(false, "Missing title or team_id.")
                val payload = JSONObject().put("query", """
                    mutation { issueCreate(input: {title: "$title", description: "$description", teamId: "$teamId"}) { issue { id number title } } }
                """.trimIndent())
                val response = ConnectorHttp.http("POST", "https://api.linear.app/graphql", headers, payload.toString())
                if (!response.ok) return HandlerResult(false, "Linear ${response.code}: ${ConnectorHttp.text(response.body, 240)}")
                HandlerResult(true, "Issue created: ${ConnectorHttp.text(response.body, 500)}")
            }
            else -> HandlerResult(false, "Unknown Linear action '$action'")
        }
    }

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}
