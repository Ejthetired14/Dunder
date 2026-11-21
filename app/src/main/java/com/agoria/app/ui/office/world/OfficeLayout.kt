package com.agoria.app.ui.office.world

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.floor
import kotlin.math.hypot

/** Sprite sheet rows: down, left, right, up. */
object Dir {
    const val DOWN = 0
    const val LEFT = 1
    const val RIGHT = 2
    const val UP = 3
}

enum class SpotKind { DESK, QUEUE, LIBRARY, WEB, MAIL, SERVER, WORKSHOP, FINANCE, KITCHEN, LOUNGE, MEETING }

/** Raw spot row from the generated map data. */
class SpotDef(
    val kind: String,
    val id: String,
    val tx: Int,
    val ty: Int,
    val x: Float,
    val y: Float,
    val face: Int,
)

/** A tappable area of the map that opens part of the app (or your desk). Rectangles are in world px. */
class HotspotDef(
    val id: String,
    val label: String,
    val blurb: String,
    val route: String,
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int,
    /** Where the "!" floats when something needs you (used by your desk). */
    val ax: Int,
    val ay: Int,
) {
    val rect: Rect get() = Rect(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
    val area: Int get() = (x1 - x0) * (y1 - y0)
}

/**
 * A place an agent can stand and do something. (tx, ty) is the walkable tile they walk to, and
 * (x, y) is where their feet end up, which can be a few pixels inside a desk or couch so the
 * furniture hides their legs.
 */
class Spot(
    val id: String,
    val kind: SpotKind,
    val tx: Int,
    val ty: Int,
    val x: Float,
    val y: Float,
    val face: Int,
)

/** Walkable tile grid with breadth-first routing and line-of-sight smoothing. */
class OfficeGrid(private val rows: Array<String>) {
    val cols: Int = rows[0].length
    val rowCount: Int = rows.size

    fun walkable(tx: Int, ty: Int): Boolean =
        tx in 0 until cols && ty in 0 until rowCount && rows[ty][tx] == '.'

    fun walkableAt(x: Float, y: Float): Boolean =
        walkable(floor(x / TILE).toInt(), floor(y / TILE).toInt())

    /**
     * Route from a world position to a walkable tile, as foot positions. The start tile may be
     * blocked (an agent sitting at a desk); the route leaves through its walkable neighbours.
     * Empty when already there or when the tile cannot be reached.
     */
    fun route(fromX: Float, fromY: Float, toTx: Int, toTy: Int): List<Offset> {
        val sx = floor(fromX / TILE).toInt().coerceIn(0, cols - 1)
        val sy = floor((fromY - 1f) / TILE).toInt().coerceIn(0, rowCount - 1)
        if (!walkable(toTx, toTy)) return emptyList()
        if (sx == toTx && sy == toTy) return emptyList()
        val n = cols * rowCount
        val prev = IntArray(n) { -2 }
        val queue = IntArray(n)
        val start = sy * cols + sx
        val goal = toTy * cols + toTx
        var head = 0
        var tail = 0
        queue[tail++] = start
        prev[start] = -1
        while (head < tail) {
            val cur = queue[head++]
            if (cur == goal) break
            val cx = cur % cols
            val cy = cur / cols
            for (d in 0 until 4) {
                val nx = cx + DX[d]
                val ny = cy + DY[d]
                if (!walkable(nx, ny)) continue
                val ni = ny * cols + nx
                if (prev[ni] != -2) continue
                prev[ni] = cur
                queue[tail++] = ni
            }
        }
        if (prev[goal] == -2) return emptyList()
        val tiles = ArrayList<Int>()
        var cur = goal
        while (cur != -1) {
            tiles.add(cur)
            cur = prev[cur]
        }
        tiles.reverse()
        val pts = ArrayList<Offset>(tiles.size + 1)
        pts.add(Offset(fromX, fromY))
        for (t in tiles) pts.add(Offset((t % cols) * TILE + TILE / 2f, (t / cols) * TILE + 26f))
        return smooth(pts)
    }

    private fun smooth(pts: List<Offset>): List<Offset> {
        if (pts.size <= 2) return pts.drop(1)
        val out = ArrayList<Offset>()
        var i = 0
        while (i < pts.size - 1) {
            var j = pts.size - 1
            while (j > i + 1 && !clear(pts[i], pts[j])) j--
            out.add(pts[j])
            i = j
        }
        return out
    }

    private fun clear(a: Offset, b: Offset): Boolean {
        val d = hypot(b.x - a.x, b.y - a.y)
        val steps = (d / 6f).toInt() + 1
        for (s in 1 until steps) {
            val t = s / steps.toFloat()
            val x = a.x + (b.x - a.x) * t
            val y = a.y + (b.y - a.y) * t
            if (!walkableAt(x, y) || !walkableAt(x - 9f, y) || !walkableAt(x + 9f, y)) return false
        }
        return true
    }

    companion object {
        const val TILE = 32f
        private val DX = intArrayOf(1, -1, 0, 0)
        private val DY = intArrayOf(0, 0, 1, -1)
    }
}

object OfficeLayout {
    const val TILE = 32f
    val worldW: Float = OfficeMapData.COLS * TILE
    val worldH: Float = OfficeMapData.ROWS * TILE
    val bounds = Rect(0f, 0f, worldW, worldH)

    val grid = OfficeGrid(OfficeMapData.GRID)

    val spots: List<Spot> = OfficeMapData.SPOTS.map {
        Spot(it.id, SpotKind.valueOf(it.kind), it.tx, it.ty, it.x, it.y, it.face)
    }
    private val byKind: Map<SpotKind, List<Spot>> = spots.groupBy { it.kind }

    val desks: List<Spot> = byKind[SpotKind.DESK] ?: emptyList()

    fun spotsOf(kind: SpotKind): List<Spot> = byKind[kind] ?: emptyList()

    val hotspots: List<HotspotDef> = OfficeMapData.HOTSPOTS
    val deskHotspot: HotspotDef = hotspots.first { it.id == "desk" }
}
