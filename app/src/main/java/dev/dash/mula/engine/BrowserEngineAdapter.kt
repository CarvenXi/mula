package dev.dash.mula.engine

import android.view.View
import android.webkit.WebView

/**
 * Mafari 内核边界。UI 和标签管理只依赖该契约，不依赖具体内核类型。
 * 内核专属能力通过 EngineCapabilities 明确声明，不能假装所有内核都支持相同功能。
 */
interface BrowserEngineAdapter {
    val view: View
    val capabilities: EngineCapabilities
    val scrollY: Int get() = 0
    val pageTitle: String get() = ""
    fun evaluateJavaScript(script: String): Boolean = false
    fun load(url: String)
    fun reload()
    fun stop()
    fun goBack(): Boolean
    fun goForward(): Boolean
    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun destroy()
}

data class EngineCapabilities(
    val javaScriptEvaluation: Boolean,
    val documentStartScripts: Boolean,
    val userscripts: Boolean,
    val downloads: Boolean,
    val desktopUserAgent: Boolean,
    val findInPage: Boolean,
    val pullToRefresh: Boolean,
    val elementInspection: Boolean,
    val multipleInstances: Boolean,
)

class WebViewEngineAdapter(private val webView: WebView) : BrowserEngineAdapter {
    override val view: View get() = webView
    override val scrollY: Int get() = webView.scrollY
    override val pageTitle: String get() = webView.title.orEmpty()
    override fun evaluateJavaScript(script: String): Boolean {
        webView.evaluateJavascript(script, null)
        return true
    }
    override val capabilities = EngineCapabilities(
        javaScriptEvaluation = true,
        documentStartScripts = true,
        userscripts = true,
        downloads = true,
        desktopUserAgent = true,
        findInPage = true,
        pullToRefresh = true,
        elementInspection = true,
        multipleInstances = true,
    )
    override fun load(url: String) = webView.loadUrl(url)
    override fun reload() = webView.reload()
    override fun stop() = webView.stopLoading()
    override fun goBack(): Boolean = if (webView.canGoBack()) { webView.goBack(); true } else false
    override fun goForward(): Boolean = if (webView.canGoForward()) { webView.goForward(); true } else false
    override fun canGoBack() = webView.canGoBack()
    override fun canGoForward() = webView.canGoForward()
    override fun destroy() {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.removeAllViews()
        webView.destroy()
    }
}

/** 集中创建内核适配器；新增内核时通过 BrowserTab 的插件适配层接入，不让 UI 知道具体实现。 */
object BrowserEngineFactory {
    fun webView(view: WebView): BrowserEngineAdapter = WebViewEngineAdapter(view)
}
