package com.agoria.app.ui.components

import android.graphics.Paint
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agoria.app.R
import com.agoria.app.data.AgentEntity
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel

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
    val isPlatformEngineer = agent.id == "seed-platform-engineer"
    val index = if (isPlatformEngineer) 0 else agent.avatarIndex.coerceIn(0, 7)
    val baseColumn = if (index < 4) index * 3 else (index - 4) * 3
    val baseRow = if (index < 4) 0 else 4

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2C3254))
                .border(2.dp, Pal.ink),
        ) {
            drawImage(
                image = sprite,
                srcOffset = IntOffset(baseColumn * 32, baseRow * 32),
                srcSize = IntSize(32, 32),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()),
            )
            // Gold badge for Platform Engineer
            if (isPlatformEngineer) {
                val badgeSize = (size.value * 0.35).toInt()
                val badgeX = this.size.width - badgeSize - 2.dp.toPx()
                val badgeY = 2.dp.toPx()
                drawRect(
                    brush = SolidColor(Pal.amber),
                    topLeft = Offset(badgeX, badgeY),
                    size = Size(badgeSize.toFloat(), badgeSize.toFloat()),
                )
                // Draw "B" letter in the badge
                drawIntoCanvas { canvas ->
                    val textPaint = Paint().apply {
                        color = Pal.ink.toArgb()
                        textSize = badgeSize * 0.6f
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.nativeCanvas.drawText(
                        "B",
                        badgeX + badgeSize / 2f,
                        badgeY + badgeSize / 2f + textPaint.textSize / 3,
                        textPaint,
                    )
                }
            }
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
            .size(12.dp)
            .background(color)
            .border(2.dp, Pal.ink),
    )
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
fun PixelPill(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color = Pal.amber.copy(alpha = 0.14f),
    textColor: Color = Pal.amber,
) {
    Box(
        modifier = modifier
            .background(fill)
            .border(2.dp, Pal.ink)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PixelLabel.copy(color = textColor, fontSize = 10.sp))
    }
}

/** @Deprecated("Use PixelPill instead") */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Pal.teal,
) {
    PixelPill(text = text, modifier = modifier, fill = color.copy(alpha = 0.14f), textColor = color)
}

/**
 * Square-cornered card: flat fill, 2 dp ink border, offset shadow. Content is a Column.
 *
 * Text colour is chosen from the fill so content is readable by default: ink on light fills (cream,
 * tan, amber), cream on dark fills (navy, teal, the theme's surfaceVariant). Text that sets its own
 * colour is unaffected. Secondary text on light fills should use `Pal.navy`, never
 * `colorScheme.onSurfaceVariant` or `primary` (those are tan/amber, made for dark backgrounds).
 *
 * Inner padding is deliberately NOT added here: every caller already pads its own content (some
 * put an image flush against the border), so a default would double-pad. Add `Modifier.padding(14.dp)`
 * inside, as `SettingsCard` and `AgentCard` do.
 */
@Composable
fun PixelCard(
    modifier: Modifier = Modifier,
    fill: Color = Pal.cream,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val contentColor = if (fill.luminance() > 0.4f) Pal.ink else Pal.cream
    PixelPanel(modifier = modifier, fill = fill) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(Modifier.fillMaxWidth()) {
                title?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                }
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentCard(
    agent: AgentEntity,
    latestDiary: String?,
    onClickAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = onClickAction
    val description = buildString {
        append(agent.name).append(", ").append(agent.role).append(". ")
        append("Status: ").append(statusLabel(agent.status)).append(". ")
        append("Goal: ").append(agent.goal.take(140)).append(". ")
        if (!latestDiary.isNullOrBlank()) append("Latest: ").append(latestDiary.take(120))
    }
    PixelCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClickAction)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Open ${agent.name}") { open(); true }
            },
        fill = MaterialTheme.colorScheme.surfaceVariant,
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
                        color = Pal.tan,
                    )
                }
                StatusPill(agent.status)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "GOAL",
                style = MaterialTheme.typography.labelSmall,
                color = Pal.amber,
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
                    color = Pal.tan,
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
            tint = Pal.amber,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = Pal.tan,
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
        "paypal", "polar" -> Icons.Default.AttachMoney
        "agentmail" -> Icons.Default.Email
        "gdrive" -> Icons.Default.Folder
        "terminal" -> Icons.Default.Code
        else -> Icons.Default.Hub
    }
}

@Composable
fun OfficeThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Pal.amber.copy(alpha = 0.14f))
            .border(2.dp, Pal.ink),
    ) {
        Icon(
            imageVector = Icons.Default.Hub,
            contentDescription = null,
            tint = Pal.amber.copy(alpha = 0.3f),
            modifier = Modifier
                .fillMaxSize()
                .size(120.dp)
                .align(Alignment.Center),
        )
    }
}