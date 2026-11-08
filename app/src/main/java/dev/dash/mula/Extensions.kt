package dev.dash.mula

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UserScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val enabled: Boolean = true,
    /** 油猴 @match / @include 规则，逗号分隔；空表示所有站点 */
    val match: String = "",
    /** 从 URL 导入时的来源，方便更新 */
    val sourceUrl: String = "",
    /** 油猴 @run-at，默认 document-end */
    val runAt: String = "document-end",
    /** 油猴 @icon，脚本列表左侧显示 */
    val icon: String = "",
    /** 非空表示这是去广告规则订阅，显示在「内置脚本」分组 */
    val subscriptionUrl: String = "",
) {
    fun matches(url: String): Boolean {
        val patterns = match.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (patterns.isEmpty()) return true
        return patterns.any { pattern ->
            val escaped = pattern.replace(".", "\\.").replace("*", ".*")
            runCatching { Regex("^$escaped$") }.getOrNull()?.containsMatchIn(url) == true
        }
    }
}

/** 从油猴元数据里抽 @name / @match / @run-at */
fun parseUserScript(code: String): Triple<String, String, String> {
    val name = Regex("@name\\s+(.+)").find(code)?.groupValues?.get(1)?.trim().orEmpty()
    val matches = Regex("@(?:match|include)\\s+(.+)").findAll(code)
        .map { it.groupValues[1].trim() }
        .joinToString(",")
    val runAt = Regex("@run-at\\s+(.+)").find(code)?.groupValues?.get(1)?.trim()
        ?.substringBefore(" ")?.trim() ?: "document-end"
    return Triple(name, matches, runAt)
}

class ExtensionStore(context: Context) {

    private val prefs = context.getSharedPreferences("mafari_ext", Context.MODE_PRIVATE)

    val items = mutableStateListOf<UserScript>().apply { addAll(load()) }

    private fun load(): List<UserScript> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        UserScript(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            code = obj.optString("code"),
                            enabled = obj.optBoolean("enabled", true),
                            match = obj.optString("match"),
                            sourceUrl = obj.optString("sourceUrl"),
                            runAt = obj.optString("runAt", "document-end"),
                            icon = obj.optString("icon"),
                            subscriptionUrl = obj.optString("subscriptionUrl"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(
        name: String,
        code: String,
        match: String = "",
        sourceUrl: String = "",
        subscriptionUrl: String = "",
    ) {
        val (parsedName, parsedMatch, parsedRunAt) = parseUserScript(code)
        items.add(
            UserScript(
                name = name.ifBlank { parsedName.ifBlank { "未命名脚本" } },
                code = code,
                match = match.ifBlank { parsedMatch },
                sourceUrl = sourceUrl,
                runAt = parsedRunAt,
                icon = UserscriptEngine.parse(code).icon,
                subscriptionUrl = subscriptionUrl,
            ),
        )
        persist()
    }

    fun update(id: String, name: String, code: String, enabled: Boolean, match: String = "") {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        val (parsedName, parsedMatch, parsedRunAt) = parseUserScript(code)
        items[index] = items[index].copy(
            name = name.ifBlank { parsedName },
            code = code,
            enabled = enabled,
            match = match.ifBlank { parsedMatch },
            runAt = parsedRunAt,
            icon = UserscriptEngine.parse(code).icon.ifBlank { items[index].icon },
        )
        persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        items[index] = items[index].copy(enabled = enabled)
        persist()
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("code", it.code)
                    .put("enabled", it.enabled)
                    .put("match", it.match)
                    .put("sourceUrl", it.sourceUrl)
                    .put("runAt", it.runAt)
                    .put("icon", it.icon)
                    .put("subscriptionUrl", it.subscriptionUrl),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "scripts"
    }
}
