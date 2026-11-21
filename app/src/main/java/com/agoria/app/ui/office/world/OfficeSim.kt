package com.agoria.app.ui.office.world

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.agoria.app.data.AgentEntity
import kotlin.collections.HashMap
import kotlin.comparisons.compareBy
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

enum class Activity { WORK, BROWSE, COFFEE, MEET, LOUNGE, CHAT, APPROVAL, IDLE }

enum class Bubble { NONE, ALERT, TALK }

/** One agent's body in the world. Plain mutable fields on purpose: the sim ticks ~30x a second. */
class Actor(val agentId: String, val desk: Spot) {
    var name = ""
    var role = ""
    var avatar = 0
    var x = desk.x
    var y = desk.y
    var dir = desk.face
    var node = -1
    var spot: Spot? = null
    var activity = Activity.WORK
    var bubble = Bubble.NONE
    var walking = false
    var timer = 0f
    var dwell = 0f
    var anim = 0f
    var partner: Actor? = null
    val route = ArrayList<Offset>()
    val routeNodes = ArrayList<Int>()
    var routeIdx = 0
}

fun Actor.statusText(): String {
    val base = when {
        bubble == Bubble.TALK && partner != null -> "Chatting with ${partner?.name ?: "a coworker"}"
        activity == Activity.WORK -> "Working at their desk"
        activity == Activity.BROWSE -> "Browsing in the library"
        activity == Activity.COFFEE -> "Getting coffee"
        activity == Activity.MEET -> "In the meeting room"
        activity == Activity.LOUNGE -> "Taking a break"
        activity == Activity.APPROVAL -> "Waiting for your approval"
        activity == Activity.CHAT -> "Going to talk to ${partner?.name ?: "a coworker"}"
        else -> "Thinking it over"
    }
    return if (walking && activity != Activity.CHAT) "On the way: $base" else base
}

/**
 * Phase 1: a self-running demo. Real agents from the database get bodies and wander the office
 * on a schedule of their own. Phase 2 replaces [decide] with intents coming from the agent
 * runtime (what the agent actually decided to do), without touching rendering.
 */
class OfficeSim(private val g: OfficeGraph = OfficeLayout.graph) {
    val actors = ArrayList<Actor>()

    /** The agent whose card is open. They finish their walk and then stay put. */
    var holdId: String? = null

    /** Seconds since start, for bubble animations. */
    var elapsed = 0f
        private set

    /** Bumped on discrete changes (activity, arrivals) so the UI can recompose without a per-frame cost. */
    var revision by mutableIntStateOf(0)
        private set

    private val deskOwner = HashMap<String, String>()
    private val taken = HashMap<String, String>()
    private val rng = Random(System.nanoTime())

    fun sync(agents: List<AgentEntity>) {
        val ids = agents.map { it.id }.toSet()
        val iter = actors.iterator()
        while (iter.hasNext()) {
            val a = iter.next()
            if (a.agentId !in ids) {
                release(a)
                endChat(a)
                if (deskOwner[a.desk.id] == a.agentId) deskOwner.remove(a.desk.id)
                iter.remove()
            }
        }
        for (ag in agents.sortedWith(compareBy<AgentEntity>({ it.createdAt }, { it.id }))) {
            var a = actors.firstOrNull { it.agentId == ag.id }
            if (a == null) {
                val desk = pickDesk(ag.name)
                deskOwner[desk.id] = ag.id
                a = Actor(ag.id, desk)
                a.node = g.indexOf(desk.id)
                a.timer = 1f + rng.nextFloat() * 5f
                actors.add(a)
            }
            a.name = ag.name
            a.role = ag.role
            a.avatar = ag.avatarIndex.coerceIn(0, 7)
        }
        revision++
    }

    fun update(dt: Float) {
        elapsed += dt
        for (a in actors) {
            a.anim += dt
            if (a.walking) {
                move(a, dt)
                continue
            }
            if (a.agentId == holdId) continue
            a.timer -= dt
            if (a.timer <= 0f) decide(a)
        }
    }

    // ---- demo director ----

    private fun decide(a: Actor) {
        endChat(a)
        a.bubble = Bubble.NONE
        if (a.activity == Activity.APPROVAL) {
            goTo(a, a.desk, Activity.WORK, 12f, 24f)
            return
        }
        val approvals = actors.count { it.activity == Activity.APPROVAL }
        val roll = rng.nextFloat()
        when {
            roll < 0.32f -> goTo(a, a.desk, Activity.WORK, 14f, 28f)
            roll < 0.48f -> goTo(a, freeSpot(a, SpotKind.LIBRARY), Activity.BROWSE, 10f, 18f)
            roll < 0.62f -> goTo(a, freeSpot(a, SpotKind.KITCHEN), Activity.COFFEE, 8f, 14f)
            roll < 0.74f -> goTo(a, freeSpot(a, SpotKind.MEETING), Activity.MEET, 12f, 22f)
            roll < 0.84f -> goTo(a, freeSpot(a, SpotKind.LOUNGE), Activity.LOUNGE, 8f, 14f)
            roll < 0.93f -> startChat(a)
            approvals < 2 -> goTo(a, firstFree(a, SpotKind.QUEUE), Activity.APPROVAL, 20f, 28f)
            else -> goTo(a, a.desk, Activity.WORK, 14f, 28f)
        }
    }

