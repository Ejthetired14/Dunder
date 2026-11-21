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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MAX_SCALE = 5f
private const val DEFAULT_SCALE = 2f

/** Redraw at most ~30 times a second. Plenty for a dozen sprites, and kind to a Helio G36. */
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
    var cx by mutableFloatStateOf(OfficeLayout.worldW / 2f)
    var cy by mutableFloatStateOf(420f)
    var viewW by mutableFloatStateOf(0f)
    var viewH by mutableFloatStateOf(0f)
    private var placed = false

    private fun minScale(): Float =
        if (viewW <= 0f) 1f else viewW / OfficeLayout.worldW

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
        cx = OfficeLayout.worldW / 2f
        cy = 420f
        clamp()
    }

    fun worldToScreen(x: Float, y: Float): Offset =
        Offset((x - cx) * scale + viewW / 2f, (y - cy) * scale + viewH / 2f)

    fun screenToWorld(p: Offset): Offset =
        Offset((p.x - viewW / 2f) / scale + cx, (p.y - viewH / 2f) / scale + cy)

    /**
     * Agent sprite edge in pixels: a whole multiple of 32 so pixel art stays crisp, and never
     * smaller than 64 so agents stay easy to see and tap when you zoom out.
     */
    fun spritePx(): Float = 32f * max(2, scale.roundToInt()).coerceAtMost(8)

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
    selectedHotspotId: String?,
    deskAlert: Boolean,
    debug: Boolean,
    onSelect: (String?) -> Unit,
    onHotspot: (HotspotDef?) -> Unit,
    onDebugTap: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseImage = rememberResBitmap(R.drawable.office_pixel_base, 2048)
    val atlas = rememberResBitmap(R.drawable.office_pixel_objects, 1024)
    val sheet = rememberResBitmap(R.drawable.office_sprites, 4096)
    var frame by remember { mutableLongStateOf(0L) }

    val onSelectNow by rememberUpdatedState(onSelect)
    val onHotspotNow by rememberUpdatedState(onHotspot)
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
                    val world = camera.screenToWorld(tap)
                    if (hit != null) {
                        onSelectNow(hit.agentId)
                        onHotspotNow(null)
                    } else {
                        val hs = OfficeLayout.hotspots
                            .filter { world.x >= it.x0 && world.x <= it.x1 && world.y >= it.y0 && world.y <= it.y1 }
                            .minByOrNull { it.area }
                        onSelectNow(null)
                        onHotspotNow(hs)
                    }
                    if (debugNow) onDebugTapNow(world)
                }
            },
    ) {
        if (frame == Long.MIN_VALUE) return@Canvas // reading `frame` is what schedules each redraw

        drawRect(Pal.ink)
        val s = camera.scale
        val halfW = camera.viewW / 2f
        val halfH = camera.viewH / 2f
        fun sx(wx: Float): Float = (wx - camera.cx) * s + halfW
        fun sy(wy: Float): Float = (wy - camera.cy) * s + halfH

        val baseBmp = baseImage
        if (baseBmp != null) {
            val l = sx(0f).roundToInt()
            val t = sy(0f).roundToInt()
            val r = sx(OfficeLayout.worldW).roundToInt()
            val b = sy(OfficeLayout.worldH).roundToInt()
            drawImage(
                image = baseBmp,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(baseBmp.width, baseBmp.height),
                dstOffset = IntOffset(l, t),
                dstSize = IntSize(r - l, b - t),
                filterQuality = FilterQuality.None,
            )
        }

        if (debug) drawDebug(camera)

        val sheetBmp = sheet ?: return@Canvas
        val atlasBmp = atlas
        val px = camera.spritePx()
        val ordered = sim.actors.sortedBy { it.y }
        val objs = OfficeMapData.OBJECTS
        val objCount = objs.size / 7
        var oi = 0
        var ai = 0

        // Furniture and agents are drawn together, back to front, so an agent sitting behind a
        // desk has their legs hidden by it and one standing in front of it covers it.
        while (oi < objCount || ai < ordered.size) {
            val objSort = if (oi < objCount) objs[oi * 7 + 6].toFloat() else Float.MAX_VALUE
            val actSort = if (ai < ordered.size) ordered[ai].y else Float.MAX_VALUE
            if (oi < objCount && objSort <= actSort) {
                if (atlasBmp != null) {
                    val b = oi * 7
                    val w = objs[b + 2]
                    val h = objs[b + 3]
                    val dx = objs[b + 4].toFloat()
                    val dy = objs[b + 5].toFloat()
                    val l = sx(dx).roundToInt()
                    val t = sy(dy).roundToInt()
                    val r = sx(dx + w).roundToInt()
                    val bt = sy(dy + h).roundToInt()
                    if (r > 0 && l < camera.viewW && bt > 0 && t < camera.viewH) {
                        drawImage(
                            image = atlasBmp,
                            srcOffset = IntOffset(objs[b], objs[b + 1]),
                            srcSize = IntSize(w, h),
                            dstOffset = IntOffset(l, t),
                            dstSize = IntSize(r - l, bt - t),
                            filterQuality = FilterQuality.None,
                        )
                    }
                }
                oi++
            } else {
                val a = ordered[ai]
                ai++
                val fx = sx(a.x)
                val fy = sy(a.y)
                drawOval(
                    color = Color(0x44000000),
                    topLeft = Offset(fx - px * 0.28f, fy - px * 0.08f),
                    size = Size(px * 0.56f, px * 0.16f),
                )
                if (a.agentId == selectedId) {
                    drawOval(
                        color = Pal.amber,
                        topLeft = Offset(fx - px * 0.42f, fy - px * 0.14f),
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
                    image = sheetBmp,
                    srcOffset = SpriteSheet.srcOffset(a.avatar, a.dir, frameIdx),
                    srcSize = IntSize(SpriteSheet.CELL, SpriteSheet.CELL),
                    dstOffset = IntOffset(
                        (fx - px / 2f).roundToInt(),
                        (fy - px * 0.88f + bob).roundToInt(),
                    ),
                    dstSize = IntSize(px.roundToInt(), px.roundToInt()),
                    filterQuality = FilterQuality.None,
                )
            }
        }

        // Selected area outline.
        if (selectedHotspotId != null) {
            val hs = OfficeLayout.hotspots.firstOrNull { it.id == selectedHotspotId }
            if (hs != null) {
                drawRect(
                    color = Pal.amber,
                    topLeft = Offset(sx(hs.x0.toFloat()), sy(hs.y0.toFloat())),
                    size = Size((hs.x1 - hs.x0) * s, (hs.y1 - hs.y0) * s),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Pass 2: bubbles and name tags on top of everything.
        val showTags = camera.scale >= 1.6f
        for (a in ordered) {
            val fx = sx(a.x)
            val fy = sy(a.y)
            val headY = fy - px * 0.9f
            when (a.bubble) {
                Bubble.ALERT -> drawAlert(fx, headY, sim.elapsed, Pal.amber)
                Bubble.ERROR -> drawAlert(fx, headY, sim.elapsed, Pal.red)
                Bubble.TALK -> drawDots(fx, headY, sim.elapsed, false)
                Bubble.THINK -> drawDots(fx, headY, sim.elapsed, true)
                Bubble.ZZZ -> drawZzz(fx, headY, sim.elapsed)
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
                val tl = Offset(fx - w / 2f, fy + 3.dp.toPx())
                pixelBox(tl, Size(w, h), if (selected) Pal.amber else Pal.cream, 1.5.dp.toPx())
                drawText(layout, topLeft = Offset(tl.x + padX, tl.y + padY))
            }
        }

        // Your desk: a "!" floats over it whenever something needs you.
        if (deskAlert) {
            val d = OfficeLayout.deskHotspot
            drawAlert(sx(d.ax.toFloat()), sy(d.ay.toFloat()), sim.elapsed, Pal.amber)
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

/** A "!" bubble: amber when something needs you, red when an agent hit a problem. */
private fun DrawScope.drawAlert(cx: Float, headY: Float, t: Float, fill: Color) {
    val w = 18.dp.toPx()
    val h = 22.dp.toPx()
    val bob = sin(t * 7f) * 2.dp.toPx()
    val tl = Offset(cx - w / 2f, headY - h - 6.dp.toPx() + bob)
    pixelBox(tl, Size(w, h), fill, 2.dp.toPx())
    val ink = if (fill == Pal.red) Color.White else Pal.ink
    val bar = 3.dp.toPx()
    drawRect(ink, Offset(cx - bar / 2f, tl.y + 4.dp.toPx()), Size(bar, 9.dp.toPx()))
    drawRect(ink, Offset(cx - bar / 2f, tl.y + 15.dp.toPx()), Size(bar, 3.dp.toPx()))
    drawRect(Pal.ink, Offset(cx - 3.dp.toPx(), tl.y + h + 2.dp.toPx()), Size(6.dp.toPx(), 3.dp.toPx()))
}

/** Three dots: a speech bubble, or a thought bubble (with a trail of small circles) when [thought]. */
private fun DrawScope.drawDots(cx: Float, headY: Float, t: Float, thought: Boolean) {
    val w = 30.dp.toPx()
    val h = 18.dp.toPx()
    val tl = Offset(cx - w / 2f, headY - h - 8.dp.toPx())
    pixelBox(tl, Size(w, h), if (thought) Color(0xFFFFF6E0) else Pal.cream, 2.dp.toPx())
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
    if (thought) {
        drawRect(Pal.ink, Offset(cx - 1.dp.toPx(), tl.y + h + 2.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx()))
        drawRect(Pal.ink, Offset(cx - 4.dp.toPx(), tl.y + h + 8.dp.toPx()), Size(2.dp.toPx(), 2.dp.toPx()))
    } else {
        drawRect(Pal.ink, Offset(cx - 3.dp.toPx(), tl.y + h + 2.dp.toPx()), Size(6.dp.toPx(), 3.dp.toPx()))
    }
}

private fun DrawScope.drawZ(x: Float, y: Float, u: Float, color: Color) {
    drawRect(color, Offset(x, y), Size(5 * u, u))
    drawRect(color, Offset(x + 3 * u, y + u), Size(u, u))
    drawRect(color, Offset(x + 2 * u, y + 2 * u), Size(u, u))
    drawRect(color, Offset(x + u, y + 3 * u), Size(u, u))
    drawRect(color, Offset(x, y + 4 * u), Size(5 * u, u))
}

private fun DrawScope.drawZzz(cx: Float, headY: Float, t: Float) {
    val bob = sin(t * 3f) * 2.dp.toPx()
    val sizes = floatArrayOf(1.4f, 1.8f, 2.3f)
    var x = cx + 2.dp.toPx()
    var y = headY - 4.dp.toPx() + bob
    for (sz in sizes) {
        val u = sz.dp.toPx()
        drawZ(x + u * 0.5f, y + u * 0.5f, u, Pal.ink)
        drawZ(x, y, u, Pal.cream)
        x += 4.dp.toPx()
        y -= 8.dp.toPx()
    }
}

/** Walls and furniture as blocked tiles, every spot, every tappable area. Long-press the title to toggle. */
private fun DrawScope.drawDebug(cam: CameraState) {
    val g = OfficeMapData
    val tile = OfficeLayout.TILE * cam.scale
    for (ty in 0 until g.ROWS) {
        val row = g.GRID[ty]
        for (tx in 0 until g.COLS) {
            if (row[tx] == '#') {
                val p = cam.worldToScreen(tx * OfficeLayout.TILE, ty * OfficeLayout.TILE)
                drawRect(Color(0x33FF2D55), p, Size(tile, tile))
            }
        }
    }
    for (s in OfficeLayout.spots) {
        val p = cam.worldToScreen(s.x, s.y)
        drawCircle(Color(0xFFFF8A00), radius = 5.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
    }
    for (h in OfficeLayout.hotspots) {
        val p = cam.worldToScreen(h.x0.toFloat(), h.y0.toFloat())
        drawRect(
            Color(0xFFFFC83D),
            topLeft = p,
            size = Size((h.x1 - h.x0) * cam.scale, (h.y1 - h.y0) * cam.scale),
            style = Stroke(1.5.dp.toPx()),
        )
    }
}
