package io.github.user4ooksk.stopcard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.user4ooksk.stopcard.AppGraph
import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.bridge.RecoveryInstaller
import io.github.user4ooksk.stopcard.model.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val settings: StateFlow<AppSettings?> = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val module: StateFlow<ModuleClient.State> = graph.module.state
    val synchronization = graph.module.synchronization
    val maintenance = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { graph.settings.update(transform) }
    }

    fun setProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!graph.setProtectionEnabled(enabled)) maintenance.value = "暂停或恢复请求未写入，请检查 Root 授权"
            graph.module.refresh()
        }
    }

    fun setObserveOnly(observeOnly: Boolean) {
        viewModelScope.launch {
            graph.settings.update { it.copy(observeOnly = observeOnly) }
            graph.module.refresh()
        }
    }

    fun reloadConfiguration() {
        viewModelScope.launch {
            maintenance.value = runCatching { graph.sync.reload(); "已重新载入系统配置" }
                .getOrElse { it.message ?: "读取系统配置失败" }
        }
    }

    fun diagnostics(onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(graph.module.diagnostics())
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            graph.events.clear()
            maintenance.value = if (graph.module.clearModuleLog()) "本地记录已清除，模块记录等待清除。" else "本地记录已清除，模块记录清除失败。"
        }
    }

    fun recovery(action: String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            maintenance.value = runCatching {
                val installer = RecoveryInstaller(graph.context)
                val ok = when (action) {
                    "install" -> installer.install()
                    "disable" -> installer.disable()
                    "rearm" -> {
                        graph.settings.update { it.copy(observeOnly = true, protectionEnabled = false) }
                        installer.rearm()
                    }
                    else -> false
                }
                if (ok) when (action) {
                    "install" -> "恢复辅助已安装，请重启手机。"
                    "disable" -> "已写入锁定暂停请求，请检查首页状态。"
                    else -> "已提交解除请求，保持暂停和观察模式。请检查诊断。"
                } else "操作未完成，请检查 Root 权限和诊断。"
            }.getOrElse { it.message ?: "恢复操作失败" }
            busy.value = false
            graph.module.refresh()
        }
    }

    fun resetExceptions() {
        viewModelScope.launch {
            graph.rules.clearExceptions()
        }
    }
}
