package com.agoria.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
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

/** Pixel look shared by every page: 2 dp ink borders, flat fills, square corners. */
private val PixelInk = Color(0xFF14161F)

fun statusColor(status: String): Color {
    return when (status) {
        "thinking" -> Color(0xFFFFC83D)
        "working" -> Color(0xFF5E9AA0)
        "waiting_approval" -> Color(0xFFC9A2F0)
        "paused" -> Color(0xFF8B93A3)
        "sleeping" -> Color(0xFF6B7280)
        "error" -> Color(0xFFD9534F)
        else -> Color(0xFFB9B2A0)
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
        "low" -> Color(0xFF5E9AA0)
        "medium" -> Color(0xFFFFC83D)
        "high" -> Color(0xFFD9534F)
        else -> Color(0xFF8B93A3)
    }
}

/** A flat panel with a 2 dp ink border and a hard offset shadow. Use instead of rounded cards. */
@Composable
fun PixelCard(
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(Color(0x66000000))
            .padding(end = 3.dp, bottom = 3.dp)
            .background(fill)
            .border(2.dp, PixelInk),
    ) {
        content()
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
    // Front-facing, standing frame (middle column of the character's block).
    val baseColumn = (if (index < 4) index * 3 else (index - 4) * 3) + 1
    val baseRow = if (index < 4) 0 else 4

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2C3254))
                .border(2.dp, PixelInk),
        ) {
            drawImage(
                image = sprite,
                srcOffset = IntOffset(baseColumn * 32, baseRow * 32),
                srcSize = IntSize(32, 32),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()),
                filterQuality = FilterQuality.None,
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
    Box(
        modifier = modifier
            .size(12.dp)
            .background(statusColor(status))
            .border(2.dp, PixelInk),
    )
}

@Composable
private fun PixelChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = color,
        shape = RectangleShape,
        border = BorderStroke(2.dp, PixelInk),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = PixelInk,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun StatusPill(status: String, modifier: Modifier = Modifier) {
    PixelChip(statusLabel(status), statusColor(status), modifier)
}

@Composable
fun RiskBadge(risk: String, modifier: Modifier = Modifier) {
    PixelChip("${risk.replaceFirstChar { it.uppercase() }} risk", riskColor(risk), modifier)
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    PixelChip(text, color, modifier)
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
        shape = RectangleShape,
        border = BorderStroke(2.dp, PixelInk),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
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
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel.uppercase()) }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(PixelInk),
        )
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
        Text(title.uppercase(), style = MaterialTheme.typography.titleMedium)
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
        "accounts" -> Icons.Default.AccountCircle
        "github" -> Icons.Default.Code
        "slack", "discord", "telegram" -> Icons.Default.Chat
        "notion" -> Icons.Default.Description
        else -> Icons.Default.Hub
    }
}

@Composable
fun OfficeThumbnail(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .background(colors.primaryContainer)
            .border(2.dp, PixelInk),
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
