package io.github.user4ooksk.stopcard

import android.content.Context
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
import io.github.user4ooksk.stopcard.model.RuleMode
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
        scope.launch {
            apps.refresh()
            val installed = apps.get().keys
            val stale = rules.all().keys - installed
            if (stale.isNotEmpty()) rules.delete(stale)
        }
        scope.launch { events.prune() }
        scope.launch { backgroundSync() }
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    foreground = true
                    module.setActive(true)
                    scope.launch { apps.refresh() }
                }
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    module.setActive(false)
                }
                else -> Unit
            }
        })
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

    suspend fun decide(packageNames: Collection<String>, mode: RuleMode) {
        rules.decide(packageNames, mode)
    }

    /** Pauses or resumes both the module flag and the app-side switch. */
    suspend fun setProtectionEnabled(enabled: Boolean): Boolean {
        // Withdraw permission before slower preference/config updates when pausing.
        if (!enabled && !module.setPaused(true)) return false
        settings.update { it.copy(protectionEnabled = enabled) }
        return !enabled || module.setPaused(false)
    }

    private companion object {
        const val EVENT_SYNC_MS = 10_000L
    }
}

val Context.graph: AppGraph get() = (applicationContext as StopApp).graph
