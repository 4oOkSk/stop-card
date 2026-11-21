# 构建、发布与设备验收

[返回首页](../README.md) · [运行规则](design.md)

## 工具链与签名

- 依赖与版本定义见 [app/build.gradle.kts](../app/build.gradle.kts) 与 [gradle/libs.versions.toml](../gradle/libs.versions.toml)；使用仓库自带 Gradle Wrapper（JDK 21、Android SDK 37 / Build Tools 37）。
- **SDK 路径坑点**：若安装的平台目录名为 `android-37.0` 且缺少 `android-37`，创建兼容软链 `ln -s android-37.0 "$ANDROID_HOME/platforms/android-37"`。
- **签名强制校验**：复制 [keystore.properties.example](../keystore.properties.example) 为受限本地 `keystore.properties`。**构建会把签名证书摘要写入模块用于核验管理 APK，缺失或不匹配时控制通道无法就绪（模块仅观察）**；覆盖安装须同签名。

## 构建与检查

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease :testbed:assembleDebug
apksigner verify --verbose --print-certs <release-apk>
```

发布前核对提交、APK SHA-256、签名摘要，清除私有路径与设备信息，附带 `SHA256SUMS.txt` 与 `build-info.txt`；若更换签名须在发布说明提醒卸载旧包会清除设置。

## 设备验收

按改动涉及的系统入口选择项目，复用同制品/同环境的有效结果，未测项记“待验证”，环境缺失能力记“能力缺失”。

### 验收前置红线

1. **先确认独立停用可用**：安装恢复辅助或写入紧急停用标记后，在测试机确认独立执行能写入停用标记并锁定暂停，再按界面恢复进入观察模式，然后做故障测试。
2. **严格按顺序验证**：先**暂停模块**记录 Android 原生行为 → 再开**观察模式** → 最后切**执行模式**。
3. 安装专用测试包 A/B/C（`io.github.user4ooksk.stopcard.test.{a,b,c}`，仅用于测试机），查看日志：`adb logcat -v monotonic -s StopCardFixture AndroidRuntime ActivityManager`。

### 核心验证矩阵

| 场景 | 通过判据 |
|---|---|
| 模块就绪与签名 | APK 校验匹配，状态 PID 与当前 `system_server` 一致、心跳更新、`ready=true`、配置代次同步 |
| 界面与例外 | 批量添加/撤销仅还原本次改动且保留后续单改；卸载重装后旧例外与撤销失效 |
| 卡片与强停 | 多文档卡逐张递减，仅移除最后一张且其它保护结束才强停（进程退出且 `stopped`）；划卡重开/暂停使旧停止失效；分屏/气泡退出不破坏 `task_remove` 钩子 |
| 跨应用与桌面入口 | A→B→C 跳转与返回、第三方安装器安装后 Open、延迟后台启动遵循原生规则；服务/绑定销毁后释放保护 |
| 桌面小组件 | 无卡时保持 `app_widget` 保护与刷新；移除小组件并完成删除回调后撤销保护并复核 |
| 后台入口与进程创建 | 未被强停的目标允许正常后台；已被强停且无保护的目标拦截已知后台 Service/广播/通知/进程创建，可见页面与共享 UID 正常放行 |
| 额外保活 | 仅调整预算内有卡缓存进程 `oom_score_adj`；暂停/观察/撤销或内存紧张时恢复系统原生分值 |
| 重启、恢复与长跑 | 重启后历史卡片待激活；紧急停用生效且重启跳过挂钩；签名/状态读取失败暂停管控；24–48 小时无异常泄漏 |

**收尾**：恢复测试前设置与 VPN，移除本次创建的测试卡片与小组件，**卸载 A/B/C 测试包**，清理设备临时脚本与诊断副本，确认模块仍就绪；公开报告仅含脱敏摘要。
