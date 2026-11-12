package io.github.user4ooksk.stopcard.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.user4ooksk.stopcard.bridge.ModuleClient
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
        listening?.cancel()
        graph.module.setTileListening(true)
        listening = graph.scope.launch(Dispatchers.Main) {
            combine(graph.settings.settings, graph.module.state, graph.module.synchronization, Protection::of)
                .collect { render(it) }
        }
    }

    override fun onStopListening() {
        graph.module.setTileListening(false)
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        graph.scope.launch {
            val currentSettings = graph.settings.current()
            val module = (graph.module.state.value as? ModuleClient.State.Connected)?.module
            if (!currentSettings.onboarded || module?.emergencyDisabled == true) {
                graph.module.refresh()
                return@launch
            }
            val currentlyPaused = !currentSettings.protectionEnabled || module?.paused == true
            graph.setProtectionEnabled(currentlyPaused)
        }
    }

    private fun render(protection: Protection) {
        val tile = qsTile ?: return
        tile.state = when (protection) {
            Protection.Active -> Tile.STATE_ACTIVE
            is Protection.EmergencyPaused, Protection.NotConfigured, Protection.RootMissing, Protection.ModuleMissing -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Stop Card"
        tile.subtitle = when (protection) {
            Protection.Active -> "卡片规则生效"
            Protection.Observe -> "观察模式"
            Protection.Paused -> "已暂停"
            is Protection.EmergencyPaused -> "已锁定暂停"
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