    private fun goTo(a: Actor, spot: Spot?, activity: Activity, minDwell: Float, maxDwell: Float) {
        val target = spot ?: a.desk
        val act = if (spot == null) Activity.WORK else activity
        release(a)
        if (target.kind != SpotKind.DESK) taken[target.id] = a.agentId
        a.spot = target
        a.activity = act
        a.dwell = minDwell + rng.nextFloat() * (maxDwell - minDwell)
        startRoute(a, g.indexOf(target.id), null)
        revision++
    }

    private fun startChat(a: Actor) {
        val p = actors.filter {
            it !== a && !it.walking && it.partner == null &&
                it.activity != Activity.APPROVAL && it.bubble == Bubble.NONE
        }.randomOrNull(rng)
        if (p == null) {
            goTo(a, a.desk, Activity.WORK, 10f, 20f)
            return
        }
        release(a)
        a.spot = null
        a.activity = Activity.CHAT
        a.partner = p
        p.partner = a
        val side = if (a.x < p.x) -1f else 1f
        val dest = Offset(p.x + side * 26f, p.y)
        a.dwell = 6f + rng.nextFloat() * 4f
        val targetNode = if (p.node >= 0) p.node else g.nearest(p.x, p.y)
        val len = startRoute(a, targetNode, dest)
        p.timer = maxOf(p.timer, len / SPEED + a.dwell + 1.5f)
        revision++
    }

    /** Builds the walking route and returns its length in world units. */
    private fun startRoute(a: Actor, targetNode: Int, finalLeg: Offset?): Float {
        a.route.clear()
        a.routeNodes.clear()
        a.routeIdx = 0
        val from = if (a.node >= 0) a.node else g.nearest(a.x, a.y)
        for (n in g.path(from, targetNode)) {
            a.route.add(Offset(g.xs[n], g.ys[n]))
            a.routeNodes.add(n)
        }
        if (finalLeg != null) {
            a.route.add(finalLeg)
            a.routeNodes.add(-1)
        }
        var len = 0f
        var px = a.x
        var py = a.y
        for (pt in a.route) {
            len += hypot(pt.x - px, pt.y - py)
            px = pt.x
            py = pt.y
        }
        a.walking = a.route.isNotEmpty() && len > 1f
        if (!a.walking) arrive(a)
        return len
    }

    private fun move(a: Actor, dt: Float) {
        var budget = SPEED * dt
        while (budget > 0f && a.routeIdx < a.route.size) {
            val t = a.route[a.routeIdx]
            val dx = t.x - a.x
            val dy = t.y - a.y
            val d = hypot(dx, dy)
            if (d <= budget) {
                a.x = t.x
                a.y = t.y
                budget -= d
                val n = a.routeNodes[a.routeIdx]
                if (n >= 0) a.node = n
                a.routeIdx++
            } else {
                a.x += dx / d * budget
                a.y += dy / d * budget
                a.dir = dirOf(dx, dy)
                budget = 0f
            }
        }
        if (a.routeIdx >= a.route.size) arrive(a)
    }

    private fun arrive(a: Actor) {
        a.walking = false
        a.route.clear()
        a.routeNodes.clear()
        a.routeIdx = 0
        val s = a.spot
        if (s != null) {
            a.x = s.x
            a.y = s.y
            a.dir = s.face
            a.node = g.indexOf(s.id)
        }
        a.timer = a.dwell
        when (a.activity) {
            Activity.APPROVAL -> a.bubble = Bubble.ALERT
            Activity.CHAT -> {
                val p = a.partner
                if (p != null && !p.walking && p.partner === a) {
                    a.bubble = Bubble.TALK
                    p.bubble = Bubble.TALK
                    a.dir = dirOf(p.x - a.x, p.y - a.y)
                    p.dir = dirOf(a.x - p.x, a.y - p.y)
                    p.timer = maxOf(p.timer, a.dwell + 0.5f)
                } else {
                    a.partner = null
                    a.activity = Activity.IDLE
                    a.timer = 1.5f
                }
            }
            else -> a.bubble = Bubble.NONE
        }
        revision++
    }

    private fun endChat(a: Actor) {
        val p = a.partner ?: return
        a.partner = null
        if (p.partner === a) {
            p.partner = null
            if (p.bubble == Bubble.TALK) p.bubble = Bubble.NONE
        }
        if (a.bubble == Bubble.TALK) a.bubble = Bubble.NONE
    }

    private fun release(a: Actor) {
        val s = a.spot ?: return
        if (taken[s.id] == a.agentId) taken.remove(s.id)
    }

    private fun isFreeFor(a: Actor, s: Spot): Boolean {
        val owner = taken[s.id]
        return owner == null || owner == a.agentId
    }

    private fun freeSpot(a: Actor, kind: SpotKind): Spot? =
        g.spotsOf(kind).filter { isFreeFor(a, it) }.randomOrNull(rng)

    private fun firstFree(a: Actor, kind: SpotKind): Spot? =
        g.spotsOf(kind).firstOrNull { isFreeFor(a, it) }

    private fun pickDesk(name: String): Spot {
        val key = name.trim().lowercase()
        g.desks.firstOrNull { it.id == "desk_$key" && deskOwner[it.id] == null }?.let { return it }
        g.desks.firstOrNull { deskOwner[it.id] == null }?.let { return it }
        return g.desks[actors.size % g.desks.size]
    }

    private fun dirOf(dx: Float, dy: Float): Int =
        if (abs(dx) > abs(dy)) {
            if (dx < 0f) Dir.LEFT else Dir.RIGHT
        } else {
            if (dy < 0f) Dir.UP else Dir.DOWN
        }

    companion object {
        /** World units per second. The whole map is 800 wide, so crossing it takes about 12 s. */
        const val SPEED = 64f
    }
}
