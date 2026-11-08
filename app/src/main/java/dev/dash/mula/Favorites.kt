package dev.dash.mula

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import android.text.Html

data class Favorite(val name: String, val url: String)

/** 主页收藏（书签）的增删改 + 本地持久化。 */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences("mula", Context.MODE_PRIVATE)

    val items = mutableStateListOf<Favorite>().apply {
        addAll(load())
    }

    private fun load(): List<Favorite> {
        val raw = prefs.getString(KEY, null) ?: return defaults()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(Favorite(obj.optString("name"), obj.optString("url")))
                }
            }
        }.getOrDefault(defaults())
    }

    private fun defaults(): List<Favorite> = listOf(
        Favorite("百度", "https://www.baidu.com"),
        Favorite("知乎", "https://www.zhihu.com"),
        Favorite("B站", "https://www.bilibili.com"),
        Favorite("GitHub", "https://github.com"),
        Favorite("微博", "https://weibo.com"),
        Favorite("淘宝", "https://www.taobao.com"),
    )

    fun add(name: String, url: String) {
        if (url.isBlank()) return
        val fixed = toUrl(url)
        items.add(Favorite(name.ifBlank { hostOf(fixed) }, fixed))
        persist()
    }

    fun update(index: Int, name: String, url: String) {
        if (index !in items.indices || url.isBlank()) return
        val fixed = toUrl(url)
        items[index] = Favorite(name.ifBlank { hostOf(fixed) }, fixed)
        persist()
    }

    fun remove(index: Int) {
        if (index in items.indices) {
            items.removeAt(index)
            persist()
        }
    }

    /** 导入 Netscape Bookmark HTML（Chrome / Firefox / Via 通用导出格式）。 */
    fun importHtml(html: String): Int {
        val anchor = Regex("(?is)<a\\b([^>]*)>(.*?)</a>")
        val imported = mutableListOf<Favorite>()
        anchor.findAll(html).forEach { match ->
            val attrs = match.groupValues[1]
            val rawHref = Regex("(?i)href\\s*=\\s*[\"']([^\"']+)[\"']")
                .find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            val href = Html.fromHtml(rawHref, Html.FROM_HTML_MODE_LEGACY).toString()
            if (href.isBlank() || !(href.startsWith("http://") || href.startsWith("https://"))) return@forEach
            val rawName = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            val name = Html.fromHtml(rawName, Html.FROM_HTML_MODE_LEGACY).toString().ifBlank { hostOf(href) }
            imported += Favorite(name, href)
        }
        if (imported.isEmpty()) return 0
        val existing = items.map { it.url }.toMutableSet()
        val unique = imported.filter { existing.add(it.url) }
        items.addAll(unique)
        persist()
        return unique.size
    }

    /** 导出为 Netscape Bookmark HTML，Chrome / Firefox / Via 均可识别。 */
    fun exportHtml(): String = buildString {
        appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        appendLine("<TITLE>Mula Bookmarks</TITLE>")
        appendLine("<H1>Bookmarks</H1>")
        appendLine("<DL><p>")
        items.forEach { item ->
            val title = item.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            val url = item.url.replace("&", "&amp;").replace("\"", "&quot;")
            appendLine("<DT><A HREF=\"$url\">$title</A>")
        }
        appendLine("</DL><p>")
    }

    fun reset() {
        items.clear()
        items.addAll(defaults())
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("name", it.name).put("url", it.url))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "favorites"
    }
}
