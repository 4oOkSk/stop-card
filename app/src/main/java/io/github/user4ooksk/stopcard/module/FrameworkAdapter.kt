package io.github.user4ooksk.stopcard.module

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.view.MotionEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.user4ooksk.stopcard.policy.*
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

data class HookCapability(val status: String, val methods: List<String> = emptyList(), val detail: String = "")
data class ActivityFact(val token: String, val packageName: String, val uid: Int, val task: TaskKey?, val visible: Boolean)
data class LaunchFact(
    val source: ActivityFact?,
    val target: ActivityFact,
    val component: String,
    val evidence: OpenSource?,
    val callback: String?,
)

/** Implementations must use memory or enqueue bounded work. Hooks must not wait for the worker. */
interface FrameworkCallbacks {
    fun wake()
    fun launch(fact: LaunchFact): EntryDecision
    fun launched(fact: LaunchFact, success: Boolean)
    fun window(fact: ActivityFact)
    fun removed(task: TaskKey, owner: String?, userClosed: Boolean)
    fun activityFinished(token: String)
    fun resultDelivered(token: String)
    fun entry(userId: Int, target: CallTarget, sourceUid: Int = -1, sourcePackage: String? = null): EntryDecision
    fun binding(token: String, source: String?, target: CallTarget, added: Boolean)
    fun fault(capability: String, cause: Throwable)
    fun stopGuard(packageName: String): Boolean
    fun keepAlive(uid: Int): Boolean
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
    private val inputs = ConcurrentHashMap<String, Pair<String, Long>>()
    private val launchSource = ThreadLocal<OpenSource?>()
    private val serviceCalls = ThreadLocal<ServiceCall?>()
    private val stops = ThreadLocal<String?>()
    private val stopUid = ThreadLocal<Int?>()
    private data class Caller(val uid: Int, val pid: Int)
    private val closeCaller = ThreadLocal<Caller?>()
    private val systemUiProcesses = ConcurrentHashMap<Int, Int>()
    private data class ServiceCall(val kind: EntryKind, val source: String?, val connection: Any?, val uid: Int)

    @Volatile private var atm: Any? = null
    @Volatile private var wm: Any? = null
    @Volatile private var ams: Any? = null
    @Volatile private var nms: Any? = null
    @Volatile private var recent: Any? = null
    private var packageManager: Any? = null
    @Volatile private var knownTasks = emptyMap<Int, TaskFact>()
    @Volatile private var launcherUids = emptySet<Int>()
    @Volatile private var systemUiUid = -1
    @Volatile private var providers = emptyMap<String, Pair<String, Int>>()
    private var pointerListener: Any? = null

    fun report(): Map<String, HookCapability> = capabilities.toSortedMap()

