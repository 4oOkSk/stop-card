package io.github.user4ooksk.stopcard.module

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
    @Volatile private var channelReady = false
    @Volatile private var lastProgress = 0L
    private var attached = false
    private var hooksInstalled = false
    private lateinit var adapter: FrameworkAdapter
    private var context: Context? = null
    private var systemFacts: SystemFacts? = null
    private var boot = ""
    private var lastInventoryAt = 0L
    private var lastStateAt = 0L
    private var lastConfigText: String? = null
    private var acceptedConfig: ModuleProtocol.Config? = null
    private var appliedGeneration = -1L
    private var runningBaseline = false
    private var lastRunningAt = -5_000L
    @Volatile private var protectedPriorities = emptyMap<Int, PriorityGrant>()
    @Volatile private var prioritySampleAt = 0L
    private var prioritySessions = emptyMap<AppKey, Long>()
    private val priorityRefreshPending = HashSet<Int>()
    private val invalidatedPriorities = ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var priorityFaulted = false
    private val processEpochs = ConcurrentHashMap<Int, Long>()
    private val epochSerial = java.util.concurrent.atomic.AtomicLong()
    private val wakeRelations = WakeRelations()
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
        boot = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")
        // The independent recovery marker is read before any enforcement hook is installed.
        paused = store.paused()
        adapter = FrameworkAdapter(loader, boot, this)
        if (!paused && boot.isNotBlank()) adapter.captureServices()
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

    override fun systemRolesChanged() {
        factsFresh = false
        ready = false
        wake()
    }

    private val loop = object : Runnable {
        override fun run() {
            guardModule(::controlFailed) {
                try { tick() }
                catch (e: Throwable) { controlFailed(e) }
                finally {
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
                }
            }
            guardModule(::controlFailed) {
                handler?.postDelayed(this, if (paused) 1_000 else 500)
            }
        }
    }

    private fun controlFailed(error: Throwable) {
        val report = readFailures == 0
        ready = false
        factsFresh = false
        lastError = "control:${error.javaClass.simpleName}"
        readFailures++
        if (readFailures >= 3) recovery = "control_failures"
        policy.unknown(now())
        if (report) android.util.Log.e("StopCard", "Control loop failed", error)
    }

    private fun tick() {
        if (!adapter.bootComplete()) {
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
        if (!hooksInstalled && !paused && boot.isNotBlank()) {
            adapter.install()
            hooksInstalled = true
        }
        readConfig()
        runCatching { refreshRecovery() }.onFailure { recovery = "unavailable" }
        if (!factsFresh || now() - lastInventoryAt >= 30_000) refreshFacts()
        configure()
        if (current == null || boot.isBlank()) {
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
        ready = factsFresh && coreHooksReady()
        appliedGeneration = if (ready && channelReady) current?.configGeneration ?: -1 else -1
        taskSource = if (ready) "ok" else "initializing"
        if (!ready) policy.unknown(now())
        repeat(pendingFacts.size) { pendingFacts.poll()?.invoke() }
        adapter.reconcileOperations(policy.operationTokens())
        readFailures = 0
        if (ready && lastError?.startsWith("control:") == true) lastError = null
        if (ready) {
            for (ticket in policy.stops(now())) execute(ticket)
        }
        commands()
    }

    private fun updatePriorities() {
        priorityRefreshPending.addAll(invalidatedPriorities)
        invalidatedPriorities.removeAll(priorityRefreshPending)
        val old = protectedPriorities
        val views = policy.views().filter { it.cards > 0 && it.state in setOf("card", "visible") }
        val sessions = views.associate { it.app to it.lastActiveAt }
        val enabled = enforcing() && current?.extraKeepAlive == true && priorityHooksReady()
        var next = if (enabled) old.filterValues { validPriority(it) } else emptyMap()
        if (enabled && (sessions != prioritySessions || now() - prioritySampleAt >= 5_000)) {
            prioritySessions = sessions
            val inventory = facts!!
            val candidates = views.map { it.app }.filter { inventory.groups[it.uid]?.size == 1 }.associateBy { it.packageName }
            val processes = adapter.priorityProcesses(candidates).take(64).mapNotNull { process ->
                val epoch = processEpochs[process.pid] ?: 0
                val rss = runCatching {
                    File("/proc/${process.pid}/status").useLines { lines ->
                        lines.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
                    }
                }.getOrNull() ?: return@mapNotNull null
                if (epoch != (processEpochs[process.pid] ?: 0)) null else process.copy(rssKb = rss, epoch = epoch)
            }
            val memory = android.app.ActivityManager.MemoryInfo()
            context!!.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
            next = KeepAlivePolicy.select(views, processes,
                MemoryPressure(memory.totalMem / 1024, memory.availMem / 1024, memory.threshold / 1024, memory.lowMemory))
            prioritySampleAt = now()
        }
        if (!enabled) prioritySessions = emptyMap()
        protectedPriorities = next
        val changed = (old.keys + next.keys).filterTo(HashSet()) { pid ->
            old[pid]?.let { it.adj to it.process.epoch } != next[pid]?.let { it.adj to it.process.epoch }
        }
        priorityRefreshPending.addAll(changed)
        if (priorityRefreshPending.isNotEmpty()) {
            try {
                adapter.recomputePriority(priorityRefreshPending)
                priorityRefreshPending.clear()
            } catch (error: Throwable) {
                // A native refresh may have completed only part of the set. Withdraw every
                // grant, retain all affected PIDs and retry their native values on later ticks.
                priorityRefreshPending.addAll(old.keys)
                priorityRefreshPending.addAll(next.keys)
                protectedPriorities = emptyMap()
                priorityFaulted = true
                throw error
            }
        }
    }

    private fun priorityHooksReady() = !priorityFaulted && adapter.installed("extra_keep_alive") && adapter.installed("cached_retention")
    private fun validPriority(grant: PriorityGrant): Boolean = facts?.apps?.get(grant.process.app.packageName) == grant.process.app &&
        (processEpochs[grant.process.pid] ?: 0) == grant.process.epoch && policy.hasCard(grant.process.app)

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
        wakeRelations.retain(inventory.apps)
        policy.setApps(inventory.apps.values, now())
        adapter.identities(inventory.launcherUids, inventory.systemUiUid, inventory.providers)
        lastInventoryAt = now()
        factsFresh = true
    }

    private fun refreshRecovery() {
        val text = store.control(ModuleProtocol.RECOVERY_STATE, 16 * 1024)
        val state = text?.let { runCatching { JSONObject(it) }.getOrNull() }
        val watching = state != null && state.optInt("protocol") == ModuleProtocol.VERSION && state.optString("boot") == boot && state.optString("status") == "watching" &&
            now() - state.optLong("elapsed", -60_000) in 0..20_000
        recovery = if (watching) "watching" else if (text == null) "not_installed" else "stale_or_paused"
    }

    private fun coreHooksReady(): Boolean {
        val hooks = adapter.report()
        return CORE.all { hooks[it]?.status == "installed" }
    }
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
            if (systemFacts?.currentIdentity(ticket.app.packageName) != ticket.app || !enforcing()) return "canceled"
            val group = facts?.groups?.get(ticket.app.uid) ?: return "identity_unknown"
            if (ticket.app.uid < 10000 || !policy.valid(ticket, group)) {
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
        policy.starting(fact.target.packageName, at)
        val target = CallTarget(fact.target.packageName, fact.component, EntryKind.ACTIVITY)
        val source = fact.source
        if (fact.evidence == null && source != null && operationCaller(source.uid) && source.packageName != target.packageName && fact.component.isNotBlank()) {
            val existing = policy.flowFor(source.packageName, source.token)
            val flow = if (existing != null && policy.extendFlow(existing, source.packageName, target, at)) existing
                else policy.beginFlow(source.packageName, source.token, target, at, observed = true)
            if (flow != null) launches[fact.target.token] = flow
            else { policy.unknown(at); ready = false }
        }
        // Android's permission and BAL checks are authoritative. No module Activity veto.
        return EntryDecision(true, "android_activity")
    }

    override fun launched(fact: LaunchFact, success: Boolean, originalToken: String?) {
        if (defer(fact.target.packageName, fact.target.uid) { launched(fact, success, originalToken) }) return
        val flow = launches.remove(originalToken ?: fact.target.token)
        if (success) {
            policy.opened(fact.target.packageName, fact.target.task, fact.evidence ?: OpenSource.ACTIVITY, now())
            if (flow != null) {
                activeActivities.computeIfAbsent(fact.target.token) { ConcurrentHashMap.newKeySet() }.add(flow)
                policy.flowActivity(flow, fact.target.token, true, now())
            }
        } else if (flow != null) {
            policy.finishFlow(flow, now())
        }
        wake()
    }

    override fun window(fact: ActivityFact) {
        if (fact.uid / 100000 != 0) return
        // Teardown produced by our own native stop is not a new user decision. A concurrent open
        // on another thread still changes the revision checked before the native call.
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
        policy.removed(fact.task, fact.owner, true, now())
        wake()
    }

    override fun activityFinished(token: String) {
        if (defer { activityFinished(token) }) return
        policy.sourceActivityRemoved(token, now())
        for (flow in activeActivities.remove(token).orEmpty()) {
            policy.flowActivity(flow, token, false, now())
            policy.finishFlow(flow, now())
        }
        wake()
    }

    override fun entry(userId: Int, target: CallTarget, sourceUid: Int, sourcePackage: String?, evidence: OpenSource?, observed: Boolean, systemEntry: SystemEntry?): EntryDecision {
        val callerPackage = sourcePackage ?: facts?.groups?.get(sourceUid)?.singleOrNull()
        val caller = callerPackage?.let { facts?.apps?.get(it) }?.takeIf { it.uid == sourceUid && it.userId == 0 }
        // Track native bindings even while paused, so resume retains live system callbacks.
        val platformCaller = caller?.packageName == "android" && caller.uid == 1000
        val binding = systemEntry == SystemEntry.BINDING && ready && factsFresh &&
            (caller?.packageName == "com.android.systemui" || platformCaller)
        val widget = systemEntry == SystemEntry.WIDGET_BROADCAST && platformCaller && target.kind == EntryKind.BROADCAST
        if (userId == 0 && caller != null && (binding || widget) && facts?.essential?.get(target.packageName)?.contains("system_package") != true) {
            if ((binding || policy.flowForCall(caller, target) == null) &&
                policy.beginFlow(caller.packageName, null, target, now(), systemBinding = binding) == null) {
                ready = false
                policy.unknown(now())
                wake()
                return EntryDecision(true, "binding_tracking_unknown")
            }
            // A widget or VPN can become active immediately before its first callback/bind.
            // Refresh its role before restricting startup dependencies.
            if (widget || caller.packageName == "android" && facts?.essential?.containsKey(target.packageName) != true)
                systemRolesChanged()
        }
        if (!ready || !factsFresh || paused) return EntryDecision(true, "initializing_or_paused")
        var result = policy.entry(userId, target, now(), caller)
        if (userId == 0 && result.reason != "essential" && caller != null && operationCaller(caller.uid) && target.kind in setOf(EntryKind.SERVICE, EntryKind.BIND, EntryKind.PROVIDER, EntryKind.BROADCAST)) {
            val systemDependency = caller.packageName == target.packageName && policy.systemBound(caller) &&
                target.kind in setOf(EntryKind.SERVICE, EntryKind.BIND, EntryKind.PROVIDER)
            val flow = policy.flowFor(caller.packageName)
            if ((policy.hasSession(caller) || systemDependency || observed) &&
                    (caller.packageName != target.packageName || flow != null || systemDependency)) {
                if (flow == null || !policy.extendFlow(flow, caller.packageName, target, now())) {
                    if (policy.beginFlow(caller.packageName, null, target, now(), observed = true,
                            systemDependency = systemDependency) == null) {
                        policy.unknown(now()); ready = false; wake()
                        return EntryDecision(true, "operation_tracking_unknown")
                    }
                }
            }
            result = policy.entry(userId, target, now(), caller)
        }
        if (result.allow && result.reason == "caller_unknown_or_system" && policy.isRestricted(target.packageName)) {
            policy.reviewLater(target.packageName, now())
            wake()
        }
        recordDecision(target, result, caller)
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
                if (policy.bind(flow, token, target, now(), caller)) policy.finishFlow(flow, now())
            }
        }
        wake()
    }

    override fun component(token: String, source: String?, target: CallTarget, added: Boolean) {
        if (!added) {
            activityFinished(token)
            source?.let { facts?.apps?.get(it) }?.let { caller -> policy.flowForCall(caller, target)?.let { policy.finishFlow(it, now()) } }
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

    override fun priority(pid: Int, uid: Int, nativeAdj: Int): Int {
        if (nativeAdj !in 900..999 || !enforcing() || current?.extraKeepAlive != true ||
            now() - prioritySampleAt !in 0..10_000 || !priorityHooksReady()) return nativeAdj
        val grant = protectedPriorities[pid]?.takeIf { it.process.app.uid == uid && validPriority(it) }
        return KeepAlivePolicy.adjust(nativeAdj, grant)
    }

    override fun processChanged(pid: Int) {
        if (pid <= 0) return
        processEpochs[pid] = epochSerial.incrementAndGet()
        if (processEpochs.size > 4096) {
            invalidatedPriorities.addAll(protectedPriorities.keys)
            protectedPriorities = emptyMap()
            processEpochs.clear()
            wake()
        }
    }

    override fun processStart(packageName: String, uid: Int, processName: String, hostingType: String,
        component: String?, sourceUid: Int, sourcePackage: String?): EntryDecision {
        val hosting = ProcessHosting.of(hostingType)
        if (hosting == ProcessHosting.ACTIVITY)
            return EntryDecision(true, "android_activity")
        val inventory = facts ?: return EntryDecision(true, "unknown_identity")
        val identity = inventory.apps[packageName]?.takeIf { it.uid == uid } ?: return EntryDecision(true, "unknown_identity")
        if (processName.isBlank() || hosting != ProcessHosting.BACKGROUND)
            return EntryDecision(true, "unknown_hosting")
        // A native process can host several packages. One permitted member prevents a partial veto.
        if (inventory.groups[uid].orEmpty().any { it != packageName &&
                (it in inventory.essential || inventory.apps[it]?.let(policy::hasSession) == true || !policy.isRestricted(it)) })
            return EntryDecision(true, "shared_uid_protected")
        return entry(identity.userId, CallTarget(packageName, component, EntryKind.PROCESS), sourceUid, sourcePackage)
    }

    override fun processStarted(packageName: String, uid: Int, pid: Int) {
        if (uid / 100000 != 0) return
        val review = ready
        fun observedStart() {
            if (policy.isRestricted(packageName)) policy.reviewLater(packageName, now())
            policy.runningWithoutPermission(packageName, now())
        }
        if (defer(packageName, uid) { if (review) observedStart(); wake() }) return
        if (review) observedStart()
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
            extraKeepAlive = enforcing() && c?.extraKeepAlive == true && priorityHooksReady(),
            diagnosticDetails = buildMap {
                put("framework", "LSPosed legacy API; scoped system_server")
                put("framework_version", runCatching { de.robv.android.xposed.XposedBridge.getXposedVersion().toString() }.getOrDefault("unknown"))
                put("management_channel", if (channelReady) "root-owned control files; primary user; release signer matched; protocol ${ModuleProtocol.VERSION}" else "controller signer not verified; observation only")
                put("platform", "Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}")
                put("validation", "runtime signatures; device behavior requires acceptance tests")
                put("observations", observations.toString())
                put("keep_alive", protectedPriorities.values.joinToString("\n") {
                    "${it.process.app.packageName}; pid=${it.process.pid}; lmkd_adj=${it.adj}; rss_kb=${it.process.rssKb}"
                })
                put("keep_alive_refresh", if (priorityFaulted) "disabled after native refresh failure; pending=${priorityRefreshPending.size}" else "ready")
                put("wake_relations", wakeRelations.views().take(32).joinToString("\n") {
                    "${it.key.source.packageName} -> ${it.key.target.packageName}; ${it.key.kind}; attempts=${it.attempts}; denied=${it.denied}; ${it.key.component.orEmpty()}"
                })
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

    private fun defer(pkg: String? = null, uid: Int? = null, action: () -> Unit): Boolean {
        fun matches() = pkg == null || facts?.apps?.get(pkg)?.let { uid == null || uid < 0 || it.uid == uid } == true
        if (facts != null && matches()) return false
        // An app can open before its install broadcast is processed. Retain the event until its
        // real identity is loaded, then discard it if it belongs to an old installation/UID.
        factsFresh = false
        ready = false
        if (!pendingFacts.offer { if (matches()) action() }) logLimited(SELF, "error", "pending_events_full")
        wake()
        return true
    }
    private fun recordDecision(target: CallTarget, result: EntryDecision, caller: AppKey?) {
        val closed = policy.isRestricted(target.packageName)
        if (closed && caller != null && caller.uid >= 10000) facts?.apps?.get(target.packageName)?.let {
            wakeRelations.record(caller, it, target, !result.allow && enforcing(), now())
        }
        if (result.wouldBlock) logLimited(target.packageName, if (result.allow || !enforcing()) "observe" else "blocked",
            "${target.kind.name.lowercase()}:${result.reason}; from=${caller?.packageName ?: "unknown_or_system"}; component=${target.component.orEmpty()}")
    }
    private fun logLimited(pkg: String, reason: String, detail: String) {
        val key = "$pkg:$reason:${detail.substringBefore(':')}"
        val at = now()
        val previous = eventTimes.put(key, at)
        if (previous == null || at - previous > 1_000) store.event(pkg, reason, detail)
        if (eventTimes.size > 4_096) eventTimes.clear()
    }
    private fun rootCause(error: Throwable): Throwable = error.cause?.let { rootCause(it) } ?: error
    private fun operationCaller(uid: Int): Boolean = uid >= 10000 && uid != facts?.systemUiUid && uid !in facts?.homeUids.orEmpty()
    private fun now() = SystemClock.elapsedRealtime()
    private const val SELF = "io.github.user4ooksk.stopcard"
    private val CORE = setOf("task_add", "task_remove", "user_task_close", "activity", "window_true", "force_stop")
    private val WAKE = setOf("activity", "service_lookup", "broadcast", "provider", "job", "alarm", "pending_intent", "ui_process_identity")
}
