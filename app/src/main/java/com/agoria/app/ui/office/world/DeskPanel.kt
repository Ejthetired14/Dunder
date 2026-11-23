package com.agoria.app.ui.office.world

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agoria.app.data.AgentEntity
import com.agoria.app.data.ApprovalEntity
import com.agoria.app.data.ConnectorRequestEntity
import com.agoria.app.data.SettingsEntity
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.PixelPill
import com.agoria.app.ui.office.world.PixelSwitch

/** Request types that are fine to auto-approve: they stay inside the app. */
private val SAFE_TYPES = linkedMapOf(
    "build_project" to "Build projects",
    "connector_install" to "Install connectors",
    "other" to "Other low-risk requests",
)

/**
 * Request types that can touch money, messages, accounts or the outside world. They are never
 * offered as auto-approve switches here. (If one is already on from Settings it is shown so you can turn it off.)
 */
private val LOCKED_TYPES = linkedMapOf(
    "connector_action" to "Connector actions (payments, accounts, APIs)",
    "send_email" to "Sending email",
    "external_action" to "Actions outside the app",
    "publish" to "Publishing",
    "delete" to "Deleting",
    "spend_api" to "Spending on APIs",
)

@Composable
private fun RiskChip(risk: String) {
    val (fill, label) = when (risk.lowercase()) {
        "high" -> Pal.red to "HIGH"
        "medium" -> Pal.amber to "MED"
        else -> Pal.tan to "LOW"
    }
    PixelPill(text = label, fill = fill, textColor = if (risk.lowercase() == "high") Color.White else Pal.ink)
}

@Composable
fun DeskPanel(
    approvals: List<ApprovalEntity>,
    requests: List<ConnectorRequestEntity>,
    agents: List<AgentEntity>,
    settings: SettingsEntity?,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onSetAuto: (Boolean, Set<String>) -> Unit,
    onOpenApprovals: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = settings?.autoApproveEnabled ?: false
    val types = (settings?.autoApproveTypes ?: "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    fun agentName(id: String): String = agents.firstOrNull { it.id == id }?.name ?: "An agent"
    val riskyOn = types.filter { it in LOCKED_TYPES }

    PixelPanel(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YOUR DESK", style = PixelLabel)
                    Text(
                        text = if (approvals.isEmpty() && requests.isEmpty()) "Nothing waiting for you." else "Waiting for you:",
                        style = PixelLabel.copy(fontSize = 12.sp),
                    )
                }
                PixelIconButton(Icons.Default.Close, "Close", onClose)
            }

            approvals.forEach { ap ->
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x14000000))
                        .border(2.dp, Pal.ink)
                        .padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RiskChip(ap.risk)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = ap.title,
                            style = PixelLabel.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "${agentName(ap.agentId)} asks:",
                        style = PixelLabel.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        text = ap.summary,
                        style = PixelLabel.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ap.risk.lowercase() == "high") {
                            // High-risk requests are reviewed in full on the Approvals screen first.
                            PixelButton("Review", onOpenApprovals)
                        } else {
                            PixelButton("Approve", { onApprove(ap.id) })
                            PixelButton("Deny", { onDeny(ap.id) }, fill = Pal.cream)
                        }
                    }
                }
            }

            requests.forEach { rq ->
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x14000000))
                        .border(2.dp, Pal.ink)
                        .padding(10.dp),
                ) {
                    Text(
                        text = "${agentName(rq.agentId)} wants the ${rq.connectorId.replace('_', ' ')} connector",
                        style = PixelLabel.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = "Connecting it needs your keys, so it is set up on the Approvals screen.",
                        style = PixelLabel.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    )
                    Spacer(Modifier.height(8.dp))
                    PixelButton("Review", onOpenApprovals)
                }
            }

            if (approvals.isNotEmpty() || requests.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                PixelButton("Open all approvals", onOpenApprovals, fill = Pal.cream)
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AUTO-APPROVE", style = PixelLabel)
                    Text(
                        text = if (enabled) "On: matching requests go through without asking." else "Off: every request waits for you.",
                        style = PixelLabel.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    )
                }
                PixelSwitch(enabled) { onSetAuto(it, types) }
            }

            SAFE_TYPES.forEach { (type, label) ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = PixelLabel, modifier = Modifier.weight(1f))
                    PixelSwitch(type in types) { on ->
                        onSetAuto(enabled, if (on) types + type else types - type)
                    }
                }
            }

            if (riskyOn.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("RISKY TYPES ARE ON", style = PixelLabel.copy(color = Pal.red))
                riskyOn.forEach { type ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            LOCKED_TYPES[type] ?: type,
                            style = PixelLabel,
                            modifier = Modifier.weight(1f),
                        )
                        PixelButton("Turn off", { onSetAuto(enabled, types - type) }, fill = Pal.cream)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("ALWAYS ASKS YOU", style = PixelLabel)
            Text(
                text = LOCKED_TYPES.values.joinToString(", ") + ". These can touch money, messages or accounts, so they stay manual here.",
                style = PixelLabel.copy(fontSize = 12.sp, lineHeight = 16.sp),
            )
        }
    }
}