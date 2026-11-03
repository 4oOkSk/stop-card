package io.github.user4ooksk.stopcard.core

import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.data.InstalledApps
import io.github.user4ooksk.stopcard.data.RulesRepository
import io.github.user4ooksk.stopcard.data.SettingsRepository
import io.github.user4ooksk.stopcard.module.ModuleProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pushes one immutable configuration to the module whenever the user-visible rule changes.
 * System roles belong to the module. The UI only sends settings and identity-bound exceptions.
 */
class ConfigSync(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val rules: RulesRepository,
    private val apps: InstalledApps,
    private val module: ModuleClient,
    private val selfPackage: String,
) {
    private val writeMutex = Mutex()

    /** The latest document the UI wants the module to load, including a failed write. */
    @Volatile
    private var desired: ModuleProtocol.Config? = null

    /** The last document whose write succeeded. This is not an acknowledgment. */
    @Volatile
    private var written: ModuleProtocol.Config? = null
    @Volatile private var restoring = false
    @Volatile private var reloadEpoch = 0L

    fun start() {
        scope.launch {
            combine(
                settings.settings,
                rules.rules,
                apps.apps.filterNotNull(),
            ) { s, r, installed -> Triple(s, r, installed) to reloadEpoch }
                .collect { (input, epoch) -> writeMutex.withLock {
                    val (s, r, installed) = input
                    if (!s.onboarded || restoring || epoch != reloadEpoch || settings.current() != s || rules.all() != r) return@withLock
                    val validRules = r.values.filter { it.exempt && installed[it.packageName]?.identity == it.identity }
                    val exceptions = validRules.mapTo(HashSet()) { it.packageName }
                    val base = ModuleProtocol.Config(
                        enabled = s.protectionEnabled,
                        observeOnly = s.observeOnly,
                        reviewMs = s.reviewMs,
                        recentVisibleMs = s.recentVisibleSeconds * 1_000L,
                        extraKeepAlive = s.extraKeepAlive,
                        userId = apps.userId,
                        configGeneration = 0,
                        exceptions = exceptions,
                        selfPackage = selfPackage,
                        exceptionIdentities = validRules.mapNotNullTo(HashSet()) { it.identity },
                    )
                    if (written != null && written!!.copy(configGeneration = 0) == base) return@withLock
                    // The pause flag is independent. ConfigSync must never remove it; only the
                    // explicit resume action in AppGraph.setProtectionEnabled can do that.
                    desired = base
                    module.expectConfiguration()
                    applyDesired(base)
                } }
        }
        // Retry the latest desired document until the module reports that it loaded it. One
        // mutex keeps the collect path and this retry path from writing out of order.
        scope.launch {
            while (true) {
                delay(10_000)
                val connected = module.state.value as? ModuleClient.State.Connected
                val target = desired ?: continue
                if (connected?.module?.appliedGeneration == target.configGeneration) continue
                push(target)
            }
        }
    }

    private suspend fun push(config: ModuleProtocol.Config) {
        writeMutex.withLock {
            applyDesired(config)
        }
    }

    private suspend fun applyDesired(config: ModuleProtocol.Config) {
        val current = desired ?: return
        if (current != config) return
        module.apply(current)?.let { applied -> written = applied; if (desired == current) desired = applied }
    }

    suspend fun reload() {
        writeMutex.withLock {
            restoring = true
            reloadEpoch++
            try {
                val config = module.loadConfiguration() ?: error("系统配置不存在")
                desired = config; written = config
                rules.restoreExceptions(config.exceptionIdentities)
                settings.update { it.copy(onboarded = true, protectionEnabled = config.enabled, observeOnly = config.observeOnly,
                    reviewMs = config.reviewMs, recentVisibleSeconds = (config.recentVisibleMs / 1000).toInt(),
                    extraKeepAlive = config.extraKeepAlive) }
                module.expectConfiguration(config.configGeneration)
                module.refresh()
            } finally { reloadEpoch++; restoring = false }
        }
    }

}
