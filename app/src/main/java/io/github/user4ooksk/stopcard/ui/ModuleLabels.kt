package io.github.user4ooksk.stopcard.ui

fun capabilityLabel(key: String): String = when (key) {
    "activity" -> "Activity 启动与操作来源"
    "task_remove" -> "划卡与任务移除"
    "recents" -> "最近任务打开"
    "user_input" -> "窗口触摸来源"
    "ui_process_identity" -> "系统界面进程身份"
    "role_changes" -> "系统角色变更"
    "recents_open" -> "最近任务打开"
    "window_visible" -> "可见窗口"
    "window_hidden" -> "窗口隐藏"
    "activity_finish" -> "操作页面结束"
    "activity_result" -> "结果回传"
    "service_context", "service_lookup" -> "服务启动"
    "bind_context", "binding_lifetime", "binding_remove" -> "服务绑定与解除"
    "notifications" -> "通知入队"
    "broadcast" -> "广播接收"
    "provider" -> "内容提供器"
    "job" -> "任务调度"
    "alarm" -> "闹钟交付"
    "pending_intent" -> "延迟操作"
    "process" -> "进程启动"
    "extra_keep_alive" -> "有卡额外保活"
    else -> if (key.startsWith("stop_")) "停止前会话复核" else key
}

fun capabilityStatus(value: String): String = when (value.substringBefore(':')) {
    "installed" -> "已挂接"
    "active" -> "已就绪"
    "partial" -> "部分入口可用"
    "degraded" -> "已降级"
    "failed", "disabled" -> "挂接失败，已放行"
    else -> "不可用"
}

fun reasonLabel(code: String): String = when (code) {
    "system_package" -> "系统应用"
    "controller" -> "管理界面"
    "root_manager" -> "Root 管理器"
    "vpn", "always_on_vpn" -> "当前 VPN"
    "home", "launcher", "android.app.role.HOME" -> "当前桌面"
    "dialer", "android.app.role.DIALER" -> "默认电话"
    "sms", "android.app.role.SMS" -> "默认短信"
    "ime", "input_method" -> "输入法"
    "accessibility" -> "无障碍服务"
    "notification_listener" -> "通知使用权"
    "autofill" -> "自动填充"
    "device_admin" -> "设备管理"
    "wallpaper", "live_wallpaper" -> "动态壁纸"
    "alarm", "alarm_clock" -> "闹钟处理器"
    "assistant" -> "默认助理"
    else -> code
}
