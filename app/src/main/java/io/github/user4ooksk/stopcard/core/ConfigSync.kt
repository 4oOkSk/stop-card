package io.github.user4ooksk.stopcard.core

import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.data.InstalledApps
import io.github.user4ooksk.stopcard.data.RulesRepository
import io.github.user4ooksk.stopcard.data.SettingsRepository
import io.github.user4ooksk.stopcard.data.SystemRoles
import io.github.user4ooksk.stopcard.module.ModuleProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pushes one immutable configuration to the module whenever the user-visible rule changes.
 * A generation is bumped only when the document actually differs, so a role refresh cannot reset
 * the module's baseline and accidentally suppress a stop.
 */
class ConfigSync(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val rules: RulesRepository,
    private val apps: InstalledApps,
    private val roles: SystemRoles,
    private val module: ModuleClient,
    private val rolesChanged: Flow<Int>,
    private val selfPackage: String,
) {
    private val writeMutex = Mutex()

    /** The latest document the UI wants the module to load, including a failed write. */
    @Volatile
    private var desired: ModuleProtocol.Config? = null

    /** The last document whose write succeeded. This is not an acknowledgment. */
    @Volatile
    private var written: ModuleProtocol.Config? = null

    fun start() {
        scope.launch {
            combine(
                settings.settings,
                rules.rules,
                apps.apps.filterNotNull(),
                rolesChanged,
            ) { s, r, installed, _ -> Triple(s, r, installed) }
                .collect { (s, r, installed) ->
                    if (!s.onboarded) return@collect
                    val essential = withContext(Dispatchers.IO) { roles.protectedPackages() }
                    val validRules = r.values.filter { it.exempt && installed[it.packageName]?.identity == it.identity }
                    val exceptions = validRules.mapTo(HashSet()) { it.packageName }
                    val base = ModuleProtocol.Config(
                        enabled = s.protectionEnabled,
                        observeOnly = s.observeOnly,
                        reviewMs = s.reviewMs,
                        recentVisibleMs = s.recentVisibleSeconds * 1_000L,
                        clearNotifications = s.clearNotifications,
                        extraKeepAlive = s.extraKeepAlive,
                        userId = apps.userId,
                        configGeneration = 0,
                        exceptions = exceptions,
                        essential = essential,
                        selfPackage = selfPackage,
                        exceptionIdentities = validRules.mapNotNullTo(HashSet()) { it.identity },
                    )
                    if (written != null && written!!.copy(configGeneration = 0) == base) return@collect
                    // The pause flag is independent. ConfigSync must never remove it; only the
                    // explicit resume action in AppGraph.setProtectionEnabled can do that.
                    desired = base
                    module.expectConfiguration()
                    push(base)
                }
        }
        // Retry the latest desired document until the module reports that it loaded it. One
        // mutex keeps the collect path and this retry path from writing out of order.
        scope.launch {
            while (true) {
                delay(10_000)
                val connected = module.state.value as? ModuleClient.State.Connected
                val target = desired ?: continue
                if (connected?.module?.configGeneration == target.configGeneration) continue
                push(target)
            }
        }
    }

    private suspend fun push(config: ModuleProtocol.Config) {
        writeMutex.withLock {
            val current = desired ?: return@withLock
            if (current != config) return@withLock
            module.apply(current)?.let { applied ->
                written = applied
                if (desired == current) desired = applied
            }
        }
    }

}
