package io.github.user4ooksk.stopcard.policy

import java.util.concurrent.atomic.AtomicLong

/** Facts have framework provenance. Intent extras never create an [OpenSource]. */
enum class OpenSource { LAUNCHER, RECENTS, NOTIFICATION, SYSTEM_CHOOSER, INTERACTION }
enum class EntryKind { ACTIVITY, SERVICE, BIND, BROADCAST, PROVIDER, JOB, ALARM, PENDING_INTENT, PROCESS, NOTIFICATION }

data class AppKey(
    val userId: Int,
    val packageName: String,
    val uid: Int,
    val installedAt: Long,
    val signer: String,
)

data class TaskKey(val userId: Int, val taskId: Int, val boot: String)

data class TaskFact(
    val key: TaskKey,
    val owner: String,
    val participants: Set<String>,
    val visiblePackages: Set<String>,
    val eligible: Boolean,
)

data class WorldSnapshot(
    val tasks: List<TaskFact>,
    val visiblePackages: Set<String>,
    val complete: Boolean,
    val boot: String,
    val userId: Int = 0,
)

data class AccessRule(
    val enabled: Boolean = false,
    val observe: Boolean = true,
    val generation: Long = 0,
    val reviewMs: Long = 500,
    val hiddenReviewMs: Long = 30_000,
    val flowLimitMs: Long = 10 * 60_000,
    val exceptions: Set<AppKey> = emptySet(),
    val essential: Map<String, Set<String>> = emptyMap(),
)

data class CallTarget(val packageName: String, val component: String? = null, val kind: EntryKind)
data class EntryDecision(val allow: Boolean, val reason: String, val wouldBlock: Boolean = false)
data class StopTicket(val app: AppKey, val revision: Long, val configGeneration: Long, val reason: String)

data class PermissionView(
    val app: AppKey,
    val cards: Int,
    val visible: Boolean,
    val flows: Int,
    val state: String,
    val reasons: Set<String>,
    val stopAt: Long?,
)

/**
 * One policy for cards, system entries, notifications and force-stop. All times are monotonic.
 *
 * Framework hooks only update in-memory facts. They never invoke Android while holding this
 * object's monitor. Entry decisions read the immutable published view. A stop ticket remains
 * tentative until the worker reports the result; one exception cannot strand the rest of a batch.
 */
class PermissionPolicy {
    private class Track(val app: AppKey) {
        val activated = HashSet<TaskKey>()
        var cards = emptySet<TaskKey>()
        var candidates = emptySet<TaskKey>()
        var visible = false
        var wasVisible = false
        var closing = false
        var reason = ""
        var due: Long? = null
        var stopped = false
        var observation: Long? = null
        var openedUntil = 0L
        var uncertainUntil = 0L
    }

    private data class Grant(val target: CallTarget)
    private class Flow(
        val id: Long,
        val source: String,
        val activity: String?,
        val created: Long,
        var reviewAt: Long,
        val observed: Boolean = false,
    ) {
        val grants = HashSet<Grant>()
        val callbacks = HashSet<String>()
        val bindings = HashMap<String, Grant>()
        val activities = HashSet<String>()
        var delivered = false
        var canceled = false
        var needsReview = false
    }

    private data class Published(
        val rule: AccessRule = AccessRule(),
        val ready: Boolean = false,
        val paused: Boolean = true,
        val apps: Map<String, AppKey> = emptyMap(),
        val states: Map<String, PermissionView> = emptyMap(),
        val grants: Map<String, Set<CallTarget>> = emptyMap(),
        val versions: Map<String, Long> = emptyMap(),
    )

    private val tracks = LinkedHashMap<String, Track>()
    private val flows = LinkedHashMap<Long, Flow>()
    private val revisions = HashMap<String, Long>()
    private val serial = AtomicLong()
    private var rule = AccessRule()
    private var ready = false
    private var paused = true
    private var boot: String? = null
    private var lastNow = 0L
    private var taskOwners = emptyMap<TaskKey, String>()
    private var lastWorld: WorldSnapshot? = null

    @Volatile private var published = Published()

    /** Installation changes invalidate every old card, exception match, flow and stop ticket. */
    @Synchronized fun setApps(apps: Collection<AppKey>, now: Long) {
        val primary = apps.filter { it.userId == 0 }.associateBy { it.packageName }
        for ((pkg, old) in tracks.toMap()) {
            if (primary[pkg] != old.app) {
                tracks.remove(pkg)
                invalidate(pkg)
                flows.entries.removeAll { (_, f) -> f.source == pkg || f.grants.any { it.target.packageName == pkg } }
            }
        }
        for ((pkg, app) in primary) tracks.getOrPut(pkg) { Track(app) }
        publish(now)
    }

