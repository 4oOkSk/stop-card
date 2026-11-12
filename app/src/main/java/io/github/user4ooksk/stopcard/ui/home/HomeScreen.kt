package io.github.user4ooksk.stopcard.ui.home

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.user4ooksk.stopcard.R
import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.ui.MainViewModel
import io.github.user4ooksk.stopcard.ui.components.AppIcon
import io.github.user4ooksk.stopcard.ui.components.GroupCard
import io.github.user4ooksk.stopcard.ui.components.GroupDivider
import io.github.user4ooksk.stopcard.ui.components.SectionHeader
import io.github.user4ooksk.stopcard.ui.components.StatusCard
import io.github.user4ooksk.stopcard.ui.countdownLabel
import io.github.user4ooksk.stopcard.ui.eventSummary
import io.github.user4ooksk.stopcard.ui.graphViewModel
import io.github.user4ooksk.stopcard.ui.relativeTime
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    main: MainViewModel,
    contentPadding: PaddingValues,
    onOpenActivity: () -> Unit,
    onOpenRecovery: () -> Unit = {},
) {
    val viewModel = graphViewModel { HomeViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val protection by main.protection.collectAsStateWithLifecycle()

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = statusBar + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        item(key = "status") {
            StatusCard(
                protection = protection,
                blockedCount = state.blockedCount,
                onPause = { main.setProtectionEnabled(false) },
                onResume = { main.setProtectionEnabled(true) },
                onRetry = main::refresh,
                onOpenRecovery = onOpenRecovery,
            )
        }
        item(key = "capability") {
            val module = state.module
            if (module is ModuleClient.State.Connected) {
                val missing = buildList {
                    if (module.module.notificationControl != "installed") add("通知入队拦截")
                    if (module.module.wakeControl != "installed") add("后台入口拦截")
                }
                if (missing.isNotEmpty()) {
                    Text(
                        "部分功能不可用。请在设置中查看诊断。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
        item(key = "stats") {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(state.cards, "卡片", Modifier.weight(1f).fillMaxHeight())
                StatTile(state.operations, "临时运行", Modifier.weight(1f).fillMaxHeight())
                StatTile(state.stopped, "已停止", Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (protection.isActive || state.running.isNotEmpty()) {
            item(key = "running-header") {
                SectionHeader(
                    stringResource(R.string.home_running),
                    action = if (protection.isEnforcing && state.running.any { it.canStop }) stringResource(R.string.action_stop_all) else null,
                    onAction = viewModel::stopAll,
                )
            }
            item(key = "running") {
                GroupCard(Modifier.animateContentSize()) {
                    if (state.running.isEmpty()) {
                        EmptyRow(stringResource(R.string.home_running_empty))
                    } else {
                        val now by produceState(SystemClock.elapsedRealtime()) {
                            while (true) {
                                delay(1_000)
                                value = SystemClock.elapsedRealtime()
                            }
                        }
                        state.running.forEachIndexed { index, running ->
                            if (index > 0) GroupDivider()
                            RunningRow(
                                running = running,
                                now = now,
                                enforcing = protection.isEnforcing,
                                onStop = { viewModel.stopNow(running.packageName) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "recent-header") {
            SectionHeader(
                stringResource(R.string.home_recent),
                action = if (state.recent.isNotEmpty()) stringResource(R.string.action_see_all) else null,
                onAction = onOpenActivity,
            )
        }
        item(key = "recent") {
            GroupCard(Modifier.animateContentSize()) {
                if (state.recent.isEmpty()) {
                    EmptyRow(stringResource(R.string.home_recent_empty), Icons.Rounded.Bedtime)
                } else {
                    val now by produceState(System.currentTimeMillis()) {
                        while (true) {
                            delay(30_000)
                            value = System.currentTimeMillis()
                        }
                    }
                    state.recent.forEachIndexed { index, recent ->
                        if (index > 0) GroupDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            leadingContent = { AppIcon(recent.event.packageName, size = 40.dp) },
                            headlineContent = { Text(recent.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(eventSummary(recent.event), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                Text(
                                    relativeTime(recent.event.time, now),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: Int, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "stat",
            ) { number ->
                Text(number.toString(), style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun RunningRow(running: HomeViewModel.Running, now: Long, enforcing: Boolean, onStop: () -> Unit) {
    val status = when {
        running.inUse -> stringResource(R.string.running_in_use)
        running.stopsAt != null && running.stopsAt > now -> stringResource(R.string.running_stops_in, countdownLabel(running.stopsAt - now))
        running.stopsAt != null -> stringResource(R.string.running_stopping)
        running.cards > 0 -> "有 ${running.cards} 张卡"
        running.state == "operation" -> "临时运行"
        running.state == "opening" -> "正在打开"
        running.state == "uncertain" -> "状态待确认"
        else -> stringResource(R.string.running_background)
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { AppIcon(running.packageName, size = 40.dp) },
        headlineContent = {
            Text(running.app?.label ?: running.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(status, color = if (running.inUse) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            IconButton(onClick = onStop, enabled = enforcing && running.canStop) {
                Icon(Icons.Rounded.Block, contentDescription = stringResource(R.string.action_stop_now))
            }
        },
    )
}

@Composable
private fun EmptyRow(text: String, icon: ImageVector = Icons.Rounded.CheckCircle) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
