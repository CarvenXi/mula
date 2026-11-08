package dev.dash.mula.engine

import android.app.Application
import android.content.Context
import android.util.Log
import dalvik.system.BaseDexClassLoader
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Field

/**
 * GeckoView 子进程（:tabN / :gpu / :socket / :utility …）启动引导。
 *
 * 子进程由系统用「主应用」的 ClassLoader 实例化 Service，而 GeckoView 的类只在插件 APK 里，
 * 父进程里创建的 DexClassLoader 在子进程中并不存在。所以每个 Gecko 子进程的 Application 创建时
 * （早于任何 Service 实例化）把插件 dex 前插进主应用 ClassLoader，并把插件 so 目录加入原生库搜索路径。
 *
 * 只在子进程执行：子进程里不跑主应用 UI，插件自带的 kotlin/androidx 副本放在最前面不会和主应用冲突。
 * 父进程仍用 GeckoPluginManager 的 DexClassLoader，不受影响。
 */
object GeckoChildBootstrap {
    private const val TAG = "MulaGeckoChild"

    class Payload(val apk: File, val libDir: File)

    /** 持有 donor，避免其 dex 元素在注入后被回收。 */
    @Volatile private var donor: ClassLoader? = null

    fun pluginRoot(context: Context) = File(context.filesDir, "engine-plugins/gecko")
    fun activeApk(context: Context) = File(pluginRoot(context), "gecko-engine-active.apk")
    fun nativeDir(context: Context) = File(pluginRoot(context), "lib/arm64-v8a")

    /** 子进程没有 Diag.init，这里直接追加写 diag-child.txt（主进程下次启动时并入 diag.txt）。 */
    fun note(context: Context, message: String, error: Throwable? = null) {
        val line = "${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}  " +
            "[${Application.getProcessName()}] $message" +
            (error?.let { "\n" + Log.getStackTraceString(it) } ?: "") + "\n"
        if (error != null) Log.e(TAG, message, error) else Log.i(TAG, message)
        runCatching {
            val dir = context.externalMediaDirs.firstOrNull() ?: context.filesDir
            dir.mkdirs()
            File(dir, "diag-child.txt").appendText(line)
        }
    }

    fun isGeckoChildProcess(context: Context): Boolean {
        val name = Application.getProcessName() ?: return false
        return name.startsWith(context.packageName + ":")
    }

    /** 在 Application.attachBaseContext 里调用。非 Gecko 子进程或插件未就绪时返回 null。 */
    fun attach(app: Application): Payload? {
        if (!isGeckoChildProcess(app)) return null
        val apk = activeApk(app)
        val lib = nativeDir(app)
        // 子进程的未捕获异常（Gecko 子进程崩溃时主进程拿不到堆栈）写进 diag-child.txt
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            note(app, "UNCAUGHT in thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        if (!apk.isFile) {
            note(app, "plugin apk missing: $apk")
            return null
        }
        val loader = app.classLoader
        runCatching { prependDex(loader, apk) }
            .onSuccess { note(app, "dex injected: apk=$apk size=${apk.length()} writable=${apk.canWrite()}") }
            .onFailure { note(app, "inject dex FAILED", it) }
        runCatching { addNativeDir(loader, lib) }
            .onSuccess { note(app, "native dir added: $lib files=${lib.list()?.size}") }
            .onFailure { note(app, "add native dir FAILED", it) }
        runCatching { loader.loadClass("org.mozilla.gecko.process.GeckoServiceChildProcess") }
            .onSuccess { note(app, "probe GeckoServiceChildProcess loadable") }
            .onFailure { note(app, "probe GeckoServiceChildProcess NOT loadable", it) }
        return Payload(apk, lib)
    }

    private fun field(start: Class<*>, name: String): Field {
        var c: Class<*>? = start
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true } }
            catch (_: NoSuchFieldException) { c = c.superclass }
        }
        throw NoSuchFieldException(name)
    }

    private fun pathList(loader: ClassLoader): Any =
        field(BaseDexClassLoader::class.java, "pathList").get(loader)!!

    @Suppress("UNCHECKED_CAST")
    private fun prependDex(host: ClassLoader, apk: File) {
        val source = PathClassLoader(apk.absolutePath, null)
        donor = source
        val hostList = pathList(host)
        val donorList = pathList(source)
        val hostField = field(hostList.javaClass, "dexElements")
        val hostElements = hostField.get(hostList) as Array<Any>
        val donorElements = field(donorList.javaClass, "dexElements").get(donorList) as Array<Any>
        val merged = java.lang.reflect.Array.newInstance(
            hostElements.javaClass.componentType!!, donorElements.size + hostElements.size,
        ) as Array<Any>
        System.arraycopy(donorElements, 0, merged, 0, donorElements.size)
        System.arraycopy(hostElements, 0, merged, donorElements.size, hostElements.size)
        hostField.set(hostList, merged)
    }

    @Suppress("UNCHECKED_CAST")
    private fun addNativeDir(loader: ClassLoader, dir: File) {
        val paths = listOf(dir.absolutePath)
        // 1) BaseDexClassLoader.addNativePath(Collection<String>)
        if (runCatching {
                BaseDexClassLoader::class.java.getDeclaredMethod("addNativePath", Collection::class.java)
                    .apply { isAccessible = true }.invoke(loader, paths)
            }.isSuccess) return
        val list = pathList(loader)
        // 2) DexPathList.addNativePath(Collection<String>)
        if (runCatching {
                list.javaClass.getDeclaredMethod("addNativePath", Collection::class.java)
                    .apply { isAccessible = true }.invoke(list, paths)
            }.isSuccess) return
        // 3) 手动重建 nativeLibraryDirectories / nativeLibraryPathElements
        val dirsField = field(list.javaClass, "nativeLibraryDirectories")
        val dirs = ArrayList(dirsField.get(list) as List<File>).also { it.add(0, dir) }
        dirsField.set(list, dirs)
        val system = runCatching {
            field(list.javaClass, "systemNativeLibraryDirectories").get(list) as List<File>
        }.getOrDefault(emptyList())
        val all = ArrayList<File>(dirs).also { it.addAll(system) }
        val make = list.javaClass.getDeclaredMethod("makeNativePathElements", List::class.java)
            .apply { isAccessible = true }
        field(list.javaClass, "nativeLibraryPathElements").set(list, make.invoke(null, all))
    }
}