    @Synchronized fun configure(next: AccessRule, now: Long) {
        if (next == rule) return
        rule = next
        for ((pkg, t) in tracks) {
            invalidate(pkg)
            t.due = null
            t.observation = null
        }
        publish(now)
    }

    /** Pause cancels work but preserves this boot's verified task and flow identities. */
    @Synchronized fun pause(value: Boolean, now: Long) {
        if (paused == value) return
        paused = value
        for ((pkg, t) in tracks) {
            invalidate(pkg)
            t.due = null
        }
        publish(now)
    }

    /** A lost fact source makes pending deadlines unusable. Recovery starts a new short review. */
    @Synchronized fun unknown(now: Long) {
        ready = false
        for ((pkg, t) in tracks) {
            invalidate(pkg)
            t.due = null
        }
        publish(now)
    }

    /**
     * Called before a framework-verified open can reach a process start. This invalidates old
     * work even if the new Activity opens and hides between two reconciliation snapshots.
     */
    @Synchronized fun opened(pkg: String, task: TaskKey?, source: OpenSource, now: Long) {
        val t = tracks[pkg] ?: return
        invalidate(pkg)
        t.stopped = false
        t.closing = false
        t.due = null
        t.observation = null
        t.openedUntil = now + OPEN_CONFIRM_MS
        if (task != null && task.userId == 0 && (boot == null || task.boot == boot)) t.activated += task
        // The source is an enum produced by the adapter, never an application-provided string.
        t.reason = "opened_${source.name.lowercase()}"
        publish(now)
    }

    /** Visible windows protect all their packages, even inside excluded/shared tasks. */
    @Synchronized fun visible(pkg: String, task: TaskKey?, value: Boolean, now: Long) {
        val t = tracks[pkg] ?: return
        if (value) {
            invalidate(pkg)
            t.visible = true
            t.wasVisible = true
            t.stopped = false
            t.due = null
            if (task != null && t.openedUntil >= now) t.activated += task
        } else if (t.visible) {
            invalidate(pkg)
            t.visible = false
            t.closing = true
            t.reason = "last_visible"
        }
        publish(now)
    }

    /** Retain close events during initialization. Only a trusted caller marks [userClosed]. */
    @Synchronized fun removed(task: TaskKey, owner: String?, userClosed: Boolean, now: Long) {
        val pkg = owner ?: taskOwners[task] ?: return
        val t = tracks[pkg] ?: return
        val active = t.activated.remove(task)
        t.cards = t.cards - task
        t.candidates = t.candidates - task
        if (active || userClosed) {
            invalidate(pkg)
            t.closing = true
            t.reason = if (userClosed) "user_closed" else "card_removed"
            t.due = null
        }
        publish(now)
    }

    @Synchronized fun reconcile(world: WorldSnapshot, now: Long) {
        if (!world.complete || world.userId != 0 || world.tasks.size > MAX_TASKS) {
            unknown(now)
            return
        }
        if (boot != null && boot != world.boot) {
            flows.clear()
            for ((pkg, t) in tracks) {
                invalidate(pkg)
                t.activated.clear()
                t.cards = emptySet()
                t.candidates = emptySet()
                t.closing = false
                t.due = null
                t.openedUntil = 0
                t.stopped = false
            }
            ready = false
        }
        boot = world.boot
        val first = lastWorld == null || lastWorld?.boot != world.boot
        val byOwner = world.tasks.filter { it.key.userId == 0 && it.key.boot == world.boot }.groupBy { it.owner }
        for ((pkg, t) in tracks) {
            val owned = byOwner[pkg].orEmpty()
            val eligible = owned.filter { it.eligible }.mapTo(HashSet()) { it.key }
            val wasCards = t.cards
            val appeared = eligible - t.candidates
            if (!first && (appeared - t.activated).isNotEmpty()) {
                // A missed open is uncertainty, not permission. Cancel the old stop and wait for
                // event provenance. It must never kill a newly opened session using an old ticket.
                invalidate(pkg)
                t.due = null
                t.uncertainUntil = now + OPEN_CONFIRM_MS
            }
            val onScreen = pkg in world.visiblePackages || world.tasks.any { it.key.userId == 0 && pkg in it.visiblePackages }
            if (onScreen != t.visible) {
                invalidate(pkg)
                if (t.visible && !onScreen) {
                    t.closing = true
                    t.reason = "last_visible"
                }
            }
            t.visible = onScreen
            if (onScreen) t.wasVisible = true
            if (t.openedUntil >= now) {
                // An explicit open may precede task creation. Only tasks actually seen visible
                // in that open's confirmation window inherit its activation.
                t.activated += owned.filter { pkg in it.visiblePackages }.map { it.key }
            }
            t.activated.retainAll(eligible)
            t.cards = eligible.intersect(t.activated)
            if (t.cards.isNotEmpty()) {
                t.openedUntil = 0
                t.uncertainUntil = 0
            }
            t.candidates = eligible
            if (wasCards.isNotEmpty() && t.cards.isEmpty()) {
                invalidate(pkg)
                t.closing = true
                t.reason = "card_removed"
            }
            if (t.cards.isNotEmpty() || onScreen) {
                t.due = null
                t.stopped = false
                t.observation = null
            }
        }
        taskOwners = world.tasks.associate { it.key to it.owner }
        lastWorld = world
        ready = true
        reviewFlows(now)
        publish(now)
    }

