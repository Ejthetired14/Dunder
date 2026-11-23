package com.agoria.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.foundation.layout.ColumnScope
import com.agoria.app.R
import com.agoria.app.data.AgentEntity
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.PixelPill

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
            .background(color)
            .border(1.5.dp, MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (status == "thinking") {
            Box(
                Modifier
                    .size(5.dp)
                    .background(color)
                    .graphicsLayer { alpha = pulse },
            )
        }
    }
}

@Composable
fun StatusPill(status: String, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    PixelPill(
        text = statusLabel(status),
        modifier = modifier,
        fill = color.copy(alpha = 0.14f),
        textColor = color,
    )
}

@Composable
fun RiskBadge(risk: String, modifier: Modifier = Modifier) {
    val color = riskColor(risk)
    PixelPill(
        text = "${risk.replaceFirstChar { it.uppercase() }} risk",
        modifier = modifier,
        fill = color.copy(alpha = 0.14f),
        textColor = color,
    )
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    PixelPill(text = text, modifier = modifier, fill = color.copy(alpha = 0.14f), textColor = color)
}

/** Square-cornered card: cream fill, 2 dp ink border, offset shadow. Content is a Column. */
@Composable
fun PixelCard(
    modifier: Modifier = Modifier,
    fill: Color = Pal.cream,
    content: @Composable ColumnScope.() -> Unit,
) {
    PixelPanel(modifier = modifier, fill = fill) {
        Column(Modifier.fillMaxWidth(), content = content)
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
            imageVector = AutoAwesome,
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
        "wikipedia" -> Language
        "hn" -> Newspaper
        "search" -> Search
        "weather" -> Cloud
        "currency" -> AttachMoney
        "device" -> PhoneAndroid
        "files" -> Folder
        "http" -> Hub
        "gmail" -> Email
        "calendar" -> CalendarMonth
        "github" -> Code
        "slack", "discord", "telegram" -> Chat
        "notion" -> Description
        "paypal", "polar" -> AttachMoney
        "agentmail" -> Email
        "gdrive" -> Folder
        else -> Hub
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
            imageVector = Hub,
            contentDescription = null,
            tint = colors.onPrimaryContainer.copy(alpha = 0.3f),
            modifier = Modifier
                .fillMaxSize()
                .size(120.dp)
                .align(Alignment.Center),
        )
    }
}