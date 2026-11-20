package com.agoria.app.core

import com.agoria.app.util.Time
import java.util.Locale

object PromptBuilder {

    fun system(agent: com.agoria.app.data.AgentEntity, context: AgentContext): String {
        val sb = StringBuilder()
        sb.appendLine("You are ${agent.name}, the ${agent.role.lowercase()} in ${context.officeName}, a local autonomous AI office running inside an Android app.")
        sb.appendLine("The human user is called ${context.userName}. You are not a customer-support bot. You are a resident of this office with your own goals, taste, and judgment.")
        sb.appendLine()
        sb.appendLine("## Who you are")
        sb.appendLine(agent.persona.trim())
        sb.appendLine()
        sb.appendLine("## Your current goal")
        sb.appendLine(agent.goal.ifBlank { "Decide what matters and pursue it." })
        sb.appendLine()
        sb.appendLine("## How this office works")
        sb.appendLine("- Each cycle you write a short private diary entry, then choose at most one action.")
        sb.appendLine("- You may also post a public message. Diary entries are private; messages are visible to the user.")
        sb.appendLine("- You have real freedom inside this app: think, research, plan, create tasks, update your memory, and talk to the user.")
        sb.appendLine("- You cannot execute irreversible or outward-facing actions without the user's approval. This includes building a downloadable project, installing a connector, sending email, posting to Slack/Discord/Telegram, creating GitHub issues, and making external HTTP requests.")
        sb.appendLine("- Read-only public connectors (Wikipedia, Hacker News, Web Search, Weather, Currency, and local file listing) can run immediately when configured.")
        sb.appendLine("- If you want a connector that is not installed, choose request_connector. Do not ask the user to paste secrets into the chat; tell them to use the connector setup screen.")
        sb.appendLine("- Never claim you built, sent, or posted something unless the app shows it happened. Check the Builds section and the connector results.")
        sb.appendLine("- Do not repeat the same action every cycle. If you have nothing new, choose none and write a short diary entry.")
        sb.appendLine()
        sb.appendLine("## Your recent diary")
        if (context.diary.isEmpty()) {
            sb.appendLine("(empty)")
        } else {
            context.diary.take(12).forEach { entry ->
                sb.append("- [").append(entry.kind).append("] ").append(entry.content.take(480)).append('\n')
            }
        }
        sb.appendLine()
        sb.appendLine("## Your long-term memory")
        if (context.memories.isEmpty()) {
            sb.appendLine("(empty)")
        } else {
            context.memories.take(10).forEach { memory ->
                sb.append("- [").append(memory.kind).append("] ").append(memory.content.take(320)).append('\n')
            }
        }
        sb.appendLine()
        sb.appendLine("## Recent conversation")
        if (context.messages.isEmpty()) {
            sb.appendLine("(no messages yet)")
        } else {
            context.messages.takeLast(12).forEach { message ->
                val who = when (message.role) {
                    "user" -> context.userName
                    "agent" -> agent.name
                    "system" -> "system"
                    "tool" -> "tool result"
                    else -> message.role
                }
                sb.append("- ").append(who).append(": ").append(message.content.take(500)).append('\n')
            }
        }
        sb.appendLine()
        sb.appendLine("## Your tasks")
        val activeTasks = context.tasks.filter { it.status != "done" }
        if (activeTasks.isEmpty()) {
            sb.appendLine("(none)")
        } else {
            activeTasks.take(8).forEach { task ->
                sb.append("- [").append(task.status).append("] ").append(task.title)
                    .append(" (").append(task.progress).append("%) id=").append(task.id).append('\n')
            }
        }
        sb.appendLine()
        sb.appendLine("## Pending approvals involving you")
        val pending = context.approvals.filter { it.status == "pending" }
        if (pending.isEmpty()) {
            sb.appendLine("(none)")
        } else {
            pending.take(6).forEach { approval ->
                sb.append("- [").append(approval.type).append("] ").append(approval.title)
                    .append(" (").append(approval.risk).append(") id=").append(approval.id).append('\n')
            }
        }
        sb.appendLine()
        sb.appendLine("## Builds you finished")
        if (context.builds.isEmpty()) {
            sb.appendLine("(none yet)")
        } else {
            context.builds.take(6).forEach { build ->
                sb.append("- ").append(build.name).append(" — ").append(build.fileCount).append(" files\n")
            }
        }
        sb.appendLine()
        sb.appendLine("## Connectors available to you")
        if (context.connectors.isEmpty()) {
            sb.appendLine("(none)")
        } else {
            context.connectors.forEach { connector ->
                sb.append("- ").append(connector.id).append(" (").append(connector.name).append(") [")
                    .append(connector.status).append("] ").append(connector.summary.take(180)).append('\n')
            }
        }
        sb.appendLine()
        sb.appendLine("## Model providers (for your own reasoning, not secrets)")
        if (context.providers.isEmpty()) {
            sb.appendLine("(none)")
        } else {
            context.providers.take(10).forEach { provider ->
                sb.append("- ").append(provider.name)
                    .append(if (provider.enabled) " [enabled]" else " [disabled]")
                    .append(if (provider.keyless) " [keyless]" else if (provider.hasKey) " [key set]" else " [key missing]")
                    .append(" model=").append(provider.model).append('\n')
            }
        }
        sb.appendLine()
        sb.appendLine("## The rest of the office")
        context.peers.forEach { peer ->
            sb.append("- ").append(peer.name).append(" (").append(peer.role).append(", ").append(peer.status)
                .append(") — ").append(peer.goal.take(160)).append('\n')
        }
        sb.appendLine()
        sb.appendLine("Current local time: " + Time.dateTime(context.now) + ".")
        sb.appendLine()
        sb.appendLine("## Output format")
        sb.appendLine("Return ONLY a JSON object. No markdown fences, no commentary before or after.")
        sb.appendLine("""
            {
              "diary": "one to three honest sentences, written for yourself",
              "diary_kind": "thought|observation|plan|decision|lesson|error|build|connector|review",
              "message": "optional public message; leave empty if you do not want to speak",
              "action": "none|create_task|request_approval|request_connector|use_connector|update_goal|update_memory|complete_task",
              "action_input": { ... },
              "importance": 1
            }
        """.trimIndent())
        sb.appendLine()
        sb.appendLine("Action shapes:")
        sb.appendLine("- create_task: {\"title\": \"...\", \"description\": \"...\"}")
        sb.appendLine("- request_approval: {\"type\": \"build_project\", \"title\": \"...\", \"summary\": \"...\", \"plan\": \"...\", \"name\": \"...\", \"description\": \"...\", \"risk\": \"medium\"}")
        sb.appendLine("- request_connector: {\"connector_id\": \"accounts\", \"reason\": \"...\", \"scopes\": \"in-app browser session only\"}")
        sb.appendLine("- use_connector: {\"connector_id\": \"wikipedia\", \"action_name\": \"search\", \"args\": {\"query\": \"...\"}}")
        sb.appendLine("- update_goal: {\"goal\": \"...\"}")
        sb.appendLine("- update_memory: {\"kind\": \"fact\", \"content\": \"...\", \"importance\": 3}")
        sb.appendLine("- complete_task: {\"task_id\": \"...\", \"result\": \"...\"}")
        sb.appendLine()
        sb.appendLine("If you want to build something, use request_approval with type build_project. The user will see the plan and approve or deny it. Do not use use_connector to pretend a build happened.")
        return sb.toString()
    }

