package com.agoria.app.core

import com.agoria.app.data.AgentEntity
import com.agoria.app.data.Repository
import com.agoria.app.util.Time
import kotlinx.coroutines.flow.first

/**
 * Builds the agent context (diary, messages, tasks, connectors, providers, peers, revenue)
 * that is injected into the LLM prompt each cycle.
 *
 * Extracted from [AgentRuntime] as part of ARCH-1.
 */
class CycleContextBuilder(
    private val repo: Repository,
) {

    suspend fun build(agent: AgentEntity): AgentContext {
        val settings = repo.settings()
        val diary = repo.recentDiaryForAgent(agent.id, 18)
        val memories = repo.memoriesFor(agent.id).first().take(14)
        val messages = repo.messagesFor(agent.id).first().takeLast(14)
        val tasks = repo.tasksFor(agent.id).first()
        val approvals = repo.approvalsFor(agent.id).first()
        val builds = repo.buildsFor(agent.id).first()
        val allowed = agent.allowedConnectors.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val connectors = repo.connectors.first()
            .filter { it.status != "disabled" && (allowed.isEmpty() || it.id in allowed) }
            .map { connector ->
                val def = ConnectorCatalog.get(connector.id)
                ConnectorBrief(
                    id = connector.id,
                    name = connector.name,
                    status = connector.status,
                    summary = def?.description ?: "Custom connector.",
                    ready = connector.status == "ready" || connector.status == "configured",
                )
            }

        val providers = repo.providers.first().map { provider ->
            val spec = ProviderCatalog.byId[provider.id]
            ProviderBrief(
                id = provider.id,
                name = spec?.name ?: provider.id,
                enabled = provider.enabled,
                hasKey = !provider.apiKeyEnc.isNullOrBlank(),
                keyless = spec?.keyRequired == false,
                model = provider.model.ifBlank { spec?.defaultModel ?: "" },
                lastStatus = provider.lastStatus,
            )
        }

        val peers = repo.agents.first().filter { it.id != agent.id }.map {
            PeerInfo(
                name = it.name,
                role = it.role,
                status = it.status,
                goal = it.goal,
            )
        }

        val chatter = repo.recentSayEntries(50)
            .filter { it.kind == "say" }
            .mapNotNull { e ->
                val a = repo.agent(e.agentId) ?: return@mapNotNull null
                val m = AgentPolicy.SAY_REGEX.find(e.content.trim()) ?: return@mapNotNull null
                ChatterLine(a.name, m.groupValues[1].trim(), m.groupValues[2].trim(), e.createdAt)
            }
            .filter { it.fromName == agent.name || it.toName.equals(agent.name, ignoreCase = true) }
            .sortedByDescending { it.at }
            .take(8)
            .reversed()
        val agentRevenue = repo.totalRevenueForAgent(agent.id)
        val infraFund = repo.getInfrastructureFund()

        return AgentContext(
            agent = agent,
            officeName = settings.officeName,
            userName = settings.userName,
            now = Time.now(),
            diary = diary,
            memories = memories,
            messages = messages,
            tasks = tasks,
            approvals = approvals,
            builds = builds,
            connectors = connectors,
            providers = providers,
            peers = peers,
            agentTotalRevenueCents = agentRevenue,
            infrastructureFundCents = infraFund,
            agentProfitSharePercent = settings.agentProfitSharePercent,
            userProfitSharePercent = settings.userProfitSharePercent,
            chatter = chatter,
        )
    }
}
