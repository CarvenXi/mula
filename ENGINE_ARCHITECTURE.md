# Mafari 多内核架构

## 当前实现

- `engine/BrowserEngineAdapter.kt`：内核统一契约、能力声明和工厂。
- `WebViewEngineAdapter`：Android System WebView 适配器。
- `ServoEngineAdapter`：Servo Android AAR 适配器。
- `BrowserTab`：持有标签页状态，并通过 adapter 路由导航、刷新、停止、前进/后退和 View 获取。
- `BrowserScreen`：通过 `BrowserEngineHost` 挂载当前适配器；页面标题、滚动位置和脚本执行等通用访问也通过 `BrowserTab` 暴露的适配器门面完成。

## 能力边界

WebView 当前支持 Mafari 的用户脚本、下载接管、UA、页内查找、页面元素检查和下拉刷新。Servo AAR 当前只提供基础导航与渲染，因此其 `EngineCapabilities` 对应能力明确标记为不支持。调用方必须检查能力，不应把 WebView 专属功能伪装成 Servo 已支持。

## 添加新内核

1. 实现 `BrowserEngineAdapter`。
2. 定义该内核的 `EngineCapabilities`。
3. 在 `BrowserEngineFactory` 注册创建方法。
4. 增加 `BrowserEngineType` 和设置项，再为该内核提供渲染宿主。

标签页、书签、历史、下载记录、地址栏和 Compose UI 不应直接依赖新内核 SDK。

## Servo 依赖

Servo AAR 在构建时从 Servo 官方 Release 下载，并校验 SHA-256。可通过 `-PservoAarUrl=...` 和 `-PservoAarSha256Url=...` 指定镜像或固定版本。


## 第二阶段：渲染宿主与生命周期收口

- `BrowserEngineHost` 统一承载 Compose 渲染：`BrowserScreen` 不再直接创建 `AndroidView`、调用 Servo Surface 或直接访问 `tab.webView` / `tab.servoView`。
- `BrowserTab.activeEngineAdapter` 是当前活动内核的唯一入口；渲染层按适配器类型选择对应 View 容器。
- WebView 的 Insets、尺寸变化和 `visualViewport` resize 通知收敛到 `BrowserEngineHost`。
- 标签销毁时先从父容器摘除 View，再分别调用适配器 `destroy()`；标签不再重复实现 WebView 的销毁流程。
- 下拉刷新通过 `EngineCapabilities.pullToRefresh` 与适配器滚动位置决定是否可用。

新增内核时，除实现 `BrowserEngineAdapter` 外，还需在 `BrowserEngineHost` 注册对应渲染分支，并为专属能力补充能力声明。


## Runtime engine plugins (Phase 3)

The main `:app` module must not depend on Servo AAR. Servo is packaged by the independent `:servo-engine-plugin` application module and delivered as a signed/hash-pinned APK payload. At runtime `ServoPluginManager` downloads that payload only after the user selects Servo, verifies SHA-256, extracts ABI-native libraries into app-private storage and loads DEX through `DexClassLoader`. `DynamicServoEngineAdapter` isolates Servo API access behind reflection; the Compose host only receives an Android `View`. See `ENGINE_PLUGIN_DISTRIBUTION.md` for the release pipeline.


## GeckoView runtime plugin (Phase 4)

- `:gecko-engine-plugin` packages the official Mozilla GeckoView Stable AAR into a standalone APK. The main `:app` module has no GeckoView dependency.
- The plugin APK is downloaded only after the user selects GeckoView; `GeckoPluginManager` validates the APK structure and SHA-256, extracts ABI native libraries, and creates a resource-aware plugin Context plus a shared `DexClassLoader`.
- `DynamicGeckoEngineAdapter` calls the plugin entry point reflectively. Each Mafari tab owns one `GeckoSession`; all sessions share one process-wide `GeckoRuntime` inside the plugin classloader.
- GeckoView currently exposes core navigation, title, URL, loading progress, and history state. WebView-only script injection, download interception, element inspection, and custom UA remain explicitly unsupported until their Gecko APIs are implemented.
- GeckoView is built against Mozilla's published Stable artifact; override with `-PgeckoViewVersion=<version>` when updating the plugin.