    /**
     * A flow can start only from a confirmed input/launcher/chooser/notification event. A visible
     * source alone is insufficient. Component grants are exact; they do not authorize arbitrary
     * services or other applications.
     */
    @Synchronized fun beginFlow(
        source: String,
        sourceActivity: String?,
        target: CallTarget,
        evidence: OpenSource,
        now: Long,
        callback: String? = null,
    ): Long? {
        if (source !in tracks || target.packageName !in tracks || flows.size >= MAX_FLOWS) return null
        val id = serial.incrementAndGet()
        val f = Flow(id, source, sourceActivity, now, now + rule.hiddenReviewMs)
        f.grants += Grant(target)
        if (callback != null) f.callbacks += callback
        flows[id] = f
        opened(target.packageName, null, evidence, now)
        invalidate(source)
        publish(now)
        return id
    }

    /** A visible caller without input provenance is observed, never a source of card activation. */
    @Synchronized fun observeFlow(source: String, sourceActivity: String?, target: CallTarget, now: Long, callback: String?): Long? {
        if (source !in tracks || target.packageName !in tracks || flows.size >= MAX_FLOWS) return null
        val f = Flow(serial.incrementAndGet(), source, sourceActivity, now, now + rule.hiddenReviewMs, true)
        f.grants += Grant(target)
        if (callback != null) f.callbacks += callback
        flows[f.id] = f
        invalidate(source)
        invalidate(target.packageName)
        tracks[target.packageName]?.due = null
        publish(now)
        return f.id
    }

    @Synchronized fun addCallback(id: Long, callback: String, now: Long) {
        val f = flows[id] ?: return
        if (f.callbacks.size >= MAX_FLOW_TARGETS) return
        f.callbacks += callback
        f.delivered = false
        publish(now)
    }

    /** Extension needs both an existing flow and fresh input for a new target. */
    @Synchronized fun extendFlow(id: Long, from: String, target: CallTarget, hasInput: Boolean, now: Long): Boolean {
        val f = flows[id] ?: return false
        if (!hasInput || !contains(f, from) || f.grants.size >= MAX_FLOW_TARGETS || target.packageName !in tracks) return false
        f.grants += Grant(target)
        f.reviewAt = now + rule.hiddenReviewMs
        invalidate(target.packageName)
        publish(now)
        return true
    }

    /** Dependencies may only address an existing participant and an exact resolved component. */
    @Synchronized fun dependency(id: Long, from: String, target: CallTarget, now: Long): Boolean {
        val f = flows[id] ?: return false
        if (!contains(f, from) || !contains(f, target.packageName) || target.component.isNullOrBlank() ||
            target.kind !in setOf(EntryKind.SERVICE, EntryKind.BIND, EntryKind.PROVIDER, EntryKind.BROADCAST) ||
            f.grants.size >= MAX_FLOW_TARGETS) return false
        f.grants += Grant(target)
        f.reviewAt = now + rule.hiddenReviewMs
        invalidate(target.packageName)
        publish(now)
        return true
    }

    @Synchronized fun flowActivity(id: Long, token: String, added: Boolean, now: Long) {
        val f = flows[id] ?: return
        if (added && f.activities.size >= MAX_FLOW_TARGETS && token !in f.activities) {
            unknown(now)
            return
        }
        if (added) f.activities += token else f.activities -= token
        f.reviewAt = now + rule.hiddenReviewMs
        publish(now)
    }

