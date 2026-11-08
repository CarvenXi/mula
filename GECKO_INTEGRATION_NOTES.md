# GeckoView 插件接入说明（实验阶段）

## 版本与来源

- SDK：Mozilla GeckoView Stable `157.0.20260910085851`
- Maven：`https://maven.mozilla.org/maven2/`
- 官方快速开始与 API：<https://mozilla.github.io/geckoview/consumer/docs/geckoview-quick-start>
- 当前接入范围：页面渲染、URL/标题/进度、前进后退、刷新、独立标签会话。

## 为什么插件不直接放进主 APK

`:gecko-engine-plugin` 单独把 GeckoView AAR、DEX、资源/Assets 和 ABI 原生库打包为 `gecko-engine.apk`。主 `:app` 不依赖 GeckoView SDK。首次选择 GeckoView 时下载插件并校验 SHA-256。

GeckoView 官方的常规集成方式是把 AAR 作为应用依赖直接打包。Mula 为满足“主 APK 不携带内核、首次选择后按需下载”的架构，采用了独立 APK + DexClassLoader + 插件资源 Context + 原生库目录的动态装载方式。这是 Mula 的定制加载路径，不是 Mozilla Quick Start 中的标准集成方式，因此必须在目标 Android 设备上完成首次启动、页面渲染、软键盘、旋转、进程重启和多标签测试后，才能视为稳定。

## 更新策略

- `gecko-engine-active.apk` 是当前进程使用的插件快照。
- 新下载的 `gecko-engine.apk` 暂存为待激活版本。
- 当前进程继续使用原快照和原生库；进程重启后，待激活版本才提升为活动版本并重新提取原生库。
- 这样避免在运行中的 GeckoRuntime 上替换 DEX、资源或 `.so`。

## 当前能力边界

GeckoView 目前未接入 Mula 的 GM 桥接、油猴脚本注入、下载接管、文件选择器/相机权限委托、网页权限弹窗、全屏媒体 Activity 委托、元素检查和桌面 UA。`EngineCapabilities` 对这些功能显式标记为不支持，不能将 WebView 的能力状态误认为 GeckoView 已实现。

## 构建

```bash
gradle :gecko-engine-plugin:assembleRelease
gradle :app:assembleDebug
```

发布工作流会同时生成：

- `gecko-engine.apk`
- `gecko-engine.apk.sha256`
- `servo-engine.apk`
- `servo-engine.apk.sha256`

主应用默认从 Mula 最新 GitHub Release 下载 Gecko 插件，也可通过 `-PgeckoPluginUrl=...` 与 `-PgeckoPluginSha256=...` 覆盖地址和固定校验值。

## 真机验收清单

1. 首次选择 GeckoView：下载、SHA-256 校验和首次加载成功。
2. 打开 HTTPS 页面：显示内容、标题、URL、进度正确。
3. 输入框：软键盘正常弹出、收起，输入法调整布局正常。
4. 系统返回、网页前进/后退、刷新正常。
5. 连续创建多个标签：每个标签会话互不串页，切换标签后会话可恢复。
6. 首页与网页之间切换、沉浸式状态栏/导航栏和旋转适配正常。
7. 更新插件：当前进程不崩溃，重启后新版本激活。
8. 杀进程重启：GeckoRuntime 和资源/原生库重新初始化成功。

## 子进程服务机制（重要）

GeckoView 的渲染在独立 Android 子进程里：内容进程 `:tabN`、`:gpu`、`:socket`、`:utility`、`:gmplugin`，
由 GeckoView 按固定类名（`org.mozilla.gecko.process.GeckoChildProcessServices$tabN` 等）`bindService` 拉起。
插件 APK 从不被安装，所以这些服务必须满足三点，缺一不可：

1. **主应用 Manifest 声明**：`app/src/main/AndroidManifest.xml` 里声明了 gpu/socket/utility/gmplugin 与 tab0–tab49
   （多声明无副作用）。不设 `isolatedProcess`，因为插件文件在 `filesDir`，isolated 进程读不到。
2. **类可被主应用 ClassLoader 找到**：`MulaApplication` 在 Gecko 子进程里（早于任何 Service 实例化）调用
   `GeckoChildBootstrap.attach`，把插件 dex 前插进主应用 ClassLoader、把插件 so 目录加入原生库搜索路径，
   并让子进程里的 `packageResourcePath / applicationInfo` 指向插件 APK（GeckoView 靠它找 omni.ja、libxul）。
   父进程仍使用 `GeckoPluginManager` 的 DexClassLoader。
3. **Context 包名必须是宿主包名**：插件 Context 的 `getPackageName()` 返回宿主包名，GeckoView 才会去绑
   声明在主应用里的服务。

`GeckoPluginManager.prepare()` 会对照插件 APK 清单校验声明；关键服务缺失时直接报错并回退 WebView。
升级 GeckoView 后若报缺失，用 `apkanalyzer manifest print gecko-engine.apk | grep -A4 "<service"` 对照补齐。
主应用还需要 `ACCESS_WIFI_STATE`、`MODIFY_AUDIO_SETTINGS`（GeckoView 的网络状态/音频，普通权限）。

## 已知限制

- 注入 ClassLoader 依赖非公开 API 反射（`BaseDexClassLoader.pathList` 等），系统大版本升级后需复测。
- 子进程只注入 dex/so，不挂插件 Resources；如果 Gecko 子进程里有读资源的路径，需要再补。
- GeckoView 的宿主 Context 目前是 Application Context：全屏视频/权限弹窗等依赖 Activity 的能力未接入。
- 下拉刷新对 GeckoView 已关闭（宿主拿不到页面滚动位置）。
