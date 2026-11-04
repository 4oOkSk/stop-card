package io.github.user4ooksk.stopcard.bridge

import android.content.Context
import android.os.SystemClock
import io.github.user4ooksk.stopcard.BuildConfig
import io.github.user4ooksk.stopcard.module.ModuleProtocol
import io.github.user4ooksk.stopcard.module.SystemFacts
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-side view of the LSPosed module. The module writes its state and event log as system_server;
 * the app reads them through the root bridge. No per-event control traffic crosses this boundary.
 */
class ModuleClient(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        /** Root is not available, so the app cannot read or write the module files. */
        data object NoRoot : State

        /** Root is available, but no fresh state file exists yet. */
        data object Waiting : State

        data class Connected(val module: ModuleProtocol.ModuleState) : State

        data class Failed(val message: String, val last: ModuleProtocol.ModuleState? = null) : State
    }

    private val root = RootShell()

    @Volatile
    private var active = true

    @Volatile
    private var rootCached = false

    private val _state = MutableStateFlow<State>(State.Waiting)
    val state: StateFlow<State> = _state.asStateFlow()

    private val commandMutex = Mutex()
    private val configMutex = Mutex()
    private val preferences = context.getSharedPreferences("module_sync", Context.MODE_PRIVATE)
    private var commandId = System.currentTimeMillis()
    private val primaryUser: Boolean get() = context.applicationInfo.uid / 100000 == 0

    data class Synchronization(val pending: Boolean = false, val generation: Long = 0, val error: String? = null)
    private val _synchronization = MutableStateFlow(Synchronization())
    val synchronization: StateFlow<Synchronization> = _synchronization.asStateFlow()

    fun expectConfiguration(generation: Long = 0) { _synchronization.value = Synchronization(pending = true, generation = generation) }

    fun start() {
        scope.launch {
            while (true) {
                if (active) {
                    refresh()
                    delay(REFRESH_MS)
                } else {
                    delay(IDLE_MS)
                }
            }
        }
    }

    /** Called from the process lifecycle; no root shell is spawned while the UI is in the background. */
    fun setActive(value: Boolean) {
        active = value
    }

    fun rootAvailable(): Boolean = rootCached

    suspend fun diagnostics(): String = withContext(Dispatchers.IO) {
        root.diagnostics() + "\n" + root.read(ModuleProtocol.STATE).orEmpty()
    }

    suspend fun refresh(forceRoot: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (!primaryUser) {
                _state.value = State.Failed("当前版本仅在主用户空间启用；此用户空间不参与管控")
                return@withContext
            }
            rootCached = root.available(forceRoot)
            if (!rootCached) {
                _state.value = State.NoRoot
                return@withContext
            }
            val text = root.read(ModuleProtocol.STATE)
            if (text.isNullOrBlank()) {
                _state.value = State.Failed("模块状态文件不存在；确认 LSPosed 已启用并重启")
                return@withContext
            }
            val module = ModuleProtocol.ModuleState.fromJson(text)
            if (module == null) {
                _state.value = State.Failed("模块状态文件无法解析")
                return@withContext
            }
            val boot = root.read("/proc/sys/kernel/random/boot_id")?.trim()
            val issue = ModuleProtocol.stateIssue(module, boot, SystemClock.elapsedRealtime(), BuildConfig.VERSION_CODE)
            if (issue == "different_boot" || issue == "stale_heartbeat") {
                _state.value = State.Failed("模块状态已过期，正在等待当前开机的心跳", module)
            } else if (issue != null) {
                _state.value = State.Failed("模块版本或系统进程身份不匹配，请重启以加载当前版本", module)
            } else {
                if (module.appliedGeneration > 0 && _synchronization.value.generation == module.appliedGeneration) {
                    _synchronization.value = Synchronization(generation = module.configGeneration)
                }
                _state.value = State.Connected(module)
            }
        }
    }

    suspend fun apply(config: ModuleProtocol.Config): ModuleProtocol.Config? = configMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                check(primaryUser) { "其他用户空间未启用，未写入系统配置" }
                check(root.available()) { "需要 Root 才能同步配置" }
                val exists = root.exists(ModuleProtocol.CONFIG)
                val text = root.read(ModuleProtocol.CONFIG)
                check(!exists || text != null) { "读取当前配置失败" }
                val previous = text?.let(ModuleProtocol.Config::fromJson)
                val diskGeneration = text?.let { JSONObject(it).optLong("configGeneration", 0) } ?: 0L
                val confirmed = (state.value as? State.Connected)?.module?.configGeneration ?: 0L
                val expected = if (config.configGeneration > 0) config.configGeneration else preferences.getLong("generation", 0)
                val next = ModuleProtocol.nextConfig(previous, diskGeneration, confirmed, config.copy(configGeneration = expected))
                _synchronization.value = Synchronization(true, next.configGeneration)
                val write = root.writeConfiguration(next.toJson(), text)
                check(write.ok) {
                    if (write.code == 74) "系统配置已变化，未覆盖，请重新载入系统配置"
                    else "配置写入失败（退出码 ${write.code}）：${write.output.trim().take(240).ifBlank { "未取得文件锁或写入未完成，请重试" }}"
                }
                check(preferences.edit().putLong("generation", next.configGeneration).commit()) { "记录配置代次失败" }
                next
            }.getOrElse {
                _synchronization.value = Synchronization(true, error = it.message ?: "配置同步失败")
                null
            }
        }
    }

    /** Explicit conflict recovery. Reading a newer generation never overwrites its content. */
    suspend fun loadConfiguration(): ModuleProtocol.Config? = configMutex.withLock {
        withContext(Dispatchers.IO) {
            check(primaryUser && root.available()) { "需要主用户 Root 授权" }
            val text = root.read(ModuleProtocol.CONFIG) ?: return@withContext null
            val config = ModuleProtocol.Config.fromJson(text) ?: error("系统配置无效")
            check(config.selfPackage == context.packageName)
            check(preferences.edit().putLong("generation", config.configGeneration).commit())
            config
        }
    }

    suspend fun setPaused(paused: Boolean): Boolean = withContext(Dispatchers.IO) {
        primaryUser && if (paused) root.pause() else root.resume()
    }

    suspend fun readEvents(afterId: Long): List<ModuleProtocol.Event> = withContext(Dispatchers.IO) {
        if (!primaryUser) return@withContext emptyList()
        val text = root.readTail(ModuleProtocol.EVENTS) ?: return@withContext emptyList()
        ModuleProtocol.Event.listFromJsonLines(text).filter { it.id > afterId }
    }

    suspend fun stopNow(packages: Collection<String>): Boolean =
        packages.isEmpty() || submitCommand(packages)

    suspend fun stopAllCards(): Boolean = submitCommand(stopAll = true)

    /** The module serializes log deletion with its own writer and acknowledges completion. */
    suspend fun clearModuleLog(): Boolean = submitCommand(clearLog = true)

    private suspend fun submitCommand(packages: Collection<String> = emptyList(), stopAll: Boolean = false, clearLog: Boolean = false): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val module = (state.value as? State.Connected)?.module ?: return@withContext false
            if (module.appliedGeneration <= 0) return@withContext false
            val targets = if (stopAll) module.apps.filter { it.cards > 0 }.map { it.packageName } else packages.distinct()
            val identity = SystemFacts(context)
            val command = ModuleProtocol.Command(
                id = ++commandId, configGeneration = module.appliedGeneration,
                expiresAt = System.currentTimeMillis() + COMMAND_TTL_MS,
                stop = if (stopAll) emptyList() else targets.toList(), stopAll = stopAll, clearLog = clearLog,
                identities = targets.mapNotNullTo(HashSet(), identity::currentIdentity),
            )
            root.write(ModuleProtocol.commandPath(command.id), command.toJson())
        }
    }

    private companion object {
        const val REFRESH_MS = 3_000L
        const val IDLE_MS = 20_000L
        const val COMMAND_TTL_MS = 15_000L
    }
}
