package dev.dsh.miuixbrowser.geckoplugin

import android.content.Context
import android.view.View
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** Callback ABI intentionally uses only strings so the host does not need GeckoView classes. */
interface GeckoPluginListener {
    fun onEvent(event: String, value: String?)
}

/** One instance represents one Mafari tab; all sessions share one process-wide GeckoRuntime. */
class GeckoPluginHandle internal constructor(
    private val session: GeckoSession,
    private val geckoView: GeckoView,
    private val listener: GeckoPluginListener,
) {
    @Volatile private var back = false
    @Volatile private var forward = false
    @Volatile private var title = ""

    fun getView(): View = geckoView
    fun getPageTitle(): String = title
    fun load(uri: String) = session.loadUri(uri)
    fun reload() = session.reload()
    fun stop() = session.stop()
    fun canGoBack(): Boolean = back
    fun canGoForward(): Boolean = forward
    fun goBack() = session.goBack()
    fun goForward() = session.goForward()
    fun destroy() {
        runCatching { geckoView.releaseSession() }
        runCatching { session.close() }
        runCatching { geckoView.removeAllViews() }
    }

    internal fun updateBack(value: Boolean) { back = value; listener.onEvent("onCanGoBack", value.toString()) }
    internal fun updateForward(value: Boolean) { forward = value; listener.onEvent("onCanGoForward", value.toString()) }
    internal fun updateTitle(value: String?) { if (!value.isNullOrBlank()) { title = value; listener.onEvent("onTitle", value) } }
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
        val handle = GeckoPluginHandle(session, view, listener)
        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String) = handle.updateTitle(title)
        })
        session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { listener.onEvent("onPageStart", url) }
            override fun onPageStop(session: GeckoSession, success: Boolean) { listener.onEvent("onPageStop", success.toString()) }
            override fun onProgressChange(session: GeckoSession, progress: Int) { listener.onEvent("onProgress", progress.toString()) }
        })
        session.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) { listener.onEvent("onUrl", url) }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) = handle.updateBack(canGoBack)
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) = handle.updateForward(canGoForward)
        })
        session.open(activeRuntime)
        view.setSession(session)
        if (initialUrl.isNotBlank() && initialUrl != "about:blank") session.loadUri(initialUrl)
        return handle
    }
}
