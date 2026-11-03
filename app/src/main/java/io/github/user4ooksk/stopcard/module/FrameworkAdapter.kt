package io.github.user4ooksk.stopcard.module

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.user4ooksk.stopcard.policy.*
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

data class HookCapability(val status: String, val methods: List<String> = emptyList(), val detail: String = "")
data class ActivityFact(val token: String, val packageName: String, val uid: Int, val task: TaskKey?, val visible: Boolean)
data class TaskCloseFact(val task: TaskKey, val owner: String, val uid: Int, val pid: Int, val reason: String)
data class LaunchFact(
    val source: ActivityFact?,
    val target: ActivityFact,
    val component: String,
    val evidence: OpenSource?,
    val observed: Boolean = false,
)

/** Implementations must use memory or enqueue bounded work. Hooks must not wait for the worker. */
interface FrameworkCallbacks {
    fun wake()
    fun launch(fact: LaunchFact): EntryDecision
    fun launched(fact: LaunchFact, success: Boolean, originalToken: String? = null)
    fun window(fact: ActivityFact)
    fun removed(task: TaskKey, owner: String?, userClosed: Boolean)
    fun taskClosed(fact: TaskCloseFact)
    fun activityFinished(token: String)
    fun entry(userId: Int, target: CallTarget, sourceUid: Int = -1, sourcePackage: String? = null, evidence: OpenSource? = null, observed: Boolean = false): EntryDecision
    fun binding(token: String, source: String?, target: CallTarget, added: Boolean)
    fun component(token: String, source: String?, target: CallTarget, added: Boolean)
    fun fault(capability: String, cause: Throwable)
    fun stopGuard(packageName: String): Boolean
    fun keepAlive(uid: Int): Boolean
    fun processStarted(packageName: String, uid: Int)
}

/**
 * Central adapter for AOSP service contracts. Every hook checks parameter and return types before
 * installation. A different ROM disables that capability. It never guesses an argument index
 * from an unchecked method name. No hook invokes force-stop, shell, PM or filesystem operations.
 */
class FrameworkAdapter(private val loader: ClassLoader, private val boot: String, private val sink: FrameworkCallbacks) {
    private val capabilities = ConcurrentHashMap<String, HookCapability>()
    private val disabled = ConcurrentHashMap.newKeySet<String>()
    private val fields = ConcurrentHashMap<String, Field>()
    private val methods = ConcurrentHashMap<String, Method>()
    private val launchSource = ThreadLocal<OpenSource?>()
    private val pendingObserved = ThreadLocal<Boolean?>()
    private val pendingCaller = ThreadLocal<ActivityFact?>()
    private val activityRequest = ThreadLocal<Any?>()
    private val serviceCalls = ThreadLocal<ServiceCall?>()
    private data class Caller(val uid: Int, val pid: Int)
    private val closeCaller = ThreadLocal<Caller?>()
    private val systemUiProcesses = ConcurrentHashMap<Int, Int>()
    private data class BroadcastOrigin(val caller: ActivityFact, val evidence: OpenSource?, val observed: Boolean)
    private val broadcastOrigins = java.util.WeakHashMap<Any, BroadcastOrigin>()
    private data class ServiceCall(val kind: EntryKind, val source: String?, val connection: Any?, val uid: Int, var record: Any? = null)

    @Volatile private var atm: Any? = null
    @Volatile private var wm: Any? = null
    @Volatile private var ams: Any? = null
    @Volatile private var nms: Any? = null
    @Volatile private var recent: Any? = null
    @Volatile private var knownTasks = emptyMap<Int, TaskFact>()
    @Volatile private var launcherUids = emptySet<Int>()
    @Volatile private var systemUiUid = -1
    @Volatile private var providers = emptyMap<String, Triple<String, Int, String>>()
    @Volatile private var notificationIntents = emptySet<String>()

    fun report(): Map<String, HookCapability> = capabilities.toSortedMap()

    fun identities(launchers: Set<Int>, systemUi: Int, authorities: Map<String, Triple<String, Int, String>>) {
        launcherUids = launchers
        systemUiUid = systemUi
        providers = authorities
    }

    fun install() {
        capture("ActivityTaskManagerService") { atm = it }
        capture("WindowManagerService") { wm = it }
        capture("RecentTasks") { recent = it }
        captureClass("com.android.server.am.ActivityManagerService", "system_ready") { ams = it }
        captureClass("com.android.server.notification.NotificationManagerService", "notification_service") { nms = it }
        taskHooks()
        activityHooks()
        serviceHooks()
        notificationHooks()
        broadcastHooks()
        providerHooks()
        jobHooks()
        alarmHooks()
        pendingIntentHooks()
        processHooks()
        keepAliveHooks()
    }

