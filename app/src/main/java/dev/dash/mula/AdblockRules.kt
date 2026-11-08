package dev.dash.mula

import org.json.JSONArray
import org.json.JSONObject

data class ConvertedRules(val name: String, val code: String)

/**
 * 把 AdGuard / Adblock 订阅源转换为可在 WebView 内运行的油猴脚本。
 * 只处理「样式隐藏」类规则和广告域名元素隐藏，网络层拦截由 WebView 决定。
 */
object AdblockRules {

    /** AdGuard 中文过滤规则（EasyList China + AdGuard Chinese） */
    const val BUILTIN_SUBSCRIPTION = "https://filters.adtidy.org/extension/ublock/filters/224.txt"

    private const val MAX_HOSTS = 5000
    private const val MAX_GENERIC = 800
    private const val MAX_SITE_RULES = 3000

    fun looksLikeFilterList(text: String): Boolean {
        val head = text.take(6000)
        if (head.contains("==UserScript==")) return false
        if (head.contains("[Adblock", ignoreCase = true)) return true
        var score = 0
        text.lineSequence().take(600).forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("! Title:") || line.startsWith("! Version:") -> score += 2
                line.startsWith("||") && line.contains("^") -> score += 1
                line.contains("##") && !line.startsWith("//") -> score += 1
            }
        }
        return score >= 5
    }

    fun convert(text: String, sourceUrl: String): ConvertedRules? {
        var title = ""
        var version = ""
        val hosts = LinkedHashSet<String>()
        val generic = LinkedHashSet<String>()
        val siteRules = LinkedHashMap<String, MutableList<String>>()
        var siteCount = 0

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("! Title:") -> title = line.removePrefix("! Title:").trim()
                line.startsWith("! Version:") -> version = line.removePrefix("! Version:").trim()
                line.startsWith("||") && line.contains("^") -> {
                    val host = line.removePrefix("||").substringBefore("^").trim()
                    if (host.length in 4..80 && host.contains('.') && !host.contains('/') && hosts.size < MAX_HOSTS) {
                        hosts.add(host)
                    }
                }
                line.contains("##") && !line.contains("#@#") && !line.contains("#?#") &&
                    !line.contains("#$#") && !line.contains("#%#") && !line.contains("$$") -> {
                    val index = line.indexOf("##")
                    val domainPart = line.substring(0, index).trim()
                    val selector = line.substring(index + 2).trim()
                    if (selector.isBlank() || selector.startsWith("+js(") || selector.length > 300) return@forEach
                    if (domainPart.isEmpty()) {
                        if (generic.size < MAX_GENERIC) generic.add(selector)
                    } else {
                        if (domainPart.contains(',') || domainPart.contains('~') || domainPart.length > 80) {
                            return@forEach
                        }
                        if (siteCount < MAX_SITE_RULES) {
                            val list = siteRules.getOrPut(domainPart) { mutableListOf() }
                            if (list.size < 40) {
                                list.add(selector)
                                siteCount++
                            }
                        }
                    }
                }
            }
        }

        if (hosts.isEmpty() && generic.isEmpty() && siteRules.isEmpty()) return null

        val name = title.ifBlank { "去广告订阅" }
        val siteJson = JSONObject()
        siteRules.forEach { (domain, selectors) -> siteJson.put(domain, JSONArray(selectors)) }

        val code = buildString {
            appendLine("// ==UserScript==")
            appendLine("// @name         " + name)
            appendLine("// @namespace    mafari.adblock")
            appendLine("// @version      " + version.ifBlank { "1.0.0" })
            appendLine("// @description  由订阅源转换的去广告规则（共 " + (hosts.size + generic.size + siteCount) + " 条）")
            appendLine("// @match        *://*/*")
            appendLine("// @run-at       document-start")
            appendLine("// @grant        none")
            appendLine("// ==/UserScript==")
            appendLine("(function(){")
            appendLine("  'use strict';")
            appendLine("  if (window.__mafariFilterRules) return;")
            appendLine("  window.__mafariFilterRules = 1;")
            appendLine("  var HOSTS = " + JSONArray(hosts).toString() + ";")
            appendLine("  var GENERIC = " + JSONArray(generic).toString() + ";")
            appendLine("  var SITE = " + siteJson.toString() + ";")
            append(JS_BODY)
            appendLine("})();")
        }
        return ConvertedRules(name = name, code = code)
    }

    private val JS_BODY = """
  var HS = {};
  for (var i = 0; i < HOSTS.length; i++) { HS[HOSTS[i]] = 1; }
  function hideBlocked() {
    var els = document.querySelectorAll('img,iframe,ins,embed,object');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var src = el.getAttribute('src') || el.getAttribute('data-src') || '';
      if (!src) continue;
      var host = '';
      try { host = new URL(src, location.href).hostname.replace(/^www\./, ''); } catch (e) { continue; }
      if (HS[host]) { el.style.setProperty('display', 'none', 'important'); }
    }
  }
  function applyCss() {
    var css = '';
    if (GENERIC.length) { css += GENERIC.join(',') + '{display:none!important}'; }
    var loc = location.hostname.replace(/^www\./, '');
    for (var k in SITE) {
      if (loc === k || loc.slice(-(k.length + 1)) === '.' + k) {
        css += SITE[k].join(',') + '{display:none!important}';
      }
    }
    try {
      var st = document.createElement('style');
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }
  function boot() {
    applyCss();
    hideBlocked();
    try {
      var mo = new MutationObserver(function () { hideBlocked(); });
      mo.observe(document.documentElement || document, { childList: true, subtree: true });
    } catch (e) {}
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
"""
}
