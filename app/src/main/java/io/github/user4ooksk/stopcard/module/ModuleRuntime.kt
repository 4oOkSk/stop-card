package io.github.user4ooksk.stopcard.module

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import io.github.user4ooksk.stopcard.BuildConfig
import io.github.user4ooksk.stopcard.model.RunningApp
import io.github.user4ooksk.stopcard.policy.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The system agent owns policy. Hooks exchange memory facts; one worker performs Android/I/O. */
object ModuleRuntime : FrameworkCallbacks {
    private val policy = PermissionPolicy()
    private val store = ModuleStore {
        lastError = it
        ready = false
        policy.unknown(now())
    }
    private val queuedWake = AtomicBoolean()
    private val pendingFacts = ArrayBlockingQueue<() -> Unit>(256)
    private val launches = ConcurrentHashMap<String, Long>()
    private val activeActivities = ConcurrentHashMap<String, MutableSet<Long>>()
    private val inFlight = ThreadLocal<StopTicket?>()
    private val eventTimes = ConcurrentHashMap<String, Long>()
    private val commandResults = LinkedHashMap<String, String>()

    @Volatile private var handler: Handler? = null
    @Volatile private var facts: SystemFactSnapshot? = null
    @Volatile private var factsFresh = false
    @Volatile private var current: ModuleProtocol.Config? = null
    @Volatile private var ready = false
    @Volatile private var paused = true
    @Volatile private var lastError: String? = null
    @Volatile private var guardReady = false
    @Volatile private var channelReady = false
    @Volatile private var overflow = false
    @Volatile private var lastProgress = 0L
    private var attached = false
    private var hooksInstalled = false
    private lateinit var adapter: FrameworkAdapter
    private var context: Context? = null
    private var systemFacts: SystemFacts? = null
    private var boot = ""
    private var startedAt = 0L
    private var lastInventoryAt = 0L
    private var lastStateAt = 0L
    private var lastConfigText: String? = null
    private var acceptedConfig: ModuleProtocol.Config? = null
    private var appliedGeneration = -1L
    private var runningBaseline = false
    private var lastRunningAt = -5_000L
    private var protectedPriorities = emptySet<Int>()
    private var world: WorldSnapshot? = null
    private var taskSource = "initializing"
    private var recovery = "not_installed"
    private var stops = 0
    private var observations = 0
    private var readFailures = 0
    private var roleObserver: Any? = null
    private var roleObserverReady = false
    private var lastRoleAttempt = -30_000L
    private var lastWatchAttempt = -30_000L
    private var packageWatchReady = false
    private var networkWatchReady = false
    private var controlObserver: FileObserver? = null
    private val watchedSettings = HashSet<String>()
    private val factObserversReady: Boolean get() = controlObserver != null && packageWatchReady && networkWatchReady && watchedSettings.containsAll(SystemFacts.WATCHED_SETTINGS)

    @Synchronized fun attach(loader: ClassLoader) {
        if (attached) return
        attached = true
        startedAt = System.currentTimeMillis()
        boot = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")
        // The independent recovery marker is read before any enforcement hook is installed.
        paused = store.paused()
        adapter = FrameworkAdapter(loader, boot, this)
        if (!paused && Build.VERSION.SDK_INT >= 37 && boot.isNotBlank()) {
            adapter.install()
            hooksInstalled = true
        }
        val worker = HandlerThread("stop-card-control").apply { start() }
        handler = Handler(worker.looper).also { it.post(loop) }
        store.ready(JSONObject().put("boot", boot).put("pid", Process.myPid()).put("status", "starting").toString())
    }

    override fun wake() {
        val h = handler ?: return
        if (queuedWake.compareAndSet(false, true)) h.post {
            queuedWake.set(false)
            h.removeCallbacks(loop)
            h.post(loop)
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            try { tick() }
            catch (e: Throwable) {
                ready = false
                factsFresh = false
                policy.unknown(now())
                lastError = "control:${e.javaClass.simpleName}"
                readFailures++
                if (readFailures >= 3) recovery = "control_failures"
            } finally {
                lastProgress = now()
                runCatching { updatePriorities() }.onFailure { lastError = "priority:${it.javaClass.simpleName}" }
                store.maintenance(now())
                runCatching { state() }
                store.ready(JSONObject().put("boot", boot).put("pid", Process.myPid()).put("elapsed", now())
                    .put("status", when {
                        readFailures >= 3 -> "failed"
                        paused -> "paused"
                        current == null -> "waiting_configuration"
                        ready -> "ready"
                        else -> "observing"
                    }).toString())
                handler?.postDelayed(this, if (paused) 1_000 else 500)
            }
        }
    }

