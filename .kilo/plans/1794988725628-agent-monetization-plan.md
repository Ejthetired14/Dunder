# Agent Monetization Plan

## Goal
Enable AI agents to autonomously generate revenue through micro-service/API businesses, with profits split between user and agent infrastructure fund.

## Core Concept
Agents build and deploy small, useful HTTP APIs (wrappers around LLMs, data transformations, niche tools) → charge per request via simple API keys → revenue funds better models/servers.

## Architecture Additions

### 1. New Connector: `stripe` or `payment`
- **Purpose**: Accept payments, manage subscriptions, issue API keys
- **Actions**: `create_customer`, `create_subscription`, `verify_key`, `usage_record`
- **Risk**: High (requires approval)

### 2. New Connector: `deploy` (Cloudflare Workers / Fly.io / Railway)
- **Purpose**: Deploy agent-built code as live HTTP endpoints
- **Actions**: `deploy_worker`, `get_logs`, `delete_worker`
- **Risk**: High (requires approval)

### 3. Revenue Tracking Entity
```kotlin
@Entity(tableName = "revenue")
data class RevenueEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val source: String,           // "api_usage", "subscription", "one_time"
    val amountCents: Long,
    val currency: String = "USD",
    val metadata: String,         // JSON: endpoint, customer, etc.
    val createdAt: Long
)
```

### 4. Profit Split Configuration (Settings)
```kotlin
// Add to SettingsEntity
val agentProfitSharePercent: Int = 50  // % to agent infrastructure fund
val userProfitSharePercent: Int = 50   // % to user
val infrastructureFundCents: Long = 0  // Accumulated fund
```

### 5. Agent Goal Template: "Build and Monetize Micro-API"
- Agents with this goal will:
  1. Identify a niche problem (via web search, HN, Reddit)
  2. Design a simple HTTP API solution
  3. Write code (using `build_project` action)
  4. Request deployment approval
  5. Request payment setup approval
  6. Market via content (Twitter, HN, dev forums) using connectors
  7. Track revenue, reinvest per split

### 6. New Agent Actions
- `deploy_service` - Package code + request deployment
- `setup_billing` - Configure Stripe product/pricing
- `record_revenue` - Log earnings (called after verified payment)
- `reinvest` - Use infrastructure fund to upgrade provider priority/model

## Implementation Phases

### Phase 1: Foundation (Week 1-2)
- [ ] Add `RevenueEntity` + DAO + Repository methods
- [ ] Add profit split fields to `SettingsEntity` + migration
- [ ] Create `PaymentConnector` (Stripe) + `DeployConnector` (Cloudflare Workers)
- [ ] Add `deploy_service` and `setup_billing` action handlers in `AgentRuntime`

### Phase 2: Agent Intelligence (Week 2-3)
- [ ] Add "micro_api_builder" agent persona/role in `SeedData`
- [ ] Create prompt templates for: niche discovery, API design, marketing copy
- [ ] Add `revenue` context to `AgentContext` (recent earnings, fund balance)
- [ ] Implement `reinvest` action: upgrade provider priority, enable better models

### Phase 3: Safety & Oversight (Week 3)
- [ ] Require approval for: deployment, billing setup, code changes to live services
- [ ] Add revenue dashboard screen (Settings → Revenue)
- [ ] Add notifications for: new revenue, fund threshold reached, deployment errors
- [ ] Implement spending limits on infrastructure fund

### Phase 4: Polish (Week 4)
- [ ] Test with 1-2 agents building real micro-APIs
- [ ] Document the workflow for user
- [ ] Add "Infrastructure Fund" visual in Home/Office screen

## Data Flow

```
Agent Cycle
    ↓
Identifies opportunity (web search, HN)
    ↓
Designs API → build_project (creates code zip)
    ↓
Request approval: deploy_service
    ↓ (user approves)
Deploy to Cloudflare Worker → gets live URL
    ↓
Request approval: setup_billing (Stripe product + price)
    ↓ (user approves)
Agent markets via content (Twitter connector, etc.)
    ↓
Users pay → Stripe webhook → record_revenue
    ↓
Split: 50% user wallet, 50% infrastructure_fund
    ↓
Agent sees fund balance → reinvest action → better models
```

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Agents deploy malicious code | All deployments require explicit user approval |
| Runaway spending | Hard cap on infrastructure fund per month |
| Stripe account issues | User owns Stripe account; app only uses restricted keys |
| Low-quality APIs | User approves each deployment; can disable agent autonomy |
| Legal/compliance | User responsible; app provides tools only |

## Open Questions

1. **Payment Processor**: Stripe (best DX) vs. Lemon Squeezy (MoR) vs. custom? → **Recommend Stripe**
2. **Deployment Target**: Cloudflare Workers (free tier, edge) vs. Fly.io (containers) vs. Railway? → **Recommend Cloudflare Workers**
3. **Profit Split**: Fixed 50/50 or configurable per agent? → **Start fixed, make configurable later**
4. **Minimum Viable Agent**: One "builder" agent or let any agent opt-in? → **Start with dedicated builder agent**

## Next Steps

1. Confirm payment processor & deployment target
2. Implement Phase 1 entities + connectors
3. Create first "API Builder" agent in SeedData
4. Test end-to-end with a simple "Hello World" paid API