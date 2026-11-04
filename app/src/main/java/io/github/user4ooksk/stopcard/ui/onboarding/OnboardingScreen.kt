package io.github.user4ooksk.stopcard.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.ui.components.StopEmblem
import io.github.user4ooksk.stopcard.ui.graphViewModel

@Composable
fun OnboardingScreen() {
    val viewModel = graphViewModel { OnboardingViewModel(it) }
    val step by viewModel.step.collectAsStateWithLifecycle()
    val module by viewModel.module.collectAsStateWithLifecycle()
    var observeOnly by remember { mutableStateOf(true) }

    BackHandler { viewModel.back() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        StopEmblem(
            color = MaterialTheme.colorScheme.primary,
            barColor = MaterialTheme.colorScheme.onPrimary,
            active = true,
            modifier = Modifier.size(88.dp),
        )
        when (step) {
            OnboardingViewModel.Step.WELCOME -> Welcome()
            OnboardingViewModel.Step.SETUP -> Setup(module = module, rootAvailable = viewModel.rootAvailable, onRefresh = viewModel::refresh)
            OnboardingViewModel.Step.READY -> Ready(observeOnly = observeOnly, onObserveChange = { observeOnly = it })
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step != OnboardingViewModel.Step.WELCOME) {
                TextButton(onClick = { viewModel.back() }) { Text("上一步") }
            }
            Spacer(Modifier.height(24.dp))
            if (step == OnboardingViewModel.Step.READY) {
                Button(onClick = { viewModel.finish(observeOnly) }) { Text("开始") }
            } else {
                Button(onClick = viewModel::next) { Text("下一步") }
            }
        }
    }
}

@Composable
private fun Welcome() {
    Text("Stop Card", style = MaterialTheme.typography.headlineLarge)
    Text("按最近任务卡片管理后台", style = MaterialTheme.typography.titleMedium)
    Text(
        "保留卡片，允许应用运行。移除最后一张卡片后，无其他保护时停止应用。",
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun Setup(module: ModuleClient.State, rootAvailable: Boolean, onRefresh: () -> Unit) {
    Text("准备系统模块", style = MaterialTheme.typography.headlineSmall)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. 在 LSPosed 或 Vector 中启用 Stop Card。", style = MaterialTheme.typography.bodyMedium)
            Text("2. 作用域只勾选“系统框架”。", style = MaterialTheme.typography.bodyMedium)
            Text("3. 重启手机。", style = MaterialTheme.typography.bodyMedium)
            Text("4. 授予 Stop Card Root 权限。", style = MaterialTheme.typography.bodyMedium)
        }
    }
    StatusRow("Root", rootAvailable, if (rootAvailable) "已授权" else "未授权或不可用")
    StatusRow(
        "模块",
        module is ModuleClient.State.Connected,
        when (module) {
            ModuleClient.State.NoRoot -> "需要 Root"
            ModuleClient.State.Waiting -> "等待状态文件"
            is ModuleClient.State.Failed -> module.message
            is ModuleClient.State.Connected -> if (module.module.configLoaded) {
                "配置已加载"
            } else {
                "已加载，等待配置"
            }
        },
    )
    TextButton(onClick = onRefresh) { Text("重新检查") }
}

@Composable
private fun Ready(observeOnly: Boolean, onObserveChange: (Boolean) -> Unit) {
    Text("选择模式", style = MaterialTheme.typography.headlineSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("观察模式", style = MaterialTheme.typography.bodyLarge)
            Text(
                "仅记录，不拦截或停止应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = observeOnly, onCheckedChange = onObserveChange)
    }
    Text(
        "需要持续运行的应用，请在应用页选择“允许后台”。",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun StatusRow(title: String, ok: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (ok) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        } else {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
