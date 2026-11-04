package io.github.user4ooksk.stopcard.ui

fun capabilityLabel(key: String): String = when (key) {
    "activity" -> "Activity 启动与操作来源"
    "user_input" -> "用户触摸来源核验"
    "activity_gate", "activity_request" -> "Activity 原生拒绝与来源核验"
    "task_remove" -> "划卡与任务移除"
    "force_stop" -> "系统原生强停"
    "user_task_close", "close_context_removeTask", "close_context_removeAllVisibleRecentTasks" -> "最近任务关闭来源"
    "ui_process_identity" -> "系统界面进程身份"
    "role_changes" -> "系统角色变更"
    "fact_changes" -> "安装、设置和网络变更"
    "recents_open" -> "最近任务打开"
    "shortcut_open" -> "桌面快捷方式打开"
    "window_true" -> "可见窗口"
    "window_false" -> "窗口隐藏"
    "activity_finish" -> "操作页面结束"
    "service_context", "service_lookup" -> "服务启动"
    "service_finish" -> "实际服务结束"
    "service_teardown" -> "未运行服务撤销"
    "bind_context", "binding_lifetime", "binding_remove" -> "服务绑定与解除"
    "notifications" -> "通知入队"
    "notification_open" -> "通知点击与操作来源"
    "broadcast" -> "广播接收"
    "broadcast_lifetime" -> "广播交付与完成"
    "broadcast_origin" -> "异步广播操作来源"
    "provider" -> "内容提供器"
    "provider_finish" -> "内容提供器引用释放"
    "job" -> "任务调度"
    "alarm" -> "闹钟交付"
    "pending_intent" -> "延迟操作"
    "process" -> "进程启动"
    "extra_keep_alive" -> "减少有卡应用回收"
    else -> when {
        key.startsWith("stop_") -> "停止前会话复核"
        key.startsWith("widget_") -> "小组件添加与移除"
        else -> key
    }
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
    "app_widget" -> "已添加的小组件"
    "autofill" -> "自动填充"
    "device_admin" -> "设备管理"
    "wallpaper", "live_wallpaper" -> "动态壁纸"
    "alarm", "alarm_clock" -> "闹钟处理器"
    "assistant" -> "默认助理"
    "google_service" -> "Google 基础服务"
    "webview" -> "当前 WebView"
    else -> code
}
