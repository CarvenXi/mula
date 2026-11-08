package dev.dash.mula

import android.content.Context

/** 用户手动「标记为广告」的隐藏规则，按域名持久化，下次访问同站自动注入。 */
object UserAdRules {

    private const val PREFS = "mafari_ad_rules"

    fun selectors(context: Context, host: String): List<String> {
        if (host.isBlank()) return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(host, emptySet())?.toList().orEmpty()
    }

    fun add(context: Context, host: String, selector: String) {
        if (host.isBlank() || selector.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(host, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(selector)
        prefs.edit().putStringSet(host, current).apply()
    }

    fun clear(context: Context, host: String) {
        if (host.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(host).apply()
    }
}
