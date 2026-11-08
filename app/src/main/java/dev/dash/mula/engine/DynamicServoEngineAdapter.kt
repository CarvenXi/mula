package dev.dash.mula.engine

import android.content.Context
import android.view.View
import kotlinx.coroutines.CoroutineScope
import java.lang.reflect.Proxy

/** Servo is deliberately accessed by reflection so its classes never enter the main APK. */
class DynamicServoEngineAdapter private constructor(
    override val view: View,
    private val navigator: Any,
    private val loader: ClassLoader,
) : BrowserEngineAdapter {
    override val capabilities = EngineCapabilities(false, false, false, false, false, false, false, false, false)

    override fun load(url: String) { invoke(view, "loadUri", url) }
    override fun reload() { invoke(navigator, "reload") }
    override fun stop() { invoke(view, "stop") }
    override fun goBack(): Boolean = if (canGoBack()) { invoke(navigator, "back"); true } else false
    override fun goForward(): Boolean = if (canGoForward()) { invoke(navigator, "forward"); true } else false
    override fun canGoBack(): Boolean = navigationFlag("canGoBackState")
    override fun canGoForward(): Boolean = navigationFlag("canGoForwardState")
    override fun destroy() { runCatching { stop() } }

    private fun navigationFlag(property: String): Boolean = runCatching {
        val state = navigator.javaClass.methods.first { it.name == "get${property.replaceFirstChar(Char::uppercase)}" }.invoke(navigator)
        state.javaClass.methods.first { it.name == "getValue" }.invoke(state) as? Boolean ?: false
    }.getOrDefault(false)

    companion object {
        fun create(
            context: Context,
            classLoader: ClassLoader,
            initialUrl: String,
            scope: CoroutineScope,
            onClientEvent: (String, Array<out Any?>?) -> Unit,
        ): DynamicServoEngineAdapter {
            val navigatorClass = classLoader.loadClass("org.servo.servoview.ServoNavigator")
            val navigator = navigatorClass.getDeclaredConstructor().newInstance()
            val clientClass = classLoader.loadClass("org.servo.servoview.Servo\$Client")
            val client = Proxy.newProxyInstance(classLoader, arrayOf(clientClass)) { _, method, args ->
                onClientEvent(method.name, args)
                null
            }
            val viewClass = classLoader.loadClass("org.servo.servoview.ServoView")
            val ctor = viewClass.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 8 && p[0].isAssignableFrom(Context::class.java) && p[1].isAssignableFrom(clientClass)
            } ?: viewClass.constructors.firstOrNull { it.parameterTypes.size == 8 }
                ?: error("ServoView 构造函数与插件 API 不兼容")
            val p = ctor.parameterTypes
            val args = arrayOfNulls<Any>(8)
            args[0] = context; args[1] = client
            for (i in 2 until 8) {
                val t = p[i]
                args[i] = when {
                    t == Boolean::class.javaPrimitiveType || t == Boolean::class.javaObjectType -> true
                    t == String::class.java -> initialUrl
                    t.isAssignableFrom(navigatorClass) -> navigator
                    t.isAssignableFrom(scope.javaClass) || t.name.startsWith("kotlinx.coroutines.CoroutineScope") -> scope
                    else -> null
                }
            }
            ServoTouchBridge.dumpApi(p, viewClass, clientClass, navigatorClass)
            val view = ctor.newInstance(*args) as? View ?: error("ServoView 未返回 Android View")
            ServoTouchBridge.install(view)
            return DynamicServoEngineAdapter(view, navigator, classLoader)
        }

        private fun invoke(target: Any, name: String, vararg args: Any?) {
            val method = target.javaClass.methods.first { it.name == name && it.parameterTypes.size == args.size }
            method.invoke(target, *args)
        }
    }
}
