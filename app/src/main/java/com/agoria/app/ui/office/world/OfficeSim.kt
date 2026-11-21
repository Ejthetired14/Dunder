package com.agoria.app.ui.office.world

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.agoria.app.data.AgentEntity
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

enum class Activity {
    WORK, THINK, BROWSE, WEB, MAIL, SERVER, BUILD, MONEY,
    COFFEE, MEET, LOUNGE, REST, SLEEP, CHAT, APPROVAL, ERROR, IDLE,
}

enum class Bubble { NONE, ALERT, TALK, THINK, ERROR, ZZZ }

/** One agent's body in the world. Plain mutable fields on purpose: the sim ticks ~30x a second. */
class Actor(val agentId: String, val desk: Spot) {
    var name = ""
    var role = ""
    var avatar = 0
    var x = desk.x
    var y = desk.y
    var dir = Dir.DOWN
    var spot: Spot? = null
    var activity = Activity.IDLE
    var bubble = Bubble.NONE
    var walking = false
    var timer = 0f
    var dwell = 0f
    var anim = 0f
    var partner: Actor? = null
    var intentKey = "boot"
    var intentSince = 0f
    var detail = ""
    val route = ArrayList<Offset>()
    var routeIdx = 0
}

fun Actor.statusText(): String {
    val base = when {
        bubble == Bubble.TALK && partner != null -> "Chatting with ${partner?.name ?: "a coworker"}"
        activity == Activity.WORK -> "Working at their desk"
        activity == Activity.THINK -> "Thinking at their desk"
        activity == Activity.BROWSE -> "Researching in the library"
        activity == Activity.WEB -> "In the web room"
        activity == Activity.MAIL -> "At the mail wall"
        activity == Activity.SERVER -> "In the server room"
        activity == Activity.BUILD -> "Building in the workshop"
        activity == Activity.MONEY -> "In finance"
        activity == Activity.COFFEE -> "Getting coffee"
        activity == Activity.MEET -> "In the war room"
        activity == Activity.LOUNGE -> "Taking a break"
        activity == Activity.REST -> "Resting (paused)"
        activity == Activity.SLEEP -> "Sleeping"
        activity == Activity.APPROVAL -> "Waiting for your approval"
        activity == Activity.ERROR -> "Something went wrong"
        activity == Activity.CHAT -> "Going to talk to ${partner?.name ?: "a coworker"}"
        else -> "Idle"
    }
    val withDetail = if (detail.isNotBlank() && activity != Activity.CHAT && activity != Activity.APPROVAL) "$base · $detail" else base
    return if (walking && activity != Activity.CHAT) "On the way: $withDetail" else withDetail
}

/**
 * The office world. [applySignals] says what each agent is really doing (from the database);
 * the sim walks them to the matching room and keeps them there until that changes. Agents with
 * nothing to do wander on their own (coffee, lounge, chats) so the office never looks frozen.
 */
class OfficeSim {
    val actors = ArrayList<Actor>()

