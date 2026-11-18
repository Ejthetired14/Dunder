package com.agoria.app.ui.office

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.agoria.app.R
import com.agoria.app.data.AgentEntity
import com.agoria.app.ui.components.statusColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val WORLD_WIDTH = 1000f

/** Normalised waypoints: x is a fraction of width, y a fraction of height. */
private val WAYPOINTS = listOf(
    Offset(0.10f, 0.16f), Offset(0.50f, 0.16f), Offset(0.90f, 0.16f),
    Offset(0.10f, 0.48f), Offset(0.50f, 0.48f), Offset(0.90f, 0.48f),
    Offset(0.10f, 0.78f), Offset(0.50f, 0.78f), Offset(0.90f, 0.78f),
)

private enum class Direction { DOWN, LEFT, RIGHT, UP }

private class Walker(
    var x: Float,
    var y: Float,
    var targetX: Float,
    var targetY: Float,
    var direction: Direction = Direction.DOWN,
    var moving: Boolean = false,
    var pauseUntil: Long = 0L,
    var animFrame: Int = 0,
    var phase: Float = 0f,
    var waypoint: Int = 0,
)

/**
 * A live top-down office. Agents walk between waypoints, face the direction they
 * are moving, and animate through the three walk frames from office_sprites.png.
 */
@Composable
fun OfficeScene(
    agents: List<AgentEntity>,
    autonomyEnabled: Boolean,
    onOpenAgent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sprite = ImageBitmap.imageResource(R.drawable.office_sprites)
    val thumbnail = ImageBitmap.imageResource(R.drawable.office_thumbnail)
    val tileset = ImageBitmap.imageResource(R.drawable.office_tileset)
    val textMeasurer = rememberTextMeasurer()

    val agentsState = rememberUpdatedState(agents)
    val autonomyState = rememberUpdatedState(autonomyEnabled)
    val walkers = remember { mutableStateMapOf<String, Walker>() }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(agents.map { it.id }) {
        val ids = agents.map { it.id }.toSet()
        walkers.keys.retainAll(ids)
        agents.forEachIndexed { index, agent ->
            if (agent.id !in walkers) {
                val wp = WAYPOINTS[index % WAYPOINTS.size]
                val x = (wp.x + (Random.nextFloat() - 0.5f) * 0.04f).coerceIn(0.06f, 0.94f)
                val y = (wp.y + (Random.nextFloat() - 0.5f) * 0.04f).coerceIn(0.10f, 0.90f)
                walkers[agent.id] = Walker(
                    x = x,
                    y = y,
                    targetX = x,
                    targetY = y,
                    phase = index * 0.37f,
                    waypoint = index % WAYPOINTS.size,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        var last = System.currentTimeMillis()
        while (true) {
            withFrameMillis { now ->
                val dt = ((now - last).coerceAtMost(64L)) / 1000f
                last = now
                val currentAgents = agentsState.value
                val byId = currentAgents.associateBy { it.id }
                val autonomy = autonomyState.value

                walkers.forEach { (id, walker) ->
                    val agent = byId[id] ?: return@forEach
                    if (!autonomy || !agent.autonomy || agent.status == "paused" ||
                        agent.status == "sleeping" || agent.status == "waiting_approval"
                    ) {
                        walker.moving = false
                        walker.animFrame = 0
                        return@forEach
                    }

                    if (abs(walker.targetX - walker.x) < 0.002f &&
                        abs(walker.targetY - walker.y) < 0.002f
                    ) {
                        if (now >= walker.pauseUntil) {
                            val choices = WAYPOINTS.indices.filter { it != walker.waypoint }
                            val next = choices.random()
                            walker.waypoint = next
                            walker.targetX = WAYPOINTS[next].x
                            walker.targetY = WAYPOINTS[next].y
                            walker.pauseUntil = now + 1400L + Random.nextLong(0L, 2200L)
                        }
                    }

                    // Walk along x first, then y: this keeps agents in the aisles.
                    var remaining = 0.075f * dt
                    val dx = walker.targetX - walker.x
                    if (abs(dx) > 0.0015f) {
                        val step = dx.coerceIn(-remaining, remaining)
                        walker.x += step
                        remaining -= abs(step)
                        walker.direction = if (step > 0f) Direction.RIGHT else Direction.LEFT
                        walker.moving = true
                    }
                    val dy = walker.targetY - walker.y
                    if (abs(dy) > 0.0015f && remaining > 0f) {
                        val step = dy.coerceIn(-remaining, remaining)
                        walker.y += step
                        walker.direction = if (step > 0f) Direction.DOWN else Direction.UP
                        walker.moving = true
                    }

                    if (abs(walker.targetX - walker.x) < 0.002f &&
                        abs(walker.targetY - walker.y) < 0.002f
                    ) {
                        walker.x = walker.targetX
                        walker.y = walker.targetY
                        walker.moving = false
                    }

                    walker.animFrame = if (walker.moving) {
                        ((now / 140L + (walker.phase * 10L).toLong()) % 3L).toInt()
                    } else {
                        0
                    }
                }
                frame++
            }
        }
    }

    val tick = frame

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val hit = agentsState.value
                    .sortedByDescending { walkers[it.id]?.y ?: 0f }
                    .firstOrNull { agent ->
                        val walker = walkers[agent.id] ?: return@firstOrNull false
                        val sx = walker.x * size.width
                        val sy = walker.y * size.height
                        offset.x >= sx - 28f && offset.x <= sx + 28f &&
                            offset.y >= sy - 58f && offset.y <= sy + 10f
                    }
                if (hit != null) onOpenAgent(hit.id)
            }
        },
    ) {
        val scale = size.width / WORLD_WIDTH
        val worldHeight = size.height / scale

        drawRect(color = Color(0xFF0D1117), size = size)

        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawOffice(tileset, thumbnail, worldHeight, tick)

            agents.sortedBy { walkers[it.id]?.y ?: 0f }.forEach { agent ->
                val walker = walkers[agent.id] ?: return@forEach
                drawAgent(agent, walker, sprite, worldHeight)
            }
        }

        // Labels are drawn in screen space so they stay readable at any size.
        agents.forEach { agent ->
            val walker = walkers[agent.id] ?: return@forEach
            drawAgentLabel(agent, walker, textMeasurer)
        }
    }
}

