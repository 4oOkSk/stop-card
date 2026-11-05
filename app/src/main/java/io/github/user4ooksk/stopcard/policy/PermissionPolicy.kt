package io.github.user4ooksk.stopcard.policy

import java.util.concurrent.atomic.AtomicLong

/** Facts have framework provenance. Intent extras never create an [OpenSource]. */
enum class OpenSource { LAUNCHER, RECENTS, NOTIFICATION, ACTIVITY }
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
    val windows: Map<String, String>? = null,
)

data class AccessRule(
    val enabled: Boolean = false,
    val observe: Boolean = true,
    val generation: Long = 0,
    val reviewMs: Long = 500,
    val hiddenReviewMs: Long = 30_000,
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
    val running: Boolean = false,
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
        var closing = false
        var restricted = false
        var reason = ""
        var due: Long? = null
        var stopped = false
        var running = false
        var observation: Long? = null
        var openedUntil = 0L
        val uncertainTasks = HashSet<TaskKey>()
    }

    private data class Grant(val caller: AppKey, val target: CallTarget, val observed: Boolean)
    private class Flow(
        val id: Long,
        val source: String,
        val activity: String?,
        var reviewAt: Long,
        val observed: Boolean = false,
        val systemBinding: Boolean = false,
        val expiresAt: Long? = null,
    ) {
        val grants = HashSet<Grant>()
        val bindings = HashMap<String, Grant>()
        val activities = HashSet<String>()
        var finished = false
    }

    private data class Published(
        val rule: AccessRule = AccessRule(),
        val ready: Boolean = false,
        val paused: Boolean = true,
        val apps: Map<String, AppKey> = emptyMap(),
        val states: Map<String, PermissionView> = emptyMap(),
        val grants: Map<String, Set<Grant>> = emptyMap(),
        val versions: Map<String, Long> = emptyMap(),
        val restricted: Set<String> = emptySet(),
    )

    private val tracks = LinkedHashMap<String, Track>()
    private var apps = emptyMap<String, AppKey>()
    private val flows = LinkedHashMap<Long, Flow>()
    private val revisions = HashMap<String, Long>()
    private val serial = AtomicLong()
    private var rule = AccessRule()
    private var ready = false
    private var paused = true
    private var boot: String? = null
    private var lastNow = 0L
    private var taskOwners = emptyMap<TaskKey, String>()
    private val visibleWindows = HashMap<String, String>()

    @Volatile private var published = Published()

    /** Installation changes invalidate every old card, exception match, flow and stop ticket. */
    @Synchronized fun setApps(apps: Collection<AppKey>, now: Long) {
        val primary = apps.filter { it.userId == 0 }.associateBy { it.packageName }
        for ((pkg, old) in tracks.toMap()) {
            if (primary[pkg] != old.app) {
                tracks.remove(pkg)
                invalidate(pkg)
                for (flow in flows.values.filter { contains(it, pkg) }) {
                    flows.remove(flow.id)
                    for (related in flowPackages(flow) - pkg) tracks[related]?.let { t ->
                        invalidate(related)
                        t.due = null
                    }
                }
                primary[pkg]?.let { tracks[pkg] = Track(it) }
            }
        }
        this.apps = primary
        revisions.keys.retainAll(primary.keys)
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
        val t = track(pkg) ?: return
        invalidate(pkg)
        t.stopped = false
        t.closing = false
        t.restricted = false
        t.due = null
        t.observation = null
        t.openedUntil = maxOf(t.openedUntil, now + OPEN_CONFIRM_MS)
        if (task != null && task.userId == 0 && (boot == null || task.boot == boot)) {
            t.activated += task
            t.uncertainTasks -= task
        }
        // The source is an enum produced by the adapter, never an application-provided string.
        t.reason = "opened_${source.name.lowercase()}"
        publish(now)
    }

    /** Android owns Activity admission. Cancel an old stop before its process can start. */
    @Synchronized fun starting(pkg: String, now: Long) {
        val t = track(pkg) ?: return
        invalidate(pkg)
        t.due = null
        t.openedUntil = now + rule.hiddenReviewMs
        t.reason = "opening_activity"
        publish(now)
    }

    /** Missing caller facts never justify an immediate stop of a potentially legitimate start. */
    @Synchronized fun reviewLater(pkg: String, now: Long) {
        val t = track(pkg) ?: return
        invalidate(pkg)
        t.due = null
        t.openedUntil = now + rule.hiddenReviewMs
        publish(now)
    }

    /** Visible windows protect all their packages, even inside excluded/shared tasks. */
    @Synchronized fun visible(pkg: String, task: TaskKey?, value: Boolean, now: Long, token: String? = null) {
        val t = track(pkg) ?: return
        if (token != null) {
            if (value) visibleWindows[token] = pkg else visibleWindows.remove(token)
        }
        val anyVisible = if (token == null) value else pkg in visibleWindows.values
        if (anyVisible) {
            invalidate(pkg)
            t.visible = true
            t.stopped = false
            t.restricted = false
            t.closing = false
            t.due = null
            // A document displayed during an active session continues that session. Hidden
            // sibling tasks do not inherit it; reconciliation still verifies task ownership.
            if (task != null) t.activated += task
        } else if (t.visible) {
            invalidate(pkg)
            t.visible = false
        }
        publish(now)
    }

    /** Retain close events during initialization. Only a verified native removal marks [userClosed]. */
    @Synchronized fun removed(task: TaskKey, owner: String?, userClosed: Boolean, now: Long) {
        val pkg = owner ?: taskOwners[task] ?: return
        val t = track(pkg) ?: return
        t.activated.remove(task)
        t.cards = t.cards - task
        t.candidates = t.candidates - task
        t.uncertainTasks -= task
        if (userClosed) {
            invalidate(pkg)
            t.closing = true
            t.reason = "user_closed"
            t.due = null
        }
        publish(now)
    }

    @Synchronized fun reconcile(world: WorldSnapshot, now: Long) {
        if (!world.complete || world.userId != 0 || world.tasks.size > MAX_TASKS) {
            unknown(now)
            return
        }
        val first = boot != world.boot
        if (boot != null && first) {
            flows.clear()
            for ((pkg, t) in tracks) {
                invalidate(pkg)
                t.activated.clear()
                t.cards = emptySet()
                t.candidates = emptySet()
                t.closing = false
                t.restricted = false
                t.due = null
                t.openedUntil = 0
                t.uncertainTasks.clear()
                t.stopped = false
            }
            ready = false
        }
        boot = world.boot
        world.windows?.let { visibleWindows.clear(); visibleWindows.putAll(it) }
        val byOwner = world.tasks.filter { it.key.userId == 0 && it.key.boot == world.boot }.groupBy { it.owner }
        for (pkg in byOwner.keys + world.visiblePackages + world.tasks.filter { it.key.userId == 0 }.flatMap { it.visiblePackages }) track(pkg)
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
                t.uncertainTasks += appeared - t.activated
            }
            val onScreen = pkg in world.visiblePackages || world.tasks.any { it.key.userId == 0 && pkg in it.visiblePackages }
            if (onScreen != t.visible) {
                invalidate(pkg)
                if (onScreen) { t.restricted = false; t.closing = false }
            }
            t.visible = onScreen
            if (onScreen) {
                // Continue a verified session only through tasks actually displayed by its owner.
                // This also covers a visible document when the window callback is unavailable.
                t.activated += owned.filter { pkg in it.visiblePackages }.map { it.key }
            }
            // PiP and other temporary recents eligibility changes do not end task identity.
            val present = owned.mapTo(HashSet()) { it.key }
            t.activated.retainAll(present)
            t.uncertainTasks.retainAll(present)
            t.uncertainTasks.removeAll(t.activated)
            t.cards = eligible.intersect(t.activated)
            if (t.cards.isNotEmpty()) {
                t.openedUntil = 0
            }
            t.candidates = eligible
            if (wasCards != t.cards) invalidate(pkg)
            if (t.cards.isNotEmpty() || onScreen) {
                t.due = null
                t.stopped = false
                t.observation = null
            }
        }
        taskOwners = world.tasks.associate { it.key to it.owner }
        ready = true
        reviewFlows(now)
        publish(now)
    }

    /** Components describe lifecycle tracking; application sessions authorize dependencies. */
    @Synchronized fun beginFlow(
        source: String,
        sourceActivity: String?,
        target: CallTarget,
        now: Long,
        observed: Boolean = false,
        systemBinding: Boolean = false,
        systemDependency: Boolean = false,
    ): Long? {
        if (systemDependency && (!observed || source != target.packageName || target.component.isNullOrBlank() ||
                target.kind !in setOf(EntryKind.SERVICE, EntryKind.BIND, EntryKind.PROVIDER) ||
                apps[source]?.let(::systemBound) != true)) return null
        if (systemBinding) {
            if (observed || target.kind != EntryKind.BIND || target.component.isNullOrBlank()) return null
            flows.values.firstOrNull { it.systemBinding && it.source == source && it.grants.any { g -> g.target == target } }
                ?.let { return it.id }
        }
        if (!systemBinding) flows.values.firstOrNull { !it.systemBinding && it.source == source &&
                it.grants.any { g -> g.target.packageName == target.packageName } }?.let { existing ->
            if (existing.grants.size >= MAX_FLOW_TARGETS && existing.grants.none { it.target == target }) {
                unknown(now)
                return null
            }
            existing.grants += Grant(apps[source] ?: return null, target, observed)
            existing.reviewAt = now + rule.hiddenReviewMs
            invalidate(target.packageName)
            publish(now)
            return existing.id
        }
        if (track(source) == null || track(target.packageName) == null || flows.size >= MAX_FLOWS) return null
        val id = serial.incrementAndGet()
        val f = Flow(id, source, sourceActivity, now + rule.hiddenReviewMs, observed, systemBinding,
            if (systemDependency) now + rule.hiddenReviewMs else null)
        f.grants += Grant(apps.getValue(source), target, observed)
        flows[id] = f
        invalidate(target.packageName)
        invalidate(source)
        publish(now)
        return id
    }

    /** Native accepted calls from a live session can extend that session without touch proofs. */
    @Synchronized fun extendFlow(id: Long, from: String, target: CallTarget, now: Long): Boolean {
        val f = flows[id] ?: return false
        if (f.systemBinding || !contains(f, from) || f.grants.size >= MAX_FLOW_TARGETS || track(target.packageName) == null) return false
        f.grants += Grant(apps.getValue(from), target, false)
        f.reviewAt = now + rule.hiddenReviewMs
        invalidate(target.packageName)
        publish(now)
        return true
    }

    /** Dependencies may only address an existing participant and an exact resolved component. */
    @Synchronized fun dependency(id: Long, from: String, target: CallTarget, now: Long): Boolean {
        val f = flows[id] ?: return false
        if (f.systemBinding || !contains(f, from) || !contains(f, target.packageName) || target.component.isNullOrBlank() ||
            target.kind !in setOf(EntryKind.SERVICE, EntryKind.BIND, EntryKind.PROVIDER, EntryKind.BROADCAST) ||
            f.grants.size >= MAX_FLOW_TARGETS) return false
        f.grants += Grant(apps.getValue(from), target, false)
        f.reviewAt = now + rule.hiddenReviewMs
        invalidate(target.packageName)
        publish(now)
        return true
    }

    @Synchronized fun flowActivity(id: Long, token: String, added: Boolean, now: Long) {
        val f = flows[id] ?: return
        if (added) f.activities += token else f.activities -= token
        f.reviewAt = now + rule.hiddenReviewMs
        publish(now)
    }

    @Synchronized fun bind(id: Long, token: String, target: CallTarget, now: Long, caller: AppKey? = null): Boolean {
        val f = flows[id] ?: return false
        val matching = f.grants.filter { it.target == target && (caller == null || it.caller == caller) }
        val grant = if (caller == null) matching.singleOrNull() else matching.firstOrNull()
        if (grant == null) return false
        // These are actual native connections, not component metadata. Track all accepted work;
        // the native lifetime review removes ended records even when a callback was missed.
        f.bindings[token] = grant
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

    @Synchronized fun finishFlow(id: Long, now: Long) {
        val f = flows[id] ?: return
        f.finished = true
        f.reviewAt = now + rule.hiddenReviewMs
        reviewFlows(now)
        publish(now)
    }

    @Synchronized fun sourceActivityRemoved(token: String, now: Long) {
        for (flow in flows.values) if (flow.activity == token) {
            flow.finished = true
            flow.reviewAt = now + rule.hiddenReviewMs
        }
        reviewFlows(now)
        publish(now)
    }

    /** Visible work stays protected. Hidden operations use lifecycle completion or a short fallback. */
    private fun reviewFlows(now: Long) {
        val finished = flows.values.filter { f ->
            val visible = f.grants.any { tracks[it.target.packageName]?.visible == true }
            if (visible) f.reviewAt = now + rule.hiddenReviewMs
            // A real connection/component outlives a timer. Missing lifecycle facts defer a
            // close; the timeout only retires an idle operation without live work.
            val liveWork = f.bindings.isNotEmpty() || f.activities.isNotEmpty()
            !visible && ((now >= (f.expiresAt ?: f.reviewAt) && !liveWork) || (f.finished &&
                f.bindings.isEmpty() && f.activities.isEmpty()))
        }
        for (f in finished) {
            flows.remove(f.id)
            for (pkg in flowPackages(f)) tracks[pkg]?.let { t ->
                invalidate(pkg)
                t.due = null
            }
        }
    }

    /** Known running evidence permits a targeted review, never an initialization sweep. */
    @Synchronized fun runningWithoutPermission(pkg: String, now: Long) {
        val t = track(pkg) ?: return
        t.running = true
        t.stopped = false
        if (!t.restricted) { publish(now); return }
        t.closing = true
        t.reason = "closed_app_restarted"
        publish(now)
    }

    /** The first running snapshot is a baseline; later process starts permit targeted review. */
    @Synchronized fun running(packages: Set<String>, now: Long, baseline: Boolean) {
        for (pkg in packages) track(pkg)
        for ((pkg, t) in tracks) {
            val started = !t.running && pkg in packages
            t.running = pkg in packages
            if (started) t.stopped = false
            if (!baseline && started && t.restricted) {
                t.closing = true; t.reason = "closed_app_restarted"; t.due = null
            }
        }
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
    fun valid(ticket: StopTicket, group: Set<String> = emptySet()): Boolean {
        val p = published
        val v = p.states[ticket.app.packageName] ?: return false
        return p.ready && !p.paused && p.rule.enabled && !p.rule.observe &&
            p.rule.generation == ticket.configGeneration && p.apps[ticket.app.packageName] == ticket.app &&
            p.versions[ticket.app.packageName] == ticket.revision &&
            (v.state == "pending" || (ticket.reason == "manual" && !v.visible && v.flows == 0 && v.state in setOf("card", "managed", "stopped"))) &&
            group.all { pkg -> pkg == ticket.app.packageName || p.apps[pkg]?.let { app ->
                app.uid == ticket.app.uid && pkg !in p.rule.essential && app !in p.rule.exceptions &&
                    (p.states[pkg]?.state ?: "managed") in setOf("managed", "pending", "stopped") &&
                    p.states[pkg]?.let { !it.running || it.state != "managed" } != false
            } == true }
    }

    /** An explicit stop can revoke a background card, never visible work or an operation. */
    @Synchronized fun manual(pkg: String): StopTicket? {
        track(pkg) ?: return null
        publish(lastNow)
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
            t.restricted = true
            t.running = false
            t.closing = false
            t.due = null
            // Android can retain a stopped task's thumbnail. A manual stop revokes its old
            // activation; that thumbnail must not authorize a later background wake-up.
            t.activated.clear()
            t.cards = emptySet()
            t.openedUntil = 0
            t.uncertainTasks.clear()
        } else t.due = now + RETRY_MS
        publish(now)
    }

    /** Bounded, in-memory entry check. Other users and unknown identities are never managed. */
    fun entry(userId: Int, target: CallTarget, now: Long, caller: AppKey? = null): EntryDecision {
        val p = published
        if (userId != 0) return EntryDecision(true, "other_user")
        if (target.kind == EntryKind.ACTIVITY) return EntryDecision(true, "android_activity")
        if (!p.ready || p.paused || !p.rule.enabled) return EntryDecision(true, "unknown_or_paused")
        val app = p.apps[target.packageName] ?: return EntryDecision(true, "unknown_identity")
        if (target.packageName in p.rule.essential) return EntryDecision(true, "essential")
        if (app in p.rule.exceptions) return EntryDecision(true, "exception")
        val v = p.states[target.packageName] ?: return EntryDecision(true, "not_closed")
        if (v.state in setOf("essential", "exception", "visible", "card")) return EntryDecision(true, v.state)
        if (v.state == "opening") return EntryDecision(true, "open_confirm")
        if (v.state == "uncertain") return EntryDecision(true, "task_provenance_unknown", true)
        if (v.flows > 0) return EntryDecision(true, "operation")
        if (target.packageName !in p.restricted) return EntryDecision(true, "not_closed")
        if (caller == null || p.apps[caller.packageName] != caller || caller.uid < 10000)
            return EntryDecision(true, "caller_unknown_or_system")
        if (hasSession(caller)) return EntryDecision(true, "active_caller")
        return EntryDecision(p.rule.observe, if (p.rule.observe) "observe_closed_background" else "closed_background", true)
    }

    fun views(): List<PermissionView> = published.states.values.toList()
    fun keepAlive(group: Set<String>): Boolean {
        val p = published
        return p.ready && !p.paused && p.rule.enabled && !p.rule.observe && group.isNotEmpty() &&
            group.all { p.states[it]?.let { v -> v.cards > 0 || v.visible || v.flows > 0 } == true }
    }
    fun liveFlowIds(): Set<Long> = synchronized(this) { flows.keys.toSet() }
    fun operationTokens(): Set<String> = synchronized(this) {
        flows.values.flatMapTo(HashSet()) { it.activities + it.bindings.keys }
    }
    @Synchronized fun systemBound(app: AppKey): Boolean = apps[app.packageName] == app && flows.values.any { f ->
        f.systemBinding && f.grants.any { it.target.packageName == app.packageName } &&
            (f.bindings.isNotEmpty() || f.activities.isNotEmpty())
    }
    fun flowFor(pkg: String, activity: String? = null): Long? = synchronized(this) {
        val matching = flows.values.filter { f -> !f.systemBinding && contains(f, pkg) &&
            (activity == null || f.activity == activity || activity in f.activities) }
        matching.singleOrNull()?.id
    }
    fun flowForCall(caller: AppKey, target: CallTarget): Long? = synchronized(this) {
        val matching = flows.values.filter { f -> f.grants.any { it.caller == caller && it.target == target } }
        (matching.singleOrNull { it.systemBinding } ?: matching.singleOrNull())?.id
    }

    fun isVisible(pkg: String): Boolean = published.states[pkg]?.visible == true
    fun hasSession(app: AppKey): Boolean = published.apps[app.packageName] == app &&
        published.states[app.packageName]?.let { it.visible || it.cards > 0 || it.flows > 0 || it.state == "opening" } == true
    fun isRestricted(pkg: String): Boolean = pkg in published.restricted
    fun flowDetails(): List<String> = synchronized(this) {
        flows.values.map { f ->
            val stage = if (f.observed) "observed" else "active"
            "${f.id}:${f.source}:${f.grants.map { it.target.packageName }.distinct().take(4).joinToString(",")}" +
                "; components=${f.grants.size}; bindings=${f.bindings.size}; activities=${f.activities.size}; $stage"
        }
    }

    private fun permitted(t: Track, now: Long): Boolean =
        t.app in rule.exceptions || t.app.packageName in rule.essential || t.visible ||
            t.cards.isNotEmpty() || t.openedUntil > now || t.uncertainTasks.isNotEmpty() ||
            flows.values.any { contains(it, t.app.packageName) }

    private fun contains(f: Flow, pkg: String): Boolean = f.source == pkg || f.grants.any { it.target.packageName == pkg }
    private fun flowPackages(f: Flow): Set<String> = f.grants.mapTo(HashSet()) { it.target.packageName } + f.source
    private fun track(pkg: String): Track? = tracks[pkg] ?: apps[pkg]?.let { Track(it).also { track -> tracks[pkg] = track; invalidate(pkg) } }
    private fun invalidate(pkg: String) { revisions[pkg] = serial.incrementAndGet() }

    private fun publish(now: Long) {
        // A backwards fake/device clock must disable enforcement until a fresh snapshot arrives.
        if (now < lastNow) ready = false
        lastNow = now
        val grants = HashMap<String, MutableSet<Grant>>()
        val flowCounts = HashMap<String, Int>()
        for (f in flows.values) {
            for (pkg in flowPackages(f)) flowCounts[pkg] = (flowCounts[pkg] ?: 0) + 1
            for (g in f.grants) grants.getOrPut(g.target.packageName) { HashSet() } += g
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
                t.uncertainTasks.isNotEmpty() -> "uncertain"
                t.stopped -> "stopped"
                t.closing -> "pending"
                else -> "managed"
            }
            PermissionView(t.app, t.cards.size, t.visible, activeFlows, state, reasons, t.due, t.running)
        }
        published = Published(rule, ready, paused, apps, states, grants, revisions.toMap(),
            tracks.filterValues { it.restricted }.keys.toSet())
    }

    companion object {
        const val OPEN_CONFIRM_MS = 3_000L
        const val RETRY_MS = 30_000L
        private const val MAX_TASKS = 512
        private const val MAX_FLOWS = 128
        private const val MAX_FLOW_TARGETS = 32
    }
}
