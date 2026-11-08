package dev.dash.mula.geckoplugin

import android.content.Context
import android.view.View
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/** Callback ABI intentionally uses only strings so the host does not need GeckoView classes. */
interface GeckoPluginListener {
    fun onEvent(event: String, value: String?)
}

/** One instance represents one Mula tab; all sessions share one process-wide GeckoRuntime. */
class GeckoPluginHandle internal constructor(
    private val runtime: GeckoRuntime,
    private val session: GeckoSession,
    private val geckoView: GeckoView,
    private val listener: GeckoPluginListener,
) {
    @Volatile private var back = false
    @Volatile private var forward = false
    @Volatile private var title = ""
    @Volatile private var lastUrl = ""
    @Volatile private var destroyed = false
    private var recoveries = 0

    fun getView(): View = geckoView
    fun getPageTitle(): String = title
    fun load(uri: String) { lastUrl = uri; session.loadUri(uri) }
    fun reload() = session.reload()
    fun stop() = session.stop()
    fun canGoBack(): Boolean = back
    fun canGoForward(): Boolean = forward
    fun goBack() = session.goBack()
    fun goForward() = session.goForward()
    fun destroy() {
        destroyed = true
        runCatching { geckoView.releaseSession() }
        runCatching { session.close() }
        runCatching { geckoView.removeAllViews() }
    }

    internal fun updateBack(value: Boolean) { back = value; listener.onEvent("onCanGoBack", value.toString()) }
    internal fun updateForward(value: Boolean) { forward = value; listener.onEvent("onCanGoForward", value.toString()) }
    internal fun updateTitle(value: String?) { if (!value.isNullOrBlank()) { title = value; listener.onEvent("onTitle", value) } }
    internal fun updateUrl(value: String?) { if (!value.isNullOrBlank() && value != "about:blank") lastUrl = value; listener.onEvent("onUrl", value) }

    /** 内容进程崩溃/被杀：重新打开会话并重载最后的地址；连续失败则上报 onFatal，避免死循环。 */
    internal fun recover() {
        if (destroyed) return
        listener.onEvent("onCrash", lastUrl)
        if (++recoveries > MAX_RECOVERIES) { listener.onEvent("onFatal", "content process keeps crashing"); return }
        runCatching { geckoView.releaseSession() }
        runCatching { session.close() }
        runCatching {
            session.open(runtime)
            geckoView.setSession(session)
            if (lastUrl.isNotBlank()) session.loadUri(lastUrl)
        }.onFailure { listener.onEvent("onFatal", it.toString()) }
    }

    internal fun pageLoaded() { recoveries = 0 }

    private companion object { const val MAX_RECOVERIES = 3 }
}

object GeckoPluginEntry {
    @Volatile private var runtime: GeckoRuntime? = null

    @JvmStatic
    fun create(context: Context, initialUrl: String, listener: GeckoPluginListener): GeckoPluginHandle {
        check(Thread.currentThread().name == "main") { "GeckoView session must be created on the main thread" }
        val activeRuntime = runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(context.applicationContext ?: context).also { runtime = it }
        }
        val session = GeckoSession()
        val view = GeckoView(context)
        val handle = GeckoPluginHandle(activeRuntime, session, view, listener)
        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) = handle.updateTitle(title)
            override fun onCrash(session: GeckoSession) = handle.recover()
            override fun onKill(session: GeckoSession) = handle.recover()
            // 区分「页面没加载」和「加载了但没画出来（白屏）」
            override fun onFirstContentfulPaint(session: GeckoSession) { listener.onEvent("onFirstPaint", "") }
        })
        session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { listener.onEvent("onPageStart", url) }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (success) handle.pageLoaded()
                listener.onEvent("onPageStop", success.toString())
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) { listener.onEvent("onProgress", progress.toString()) }
        })
        session.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) = handle.updateUrl(url)
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) = handle.updateBack(canGoBack)
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) = handle.updateForward(canGoForward)
            // target=_blank / window.open：宿主暂不支持插件内新建标签，直接在当前标签打开，避免链接点了没反应
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                listener.onEvent("onLoadError", "$uri code=${error.code} category=${error.category}")
                return null // 交给 Gecko 默认错误页
            }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                handle.load(uri)
                return null
            }
        })
        session.open(activeRuntime)
        view.setSession(session)
        if (initialUrl.isNotBlank() && initialUrl != "about:blank") handle.load(initialUrl)
        return handle
    }
}