    private fun capture(simple: String, action: (Any) -> Unit) = captureClass("com.android.server.wm.$simple", "capture_$simple", action)
    private fun captureClass(name: String, capability: String, action: (Any) -> Unit) {
        try {
            val type = Class.forName(name, false, loader)
            XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    guarded(capability) { action(param.thisObject); sink.wake() }
                }
            })
            capabilities[capability] = HookCapability("installed", listOf("$name.<init>"))
        } catch (e: Throwable) { unavailable(capability, e) }
    }

    private fun taskHooks() {
        for (name in listOf("removeTask", "removeAllVisibleRecentTasks")) {
            hook("close_context_$name", "com.android.server.wm.ActivityTaskManagerService", name, {
                if (name == "removeTask") it.returnType == Boolean::class.javaPrimitiveType && names(it) == listOf("int")
                else it.returnType == Void.TYPE && it.parameterCount == 0
            }, before = { p ->
                p.setObjectExtra("stopcard.closeCaller", closeCaller.get())
                closeCaller.set(Caller(Binder.getCallingUid(), Binder.getCallingPid()))
            })
        }
        hook("task_add", "com.android.server.wm.RecentTasks", "add", {
            it.returnType == Void.TYPE && names(it) == listOf("com.android.server.wm.Task")
        }, after = { sink.wake() })
        hook("task_remove", "com.android.server.wm.RecentTasks", "remove", {
            it.returnType == Void.TYPE && names(it) == listOf("com.android.server.wm.Task")
        }, before = { p ->
            val t = taskFact(p.args[0], recent)
            if (t.key.userId == 0) sink.removed(t.key, t.owner, false)
            sink.wake()
        })
        hook("user_task_close", "com.android.server.wm.ActivityTaskSupervisor", "removeTask", {
            it.returnType == Void.TYPE && names(it).take(4) == listOf("com.android.server.wm.Task", "boolean", "boolean", "java.lang.String") &&
                (it.parameterCount == 4 || names(it).drop(4) == listOf("int", "int", "java.lang.String"))
        }, before = { p ->
            val t = taskFact(p.args[0], recent)
            val reason = p.args[3] as String
            // Only the framework's recents removal route denotes an explicit close. Trimming,
            // display changes and finish() remain task reconciliation events.
            val caller = closeCaller.get() ?: if (p.args.size == 7) Caller(p.args[4] as Int, p.args[5] as Int) else Caller(-1, -1)
            val userClosed = p.args[2] == true && (caller.uid in launcherUids || trustedSystemUi(caller.uid, caller.pid)) &&
                reason in setOf("remove-task", "removeTask", "remove-all-visible-recent-tasks")
            if (p.args[2] == true && reason in setOf("remove-task", "removeTask", "remove-all-visible-recent-tasks")) {
                sink.taskClosed(TaskCloseFact(t.key, t.owner, caller.uid, caller.pid, reason))
            } else sink.removed(t.key, t.owner, userClosed)
        })
        hook("recents_open", "com.android.server.wm.ActivityTaskSupervisor", "startActivityFromRecents", {
            it.returnType == Int::class.javaPrimitiveType && names(it).take(3) == listOf("int", "int", "int")
        }, before = { p ->
            p.setObjectExtra("stopcard.previous", launchSource.get())
            if (p.args[1] as Int !in launcherUids && !trustedSystemUi(p.args[1] as Int, p.args[0] as Int)) return@hook
            launchSource.set(OpenSource.RECENTS)
            val t = knownTasks[p.args[2] as Int]
            if (t != null) {
                val target = ActivityFact("recents:${t.key.taskId}", t.owner, -1, t.key, false)
                val fact = LaunchFact(null, target, "", OpenSource.RECENTS)
                p.setObjectExtra("stopcard.launch", fact)
                sink.launch(fact)
            }
        }, after = { p ->
            (p.getObjectExtra("stopcard.launch") as? LaunchFact)?.let { sink.launched(it, !p.hasThrowable() && (p.result as? Int ?: -1) >= 0) }
            launchSource.set(p.getObjectExtra("stopcard.previous") as? OpenSource)
        })
    }

    private fun activityHooks() {
        hook("activity_request", "com.android.server.wm.ActivityStarter", "executeRequest", {
            it.returnType == Int::class.javaPrimitiveType && names(it) == listOf("com.android.server.wm.ActivityStarter\$Request")
        }, before = { p -> activityRequest.set(p.args[0]) })
        hook("activity_gate", "com.android.server.firewall.IntentFirewall", "checkStartActivity", {
            it.returnType == Boolean::class.javaPrimitiveType && names(it) == listOf("android.content.Intent", "int", "int", "java.lang.String", "android.content.pm.ApplicationInfo")
        }, after = { p ->
            if (p.hasThrowable() || p.result != true) return@hook
            val request = activityRequest.get() ?: return@hook
            val info = p.args[4] as ApplicationInfo
            if (info.uid / 100000 != 0) return@hook
            val intent = p.args[0] as Intent
            val caller = p.args[1] as Int
            val sourceToken = field(request, "resultTo") as? IBinder
            val source = sourceToken?.let {
                Class.forName("com.android.server.wm.ActivityRecord", false, loader)
                    .getDeclaredMethod("forTokenLocked", IBinder::class.java).apply { isAccessible = true }.invoke(null, it)
            }?.let(::activityFact)
            // Native firewall rejection sends RESULT_CANCELED and aborts options before a new
            // ActivityRecord/transition exists. Do not short-circuit startActivityInner cleanup.
            val userOpen = launchSource.get() != null || pendingObserved.get() == true || (caller in launcherUids &&
                intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER))
            if (!userOpen && source?.visible != true) {
                val resolved = field(request, "activityInfo") as? ActivityInfo
                val component = resolved?.let { ComponentName(it.packageName, it.name).flattenToString() }
                if (!sink.entry(0, CallTarget(info.packageName, component, EntryKind.ACTIVITY), caller,
                        field(request, "callingPackage") as? String).allow) p.result = false
            }
        })
        hook("activity", "com.android.server.wm.ActivityStarter", "startActivityInner", {
            it.returnType == Int::class.javaPrimitiveType && names(it).take(2) == listOf("com.android.server.wm.ActivityRecord", "com.android.server.wm.ActivityRecord")
        }, before = { p ->
            val r = p.args[0]
            val target = activityFact(r)
            if (target.uid / 100000 != 0) return@hook
            val source = p.args[1]?.let(::activityFact) ?: pendingCaller.get()
            val callingUid = field(r, "launchedFromUid") as Int
            val intent = field(r, "intent") as Intent
            val evidence = launchSource.get() ?: when {
                callingUid in launcherUids && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) -> OpenSource.LAUNCHER
                else -> null
            }
            val info = field(r, "info") as ActivityInfo
            val fact = LaunchFact(source, target, ComponentName(info.packageName, info.name).flattenToString(), evidence, pendingObserved.get() == true)
            p.setObjectExtra("stopcard.launch", fact)
            sink.launch(fact) // Rejection belongs to the earlier native firewall boundary.
        }, after = { p ->
            val original = p.getObjectExtra("stopcard.launch") as? LaunchFact ?: return@hook
            val result = p.result as? Int ?: -1
            val actual = if (result in 0..3) field(p.thisObject, "mLastStartActivityRecord") ?: p.args[0] else p.args[0]
            val fact = original.copy(target = activityFact(actual))
            sink.launched(fact, !p.hasThrowable() && result in 0..3, original.target.token)
            sink.wake()
        })
        for ((method, visible) in listOf("onWindowsVisible" to true, "onWindowsGone" to false)) {
            hook("window_$visible", "com.android.server.wm.ActivityRecord", method, {
                it.returnType == Void.TYPE && it.parameterCount == 0
            }, after = { p ->
                val fact = activityFact(p.thisObject).copy(visible = visible)
                sink.window(fact)
                sink.wake()
            })
        }
        hook("activity_finish", "com.android.server.wm.ActivityRecord", "removeFromHistory", {
            it.returnType == Void.TYPE && names(it) == listOf("java.lang.String")
        }, after = { p ->
            val token = activityFact(p.thisObject).token
            sink.activityFinished(token)
            sink.wake()
        })
    }

    private fun serviceHooks() {
        for ((method, kind) in listOf("startServiceLocked" to EntryKind.SERVICE, "bindServiceLocked" to EntryKind.BIND)) {
            hook("${kind.name.lowercase()}_context", "com.android.server.am.ActiveServices", method, {
                val n = names(it)
                if (kind == EntryKind.SERVICE) n.take(7) == listOf("android.app.IApplicationThread", "android.content.Intent", "java.lang.String", "int", "int", "boolean", "java.lang.String") && it.returnType == ComponentName::class.java
                else n.take(5) == listOf("android.app.IApplicationThread", "android.os.IBinder", "android.content.Intent", "java.lang.String", "android.app.IServiceConnection") && n.takeLast(2) == listOf("java.lang.String", "int") && it.returnType == Int::class.javaPrimitiveType
            }, before = { p ->
                p.setObjectExtra("stopcard.previous", serviceCalls.get())
                val source = if (kind == EntryKind.SERVICE) p.args[6] as? String else p.args[p.args.size - 2] as? String
                serviceCalls.set(ServiceCall(kind, source, if (kind == EntryKind.BIND) p.args[4] else null,
                    if (kind == EntryKind.SERVICE) p.args[4] as Int else Binder.getCallingUid()))
            }, after = { p ->
                val context = serviceCalls.get()
                val record = context?.record
                if (kind == EntryKind.SERVICE && record != null) {
                    val name = field(record, "name") as ComponentName
                    sink.component(token(record), context.source, CallTarget(name.packageName, name.flattenToString(), kind), !p.hasThrowable() && p.result == name)
                }
                if (kind == EntryKind.BIND && record != null && (p.hasThrowable() || (p.result as? Int ?: 0) <= 0)) {
                    val name = field(record, "name") as ComponentName
                    sink.component("failed_bind:${token(record)}", context.source, CallTarget(name.packageName, name.flattenToString(), kind), false)
                }
                serviceCalls.set(p.getObjectExtra("stopcard.previous") as? ServiceCall)
            })
        }
        hook("service_lookup", "com.android.server.am.ActiveServices", "retrieveServiceLocked", {
            it.returnType.name == "com.android.server.am.ActiveServices\$ServiceLookupResult" && names(it).firstOrNull() == "android.content.Intent"
        }, after = { p ->
            val context = serviceCalls.get() ?: return@hook
            val result = p.result ?: return@hook
            val r = field(result, "record") ?: return@hook
            val name = field(r, "name") as ComponentName
            val info = field(r, "appInfo") as ApplicationInfo
            val target = CallTarget(name.packageName, name.flattenToString(), context.kind)
            val decision = entry(info.uid / 100000, target, context.uid, context.source)
            if (!decision.allow) {
                // Both callers check res == null before creating AppBindRecord/ConnectionRecord:
                // startServiceLocked returns null; bindServiceLocked returns 0 (not bound).
                p.result = null
            } else {
                context.record = r
                p.setObjectExtra("stopcard.binding", target)
                // The connection is tracked only after Android actually adds it, below.
            }
        })
        try {
            val type = Class.forName("com.android.server.am.ConnectionRecord", false, loader)
            XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded("binding_lifetime") {
                    val context = serviceCalls.get() ?: return@guarded
                    val connection = field(param.thisObject, "conn") ?: return@guarded
                    val binding = field(param.thisObject, "binding") ?: return@guarded
                    val service = field(binding, "service") ?: return@guarded
                    val name = field(service, "name") as ComponentName
                    sink.binding(token(param.thisObject), context.source, CallTarget(name.packageName, name.flattenToString(), EntryKind.BIND), true)
                }
            })
            capabilities["binding_lifetime"] = HookCapability("installed", listOf("ConnectionRecord.<init>"))
        } catch (e: Throwable) { unavailable("binding_lifetime", e) }
        hook("binding_remove", "com.android.server.am.ActiveServices", "removeConnectionLocked", {
            it.returnType == Int::class.javaPrimitiveType && names(it).firstOrNull() == "com.android.server.am.ConnectionRecord"
        }, after = { p ->
            sink.binding(token(p.args[0]), null, CallTarget("", kind = EntryKind.BIND), false)
        })
        hook("service_finish", "com.android.server.am.ActiveServices", "bringDownServiceLocked", {
            it.returnType == Void.TYPE && names(it) == listOf("com.android.server.am.ServiceRecord", "boolean")
        }, after = { p -> if (!p.hasThrowable()) sink.activityFinished(token(p.args[0])) })
    }

    private fun notificationHooks() {
        hook("notifications", "com.android.server.notification.NotificationManagerService", "checkDisqualifyingFeatures", {
            it.returnType == Boolean::class.javaPrimitiveType && names(it) == listOf("int", "int", "int", "java.lang.String", "com.android.server.notification.NotificationRecord", "boolean", "boolean")
        }, after = { p ->
            if (p.hasThrowable() || p.result != true) return@hook
            val record = p.args[4]
            val sbn = call(record, "getSbn")!!
            val pkg = call(sbn, "getPackageName") as String
            val user = p.args[0] as Int
            val decision = sink.entry(user, CallTarget(pkg, kind = EntryKind.NOTIFICATION), Binder.getCallingUid(), call(sbn, "getOpPkg") as? String)
            if (!decision.allow) {
                // Native identity/FGS policy has run, but the notification has not been queued.
                // Report the same not-shown outcome used by the native UPDATE_ONLY path.
                call(p.thisObject, "maybeReportForegroundServiceUpdate", record, false)
                p.result = false
            }
        })
    }

    private fun broadcastHooks() {
        // A PendingIntent returns before asynchronous delivery. Keep provenance on the actual
        // native broadcast record, never on the Binder thread that later dispatches a receiver.
        try {
            val type = Class.forName("com.android.server.am.BroadcastRecord", false, loader)
            XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    guarded("broadcast_origin") {
                        val origin = pendingCaller.get()?.let { BroadcastOrigin(it, launchSource.get(), pendingObserved.get() == true) }
                            ?: param.args.firstOrNull()?.takeIf(type::isInstance)?.let { synchronized(broadcastOrigins) { broadcastOrigins[it] } }
                            ?: return@guarded
                        if (field(param.thisObject, "callingUid") != origin.caller.uid) return@guarded
                        synchronized(broadcastOrigins) {
                            check(broadcastOrigins.size < 512 || broadcastOrigins.containsKey(param.thisObject)) { "Broadcast provenance limit" }
                            broadcastOrigins[param.thisObject] = origin
                        }
                    }
                }
            })
            capabilities["broadcast_origin"] = HookCapability("installed", listOf("BroadcastRecord.<init>"))
        } catch (e: Throwable) { unavailable("broadcast_origin", e) }
        hook("broadcast", "com.android.server.am.BroadcastSkipPolicy", "shouldSkipMessage", {
            it.returnType == String::class.java && names(it) == listOf("com.android.server.am.BroadcastRecord", "java.lang.Object")
        }, after = { p ->
            if (p.result != null || p.hasThrowable()) return@hook
            val receiver = p.args[1]
            val info = if (receiver is android.content.pm.ResolveInfo) receiver.activityInfo else null
            val pkg = info?.packageName ?: field(receiver, "packageName") as? String ?: return@hook
            val uid = info?.applicationInfo?.uid ?: field(receiver, "owningUid") as Int
            val component = info?.let { ComponentName(it.packageName, it.name).flattenToString() }
            val record = p.args[0]
            val origin = synchronized(broadcastOrigins) { broadcastOrigins[record] }
            val decision = sink.entry(uid / 100000, CallTarget(pkg, component, EntryKind.BROADCAST),
                field(record, "callingUid") as Int, field(record, "callerPackage") as? String, origin?.evidence, origin?.observed == true)
            if (!decision.allow) p.result = "Stop Card: no runtime permission"
            // BroadcastQueueModernImpl consumes this reason in both cold and warm dispatch,
            // sets DELIVERY_SKIPPED, and advances completion/timeout state itself.
        })
        hook("broadcast_lifetime", "com.android.server.am.BroadcastQueueModernImpl", "setDeliveryState", {
            it.returnType == Void.TYPE && names(it) == listOf("com.android.server.am.BroadcastProcessQueue", "com.android.server.am.ProcessRecord", "com.android.server.am.BroadcastRecord", "int", "java.lang.Object", "int", "java.lang.String")
        }, after = { p ->
            if (p.hasThrowable()) return@hook
            val r = p.args[2]
            val receiver = p.args[4]
            val info = (receiver as? android.content.pm.ResolveInfo)?.activityInfo
            val pkg = info?.packageName ?: field(receiver, "packageName") as? String ?: return@hook
            val name = info?.let { ComponentName(it.packageName, it.name).flattenToString() }
            val state = p.args[5] as Int
            val type = r.javaClass
            val scheduled = type.getDeclaredField("DELIVERY_SCHEDULED").apply { isAccessible = true }.getInt(null)
            val terminal = type.getDeclaredMethod("isDeliveryStateTerminal", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(null, state) == true
            if (state == scheduled || terminal) sink.component("${token(r)}:${p.args[3]}", field(r, "callerPackage") as? String,
                CallTarget(pkg, name, EntryKind.BROADCAST), !terminal)
            if (terminal && field(r, "terminalCount") == (field(r, "receivers") as List<*>).size) {
                synchronized(broadcastOrigins) { broadcastOrigins.remove(r) }
            }
        })
    }

    private fun providerHooks() {
        hook("provider", "com.android.server.am.ContentProviderHelper", "getContentProviderImpl", {
            it.returnType.name == "android.app.ContentProviderHolder" && names(it) == listOf("android.app.IApplicationThread", "java.lang.String", "android.os.IBinder", "int", "java.lang.String", "java.lang.String", "boolean", "int")
        }, before = { p ->
            val owner = providers[p.args[1] as String] ?: return@hook
            val user = p.args[7] as Int
            val target = CallTarget(owner.first, owner.third, EntryKind.PROVIDER)
            val callerUid = (p.args[3] as Int).let { if (it < 0) Binder.getCallingUid() else it }
            val source = p.args[4] as? String
            p.setObjectExtra("stopcard.provider", target)
            p.setObjectExtra("stopcard.source", source)
            if (!entry(user, target, callerUid, source).allow) p.result = null
            // Null is the native unavailable-provider result. No stable/unstable reference or
            // ContentProviderConnection has been created at this entry point.
        }, after = { p ->
            val target = p.getObjectExtra("stopcard.provider") as? CallTarget ?: return@hook
            val source = p.getObjectExtra("stopcard.source") as? String
            val connection = if (!p.hasThrowable()) p.result?.let { field(it, "connection") } else null
            sink.component(connection?.let(::token) ?: "provider_failed", source, target, connection != null)
        })
        hook("provider_finish", "com.android.server.am.ContentProviderHelper", "handleProviderRemoval", {
            it.returnType == Void.TYPE && names(it) == listOf("com.android.server.am.ContentProviderConnection", "boolean", "boolean")
        }, after = { p ->
            val connection = p.args[0] ?: return@hook
            if (!p.hasThrowable() && call(connection, "totalRefCount") == 0) sink.activityFinished(token(connection))
        })
    }

    private fun jobHooks() {
        hook("job", "com.android.server.job.JobServiceContext", "executeRunnableJob", {
            it.returnType == Boolean::class.javaPrimitiveType && names(it) == listOf("com.android.server.job.controllers.JobStatus", "int")
        }, before = { p ->
            val job = p.args[0]
            val info = call(job, "getJob") as android.app.job.JobInfo
            val uid = call(job, "getUid") as Int
            val component = info.service
            if (!sink.entry(uid / 100000, CallTarget(component.packageName, component.flattenToString(), EntryKind.JOB), uid).allow) p.result = false
            // false is the native pre-execution failure path. The concurrency manager handles
            // the unstarted context; mRunningJob and callbacks remain untouched.
        })
    }

    private fun alarmHooks() {
        hook("alarm", "com.android.server.alarm.AlarmManagerService\$DeliveryTracker", "deliverLocked", {
            it.returnType == Void.TYPE && names(it) == listOf("com.android.server.alarm.Alarm", "long")
        }, before = { p ->
            val alarm = p.args[0]
            val uid = field(alarm, "creatorUid") as Int
            val pkg = field(alarm, "sourcePackage") as? String ?: field(alarm, "packageName") as String
            if (!sink.entry(uid / 100000, CallTarget(pkg, kind = EntryKind.ALARM), uid).allow) {
                val service = field(p.thisObject, "this\$0")!!
                val listener = field(alarm, "listener")
                val sent = if (listener == null) "mSendCount" else "mListenerCount"
                val finished = if (listener == null) "mSendFinishCount" else "mListenerFinishCount"
                val sendCount = field(service, sent) as Int
                val finishCount = field(service, finished) as Int
                val binder = listener?.let { call(it, "asBinder") as IBinder }
                val recipient = if (listener == null) null else field(service, "mListenerDeathRecipient") as IBinder.DeathRecipient
                // Once cleanup begins, never fall through and deliver the same alarm again.
                // All reflective reads are validated before committing the native rejection.
                p.result = null
                setField(service, sent, sendCount + 1)
                setField(service, finished, finishCount + 1)
                if (binder != null && recipient != null) binder.unlinkToDeath(recipient, 0)
            }
            // deliverAlarmsLocked still decrements the scheduled alarm count. DeliveryTracker
            // has not acquired its wake lock or posted listener/PI timeout accounting yet.
        })
    }

    private fun pendingIntentHooks() {
        hook("pending_intent", "com.android.server.am.PendingIntentRecord", "sendInner", {
            it.returnType == Int::class.javaPrimitiveType && names(it).take(4) == listOf("android.app.IApplicationThread", "int", "android.content.Intent", "java.lang.String")
        }, before = { p ->
            p.setObjectExtra("stopcard.previous", launchSource.get())
            p.setObjectExtra("stopcard.pendingObserved", pendingObserved.get())
            p.setObjectExtra("stopcard.pendingCaller", pendingCaller.get())
            val key = field(p.thisObject, "key") ?: return@hook
            val creator = field(key, "packageName") as String
            val uid = field(p.thisObject, "uid") as Int
            pendingCaller.set(ActivityFact("pending:$uid", creator, uid, null, false))
            val sender = Binder.getCallingUid()
            if (trustedSystemUi(sender, Binder.getCallingPid())) {
                if (token(p.thisObject) in notificationIntents) launchSource.set(OpenSource.NOTIFICATION)
                else pendingObserved.set(true)
            }
            // Gate the final resolved Activity/service/receiver after native user and fill-in
            // resolution. A creator package or mutable original Intent is not the target identity.
        }, after = { p ->
            launchSource.set(p.getObjectExtra("stopcard.previous") as? OpenSource)
            pendingObserved.set(p.getObjectExtra("stopcard.pendingObserved") as? Boolean)
            pendingCaller.set(p.getObjectExtra("stopcard.pendingCaller") as? ActivityFact)
        })
    }

    private fun processHooks() {
        hook("ui_process_identity", "com.android.server.am.ProcessRecord", "setPid", {
            it.returnType == Void.TYPE && names(it) == listOf("int")
        }, before = { p ->
            val previous = call(p.thisObject, "getPid") as Int
            systemUiProcesses.remove(previous)
            systemUiProcesses.remove(p.args[0] as Int)
        }, after = { p ->
            if (p.hasThrowable()) return@hook
            val info = field(p.thisObject, "info") as ApplicationInfo
            val pid = p.args[0] as Int
            if (pid > 0) sink.processStarted(info.packageName, info.uid)
            if (pid > 0 && info.packageName == "com.android.systemui" && field(p.thisObject, "processName") == "com.android.systemui") {
                systemUiProcesses[pid] = info.uid
            }
        })
        hook("process", "com.android.server.am.ProcessList", "startProcessLocked", {
            it.returnType.name == "com.android.server.am.ProcessRecord" && names(it).take(5) == listOf("java.lang.String", "android.content.pm.ApplicationInfo", "boolean", "int", "com.android.server.am.HostingRecord")
        }, before = { p ->
            val info = p.args[1] as ApplicationInfo
            val name = p.args[4]?.let { call(it, "getName") as? String }?.removeSurrounding("{", "}")
            val component = name?.let(ComponentName::unflattenFromString)?.flattenToString()
            if (!sink.entry(info.uid / 100000, CallTarget(info.packageName, component, EntryKind.PROCESS)).allow) p.result = null
            // This overload returns null for denied/bad processes before creating a ProcessRecord.
            // Resolved activity/service/broadcast callers retain their own native failure paths.
        })
    }

    private fun trustedSystemUi(uid: Int, pid: Int): Boolean =
        uid >= 0 && uid == systemUiUid && pid > 0 && systemUiProcesses[pid] == uid

    fun recentsCaller(uid: Int, pid: Int): Boolean = uid in launcherUids || trustedSystemUi(uid, pid)

    private fun entry(userId: Int, target: CallTarget, sourceUid: Int = -1, sourcePackage: String? = null): EntryDecision =
        sink.entry(userId, target, sourceUid, sourcePackage, launchSource.get(), pendingObserved.get() == true)

    private fun keepAliveHooks() {
        hook("extra_keep_alive", "com.android.server.am.ProcessStateRecord", "setCurAdj", {
            it.returnType == Void.TYPE && names(it) == listOf("int")
        }, before = { p ->
            val adj = p.args[0] as Int
            if (adj < 900 || adj > 999) return@hook
            val app = field(p.thisObject, "mApp") ?: return@hook
            val uid = field(app, "uid") as Int
            // Stay in Android's cached band. Never promote to foreground/persistent priority.
            if (sink.keepAlive(uid)) p.args[0] = 900
        })
    }

    /** Worker only. Use Android's ordinary OOM pass when a cached-priority grant changes. */
    fun recomputePriority() {
        val service = ams ?: return
        synchronized(service) { call(service, "updateOomAdjLocked", 0) }
    }

    fun runningPackages(): Set<String> {
        val service = ams ?: error("No process service")
        return synchronized(service) {
            synchronized(field(service, "mProcLock")!!) {
                val records = field(field(service, "mProcessList")!!, "mLruProcesses") as List<*>
                require(records.size <= 4096)
                records.filterNotNull().filter { (call(it, "getPid") as Int) > 0 }
                    .map { (field(it, "info") as ApplicationInfo).packageName }.toSet()
            }
        }
    }

    /** Worker only. Android owns its lock scopes and complete native teardown. */
    fun forceStop(ticket: StopTicket): Boolean {
        val service = ams ?: error("ActivityManagerService not captured")
        if (!sink.stopGuard(ticket.app.packageName)) return false
        call(service, "forceStopPackage", ticket.app.packageName, ticket.app.userId)
        return true
    }

    fun discover() {
        fun singleton(type: String, name: String): Any? = Class.forName(type, false, loader).getDeclaredMethod(name).apply { isAccessible = true }.invoke(null)
        if (ams == null) ams = singleton("android.app.ActivityManager", "getService")
        if (atm == null) atm = singleton("android.app.ActivityTaskManager", "getService")
        if (wm == null) wm = singleton("android.view.WindowManagerGlobal", "getWindowManagerService")
        if (recent == null) recent = atm?.let { field(it, "mRecentTasks") }
        if (nms == null) nms = singleton("android.app.NotificationManager", "getService")?.let { field(it, "this\$0") }
        if (capabilities["force_stop"] == null) runCatching {
            val service = ams ?: error("No activity service")
            val method = service.javaClass.getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
            require(method.returnType == Void.TYPE)
            capabilities["force_stop"] = HookCapability("installed", listOf(method.toGenericString()), "native force-stop; Android owns locking")
        }.onFailure { unavailable("force_stop", it) }
        refreshNotificationIntents()
        // Hooks may be enabled after SystemUI started, for example after an independent pause.
        // Seed from real ProcessRecords; setPid keeps this cache safe against later PID reuse.
        if (systemUiProcesses.isEmpty() && capabilities["ui_process_identity"]?.status == "installed") {
            val pids = ams?.let { field(it, "mPidsSelfLocked") }
            if (pids != null) synchronized(pids) {
                val count = call(pids, "size") as Int
                require(count <= 4096) { "Process inventory exceeds limit" }
                for (i in 0 until count) {
                    val record = call(pids, "valueAt", i) ?: continue
                    val info = field(record, "info") as? ApplicationInfo ?: continue
                    if (info.packageName == "com.android.systemui" && field(record, "processName") == info.packageName && info.uid == systemUiUid) {
                        val pid = call(record, "getPid") as Int
                        if (pid > 0) systemUiProcesses[pid] = info.uid
                    }
                }
            }
        }
    }

    private fun refreshNotificationIntents() {
        val service = nms ?: return
        runCatching {
            val lock = field(service, "mNotificationLock")!!
            notificationIntents = synchronized(lock) {
                val list = field(service, "mNotificationList") as List<*>
                require(list.size <= 4096)
                buildSet {
                    for (record in list.filterNotNull()) {
                        val notification = call(call(record, "getSbn")!!, "getNotification") as Notification
                        fun addIntent(intent: PendingIntent?) { intent?.let { call(it, "getTarget") }?.let { add(token(it)) } }
                        addIntent(notification.contentIntent)
                        notification.actions.orEmpty().forEach { addIntent(it.actionIntent) }
                    }
                }
            }
            capabilities["notification_open"] = HookCapability("installed", detail = "SystemUI process and posted notification content/action PendingIntent identity")
        }.onFailure { notificationIntents = emptySet(); unavailable("notification_open", it) }
    }

    /** Full recent tasks plus all Activity records, including shared tasks, split-screen and PiP. */
    fun snapshot(): WorldSnapshot {
        val service = atm ?: error("Task service not captured")
        val recents = recent ?: error("RecentTasks not captured")
        val lock = field(service, "mGlobalLock") ?: error("Task lock unavailable")
        return synchronized(lock) {
            val list = field(recents, "mTasks") as? List<*> ?: error("Recent tasks unavailable")
            require(list.size <= 512) { "Task snapshot exceeds limit" }
            val tasks = list.filterNotNull().map { taskFact(it, recents) }
            val root = field(service, "mRootWindowContainer") ?: error("Window root unavailable")
            val visible = HashSet<String>()
            val windows = HashMap<String, String>()
            var count = 0
            call(root, "forAllActivities", Consumer<Any> { record ->
                require(++count <= 4096) { "Activity snapshot exceeds limit" }
                val a = activityFact(record)
                if (a.uid / 100000 == 0 && a.visible) { visible += a.packageName; windows[a.token] = a.packageName }
            }, true)
            knownTasks = tasks.filter { it.key.userId == 0 }.associateBy { it.key.taskId }
            WorldSnapshot(tasks, visible, true, boot, windows = windows)
        }
    }

    private fun taskFact(task: Any, recents: Any?): TaskFact {
        val id = field(task, "mTaskId") as Int
        val user = field(task, "mUserId") as Int
        val intent = call(task, "getBaseIntent") as? Intent
        val owner = (field(task, "realActivity") as? ComponentName)?.packageName
            ?: intent?.component?.packageName ?: error("Task has no owner")
        val participants = HashSet<String>()
        val visible = HashSet<String>()
        var count = 0
        call(task, "forAllActivities", Consumer<Any> { r ->
            require(++count <= 1024) { "Task Activity limit" }
            val a = activityFact(r)
            participants += a.packageName
            if (a.visible) visible += a.packageName
        }, true)
        val eligible = recents != null && call(recents, "isVisibleRecentTask", task) == true &&
            intent != null && intent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0
        return TaskFact(TaskKey(user, id, boot), owner, participants, visible, eligible)
    }

    private fun activityFact(record: Any): ActivityFact {
        val info = field(record, "info") as ActivityInfo
        val task = call(record, "getTask")
        val key = task?.let { TaskKey(field(it, "mUserId") as Int, field(it, "mTaskId") as Int, boot) }
        val visible = call(record, "isVisible") == true || call(record, "isVisibleRequested") == true
        return ActivityFact(token(field(record, "token") ?: record), info.packageName, info.applicationInfo.uid, key, visible)
    }

    private fun token(value: Any): String {
        val binder = if (value is IBinder) value else runCatching { call(value, "asBinder") }.getOrNull() ?: value
        return "${binder.javaClass.name}:${System.identityHashCode(binder)}"
    }

    private fun hook(
        capability: String, className: String, name: String, matches: (Method) -> Boolean,
        before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
    ) {
        try {
            val type = Class.forName(className, false, loader)
            val found = type.declaredMethods.filter { it.name == name && matches(it) }
            require(found.isNotEmpty()) { "No supported $className.$name contract" }
            for (method in found) XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Save context even when a capability was disabled by an earlier invocation.
                    // The paired finally must restore an enclosing call's exact provenance.
                    when {
                        capability == "activity_request" -> param.setObjectExtra("stopcard.request", activityRequest.get())
                        capability.startsWith("close_context_") -> param.setObjectExtra("stopcard.closeCaller", closeCaller.get())
                        capability.endsWith("_context") -> param.setObjectExtra("stopcard.previous", serviceCalls.get())
                        capability == "pending_intent" || capability == "recents_open" -> {
                            param.setObjectExtra("stopcard.previous", launchSource.get())
                            param.setObjectExtra("stopcard.pendingObserved", pendingObserved.get())
                            param.setObjectExtra("stopcard.pendingCaller", pendingCaller.get())
                        }
                    }
                    if (before != null) guarded(capability) { before(param) }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (after != null) guarded(capability) { after(param) }
                    } finally {
                        // Restore provenance even if a capability disabled itself during entry.
                        // Nested service/PI calls must not leave trust on the Binder thread.
                        when {
                            capability == "activity_request" -> activityRequest.set(param.getObjectExtra("stopcard.request"))
                            capability.startsWith("close_context_") -> closeCaller.set(param.getObjectExtra("stopcard.closeCaller") as? Caller)
                            capability.endsWith("_context") -> serviceCalls.set(param.getObjectExtra("stopcard.previous") as? ServiceCall)
                            capability == "pending_intent" || capability == "recents_open" -> {
                                launchSource.set(param.getObjectExtra("stopcard.previous") as? OpenSource)
                                pendingObserved.set(param.getObjectExtra("stopcard.pendingObserved") as? Boolean)
                                pendingCaller.set(param.getObjectExtra("stopcard.pendingCaller") as? ActivityFact)
                            }
                        }
                    }
                }
            })
            capabilities[capability] = HookCapability("installed", found.map { it.toGenericString() })
        } catch (e: Throwable) { unavailable(capability, e) }
    }

    private inline fun guarded(capability: String, action: () -> Unit) {
        if (capability in disabled) return
        try { action() }
        catch (e: Throwable) {
            disabled += capability
            capabilities[capability] = HookCapability("degraded", detail = e.javaClass.simpleName)
            sink.fault(capability, e)
        }
    }

    private fun unavailable(capability: String, e: Throwable) {
        capabilities[capability] = HookCapability("unavailable", detail = "${e.javaClass.simpleName}: ${e.message}")
    }
    private fun names(method: Method) = method.parameterTypes.map { it.name }

    private fun field(instance: Any, name: String): Any? {
        val key = "${instance.javaClass.name}#$name"
        val f = fields[key] ?: run {
            var type: Class<*>? = instance.javaClass
            var result: Field? = null
            while (type != null && result == null) {
                val current = type
                result = runCatching { current.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                type = type.superclass
            }
            (result ?: error("Missing field $key")).also { fields[key] = it }
        }
        return f.get(instance)
    }

    private fun setField(instance: Any, name: String, value: Any) {
        field(instance, name)
        fields["${instance.javaClass.name}#$name"]!!.set(instance, value)
    }

    private fun call(instance: Any, name: String, vararg args: Any?): Any? {
        val key = "${instance.javaClass.name}#$name(${args.joinToString { it?.javaClass?.name ?: "null" }})"
        val m = methods[key] ?: run {
            var type: Class<*>? = instance.javaClass
            var result: Method? = null
            while (type != null && result == null) {
                result = type.declaredMethods.firstOrNull { method ->
                    method.name == name && method.parameterCount == args.size && method.parameterTypes.indices.all { i ->
                        val arg = args[i]
                        if (arg == null) !method.parameterTypes[i].isPrimitive else boxed(method.parameterTypes[i]).isInstance(arg)
                    }
                }
                type = type.superclass
            }
            (result ?: error("Missing method $key")).apply { isAccessible = true }.also { methods[key] = it }
        }
        return m.invoke(instance, *args)
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        else -> type
    }

}