    /** The agent whose card is open. They do not start idle wandering while it is open. */
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
    private var signals: Map<String, AgentSignal> = emptyMap()

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
                a.timer = 1f + rng.nextFloat() * 4f
                actors.add(a)
            }
            a.name = ag.name
            a.role = ag.role
            a.avatar = ag.avatarIndex.coerceIn(0, 7)
        }
        revision++
    }

    fun applySignals(next: Map<String, AgentSignal>) {
        signals = next
    }

    fun update(dt: Float) {
        elapsed += dt
        for (a in actors) {
            a.anim += dt
            val want = intentOf(a)
            if (want != a.intentKey && (a.intentKey == "boot" || want == "approval" || elapsed - a.intentSince >= MIN_HOLD)) {
                switchIntent(a, want)
            } else if (a.intentKey == want) {
                val d = signals[a.agentId]?.detail.orEmpty()
                if (d != a.detail) {
                    a.detail = d
                    revision++
                }
            }
            if (a.walking) {
                move(a, dt)
                continue
            }
            if (a.intentKey == "idle" && a.agentId != holdId) {
                a.timer -= dt
                if (a.timer <= 0f) ambient(a)
            }
        }
    }

    // ---- what the agent is really doing ----

    private fun intentOf(a: Actor): String {
        val s = signals[a.agentId] ?: return "idle"
        return when {
            s.needsApproval -> "approval"
            s.status == "error" -> "error"
            s.status == "paused" -> "rest"
            s.status == "sleeping" -> "sleep"
            s.building -> "build"
            s.place != null -> "place:${s.place.name}"
            s.status == "thinking" -> "think"
            s.status == "working" -> "work"
            else -> "idle"
        }
    }

    private fun switchIntent(a: Actor, key: String) {
        endChat(a)
        a.intentKey = key
        a.intentSince = elapsed
        a.detail = signals[a.agentId]?.detail.orEmpty()
        when {
            key == "approval" -> go(a, firstFree(a, SpotKind.QUEUE), Activity.APPROVAL, FOREVER)
            key == "error" -> go(a, a.desk, Activity.ERROR, FOREVER)
            key == "rest" -> go(a, freeSpot(a, SpotKind.LOUNGE), Activity.REST, FOREVER)
            key == "sleep" -> go(a, freeSpot(a, SpotKind.LOUNGE), Activity.SLEEP, FOREVER)
            key == "build" -> go(a, freeSpot(a, SpotKind.WORKSHOP), Activity.BUILD, FOREVER)
            key.startsWith("place:") -> {
                val kind = SpotKind.valueOf(key.removePrefix("place:"))
                val act = when (kind) {
                    SpotKind.LIBRARY -> Activity.BROWSE
                    SpotKind.WEB -> Activity.WEB
                    SpotKind.MAIL -> Activity.MAIL
                    SpotKind.SERVER -> Activity.SERVER
                    SpotKind.FINANCE -> Activity.MONEY
                    else -> Activity.WORK
                }
                go(a, freeSpot(a, kind), act, FOREVER)
            }
            key == "think" -> go(a, a.desk, Activity.THINK, FOREVER)
            key == "work" -> go(a, a.desk, Activity.WORK, FOREVER)
            else -> go(a, a.desk, Activity.IDLE, 2f + rng.nextFloat() * 5f)
        }
    }

    // ---- idle wandering ----

    private fun ambient(a: Actor) {
        endChat(a)
        a.bubble = Bubble.NONE
        val roll = rng.nextFloat()
        when {
            roll < 0.34f -> go(a, a.desk, Activity.IDLE, 8f + rng.nextFloat() * 14f)
            roll < 0.50f -> go(a, freeSpot(a, SpotKind.KITCHEN), Activity.COFFEE, 8f + rng.nextFloat() * 6f)
            roll < 0.62f -> go(a, freeSpot(a, SpotKind.LOUNGE), Activity.LOUNGE, 8f + rng.nextFloat() * 8f)
            roll < 0.74f -> go(a, freeSpot(a, SpotKind.MEETING), Activity.MEET, 10f + rng.nextFloat() * 10f)
            roll < 0.82f -> go(a, freeSpot(a, SpotKind.LIBRARY), Activity.BROWSE, 8f + rng.nextFloat() * 8f)
            else -> startChat(a)
        }
    }

    private fun go(a: Actor, spot: Spot?, act: Activity, dwell: Float) {
        val target = spot ?: a.desk
        val activity = if (spot == null && act != Activity.IDLE && act != Activity.THINK && act != Activity.ERROR) Activity.WORK else act
        release(a)
        if (target.kind != SpotKind.DESK) taken[target.id] = a.agentId
        a.spot = target
        a.activity = activity
        a.dwell = dwell
        a.bubble = Bubble.NONE
        a.route.clear()
        a.routeIdx = 0
        if (hypot(a.x - target.x, a.y - target.y) < 2f) {
            a.walking = false
            arrive(a)
            return
        }
        a.route.addAll(OfficeLayout.grid.route(a.x, a.y, target.tx, target.ty))
        a.route.add(Offset(target.x, target.y))
        a.walking = true
        revision++
    }

    private fun startChat(a: Actor) {
        val p = actors.filter {
            it !== a && !it.walking && it.partner == null && it.intentKey == "idle" &&
                it.activity != Activity.APPROVAL && it.bubble == Bubble.NONE
        }.randomOrNull(rng)
        if (p == null) {
            go(a, a.desk, Activity.IDLE, 8f + rng.nextFloat() * 8f)
            return
        }
        var side = if (a.x < p.x) -1f else 1f
        if (!OfficeLayout.grid.walkableAt(p.x + side * 30f, p.y)) side = -side
        val destX = p.x + side * 30f
        if (!OfficeLayout.grid.walkableAt(destX, p.y)) {
            go(a, a.desk, Activity.IDLE, 8f + rng.nextFloat() * 8f)
            return
        }
        release(a)
        a.spot = null
        a.activity = Activity.CHAT
        a.partner = p
        p.partner = a
        a.dwell = 6f + rng.nextFloat() * 4f
        a.route.clear()
        a.routeIdx = 0
        val tx = (destX / OfficeLayout.TILE).toInt()
        val ty = ((p.y - 1f) / OfficeLayout.TILE).toInt()
        a.route.addAll(OfficeLayout.grid.route(a.x, a.y, tx, ty))
        a.route.add(Offset(destX, p.y))
        a.walking = true
        var len = 0f
        var px = a.x
        var py = a.y
        for (pt in a.route) {
            len += hypot(pt.x - px, pt.y - py)
            px = pt.x
            py = pt.y
        }
        p.timer = maxOf(p.timer, len / SPEED + a.dwell + 1.5f)
        revision++
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
        a.routeIdx = 0
        val s = a.spot
        if (s != null) {
            a.x = s.x
            a.y = s.y
            a.dir = s.face
        }
        a.timer = a.dwell
        a.bubble = when (a.activity) {
            Activity.APPROVAL -> Bubble.ALERT
            Activity.THINK -> Bubble.THINK
            Activity.ERROR -> Bubble.ERROR
            Activity.REST, Activity.SLEEP -> Bubble.ZZZ
            else -> Bubble.NONE
        }
        if (a.activity == Activity.CHAT) {
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
        OfficeLayout.spotsOf(kind).filter { isFreeFor(a, it) }.randomOrNull(rng)

    private fun firstFree(a: Actor, kind: SpotKind): Spot? =
        OfficeLayout.spotsOf(kind).firstOrNull { isFreeFor(a, it) }

    private fun pickDesk(name: String): Spot {
        val desks = OfficeLayout.desks
        val key = name.trim().lowercase()
        desks.firstOrNull { it.id == "desk_$key" && deskOwner[it.id] == null }?.let { return it }
        desks.firstOrNull { deskOwner[it.id] == null }?.let { return it }
        return desks[actors.size % desks.size]
    }

    private fun dirOf(dx: Float, dy: Float): Int =
        if (abs(dx) > abs(dy)) {
            if (dx < 0f) Dir.LEFT else Dir.RIGHT
        } else {
            if (dy < 0f) Dir.UP else Dir.DOWN
        }

    companion object {
        /** World px per second. A tile is 32, so this is about two tiles a second. */
        const val SPEED = 64f

        /** An agent stays in a room at least this long before changing, so quick status flips do not make them run around. */
        const val MIN_HOLD = 4f

        private const val FOREVER = 1_000_000f
    }
}
