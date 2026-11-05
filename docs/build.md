# 构建与发布

[返回首页](../README.md) · [运行规则](design.md) · [设备验收](device-tests.md)

## 准备工具链

需要 JDK 21、Android SDK 37 和 Build Tools 37。使用仓库自带的 Gradle Wrapper。

版本定义见 [app/build.gradle.kts](../app/build.gradle.kts)。依赖版本见 [libs.versions.toml](../gradle/libs.versions.toml)。

安装 SDK：

```sh
sdkmanager --channel=3 "platforms;android-37.0" "build-tools;37.0.0"
```

如果平台目录名是 `android-37.0`，且 `android-37` 不存在，创建兼容链接：

```sh
ln -s android-37.0 "$ANDROID_HOME/platforms/android-37"
```

## 配置签名

1. 准备自己的签名密钥，并保存备份。
2. 将 [keystore.properties.example](../keystore.properties.example) 复制为仓库根目录下的 `keystore.properties`。
3. 填写密钥路径、别名和密码。
4. 保持密钥与填写后的配置文件不进入版本控制。

构建会把签名证书摘要写入模块。模块用该摘要核验管理 APK。没有配置可信签名时，模块只观察。

用于安装验收的 release APK 必须签名。覆盖安装需要与已安装版本使用相同签名；自建密钥不能覆盖官方发布包。

## 构建与检查

执行单元测试，并构建发布包和测试包：

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease :testbed:assembleDebug
```

输出位置：

| 内容 | 目录 |
|---|---|
| 主应用 APK | `app/build/outputs/apk/release/` |
| A、B、C 测试 APK | `testbed/build/outputs/apk/` |
| 单元测试报告 | `app/build/reports/tests/testDebugUnitTest/` |

测试通过后，使用 SDK 中的 `apksigner verify --verbose --print-certs` 检查 APK 签名。再按[设备验收](device-tests.md)检查受本次修改影响的功能。

## 发布

1. 确认版本号、版本代码和 Git 提交。
2. 确认 APK 来自该源码，记录 APK 的 SHA-256。
3. 记录签名证书摘要、工具链和已执行的测试。
4. 检查源码、附件和发布说明，移除私人路径、设备信息和凭据。
5. 创建版本标签，并发布 APK、`SHA256SUMS.txt` 和 `build-info.txt`。
6. 下载发布附件，核对文件和校验值。

发布说明只写本版改动、实际验证范围和升级步骤。签名变化时，明确说明是否需要卸载旧包。卸载会清除应用设置。

## 源码目录

| 目录 | 内容 |
|---|---|
| `app/src/main/java/io/github/user4ooksk/stopcard/policy/` | 许可与强停决策规则。 |
| `app/src/main/java/io/github/user4ooksk/stopcard/module/` | 系统适配、状态和执行。 |
| `app/src/main/java/io/github/user4ooksk/stopcard/bridge/` | Root 通信与恢复安装。 |
| `app/src/main/java/io/github/user4ooksk/stopcard/ui/` | 管理界面。 |
| `app/src/test/` | 离线单元测试。 |
| `testbed/` | 可控测试应用。 |
