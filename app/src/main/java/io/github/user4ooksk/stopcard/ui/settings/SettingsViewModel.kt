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
            maintenance.value = if (graph.module.clearModuleLog()) "已提交模块日志清除请求，处理结果见命令回执" else "模块日志清除请求未提交"
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
                if (ok) "已提交恢复操作。监测确认和锁定状态见模块状态；解除锁定后仍保持暂停。" else "恢复操作未完成，请检查 Root 授权和诊断信息。"
            }.getOrElse { it.message ?: "恢复操作失败" }
            busy.value = false
            graph.module.refresh()
        }
    }

    fun resetExceptions() {
        viewModelScope.launch {
            graph.rules.delete(graph.rules.all().keys)
        }
    }
}
