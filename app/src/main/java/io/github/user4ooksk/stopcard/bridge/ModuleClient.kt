package io.github.user4ooksk.stopcard.bridge

import android.content.Context
import android.os.SystemClock
import io.github.user4ooksk.stopcard.BuildConfig
import io.github.user4ooksk.stopcard.module.ModuleProtocol
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

    private val _appliedGeneration = MutableStateFlow(-1L)
    val appliedGeneration: StateFlow<Long> = _appliedGeneration.asStateFlow()

    private val commandMutex = Mutex()
    private val configMutex = Mutex()
    private var commandId = System.currentTimeMillis()
    private val primaryUser: Boolean get() = context.applicationInfo.uid / 100000 == 0

    data class Synchronization(val pending: Boolean = false, val generation: Long = 0, val error: String? = null)
    private val _synchronization = MutableStateFlow(Synchronization())
    val synchronization: StateFlow<Synchronization> = _synchronization.asStateFlow()

    fun expectConfiguration() { _synchronization.value = Synchronization(pending = true) }

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
                _appliedGeneration.value = -1
            } else if (issue != null) {
                _state.value = State.Failed("模块版本或系统进程身份不匹配，请重启以加载当前版本", module)
                _appliedGeneration.value = -1
            } else {
                _appliedGeneration.value = if (module.configLoaded) module.configGeneration else -1
                if (module.configLoaded && _synchronization.value.generation == module.configGeneration) {
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
                val next = ModuleProtocol.nextConfig(previous, diskGeneration, confirmed, config)
                _synchronization.value = Synchronization(true, next.configGeneration)
                check(root.write(ModuleProtocol.CONFIG, next.toJson())) { "写入配置失败，将自动重试" }
                next
            }.getOrElse {
                _synchronization.value = Synchronization(true, error = it.message ?: "配置同步失败")
                null
            }
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

    suspend fun stopNow(packages: Collection<String>): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            if (packages.isEmpty()) return@withContext true
            if (confirmedGeneration() <= 0) return@withContext false
            val command = ModuleProtocol.Command(
                id = ++commandId,
                configGeneration = confirmedGeneration(),
                expiresAt = System.currentTimeMillis() + COMMAND_TTL_MS,
                stop = packages.filter { it.isNotBlank() }.distinct(),
            )
            root.write(ModuleProtocol.commandPath(command.id), command.toJson())
        }
    }

    suspend fun stopAllCards(): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            if (confirmedGeneration() <= 0) return@withContext false
            val command = ModuleProtocol.Command(
                id = ++commandId,
                configGeneration = confirmedGeneration(),
                expiresAt = System.currentTimeMillis() + COMMAND_TTL_MS,
                stopAll = true,
            )
            root.write(ModuleProtocol.commandPath(command.id), command.toJson())
        }
    }

    private fun confirmedGeneration(): Long =
        (state.value as? State.Connected)?.module?.configGeneration ?: 0L

    /** The module serializes log deletion with its own writer and acknowledges completion. */
    suspend fun clearModuleLog(): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            if (confirmedGeneration() <= 0) return@withContext false
            val command = ModuleProtocol.Command(
                id = ++commandId, configGeneration = confirmedGeneration(),
                expiresAt = System.currentTimeMillis() + COMMAND_TTL_MS, clearLog = true,
            )
            root.write(ModuleProtocol.commandPath(command.id), command.toJson())
        }
    }

    fun packageName(): String = context.packageName

    private companion object {
        const val REFRESH_MS = 3_000L
        const val IDLE_MS = 20_000L
        const val COMMAND_TTL_MS = 15_000L
    }
}