    @Synchronized fun bind(id: Long, token: String, target: CallTarget, now: Long): Boolean {
        val f = flows[id] ?: return false
        if (Grant(target) !in f.grants || f.bindings.size >= MAX_FLOW_TARGETS) return false
        f.bindings[token] = Grant(target)
        invalidate(target.packageName)
        publish(now)
        return true
    }

    @Synchronized fun unbind(token: String, now: Long) {
        flows.values.forEach { f ->
            if (f.bindings.remove(token) != null) f.reviewAt = now + rule.hiddenReviewMs
        }
        reviewFlows(now)
        publish(now)
    }

    @Synchronized fun callbackDelivered(token: String, now: Long) {
        flows.values.forEach { f ->
            if (f.callbacks.remove(token)) {
                f.delivered = f.callbacks.isEmpty()
                f.reviewAt = now + rule.hiddenReviewMs
            }
        }
        reviewFlows(now)
        publish(now)
    }

    @Synchronized fun finishFlow(id: Long, canceled: Boolean, now: Long) {
        val f = flows[id] ?: return
        f.canceled = canceled
        f.delivered = true
        f.reviewAt = now
        reviewFlows(now)
        publish(now)
    }

    /** A timeout asks for reconciliation; unresolved callbacks/bindings never become a kill timer. */
    private fun reviewFlows(now: Long) {
        val finished = flows.values.filter { f ->
            if (now < f.reviewAt) return@filter false
            val visible = f.grants.any { tracks[it.target.packageName]?.visible == true }
            f.needsReview = now - f.created >= rule.flowLimitMs
            (f.delivered || f.canceled) && !visible && f.callbacks.isEmpty() &&
                f.bindings.isEmpty() && f.activities.isEmpty()
        }
        for (f in finished) {
            flows.remove(f.id)
            for (pkg in flowPackages(f)) tracks[pkg]?.let { t ->
                invalidate(pkg)
                t.closing = true
                t.reason = "flow_finished"
                t.due = null
            }
        }
    }

    /** Known running evidence permits a targeted review, never an initialization sweep. */
    @Synchronized fun runningWithoutPermission(pkg: String, now: Long) {
        val t = tracks[pkg] ?: return
        if (!ready || permitted(t, now)) return
        t.closing = true
        t.reason = "unlicensed_running"
        publish(now)
    }

    @Synchronized fun stops(now: Long): List<StopTicket> {
        reviewFlows(now)
        if (!ready || paused || !rule.enabled) return emptyList()
        val result = ArrayList<StopTicket>()
        for ((pkg, t) in tracks) {
            if (!t.closing || t.stopped || permitted(t, now)) {
                t.due = null
                continue
            }
            val revision = revisions[pkg] ?: 0
            if (t.observation == revision && rule.observe) continue
            val due = t.due
            if (due == null) t.due = now + rule.reviewMs
            else if (now >= due) result += StopTicket(t.app, revision, rule.generation, t.reason)
        }
        publish(now)
        return result
    }

    /** Final memory check; the adapter also checks Android identity and facts before this point. */
    fun valid(ticket: StopTicket): Boolean {
        val p = published
        val v = p.states[ticket.app.packageName] ?: return false
        return p.ready && !p.paused && p.rule.enabled && !p.rule.observe &&
            p.rule.generation == ticket.configGeneration && p.apps[ticket.app.packageName] == ticket.app &&
            p.versions[ticket.app.packageName] == ticket.revision &&
            (v.state == "pending" || (ticket.reason == "manual" && !v.visible && v.flows == 0 && v.state in setOf("card", "managed", "stopped")))
    }

    /** An explicit stop can revoke a background card, never visible work or an operation. */
    @Synchronized fun manual(pkg: String): StopTicket? {
        val v = published.states[pkg] ?: return null
        if (v.visible || v.flows > 0 || v.state !in setOf("card", "managed", "pending", "stopped")) return null
        return StopTicket(v.app, revisions[pkg] ?: 0, rule.generation, "manual")
    }

    @Synchronized fun completed(ticket: StopTicket, success: Boolean, observed: Boolean, now: Long) {
        val t = tracks[ticket.app.packageName] ?: return
        if (t.app != ticket.app || revisions[t.app.packageName] != ticket.revision) return
        if (observed) {
            t.observation = ticket.revision
            t.due = null
        } else if (success) {
            t.stopped = true
            t.closing = false
            t.due = null
        } else t.due = now + RETRY_MS
        publish(now)
    }

