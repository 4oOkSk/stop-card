package io.github.user4ooksk.stopcard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import io.github.user4ooksk.stopcard.ui.components.GroupCard
import io.github.user4ooksk.stopcard.ui.components.GroupDivider
import io.github.user4ooksk.stopcard.ui.components.SectionHeader
import io.github.user4ooksk.stopcard.ui.graphViewModel
import io.github.user4ooksk.stopcard.ui.capabilityLabel
import io.github.user4ooksk.stopcard.ui.capabilityStatus

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val viewModel = graphViewModel { SettingsViewModel(it) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val module by viewModel.module.collectAsStateWithLifecycle()
    val synchronization by viewModel.synchronization.collectAsStateWithLifecycle()
    val maintenance by viewModel.maintenance.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val current = settings ?: return
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = top + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                "设置",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            )
        }

        item { SectionHeader("保护") }
        item {
            GroupCard {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("卡片规则") },
                    supportingContent = { Text("卡片还在就允许运行；最后一张卡移除后复核停止。") },
                    trailingContent = {
                        Switch(
                            checked = current.protectionEnabled,
                            onCheckedChange = viewModel::setProtectionEnabled,
                        )
                    },
                )
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("观察模式") },
                    supportingContent = { Text("只记录“本来会怎么判”，不拦截、不强停。建议先观察一段时间。") },
                    trailingContent = {
                        Switch(
                            checked = current.observeOnly,
                            onCheckedChange = viewModel::setObserveOnly,
                        )
                    },
                )
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("划卡复核窗口") },
                    supportingContent = { Text("${current.reviewMs} 毫秒。用于防止刚划掉又打开时被旧决策误杀。") },
                )
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("无卡页面隐藏后的复核") },
                    supportingContent = { Text("可见窗口继续保护；跨应用操作跟随页面、服务与绑定生命周期，隐藏后最长 ${current.recentVisibleSeconds} 秒兜底，再复核卡片和例外。") },
                )
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("通知入口拦截") },
                    supportingContent = { Text("按通知所属应用检查运行许可，代理发布也检查目标应用。入口是否挂接见下方能力状态。") },
                )
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("停止后的通知清理") },
                    supportingContent = { Text("由 Android 原生强停处理，保留用户的通知渠道设置。") },
                )
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("有卡额外保活") },
                    supportingContent = { Text("仅调整有卡应用的缓存进程回收优先级，不保证常驻。默认关闭；启用后需测量耗电和存活。") },
                    trailingContent = { Switch(current.extraKeepAlive, { on -> viewModel.update { it.copy(extraKeepAlive = on) } }) },
                )
            }
        }

        item { SectionHeader("模块状态") }
        item {
            GroupCard {
                ModuleRows(module)
                GroupDivider()
                RowMessage("配置确认", when {
                    synchronization.error != null -> synchronization.error!!
                    synchronization.pending -> "等待模块确认代次 ${synchronization.generation}"
                    synchronization.generation > 0 -> "模块已确认代次 ${synchronization.generation}"
                    else -> "等待同步"
                })
                if (synchronization.error != null) TextButton(onClick = viewModel::reloadConfiguration) { Text("重新载入系统配置") }
            }
        }

        item { SectionHeader("应用") }
        item {
            GroupCard {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("显示系统应用") },
                    supportingContent = { Text("系统应用默认不按卡片规则管理；这里只影响列表显示。") },
                    trailingContent = {
                        Switch(
                            checked = current.showSystemApps,
                            onCheckedChange = { show -> viewModel.update { it.copy(showSystemApps = show) } },
                        )
                    },
                )
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
                                    label = {
                                        Text(
                                            when (mode) {
                                                ThemeMode.SYSTEM -> "跟随系统"
                                                ThemeMode.LIGHT -> "浅色"
                                                ThemeMode.DARK -> "深色"
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    },
                )
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("动态颜色") },
                    supportingContent = { Text("使用壁纸提取的颜色。") },
                    trailingContent = {
                        Switch(
                            checked = current.dynamicColor,
                            onCheckedChange = { on -> viewModel.update { it.copy(dynamicColor = on) } },
                        )
                    },
                )
            }
        }

        item { SectionHeader("维护") }
        item {
            GroupCard {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("清除历史") },
                    supportingContent = { Text("删除本地决策日志。不会改变应用例外。") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = {
                        TextButton(onClick = viewModel::clearHistory) { Text("清除") }
                    },
                )
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("清除全部例外") },
                    supportingContent = { Text("恢复为所有普通应用都按卡片规则管理。") },
                    trailingContent = {
                        TextButton(onClick = { confirmReset = true }) { Text("清除") }
                    },
                )
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("诊断信息") },
                    supportingContent = { Text("Root、模块文件和状态。") },
                    trailingContent = {
                        TextButton(onClick = { viewModel.diagnostics { diagnostics = it } }) { Text("查看") }
                    },
                )
            }
        }

        item { SectionHeader("故障恢复") }
        item {
            GroupCard {
                Column(Modifier.padding(16.dp)) {
                    Text("可选的独立恢复辅助监测模块心跳和系统进程重启，不影响核心能力是否就绪。连续失败会锁定暂停，下次系统进程启动先检查锁定。", style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = !busy, onClick = { viewModel.recovery("install") }) { Text("安装或更新恢复辅助") }
                    TextButton(enabled = !busy, onClick = { viewModel.recovery("disable") }) { Text("立即锁定暂停") }
                    TextButton(enabled = !busy, onClick = { viewModel.recovery("rearm") }) { Text("请求解除锁定并保持观察") }
                    Text("更新已运行的辅助后需要重启手机。解除锁定仅在当前模块心跳正常时执行，之后需手动恢复卡片规则。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "adb shell su -c 'sh /data/adb/stop_card_recovery/disable.sh'",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "暂停不会自动恢复被强停应用的推送；重新打开应用才会恢复其正常调度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("手机不能正常启动时，在 KernelSU/Vector 中停用 Stop Card；恢复辅助的关闭脚本也可从支持访问 /data 的恢复环境运行。实际恢复路径须在本机验收。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item { SectionHeader("关于") }
        item {
            GroupCard {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Stop Card") },
                    supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME} · GPL-3.0 · 基于 Stop") },
                )
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("源码") },
                    supportingContent = { Text("github.com/4oOkSk/stop-card") },
                )
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("本机") },
                    supportingContent = { Text(context.packageName) },
                )
            }
        }
    }

    diagnostics?.let { text ->
        AlertDialog(
            onDismissRequest = { diagnostics = null },
            title = { Text("诊断信息") },
            text = {
                Text(
                    text.ifBlank { "没有输出。请确认 KernelSU 已授权 Stop Card。" },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            },
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
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("清除全部例外？") },
            text = { Text("所有普通应用都会重新按卡片规则管理。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetExceptions()
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ModuleRows(module: ModuleClient.State) {
    when (module) {
        ModuleClient.State.NoRoot -> RowMessage("需要 Root", "无法读取模块文件。请在 KernelSU 中授权 Stop Card。")
        ModuleClient.State.Waiting -> RowMessage("等待模块", "尚未读到状态文件。确认 Vector 已启用本模块并重启手机。")
        is ModuleClient.State.Failed -> RowMessage("模块未就绪", module.message)
        is ModuleClient.State.Connected -> {
            val m = module.module
            if (!m.configLoaded) {
                RowMessage("等待配置", "模块已运行，但尚未读到配置。完成引导或重新打开本应用。")
                return
            }
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("系统框架内模块") },
                supportingContent = {
                    Text("PID ${m.pid} · UID ${m.uid} · SDK ${m.sdk} · 配置代次 ${m.configGeneration}")
                },
                trailingContent = {
                    AssistChip(onClick = {}, label = { Text(when { m.paused -> "已暂停"; !m.ready -> "初始化或降级"; m.observeOnly -> "观察中"; else -> "执行中" }) })
                },
            )
            GroupDivider()
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("能力") },
                supportingContent = { Text("卡片：${capabilityStatus(m.cardControl)} · 通知：${capabilityStatus(m.notificationControl)} · 后台入口：${capabilityStatus(m.wakeControl)}") },
            )
            GroupDivider()
            RowMessage("独立恢复", if (m.recovery == "watching") "监测心跳正常" else "未就绪或已锁定，保持观察：${m.recovery}")
            m.hooks.forEach { (key, status) ->
                GroupDivider()
                RowMessage(capabilityLabel(key), capabilityStatus(status) + if (':' in status) " · ${status.substringAfter(':')}" else "")
            }
            if (m.flows.isNotEmpty()) {
                GroupDivider()
                RowMessage("操作链", m.flows.joinToString("\n"))
            }
            if (m.commandResults.isNotEmpty()) {
                GroupDivider()
                RowMessage("命令回执", m.commandResults.entries.toList().takeLast(5).joinToString("\n") { "${it.key}: ${it.value}" })
            }
            GroupDivider()
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("任务来源") },
                supportingContent = {
                    Text(
                        when (m.taskSource) {
                            "ok" -> "最近任务读取正常"
                            "empty" -> "最近任务为空"
                            "denied" -> "没有读取最近任务的权限"
                            else -> m.taskSource
                        } + " · 已停止 ${m.stops} 次" + (m.lastError?.let { "\n$it" } ?: ""),
                    )
                },
            )
        }
    }
}

@Composable
private fun RowMessage(title: String, body: String) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
    )
}
