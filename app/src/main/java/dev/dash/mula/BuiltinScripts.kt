package dev.dash.mula

/** 内置脚本：不进 prefs，按需一键加入脚本列表 */
data class BuiltinScript(
    val name: String,
    val description: String,
    val code: String,
    /** 去广告规则订阅源；为空则只使用内置规则 */
    val subscriptionUrl: String = "",
    /** 直接是 .user.js 的订阅地址 */
    val userScriptUrl: String = "",
)

const val ADBLOCK_SCRIPT_NAME = "全网查询去广告规则集"

private val ADBLOCK_RULES_JS = """
// ==UserScript==
// @name         全网查询去广告规则集
// @namespace    mula.builtin
// @version      1.0.0
// @description  通用广告位与弹窗清理规则，按选择器隐藏常见广告容器
// @match        *://*/*
// @run-at       document-start
// @grant        none
// ==/UserScript==
(function () {
  'use strict';
  if (window.__mulaAdRules) return;
  window.__mulaAdRules = true;

  var SELECTORS = [
    '.adsbygoogle',
    'ins.adsbygoogle',
    '[id^="ad-"]',
    '[id^="ads"]',
    '[id^="banner-ad"]',
    '[class^="ad-"]',
    '[class*=" ad-"]',
    '[class*="advert"]',
    '[class*="ad-banner"]',
    '[class*="banner-ad"]',
    '[class*="sponsor"]',
    'iframe[src*="doubleclick.net"]',
    'iframe[src*="googlesyndication"]',
    'iframe[src*="adservice"]',
    'iframe[src*="cpro.baidu.com"]',
    'a[href*="doubleclick.net"]',
    'div[data-ad]',
    'div[data-ads]'
  ];

  function sweep(root) {
    var doc = root || document;
    for (var i = 0; i < SELECTORS.length; i++) {
      var list = [];
      try { list = doc.querySelectorAll(SELECTORS[i]); } catch (e) { continue; }
      for (var j = 0; j < list.length; j++) {
        var el = list[j];
        if (!el) continue;
        if (el.dataset && el.dataset.mulaAdHidden) continue;
        if (el.dataset) el.dataset.mulaAdHidden = '1';
        el.style.setProperty('display', 'none', 'important');
        el.style.setProperty('visibility', 'hidden', 'important');
      }
    }
  }

  function start() {
    sweep(document);
    try {
      var observer = new MutationObserver(function (records) {
        for (var i = 0; i < records.length; i++) {
          var nodes = records[i].addedNodes;
          for (var j = 0; j < nodes.length; j++) {
            var node = nodes[j];
            if (node && node.nodeType === 1) sweep(node.parentNode || node);
          }
        }
      });
      observer.observe(document.documentElement || document, { childList: true, subtree: true });
    } catch (e) {}
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    start();
  }
})();
"""

val BUILTIN_SCRIPTS = listOf(
    BuiltinScript(
        name = ADBLOCK_SCRIPT_NAME,
        description = "订阅 AdGuard 中文过滤规则（EasyList China + AdGuard Chinese），拉取后转换为本地规则；网络不可用时回落到内置规则",
        code = ADBLOCK_RULES_JS,
        subscriptionUrl = AdblockRules.BUILTIN_SUBSCRIPTION,
    ),
    BuiltinScript(
        name = "Adblock4limbo",
        description = "limbopro/Adblock4limbo 的通用去广告脚本，添加后随页面加载注入",
        code = "",
        userScriptUrl = "https://github.com/limbopro/Adblock4limbo/raw/refs/heads/main/Adguard/Adblock4limbo.user.js",
    ),
)