    /** Bounded, in-memory entry check. Other users and unknown identities are never managed. */
    fun entry(userId: Int, target: CallTarget, now: Long): EntryDecision {
        val p = published
        if (userId != 0) return EntryDecision(true, "other_user")
        if (!p.ready || p.paused || !p.rule.enabled) return EntryDecision(true, "unknown_or_paused")
        val v = p.states[target.packageName] ?: return EntryDecision(true, "unknown_identity")
        if (v.state in setOf("essential", "exception", "visible", "card")) return EntryDecision(true, v.state)
        if (v.state == "opening") return EntryDecision(true, "open_confirm")
        if (v.state == "uncertain") return EntryDecision(true, "task_provenance_unknown")
        val grants = p.grants[target.packageName].orEmpty()
        if (grants.any { it.kind == target.kind && (it.component == null || it.component == target.component) }) {
            return EntryDecision(true, "operation")
        }
        // Notifications belong to the target, including a known proxy target. An operation may
        // post a notification, but it does not grant unrelated starts to all flow participants.
        if (target.kind == EntryKind.NOTIFICATION && v.flows > 0) return EntryDecision(true, "operation_notification")
        if (target.kind == EntryKind.PROCESS && v.flows > 0) return EntryDecision(true, "operation_process")
        return EntryDecision(p.rule.observe, if (p.rule.observe) "observe_no_permission" else "no_permission", true)
    }

    fun views(): List<PermissionView> = published.states.values.toList()
    fun liveFlowIds(): Set<Long> = synchronized(this) { flows.keys.toSet() }
    fun flowFor(pkg: String): Long? = synchronized(this) { flows.values.firstOrNull { contains(it, pkg) }?.id }
    fun flowDetails(): List<String> = synchronized(this) {
        flows.values.map { f ->
            val stage = if (f.needsReview) "needs_review" else if (f.observed) "observed" else "active"
            "${f.id}:${f.source}:${f.grants.map { it.target.packageName }.distinct().take(4).joinToString(",")}" +
                "; components=${f.grants.size}; callbacks=${f.callbacks.size}; bindings=${f.bindings.size}; activities=${f.activities.size}; $stage"
        }
    }

    private fun permitted(t: Track, now: Long): Boolean =
        t.app in rule.exceptions || t.app.packageName in rule.essential || t.visible ||
            t.cards.isNotEmpty() || t.openedUntil > now || t.uncertainUntil > now ||
            flows.values.any { contains(it, t.app.packageName) }

    private fun contains(f: Flow, pkg: String): Boolean = f.source == pkg || f.grants.any { it.target.packageName == pkg }
    private fun flowPackages(f: Flow): Set<String> = f.grants.mapTo(HashSet()) { it.target.packageName } + f.source
    private fun invalidate(pkg: String) { revisions[pkg] = serial.incrementAndGet() }

    private fun publish(now: Long) {
        // A backwards fake/device clock must disable enforcement until a fresh snapshot arrives.
        if (now < lastNow) ready = false
        lastNow = now
        val grants = HashMap<String, MutableSet<CallTarget>>()
        val flowCounts = HashMap<String, Int>()
        for (f in flows.values) {
            for (pkg in flowPackages(f)) flowCounts[pkg] = (flowCounts[pkg] ?: 0) + 1
            for (g in f.grants) grants.getOrPut(g.target.packageName) { HashSet() } += g.target
        }
        val states = tracks.mapValues { (pkg, t) ->
            val activeFlows = flowCounts[pkg] ?: 0
            val reasons = rule.essential[pkg].orEmpty()
            val state = when {
                pkg in rule.essential -> "essential"
                t.app in rule.exceptions -> "exception"
                t.visible -> "visible"
                t.cards.isNotEmpty() -> "card"
                activeFlows > 0 -> "operation"
                t.openedUntil > now -> "opening"
                t.uncertainUntil > now -> "uncertain"
                t.stopped -> "stopped"
                t.closing -> "pending"
                else -> "managed"
            }
            PermissionView(t.app, t.cards.size, t.visible, activeFlows, state, reasons, t.due)
        }
        published = Published(rule, ready, paused, tracks.mapValues { it.value.app }, states, grants, revisions.toMap())
    }

    companion object {
        const val OPEN_CONFIRM_MS = 3_000L
        const val RETRY_MS = 30_000L
        private const val MAX_TASKS = 512
        private const val MAX_FLOWS = 128
        private const val MAX_FLOW_TARGETS = 32
    }
}