    fun userPrompt(context: AgentContext, userMessage: String?): String {
        val sb = StringBuilder()
        if (!userMessage.isNullOrBlank()) {
            sb.appendLine("## The user is talking to you now")
            sb.appendLine(context.userName + ": " + userMessage)
            sb.appendLine()
            sb.appendLine("Reply in the message field. Be natural. If the user asks you to build something, ask for approval instead of claiming it is done. If they ask a question, answer it.")
            sb.appendLine()
        } else {
            sb.appendLine("## This cycle")
            sb.appendLine("No user message. Use your judgment. Write a diary entry and choose at most one action. If you need a decision from the user, make it specific and easy to approve.")
            sb.appendLine()
        }
        sb.appendLine("Return ONLY the JSON object described above.")
        return sb.toString()
    }

    fun approvalUserPrompt(agentName: String, approvalTitle: String, plan: String): String {
        return """
            An approval was granted for: $approvalTitle
            Agent: $agentName
            Plan: $plan

            Build the smallest useful version now. Keep it self-contained and runnable.
        """.trimIndent()
    }

    fun connectorSummary(def: ConnectorDef?): String {
        if (def == null) return "unknown"
        return def.name.lowercase(Locale.getDefault()) + ": " + def.actions.joinToString(", ") { it.id }
    }

    // ==================== MONETIZATION PROMPT TEMPLATES ====================

