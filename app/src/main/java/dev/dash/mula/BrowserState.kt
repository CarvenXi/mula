package dev.dash.mula

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URLEncoder
import dev.dash.mula.engine.BrowserEngineAdapter
import dev.dash.mula.engine.BrowserEngineFactory
import dev.dash.mula.engine.DynamicServoEngineAdapter
import dev.dash.mula.engine.ServoPluginManager
import dev.dash.mula.engine.GeckoPluginManager
import dev.dash.mula.engine.DynamicGeckoEngineAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** 判断输入是网址还是搜索词。 */
fun looksLikeUrl(input: String): Boolean {
    val s = input.trim()
    if (s.isEmpty() || s.contains(" ")) return false
    if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file://") || s.startsWith("about:")) return true
    if (s == "localhost" || s.startsWith("localhost:")) return true
    return Regex("^[A-Za-z0-9\\-._~%]+(\\.[A-Za-z0-9\\-._~%]+)+(/.*)?$").matches(s)
}

/** 把地址栏输入变成可加载的 URL，不像网址就走搜索。 */
fun toUrl(input: String, searchTemplate: String = "https://www.baidu.com/s?wd=%s"): String {
    val s = input.trim()
    return if (looksLikeUrl(s)) {
        if (s.startsWith("http") || s.startsWith("file") || s.startsWith("about")) s else "https://$s"
    } else {
        searchTemplate.replace("%s", URLEncoder.encode(s, "UTF-8"))
    }
}

fun hostOf(url: String): String {
    val trimmed = url.trim()
    if (trimmed.startsWith("data:", ignoreCase = true)) return "内嵌图片"
    val host = runCatching { Uri.parse(trimmed).host }.getOrNull()
    return if (!host.isNullOrBlank()) host else trimmed.take(48)
}

enum class BrowserEngineType { WEBVIEW, SERVO, GECKO }

