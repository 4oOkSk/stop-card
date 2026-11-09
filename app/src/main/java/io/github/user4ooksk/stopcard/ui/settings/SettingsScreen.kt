package io.github.user4ooksk.stopcard.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.user4ooksk.stopcard.BuildConfig
import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.model.ThemeMode
import io.github.user4ooksk.stopcard.ui.capabilityLabel
import io.github.user4ooksk.stopcard.ui.capabilityStatus
import io.github.user4ooksk.stopcard.ui.components.GroupCard
import io.github.user4ooksk.stopcard.ui.components.GroupDivider
import io.github.user4ooksk.stopcard.ui.components.SectionHeader
import io.github.user4ooksk.stopcard.ui.graphViewModel
import io.github.user4ooksk.stopcard.ui.moduleProblem

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val viewModel = graphViewModel { SettingsViewModel(it) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val module by viewModel.module.collectAsStateWithLifecycle()
    val synchronization by viewModel.synchronization.collectAsStateWithLifecycle()
    val maintenance by viewModel.maintenance.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val current = settings ?: return
    var page by rememberSaveable { mutableStateOf("设置") }
    var showTechnical by rememberSaveable { mutableStateOf(false) }
    var recoveryAction by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listState = remember(page) { LazyListState() }
    BackHandler(page != "设置") { page = "设置" }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, top + 8.dp, 16.dp, contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "$page-title") {
            Row {
                if (page != "设置") TextButton(onClick = { page = "设置" }) { Text("返回") }
                Text(page, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(8.dp))
            }
        }
        when (page) {
            "设置" -> {
                item { SectionHeader("保护") }
                item {
                    GroupCard {
                        SettingSwitch("卡片规则", "划掉最后一张卡片后，无其他保护时停止应用。", current.protectionEnabled, viewModel::setProtectionEnabled)
                        GroupDivider()
                        SettingSwitch("观察模式", "仅记录，不拦截或停止应用。", current.observeOnly, viewModel::setObserveOnly)
                        GroupDivider()
                        SettingSwitch("减少有卡应用回收", "优先保留最近使用的有卡应用，内存紧张时自动减弱。", current.extraKeepAlive) { on ->
                            viewModel.update { it.copy(extraKeepAlive = on) }
                        }
                    }
                }
                item {
                    GroupCard {
                        ModuleSummary(module)
                        if (synchronization.pending || synchronization.error != null) {
                            RowMessage("配置", if (synchronization.error != null) "同步失败，请重新载入。" else "正在同步")
                            if (synchronization.error != null) TextButton(onClick = viewModel::reloadConfiguration) { Text("重新载入") }
                        }
                    }
                }
                item { SectionHeader("应用") }
                item {
                    GroupCard {
                        SettingSwitch("显示系统应用", null, current.showSystemApps) { show -> viewModel.update { it.copy(showSystemApps = show) } }
                    }
                }
                item { SectionHeader("外观") }
                item {
                    GroupCard {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("主题") },
                            supportingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ThemeMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = current.themeMode == mode,
                                            onClick = { viewModel.update { it.copy(themeMode = mode) } },
                                            label = { Text(when (mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) },
                                        )
                                    }
                                }
                            },
                        )
                        GroupDivider()
                        SettingSwitch("动态颜色", "使用壁纸颜色。", current.dynamicColor) { on -> viewModel.update { it.copy(dynamicColor = on) } }
                    }
                }
                item { SectionHeader("维护") }
                item {
                    GroupCard {
                        ActionRow("清除记录", "保留应用例外。", "清除", onClick = viewModel::clearHistory)
                        GroupDivider()
                        ActionRow("清除全部例外", null, "清除", onClick = { confirmReset = true })
                        GroupDivider()
                        ActionRow("诊断", null, "查看", onClick = { page = "诊断"; showTechnical = false })
                        GroupDivider()
                        ActionRow("故障恢复", null, "查看", onClick = { page = "故障恢复" })
                    }
                }
                item { SectionHeader("关于") }
                item {
                    GroupCard {
                        RowMessage("Stop Card", "${BuildConfig.VERSION_NAME} · GPL-3.0 · 基于 Stop")
                        GroupDivider()
                        RowMessage("源码", "github.com/4oOkSk/stop-card")
                    }
                }
            }
            "诊断" -> {
                item {
                    GroupCard {
                        ModuleSummary(module)
                        ActionRow("技术信息", null, if (showTechnical) "收起" else "展开", onClick = { showTechnical = !showTechnical })
                        ActionRow("完整诊断", null, "查看", onClick = { viewModel.diagnostics { diagnostics = it } })
                    }
                }
                if (showTechnical) item {
                    GroupCard {
                        RowMessage("配置确认", synchronization.error ?: when {
                            synchronization.pending -> "等待代次 ${synchronization.generation}"
                            else -> "已确认代次 ${synchronization.generation}"
                        })
                        ModuleRows(module)
                    }
                }
            }
            "故障恢复" -> {
                item {
                    GroupCard {
                        ActionRow("恢复辅助", null, "安装或更新", !busy) { recoveryAction = "install" }
                        GroupDivider()
                        ActionRow("锁定暂停", null, "锁定", !busy) { recoveryAction = "disable" }
                        GroupDivider()
                        ActionRow("解除锁定", null, "解除", !busy) { recoveryAction = "rearm" }
                    }
                }
                item {
                    RowMessage("无法正常启动", "在模块管理器中停用 Stop Card，然后重启。")
                }
            }
        }
    }

    recoveryAction?.let { action ->
        val title = when (action) { "install" -> "安装或更新恢复辅助"; "disable" -> "锁定暂停"; else -> "解除锁定" }
        val explanation = when (action) {
            "install" -> "辅助程序在模块连续异常时锁定暂停。安装或更新后请重启手机。"
            "disable" -> "暂停管控，并阻止下次启动时执行规则。已停止的应用需重新打开。"
            else -> "仅在模块心跳正常时解除锁定。解除后保持暂停和观察模式，请检查状态后再启用。"
        }
        AlertDialog(
            onDismissRequest = { recoveryAction = null },
            title = { Text(title) }, text = { Text(explanation) },
            confirmButton = { TextButton(enabled = !busy, onClick = { recoveryAction = null; viewModel.recovery(action) }) { Text(title) } },
            dismissButton = { TextButton(onClick = { recoveryAction = null }) { Text("取消") } },
        )
    }
    diagnostics?.let { text ->
        AlertDialog(
            onDismissRequest = { diagnostics = null }, title = { Text("诊断") },
            text = { Text(text.ifBlank { "没有诊断信息，请检查 Root 权限。" }, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) },
            confirmButton = { TextButton(onClick = { diagnostics = null }) { Text("关闭") } },
            dismissButton = { TextButton(onClick = {
                val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                context.startActivity(android.content.Intent.createChooser(share, "导出诊断"))
            }) { Text("导出") } },
        )
    }
    maintenance?.let { message ->
        AlertDialog(onDismissRequest = { viewModel.maintenance.value = null }, title = { Text("操作结果") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { viewModel.maintenance.value = null }) { Text("关闭") } })
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false }, title = { Text("清除全部例外？") },
        text = { Text("普通应用将恢复按卡片管理。") },
        confirmButton = { TextButton(onClick = { confirmReset = false; viewModel.resetExceptions() }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
    )
}

