package com.agoria.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.agoria.app.R
import com.agoria.app.data.AgentEntity

fun statusColor(status: String): Color {
    return when (status) {
        "thinking" -> Color(0xFFF2B84B)
        "working" -> Color(0xFF5FD3C4)
        "waiting_approval" -> Color(0xFFB69CFF)
        "paused" -> Color(0xFF8B93A3)
        "sleeping" -> Color(0xFF6B7280)
        "error" -> Color(0xFFFF6B6B)
        else -> Color(0xFF7C8798)
    }
}

fun statusLabel(status: String): String {
    return when (status) {
        "thinking" -> "Thinking"
        "working" -> "Working"
        "waiting_approval" -> "Needs approval"
        "paused" -> "Paused"
        "sleeping" -> "Sleeping"
        "error" -> "Error"
        else -> "Idle"
    }
}

fun riskColor(risk: String): Color {
    return when (risk) {
        "low" -> Color(0xFF5FD3C4)
        "medium" -> Color(0xFFF2B84B)
        "high" -> Color(0xFFFF6B6B)
        else -> Color(0xFF8B93A3)
    }
}

@Composable
fun AgentAvatar(
    agent: AgentEntity,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    showStatus: Boolean = true,
) {
    val sprite = ImageBitmap.imageResource(R.drawable.office_sprites)
    val index = agent.avatarIndex.coerceIn(0, 7)
    val baseColumn = if (index < 4) index * 3 else (index - 4) * 3
    val baseRow = if (index < 4) 0 else 4

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0xFF2A3140),
                radius = size.toPx() / 2f,
            )
            drawImage(
                image = sprite,
                srcOffset = IntOffset(baseColumn * 32, baseRow * 32),
                srcSize = IntSize(32, 32),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(size.toPx().toInt(), size.toPx().toInt()),
            )
        }
        if (showStatus) {
            StatusDot(
                status = agent.status,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    val transition = rememberInfiniteTransition(label = "status")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (status == "thinking") 0.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Box(
        modifier = modifier
            .size(11.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
            .alpha(if (status == "thinking") pulse else 1f),
    )
}

@Composable
fun StatusPill(status: String, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = statusLabel(status),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
fun RiskBadge(risk: String, modifier: Modifier = Modifier) {
    val color = riskColor(risk)
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = "${risk.replaceFirstChar { it.uppercase() }} risk",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentCard(
    agent: AgentEntity,
    latestDiary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(agent, 50.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(agent.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        agent.role,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(agent.status)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "GOAL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = agent.goal,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!latestDiary.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "“${latestDiary.take(120)}”",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun connectorIcon(iconKey: String): ImageVector {
    return when (iconKey) {
        "wikipedia" -> Icons.Default.Language
        "hn" -> Icons.Default.Newspaper
        "search" -> Icons.Default.Search
        "weather" -> Icons.Default.Cloud
        "currency" -> Icons.Default.AttachMoney
        "device" -> Icons.Default.PhoneAndroid
        "files" -> Icons.Default.Folder
        "http" -> Icons.Default.Hub
        "gmail" -> Icons.Default.Email
        "calendar" -> Icons.Default.CalendarMonth
        "github" -> Icons.Default.Code
        "slack", "discord", "telegram" -> Icons.Default.Chat
        "notion" -> Icons.Default.Description
        "paypal" -> Icons.Default.AttachMoney
        else -> Icons.Default.Hub
    }
}

@Composable
fun OfficeThumbnail(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .background(colors.primaryContainer)
    ) {
        Icon(
            imageVector = Icons.Default.Hub,
            contentDescription = null,
            tint = colors.onPrimaryContainer.copy(alpha = 0.3f),
            modifier = Modifier
                .fillMaxSize()
                .size(120.dp)
                .align(Alignment.Center),
        )
    }
}