/** 一个标签页：保留 WebView，并按需创建 ServoView。 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserTab(private val appContext: Context, initialEngine: BrowserEngineType = BrowserEngineType.WEBVIEW) {

    /** 每个标签的唯一 id，Compose 用它做 key。 */
    val id: Int = nextId()

    var title by mutableStateOf("新标签页")
    var url by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set

    var engine by mutableStateOf(BrowserEngineType.WEBVIEW)
        private set
    private var servoAdapter: BrowserEngineAdapter? = null
    private var servoScope: CoroutineScope? = null
    var servoView: View? = null
        private set
    private var geckoAdapter: BrowserEngineAdapter? = null
    var geckoView: View? = null
        private set
    private val servoPluginManager = ServoPluginManager(appContext)
    private val geckoPluginManager = GeckoPluginManager(appContext)

    private lateinit var engineAdapter: BrowserEngineAdapter

    val currentView: View
        get() = if (::engineAdapter.isInitialized) engineAdapter.view else webView

    val activeEngineAdapter: BrowserEngineAdapter get() = engineAdapter
    val engineCapabilities get() = engineAdapter.capabilities
    val canPullToRefresh get() = engineAdapter.capabilities.pullToRefresh && engineAdapter.scrollY <= 0
    val scrollY get() = engineAdapter.scrollY
    val pageTitle get() = engineAdapter.pageTitle
    fun evaluateJavaScript(script: String): Boolean = engineAdapter.evaluateJavaScript(script)
    var errorText by mutableStateOf<String?>(null)
    /** 是否是从主页打开的第一个页面（用于「返回到主页」） */
    var fromHome by mutableStateOf(false)
        private set

    var closed = false
    private var destroyed = false

    /** 当前标签是否「请求桌面网站」 */
    var desktopMode by mutableStateOf(false)
        private set

    private val defaultUserAgent: String by lazy { webView.settings.userAgentString }

    /** 设置里的自定义 UA（空串 = 跟随系统） */
    var customUserAgent: String = ""

    /** 长按链接时由界面挂上回调。 */
    var onLongPressLink: ((String) -> Unit)? = null

    /** 长按图片 */
    var onLongPressImage: ((String) -> Unit)? = null

    /** 长按普通元素：由页面内 JS 判定为广告浮层后回调 */
    var onElementLongPress: ((ElementHit) -> Unit)? = null

    /** 用户脚本扩展（页面加载完后注入） */
    var userScripts: List<UserScript> = emptyList()

    /** 页面增强总开关：关闭后不再注入任何脚本 / 规则 / 长按逻辑，用于排查 */
    var pageEnhancements: Boolean = true

    /** 搜索模板，由设置注入 */
    var searchTemplate: String = "https://www.baidu.com/s?wd=%s"

    /** 下拉进度 0..1，用于顶部动画 */
    var pullProgress by mutableFloatStateOf(0f)
        private set

    /** 下载请求回调（交给界面弹确认框） */
    var onDownload: ((url: String, userAgent: String?, disposition: String?, mime: String?) -> Unit)? = null

    /** 下拉刷新回调 */
    var onPullRefresh: (() -> Unit)? = null

    /** 遇到 .user.js 链接 */
    var onUserScriptLink: ((String) -> Unit)? = null

    /** 记录历史 */
    var onVisited: ((url: String, title: String) -> Unit)? = null

    val webView: WebView = WebView(appContext)
    private var webViewAdapter: BrowserEngineAdapter = BrowserEngineFactory.webView(webView)

    private val elementBridge = object {
        @android.webkit.JavascriptInterface
        fun onElement(selector: String, tag: String, imageUrl: String) {
            webView.post {
                onElementLongPress?.invoke(ElementHit(tag, imageUrl, selector))
            }
        }
    }

    /** 打开新标签页（GM_openInTab 用） */
    var onOpenNewTab: ((String) -> Unit)? = null

    private val scriptHandlers = mutableListOf<androidx.webkit.ScriptHandler>()
    private var installedScriptsSignature = ""

    init {
        engineAdapter = webViewAdapter
        runCatching {
            webView.addJavascriptInterface(
                GmBridge(appContext, { webView }, { target -> onOpenNewTab?.invoke(target) }),
                "MulaGM",
            )
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString.replace("; wv", "")
        }
        // 打开 WebView 的 WebAuthn（Passkey）支持
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_AUTHENTICATION)) {
                androidx.webkit.WebSettingsCompat.setWebAuthenticationSupport(
                    webView.settings,
                    androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE,
                )
            }
        }
        webView.setBackgroundColor(0xFFFFFFFF.toInt())
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                pageUrl?.let { url = it }
                loading = true
                errorText = null
                syncNav()
                installDocumentStartScripts()
            }

            override fun onPageFinished(view: WebView, pageUrl: String?) {
                pageUrl?.let { url = it }
                loading = false
                progress = 1f
                syncNav()
                onVisited?.invoke(url, title)

                injectDocumentEndScripts(view)
                injectUserAdRules(view, pageUrl)
                installElementInspector(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, pageUrl: String?, isReload: Boolean) {
                pageUrl?.let { url = it }
                syncNav()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) errorText = error.description?.toString()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (target.endsWith(".user.js") || target.contains("greasyfork") && target.contains(".user.js")) {
                    onUserScriptLink?.invoke(target)
                    return true
                }
                val scheme = request.url.scheme ?: return false
                return when (scheme) {
                    "http", "https", "file", "about", "data", "javascript" -> false
                    else -> {
                        // 支付宝 / 微信 / 淘宝等外部协议交给系统处理
                        runCatching {
                            appContext.startActivity(
                                Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        true
                    }
                }
            }
        }

        webView.addJavascriptInterface(elementBridge, "MulaElement")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress / 100f
                loading = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView, pageTitle: String?) {
                if (!pageTitle.isNullOrBlank()) title = pageTitle
            }
        }

        webView.setDownloadListener { link, userAgent, contentDisposition, mimeType, _ ->
            onDownload?.invoke(link, userAgent, contentDisposition, mimeType)
        }

        // 约 2.5cm 的拉动距离，避免误触
        val pullThresholdPx = webView.context.resources.displayMetrics.xdpi / 2.54f * 4f
        var touchDownY = 0f
        var pulled = 0f
        var lastTouchX = 0f
        var lastTouchY = 0f
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchDownY = event.y
                    lastTouchX = event.x
                    lastTouchY = event.y
                    pulled = 0f
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (url.isNotEmpty() && webView.scrollY <= 0 && event.y > touchDownY) {
                        pulled = event.y - touchDownY
                        pullProgress = (pulled / pullThresholdPx).coerceIn(0f, 1f)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (url.isNotEmpty() && pulled > pullThresholdPx && webView.scrollY <= 0) {
                        onPullRefresh?.invoke()
                    }
                    pulled = 0f
                    pullProgress = 0f
                }
            }
            false
        }

        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val extra = result.extra
            when {
                result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && !extra.isNullOrEmpty() -> {
                    onLongPressLink?.invoke(extra)
                    true
                }
                (result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE ||
                    result.type == WebView.HitTestResult.IMAGE_TYPE) && !extra.isNullOrEmpty() -> {
                    // 图片链接的 extra 是图片地址（可能是 data: base64），走图片菜单
                    onLongPressImage?.invoke(extra)
                    true
                }
                // 文字/空白区域交还给系统长按选词与页面内 JS 判定
                else -> false
            }
        }
        if (initialEngine != BrowserEngineType.WEBVIEW) switchEngine(initialEngine)
    }

    data class ElementHit(val tag: String, val imageUrl: String, val selector: String)

    /** 只在广告浮层（fixed/absolute 且像广告或面积大）上响应长按，文字区域交给系统选词 */
    private fun installElementInspector(view: WebView) {
        if (!pageEnhancements) return
        val js = """(function(){
  if (window.__mulaInspector) return;
  window.__mulaInspector = true;
  function selectorOf(el){
    try {
      if (el.id) return '#' + CSS.escape(el.id);
      var parts = []; var cur = el;
      while (cur && cur.nodeType === 1 && parts.length < 4) {
        var p = cur.tagName.toLowerCase();
        var cls = (typeof cur.className === 'string' ? cur.className : '').trim().split(/\s+/).filter(Boolean).slice(0, 2);
        for (var k = 0; k < cls.length; k++) { p += '.' + CSS.escape(cls[k]); }
        var parent = cur.parentElement;
        if (parent) { p += ':nth-child(' + (Array.prototype.indexOf.call(parent.children, cur) + 1) + ')'; }
        parts.unshift(p); cur = parent;
      }
      return parts.join(' > ');
    } catch (e) { return ''; }
  }
  function isAdLike(el){
    var st = getComputedStyle(el);
    var pos = st.position;
    if (pos !== 'fixed' && pos !== 'absolute') return false;
    var cls = (typeof el.className === 'string') ? el.className : '';
    var key = (cls + ' ' + (el.id || '')).toLowerCase();
    if (/(^|[\s_-])(ad|ads|advert|banner|popup|float|modal|overlay|mask|sponsor)([\s_-]|$)/.test(key)) return true;
    var r = el.getBoundingClientRect();
    return r.width > innerWidth * 0.5 && r.height > innerHeight * 0.25;
  }
  function fire(el){
    var tag = el.tagName;
    if (tag === 'IMG' || tag === 'INPUT' || tag === 'TEXTAREA') return;
    if (!isAdLike(el)) return;
    var img = el.querySelector ? el.querySelector('img') : null;
    var src = img ? (img.currentSrc || img.src || '') : '';
    try { MulaElement.onElement(selectorOf(el), tag, src); } catch (e) {}
    try { var sel = getSelection(); if (sel) sel.removeAllRanges(); } catch (e) {}
  }
  var timer = null, sx = 0, sy = 0, moved = false;
  document.addEventListener('touchstart', function(e){
    if (e.touches.length !== 1) return;
    var el = e.target;
    if (!el || el.nodeType !== 1) return;
    sx = e.touches[0].clientX; sy = e.touches[0].clientY; moved = false;
    clearTimeout(timer);
    timer = setTimeout(function(){ if (!moved) fire(el); }, 520);
  }, { capture: true, passive: true });
  document.addEventListener('touchmove', function(e){
    if (!e.touches.length) return;
    if (Math.abs(e.touches[0].clientX - sx) > 12 || Math.abs(e.touches[0].clientY - sy) > 12) {
      moved = true; clearTimeout(timer);
    }
  }, { capture: true, passive: true });
  document.addEventListener('touchend', function(){ clearTimeout(timer); }, { capture: true, passive: true });
  document.addEventListener('touchcancel', function(){ clearTimeout(timer); }, { capture: true, passive: true });
})();"""
        view.evaluateJavascript(js, null)
    }

    /** 把用户「标记为广告」记住的选择器注入当前页 */
    private fun injectUserAdRules(view: WebView, pageUrl: String?) {
        if (!pageEnhancements) return
        val host = pageUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: return
        val selectors = UserAdRules.selectors(appContext, host)
        if (selectors.isEmpty()) return
        val css = selectors.joinToString(",") + "{display:none!important}"
        val js = "(function(){try{var s=document.createElement('style');s.textContent=" +
            org.json.JSONObject.quote(css) +
            ";(document.head||document.documentElement).appendChild(s);}catch(e){}})();"
        view.evaluateJavascript(js, null)
    }

    /** 用 AndroidX WebKit 的 addDocumentStartJavaScript 注入 @run-at document-start 脚本 */
    private fun installDocumentStartScripts() {
        if (!pageEnhancements) {
            scriptHandlers.forEach { handler -> runCatching { handler.remove() } }
            scriptHandlers.clear()
            installedScriptsSignature = ""
            return
        }
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return
        }
        val startScripts = userScripts.filter { it.enabled && it.runAt == "document-start" }
        val signature = startScripts.joinToString("|") { "${it.id}:${it.code.hashCode()}" }
        if (signature == installedScriptsSignature) return
        scriptHandlers.forEach { handler -> runCatching { handler.remove() } }
        scriptHandlers.clear()
        startScripts.forEach { script ->
            runCatching {
                val source = UserscriptEngine.buildSource(script)
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, source, setOf("*"))
                    ?.let { scriptHandlers.add(it) }
            }
        }
        installedScriptsSignature = signature
        Diag.log("userscript: document-start handlers=${scriptHandlers.size}")
    }

    /** 页面加载完成后注入 document-end / document-idle 脚本 */
    private fun injectDocumentEndScripts(view: WebView) {
        if (!pageEnhancements) return
        val pageUrl = url
        userScripts.filter { script ->
            if (!script.enabled) return@filter false
            if (script.runAt == "document-start") return@filter false
            val meta = UserscriptEngine.parse(script.code)
            if (meta.matches.isNotEmpty() || meta.includes.isNotEmpty()) {
                UserscriptEngine.matchesUrl(meta, pageUrl)
            } else {
                // 没有元数据匹配规则时：脚本自带 match 为空则全站执行
                if (script.match.isBlank()) true else script.matches(pageUrl)
            }
        }.forEach { script ->
            runCatching {
                view.evaluateJavascript(UserscriptEngine.buildSource(script), null)
            }
        }
        val matched = userScripts.filter { script ->
            script.enabled && script.runAt != "document-start" &&
                UserscriptEngine.matchesUrl(UserscriptEngine.parse(script.code), pageUrl)
        }
        Diag.log("userscript: page=$pageUrl matched=${matched.size}/${userScripts.size}")
    }

    fun refreshNavigationState() = syncNav()

    private fun syncNav() {
        if (engine != BrowserEngineType.WEBVIEW) {
            canGoBack = engineAdapter.canGoBack() || fromHome ||
                (url.isEmpty() && savedUrl.isNotBlank())
            canGoForward = engineAdapter.canGoForward()
        } else {
            canGoBack = runCatching { engineAdapter.canGoBack() }.getOrDefault(false) || fromHome ||
                (url.isEmpty() && savedUrl.isNotBlank())
            canGoForward = runCatching { engineAdapter.canGoForward() }.getOrDefault(false)
        }
    }

    private fun createServoView(initialUri: String) {
        if (servoView != null && servoAdapter != null) {
            engineAdapter = servoAdapter!!
            return
        }
        check(servoPluginManager.isInstalled()) { "Servo 内核尚未安装，请先在设置中下载引擎包" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        servoScope = scope
        val adapter = DynamicServoEngineAdapter.create(
            appContext,
            servoPluginManager.createClassLoader(),
            initialUri,
            scope,
        ) { event, args ->
            when (event) {
                "onLoadStarted" -> {
                    loading = true; progress = 0f; errorText = null
                }
                "onLoadEnded" -> {
                    loading = false; progress = 1f; syncNav(); onVisited?.invoke(url, title)
                }
                "onTitleChanged" -> args?.getOrNull(0)?.toString()?.takeIf { it.isNotBlank() }?.let { title = it }
                "onUrlChanged" -> args?.getOrNull(0)?.toString()?.let { url = it; syncNav() }
                "onImeShow" -> servoView?.post {
                    val view = servoView ?: return@post
                    view.isFocusableInTouchMode = true; view.requestFocus()
                    val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                }
                "onImeHide" -> servoView?.post {
                    val view = servoView ?: return@post
                    val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
        }
        servoAdapter = adapter
        servoView = adapter.view
        engineAdapter = adapter
    }

    private fun createGeckoView(initialUri: String) {
        if (geckoView != null && geckoAdapter != null) {
            engineAdapter = geckoAdapter!!
            return
        }
        check(geckoPluginManager.isInstalled()) { "GeckoView 内核尚未安装，请先在设置中下载引擎包" }
        val adapter = DynamicGeckoEngineAdapter.create(
            appContext,
            geckoPluginManager,
            initialUri,
        ) { event, value ->
            when (event) {
                "onPageStart" -> { loading = true; progress = 0f; errorText = null }
                "onPageStop" -> { loading = false; progress = 1f; syncNav(); onVisited?.invoke(url, title) }
                "onProgress" -> progress = (value?.toFloatOrNull() ?: 0f).coerceIn(0f, 100f) / 100f
                "onTitle" -> value?.takeIf { it.isNotBlank() }?.let { title = it }
                "onUrl" -> value?.let { url = it; syncNav() }
                "onCanGoBack" -> { canGoBack = value == "true" }
                "onCanGoForward" -> { canGoForward = value == "true" }
            }
        }
        geckoAdapter = adapter
        geckoView = adapter.view
        engineAdapter = adapter
    }

    fun switchEngine(target: BrowserEngineType) {
        if (engine == target) return
        val currentUrl = url
        engine = target
        when (target) {
            BrowserEngineType.SERVO -> {
                webView.onPause()
                createServoView(currentUrl.ifBlank { "about:blank" })
            }
            BrowserEngineType.GECKO -> {
                webView.onPause()
                createGeckoView(currentUrl.ifBlank { "about:blank" })
            }
            BrowserEngineType.WEBVIEW -> {
                webView.onResume()
                engineAdapter = webViewAdapter
                applyUserAgent()
                if (currentUrl.isNotBlank()) engineAdapter.load(currentUrl)
            }
        }
        syncNav()
    }

    fun load(input: String) {
        val target = toUrl(input, searchTemplate)
        if (url.isEmpty()) fromHome = true
        url = target
        when (engine) {
            BrowserEngineType.SERVO -> { createServoView("about:blank"); engineAdapter.load(target) }
            BrowserEngineType.GECKO -> { createGeckoView("about:blank"); engineAdapter.load(target) }
            BrowserEngineType.WEBVIEW -> engineAdapter.load(target)
        }
        syncNav()
    }

    fun loadUrl(raw: String) {
        url = raw
        when (engine) {
            BrowserEngineType.SERVO -> { createServoView("about:blank"); engineAdapter.load(raw) }
            BrowserEngineType.GECKO -> { createGeckoView("about:blank"); engineAdapter.load(raw) }
            BrowserEngineType.WEBVIEW -> engineAdapter.load(raw)
        }
    }

    fun reload() {
        if (loading) {
            engineAdapter.stop()
            loading = false
        } else {
            engineAdapter.reload()
        }
    }

    /** 应用 UA：桌面模式优先，其次设置里的自定义 UA，最后系统默认 */
    fun applyUserAgent() {
        val ua = when {
            desktopMode -> DESKTOP_UA
            customUserAgent.isBlank() || customUserAgent == DESKTOP_UA -> defaultUserAgent
            else -> customUserAgent
        }
        if (engine == BrowserEngineType.WEBVIEW) {
            runCatching { webView.settings.userAgentString = ua }
        }
    }

    fun toggleDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        if (engine == BrowserEngineType.WEBVIEW) {
            runCatching {
                webView.settings.useWideViewPort = true
                webView.settings.loadWithOverviewMode = true
            }
            applyUserAgent()
        }
        val target = url
        if (target.isNotBlank() && !target.startsWith("about:")) loadUrl(target) else reload()
    }

    /** 页内查找 */
    fun findAll(text: String) {
        if (engine != BrowserEngineType.WEBVIEW) return
        if (text.isBlank()) webView.clearMatches() else webView.findAllAsync(text)
    }

    fun findNext(forward: Boolean) {
        if (engine == BrowserEngineType.WEBVIEW) runCatching { webView.findNext(forward) }
    }

    fun clearFind() {
        if (engine == BrowserEngineType.WEBVIEW) runCatching { webView.clearMatches() }
    }

    /** 回主页 */
    private var savedUrl: String = ""

    fun goHome() {
        savedUrl = url
        fromHome = false
        url = ""
        errorText = null
        loading = false
        syncNav()
    }

    fun goBack() {
        // 从主页返回时恢复此前页面；否则交由当前内核处理历史栈。
        if (url.isEmpty() && savedUrl.isNotBlank()) {
            url = savedUrl
            savedUrl = ""
            syncNav()
            return
        }
        if (!engineAdapter.goBack() && fromHome) {
            fromHome = false
            url = ""
            errorText = null
        }
        syncNav()
    }

    fun goForward() {
        engineAdapter.goForward()
        syncNav()
    }

    fun destroyNow() {
        if (destroyed) return
        destroyed = true
        runCatching { (servoView?.parent as? ViewGroup)?.removeView(servoView) }
        runCatching { (geckoView?.parent as? ViewGroup)?.removeView(geckoView) }
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        // 每个内核适配器负责释放自己的底层资源，标签不再重复实现内核销毁细节。
        runCatching { servoAdapter?.destroy() }
        runCatching { geckoAdapter?.destroy() }
        runCatching { webViewAdapter.destroy() }
        servoScope?.cancel()
        servoAdapter = null
        servoView = null
        geckoAdapter = null
        geckoView = null
    }

    /** 关闭标签：等界面把 WebView 从视图树摘下来后再销毁。 */
    fun close() {
        closed = true
        webView.post {
            if (webView.parent == null && (servoView?.parent == null) && (geckoView?.parent == null)) destroyNow()
        }
        servoView?.post {
            if (servoView?.parent == null && geckoView?.parent == null && webView.parent == null) destroyNow()
        }
    }

    private companion object {
        private var counter = 0

        fun nextId(): Int {
            counter += 1
            return counter
        }
    }
}

/** 多标签状态。 */
class BrowserState(
    private val context: Context,
    initialEngine: BrowserEngineType = BrowserEngineType.WEBVIEW,
) {
    var engine by mutableStateOf(initialEngine)
        private set


    val tabs = mutableStateListOf<BrowserTab>()

    /** 全局 UA，新标签也用它 */
    var userAgent: String = ""

    var activeIndex by mutableIntStateOf(0)
        private set

    val activeTab: BrowserTab
        get() = tabs[activeIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))]

    init {
        newTab()
    }

    fun newTab(target: String = "") {
        // 当前 Servo AAR 的多实例/多标签切换尚未验证稳定性，Servo 模式暂用单标签回退：
        // 点击“新建标签页”回到当前标签主页；带目标地址的新标签请求则在当前标签加载。
        if (engine == BrowserEngineType.SERVO && tabs.isNotEmpty()) {
            if (target.isBlank()) activeTab.goHome() else activeTab.load(target)
            return
        }
        val tab = BrowserTab(context, engine)
        tab.customUserAgent = userAgent
        tab.applyUserAgent()
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        if (target.isNotEmpty()) tab.load(target)
    }

    fun switchEngine(value: BrowserEngineType) {
        if (engine == value) return
        engine = value
        tabs.forEach { it.switchEngine(value) }
    }

    fun applyUserAgent(ua: String) {
        userAgent = ua
        tabs.forEach {
            it.customUserAgent = ua
            it.applyUserAgent()
        }
    }

    fun select(index: Int) {
        if (index in tabs.indices) activeIndex = index
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        tab.close()
        if (tabs.isEmpty()) {
            newTab()
        } else {
            activeIndex = index.coerceAtMost(tabs.lastIndex)
        }
    }

    fun newTabInBackground(target: String) {
        // Servo 暂不创建后台 ServoView，退化为在当前标签打开目标地址。
        if (engine == BrowserEngineType.SERVO && tabs.isNotEmpty()) {
            activeTab.load(target)
            return
        }
        val tab = BrowserTab(context, engine)
        tabs.add(tab)
        tab.load(target)
    }

    fun closeAll() {
        tabs.forEach { it.close() }
        tabs.clear()
        newTab()
    }
}
