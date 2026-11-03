package io.github.user4ooksk.stopcard.ui

import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.model.AppSettings

/** Everything the UI needs to know about whether card control is working. */
sealed interface Protection {
    /** Root is available and the module state is fresh. */
    data object Active : Protection

    /** The module is running but only records decisions. */
    data object Observe : Protection

    data object Starting : Protection
    data object NotConfigured : Protection
    data object Paused : Protection
    data class Degraded(val message: String) : Protection
    data object RootMissing : Protection
    data object ModuleMissing : Protection
    data class Failed(val message: String) : Protection

    val isActive: Boolean get() = this is Active || this is Observe
    val isEnforcing: Boolean get() = this is Active

    companion object {
        fun of(settings: AppSettings, module: ModuleClient.State, sync: ModuleClient.Synchronization = ModuleClient.Synchronization()): Protection = when (module) {
            ModuleClient.State.NoRoot -> RootMissing
            ModuleClient.State.Waiting -> Starting
            is ModuleClient.State.Failed -> Failed(module.message)
            is ModuleClient.State.Connected -> when {
                !module.module.configLoaded -> NotConfigured
                module.module.paused -> Paused
                sync.error != null -> Degraded(sync.error)
                sync.pending -> Starting
                !settings.protectionEnabled -> Starting
                !module.module.ready -> Degraded(module.module.lastError ?: "任务、窗口或必要入口尚未就绪，正在观察")
                module.module.recovery != "watching" -> Degraded("独立恢复监测未就绪，正在观察。请到设置安装或检查恢复辅助。")
                module.module.observeOnly -> Observe
                module.module.notificationControl != "installed" || module.module.wakeControl != "installed" ->
                    Degraded("部分通知或后台入口未挂接。已挂接入口继续工作，缺失项见设置中的能力状态。")
                else -> Active
            }
        }
    }
}
