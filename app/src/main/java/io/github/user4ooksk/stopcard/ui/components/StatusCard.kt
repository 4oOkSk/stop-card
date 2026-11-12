package io.github.user4ooksk.stopcard.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.user4ooksk.stopcard.R
import io.github.user4ooksk.stopcard.ui.Protection
import io.github.user4ooksk.stopcard.ui.moduleProblem

/** The big card on top of Home: what card control is doing and how to fix it. */
@Composable
fun StatusCard(
    protection: Protection,
    blockedCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRecovery: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (protection) {
        Protection.Active -> colors.primaryContainer to colors.onPrimaryContainer
        Protection.Observe -> colors.tertiaryContainer to colors.onTertiaryContainer
        Protection.Starting, Protection.NotConfigured, Protection.Paused ->
            colors.surfaceContainerHigh to colors.onSurface
        is Protection.EmergencyPaused, Protection.RootMissing, Protection.ModuleMissing, is Protection.Failed, is Protection.Degraded ->
            colors.errorContainer to colors.onErrorContainer
    }
    val containerColor by animateColorAsState(container, label = "statusContainer")
    val contentColor by animateColorAsState(content, label = "statusContent")

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        AnimatedContent(
            targetState = protection,
            contentKey = { it.javaClass },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "status",
        ) { state ->
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StopEmblem(
                        color = if (state.isActive) colors.primary else contentColor.copy(alpha = 0.72f),
                        barColor = colors.onPrimary,
                        active = state.isActive,
                        modifier = Modifier.size(76.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title(state), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.padding(top = 4.dp))
                        Text(
                            body(state, blockedCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor.copy(alpha = 0.85f),
                        )
                    }
                }
                Spacer(Modifier.padding(top = 16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (state) {
                        Protection.Starting -> CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = contentColor,
                        )
                        else -> Unit
                    }
                    Spacer(Modifier.weight(1f))
                    when (state) {
                        Protection.Active, Protection.Observe, is Protection.Degraded -> FilledTonalButton(
                            onClick = onPause,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colors.surface.copy(alpha = 0.6f),
                                contentColor = contentColor,
                            ),
                        ) { Text("暂停") }
                        Protection.Paused -> Button(onClick = onResume) { Text("恢复") }
                        is Protection.EmergencyPaused -> Button(
                            onClick = onOpenRecovery,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.error,
                                contentColor = colors.onError,
                            ),
                        ) { Text("故障恢复") }
                        Protection.NotConfigured, Protection.RootMissing, Protection.ModuleMissing,
                        is Protection.Failed ->
                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.error,
                                    contentColor = colors.onError,
                                ),
                            ) { Text(stringResource(R.string.action_retry)) }
                        Protection.Starting -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun title(state: Protection): String = when (state) {
    Protection.Active -> "已启用"
    Protection.Observe -> "观察模式"
    Protection.Starting -> "正在连接模块"
    Protection.NotConfigured -> "等待配置"
    is Protection.Degraded -> "部分功能不可用"
    Protection.Paused -> "已暂停"
    is Protection.EmergencyPaused -> "已锁定暂停"
    Protection.RootMissing -> "需要 Root"
    Protection.ModuleMissing -> "模块未就绪"
    is Protection.Failed -> "模块状态异常"
}

@Composable
private fun body(state: Protection, blockedCount: Int): String = when (state) {
    Protection.Active -> pluralStringResource(R.plurals.status_active_body, blockedCount, blockedCount)
    Protection.Observe -> "仅记录，不拦截或停止应用。"
    Protection.Starting -> "正在读取系统模块状态。"
    Protection.NotConfigured -> "请完成引导或重新打开应用。"
    is Protection.Degraded -> moduleProblem(state.message)
    Protection.Paused -> "已暂停管控。"
    is Protection.EmergencyPaused -> "已触发紧急停用标记。请在设置 → 故障恢复中检查并解除锁定。"
    Protection.RootMissing -> "请授予 Stop Card Root 权限。"
    Protection.ModuleMissing -> "请在 LSPosed 中启用模块，选择“系统框架”，然后重启。"
    is Protection.Failed -> moduleProblem(state.message)
}
