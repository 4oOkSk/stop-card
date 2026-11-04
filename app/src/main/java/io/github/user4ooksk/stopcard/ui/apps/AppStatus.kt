package io.github.user4ooksk.stopcard.ui.apps

import io.github.user4ooksk.stopcard.ui.reasonLabel

fun AppsViewModel.Item.statusLabel(): String = when {
    !statusKnown -> "状态待确认"
    visible -> "正在使用"
    cards > 0 -> "有卡片"
    stopped || state == "stopped" -> "已停止"
    !running -> "未运行"
    state in setOf("operation", "opening", "uncertain") -> "临时运行"
    state == "pending" -> "等待停止"
    else -> "正在运行"
}

/** Human-readable facts only; raw component names remain in diagnostics. */
fun AppsViewModel.Item.statusDetails(): List<String> = buildList {
    if (!statusKnown) {
        add("无法确认当前状态。请在设置中检查模块。")
        return@buildList
    }
    if (visible) add("应用页面可见。")
    if (cards > 0) add("保留 $cards 张卡片。")
    if (exempt) add("已允许后台运行。")
    reasons.sorted().forEach { code ->
        add(when (code) {
            "app_widget" -> "已添加桌面小组件。"
            "vpn", "always_on_vpn" -> "VPN 受保护。"
            else -> reasonLabel(code).takeIf { it != code }?.let { "$it：自动保护。" }
                ?: "系统必要组件：自动保护。"
        })
    }
    when (state) {
        "operation" -> add("正在处理跨应用操作或系统绑定。")
        "opening" -> add("正在打开应用。")
        "uncertain" -> add("正在确认启动来源，暂缓停止。")
        "pending" -> add("保护已结束，等待停止。")
        "stopped" -> add("应用已停止。")
    }
    if (isEmpty()) add(if (running) "应用正在运行。" else "应用未运行。")
}.distinct()
