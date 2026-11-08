package dev.dash.mula

import org.json.JSONObject

/** 油猴脚本元数据（覆盖常用键） */
data class UserscriptMeta(
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val runAt: String = "document-idle",
    val noFrames: Boolean = false,
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val icon: String = "",
)

/**
 * Userscript 引擎：元数据解析 + URL 匹配 + 沙箱包装 + GM API。
 * document-start 脚本由 AndroidX WebKit 的 addDocumentStartJavaScript 注入，
 * 其余脚本在页面加载完成后注入。
 */
object UserscriptEngine {

    /** 解析 ==UserScript== 区块 */
    fun parse(code: String): UserscriptMeta {
        val block = Regex("==UserScript==([\\s\\S]*?)==/UserScript==").find(code)?.groupValues?.get(1)
            ?: code.take(4000)
        val values = mutableMapOf<String, MutableList<String>>()
        block.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("//").trim().removePrefix("/*").trim()
            val m = Regex("^@([\\w:-]+)\\s+(.+)$").find(line) ?: return@forEach
            values.getOrPut(m.groupValues[1].lowercase()) { mutableListOf() }
                .add(m.groupValues[2].trim())
        }
        fun first(key: String) = values[key]?.firstOrNull()?.trim().orEmpty()
        return UserscriptMeta(
            name = first("name"),
            version = first("version"),
            description = first("description"),
            runAt = first("run-at").ifBlank { "document-idle" }.substringBefore(" "),
            noFrames = values["noframes"] != null,
            matches = values["match"].orEmpty(),
            includes = values["include"].orEmpty(),
            excludes = values["exclude"].orEmpty(),
            grants = values["grant"].orEmpty(),
            requires = values["require"].orEmpty(),
            icon = first("icon"),
        )
    }

    /** 通配符转正则：只有 * 是通配，其余字符全部转义 */
    private fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        pattern.forEach { c ->
            if (c == '*') sb.append(".*") else sb.append(Regex.escape(c.toString()))
        }
        sb.append("$")
        return Regex(sb.toString())
    }

    fun matchesUrl(meta: UserscriptMeta, url: String): Boolean {
        if (url.isBlank()) return false
        if (meta.excludes.any { globToRegex(it).matches(url) }) return false
        if (meta.matches.isEmpty() && meta.includes.isEmpty()) return true
        if (meta.matches.any { globToRegex(it).matches(url) }) return true
        return meta.includes.any { globToRegex(it).matches(url) }
    }

    /** 生成注入用的完整源码：运行时再判断 URL，命中才执行 */
    fun buildSource(script: UserScript, requireCode: Map<String, String> = emptyMap()): String {
        val meta = parse(script.code)
        val metaJson = JSONObject().apply {
            put("id", script.id)
            put("name", script.name.ifBlank { meta.name })
            put("runAt", script.runAt)
            put("match", meta.matches.joinToString("\n"))
            put("include", meta.includes.joinToString("\n"))
            put("exclude", meta.excludes.joinToString("\n"))
            put("grant", meta.grants.joinToString("\n"))
        }.toString()

        val requires = meta.requires.mapNotNull { requireCode[it] }.joinToString("\n")
        val body = script.code.replace("</script", "<\\/script")

        return """
(function(){
  "use strict";
  var META = $metaJson;

  // ---------- URL 匹配（* 任意字符） ----------
  function globToRe(p){
    var out = '^';
    for (var i = 0; i < p.length; i++){
      var c = p.charAt(i);
      if (c === '*') { out += '.*'; }
      else { out += c.replace(/[.*+?^${'$'}(){}|\[\]\\]/g, '\\${'$'}&'); }
    }
    return new RegExp(out + '${'$'}');
  }
  function matchUrl(url, matches, includes, excludes){
    var i;
    for (i = 0; i < excludes.length; i++){ if (globToRe(excludes[i]).test(url)) return false; }
    if (!matches.length && !includes.length) return true; // 没有声明匹配规则：全站执行
    for (i = 0; i < matches.length; i++){ if (globToRe(matches[i]).test(url)) return true; }
    for (i = 0; i < includes.length; i++){ if (globToRe(includes[i]).test(url)) return true; }
    return false;
  }
  function split(s){ return s ? s.split('\n').filter(Boolean) : []; }
  if (!matchUrl(location.href, split(META.match), split(META.include), split(META.exclude))) return;

  var RAN = window.__mulaRan || (window.__mulaRan = {});
  if (RAN[META.id]) return;
  RAN[META.id] = 1;

  // ---------- GM API ----------
  var bridge = window.MulaGM || null;
  var xhrCbs = window.__mulaXhrCallbacks || (window.__mulaXhrCallbacks = {});
  window.__mulaXhrDone = function(reqId, status, text, finalUrl){
    var cb = xhrCbs[reqId];
    if (!cb) return;
    delete xhrCbs[reqId];
    var resp = { status: status, responseText: text, response: text, finalUrl: finalUrl || '', readyState: 4 };
    try { if (cb.onload) cb.onload(resp); if (cb.onreadystatechange) cb.onreadystatechange(resp); } catch(e){}
  };
  var GM = {
    info: { scriptHandler: 'Mula', version: '3.5.0', script: { name: META.name, id: META.id } },
    setValue: function(k, v){ try { if (bridge) bridge.setValue(META.id, k, JSON.stringify(v)); } catch(e){} },
    getValue: function(k, d){
      try {
        var raw = bridge ? bridge.getValue(META.id, k) : null;
        return (raw === null || raw === undefined) ? d : JSON.parse(raw);
      } catch(e){ return d; }
    },
    deleteValue: function(k){ try { if (bridge) bridge.deleteValue(META.id, k); } catch(e){} },
    listValues: function(){ try { return bridge ? JSON.parse(bridge.listValues(META.id)) : []; } catch(e){ return []; } },
    addStyle: function(css){
      var st = document.createElement('style');
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);
      return st;
    },
    xmlHttpRequest: function(details){
      if (!bridge || !bridge.xhr) throw new Error('GM_xmlhttpRequest unavailable');
      var reqId = META.id + ':' + Math.random().toString(36).slice(2);
      xhrCbs[reqId] = details;
      bridge.xhr(reqId, META.id, details.url, (details.method || 'GET'),
                 JSON.stringify(details.headers || {}),
                 details.data == null ? '' : String(details.data));
    },
    notification: function(text, title){ try { if (bridge) bridge.toast(String(title || '') + ' ' + String(text || '')); } catch(e){} },
    openInTab: function(url){ try { if (bridge) bridge.openTab(url); } catch(e){} },
    registerMenuCommand: function(){ return 0; },
    log: function(){ try { console.log.apply(console, arguments); } catch(e){} },
    getResourceText: function(){ return null; }
  };
  var unsafeWindow = window;
  // 兼容直接调用全局函数的脚本
  window.unsafeWindow = window;
  window.GM_info = GM.info;
  window.GM_setValue = GM.setValue;
  window.GM_getValue = GM.getValue;
  window.GM_deleteValue = GM.deleteValue;
  window.GM_listValues = GM.listValues;
  window.GM_addStyle = GM.addStyle;
  window.GM_xmlhttpRequest = GM.xmlHttpRequest;
  window.GM_notification = GM.notification;
  window.GM_openInTab = GM.openInTab;
  window.GM_registerMenuCommand = GM.registerMenuCommand;
  window.GM_log = GM.log;
  window.GM_getResourceText = GM.getResourceText;
  window.GM_getResourceURL = function(){ return ''; };
  window.GM_download = function(o){ try { if (bridge) bridge.openTab(o && o.url ? o.url : String(o)); } catch(e){} };
  window.GM_setClipboard = function(t){ try { navigator.clipboard.writeText(String(t)); } catch(e){} };
  window.GM_unregisterMenuCommand = function(){};
  window.GM_addValueChangeListener = function(){ return 0; };
  window.GM_removeValueChangeListener = function(){};
  window.GM_getTab = function(cb){ try { cb && cb({}); } catch(e){} };
  window.GM_saveTab = function(){};
  window.GM_getTabs = function(cb){ try { cb && cb([]); } catch(e){} };
  window.GM_cookie = { list: function(){ return {}; }, set: function(){}, delete: function(){} };

  try {
    (function(GM, unsafeWindow, window){
$requires
$body
    })(GM, unsafeWindow, window);
  } catch (err) {
    try { console.error('[Mula userscript] ' + META.name + ': ' + (err && err.message)); } catch(e){}
  }
})();
""".trimIndent()
    }
}
