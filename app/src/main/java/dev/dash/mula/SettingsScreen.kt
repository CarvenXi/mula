package dev.dash.mula

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalContext
import androidx.webkit.WebViewCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onPickUa: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenBookmarksHistory: () -> Unit,
    onOpenScripts: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val engineScope = rememberCoroutineScope()
    var engineDownloadState by remember { mutableStateOf("") }
    val servoManager = remember { dev.dash.mula.engine.ServoPluginManager(context) }
    val geckoManager = remember { dev.dash.mula.engine.GeckoPluginManager(context) }
    // Servo 暂不支持 Mafari 的页面增强能力，切换到 Servo 后持久化关闭。
    LaunchedEffect(settings.browserEngine) {
        if (settings.browserEngine != 0 && settings.pageEnhancements) {
            settings.updatePageEnhancements(false)
        }
    }
    var showSearch by remember { mutableStateOf(false) }
    var showWallpaper by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            settings.updateWallpaper(uri.toString())
        }
    }

    val backdrop = rememberLayerBackdrop()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(MiuixTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(top = statusTop + 56.dp)
                    .padding(bottom = 24.dp),
            ) {
            SmallTitle(text = "外观")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    OverlayDropdownPreference(
                        title = "显示模式",
                        items = listOf("跟随系统", "日间", "夜间"),
                        selectedIndex = settings.themeMode.coerceIn(0, 2),
                        onSelectedIndexChange = { settings.updateThemeMode(it) },
                    )
                    SettingsRow(
                        title = "主页壁纸",
                        summary = if (settings.wallpaper.isBlank()) "未设置" else "已设置",
                        onClick = { showWallpaper = true },
                    )
                    SwitchPreference(
                        title = "模糊效果",
                        summary = "关闭后底栏、收藏卡片和页面顶栏不再模糊",
                        checked = settings.blurEnabled,
                        onCheckedChange = { settings.updateBlur(it) },
                    )
                }

                SmallTitle(text = "布局")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    OverlayDropdownPreference(
                        title = "地址栏显示",
                        items = listOf("网站标题", "主域名", "完整域名"),
                        selectedIndex = settings.addressDisplay.coerceIn(0, 2),
                        onSelectedIndexChange = { settings.updateAddressDisplay(it) },
                    )
                    OverlayDropdownPreference(
                        title = "标签栏样式",
                        items = listOf("文字显示", "卡片显示"),
                        selectedIndex = settings.tabsStyle.coerceIn(0, 1),
                        onSelectedIndexChange = { settings.updateTabsStyle(it) },
                    )
                }

                SmallTitle(text = "浏览")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    OverlayDropdownPreference(
                        title = "外部链接打开方式",
                        items = listOf("应用内打开", "自定义标签页", "其他浏览器"),
                        selectedIndex = settings.linkOpenMode.coerceIn(0, 2),
                        onSelectedIndexChange = { settings.updateLinkOpenMode(it) },
                    )
                    SettingsRow(
                        title = "搜索引擎",
                        summary = SEARCH_ENGINES.getOrNull(settings.searchIndex)?.name ?: "自定义",
                        onClick = { showSearch = true },
                    )
                    SettingsRow(
                        title = "自定义 UA",
                        summary = uaSummary(settings.userAgent),
                        onClick = onPickUa,
                    )
                    SettingsRow(
                        title = "书签与历史",
                        summary = "管理收藏的书签与浏览记录",
                        onClick = onOpenBookmarksHistory,
                    )
                    SettingsRow(
                        title = "下载",
                        summary = "查看已下载的文件",
                        onClick = onOpenDownloads,
                    )
                }

                SmallTitle(text = "高级")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    SettingsRow(
                        title = "脚本管理",
                        summary = "用户脚本 / 油猴脚本",
                        onClick = onOpenScripts,
                    )
                    OverlayDropdownPreference(
                        title = "浏览器内核",
                        items = listOf("Android WebView", "Servo（实验性）", "GeckoView（实验性）"),
                        selectedIndex = settings.browserEngine,
                        onSelectedIndexChange = { selected ->
                            if (selected == 0) {
                                settings.updateBrowserEngine(0)
                                engineDownloadState = ""
                            } else if (selected == 1) {
                                if (servoManager.isInstalled()) {
                                    settings.updateBrowserEngine(1)
                                    settings.updatePageEnhancements(false)
                                } else engineScope.launch {
                                    val url = BuildConfig.SERVO_PLUGIN_APK_URL
                                    val sha = BuildConfig.SERVO_PLUGIN_SHA256
                                    if (url.isBlank()) engineDownloadState = "Servo 插件发布地址未配置" else {
                                        engineDownloadState = "正在下载 Servo 内核…"
                                        runCatching { servoManager.install(url, sha) }
                                            .onSuccess { settings.updateBrowserEngine(1); settings.updatePageEnhancements(false); engineDownloadState = "Servo 内核已安装" }
                                            .onFailure { engineDownloadState = "下载失败：${it.message ?: "未知错误"}" }
                                    }
                                }
                            } else if (selected == 2) {
                                engineScope.launch {
                                    val url = BuildConfig.GECKO_PLUGIN_APK_URL
                                    val sha = BuildConfig.GECKO_PLUGIN_SHA256
                                    if (!geckoManager.isInstalled() && url.isBlank()) {
                                        engineDownloadState = "GeckoView 插件发布地址未配置"
                                    } else {
                                        engineDownloadState = if (geckoManager.isInstalled()) "正在准备 GeckoView 内核…" else "正在下载 GeckoView 内核…"
                                        runCatching {
                                            if (!geckoManager.isInstalled()) geckoManager.install(url, sha)
                                            geckoManager.prepare()
                                        }
                                            .onSuccess {
                                                settings.updateBrowserEngine(2)
                                                settings.updatePageEnhancements(false)
                                                engineDownloadState = "GeckoView 内核已就绪"
                                            }
                                            .onFailure { engineDownloadState = "准备失败：${it.message ?: "未知错误"}" }
                                    }
                                }
                            }
                        },
                    )
                    if (engineDownloadState.isNotBlank()) {
                        Text(
                            text = engineDownloadState,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            fontSize = 12.sp,
                        )
                    }
                    SettingsRow(
                        title = "Servo 内核管理",
                        summary = if (servoManager.isInstalled()) "已安装 · ${(servoManager.installedSize() / (1024 * 1024))} MB · 点击检查更新/重新下载" else "未安装 · 从 Mafari 发布页下载",
                        onClick = {
                            engineScope.launch {
                                val url = BuildConfig.SERVO_PLUGIN_APK_URL
                                val sha = BuildConfig.SERVO_PLUGIN_SHA256
                                if (url.isBlank()) {
                                    engineDownloadState = "Servo 插件发布地址未配置"
                                } else {
                                    engineDownloadState = "正在下载/更新 Servo 内核…"
                                    runCatching { servoManager.install(url, sha) }
                                        .onSuccess { engineDownloadState = "Servo 内核已安装/更新" }
                                        .onFailure { engineDownloadState = "更新失败：${it.message ?: "未知错误"}" }
                                }
                            }
                        },
                    )
                    SettingsRow(
                        title = "GeckoView 内核管理",
                        summary = if (geckoManager.isInstalled()) "已安装 · ${(geckoManager.installedSize() / (1024 * 1024))} MB · 点击重新下载/更新" else "未安装 · 首次选择 GeckoView 时按需下载",
                        onClick = {
                            engineScope.launch {
                                val url = BuildConfig.GECKO_PLUGIN_APK_URL
                                val sha = BuildConfig.GECKO_PLUGIN_SHA256
                                if (url.isBlank()) engineDownloadState = "GeckoView 插件发布地址未配置" else {
                                    engineDownloadState = "正在下载/更新 GeckoView 内核…"
                                    runCatching { geckoManager.install(url, sha) }
                                        .onSuccess { engineDownloadState = "GeckoView 内核已更新，重启 Mafari 后生效" }
                                        .onFailure { engineDownloadState = "更新失败：${it.message ?: "未知错误"}" }
                                }
                            }
                        },
                    )
                    if (settings.browserEngine != 0) {
                        // 非 WebView 内核暂不支持 Mafari 页面增强，避免显示可用开关。
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "页面增强",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (settings.browserEngine == 1) "Servo 内核暂不支持，已自动关闭" else "GeckoView 内核暂不支持，已自动关闭",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    } else {
                        SwitchPreference(
                            title = "页面增强",
                            summary = "关闭后不注入油猴脚本、去广告规则和长按标记",
                            checked = settings.pageEnhancements,
                            onCheckedChange = { settings.updatePageEnhancements(it) },
                        )
                    }
                }

                SmallTitle(text = "关于")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    SettingsRow(
                        title = "关于 Mafari",
                        summary = "版本 " + BuildConfig.VERSION_NAME,
                        onClick = onOpenAbout,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp + statusTop)
                    .progressiveTopBar(
                        backdrop = backdrop,
                        tint = MiuixTheme.colorScheme.surface.copy(alpha = 0.62f),
                        enabled = settings.blurEnabled,
                    ),
            ) {
                SmallTopAppBar(
                    title = "设置",
                    color = Color.Transparent,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                    defaultWindowInsetsPadding = false,
                    modifier = Modifier.statusBarsPadding(),
                )
            }
        }
    }

    SearchSheet(
        show = showSearch,
        current = settings.searchIndex,
        custom = settings.customSearch,
        onDismiss = { showSearch = false },
        onPick = {
            settings.updateSearch(it)
            showSearch = false
        },
        onCustom = {
            settings.updateCustomSearch(it)
            showSearch = false
        },
    )

    if (showWallpaper) {
        OverlayDialog(
            show = true,
            title = "主页壁纸",
            onDismissRequest = { showWallpaper = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "选择壁纸",
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showWallpaper = false
                            picker.launch(arrayOf("image/*"))
                        }
                        .padding(horizontal = 6.dp, vertical = 12.dp),
                )
                Text(
                    text = "清除壁纸",
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showWallpaper = false
                            settings.updateWallpaper("")
                        }
                        .padding(horizontal = 6.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, summary: String, onClick: () -> Unit) {
    ArrowPreference(
        title = title,
        summary = summary,
        onClick = onClick,
    )
}

@Composable
internal fun webViewVersion(): String? {
    val context = LocalContext.current
    return remember(context) {
        runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull()
    }
}

private const val PROJECT_URL = "https://github.com/rosenii/mafari"
private const val RELEASE_API = "https://api.github.com/repos/rosenii/mafari/releases/latest"

@Composable
internal fun CheckUpdateButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(
            text = if (checking) "检查中…" else "检查更新",
            onClick = {
                if (checking) return@TextButton
                checking = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { fetchLatestVersion() }
                    checking = false
                    if (result == null) {
                        Toast.makeText(context, "检查失败，请稍后再试", Toast.LENGTH_SHORT).show()
                    } else {
                        val current = BuildConfig.VERSION_NAME.removePrefix("v")
                        val latest = result.first.removePrefix("v")
                        if (latest == current) {
                            Toast.makeText(context, "已是最新版本 $current", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "发现新版本 $latest", Toast.LENGTH_LONG).show()
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(result.second)))
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun fetchLatestVersion(): Pair<String, String>? = runCatching {
    val connection = (java.net.URL(RELEASE_API).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("User-Agent", "mafari")
        setRequestProperty("Accept", "application/vnd.github+json")
    }
    try {
        if (connection.responseCode !in 200..299) return@runCatching null
        val text = connection.inputStream.bufferedReader().readText()
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?: return@runCatching null
        val url = Regex("\"html_url\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?: PROJECT_URL + "/releases"
        tag to url
    } finally {
        connection.disconnect()
    }
}.getOrNull()

private fun uaSummary(ua: String): String = when {
    ua.isBlank() -> "跟随系统"
    ua == DESKTOP_UA -> "桌面 Chrome"
    else -> ua.take(48) + if (ua.length > 48) "…" else ""
}

@Composable
private fun SearchSheet(
    show: Boolean,
    current: Int,
    custom: String,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onCustom: (String) -> Unit,
) {
    var text by remember(show) { mutableStateOf(custom) }
    OverlayBottomSheet(
        show = show,
        title = "搜索引擎",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            SEARCH_ENGINES.forEachIndexed { index, engine ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(index) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = rememberSearchIcon(engine.iconUrl)
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = engine.name,
                        fontSize = 16.sp,
                        color = if (index == current) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = "自定义（用 %s 代表关键词）",
                singleLine = true,
            )
            Spacer(Modifier.height(14.dp))
            TextButton(
                text = "保存自定义",
                onClick = { onCustom(text) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