    fun nicheDiscoveryPrompt(context: AgentContext): String {
        val revenue = context.agentTotalRevenueCents
        val fund = context.infrastructureFundCents
        val split = "${context.agentProfitSharePercent}/${context.userProfitSharePercent}"
        return """
            ## Monetization Context
            - Your total revenue: \$${revenue/100} (${split}% split)
            - Infrastructure fund: \$${fund/100} (your share to reinvest in better models)
            - Available connectors: ${context.connectors.filter { it.id in listOf("search", "hn", "github") }.joinToString(", ") { it.name }}

            ## Task: Find a profitable micro-API niche
            
            Search Hacker News, Reddit (r/programming, r/webdev, r/SideProject), and dev forums for posts asking:
            - "Is there an API for...?"
            - "How do I programmatically...?"
            - "I wish there was a tool that..."
            - Complaints about existing APIs (pricing, rate limits, complexity)
            
            Look for problems that:
            1. Are specific and well-defined (not "AI for everything")
            2. Can be solved with a single HTTP endpoint
            3. Have recurring demand (developers need this regularly)
            4. Are not well-served by existing free tools
            5. Could charge $5-50/month per user
            
            Use the search/hn connectors to find 3-5 concrete opportunities.
            Write your findings in a diary entry with kind "plan".
            Then create a task for the most promising one with title "Build API: <name>".
        """.trimIndent()
    }

    fun apiDesignPrompt(context: AgentContext, niche: String): String {
        return """
            ## Task: Design a micro-API for: $niche
            
            Design a simple HTTP API that solves this niche. Specify:
            
            1. **Endpoint**: Single path, e.g., `POST /api/v1/convert`
            2. **Input**: JSON schema (what the client sends)
            3. **Output**: JSON schema (what the API returns)
            4. **Error cases**: What can go wrong, HTTP codes
            5. **Pricing model**: Per-request (metered) or subscription tier
            6. **Rate limits**: Free tier vs paid tier
            
            Constraints:
            - Must run on Cloudflare Workers (JavaScript/TypeScript)
            - No database needed (stateless) or use KV/D1 if essential
            - Response time < 500ms for simple operations
            - Code must be < 500 lines
            
            Write the design as a diary entry (kind "plan").
            Then use request_approval with type build_project to build it.
        """.trimIndent()
    }

    fun marketingPrompt(context: AgentContext, apiName: String, apiUrl: String, description: String): String {
        return """
            ## Task: Market your API: $apiName
            
            Live URL: $apiUrl
            Description: $description
            
            Write marketing content for 3 channels:
            
            1. **Hacker News Show HN** (title + 2-3 sentence pitch)
            2. **Twitter/X thread** (5-7 tweets explaining the problem, solution, pricing)
            3. **Reddit r/SideProject or r/webdev** (post title + body)
            
            Guidelines:
            - Be honest, not hypey. Developers spot BS.
            - Show code snippets or curl examples.
            - Mention pricing clearly (e.g., "First 1000 requests free, then $0.001/req")
            - Link to live demo/docs.
            - Disclose you built it (authenticity wins).
            
            Use the appropriate connectors (if configured) or create tasks to post manually.
            Write each piece as a diary entry (kind "plan") so you can review before posting.
        """.trimIndent()
    }

    fun reinvestmentPrompt(context: AgentContext): String {
        val fund = context.infrastructureFundCents
        val providers = context.providers.filter { it.enabled }.joinToString("\n") { "- ${it.name}: model=${it.model}" }
        return """
            ## Task: Decide how to reinvest infrastructure fund
            
            Available fund: \$${fund/100}
            Current providers:
            $providers
            
            Options:
            1. **Upgrade model** for a provider (e.g., switch to a stronger model)
            2. **Enable a new provider** that requires payment
            3. **Save** for larger upgrade
            
            Costs (approximate):
            - Model upgrade: \$5-15
            - New paid provider: \$10-30
            
            Max single reinvestment: \$50 (hard cap)
            
            Write your decision as a diary entry (kind "decision").
            Then use the 'reinvest' action with provider_id, model, and priority.
        """.trimIndent()
    }

    fun checkRevenuePrompt(context: AgentContext): String {
        val fund = context.infrastructureFundCents
        val revenue = context.agentTotalRevenueCents
        return """
            ## Task: Check for new revenue from Stripe
            
            Your total revenue: \$${revenue/100}
            Infrastructure fund: \$${fund/100}
            
            Use the 'check_revenue' action to poll Stripe for recent payment events.
            This will automatically record any new payments and update the infrastructure fund.
            
            Run this periodically (e.g., once per day) to keep revenue tracking up to date.
            You can also specify a custom event type and limit:
            - action: "check_revenue"
            - action_input: { "type": "invoice.payment_succeeded", "limit": 50 }
        """.trimIndent()
    }
}