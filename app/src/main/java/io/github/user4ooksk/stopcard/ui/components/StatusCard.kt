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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.user4ooksk.stopcard.R
import io.github.user4ooksk.stopcard.ui.Protection

/** The big card on top of Home: what card control is doing and how to fix it. */
@Composable
fun StatusCard(
    protection: Protection,
    blockedCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (protection) {
        Protection.Active -> colors.primaryContainer to colors.onPrimaryContainer
        Protection.Observe -> colors.tertiaryContainer to colors.onTertiaryContainer
        Protection.Starting, Protection.NotConfigured, Protection.Paused ->
            colors.surfaceContainerHigh to colors.onSurface
        Protection.RootMissing, Protection.ModuleMissing, is Protection.Failed, is Protection.Degraded ->
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
                        Protection.Active -> ModePill("强制执行", contentColor)
                        Protection.Observe -> ModePill("只记录", contentColor)
                        Protection.Starting -> CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = contentColor,
                        )
                        else -> Unit
                    }
                    Spacer(Modifier.weight(1f))
                    when (state) {
                        Protection.Active, Protection.Observe -> FilledTonalButton(
                            onClick = onPause,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colors.surface.copy(alpha = 0.6f),
                                contentColor = contentColor,
                            ),
                        ) { Text("暂停") }
                        Protection.Paused -> Button(onClick = onResume) { Text("恢复") }
                        Protection.NotConfigured, Protection.RootMissing, Protection.ModuleMissing,
                        is Protection.Failed, is Protection.Degraded ->
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
private fun ModePill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.10f),
        contentColor = color,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun title(state: Protection): String = when (state) {
    Protection.Active -> "卡片规则生效"
    Protection.Observe -> "观察模式"
    Protection.Starting -> "正在连接模块"
    Protection.NotConfigured -> "模块尚未收到配置"
    is Protection.Degraded -> "部分功能不可用"
    Protection.Paused -> "已暂停"
    Protection.RootMissing -> "需要 Root"
    Protection.ModuleMissing -> "模块未就绪"
    is Protection.Failed -> "模块状态异常"
}

@Composable
private fun body(state: Protection, blockedCount: Int): String = when (state) {
    Protection.Active -> if (blockedCount == 0) {
        "当前没有受管的普通应用。"
    } else {
        pluralStringResource(R.plurals.status_active_body, blockedCount, blockedCount)
    }
    Protection.Observe -> "只记录“本来会怎么判”，不拦截、不强停。确认日志无误后再关闭观察模式。"
    Protection.Starting -> "正在读取系统模块状态。"
    Protection.NotConfigured -> "完成引导或重新打开本应用后，配置会写入系统模块。"
    is Protection.Degraded -> state.message
    Protection.Paused -> "卡片规则已暂停。系统其余功能继续工作；未执行的强停已取消。"
    Protection.RootMissing -> "请在 KernelSU 中给本应用授予 Root。Root 只用于读取模块状态、写入配置和故障恢复。"
    Protection.ModuleMissing -> "确认 Vector 已启用 Stop Card、作用域只有“系统框架”，并已重启手机。"
    is Protection.Failed -> state.message
}
