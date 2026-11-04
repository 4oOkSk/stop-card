package io.github.user4ooksk.stopcard.ui.apps

import io.github.user4ooksk.stopcard.model.InstalledApp
import org.junit.Assert.*
import org.junit.Test

class AppStatusTest {
    private val item = AppsViewModel.Item(InstalledApp("a", "A", false), null, false, 0, statusKnown = true)

    @Test fun absenceAndUnavailableStateDoNotClaimForceStop() {
        assertEquals("未运行", item.statusLabel())
        assertEquals("状态待确认", item.copy(statusKnown = false, stopped = true).statusLabel())
        assertEquals("已停止", item.copy(stopped = true).statusLabel())
    }

    @Test fun cardsAndVisibleActivityTakePriorityOverOldStopState() {
        assertEquals("有卡片", item.copy(cards = 2, stopped = true).statusLabel())
        assertEquals("正在使用", item.copy(visible = true, stopped = true).statusLabel())
        assertEquals("临时运行", item.copy(running = true, state = "operation").statusLabel())
    }

    @Test fun detailsExplainProtectionWithoutExposingUnknownRawReason() {
        val protected = item.copy(reasons = setOf("app_widget", "vpn", "unknown/component"), state = "operation")
        val details = protected.statusDetails().joinToString()
        assertTrue(details.contains("桌面小组件"))
        assertTrue(details.contains("VPN"))
        assertTrue(details.contains("跨应用操作或系统绑定"))
        assertFalse(details.contains("unknown/component"))
    }
}
