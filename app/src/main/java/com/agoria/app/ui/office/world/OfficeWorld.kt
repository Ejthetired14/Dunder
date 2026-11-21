package com.agoria.app.ui.office.world

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.agoria.app.R
import com.agoria.app.ui.office.world.rememberResBitmap
import kotlin.collections.HashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SPRITE_WORLD = 34f
private const val MIN_ZOOM_FIT = 1f
private const val MAX_SCALE = 5f
private const val DEFAULT_SCALE = 2.2f

/** Redraw at most ~30 times a second. Plenty for 8 sprites, and kind to a Helio G36. */
private const val FRAME_NS = 30_000_000L

private val TagStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 10.sp,
    color = Pal.ink,
)

/**
 * Viewport over the world. Screen = (world - center) * scale + viewport / 2.
 * State is read only inside the Canvas draw block, so panning and zooming redraw without recomposing.
 */
@Stable
class CameraState {
    var scale by mutableFloatStateOf(DEFAULT_SCALE)
    var cx by mutableFloatStateOf(420f)
    var cy by mutableFloatStateOf(300f)
    var viewW by mutableFloatStateOf(0f)
    var viewH by mutableFloatStateOf(0f)
    private var placed = false

    private fun minScale(): Float =
        if (viewW <= 0f) MIN_ZOOM_FIT else viewW / OfficeLayout.bounds.width

    fun onResize() {
        if (viewW <= 0f || viewH <= 0f) return
        if (!placed) {
            placed = true
            reset()
        } else {
            clamp()
        }
    }

    fun reset() {
        scale = max(minScale(), DEFAULT_SCALE)
        cx = 420f
        cy = 300f
        clamp()
    }

    fun worldToScreen(x: Float, y: Float): Offset =
        Offset((x - cx) * scale + viewW / 2f, (y - cy) * scale + viewH / 2f)

    fun screenToWorld(p: Offset): Offset =
        Offset((p.x - viewW / 2f) / scale + cx, (p.y - viewH / 2f) / scale + cy)

    /** Sprite edge in pixels: always a whole multiple of 32 so pixel art stays crisp. */
    fun spritePx(): Float = 32f * (scale * SPRITE_WORLD / 32f).roundToInt().coerceIn(1, 8)

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val before = screenToWorld(centroid)
        scale = (scale * zoom).coerceIn(minScale(), MAX_SCALE)
        cx = before.x - (centroid.x - viewW / 2f) / scale - pan.x / scale
        cy = before.y - (centroid.y - viewH / 2f) / scale - pan.y / scale
        clamp()
    }

    fun clamp() {
        val b = OfficeLayout.bounds
        scale = scale.coerceIn(minScale(), MAX_SCALE)
        val halfW = viewW / 2f / scale
        val halfH = viewH / 2f / scale
        cx = if (halfW * 2f >= b.width) b.center.x else cx.coerceIn(b.left + halfW, b.right - halfW)
        cy = if (halfH * 2f >= b.height) b.center.y else cy.coerceIn(b.top + halfH, b.bottom - halfH)
    }
}

