package com.agoria.app.ui.office.world

import androidx.compose.ui.geometry.Rect
import kotlin.collections.HashMap
import kotlin.math.hypot

/** Sprite sheet rows: down, left, right, up. */
object Dir {
    const val DOWN = 0
    const val LEFT = 1
    const val RIGHT = 2
    const val UP = 3
}

enum class SpotKind { DESK, KITCHEN, LIBRARY, MEETING, LOUNGE, QUEUE }

/** A place an agent can stand and do something. [face] is the direction they look while there. */
class Spot(val id: String, val kind: SpotKind, val x: Float, val y: Float, val face: Int)

/**
 * A small waypoint graph. Agents only ever walk along its edges, so they stay in the corridors
 * and doorways of the map image instead of cutting through walls and desks.
 */
class OfficeGraph(
    val ids: List<String>,
    val xs: FloatArray,
    val ys: FloatArray,
    val adj: Array<IntArray>,
    val spots: List<Spot>,
    private val index: Map<String, Int>,
) {
    val size: Int get() = ids.size
    val desks: List<Spot> = spots.filter { it.kind == SpotKind.DESK }

    fun indexOf(id: String): Int = index.getValue(id)

    fun spotsOf(kind: SpotKind): List<Spot> = spots.filter { it.kind == kind }

    fun nearest(x: Float, y: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until size) {
            val dx = xs[i] - x
            val dy = ys[i] - y
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** Shortest path as node indices, start and end included. Empty if unreachable. */
    fun path(from: Int, to: Int): IntArray {
        if (from == to) return intArrayOf(from)
        val n = size
        val dist = FloatArray(n) { Float.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val done = BooleanArray(n)
        dist[from] = 0f
        while (true) {
            var u = -1
            for (i in 0 until n) {
                if (!done[i] && dist[i] < Float.MAX_VALUE && (u == -1 || dist[i] < dist[u])) u = i
            }
            if (u == -1 || u == to) break
            done[u] = true
            for (v in adj[u]) {
                val d = dist[u] + hypot(xs[u] - xs[v], ys[u] - ys[v])
                if (d < dist[v]) {
                    dist[v] = d
                    prev[v] = u
                }
            }
        }
        if (prev[to] == -1) return IntArray(0)
        val out = ArrayList<Int>()
        var cur = to
        while (cur != -1) {
            out.add(cur)
            cur = prev[cur]
        }
        out.reverse()
        return out.toIntArray()
    }
}

/**
 * Coordinates are in "world units": the office image is stretched over an 800 x 800 square.
 * Turn on the debug overlay (long-press the title) to see every node and spot on the map and
 * tap anywhere to read its world coordinates, then nudge the numbers below.
 */
object OfficeLayout {
    const val WORLD = 800f

    /** The part of the image that is actually office (the rest is black margin). */
    val bounds = Rect(50f, 28f, 775f, 788f)

    /** Your office desk. Tapping it is how you will reach approvals and auto-approve. */
    val userDesk = Rect(213f, 80f, 362f, 228f)

    val graph: OfficeGraph = buildGraph()

    private class Builder {
        val ids = ArrayList<String>()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val spots = ArrayList<Spot>()
        private val index = HashMap<String, Int>()
        private val edges = ArrayList<IntArray>()

        fun node(id: String, x: Float, y: Float) {
            require(id !in index) { "duplicate node $id" }
            index[id] = ids.size
            ids.add(id)
            xs.add(x)
            ys.add(y)
        }

        fun edge(a: String, b: String) {
            edges.add(intArrayOf(index.getValue(a), index.getValue(b)))
        }

        fun lane(vararg chain: String) {
            for (i in 0 until chain.size - 1) edge(chain[i], chain[i + 1])
        }

        fun spot(kind: SpotKind, id: String, x: Float, y: Float, face: Int, link: String) {
            node(id, x, y)
            spots.add(Spot(id, kind, x, y, face))
            edge(id, link)
        }

        fun build(): OfficeGraph {
            val n = ids.size
            val lists = Array(n) { ArrayList<Int>() }
            for (e in edges) {
                lists[e[0]].add(e[1])
                lists[e[1]].add(e[0])
            }
            return OfficeGraph(
                ids = ids,
                xs = xs.toFloatArray(),
                ys = ys.toFloatArray(),
                adj = Array(n) { lists[it].toIntArray() },
                spots = spots,
                index = index,
            )
        }
    }

    private fun buildGraph(): OfficeGraph = Builder().apply {
        // ---- Main hall, north lane (just under the top rooms) ----
        node("hn_a", 185f, 250f)
        node("hn_b", 215f, 250f)
        node("hn_c", 258f, 250f)
        node("hn_d", 292f, 250f)
        node("hn_e", 326f, 250f)
        node("hn_f", 372f, 276f)
        node("hn_g", 440f, 276f)
        node("hn_h", 500f, 252f)
        node("hn_i", 580f, 246f)
        node("hn_j", 660f, 250f)
        lane("hn_a", "hn_b", "hn_c", "hn_d", "hn_e", "hn_f", "hn_g", "hn_h", "hn_i", "hn_j")

        // ---- West lane (kitchen side) ----
        node("wl_305", 185f, 305f)
        node("wl_345", 185f, 345f)
        node("wl_385", 185f, 385f)
        node("wl_410", 185f, 410f)
        lane("hn_a", "wl_305", "wl_345", "wl_385", "wl_410")

        // ---- East side of the long desk ----
        node("ea_350", 372f, 350f)
        lane("hn_f", "ea_350")

        // ---- Spine: top-right room door, hall, bottom-right room door ----
        node("sp_330", 500f, 330f)
        node("sp_420", 500f, 420f)
        node("sp_470", 498f, 470f)
        node("sp_door", 497f, 497f)
        node("br_hub", 497f, 525f)
        lane("hn_h", "sp_330", "sp_420", "sp_470", "sp_door", "br_hub")
        lane("ea_350", "sp_330")

        // ---- Top-right room ----
        node("tr_s", 503f, 205f)
        node("tr_n", 503f, 172f)
        node("tr_hub", 503f, 150f)
        node("tr_m1", 560f, 148f)
        node("tr_m2", 625f, 148f)
        node("tr_m3", 690f, 148f)
        lane("hn_h", "tr_s", "tr_n", "tr_hub", "tr_m1", "tr_m2", "tr_m3")

        // ---- East lane (library side) ----
        node("ne_300", 660f, 300f)
        node("ne_362", 660f, 362f)
        node("ne_400", 660f, 400f)
        node("ne_452", 660f, 452f)
        lane("hn_j", "ne_300", "ne_362", "ne_400", "ne_452")

        // ---- Bottom-right room: a loop around the meeting table ----
        node("br_n1", 598f, 530f)
        node("br_n2", 632f, 530f)
        node("br_ne", 716f, 530f)
        node("br_e1", 716f, 575f)
        node("br_e2", 716f, 612f)
        node("br_e3", 716f, 645f)
        node("br_se", 716f, 690f)
        node("br_s2", 645f, 690f)
        node("br_s1", 595f, 690f)
        node("br_sw", 508f, 690f)
        node("br_w3", 508f, 618f)
        node("br_w2", 508f, 595f)
        node("br_w1", 508f, 560f)
        lane(
            "br_hub", "br_n1", "br_n2", "br_ne", "br_e1", "br_e2", "br_e3",
            "br_se", "br_s2", "br_s1", "br_sw", "br_w3", "br_w2", "br_w1", "br_hub",
        )

        // ---- Desks (one per agent; matched by name, otherwise first free) ----
        spot(SpotKind.DESK, "desk_michael", 437f, 148f, Dir.UP, "tr_hub")
        spot(SpotKind.DESK, "desk_jim", 352f, 290f, Dir.LEFT, "hn_f")
        spot(SpotKind.DESK, "desk_pam", 350f, 350f, Dir.LEFT, "ea_350")
        spot(SpotKind.DESK, "desk_dwight", 222f, 345f, Dir.RIGHT, "wl_345")
        spot(SpotKind.DESK, "desk_angela", 222f, 410f, Dir.RIGHT, "wl_410")
        spot(SpotKind.DESK, "desk_kevin", 708f, 362f, Dir.RIGHT, "ne_362")
        spot(SpotKind.DESK, "desk_toby", 722f, 625f, Dir.RIGHT, "br_e2")
        spot(SpotKind.DESK, "desk_stanley", 470f, 595f, Dir.LEFT, "br_w2")

        // ---- Kitchen / coffee ----
        spot(SpotKind.KITCHEN, "kit_1", 150f, 305f, Dir.LEFT, "wl_305")
        spot(SpotKind.KITCHEN, "kit_2", 150f, 345f, Dir.LEFT, "wl_345")
        spot(SpotKind.KITCHEN, "kit_3", 150f, 385f, Dir.LEFT, "wl_385")

        // ---- Library (bookshelf on the east wall) ----
        spot(SpotKind.LIBRARY, "lib_1", 708f, 400f, Dir.RIGHT, "ne_400")
        spot(SpotKind.LIBRARY, "lib_2", 706f, 455f, Dir.RIGHT, "ne_452")

        // ---- Meeting table ----
        spot(SpotKind.MEETING, "meet_1", 598f, 556f, Dir.DOWN, "br_n1")
        spot(SpotKind.MEETING, "meet_2", 634f, 560f, Dir.DOWN, "br_n2")
        spot(SpotKind.MEETING, "meet_3", 693f, 578f, Dir.LEFT, "br_e1")
        spot(SpotKind.MEETING, "meet_4", 694f, 612f, Dir.LEFT, "br_e2")
        spot(SpotKind.MEETING, "meet_5", 694f, 645f, Dir.LEFT, "br_e3")
        spot(SpotKind.MEETING, "meet_6", 598f, 668f, Dir.UP, "br_s1")
        spot(SpotKind.MEETING, "meet_7", 645f, 668f, Dir.UP, "br_s2")
        spot(SpotKind.MEETING, "meet_8", 537f, 618f, Dir.RIGHT, "br_w3")

        // ---- Lounge (red sofa room) ----
        spot(SpotKind.LOUNGE, "lounge_1", 585f, 138f, Dir.UP, "tr_m1")
        spot(SpotKind.LOUNGE, "lounge_2", 625f, 138f, Dir.UP, "tr_m2")
        spot(SpotKind.LOUNGE, "lounge_3", 660f, 138f, Dir.UP, "tr_m3")

        // ---- Queue in front of your office (approvals) ----
        spot(SpotKind.QUEUE, "queue_1", 250f, 240f, Dir.UP, "hn_c")
        spot(SpotKind.QUEUE, "queue_2", 292f, 240f, Dir.UP, "hn_d")
        spot(SpotKind.QUEUE, "queue_3", 334f, 240f, Dir.UP, "hn_e")
        spot(SpotKind.QUEUE, "queue_4", 271f, 262f, Dir.UP, "hn_c")
        spot(SpotKind.QUEUE, "queue_5", 313f, 262f, Dir.UP, "hn_d")
    }.build()
}
