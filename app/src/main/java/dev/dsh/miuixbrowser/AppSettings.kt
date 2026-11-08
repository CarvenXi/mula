package dev.dsh.miuixbrowser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("mafari_settings", Context.MODE_PRIVATE)

    var blurEnabled by mutableStateOf(prefs.getBoolean(KEY_BLUR, true))
        private set

    /** Selected browser engine: 0=WebView, 1=Servo plugin, 2=GeckoView plugin. */
    var browserEngine by mutableStateOf(prefs.getInt(KEY_ENGINE, 0))
        private set

    fun updateBrowserEngine(value: Int) {
        browserEngine = value.coerceIn(0, 2)
        prefs.edit().putInt(KEY_ENGINE, browserEngine).apply()
    }

    /** 外部链接打开方式：0=应用内打开 1=交给其他浏览器（询问） */
    var linkOpenMode by mutableStateOf(prefs.getInt(KEY_LINK_MODE, 0))
        private set

    fun updateLinkOpenMode(mode: Int) {
        linkOpenMode = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_LINK_MODE, linkOpenMode).apply()
    }

    /** 地址栏显示：0=网站标题 1=主域名 2=完整域名 */
    var addressDisplay by mutableStateOf(prefs.getInt(KEY_ADDR, 2))
        private set

    /** 标签面板样式：0=文字列表 1=卡片 */
    var tabsStyle by mutableStateOf(prefs.getInt(KEY_TABS, 0))
        private set

    fun updateAddressDisplay(mode: Int) {
        addressDisplay = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_ADDR, addressDisplay).apply()
    }

    fun updateTabsStyle(style: Int) {
        tabsStyle = style.coerceIn(0, 1)
        prefs.edit().putInt(KEY_TABS, tabsStyle).apply()
    }

    /** 搜索引擎：预设索引 + 自定义模板（%s 占位） */
    var searchIndex by mutableStateOf(prefs.getInt(KEY_SEARCH, 0))
        private set

    var customSearch by mutableStateOf(prefs.getString(KEY_SEARCH_CUSTOM, "").orEmpty())
        private set

    val searchTemplate: String
        get() = when {
            searchIndex < 0 -> customSearch.ifBlank { SEARCH_ENGINES[0].template }
            customSearch.isNotBlank() && searchIndex >= SEARCH_ENGINES.size -> customSearch
            else -> SEARCH_ENGINES.getOrNull(searchIndex)?.template ?: SEARCH_ENGINES[0].template
        }

    fun updateSearch(index: Int) {
        searchIndex = index
        prefs.edit().putInt(KEY_SEARCH, index).apply()
    }

    fun updateCustomSearch(template: String) {
        customSearch = template.trim()
        prefs.edit().putString(KEY_SEARCH_CUSTOM, customSearch).apply()
        if (customSearch.isNotBlank()) updateSearch(SEARCH_ENGINES.size)
    }

    /** 0=跟随系统 1=日间 2=夜间 */
    var themeMode by mutableStateOf(prefs.getInt(KEY_THEME, 0))
        private set

    /** 主页壁纸（content:// 或空） */
    var wallpaper by mutableStateOf(prefs.getString(KEY_WALL, "").orEmpty())
        private set

    /** 空串表示跟随系统 WebView 默认 UA */
    var userAgent by mutableStateOf(prefs.getString(KEY_UA, "").orEmpty())
        private set

    fun updateThemeMode(mode: Int) {
        themeMode = mode.coerceIn(0, 2)
        prefs.edit().putInt(KEY_THEME, themeMode).apply()
    }

    fun updateWallpaper(uri: String) {
        wallpaper = uri
        prefs.edit().putString(KEY_WALL, uri).apply()
    }

    fun updateBlur(enabled: Boolean) {
        blurEnabled = enabled
        prefs.edit().putBoolean(KEY_BLUR, enabled).apply()
    }

    /** 页面增强：油猴脚本、去广告规则、长按标记广告 */
    var pageEnhancements by mutableStateOf(prefs.getBoolean(KEY_ENHANCE, true))
        private set

    fun updatePageEnhancements(enabled: Boolean) {
        pageEnhancements = enabled
        prefs.edit().putBoolean(KEY_ENHANCE, enabled).apply()
    }

    fun updateUserAgent(value: String) {
        userAgent = value.trim()
        prefs.edit().putString(KEY_UA, userAgent).apply()
    }

    private companion object {
        const val KEY_BLUR = "blur"
        const val KEY_ENGINE = "browser_engine"
        const val KEY_UA = "ua"
        const val KEY_THEME = "theme_mode"
        const val KEY_SEARCH = "search_index"
        const val KEY_ADDR = "address_display"
        const val KEY_LINK_MODE = "link_open_mode"
        const val KEY_TABS = "tabs_style"
        const val KEY_SEARCH_CUSTOM = "search_custom"
        const val KEY_WALL = "wallpaper"
        const val KEY_ENHANCE = "page_enhancements"
    }
}

data class SearchEngine(val name: String, val template: String, val iconUrl: String = "")

val SEARCH_ENGINES = listOf(
    SearchEngine("百度", "https://www.baidu.com/s?wd=%s", "https://www.baidu.com/favicon.ico"),
    SearchEngine("必应", "https://www.bing.com/search?q=%s", "https://www.bing.com/favicon.ico"),
    SearchEngine("Google", "https://www.google.com/search?q=%s", "https://www.google.com/favicon.ico"),
    SearchEngine("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/favicon.ico"),
)

data class UaPreset(val name: String, val value: String)

/** 常用 UA 预设 */
val UA_PRESETS = listOf(
    UaPreset("跟随系统", ""),
    UaPreset(
        "Android Chrome",
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
    ),
    UaPreset(
        "桌面 Chrome",
        DESKTOP_UA,
    ),
    UaPreset(
        "iPhone Safari",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
    ),
)

const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