@Composable
private fun SettingSwitch(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) }, supportingContent = body?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
    )
}

@Composable
private fun ActionRow(title: String, body: String?, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) }, supportingContent = body?.let { { Text(it) } },
        trailingContent = { TextButton(enabled = enabled, onClick = onClick) { Text(action) } },
    )
}

@Composable
private fun ModuleSummary(module: ModuleClient.State) {
    when (module) {
        ModuleClient.State.NoRoot -> RowMessage("需要 Root", "请授予 Stop Card Root 权限。")
        ModuleClient.State.Waiting -> RowMessage("等待模块", "请在 LSPosed 中启用模块，选择“系统框架”，然后重启。")
        is ModuleClient.State.Failed -> RowMessage("模块未就绪", moduleProblem(module.message))
        is ModuleClient.State.Connected -> {
            val m = module.module
            when {
                !m.configLoaded -> RowMessage("等待配置", "请完成引导或重新打开应用。")
                m.paused -> RowMessage("已暂停")
                !m.ready -> RowMessage("模块未就绪", moduleProblem(m.lastError))
                m.observeOnly -> RowMessage("观察中")
                else -> RowMessage("已启用")
            }
        }
    }
}

@Composable
private fun ModuleRows(module: ModuleClient.State) {
    if (module is ModuleClient.State.Failed) RowMessage("错误", module.message)
    if (module !is ModuleClient.State.Connected) return
    val m = module.module
    RowMessage("系统模块", "PID ${m.pid} · UID ${m.uid} · SDK ${m.sdk} · 配置代次 ${m.configGeneration}")
    RowMessage("能力", "卡片：${capabilityStatus(m.cardControl)} · 通知：${capabilityStatus(m.notificationControl)} · 后台入口：${capabilityStatus(m.wakeControl)}")
    RowMessage("恢复辅助", m.recovery)
    m.hooks.forEach { (key, status) ->
        RowMessage(capabilityLabel(key), capabilityStatus(status) + if (':' in status) " · ${status.substringAfter(':')}" else "")
    }
    if (m.flows.isNotEmpty()) RowMessage("操作链", m.flows.joinToString("\n"))
    if (m.commandResults.isNotEmpty()) RowMessage("命令回执", m.commandResults.entries.toList().takeLast(5).joinToString("\n") { "${it.key}: ${it.value}" })
    RowMessage("任务来源", m.taskSource)
    m.lastError?.let { RowMessage("错误", it) }
}

@Composable
private fun RowMessage(title: String, body: String? = null) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) }, supportingContent = body?.let { { Text(it) } },
    )
}