    fun identities(launchers: Set<Int>, systemUi: Int, authorities: Map<String, Pair<String, Int>>) {
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
        stopHooks()
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
            sink.removed(t.key, t.owner, userClosed)
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
                val fact = LaunchFact(null, target, "", OpenSource.RECENTS, null)
                p.setObjectExtra("stopcard.launch", fact)
                sink.launch(fact)
            }
        }, after = { p ->
            (p.getObjectExtra("stopcard.launch") as? LaunchFact)?.let { sink.launched(it, !p.hasThrowable() && (p.result as? Int ?: -1) >= 0) }
            launchSource.set(p.getObjectExtra("stopcard.previous") as? OpenSource)
        })
    }

    private fun activityHooks() {
        hook("activity", "com.android.server.wm.ActivityStarter", "startActivityInner", {
            it.returnType == Int::class.javaPrimitiveType && names(it).take(2) == listOf("com.android.server.wm.ActivityRecord", "com.android.server.wm.ActivityRecord")
        }, before = { p ->
            val r = p.args[0]
            val target = activityFact(r)
            if (target.uid / 100000 != 0) return@hook
            val source = p.args[1]?.let(::activityFact)
            val callingUid = field(r, "launchedFromUid") as Int
            val intent = field(r, "intent") as Intent
            val input = source?.let { inputs.remove(it.packageName) }
            val sourceInfo = p.args[1]?.let { field(it, "info") as ActivityInfo }
            val evidence = launchSource.get() ?: when {
                callingUid in launcherUids && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) -> OpenSource.LAUNCHER
                input != null && input.first == source.token && SystemClock.elapsedRealtime() - input.second in 0..INPUT_MS &&
                    sourceInfo?.name in setOf("com.android.internal.app.ChooserActivity", "com.android.internal.app.ResolverActivity") &&
                    ((sourceInfo?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0 -> OpenSource.SYSTEM_CHOOSER
                input != null && input.first == source.token && SystemClock.elapsedRealtime() - input.second in 0..INPUT_MS -> OpenSource.INTERACTION
                else -> null
            }
            val info = field(r, "info") as ActivityInfo
            val resultTo = field(r, "resultTo")
            val callback = resultTo?.let { callbackKey(activityFact(it).token, field(r, "resultWho") as? String, field(r, "requestCode") as Int) }
            val fact = LaunchFact(source, target, ComponentName(info.packageName, info.name).flattenToString(), evidence, callback)
            p.setObjectExtra("stopcard.launch", fact)
            val decision = sink.launch(fact)
            if (!decision.allow) {
                // This point is past Android's source/result resolution. START_ABORTED follows
                // ActivityStarter.handleStartResult's native failure cleanup and option abort.
                p.result = 102 // ActivityManager.START_ABORTED
            }
        }, after = { p ->
            val original = p.getObjectExtra("stopcard.launch") as? LaunchFact ?: return@hook
            val result = p.result as? Int ?: -1
            val fact = original.copy(target = activityFact(p.args[0]))
            sink.launched(fact, !p.hasThrowable() && result in 0..3)
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
        hook("activity_finish", "com.android.server.wm.ActivityRecord", "finishActivityResults", {
            it.returnType == Void.TYPE && names(it).take(2) == listOf("int", "android.content.Intent")
        }, after = { p ->
            val token = activityFact(p.thisObject).token
            sink.activityFinished(token)
            sink.wake()
        })
        hook("activity_result", "android.app.servertransaction.ClientTransaction", "schedule", {
            it.returnType == Void.TYPE && it.parameterCount == 0
        }, before = { p ->
            val callbacks = ArrayList<String>()
            val items = call(p.thisObject, "getTransactionItems") as? List<*> ?: return@hook
            for (item in items.filterNotNull()) {
                val results = when (item.javaClass.name) {
                    "android.app.servertransaction.ActivityResultItem" -> field(item, "mResultInfoList")
                    "android.app.servertransaction.LaunchActivityItem" -> field(item, "mPendingResults")
                    else -> continue
                } as? List<*> ?: continue
                val target = token(call(item, "getActivityToken") ?: continue)
                for (result in results.filterNotNull()) callbacks += callbackKey(target, field(result, "mResultWho") as? String, field(result, "mRequestCode") as Int)
            }
            p.setObjectExtra("stopcard.results", callbacks)
        }, after = { p ->
            if (!p.hasThrowable()) (p.getObjectExtra("stopcard.results") as? List<*>)?.filterIsInstance<String>()?.forEach(sink::resultDelivered)
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
            }, after = { p -> serviceCalls.set(p.getObjectExtra("stopcard.previous") as? ServiceCall) })
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
            val decision = sink.entry(info.uid / 100000, target, context.uid, context.source)
            if (!decision.allow) {
                // Both callers check res == null before creating AppBindRecord/ConnectionRecord:
                // startServiceLocked returns null; bindServiceLocked returns 0 (not bound).
                p.result = null
            } else if (context.kind == EntryKind.BIND && context.connection != null) {
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
    }

    private fun notificationHooks() {
        hook("notifications", "com.android.server.notification.NotificationManagerService", "enqueueNotificationInternal", {
            it.returnType == Boolean::class.javaPrimitiveType && names(it).take(8) == listOf("java.lang.String", "java.lang.String", "int", "int", "java.lang.String", "int", "android.app.Notification", "int")
        }, before = { p ->
            val pkg = p.args[0] as? String ?: return@hook
            val user = p.args[7] as Int
            val decision = sink.entry(user, CallTarget(pkg, kind = EntryKind.NOTIFICATION), p.args[2] as Int, p.args[1] as? String)
            if (!decision.allow) {
                // The boolean overload owns no wake lock. Its wrapper cancels PostNotificationTracker
                // in finally on false. A proxy's opPkg never replaces the notification owner pkg.
                p.result = false
            }
        })
    }

    private fun broadcastHooks() {
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
            val decision = sink.entry(uid / 100000, CallTarget(pkg, component, EntryKind.BROADCAST), field(record, "callingUid") as Int, field(record, "callerPackage") as? String)
            if (!decision.allow) p.result = "Stop Card: no runtime permission"
            // BroadcastQueueModernImpl consumes this reason in both cold and warm dispatch,
            // sets DELIVERY_SKIPPED, and advances completion/timeout state itself.
        })
    }

    private fun providerHooks() {
        hook("provider", "com.android.server.am.ContentProviderHelper", "getContentProviderImpl", {
            it.returnType.name == "android.app.ContentProviderHolder" && names(it) == listOf("android.app.IApplicationThread", "java.lang.String", "android.os.IBinder", "int", "java.lang.String", "java.lang.String", "boolean", "int")
        }, before = { p ->
            val owner = providers[p.args[1] as String] ?: return@hook
            val user = p.args[7] as Int
            val target = CallTarget(owner.first, p.args[1] as String, EntryKind.PROVIDER)
            if (!sink.entry(user, target, p.args[3] as Int, p.args[4] as? String).allow) p.result = null
            // Null is the native unavailable-provider result. No stable/unstable reference or
            // ContentProviderConnection has been created at this entry point.
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
            if (!sink.entry(uid / 100000, CallTarget(pkg, kind = EntryKind.ALARM), uid).allow) p.result = null
            // deliverAlarmsLocked still decrements the scheduled alarm count. DeliveryTracker
            // has not acquired its wake lock or posted listener/PI timeout accounting yet.
        })
    }

    private fun pendingIntentHooks() {
        hook("pending_intent", "com.android.server.am.PendingIntentRecord", "sendInner", {
            it.returnType == Int::class.javaPrimitiveType && names(it).take(4) == listOf("android.app.IApplicationThread", "int", "android.content.Intent", "java.lang.String")
        }, before = { p ->
            p.setObjectExtra("stopcard.previous", launchSource.get())
            val key = field(p.thisObject, "key") ?: return@hook
            val creator = field(key, "packageName") as String
            val uid = field(p.thisObject, "uid") as Int
            val request = field(key, "requestIntent") as? Intent
            val targetPkg = request?.component?.packageName ?: request?.`package` ?: creator
            val sender = Binder.getCallingUid()
            if (trustedSystemUi(sender, Binder.getCallingPid()) && field(key, "type") == 2) {
                launchSource.set(OpenSource.NOTIFICATION)
                // Actual activity resolution in ActivityStarter determines the target and grants
                // permission before process start. Mutable fill-in extras are never credentials.
            } else if (!sink.entry(uid / 100000, CallTarget(targetPkg, request?.component?.flattenToString(), EntryKind.PENDING_INTENT), sender, creator).allow) {
                // START_CANCELED reaches PendingIntent's normal canceled callback path.
                p.result = -96
            }
        }, after = { p -> launchSource.set(p.getObjectExtra("stopcard.previous") as? OpenSource) })
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
            if (pid > 0 && info.packageName == "com.android.systemui" && field(p.thisObject, "processName") == "com.android.systemui") {
                systemUiProcesses[pid] = info.uid
            }
        })
        hook("process", "com.android.server.am.ProcessList", "startProcessLocked", {
            it.returnType.name == "com.android.server.am.ProcessRecord" && names(it).take(2) == listOf("java.lang.String", "android.content.pm.ApplicationInfo")
        }, before = { p ->
            val info = p.args[1] as ApplicationInfo
            if (!sink.entry(info.uid / 100000, CallTarget(info.packageName, kind = EntryKind.PROCESS)).allow) p.result = null
            // This overload returns null for denied/bad processes before creating a ProcessRecord.
            // Resolved activity/service/broadcast callers retain their own native failure paths.
        })
    }

    private fun trustedSystemUi(uid: Int, pid: Int): Boolean =
        uid >= 0 && uid == systemUiUid && pid > 0 && systemUiProcesses[pid] == uid

    private fun stopHooks() {
        hook("stop_fence", "com.android.server.am.ActivityManagerService", "forceStopPackage", {
            it.returnType == Void.TYPE && names(it) == listOf("java.lang.String", "int")
        }, before = { p ->
            val pkg = stops.get() ?: return@hook
            if (p.args[0] == pkg && !sink.stopGuard(pkg)) p.throwable = StopInvalidated()
        })
        hook("stop_locked_fence", "com.android.server.am.ActivityManagerService", "forceStopPackageInternalLocked", {
            it.returnType == Boolean::class.javaPrimitiveType && names(it).firstOrNull() == "java.lang.String"
        }, before = { p ->
            val pkg = stops.get() ?: return@hook
            if (p.args[0] == pkg && !sink.stopGuard(pkg)) p.throwable = StopInvalidated()
        })
        hook("stop_package_fence", "com.android.server.pm.PackageManagerService", "setPackageStoppedState", {
            it.returnType == Void.TYPE && names(it) == listOf("com.android.server.pm.Computer", "java.lang.String", "boolean", "int")
        }, before = { p ->
            val pkg = stops.get() ?: return@hook
            if (p.args[1] == pkg && p.args[2] == true && !sink.stopGuard(pkg)) p.throwable = StopInvalidated()
        })
        for ((capability, type, method) in listOf(
            Triple("stop_window_fence", "com.android.server.wm.RootWindowContainer", "finishDisabledPackageActivities"),
            Triple("stop_process_fence", "com.android.server.am.ProcessList", "killPackageProcessesLSP"),
            Triple("stop_service_fence", "com.android.server.am.ActiveServices", "bringDownDisabledPackageServicesLocked"),
        )) hook(capability, type, method, {
            it.returnType == Boolean::class.javaPrimitiveType && names(it).firstOrNull() == "java.lang.String"
        }, before = { p ->
            val pkg = stops.get() ?: return@hook
            if (p.args[0] == pkg && !sink.stopGuard(pkg)) p.throwable = StopInvalidated()
        })
        hook("stop_victim_fence", "com.android.server.am.ProcessList", "removeProcessLocked", {
            it.returnType == Boolean::class.javaPrimitiveType && names(it).firstOrNull() == "com.android.server.am.ProcessRecord"
        }, before = { p ->
            val pkg = stops.get() ?: return@hook
            val info = field(p.args[0], "info") as ApplicationInfo
            if (info.uid != stopUid.get() || !sink.stopGuard(pkg)) p.throwable = StopInvalidated()
        })
    }

    private fun keepAliveHooks() {
        hook("extra_keep_alive", "com.android.server.am.ProcessStateRecord", "setCurRawAdj", {
            (it.returnType == Void.TYPE && names(it) == listOf("int")) ||
                (it.returnType == Boolean::class.javaPrimitiveType && names(it) == listOf("int", "boolean"))
        }, before = { p ->
            val adj = p.args[0] as Int
            if (adj < 900 || adj > 999) return@hook
            val app = field(p.thisObject, "mApp") ?: return@hook
            val uid = field(app, "uid") as Int
            // Stay in Android's cached band. Never promote to foreground/persistent priority.
            if (sink.keepAlive(uid)) p.args[0] = 900
        })
    }

    /** Worker only. No module monitor is held across any Android call. */
    fun forceStop(ticket: StopTicket, wasStopped: Boolean) {
        val service = ams ?: error("ActivityManagerService not captured")
        if (report()["stop_locked_fence"]?.status != "installed") error("No final stop fence")
        stops.set(ticket.app.packageName)
        stopUid.set(ticket.app.uid)
        try { StopTransaction.run(
            valid = { sink.stopGuard(ticket.app.packageName) },
            wasStopped = wasStopped,
            stop = { call(service, "forceStopPackage", ticket.app.packageName, ticket.app.userId) },
            restore = {
                // Public AMS sets this bit before native teardown. Restore our change if an open
                // invalidates the stop or native teardown fails. This is worker work, outside hooks.
                val pm = packageManager ?: error("Package state recovery unavailable")
                call(pm, "setPackageStoppedState", ticket.app.packageName, false, ticket.app.userId)
            },
        ) }
        finally {
            stops.remove()
            stopUid.remove()
        }
    }

    fun discover() {
        fun singleton(type: String, name: String): Any? = Class.forName(type, false, loader).getDeclaredMethod(name).apply { isAccessible = true }.invoke(null)
        if (ams == null) ams = singleton("android.app.ActivityManager", "getService")
        if (atm == null) atm = singleton("android.app.ActivityTaskManager", "getService")
        if (wm == null) wm = singleton("android.view.WindowManagerGlobal", "getWindowManagerService")
        if (recent == null) recent = atm?.let { field(it, "mRecentTasks") }
        if (nms == null) nms = singleton("android.app.NotificationManager", "getService")?.let { field(it, "this\$0") }
        if (packageManager == null) packageManager = singleton("android.app.AppGlobals", "getPackageManager")
    }

    fun takeInteraction(pkg: String): Boolean {
        val input = inputs.remove(pkg) ?: return false
        return SystemClock.elapsedRealtime() - input.second in 0..INPUT_MS
    }

    fun clearNotifications(pkg: String, user: Int) {
        val service = nms ?: error("Notification service not captured")
        // Only clear ordinary notifications. Android force-stop coordinates FGS teardown itself.
        call(service, "cancelAllNotificationsInt", 1000, android.os.Process.myPid(), pkg, null, 0,
            Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR,
            user, 20 /* NotificationListenerService.REASON_CLEAR_DATA */)
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
            var count = 0
            call(root, "forAllActivities", Consumer<Any> { record ->
                require(++count <= 4096) { "Activity snapshot exceeds limit" }
                val a = activityFact(record)
                if (a.uid / 100000 == 0 && a.visible) visible += a.packageName
            }, true)
            knownTasks = tasks.filter { it.key.userId == 0 }.associateBy { it.key.taskId }
            WorldSnapshot(tasks, visible, true, boot)
        }
    }

    /** Register the platform pointer listener once WMS exists. This receives dispatched input. */
    fun registerInput() {
        if (pointerListener != null) return
        val service = wm ?: return
        try {
            val type = Class.forName("android.view.WindowManagerPolicyConstants\$PointerEventListener", false, loader)
            val listener = Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "onPointerEvent" -> guarded("user_input") {
                        val event = args?.firstOrNull() as? MotionEvent ?: return@guarded
                        if (event.actionMasked != MotionEvent.ACTION_UP || !event.isFromSource(0x1002)) return@guarded
                        val lock = field(service, "mGlobalLock") ?: return@guarded
                        synchronized(lock) {
                            val root = field(service, "mRoot") ?: return@synchronized
                            val display = call(root, "getDisplayContent", call(event, "getDisplayId") as Int) ?: return@synchronized
                            val window = call(display, "getTouchableWinAtPointLocked", event.rawX, event.rawY) ?: return@synchronized
                            val record = field(window, "mActivityRecord") ?: return@synchronized
                            val a = activityFact(record)
                            if (a.uid / 100000 == 0 && a.visible) inputs[a.packageName] = a.token to SystemClock.elapsedRealtime()
                        }
                    }
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                    "toString" -> return@newProxyInstance "StopCardPointerListener"
                }
                null
            }
            call(service, "registerPointerEventListener", listener, 0)
            pointerListener = listener
            capabilities["user_input"] = HookCapability("installed", listOf("WindowManagerService.registerPointerEventListener"), "touchscreen display 0; other input sources remain observed")
        } catch (e: Throwable) { unavailable("user_input", e) }
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

    private fun callbackKey(token: String, who: String?, code: Int) = "$token:${who.orEmpty()}:$code"

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
                        capability.startsWith("close_context_") -> param.setObjectExtra("stopcard.closeCaller", closeCaller.get())
                        capability.endsWith("_context") -> param.setObjectExtra("stopcard.previous", serviceCalls.get())
                        capability == "pending_intent" || capability == "recents_open" -> param.setObjectExtra("stopcard.previous", launchSource.get())
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
                            capability.startsWith("close_context_") -> closeCaller.set(param.getObjectExtra("stopcard.closeCaller") as? Caller)
                            capability.endsWith("_context") -> serviceCalls.set(param.getObjectExtra("stopcard.previous") as? ServiceCall)
                            capability == "pending_intent" || capability == "recents_open" -> launchSource.set(param.getObjectExtra("stopcard.previous") as? OpenSource)
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
        catch (e: StopInvalidated) { throw e }
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

    class StopInvalidated : RuntimeException("Stop Card decision changed")
    companion object { private const val INPUT_MS = 2_000L }
}
