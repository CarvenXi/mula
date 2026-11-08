# Mula 按需内核插件分发

## 目标

主 APK 不依赖 Servo AAR，也不携带 Servo DEX/so。用户在设置中选择 Servo 时，主应用才下载独立的 `servo-engine.apk`，校验 SHA-256 后放入应用私有目录，通过 `DexClassLoader` 加载。

## 构建与发布

GitHub Actions 会自动构建并发布 Servo 插件：

- `:servo-engine-plugin:assembleRelease` 生成独立插件 APK。
- 工作流复制为 `servo-engine.apk`，并生成 `servo-engine.apk.sha256`。
- 推送 `v*` 标签时，两个文件会与 Mula APK 一起上传到 GitHub Release。
- 主应用默认使用 `https://github.com/CarvenXi/mula/releases/download/engine-plugins/servo-engine.apk`。
- 安装时优先使用构建时注入的 `servoPluginSha256`；未注入时，自动读取同地址追加 `.sha256` 的校验文件。

本地构建时仍可覆盖发布地址和固定校验值：

```bash
./gradlew :app:assembleRelease \
  -PservoPluginUrl=https://your-host.example/releases/servo-engine.apk \
  -PservoPluginSha256=<64位SHA256>
```

### 自动构建规则（`.github/workflows/plugins.yml`）

插件随代码变动自动构建，任一路径变动都会触发：`gecko-engine-plugin/**`、`servo-engine-plugin/**`、
主应用里的反射适配层 `app/src/main/java/dev/dash/mula/engine/**`、根 Gradle 配置、`gradle/**`（版本目录/Wrapper）、`scripts/**`、工作流本身。

- push 到 `main` 或推送 `v*` 标签：构建 Gecko / Servo 两个插件，并滚动发布到固定标签 `engine-plugins` 的 Release（pre-release）。
- Pull Request：只构建并上传 artifact 做验证，不发布。
- 手动运行（workflow_dispatch）：可选择是否发布。
- 未配置 `KEYSTORE_BASE64` 时（含 fork 的 PR）使用 debug 签名，可构建、可安装。

主应用已安装的插件不会因此自动替换：设置里点「内核管理」重新下载，或在应用内加自动检查更新。

## 运行流程

- WebView 是默认内核，无需下载。
- 选择 Servo：先检查私有目录是否已有插件；没有则下载、校验 APK、提取当前 ABI 的 `.so`，再由 `DexClassLoader` 加载。
- 下载失败或校验失败时，不更改已选择的内核，用户仍可使用 WebView。
- 插件卸载只删除 Mula 私有目录中的文件，不涉及 Android 系统安装包。

## 安全与兼容性

- 仅允许 HTTPS 下载；优先使用构建时固定的 SHA-256，未固定时从同一可信 GitHub Release 下载 `.sha256` 文件并校验。生产环境建议固定 SHA-256。
- 插件 APK 不作为 Android 应用安装；它只作为代码容器由 Mula 私有 ClassLoader 加载。
- 当前插件构建面向 `arm64-v8a` / `armeabi-v7a`，最终可用 ABI 取决于 Servo AAR 实际包含的 JNI 库。
- Android 动态代码加载受平台安全策略约束。插件只能来自可信发布源；不要允许用户任意导入 APK/JAR。
- Servo AAR 的构造函数与回调 API 仍需用目标 Servo release 实机验证。反射适配器刻意隔离 API，但无法替代真实设备测试。


## GeckoView plugin

The independent `:gecko-engine-plugin` application module packages `org.mozilla.geckoview:geckoview-stable` (default version `157.0.20260910085851`) into `gecko-engine.apk`. Release workflows publish `gecko-engine.apk` and `gecko-engine.apk.sha256`. Mula downloads it lazily from the latest release after GeckoView is selected. Unlike a plain DEX plugin, GeckoView also needs its compiled Android resources/assets and native libraries; `GeckoPluginManager` therefore mounts the plugin APK resources into a plugin Context and extracts ABI libraries before reflective initialization.