private fun DrawScope.drawOffice(
    tileset: ImageBitmap,
    thumbnail: ImageBitmap,
    worldHeight: Float,
    tick: Int,
) {
    // Floor
    drawRect(color = Color(0xFF2B2118), size = Size(WORLD_WIDTH, worldHeight))
    drawImage(
        image = tileset,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(tileset.width, tileset.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(WORLD_WIDTH.roundToInt(), worldHeight.roundToInt()),
        alpha = 0.13f,
        filterQuality = FilterQuality.Low,
    )
    drawRect(color = Color(0xFF17120E).copy(alpha = 0.48f), size = Size(WORLD_WIDTH, worldHeight))

    // Floorboards
    var y = 80f
    while (y < worldHeight - 40f) {
        drawLine(
            color = Color(0x22FFFFFF),
            start = Offset(0f, y),
            end = Offset(WORLD_WIDTH, y),
            strokeWidth = 1f,
        )
        y += 46f
    }
    var x = 0f
    while (x < WORLD_WIDTH) {
        drawLine(
            color = Color(0x11FFFFFF),
            start = Offset(x, 80f),
            end = Offset(x, worldHeight - 40f),
            strokeWidth = 1f,
        )
        x += 100f
    }

    // Walls
    drawRect(color = Color(0xFF121821), size = Size(WORLD_WIDTH, 80f))
    drawRect(
        color = Color(0xFF121821),
        topLeft = Offset(0f, worldHeight - 40f),
        size = Size(WORLD_WIDTH, 40f),
    )
    drawRect(color = Color(0xFF121821), size = Size(40f, worldHeight))
    drawRect(
        color = Color(0xFF121821),
        topLeft = Offset(WORLD_WIDTH - 40f, 0f),
        size = Size(40f, worldHeight),
    )
    drawRect(color = Color(0xFF2A3647), topLeft = Offset(0f, 76f), size = Size(WORLD_WIDTH, 4f))

    // Windows
    for (i in 0..2) {
        val wx = 90f + i * 250f
        drawRoundRect(
            color = Color(0xFF0B1220),
            topLeft = Offset(wx, 14f),
            size = Size(200f, 50f),
            cornerRadius = CornerRadius(10f, 10f),
        )
        drawRect(color = Color(0xFF102238), topLeft = Offset(wx + 5f, 19f), size = Size(190f, 40f))
        for (s in 0..4) {
            drawCircle(
                color = Color.White.copy(alpha = 0.65f),
                radius = 1.2f,
                center = Offset(wx + 22f + s * 37f, 27f + (s * 13f) % 24f),
            )
        }
        drawCircle(color = Color(0xFFF4E8C1), radius = 7f, center = Offset(wx + 160f, 39f))
        drawRect(color = Color(0xFF2A3647), topLeft = Offset(wx + 98f, 14f), size = Size(4f, 50f))
    }

    // Framed office thumbnail
    drawRect(color = Color(0xFF0A0D13), topLeft = Offset(800f, 10f), size = Size(160f, 70f))
    drawImage(
        image = thumbnail,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(thumbnail.width, thumbnail.height),
        dstOffset = IntOffset(804, 14),
        dstSize = IntSize(152, 62),
        filterQuality = FilterQuality.Low,
    )
    drawRect(
        color = Color(0xFF6B4A2F),
        topLeft = Offset(800f, 10f),
        size = Size(160f, 70f),
        style = Stroke(3f),
    )

    // Clock
    drawCircle(color = Color(0xFFE8E2D4), radius = 18f, center = Offset(500f, 42f))
    drawCircle(color = Color(0xFF1A1A1A), radius = 15f, center = Offset(500f, 42f))
    drawLine(color = Color.White, start = Offset(500f, 42f), end = Offset(500f, 32f), strokeWidth = 2f)
    val secondAngle = (tick % 60) * (PI.toFloat() / 30f)
    drawLine(
        color = Color(0xFFF2B84B),
        start = Offset(500f, 42f),
        end = Offset(500f + sin(secondAngle) * 12f, 42f - cos(secondAngle) * 12f),
        strokeWidth = 1.5f,
    )

    // Rug
    val rugTop = worldHeight * 0.18f
    val rugHeight = worldHeight * 0.64f
    drawRoundRect(
        color = Color(0xFF1B2838),
        topLeft = Offset(250f, rugTop),
        size = Size(500f, rugHeight),
        cornerRadius = CornerRadius(28f, 28f),
    )
    drawRoundRect(
        color = Color(0xFF3D5A78),
        topLeft = Offset(250f, rugTop),
        size = Size(500f, rugHeight),
        cornerRadius = CornerRadius(28f, 28f),
        style = Stroke(3f),
    )

    // Desks
    val topDeskY = worldHeight * 0.22f
    val bottomDeskY = worldHeight * 0.56f
    for (dx in listOf(150f, 300f, 570f, 720f)) {
        drawDesk(dx, topDeskY)
        drawChair(dx + 65f, topDeskY + 88f)
        drawDesk(dx, bottomDeskY)
        drawChair(dx + 65f, bottomDeskY + 88f)
    }

    // Coffee bar
    val coffeeY = maxOf(92f, worldHeight * 0.09f)
    drawRoundRect(
        color = Color(0xFF4A3426),
        topLeft = Offset(760f, coffeeY),
        size = Size(180f, 42f),
        cornerRadius = CornerRadius(8f, 8f),
    )
    drawRoundRect(
        color = Color(0xFF1A1A1A),
        topLeft = Offset(790f, coffeeY - 18f),
        size = Size(50f, 36f),
        cornerRadius = CornerRadius(5f, 5f),
    )
    drawCircle(color = Color(0xFFE74C3C), radius = 3f, center = Offset(815f, coffeeY - 10f))
    drawCircle(color = Color(0xFFE8E2D4), radius = 4f, center = Offset(860f, coffeeY + 8f))
    drawCircle(color = Color(0xFFE8E2D4), radius = 4f, center = Offset(880f, coffeeY + 8f))

    // Plants
    drawPlant(450f, worldHeight * 0.10f)
    drawPlant(550f, worldHeight * 0.10f)
    drawPlant(450f, worldHeight * 0.90f)
    drawPlant(550f, worldHeight * 0.90f)

    // Water cooler and shelf
    drawRoundRect(
        color = Color(0xFFD8E6F2),
        topLeft = Offset(48f, worldHeight * 0.36f),
        size = Size(26f, 44f),
        cornerRadius = CornerRadius(6f, 6f),
    )
    drawRect(color = Color(0xFF4A90D9), topLeft = Offset(52f, worldHeight * 0.36f - 14f), size = Size(18f, 16f))
    drawRoundRect(
        color = Color(0xFF5A3B28),
        topLeft = Offset(46f, worldHeight * 0.62f),
        size = Size(38f, 70f),
        cornerRadius = CornerRadius(3f, 3f),
    )
    for (i in 0..2) {
        drawRect(
            color = Color(0xFFC9A66B),
            topLeft = Offset(50f, worldHeight * 0.62f + 10f + i * 18f),
            size = Size(30f, 5f),
        )
    }

    // Entrance
    drawRect(color = Color(0xFF2A3647), topLeft = Offset(450f, worldHeight - 40f), size = Size(100f, 40f))
    drawRect(color = Color(0xFF161F2A), topLeft = Offset(456f, worldHeight - 36f), size = Size(88f, 36f))
    drawCircle(color = Color(0xFFC9A66B), radius = 2.5f, center = Offset(536f, worldHeight - 18f))
}

private fun DrawScope.drawDesk(x: Float, y: Float) {
    val w = 130f
    val h = 70f
    drawRoundRect(
        color = Color(0x55000000),
        topLeft = Offset(x + 4f, y + 6f),
        size = Size(w, h),
        cornerRadius = CornerRadius(10f, 10f),
    )
    drawRoundRect(
        color = Color(0xFF6B4A2F),
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = CornerRadius(10f, 10f),
    )
    drawRoundRect(
        color = Color(0xFF7D5738),
        topLeft = Offset(x + 4f, y + 4f),
        size = Size(w - 8f, h - 8f),
        cornerRadius = CornerRadius(8f, 8f),
    )
    val mx = x + w * 0.5f - 24f
    drawRect(color = Color(0xFF2B6CB0).copy(alpha = 0.18f), topLeft = Offset(mx - 5f, y + 3f), size = Size(58f, 40f))
    drawRoundRect(
        color = Color(0xFF0B1018),
        topLeft = Offset(mx, y + 7f),
        size = Size(48f, 30f),
        cornerRadius = CornerRadius(4f, 4f),
    )
    drawRect(color = Color(0xFF2B6CB0), topLeft = Offset(mx + 3f, y + 10f), size = Size(42f, 24f))
    drawRect(color = Color(0xFF4A90D9).copy(alpha = 0.65f), topLeft = Offset(mx + 6f, y + 13f), size = Size(16f, 18f))
    drawRect(color = Color(0xFF1A2028), topLeft = Offset(mx + 21f, y + 37f), size = Size(6f, 9f))
    drawRoundRect(
        color = Color(0xFFD8D2C4),
        topLeft = Offset(x + w * 0.5f - 24f, y + h - 18f),
        size = Size(48f, 10f),
        cornerRadius = CornerRadius(2f, 2f),
    )
    drawCircle(color = Color(0xFFE8E2D4), radius = 5f, center = Offset(x + w - 15f, y + 16f))
    drawCircle(color = Color(0xFF6B4A2F), radius = 2f, center = Offset(x + w - 15f, y + 16f))
}

private fun DrawScope.drawChair(x: Float, y: Float) {
    drawCircle(color = Color(0x55000000), radius = 15f, center = Offset(x + 2f, y + 3f))
    drawCircle(color = Color(0xFF11151D), radius = 14f, center = Offset(x, y))
    drawCircle(color = Color(0xFF2A3546), radius = 10f, center = Offset(x, y))
    drawRect(color = Color(0xFF1A2230), topLeft = Offset(x - 9f, y - 16f), size = Size(18f, 8f))
}

private fun DrawScope.drawPlant(x: Float, y: Float) {
    drawRect(color = Color(0xFF8A5A3B), topLeft = Offset(x - 10f, y), size = Size(20f, 18f))
    drawCircle(color = Color(0xFF2F7D4F), radius = 17f, center = Offset(x, y - 6f))
    drawCircle(color = Color(0xFF3FA66A), radius = 11f, center = Offset(x - 9f, y - 13f))
    drawCircle(color = Color(0xFF256B42), radius = 10f, center = Offset(x + 9f, y - 11f))
    drawCircle(color = Color(0xFF4FBF7A), radius = 7f, center = Offset(x + 1f, y - 18f))
}

private fun DrawScope.drawAgent(
    agent: AgentEntity,
    walker: Walker,
    sprite: ImageBitmap,
    worldHeight: Float,
) {
    val px = walker.x * WORLD_WIDTH
    val py = walker.y * worldHeight
    val index = agent.avatarIndex.coerceIn(0, 7)
    val baseX = (index % 4) * 96
    val baseY = (index / 4) * 128
    val rowOffset = when (walker.direction) {
        Direction.DOWN -> 0
        Direction.LEFT -> 32
        Direction.RIGHT -> 64
        Direction.UP -> 96
    }
    val bob = if (walker.moving) (walker.animFrame - 1) * 1.2f else 0f

    drawOval(
        color = Color(0x66000000),
        topLeft = Offset(px - 17f, py - 6f),
        size = Size(34f, 11f),
    )
    drawImage(
        image = sprite,
        srcOffset = IntOffset(baseX + walker.animFrame.coerceIn(0, 2) * 32, baseY + rowOffset),
        srcSize = IntSize(32, 32),
        dstOffset = IntOffset((px - 24f).roundToInt(), (py - 48f + bob).roundToInt()),
        dstSize = IntSize(48, 48),
        alpha = if (agent.status == "paused" || agent.status == "sleeping") 0.55f else 1f,
        filterQuality = FilterQuality.None,
    )

    val dot = Offset(px + 19f, py - 44f)
    drawCircle(color = statusColor(agent.status), radius = 5f, center = dot)
    drawCircle(color = Color.White, radius = 5f, center = dot, style = Stroke(1.5f))

    if (agent.status == "waiting_approval") {
        drawRoundRect(
            color = Color(0xFFF2B84B),
            topLeft = Offset(px + 12f, py - 72f),
            size = Size(20f, 20f),
            cornerRadius = CornerRadius(10f, 10f),
        )
        drawRect(color = Color(0xFF1A1205), topLeft = Offset(px + 21f, py - 67f), size = Size(2f, 8f))
        drawCircle(color = Color(0xFF1A1205), radius = 1.5f, center = Offset(px + 22f, py - 56f))
    } else if (agent.status == "thinking") {
        val alpha = 0.45f + 0.35f * (walker.animFrame / 2f)
        drawCircle(color = Color.White.copy(alpha = alpha), radius = 3f, center = Offset(px - 6f, py - 58f))
        drawCircle(color = Color.White.copy(alpha = alpha), radius = 2.3f, center = Offset(px + 2f, py - 62f))
        drawCircle(color = Color.White.copy(alpha = alpha), radius = 1.7f, center = Offset(px + 9f, py - 65f))
    }
}

private fun DrawScope.drawAgentLabel(
    agent: AgentEntity,
    walker: Walker,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val sx = walker.x * size.width
    val sy = walker.y * size.height
    val scale = size.width / WORLD_WIDTH
    val style = TextStyle(color = Color.White, fontSize = 11.sp)
    val layout = textMeasurer.measure(agent.name, style)
    val left = sx - layout.size.width / 2f
    val top = sy - 58f * scale - layout.size.height
    drawRoundRect(
        color = Color(0xCC11151D),
        topLeft = Offset(left - 5f, top - 2f),
        size = Size(layout.size.width.toFloat() + 10f, layout.size.height.toFloat() + 4f),
        cornerRadius = CornerRadius(5f, 5f),
    )
    drawText(
        textMeasurer = textMeasurer,
        text = agent.name,
        topLeft = Offset(left, top),
        style = style,
    )
}