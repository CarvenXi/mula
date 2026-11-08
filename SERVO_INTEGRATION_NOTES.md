# Servo 双内核集成说明

本项目不再要求把 Servo AAR 提交到 Git。Gradle 会在编译前自动从 Servo 官方 GitHub Release 下载 `servo-aarch64-android.aar`，并下载同名 `.sha256` 文件进行完整性校验。

## 使用方式
1. 打开设置 -> 浏览 -> 浏览器内核。
2. 选择 Android WebView 或 Servo（实验性）。
3. 切换会将当前标签页的 URL 在新内核中重新打开；不会迁移旧内核的完整历史记录或页面运行状态。
4. 新建标签页默认使用当前设置的内核。

## 当前边界
Servo AAR 目前提供的 Android API 只暴露基础导航、标题/URL/加载状态和 Surface 渲染回调。以下 Mula 功能仍由 WebView 实现，切换到 Servo 后暂不可用或不会生效：
- Tampermonkey / 用户脚本与 document-start 注入
- Mula 页面元素检查、广告元素长按标记
- WebView DownloadListener 下载接管
- WebView UA / 桌面站点 UA 切换
- WebView 页内查找
- WebAuthn / Passkey 的 WebView 专属配置
- WebView 的下拉滚动位置检测与动态底栏联动

Servo 当前只包含 `arm64-v8a` 原生库，因此目标设备必须支持 arm64。AAR 的 `minSdkVersion` 为 29，项目当前 minSdk 33 满足要求。

## 构建
项目仍使用原有 Gradle Wrapper、AGP、Kotlin 和 Android SDK 配置。需要安装项目要求的 Android SDK 37 / Build Tools 36.0.0，并使用 JDK 17 或兼容版本。

首次执行 `./gradlew :app:assembleDebug` 时，Gradle 会自动下载 Servo AAR 到 `app/build/generated/servo/`，校验成功后再进入编译流程。该文件位于 `build/` 目录，不需要也不应该提交到 Git。

默认下载地址：
`https://github.com/servo/servo/releases/latest/download/servo-aarch64-android.aar`

默认校验地址为 AAR 下载地址追加 `.sha256`。如需使用镜像或固定版本，可通过 Gradle 属性或环境变量覆盖：

- `-PservoAarUrl=https://example.com/servo-aarch64-android.aar`
- `-PservoAarSha256Url=https://example.com/servo-aarch64-android.aar.sha256`
- 或设置环境变量 `SERVO_AAR_URL`、`SERVO_AAR_SHA256_URL`

清理构建目录后会重新下载。若希望不清理目录、强制检查并重新下载最新 AAR，可执行：
`./gradlew :app:assembleDebug -PrefreshServoAar=true`
