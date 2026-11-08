package dev.dash.mula

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.WorldClock
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Translate

enum class MenuAction(val id: String, val label: String, val icon: ImageVector) {
    NewTab("new_tab", "新建标签", MiuixIcons.Medium.Add),
    Desktop("desktop", "桌面版", MiuixIcons.Medium.GridView),
    Find("find", "页内查找", MiuixIcons.Medium.Search),
    Translate("translate", "翻译", MiuixIcons.Medium.Translate),
    Favorite("favorite", "收藏", MiuixIcons.Medium.Favorites),
    Share("share", "分享", MiuixIcons.Medium.Share),
    Refresh("refresh", "刷新", MiuixIcons.Medium.Refresh),
    CopyLink("copy", "复制链接", MiuixIcons.Medium.Copy),
    External("external", "外部打开", MiuixIcons.Medium.Link),
    Settings("settings", "设置", MiuixIcons.Medium.Settings),
    Home("home", "主页", MiuixIcons.Medium.Sidebar),
    Downloads("downloads", "下载", MiuixIcons.Medium.Download),
    Extensions("extensions", "脚本", MiuixIcons.Medium.Edit),
    History("history", "历史", MiuixIcons.Medium.WorldClock),
    CloseTab("close", "关闭标签", MiuixIcons.Medium.Close),
}

/** 更多面板的图标顺序，可拖动排序并持久化 */
class MenuOrderStore(context: Context) {

    private val prefs = context.getSharedPreferences("mula_menu", Context.MODE_PRIVATE)

    val order = mutableStateListOf<MenuAction>()

    init {
        val saved = prefs.getString(KEY, null)
        val ids = saved?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val restored = ids.mapNotNull { id -> MenuAction.entries.firstOrNull { it.id == id } }
        order.addAll(restored)
        MenuAction.entries.forEach { if (it !in order) order.add(it) }
    }

    fun move(from: Int, to: Int) {
        if (from !in order.indices) return
        val target = to.coerceIn(0, order.lastIndex)
        if (from == target) return
        val item = order.removeAt(from)
        order.add(target, item)
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY, order.joinToString(",") { it.id }).apply()
    }

    private companion object {
        const val KEY = "order"
    }
}
