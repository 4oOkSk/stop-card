package io.github.user4ooksk.stopcard.ui.apps

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.user4ooksk.stopcard.R
import io.github.user4ooksk.stopcard.graph
import io.github.user4ooksk.stopcard.ui.reasonLabel
import io.github.user4ooksk.stopcard.ui.relativeTime
import io.github.user4ooksk.stopcard.model.isStop
import io.github.user4ooksk.stopcard.ui.startOfDay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppDetailSheet(
    item: AppsViewModel.Item,
    viewModel: AppsViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val packageName = item.app.packageName
    val history by remember(packageName) { viewModel.history(packageName) }.collectAsStateWithLifecycle(emptyList())
    val versionName = remember(packageName) { context.graph.apps.versionName(packageName) }
    var showReasons by rememberSaveable(packageName) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                io.github.user4ooksk.stopcard.ui.components.AppIcon(packageName, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.app.label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(packageName, versionName).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.statusLabel(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { showReasons = !showReasons }) {
                    Text(if (showReasons) "收起原因" else "查看原因")
                }
            }
            if (showReasons) {
                item.statusDetails().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Spacer(Modifier.height(12.dp))

            if (item.isProtected) {
                Notice(Icons.Rounded.Shield, "自动保护")
            } else {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = item.managed,
                        onClick = { viewModel.setAllowed(packageName, false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("按卡片管理", maxLines = 1) }
                    SegmentedButton(
                        selected = item.exempt,
                        onClick = { viewModel.setAllowed(packageName, true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("允许后台", maxLines = 1) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (item.managed) {
                        "划掉最后一张卡片后，无其他保护时停止应用。"
                    } else {
                        "不受卡片规则限制。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.detail_activity), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val now = System.currentTimeMillis()
            val today = startOfDay(now)
            val week = viewModel.weekStart()
            val todayCount = history.count { it.time >= today && it.reason.isStop }
            val weekCount = history.count { it.time >= week && it.reason.isStop }
            if (history.isEmpty()) {
                Text(
                    stringResource(R.string.detail_never_stopped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (todayCount > 0) {
                    Text(pluralStringResource(R.plurals.detail_stops_today, todayCount, todayCount), style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    pluralStringResource(R.plurals.detail_stops_week, weekCount, weekCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                for (event in history.take(5)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(reasonLabel(event.reason), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            relativeTime(event.time, now),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!item.isProtected) {
                    FilledTonalButton(
                        onClick = { viewModel.stopNow(packageName) },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Rounded.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_stop_now), maxLines = 1)
                    }
                }
                val launch = remember(packageName) { context.graph.apps.launchIntent(packageName) }
                if (launch != null) {
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(launch)
                            } catch (_: ActivityNotFoundException) {
                            }
                        },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_open), maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                        )
                    },
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_app_info), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, warning: Boolean = false) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (warning) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
