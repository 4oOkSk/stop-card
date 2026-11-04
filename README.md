# Stop Card

按最近任务卡片管理 Android 后台。有效卡片保留时允许运行；最后一张卡移除后，无可见窗口、操作流程或例外保护时，经短复核强行停止。

由 [4oOkSk](https://github.com/4oOkSk) 维护，基于 [burakgon/stop](https://github.com/burakgon/stop) 的 `51c6b61c1988750821edeb36e05831726bcb1f24`，沿用 GPL-3.0。独立包名：`io.github.user4ooksk.stopcard`。

## 当前版本

0.2.2 开发版本，目标为 Android 17，尚待设备验收。完整需求与验收用例见 [设计书](docs/design.md)。
设备步骤与记录要求见 [设备验收](docs/device-tests.md)。

- 全局默认管理普通应用，新装应用自动纳入；用户例外绑定安装身份。
- 核心处理有效卡片和原生强停；跨应用保护采用可见窗口、Activity／Service 生命周期与隐藏后 30 秒兜底。
- 覆盖通知入队、Activity、Service、广播、Provider、Job、闹钟、PendingIntent 和进程启动入口。设备上的实际可用入口显示在设置中；不匹配的能力降级放行。
- 模块独立维护必要角色，系统预装包默认放开；应用页可查看例外原因。
- 默认观察模式；可选恢复辅助监测启动失败、异常重启和模块心跳，核心运行不依赖其心跳。
- 额外保活可选，默认关闭，仅调整 Android 缓存进程范围内的优先级。

模块运行在 `system_server`，不依赖 Shizuku。Root 用于按需配置、诊断和恢复；恢复辅助不参与日常应用判断。

## 安装

需要 Root、Zygisk 和支持目标系统的 LSPosed／Vector。当前仅管理主用户。

1. 安装 APK。在框架中启用 **Stop Card**，作用域只选 **系统框架**，随后重启。
2. 授予 Stop Card Root，完成引导并保持观察模式。
3. 确认卡片核心和配置代次已就绪，可关闭观察模式测试强停；通知、唤醒和保活按各自挂接状态工作。
4. 需要独立恢复监测时，在设置中安装恢复辅助；更新已运行的辅助时按提示重启。

本版更换了包名，不覆盖早期测试包。升级前须在框架中关闭旧模块并重启，避免同时加载两个版本。

系统重启后的历史卡片不自动激活；用户重新打开后才获得本次卡片许可。来源未确认的调用按具体目标临时观察。跨应用流程保留窗口与生命周期保护；隐藏后超时撤销临时许可，再检查卡片、可见性和例外。本版不追踪应用内部结果回调是否执行完成。

强停在模块工作线程完成轻量复核后直接调用系统接口，由 Android 自己管理锁和清理过程。

## 暂停与恢复

首页暂停会取消待执行停止和额外保活。界面不可用时，已安装的恢复辅助提供独立入口：

```sh
adb shell su -c 'sh /data/adb/stop_card_recovery/disable.sh'
```

如模块无法响应，在框架中停用 Stop Card 后重启。设置中的重新准备恢复功能会保留暂停与观察模式，不自动恢复严格拦截。已被系统强停的应用需重新打开，暂停不会自动恢复其推送。

配置与状态位于 `/data/system/stop_card`。控制文件由 Root 持有且仅 Root 可写；模块核对主用户、管理 APK 签名、协议与配置代次。界面仅将模块确认的修改显示为生效。文件访问、SELinux 和独立恢复入口须在目标设备核验。

## 构建

需要 JDK 21、Android SDK 37 与 Build Tools 37。使用平台包目录带小版本号的 SDK 时：

```sh
sdkmanager --channel=3 "platforms;android-37.0" "build-tools;37.0.0"
ln -sfn android-37.0 "$ANDROID_HOME/platforms/android-37"
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :testbed:assembleDebug
```

签名配置从未提交的 `keystore.properties` 读取：

```properties
storeFile=signing/release.p12
storePassword=YOUR_STORE_PASSWORD
keyAlias=release
keyPassword=YOUR_KEY_PASSWORD
```

发布版本使用固定签名，构建时将证书摘要写入模块。未配置签名时不生成可直接分发的签名 release 包；签名未核验时仅观察。APK、源码版本及校验值一并发布。

## 源码

- `app/src/main/java/io/github/user4ooksk/stopcard/policy/`：纯规则核心。
- `app/src/main/java/io/github/user4ooksk/stopcard/module/`：系统接口适配、状态与执行。
- `app/src/main/java/io/github/user4ooksk/stopcard/bridge/`：按需 Root 通信和恢复安装。
- `app/src/main/java/io/github/user4ooksk/stopcard/ui/`：管理界面。
- `app/src/test/`：离线回归测试。
- `testbed/`：A／B／C 三个可控测试包，独立于管理 APK。

日志只保留包名、事件和原因，滚动保留 7 天并限制容量；不记录消息正文或支付参数，无广告或统计。诊断导出含设备运行信息，分享前可自行检查。