    private fun tick() {
        if (!isBootComplete()) {
            paused = store.paused()
            ready = false
            taskSource = "booting"
            return
        }
        if (context == null) {
            val thread = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null) ?: return
            val ctx = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
            context = ctx
            systemFacts = SystemFacts(ctx)
        }
        if (!factObserversReady && now() - lastWatchAttempt >= 30_000) watchChanges(context!!)
        if (!roleObserverReady && now() - lastRoleAttempt >= 30_000) watchRoles(context!!)
        paused = store.paused()
        policy.pause(paused, now())
        if (!hooksInstalled && !paused && Build.VERSION.SDK_INT >= 37 && boot.isNotBlank()) {
            adapter.install()
            hooksInstalled = true
        }
        readConfig()
        runCatching { refreshRecovery() }.onFailure { guardReady = false; recovery = "unavailable" }
        if (!factsFresh || now() - lastInventoryAt >= 30_000) refreshFacts()
        configure()
        if (current == null || overflow || Build.VERSION.SDK_INT < 37 || boot.isBlank()) {
            ready = false
            policy.unknown(now())
            return
        }
        adapter.discover()
        if (now() - lastRunningAt >= 5_000) {
            runCatching { policy.running(adapter.runningPackages(), now(), baseline = !runningBaseline) }
                .onSuccess { runningBaseline = true }
                .onFailure { lastError = "running:${it.javaClass.simpleName}" }
            lastRunningAt = now()
        }
        val latest = try { adapter.snapshot() }
        catch (e: Throwable) {
            taskSource = "unknown"
            ready = false
            policy.unknown(now())
            lastError = "task:${e.javaClass.simpleName}"
            return
        }
        world = latest
        policy.reconcile(latest, now())
        val liveFlows = policy.liveFlowIds()
        launches.entries.removeAll { it.value !in liveFlows }
        activeActivities.values.forEach { it.retainAll(liveFlows) }
        activeActivities.entries.removeAll { it.value.isEmpty() }
        val systemReady = isBootComplete() && factsFresh
        ready = systemReady && coreHooksReady()
        appliedGeneration = if (ready && channelReady) current?.configGeneration ?: -1 else -1
        taskSource = if (ready) "ok" else "initializing"
        if (!ready) policy.unknown(now())
        repeat(pendingFacts.size) { pendingFacts.poll()?.invoke() }
        readFailures = 0
        if (ready) {
            for (ticket in policy.stops(now())) execute(ticket)
        }
        commands()
    }

    private fun updatePriorities() {
        val priority = if (enforcing() && current?.extraKeepAlive == true)
            facts?.groups.orEmpty().filterValues(policy::keepAlive).keys else emptySet()
        if (priority != protectedPriorities) {
            adapter.recomputePriority()
            protectedPriorities = priority
        }
    }

    private fun readConfig() {
        val text = store.control(ModuleProtocol.CONFIG)
        if (text == lastConfigText && current != null) return
        val parsed = text?.let(ModuleProtocol.Config::fromJson)
        if (parsed == null || parsed.selfPackage != SELF || parsed.userId != 0) {
            current = null
            lastConfigText = null
            ready = false
            policy.unknown(now())
            lastError = if (text == null) "configuration_missing" else "configuration_invalid"
            return
        }
        val previous = acceptedConfig
        if (previous != null && (parsed.configGeneration < previous.configGeneration ||
                (parsed.configGeneration == previous.configGeneration && parsed != previous))) {
            current = null
            appliedGeneration = -1
            ready = false
            policy.unknown(now())
            lastError = "configuration_stale"
            return
        }
        current = parsed
        acceptedConfig = parsed
        lastConfigText = text
        if (lastError?.startsWith("configuration_") == true) lastError = null
    }

    private fun configure() {
        val c = current ?: return
        val inventory = facts ?: return
        policy.configure(AccessRule(
            enabled = c.enabled,
            observe = c.observeOnly || !channelReady,
            generation = c.configGeneration,
            reviewMs = c.reviewMs,
            hiddenReviewMs = c.recentVisibleMs,
            exceptions = c.exceptionIdentities.filterTo(HashSet()) { inventory.apps[it.packageName] == it },
            essential = inventory.essential,
        ), now())
    }

    private fun refreshFacts() {
        val inventory = systemFacts?.read() ?: return
        channelReady = BuildConfig.MODULE_SIGNER_SHA256.isNotEmpty() &&
            inventory.apps[SELF]?.let { it.userId == 0 && it.uid >= 10000 && it.signer == BuildConfig.MODULE_SIGNER_SHA256 } == true
        facts = inventory
        policy.setApps(inventory.apps.values, now())
        adapter.identities(inventory.launcherUids, inventory.systemUiUid, inventory.providers)
        lastInventoryAt = now()
        factsFresh = true
    }

    private fun refreshRecovery() {
        val text = store.control(ModuleProtocol.RECOVERY_STATE, 16 * 1024)
        val state = text?.let { runCatching { JSONObject(it) }.getOrNull() }
        guardReady = state != null && state.optInt("protocol") == ModuleProtocol.VERSION && state.optString("boot") == boot && state.optString("status") == "watching" &&
            now() - state.optLong("elapsed", -60_000) in 0..20_000
        recovery = if (guardReady) "watching" else if (text == null) "not_installed" else "stale_or_paused"
    }

    private fun coreHooksReady(): Boolean = CORE.all { adapter.report()[it]?.status == "installed" }
    private fun enforcing(): Boolean = now() - lastProgress in 0..5_000 && ready && factsFresh && !paused && channelReady && current?.let { it.enabled && !it.observeOnly } == true

    private fun execute(ticket: StopTicket): String {
        val c = current ?: return "configuration_missing"
        if (c.observeOnly || !channelReady) {
            observations++
            policy.completed(ticket, false, true, now())
            store.event(ticket.app.packageName, "observe", ticket.reason)
            return "observed"
        }
        try {
            // The control loop already reconciled tasks. Recheck the target identity, current
            // config, pause and in-memory permission; Android owns the force-stop transaction.
            readConfig()
            configure()
            paused = store.paused()
            policy.pause(paused, now())
            if (systemFacts?.currentIdentity(ticket.app.packageName) != ticket.app || !enforcing() || !policy.valid(ticket)) return "canceled"
            val group = facts?.groups?.get(ticket.app.uid) ?: return "identity_unknown"
            if (ticket.app.uid < 10000 || group.any { pkg ->
                    pkg in facts!!.essential || (pkg != ticket.app.packageName && policy.views().any { it.app.packageName == pkg && it.state !in setOf("managed", "pending", "stopped") })
                }) {
                policy.completed(ticket, false, false, now())
                store.event(ticket.app.packageName, "blocked", "shared_uid_or_role")
                return "protected"
            }
            inFlight.set(ticket)
            val committed = try { adapter.forceStop(ticket) } finally { inFlight.remove() }
            if (!committed) return "canceled"
            // Android owns teardown and notification cleanup. A later open has its own revision;
            // no additional notification clear is queued against it here.
            policy.completed(ticket, true, false, now())
            stops++
            store.event(ticket.app.packageName, if (ticket.reason in setOf("card_removed", "user_closed")) "card_removed" else ticket.reason, "force_stopped")
            return "stopped"
        } catch (e: Throwable) {
            policy.completed(ticket, false, false, now())
            store.event(ticket.app.packageName, "error", "stop:${rootCause(e).javaClass.simpleName}")
            return "failed"
        }
    }

    override fun stopGuard(packageName: String): Boolean = inFlight.get()?.let { ticket ->
        ticket.app.packageName == packageName && enforcing() &&
            facts?.groups?.get(ticket.app.uid)?.let { group -> policy.valid(ticket, group) } == true
    } == true

    override fun launch(fact: LaunchFact): EntryDecision {
        if (fact.target.uid >= 0 && fact.target.uid / 100000 != 0) return EntryDecision(true, "other_user")
        if (defer(fact.target.packageName, fact.target.uid) { launch(fact) }) return EntryDecision(true, "initializing")
        val at = now()
        val target = CallTarget(fact.target.packageName, fact.component, EntryKind.ACTIVITY)
        val source = fact.source
        if (fact.evidence != null) {
            policy.opened(target.packageName, fact.target.task, fact.evidence, at)
            if (source != null && source.packageName != target.packageName) {
                val existing = policy.flowFor(source.packageName, source.token)
                val flow = if (existing != null && policy.extendFlow(existing, source.packageName, target, true, at)) existing
                    else policy.beginFlow(source.packageName, source.token, target, fact.evidence, at)
                if (flow != null) {
                    launches[fact.target.token] = flow
                }
            }
            return EntryDecision(true, "user_open")
        }
        val decision = policy.entry(0, target, at, source?.let { facts?.apps?.get(it.packageName) })
        val existing = source?.let { s -> facts?.apps?.get(s.packageName)?.let { policy.flowForCall(it, target) } }
        if (existing != null) {
            launches[fact.target.token] = existing
            return EntryDecision(true, "existing_operation")
        }
        if (source != null && source.packageName != target.packageName && (source.visible || fact.observed)) {
            // Source visibility is not user authorization. Observe the unresolved call and hold
            // only its actual Activity/result/binding chain, without activating a card.
            val flow = policy.observeFlow(source.packageName, source.token, target, at)
            if (flow != null) {
                launches[fact.target.token] = flow
                logLimited(target.packageName, "observe", "activity_origin_unconfirmed")
                return EntryDecision(true, "observed_activity_chain")
            }
            policy.unknown(at)
            ready = false
            return EntryDecision(true, "operation_capacity_unknown")
        }
        if (decision.allow && !decision.wouldBlock) return decision
        recordDecision(target, decision)
        return if (enforcing()) decision else EntryDecision(true, "initializing_or_observe", decision.wouldBlock)
    }

    override fun launched(fact: LaunchFact, success: Boolean, originalToken: String?) {
        if (defer(fact.target.packageName, fact.target.uid) { launched(fact, success, originalToken) }) return
        val flow = launches.remove(originalToken ?: fact.target.token)
        if (success) {
            if (fact.evidence != null) policy.opened(fact.target.packageName, fact.target.task, fact.evidence, now())
            if (flow != null) {
                activeActivities.computeIfAbsent(fact.target.token) { ConcurrentHashMap.newKeySet() }.add(flow)
                policy.flowActivity(flow, fact.target.token, true, now())
            }
        } else if (flow != null) {
            policy.finishFlow(flow, true, now())
        }
        wake()
    }

    override fun window(fact: ActivityFact) {
        if (fact.uid / 100000 != 0) return
        // Teardown produced by our own native stop is not a new user decision. A concurrent open
        // on another thread still changes the revision and invalidates the stop fences.
        if (!fact.visible && inFlight.get()?.app?.packageName == fact.packageName) { wake(); return }
        if (defer(fact.packageName, fact.uid) { window(fact) }) return
        policy.visible(fact.packageName, fact.task, fact.visible, now(), fact.token)
        wake()
    }

    override fun removed(task: TaskKey, owner: String?, userClosed: Boolean) {
        if (task.userId != 0) return
        if (!userClosed && inFlight.get()?.app?.packageName == owner) { wake(); return }
        if (defer(owner) { removed(task, owner, userClosed) }) return
        policy.removed(task, owner, userClosed, now())
        wake()
    }

    override fun taskClosed(fact: TaskCloseFact) {
        if (fact.task.userId != 0) return
        if (defer(fact.owner) { taskClosed(fact) }) return
        policy.removed(fact.task, fact.owner, adapter.recentsCaller(fact.uid, fact.pid), now())
        wake()
    }

    override fun activityFinished(token: String) {
        if (defer { activityFinished(token) }) return
        policy.sourceActivityRemoved(token, now())
        for (flow in activeActivities.remove(token).orEmpty()) {
            policy.flowActivity(flow, token, false, now())
            policy.finishFlow(flow, false, now())
        }
        wake()
    }

    override fun entry(userId: Int, target: CallTarget, sourceUid: Int, sourcePackage: String?, evidence: OpenSource?, observed: Boolean): EntryDecision {
        if (!ready || !factsFresh || paused) return EntryDecision(true, "initializing_or_paused")
        val caller = sourcePackage?.let { facts?.apps?.get(it) }?.takeIf { it.uid == sourceUid }
        var result = policy.entry(userId, target, now(), caller)
        if (userId == 0 && caller != null && target.kind in setOf(EntryKind.SERVICE, EntryKind.BIND, EntryKind.PROVIDER, EntryKind.BROADCAST)) {
            val flow = policy.flowFor(caller.packageName)
            if (flow != null) policy.dependency(flow, caller.packageName, target, now())
            if (policy.flowForCall(caller, target) == null && (evidence != null || observed || policy.isVisible(caller.packageName))) {
                if (evidence != null) policy.beginFlow(caller.packageName, null, target, evidence, now())
                else policy.observeFlow(caller.packageName, null, target, now())
            }
            result = policy.entry(userId, target, now(), caller)
        }
        recordDecision(target, result)
        // A notification from the target itself proves running state. A proxy notification does
        // not prove that the target process is alive, and cannot start an initialization sweep.
        if (result.wouldBlock && target.kind == EntryKind.NOTIFICATION && facts?.apps?.get(target.packageName)?.uid == sourceUid) {
            policy.runningWithoutPermission(target.packageName, now())
            wake()
        }
        return if (enforcing()) result else EntryDecision(true, "observing", result.wouldBlock)
    }

    override fun binding(token: String, source: String?, target: CallTarget, added: Boolean) {
        if (defer { binding(token, source, target, added) }) return
        if (!added) policy.unbind(token, now())
        else if (source != null) facts?.apps?.get(source)?.let { caller ->
            val existing = policy.flowFor(caller.packageName)
            if (existing != null) policy.dependency(existing, caller.packageName, target, now())
            policy.flowForCall(caller, target)?.let { flow ->
                if (policy.bind(flow, token, target, now(), caller)) policy.finishFlow(flow, false, now())
            }
        }
        wake()
    }

    override fun component(token: String, source: String?, target: CallTarget, added: Boolean) {
        if (!added) {
            activityFinished(token)
            source?.let { facts?.apps?.get(it) }?.let { caller -> policy.flowForCall(caller, target)?.let { policy.finishFlow(it, true, now()) } }
            return
        }
        val caller = source?.let { facts?.apps?.get(it) } ?: return
        val flow = policy.flowForCall(caller, target) ?: return
        activeActivities.computeIfAbsent(token) { ConcurrentHashMap.newKeySet() }.add(flow)
        policy.flowActivity(flow, token, true, now())
        wake()
    }

    override fun fault(capability: String, cause: Throwable) {
        lastError = "$capability:${cause.javaClass.simpleName}"
        if (capability in CORE) {
            ready = false
            policy.unknown(now())
        }
        logLimited(SELF, "error", lastError!!)
        wake()
    }

    override fun keepAlive(uid: Int): Boolean {
        if (!enforcing() || current?.extraKeepAlive != true) return false
        val group = facts?.groups?.get(uid) ?: return false
        return uid >= 10000 && policy.keepAlive(group)
    }

    override fun processStarted(packageName: String, uid: Int) {
        if (uid / 100000 != 0) return
        val review = ready
        if (defer(packageName, uid) { if (review) policy.runningWithoutPermission(packageName, now()); wake() }) return
        if (review) policy.runningWithoutPermission(packageName, now())
        wake()
    }

    private fun commands() {
        val c = current ?: return
        for ((file, text) in store.commands()) {
            val command = ModuleProtocol.Command.fromJson(text)
            val id = command?.id?.toString() ?: file.nameWithoutExtension
            val reason = when {
                command == null -> "invalid"
                command.configGeneration != c.configGeneration -> "stale_generation"
                command.expiresAt < System.currentTimeMillis() || command.expiresAt > System.currentTimeMillis() + 60_000 -> "expired"
                !command.clearLog && (paused || !enforcing()) -> "observe_or_paused"
                commandResults.containsKey(id) -> "already_processed"
                else -> null
            }
            if (reason != null) commandResults[id] = reason
            else if (command!!.clearLog) {
                commandResults[id] = "clearing"
                store.clearEvents { success -> handler?.post { commandResults[id] = if (success) "cleared" else "failed" } }
            }
            else {
                val targets = if (command.stopAll) command.identities.map { it.packageName } else command.stop
                val outcomes = LinkedHashMap<String, String>()
                for (pkg in targets.distinct().take(512)) {
                    val identity = command.identities.singleOrNull { it.packageName == pkg }
                    val ticket = policy.manual(pkg)
                    outcomes[pkg] = when {
                        identity == null || facts?.apps?.get(pkg) != identity -> "installation_changed"
                        ticket == null -> "protected"
                        ticket.app != identity -> "installation_changed"
                        command.stopAll && policy.views().none { it.app == identity && it.cards > 0 } -> "card_changed"
                        else -> execute(ticket)
                    }
                }
                commandResults[id] = JSONObject(outcomes).toString()
            }
            file.delete()
            while (commandResults.size > 64) commandResults.remove(commandResults.keys.first())
        }
    }

    private fun state() {
        if (now() - lastStateAt < 1_000) return
        lastStateAt = now()
        val c = current
        val view = policy.views()
        val hooks = adapter.report()
        val state = ModuleProtocol.ModuleState(
            versionCode = BuildConfig.VERSION_CODE,
            heartbeat = System.currentTimeMillis(),
            heartbeatElapsed = now(),
            bootId = boot,
            bootEpoch = System.currentTimeMillis() - now(),
            startedAt = startedAt,
            taskSource = taskSource,
            configGeneration = c?.configGeneration ?: -1,
            configLoaded = c != null,
            appliedGeneration = appliedGeneration,
            ready = ready,
            observeOnly = c?.observeOnly != false || !channelReady,
            paused = paused || c?.enabled != true,
            lastError = lastError,
            cards = world?.tasks?.filter { it.key.userId == 0 }?.map { ModuleProtocol.Card(it.key.taskId, it.owner, it.visiblePackages.isNotEmpty(), !it.eligible) }.orEmpty(),
             apps = view.filter { it.running || it.state !in setOf("managed", "essential", "exception") || it.cards > 0 || it.flows > 0 || it.visible }.take(512).map { RunningApp(it.app.packageName, it.cards, it.visible, it.state == "stopped",
                 it.stopAt?.let { due -> maxOf(0, due - now()) } ?: -1, it.reasons.joinToString(), it.state, running = it.running) },
            stops = stops,
            pid = Process.myPid(), uid = Process.myUid(), sdk = Build.VERSION.SDK_INT,
            cardControl = if (ready) "active" else "degraded",
            notificationControl = hooks["notifications"]?.status ?: "unavailable",
            wakeControl = if (WAKE.all { hooks[it]?.status == "installed" }) "installed" else "partial",
            hooks = hooks.mapValues { (_, h) -> h.status + if (h.detail.isBlank()) "" else ":${h.detail}" } +
                ("role_changes" to if (roleObserverReady) "installed" else "degraded:periodic role refresh") +
                ("fact_changes" to if (factObserversReady) "installed" else "degraded:periodic fact refresh"),
            essentialReasons = facts?.essential.orEmpty().filterValues { it != setOf("system_package") },
            flows = policy.flowDetails(),
            recovery = recovery,
            commandResults = commandResults.entries.toList().takeLast(8).associate { it.key to it.value.take(2048) },
            extraKeepAlive = enforcing() && c?.extraKeepAlive == true && hooks["extra_keep_alive"]?.status == "installed",
            diagnosticDetails = buildMap {
                put("framework", "LSPosed legacy API; scoped system_server")
                put("framework_version", runCatching { de.robv.android.xposed.XposedBridge.getXposedVersion().toString() }.getOrDefault("unknown"))
                put("management_channel", if (channelReady) "root-owned control files; primary user; release signer matched; protocol ${ModuleProtocol.VERSION}" else "controller signer not verified; observation only")
                put("platform", "Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}")
                put("validation", "runtime signatures; device behavior requires acceptance tests")
                put("observations", observations.toString())
                hooks.forEach { (name, hook) -> put(name, hook.methods.joinToString("\n").take(4096)) }
            },
        )
        store.state(state.toTransportJson())
    }

    private fun watchChanges(ctx: Context) {
        val h = handler ?: return
        lastWatchAttempt = now()
        fun dirty() { factsFresh = false; ready = false; wake() }
        // Cancel native stop tickets as soon as root replaces config or creates a pause marker.
        // The callback changes memory only. Resume still needs the worker's full marker check.
        if (controlObserver == null && File(ModuleProtocol.DIR).isDirectory) runCatching {
            @Suppress("DEPRECATION")
            val observer = object : FileObserver(ModuleProtocol.DIR, FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == "config.json") { ready = false; wake() }
                    if (path == "paused" || path == "emergency_disabled") {
                        if (event and (CREATE or MOVED_TO or CLOSE_WRITE) != 0) {
                            paused = true
                            policy.pause(true, now())
                        }
                        ready = false
                        wake()
                    }
                }
            }
            observer.startWatching()
            controlObserver = observer
        }.onFailure { lastError = "control_observer:${it.javaClass.simpleName}" }
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching {
            if (!packageWatchReady) {
                ctx.registerReceiver(object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { dirty() } }, packages, null, h)
                packageWatchReady = true
            }
        }.onFailure { lastError = "package_observer:${it.javaClass.simpleName}" }
        val settings = object : ContentObserver(h) { override fun onChange(selfChange: Boolean) { dirty() } }
        for (setting in SystemFacts.WATCHED_SETTINGS) runCatching {
            if (setting !in watchedSettings) {
                ctx.contentResolver.registerContentObserver(Settings.Secure.getUriFor(setting), false, settings)
                watchedSettings += setting
            }
        }.onFailure { lastError = "settings_observer:${it.javaClass.simpleName}" }
        runCatching {
            if (networkWatchReady) return@runCatching
            val networks = ctx.getSystemService(ConnectivityManager::class.java) ?: error("No network service")
            networks.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { dirty() }
                override fun onLost(network: Network) { dirty() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { dirty() }
            }, h)
            networkWatchReady = true
        }.onFailure { lastError = "network_observer:${it.javaClass.simpleName}" }
    }

    private fun watchRoles(ctx: Context) {
        lastRoleAttempt = now()
        // Use the listener's direct executor to withdraw permission before scheduling the slow
        // role refresh. Callback delivery never queries Android or waits on the control worker.
        runCatching {
            val manager = ctx.getSystemService(android.app.role.RoleManager::class.java) ?: error("No role manager")
            val type = Class.forName("android.app.role.OnRoleHoldersChangedListener")
            val observer = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "onRoleHoldersChanged" -> { factsFresh = false; ready = false; wake(); null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "StopCardRoleListener"
                    else -> null
                }
            }
            val userClass = android.os.UserHandle::class.java
            val all = userClass.getField("ALL").get(null)
            manager.javaClass.getMethod("addOnRoleHoldersChangedListenerAsUser", java.util.concurrent.Executor::class.java, type, userClass)
                .invoke(manager, java.util.concurrent.Executor { it.run() }, observer, all)
            roleObserver = observer
            roleObserverReady = true
        }.onFailure { roleObserverReady = false; lastError = "role_observer:${rootCause(it).javaClass.simpleName}" }
    }

    private fun isBootComplete(): Boolean = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "sys.boot_completed") == "1"
    private fun defer(pkg: String? = null, uid: Int? = null, action: () -> Unit): Boolean {
        fun matches() = pkg == null || facts?.apps?.get(pkg)?.let { uid == null || uid < 0 || it.uid == uid } == true
        if (facts != null && matches()) return false
        // An app can open before its install broadcast is processed. Retain the event until its
        // real identity is loaded, then discard it if it belongs to an old installation/UID.
        factsFresh = false
        ready = false
        if (!pendingFacts.offer { if (matches()) action() }) overflow = true
        wake()
        return true
    }
    private fun recordDecision(target: CallTarget, result: EntryDecision) {
        if (result.wouldBlock) logLimited(target.packageName, if (result.allow || !enforcing()) "observe" else "blocked", "${target.kind.name.lowercase()}:${result.reason}")
    }
    private fun logLimited(pkg: String, reason: String, detail: String) {
        val key = "$pkg:$reason:${detail.substringBefore(':')}"
        val at = now()
        val previous = eventTimes.put(key, at)
        if (previous == null || at - previous > 1_000) store.event(pkg, reason, detail)
        if (eventTimes.size > 4_096) eventTimes.clear()
    }
    private fun rootCause(error: Throwable): Throwable = error.cause?.let { rootCause(it) } ?: error
    private fun now() = SystemClock.elapsedRealtime()
    private const val SELF = "io.github.user4ooksk.stopcard"
    private val CORE = setOf("task_add", "task_remove", "force_stop")
    private val WAKE = setOf("activity", "service_lookup", "broadcast", "provider", "job", "alarm", "pending_intent", "process")
}
