package dev.dash.mula.engine

import android.content.Context
import android.view.View
import java.lang.reflect.Proxy

/** GeckoView stays completely outside the main APK and is accessed through a narrow reflective bridge. */
class DynamicGeckoEngineAdapter private constructor(
    override val view: View,
    private val handle: Any,
    private val loader: ClassLoader,
) : BrowserEngineAdapter {
    override val capabilities = EngineCapabilities(
        javaScriptEvaluation = false,
        documentStartScripts = false,
        userscripts = false,
        downloads = false,
        desktopUserAgent = false,
        findInPage = false,
        pullToRefresh = true,
        elementInspection = false,
        multipleInstances = true,
    )

    override val scrollY: Int get() = runCatching { view.scrollY }.getOrDefault(0)
    override val pageTitle: String get() = runCatching { call(handle, "getPageTitle")?.toString().orEmpty() }.getOrDefault("")
    override fun load(url: String) { call(handle, "load", url) }
    override fun reload() { call(handle, "reload") }
    override fun stop() { call(handle, "stop") }
    override fun goBack(): Boolean = if (canGoBack()) { call(handle, "goBack"); true } else false
    override fun goForward(): Boolean = if (canGoForward()) { call(handle, "goForward"); true } else false
    override fun canGoBack(): Boolean = runCatching { call(handle, "canGoBack") as? Boolean ?: false }.getOrDefault(false)
    override fun canGoForward(): Boolean = runCatching { call(handle, "canGoForward") as? Boolean ?: false }.getOrDefault(false)
    override fun destroy() { runCatching { call(handle, "destroy") } }

    companion object {
        fun create(
            context: Context,
            manager: GeckoPluginManager,
            initialUrl: String,
            onEvent: (String, String?) -> Unit,
        ): DynamicGeckoEngineAdapter {
            val classLoader = manager.createClassLoader()
            val pluginContext = manager.createPluginContext(classLoader, context)
            val listenerType = classLoader.loadClass("dev.dash.mula.geckoplugin.GeckoPluginListener")
            val listener = Proxy.newProxyInstance(classLoader, arrayOf(listenerType)) { _, method, args ->
                if (method.name == "onEvent") onEvent(args?.getOrNull(0)?.toString().orEmpty(), args?.getOrNull(1)?.toString())
                null
            }
            val entry = classLoader.loadClass("dev.dash.mula.geckoplugin.GeckoPluginEntry")
            val create = entry.getMethod("create", Context::class.java, String::class.java, listenerType)
            val handle = create.invoke(null, pluginContext, initialUrl, listener)
                ?: error("GeckoView 插件未返回会话句柄")
            val view = call(handle, "getView") as? View ?: error("GeckoView 插件未返回 Android View")
            return DynamicGeckoEngineAdapter(view, handle, classLoader)
        }

        private fun call(target: Any, name: String, vararg args: Any?): Any? {
            val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
                ?: error("GeckoView 插件 API 缺少方法：$name")
            return method.invoke(target, *args)
        }
    }
}