@Composable
fun OfficeWorld(
    sim: OfficeSim,
    camera: CameraState,
    selectedId: String?,
    debug: Boolean,
    onSelect: (String?) -> Unit,
    onDeskTap: () -> Unit,
    onDebugTap: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val map = rememberResBitmap(R.drawable.office_tileset, 2048)
    val sheet = rememberResBitmap(R.drawable.office_sprites, 4096)
    var frame by remember { mutableLongStateOf(0L) }

    val onSelectNow by rememberUpdatedState(onSelect)
    val onDeskTapNow by rememberUpdatedState(onDeskTap)
    val onDebugTapNow by rememberUpdatedState(onDebugTap)
    val debugNow by rememberUpdatedState(debug)

    val textMeasurer = rememberTextMeasurer()
    val tagCache = remember { HashMap<String, TextLayoutResult>() }

    // Game loop. Only runs while the screen is visible, and redraws at ~30 fps.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, sim) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                if (last == 0L) {
                    last = now
                    continue
                }
                val dtNs = now - last
                if (dtNs < FRAME_NS) continue
                last = now
                sim.update((dtNs / 1_000_000_000f).coerceAtMost(0.1f))
                frame = now
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                camera.viewW = size.width.toFloat()
                camera.viewH = size.height.toFloat()
                camera.onResize()
            }
            .pointerInput(camera) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    camera.transform(centroid, pan, zoom)
                }
            }
            .pointerInput(sim, camera) {
                detectTapGestures { tap ->
                    val hit = hitActor(sim, camera, tap, 26.dp.toPx())
                    if (hit != null) {
                        onSelectNow(hit.agentId)
                    } else if (OfficeLayout.userDesk.contains(camera.screenToWorld(tap))) {
                        onDeskTapNow()
                    } else {
                        onSelectNow(null)
                    }
                    if (debugNow) onDebugTapNow(camera.screenToWorld(tap))
                }
            },
    ) {
        if (frame == Long.MIN_VALUE) return@Canvas // reading `frame` is what schedules each redraw

        drawRect(Pal.ink)

        val mapImage = map
        if (mapImage != null) {
            val topLeft = camera.worldToScreen(0f, 0f)
            val side = OfficeLayout.WORLD * camera.scale
            drawImage(
                image = mapImage,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(mapImage.width, mapImage.height),
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                dstSize = IntSize(side.roundToInt(), side.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }

        if (debug) drawDebug(camera)

        val sheetImage = sheet ?: return@Canvas
        val px = camera.spritePx()
        val ordered = sim.actors.sortedBy { it.y }

        // Pass 1: shadows, selection rings and sprites, back to front.
        for (a in ordered) {
            val s = camera.worldToScreen(a.x, a.y)
            drawOval(
                color = Color(0x44000000),
                topLeft = Offset(s.x - px * 0.28f, s.y - px * 0.08f),
                size = Size(px * 0.56f, px * 0.16f),
            )
            if (a.agentId == selectedId) {
                drawOval(
                    color = Pal.amber,
                    topLeft = Offset(s.x - px * 0.42f, s.y - px * 0.14f),
                    size = Size(px * 0.84f, px * 0.28f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            val frameIdx = if (a.walking) {
                SpriteSheet.WALK[((a.anim * 7f).toInt()) % SpriteSheet.WALK.size]
            } else {
                1
            }
            val bob = if (!a.walking && ((a.anim * 2f).toInt() and 1) == 1) 1.dp.toPx() else 0f
            drawImage(
                image = sheetImage,
                srcOffset = SpriteSheet.srcOffset(a.avatar, a.dir, frameIdx),
                srcSize = IntSize(SpriteSheet.CELL, SpriteSheet.CELL),
                dstOffset = IntOffset(
                    (s.x - px / 2f).roundToInt(),
                    (s.y - px * 0.88f + bob).roundToInt(),
                ),
                dstSize = IntSize(px.roundToInt(), px.roundToInt()),
                filterQuality = FilterQuality.None,
            )
        }

        // Pass 2: bubbles and name tags on top of every sprite.
        val showTags = camera.scale >= 1.8f
        for (a in ordered) {
            val s = camera.worldToScreen(a.x, a.y)
            val headY = s.y - px * 0.9f
            when (a.bubble) {
                Bubble.ALERT -> drawAlert(s.x, headY, sim.elapsed)
                Bubble.TALK -> drawTalk(s.x, headY, sim.elapsed)
                Bubble.NONE -> Unit
            }
            val selected = a.agentId == selectedId
            if ((showTags || selected) && a.name.isNotEmpty()) {
                val layout = tagCache.getOrPut(a.name) {
                    textMeasurer.measure(a.name.uppercase(), TagStyle)
                }
                val padX = 4.dp.toPx()
                val padY = 2.dp.toPx()
                val w = layout.size.width + padX * 2f
                val h = layout.size.height + padY * 2f
                val tl = Offset(s.x - w / 2f, s.y + 3.dp.toPx())
                pixelBox(tl, Size(w, h), if (selected) Pal.amber else Pal.cream, 1.5.dp.toPx())
                drawText(layout, topLeft = Offset(tl.x + padX, tl.y + padY))
            }
        }
    }
}

private fun hitActor(sim: OfficeSim, cam: CameraState, tap: Offset, minHalfW: Float): Actor? {
    val size = cam.spritePx()
    var best: Actor? = null
    var bestY = -Float.MAX_VALUE
    for (a in sim.actors) {
        val s = cam.worldToScreen(a.x, a.y)
        val halfW = max(size * 0.45f, minHalfW)
        val top = s.y - size * (if (a.bubble == Bubble.NONE) 1.0f else 1.6f)
        val bottom = s.y + size * 0.15f
        if (abs(tap.x - s.x) <= halfW && tap.y >= top && tap.y <= bottom && s.y > bestY) {
            best = a
            bestY = s.y
        }
    }
    return best
}

private fun DrawScope.pixelBox(topLeft: Offset, size: Size, fill: Color, border: Float) {
    drawRect(
        Pal.ink,
        topLeft = Offset(topLeft.x - border, topLeft.y - border),
        size = Size(size.width + border * 2f, size.height + border * 2f),
    )
    drawRect(fill, topLeft = topLeft, size = size)
}

/** Yellow "!" above an agent who is waiting for you. */
private fun DrawScope.drawAlert(cx: Float, headY: Float, t: Float) {
    val w = 18.dp.toPx()
    val h = 22.dp.toPx()
    val bob = sin(t * 7f) * 2.dp.toPx()
    val tl = Offset(cx - w / 2f, headY - h - 6.dp.toPx() + bob)
    pixelBox(tl, Size(w, h), Pal.amber, 2.dp.toPx())
    val bar = 3.dp.toPx()
    drawRect(Pal.ink, Offset(cx - bar / 2f, tl.y + 4.dp.toPx()), Size(bar, 9.dp.toPx()))
    drawRect(Pal.ink, Offset(cx - bar / 2f, tl.y + 15.dp.toPx()), Size(bar, 3.dp.toPx()))
    drawRect(Pal.ink, Offset(cx - 3.dp.toPx(), tl.y + h + 2.dp.toPx()), Size(6.dp.toPx(), 3.dp.toPx()))
}

/** Three-dot speech bubble for agents who are talking. Phase 3 puts real text in here. */
private fun DrawScope.drawTalk(cx: Float, headY: Float, t: Float) {
    val w = 30.dp.toPx()
    val h = 18.dp.toPx()
    val tl = Offset(cx - w / 2f, headY - h - 6.dp.toPx())
    pixelBox(tl, Size(w, h), Pal.cream, 2.dp.toPx())
    val dot = 3.dp.toPx()
    val gap = 4.dp.toPx()
    val startX = cx - (dot * 3f + gap * 2f) / 2f
    val active = (t * 3f).toInt() % 3
    for (i in 0 until 3) {
        val lift = if (i == active) 2.dp.toPx() else 0f
        drawRect(
            Pal.ink,
            Offset(startX + i * (dot + gap), tl.y + h / 2f - dot / 2f - lift),
            Size(dot, dot),
        )
    }
    drawRect(Pal.ink, Offset(cx - 3.dp.toPx(), tl.y + h + 2.dp.toPx()), Size(6.dp.toPx(), 3.dp.toPx()))
}

/** Shows the walking graph, every spot, your desk and the map bounds. Long-press the title to toggle. */
private fun DrawScope.drawDebug(cam: CameraState) {
    val g = OfficeLayout.graph
    val line = Color(0xAA00E5FF)
    for (i in 0 until g.size) {
        val a = cam.worldToScreen(g.xs[i], g.ys[i])
        for (j in g.adj[i]) {
            if (j > i) {
                val b = cam.worldToScreen(g.xs[j], g.ys[j])
                drawLine(line, a, b, strokeWidth = 2.dp.toPx())
            }
        }
    }
    val dot = 4.dp.toPx()
    for (i in 0 until g.size) {
        val p = cam.worldToScreen(g.xs[i], g.ys[i])
        drawRect(Color(0xFF00E5FF), Offset(p.x - dot / 2f, p.y - dot / 2f), Size(dot, dot))
    }
    for (s in g.spots) {
        val p = cam.worldToScreen(s.x, s.y)
        val r = 5.dp.toPx()
        drawOval(Color(0xFFFF8A00), topLeft = Offset(p.x - r, p.y - r), size = Size(r * 2f, r * 2f), style = Stroke(2.dp.toPx()))
    }
    val d = OfficeLayout.userDesk
    val dTl = cam.worldToScreen(d.left, d.top)
    drawRect(
        Color(0xFFFFC83D),
        topLeft = dTl,
        size = Size(d.width * cam.scale, d.height * cam.scale),
        style = Stroke(2.dp.toPx()),
    )
    val b = OfficeLayout.bounds
    val bTl = cam.worldToScreen(b.left, b.top)
    drawRect(
        Color(0xFFFF4D6D),
        topLeft = bTl,
        size = Size(b.width * cam.scale, b.height * cam.scale),
        style = Stroke(1.dp.toPx()),
    )
}
