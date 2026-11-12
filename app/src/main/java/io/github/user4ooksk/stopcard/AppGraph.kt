package io.github.user4ooksk.stopcard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.core.ConfigSync
import io.github.user4ooksk.stopcard.data.AppIcons
import io.github.user4ooksk.stopcard.data.EventsRepository
import io.github.user4ooksk.stopcard.data.InstalledApps
import io.github.user4ooksk.stopcard.data.RulesRepository
import io.github.user4ooksk.stopcard.data.SettingsRepository
import io.github.user4ooksk.stopcard.data.db.StopDatabase
import io.github.user4ooksk.stopcard.module.SystemFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Process-wide singletons. Created once in [StopApp]. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context)
    private val database = StopDatabase.create(context)
    private val identities = SystemFacts(context)
    val rules = RulesRepository(database.rules(), identities::currentIdentity)
    val events = EventsRepository(context, database.events())
    val apps = InstalledApps(context)
    val icons = AppIcons(context)
    val module = ModuleClient(context, scope)

    @Volatile
    private var foreground = false
    private var lastPrune = 0L

    val sync = ConfigSync(
        scope = scope,
        settings = settings,
        rules = rules,
        apps = apps,
        module = module,
        selfPackage = context.packageName,
    )

    fun start() {
        module.start()
        sync.start()
        scope.launch { refreshAppsAndCleanRules() }
        scope.launch { events.prune() }
        scope.launch { backgroundSync() }
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (foreground) scope.launch { refreshAppsAndCleanRules() }
            }
        }
        runCatching { context.registerReceiver(packageReceiver, packageFilter) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    foreground = true
                    module.setActive(true)
                    scope.launch { refreshAppsAndCleanRules() }
                }
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    module.setActive(false)
                }
                else -> Unit
            }
        })
    }

    private suspend fun refreshAppsAndCleanRules() {
        val installed = apps.refresh().keys
        if (installed.isEmpty()) return
        val stale = rules.all().keys - installed
        if (stale.isNotEmpty()) rules.delete(stale)
    }

    /** Pulls the module's JSONL log at a low rate; the module keeps events if the app is away. */
    private suspend fun backgroundSync() {
        while (true) {
            if (foreground && module.state.value is ModuleClient.State.Connected) syncEvents()
            delay(EVENT_SYNC_MS)
        }
    }

    suspend fun syncEvents() {
        val after = events.latestId()
        val incoming = module.readEvents(after)
        if (incoming.isNotEmpty()) events.add(incoming)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPrune > 60_000) { events.prune(); lastPrune = now }
    }

    suspend fun stopNow(packages: Collection<String>): Boolean {
        val submitted = module.stopNow(packages)
        if (submitted) {
            delay(600)
            module.refresh()
            syncEvents()
        }
        return submitted
    }

    suspend fun stopAllCards(): Boolean {
        val submitted = module.stopAllCards()
        if (submitted) {
            delay(600)
            module.refresh()
            syncEvents()
        }
        return submitted
    }

    /** Pauses or resumes both the module flag and the app-side switch. */
    suspend fun setProtectionEnabled(enabled: Boolean): Boolean {
        if (enabled && (module.state.value as? ModuleClient.State.Connected)?.module?.emergencyDisabled == true) {
            module.refresh()
            return false
        }
        // Withdraw permission before slower preference/config updates when pausing.
        if (!enabled && !module.setPaused(true)) return false
        settings.update { it.copy(protectionEnabled = enabled) }
        val ok = !enabled || module.setPaused(false)
        module.refresh()
        return ok
    }

    private companion object {
        const val EVENT_SYNC_MS = 10_000L
    }
}

val Context.graph: AppGraph get() = (applicationContext as StopApp).graph
