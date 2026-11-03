package io.github.user4ooksk.stopcard.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.user4ooksk.stopcard.graph
import io.github.user4ooksk.stopcard.ui.Protection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Quick Settings tile: shows whether card control is active and toggles pause/resume. */
class StopTileService : TileService() {
    private var listening: Job? = null

    override fun onStartListening() {
        listening = graph.scope.launch(Dispatchers.Main) {
            combine(graph.settings.settings, graph.module.state, graph.module.synchronization, Protection::of)
                .collect { render(it) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        graph.scope.launch {
            val enabled = graph.settings.current().protectionEnabled
            graph.setProtectionEnabled(!enabled)
            graph.module.refresh()
        }
    }

    private fun render(protection: Protection) {
        val tile = qsTile ?: return
        tile.state = if (protection.isEnforcing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Stop Card"
        tile.subtitle = when (protection) {
            Protection.Active -> "卡片规则生效"
            Protection.Observe -> "观察模式"
            Protection.Paused -> "已暂停"
            Protection.Starting -> "正在连接"
            Protection.NotConfigured -> "等待配置"
            Protection.RootMissing -> "需要 Root"
            Protection.ModuleMissing -> "模块未就绪"
            is Protection.Failed -> "状态异常"
            is Protection.Degraded -> "任务读取异常"
        }
        tile.updateTile()
    }
}
