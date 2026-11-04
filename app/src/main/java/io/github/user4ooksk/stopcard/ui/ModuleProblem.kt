package io.github.user4ooksk.stopcard.ui

/** Keep the cause and next action visible; leave raw errors in diagnostics. */
fun moduleProblem(message: String?): String = when {
    message == null -> "模块尚未就绪。请稍后重试。"
    "Root" in message -> "请授予 Stop Card Root 权限。"
    "用户空间" in message -> "请在主用户空间打开 Stop Card。"
    "状态文件不存在" in message -> "请在 LSPosed 中启用模块，选择“系统框架”，然后重启。"
    "版本" in message || "身份不匹配" in message -> "模块版本未更新。请重启手机。"
    "过期" in message || "心跳" in message -> "模块未响应。请重试，仍未恢复时重启手机。"
    "配置" in message -> "配置未同步。请在设置中重新载入。"
    "无法解析" in message -> "模块状态无法读取。请重启，仍异常时查看诊断。"
    message.startsWith("control:") -> "读取系统状态失败。请在设置中查看诊断。"
    else -> "模块尚未就绪。请稍后重试，持续异常时查看诊断。"
}
