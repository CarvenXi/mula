package dev.dash.mula

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val url: String, val title: String, val time: Long)

class HistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("mafari_history", Context.MODE_PRIVATE)

    val items = mutableStateListOf<HistoryEntry>().apply { addAll(load()) }

    private fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        HistoryEntry(
                            url = obj.optString("url"),
                            title = obj.optString("title"),
                            time = obj.optLong("time"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun record(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        items.removeAll { it.url == url }
        items.add(0, HistoryEntry(url, title.ifBlank { hostOf(url) }, System.currentTimeMillis()))
        while (items.size > 300) items.removeAt(items.lastIndex)
        persist()
    }

    fun clear() {
        items.clear()
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("url", it.url).put("title", it.title).put("time", it.time))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "entries"
    }
}
