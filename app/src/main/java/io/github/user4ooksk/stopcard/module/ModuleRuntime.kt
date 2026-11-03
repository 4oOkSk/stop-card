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
    private val store = ModuleStore { lastError = it }
    private val queuedWake = AtomicBoolean()
    private val pendingFacts = ArrayBlockingQueue<() -> Unit>(256)
    private val launches = ConcurrentHashMap<String, Long>()
    private val activeActivities = ConcurrentHashMap<String, Long>()
    private val callbacks = ConcurrentHashMap<String, Long>()
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
    private val watchedSettings = HashSet<String>()
    private val factObserversReady: Boolean get() = packageWatchReady && networkWatchReady && watchedSettings.containsAll(SystemFacts.WATCHED_SETTINGS)

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
        refreshRecovery()
        if (!factsFresh || now() - lastInventoryAt >= 30_000) refreshFacts()
        configure()
        if (current == null || overflow || Build.VERSION.SDK_INT < 37 || boot.isBlank()) {
            ready = false
            policy.unknown(now())
            return
        }
        adapter.discover()
        adapter.registerInput()
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
        activeActivities.entries.removeAll { it.value !in liveFlows }
        callbacks.entries.removeAll { it.value !in liveFlows }
        val systemReady = isBootComplete() && factsFresh
        ready = systemReady && coreHooksReady()
        taskSource = if (ready) "ok" else "initializing"
        if (!ready) policy.unknown(now())
        while (true) (pendingFacts.poll() ?: break).invoke()
        readFailures = 0
        if (ready) {
            for (ticket in policy.stops(now())) execute(ticket)
        }
        commands()
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
            observe = c.observeOnly || !guardReady || !coreHooksReady() || !roleObserverReady || !channelReady || !factObserversReady,
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
    private fun enforcing(): Boolean = ready && factsFresh && !paused && guardReady && roleObserverReady && factObserversReady && channelReady && current?.let { it.enabled && !it.observeOnly } == true

    private fun execute(ticket: StopTicket): String {
        val c = current ?: return "configuration_missing"
        if (c.observeOnly || !guardReady || !coreHooksReady() || !roleObserverReady || !channelReady || !factObserversReady) {
            observations++
            policy.completed(ticket, false, true, now())
            store.event(ticket.app.packageName, "observe", ticket.reason)
            return "observed"
        }
        try {
            // All slow identity and fact reads precede the native stop fences. Each target gets
            // current roles, task/window records, installation identity, config and pause state.
            refreshFacts()
            readConfig()
            configure()
            paused = store.paused()
            policy.pause(paused, now())
            val snapshot = adapter.snapshot()
            policy.reconcile(snapshot, now())
            if (systemFacts?.currentIdentity(ticket.app.packageName) != ticket.app || !enforcing() || !policy.valid(ticket)) return "canceled"
            val group = facts?.groups?.get(ticket.app.uid) ?: return "identity_unknown"
            if (ticket.app.uid < 10000 || group.any { pkg ->
                    pkg in facts!!.essential || (pkg != ticket.app.packageName && policy.views().any { it.app.packageName == pkg && it.state !in setOf("managed", "pending", "stopped") })
                }) {
                policy.completed(ticket, false, false, now())
                store.event(ticket.app.packageName, "blocked", "shared_uid_or_role")
                return "protected"
            }
            val wasStopped = context!!.packageManager.getApplicationInfo(ticket.app.packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0
            inFlight.set(ticket)
            try { adapter.forceStop(ticket, wasStopped) } finally { inFlight.remove() }
            if (policy.valid(ticket)) {
                if (c.clearNotifications) runCatching { adapter.clearNotifications(ticket.app.packageName, 0) }
                // A successful Android force-stop must also set the package stopped flag.
                val info = context!!.packageManager.getApplicationInfo(ticket.app.packageName, 0)
                val success = info.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0
                policy.completed(ticket, success, false, now())
                if (success) {
                    stops++
                    store.event(ticket.app.packageName, if (ticket.reason in setOf("card_removed", "user_closed")) "card_removed" else ticket.reason, "force_stopped")
                } else store.event(ticket.app.packageName, "error", "force_stop_not_confirmed")
                return if (success) "stopped" else "failed"
            } else {
                store.event(ticket.app.packageName, "canceled", "opened_during_stop")
                return "canceled"
            }
        } catch (e: Throwable) {
            if (rootCause(e) is FrameworkAdapter.StopInvalidated) {
                store.event(ticket.app.packageName, "canceled", "session_changed_at_native_fence")
                return "canceled"
            }
            policy.completed(ticket, false, false, now())
            store.event(ticket.app.packageName, "error", "stop:${rootCause(e).javaClass.simpleName}")
            return "failed"
        }
    }

    override fun stopGuard(packageName: String): Boolean = inFlight.get()?.let { it.app.packageName == packageName && enforcing() && policy.valid(it) } == true

    override fun launch(fact: LaunchFact): EntryDecision {
        if (fact.target.uid >= 0 && fact.target.uid / 100000 != 0) return EntryDecision(true, "other_user")
        if (defer { launch(fact) }) return EntryDecision(true, "initializing")
        val at = now()
        val target = CallTarget(fact.target.packageName, fact.component, EntryKind.ACTIVITY)
        val source = fact.source
        if (fact.evidence != null) {
            policy.opened(target.packageName, fact.target.task, fact.evidence, at)
            if (source != null && source.packageName != target.packageName) {
                val existing = policy.flowFor(source.packageName)
                val flow = if (existing != null && policy.extendFlow(existing, source.packageName, target, true, at)) existing
                    else policy.beginFlow(source.packageName, source.token, target, fact.evidence, at, fact.callback)
                if (flow != null) {
                    launches[fact.target.token] = flow
                    fact.callback?.let { callbacks[it] = flow; policy.addCallback(flow, it, at) }
                }
            }
            return EntryDecision(true, "user_open")
        }
        val decision = policy.entry(0, target, at)
        if (decision.allow && !decision.wouldBlock) return decision
        if (source != null && (source.visible || policy.flowFor(source.packageName) != null)) {
            // Source visibility is not user authorization. Observe the unresolved call and hold
            // only its actual Activity/result/binding chain, without activating a card.
            val flow = policy.observeFlow(source.packageName, source.token, target, at, fact.callback)
            if (flow != null) {
                launches[fact.target.token] = flow
                fact.callback?.let { callbacks[it] = flow }
                logLimited(target.packageName, "observe", "activity_origin_unconfirmed")
                return EntryDecision(true, "observed_activity_chain")
            }
            policy.unknown(at)
            ready = false
            return EntryDecision(true, "operation_capacity_unknown")
        }
        recordDecision(target, decision)
        return if (enforcing()) decision else EntryDecision(true, "initializing_or_observe", decision.wouldBlock)
    }

    override fun launched(fact: LaunchFact, success: Boolean) {
        if (defer { launched(fact, success) }) return
        val flow = launches.remove(fact.target.token)
        if (success) {
            if (fact.evidence != null) policy.opened(fact.target.packageName, fact.target.task, fact.evidence, now())
            if (flow != null) {
                activeActivities[fact.target.token] = flow
                policy.flowActivity(flow, fact.target.token, true, now())
            }
        } else if (flow != null) {
            fact.callback?.let { policy.callbackDelivered(it, now()); callbacks.remove(it) }
            policy.finishFlow(flow, true, now())
        }
        wake()
    }

    override fun window(fact: ActivityFact) {
        if (fact.uid / 100000 != 0) return
        // Teardown produced by our own native stop is not a new user decision. A concurrent open
        // on another thread still changes the revision and invalidates the stop fences.
        if (!fact.visible && inFlight.get()?.app?.packageName == fact.packageName) { wake(); return }
        if (defer { window(fact) }) return
        policy.visible(fact.packageName, fact.task, fact.visible, now())
        wake()
    }

    override fun removed(task: TaskKey, owner: String?, userClosed: Boolean) {
        if (task.userId != 0) return
        if (!userClosed && inFlight.get()?.app?.packageName == owner) { wake(); return }
        if (defer { removed(task, owner, userClosed) }) return
        policy.removed(task, owner, userClosed, now())
        wake()
    }

    override fun activityFinished(token: String) {
        if (defer { activityFinished(token) }) return
        val flow = activeActivities.remove(token) ?: return
        policy.flowActivity(flow, token, false, now())
        policy.finishFlow(flow, false, now())
        wake()
    }

    override fun resultDelivered(token: String) {
        if (defer { resultDelivered(token) }) return
        callbacks.remove(token)
        policy.callbackDelivered(token, now())
        wake()
    }

    override fun entry(userId: Int, target: CallTarget, sourceUid: Int, sourcePackage: String?): EntryDecision {
        if (!ready || !factsFresh || paused) return EntryDecision(true, "initializing_or_paused")
        var result = policy.entry(userId, target, now())
        if (result.wouldBlock && sourcePackage != null && facts?.apps?.get(sourcePackage)?.uid == sourceUid) {
            val flow = policy.flowFor(sourcePackage)
            if (flow != null && policy.dependency(flow, sourcePackage, target, now())) result = policy.entry(userId, target, now())
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
        else if (source != null) policy.flowFor(source)?.let { flow -> policy.bind(flow, token, target, now()) }
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
        val views = policy.views().filter { it.app.packageName in group }
        return views.isNotEmpty() && views.all { it.cards > 0 || it.visible || it.flows > 0 } && uid >= 10000
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
                val targets = if (command.stopAll) policy.views().filter { it.cards > 0 }.map { it.app.packageName } else command.stop
                val outcomes = LinkedHashMap<String, String>()
                for (pkg in targets.distinct().take(512)) {
                    val ticket = policy.manual(pkg)
                    outcomes[pkg] = if (ticket == null) "protected" else execute(ticket)
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
            ready = ready,
            observeOnly = c?.observeOnly != false || !guardReady || !coreHooksReady() || !roleObserverReady || !channelReady || !factObserversReady,
            paused = paused || c?.enabled != true,
            lastError = lastError,
            cards = world?.tasks?.filter { it.key.userId == 0 }?.map { ModuleProtocol.Card(it.key.taskId, it.owner, it.visiblePackages.isNotEmpty(), !it.eligible) }.orEmpty(),
            apps = view.filter { it.state !in setOf("managed", "essential", "exception") || it.cards > 0 || it.flows > 0 || it.visible }.take(512).map { RunningApp(it.app.packageName, it.cards, it.visible, it.state == "stopped",
                it.stopAt?.let { due -> maxOf(0, due - now()) } ?: -1, it.reasons.joinToString(), it.state) },
            stops = stops,
            pid = Process.myPid(), uid = Process.myUid(), sdk = Build.VERSION.SDK_INT,
            cardControl = if (ready) "active" else "degraded",
            notificationControl = hooks["notifications"]?.status ?: "unavailable",
            wakeControl = if (WAKE.all { hooks[it]?.status == "installed" }) "installed" else "partial",
            hooks = hooks.mapValues { (_, h) -> h.status + if (h.detail.isBlank()) "" else ":${h.detail}" } +
                ("role_changes" to if (roleObserverReady) "installed" else "degraded:role listener unavailable; observation only") +
                ("fact_changes" to if (factObserversReady) "installed" else "degraded:package/settings/network observer unavailable; observation only"),
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
        store.state(state.toJson())
    }

    private fun watchChanges(ctx: Context) {
        val h = handler ?: return
        lastWatchAttempt = now()
        fun dirty() { factsFresh = false; ready = false; wake() }
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
    private fun defer(action: () -> Unit): Boolean {
        if (facts != null) return false
        if (!pendingFacts.offer(action)) { overflow = true; ready = false }
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
    private val CORE = setOf("activity", "task_remove", "activity_finish", "activity_result", "binding_lifetime", "binding_remove", "bind_context", "service_context", "service_lookup", "stop_locked_fence", "stop_window_fence", "stop_process_fence", "stop_service_fence", "stop_package_fence", "stop_victim_fence")
    private val WAKE = setOf("activity", "service_lookup", "broadcast", "provider", "job", "alarm", "pending_intent", "process")
}
