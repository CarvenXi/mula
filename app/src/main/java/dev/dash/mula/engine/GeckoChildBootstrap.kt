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

    fun isGeckoChildProcess(context: Context): Boolean {
        val name = Application.getProcessName() ?: return false
        return name.startsWith(context.packageName + ":")
    }

    /** 在 Application.attachBaseContext 里调用。非 Gecko 子进程或插件未就绪时返回 null。 */
    fun attach(app: Application): Payload? {
        if (!isGeckoChildProcess(app)) return null
        val apk = activeApk(app)
        val lib = nativeDir(app)
        if (!apk.isFile) {
            Log.w(TAG, "plugin apk missing in ${Application.getProcessName()}: $apk")
            return null
        }
        val loader = app.classLoader
        runCatching { prependDex(loader, apk) }
            .onFailure { Log.e(TAG, "inject dex failed", it) }
        runCatching { addNativeDir(loader, lib) }
            .onFailure { Log.e(TAG, "add native dir failed", it) }
        Log.i(TAG, "attached ${Application.getProcessName()} apk=$apk lib=$lib")
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
